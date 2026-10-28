@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.zhuanformat.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object MediaTransformer {
    /** Every Transformer call, including cancellation and progress, belongs to its main looper. */
    suspend fun convert(context: Context, source: SourceInfo, options: ConversionOptions, output: File, progress: (Int?, String) -> Unit) = withContext(Dispatchers.Main.immediate) {
        require(options.kind != MediaKind.VIDEO || VideoEncoding.isSupported(options.videoCodec)) { "设备没有可用的 ${options.videoCodec.label} 编码器，请选择 H.264 或其他设备" }
        suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            val encoder = DefaultEncoderFactory.Builder(context)
                .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(options.audioBitrate).build())
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(VideoEncoding.targetBitrate(source, options)).build())
                // Allow device parameter alignment, then verify the actual output codec before publishing.
                .setEnableFallback(true)
                .build()
            val edited = EditedMediaItem.Builder(MediaItem.fromUri(Uri.parse(source.uri)))
                .setRemoveVideo(options.kind == MediaKind.AUDIO)
            // Do not upscale smaller sources. Portrait height follows the display orientation.
            if (options.kind == MediaKind.VIDEO && options.videoHeight > 0 && source.height > options.videoHeight) {
                edited.setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(options.videoHeight))))
            }
            var poll: Runnable? = null
            val transformer = Transformer.Builder(context)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .apply { if (options.kind == MediaKind.VIDEO) setVideoMimeType(options.videoCodec.mime) }
                .setEncoderFactory(encoder)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        poll?.let(handler::removeCallbacks)
                        if (continuation.isActive) {
                            if (options.kind == MediaKind.VIDEO && exportResult.videoMimeType != options.videoCodec.mime)
                                continuation.resumeWithException(IllegalStateException("设备未能输出所选 ${options.videoCodec.label} 编码，请调整尺寸/码率或改用 H.264 后重试"))
                            else continuation.resume(Unit)
                        }
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        poll?.let(handler::removeCallbacks)
                        if (continuation.isActive) continuation.resumeWithException(exportException)
                    }
                })
                .build()
            poll = object : Runnable {
                override fun run() {
                    if (!continuation.isActive) return
                    val holder = ProgressHolder()
                    val state = transformer.getProgress(holder)
                    progress(if (state == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else null, if (options.kind == MediaKind.VIDEO) "编码 ${options.videoCodec.label} / AAC" else "编码 AAC 音频")
                    handler.postDelayed(this, 500)
                }
            }
            continuation.invokeOnCancellation {
                val cancel = Runnable {
                    poll?.let(handler::removeCallbacks)
                    transformer.cancel()
                }
                if (Looper.myLooper() == handler.looper) cancel.run() else handler.post(cancel)
            }
            try {
                val sequence = EditedMediaItemSequence.Builder(edited.build()).build()
                val composition = Composition.Builder(sequence)
                    .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                    .build()
                transformer.start(composition, output.absolutePath)
                handler.post(checkNotNull(poll))
            } catch (exception: Exception) {
                transformer.cancel()
                if (continuation.isActive) continuation.resumeWithException(exception)
            }
        }
    }
}
