package com.zhuanformat.app

import android.Manifest
import android.app.Activity.RESULT_OK
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import com.zhuanformat.app.icons.ArrowDownward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import com.zhuanformat.app.icons.DeleteOutline
import com.zhuanformat.app.icons.FolderOpen
import com.zhuanformat.app.icons.GraphicEq
import com.zhuanformat.app.icons.History
import com.zhuanformat.app.icons.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import com.zhuanformat.app.icons.Movie
import com.zhuanformat.app.icons.MusicNote
import androidx.compose.material.icons.outlined.Share
import com.zhuanformat.app.icons.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ConverterTheme { ConverterScreen() } }
    }
}

private val Ink = Color(0xFF172D2A)
private val Teal = Color(0xFF087F70)
private val Muted = Color(0xFF65736D)
private val Paper = Color(0xFFF6F7F4)

@Composable
fun ConverterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Teal, onPrimary = Color.White, primaryContainer = Color(0xFFD9F2E9), onPrimaryContainer = Ink,
            secondary = Color(0xFFB36235), secondaryContainer = Color(0xFFFFE8D8),
            background = Paper, onBackground = Ink, surface = Color.White, onSurface = Ink,
            surfaceVariant = Color(0xFFEDF1EB), onSurfaceVariant = Muted, outline = Color(0xFFBEC9C2)
        ), content = content
    )
}

@Composable
fun ConverterScreen() {
    val context = LocalContext.current
    val repository = context.conversionRepository
    val exporter = (context.applicationContext as ConverterApp).exporter
    val exportState by exporter.state.collectAsStateWithLifecycle()
    val exporting = exportState.running
    val records by repository.records.collectAsStateWithLifecycle()
    val active by repository.active.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }
    var page by rememberSaveable { mutableStateOf(0) }
    var kindName by rememberSaveable { mutableStateOf(MediaKind.VIDEO.name) }
    val kind = MediaKind.valueOf(kindName)
    var selectedUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pickingKind by rememberSaveable { mutableStateOf(MediaKind.VIDEO.name) }
    var formatName by rememberSaveable { mutableStateOf(OutputFormat.MP4.name) }
    var videoHeight by rememberSaveable { mutableStateOf(0) }
    var videoBitrate by rememberSaveable { mutableStateOf(0) }
    var audioBitrate by rememberSaveable { mutableStateOf(320_000) }
    var imageMaxEdge by rememberSaveable { mutableStateOf(0) }
    var imageQuality by rememberSaveable { mutableStateOf(100) }
    var videoAudioBitrate by rememberSaveable { mutableStateOf(320_000) }
    var videoCodecName by rememberSaveable { mutableStateOf(VideoCodec.H264.name) }
    var imageLossless by rememberSaveable { mutableStateOf(Build.VERSION.SDK_INT >= 30) }
    val hevcSupported = remember { VideoEncoding.isSupported(VideoCodec.HEVC) }
    var audioSampleRate by rememberSaveable { mutableStateOf(0) }
    var audioBitDepth by rememberSaveable { mutableStateOf(0) }
    val options = ConversionOptions(kind, OutputFormat.valueOf(formatName), videoHeight, videoBitrate,
        if (kind == MediaKind.VIDEO) videoAudioBitrate else audioBitrate, imageMaxEdge, imageQuality, audioSampleRate,
        if (formatName == OutputFormat.FLAC.name && audioBitDepth == -32) 0 else audioBitDepth,
        VideoCodec.valueOf(videoCodecName), imageLossless)
    var source by remember { mutableStateOf<SourceInfo?>(null) }
    var inspecting by remember { mutableStateOf(false) }
    var sourceError by remember { mutableStateOf<String?>(null) }
    var help by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<ConversionRecord?>(null) }
    var deleting by remember { mutableStateOf<ConversionRecord?>(null) }
    var saveGuideId by rememberSaveable { mutableStateOf<String?>(null) }
    var savingId by rememberSaveable { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf(false) }

    fun message(text: String) { scope.launch { snackbars.showSnackbar(text) } }
    fun startConversion() {
        val input = source ?: return
        if (active != null || starting) return
        starting = true
        runCatching { ConversionService.start(context, input, options) }
            .onFailure { message("无法启动转换，请回到应用后重试。"); starting = false }
    }

    LaunchedEffect(active) { if (active != null) starting = false }
    LaunchedEffect(exportState.message) {
        exportState.message?.let {
            snackbars.showSnackbar(it)
            exporter.consumeMessage()
        }
    }
    LaunchedEffect(starting) {
        if (starting) { kotlinx.coroutines.delay(3000); starting = false }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { startConversion() }
    fun selectSource(uri: Uri?) {
        if (uri == null) return
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        if (pickingKind != kindName) {
            kindName = pickingKind
            formatName = formatsFor(MediaKind.valueOf(pickingKind)).first().name
        }
        selectedUri = uri.toString()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selectSource(it) }
    // AndroidX uses the system Photo Picker where available and OpenDocument on older devices.
    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { selectSource(it) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val destination = result.data?.data
        val record = records.firstOrNull { it.id == savingId }
        savingId = null
        if (result.resultCode == RESULT_OK && destination != null && record != null) {
            exporter.start(record, destination)
        }
    }
    LaunchedEffect(selectedUri, kind) {
        source = null; sourceError = null
        val uri = selectedUri ?: return@LaunchedEffect
        inspecting = true
        try { source = MediaFiles.inspect(context, Uri.parse(uri), kind) }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            sourceError = ConversionService.friendlyError(error)
        } finally { inspecting = false }
    }

    fun launchSave(record: ConversionRecord) {
        if (repository.resultFile(record) == null) { message("转换结果已不存在"); return }
        savingId = record.id
        runCatching {
            saver.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = record.format.mime
                putExtra(Intent.EXTRA_TITLE, record.outputName)
            })
        }.onFailure { savingId = null; message("设备没有可用的文件保存应用") }
    }
    fun share(record: ConversionRecord) {
        val file = repository.resultFile(record) ?: run { message("转换结果已不存在"); return }
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = record.format.mime; putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("转换结果", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "分享转换结果"))
        }.onFailure { message("设备没有可用的分享应用") }
    }

    Scaffold(
        containerColor = Paper,
        snackbarHost = { SnackbarHost(snackbars) },
        bottomBar = {
            NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                NavigationBarItem(selected = page == 0, onClick = { page = 0 }, icon = { Icon(Icons.Outlined.SwapHoriz, null) }, label = { Text("工作台") })
                NavigationBarItem(selected = page == 1, onClick = { page = 1 }, icon = { Icon(Icons.Outlined.History, null) }, label = { Text("转换记录${if (records.isEmpty()) "" else " · ${records.size}"}") })
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 720.dp).fillMaxSize().testTag("main_list"),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(12.dp), color = Teal) { Icon(Icons.Outlined.SwapHoriz, null, Modifier.padding(9.dp).size(24.dp), tint = Color.White) }
                        Text("转格式", Modifier.padding(start = 12.dp).weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { help = true }) { Icon(Icons.Outlined.Info, contentDescription = "支持格式与使用说明", tint = Muted) }
                    }
                }
                if (page == 0) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("让文件，\n换个格式。", fontSize = 34.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Lock, null, Modifier.size(15.dp), tint = Teal)
                                Text(" 本地处理 · 无需上传 · 轻松分享", color = Muted, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            MediaKind.entries.forEach { item ->
                                val chosen = item == kind
                                Surface(onClick = {
                                    if (item != kind) { kindName = item.name; formatName = formatsFor(item).first().name; selectedUri = null }
                                }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp), color = if (chosen) Ink else Color.White) {
                                    Column(Modifier.padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(kindIcon(item), null, tint = if (chosen) Color(0xFF9DE8CD) else Muted)
                                        Text(item.title, color = if (chosen) Color.White else Ink, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                    item {
                        SourceCard(kind, source, inspecting, sourceError, enabled = !exporting,
                            onGalleryPick = {
                                pickingKind = kind.name
                                val mediaType = if (kind == MediaKind.IMAGE) ActivityResultContracts.PickVisualMedia.ImageOnly
                                    else ActivityResultContracts.PickVisualMedia.VideoOnly
                                runCatching { galleryPicker.launch(PickVisualMediaRequest(mediaType)) }
                                    .onFailure {
                                        runCatching { picker.launch(arrayOf(if (kind == MediaKind.IMAGE) "image/*" else "video/*")) }
                                            .onFailure { message("设备没有可用的相册或文件选择应用") }
                                    }
                            },
                            onPick = {
                                pickingKind = kind.name
                                // Some document providers report audio as application/octet-stream.
                                runCatching { picker.launch(if (kind == MediaKind.AUDIO) arrayOf("*/*") else arrayOf(kind.pickerMime)) }
                                    .onFailure { message("设备没有可用的文件选择应用") }
                            })
                    }
                    item {
                        SettingsCard(options, source, hevcSupported, onVideoCodec = { videoCodecName = it.name }, onImageLossless = { imageLossless = it }, onFormat = { formatName = it.name }, onVideoHeight = { videoHeight = it }, onVideoBitrate = { videoBitrate = it }, onAudioBitrate = { if (kind == MediaKind.VIDEO) videoAudioBitrate = it else audioBitrate = it }, onAudioSampleRate = { audioSampleRate = it }, onAudioBitDepth = { audioBitDepth = it }, onImageEdge = { imageMaxEdge = it }, onImageQuality = { imageQuality = it })
                    }
                    if (active != null) item { ProgressCard(checkNotNull(active)) { ConversionService.cancel(context) } }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = {
                                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else startConversion()
                            }, enabled = source != null && !inspecting && active == null && !starting, modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp), shape = RoundedCornerShape(18.dp)) {
                                if (starting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                else { Text("开始转换", fontSize = 17.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.width(10.dp)); Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(20.dp)) }
                            }
                            Text(if (active != null) "已有任务正在处理，可切换页面或将应用放到后台。" else "转换能力取决于设备编解码器，暂不支持所有格式。", style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                    }
                    val latest = records.firstOrNull { it.status != JobStatus.RUNNING }
                    if (latest != null) item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("最近结果", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                TextButton(onClick = { page = 1 }) { Text("全部记录") }
                            }
                            ResultCard(latest, exporting, onSave = { saveGuideId = latest.id }, onShare = { share(latest) }, onDetail = { detail = latest })
                        }
                    }
                } else {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("转换记录", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                            Text("结果保存在应用内，保存一份到你的文件夹。", color = Muted, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (active != null) item { ProgressCard(checkNotNull(active)) { ConversionService.cancel(context) } }
                    if (records.isEmpty()) item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.History, null, Modifier.size(54.dp), tint = Color(0xFFB1C1B8))
                            Text("还没有转换记录", fontWeight = FontWeight.SemiBold)
                            Text("从一个视频、音频或图片开始。", color = Muted)
                            TextButton(onClick = { page = 0 }) { Text("去选文件") }
                        }
                    }
                    items(records.filterNot { it.status == JobStatus.RUNNING }, key = { it.id }) { record ->
                        Column {
                            ResultCard(record, exporting, onSave = { saveGuideId = record.id }, onShare = { share(record) }, onDetail = { detail = record })
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { deleting = record }, enabled = !exporting) { Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(16.dp)); Text(" 删除", style = MaterialTheme.typography.labelMedium) }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(4.dp)) }
            }
        }
    }

    if (help) AlertDialog(onDismissRequest = { help = false }, icon = { Icon(Icons.Outlined.Info, null) }, title = { Text("在本地，安心转换") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("视频 → MP4（H.264 或 H.265 / AAC）\n音频或视频声音 → M4A / MP3 / FLAC / WAV\n图片 → JPG / PNG / WebP")
            Text("音频由 FFmpeg 在本机处理，可尝试 MP4、MOV、MKV、WebM、MP3、AAC、FLAC、WAV 等。视频兼容性取决于设备；不支持批量转换或加密媒体处理。", color = Muted)
            Text("视频、图片页可直接从相册选择；“音频”页可从相册选视频提取声音。结果可保存到手机或系统识别的 OTG U盘，在系统文件窗口选择位置。", color = Muted)
            Text("音频源文件上限 8GB，读取时会创建应用内临时副本；请为副本及输出预留存储空间。", color = Muted)
            Text("视频默认原尺寸和自动高码率，HDR 转 SDR；高码率不等于无损。图片默认原尺寸，PNG / WebP 无损只保留解码后像素；导出不保留 EXIF / 位置信息或宽色域。JPG 透明区域填为白色。WAV 文件较大，上限约 4GB。", color = Muted)
            Text("音频开源组件：FFmpeg 7.1.5（LGPL 2.1 或更高版本）、LAME 3.100（LGPL 2 或更高版本）。许可证随应用附带，源码、构建和替换说明：github.com/GZR-zhuanjia/AndroidFormatFactory", color = Muted)
            Text("无需联网或全盘权限。后台任务可在通知或应用中取消；系统结束进程后不会自动续传。卸载应用会清除应用内结果，请及时保存。", color = Muted)
        }
    }, confirmButton = { TextButton(onClick = { help = false }) { Text("知道了") } })
    saveGuideId?.let { id ->
        val record = records.firstOrNull { it.id == id }
        if (record != null) AlertDialog(onDismissRequest = { saveGuideId = null }, title = { Text("保存到手机 / U盘") },
            text = { Text("在接下来的系统文件窗口，打开左上角菜单，选择手机存储或已连接的 U盘，再选择文件夹并点“保存”。\n\n若看不到 U盘，请检查 OTG 连接及手机是否识别了 U盘；部分设备不支持外接存储。") },
            confirmButton = { TextButton(onClick = { saveGuideId = null; launchSave(record) }) { Text("选择保存位置") } },
            dismissButton = { TextButton(onClick = { saveGuideId = null }) { Text("取消") } })
    }
    detail?.let { record ->
        AlertDialog(onDismissRequest = { detail = null }, title = { Text(record.status.title) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(record.sourceName)
                Text("输出：${record.format.label}\n${record.optionsSummary}", color = Muted)
                if (record.outputName != null) Text(record.outputName)
                if (record.message != null) Text(record.message)
                if (record.status == JobStatus.SUCCESS) Text("${formatBytes(record.size)} · ${dateText(record.createdAt)}", color = Muted)
            }
        }, confirmButton = { TextButton(onClick = { detail = null }) { Text("关闭") } })
    }
    deleting?.let { record ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这条记录？") }, text = { Text("应用内的转换结果也会删除。已保存到其他文件夹的副本不受影响。") },
            confirmButton = { TextButton(onClick = {
                runCatching { repository.delete(record) }.onFailure { message("删除失败，请稍后重试") }
                deleting = null
            }) { Text("删除", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("保留") } })
    }
    if (exporting) AlertDialog(onDismissRequest = {}, title = { Text("正在保存文件") }, text = { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp); Text(" 正在复制到所选位置…", Modifier.padding(start = 8.dp)) } }, confirmButton = { TextButton(onClick = { exporter.cancel() }) { Text("取消保存") } })
}

@Composable
private fun SourceCard(kind: MediaKind, source: SourceInfo?, loading: Boolean, error: String?, enabled: Boolean, onGalleryPick: (() -> Unit)?, onPick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionLabel("01", "选择${kind.title}文件")
            if (onGalleryPick != null) {
                OutlinedButton(onClick = onGalleryPick, enabled = enabled, shape = RoundedCornerShape(12.dp)) {
                    Icon(if (kind == MediaKind.IMAGE) Icons.Outlined.Image else Icons.Outlined.Movie, null, Modifier.size(18.dp))
                    Text(when (kind) { MediaKind.AUDIO -> " 从相册选视频提取声音"; MediaKind.VIDEO -> " 从相册选视频"; MediaKind.IMAGE -> " 从相册选图片" })
                }
            }
            if (loading) {
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp); Text(" 正在读取文件信息…", Modifier.padding(start = 10.dp), color = Muted) }
            } else if (source != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFFEDF5EF)) { Icon(kindIcon(kind), null, Modifier.padding(13.dp), tint = Teal) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(source.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(source.detail, style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    Icon(Icons.Outlined.CheckCircle, "文件已就绪", tint = Teal, modifier = Modifier.size(20.dp))
                }
                TextButton(onClick = onPick, enabled = enabled, contentPadding = PaddingValues(0.dp)) { Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp)); Text(" 重新选择") }
            } else {
                Surface(color = Color(0xFFF0F5EF), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(if (kind == MediaKind.AUDIO) Icons.Outlined.GraphicEq else kindIcon(kind), null, Modifier.size(38.dp), tint = Teal)
                        Text(if (kind == MediaKind.AUDIO) "转换音频，也能提取视频声音" else "选择一个${kind.title}，开始转换", style = MaterialTheme.typography.bodyMedium, color = Muted)
                        OutlinedButton(onClick = onPick, enabled = enabled, shape = RoundedCornerShape(12.dp)) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Text(" 选择文件") }
                    }
                }
                if (error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsCard(options: ConversionOptions, source: SourceInfo?, hevcSupported: Boolean, onVideoCodec: (VideoCodec) -> Unit, onImageLossless: (Boolean) -> Unit, onFormat: (OutputFormat) -> Unit, onVideoHeight: (Int) -> Unit, onVideoBitrate: (Int) -> Unit, onAudioBitrate: (Int) -> Unit, onAudioSampleRate: (Int) -> Unit, onAudioBitDepth: (Int) -> Unit, onImageEdge: (Int) -> Unit, onImageQuality: (Int) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionLabel("02", "设置输出")
            Text("目标格式", style = MaterialTheme.typography.labelLarge, color = Muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                formatsFor(options.kind).forEach { format -> FilterChip(selected = format == options.format, onClick = { onFormat(format) }, label = { Text(format.label, fontWeight = FontWeight.Bold) }, leadingIcon = if (format == options.format) {{ Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)) }} else null) }
            }
            HorizontalDivider(color = Paper)
            when (options.kind) {
                MediaKind.VIDEO -> {
                    Text("视频编码", style = MaterialTheme.typography.labelLarge, color = Muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VideoCodec.entries.forEach { codec ->
                            FilterChip(selected = options.videoCodec == codec, onClick = { onVideoCodec(codec) },
                                enabled = codec != VideoCodec.HEVC || hevcSupported,
                                label = { Text(codec.label) })
                        }
                    }
                    if (!hevcSupported) Text("此设备未报告 HEVC 编码器，可使用 H.264。", style = MaterialTheme.typography.bodySmall, color = Muted)
                    ChoiceRow("视频分辨率", listOf(0 to "原始", 2160 to "2160p", 1080 to "1080p", 720 to "720p", 480 to "480p"), options.videoHeight, onVideoHeight)
                    ChoiceRow("画质 / 目标码率", listOf(0 to "自动高质量", 100_000_000 to "100M", 60_000_000 to "60M", 40_000_000 to "40M", 24_000_000 to "24M", 16_000_000 to "16M", 8_000_000 to "8M", 4_000_000 to "4M", 2_000_000 to "2M"), options.videoBitrate, onVideoBitrate)
                    if (options.videoBitrate == 0 && source != null) Text("自动目标约 ${VideoEncoding.targetBitrate(source, options) / 1_000_000} Mbps，根据源信息、尺寸与帧率估算（8–100 Mbps）。", style = MaterialTheme.typography.bodySmall, color = Muted)
                    ChoiceRow("视频中的 AAC 音频码率", listOf(128_000 to "128 kbps", 192_000 to "192 kbps", 256_000 to "256 kbps", 320_000 to "320 kbps"), options.audioBitrate, onAudioBitrate)
                    Text("默认原尺寸、保留源帧率；高码率仍是有损重编码。设备可能调整尺寸或码率，HEVC 兼容性取决于播放设备；不能输出所选编码时会提示失败。HDR 会转换为 SDR，不保留原 HDR 效果。", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                MediaKind.AUDIO -> {
                    if (options.format in listOf(OutputFormat.M4A, OutputFormat.MP3)) ChoiceRow("音频码率", listOf(128_000 to "128 kbps", 192_000 to "192 kbps", 256_000 to "256 kbps", 320_000 to "320 kbps"), options.audioBitrate, onAudioBitrate)
                    ChoiceRow("采样率", listOf(0 to "原始", 44100 to "44.1 kHz", 48000 to "48 kHz", 96000 to "96 kHz"), options.audioSampleRate, onAudioSampleRate)
                    if (options.format in listOf(OutputFormat.WAV, OutputFormat.FLAC)) {
                        val depths = listOf(0 to "按源位深", 16 to "16 位", 24 to "24 位") +
                            if (options.format == OutputFormat.WAV) listOf(-32 to "32 位浮点") else emptyList()
                        ChoiceRow("音频位深", depths, options.audioBitDepth, onAudioBitDepth)
                    }
                    Text(when (options.format) {
                        OutputFormat.WAV -> "PCM 无压缩，可选 16 / 24 位整数、32 位浮点。按源位深保留整数精度（最高 32 位），浮点转 32 位浮点。保留声道，标准 WAV 上限约 4GB。"
                        OutputFormat.FLAC -> "FLAC 16 / 24 位整数压缩，保留声道。按源位深最高保留 24 位；浮点或 32 位整数源转为 24 位，无法完整保留原精度。"
                        OutputFormat.MP3 -> "LAME MP3 编码，最多双声道；采样率必要时调整到编码器支持的值。选择视频时仅提取声音。"
                        else -> "M4A 容器 · AAC 编码，最多双声道。选择视频时仅提取声音。"
                    }, style = MaterialTheme.typography.bodySmall, color = Muted)
                    Text("提高码率可减少重编码损失；提高采样率或位深不能恢复源文件已丢失的音质。MP3 / AAC 采样率会调整到编码器支持的值。", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                MediaKind.IMAGE -> {
                    ChoiceRow("图片最长边", listOf(0 to "原图", 4096 to "4096px", 3840 to "3840px", 2560 to "2560px", 1920 to "1920px", 1280 to "1280px"), options.imageMaxEdge, onImageEdge)
                    if (options.format == OutputFormat.WEBP) {
                        Text("WebP 编码", style = MaterialTheme.typography.labelLarge, color = Muted)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !options.imageLossless, onClick = { onImageLossless(false) }, label = { Text("有损") })
                            FilterChip(selected = options.imageLossless, onClick = { onImageLossless(true) }, enabled = Build.VERSION.SDK_INT >= 30, label = { Text("无损（Android 11+）") })
                        }
                    }
                    if (options.format != OutputFormat.PNG && !(options.format == OutputFormat.WEBP && options.imageLossless)) ChoiceRow("图片质量", listOf(80 to "80%", 90 to "90%", 95 to "95%", 100 to "100%"), options.imageQuality, onImageQuality)
                    Text(if (options.format == OutputFormat.JPG) "默认原尺寸、质量 100；JPG 仍为有损编码，透明区域填为白色。" else "PNG / WebP 无损保留解码后的像素；WebP 有损质量 100 不等于无损。保留透明度，仅导出静态图片。", style = MaterialTheme.typography.bodySmall, color = Muted)
                    Text("自动纠正方向，不保留原 EXIF / 位置信息或宽色域。解码超过 2400 万像素会提示选择较小尺寸，不会自动缩小原图。", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceRow(label: String, choices: List<Pair<Int, String>>, selected: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            choices.forEach { (value, text) -> FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(text, style = MaterialTheme.typography.labelMedium) }) }
        }
    }
}

@Composable
private fun SectionLabel(number: String, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(number, color = Teal, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(Color(0xFFE8F4EC), RoundedCornerShape(7.dp)).padding(horizontal = 7.dp, vertical = 5.dp))
        Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ProgressCard(job: ActiveJob, onCancel: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFDFEFE7)), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(job.stage, fontWeight = FontWeight.Bold)
                    Text(job.record.sourceName, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(job.progress?.let { "$it%" } ?: "处理中", fontWeight = FontWeight.Bold, color = Teal)
                IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, "取消转换", Modifier.size(20.dp)) }
            }
            if (job.progress == null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Teal, trackColor = Color.White)
            else LinearProgressIndicator(progress = { job.progress / 100f }, modifier = Modifier.fillMaxWidth(), color = Teal, trackColor = Color.White)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultCard(record: ConversionRecord, busy: Boolean, onSave: () -> Unit, onShare: () -> Unit, onDetail: () -> Unit) {
    Card(onClick = onDetail, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(color = if (record.status == JobStatus.SUCCESS) Color(0xFFE8F4EC) else Color(0xFFF5EDE3), shape = RoundedCornerShape(12.dp)) {
                    Icon(kindIcon(record.kind), null, Modifier.padding(11.dp), tint = if (record.status == JobStatus.SUCCESS) Teal else Color(0xFF9D6D36))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(record.outputName ?: record.sourceName, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${record.format.label} · ${record.status.title}${if (record.status == JobStatus.SUCCESS) " · ${formatBytes(record.size)}" else ""}", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
            if (record.message != null) Text(record.message, style = MaterialTheme.typography.bodySmall, color = Muted)
            if (record.status == JobStatus.SUCCESS) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onSave, enabled = !busy, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) { Icon(Icons.Outlined.ArrowDownward, null, Modifier.size(17.dp)); Text(" 保存到手机 / U盘") }
                    OutlinedButton(onClick = onShare, enabled = !busy, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) { Icon(Icons.Outlined.Share, null, Modifier.size(17.dp)); Text(" 分享") }
                }
            }
            Text(dateText(record.createdAt), style = MaterialTheme.typography.labelSmall, color = Muted)
        }
    }
}

private fun kindIcon(kind: MediaKind): ImageVector = when (kind) {
    MediaKind.VIDEO -> Icons.Outlined.Movie
    MediaKind.AUDIO -> Icons.Outlined.MusicNote
    MediaKind.IMAGE -> Icons.Outlined.Image
}

private fun dateText(time: Long): String = SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(Date(time))
