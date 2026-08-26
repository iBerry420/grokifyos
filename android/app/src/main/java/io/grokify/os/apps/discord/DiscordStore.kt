package io.grokify.os.apps.discord

import android.content.Context

class DiscordStore(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var tab: String
        get() = prefs.getString(KEY_TAB, "feed")?.trim().orEmpty().ifBlank { "feed" }
        set(v) = prefs.edit().putString(KEY_TAB, v).apply()

    var timeframe: String
        get() = prefs.getString(KEY_TF, "1d")?.trim().orEmpty().ifBlank { "1d" }
        set(v) = prefs.edit().putString(KEY_TF, v).apply()

    var guildId: String
        get() = prefs.getString(KEY_GUILD, "")?.trim().orEmpty()
        set(v) = prefs.edit().putString(KEY_GUILD, v.trim()).apply()

    var botId: Int
        get() = prefs.getInt(KEY_BOT, 0)
        set(v) = prefs.edit().putInt(KEY_BOT, v).apply()

    var userSort: String
        get() = prefs.getString(KEY_USER_SORT, "lastActive")?.trim().orEmpty().ifBlank { "lastActive" }
        set(v) = prefs.edit().putString(KEY_USER_SORT, v).apply()

    var userOrder: String
        get() = prefs.getString(KEY_USER_ORDER, "desc")?.trim().orEmpty().ifBlank { "desc" }
        set(v) = prefs.edit().putString(KEY_USER_ORDER, v).apply()

    var guildFilter: String
        get() = prefs.getString(KEY_GUILD_FILTER, "watched")?.trim().orEmpty().ifBlank { "watched" }
        set(v) = prefs.edit().putString(KEY_GUILD_FILTER, v).apply()

    var guildSort: String
        get() = prefs.getString(KEY_GUILD_SORT, "name")?.trim().orEmpty().ifBlank { "name" }
        set(v) = prefs.edit().putString(KEY_GUILD_SORT, v).apply()

    var guildBotIdsRaw: String
        get() = prefs.getString(KEY_GUILD_BOTS, "")?.trim().orEmpty()
        set(v) = prefs.edit().putString(KEY_GUILD_BOTS, v.trim()).apply()

    var auditAction: String
        get() = prefs.getString(KEY_AUDIT_ACTION, "")?.trim().orEmpty()
        set(v) = prefs.edit().putString(KEY_AUDIT_ACTION, v.trim()).apply()

    var auditTf: String
        get() = prefs.getString(KEY_AUDIT_TF, "1d")?.trim().orEmpty().ifBlank { "1d" }
        set(v) = prefs.edit().putString(KEY_AUDIT_TF, v).apply()

    var mediaKind: String
        get() = prefs.getString(KEY_MEDIA_KIND, "image")?.trim().orEmpty().ifBlank { "image" }
        set(v) = prefs.edit().putString(KEY_MEDIA_KIND, v).apply()

    var mediaGuildId: String
        get() = prefs.getString(KEY_MEDIA_GUILD, "")?.trim().orEmpty()
        set(v) = prefs.edit().putString(KEY_MEDIA_GUILD, v.trim()).apply()

    var mediaOrder: String
        get() = prefs.getString(KEY_MEDIA_ORDER, "desc")?.trim().orEmpty().ifBlank { "desc" }
        set(v) = prefs.edit().putString(KEY_MEDIA_ORDER, if (v.equals("asc", true) || v.equals("oldest", true)) "asc" else "desc").apply()

    var mediaLikedOnly: Boolean
        get() = prefs.getBoolean(KEY_MEDIA_LIKED, false)
        set(v) = prefs.edit().putBoolean(KEY_MEDIA_LIKED, v).apply()

    var mediaHideStale: Boolean
        get() = prefs.getBoolean(KEY_MEDIA_HIDE_STALE, true)
        set(v) = prefs.edit().putBoolean(KEY_MEDIA_HIDE_STALE, v).apply()

    var auditGuildId: String
        get() = prefs.getString(KEY_AUDIT_GUILD, "")?.trim().orEmpty()
        set(v) = prefs.edit().putString(KEY_AUDIT_GUILD, v.trim()).apply()

    var userGuildId: String
        get() = prefs.getString(KEY_USER_GUILD, "")?.trim().orEmpty()
        set(v) = prefs.edit().putString(KEY_USER_GUILD, v.trim()).apply()

    var aiSub: String
        get() = prefs.getString(KEY_AI_SUB, "activity")?.trim().orEmpty().ifBlank { "activity" }
        set(v) = prefs.edit().putString(KEY_AI_SUB, v).apply()

    var aiTf: String
        get() = prefs.getString(KEY_AI_TF, "1d")?.trim().orEmpty().ifBlank { "1d" }
        set(v) = prefs.edit().putString(KEY_AI_TF, v).apply()

    var aiLimit: Int
        get() = prefs.getInt(KEY_AI_LIMIT, 50).let { if (it in 1..1000) it else 50 }
        set(v) = prefs.edit().putInt(KEY_AI_LIMIT, v.coerceIn(1, 1000)).apply()

    var aiSkipTagged: Boolean
        get() = prefs.getBoolean(KEY_AI_SKIP, true)
        set(v) = prefs.edit().putBoolean(KEY_AI_SKIP, v).apply()

    var aiSort: String
        get() = prefs.getString(KEY_AI_SORT, "newest")?.trim().orEmpty().ifBlank { "newest" }
        set(v) = prefs.edit().putString(KEY_AI_SORT, v).apply()

    var aiPrompt: String
        get() = prefs.getString(KEY_AI_PROMPT, "")?.trim().orEmpty()
        set(v) = prefs.edit().putString(KEY_AI_PROMPT, v.trim().take(4000)).apply()

    var aiVoiceId: String
        get() = prefs.getString(KEY_AI_VOICE, "eve")?.trim().orEmpty().ifBlank { "eve" }
        set(v) = prefs.edit().putString(KEY_AI_VOICE, v.trim().ifBlank { "eve" }).apply()

    var aiPreferDeviceTts: Boolean
        get() = prefs.getBoolean(KEY_AI_PREFER_DEVICE, false)
        set(v) = prefs.edit().putBoolean(KEY_AI_PREFER_DEVICE, v).apply()

    companion object {
        private const val PREFS = "discord_inner_app"
        private const val KEY_TAB = "tab"
        private const val KEY_TF = "timeframe"
        private const val KEY_GUILD = "guild_id"
        private const val KEY_BOT = "bot_id"
        private const val KEY_USER_SORT = "user_sort"
        private const val KEY_USER_ORDER = "user_order"
        private const val KEY_GUILD_FILTER = "guild_filter"
        private const val KEY_GUILD_SORT = "guild_sort"
        private const val KEY_GUILD_BOTS = "guild_bot_ids"
        private const val KEY_AUDIT_ACTION = "audit_action"
        private const val KEY_AUDIT_TF = "audit_tf"
        private const val KEY_MEDIA_KIND = "media_kind"
        private const val KEY_MEDIA_GUILD = "media_guild_id"
        private const val KEY_MEDIA_ORDER = "media_order"
        private const val KEY_MEDIA_LIKED = "media_liked_only"
        private const val KEY_MEDIA_HIDE_STALE = "media_hide_stale"
        private const val KEY_AUDIT_GUILD = "audit_guild_id"
        private const val KEY_USER_GUILD = "user_guild_id"
        private const val KEY_AI_SUB = "ai_sub"
        private const val KEY_AI_TF = "ai_tf"
        private const val KEY_AI_LIMIT = "ai_limit"
        private const val KEY_AI_SKIP = "ai_skip_tagged"
        private const val KEY_AI_SORT = "ai_sort"
        private const val KEY_AI_PROMPT = "ai_prompt"
        private const val KEY_AI_VOICE = "ai_voice_id"
        private const val KEY_AI_PREFER_DEVICE = "ai_prefer_device_tts"
    }
}
