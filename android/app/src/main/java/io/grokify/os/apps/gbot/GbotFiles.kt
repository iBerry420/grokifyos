package io.grokify.os.apps.gbot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.webkit.MimeTypeMap
import java.io.ByteArrayOutputStream
import kotlin.math.max

internal data class GbotLocalFile(
    val name: String,
    val bytesBase64: String,
    val size: Int,
)

internal object GbotFiles {
    const val MAX_BYTES = 4 * 1024 * 1024

    fun fromUri(ctx: Context, uri: Uri): GbotLocalFile? {
        val resolver = ctx.contentResolver
        val mime = resolver.getType(uri).orEmpty()
        val name = queryName(ctx, uri).ifBlank { defaultName(mime) }
        val jpeg = if (mime.startsWith("image/") && mime != "image/gif") {
            loadJpeg(ctx, uri)
        } else {
            null
        }
        val bytes = jpeg ?: resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        return GbotLocalFile(
            name = if (jpeg != null) name.replace(Regex("\\.[A-Za-z0-9]+$"), ".jpg") else name,
            bytesBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
            size = bytes.size,
        )
    }

    private fun queryName(ctx: Context, uri: Uri): String {
        val cursor = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return it.getString(idx).orEmpty()
            }
        }
        return uri.lastPathSegment.orEmpty()
    }

    private fun defaultName(mime: String): String {
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.ifBlank { null } ?: "bin"
        return "upload.$ext"
    }

    private fun loadJpeg(ctx: Context, uri: Uri, maxEdge: Int = 1600, quality: Int = 82): ByteArray? {
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
            } ?: return null
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
                out.toByteArray()
            } finally {
                bmp.recycle()
            }
        } catch (_: Exception) {
            null
        }
    }
}
