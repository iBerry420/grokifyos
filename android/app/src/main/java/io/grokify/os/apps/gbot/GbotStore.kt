package io.grokify.os.apps.gbot

import android.content.Context

class GbotStore(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var selectedAgentId: String
        get() = prefs.getString(KEY_AGENT, "")?.trim().orEmpty()
        set(v) = prefs.edit().putString(KEY_AGENT, v.trim()).apply()

    var watchEnabled: Boolean
        get() = prefs.getBoolean(KEY_WATCH, true)
        set(v) = prefs.edit().putBoolean(KEY_WATCH, v).apply()

    var watchStateJson: String
        get() = prefs.getString(KEY_WATCH_STATE, "")?.trim().orEmpty()
        set(v) = prefs.edit().putString(KEY_WATCH_STATE, v).apply()

    companion object {
        private const val PREFS = "gbot_inner_app"
        private const val KEY_AGENT = "selected_agent_id"
        private const val KEY_WATCH = "watch_enabled"
        private const val KEY_WATCH_STATE = "watch_state_json"
    }
}
