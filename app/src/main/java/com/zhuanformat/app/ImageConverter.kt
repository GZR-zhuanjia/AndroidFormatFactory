package com.zhuanformat.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

object ImageConverter {
    // Retain headroom for EXIF rotation, resizing and JPEG flattening on ordinary phones.
    private const val MAX_DECODED_PIXELS = 24_000_000L

    suspend fun convert(context: Context, uri: Uri, options: ConversionOptions, output: File, progress: (Int?, String) -> Unit) = withContext(Dispatchers.IO) {
        progress(5, "读取图片")
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val input = resolver.openInputStream(uri) ?: throw IOException("无法读取图片")
        input.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片损坏或设备不支持此格式" }
        val orientation = resolver.openInputStream(uri)?.use { input ->
            runCatching { ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val target = fitDimensions(bounds.outWidth, bounds.outHeight, options.imageMaxEdge)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target.first && bounds.outHeight / (sample * 2) >= target.second) sample *= 2
        require(bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) <= MAX_DECODED_PIXELS) {
            "所选尺寸解码超过 2400 万像素的内存安全上限，请选择较小的最长边（如 4096px 或 2560px）后重试；不会自动缩小原图"
        }
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        var bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
            ?: throw IOException("图片解码失败")
        try {
            coroutineContext.ensureActive()
            progress(30, "调整方向与尺寸")
            val matrix = orientationMatrix(orientation)
            if (!matrix.isIdentity) {
                val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (oriented !== bitmap) { bitmap.recycle(); bitmap = oriented }
            }
            val (width, height) = fitDimensions(bitmap.width, bitmap.height, options.imageMaxEdge)
            if (width != bitmap.width || height != bitmap.height) {
                val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)
                if (resized !== bitmap) { bitmap.recycle(); bitmap = resized }
            }
            if (options.format == OutputFormat.JPG && bitmap.hasAlpha()) {
                val opaque = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                Canvas(opaque).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
                bitmap.recycle(); bitmap = opaque
            }
            coroutineContext.ensureActive()
            progress(65, "编码 ${options.format.label}")
            val format = when (options.format) {
                OutputFormat.JPG -> Bitmap.CompressFormat.JPEG
                OutputFormat.PNG -> Bitmap.CompressFormat.PNG
                OutputFormat.WEBP -> if (Build.VERSION.SDK_INT >= 30) {
                    if (options.imageLossless) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    require(!options.imageLossless) { "WebP 无损输出需要 Android 11 或更新版本，请选择 PNG 或 WebP 有损编码" }
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                else -> error("不支持的图片输出格式")
            }
            output.outputStream().buffered().use {
                check(bitmap.compress(format, options.imageQuality, it)) { "图片编码失败" }
            }
            coroutineContext.ensureActive()
            progress(95, "整理输出文件")
        } finally {
            bitmap.recycle()
        }
    }

    internal fun orientationMatrix(orientation: Int) = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }
}
