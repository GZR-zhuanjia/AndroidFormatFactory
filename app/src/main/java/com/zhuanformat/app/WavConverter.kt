package com.zhuanformat.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

object WavConverter {
    suspend fun convert(context: Context, uri: Uri, output: File, progress: (Int?, String) -> Unit) = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var started = false
        try {
            progress(null, "读取音频轨道")
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("文件没有可解码的音频轨道")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IOException("缺少音频格式信息")
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
            var dataBytes = 0L
            var lastProgress = -1
            fun update(timeUs: Long) {
                val percent = if (durationUs > 0) (timeUs * 100 / durationUs).toInt().coerceIn(0, 99) else null
                if (percent == null || percent != lastProgress) {
                    lastProgress = percent ?: -1
                    progress(percent, "解码为 PCM 音频")
                }
            }
            RandomAccessFile(output, "rw").use { file ->
                file.setLength(0)
                file.write(ByteArray(44))
                fun writePcm(buffer: ByteBuffer) {
                    require(pcmEncoding == AudioFormat.ENCODING_PCM_16BIT) { "该设备未提供 16 位 PCM 解码输出，请改用 M4A" }
                    require(dataBytes + buffer.remaining() <= WavHeader.MAX_DATA_BYTES) { "WAV 超过 4GB，请改用 M4A" }
                    val bytes = buffer.remaining()
                    while (buffer.hasRemaining()) file.channel.write(buffer)
                    dataBytes += bytes
                }
                if (mime == "audio/raw") {
                    val buffer = ByteBuffer.allocateDirect(1024 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        buffer.position(0); buffer.limit(size)
                        writePcm(buffer)
                        update(extractor.sampleTime)
                        extractor.advance()
                    }
                } else {
                    format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    val decoder = MediaCodec.createDecoderByType(mime)
                    codec = decoder
                    decoder.configure(format, null, null, 0)
                    decoder.start()
                    started = true
                    val info = MediaCodec.BufferInfo()
                    var inputEnded = false
                    var outputEnded = false
                    var lastOutputAt = SystemClock.elapsedRealtime()
                    while (!outputEnded) {
                        coroutineContext.ensureActive()
                        if (!inputEnded) {
                            val index = decoder.dequeueInputBuffer(10_000)
                            if (index >= 0) {
                                val buffer = checkNotNull(decoder.getInputBuffer(index))
                                val size = extractor.readSampleData(buffer, 0)
                                if (size < 0) {
                                    decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputEnded = true
                                } else {
                                    decoder.queueInputBuffer(index, 0, size, extractor.sampleTime.coerceAtLeast(0), 0)
                                    extractor.advance()
                                }
                            }
                        }
                        when (val index = decoder.dequeueOutputBuffer(info, 10_000)) {
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val actual = decoder.outputFormat
                                sampleRate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                channels = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                pcmEncoding = if (actual.containsKey(MediaFormat.KEY_PCM_ENCODING)) actual.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                            }
                            MediaCodec.INFO_TRY_AGAIN_LATER -> {
                                check(SystemClock.elapsedRealtime() - lastOutputAt < 30_000) { "音频解码长时间无响应，请换一个文件重试" }
                            }
                            else -> if (index >= 0) {
                                try {
                                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                        val buffer = checkNotNull(decoder.getOutputBuffer(index))
                                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                        writePcm(buffer)
                                        update(info.presentationTimeUs)
                                    }
                                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                    lastOutputAt = SystemClock.elapsedRealtime()
                                } finally { decoder.releaseOutputBuffer(index, false) }
                            }
                        }
                    }
                }
                coroutineContext.ensureActive()
                require(dataBytes > 0) { "音频轨道为空" }
                file.seek(0)
                file.write(WavHeader.create(dataBytes, sampleRate, channels))
                progress(99, "写入 WAV 文件头")
            }
        } finally {
            if (started) runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }
}
