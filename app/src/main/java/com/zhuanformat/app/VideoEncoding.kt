package com.zhuanformat.app

import android.media.MediaCodecList
import kotlin.math.max

object VideoEncoding {
    fun isSupported(codec: VideoCodec): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(codec.mime, ignoreCase = true) }
        }
    }.getOrDefault(false)

    /** Conservative target, not a promise of source quality or lossless video. */
    fun targetBitrate(source: SourceInfo, options: ConversionOptions): Int {
        if (options.videoBitrate > 0) return options.videoBitrate
        val width = source.width.takeIf { it > 0 } ?: 1920
        val height = source.height.takeIf { it > 0 } ?: 1080
        val scale = if (options.videoHeight > 0 && height > options.videoHeight)
            options.videoHeight.toDouble() / height else 1.0
        val frameRate = source.frameRate.takeIf { it.isFinite() && it > 0 } ?: 30f
        val pixelTarget = (width.toDouble() * height * scale * scale * frameRate * 0.20).toLong()
        // Include source bitrate before applying the bounded target range.
        return max(source.videoBitrate.toLong(), pixelTarget).coerceIn(8_000_000L, 100_000_000L).toInt()
    }
}
