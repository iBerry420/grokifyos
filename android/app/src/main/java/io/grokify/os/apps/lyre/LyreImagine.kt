package io.grokify.os.apps.lyre

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

enum class LyreImagineMode {
    NEXT_STILL,
    EDIT_STILL,
    GEN_VIDEO,
    EDIT_VIDEO,
}

data class LyreImagineJob(
    val mode: LyreImagineMode,
    val frameId: String? = null,
    val clipId: String? = null,
    val title: String,
)

object LyreImagine {
    val VOICES = listOf(
        "eve", "ara", "leo", "rex", "sal", "carina",
        "helix", "orion", "luna", "iris", "sirius", "atlas",
    )

    fun capVoices(ids: List<String>): List<String> {
        val out = ArrayList<String>()
        for (raw in ids) {
            val id = raw.trim().lowercase()
            if (id.isEmpty() || id !in VOICES || id in out) continue
            out += id
            if (out.size >= 3) break
        }
        return out
    }

    fun capRefs(files: List<File>, max: Int = 3): List<File> {
        return files.filter { it.isFile && it.length() > 0L }.take(max)
    }

    fun tagPrompt(prompt: String, imageCount: Int, voiceCount: Int): String {
        var text = prompt.trim()
        if (imageCount > 0 && !text.contains("<IMAGE_")) {
            val tags = (0 until imageCount).map { "<IMAGE_$it>" }
            val join = if (tags.size == 1) tags[0] else tags.dropLast(1).joinToString(", ") + " and " + tags.last()
            text = "$text Use $join.".trim()
        }
        if (voiceCount > 0 && !text.contains("<AUDIO_")) {
            val tags = (0 until voiceCount).map { "<AUDIO_$it>" }
            val join = if (tags.size == 1) tags[0] else tags.dropLast(1).joinToString(", ") + " and " + tags.last()
            text = "$text Speak with the voice from $join.".trim()
        }
        return text.ifBlank { "Continue this cinematic shot." }
    }

    fun encodeImage(file: File): JSONObject? {
        if (!file.isFile || file.length() <= 0L) return null
        val bytes = file.readBytes()
        if (bytes.isEmpty()) return null
        val mime = when (file.extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }
        return JSONObject()
            .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("mimeType", mime)
    }

    fun stillBody(
        prompt: String,
        source: File?,
        refs: List<File>,
        aspect: String = "16:9",
        boardId: String? = null,
    ): JSONObject {
        val images = JSONArray()
        source?.let { encodeImage(it)?.let { img -> images.put(img) } }
        for (ref in capRefs(refs)) {
            encodeImage(ref)?.let { images.put(it) }
        }
        val body = JSONObject()
            .put("prompt", tagPrompt(prompt, images.length(), 0))
            .put("aspect_ratio", aspect)
            .put("images", images)
        if (!boardId.isNullOrBlank()) body.put("board_id", boardId)
        return body
    }

    fun videoBody(
        prompt: String,
        sourceStill: File?,
        refs: List<File>,
        voices: List<String>,
        duration: Int,
        aspect: String,
        resolution: String,
        mode: String,
        videoKey: String? = null,
        boardId: String? = null,
        frameId: String? = null,
        clipId: String? = null,
    ): JSONObject {
        val images = JSONArray()
        sourceStill?.let { encodeImage(it)?.let { img -> images.put(img) } }
        for (ref in capRefs(refs)) {
            encodeImage(ref)?.let { images.put(it) }
        }
        val voiceIds = capVoices(voices)
        val tagged = tagPrompt(prompt, images.length(), voiceIds.size)
        val body = JSONObject()
            .put("prompt", tagged)
            .put("mode", mode)
            .put("duration", duration.coerceIn(1, 15))
            .put("aspect_ratio", aspect)
            .put("resolution", resolution)
            .put("images", images)
            .put("voice_ids", JSONArray(voiceIds))
        if (!videoKey.isNullOrBlank()) body.put("video_key", videoKey)
        if (!boardId.isNullOrBlank()) body.put("board_id", boardId)
        if (!frameId.isNullOrBlank()) body.put("frame_id", frameId)
        if (!clipId.isNullOrBlank()) body.put("clip_id", clipId)
        return body
    }

    fun dataUri(file: File): String? {
        val encoded = encodeImage(file) ?: return null
        return "data:${encoded.getString("mimeType")};base64,${encoded.getString("data")}"
    }

    fun videoDataUri(file: File, maxBytes: Long = 12L * 1024 * 1024): String? {
        if (!file.isFile || file.length() <= 0L || file.length() > maxBytes) return null
        val mime = when (file.extension.lowercase()) {
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            else -> "video/mp4"
        }
        return "data:$mime;base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    }
}

class LyreImagineClient(
    private val api: LyreApi,
    private val xaiKey: String?,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun generateStill(
        prompt: String,
        source: File?,
        refs: List<File>,
        aspect: String,
        boardId: String = "",
        isOdysseus: Boolean = true,
    ): JSONObject {
        val php = api.imagineStill(LyreImagine.stillBody(prompt, source, refs, aspect, boardId))
        if (php.optBoolean("ok") && php.optString("key").isNotBlank()) return php
        if (xaiKey.isNullOrBlank()) return php.takeIf { it.has("error") } ?: JSONObject().put("ok", false).put("error", "spacexai_key_missing")
        val uris = ArrayList<String>()
        source?.let { LyreImagine.dataUri(it)?.let(uris::add) }
        LyreImagine.capRefs(refs).forEach { LyreImagine.dataUri(it)?.let(uris::add) }
        val tagged = LyreImagine.tagPrompt(prompt, uris.size, 0)
        val payload = JSONObject().put("model", "grok-imagine-image-2.0").put("prompt", tagged).put("n", 1)
        if (aspect.isNotBlank()) payload.put("aspect_ratio", aspect)
        val url = if (uris.isEmpty()) {
            "https://api.x.ai/v1/images/generations"
        } else {
            if (uris.size == 1) {
                payload.put("image", JSONObject().put("url", uris[0]).put("type", "image_url"))
            } else {
                val arr = JSONArray()
                uris.take(3).forEach { arr.put(JSONObject().put("url", it).put("type", "image_url")) }
                payload.put("images", arr)
            }
            "https://api.x.ai/v1/images/edits"
        }
        val res = xaiJson("POST", url, payload)
        if (!res.optBoolean("ok")) return res
        val bytes = imageBytes(res) ?: return JSONObject().put("ok", false).put("error", "empty_image")
        val meKey = LyreStorageKeys.writeKey(boardId, isOdysseus, "stills", LyreEdits.newId("st"), "jpg")
        val key = LyreStorageKeys.normalize(meKey) ?: meKey.removePrefix("me:")
        val put = api.putStorage(key, bytes, "image/jpeg")
        if (!put.optBoolean("ok")) return put
        return JSONObject().put("ok", true).put("status", "done").put("key", key).put("src", meKey)
    }

    fun startVideo(
        prompt: String,
        sourceStill: File?,
        refs: List<File>,
        voices: List<String>,
        duration: Int,
        aspect: String,
        resolution: String,
        mode: String,
        videoKey: String?,
        videoFile: File?,
        boardId: String = "",
        frameId: String? = null,
        clipId: String? = null,
    ): JSONObject {
        val php = api.imagineVideo(
            LyreImagine.videoBody(
                prompt,
                sourceStill,
                refs,
                voices,
                duration,
                aspect,
                resolution,
                mode,
                videoKey,
                boardId,
                frameId,
                clipId,
            ),
        )
        if (php.optBoolean("ok") && (php.optString("request_id").isNotBlank() || php.optString("key").isNotBlank())) {
            return php
        }
        if (xaiKey.isNullOrBlank()) {
            return php.takeIf { it.has("error") }
                ?: JSONObject().put("ok", false).put("error", "spacexai_key_missing")
        }
        val uris = ArrayList<String>()
        sourceStill?.let { LyreImagine.dataUri(it)?.let(uris::add) }
        LyreImagine.capRefs(refs).forEach { LyreImagine.dataUri(it)?.let(uris::add) }
        val voiceIds = LyreImagine.capVoices(voices)
        val tagged = LyreImagine.tagPrompt(prompt, uris.size, voiceIds.size)
        val payload = JSONObject().put("model", "grok-imagine-video-1.5").put("prompt", tagged)
        if (mode == "edit") {
            val vuri = videoFile?.let { LyreImagine.videoDataUri(it) }
            if (vuri != null) {
                payload.put("video", JSONObject().put("url", vuri))
            } else if (uris.isNotEmpty()) {
                payload.put("duration", duration.coerceIn(1, 15))
                payload.put("aspect_ratio", aspect)
                payload.put("resolution", if (voiceIds.isNotEmpty() || uris.size > 1) "720p" else resolution)
                if (voiceIds.isNotEmpty() || uris.size > 1) {
                    payload.put("reference_images", uriArray(uris))
                    if (voiceIds.isNotEmpty()) payload.put("reference_audios", voiceArray(voiceIds))
                } else {
                    payload.put("image", JSONObject().put("url", uris[0]))
                }
            } else {
                return JSONObject().put("ok", false).put("error", "video_required")
            }
        } else {
            payload.put("duration", duration.coerceIn(1, 15))
            payload.put("aspect_ratio", aspect)
            val useRefs = voiceIds.isNotEmpty() || uris.size > 1
            payload.put("resolution", if (useRefs) "720p" else resolution)
            if (useRefs) {
                if (uris.isNotEmpty()) payload.put("reference_images", uriArray(uris))
                if (voiceIds.isNotEmpty()) payload.put("reference_audios", voiceArray(voiceIds))
            } else if (uris.isNotEmpty()) {
                payload.put("image", JSONObject().put("url", uris[0]))
            }
        }
        return xaiJson("POST", "https://api.x.ai/v1/videos/generations", payload)
    }

    fun pollVideo(requestId: String, boardId: String = "", isOdysseus: Boolean = true): JSONObject {
        val php = api.imagineStatus(requestId)
        if (php.optBoolean("ok") && (php.optString("status") == "done" || php.optString("key").isNotBlank())) {
            return php
        }
        if (xaiKey.isNullOrBlank()) return php
        val res = xaiJson("GET", "https://api.x.ai/v1/videos/$requestId", null)
        if (!res.optBoolean("ok")) return res
        val status = res.optString("status").lowercase()
        if (status == "done" || status == "completed" || status == "succeeded") {
            val url = res.optJSONObject("video")?.optString("url").orEmpty().ifBlank { res.optString("url") }
            val bytes = download(url) ?: return JSONObject().put("ok", false).put("error", "empty_video")
            val meKey = LyreStorageKeys.writeKey(boardId, isOdysseus, "videos", LyreEdits.newId("vid"), "mp4")
            val key = LyreStorageKeys.normalize(meKey) ?: meKey.removePrefix("me:")
            val put = api.putStorage(key, bytes, "video/mp4")
            if (!put.optBoolean("ok")) return put
            val dur = res.optJSONObject("video")?.optDouble("duration") ?: res.optDouble("duration", 6.0)
            return JSONObject()
                .put("ok", true)
                .put("status", "done")
                .put("key", key)
                .put("src", meKey)
                .put("duration", dur)
        }
        if (status == "failed" || status == "expired" || status == "error") {
            return JSONObject().put("ok", false).put("status", status).put("error", res.optString("error").ifBlank { status })
        }
        return JSONObject().put("ok", true).put("status", "pending").put("request_id", requestId)
    }

    private fun uriArray(uris: List<String>): JSONArray {
        val arr = JSONArray()
        uris.take(7).forEach { arr.put(JSONObject().put("url", it)) }
        return arr
    }

    private fun voiceArray(ids: List<String>): JSONArray {
        val arr = JSONArray()
        ids.forEach { arr.put(JSONObject().put("voice_id", it)) }
        return arr
    }

    private fun imageBytes(json: JSONObject): ByteArray? {
        val first = json.optJSONArray("data")?.optJSONObject(0)
        val b64 = first?.optString("b64_json").orEmpty().ifBlank { first?.optString("b64").orEmpty() }
        if (b64.isNotBlank()) return Base64.decode(b64, Base64.DEFAULT)
        val url = first?.optString("url").orEmpty().ifBlank { json.optString("url") }
        if (url.startsWith("data:")) {
            val data = url.substringAfter(',')
            return Base64.decode(data, Base64.DEFAULT)
        }
        if (url.isNotBlank()) return download(url)
        return null
    }

    private fun xaiJson(method: String, url: String, body: JSONObject?): JSONObject {
        val b = Request.Builder().url(url).header("Authorization", "Bearer $xaiKey")
        if (method == "POST") {
            b.post((body ?: JSONObject()).toString().toRequestBody(jsonMedia))
        }
        return try {
            http.newCall(b.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(if (text.isBlank()) "{}" else text) }.getOrElse {
                    JSONObject().put("ok", false).put("error", "invalid_json")
                }
                if (!resp.isSuccessful) {
                    val err = json.optJSONObject("error")?.optString("message")
                        ?: json.optString("error").ifBlank { json.optString("message") }
                    json.put("ok", false).put("error", err.ifBlank { "http_${resp.code}" })
                } else if (!json.has("ok")) {
                    json.put("ok", true)
                }
                json
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "xai_failed")
        }
    }

    private fun download(url: String): ByteArray? {
        if (url.isBlank()) return null
        return try {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.bytes()?.takeIf { it.isNotEmpty() }
            }
        } catch (_: Exception) {
            null
        }
    }
}
