package com.zhuanformat.app

import android.content.Context
import android.net.Uri
import java.io.File

object NativeAudioConverter {
    suspend fun convert(
        context: Context, source: SourceInfo, options: ConversionOptions,
        output: File, progress: (Int?, String) -> Unit
    ) {
        require(options.kind == MediaKind.AUDIO)
        val input = LocalMediaInput.copy(context, Uri.parse(source.uri), source.size, progress)
        try {
            LocalMediaInput.requireSpace(output.parentFile ?: context.cacheDir)
            progress(null, "读取音频轨道")
            nativeAudioSession { session ->
                var lastPercent = -2
                NativeAudioBridge.convert(
                    session, input.absolutePath, output.absolutePath, options.format.extension,
                    options.audioSampleRate, 0, options.audioBitrate,
                    NativeProgressCallback { fraction ->
                        val percent = if (fraction.isFinite() && fraction >= 0) (fraction * 100).toInt().coerceIn(0, 99) else null
                        if ((percent ?: -1) != lastPercent) {
                            lastPercent = percent ?: -1
                            progress(percent, "编码 ${options.format.label} 音频")
                        }
                    }, options.audioBitDepth
                )
            }
        } finally {
            input.delete()
        }
    }
}
