@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.zhuanformat.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class ConversionEngineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val created = mutableListOf<File>()

    private fun file(name: String) = File(context.conversionRepository.resultsDir, "test_${System.nanoTime()}_$name").also { created += it }
    private fun fixture(name: String): Pair<File, Uri> {
        val file = file(name)
        instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        return file to FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }
    private fun uri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    private fun tracks(file: File): List<MediaFormat> {
        val extractor = MediaExtractor()
        return try { extractor.setDataSource(file.absolutePath); (0 until extractor.trackCount).map { extractor.getTrackFormat(it) } } finally { extractor.release() }
    }

    @After fun cleanUp() { created.forEach { it.delete() } }

    @Test fun jpegResizesAndFillsTransparentPixelsWhite() = runBlocking {
        val input = file("transparent.png")
        Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888).let { bitmap -> input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
        val output = file("output.jpg")
        ImageConverter.convert(context, uri(input), ConversionOptions(kind = MediaKind.IMAGE, format = OutputFormat.JPG, imageMaxEdge = 1280), output) { _, _ -> }
        val bitmap = BitmapFactory.decodeFile(output.absolutePath)
        assertEquals(1280, bitmap.width)
        assertEquals(853, bitmap.height)
        assertEquals(Color.WHITE, bitmap.getPixel(0, 0))
        bitmap.recycle()
        assertTrue(input.isFile)
    }

    @Test fun pngAndWebpPreserveTransparency() = runBlocking {
        val input = file("transparent.png")
        Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888).let { bitmap -> input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
        for (format in listOf(OutputFormat.PNG, OutputFormat.WEBP)) {
            val output = file("output.${format.extension}")
            ImageConverter.convert(context, uri(input), ConversionOptions(kind = MediaKind.IMAGE, format = format), output) { _, _ -> }
            val bitmap = BitmapFactory.decodeFile(output.absolutePath)
            assertEquals(32, bitmap.width)
            assertEquals(48, bitmap.height)
            assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
            bitmap.recycle()
        }
    }

    @Test fun compressedAudioDecodesToValidPcmWav() = runBlocking {
        val (_, sourceUri) = fixture("sample.m4a")
        val output = file("output.wav")
        withTimeout(60_000) { WavConverter.convert(context, sourceUri, output) { _, _ -> } }
        val bytes = output.readBytes()
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(output.length() - 44, b.getInt(40).toLong())
        assertEquals(48000, b.getInt(24))
        assertEquals(16, b.getShort(34).toInt())
        assertTrue(bytes.drop(44).any { it != 0.toByte() })
        assertTrue(output.length() > 180_000)
    }

    @Test fun videoConvertsToH264Mp4AndKeepsAudio() = runBlocking {
        val (_, sourceUri) = fixture("sample.mp4")
        val source = MediaFiles.inspect(context, sourceUri, MediaKind.VIDEO)
        val output = file("output.mp4")
        withTimeout(120_000) { MediaTransformer.convert(context, source, ConversionOptions(videoHeight = 480, videoBitrate = 2_000_000), output) { _, _ -> } }
        val formats = tracks(output)
        val video = formats.first { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        assertEquals("video/avc", video.getString(MediaFormat.KEY_MIME))
        assertEquals(640, video.getInteger(MediaFormat.KEY_WIDTH))
        assertEquals(360, video.getInteger(MediaFormat.KEY_HEIGHT))
        assertTrue(formats.any { it.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" })
    }

    @Test fun videoSoundExtractsToAudioOnlyM4a() = runBlocking {
        val (_, sourceUri) = fixture("sample.mp4")
        val source = MediaFiles.inspect(context, sourceUri, MediaKind.AUDIO)
        val output = file("output.m4a")
        withTimeout(60_000) { MediaTransformer.convert(context, source, ConversionOptions(kind = MediaKind.AUDIO, format = OutputFormat.M4A), output) { _, _ -> } }
        val formats = tracks(output)
        assertEquals(1, formats.size)
        assertEquals("audio/mp4a-latm", formats.single().getString(MediaFormat.KEY_MIME))
    }

    @Test fun portraitVideoScalesToEncoderAlignedH264Dimensions() = runBlocking {
        val (_, sourceUri) = fixture("portrait.mp4")
        val source = MediaFiles.inspect(context, sourceUri, MediaKind.VIDEO)
        assertEquals(1920, source.height)
        val output = file("portrait-output.mp4")
        withTimeout(120_000) { MediaTransformer.convert(context, source, ConversionOptions(videoHeight = 720, videoBitrate = 2_000_000), output) { _, _ -> } }
        val video = tracks(output).first { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        assertEquals("video/avc", video.getString(MediaFormat.KEY_MIME))
        val width = video.getInteger(MediaFormat.KEY_WIDTH)
        val height = video.getInteger(MediaFormat.KEY_HEIGHT)
        assertEquals(0, width % 2)
        assertEquals(0, height % 2)
        assertTrue(maxOf(width, height) in 700..720)
        assertTrue(minOf(width, height) in 395..416)
    }

    @Test fun foregroundServiceRejectsConcurrentStartAndCleansCancelledPartial() {
        val (_, sourceUri) = fixture("sample.mp4")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val before = context.conversionRepository.records.value.map { it.id }.toSet()
            scenario.onActivity {
                val source = SourceInfo(sourceUri.toString(), "sample.mp4")
                ConversionService.start(it, source, ConversionOptions())
                ConversionService.start(it, source, ConversionOptions())
            }
            waitUntil(10_000) { context.conversionRepository.active.value != null }
            scenario.onActivity { ConversionService.cancel(it) }
            waitUntil(15_000) { context.conversionRepository.active.value == null }
            val added = context.conversionRepository.records.value.filter { it.id !in before }
            assertEquals(1, added.size)
            assertEquals(JobStatus.CANCELLED, added.single().status)
            assertFalse(File(context.conversionRepository.temporaryDir, "${added.single().id}.part").exists())
            instrumentation.runOnMainSync { context.conversionRepository.delete(added.single()) }
        }
    }

    private fun waitUntil(timeout: Long, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition()) { check(System.currentTimeMillis() - start < timeout) { "等待任务状态超时" }; Thread.sleep(25) }
    }
}
