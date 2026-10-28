package com.zhuanformat.app

import android.app.Application
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ConverterApp : Application() {
    lateinit var repository: ConversionRepository
        private set
    lateinit var exporter: ResultExporter
        private set

    override fun onCreate() {
        super.onCreate()
        repository = ConversionRepository(this)
        exporter = ResultExporter(this)
    }
}

val Context.conversionRepository: ConversionRepository
    get() = (applicationContext as ConverterApp).repository

/** One process-wide owner. A service is the only writer for active task transitions. */
class ConversionRepository(context: Context) {
    private val prefs = context.getSharedPreferences("conversions", Context.MODE_PRIVATE)
    val resultsDir = File(context.filesDir, "results").apply { mkdirs() }
    val temporaryDir = File(context.cacheDir, "jobs").apply { mkdirs() }
    private val _records = MutableStateFlow(load())
    val records = _records.asStateFlow()
    private val _active = MutableStateFlow<ActiveJob?>(null)
    val active = _active.asStateFlow()

    init {
        // No service survives a dead process. Mark the previous task honestly and discard partials.
        _records.value = _records.value.map {
            if (it.status == JobStatus.RUNNING) it.copy(status = JobStatus.INTERRUPTED, message = "应用进程已结束，请重新选择文件转换。") else it
        }
        temporaryDir.listFiles()?.forEach { it.delete() }
        persist()
    }

    fun begin(record: ConversionRecord): Boolean {
        if (_active.value != null) return false
        _records.value = listOf(record) + _records.value
        _active.value = ActiveJob(record)
        persist()
        return true
    }

    fun progress(id: String, percent: Int?, stage: String) {
        val active = _active.value ?: return
        if (active.record.id == id) _active.value = active.copy(progress = percent?.coerceIn(0, 99), stage = stage)
    }

    fun finish(id: String, status: JobStatus, outputName: String? = null, size: Long = 0, message: String? = null) {
        if (_active.value?.record?.id != id) return
        _records.value = _records.value.map {
            if (it.id == id) it.copy(status = status, outputName = outputName, size = size, message = message) else it
        }
        _active.value = null
        persist()
    }

    fun resultFile(record: ConversionRecord): File? {
        val name = record.outputName ?: return null
        if (name != File(name).name || name.contains('/')) return null
        return File(resultsDir, name).takeIf { it.isFile && it.parentFile == resultsDir }
    }

    fun delete(record: ConversionRecord) {
        if (_active.value?.record?.id == record.id) return
        val result = resultFile(record)
        check(result == null || result.delete()) { "文件暂时无法删除，请稍后重试" }
        _records.value = _records.value.filterNot { it.id == record.id }
        persist()
    }

    private fun persist() {
        val array = JSONArray()
        _records.value.forEach { record ->
            array.put(JSONObject().apply {
                put("id", record.id); put("sourceName", record.sourceName)
                put("kind", record.kind.name); put("format", record.format.name)
                put("optionsSummary", record.optionsSummary); put("createdAt", record.createdAt)
                put("status", record.status.name); put("size", record.size)
                put("outputName", record.outputName); put("message", record.message)
            })
        }
        prefs.edit().putString("history", array.toString()).apply()
    }

    private fun load(): List<ConversionRecord> = runCatching {
        val array = JSONArray(prefs.getString("history", "[]"))
        (0 until array.length()).mapNotNull { index ->
            runCatching {
                val json = array.getJSONObject(index)
                ConversionRecord(
                    id = json.getString("id"), sourceName = json.getString("sourceName"),
                    kind = MediaKind.valueOf(json.getString("kind")), format = OutputFormat.valueOf(json.getString("format")),
                    optionsSummary = json.getString("optionsSummary"), createdAt = json.getLong("createdAt"),
                    status = JobStatus.valueOf(json.getString("status")), size = json.optLong("size"),
                    outputName = json.optString("outputName").takeIf { it.isNotBlank() },
                    message = json.optString("message").takeIf { it.isNotBlank() }
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())
}
