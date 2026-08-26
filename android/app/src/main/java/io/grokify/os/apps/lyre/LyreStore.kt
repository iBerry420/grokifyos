package io.grokify.os.apps.lyre

import android.content.Context

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

    companion object {
        private const val PREFS = "lyre_prefs"
        private const val KEY_PROJECT = "project_id"
        private const val KEY_CHIP = "chip"
        private const val KEY_MUSE = "muse_open"
        private const val KEY_LOOP = "loop_clip"
        private const val KEY_PLAYHEAD = "playhead"
    }
}
