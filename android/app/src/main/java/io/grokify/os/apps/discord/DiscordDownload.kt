package io.grokify.os.apps.discord

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

private val discordDownloadClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

internal fun discordSaveMedia(
    context: Context,
    url: String,
    filename: String,
    mime: String,
    headers: Map<String, String>,
): Result<String> {
    if (url.isBlank()) return Result.failure(IllegalArgumentException("No cached file to download"))
    val name = discordSafeDownloadName(filename, mime)
    val type = mime.ifBlank { "application/octet-stream" }
    val bytes = try {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        discordDownloadClient.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}")
            }
            resp.body?.bytes() ?: throw IllegalStateException("empty file")
        }
    } catch (e: Exception) {
        return Result.failure(e)
    }
    return try {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, type)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GrokifyOS")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return Result.failure(IllegalStateException("Couldn't create download"))
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: return Result.failure(IllegalStateException("Couldn't write file"))
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            Result.success("Downloads/GrokifyOS/$name")
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "GrokifyOS")
            if (!dir.exists() && !dir.mkdirs()) {
                return Result.failure(IllegalStateException("Couldn't create Downloads/GrokifyOS"))
            }
            val out = File(dir, name)
            FileOutputStream(out).use { it.write(bytes) }
            Result.success(out.absolutePath)
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}
