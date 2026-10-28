package com.zhuanformat.app

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** FFmpeg receives a private seekable file, never a SAF URI or a remote URL. */
object LocalMediaInput {
    const val MAX_INPUT_BYTES = 8L * 1024 * 1024 * 1024
    private const val FREE_RESERVE = 64L * 1024 * 1024

    fun requireSpace(directory: File, additionalBytes: Long = 0) {
        require(directory.usableSpace >= additionalBytes.coerceAtLeast(0) + FREE_RESERVE) {
            "应用存储空间不足，请释放空间后重试（还需为输出文件预留空间）。"
        }
    }

    suspend fun copy(
        context: Context, uri: Uri, knownSize: Long,
        progress: (Int?, String) -> Unit = { _, _ -> }
    ): File {
        var created: File? = null
        return try {
            withContext(Dispatchers.IO) {
                require(uri.scheme == "content") { "请通过系统文件选择器选择文件" }
                require(knownSize <= MAX_INPUT_BYTES) { "音频处理的源文件上限为 8GB，请选择更小的文件。" }
                val directory = context.conversionRepository.temporaryDir
                requireSpace(directory, knownSize.coerceAtLeast(0))
                val target = File.createTempFile("input_", ".source", directory).also { created = it }
                progress(null, "准备本地音频文件")
                val input = context.contentResolver.openInputStream(uri) ?: throw IOException("无法读取所选文件")
                input.use { source ->
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var copied = 0L
                        var spaceCheckAt = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = source.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            copied += count
                            require(copied <= MAX_INPUT_BYTES) { "音频处理的源文件上限为 8GB，请选择更小的文件。" }
                            if (copied >= spaceCheckAt) {
                                requireSpace(directory, count.toLong())
                                spaceCheckAt = copied + 8 * 1024 * 1024
                            }
                            output.write(buffer, 0, count)
                        }
                        require(copied > 0) { "所选文件是空文件" }
                    }
                }
                coroutineContext.ensureActive()
                target
            }
        } catch (error: Throwable) {
            // Also handles cancellation while dispatching the completed IO result.
            created?.delete()
            throw error
        }
    }
}
