package com.zhuanformat.app

import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

enum class MediaKind(val title: String, val pickerMime: String) {
    VIDEO("视频", "video/*"), AUDIO("音频", "audio/*"), IMAGE("图片", "image/*")
}

enum class OutputFormat(val label: String, val extension: String, val mime: String) {
    MP4("MP4", "mp4", "video/mp4"),
    M4A("M4A", "m4a", "audio/mp4"),
    WAV("WAV", "wav", "audio/wav"),
    JPG("JPG", "jpg", "image/jpeg"),
    PNG("PNG", "png", "image/png"),
    WEBP("WebP", "webp", "image/webp")
}

data class ConversionOptions(
    val kind: MediaKind = MediaKind.VIDEO,
    val format: OutputFormat = OutputFormat.MP4,
    val videoHeight: Int = 720,
    val videoBitrate: Int = 4_000_000,
    val audioBitrate: Int = 192_000,
    val imageMaxEdge: Int = 1920,
    val imageQuality: Int = 90
) {
    fun validate() {
        require(format in formatsFor(kind)) { "该输出格式不适用于所选媒体类型" }
        require(videoHeight in listOf(0, 480, 720, 1080))
        require(videoBitrate in listOf(2_000_000, 4_000_000, 8_000_000))
        require(audioBitrate in listOf(128_000, 192_000, 256_000))
        require(imageMaxEdge in listOf(0, 1280, 1920, 2560))
        require(imageQuality in listOf(80, 90, 100))
    }
    val summary: String get() = when (kind) {
        MediaKind.VIDEO -> "H.264 / AAC · ${if (videoHeight == 0) "原始尺寸" else "最高 ${videoHeight}p"}"
        MediaKind.AUDIO -> if (format == OutputFormat.WAV) "PCM 16 位 · 保留原采样率与声道" else "AAC · ${audioBitrate / 1000} kbps"
        MediaKind.IMAGE -> "${if (imageMaxEdge == 0) "原始尺寸" else "最长边 ${imageMaxEdge}px"}${if (format == OutputFormat.PNG) " · 无损编码" else " · 质量 $imageQuality%"}"
    }
}

fun formatsFor(kind: MediaKind): List<OutputFormat> = when (kind) {
    MediaKind.VIDEO -> listOf(OutputFormat.MP4)
    MediaKind.AUDIO -> listOf(OutputFormat.M4A, OutputFormat.WAV)
    MediaKind.IMAGE -> listOf(OutputFormat.JPG, OutputFormat.PNG, OutputFormat.WEBP)
}

data class SourceInfo(
    val uri: String,
    val name: String,
    val size: Long = -1,
    val durationMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0
) {
    val detail: String get() = listOfNotNull(
        if (size >= 0) formatBytes(size) else null,
        if (durationMs > 0) formatDuration(durationMs) else null,
        if (width > 0 && height > 0) "$width × $height" else null
    ).joinToString(" · ").ifEmpty { "文件已就绪" }
}

enum class JobStatus(val title: String) {
    RUNNING("转换中"), SUCCESS("已完成"), FAILED("转换失败"), CANCELLED("已取消"), INTERRUPTED("任务中断")
}

data class ConversionRecord(
    val id: String = UUID.randomUUID().toString(),
    val sourceName: String,
    val kind: MediaKind,
    val format: OutputFormat,
    val optionsSummary: String,
    val createdAt: Long = System.currentTimeMillis(),
    val status: JobStatus = JobStatus.RUNNING,
    val outputName: String? = null,
    val size: Long = 0,
    val message: String? = null
)

data class ActiveJob(val record: ConversionRecord, val progress: Int? = null, val stage: String = "准备文件")

fun safeOutputName(source: String, format: OutputFormat, unique: String = UUID.randomUUID().toString().take(8)): String {
    val base = source.substringBeforeLast('.', source).map { char ->
        if (char.isLetterOrDigit() || char in "-_ ()") char else '_'
    }.joinToString("").trim(' ', '.', '_').take(64).ifEmpty { "转换结果" }
    return "${base}_${unique}.${format.extension}"
}

fun fitDimensions(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
    require(width > 0 && height > 0)
    if (maxEdge <= 0 || maxOf(width, height) <= maxEdge) return width to height
    val ratio = maxEdge.toDouble() / maxOf(width, height)
    return maxOf(1, (width * ratio).roundToInt()) to maxOf(1, (height * ratio).roundToInt())
}

fun formatBytes(bytes: Long): String = when {
    bytes < 0 -> "大小未知"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
}

fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
