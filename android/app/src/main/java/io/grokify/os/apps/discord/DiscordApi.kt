package io.grokify.os.apps.discord

import io.grokify.os.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class DiscordApi(
    private val tokenProvider: () -> String?,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val slowClient = client.newBuilder()
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun health(): JSONObject = get("health")

    fun bots(): JSONObject = get("bots")

    fun guilds(
        watched: Boolean? = null,
        search: String = "",
        sort: String = "name",
        botIds: Collection<Int> = emptyList(),
    ): JSONObject {
        val extra = buildString {
            if (watched != null) append("&watched=").append(watched)
            if (search.isNotBlank()) append("&search=").append(enc(search))
            if (sort.isNotBlank()) append("&sort=").append(enc(sort))
            val ids = botIds.filter { it > 0 }.distinct().sorted()
            if (ids.isNotEmpty()) append("&botId=").append(ids.joinToString(","))
        }
        return get("guilds$extra")
    }

    fun channels(
        guildId: String,
        botId: Int? = null,
        kind: String = "channels",
        search: String = "",
        limit: Int = 200,
        offset: Int = 0,
    ): JSONObject {
        val extra = buildString {
            append("&guildId=").append(enc(guildId))
            if (botId != null && botId > 0) append("&botId=").append(botId)
            if (kind.isNotBlank()) append("&kind=").append(enc(kind))
            if (search.isNotBlank()) append("&search=").append(enc(search))
            append("&limit=").append(limit.coerceIn(1, 200))
            append("&offset=").append(offset.coerceAtLeast(0))
        }
        return get("channels$extra")
    }

    fun roles(guildId: String, botId: Int? = null): JSONObject {
        val extra = buildString {
            append("&guildId=").append(enc(guildId))
            if (botId != null && botId > 0) append("&botId=").append(botId)
        }
        return get("roles$extra")
    }

    fun messages(
        timeframe: String = "1d",
        guildId: String = "",
        channelId: String = "",
        botId: Int? = null,
        search: String = "",
        page: Int = 1,
        limit: Int = 40,
        userId: String = "",
        aroundMessageId: String = "",
        aroundAt: String = "",
        beforeAt: String = "",
    ): JSONObject {
        val extra = buildString {
            append("&timeframe=").append(enc(timeframe))
            append("&page=").append(page.coerceAtLeast(1))
            append("&limit=").append(limit.coerceIn(10, 100))
            if (guildId.isNotBlank()) append("&guildId=").append(enc(guildId))
            if (channelId.isNotBlank()) append("&channelId=").append(enc(channelId))
            if (botId != null && botId > 0) append("&botId=").append(botId)
            if (search.isNotBlank()) append("&search=").append(enc(search))
            if (userId.isNotBlank()) append("&userId=").append(enc(userId))
            if (aroundMessageId.isNotBlank()) append("&aroundMessageId=").append(enc(aroundMessageId))
            if (aroundAt.isNotBlank()) append("&aroundAt=").append(enc(aroundAt))
            if (beforeAt.isNotBlank()) append("&beforeAt=").append(enc(beforeAt))
        }
        return get("messages$extra")
    }

    fun user(id: String, byDiscordId: Boolean = false): JSONObject {
        val extra = buildString {
            append("&id=").append(enc(id))
            if (byDiscordId) append("&byDiscordId=true")
        }
        return get("user$extra")
    }

    fun users(
        search: String = "",
        offset: Int = 0,
        limit: Int = 40,
        sort: String = "lastActive",
        order: String = "desc",
        guildId: String = "",
    ): JSONObject {
        val extra = buildString {
            append("&limit=").append(limit.coerceIn(10, 100))
            append("&offset=").append(offset.coerceAtLeast(0))
            append("&sort=").append(enc(sort.ifBlank { "lastActive" }))
            append("&order=").append(enc(order.ifBlank { "desc" }))
            if (search.isNotBlank()) append("&search=").append(enc(search))
            if (guildId.isNotBlank()) append("&guildId=").append(enc(guildId))
        }
        return get("users$extra")
    }

    fun attachments(
        guildId: String = "",
        offset: Int = 0,
        limit: Int = 40,
        contentType: String = "image",
        sort: String = "newest",
        liked: Boolean = false,
        mode: String = "",
        excludeIds: Collection<Int> = emptyList(),
        userId: String = "",
        discordUserId: String = "",
        playableOnly: Boolean = false,
    ): JSONObject {
        val extra = buildString {
            append("&limit=").append(limit.coerceIn(1, 100))
            append("&offset=").append(offset.coerceAtLeast(0))
            val order = if (sort.equals("oldest", true) || sort.equals("asc", true)) "oldest" else "newest"
            append("&sort=").append(enc(order))
            if (contentType.isNotBlank()) append("&contentType=").append(enc(contentType))
            if (guildId.isNotBlank()) append("&guildId=").append(enc(guildId))
            if (liked) append("&liked=1")
            if (playableOnly) append("&playable=1")
            if (mode.isNotBlank()) append("&mode=").append(enc(mode))
            val exclude = excludeIds.filter { it > 0 }.distinct()
            if (exclude.isNotEmpty()) append("&exclude=").append(exclude.joinToString(","))
            val did = discordUserId.trim()
            if (did.isNotBlank()) append("&discordUserId=").append(enc(did))
            else if (userId.isNotBlank()) append("&userId=").append(enc(userId))
        }
        return get("attachments$extra")
    }

    fun mediaLike(attachmentId: Int, liked: Boolean): JSONObject =
        post(
            JSONObject()
                .put("action", "media_like")
                .put("attachmentId", attachmentId)
                .put("liked", liked),
        )

    fun mediaFollow(discordUserId: String, following: Boolean): JSONObject =
        post(
            JSONObject()
                .put("action", "media_follow")
                .put("discordUserId", discordUserId)
                .put("following", following),
        )

    fun mediaPlaylistCursor(guildId: String, cursor: Int, attachmentId: Int): JSONObject =
        post(
            JSONObject()
                .put("action", "media_playlist_cursor")
                .put("guildId", guildId)
                .put("cursor", cursor.coerceAtLeast(0))
                .put("attachmentId", attachmentId.coerceAtLeast(0)),
        )

    fun rolePickers(botId: Int? = null): JSONObject {
        val extra = if (botId != null && botId > 0) "&botId=$botId" else ""
        return get("role_pickers$extra")
    }

    fun captchas(botId: Int? = null): JSONObject {
        val extra = if (botId != null && botId > 0) "&botId=$botId" else ""
        return get("captchas$extra")
    }

    fun captchaAttempts(id: Int): JSONObject = get("captcha_attempts&id=$id")

    fun emojisLocal(search: String = "", offset: Int = 0, limit: Int = 48): JSONObject {
        val extra = buildString {
            append("&limit=").append(limit.coerceIn(12, 100))
            append("&offset=").append(offset.coerceAtLeast(0))
            if (search.isNotBlank()) append("&search=").append(enc(search))
        }
        return get("emojis_local$extra")
    }

    fun emojisGuild(botId: Int, guildId: String): JSONObject {
        return get("emojis_guild&botId=$botId&guildId=${enc(guildId)}")
    }

    fun audits(
        guildId: String = "",
        action: String = "",
        timeframe: String = "1d",
        sort: String = "desc",
        limit: Int = 40,
        offset: Int = 0,
    ): JSONObject {
        val extra = buildString {
            append("&limit=").append(limit.coerceIn(10, 100))
            append("&offset=").append(offset.coerceAtLeast(0))
            append("&timeframe=").append(enc(timeframe.ifBlank { "1d" }))
            append("&sort=").append(enc(sort.ifBlank { "desc" }))
            if (guildId.isNotBlank()) append("&guildId=").append(enc(guildId))
            if (action.isNotBlank()) append("&eventAction=").append(enc(action))
        }
        return get("audits$extra")
    }

    fun userTags(
        id: String,
        byDiscordId: Boolean = false,
        offset: Int = 0,
        limit: Int = 80,
    ): JSONObject {
        val extra = buildString {
            append("&id=").append(enc(id))
            append("&tagOffset=").append(offset.coerceAtLeast(0))
            append("&tagLimit=").append(limit.coerceIn(10, 200))
            if (byDiscordId) append("&byDiscordId=true")
        }
        return get("user_tags$extra", slow = true)
    }

    fun aiJobs(
        botId: Int? = null,
        status: String = "",
        kind: String = "",
        sort: String = "newest",
        offset: Int = 0,
        limit: Int = 40,
    ): JSONObject {
        val extra = buildString {
            append("&limit=").append(limit.coerceIn(10, 100))
            append("&offset=").append(offset.coerceAtLeast(0))
            append("&sort=").append(enc(sort.ifBlank { "newest" }))
            if (botId != null && botId > 0) append("&botId=").append(botId)
            if (status.isNotBlank()) append("&status=").append(enc(status))
            if (kind.isNotBlank()) append("&kind=").append(enc(kind))
        }
        return get("ai_jobs$extra")
    }

    fun aiJob(id: Int): JSONObject = get("ai_job&id=$id")

    fun aiActivity(
        botId: Int? = null,
        guildId: String = "",
        status: String = "",
        search: String = "",
        sort: String = "newest",
        timeframe: String = "all",
        fromDate: String = "",
        toDate: String = "",
        offset: Int = 0,
        limit: Int = 40,
    ): JSONObject {
        val extra = buildString {
            append("&limit=").append(limit.coerceIn(10, 100))
            append("&offset=").append(offset.coerceAtLeast(0))
            append("&sort=").append(enc(sort.ifBlank { "newest" }))
            append("&timeframe=").append(enc(timeframe.ifBlank { "all" }))
            if (botId != null && botId > 0) append("&botId=").append(botId)
            if (guildId.isNotBlank()) append("&guildId=").append(enc(guildId))
            if (status.isNotBlank()) append("&status=").append(enc(status))
            if (search.isNotBlank()) append("&search=").append(enc(search))
            if (fromDate.isNotBlank()) append("&fromDate=").append(enc(fromDate))
            if (toDate.isNotBlank()) append("&toDate=").append(enc(toDate))
        }
        return get("ai_activity$extra")
    }

    fun aiAnalyzeStart(
        botId: Int,
        guildId: String = "",
        channelId: String = "",
        userId: String = "",
        timeframe: String = "1d",
        fromDate: String = "",
        toDate: String = "",
        limit: Int = 50,
        skipTagged: Boolean = true,
        label: String = "",
        kind: String = "tag",
        prompt: String = "",
        messageId: String = "",
    ): JSONObject {
        val jobKind = if (kind == "analyze") "analyze" else "tag"
        val cap = if (jobKind == "analyze") 250 else 1000
        val pinned = messageId.trim()
        val body = JSONObject()
            .put("action", "ai_analyze_start")
            .put("botId", botId)
            .put("kind", jobKind)
            .put("timeframe", if (pinned.isNotBlank()) "all" else timeframe)
            .put("limit", if (pinned.isNotBlank()) 1 else limit.coerceIn(1, cap))
            .put("skipTagged", if (jobKind == "analyze" || pinned.isNotBlank()) false else skipTagged)
        if (guildId.isNotBlank()) body.put("guildId", guildId)
        if (channelId.isNotBlank()) body.put("channelId", channelId)
        if (userId.isNotBlank()) body.put("userId", userId)
        if (fromDate.isNotBlank() && pinned.isBlank()) body.put("fromDate", fromDate)
        if (toDate.isNotBlank() && pinned.isBlank()) body.put("toDate", toDate)
        if (label.isNotBlank()) body.put("label", label)
        if (pinned.isNotBlank()) body.put("messageId", pinned)
        val focus = prompt.trim().take(4000)
        if (jobKind == "analyze" && focus.isNotEmpty()) body.put("prompt", focus)
        return post(body)
    }

    fun aiAnalyzeTick(jobId: Int): JSONObject =
        post(JSONObject().put("action", "ai_analyze_tick").put("id", jobId), slow = true)

    fun aiAnalyzeCancel(jobId: Int): JSONObject =
        post(JSONObject().put("action", "ai_analyze_cancel").put("id", jobId))

    fun aiSettings(provider: String = "", refresh: Boolean = false): JSONObject {
        val extra = buildString {
            if (provider.isNotBlank()) append("&provider=").append(enc(provider))
            if (refresh) append("&refresh=1")
        }
        return get("ai_settings$extra", slow = true)
    }

    fun aiSettingsSave(
        provider: String,
        model: String,
        reasoningEffort: String,
        apiKey: String = "",
        clearKey: Boolean = false,
    ): JSONObject {
        val body = JSONObject()
            .put("action", "ai_settings_save")
            .put("provider", provider)
            .put("model", model)
            .put("reasoningEffort", reasoningEffort)
        if (clearKey) body.put("clearKey", true)
        val key = apiKey.trim()
        if (!clearKey && key.isNotEmpty()) body.put("apiKey", key)
        return post(body, slow = true)
    }

    fun startBot(id: Int): JSONObject = post(JSONObject().put("action", "start_bot").put("id", id))

    fun stopBot(id: Int): JSONObject = post(JSONObject().put("action", "stop_bot").put("id", id))

    fun deleteBot(id: Int): JSONObject = post(JSONObject().put("action", "delete_bot").put("id", id))

    fun createBot(
        name: String,
        token: String,
        clientType: String,
        respondMentions: Boolean,
        respondReplies: Boolean,
    ): JSONObject {
        return post(
            JSONObject()
                .put("action", "create_bot")
                .put("name", name)
                .put("token", token)
                .put("clientType", clientType)
                .put("respondMentions", respondMentions)
                .put("respondReplies", respondReplies),
        )
    }

    fun updateBot(
        id: Int,
        respondMentions: Boolean? = null,
        respondReplies: Boolean? = null,
        isActive: Boolean? = null,
    ): JSONObject {
        val body = JSONObject().put("action", "update_bot").put("id", id)
        if (respondMentions != null) body.put("respondMentions", respondMentions)
        if (respondReplies != null) body.put("respondReplies", respondReplies)
        if (isActive != null) body.put("isActive", isActive)
        return post(body)
    }

    fun updateGuildSettings(
        guildId: String,
        botId: Int?,
        isWatched: Boolean? = null,
        respondToMentions: Boolean? = null,
        respondToReplies: Boolean? = null,
        semanticTagging: Boolean? = null,
        analyzeFiles: Boolean? = null,
    ): JSONObject {
        val body = JSONObject()
            .put("action", "update_guild_settings")
            .put("guildId", guildId)
        if (botId != null && botId > 0) body.put("botId", botId)
        if (isWatched != null) body.put("isWatched", isWatched)
        if (respondToMentions != null) body.put("respondToMentions", respondToMentions)
        if (respondToReplies != null) body.put("respondToReplies", respondToReplies)
        if (semanticTagging != null) body.put("semanticTagging", semanticTagging)
        if (analyzeFiles != null) body.put("analyzeFiles", analyzeFiles)
        return post(body)
    }

    fun updateChannelSettings(
        guildId: String,
        channelId: String,
        botId: Int?,
        isEnabled: Boolean? = null,
        isMuted: Boolean? = null,
        respondToAll: Boolean? = null,
        channelName: String? = null,
        channelType: Int? = null,
    ): JSONObject {
        val body = JSONObject()
            .put("action", "update_channel_settings")
            .put("guildId", guildId)
            .put("channelId", channelId)
        if (botId != null && botId > 0) body.put("botId", botId)
        if (isEnabled != null) body.put("isEnabled", isEnabled)
        if (isMuted != null) body.put("isMuted", isMuted)
        if (respondToAll != null) body.put("respondToAll", respondToAll)
        if (channelName != null) body.put("channelName", channelName)
        if (channelType != null) body.put("channelType", channelType)
        return post(body)
    }

    fun createRolePicker(
        botId: Int,
        guildId: String,
        channelId: String,
        title: String,
        description: String,
        roles: List<Pair<String, String>>,
        deploy: Boolean,
    ): JSONObject {
        val arr = org.json.JSONArray()
        roles.forEach { (emoji, roleId) ->
            arr.put(JSONObject().put("emoji", emoji).put("roleId", roleId))
        }
        return post(
            JSONObject()
                .put("action", "create_role_picker")
                .put("botId", botId)
                .put("guildId", guildId)
                .put("channelId", channelId)
                .put("embedTitle", title)
                .put("embedDescription", description)
                .put("roles", arr)
                .put("deploy", deploy),
        )
    }

    fun deployRolePicker(id: Int): JSONObject =
        post(JSONObject().put("action", "deploy_role_picker").put("id", id))

    fun deleteRolePicker(id: Int): JSONObject =
        post(JSONObject().put("action", "delete_role_picker").put("id", id))

    fun createCaptcha(
        botId: Int,
        guildId: String,
        channelId: String,
        postRoleId: String,
        postRoleName: String,
        preRoleId: String,
        title: String,
        description: String,
        deploy: Boolean,
    ): JSONObject {
        val body = JSONObject()
            .put("action", "create_captcha")
            .put("botId", botId)
            .put("guildId", guildId)
            .put("channelId", channelId)
            .put("postRoleId", postRoleId)
            .put("postRoleName", postRoleName)
            .put("embedTitle", title)
            .put("embedDescription", description)
            .put("deploy", deploy)
        if (preRoleId.isNotBlank()) body.put("preRoleId", preRoleId)
        return post(body)
    }

    fun deployCaptcha(id: Int): JSONObject =
        post(JSONObject().put("action", "deploy_captcha").put("id", id))

    fun deleteCaptcha(id: Int): JSONObject =
        post(JSONObject().put("action", "delete_captcha").put("id", id))

    fun addEmoji(botId: Int, guildId: String, filename: String): JSONObject {
        return post(
            JSONObject()
                .put("action", "add_emoji")
                .put("botId", botId)
                .put("guildId", guildId)
                .put("filename", filename),
        )
    }

    fun renameEmoji(botId: Int, guildId: String, emojiId: String, name: String): JSONObject {
        return post(
            JSONObject()
                .put("action", "rename_emoji")
                .put("botId", botId)
                .put("guildId", guildId)
                .put("emojiId", emojiId)
                .put("name", name),
        )
    }

    fun deleteEmoji(botId: Int, guildId: String, emojiId: String): JSONObject {
        return post(
            JSONObject()
                .put("action", "delete_emoji")
                .put("botId", botId)
                .put("guildId", guildId)
                .put("emojiId", emojiId),
        )
    }

    fun emojiUrl(filename: String): String {
        return BuildConfig.API_BASE + "/discord.php?action=emoji&name=" + enc(filename)
    }

    fun authHeaders(): Map<String, String> {
        val token = tokenProvider()?.takeIf { it.isNotBlank() } ?: return emptyMap()
        return mapOf("Authorization" to "Bearer $token")
    }

    private fun get(actionQuery: String, slow: Boolean = false): JSONObject {
        val req = auth("/discord.php?action=$actionQuery").get().build()
        return execute(req, slow)
    }

    private fun post(body: JSONObject, slow: Boolean = false): JSONObject {
        val req = auth("/discord.php")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        return execute(req, slow)
    }

    private fun auth(path: String): Request.Builder {
        val b = Request.Builder().url(BuildConfig.API_BASE + path)
        tokenProvider()?.takeIf { it.isNotBlank() }?.let {
            b.header("Authorization", "Bearer $it")
        }
        return b
    }

    private fun execute(req: Request, slow: Boolean = false): JSONObject {
        return try {
            val http = if (slow) slowClient else client
            http.newCall(req).execute().use { resp ->
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
