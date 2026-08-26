package io.grokify.os.apps.discord

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class DiscordBot(
    val id: Int,
    val name: String,
    val clientType: String,
    val isActive: Boolean,
    val isRunning: Boolean,
    val respondMentions: Boolean,
    val respondReplies: Boolean,
    val activityType: String,
    val activityText: String,
    val lastOnlineMs: Long,
    val agentName: String,
)

data class DiscordGuildBot(
    val botId: Int,
    val name: String,
    val isWatched: Boolean,
    val respondToMentions: Boolean = false,
    val respondToReplies: Boolean = false,
    val semanticTagging: Boolean = false,
)

data class DiscordGuild(
    val id: Int,
    val guildId: String,
    val name: String,
    val icon: String,
    val botId: Int,
    val isWatched: Boolean,
    val respondToMentions: Boolean,
    val respondToReplies: Boolean,
    val semanticTagging: Boolean,
    val analyzeFiles: Boolean,
    val bots: List<DiscordGuildBot> = emptyList(),
)

data class DiscordChannelBot(
    val botId: Int,
    val name: String,
    val isEnabled: Boolean,
    val isMuted: Boolean,
    val respondToAll: Boolean,
)

data class DiscordChannel(
    val channelId: String,
    val guildId: String,
    val name: String,
    val type: Int,
    val isEnabled: Boolean,
    val isMuted: Boolean,
    val respondToAll: Boolean,
    val bots: List<DiscordChannelBot> = emptyList(),
    val kind: String = "channel",
)

data class DiscordChannelBundle(
    val items: List<DiscordChannel> = emptyList(),
    val kind: String = "channels",
    val guildId: String = "",
    val total: Int = 0,
    val hasMore: Boolean = false,
    val channelCount: Int = 0,
    val threadCount: Int = 0,
    val query: String = "",
)

internal fun discordChannelCacheKey(guildId: String, kind: String): String = "$guildId|$kind"

internal fun Map<String, DiscordChannelBundle>.bundleFor(
    guildId: String,
    kind: String,
): DiscordChannelBundle {
    return this[discordChannelCacheKey(guildId, kind)]
        ?: DiscordChannelBundle(kind = kind, guildId = guildId)
}

internal fun discordChannelLabel(ch: DiscordChannel, kind: String): String {
    val name = ch.name.ifBlank { ch.channelId }
    return if (kind == "threads") name else "# $name"
}

internal fun DiscordChannel.watchedFor(botIds: Set<Int>): Boolean {
    val rows = if (botIds.isEmpty()) bots else bots.filter { it.botId in botIds }
    if (rows.isEmpty()) return isEnabled && !isMuted
    return rows.any { it.isEnabled && !it.isMuted }
}

internal fun DiscordMessage.lazyKey(): String =
    "msg-$id-${messageId.ifBlank { createdAtMs.toString() }}"

internal fun DiscordAttachment.lazyKey(): String =
    "att-$id-${discordAttachmentId.ifBlank { filename }}-$messageId"

internal fun DiscordUserRow.lazyKey(): String =
    "user-$id-${discordId.ifBlank { username }}"

internal fun discordParseBotIdSet(raw: String): Set<Int>? {
    val t = raw.trim()
    if (t.isEmpty()) return null
    if (t == "-") return emptySet()
    return t.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }.toSet()
}

internal fun discordFormatBotIdSet(ids: Set<Int>): String {
    if (ids.isEmpty()) return "-"
    return ids.sorted().joinToString(",")
}

internal const val DISCORD_DISCOGRAM_EXIT_WINDOW_MS = 2_000L

internal const val DISCORD_IMAGE_ZOOM_EPS = 1.05f

/** Pinch or an already-zoomed image owns the pointer; a 1x single-finger swipe does not. */
internal fun discordImageOwnsPointer(pointerCount: Int, scale: Float): Boolean =
    pointerCount >= 2 || scale > DISCORD_IMAGE_ZOOM_EPS

/** Second back inside [windowMs] leaves Discogram; first press (or a stale one) does not. */
internal fun discordDiscogramConfirmExit(
    lastPressMs: Long,
    nowMs: Long,
    windowMs: Long = DISCORD_DISCOGRAM_EXIT_WINDOW_MS,
): Boolean {
    if (lastPressMs <= 0L || nowMs <= lastPressMs) return false
    return nowMs - lastPressMs in 1 until windowMs.coerceAtLeast(2L)
}

internal fun <T> List<T>.discordMergePage(
    next: List<T>,
    reset: Boolean,
    keyOf: (T) -> String,
): List<T> {
    if (reset) {
        if (next.isEmpty()) return emptyList()
        val seen = HashSet<String>(next.size)
        return next.filter { seen.add(keyOf(it)) }
    }
    if (next.isEmpty()) return this
    val seen = HashSet<String>(size + next.size)
    forEach { seen.add(keyOf(it)) }
    val add = next.filter { seen.add(keyOf(it)) }
    return if (add.isEmpty()) this else this + add
}

internal fun discordStableMediaKey(url: String): String {
    if (url.isBlank()) return ""
    val q = url.indexOf('?')
    if (q < 0) return url
    val base = url.substring(0, q)
    val kept = url.substring(q + 1).split('&').filter { part ->
        val key = part.substringBefore('=').lowercase()
        key.isNotBlank() && key != "exp" && key != "sig"
    }
    return if (kept.isEmpty()) base else "$base?${kept.joinToString("&")}"
}

internal fun discordPreferStableUrl(old: String, incoming: String): String {
    if (old.isBlank()) return incoming
    if (incoming.isBlank()) return old
    return if (discordStableMediaKey(old) == discordStableMediaKey(incoming)) old else incoming
}

internal fun List<DiscordAttachment>.discordWithLike(id: Int, liked: Boolean): List<DiscordAttachment> {
    if (id <= 0) return this
    var changed = false
    val next = map {
        if (it.id != id || it.liked == liked) it else {
            changed = true
            it.copy(liked = liked)
        }
    }
    return if (changed) next else this
}

internal fun List<DiscordAttachment>.discordWithFollow(discordId: String, following: Boolean): List<DiscordAttachment> {
    if (discordId.isBlank()) return this
    var changed = false
    val next = map {
        if (it.discordId != discordId || it.following == following) it else {
            changed = true
            it.copy(following = following)
        }
    }
    return if (changed) next else this
}

internal fun DiscordAttachment.withStableMediaFrom(old: DiscordAttachment): DiscordAttachment = copy(
    url = discordPreferStableUrl(old.url, url),
    avatar = discordPreferStableUrl(old.avatar, avatar),
    thumbUrl = discordPreferStableUrl(old.thumbUrl, thumbUrl),
)

internal fun DiscordMessage.withStableMediaFrom(old: DiscordMessage): DiscordMessage {
    val atts = attachments.map { att ->
        val prev = old.attachments.firstOrNull { it.lazyKey() == att.lazyKey() }
        if (prev == null) att else att.withStableMediaFrom(prev)
    }
    return copy(
        avatar = discordPreferStableUrl(old.avatar, avatar),
        attachments = atts,
    )
}

/** Live feed: update tags/content in place, prepend only newer messages. */
internal fun List<DiscordMessage>.discordMergeLive(incoming: List<DiscordMessage>): List<DiscordMessage> {
    if (incoming.isEmpty()) return this
    if (isEmpty()) return incoming
    val byKey = LinkedHashMap<String, DiscordMessage>(size)
    forEach { byKey[it.lazyKey()] = it }
    val newestId = maxOf { it.id }
    val newestMs = maxOf { it.createdAtMs }
    val newOnes = ArrayList<DiscordMessage>()
    var changed = false
    for (msg in incoming) {
        val key = msg.lazyKey()
        val old = byKey[key]
        if (old != null) {
            val next = msg.withStableMediaFrom(old)
            if (next != old) {
                byKey[key] = next
                changed = true
            }
        } else if (msg.id > newestId || msg.createdAtMs > newestMs) {
            newOnes.add(msg)
        }
    }
    if (!changed && newOnes.isEmpty()) return this
    val existing = mapNotNull { byKey[it.lazyKey()] }
    return if (newOnes.isEmpty()) existing else newOnes + existing
}

data class DiscordRole(
    val id: String,
    val name: String,
    val color: Int = 0,
)

data class DiscordUserRow(
    val id: Int,
    val discordId: String,
    val username: String,
    val displayName: String,
    val avatar: String,
    val level: Int,
    val messageCount: Int,
    val lastActiveMs: Long,
)

data class DiscordAttachment(
    val id: Int,
    val filename: String,
    val contentType: String,
    val url: String,
    val guildName: String,
    val channelName: String,
    val username: String,
    val kind: String = "file",
    val guildId: String = "",
    val local: Boolean = false,
    val playable: Boolean = true,
    val channelId: String = "",
    val messageId: String = "",
    val discordAttachmentId: String = "",
    val userId: Int = 0,
    val discordId: String = "",
    val displayName: String = "",
    val avatar: String = "",
    val size: Long = 0L,
    val createdAtMs: Long = 0L,
    val liked: Boolean = false,
    val following: Boolean = false,
    val thumbUrl: String = "",
)

internal fun DiscordAttachment.withMessageContext(msg: DiscordMessage): DiscordAttachment = copy(
    guildName = guildName.ifBlank { msg.guildName },
    channelName = channelName.ifBlank { msg.channelName },
    guildId = guildId.ifBlank { msg.guildId },
    channelId = channelId.ifBlank { msg.channelId },
    username = username.ifBlank { msg.username },
    displayName = displayName.ifBlank { msg.displayName },
    discordId = discordId.ifBlank { msg.discordId },
    avatar = avatar.ifBlank { msg.avatar },
    userId = if (userId > 0) userId else msg.userId,
    createdAtMs = if (createdAtMs > 0L) createdAtMs else msg.createdAtMs,
    messageId = messageId.ifBlank { msg.messageId },
)

internal fun DiscordAttachment.withAuditContext(ev: DiscordAudit): DiscordAttachment = copy(
    guildName = guildName.ifBlank { ev.guildName },
    guildId = guildId.ifBlank { ev.guildId },
    channelName = channelName.ifBlank { ev.channelName },
    username = username.ifBlank { ev.target.ifBlank { ev.actor } },
    displayName = displayName.ifBlank { ev.target.ifBlank { ev.actor } },
    discordId = discordId.ifBlank { ev.targetId.ifBlank { ev.actorId } },
    createdAtMs = if (createdAtMs > 0L) createdAtMs else ev.createdAtMs,
)

internal fun discordExtForMime(mime: String): String = when (mime.lowercase().substringBefore(';').trim()) {
    "image/gif" -> "gif"
    "image/jpeg", "image/jpg" -> "jpg"
    "image/png" -> "png"
    "image/webp" -> "webp"
    "video/mp4" -> "mp4"
    "video/webm" -> "webm"
    "audio/mpeg" -> "mp3"
    "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
    "audio/ogg", "application/ogg", "audio/opus", "audio/vorbis" -> "ogg"
    "audio/wav", "audio/x-wav" -> "wav"
    else -> ""
}

internal fun discordSafeDownloadName(raw: String, mime: String = ""): String {
    var name = raw.trim().replace("\u0000", "")
        .substringAfterLast('/')
        .substringAfterLast('\\')
    name = name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('.', '_')
    val ext = discordExtForMime(mime)
    if (name.isBlank() || name == "_" || name == ".") {
        name = when {
            mime.startsWith("video/") -> "video"
            mime.startsWith("audio/") -> "audio"
            mime.contains("gif") -> "image"
            mime.startsWith("image/") -> "photo"
            else -> "file"
        }
        if (ext.isNotBlank()) name = "$name.$ext"
    } else if (ext.isNotBlank() && !name.contains('.')) {
        name = "$name.$ext"
    }
    return name.take(120).ifBlank { "file" }
}

internal fun discordFormatBytes(n: Long): String {
    if (n < 0L) return "—"
    if (n < 1024L) return "$n B"
    if (n < 1024L * 1024L) {
        return String.format(java.util.Locale.US, "%.1f KB", n / 1024.0)
    }
    if (n < 1024L * 1024L * 1024L) {
        return String.format(java.util.Locale.US, "%.1f MB", n / (1024.0 * 1024.0))
    }
    return String.format(java.util.Locale.US, "%.2f GB", n / (1024.0 * 1024.0 * 1024.0))
}

data class DiscordMessage(
    val id: Int,
    val messageId: String,
    val guildId: String,
    val channelId: String,
    val content: String,
    val tags: List<String>,
    val createdAtMs: Long,
    val guildName: String,
    val channelName: String,
    val username: String,
    val displayName: String,
    val avatar: String,
    val level: Int,
    val attachments: List<DiscordAttachment>,
    val userId: Int = 0,
    val discordId: String = "",
)

data class DiscordRolePicker(
    val id: Int,
    val botId: Int,
    val guildId: String,
    val channelId: String,
    val messageId: String,
    val title: String,
    val description: String,
    val rolesJson: String,
    val deployed: Boolean,
)

data class DiscordCaptcha(
    val id: Int,
    val botId: Int,
    val guildId: String,
    val channelId: String,
    val messageId: String,
    val title: String,
    val postRoleName: String,
    val preRoleName: String,
    val deployed: Boolean,
)

data class DiscordEmojiFile(
    val filename: String,
    val name: String,
)

data class DiscordGuildEmoji(
    val id: String,
    val name: String,
    val animated: Boolean,
    val url: String,
)

data class DiscordAudit(
    val id: Long,
    val action: String,
    val guildId: String,
    val guildName: String,
    val actor: String,
    val target: String,
    val actorAvatar: String = "",
    val targetAvatar: String = "",
    val beforeText: String = "",
    val afterText: String = "",
    val beforeAvatar: String = "",
    val afterAvatar: String = "",
    val channelName: String = "",
    val beforeAttachments: List<DiscordAttachment> = emptyList(),
    val afterAttachments: List<DiscordAttachment> = emptyList(),
    val createdAtMs: Long,
    val actorId: String = "",
    val targetId: String = "",
    val targetType: String = "",
)

data class DiscordNameChange(
    val oldValue: String,
    val newValue: String,
    val changedAtMs: Long,
)

data class DiscordProfilePlace(
    val id: String,
    val name: String,
    val guildId: String = "",
    val guildName: String = "",
    val messageCount: Int = 0,
)

data class DiscordTagCount(
    val tag: String,
    val count: Int,
)

data class DiscordTagPage(
    val tags: List<DiscordTagCount>,
    val totalTagCount: Int,
    val uniqueTagCount: Int,
    val hasMore: Boolean,
    val offset: Int,
    val error: String? = null,
)

data class DiscordAiJob(
    val id: Int,
    val botId: Int,
    val kind: String,
    val scope: String,
    val guildId: String,
    val channelId: String,
    val userId: Int,
    val discordUserId: String,
    val timeframe: String,
    val fromDate: String,
    val toDate: String,
    val messageLimit: Int,
    val skipTagged: Boolean,
    val status: String,
    val total: Int,
    val processed: Int,
    val tagged: Int,
    val skipped: Int,
    val failed: Int,
    val lastError: String,
    val label: String,
    val prompt: String,
    val summary: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val provider: String = "",
    val model: String = "",
    val reasoningEffort: String = "",
    val error: String? = null,
)

data class DiscordAiModel(
    val id: String,
    val name: String,
    val provider: String,
    val reasoningEfforts: List<String>,
    val defaultReasoningEffort: String,
)

data class DiscordAiSettings(
    val provider: String = "spacexai",
    val listingProvider: String = "spacexai",
    val model: String = "grok-4.6",
    val reasoningEffort: String = "high",
    val models: List<DiscordAiModel> = emptyList(),
    val keySet: Boolean = false,
    val keyHint: String = "",
    val keySource: String = "",
    val bridgeHealthy: Boolean = false,
    val bridgeError: String = "",
    val defaultModel: String = "grok-4.6",
    val defaultEffort: String = "high",
    val error: String? = null,
)

data class DiscordAiResult(
    val id: Long,
    val jobId: Int,
    val messageId: Int,
    val guildId: String,
    val guildName: String,
    val channelId: String,
    val channelName: String,
    val content: String,
    val tags: List<String>,
    val status: String,
    val error: String,
    val createdAtMs: Long,
    val userId: Int,
    val discordId: String,
    val username: String,
    val displayName: String,
    val avatar: String,
    val roles: List<String> = emptyList(),
)

data class DiscordUserProfile(
    val id: Int,
    val discordId: String,
    val username: String,
    val displayName: String,
    val avatar: String,
    val level: Int,
    val xp: Int,
    val totalXp: Int,
    val activityScore: Double,
    val messageCount: Int,
    val xpToNextLevel: Int,
    val levelProgress: Float,
    val lastActiveMs: Long,
    val guilds: List<DiscordProfilePlace>,
    val channels: List<DiscordProfilePlace>,
    val usernameChanges: List<DiscordNameChange>,
    val displayNameChanges: List<DiscordNameChange>,
    val avatarChanges: List<DiscordNameChange>,
    val topTags: List<DiscordTagCount>,
    val totalTagCount: Int,
    val uniqueTagCount: Int,
    val hasMoreTags: Boolean,
    val error: String? = null,
)

data class DiscordProfileKey(
    val id: String,
    val byDiscordId: Boolean,
)

internal fun discordParseTags(row: JSONObject): List<String> {
    if (row.isNull("tags")) return emptyList()
    val v = row.opt("tags") ?: return emptyList()
    if (v is JSONArray) {
        val out = ArrayList<String>(v.length())
        for (i in 0 until v.length()) {
            val t = v.optString(i).trim()
            if (t.isNotBlank() && t != "null") out.add(t)
        }
        return out
    }
    val raw = v.toString().trim()
    if (raw.isBlank() || raw == "null") return emptyList()
    if (raw.startsWith("[")) {
        return runCatching {
            val arr = JSONArray(raw)
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val t = arr.optString(i).trim()
                if (t.isNotBlank() && t != "null") out.add(t)
            }
            out
        }.getOrDefault(emptyList())
    }
    return raw.split(',').map { it.trim() }.filter { it.isNotBlank() && it != "null" }
}

internal fun discordProfileKey(
    id: Int = 0,
    discordId: String = "",
    targetType: String = "",
): DiscordProfileKey {
    val did = discordId.trim()
    if (did.isNotBlank()) return DiscordProfileKey(did, true)
    if (id > 0) return DiscordProfileKey(id.toString(), false)
    return DiscordProfileKey("", false)
}

data class DiscordPage<T>(
    val items: List<T>,
    val total: Int,
    val hasMore: Boolean,
    val error: String? = null,
    val cursor: Int = 0,
)

internal fun discordAuditPlain(obj: JSONObject?): String {
    if (obj == null) return ""
    for (k in listOf("content", "value", "nickname", "roleName", "name")) {
        if (!obj.has(k) || obj.isNull(k)) continue
        val v = obj.opt(k) ?: continue
        if (v is JSONObject || v is JSONArray) continue
        return v.toString()
    }
    return ""
}

internal fun discordAuditMessageText(
    text: String,
    attachments: List<DiscordAttachment> = emptyList(),
): String {
    if (text.isNotBlank()) return text
    return if (attachments.isEmpty()) "(no text)" else ""
}

internal fun discordAuditLabel(action: String): String = when (action) {
    "avatar_change" -> "Avatar change"
    "username_change" -> "Username change"
    "displayname_change" -> "Display name"
    "nickname_change" -> "Nickname"
    "message_delete" -> "Message deleted"
    "message_edit" -> "Message edited"
    "role_assign" -> "Role assigned"
    "role_remove" -> "Role removed"
    "role_create" -> "Role created"
    "role_update" -> "Role updated"
    "role_delete" -> "Role deleted"
    "channel_create" -> "Channel created"
    "channel_update" -> "Channel updated"
    "channel_delete" -> "Channel deleted"
    else -> action.replace('_', ' ')
}

internal fun discordMediaKind(contentType: String, filename: String): String {
    val c = contentType.lowercase().substringBefore(';').trim()
    val f = filename.lowercase()
    if (c == "image/gif" || f.endsWith(".gif")) return "gif"
    if (c.startsWith("video/") || f.endsWith(".mp4") || f.endsWith(".webm") || f.endsWith(".mov") || f.endsWith(".mkv")) {
        return "video"
    }
    if (
        c.startsWith("audio/") ||
        c == "application/ogg" ||
        f.endsWith(".mp3") ||
        f.endsWith(".ogg") ||
        f.endsWith(".oga") ||
        f.endsWith(".opus") ||
        f.endsWith(".wav") ||
        f.endsWith(".m4a") ||
        f.endsWith(".flac")
    ) {
        return "audio"
    }
    if (c.startsWith("image/") || f.endsWith(".png") || f.endsWith(".jpg") || f.endsWith(".jpeg") || f.endsWith(".webp")) {
        return "image"
    }
    return "file"
}

internal fun discordPlayerMime(contentType: String, filename: String): String {
    val c = contentType.lowercase().substringBefore(';').trim()
    val f = filename.lowercase()
    if (
        c == "audio/ogg" ||
        c == "application/ogg" ||
        c == "audio/opus" ||
        c == "audio/vorbis" ||
        f.endsWith(".ogg") ||
        f.endsWith(".oga") ||
        f.endsWith(".opus")
    ) {
        return "audio/ogg"
    }
    if (c.isNotBlank()) return c
    return when {
        f.endsWith(".mp3") -> "audio/mpeg"
        f.endsWith(".m4a") -> "audio/mp4"
        f.endsWith(".wav") -> "audio/wav"
        f.endsWith(".flac") -> "audio/flac"
        f.endsWith(".mp4") -> "video/mp4"
        f.endsWith(".webm") -> "video/webm"
        f.endsWith(".mov") -> "video/quicktime"
        f.endsWith(".gif") -> "image/gif"
        else -> ""
    }
}

internal fun discordLocalOnlyUrl(raw: String): String {
    val u = raw.trim()
    if (u.isBlank()) return ""
    val host = u.lowercase()
    if (host.contains("cdn.discordapp.com") || host.contains("media.discordapp.net")) {
        return ""
    }
    return u
}

internal fun discordLocalMediaUrl(row: JSONObject): String {
    val candidates = listOf(
        DiscordJson.str(row, "url"),
        DiscordJson.str(row, "proxyUrl"),
    )
    for (u in candidates) {
        val local = discordLocalOnlyUrl(u)
        if (local.isNotBlank()) return local
    }
    return ""
}

object DiscordJson {
    fun obj(raw: JSONObject?, key: String): JSONObject? {
        if (raw == null || raw.isNull(key)) return null
        return raw.optJSONObject(key)
    }

    fun str(raw: JSONObject?, key: String, fallback: String = ""): String {
        if (raw == null || raw.isNull(key)) return fallback
        val v = raw.opt(key) ?: return fallback
        return v.toString().takeIf { it != "null" } ?: fallback
    }

    fun bool(raw: JSONObject?, key: String, fallback: Boolean = false): Boolean {
        if (raw == null || raw.isNull(key)) return fallback
        return when (val v = raw.opt(key)) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v == "1" || v.equals("true", ignoreCase = true)
            else -> fallback
        }
    }

    fun int(raw: JSONObject?, key: String, fallback: Int = 0): Int {
        if (raw == null || raw.isNull(key)) return fallback
        return raw.optInt(key, fallback)
    }

    fun long(raw: JSONObject?, key: String, fallback: Long = 0L): Long {
        if (raw == null || raw.isNull(key)) return fallback
        return raw.optLong(key, fallback)
    }

    fun arr(raw: JSONObject?, key: String): JSONArray {
        if (raw == null || raw.isNull(key)) return JSONArray()
        return raw.optJSONArray(key) ?: JSONArray()
    }

    fun strings(raw: JSONObject?, key: String): List<String> {
        val a = arr(raw, key)
        val out = ArrayList<String>(a.length())
        for (i in 0 until a.length()) {
            val s = a.optString(i).trim()
            if (s.isNotBlank() && s != "null") out.add(s)
        }
        return out
    }

    fun ts(raw: JSONObject?, key: String): Long {
        if (raw == null || raw.isNull(key)) return 0L
        val v = raw.opt(key) ?: return 0L
        if (v is Number) {
            val n = v.toLong()
            return if (n in 1 until 10_000_000_000L) n * 1000L else n
        }
        return parseTs(v.toString())
    }

    fun parseTs(raw: String): Long {
        val s = raw.trim()
        if (s.isBlank() || s == "null") return 0L
        s.toLongOrNull()?.let { n ->
            return if (n in 1 until 10_000_000_000L) n * 1000L else n
        }
        val iso = s.replace(' ', 'T')
        val withZ = if (iso.endsWith("Z") || iso.contains('+')) iso else iso + "Z"
        runCatching { return Instant.parse(withZ).toEpochMilli() }
        runCatching {
            val ldt = LocalDateTime.parse(iso.substringBefore('.').take(19), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            return ldt.toInstant(ZoneOffset.UTC).toEpochMilli()
        }
        return 0L
    }

    fun unwrap(raw: JSONObject?): Any? {
        if (raw == null) return null
        if (!raw.isNull("data")) return raw.opt("data")
        return raw
    }

    fun err(raw: JSONObject?): String? {
        if (raw == null) return "no_response"
        if (raw.optBoolean("ok", true)) {
            val nested = obj(raw, "data")
            val nestedErr = str(nested, "error")
            return nestedErr.ifBlank { null }
        }
        return str(raw, "error").ifBlank { "request_failed" }
    }
}

object DiscordParse {
    fun bots(raw: JSONObject?): List<DiscordBot> {
        val data = DiscordJson.unwrap(raw)
        val arr = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("bots") ?: JSONArray()
            else -> JSONArray()
        }
        val out = ArrayList<DiscordBot>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val id = DiscordJson.int(row, "id")
            if (id <= 0) continue
            val agent = DiscordJson.obj(row, "agent")
            out.add(
                DiscordBot(
                    id = id,
                    name = DiscordJson.str(row, "name").ifBlank { "bot-$id" },
                    clientType = DiscordJson.str(row, "clientType", "discord.js"),
                    isActive = DiscordJson.bool(row, "isActive", true),
                    isRunning = DiscordJson.bool(row, "isRunning"),
                    respondMentions = DiscordJson.bool(row, "respondMentions"),
                    respondReplies = DiscordJson.bool(row, "respondReplies"),
                    activityType = DiscordJson.str(row, "activityType"),
                    activityText = DiscordJson.str(row, "activityText"),
                    lastOnlineMs = DiscordJson.ts(row, "lastOnline"),
                    agentName = DiscordJson.str(agent, "agentName"),
                ),
            )
        }
        return out
    }

    fun guilds(raw: JSONObject?): List<DiscordGuild> {
        val data = DiscordJson.unwrap(raw)
        val arr = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("guilds") ?: JSONArray()
            else -> JSONArray()
        }
        val out = ArrayList<DiscordGuild>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val gid = DiscordJson.str(row, "guildId")
            if (gid.isBlank()) continue
            val botArr = DiscordJson.arr(row, "bots")
            val gBots = ArrayList<DiscordGuildBot>(botArr.length())
            for (j in 0 until botArr.length()) {
                val br = botArr.optJSONObject(j) ?: continue
                val bid = DiscordJson.int(br, "botId")
                if (bid <= 0) continue
                gBots.add(
                    DiscordGuildBot(
                        botId = bid,
                        name = DiscordJson.str(br, "name").ifBlank { "bot-$bid" },
                        isWatched = DiscordJson.bool(br, "isWatched"),
                        respondToMentions = DiscordJson.bool(br, "respondToMentions"),
                        respondToReplies = DiscordJson.bool(br, "respondToReplies"),
                        semanticTagging = DiscordJson.bool(br, "semanticTagging"),
                    ),
                )
            }
            out.add(
                DiscordGuild(
                    id = DiscordJson.int(row, "id"),
                    guildId = gid,
                    name = DiscordJson.str(row, "guildName").ifBlank { gid },
                    icon = DiscordJson.str(row, "guildIcon"),
                    botId = DiscordJson.int(row, "botId"),
                    isWatched = DiscordJson.bool(row, "isWatched") || gBots.any { it.isWatched },
                    respondToMentions = DiscordJson.bool(row, "respondToMentions"),
                    respondToReplies = DiscordJson.bool(row, "respondToReplies"),
                    semanticTagging = DiscordJson.bool(row, "semanticTagging") || gBots.any { it.semanticTagging },
                    analyzeFiles = DiscordJson.bool(row, "analyzeFiles"),
                    bots = gBots,
                ),
            )
        }
        return out
    }

    fun channels(raw: JSONObject?): List<DiscordChannel> {
        val data = DiscordJson.unwrap(raw)
        val arr = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("channels")
                ?: data.optJSONArray("live")
                ?: JSONArray()
            else -> JSONArray()
        }
        val out = ArrayList<DiscordChannel>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val settings = DiscordJson.obj(row, "settings")
            val id = DiscordJson.str(row, "channelId").ifBlank { DiscordJson.str(row, "id") }
            if (id.isBlank()) continue
            val botArr = DiscordJson.arr(row, "bots")
            val chBots = ArrayList<DiscordChannelBot>(botArr.length())
            for (j in 0 until botArr.length()) {
                val br = botArr.optJSONObject(j) ?: continue
                val bid = DiscordJson.int(br, "botId")
                if (bid <= 0) continue
                chBots.add(
                    DiscordChannelBot(
                        botId = bid,
                        name = DiscordJson.str(br, "name").ifBlank { "bot-$bid" },
                        isEnabled = DiscordJson.bool(br, "isEnabled", true),
                        isMuted = DiscordJson.bool(br, "isMuted"),
                        respondToAll = DiscordJson.bool(br, "respondToAll"),
                    ),
                )
            }
            val rowKind = DiscordJson.str(row, "kind").ifBlank {
                val type = DiscordJson.int(row, "channelType", DiscordJson.int(row, "type"))
                if (type == 10 || type == 11 || type == 12) "thread" else "channel"
            }
            out.add(
                DiscordChannel(
                    channelId = id,
                    guildId = DiscordJson.str(row, "guildId"),
                    name = DiscordJson.str(row, "channelName").ifBlank {
                        DiscordJson.str(row, "name").ifBlank { id }
                    },
                    type = DiscordJson.int(row, "channelType", DiscordJson.int(row, "type")),
                    isEnabled = DiscordJson.bool(settings ?: row, "isEnabled", true),
                    isMuted = DiscordJson.bool(settings ?: row, "isMuted"),
                    respondToAll = DiscordJson.bool(settings ?: row, "respondToAll"),
                    bots = chBots,
                    kind = if (rowKind == "thread") "thread" else "channel",
                ),
            )
        }
        return out
    }

    fun channelPage(raw: JSONObject?): DiscordChannelBundle {
        val items = channels(raw)
        val data = DiscordJson.unwrap(raw) as? JSONObject
        val kind = DiscordJson.str(data, "kind", "channels").ifBlank { "channels" }
        val total = DiscordJson.int(data, "total", items.size)
        return DiscordChannelBundle(
            items = items,
            kind = kind,
            guildId = DiscordJson.str(data, "guildId"),
            total = total,
            hasMore = DiscordJson.bool(data, "hasMore"),
            channelCount = DiscordJson.int(data, "channelCount"),
            threadCount = DiscordJson.int(data, "threadCount"),
        )
    }

    fun roles(raw: JSONObject?): List<DiscordRole> {
        val data = DiscordJson.unwrap(raw)
        val arr = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("roles") ?: JSONArray()
            else -> JSONArray()
        }
        val out = ArrayList<DiscordRole>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val id = DiscordJson.str(row, "id")
            if (id.isBlank()) continue
            out.add(
                DiscordRole(
                    id = id,
                    name = DiscordJson.str(row, "name").ifBlank { id },
                    color = DiscordJson.int(row, "color"),
                ),
            )
        }
        return out
    }

    fun messages(raw: JSONObject?): DiscordPage<DiscordMessage> {
        val err = DiscordJson.err(raw)
        val data = DiscordJson.unwrap(raw) as? JSONObject
        val arr = DiscordJson.arr(data, "messages")
        val items = ArrayList<DiscordMessage>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            items.add(message(row))
        }
        val total = DiscordJson.int(data, "total", items.size)
        val page = DiscordJson.int(data, "page", 1)
        val pages = DiscordJson.int(data, "totalPages", if (total == 0) 0 else 1)
        val hasMore = DiscordJson.bool(data, "hasMore", page < pages && items.isNotEmpty())
        return DiscordPage(items, total, hasMore && items.isNotEmpty(), err)
    }

    fun message(row: JSONObject): DiscordMessage {
        val user = DiscordJson.obj(row, "user")
        val attArr = DiscordJson.arr(row, "attachments")
        val atts = ArrayList<DiscordAttachment>(attArr.length())
        for (i in 0 until attArr.length()) {
            val a = attArr.optJSONObject(i) ?: continue
            atts.add(attachment(a, fallbackUser = DiscordJson.str(user, "username")))
        }
        val tags = discordParseTags(row)
        val msg = DiscordMessage(
            id = DiscordJson.int(row, "id"),
            messageId = DiscordJson.str(row, "messageId"),
            guildId = DiscordJson.str(row, "guildId"),
            channelId = DiscordJson.str(row, "channelId"),
            content = DiscordJson.str(row, "content"),
            tags = tags,
            createdAtMs = DiscordJson.ts(row, "createdAt"),
            guildName = DiscordJson.str(row, "guildName"),
            channelName = DiscordJson.str(row, "channelName"),
            username = DiscordJson.str(user, "username"),
            displayName = DiscordJson.str(user, "displayName"),
            avatar = DiscordJson.str(user, "avatar"),
            level = DiscordJson.int(user, "level"),
            attachments = emptyList(),
            userId = DiscordJson.int(user, "id"),
            discordId = DiscordJson.str(user, "discordId"),
        )
        return msg.copy(attachments = atts.map { it.withMessageContext(msg) })
    }

    fun users(raw: JSONObject?): DiscordPage<DiscordUserRow> {
        val err = DiscordJson.err(raw)
        val data = DiscordJson.unwrap(raw) as? JSONObject
        val arr = DiscordJson.arr(data, "users")
        val items = ArrayList<DiscordUserRow>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val id = DiscordJson.int(row, "id")
            val discordId = DiscordJson.str(row, "discordId")
            if (id <= 0 && discordId.isBlank()) continue
            items.add(
                DiscordUserRow(
                    id = id,
                    discordId = discordId,
                    username = DiscordJson.str(row, "username"),
                    displayName = DiscordJson.str(row, "displayName"),
                    avatar = DiscordJson.str(row, "avatar"),
                    level = DiscordJson.int(row, "level"),
                    messageCount = DiscordJson.int(row, "messageCount"),
                    lastActiveMs = DiscordJson.ts(row, "lastActive").let {
                        if (it > 0L) it else DiscordJson.ts(row, "lastActivity")
                    },
                ),
            )
        }
        val total = DiscordJson.int(data, "total", items.size)
        val offset = DiscordJson.int(data, "offset")
        val limit = DiscordJson.int(data, "limit", items.size.coerceAtLeast(1))
        val hasMore = DiscordJson.bool(data, "hasMore", offset + items.size < total && items.isNotEmpty())
        return DiscordPage(items, total, hasMore && items.isNotEmpty(), err)
    }

    fun attachments(raw: JSONObject?): DiscordPage<DiscordAttachment> {
        val err = DiscordJson.err(raw)
        val data = DiscordJson.unwrap(raw) as? JSONObject
        val arr = DiscordJson.arr(data, "attachments")
        val items = ArrayList<DiscordAttachment>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            items.add(attachment(row))
        }
        val total = DiscordJson.int(data, "total", items.size)
        val hasMore = if (data != null && data.has("hasMore")) {
            DiscordJson.bool(data, "hasMore")
        } else {
            val offset = DiscordJson.int(data, "offset")
            offset + items.size < total && items.isNotEmpty()
        }
        val cursor = DiscordJson.int(data, "cursor")
        return DiscordPage(items, total, hasMore && items.isNotEmpty(), err, cursor)
    }

    fun attachment(row: JSONObject, fallbackUser: String = ""): DiscordAttachment {
        val user = DiscordJson.obj(row, "user")
        val url = discordLocalMediaUrl(row)
        val filename = DiscordJson.str(row, "filename")
        val contentType = DiscordJson.str(row, "contentType")
        val kind = DiscordJson.str(row, "kind").ifBlank { discordMediaKind(contentType, filename) }
        val messageId = DiscordJson.str(row, "discordMessageId").ifBlank {
            DiscordJson.str(row, "messageId")
        }
        val playable = if (row.has("playable") && !row.isNull("playable")) {
            DiscordJson.bool(row, "playable")
        } else {
            url.isNotBlank()
        }
        return DiscordAttachment(
            id = DiscordJson.int(row, "id"),
            filename = filename,
            contentType = contentType,
            url = url,
            guildName = DiscordJson.str(row, "guildName"),
            channelName = DiscordJson.str(row, "channelName"),
            username = DiscordJson.str(user, "username").ifBlank { fallbackUser },
            kind = kind,
            guildId = DiscordJson.str(row, "guildId"),
            local = DiscordJson.bool(row, "local"),
            playable = playable,
            channelId = DiscordJson.str(row, "channelId"),
            messageId = messageId,
            discordAttachmentId = DiscordJson.str(row, "discordAttachmentId"),
            userId = DiscordJson.int(user, "id"),
            discordId = DiscordJson.str(user, "discordId"),
            displayName = DiscordJson.str(user, "displayName"),
            avatar = DiscordJson.str(user, "avatar"),
            size = DiscordJson.long(row, "size"),
            createdAtMs = DiscordJson.ts(row, "createdAt"),
            liked = DiscordJson.bool(row, "liked"),
            following = DiscordJson.bool(row, "following") || DiscordJson.bool(user, "following"),
            thumbUrl = discordLocalOnlyUrl(
                DiscordJson.str(row, "thumbUrl").ifBlank { DiscordJson.str(row, "thumbnailUrl") },
            ),
        )
    }

    fun pickers(raw: JSONObject?): List<DiscordRolePicker> {
        val data = DiscordJson.unwrap(raw)
        val arr = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("pickers") ?: JSONArray()
            else -> JSONArray()
        }
        val out = ArrayList<DiscordRolePicker>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val id = DiscordJson.int(row, "id")
            if (id <= 0) continue
            val msg = DiscordJson.str(row, "messageId")
            out.add(
                DiscordRolePicker(
                    id = id,
                    botId = DiscordJson.int(row, "botId"),
                    guildId = DiscordJson.str(row, "guildId"),
                    channelId = DiscordJson.str(row, "channelId"),
                    messageId = msg,
                    title = DiscordJson.str(row, "embedTitle"),
                    description = DiscordJson.str(row, "embedDescription"),
                    rolesJson = DiscordJson.str(row, "rolesJson"),
                    deployed = msg.isNotBlank(),
                ),
            )
        }
        return out
    }

    fun captchas(raw: JSONObject?): List<DiscordCaptcha> {
        val data = DiscordJson.unwrap(raw)
        val arr = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("captchas") ?: JSONArray()
            else -> JSONArray()
        }
        val out = ArrayList<DiscordCaptcha>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val id = DiscordJson.int(row, "id")
            if (id <= 0) continue
            val msg = DiscordJson.str(row, "messageId")
            out.add(
                DiscordCaptcha(
                    id = id,
                    botId = DiscordJson.int(row, "botId"),
                    guildId = DiscordJson.str(row, "guildId"),
                    channelId = DiscordJson.str(row, "channelId"),
                    messageId = msg,
                    title = DiscordJson.str(row, "embedTitle"),
                    postRoleName = DiscordJson.str(row, "postRoleName"),
                    preRoleName = DiscordJson.str(row, "preRoleName"),
                    deployed = msg.isNotBlank(),
                ),
            )
        }
        return out
    }

    fun emojiFiles(raw: JSONObject?): DiscordPage<DiscordEmojiFile> {
        val err = DiscordJson.err(raw)
        val data = DiscordJson.unwrap(raw) as? JSONObject
        val arr = DiscordJson.arr(data, "files")
        val items = ArrayList<DiscordEmojiFile>(arr.length())
        for (i in 0 until arr.length()) {
            val name = arr.optString(i)
            if (name.isBlank()) continue
            items.add(DiscordEmojiFile(filename = name, name = name.substringBefore('_').ifBlank { name }))
        }
        val total = DiscordJson.int(data, "total", items.size)
        val offset = DiscordJson.int(data, "offset")
        return DiscordPage(items, total, offset + items.size < total && items.isNotEmpty(), err)
    }

    fun guildEmojis(raw: JSONObject?): List<DiscordGuildEmoji> {
        val data = DiscordJson.unwrap(raw)
        val arr = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("emojis") ?: JSONArray()
            else -> JSONArray()
        }
        val out = ArrayList<DiscordGuildEmoji>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val id = DiscordJson.str(row, "id")
            if (id.isBlank()) continue
            val animated = DiscordJson.bool(row, "animated")
            val ext = if (animated) "gif" else "png"
            out.add(
                DiscordGuildEmoji(
                    id = id,
                    name = DiscordJson.str(row, "name").ifBlank { id },
                    animated = animated,
                    url = "https://cdn.discordapp.com/emojis/$id.$ext?size=64",
                ),
            )
        }
        return out
    }

    fun audits(raw: JSONObject?): DiscordPage<DiscordAudit> {
        val err = DiscordJson.err(raw)
        val data = DiscordJson.unwrap(raw) as? JSONObject
        val arr = DiscordJson.arr(data, "events")
        val items = ArrayList<DiscordAudit>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            items.add(audit(row))
        }
        val total = DiscordJson.int(data, "total", items.size)
        val hasMore = DiscordJson.bool(data, "hasMore", items.size >= 40)
        return DiscordPage(items, total, hasMore && items.isNotEmpty(), err)
    }

    fun audit(row: JSONObject): DiscordAudit {
        val actor = DiscordJson.str(row, "actorDisplayName").ifBlank {
            DiscordJson.str(row, "actorUsername")
        }
        val target = DiscordJson.str(row, "targetDisplayName").ifBlank {
            DiscordJson.str(row, "targetUsername")
        }
        val beforeObj = DiscordJson.obj(row, "before")
        val afterObj = DiscordJson.obj(row, "after")
        val meta = DiscordJson.obj(row, "metadata")
        val beforeAtt = DiscordJson.arr(row, "beforeAttachments").takeIf { it.length() > 0 }
            ?: DiscordJson.arr(beforeObj, "attachments")
        val afterAtt = DiscordJson.arr(row, "afterAttachments").takeIf { it.length() > 0 }
            ?: DiscordJson.arr(afterObj, "attachments")
        return DiscordAudit(
            id = DiscordJson.long(row, "id"),
            action = DiscordJson.str(row, "action"),
            guildId = DiscordJson.str(row, "guildId"),
            guildName = DiscordJson.str(row, "guildName"),
            actor = actor,
            target = target,
            actorAvatar = DiscordJson.str(row, "actorAvatar"),
            targetAvatar = DiscordJson.str(row, "targetAvatar"),
            beforeText = DiscordJson.str(row, "beforeText").ifBlank { discordAuditPlain(beforeObj) },
            afterText = DiscordJson.str(row, "afterText").ifBlank { discordAuditPlain(afterObj) },
            beforeAvatar = DiscordJson.str(row, "beforeAvatar"),
            afterAvatar = DiscordJson.str(row, "afterAvatar"),
            channelName = DiscordJson.str(row, "channelName").ifBlank {
                DiscordJson.str(meta, "channelName")
            },
            beforeAttachments = attachmentList(beforeAtt),
            afterAttachments = attachmentList(afterAtt),
            createdAtMs = DiscordJson.ts(row, "createdAt"),
            actorId = DiscordJson.str(row, "actorId"),
            targetId = DiscordJson.str(row, "targetId"),
            targetType = DiscordJson.str(row, "targetType"),
        )
    }

    fun profile(raw: JSONObject?): DiscordUserProfile {
        val err = DiscordJson.err(raw)
        val data = DiscordJson.unwrap(raw) as? JSONObject ?: JSONObject()
        return DiscordUserProfile(
            id = DiscordJson.int(data, "id"),
            discordId = DiscordJson.str(data, "discordId"),
            username = DiscordJson.str(data, "username"),
            displayName = DiscordJson.str(data, "displayName"),
            avatar = DiscordJson.str(data, "avatar"),
            level = DiscordJson.int(data, "level"),
            xp = DiscordJson.int(data, "xp"),
            totalXp = DiscordJson.int(data, "totalXp"),
            activityScore = if (data.isNull("activityScore")) 0.0 else data.optDouble("activityScore", 0.0),
            messageCount = DiscordJson.int(data, "messageCount"),
            xpToNextLevel = DiscordJson.int(data, "xpToNextLevel"),
            levelProgress = data.optDouble("levelProgress", 0.0).toFloat(),
            lastActiveMs = DiscordJson.ts(data, "lastActive").let {
                if (it > 0L) it else DiscordJson.ts(data, "lastActivity")
            },
            guilds = placeList(DiscordJson.arr(data, "activeGuilds")),
            channels = placeList(DiscordJson.arr(data, "activeChannels")),
            usernameChanges = changeList(DiscordJson.arr(data, "usernameChanges")),
            displayNameChanges = changeList(DiscordJson.arr(data, "displayNameChanges")),
            avatarChanges = changeList(DiscordJson.arr(data, "avatarChanges")),
            topTags = tagList(DiscordJson.arr(data, "topTags")),
            totalTagCount = DiscordJson.int(data, "totalTagCount"),
            uniqueTagCount = DiscordJson.int(data, "uniqueTagCount"),
            hasMoreTags = DiscordJson.bool(data, "hasMoreTags"),
            error = err,
        )
    }

    private fun placeList(arr: JSONArray): List<DiscordProfilePlace> {
        val out = ArrayList<DiscordProfilePlace>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val id = DiscordJson.str(row, "id")
            if (id.isBlank()) continue
            out.add(
                DiscordProfilePlace(
                    id = id,
                    name = DiscordJson.str(row, "name").ifBlank { id },
                    guildId = DiscordJson.str(row, "guildId"),
                    guildName = DiscordJson.str(row, "guildName"),
                    messageCount = DiscordJson.int(row, "messageCount"),
                ),
            )
        }
        return out
    }

    private fun changeList(arr: JSONArray): List<DiscordNameChange> {
        val out = ArrayList<DiscordNameChange>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            out.add(
                DiscordNameChange(
                    oldValue = DiscordJson.str(row, "oldValue"),
                    newValue = DiscordJson.str(row, "newValue"),
                    changedAtMs = DiscordJson.ts(row, "changedAt"),
                ),
            )
        }
        return out
    }

    fun tagPage(raw: JSONObject?): DiscordTagPage {
        val err = DiscordJson.err(raw)
        val data = DiscordJson.unwrap(raw) as? JSONObject ?: JSONObject()
        return DiscordTagPage(
            tags = tagList(DiscordJson.arr(data, "topTags")),
            totalTagCount = DiscordJson.int(data, "totalTagCount"),
            uniqueTagCount = DiscordJson.int(data, "uniqueTagCount"),
            hasMore = DiscordJson.bool(data, "hasMoreTags"),
            offset = DiscordJson.int(data, "tagOffset"),
            error = err,
        )
    }

    fun aiJobs(raw: JSONObject?): DiscordPage<DiscordAiJob> {
        val err = DiscordJson.err(raw)
        val data = DiscordJson.unwrap(raw) as? JSONObject
        val arr = DiscordJson.arr(data, "jobs")
        val items = ArrayList<DiscordAiJob>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            items.add(aiJob(row))
        }
        val hasMore = DiscordJson.bool(data, "hasMore")
        return DiscordPage(items, items.size, hasMore && items.isNotEmpty(), err)
    }

    fun aiJobWrap(raw: JSONObject?): DiscordAiJob {
        val err = DiscordJson.err(raw)
        val data = DiscordJson.unwrap(raw) as? JSONObject ?: JSONObject()
        val row = DiscordJson.obj(data, "job") ?: data
        return aiJob(row).copy(error = err)
    }

    fun aiJob(row: JSONObject): DiscordAiJob {
        return DiscordAiJob(
            id = DiscordJson.int(row, "id"),
            botId = DiscordJson.int(row, "botId"),
            kind = DiscordJson.str(row, "kind").ifBlank { "tag" },
            scope = DiscordJson.str(row, "scope"),
            guildId = DiscordJson.str(row, "guildId"),
            channelId = DiscordJson.str(row, "channelId"),
            userId = DiscordJson.int(row, "userId"),
            discordUserId = DiscordJson.str(row, "discordUserId"),
            timeframe = DiscordJson.str(row, "timeframe"),
            fromDate = DiscordJson.str(row, "fromDate"),
            toDate = DiscordJson.str(row, "toDate"),
            messageLimit = DiscordJson.int(row, "messageLimit"),
            skipTagged = DiscordJson.bool(row, "skipTagged", true),
            status = DiscordJson.str(row, "status"),
            total = DiscordJson.int(row, "total"),
            processed = DiscordJson.int(row, "processed"),
            tagged = DiscordJson.int(row, "tagged"),
            skipped = DiscordJson.int(row, "skipped"),
            failed = DiscordJson.int(row, "failed"),
            lastError = DiscordJson.str(row, "lastError"),
            label = DiscordJson.str(row, "label"),
            prompt = DiscordJson.str(row, "prompt"),
            summary = DiscordJson.str(row, "summary"),
            createdAtMs = DiscordJson.ts(row, "createdAt"),
            updatedAtMs = DiscordJson.ts(row, "updatedAt"),
            provider = DiscordJson.str(row, "provider"),
            model = DiscordJson.str(row, "model"),
            reasoningEffort = DiscordJson.str(row, "reasoningEffort"),
        )
    }

    fun aiSettings(raw: JSONObject?): DiscordAiSettings {
        val err = DiscordJson.err(raw)
        val data = DiscordJson.unwrap(raw) as? JSONObject ?: JSONObject()
        val arr = DiscordJson.arr(data, "models")
        val models = ArrayList<DiscordAiModel>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val id = DiscordJson.str(row, "id")
            if (id.isBlank()) continue
            val effortsArr = DiscordJson.arr(row, "reasoning_efforts")
            val efforts = ArrayList<String>(effortsArr.length())
            for (j in 0 until effortsArr.length()) {
                val e = effortsArr.optString(j).trim()
                if (e.isNotBlank()) efforts.add(e)
            }
            models.add(
                DiscordAiModel(
                    id = id,
                    name = DiscordJson.str(row, "name").ifBlank { id },
                    provider = DiscordJson.str(row, "provider").ifBlank { "spacexai" },
                    reasoningEfforts = efforts,
                    defaultReasoningEffort = DiscordJson.str(row, "default_reasoning_effort"),
                ),
            )
        }
        return DiscordAiSettings(
            provider = DiscordJson.str(data, "provider").ifBlank { "spacexai" },
            listingProvider = DiscordJson.str(data, "listingProvider").ifBlank {
                DiscordJson.str(data, "provider").ifBlank { "spacexai" }
            },
            model = DiscordJson.str(data, "model").ifBlank { "grok-4.6" },
            reasoningEffort = DiscordJson.str(data, "reasoningEffort").ifBlank { "high" },
            models = models,
            keySet = DiscordJson.bool(data, "keySet"),
            keyHint = DiscordJson.str(data, "keyHint"),
            keySource = DiscordJson.str(data, "keySource"),
            bridgeHealthy = DiscordJson.bool(data, "bridgeHealthy"),
            bridgeError = DiscordJson.str(data, "bridgeError"),
            defaultModel = DiscordJson.str(data, "defaultModel").ifBlank { "grok-4.6" },
            defaultEffort = DiscordJson.str(data, "defaultEffort").ifBlank { "high" },
            error = err,
        )
    }

    fun aiActivity(raw: JSONObject?): DiscordPage<DiscordAiResult> {
        val err = DiscordJson.err(raw)
        val data = DiscordJson.unwrap(raw) as? JSONObject
        val arr = DiscordJson.arr(data, "items")
        val items = ArrayList<DiscordAiResult>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            items.add(aiResult(row))
        }
        val hasMore = DiscordJson.bool(data, "hasMore")
        return DiscordPage(items, items.size, hasMore && items.isNotEmpty(), err)
    }

    fun aiTick(raw: JSONObject?): Pair<DiscordAiJob, DiscordAiResult?> {
        val data = DiscordJson.unwrap(raw) as? JSONObject ?: JSONObject()
        val job = aiJobWrap(raw)
        val resultObj = DiscordJson.obj(data, "result")
        val result = resultObj?.let { aiResult(it) }
        return job to result
    }

    fun aiResult(row: JSONObject): DiscordAiResult {
        val user = DiscordJson.obj(row, "user")
        return DiscordAiResult(
            id = DiscordJson.long(row, "id"),
            jobId = DiscordJson.int(row, "jobId"),
            messageId = DiscordJson.int(row, "messageId"),
            guildId = DiscordJson.str(row, "guildId"),
            guildName = DiscordJson.str(row, "guildName"),
            channelId = DiscordJson.str(row, "channelId"),
            channelName = DiscordJson.str(row, "channelName"),
            content = DiscordJson.str(row, "content"),
            tags = discordParseTags(row),
            status = DiscordJson.str(row, "status"),
            error = DiscordJson.str(row, "error"),
            createdAtMs = DiscordJson.ts(row, "createdAt"),
            userId = DiscordJson.int(user, "id"),
            discordId = DiscordJson.str(user, "discordId"),
            username = DiscordJson.str(user, "username"),
            displayName = DiscordJson.str(user, "displayName"),
            avatar = DiscordJson.str(user, "avatar"),
            roles = DiscordJson.strings(user, "roles"),
        )
    }

    private fun tagList(arr: JSONArray): List<DiscordTagCount> {
        val out = ArrayList<DiscordTagCount>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val tag = DiscordJson.str(row, "tag")
            if (tag.isBlank()) continue
            out.add(DiscordTagCount(tag = tag, count = DiscordJson.int(row, "count")))
        }
        return out
    }

    private fun attachmentList(arr: JSONArray): List<DiscordAttachment> {
        val out = ArrayList<DiscordAttachment>(arr.length())
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            out.add(attachment(a))
        }
        return out
    }

    fun requireOk(raw: JSONObject?): JSONObject {
        val obj = raw ?: throw IllegalStateException("no_response")
        val err = DiscordJson.err(obj)
        if (err != null) throw IllegalStateException(err)
        return obj
    }
}
