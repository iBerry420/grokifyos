package io.grokify.os.apps.lyre

import io.grokify.os.apps.GROK_VOICES
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

data class LyreInlineImage(val data: String, val mimeType: String)

sealed class ImagineStillResult {
    data class Remote(val path: String, val provider: String) : ImagineStillResult()
    data class Local(val file: File, val provider: String) : ImagineStillResult()
    data class Err(val reason: String) : ImagineStillResult()
}

sealed class ImagineVideoResult {
    data class Ready(val file: File, val provider: String) : ImagineVideoResult()
    data class Err(val reason: String) : ImagineVideoResult()
}

/** Phone inlines still bytes; video awaits storage_put. Never ME_API_KEY. */
object LyreImagine {
    const val MAX_IMAGES = 4
    const val MAX_REFS = 3
    const val MAX_VOICES = 3
    const val POLL_MS = 5_000L
    const val POLL_LIMIT_MS = 10L * 60L * 1000L
    const val XAI_EDIT_MAX_SEC = 8.7
    const val XAI_IMAGES = "https://api.x.ai/v1/images/generations"
    const val XAI_VIDEOS = "https://api.x.ai/v1/videos/generations"
    const val XAI_VIDEO = "https://api.x.ai/v1/videos/"
    const val XAI_EDITS = "https://api.x.ai/v1/videos/edits"
    const val IMAGE_MODEL = "grok-imagine-image"
    const val VIDEO_MODEL = "grok-imagine-video-1.5"

    fun grokmeUnavailable(json: JSONObject): Boolean {
        if (json.optBoolean("ok", false)) return false
        val err = json.optString("error")
        return err == "grokme_unavailable" ||
            err == "http_404" ||
            err == "http_405" ||
            err == "http_502" ||
            err == "http_0"
    }

    fun grokmeFailed(json: JSONObject): Boolean = !json.optBoolean("ok", false)

    fun jobDone(status: String): Boolean {
        val s = status.lowercase()
        return s == "done" || s == "completed" || s == "success" || s == "succeeded"
    }

    fun jobFailed(status: String): Boolean {
        val s = status.lowercase()
        return s == "failed" || s == "expired" || s == "error" || s == "cancelled"
    }

    fun requestId(json: JSONObject): String? {
        val id = json.optString("request_id").ifBlank { json.optString("id") }.trim()
        return id.takeIf { it.length in 8..128 }
    }

    fun harvestObjectKey(json: JSONObject): String? {
        fun from(o: JSONObject?): String? {
            if (o == null) return null
            val path = o.optString("path").trim()
            if (path.startsWith("boards/")) return path
            return null
        }
        from(json)?.let { return it }
        from(json.optJSONObject("video"))?.let { return it }
        from(json.optJSONObject("image"))?.let { return it }
        return null
    }

    fun harvestUrl(json: JSONObject): String? {
        fun urlIn(o: JSONObject?): String? {
            if (o == null) return null
            for (k in listOf("url", "content")) {
                val v = o.optString(k).trim()
                if (v.startsWith("http://") || v.startsWith("https://")) return v
            }
            return null
        }
        urlIn(json)?.let { return it }
        urlIn(json.optJSONObject("video"))?.let { return it }
        urlIn(json.optJSONObject("image"))?.let { return it }
        val data = json.optJSONArray("data")
        if (data != null) {
            for (i in 0 until data.length()) {
                urlIn(data.optJSONObject(i))?.let { return it }
            }
        }
        return null
    }

    fun harvestB64(json: JSONObject): String? {
        fun b64(o: JSONObject?): String? {
            if (o == null) return null
            val v = o.optString("b64_json").ifBlank { o.optString("b64") }.trim()
            return v.takeIf { it.isNotEmpty() }
        }
        b64(json)?.let { return it }
        val data = json.optJSONArray("data")
        if (data != null) {
            for (i in 0 until data.length()) {
                b64(data.optJSONObject(i))?.let { return it }
            }
        }
        return null
    }

    fun filterVoices(ids: Iterable<String>): List<String> {
        val allowed = GROK_VOICES.map { it.id.lowercase() }.toSet()
        return ids.map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && it in allowed }
            .distinct()
            .take(MAX_VOICES)
    }

    fun taggedPrompt(prompt: String, imageCount: Int, audioCount: Int): String {
        val sb = StringBuilder(prompt.trim())
        for (i in 0 until imageCount) {
            val tag = "<IMAGE_$i>"
            if (!sb.contains(tag)) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(tag)
            }
        }
        for (i in 0 until audioCount) {
            val tag = "<AUDIO_$i>"
            if (!sb.contains(tag)) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(tag)
            }
        }
        return sb.toString()
    }

    fun coerceDuration(seconds: Int): Int = if (seconds >= 10) 10 else 6

    fun coerceEditDuration(seconds: Int): Double {
        val d = coerceDuration(seconds).toDouble()
        return if (d > XAI_EDIT_MAX_SEC) XAI_EDIT_MAX_SEC else d
    }

    fun coerceAspect(aspect: String): String {
        return when (aspect.trim()) {
            "9:16", "1:1", "4:3", "3:4", "3:2", "2:3", "2:1", "1:2" -> aspect.trim()
            else -> "16:9"
        }
    }

    fun coerceResolution(res: String): String =
        if (res.trim().equals("480p", ignoreCase = true)) "480p" else "720p"

    fun mimeFor(file: File): String {
        return when (file.extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "m4a", "aac" -> "audio/mp4"
            "jpg", "jpeg" -> "image/jpeg"
            else -> "application/octet-stream"
        }
    }

    fun inlineImage(file: File): LyreInlineImage? {
        if (!file.isFile || file.length() <= 0L) return null
        val mime = mimeFor(file)
        if (!mime.startsWith("image/")) return null
        val data = Base64.getEncoder().encodeToString(file.readBytes())
        if (data.isEmpty()) return null
        return LyreInlineImage(data, mime)
    }

    fun dataUrl(file: File): String? {
        if (!file.isFile || file.length() <= 0L) return null
        val mime = mimeFor(file)
        val data = Base64.getEncoder().encodeToString(file.readBytes())
        if (data.isEmpty()) return null
        return "data:$mime;base64,$data"
    }
}

class LyreImagineClient(
    private val api: LyreApi,
    private val xaiKey: () -> String?,
    private val http: OkHttpClient = defaultHttp(),
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun still(
        projectId: String,
        boardId: String,
        frameId: String,
        prompt: String,
        images: List<LyreInlineImage>,
        aspect: String,
        dest: File,
    ): ImagineStillResult {
        val grokme = api.imagineStill(projectId, boardId, frameId, prompt, images)
        if (!LyreImagine.grokmeFailed(grokme)) {
            val path = LyreImagine.harvestObjectKey(grokme)
            if (!path.isNullOrBlank()) return ImagineStillResult.Remote(path, "grokme")
            val url = LyreImagine.harvestUrl(grokme)
            if (url != null && writeUrl(url, dest, emptyMap())) {
                return ImagineStillResult.Local(dest, "grokme")
            }
        }
        return stillXai(prompt, aspect, dest)
    }

    suspend fun video(
        projectId: String,
        boardId: String,
        prompt: String,
        duration: Int,
        aspect: String,
        resolution: String,
        imageKey: String,
        refKeys: List<String>,
        voiceIds: List<String>,
        poster: File?,
        refFiles: List<File>,
        dest: File,
    ): ImagineVideoResult {
        val dur = LyreImagine.coerceDuration(duration)
        val asp = LyreImagine.coerceAspect(aspect)
        val res = LyreImagine.coerceResolution(resolution)
        val voices = LyreImagine.filterVoices(voiceIds)
        if (imageKey.isNotBlank()) {
            val grokme = api.imagineVideo(
                projectId, boardId, prompt, dur, asp, res, imageKey, refKeys, voices,
            )
            if (!LyreImagine.grokmeFailed(grokme)) {
                val rid = LyreImagine.requestId(grokme)
                if (rid != null && pollGrokme(rid, dest)) {
                    return ImagineVideoResult.Ready(dest, "grokme")
                }
            }
        }
        return videoXai(prompt, dur, asp, res, poster, refFiles, voices, dest)
    }

    suspend fun edit(
        projectId: String,
        boardId: String,
        prompt: String,
        duration: Int,
        aspect: String,
        resolution: String,
        videoKey: String,
        imageKey: String?,
        refKeys: List<String>,
        voiceIds: List<String>,
        poster: File?,
        video: File?,
        refFiles: List<File>,
        dest: File,
    ): ImagineVideoResult {
        val dur = LyreImagine.coerceDuration(duration)
        val asp = LyreImagine.coerceAspect(aspect)
        val res = LyreImagine.coerceResolution(resolution)
        val voices = LyreImagine.filterVoices(voiceIds)
        val grokme = api.imagineEdit(
            projectId, boardId, prompt, dur, asp, res, videoKey, imageKey, refKeys, voices,
        )
        if (!LyreImagine.grokmeFailed(grokme)) {
            val rid = LyreImagine.requestId(grokme)
            if (rid != null && pollGrokme(rid, dest)) {
                return ImagineVideoResult.Ready(dest, "grokme")
            }
        }
        val xai = editXai(prompt, dur, asp, res, poster, video, voices, dest)
        if (xai is ImagineVideoResult.Ready) return xai
        val fallbackOk = xai is ImagineVideoResult.Err && xai.reason == EDIT_ROUTE_MISSING
        if (!fallbackOk) return ImagineVideoResult.Err("edit_unavailable")
        val fallback = video(
            projectId, boardId, prompt, dur, asp, res,
            imageKey.orEmpty(), refKeys, voices, poster, refFiles, dest,
        )
        return if (fallback is ImagineVideoResult.Ready) {
            fallback
        } else {
            ImagineVideoResult.Err("edit_unavailable")
        }
    }

    private suspend fun pollGrokme(requestId: String, dest: File): Boolean {
        val deadline = System.currentTimeMillis() + LyreImagine.POLL_LIMIT_MS
        while (System.currentTimeMillis() <= deadline) {
            val st = api.imagineStatus(requestId)
            val status = st.optString("status")
            if (LyreImagine.jobFailed(status)) return false
            if (!LyreImagine.grokmeFailed(st)) {
                if (LyreImagine.jobDone(status) || status.isEmpty() && LyreImagine.harvestUrl(st) != null) {
                    return materializeVideo(st, dest)
                }
            }
            delay(LyreImagine.POLL_MS)
        }
        return false
    }

    private fun materializeVideo(json: JSONObject, dest: File): Boolean {
        val key = LyreImagine.harvestObjectKey(json)
        if (!key.isNullOrBlank()) {
            return api.getStorage(key).use { writeResponse(it, dest) }
        }
        val url = LyreImagine.harvestUrl(json) ?: return false
        return writeUrl(url, dest, emptyMap())
    }

    private fun stillXai(prompt: String, aspect: String, dest: File): ImagineStillResult {
        val key = xaiKey()?.trim().orEmpty()
        if (key.isEmpty()) return ImagineStillResult.Err("spacexai_key")
        val body = JSONObject()
            .put("model", LyreImagine.IMAGE_MODEL)
            .put("prompt", prompt)
            .put("aspect_ratio", LyreImagine.coerceAspect(aspect))
            .put("n", 1)
        val json = xaiPost(LyreImagine.XAI_IMAGES, key, body) ?: return ImagineStillResult.Err("spacexai_http")
        if (!json.optBoolean("ok", true) && json.has("error") && json.optInt("http_status", 0) >= 400) {
            return ImagineStillResult.Err("spacexai_http")
        }
        val b64 = LyreImagine.harvestB64(json)
        if (b64 != null) {
            return if (writeB64(b64, dest)) ImagineStillResult.Local(dest, "spacexai")
            else ImagineStillResult.Err("spacexai_download")
        }
        val url = LyreImagine.harvestUrl(json) ?: return ImagineStillResult.Err("spacexai_empty")
        return if (writeUrl(url, dest, mapOf("Authorization" to "Bearer $key"))) {
            ImagineStillResult.Local(dest, "spacexai")
        } else {
            ImagineStillResult.Err("spacexai_download")
        }
    }

    private suspend fun videoXai(
        prompt: String,
        duration: Int,
        aspect: String,
        resolution: String,
        poster: File?,
        refFiles: List<File>,
        voices: List<String>,
        dest: File,
    ): ImagineVideoResult {
        val key = xaiKey()?.trim().orEmpty()
        if (key.isEmpty()) return ImagineVideoResult.Err("spacexai_key")
        val posterUrl = poster?.let { LyreImagine.dataUrl(it) }
        val refs = refFiles.mapNotNull { LyreImagine.dataUrl(it) }.take(LyreImagine.MAX_REFS)
        val imageCount = (if (posterUrl != null) 1 else 0) + refs.size
        val body = JSONObject()
            .put("model", LyreImagine.VIDEO_MODEL)
            .put("prompt", LyreImagine.taggedPrompt(prompt, imageCount, voices.size))
            .put("duration", duration)
            .put("aspect_ratio", aspect)
            .put("resolution", resolution)
        if (posterUrl != null) {
            body.put("image", JSONObject().put("url", posterUrl))
        }
        if (refs.isNotEmpty()) {
            val arr = JSONArray()
            for (u in refs) arr.put(JSONObject().put("url", u))
            body.put("reference_images", arr)
        }
        if (voices.isNotEmpty()) {
            val arr = JSONArray()
            for (id in voices) arr.put(JSONObject().put("voice_id", id))
            body.put("reference_audios", arr)
        }
        val json = xaiPost(LyreImagine.XAI_VIDEOS, key, body) ?: return ImagineVideoResult.Err("spacexai_http")
        val rid = LyreImagine.requestId(json) ?: return ImagineVideoResult.Err("spacexai_http")
        return if (pollXai(rid, key, dest)) ImagineVideoResult.Ready(dest, "spacexai")
        else ImagineVideoResult.Err("spacexai_timeout")
    }

    private suspend fun editXai(
        prompt: String,
        duration: Int,
        aspect: String,
        resolution: String,
        poster: File?,
        video: File?,
        voices: List<String>,
        dest: File,
    ): ImagineVideoResult {
        val key = xaiKey()?.trim().orEmpty()
        if (key.isEmpty()) return ImagineVideoResult.Err("spacexai_key")
        val videoUrl = video?.let { LyreImagine.dataUrl(it) }
            ?: return ImagineVideoResult.Err("edit_unavailable")
        val posterUrl = poster?.let { LyreImagine.dataUrl(it) }
        val body = JSONObject()
            .put("model", LyreImagine.VIDEO_MODEL)
            .put("prompt", LyreImagine.taggedPrompt(prompt, if (posterUrl != null) 1 else 0, voices.size))
            .put("duration", LyreImagine.coerceEditDuration(duration))
            .put("aspect_ratio", aspect)
            .put("resolution", resolution)
            .put("video", JSONObject().put("url", videoUrl))
        if (posterUrl != null) {
            body.put("image", JSONObject().put("url", posterUrl))
        }
        if (voices.isNotEmpty()) {
            val arr = JSONArray()
            for (id in voices) arr.put(JSONObject().put("voice_id", id))
            body.put("reference_audios", arr)
        }
        val json = xaiPost(LyreImagine.XAI_EDITS, key, body) ?: return ImagineVideoResult.Err("edit_unavailable")
        val httpStatus = json.optInt("http_status", 0)
        if (httpStatus == 404 || httpStatus == 405) return ImagineVideoResult.Err(EDIT_ROUTE_MISSING)
        val rid = LyreImagine.requestId(json) ?: return ImagineVideoResult.Err("edit_unavailable")
        return if (pollXai(rid, key, dest)) ImagineVideoResult.Ready(dest, "spacexai")
        else ImagineVideoResult.Err("edit_unavailable")
    }

    private suspend fun pollXai(requestId: String, key: String, dest: File): Boolean {
        val deadline = System.currentTimeMillis() + LyreImagine.POLL_LIMIT_MS
        val headers = mapOf("Authorization" to "Bearer $key")
        while (System.currentTimeMillis() <= deadline) {
            val json = xaiGet(LyreImagine.XAI_VIDEO + requestId, key) ?: return false
            val httpStatus = json.optInt("http_status", 0)
            if (httpStatus == 404 || httpStatus == 405) return false
            val job = json.optString("status")
            if (LyreImagine.jobFailed(job)) return false
            if (LyreImagine.jobDone(job)) {
                val url = LyreImagine.harvestUrl(json) ?: return false
                return writeUrl(url, dest, headers)
            }
            delay(LyreImagine.POLL_MS)
        }
        return false
    }

    private fun xaiPost(url: String, key: String, body: JSONObject): JSONObject? {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(if (text.isBlank()) "{}" else text) }
                    .getOrElse { JSONObject() }
                json.put("http_status", resp.code)
                if (!resp.isSuccessful) {
                    json.put("ok", false)
                    if (!json.has("error")) json.put("error", "http_${resp.code}")
                }
                json
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun xaiGet(url: String, key: String): JSONObject? {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .get()
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(if (text.isBlank()) "{}" else text) }
                    .getOrElse { JSONObject() }
                json.put("http_status", resp.code)
                if (!resp.isSuccessful) {
                    json.put("ok", false)
                    if (!json.has("error")) json.put("error", "http_${resp.code}")
                }
                json
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeB64(b64: String, dest: File): Boolean {
        return try {
            val bytes = Base64.getDecoder().decode(b64)
            if (bytes.isEmpty()) return false
            dest.parentFile?.mkdirs()
            val part = File(dest.path + ".part")
            part.writeBytes(bytes)
            if (part.length() != bytes.size.toLong()) {
                part.delete()
                return false
            }
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
            dest.isFile && dest.length() == bytes.size.toLong()
        } catch (_: Exception) {
            false
        }
    }

    private fun writeUrl(url: String, dest: File, headers: Map<String, String>): Boolean {
        if (url.isBlank()) return false
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        return try {
            http.newCall(b.build()).execute().use { writeResponse(it, dest) }
        } catch (_: Exception) {
            dest.delete()
            File(dest.path + ".part").delete()
            false
        }
    }

    /** Truncated body is not a hit. */
    private fun writeResponse(resp: Response, dest: File): Boolean {
        if (!resp.isSuccessful) {
            File(dest.path + ".part").delete()
            return false
        }
        val body = resp.body ?: return false
        val expected = resp.header("Content-Length")?.trim()?.toLongOrNull()?.takeIf { it >= 0L }
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        return try {
            val copied = part.outputStream().use { out -> body.byteStream().copyTo(out) }
            if (copied <= 0L || part.length() != copied || (expected != null && copied != expected)) {
                part.delete()
                return false
            }
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
            dest.isFile && dest.length() > 0L && (expected == null || dest.length() == expected)
        } catch (_: Exception) {
            part.delete()
            dest.delete()
            false
        }
    }

    companion object {
        private const val EDIT_ROUTE_MISSING = "edit_route_missing"

        private fun defaultHttp(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }
}
