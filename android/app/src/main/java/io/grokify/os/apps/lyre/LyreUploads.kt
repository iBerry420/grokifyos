package io.grokify.os.apps.lyre

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

object LyreUploads {
    fun displayName(ctx: Context, uri: Uri): String {
        val cursor = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return it.getString(idx).orEmpty()
            }
        }
        return uri.lastPathSegment.orEmpty()
    }

    fun mime(ctx: Context, uri: Uri): String {
        return ctx.contentResolver.getType(uri).orEmpty()
    }

    fun extension(mime: String, name: String, fallback: String): String {
        val fromName = name.substringAfterLast('.', "").lowercase()
        if (fromName.matches(Regex("[a-z0-9]{1,8}"))) return fromName
        val fromMime = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.lowercase().orEmpty()
        if (fromMime.isNotEmpty()) return fromMime
        return fallback
    }

    fun copy(ctx: Context, uri: Uri, dest: File): Boolean {
        return try {
            dest.parentFile?.mkdirs()
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return false
            dest.isFile && dest.length() > 0L
        } catch (_: Exception) {
            false
        }
    }

    fun copyStillJpeg(ctx: Context, uri: Uri, dest: File, maxEdge: Int = 1920, quality: Int = 86): Boolean {
        return try {
            val resolver = ctx.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            val srcMax = max(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            while (srcMax / sample > maxEdge * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return copy(ctx, uri, dest)
            try {
                var working = bmp
                val maxDim = max(bmp.width, bmp.height)
                if (maxDim > maxEdge) {
                    val scale = maxEdge.toFloat() / maxDim
                    val w = max(1, (bmp.width * scale).toInt())
                    val h = max(1, (bmp.height * scale).toInt())
                    working = Bitmap.createScaledBitmap(bmp, w, h, true)
                }
                val out = ByteArrayOutputStream()
                working.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (working !== bmp) working.recycle()
                dest.parentFile?.mkdirs()
                dest.writeBytes(out.toByteArray())
                dest.isFile && dest.length() > 0L
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
        } catch (_: Exception) {
            copy(ctx, uri, dest)
        }
    }

    fun firstFrameJpeg(file: File, dest: File, maxEdge: Int = 1920, quality: Int = 86): Boolean {
        if (!file.isFile) return false
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            val bmp = r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: r.frameAtTime
                ?: return false
            try {
                writeJpeg(bmp, dest, maxEdge, quality)
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
        } catch (_: Exception) {
            false
        } finally {
            runCatching { r.release() }
        }
    }

    private fun writeJpeg(bmp: Bitmap, dest: File, maxEdge: Int, quality: Int): Boolean {
        var working = bmp
        val maxDim = max(bmp.width, bmp.height)
        if (maxDim > maxEdge) {
            val scale = maxEdge.toFloat() / maxDim
            val w = max(1, (bmp.width * scale).toInt())
            val h = max(1, (bmp.height * scale).toInt())
            working = Bitmap.createScaledBitmap(bmp, w, h, true)
        }
        val out = ByteArrayOutputStream()
        working.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (working !== bmp) working.recycle()
        dest.parentFile?.mkdirs()
        dest.writeBytes(out.toByteArray())
        return dest.isFile && dest.length() > 0L
    }

    fun durationSec(file: File): Double {
        if (!file.isFile) return 6.0
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            (ms / 1000.0).coerceAtLeast(0.1)
        } catch (_: Exception) {
            6.0
        } finally {
            runCatching { r.release() }
        }
    }
}
