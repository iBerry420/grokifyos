package io.grokify.os.apps.lyre

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class LyreStore(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var projectId: String
        get() = prefs.getString(KEY_PROJECT, "")?.trim().orEmpty()
        set(v) = prefs.edit().putString(KEY_PROJECT, v.trim()).apply()

    var chip: String
        get() = prefs.getString(KEY_CHIP, "scenes")?.trim().orEmpty().ifBlank { "scenes" }
        set(v) = prefs.edit().putString(KEY_CHIP, v.trim().ifBlank { "scenes" }).apply()

    var museOpen: Boolean
        get() = prefs.getBoolean(KEY_MUSE, false)
        set(v) = prefs.edit().putBoolean(KEY_MUSE, v).apply()

    var loopClip: Boolean
        get() = prefs.getBoolean(KEY_LOOP, false)
        set(v) = prefs.edit().putBoolean(KEY_LOOP, v).apply()

    var playhead: Float
        get() = prefs.getFloat(KEY_PLAYHEAD, 0f)
        set(v) = prefs.edit().putFloat(KEY_PLAYHEAD, v).apply()

    fun museMessages(boardId: String): List<LyreMuseMessage> {
        val raw = prefs.getString(museKey(boardId), null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<LyreMuseMessage>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val text = o.optString("text")
                if (text.isEmpty()) continue
                out.add(LyreMuseMessage(role = o.optString("role").ifBlank { "muse" }, text = text))
            }
            if (out.size <= LyreMuse.TRANSCRIPT_CAP) out else out.takeLast(LyreMuse.TRANSCRIPT_CAP)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun setMuseMessages(boardId: String, messages: List<LyreMuseMessage>) {
        val cap = if (messages.size <= LyreMuse.TRANSCRIPT_CAP) {
            messages
        } else {
            messages.takeLast(LyreMuse.TRANSCRIPT_CAP)
        }
        val arr = JSONArray()
        cap.forEach { msg ->
            arr.put(JSONObject().put("role", msg.role).put("text", msg.text))
        }
        prefs.edit().putString(museKey(boardId), arr.toString()).apply()
    }

    private fun museKey(boardId: String): String {
        val safe = boardId.replace(UNSAFE, "_").ifBlank { "_" }
        return KEY_MUSE_MSGS + safe
    }

    companion object {
        private const val PREFS = "lyre_prefs"
        private const val KEY_PROJECT = "project_id"
        private const val KEY_CHIP = "chip"
        private const val KEY_MUSE = "muse_open"
        private const val KEY_LOOP = "loop_clip"
        private const val KEY_PLAYHEAD = "playhead"
        private const val KEY_MUSE_MSGS = "muse_messages_"
        private val UNSAFE = Regex("[^A-Za-z0-9._-]")
    }
}
