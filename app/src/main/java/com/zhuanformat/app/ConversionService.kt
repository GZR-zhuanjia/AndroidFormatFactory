@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.zhuanformat.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.transformer.ExportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class ConversionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val repository get() = conversionRepository
    private lateinit var notifications: NotificationManager
    private var lastNotificationAt = 0L
    private var taskName = ""

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "格式转换", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            job?.cancel()
            if (job == null) stopSelf()
            return START_NOT_STICKY
        }
        // Android serializes onStartCommand on the main thread. begin() closes the double-tap window.
        if (intent?.action != ACTION_START || repository.active.value != null || job?.isActive == true) return START_NOT_STICKY
        val sourceUri = intent.getStringExtra("uri") ?: return stopInvalid()
        val options = runCatching { optionsFrom(intent).also { it.validate() } }.getOrElse { return stopInvalid() }
        val record = ConversionRecord(sourceName = intent.getStringExtra("name") ?: "未命名文件", kind = options.kind, format = options.format, optionsSummary = options.summary)
        if (!repository.begin(record)) return START_NOT_STICKY
        taskName = record.sourceName
        try {
            val notification = notification(null, "准备转换")
            if (Build.VERSION.SDK_INT >= 35) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            else if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION_ID, notification)
        } catch (error: Exception) {
            repository.finish(record.id, JobStatus.FAILED, message = "无法启动后台转换，请回到应用重试。")
            stopSelf()
            return START_NOT_STICKY
        }
        job = scope.launch {
            val temporary = File(repository.temporaryDir, "${record.id}.part")
            var finalOutput: File? = null
            try {
                val source = if (options.kind == MediaKind.AUDIO) {
                    MediaFiles.documentInfo(this@ConversionService, Uri.parse(sourceUri))
                } else MediaFiles.inspect(this@ConversionService, Uri.parse(sourceUri), options.kind)
                val report: (Int?, String) -> Unit = { percent, stage ->
                    // IO engines call this from worker threads. Serialize observable state on main.
                    scope.launch {
                        repository.progress(record.id, percent, stage)
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (repository.active.value?.record?.id == record.id && now - lastNotificationAt > 900) {
                            if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this@ConversionService, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                                notifications.notify(NOTIFICATION_ID, notification(percent, stage))
                            }
                            lastNotificationAt = now
                        }
                    }
                }
                when {
                    options.kind == MediaKind.IMAGE -> ImageConverter.convert(this@ConversionService, Uri.parse(sourceUri), options, temporary, report)
                    options.kind == MediaKind.AUDIO -> NativeAudioConverter.convert(this@ConversionService, source, options, temporary, report)
                    else -> MediaTransformer.convert(this@ConversionService, source, options, temporary, report)
                }
                ensureActive()
                require(temporary.isFile && temporary.length() > 0) { "未生成有效的输出文件" }
                val outputName = safeOutputName(source.name, options.format)
                val output = File(repository.resultsDir, outputName)
                finalOutput = output
                withContext(Dispatchers.IO) {
                    check(!output.exists() && temporary.renameTo(output)) { "无法保存转换结果，请检查剩余存储空间" }
                }
                ensureActive()
                repository.finish(record.id, JobStatus.SUCCESS, outputName, output.length())
                finalOutput = null // Ownership is now with the result history.
            } catch (cancelled: CancellationException) {
                repository.finish(record.id, JobStatus.CANCELLED, message = "转换已取消，未完成的文件已清理。")
            } catch (error: OutOfMemoryError) {
                repository.finish(record.id, JobStatus.FAILED, message = "可用内存不足，请降低输出尺寸或关闭其他应用后重试。")
            } catch (error: Exception) {
                repository.finish(record.id, JobStatus.FAILED, message = friendlyError(error))
            } finally {
                temporary.delete()
                finalOutput?.delete()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun stopInvalid(): Int { stopSelf(); return START_NOT_STICKY }

    private fun notification(progress: Int?, stage: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(ACTION_CANCEL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("正在转换 · $taskName")
            .setContentText(stage).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, progress ?: 0, progress == null)
            .addAction(0, "取消", cancel).setCategory(NotificationCompat.CATEGORY_PROGRESS).build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onTimeout(startId: Int, fgsType: Int) { job?.cancel(); stopSelf() }

    companion object {
        private const val CHANNEL = "conversion"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.zhuanformat.app.START"
        private const val ACTION_CANCEL = "com.zhuanformat.app.CANCEL"

        fun start(context: Context, source: SourceInfo, options: ConversionOptions) {
            options.validate()
            ContextCompat.startForegroundService(context, Intent(context, ConversionService::class.java).setAction(ACTION_START).apply {
                putExtra("uri", source.uri); putExtra("name", source.name)
                putExtra("kind", options.kind.name); putExtra("format", options.format.name)
                putExtra("videoHeight", options.videoHeight); putExtra("videoBitrate", options.videoBitrate)
                putExtra("audioBitrate", options.audioBitrate); putExtra("imageMaxEdge", options.imageMaxEdge)
                putExtra("imageQuality", options.imageQuality)
                putExtra("audioSampleRate", options.audioSampleRate); putExtra("audioBitDepth", options.audioBitDepth)
            })
        }
        fun cancel(context: Context) {
            context.startService(Intent(context, ConversionService::class.java).setAction(ACTION_CANCEL))
        }
        private fun optionsFrom(intent: Intent) = ConversionOptions(
            kind = MediaKind.valueOf(checkNotNull(intent.getStringExtra("kind"))),
            format = OutputFormat.valueOf(checkNotNull(intent.getStringExtra("format"))),
            videoHeight = intent.getIntExtra("videoHeight", 720), videoBitrate = intent.getIntExtra("videoBitrate", 4_000_000),
            audioBitrate = intent.getIntExtra("audioBitrate", 192_000), imageMaxEdge = intent.getIntExtra("imageMaxEdge", 1920),
            imageQuality = intent.getIntExtra("imageQuality", 90),
            audioSampleRate = intent.getIntExtra("audioSampleRate", 0), audioBitDepth = intent.getIntExtra("audioBitDepth", 0)
        )
        fun friendlyError(error: Exception): String = when (error) {
            is SecurityException -> "文件访问权限已失效，请重新选择文件。"
            is ExportException -> when (error.errorCode) {
                in 3000..3999 -> "设备无法解码这个文件，文件可能已损坏或采用了不支持的编码。（${error.errorCode}）"
                in 4000..4999 -> "设备不支持所选编码参数，请降低视频分辨率或换一个文件重试。（${error.errorCode}）"
                in 5000..6999 -> "设备无法处理此媒体，HDR 或特殊音视频编码可能不受支持。（${error.errorCode}）"
                in 7000..7999 -> "输出封装失败，请检查存储空间，或尝试更小的文件。（${error.errorCode}）"
                else -> "读取或转换失败，请确认文件可用、存储空间充足后重试。（${error.errorCode}）"
            }
            is IllegalArgumentException, is IllegalStateException -> error.message?.takeIf { it.any { c -> c.code in 0x4E00..0x9FFF } } ?: "设备不支持这个文件或所选参数，请更换文件或降低尺寸后重试。"
            is IOException -> "无法读写文件，请检查文件访问权限与剩余存储空间。"
            is RuntimeException -> when {
                error.message?.contains("ENOSPC", ignoreCase = true) == true || error.message?.contains("No space left", ignoreCase = true) == true -> "存储空间不足，请释放空间后重试。"
                error.message?.contains("OUTPUT_TOO_LARGE") == true -> "输出文件超过大小上限：WAV 约 4GB，其他音频 8GB。请缩短源文件或选择压缩格式。"
                else -> "音频转换失败，文件可能已损坏、加密或不支持。请尝试其他文件或输出格式。"
            }
            else -> "转换未能完成，请重新选择文件，或尝试其他输出参数。"
        }
    }
}
