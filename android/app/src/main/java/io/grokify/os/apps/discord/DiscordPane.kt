package io.grokify.os.apps.discord

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.grokify.os.GrokifyApp
import io.grokify.os.apps.GROK_VOICES
import io.grokify.os.apps.plugin.HostAiClient
import io.grokify.os.apps.plugin.HostApiKeyStore
import io.grokify.os.data.ApiKeyIds
import io.grokify.os.data.TokenStore
import io.grokify.os.apps.plugin.BuiltinPluginCatalog
import io.grokify.os.apps.plugin.PluginFaviconImage
import io.grokify.os.apps.plugin.PluginIconKey
import io.grokify.os.ui.theme.GrokifyColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal enum class DiscordTab {
    Feed, Bots, Guilds, Users, Media, Roles, Captchas, Emojis, Audits, Ai, Settings
}

private fun DiscordTab.key(): String = name.lowercase()

private fun tabFromKey(raw: String): DiscordTab =
    DiscordTab.entries.firstOrNull { it.key() == raw.lowercase() } ?: DiscordTab.Feed

private data class DiscordNavFrame(
    val tab: DiscordTab,
    val aiSub: String,
)

@Composable
fun DiscordPane(onBack: () -> Unit) {
    val context = LocalContext.current
    val appCtx = context.applicationContext
    val scope = rememberCoroutineScope()
    val store = remember { DiscordStore(appCtx) }
    val tokenStore = remember {
        if (appCtx is GrokifyApp) appCtx.tokenStore else TokenStore(appCtx)
    }
    val api = remember {
        DiscordApi {
            kotlinx.coroutines.runBlocking {
                tokenStore.tokenFlow.first()
            }
        }
    }

    var tab by remember { mutableStateOf(tabFromKey(store.tab)) }
    var statusLine by remember { mutableStateOf("Connecting…") }
    var busy by remember { mutableStateOf(false) }
    var timeframe by remember { mutableStateOf(store.timeframe) }
    var selectedGuildId by remember { mutableStateOf(store.guildId) }
    var selectedBotId by remember { mutableIntStateOf(store.botId) }
    var search by remember { mutableStateOf("") }
    var userSort by remember { mutableStateOf(store.userSort) }
    var userOrder by remember { mutableStateOf(store.userOrder) }
    var guildFilter by remember { mutableStateOf(store.guildFilter) }
    var guildSort by remember { mutableStateOf(store.guildSort) }
    var auditAction by remember { mutableStateOf(store.auditAction) }
    var auditTf by remember { mutableStateOf(store.auditTf) }
    var mediaKind by remember { mutableStateOf(store.mediaKind) }
    var mediaGuildId by remember { mutableStateOf(store.mediaGuildId) }
    var mediaOrder by remember { mutableStateOf(store.mediaOrder) }
    var mediaLikedOnly by remember { mutableStateOf(store.mediaLikedOnly) }
    var mediaHideStale by remember { mutableStateOf(store.mediaHideStale) }
    var auditGuildId by remember { mutableStateOf(store.auditGuildId) }
    var userGuildId by remember { mutableStateOf(store.userGuildId) }
    var guildSearch by remember { mutableStateOf("") }
    var guildBotIds by remember {
        mutableStateOf(discordParseBotIdSet(store.guildBotIdsRaw) ?: emptySet())
    }

    var bots by remember { mutableStateOf<List<DiscordBot>>(emptyList()) }
    var guilds by remember { mutableStateOf<List<DiscordGuild>>(emptyList()) }
    var channelsByGuild by remember { mutableStateOf<Map<String, DiscordChannelBundle>>(emptyMap()) }
    var guildChannelKind by remember { mutableStateOf("channels") }
    var guildChannelQuery by remember { mutableStateOf("") }
    var aiChannelKind by remember { mutableStateOf("channels") }
    var aiChannelQuery by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf<List<DiscordMessage>>(emptyList()) }
    var messageTotal by remember { mutableIntStateOf(0) }
    var messagePage by remember { mutableIntStateOf(1) }
    var messageHasMore by remember { mutableStateOf(false) }
    var users by remember { mutableStateOf<List<DiscordUserRow>>(emptyList()) }
    var userTotal by remember { mutableIntStateOf(0) }
    var userHasMore by remember { mutableStateOf(false) }
    var userOffset by remember { mutableIntStateOf(0) }
    var media by remember { mutableStateOf<List<DiscordAttachment>>(emptyList()) }
    var mediaTotal by remember { mutableIntStateOf(0) }
    var mediaHasMore by remember { mutableStateOf(false) }
    var mediaOffset by remember { mutableIntStateOf(0) }
    var discogram by remember { mutableStateOf(false) }
    var discogramFeed by remember { mutableStateOf<List<DiscordAttachment>>(emptyList()) }
    var discogramHasMore by remember { mutableStateOf(false) }
    var discogramLoading by remember { mutableStateOf(false) }
    var discogramError by remember { mutableStateOf("") }
    var discogramExitAt by remember { mutableLongStateOf(0L) }
    var discogramCursor by remember { mutableIntStateOf(0) }
    var feedAroundMessageId by remember { mutableStateOf("") }
    var feedAroundAtMs by remember { mutableLongStateOf(0L) }
    var discogramProfile by remember { mutableStateOf<DiscordAttachment?>(null) }
    var discogramProfileItems by remember { mutableStateOf<List<DiscordAttachment>>(emptyList()) }
    var discogramProfileHasMore by remember { mutableStateOf(false) }
    var discogramProfileStart by remember { mutableStateOf<DiscordAttachment?>(null) }
    var mediaDownloading by remember { mutableStateOf(false) }
    var pickers by remember { mutableStateOf<List<DiscordRolePicker>>(emptyList()) }
    var captchas by remember { mutableStateOf<List<DiscordCaptcha>>(emptyList()) }
    var emojiFiles by remember { mutableStateOf<List<DiscordEmojiFile>>(emptyList()) }
    var emojiTotal by remember { mutableIntStateOf(0) }
    var guildEmojis by remember { mutableStateOf<List<DiscordGuildEmoji>>(emptyList()) }
    var audits by remember { mutableStateOf<List<DiscordAudit>>(emptyList()) }
    var auditHasMore by remember { mutableStateOf(false) }
    var auditOffset by remember { mutableIntStateOf(0) }
    var expandedGuild by remember { mutableStateOf<String?>(null) }
    var profileKey by remember { mutableStateOf<DiscordProfileKey?>(null) }
    var openedMedia by remember { mutableStateOf<DiscordAttachment?>(null) }
    var profile by remember { mutableStateOf<DiscordUserProfile?>(null) }
    var profileMessages by remember { mutableStateOf<List<DiscordMessage>>(emptyList()) }
    var profileMessagePage by remember { mutableIntStateOf(1) }
    var profileMessageHasMore by remember { mutableStateOf(false) }
    var profileTags by remember { mutableStateOf<List<DiscordTagCount>>(emptyList()) }
    var profileTagsHasMore by remember { mutableStateOf(false) }
    var profileTagsOffset by remember { mutableIntStateOf(0) }
    var aiSub by remember {
        mutableStateOf(
            when (store.aiSub) {
                "tag", "analyze", "activity" -> store.aiSub
                else -> "activity"
            },
        )
    }
    var aiTf by remember { mutableStateOf(store.aiTf) }
    var aiFrom by remember { mutableStateOf("") }
    var aiTo by remember { mutableStateOf("") }
    var aiLimit by remember { mutableIntStateOf(store.aiLimit) }
    var aiSkipTagged by remember { mutableStateOf(store.aiSkipTagged) }
    var aiPrompt by remember { mutableStateOf(store.aiPrompt) }
    var aiSort by remember { mutableStateOf(store.aiSort) }
    var aiResultStatus by remember { mutableStateOf("") }
    var aiResultGuildId by remember { mutableStateOf("") }
    var aiSearch by remember { mutableStateOf("") }
    var aiUserSearch by remember { mutableStateOf("") }
    var aiJobs by remember { mutableStateOf<List<DiscordAiJob>>(emptyList()) }
    var aiResults by remember { mutableStateOf<List<DiscordAiResult>>(emptyList()) }
    var aiJobHasMore by remember { mutableStateOf(false) }
    var aiResultHasMore by remember { mutableStateOf(false) }
    var aiJobOffset by remember { mutableIntStateOf(0) }
    var aiResultOffset by remember { mutableIntStateOf(0) }
    var aiUsers by remember { mutableStateOf<List<DiscordUserRow>>(emptyList()) }
    var aiUserHasMore by remember { mutableStateOf(false) }
    var aiUserOffset by remember { mutableIntStateOf(0) }
    var aiExpandedGuild by remember { mutableStateOf<String?>(null) }
    var analyzingJobId by remember { mutableIntStateOf(0) }
    var aiSettings by remember { mutableStateOf(DiscordAiSettings()) }
    var aiVoiceId by remember { mutableStateOf(store.aiVoiceId) }
    var aiPreferDeviceTts by remember { mutableStateOf(store.aiPreferDeviceTts) }
    var aiVoicePreviewMsg by remember { mutableStateOf<String?>(null) }
    var hasXaiKey by remember {
        mutableStateOf(!HostApiKeyStore.getValue(appCtx, ApiKeyIds.SPACEXAI).isNullOrBlank())
    }
    val discordNav = remember { mutableStateListOf<DiscordNavFrame>() }

    fun currentDiscordFrame() = DiscordNavFrame(tab, aiSub)

    fun pushDiscordNav() {
        val frame = currentDiscordFrame()
        if (discordNav.lastOrNull() == frame) return
        discordNav.add(frame)
        while (discordNav.size > 24) discordNav.removeAt(0)
    }

    fun setTab(next: DiscordTab) {
        if (next == tab) return
        pushDiscordNav()
        tab = next
        store.tab = next.key()
    }

    fun setAiSub(next: String) {
        if (next == aiSub) return
        pushDiscordNav()
        aiSub = next
        store.aiSub = next
    }

    fun requestDiscogramExit() {
        val now = System.currentTimeMillis()
        if (discordDiscogramConfirmExit(discogramExitAt, now)) {
            discogramExitAt = 0L
            discogram = false
            discogramProfile = null
            discogramProfileStart = null
            discogramProfileItems = emptyList()
            discogramProfileHasMore = false
            return
        }
        discogramExitAt = now
        Toast.makeText(appCtx, "Press back again to exit", Toast.LENGTH_SHORT).show()
    }

    fun closeProfile() {
        profileKey = null
        profile = null
        profileMessages = emptyList()
        profileMessageHasMore = false
        profileTags = emptyList()
        profileTagsHasMore = false
    }

    fun handleDiscordBack(): Boolean {
        if (openedMedia != null) {
            openedMedia = null
            return true
        }
        if (discogramProfileStart != null) {
            discogramProfileStart = null
            return true
        }
        if (discogramProfile != null) {
            discogramProfile = null
            discogramProfileItems = emptyList()
            discogramProfileHasMore = false
            return true
        }
        if (discogram) {
            requestDiscogramExit()
            return true
        }
        if (profileKey != null) {
            closeProfile()
            return true
        }
        if (discordNav.isNotEmpty()) {
            val prev = discordNav.removeAt(discordNav.lastIndex)
            tab = prev.tab
            store.tab = prev.tab.key()
            aiSub = prev.aiSub
            store.aiSub = prev.aiSub
            return true
        }
        return false
    }

    BackHandler(enabled = openedMedia != null || discogram || profileKey != null || discordNav.isNotEmpty()) {
        handleDiscordBack()
    }

    fun setGuild(id: String) {
        selectedGuildId = id
        store.guildId = id
    }

    fun setBot(id: Int) {
        selectedBotId = id
        store.botId = id
    }

    fun setTf(tf: String) {
        timeframe = tf
        store.timeframe = tf
    }

    suspend fun loadBots() {
        val raw = withContext(Dispatchers.IO) { api.bots() }
        val err = DiscordJson.err(raw)
        if (err != null) {
            statusLine = err
            return
        }
        bots = DiscordParse.bots(raw)
        val live = bots.count { it.isRunning }
        val stored = discordParseBotIdSet(store.guildBotIdsRaw)
        val valid = bots.map { it.id }.toSet()
        guildBotIds = if (stored == null) {
            val all = valid
            store.guildBotIdsRaw = discordFormatBotIdSet(all)
            all
        } else {
            stored.filter { it in valid }.toSet()
        }
        statusLine = "${bots.size} bots · $live live"
    }

    suspend fun loadGuilds(retainIfMissing: DiscordGuild? = null) {
        if (guildBotIds.isEmpty() && bots.isNotEmpty()) {
            guilds = emptyList()
            statusLine = "Select a bot"
            return
        }
        val watched = when (guildFilter) {
            "watched" -> true
            "off" -> false
            else -> null
        }
        val raw = withContext(Dispatchers.IO) {
            api.guilds(
                watched = watched,
                search = guildSearch,
                sort = guildSort,
                botIds = guildBotIds,
            )
        }
        val err = DiscordJson.err(raw)
        if (err != null) {
            statusLine = err
            return
        }
        val next = DiscordParse.guilds(raw).toMutableList()
        if (retainIfMissing != null && next.none { it.guildId == retainIfMissing.guildId }) {
            next.add(0, retainIfMissing)
        }
        guilds = next
        statusLine = "${guilds.count { it.isWatched }} watched · ${guilds.size} guilds"
    }

    suspend fun loadChannels(
        guildId: String,
        kind: String = "channels",
        search: String = "",
        append: Boolean = false,
    ) {
        if (guildId.isBlank()) return
        val key = discordChannelCacheKey(guildId, kind)
        val prev = channelsByGuild[key]?.takeIf { it.query == search }
        val offset = if (append) prev?.items?.size ?: 0 else 0
        val limit = if (kind == "threads") 80 else 200
        val raw = withContext(Dispatchers.IO) {
            api.channels(
                guildId = guildId,
                kind = kind,
                search = search,
                limit = limit,
                offset = offset,
            )
        }
        if (DiscordJson.err(raw) != null) {
            statusLine = DiscordJson.err(raw) ?: "channels failed"
            return
        }
        val page = DiscordParse.channelPage(raw).copy(query = search, guildId = guildId, kind = kind)
        val merged = if (append && prev != null) {
            page.copy(items = prev.items.discordMergePage(page.items, reset = false) { it.channelId })
        } else {
            page.copy(items = page.items.distinctBy { it.channelId })
        }
        channelsByGuild = channelsByGuild + (key to merged)
        val noun = if (kind == "threads") "threads" else "channels"
        statusLine = if (merged.total > merged.items.size) {
            "${merged.items.size} of ${merged.total} $noun"
        } else {
            "${merged.items.size} $noun"
        }
    }

    suspend fun loadFeed(reset: Boolean) {
        val around = feedAroundMessageId.isNotBlank() || feedAroundAtMs > 0L
        val page = if (reset || around) 1 else messagePage + 1
        val channelId = if (around && search.matches(Regex("^[0-9]{15,22}$"))) search else ""
        val raw = withContext(Dispatchers.IO) {
            api.messages(
                timeframe = timeframe,
                guildId = selectedGuildId,
                channelId = channelId,
                botId = selectedBotId.takeIf { it > 0 },
                search = search,
                page = page,
                aroundMessageId = if (reset) feedAroundMessageId else "",
                aroundAt = if (reset && feedAroundAtMs > 0L) feedAroundAtMs.toString() else "",
                beforeAt = if (!reset && around) {
                    messages.lastOrNull()?.createdAtMs?.takeIf { it > 0L }?.toString().orEmpty()
                } else {
                    ""
                },
            )
        }
        val parsed = DiscordParse.messages(raw)
        if (parsed.error != null) {
            statusLine = parsed.error
            return
        }
        messages = messages.discordMergePage(parsed.items, reset) { it.lazyKey() }
        messagePage = page
        messageTotal = parsed.total
        messageHasMore = parsed.hasMore
        statusLine = if (around) {
            "${parsed.items.size} around post · $timeframe"
        } else {
            "${parsed.total} messages · $timeframe"
        }
    }

    suspend fun loadFeedLive() {
        if (feedAroundMessageId.isNotBlank() || feedAroundAtMs > 0L) return
        val raw = withContext(Dispatchers.IO) {
            api.messages(
                timeframe = timeframe,
                guildId = selectedGuildId,
                botId = selectedBotId.takeIf { it > 0 },
                search = search,
                page = 1,
            )
        }
        val parsed = DiscordParse.messages(raw)
        if (parsed.error != null) return
        val next = messages.discordMergeLive(parsed.items)
        if (next != messages) {
            messages = next
        }
        if (parsed.total > messageTotal) {
            messageTotal = parsed.total
        }
        if (parsed.hasMore) {
            messageHasMore = true
        }
    }

    suspend fun loadUsers(reset: Boolean) {
        val offset = if (reset) 0 else users.size
        val raw = withContext(Dispatchers.IO) {
            api.users(
                search = search,
                offset = offset,
                sort = userSort,
                order = userOrder,
                guildId = userGuildId,
            )
        }
        val parsed = DiscordParse.users(raw)
        if (parsed.error != null) {
            statusLine = parsed.error
            return
        }
        users = users.discordMergePage(parsed.items, reset) { it.lazyKey() }
        userOffset = offset
        userTotal = parsed.total
        userHasMore = parsed.hasMore
        statusLine = "${parsed.total} users"
    }

    suspend fun loadMedia(reset: Boolean) {
        val offset = if (reset) 0 else media.size
        val raw = withContext(Dispatchers.IO) {
            api.attachments(
                guildId = mediaGuildId,
                contentType = mediaKind,
                offset = offset,
                sort = mediaOrder,
                liked = mediaLikedOnly,
                playableOnly = mediaHideStale,
            )
        }
        val parsed = DiscordParse.attachments(raw)
        if (parsed.error != null) {
            statusLine = parsed.error
            return
        }
        media = media.discordMergePage(parsed.items, reset) { it.lazyKey() }
        mediaOffset = offset
        mediaTotal = parsed.total
        mediaHasMore = parsed.hasMore
        statusLine = "${parsed.total} files"
    }

    suspend fun loadDiscogram(reset: Boolean) {
        if (discogramLoading) return
        discogramLoading = true
        if (reset) discogramError = ""
        try {
            val exclude = if (reset) emptyList() else discogramFeed.map { it.id }
            val raw = withContext(Dispatchers.IO) {
                api.attachments(
                    guildId = mediaGuildId,
                    contentType = "",
                    limit = 10,
                    mode = "discogram",
                    excludeIds = exclude,
                    playableOnly = true,
                )
            }
            val parsed = DiscordParse.attachments(raw)
            if (parsed.error != null) {
                if (reset) discogramError = parsed.error
                statusLine = parsed.error
                return
            }
            discogramFeed = discogramFeed.discordMergePage(parsed.items, reset) { it.lazyKey() }
            discogramHasMore = parsed.hasMore
            if (reset) {
                discogramCursor = parsed.cursor.coerceAtLeast(0)
            }
            if (reset && parsed.items.isEmpty()) {
                discogramError = "No media for Discogram"
                statusLine = discogramError
            }
        } finally {
            discogramLoading = false
        }
    }

    suspend fun loadCreatorMedia(reset: Boolean, discordId: String) {
        val id = discordId.trim()
        if (id.isBlank()) return
        val offset = if (reset) 0 else discogramProfileItems.size
        val raw = withContext(Dispatchers.IO) {
            api.attachments(
                guildId = mediaGuildId,
                contentType = "",
                offset = offset,
                limit = 30,
                sort = "newest",
                discordUserId = id,
                playableOnly = true,
            )
        }
        val parsed = DiscordParse.attachments(raw)
        if (parsed.error != null) {
            statusLine = parsed.error
            return
        }
        discogramProfileItems = discogramProfileItems.discordMergePage(parsed.items, reset) { it.lazyKey() }
        discogramProfileHasMore = parsed.hasMore
    }

    fun applyMediaLike(id: Int, liked: Boolean) {
        media = media.discordWithLike(id, liked)
        discogramFeed = discogramFeed.discordWithLike(id, liked)
        discogramProfileItems = discogramProfileItems.discordWithLike(id, liked)
        openedMedia = openedMedia?.let { if (it.id == id) it.copy(liked = liked) else it }
        discogramProfile = discogramProfile?.let { if (it.id == id) it.copy(liked = liked) else it }
        discogramProfileStart = discogramProfileStart?.let { if (it.id == id) it.copy(liked = liked) else it }
    }

    fun applyMediaFollow(discordId: String, following: Boolean) {
        media = media.discordWithFollow(discordId, following)
        discogramFeed = discogramFeed.discordWithFollow(discordId, following)
        discogramProfileItems = discogramProfileItems.discordWithFollow(discordId, following)
        openedMedia = openedMedia?.let { if (it.discordId == discordId) it.copy(following = following) else it }
        discogramProfile = discogramProfile?.let { if (it.discordId == discordId) it.copy(following = following) else it }
        discogramProfileStart = discogramProfileStart?.let { if (it.discordId == discordId) it.copy(following = following) else it }
    }

    fun toggleMediaLike(att: DiscordAttachment) {
        if (att.id <= 0) return
        val next = !att.liked
        applyMediaLike(att.id, next)
        scope.launch {
            val raw = withContext(Dispatchers.IO) { api.mediaLike(att.id, next) }
            val err = DiscordJson.err(raw)
            if (err != null) {
                applyMediaLike(att.id, att.liked)
                statusLine = err
            }
        }
    }

    fun toggleMediaFollow(att: DiscordAttachment) {
        if (att.discordId.isBlank()) return
        val next = !att.following
        applyMediaFollow(att.discordId, next)
        scope.launch {
            val raw = withContext(Dispatchers.IO) { api.mediaFollow(att.discordId, next) }
            val err = DiscordJson.err(raw)
            if (err != null) {
                applyMediaFollow(att.discordId, att.following)
                statusLine = err
            }
        }
    }

    fun downloadMedia(att: DiscordAttachment) {
        if (att.url.isBlank() || mediaDownloading) return
        mediaDownloading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                discordSaveMedia(appCtx, att.url, att.filename, att.contentType, api.authHeaders())
            }
            mediaDownloading = false
            statusLine = result.fold(
                onSuccess = { "Saved $it" },
                onFailure = { it.message ?: "Download failed" },
            )
        }
    }

    suspend fun loadPickers() {
        val raw = withContext(Dispatchers.IO) { api.rolePickers(selectedBotId.takeIf { it > 0 }) }
        if (DiscordJson.err(raw) != null) {
            statusLine = DiscordJson.err(raw) ?: "pickers failed"
            return
        }
        pickers = DiscordParse.pickers(raw)
        statusLine = "${pickers.size} role pickers"
    }

    suspend fun loadCaptchas() {
        val raw = withContext(Dispatchers.IO) { api.captchas(selectedBotId.takeIf { it > 0 }) }
        if (DiscordJson.err(raw) != null) {
            statusLine = DiscordJson.err(raw) ?: "captchas failed"
            return
        }
        captchas = DiscordParse.captchas(raw)
        statusLine = "${captchas.size} captchas"
    }

    suspend fun loadEmojis() {
        val raw = withContext(Dispatchers.IO) { api.emojisLocal(search = search) }
        val parsed = DiscordParse.emojiFiles(raw)
        if (parsed.error != null) {
            statusLine = parsed.error
            return
        }
        emojiFiles = parsed.items
        emojiTotal = parsed.total
        statusLine = "${parsed.total} captured emoji"
        val gid = selectedGuildId
        val bid = selectedBotId
        if (gid.isNotBlank() && bid > 0) {
            val gRaw = withContext(Dispatchers.IO) { api.emojisGuild(bid, gid) }
            if (DiscordJson.err(gRaw) == null) {
                guildEmojis = DiscordParse.guildEmojis(gRaw)
            }
        }
    }

    suspend fun loadAudits(reset: Boolean) {
        val offset = if (reset) 0 else audits.size
        val raw = withContext(Dispatchers.IO) {
            api.audits(
                guildId = auditGuildId,
                action = auditAction,
                timeframe = auditTf,
                offset = offset,
            )
        }
        val parsed = DiscordParse.audits(raw)
        if (parsed.error != null) {
            statusLine = parsed.error
            return
        }
        audits = audits.discordMergePage(parsed.items, reset) { "aud-${it.id}-${it.createdAtMs}" }
        auditOffset = offset
        auditHasMore = parsed.hasMore
        statusLine = if (parsed.total > 0) "${parsed.total} audit events" else "${parsed.items.size} audit events"
    }

    suspend fun loadProfileMessages(reset: Boolean) {
        val key = profileKey ?: return
        val page = if (reset) 1 else profileMessagePage + 1
        val raw = withContext(Dispatchers.IO) {
            api.messages(timeframe = "all", userId = key.id, page = page, limit = 30)
        }
        val parsed = DiscordParse.messages(raw)
        if (parsed.error != null && profileMessages.isEmpty()) {
            statusLine = parsed.error
            return
        }
        profileMessages = profileMessages.discordMergePage(parsed.items, reset) { it.lazyKey() }
        profileMessagePage = page
        profileMessageHasMore = parsed.hasMore
    }

    suspend fun loadProfile(reset: Boolean) {
        val key = profileKey ?: return
        val raw = withContext(Dispatchers.IO) { api.user(key.id, key.byDiscordId) }
        val parsed = DiscordParse.profile(raw)
        profile = parsed
        if (parsed.error != null) {
            statusLine = parsed.error ?: "not_found"
            return
        }
        val name = parsed.displayName.ifBlank { parsed.username }.ifBlank { key.id }
        statusLine = "$name · ${parsed.messageCount} msgs"
        profileTags = parsed.topTags
        profileTagsHasMore = parsed.hasMoreTags || parsed.uniqueTagCount > parsed.topTags.size
        profileTagsOffset = parsed.topTags.size
        loadProfileMessages(reset)
    }

    suspend fun loadProfileTags() {
        val key = profileKey ?: return
        val raw = withContext(Dispatchers.IO) {
            api.userTags(key.id, key.byDiscordId, offset = profileTagsOffset, limit = 80)
        }
        val parsed = DiscordParse.tagPage(raw)
        if (parsed.error != null) {
            statusLine = parsed.error
            return
        }
        val merged = (profileTags + parsed.tags).distinctBy { it.tag.lowercase() }
        profileTags = merged
        profileTagsHasMore = parsed.hasMore
        profileTagsOffset = profileTagsOffset + parsed.tags.size
        val p = profile
        if (p != null && parsed.uniqueTagCount > 0) {
            profile = p.copy(
                uniqueTagCount = parsed.uniqueTagCount,
                totalTagCount = parsed.totalTagCount,
                hasMoreTags = parsed.hasMore,
            )
        }
        statusLine = "${merged.size} / ${parsed.uniqueTagCount} tags"
    }

    suspend fun loadAiJobs(reset: Boolean) {
        val offset = if (reset) 0 else aiJobs.size
        val raw = withContext(Dispatchers.IO) {
            api.aiJobs(
                botId = selectedBotId.takeIf { it > 0 },
                sort = "newest",
                offset = offset,
            )
        }
        val parsed = DiscordParse.aiJobs(raw)
        if (parsed.error != null) {
            statusLine = parsed.error
            return
        }
        aiJobs = aiJobs.discordMergePage(parsed.items, reset) { "job-${it.id}" }
        aiJobOffset = offset
        aiJobHasMore = parsed.hasMore
        val running = aiJobs.firstOrNull { discordAiJobRunning(it) }
        if (running != null && analyzingJobId <= 0) {
            analyzingJobId = running.id
        }
    }

    suspend fun loadAiActivity(reset: Boolean) {
        val offset = if (reset) 0 else aiResults.size
        val rangeTf = if (aiTf == "between") "all" else "all"
        val raw = withContext(Dispatchers.IO) {
            api.aiActivity(
                botId = selectedBotId.takeIf { it > 0 },
                guildId = aiResultGuildId,
                status = aiResultStatus,
                search = aiSearch,
                sort = aiSort,
                timeframe = rangeTf,
                offset = offset,
            )
        }
        val parsed = DiscordParse.aiActivity(raw)
        if (parsed.error != null) {
            statusLine = parsed.error
            return
        }
        aiResults = aiResults.discordMergePage(parsed.items, reset) { "res-${it.id}" }
        aiResultOffset = offset
        aiResultHasMore = parsed.hasMore
        statusLine = if (analyzingJobId > 0) {
            val job = aiJobs.firstOrNull { it.id == analyzingJobId }
            if (job != null) "AI ${job.processed}/${job.total} · ${job.tagged} tagged" else "AI running"
        } else {
            "${aiResults.size} analyses"
        }
    }

    suspend fun loadAiUsers(reset: Boolean) {
        val offset = if (reset) 0 else aiUserOffset + aiUsers.size
        val raw = withContext(Dispatchers.IO) {
            api.users(search = aiUserSearch, offset = offset, sort = "lastActive", order = "desc")
        }
        val parsed = DiscordParse.users(raw)
        if (parsed.error != null) {
            statusLine = parsed.error
            return
        }
        aiUsers = if (reset) parsed.items else aiUsers + parsed.items
        aiUserOffset = offset
        aiUserHasMore = parsed.hasMore
    }

    suspend fun loadAiSettings(provider: String = aiSettings.listingProvider.ifBlank { aiSettings.provider }, refresh: Boolean = false) {
        val raw = withContext(Dispatchers.IO) { api.aiSettings(provider = provider, refresh = refresh) }
        val parsed = DiscordParse.aiSettings(raw)
        if (parsed.error != null) {
            aiSettings = parsed
            statusLine = parsed.error
            return
        }
        aiSettings = parsed
        val who = if (parsed.listingProvider == "bridge") "Bridge" else "SpaceXAI"
        statusLine = "$who · ${parsed.model} · ${parsed.models.size} models"
    }

    suspend fun saveAiSettings(
        provider: String = aiSettings.provider,
        model: String = aiSettings.model,
        effort: String = aiSettings.reasoningEffort,
        apiKey: String = "",
        clearKey: Boolean = false,
        listing: String = provider,
    ) {
        val raw = withContext(Dispatchers.IO) {
            api.aiSettingsSave(
                provider = provider,
                model = model,
                reasoningEffort = effort,
                apiKey = apiKey,
                clearKey = clearKey,
            )
        }
        val parsed = DiscordParse.aiSettings(raw)
        if (parsed.error != null) {
            statusLine = parsed.error
            return
        }
        aiSettings = if (listing != parsed.listingProvider && listing.isNotBlank()) {
            val listed = DiscordParse.aiSettings(
                withContext(Dispatchers.IO) { api.aiSettings(provider = listing, refresh = true) },
            )
            listed.copy(
                provider = parsed.provider,
                model = parsed.model,
                reasoningEffort = parsed.reasoningEffort,
                keySet = parsed.keySet,
                keyHint = parsed.keyHint,
                keySource = parsed.keySource,
            )
        } else {
            parsed
        }
        statusLine = "Saved · ${aiSettings.model}"
    }

    suspend fun startAiJob(
        kind: String,
        guildId: String,
        channelId: String,
        userId: String,
        label: String,
        limitOverride: Int? = null,
        promptOverride: String? = null,
        switchToActivity: Boolean = true,
        messageId: String = "",
    ) {
        val botId = selectedBotId.takeIf { it > 0 } ?: bots.firstOrNull()?.id ?: 0
        if (botId <= 0) {
            statusLine = "Pick a bot first"
            return
        }
        val tf = if (aiTf == "between") "all" else aiTf
        val from = if (aiTf == "between") aiFrom else ""
        val to = if (aiTf == "between") aiTo else ""
        val jobKind = if (kind == "analyze") "analyze" else "tag"
        val raw = withContext(Dispatchers.IO) {
            api.aiAnalyzeStart(
                botId = botId,
                guildId = guildId,
                channelId = channelId,
                userId = userId,
                timeframe = tf,
                fromDate = from,
                toDate = to,
                limit = limitOverride ?: aiLimit,
                skipTagged = if (jobKind == "analyze") false else aiSkipTagged,
                label = label,
                kind = jobKind,
                prompt = if (jobKind == "analyze") {
                    (promptOverride ?: aiPrompt).trim()
                } else {
                    ""
                },
                messageId = messageId,
            )
        }
        val err = DiscordJson.err(raw)
        if (err != null) {
            statusLine = when (err) {
                "spacexai_key_missing", "xai_key_missing" -> "Set a SpaceXAI API key in Settings"
                "bridge_unreachable" -> "Bridge unavailable — pick SpaceXAI in Settings or start the host bridge"
                else -> err
            }
            return
        }
        val job = DiscordParse.aiJobWrap(raw)
        if (job.id <= 0) {
            statusLine = job.error ?: "start_failed"
            return
        }
        aiJobs = listOf(job) + aiJobs.filter { it.id != job.id }
        if (switchToActivity) {
            setAiSub("activity")
        }
        analyzingJobId = job.id
        statusLine = if (jobKind == "analyze") {
            "Analyze ${job.processed}/${job.total}"
        } else {
            "Tag ${job.processed}/${job.total}"
        }
    }

    suspend fun loadTab(which: DiscordTab) {
        when (which) {
            DiscordTab.Feed -> {
                if (guilds.isEmpty()) loadGuilds()
                if (bots.isEmpty()) loadBots()
                loadFeed(reset = true)
            }
            DiscordTab.Bots -> loadBots()
            DiscordTab.Guilds -> {
                loadBots()
                loadGuilds()
            }
            DiscordTab.Users -> {
                if (guilds.isEmpty()) loadGuilds()
                loadUsers(reset = true)
            }
            DiscordTab.Media -> {
                if (guilds.isEmpty()) loadGuilds()
                loadMedia(reset = true)
            }
            DiscordTab.Roles -> {
                if (bots.isEmpty()) loadBots()
                if (guilds.isEmpty()) loadGuilds()
                loadPickers()
            }
            DiscordTab.Captchas -> {
                if (bots.isEmpty()) loadBots()
                if (guilds.isEmpty()) loadGuilds()
                loadCaptchas()
            }
            DiscordTab.Emojis -> {
                if (bots.isEmpty()) loadBots()
                if (guilds.isEmpty()) loadGuilds()
                loadEmojis()
            }
            DiscordTab.Audits -> {
                if (guilds.isEmpty()) loadGuilds()
                loadAudits(reset = true)
            }
            DiscordTab.Ai -> {
                if (bots.isEmpty()) loadBots()
                if (guilds.isEmpty()) loadGuilds()
                if (selectedBotId <= 0 && bots.isNotEmpty()) {
                    setBot(bots.first().id)
                }
                loadAiJobs(reset = true)
                loadAiActivity(reset = true)
            }
            DiscordTab.Settings -> {
                hasXaiKey = !HostApiKeyStore.getValue(appCtx, ApiKeyIds.SPACEXAI).isNullOrBlank()
                loadAiSettings()
            }
        }
    }

    fun guildPatchBots(g: DiscordGuild): List<Int?> {
        val memberIds = g.bots.map { it.botId }.filter { it > 0 }
        val chosen = guildBotIds.filter { it in memberIds }
        val targets = if (chosen.isNotEmpty()) chosen else memberIds
        val out = ArrayList<Int?>(targets.size + 1)
        out.add(null)
        targets.forEach { out.add(it) }
        return out.distinct()
    }

    suspend fun patchGuild(
        g: DiscordGuild,
        isWatched: Boolean? = null,
        respondToMentions: Boolean? = null,
        semanticTagging: Boolean? = null,
    ) {
        // Auto-tag is guild-wide. Writes used to hit only botId=null, so a leftover
        // bot row kept semanticTagging=1 and the switch snapped back on after refresh.
        val ids = if (semanticTagging != null && isWatched == null && respondToMentions == null) {
            discordAutoTagPatchBotIds(g.bots.map { it.botId })
        } else {
            guildPatchBots(g)
        }
        for (bid in ids) {
            DiscordParse.requireOk(
                withContext(Dispatchers.IO) {
                    api.updateGuildSettings(
                        guildId = g.guildId,
                        botId = bid,
                        isWatched = isWatched,
                        respondToMentions = respondToMentions,
                        semanticTagging = semanticTagging,
                    )
                },
            )
        }
    }

    suspend fun patchChannelWatch(g: DiscordGuild, ch: DiscordChannel, on: Boolean) {
        val memberIds = g.bots.map { it.botId }.filter { it > 0 }
        val chosen = guildBotIds.filter { it in memberIds }
        val targets = if (chosen.isNotEmpty()) chosen else memberIds
        val botIds = ArrayList<Int?>(targets.size.coerceAtLeast(1))
        if (targets.isEmpty()) {
            botIds.add(null)
        } else {
            targets.forEach { botIds.add(it) }
        }
        for (bid in botIds) {
            DiscordParse.requireOk(
                withContext(Dispatchers.IO) {
                    api.updateChannelSettings(
                        guildId = g.guildId,
                        channelId = ch.channelId,
                        botId = bid,
                        isEnabled = on,
                        isMuted = if (on) false else null,
                        channelName = ch.name,
                        channelType = ch.type,
                    )
                },
            )
        }
    }

    fun runOp(okStatus: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
                if (statusLine == "Connecting…" || statusLine.startsWith("Failed") || statusLine.contains("failed", true)) {
                    statusLine = okStatus
                }
            } catch (e: Exception) {
                statusLine = e.message ?: "failed"
            } finally {
                busy = false
            }
        }
    }

    fun openUser(key: DiscordProfileKey) {
        if (key.id.isBlank()) return
        profileKey = key
        profile = null
        profileMessages = emptyList()
        profileMessageHasMore = false
        profileMessagePage = 1
        profileTags = emptyList()
        profileTagsHasMore = false
        profileTagsOffset = 0
        runOp("Profile") { loadProfile(reset = true) }
    }

    LaunchedEffect(analyzingJobId) {
        val jobId = analyzingJobId
        if (jobId <= 0) return@LaunchedEffect
        var ticks = 0
        var misses = 0
        while (isActive && analyzingJobId == jobId) {
            delay(if (ticks == 0) 400 else 1500)
            ticks++
            val raw = withContext(Dispatchers.IO) { api.aiJob(jobId) }
            val err = DiscordJson.err(raw)
            if (err != null) {
                misses++
                statusLine = err
                if (misses >= 5) {
                    analyzingJobId = 0
                    break
                }
                continue
            }
            misses = 0
            val job = DiscordParse.aiJobWrap(raw)
            if (job.id <= 0) continue
            aiJobs = listOf(job) + aiJobs.filter { it.id != job.id }
            statusLine = if (job.kind == "analyze") {
                "Analyze ${job.processed}/${job.total} · ${job.status}"
            } else {
                "Tag ${job.processed}/${job.total} · ${job.tagged} tagged"
            }
            if (ticks % 2 == 0) {
                val others = aiJobs.filter { it.id != jobId && discordAiJobRunning(it) }
                for (other in others) {
                    val otherRaw = withContext(Dispatchers.IO) { api.aiJob(other.id) }
                    if (DiscordJson.err(otherRaw) != null) continue
                    val otherJob = DiscordParse.aiJobWrap(otherRaw)
                    if (otherJob.id > 0) {
                        aiJobs = listOf(otherJob) + aiJobs.filter { it.id != otherJob.id }
                    }
                }
            }
            if (!discordAiJobRunning(job)) {
                runCatching { loadAiJobs(reset = true) }
                val next = aiJobs.firstOrNull { it.id != job.id && discordAiJobRunning(it) }
                analyzingJobId = next?.id ?: 0
                statusLine = if (job.kind == "analyze") {
                    "Analyze ${job.status}"
                } else {
                    "Tag ${job.status} · ${job.tagged} tagged"
                }
                if (tab == DiscordTab.Ai) {
                    runCatching { loadAiActivity(reset = true) }
                }
                break
            }
            if (ticks % 3 == 0 && tab == DiscordTab.Ai && aiSub == "activity") {
                runCatching { loadAiActivity(reset = true) }
            }
        }
    }

    LaunchedEffect(tab, timeframe, selectedGuildId, selectedBotId) {
        if (tab != DiscordTab.Feed) return@LaunchedEffect
        while (isActive) {
            delay(2000)
            if (tab != DiscordTab.Feed || busy) continue
            runCatching { loadFeedLive() }
        }
    }

    LaunchedEffect(tab, timeframe, selectedGuildId, selectedBotId, userSort, userOrder, guildFilter, guildSort, auditAction, auditTf, mediaKind, mediaGuildId, mediaOrder, mediaLikedOnly, mediaHideStale, auditGuildId, userGuildId) {
        busy = true
        try {
            loadTab(tab)
        } catch (e: Exception) {
            statusLine = e.message ?: "failed"
        } finally {
            busy = false
        }
    }

    val headers = remember(tokenStore) { api.authHeaders() }

    CompositionLocalProvider(
        LocalDiscordOpenMedia provides { openedMedia = it },
    ) {
    Box(
        Modifier
            .fillMaxSize()
            .background(GrokifyColors.Void)
            .imePadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { if (!handleDiscordBack()) onBack() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = GrokifyColors.TextPrimary,
                    )
                }
                PluginFaviconImage(
                    pluginId = BuiltinPluginCatalog.DISCORD,
                    fallback = PluginIconKey.Forum,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Discord",
                        color = GrokifyColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp,
                    )
                    Text(
                        statusLine,
                        color = if (statusLine.contains("fail", true) || statusLine.contains("error", true)) {
                            GrokifyColors.GlowRose
                        } else {
                            GrokifyColors.TextDim
                        },
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 4.dp).size(18.dp),
                        strokeWidth = 2.dp,
                        color = GrokifyColors.GlowCyan,
                    )
                }
                IconButton(onClick = { runOp("Refreshed") { loadTab(tab) } }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = GrokifyColors.TextPrimary)
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DiscordToolbarChip("Feed", Icons.Default.Forum, tab == DiscordTab.Feed) { setTab(DiscordTab.Feed) }
                DiscordToolbarChip("Bots", Icons.Default.SmartToy, tab == DiscordTab.Bots) { setTab(DiscordTab.Bots) }
                DiscordToolbarChip("Guilds", Icons.Default.Groups, tab == DiscordTab.Guilds) { setTab(DiscordTab.Guilds) }
                DiscordToolbarChip("Users", Icons.Default.Person, tab == DiscordTab.Users) { setTab(DiscordTab.Users) }
                DiscordToolbarChip("Media", Icons.Default.Image, tab == DiscordTab.Media) { setTab(DiscordTab.Media) }
                DiscordToolbarChip("Roles", Icons.Default.Badge, tab == DiscordTab.Roles) { setTab(DiscordTab.Roles) }
                DiscordToolbarChip("Captchas", Icons.Default.Lock, tab == DiscordTab.Captchas) { setTab(DiscordTab.Captchas) }
                DiscordToolbarChip("Emoji", Icons.Default.Tag, tab == DiscordTab.Emojis) { setTab(DiscordTab.Emojis) }
                DiscordToolbarChip("Audits", Icons.AutoMirrored.Outlined.FactCheck, tab == DiscordTab.Audits) { setTab(DiscordTab.Audits) }
                DiscordToolbarChip("AI", Icons.Default.AutoAwesome, tab == DiscordTab.Ai) { setTab(DiscordTab.Ai) }
                DiscordToolbarChip("Settings", Icons.Default.Settings, tab == DiscordTab.Settings) { setTab(DiscordTab.Settings) }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    DiscordTab.Feed -> DiscordFeedTab(
                        messages = messages,
                        guilds = guilds,
                        bots = bots,
                        timeframe = timeframe,
                        selectedGuildId = selectedGuildId,
                        selectedBotId = selectedBotId,
                        search = search,
                        hasMore = messageHasMore,
                        total = messageTotal,
                        busy = busy,
                        onSearch = { search = it },
                        onSubmitSearch = {
                            feedAroundMessageId = ""
                            feedAroundAtMs = 0L
                            runOp("Search") { loadFeed(reset = true) }
                        },
                        onTimeframe = { setTf(it) },
                        onGuild = { id ->
                            if (feedAroundMessageId.isNotBlank() || feedAroundAtMs > 0L) {
                                feedAroundMessageId = ""
                                feedAroundAtMs = 0L
                                search = ""
                            }
                            setGuild(id)
                        },
                        onBot = { setBot(it) },
                        onMore = { runOp("Loaded more") { loadFeed(reset = false) } },
                        headers = headers,
                        onOpenUser = { openUser(it) },
                        aroundActive = feedAroundMessageId.isNotBlank() || feedAroundAtMs > 0L,
                        onClearAround = {
                            feedAroundMessageId = ""
                            feedAroundAtMs = 0L
                            runOp("Search") { loadFeed(reset = true) }
                        },
                    )
                    DiscordTab.Bots -> DiscordBotsTab(
                        bots = bots,
                        busy = busy,
                        onStart = { id ->
                            runOp("Started") {
                                DiscordParse.requireOk(withContext(Dispatchers.IO) { api.startBot(id) })
                                loadBots()
                            }
                        },
                        onStop = { id ->
                            runOp("Stopped") {
                                DiscordParse.requireOk(withContext(Dispatchers.IO) { api.stopBot(id) })
                                loadBots()
                            }
                        },
                        onToggleMentions = { bot, on ->
                            runOp("Updated") {
                                DiscordParse.requireOk(
                                    withContext(Dispatchers.IO) { api.updateBot(bot.id, respondMentions = on) },
                                )
                                loadBots()
                            }
                        },
                        onToggleReplies = { bot, on ->
                            runOp("Updated") {
                                DiscordParse.requireOk(
                                    withContext(Dispatchers.IO) { api.updateBot(bot.id, respondReplies = on) },
                                )
                                loadBots()
                            }
                        },
                        onCreate = { name, token, type, mentions, replies ->
                            runOp("Created") {
                                DiscordParse.requireOk(
                                    withContext(Dispatchers.IO) {
                                        api.createBot(name, token, type, mentions, replies)
                                    },
                                )
                                loadBots()
                            }
                        },
                        onDelete = { id ->
                            runOp("Deleted") {
                                DiscordParse.requireOk(withContext(Dispatchers.IO) { api.deleteBot(id) })
                                loadBots()
                            }
                        },
                    )
                    DiscordTab.Guilds -> DiscordGuildsTab(
                        guilds = guilds,
                        bots = bots,
                        selectedBotIds = guildBotIds,
                        channelsByGuild = channelsByGuild,
                        expandedId = expandedGuild,
                        busy = busy,
                        search = guildSearch,
                        filter = guildFilter,
                        sort = guildSort,
                        channelKind = guildChannelKind,
                        channelQuery = guildChannelQuery,
                        onToggleBot = { id ->
                            val next = if (id in guildBotIds) guildBotIds - id else guildBotIds + id
                            guildBotIds = next
                            store.guildBotIdsRaw = discordFormatBotIdSet(next)
                            runOp("Guilds") { loadGuilds() }
                        },
                        onSearch = { guildSearch = it },
                        onSubmitSearch = { runOp("Guilds") { loadGuilds() } },
                        onFilter = {
                            guildFilter = it
                            store.guildFilter = it
                        },
                        onSort = {
                            guildSort = it
                            store.guildSort = it
                        },
                        onExpand = { id ->
                            expandedGuild = if (expandedGuild == id) null else id
                            if (expandedGuild != null) {
                                runOp("Channels") {
                                    loadChannels(id, kind = guildChannelKind, search = guildChannelQuery)
                                }
                            }
                        },
                        onChannelKind = { k ->
                            guildChannelKind = k
                            if (k != "threads") guildChannelQuery = ""
                            val gid = expandedGuild
                            if (gid != null) {
                                runOp("Channels") {
                                    loadChannels(
                                        gid,
                                        kind = k,
                                        search = if (k == "threads") guildChannelQuery else "",
                                    )
                                }
                            }
                        },
                        onChannelQuery = { guildChannelQuery = it },
                        onSubmitChannelQuery = {
                            val gid = expandedGuild
                            if (gid != null) {
                                runOp("Channels") {
                                    loadChannels(gid, kind = guildChannelKind, search = guildChannelQuery)
                                }
                            }
                        },
                        onMoreChannels = {
                            val gid = expandedGuild
                            if (gid != null) {
                                runOp("Channels") {
                                    loadChannels(
                                        gid,
                                        kind = guildChannelKind,
                                        search = guildChannelQuery,
                                        append = true,
                                    )
                                }
                            }
                        },
                        onWatch = { g, on ->
                            runOp(if (on) "Watching" else "Unwatched") {
                                patchGuild(g, isWatched = on)
                                loadGuilds()
                            }
                        },
                        onMentions = { g, on ->
                            runOp("Updated") {
                                patchGuild(g, respondToMentions = on)
                                loadGuilds()
                            }
                        },
                        onAutoTag = { g, on ->
                            runOp(if (on) "Auto tagging on" else "Auto tagging off") {
                                patchGuild(g, semanticTagging = on)
                                loadGuilds()
                            }
                        },
                        onOpenFeed = { id ->
                            setGuild(id)
                            setTab(DiscordTab.Feed)
                        },
                        onChannelWatch = { g, ch, on ->
                            runOp("Channel") {
                                patchChannelWatch(g, ch, on)
                                loadChannels(g.guildId, kind = guildChannelKind, search = guildChannelQuery)
                            }
                        },
                    )
                    DiscordTab.Users -> DiscordUsersTab(
                        users = users,
                        total = userTotal,
                        search = search,
                        sort = userSort,
                        order = userOrder,
                        hasMore = userHasMore,
                        busy = busy,
                        headers = headers,
                        guilds = guilds,
                        selectedGuildId = userGuildId,
                        onSearch = { search = it },
                        onSubmit = { runOp("Users") { loadUsers(reset = true) } },
                        onSort = {
                            userSort = it
                            store.userSort = it
                        },
                        onOrder = {
                            userOrder = it
                            store.userOrder = it
                        },
                        onGuild = {
                            userGuildId = it
                            store.userGuildId = it
                        },
                        onMore = { runOp("Users") { loadUsers(reset = false) } },
                        onOpenUser = { openUser(it) },
                    )
                    DiscordTab.Media -> DiscordMediaTab(
                        items = media,
                        total = mediaTotal,
                        kind = mediaKind,
                        order = mediaOrder,
                        likedOnly = mediaLikedOnly,
                        hideStale = mediaHideStale,
                        guilds = guilds,
                        selectedGuildId = mediaGuildId,
                        hasMore = mediaHasMore,
                        busy = busy,
                        headers = headers,
                        onKind = {
                            mediaKind = it
                            store.mediaKind = it
                        },
                        onOrder = {
                            mediaOrder = it
                            store.mediaOrder = it
                        },
                        onLikedOnly = {
                            mediaLikedOnly = it
                            store.mediaLikedOnly = it
                        },
                        onHideStale = {
                            mediaHideStale = it
                            store.mediaHideStale = it
                        },
                        onDiscogram = {
                            discogram = true
                            discogramExitAt = 0L
                            discogramError = ""
                            discogramProfile = null
                            discogramProfileStart = null
                            scope.launch {
                                runCatching { loadDiscogram(reset = true) }
                                    .onFailure {
                                        discogramError = it.message ?: "failed"
                                        statusLine = discogramError
                                    }
                            }
                        },
                        onGuild = {
                            mediaGuildId = it
                            store.mediaGuildId = it
                        },
                        onMore = { runOp("Media") { loadMedia(reset = false) } },
                    )
                    DiscordTab.Roles -> DiscordRolesTab(
                        pickers = pickers,
                        bots = bots,
                        guilds = guilds,
                        channels = channelsByGuild.bundleFor(selectedGuildId, "channels").items,
                        selectedBotId = selectedBotId,
                        selectedGuildId = selectedGuildId,
                        busy = busy,
                        onBot = { setBot(it) },
                        onGuild = { id ->
                            setGuild(id)
                            runOp("Channels") { loadChannels(id, kind = "channels") }
                        },
                        onDeploy = { id ->
                            runOp("Deployed") {
                                DiscordParse.requireOk(withContext(Dispatchers.IO) { api.deployRolePicker(id) })
                                loadPickers()
                            }
                        },
                        onDelete = { id ->
                            runOp("Deleted") {
                                DiscordParse.requireOk(withContext(Dispatchers.IO) { api.deleteRolePicker(id) })
                                loadPickers()
                            }
                        },
                        onCreate = { botId, guildId, channelId, title, desc, roles, deploy ->
                            runOp("Created") {
                                DiscordParse.requireOk(
                                    withContext(Dispatchers.IO) {
                                        api.createRolePicker(botId, guildId, channelId, title, desc, roles, deploy)
                                    },
                                )
                                loadPickers()
                            }
                        },
                    )
                    DiscordTab.Captchas -> DiscordCaptchasTab(
                        captchas = captchas,
                        bots = bots,
                        guilds = guilds,
                        channels = channelsByGuild.bundleFor(selectedGuildId, "channels").items,
                        selectedBotId = selectedBotId,
                        selectedGuildId = selectedGuildId,
                        busy = busy,
                        onBot = { setBot(it) },
                        onGuild = { id ->
                            setGuild(id)
                            runOp("Channels") { loadChannels(id, kind = "channels") }
                        },
                        onDeploy = { id ->
                            runOp("Deployed") {
                                DiscordParse.requireOk(withContext(Dispatchers.IO) { api.deployCaptcha(id) })
                                loadCaptchas()
                            }
                        },
                        onDelete = { id ->
                            runOp("Deleted") {
                                DiscordParse.requireOk(withContext(Dispatchers.IO) { api.deleteCaptcha(id) })
                                loadCaptchas()
                            }
                        },
                        onCreate = { botId, guildId, channelId, postRole, title, desc, deploy ->
                            runOp("Created") {
                                DiscordParse.requireOk(
                                    withContext(Dispatchers.IO) {
                                        api.createCaptcha(
                                            botId, guildId, channelId, postRole, "", "", title, desc, deploy,
                                        )
                                    },
                                )
                                loadCaptchas()
                            }
                        },
                    )
                    DiscordTab.Emojis -> DiscordEmojisTab(
                        files = emojiFiles,
                        total = emojiTotal,
                        guildEmojis = guildEmojis,
                        guilds = guilds,
                        bots = bots,
                        selectedGuildId = selectedGuildId,
                        selectedBotId = selectedBotId,
                        search = search,
                        busy = busy,
                        emojiUrl = { api.emojiUrl(it) },
                        headers = headers,
                        onSearch = { search = it },
                        onSubmitSearch = { runOp("Emoji") { loadEmojis() } },
                        onGuild = { setGuild(it) },
                        onBot = { setBot(it) },
                        onAdd = { filename ->
                            val gid = selectedGuildId
                            val bid = selectedBotId
                            if (gid.isBlank() || bid <= 0) {
                                statusLine = "Pick a bot and guild first"
                            } else {
                                runOp("Added") {
                                    DiscordParse.requireOk(
                                        withContext(Dispatchers.IO) { api.addEmoji(bid, gid, filename) },
                                    )
                                    loadEmojis()
                                }
                            }
                        },
                        onDeleteGuild = { emojiId ->
                            val gid = selectedGuildId
                            val bid = selectedBotId
                            if (gid.isNotBlank() && bid > 0) {
                                runOp("Removed") {
                                    DiscordParse.requireOk(
                                        withContext(Dispatchers.IO) { api.deleteEmoji(bid, gid, emojiId) },
                                    )
                                    loadEmojis()
                                }
                            }
                        },
                    )
                    DiscordTab.Audits -> DiscordAuditsTab(
                        events = audits,
                        guilds = guilds,
                        selectedGuildId = auditGuildId,
                        action = auditAction,
                        timeframe = auditTf,
                        hasMore = auditHasMore,
                        busy = busy,
                        headers = headers,
                        onGuild = {
                            auditGuildId = it
                            store.auditGuildId = it
                        },
                        onAction = {
                            auditAction = it
                            store.auditAction = it
                        },
                        onTimeframe = {
                            auditTf = it
                            store.auditTf = it
                        },
                        onMore = { runOp("Audits") { loadAudits(reset = false) } },
                        onOpenUser = { openUser(it) },
                    )
                    DiscordTab.Ai -> DiscordAiTab(
                        bots = bots,
                        guilds = guilds,
                        channelsByGuild = channelsByGuild,
                        jobs = aiJobs,
                        results = aiResults,
                        selectedBotId = selectedBotId,
                        subTab = aiSub,
                        timeframe = aiTf,
                        fromDate = aiFrom,
                        toDate = aiTo,
                        limit = aiLimit,
                        skipTagged = aiSkipTagged,
                        prompt = aiPrompt,
                        sort = aiSort,
                        resultStatus = aiResultStatus,
                        resultGuildId = aiResultGuildId,
                        search = aiSearch,
                        expandedGuildId = aiExpandedGuild,
                        channelKind = aiChannelKind,
                        channelQuery = aiChannelQuery,
                        jobHasMore = aiJobHasMore,
                        resultHasMore = aiResultHasMore,
                        busy = busy,
                        analyzing = analyzingJobId > 0,
                        headers = headers,
                        onBot = { setBot(it) },
                        onSubTab = {
                            setAiSub(it)
                            if (it == "activity") {
                                runOp("AI") {
                                    loadAiJobs(reset = true)
                                    loadAiActivity(reset = true)
                                }
                            }
                        },
                        onTimeframe = {
                            aiTf = it
                            store.aiTf = it
                        },
                        onFromDate = { aiFrom = it },
                        onToDate = { aiTo = it },
                        onLimit = {
                            aiLimit = it
                            store.aiLimit = it
                        },
                        onSkipTagged = {
                            aiSkipTagged = it
                            store.aiSkipTagged = it
                        },
                        onPrompt = {
                            val next = it.take(4000)
                            aiPrompt = next
                            store.aiPrompt = next
                        },
                        onSort = {
                            aiSort = it
                            store.aiSort = it
                            runOp("AI") { loadAiActivity(reset = true) }
                        },
                        onResultStatus = {
                            aiResultStatus = it
                            runOp("AI") { loadAiActivity(reset = true) }
                        },
                        onResultGuild = {
                            aiResultGuildId = it
                            runOp("AI") { loadAiActivity(reset = true) }
                        },
                        onSearch = { aiSearch = it },
                        onSubmitSearch = { runOp("AI") { loadAiActivity(reset = true) } },
                        onExpandGuild = { id ->
                            aiExpandedGuild = if (aiExpandedGuild == id) null else id
                            val gid = aiExpandedGuild
                            if (gid != null) {
                                runOp("Channels") {
                                    loadChannels(gid, kind = aiChannelKind, search = aiChannelQuery)
                                }
                            }
                        },
                        onChannelKind = { k ->
                            aiChannelKind = k
                            if (k != "threads") aiChannelQuery = ""
                            val gid = aiExpandedGuild
                            if (gid != null) {
                                runOp("Channels") {
                                    loadChannels(
                                        gid,
                                        kind = k,
                                        search = if (k == "threads") aiChannelQuery else "",
                                    )
                                }
                            }
                        },
                        onChannelQuery = { aiChannelQuery = it },
                        onSubmitChannelQuery = {
                            val gid = aiExpandedGuild
                            if (gid != null) {
                                runOp("Channels") {
                                    loadChannels(gid, kind = aiChannelKind, search = aiChannelQuery)
                                }
                            }
                        },
                        onMoreChannels = {
                            val gid = aiExpandedGuild
                            if (gid != null) {
                                runOp("Channels") {
                                    loadChannels(
                                        gid,
                                        kind = aiChannelKind,
                                        search = aiChannelQuery,
                                        append = true,
                                    )
                                }
                            }
                        },
                        onMoreJobs = { runOp("Jobs") { loadAiJobs(reset = false) } },
                        onMoreResults = { runOp("AI") { loadAiActivity(reset = false) } },
                        onStart = { kind, gid, cid, uid, label ->
                            runOp("Started") { startAiJob(kind, gid, cid, uid, label) }
                        },
                        onCancel = { id ->
                            runOp("Cancelled") {
                                DiscordParse.requireOk(withContext(Dispatchers.IO) { api.aiAnalyzeCancel(id) })
                                loadAiJobs(reset = true)
                                analyzingJobId = aiJobs.firstOrNull { discordAiJobRunning(it) }?.id ?: 0
                            }
                        },
                        onOpenUser = { openUser(it) },
                    )
                    DiscordTab.Settings -> DiscordAiSettingsTab(
                        settings = aiSettings,
                        busy = busy,
                        voiceId = aiVoiceId,
                        preferDeviceTts = aiPreferDeviceTts,
                        hasXaiKey = hasXaiKey,
                        voicePreviewMsg = aiVoicePreviewMsg,
                        onProvider = { next ->
                            runOp("Models") {
                                saveAiSettings(provider = next, listing = next)
                                loadAiSettings(provider = next, refresh = true)
                            }
                        },
                        onModel = { id ->
                            val m = aiSettings.models.firstOrNull { it.id == id }
                            val effort = m?.let { model ->
                                if (aiSettings.reasoningEffort in model.reasoningEfforts) {
                                    aiSettings.reasoningEffort
                                } else {
                                    model.defaultReasoningEffort.ifBlank { "high" }
                                }
                            } ?: aiSettings.reasoningEffort
                            runOp("Saved") {
                                saveAiSettings(model = id, effort = effort)
                            }
                        },
                        onEffort = { effort ->
                            runOp("Saved") { saveAiSettings(effort = effort) }
                        },
                        onSaveKey = { key ->
                            runOp("Key saved") {
                                saveAiSettings(apiKey = key, listing = "spacexai")
                                loadAiSettings(provider = "spacexai", refresh = true)
                            }
                        },
                        onClearKey = {
                            runOp("Key cleared") {
                                saveAiSettings(clearKey = true, listing = "spacexai")
                                loadAiSettings(provider = "spacexai", refresh = true)
                            }
                        },
                        onRefresh = {
                            runOp("Models") {
                                loadAiSettings(
                                    provider = aiSettings.listingProvider.ifBlank { aiSettings.provider },
                                    refresh = true,
                                )
                            }
                        },
                        onVoiceId = { id ->
                            aiVoiceId = id
                            store.aiVoiceId = id
                            val tone = GROK_VOICES.firstOrNull { it.id.equals(id, ignoreCase = true) }?.tone
                            aiVoicePreviewMsg = tone
                        },
                        onPreferDeviceTts = {
                            aiPreferDeviceTts = it
                            store.aiPreferDeviceTts = it
                        },
                        onPreviewVoice = {
                            val v = GROK_VOICES.firstOrNull { it.id.equals(aiVoiceId, ignoreCase = true) }
                            val line = "Hi, I'm ${v?.label ?: "Grok"} — reading Discord analysis summaries."
                            aiVoicePreviewMsg = "Playing ${v?.label ?: aiVoiceId}…"
                            scope.launch(Dispatchers.IO) {
                                val speakOpts = JSONObject()
                                    .put("voice_id", store.aiVoiceId)
                                    .put("prefer_device", store.aiPreferDeviceTts)
                                    .put("language", "en")
                                    .toString()
                                val raw = HostAiClient.speak(appCtx, line, speakOpts)
                                val ok = runCatching { JSONObject(raw).optBoolean("ok", false) }.getOrDefault(false)
                                withContext(Dispatchers.Main) {
                                    aiVoicePreviewMsg = if (ok) {
                                        "Played ${v?.label ?: aiVoiceId}"
                                    } else {
                                        "Preview failed"
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
        if (profileKey != null) {
            DiscordProfileSheet(
                profile = profile,
                messages = profileMessages,
                messageHasMore = profileMessageHasMore,
                busy = busy,
                headers = headers,
                onBack = { closeProfile() },
                onMoreMessages = { runOp("Messages") { loadProfileMessages(reset = false) } },
                onOpenGuild = { gid ->
                    closeProfile()
                    setGuild(gid)
                    setTab(DiscordTab.Feed)
                },
                extraTags = profileTags,
                tagsHasMore = profileTagsHasMore,
                onMoreTags = { runOp("Tags") { loadProfileTags() } },
                analyzing = analyzingJobId > 0,
                onTag = { lim ->
                    val key = profileKey
                    if (key != null && key.id.isNotBlank()) {
                        val name = profile?.displayName?.ifBlank { profile?.username } ?: "profile"
                        runOp("Started") {
                            startAiJob("tag", "", "", key.id, name, lim, switchToActivity = false)
                        }
                    }
                },
                onAnalyze = { lim, prompt ->
                    val key = profileKey
                    if (key != null && key.id.isNotBlank()) {
                        val name = profile?.displayName?.ifBlank { profile?.username } ?: "profile"
                        runOp("Started") {
                            startAiJob(
                                "analyze",
                                "",
                                "",
                                key.id,
                                name,
                                lim,
                                promptOverride = prompt,
                                switchToActivity = false,
                            )
                        }
                    }
                },
            )
        }
        openedMedia?.let { att ->
            DiscordMediaPane(
                att = att,
                headers = headers,
                onBack = { openedMedia = null },
                onOpenUser = { key ->
                    openedMedia = null
                    openUser(key)
                },
                onDownload = { downloadMedia(it) },
                downloading = mediaDownloading,
            )
        }
        if (discogram) {
            DiscordDiscogramHost(
                feed = discogramFeed,
                feedHasMore = discogramHasMore,
                feedStartIndex = discogramCursor,
                profile = discogramProfile,
                profileItems = discogramProfileItems,
                profileHasMore = discogramProfileHasMore,
                profilePagerStart = discogramProfileStart,
                busy = discogramLoading,
                analyzing = analyzingJobId > 0,
                emptyMessage = discogramError,
                headers = headers,
                onClose = { requestDiscogramExit() },
                onCloseProfile = {
                    discogramProfile = null
                    discogramProfileItems = emptyList()
                    discogramProfileHasMore = false
                },
                onCloseProfilePager = { discogramProfileStart = null },
                onMoreFeed = {
                    scope.launch { runCatching { loadDiscogram(reset = false) } }
                },
                onMoreProfile = {
                    val did = discogramProfile?.discordId.orEmpty()
                    if (did.isNotBlank()) {
                        scope.launch { runCatching { loadCreatorMedia(reset = false, discordId = did) } }
                    }
                },
                onOpenCreator = { att ->
                    discogramExitAt = 0L
                    discogramProfile = att
                    discogramProfileStart = null
                    discogramProfileItems = emptyList()
                    scope.launch {
                        runCatching { loadCreatorMedia(reset = true, discordId = att.discordId) }
                            .onFailure { statusLine = it.message ?: "failed" }
                    }
                },
                onOpenProfileItem = { att ->
                    discogramProfileStart = att
                },
                onLike = { toggleMediaLike(it) },
                onFollow = { toggleMediaFollow(it) },
                onDownload = { downloadMedia(it) },
                onTag = { att ->
                    if (att.messageId.isBlank()) {
                        statusLine = "No message to tag"
                    } else {
                        runOp("Started") {
                            startAiJob(
                                kind = "tag",
                                guildId = att.guildId,
                                channelId = att.channelId,
                                userId = if (att.userId > 0) att.userId.toString() else att.discordId,
                                label = att.filename.ifBlank { "media" },
                                limitOverride = 1,
                                switchToActivity = false,
                                messageId = att.messageId,
                            )
                        }
                    }
                },
                onOpenGuild = { att ->
                    discogram = false
                    discogramProfile = null
                    discogramProfileStart = null
                    search = ""
                    feedAroundMessageId = ""
                    feedAroundAtMs = 0L
                    setGuild(att.guildId)
                    setTab(DiscordTab.Feed)
                },
                onOpenChannel = { att ->
                    discogram = false
                    discogramProfile = null
                    discogramProfileStart = null
                    search = att.channelId
                    feedAroundMessageId = att.messageId
                    feedAroundAtMs = att.createdAtMs
                    setTf("1h")
                    setGuild(att.guildId)
                    setTab(DiscordTab.Feed)
                },
                onFeedCursor = { index, att ->
                    discogramCursor = index
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                api.mediaPlaylistCursor(mediaGuildId, index, att?.id ?: 0)
                            }
                        }
                    }
                },
                onAnalyze = { att, prompt ->
                    if (att.messageId.isBlank()) {
                        statusLine = "No message to analyze"
                    } else {
                        runOp("Started") {
                            startAiJob(
                                kind = "analyze",
                                guildId = att.guildId,
                                channelId = att.channelId,
                                userId = if (att.userId > 0) att.userId.toString() else att.discordId,
                                label = att.filename.ifBlank { "media" },
                                limitOverride = 1,
                                promptOverride = prompt,
                                switchToActivity = false,
                                messageId = att.messageId,
                            )
                        }
                    }
                },
            )
        }
    }
    }
}
