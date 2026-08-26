package io.grokify.os.apps.lyre

import io.grokify.os.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class LyreApi(private val tokenProvider: () -> String?) {
    private val jsonClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val streamClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.MINUTES)
        .writeTimeout(15, TimeUnit.MINUTES)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun projects(): JSONObject = get("projects")

    fun project(id: String): JSONObject = get("project&id=" + enc(id))

    fun create(name: String): JSONObject =
        post(JSONObject().put("action", "create").put("name", name))

    fun rename(id: String, name: String): JSONObject =
        post(JSONObject().put("action", "rename").put("id", id).put("name", name))

    fun delete(id: String): JSONObject =
        post(JSONObject().put("action", "delete").put("id", id))

    fun board(boardId: String): JSONObject = get("board&board_id=" + enc(boardId))

    fun saveBoard(id: String, data: JSONObject): JSONObject =
        post(JSONObject().put("action", "save_board").put("id", id).put("data", data))

    /** JSON POST; copies compiled mp4 to public/watch or deletes it. */
    fun publish(id: String, visibility: String, compiledKey: String?): JSONObject {
        val body = JSONObject().put("action", "publish").put("id", id).put("visibility", visibility)
        if (!compiledKey.isNullOrBlank()) body.put("compiled_key", compiledKey)
        return post(body)
    }

    /** 200 only; 401/404/405 → false (use proxy). Range GET if HEAD is not allowed. */
    fun publicWatchReachable(url: String): Boolean {
        return try {
            val head = Request.Builder().url(url).head().build()
            jsonClient.newCall(head).execute().use { resp ->
                if (resp.code == 200) return true
                if (resp.code != 405 && resp.code != 501) return false
            }
            val range = Request.Builder()
                .url(url)
                .get()
                .header("Range", "bytes=0-1")
                .build()
            jsonClient.newCall(range).execute().use { it.code == 200 || it.code == 206 }
        } catch (_: Exception) {
            false
        }
    }

    /** Raw 200 body. Caller must close. JSON error body if !isSuccessful. */
    fun getStorage(key: String): Response {
        val encoded = URLEncoder.encode(key, "UTF-8")
        val req = auth("/lyre.php?action=storage_get&key=$encoded").get().build()
        return streamClient.newCall(req).execute()
    }

    /** Raw POST on streamClient; key query-encoded. JSON metadata only on the response. Never gos_json_body / never PUT. */
    fun putStorage(key: String, file: File): JSONObject {
        val encoded = URLEncoder.encode(key, "UTF-8")
        val mime = mimeFor(file)
        val body = file.asRequestBody(mime.toMediaType())
        val req = auth("/lyre.php?action=storage_put&key=$encoded").post(body).build()
        return streamClient.newCall(req).execute().use { parseJsonMetadata(it) }
    }

    private fun get(actionQuery: String): JSONObject {
        val req = auth("/lyre.php?action=$actionQuery").get().build()
        return execute(req)
    }

    private fun post(body: JSONObject): JSONObject {
        val req = auth("/lyre.php")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        return execute(req)
    }

    private fun auth(path: String): Request.Builder {
        val b = Request.Builder().url(BuildConfig.API_BASE + path)
        tokenProvider()?.takeIf { it.isNotBlank() }?.let {
            b.header("Authorization", "Bearer $it")
        }
        return b
    }

    private fun parseJsonMetadata(resp: Response): JSONObject {
        val text = resp.body?.string().orEmpty()
        val json = try {
            JSONObject(if (text.isBlank()) "{}" else text)
        } catch (_: Exception) {
            JSONObject().put("ok", false).put("error", "invalid_json")
        }
        if (!resp.isSuccessful && !json.has("error")) {
            json.put("error", "http_${resp.code}")
        }
        if (!resp.isSuccessful) json.put("ok", false)
        return json
    }

    private fun mimeFor(file: File): String {
        return when (file.extension.lowercase()) {
            "mp4" -> "video/mp4"
            "m4a", "aac" -> "audio/mp4"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
    }

    private fun execute(req: Request): JSONObject {
        return try {
            jsonClient.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = try {
                    JSONObject(if (text.isBlank()) "{}" else text)
                } catch (_: Exception) {
                    JSONObject().put("ok", false).put("error", "invalid_json")
                }
                if (!resp.isSuccessful && !json.has("error")) {
                    json.put("error", "http_${resp.code}")
                }
                if (!resp.isSuccessful) json.put("ok", false)
                json
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "request_failed")
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}

fun lyreWatchProxyUrl(token: String): String =
    BuildConfig.API_BASE.trimEnd('/') + "/lyre-watch.php?token=" + token

fun lyreWatchGrokmeUrl(token: String): String =
    "https://me.grokpot.io/v1/storage/public/watch/$token.mp4"
