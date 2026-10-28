package com.zhuanformat.app

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

object MediaFiles {
    suspend fun inspect(context: Context, uri: Uri, kind: MediaKind): SourceInfo = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "请通过系统文件选择器选择文件" }
        var name = "未命名文件"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameColumn >= 0) name = cursor.getString(nameColumn) ?: name
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
            }
        }
        require(size != 0L) { "所选文件是空文件" }
        if (kind == MediaKind.IMAGE) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("无法读取所选图片")
            input.use { BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "设备无法解码这张图片，请选择 JPG、PNG、WebP 等常见图片" }
            val orientation = context.contentResolver.openInputStream(uri)?.use {
                runCatching { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            }
            val swap = orientation in listOf(ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE)
            return@withContext SourceInfo(uri.toString(), name, size, width = if (swap) bounds.outHeight else bounds.outWidth, height = if (swap) bounds.outWidth else bounds.outHeight)
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            if (kind == MediaKind.VIDEO) require(hasVideo) { "所选文件不包含可识别的视频轨道" }
            if (kind == MediaKind.AUDIO) require(hasAudio) { "所选文件不包含可识别的音频轨道" }
            val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val swapAxes = rotation % 180 != 0
            SourceInfo(
                uri.toString(), name, size,
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
                width = if (swapAxes) rawHeight else rawWidth,
                height = if (swapAxes) rawWidth else rawHeight
            )
        } finally {
            retriever.release()
        }
    }
}
