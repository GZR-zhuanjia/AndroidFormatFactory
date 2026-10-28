package com.zhuanformat.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ExportState(val running: Boolean = false, val message: String? = null)

/** Application lifetime keeps large SAF copies and their visible status intact across rotation. */
class ResultExporter(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(ExportState())
    val state = _state.asStateFlow()

    fun start(record: ConversionRecord, destination: Uri) {
        if (_state.value.running) return
        _state.value = ExportState(running = true)
        scope.launch {
            val message = try {
                withContext(Dispatchers.IO) {
                    val file = context.conversionRepository.resultFile(record) ?: error("转换结果已不存在")
                    val output = context.contentResolver.openOutputStream(destination, "wt") ?: error("无法打开保存位置")
                    output.use { stream ->
                        file.inputStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                ensureActive()
                                val size = input.read(buffer)
                                if (size < 0) break
                                stream.write(buffer, 0, size)
                            }
                        }
                    }
                }
                "已保存到所选位置"
            } catch (error: Exception) {
                withContext(Dispatchers.IO) {
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, destination) }
                }
                if (error is CancellationException) throw error
                "保存失败，请检查目标目录权限和剩余空间。可再次保存。"
            }
            _state.value = ExportState(message = message)
        }
    }

    fun consumeMessage() { _state.value = _state.value.copy(message = null) }
}
