package io.grokify.os.apps.gbot

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.grokify.os.GrokifyApp
import io.grokify.os.data.TokenStore
import io.grokify.os.ui.theme.GrokifyColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

private enum class GbotPanel { None, Bots, Autos, Inbox, Box, Setup }

@Composable
fun GbotPane(onBack: () -> Unit) {
    val context = LocalContext.current
    val appCtx = context.applicationContext
    val scope = rememberCoroutineScope()
    val store = remember { GbotStore(appCtx) }
    val tokenStore = remember {
        if (appCtx is GrokifyApp) appCtx.tokenStore else TokenStore(appCtx)
    }
    val api = remember {
        GbotApi {
            kotlinx.coroutines.runBlocking {
                tokenStore.tokenFlow.first()
            }
        }
    }

    var panel by remember { mutableStateOf(GbotPanel.None) }
    var snapshot by remember { mutableStateOf<GbotSnapshot?>(null) }
    var statusLine by remember { mutableStateOf("Connecting to gbotd…") }
    var busy by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf(store.selectedAgentId) }
    var bubbles by remember { mutableStateOf<List<GbotBubble>>(emptyList()) }
    var pending by remember { mutableStateOf<List<GbotPendingCard>>(emptyList()) }
    var box by remember { mutableStateOf<GbotBoxStatus?>(null) }
    var memories by remember { mutableStateOf<List<GbotMemory>>(emptyList()) }
    var automations by remember { mutableStateOf<List<GbotAutomation>>(emptyList()) }
    var workflows by remember { mutableStateOf<List<GbotWorkflow>>(emptyList()) }
    var skills by remember { mutableStateOf<List<GbotSkill>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var searchHits by remember { mutableStateOf<List<GbotSearchHit>>(emptyList()) }
    var listeners by remember { mutableStateOf<List<GbotListener>>(emptyList()) }
    var channels by remember { mutableStateOf<List<GbotChannel>>(emptyList()) }
    var boxMcp by remember { mutableStateOf<List<GbotConnector>>(emptyList()) }
    var tailLimit by remember { mutableIntStateOf(80) }
    val refreshMutex = remember { Mutex() }
    var tzDraft by remember { mutableStateOf("") }
    var loginHint by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var answerDrafts by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var secretDrafts by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var showCreate by remember { mutableStateOf(false) }
    var createName by remember { mutableStateOf("") }
    var createDesc by remember { mutableStateOf("") }
    var createKick by remember { mutableStateOf(true) }
    var vncUrl by remember { mutableStateOf<String?>(null) }
    var expandedAgent by remember { mutableStateOf<String?>(null) }
    var rawMethod by remember { mutableStateOf("getHostStatus") }
    var rawArgs by remember { mutableStateOf("{}") }
    var rawOut by remember { mutableStateOf("") }
    var transcriptCache by remember { mutableStateOf<Map<String, List<GbotBubble>>>(emptyMap()) }
    var watchAlerts by remember { mutableStateOf(store.watchEnabled) }
    var pendingFiles by remember { mutableStateOf<List<GbotLocalFile>>(emptyList()) }
    var replyTo by remember { mutableStateOf<GbotBubble?>(null) }
    var votes by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var webhookCred by remember { mutableStateOf<GbotWebhookCred?>(null) }
    var webhookName by remember { mutableStateOf("") }
    var showCreateWebhook by remember { mutableStateOf(false) }
    var webhookDraftName by remember { mutableStateOf("") }
    var webhookDraftPrompt by remember { mutableStateOf("") }
    var showImportWorkflow by remember { mutableStateOf(false) }
    var workflowDraftName by remember { mutableStateOf("") }
    var workflowDraftMd by remember { mutableStateOf("") }
    var teach by remember { mutableStateOf<GbotTeachStatus?>(null) }
    var confirmHostUpdate by remember { mutableStateOf(false) }
    var confirmBoxImage by remember { mutableStateOf(false) }
    var confirmClearMemories by remember { mutableStateOf(false) }
    var confirmDeleteBot by remember { mutableStateOf<GbotAgent?>(null) }
    var confirmWipeBots by remember { mutableStateOf(false) }
    var confirmResetBox by remember { mutableStateOf(false) }
    var deleteConfirmText by remember { mutableStateOf("") }
    var wipeConfirmText by remember { mutableStateOf("") }
    var showCookieImport by remember { mutableStateOf(false) }
    var cookieDraft by remember { mutableStateOf("") }
    var showJoinRoom by remember { mutableStateOf(false) }
    var joinLinkDraft by remember { mutableStateOf("") }
    var shareLink by remember { mutableStateOf<GbotShareLink?>(null) }
    var sharing by remember { mutableStateOf<GbotSharingState?>(null) }
    var skillPublish by remember { mutableStateOf<GbotSkillPublish?>(null) }
    var secrets by remember { mutableStateOf<GbotSecretsStatus?>(null) }
    var publishWorkflowId by remember { mutableStateOf<String?>(null) }
    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                uris.mapNotNull { GbotFiles.fromUri(appCtx, it) }
            }
            if (loaded.isEmpty()) {
                statusLine = "Could not read attachment (max 4 MB)"
            } else {
                pendingFiles = (pendingFiles + loaded).take(8)
                statusLine = "Attached ${loaded.size} file${if (loaded.size == 1) "" else "s"}"
            }
        }
    }

    val agents = snapshot?.agents.orEmpty()
    val selected = agents.firstOrNull { it.id == selectedId } ?: agents.firstOrNull()
    val selectedAgentId = selected?.id.orEmpty()
    val mineAutos = automations
        .filter { it.agentId.isBlank() || it.agentId == selectedAgentId }
        .ifEmpty { snapshot?.automations.orEmpty().filter { it.agentId == selectedAgentId } }
    val autosLive = mineAutos.count { it.isRunning }

    fun setSelected(id: String) {
        val changed = selectedId != id
        selectedId = id
        store.selectedAgentId = id
        if (changed) {
            bubbles = transcriptCache[id].orEmpty()
            tailLimit = 80
            replyTo = null
            pendingFiles = emptyList()
        }
    }

    fun handleBack() {
        when {
            showCreate -> showCreate = false
            vncUrl != null -> vncUrl = null
            expandedAgent != null -> expandedAgent = null
            panel != GbotPanel.None -> panel = GbotPanel.None
            else -> onBack()
        }
    }

    BackHandler(
        enabled = showCreate || vncUrl != null || expandedAgent != null || panel != GbotPanel.None,
    ) {
        handleBack()
    }

    fun cardDraft(card: GbotPendingCard) = answerDrafts[card.key()].orEmpty()
    fun secretDraft(card: GbotPendingCard) = secretDrafts[card.key()].orEmpty()

    suspend fun enrichCards(cards: List<GbotPendingCard>): List<GbotPendingCard> {
        if (cards.isEmpty()) return cards
        val extra = ArrayList<GbotBubble>()
        val cache = transcriptCache.toMutableMap()
        for ((aid, agentCards) in cards.groupBy { it.agentId }) {
            if (aid.isBlank()) continue
            val needed = agentCards.map { it.entryId }.toSet()
            val cached = cache[aid]
            if (cached != null && needed.all { id -> cached.any { it.id == id } }) {
                extra += cached
                continue
            }
            val raw = withContext(Dispatchers.IO) { api.transcript(aid, 200) }
            val data = if (raw.optBoolean("ok", false)) {
                raw.opt("data")
            } else {
                val tail = withContext(Dispatchers.IO) { api.tail(aid, 200) }
                if (tail.optBoolean("ok", false)) tail.opt("data") else null
            }
            val parsedBubbles = GbotParse.bubbles(data, aid)
            cache[aid] = parsedBubbles
            extra += parsedBubbles
        }
        transcriptCache = cache
        return GbotParse.enrichPending(cards, extra)
    }

    suspend fun loadSnapshot(): GbotSnapshot {
        val raw = withContext(Dispatchers.IO) { api.snapshot() }
        val parsed = GbotParse.coalesceSnapshot(snapshot, GbotParse.snapshot(raw))
        snapshot = parsed
        GbotWatch.onSnapshot(appCtx, parsed)
        if (parsed.error != null) {
            statusLine = parsed.error
        } else {
            val health = parsed.health
            val auth = parsed.auth
            val bits = mutableListOf<String>()
            bits += if (auth?.kind == "logged-in") "signed in" else (auth?.kind ?: "auth?")
            if (auth?.boxConnected == true) bits += "box"
            if (parsed.computer?.registered == true) bits += parsed.computer.computerLabel.ifBlank { "computer" }
            else if (parsed.computer != null) bits += "computer off"
            if (health?.busy == true) bits += if (health.busyOnlyAwaitingApproval) "awaiting you" else "busy"
            if (parsed.pending.isNotEmpty()) bits += "${parsed.pending.size} pending"
            statusLine = bits.joinToString(" · ")
        }
        if (selectedId.isBlank() && parsed.agents.isNotEmpty()) {
            setSelected(parsed.agents.first().id)
        } else if (selectedId.isNotBlank() && parsed.agents.none { it.id == selectedId } && parsed.agents.isNotEmpty()) {
            setSelected(parsed.agents.first().id)
        }
        val merged = GbotParse.mergePending(pending, parsed.pending)
        pending = if (merged.any { it.prompt.isBlank() && it.options.isEmpty() }) {
            GbotParse.mergePending(merged, enrichCards(merged))
        } else {
            merged
        }
        return parsed
    }

    suspend fun loadTail(agentId: String) {
        if (agentId.isBlank()) return
        val raw = withContext(Dispatchers.IO) { api.tail(agentId, tailLimit) }
        val err = GbotParse.apiError(raw)
        if (err.isNotBlank() && !raw.optBoolean("ok", false)) {
            statusLine = err
            return
        }
        val parsedBubbles = GbotParse.bubbles(raw.opt("data"), agentId)
        val previous = when {
            bubbles.any { it.agentId == agentId } -> bubbles
            else -> transcriptCache[agentId].orEmpty()
        }
        val merged = GbotParse.mergeBubbles(previous, parsedBubbles)
        transcriptCache = transcriptCache + (agentId to merged)
        if (selectedId == agentId || store.selectedAgentId == agentId) {
            bubbles = merged
        }
        pending = GbotParse.enrichPending(pending, merged)
    }

    suspend fun loadBox(agentId: String) {
        if (agentId.isBlank()) return
        val raw = withContext(Dispatchers.IO) { api.boxStatus(agentId) }
        if (!raw.optBoolean("ok", false)) return
        box = GbotParse.box(raw.optJSONObject("data"))
        val teachRaw = withContext(Dispatchers.IO) { api.teachStatus() }
        if (teachRaw.optBoolean("ok", false)) {
            teach = GbotParse.teachStatus(teachRaw.opt("data"))
        }
    }

    suspend fun loadListeners() {
        val raw = withContext(Dispatchers.IO) { api.listeners() }
        if (raw.optBoolean("ok", false)) listeners = GbotParse.listeners(raw.opt("data"))
    }

    suspend fun loadSkills() {
        val raw = withContext(Dispatchers.IO) { api.skills() }
        if (raw.optBoolean("ok", false)) skills = GbotParse.skills(raw.opt("data"))
        val pub = withContext(Dispatchers.IO) { api.skillPublishTargets() }
        if (pub.optBoolean("ok", false)) skillPublish = GbotParse.skillPublish(pub.opt("data"))
    }

    suspend fun loadSharing() {
        val raw = withContext(Dispatchers.IO) { api.sharingState() }
        if (raw.optBoolean("ok", false)) sharing = GbotParse.sharing(raw.opt("data"))
        val sec = withContext(Dispatchers.IO) { api.secretsStatus() }
        if (sec.optBoolean("ok", false)) secrets = GbotParse.secretsStatus(sec.opt("data"))
    }

    suspend fun loadAgentExtras(agentId: String) {
        if (agentId.isBlank()) return
        val mem = withContext(Dispatchers.IO) { api.memories(agentId) }
        if (mem.optBoolean("ok", false)) memories = GbotParse.memories(mem.opt("data"))
        val auto = withContext(Dispatchers.IO) { api.automations(agentId) }
        if (auto.optBoolean("ok", false)) automations = GbotParse.automations(auto.opt("data"), agentId)
        val wf = withContext(Dispatchers.IO) { api.workflows(agentId) }
        if (wf.optBoolean("ok", false)) workflows = GbotParse.workflows(wf.opt("data"))
        val ch = withContext(Dispatchers.IO) { api.channels(agentId) }
        if (ch.optBoolean("ok", false)) channels = GbotParse.channels(ch.opt("data"))
        val knownIds = (snapshot?.settings?.mcpServers.orEmpty() + boxMcp).map { it.id }.distinct()
        val mcp = withContext(Dispatchers.IO) { api.listBoxMcp(knownIds) }
        if (mcp.optBoolean("ok", false)) {
            val parsed = GbotParse.connectors(mcp.opt("data"), source = "box")
            boxMcp = parsed.ifEmpty { snapshot?.settings?.mcpServers.orEmpty() }
        }
        loadListeners()
    }

    suspend fun refreshAll() {
        refreshMutex.withLock {
            loadSnapshot()
            val id = store.selectedAgentId.ifBlank { snapshot?.agents?.firstOrNull()?.id.orEmpty() }
            if (id.isNotBlank()) loadTail(id)
            when (panel) {
                GbotPanel.Box -> if (id.isNotBlank()) loadBox(id)
                GbotPanel.Autos -> if (id.isNotBlank()) loadAgentExtras(id)
                GbotPanel.Bots -> {
                    val extraId = expandedAgent ?: id
                    if (extraId.isNotBlank()) loadAgentExtras(extraId)
                }
                GbotPanel.Setup -> {
                    loadListeners()
                    loadSharing()
                    if (skills.isEmpty()) loadSkills() else {
                        val pub = withContext(Dispatchers.IO) { api.skillPublishTargets() }
                        if (pub.optBoolean("ok", false)) skillPublish = GbotParse.skillPublish(pub.opt("data"))
                    }
                }
                else -> {}
            }
        }
    }

    fun requireOk(raw: JSONObject): JSONObject {
        if (!raw.optBoolean("ok", false)) {
            throw IllegalStateException(GbotParse.apiError(raw).ifBlank { "request_failed" })
        }
        return raw
    }

    fun runOp(label: String, work: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                work()
                statusLine = label
                refreshAll()
            } catch (e: Exception) {
                statusLine = e.message ?: "failed"
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        GbotWatch.sync(appCtx)
        val token = tokenStore.tokenFlow.first()?.trim().orEmpty()
        if (token.isBlank()) {
            statusLine = "Save your device token on Home first"
            return@LaunchedEffect
        }
        refreshAll()
    }

    LaunchedEffect(selectedAgentId) {
        if (selectedAgentId.isNotBlank()) {
            runCatching { withContext(Dispatchers.IO) { api.markRead(selectedAgentId) } }
        }
        while (isActive) {
            delay(if (sending) 1600L else 2800L)
            if (!busy && !sending) {
                runCatching { refreshAll() }
            }
        }
    }

    LaunchedEffect(panel, selectedAgentId) {
        when (panel) {
            GbotPanel.Setup -> {
                val tz = snapshot?.settings?.userTimeZoneOverride
                    ?: snapshot?.settings?.userTimeZone
                    ?: ""
                if (tzDraft.isBlank() && tz.isNotBlank()) tzDraft = tz
                loadListeners()
                loadSharing()
                if (skills.isEmpty()) loadSkills()
            }
            GbotPanel.Box -> if (selectedAgentId.isNotBlank()) loadBox(selectedAgentId)
            GbotPanel.Inbox -> if (pending.isNotEmpty()) {
                pending = GbotParse.mergePending(pending, enrichCards(pending))
            }
            GbotPanel.Autos -> if (selectedAgentId.isNotBlank()) loadAgentExtras(selectedAgentId)
            GbotPanel.Bots -> {
                val extraId = expandedAgent ?: selectedAgentId
                if (extraId.isNotBlank()) loadAgentExtras(extraId)
            }
            else -> {}
        }
    }

    val minePending = pending.filter { it.agentId == selectedAgentId }
    val pendingByEntry = minePending.associateBy { it.entryId }
    val detailAgentId = expandedAgent ?: selectedAgentId
    val detailConnectors = run {
        val fromCards = GbotParse.connectorsFromBubbles(
            transcriptCache[detailAgentId].orEmpty().ifEmpty {
                if (detailAgentId == selectedAgentId) bubbles else emptyList()
            },
        )
        (snapshot?.settings?.mcpServers.orEmpty() + boxMcp + fromCards)
            .distinctBy { it.id.ifBlank { it.name } }
    }

    val settleApprove: (GbotPendingCard, Boolean) -> Unit = { card, always ->
        runOp("Approved") { settleCard(api, card, approve = true, always = always) }
    }
    val settleDeny: (GbotPendingCard, Boolean) -> Unit = { card, never ->
        runOp("Denied") { settleCard(api, card, approve = false, never = never) }
    }
    val settleAnswer: (GbotPendingCard, String) -> Unit = { card, value ->
        runOp("Answered") {
            withContext(Dispatchers.IO) { api.answerWidget(card.agentId, card.entryId, value) }
            answerDrafts = answerDrafts - card.key()
        }
    }
    val settleDismiss: (GbotPendingCard) -> Unit = { card ->
        runOp("Dismissed") {
            withContext(Dispatchers.IO) { api.dismissWidget(card.agentId, card.entryId) }
        }
    }
    val settleSecret: (GbotPendingCard, String) -> Unit = { card, value ->
        runOp("Secret sent") {
            withContext(Dispatchers.IO) { api.submitSecret(card.agentId, card.entryId, value) }
            secretDrafts = secretDrafts - card.key()
        }
    }
    val settleHandback: (GbotPendingCard, Boolean) -> Unit = { card, dismiss ->
        runOp("Handed back") {
            withContext(Dispatchers.IO) { api.handBack(card.agentId, dismiss) }
        }
    }

    if (vncUrl != null) {
        GbotVncPane(
            url = vncUrl!!,
            title = selected?.name ?: "Computer",
            onBack = { vncUrl = null },
        )
        return
    }

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
                IconButton(onClick = { handleBack() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = GrokifyColors.TextPrimary,
                    )
                }
                GbotAvatar(selected, size = 32)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        selected?.name ?: "Grok Bot",
                        color = GrokifyColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        statusLine,
                        color = if (snapshot?.error != null) GrokifyColors.GlowRose else GrokifyColors.TextDim,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (busy || sending) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(18.dp),
                        strokeWidth = 2.dp,
                        color = GrokifyColors.GlowCyan,
                    )
                }
                if (selected?.isRunning == true || selected?.isComposing == true) {
                    IconButton(
                        onClick = {
                            val id = selectedAgentId
                            if (id.isBlank()) return@IconButton
                            runOp("Stopped") {
                                requireOk(withContext(Dispatchers.IO) { api.interrupt(id) })
                            }
                        },
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop", tint = GrokifyColors.GlowRose)
                    }
                }
                IconButton(onClick = { runOp("Refreshed") { refreshAll() } }) {
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
                GbotToolbarChip(
                    label = "Bots",
                    icon = Icons.Default.SmartToy,
                    active = panel == GbotPanel.Bots,
                    onClick = { panel = if (panel == GbotPanel.Bots) GbotPanel.None else GbotPanel.Bots },
                )
                GbotToolbarChip(
                    label = if (autosLive > 0) "Autos · live" else "Autos",
                    icon = Icons.Default.Schedule,
                    active = panel == GbotPanel.Autos,
                    alert = autosLive > 0,
                    onClick = { panel = if (panel == GbotPanel.Autos) GbotPanel.None else GbotPanel.Autos },
                )
                GbotToolbarChip(
                    label = if (pending.isEmpty()) "Inbox" else "Inbox · ${pending.size}",
                    icon = Icons.Default.Inbox,
                    active = panel == GbotPanel.Inbox,
                    alert = pending.isNotEmpty(),
                    onClick = { panel = if (panel == GbotPanel.Inbox) GbotPanel.None else GbotPanel.Inbox },
                )
                GbotToolbarChip(
                    label = "Computer",
                    icon = Icons.Default.Computer,
                    active = panel == GbotPanel.Box,
                    onClick = {
                        panel = if (panel == GbotPanel.Box) GbotPanel.None else GbotPanel.Box
                        if (panel == GbotPanel.Box && selectedAgentId.isNotBlank()) {
                            scope.launch { loadBox(selectedAgentId) }
                        }
                    },
                )
                GbotToolbarChip(
                    label = "Setup",
                    icon = Icons.Default.Settings,
                    active = panel == GbotPanel.Setup,
                    onClick = { panel = if (panel == GbotPanel.Setup) GbotPanel.None else GbotPanel.Setup },
                )
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
            when (panel) {
            GbotPanel.None -> GbotChatTab(
                agent = selected,
                bubbles = bubbles,
                pendingByEntry = pendingByEntry,
                unmatchedPending = minePending.filter { card -> bubbles.none { it.id == card.entryId } },
                draft = draft,
                busy = busy || sending,
                canLoadEarlier = bubbles.isNotEmpty() && tailLimit < 400,
                answerDraftOf = { cardDraft(it) },
                secretDraftOf = { secretDraft(it) },
                onAnswerDraft = { card, v -> answerDrafts = answerDrafts + (card.key() to v) },
                onSecretDraft = { card, v -> secretDrafts = secretDrafts + (card.key() to v) },
                onLoadEarlier = {
                    tailLimit = (tailLimit + 80).coerceAtMost(400)
                    runOp("Loaded earlier") { loadTail(selectedAgentId) }
                },
                onDraft = { draft = it },
                running = selected?.isRunning == true || selected?.isComposing == true,
                pendingNames = pendingFiles.map { it.name },
                replyLabel = replyTo?.text?.take(48)?.ifBlank { replyTo?.id },
                onAttach = { pickFiles.launch(arrayOf("*/*")) },
                onRemoveAttach = { index ->
                    pendingFiles = pendingFiles.filterIndexed { i, _ -> i != index }
                },
                onClearReply = { replyTo = null },
                onReply = { bubble -> replyTo = bubble },
                onVote = { bubble, action ->
                    val id = selectedAgentId
                    if (id.isBlank()) return@GbotChatTab
                    runOp(if (action == "revert") "Vote cleared" else "Voted") {
                        requireOk(withContext(Dispatchers.IO) { api.vote(id, bubble.id, action) })
                        votes = if (action == "revert") votes - bubble.id else votes + (bubble.id to action)
                    }
                },
                votes = votes,
                onStop = {
                    val id = selectedAgentId
                    if (id.isBlank()) return@GbotChatTab
                    runOp("Stopped") {
                        requireOk(withContext(Dispatchers.IO) { api.interrupt(id) })
                    }
                },
                onSend = {
                    val text = draft.trim()
                    val id = selectedAgentId
                    val files = pendingFiles
                    val replyId = replyTo?.id
                    if ((text.isEmpty() && files.isEmpty()) || id.isBlank() || sending || busy) return@GbotChatTab
                    draft = ""
                    pendingFiles = emptyList()
                    replyTo = null
                    sending = true
                    scope.launch {
                        try {
                            val paths = ArrayList<String>(files.size)
                            val names = ArrayList<String>(files.size)
                            for (file in files) {
                                val uploaded = requireOk(
                                    withContext(Dispatchers.IO) {
                                        api.uploadAttachment(id, file.name, file.bytesBase64)
                                    },
                                )
                                val path = GbotParse.uploadedPath(uploaded.opt("data"))
                                if (path.isBlank()) throw IllegalStateException("upload returned no path")
                                paths.add(path)
                                names.add(file.name)
                            }
                            requireOk(
                                withContext(Dispatchers.IO) {
                                    api.send(
                                        agentId = id,
                                        prompt = text,
                                        attachmentPaths = paths,
                                        attachmentNames = names,
                                        replyToId = replyId,
                                    )
                                },
                            )
                            statusLine = if (files.isEmpty()) "Sent" else "Sent · ${files.size} file${if (files.size == 1) "" else "s"}"
                            loadTail(id)
                            loadSnapshot()
                        } catch (e: Exception) {
                            statusLine = e.message ?: "send failed"
                            draft = text
                            pendingFiles = files
                            if (!replyId.isNullOrBlank()) {
                                replyTo = bubbles.firstOrNull { it.id == replyId }
                            }
                        } finally {
                            sending = false
                        }
                    }
                },
                onApprove = settleApprove,
                onDeny = settleDeny,
                onAnswer = settleAnswer,
                onDismiss = settleDismiss,
                onSecret = settleSecret,
                onHandback = settleHandback,
            )
            GbotPanel.Bots -> GbotBotsTab(
                    agents = agents,
                    selectedId = selectedAgentId,
                    expandedId = expandedAgent,
                    memories = memories,
                    automations = automations,
                    workflows = workflows,
                    searchQuery = searchQuery,
                    searchHits = searchHits,
                    onSearchQuery = { searchQuery = it },
                    onSearch = {
                        val q = searchQuery.trim()
                        if (q.isBlank()) {
                            searchHits = emptyList()
                            return@GbotBotsTab
                        }
                        runOp("Search") {
                            val raw = withContext(Dispatchers.IO) { api.search(q) }
                            requireOk(raw)
                            searchHits = GbotParse.searchHits(raw.opt("data"))
                            statusLine = "${searchHits.size} hits"
                        }
                    },
                    onOpenHit = { id ->
                        setSelected(id)
                        panel = GbotPanel.None
                        scope.launch { loadTail(id) }
                    },
                    onSelect = {
                        setSelected(it)
                        tailLimit = 80
                        panel = GbotPanel.None
                        scope.launch { loadTail(it) }
                    },
                    onExpand = { id ->
                        expandedAgent = if (expandedAgent == id) null else id
                        if (expandedAgent != null) {
                            scope.launch { loadAgentExtras(id) }
                        }
                    },
                    onSwitch = { id ->
                        runOp("Switched box") { requireOk(withContext(Dispatchers.IO) { api.openAgent(id) }) }
                    },
                    onKickstart = { id ->
                        runOp("Kickstarted") { requireOk(withContext(Dispatchers.IO) { api.kickstart(id) }) }
                    },
                    onDuplicate = { id ->
                        runOp("Duplicated") {
                            val raw = requireOk(withContext(Dispatchers.IO) { api.duplicate(id) })
                            val created = GbotParse.createdAgentId(raw.opt("data"))
                            if (created != null) setSelected(created.first)
                        }
                    },
                    onToggleAuto = { agentId, autoId, enabled ->
                        runOp(if (enabled) "Automation on" else "Automation off") {
                            requireOk(
                                withContext(Dispatchers.IO) {
                                    api.setAutomationEnabled(agentId, autoId, enabled)
                                },
                            )
                            loadAgentExtras(agentId)
                        }
                    },
                    onRunAuto = { agentId, autoId ->
                        runOp("Automation run") {
                            requireOk(withContext(Dispatchers.IO) { api.runAutomation(agentId, autoId) })
                        }
                    },
                    onRunWorkflow = { agentId, workflowId ->
                        runOp("Workflow run") {
                            requireOk(withContext(Dispatchers.IO) { api.runWorkflow(agentId, workflowId) })
                        }
                    },
                    onCreate = { showCreate = true },
                    onDeleteMemory = { agentId, memoryId ->
                        runOp("Memory deleted") {
                            requireOk(withContext(Dispatchers.IO) { api.deleteMemory(agentId, memoryId) })
                            loadAgentExtras(agentId)
                        }
                    },
                    onClearMemories = { confirmClearMemories = true },
                    onImportWorkflow = { showImportWorkflow = true },
                    onDeleteBot = { id ->
                        confirmDeleteBot = agents.firstOrNull { it.id == id }
                        deleteConfirmText = ""
                    },
                    onShareBot = { id ->
                        runOp("Share link") {
                            val raw = requireOk(withContext(Dispatchers.IO) { api.createRoomFromAgent(id) })
                            shareLink = GbotParse.shareLink(raw.opt("data"))
                                ?: throw IllegalStateException("No share URL")
                        }
                    },
                    onPublishWorkflow = { workflowId ->
                        publishWorkflowId = workflowId
                        if (skillPublish == null) {
                            scope.launch { loadSkills() }
                        }
                    },
                    connectors = detailConnectors,
                    channels = channels,
                    listeners = listeners,
                    onAddConnector = { id ->
                        runOp("Add connector") {
                            requireOk(withContext(Dispatchers.IO) { api.runWorkflow(id, "add-connector") })
                            setSelected(id)
                            panel = GbotPanel.None
                            loadTail(id)
                        }
                    },
                    onRefreshMcp = {
                        runOp("MCP refreshed") {
                            withContext(Dispatchers.IO) { api.refreshMcp() }
                            loadAgentExtras(expandedAgent ?: selectedAgentId)
                        }
                    },
                    onConnectListener = { platform ->
                        runOp("Connect $platform") {
                            val raw = withContext(Dispatchers.IO) { api.mcpConnectUrl(platform) }
                            val url = GbotParse.connectUrl(raw.opt("data"))
                            if (url.isBlank()) {
                                statusLine = GbotParse.apiError(raw).ifBlank { "No connect URL" }
                            } else {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                appCtx.startActivity(intent)
                                statusLine = "Opened $platform login"
                            }
                        }
                    },
                )
            GbotPanel.Autos -> GbotAutosTab(
                    agentName = selected?.name.orEmpty(),
                    automations = mineAutos,
                    busy = busy,
                    timeZone = snapshot?.settings?.userTimeZoneOverride?.ifBlank { null }
                        ?: snapshot?.settings?.userTimeZone.orEmpty(),
                    onToggle = { autoId, enabled ->
                        val id = selectedAgentId
                        if (id.isBlank()) return@GbotAutosTab
                        runOp(if (enabled) "Automation on" else "Automation off") {
                            requireOk(
                                withContext(Dispatchers.IO) {
                                    api.setAutomationEnabled(id, autoId, enabled)
                                },
                            )
                            loadAgentExtras(id)
                        }
                    },
                    onRun = { autoId ->
                        val id = selectedAgentId
                        if (id.isBlank()) return@GbotAutosTab
                        runOp("Automation run") {
                            requireOk(withContext(Dispatchers.IO) { api.runAutomation(id, autoId) })
                        }
                    },
                    onSave = { auto, prompt, cron ->
                        val id = selectedAgentId.ifBlank { auto.agentId }
                        if (id.isBlank()) return@GbotAutosTab
                        if (prompt.isBlank()) {
                            statusLine = "Prompt is empty — refresh this sheet first"
                            return@GbotAutosTab
                        }
                        val trigger = when {
                            auto.triggerType == "webhook" -> JSONObject().put("type", "webhook")
                            GbotCron.looksLikeCron(cron) -> GbotCron.mergeTrigger(auto.triggerJson, cron)
                            auto.triggerJson.isNotBlank() -> runCatching { JSONObject(auto.triggerJson) }.getOrNull()
                            else -> null
                        }
                        if (trigger == null) {
                            statusLine = "Need a 5-field cron schedule"
                            return@GbotAutosTab
                        }
                        runOp("Automation saved") {
                            requireOk(
                                withContext(Dispatchers.IO) {
                                    api.updateAutomation(
                                        id,
                                        auto.id,
                                        auto.name,
                                        prompt,
                                        trigger,
                                        auto.enabled,
                                    )
                                },
                            )
                            loadAgentExtras(id)
                        }
                    },
                    onWebhook = { auto ->
                        val id = selectedAgentId.ifBlank { auto.agentId }
                        if (id.isBlank()) return@GbotAutosTab
                        runOp("Webhook credential") {
                            val raw = requireOk(
                                withContext(Dispatchers.IO) { api.webhookCredential(id, auto.id) },
                            )
                            val cred = GbotParse.webhookCred(raw.opt("data"))
                                ?: throw IllegalStateException("No webhook URL")
                            webhookName = auto.name
                            webhookCred = cred
                        }
                    },
                    onCreateWebhook = { showCreateWebhook = true },
                    onDelete = { auto ->
                        val id = selectedAgentId.ifBlank { auto.agentId }
                        if (id.isBlank()) return@GbotAutosTab
                        runOp("Automation deleted") {
                            requireOk(withContext(Dispatchers.IO) { api.deleteAutomation(id, auto.id) })
                            loadAgentExtras(id)
                        }
                    },
                )
            GbotPanel.Inbox -> GbotInboxTab(
                    agents = agents,
                    pending = pending,
                    busy = busy,
                    answerDraftOf = { cardDraft(it) },
                    secretDraftOf = { secretDraft(it) },
                    onAnswerDraft = { card, v -> answerDrafts = answerDrafts + (card.key() to v) },
                    onSecretDraft = { card, v -> secretDrafts = secretDrafts + (card.key() to v) },
                    onOpen = { id ->
                        setSelected(id)
                        panel = GbotPanel.None
                    },
                    onApprove = settleApprove,
                    onDeny = settleDeny,
                    onAnswer = settleAnswer,
                    onDismiss = settleDismiss,
                    onSecret = settleSecret,
                    onHandback = settleHandback,
                )
            GbotPanel.Box -> GbotBoxTab(
                    agent = selected,
                    box = box,
                    busy = busy,
                    teach = teach,
                    hostUpdateAvailable = snapshot?.host?.hostUpdateAvailable == true || box?.hostUpdateAvailable == true,
                    onStartTeach = {
                        runOp("Teach recording started") {
                            requireOk(withContext(Dispatchers.IO) { api.startTeach(selectedAgentId) })
                            loadBox(selectedAgentId)
                        }
                    },
                    onStopTeach = {
                        runOp("Teach recording stopped") {
                            requireOk(withContext(Dispatchers.IO) { api.stopTeach(selectedAgentId) })
                            loadBox(selectedAgentId)
                        }
                    },
                    onDiskSaver = {
                        val id = selectedAgentId
                        runOp("Disk saver audit") {
                            requireOk(withContext(Dispatchers.IO) { api.diskSaver(id) })
                        }
                    },
                    onHostUpdate = { confirmHostUpdate = true },
                    onBoxImage = { confirmBoxImage = true },
                    onResetBox = { confirmResetBox = true },
                    onEnsure = {
                        val id = selectedAgentId
                        runOp("Ensuring computer…") {
                            requireOk(withContext(Dispatchers.IO) { api.ensureBox(id) })
                            loadBox(id)
                        }
                    },
                    onHandback = {
                        val id = selectedAgentId
                        runOp("Handed back") {
                            withContext(Dispatchers.IO) { api.handBack(id, false) }
                            loadBox(id)
                        }
                    },
                    onOpenVnc = { url ->
                        val id = selectedAgentId
                        runOp("Opening computer") {
                            val local = url.ifBlank { box?.vncUrl.orEmpty() }
                            if (local.isNotBlank()) {
                                vncUrl = local
                                return@runOp
                            }
                            val raw = withContext(Dispatchers.IO) { api.vnc(id) }
                            val resolved = GbotParse.vncUrl(raw)
                            if (resolved.isBlank()) {
                                statusLine = GbotParse.apiError(raw).ifBlank { "No VNC URL" }
                            } else {
                                vncUrl = resolved
                            }
                        }
                    },
                )
            GbotPanel.Setup -> GbotSetupTab(
                        snapshot = snapshot,
                        listeners = listeners,
                        skills = skills,
                        busy = busy,
                        sharing = sharing,
                        secrets = secrets,
                        skillPublish = skillPublish,
                        selectedName = selected?.name.orEmpty(),
                        onHostUpdate = { confirmHostUpdate = true },
                        onImportCookies = { showCookieImport = true },
                        onDeleteBot = {
                            confirmDeleteBot = selected
                            deleteConfirmText = ""
                        },
                        onWipeBots = {
                            confirmWipeBots = true
                            wipeConfirmText = ""
                        },
                        onResetBox = { confirmResetBox = true },
                        onJoinRoom = { showJoinRoom = true },
                        onInviteRoom = { roomId ->
                            runOp("Invite link") {
                                val raw = requireOk(withContext(Dispatchers.IO) { api.createRoomInvite(roomId) })
                                shareLink = GbotParse.shareLink(raw.opt("data"))
                                    ?: throw IllegalStateException("No invite URL")
                            }
                        },
                        onLeaveRoom = { roomId ->
                            runOp("Left room") {
                                requireOk(withContext(Dispatchers.IO) { api.leaveRoom(roomId) })
                                loadSharing()
                            }
                        },
                        onApproveJoin = { requestId, approved ->
                            runOp(if (approved) "Join approved" else "Join denied") {
                                requireOk(withContext(Dispatchers.IO) { api.respondJoin(requestId, approved) })
                                loadSharing()
                            }
                        },
                        onPublishSkill = { workflowId ->
                            publishWorkflowId = workflowId
                        },
                        onRegisterComputer = {
                            runOp("Registering computer…") {
                                requireOk(withContext(Dispatchers.IO) { api.registerComputer() })
                            }
                        },
                        onShareBot = {
                            val id = selectedAgentId
                            if (id.isBlank()) return@GbotSetupTab
                            runOp("Share link") {
                                val raw = requireOk(withContext(Dispatchers.IO) { api.createRoomFromAgent(id) })
                                shareLink = GbotParse.shareLink(raw.opt("data"))
                                    ?: throw IllegalStateException("No share URL")
                            }
                        },
                        rawMethod = rawMethod,
                        rawArgs = rawArgs,
                        rawOut = rawOut,
                        tzDraft = tzDraft,
                        loginHint = loginHint,
                        watchEnabled = watchAlerts,
                        onWatchEnabled = { on ->
                            watchAlerts = on
                            GbotWatch.setEnabled(appCtx, on)
                            statusLine = if (on) "Background alerts on" else "Background alerts off"
                        },
                        onRawMethod = { rawMethod = it },
                        onRawArgs = { rawArgs = it },
                        onTzDraft = { tzDraft = it },
                        onSetToolPerm = { value ->
                            runOp("localToolPermission $value") {
                                requireOk(withContext(Dispatchers.IO) { api.setLocalToolPermission(value) })
                            }
                        },
                        onSetAutoReview = { enabled ->
                            runOp("auto-review $enabled") {
                                requireOk(
                                    withContext(Dispatchers.IO) {
                                        api.setAutoReview(enabled, snapshot?.settings)
                                    },
                                )
                            }
                        },
                        onSetTimezone = { tz ->
                            runOp("timezone $tz") {
                                requireOk(withContext(Dispatchers.IO) { api.setTimezone(tz) })
                            }
                        },
                        onLogin = {
                            runOp("Login started") {
                                val raw = requireOk(withContext(Dispatchers.IO) { api.loginStart() })
                                val started = GbotParse.loginStart(raw)
                                    ?: throw IllegalStateException("No login URL")
                                loginHint = started.uuid
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(started.loginUrl))
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                appCtx.startActivity(intent)
                                statusLine = "Finish sign-in in the browser"
                            }
                        },
                        onRefreshMcp = {
                            runOp("MCP refreshed") {
                                withContext(Dispatchers.IO) { api.refreshMcp() }
                                loadListeners()
                            }
                        },
                        onConnect = { platform ->
                            runOp("Connect $platform") {
                                val raw = withContext(Dispatchers.IO) { api.mcpConnectUrl(platform) }
                                val url = GbotParse.connectUrl(raw.opt("data"))
                                if (url.isBlank()) {
                                    statusLine = GbotParse.apiError(raw).ifBlank { "No connect URL" }
                                } else {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    appCtx.startActivity(intent)
                                    statusLine = "Opened $platform login"
                                }
                            }
                        },
                        onRawSend = {
                            runOp("gateway $rawMethod") {
                                val args = runCatching { JSONObject(rawArgs.ifBlank { "{}" }) }
                                    .getOrElse { JSONObject() }
                                val raw = withContext(Dispatchers.IO) { api.rpc(rawMethod.trim(), args) }
                                rawOut = raw.toString(2)
                                requireOk(raw)
                            }
                        },
                    )
            }
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = createName.trim()
                        if (name.isBlank()) return@TextButton
                        showCreate = false
                        runOp("Created $name") {
                            val raw = requireOk(
                                withContext(Dispatchers.IO) {
                                    api.createAgent(name, createDesc.trim(), createKick)
                                },
                            )
                            val created = GbotParse.createdAgentId(raw.opt("data"))
                            if (created != null) setSelected(created.first)
                            createName = ""
                            createDesc = ""
                        }
                    },
                ) { Text("Create", color = GrokifyColors.GlowCyan) }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) {
                    Text("Cancel", color = GrokifyColors.TextMuted)
                }
            },
            title = { Text("New bot", color = GrokifyColors.TextPrimary) },
            text = {
                Column {
                    OutlinedTextField(
                        value = createName,
                        onValueChange = { createName = it },
                        label = { Text("Name") },
                        singleLine = true,
                        colors = gbotFieldColors(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = createDesc,
                        onValueChange = { createDesc = it },
                        label = { Text("Description") },
                        minLines = 3,
                        colors = gbotFieldColors(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Kickstart greeting", color = GrokifyColors.TextMuted, modifier = Modifier.weight(1f))
                        Switch(
                            checked = createKick,
                            onCheckedChange = { createKick = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = GrokifyColors.GlowCyan),
                        )
                    }
                }
            },
            containerColor = GrokifyColors.Panel,
        )
    }

    if (webhookCred != null) {
        val cred = webhookCred!!
        AlertDialog(
            onDismissRequest = { webhookCred = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        copyText(appCtx, cred.url)
                        Toast.makeText(appCtx, "URL copied", Toast.LENGTH_SHORT).show()
                    },
                ) { Text("Copy URL", color = GrokifyColors.GlowCyan) }
            },
            dismissButton = {
                TextButton(onClick = { webhookCred = null }) {
                    Text("Close", color = GrokifyColors.TextMuted)
                }
            },
            title = { Text(webhookName.ifBlank { "Webhook" }, color = GrokifyColors.TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("POST this URL. Send the key as a bearer or header the bot already expects.", color = GrokifyColors.TextDim, fontSize = 12.sp)
                    Text(cred.url, color = GrokifyColors.TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    if (cred.key.isNotBlank()) {
                        Text("Key", color = GrokifyColors.TextDim, fontSize = 11.sp)
                        Text(cred.key, color = GrokifyColors.GlowMint, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        TextButton(
                            onClick = {
                                copyText(appCtx, cred.key)
                                Toast.makeText(appCtx, "Key copied", Toast.LENGTH_SHORT).show()
                            },
                        ) { Text("Copy key", color = GrokifyColors.GlowViolet) }
                    }
                }
            },
            containerColor = GrokifyColors.Panel,
        )
    }

    if (showCreateWebhook) {
        AlertDialog(
            onDismissRequest = { showCreateWebhook = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = webhookDraftName.trim()
                        val prompt = webhookDraftPrompt.trim()
                        val id = selectedAgentId
                        if (name.isBlank() || prompt.isBlank() || id.isBlank()) return@TextButton
                        showCreateWebhook = false
                        runOp("Webhook automation created") {
                            requireOk(
                                withContext(Dispatchers.IO) {
                                    api.createAutomation(
                                        id,
                                        name,
                                        prompt,
                                        JSONObject().put("type", "webhook"),
                                        true,
                                    )
                                },
                            )
                            webhookDraftName = ""
                            webhookDraftPrompt = ""
                            loadAgentExtras(id)
                        }
                    },
                ) { Text("Create", color = GrokifyColors.GlowCyan) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateWebhook = false }) {
                    Text("Cancel", color = GrokifyColors.TextMuted)
                }
            },
            title = { Text("Webhook automation", color = GrokifyColors.TextPrimary) },
            text = {
                Column {
                    OutlinedTextField(
                        value = webhookDraftName,
                        onValueChange = { webhookDraftName = it },
                        label = { Text("Name") },
                        singleLine = true,
                        colors = gbotFieldColors(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = webhookDraftPrompt,
                        onValueChange = { webhookDraftPrompt = it },
                        label = { Text("Prompt") },
                        minLines = 4,
                        colors = gbotFieldColors(),
                    )
                }
            },
            containerColor = GrokifyColors.Panel,
        )
    }

    if (showImportWorkflow) {
        AlertDialog(
            onDismissRequest = { showImportWorkflow = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val md = workflowDraftMd.trim()
                        val id = selectedAgentId
                        if (md.isBlank() || id.isBlank()) return@TextButton
                        showImportWorkflow = false
                        runOp("Workflow imported") {
                            requireOk(
                                withContext(Dispatchers.IO) {
                                    api.importWorkflowText(id, md, workflowDraftName.trim())
                                },
                            )
                            workflowDraftName = ""
                            workflowDraftMd = ""
                            loadAgentExtras(id)
                        }
                    },
                ) { Text("Import", color = GrokifyColors.GlowCyan) }
            },
            dismissButton = {
                TextButton(onClick = { showImportWorkflow = false }) {
                    Text("Cancel", color = GrokifyColors.TextMuted)
                }
            },
            title = { Text("Import workflow", color = GrokifyColors.TextPrimary) },
            text = {
                Column {
                    OutlinedTextField(
                        value = workflowDraftName,
                        onValueChange = { workflowDraftName = it },
                        label = { Text("Name (optional)") },
                        singleLine = true,
                        colors = gbotFieldColors(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = workflowDraftMd,
                        onValueChange = { workflowDraftMd = it },
                        label = { Text("Markdown") },
                        minLines = 6,
                        colors = gbotFieldColors(),
                    )
                }
            },
            containerColor = GrokifyColors.Panel,
        )
    }

    if (confirmHostUpdate) {
        AlertDialog(
            onDismissRequest = { confirmHostUpdate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmHostUpdate = false
                        runOp("Host update started") {
                            requireOk(withContext(Dispatchers.IO) { api.updateHost(true) })
                        }
                    },
                ) { Text("Update host", color = GrokifyColors.GlowAmber) }
            },
            dismissButton = {
                TextButton(onClick = { confirmHostUpdate = false }) {
                    Text("Cancel", color = GrokifyColors.TextMuted)
                }
            },
            title = { Text("Update VM host", color = GrokifyColors.TextPrimary) },
            text = {
                Text(
                    "This upgrades the cloud VM (${snapshot?.host?.hostVersion ?: "current"} → ${snapshot?.host?.latestHostVersion ?: "latest"}). The box may pause while it swaps.",
                    color = GrokifyColors.TextMuted,
                )
            },
            containerColor = GrokifyColors.Panel,
        )
    }

    if (confirmBoxImage) {
        AlertDialog(
            onDismissRequest = { confirmBoxImage = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmBoxImage = false
                        val id = selectedAgentId
                        runOp("Box image update started") {
                            requireOk(withContext(Dispatchers.IO) { api.updateBoxImage(id) })
                            loadBox(id)
                        }
                    },
                ) { Text("Update image", color = GrokifyColors.GlowRose) }
            },
            dismissButton = {
                TextButton(onClick = { confirmBoxImage = false }) {
                    Text("Cancel", color = GrokifyColors.TextMuted)
                }
            },
            title = { Text("Update box image", color = GrokifyColors.TextPrimary) },
            text = {
                Text(
                    "This rebuilds the computer VM image and will reboot the desktop session.",
                    color = GrokifyColors.TextMuted,
                )
            },
            containerColor = GrokifyColors.Panel,
        )
    }

    if (confirmClearMemories) {
        AlertDialog(
            onDismissRequest = { confirmClearMemories = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearMemories = false
                        val id = expandedAgent ?: selectedAgentId
                        if (id.isBlank()) return@TextButton
                        runOp("Memories cleared") {
                            requireOk(withContext(Dispatchers.IO) { api.clearMemories(id) })
                            loadAgentExtras(id)
                        }
                    },
                ) { Text("Clear all", color = GrokifyColors.GlowRose) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearMemories = false }) {
                    Text("Cancel", color = GrokifyColors.TextMuted)
                }
            },
            title = { Text("Clear memories", color = GrokifyColors.TextPrimary) },
            text = {
                Text("Deletes every saved memory on this bot. This cannot be undone from the phone.", color = GrokifyColors.TextMuted)
            },
            containerColor = GrokifyColors.Panel,
        )
    }

    val deleteTarget = confirmDeleteBot
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { confirmDeleteBot = null; deleteConfirmText = "" },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = deleteTarget.id
                        confirmDeleteBot = null
                        deleteConfirmText = ""
                        runOp("Deleted ${deleteTarget.name}") {
                            requireOk(withContext(Dispatchers.IO) { api.deleteAgent(id) })
                            if (selectedId == id) {
                                selectedId = ""
                                store.selectedAgentId = ""
                            }
                            if (expandedAgent == id) expandedAgent = null
                        }
                    },
                    enabled = deleteConfirmText.trim() == deleteTarget.name,
                ) { Text("Delete", color = GrokifyColors.GlowRose) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteBot = null; deleteConfirmText = "" }) {
                    Text("Cancel", color = GrokifyColors.TextMuted)
                }
            },
            title = { Text("Delete ${deleteTarget.name}", color = GrokifyColors.TextPrimary) },
            text = {
                Column {
                    Text(
                        "Removes this bot and its chat history. Type the bot name to confirm.",
                        color = GrokifyColors.TextMuted,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = deleteConfirmText,
                        onValueChange = { deleteConfirmText = it },
                        label = { Text(deleteTarget.name) },
                        singleLine = true,
                        colors = gbotFieldColors(),
                    )
                }
            },
            containerColor = GrokifyColors.Panel,
        )
    }

    if (confirmWipeBots) {
        AlertDialog(
            onDismissRequest = { confirmWipeBots = false; wipeConfirmText = "" },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmWipeBots = false
                        wipeConfirmText = ""
                        val ids = snapshot?.agents.orEmpty().map { it.id }
                        runOp("Wiped ${ids.size} bots") {
                            requireOk(withContext(Dispatchers.IO) { api.deleteAgents(ids) })
                            selectedId = ""
                            store.selectedAgentId = ""
                            expandedAgent = null
                            bubbles = emptyList()
                        }
                    },
                    enabled = wipeConfirmText.trim().equals("WIPE", ignoreCase = true),
                ) { Text("Wipe all", color = GrokifyColors.GlowRose) }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipeBots = false; wipeConfirmText = "" }) {
                    Text("Cancel", color = GrokifyColors.TextMuted)
                }
            },
            title = { Text("Wipe all bots", color = GrokifyColors.TextPrimary) },
            text = {
                Column {
                    Text(
                        "Deletes every bot on this account and their chats. Type WIPE to confirm.",
                        color = GrokifyColors.TextMuted,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = wipeConfirmText,
                        onValueChange = { wipeConfirmText = it },
                        label = { Text("WIPE") },
                        singleLine = true,
                        colors = gbotFieldColors(),
                    )
                }
            },
            containerColor = GrokifyColors.Panel,
        )
    }

    if (confirmResetBox) {
        AlertDialog(
            onDismissRequest = { confirmResetBox = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmResetBox = false
                        val id = selectedAgentId
                        runOp("Computer reset started") {
                            requireOk(withContext(Dispatchers.IO) { api.resetBox(id) })
                            loadBox(id)
                        }
                    },
                ) { Text("Reset computer", color = GrokifyColors.GlowRose) }
            },
            dismissButton = {
                TextButton(onClick = { confirmResetBox = false }) {
                    Text("Cancel", color = GrokifyColors.TextMuted)
                }
            },
            title = { Text("Reset computer", color = GrokifyColors.TextPrimary) },
            text = {
                Text(
                    "Factory-resets the cloud desktop for ${selected?.name ?: "this bot"}. Installed apps and local files in the VM go away. Bots and chats stay.",
                    color = GrokifyColors.TextMuted,
                )
            },
            containerColor = GrokifyColors.Panel,
        )
    }

    if (showCookieImport) {
        AlertDialog(
            onDismissRequest = { showCookieImport = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val parsed = runCatching { GbotCookies.parse(cookieDraft) }.getOrElse {
                            statusLine = it.message ?: "invalid cookies"
                            return@TextButton
                        }
                        if (parsed.isEmpty()) {
                            statusLine = "No usable cookies (need name, value, domain, path)"
                            return@TextButton
                        }
                        showCookieImport = false
                        runOp("Imported ${parsed.size} cookies") {
                            val raw = requireOk(
                                withContext(Dispatchers.IO) {
                                    api.injectCookies(GbotCookies.toJsonArray(parsed))
                                },
                            )
                            val result = GbotParse.cookieImport(raw.opt("data"))
                            if (result != null) {
                                statusLine = "Injected ${result.injected} cookies" +
                                    if (result.sites > 0) " · ${result.sites} sites" else ""
                            }
                            cookieDraft = ""
                            loadSharing()
                        }
                    },
                ) { Text("Import", color = GrokifyColors.GlowAmber) }
            },
            dismissButton = {
                TextButton(onClick = { showCookieImport = false }) {
                    Text("Cancel", color = GrokifyColors.TextMuted)
                }
            },
            title = { Text("Import Chrome cookies", color = GrokifyColors.TextPrimary) },
            text = {
                Column {
                    Text(
                        "Paste cookie-editor JSON or a Netscape cookie file. This is sent to the box browser.",
                        color = GrokifyColors.TextMuted,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = cookieDraft,
                        onValueChange = { cookieDraft = it },
                        label = { Text("Cookies") },
                        minLines = 6,
                        colors = gbotFieldColors(),
                    )
                }
            },
            containerColor = GrokifyColors.Panel,
        )
    }

    if (showJoinRoom) {
        AlertDialog(
            onDismissRequest = { showJoinRoom = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val link = joinLinkDraft.trim()
                        if (link.isBlank()) return@TextButton
                        showJoinRoom = false
                        runOp("Join requested") {
                            val raw = requireOk(withContext(Dispatchers.IO) { api.joinRoom(link) })
                            shareLink = GbotParse.shareLink(raw.opt("data"))
                            joinLinkDraft = ""
                            loadSharing()
                        }
                    },
                ) { Text("Join", color = GrokifyColors.GlowCyan) }
            },
            dismissButton = {
                TextButton(onClick = { showJoinRoom = false }) {
                    Text("Cancel", color = GrokifyColors.TextMuted)
                }
            },
            title = { Text("Join shared room", color = GrokifyColors.TextPrimary) },
            text = {
                OutlinedTextField(
                    value = joinLinkDraft,
                    onValueChange = { joinLinkDraft = it },
                    label = { Text("Invite link") },
                    singleLine = true,
                    colors = gbotFieldColors(),
                )
            },
            containerColor = GrokifyColors.Panel,
        )
    }

    val link = shareLink
    if (link != null) {
        AlertDialog(
            onDismissRequest = { shareLink = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        val text = link.url.ifBlank { link.message }
                        if (text.isNotBlank()) {
                            copyText(appCtx, text)
                            Toast.makeText(appCtx, "Copied", Toast.LENGTH_SHORT).show()
                        }
                    },
                ) { Text("Copy", color = GrokifyColors.GlowCyan) }
            },
            dismissButton = {
                TextButton(onClick = { shareLink = null }) {
                    Text("Close", color = GrokifyColors.TextMuted)
                }
            },
            title = { Text(if (link.url.isNotBlank()) "Share link" else "Sharing", color = GrokifyColors.TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (link.status.isNotBlank() && link.status != "ok") {
                        Text(link.status, color = GrokifyColors.GlowAmber, fontSize = 12.sp)
                    }
                    if (link.url.isNotBlank()) {
                        Text(link.url, color = GrokifyColors.TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                    if (link.message.isNotBlank()) {
                        Text(link.message, color = GrokifyColors.TextMuted, fontSize = 12.sp)
                    }
                    if (link.url.isBlank() && link.message.isBlank()) {
                        Text("No URL returned. Sharing may be off for this account.", color = GrokifyColors.TextMuted)
                    }
                }
            },
            containerColor = GrokifyColors.Panel,
        )
    }

    val publishId = publishWorkflowId
    if (publishId != null) {
        val teams = skillPublish?.teams.orEmpty()
        val blocked = skillPublish?.unavailableReason.orEmpty()
        AlertDialog(
            onDismissRequest = { publishWorkflowId = null },
            confirmButton = {
                if (teams.isEmpty()) {
                    TextButton(onClick = { publishWorkflowId = null }) {
                        Text("OK", color = GrokifyColors.GlowCyan)
                    }
                } else {
                    TextButton(onClick = { publishWorkflowId = null }) {
                        Text("Close", color = GrokifyColors.TextMuted)
                    }
                }
            },
            title = { Text("Publish skill", color = GrokifyColors.TextPrimary) },
            text = {
                Column {
                    if (blocked.isNotBlank() && teams.isEmpty()) {
                        Text(blocked, color = GrokifyColors.TextMuted)
                    } else if (teams.isEmpty()) {
                        Text("No Cursor team to publish to.", color = GrokifyColors.TextMuted)
                    } else {
                        Text("Publish $publishId to a team.", color = GrokifyColors.TextMuted, fontSize = 12.sp)
                        teams.forEach { team ->
                            TextButton(
                                onClick = {
                                    publishWorkflowId = null
                                    runOp("Published to ${team.name}") {
                                        requireOk(
                                            withContext(Dispatchers.IO) {
                                                api.publishSkill(publishId, team.id)
                                            },
                                        )
                                        loadSkills()
                                    }
                                },
                                enabled = !busy,
                            ) {
                                Text(team.name, color = GrokifyColors.GlowViolet)
                            }
                        }
                    }
                }
            },
            containerColor = GrokifyColors.Panel,
        )
    }
}

private suspend fun settleCard(
    api: GbotApi,
    card: GbotPendingCard,
    approve: Boolean,
    always: Boolean = false,
    never: Boolean = false,
) {
    withContext(Dispatchers.IO) {
        when (card.kind) {
            "local-tool-permission" -> {
                if (approve) api.approveLocalTool(card.agentId, card.entryId, card.requestId, always)
                else api.denyLocalTool(card.agentId, card.entryId, card.requestId, never)
            }
            "auto-review-approval" -> {
                if (approve) api.approveReview(card.agentId, card.entryId, card.requestId, always)
                else api.denyReview(card.agentId, card.entryId, card.requestId)
            }
            else -> JSONObject()
        }
    }
}

@Composable
private fun GbotBotsTab(
    agents: List<GbotAgent>,
    selectedId: String,
    expandedId: String?,
    memories: List<GbotMemory>,
    automations: List<GbotAutomation>,
    workflows: List<GbotWorkflow>,
    searchQuery: String,
    searchHits: List<GbotSearchHit>,
    onSearchQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenHit: (String) -> Unit,
    onSelect: (String) -> Unit,
    onExpand: (String) -> Unit,
    onSwitch: (String) -> Unit,
    onKickstart: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onToggleAuto: (String, String, Boolean) -> Unit,
    onRunAuto: (String, String) -> Unit,
    onRunWorkflow: (String, String) -> Unit,
    onCreate: () -> Unit,
    connectors: List<GbotConnector> = emptyList(),
    channels: List<GbotChannel> = emptyList(),
    listeners: List<GbotListener> = emptyList(),
    onAddConnector: (String) -> Unit = {},
    onRefreshMcp: () -> Unit = {},
    onConnectListener: (String) -> Unit = {},
    onDeleteMemory: (String, String) -> Unit = { _, _ -> },
    onClearMemories: () -> Unit = {},
    onImportWorkflow: () -> Unit = {},
    onDeleteBot: (String) -> Unit = {},
    onShareBot: (String) -> Unit = {},
    onPublishWorkflow: (String) -> Unit = {},
) {
    val names = agents.associate { it.id to it.name }
    val filtered = if (searchQuery.isBlank() || searchHits.isNotEmpty()) {
        agents
    } else {
        agents.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                it.description.contains(searchQuery, ignoreCase = true)
        }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            placeholder = { Text("Filter bots or search transcripts", color = GrokifyColors.TextDim) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            trailingIcon = {
                IconButton(onClick = onSearch) {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = GrokifyColors.GlowCyan)
                }
            },
            colors = gbotFieldColors(),
            shape = RoundedCornerShape(14.dp),
        )
        if (searchHits.isNotEmpty()) {
            Text(
                "${searchHits.size} transcript hits",
                color = GrokifyColors.GlowCyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
            )
            searchHits.take(12).forEach { hit ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(GrokifyColors.PanelSoft)
                        .clickable { onOpenHit(hit.agentId) }
                        .padding(10.dp),
                ) {
                    Text(
                        names[hit.agentId] ?: hit.agentId.take(8),
                        color = GrokifyColors.GlowViolet,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        hit.snippet,
                        color = GrokifyColors.TextPrimary,
                        fontSize = 13.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${filtered.size} bots",
                color = GrokifyColors.TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCreate) {
                Icon(Icons.Default.Add, contentDescription = null, tint = GrokifyColors.GlowCyan, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("New", color = GrokifyColors.GlowCyan)
            }
        }
        if (agents.isEmpty()) {
            Text(
                "No bots yet. Create one, or check that gbotd is signed in.",
                color = GrokifyColors.TextDim,
                modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(filtered, key = { it.id }) { agent ->
                val selected = agent.id == selectedId
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(GrokifyColors.Panel.copy(alpha = 0.92f))
                        .border(
                            1.dp,
                            if (selected) GrokifyColors.GlowCyan.copy(alpha = 0.55f) else GrokifyColors.PanelBorder,
                            RoundedCornerShape(16.dp),
                        )
                        .clickable { onSelect(agent.id) }
                        .padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GbotAvatar(agent)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    agent.name,
                                    color = GrokifyColors.TextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Spacer(Modifier.width(8.dp))
                                GbotStatusDot(agent)
                            }
                            Text(
                                agent.lastPreview.ifBlank { agent.description }.ifBlank { agent.id },
                                color = GrokifyColors.TextDim,
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Row(Modifier.padding(top = 6.dp)) {
                        TextButton(onClick = { onExpand(agent.id) }) {
                            Text(if (expandedId == agent.id) "Hide" else "Details", color = GrokifyColors.GlowCyan, fontSize = 12.sp)
                        }
                        TextButton(onClick = { onSwitch(agent.id) }) {
                            Text("Focus box", color = GrokifyColors.GlowAmber, fontSize = 12.sp)
                        }
                        TextButton(onClick = { onKickstart(agent.id) }) {
                            Text("Kickstart", color = GrokifyColors.GlowMint, fontSize = 12.sp)
                        }
                    }
                    if (expandedId == agent.id) {
                        if (agent.description.isNotBlank()) {
                            Text(agent.description, color = GrokifyColors.TextMuted, fontSize = 12.sp)
                            Spacer(Modifier.height(8.dp))
                        }
                        GbotSectionLabel("CONNECTORS")
                        Text(
                            "This bot can add and use MCP connectors when you ask it to — same as `gbot` `add-connector`. Cards and box servers show up here.",
                            color = GrokifyColors.TextDim,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                        val shown = connectors.ifEmpty { emptyList() }
                        if (shown.isEmpty() && listeners.isEmpty() && channels.isEmpty()) {
                            Text("None yet", color = GrokifyColors.TextDim, fontSize = 12.sp)
                        } else {
                            shown.forEach { conn ->
                                Column(Modifier.padding(vertical = 4.dp)) {
                                    Text(conn.name, color = GrokifyColors.TextPrimary, fontSize = 13.sp)
                                    Text(
                                        buildString {
                                            append(conn.source.ifBlank { "mcp" })
                                            if (conn.status.isNotBlank()) {
                                                append(" · ")
                                                append(conn.status)
                                            }
                                        },
                                        color = if (conn.status.contains("connect", ignoreCase = true) ||
                                            conn.status.equals("ok", ignoreCase = true)
                                        ) {
                                            GrokifyColors.GlowMint
                                        } else {
                                            GrokifyColors.TextDim
                                        },
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                            listeners.forEach { listener ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(listener.platform, color = GrokifyColors.TextPrimary, fontSize = 13.sp)
                                        Text(
                                            if (listener.connected) {
                                                "listener · connected"
                                            } else {
                                                "listener · ${listener.state.ifBlank { "idle" }}"
                                            },
                                            color = if (listener.connected) GrokifyColors.GlowMint else GrokifyColors.TextDim,
                                            fontSize = 11.sp,
                                        )
                                    }
                                    TextButton(onClick = { onConnectListener(listener.platform) }) {
                                        Text("Connect", color = GrokifyColors.GlowCyan, fontSize = 12.sp)
                                    }
                                }
                            }
                            channels.forEach { channel ->
                                Column(Modifier.padding(vertical = 4.dp)) {
                                    Text(
                                        channel.displayName.ifBlank { channel.platform },
                                        color = GrokifyColors.TextPrimary,
                                        fontSize = 13.sp,
                                    )
                                    Text(
                                        buildString {
                                            if (channel.connected) append("connected")
                                            else append(channel.availability.ifBlank { "not connected" })
                                            if (channel.blurb.isNotBlank()) {
                                                append(" · ")
                                                append(channel.blurb)
                                            }
                                        },
                                        color = if (channel.connected) GrokifyColors.GlowMint else GrokifyColors.TextDim,
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                        }
                        Row {
                            TextButton(onClick = { onAddConnector(agent.id) }) {
                                Text("Add connector", color = GrokifyColors.GlowCyan, fontSize = 12.sp)
                            }
                            TextButton(onClick = onRefreshMcp) {
                                Text("Refresh MCP", color = GrokifyColors.GlowMint, fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        GbotSectionLabel("MEMORIES")
                        if (memories.isEmpty()) {
                            Text("None yet", color = GrokifyColors.TextDim, fontSize = 12.sp)
                        } else {
                            memories.take(12).forEach { mem ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Text(
                                        "· ${mem.content}",
                                        color = GrokifyColors.TextMuted,
                                        fontSize = 12.sp,
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f).padding(top = 4.dp),
                                    )
                                    if (mem.id.isNotBlank()) {
                                        TextButton(onClick = { onDeleteMemory(agent.id, mem.id) }) {
                                            Text("Del", color = GrokifyColors.GlowRose, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                            TextButton(onClick = onClearMemories) {
                                Text("Clear all memories", color = GrokifyColors.GlowRose, fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        GbotSectionLabel("AUTOMATIONS")
                        if (automations.isEmpty()) {
                            Text("None", color = GrokifyColors.TextDim, fontSize = 12.sp)
                        } else {
                            automations.forEach { auto ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            if (auto.isRunning) "${auto.name} · running" else auto.name,
                                            color = if (auto.isRunning) GrokifyColors.GlowMint else GrokifyColors.TextPrimary,
                                            fontSize = 13.sp,
                                        )
                                        Text(
                                            buildString {
                                                append(auto.schedule.ifBlank { "manual" })
                                                val run = auto.latestRun
                                                if (run != null) {
                                                    append(" · ")
                                                    append(
                                                        when {
                                                            run.isActive -> "in progress"
                                                            run.ok -> "last ok"
                                                            else -> "last ${run.status.ifBlank { "ended" }}"
                                                        },
                                                    )
                                                }
                                            },
                                            color = GrokifyColors.TextDim,
                                            fontSize = 11.sp,
                                        )
                                    }
                                    TextButton(onClick = { onRunAuto(agent.id, auto.id) }) {
                                        Text("Run", color = GrokifyColors.GlowMint, fontSize = 12.sp)
                                    }
                                    Switch(
                                        checked = auto.enabled,
                                        onCheckedChange = { onToggleAuto(agent.id, auto.id, it) },
                                        colors = SwitchDefaults.colors(checkedTrackColor = GrokifyColors.GlowMint),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        GbotSectionLabel("WORKFLOWS")
                        if (workflows.isEmpty()) {
                            Text("None", color = GrokifyColors.TextDim, fontSize = 12.sp)
                        } else {
                            workflows.take(12).forEach { wf ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(wf.name, color = GrokifyColors.TextPrimary, fontSize = 13.sp)
                                        if (wf.description.isNotBlank()) {
                                            Text(
                                                wf.description,
                                                color = GrokifyColors.TextDim,
                                                fontSize = 11.sp,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    TextButton(onClick = { onRunWorkflow(agent.id, wf.id) }) {
                                        Text("Run", color = GrokifyColors.GlowMint, fontSize = 12.sp)
                                    }
                                    TextButton(onClick = { onPublishWorkflow(wf.id) }) {
                                        Text("Publish", color = GrokifyColors.GlowViolet, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                        TextButton(onClick = onImportWorkflow) {
                            Text("Import workflow markdown", color = GrokifyColors.GlowCyan, fontSize = 12.sp)
                        }
                        TextButton(onClick = { onDuplicate(agent.id) }) {
                            Text("Duplicate bot", color = GrokifyColors.GlowAmber, fontSize = 12.sp)
                        }
                        TextButton(onClick = { onShareBot(agent.id) }) {
                            Text("Share room", color = GrokifyColors.GlowCyan, fontSize = 12.sp)
                        }
                        TextButton(onClick = { onDeleteBot(agent.id) }) {
                            Text("Delete bot", color = GrokifyColors.GlowRose, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GbotChatTab(
    agent: GbotAgent?,
    bubbles: List<GbotBubble>,
    pendingByEntry: Map<String, GbotPendingCard>,
    unmatchedPending: List<GbotPendingCard>,
    draft: String,
    busy: Boolean,
    canLoadEarlier: Boolean,
    answerDraftOf: (GbotPendingCard) -> String,
    secretDraftOf: (GbotPendingCard) -> String,
    onAnswerDraft: (GbotPendingCard, String) -> Unit,
    onSecretDraft: (GbotPendingCard, String) -> Unit,
    onLoadEarlier: () -> Unit,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onApprove: (GbotPendingCard, Boolean) -> Unit,
    onDeny: (GbotPendingCard, Boolean) -> Unit,
    onAnswer: (GbotPendingCard, String) -> Unit,
    onDismiss: (GbotPendingCard) -> Unit,
    onSecret: (GbotPendingCard, String) -> Unit,
    onHandback: (GbotPendingCard, Boolean) -> Unit,
    running: Boolean = false,
    onStop: () -> Unit = {},
    onAttach: () -> Unit = {},
    pendingNames: List<String> = emptyList(),
    onRemoveAttach: (Int) -> Unit = {},
    replyLabel: String? = null,
    onClearReply: () -> Unit = {},
    onReply: (GbotBubble) -> Unit = {},
    onVote: (GbotBubble, String) -> Unit = { _, _ -> },
    votes: Map<String, String> = emptyMap(),
) {
    val listState = rememberLazyListState()
    var pinToBottom by remember { mutableStateOf(true) }
    var prevCount by remember { mutableIntStateOf(0) }
    val tailFingerprint = remember(bubbles) {
        bubbles.takeLast(4).joinToString("|") { "${it.id}:${it.text.length}:${it.streaming}" }
    }

    fun isNearBottom(): Boolean {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull() ?: return true
        if (last.index < info.totalItemsCount - 2) return false
        val viewportEnd = info.viewportEndOffset - info.afterContentPadding
        return last.offset + last.size - viewportEnd <= 160
    }

    val stickScrollConnection = remember(listState) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source == NestedScrollSource.UserInput && consumed.y != 0f) {
                    pinToBottom = isNearBottom()
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                pinToBottom = isNearBottom()
                return Velocity.Zero
            }
        }
    }

    LaunchedEffect(bubbles.size, tailFingerprint, pinToBottom, agent?.id) {
        val size = bubbles.size
        if (size > prevCount && !pinToBottom && prevCount > 0) {
            prevCount = size
            return@LaunchedEffect
        }
        prevCount = size
        if (bubbles.isNotEmpty() && pinToBottom) {
            val extra = if (canLoadEarlier) 1 else 0
            listState.scrollToItem(extra + bubbles.lastIndex)
        }
    }

    if (agent == null) {
        Text("Pick a bot from Bots.", color = GrokifyColors.TextDim, modifier = Modifier.padding(16.dp))
        return
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .nestedScroll(stickScrollConnection)
                .padding(horizontal = 12.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
        ) {
            if (canLoadEarlier) {
                item("load-earlier") {
                    TextButton(onClick = onLoadEarlier, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Load earlier", color = GrokifyColors.GlowCyan, fontSize = 12.sp)
                    }
                }
            }
            items(unmatchedPending, key = { "pend-${it.key()}" }) { card ->
                GbotPendingCardView(
                    card = card,
                    agentName = agent.name,
                    busy = busy,
                    answerDraft = answerDraftOf(card),
                    secretDraft = secretDraftOf(card),
                    onAnswerDraft = { onAnswerDraft(card, it) },
                    onSecretDraft = { onSecretDraft(card, it) },
                    onApprove = onApprove,
                    onDeny = onDeny,
                    onAnswer = onAnswer,
                    onDismiss = onDismiss,
                    onSecret = onSecret,
                    onHandback = onHandback,
                )
            }
            items(bubbles, key = { it.id }) { bubble ->
                val card = pendingByEntry[bubble.id]
                GbotBubbleView(
                    bubble = bubble,
                    agentName = agent.name,
                    pending = card,
                    busy = busy,
                    answerDraft = card?.let(answerDraftOf).orEmpty(),
                    secretDraft = card?.let(secretDraftOf).orEmpty(),
                    onAnswerDraft = { v -> card?.let { onAnswerDraft(it, v) } },
                    onSecretDraft = { v -> card?.let { onSecretDraft(it, v) } },
                    onApprove = onApprove,
                    onDeny = onDeny,
                    onAnswer = onAnswer,
                    onDismiss = onDismiss,
                    onSecret = onSecret,
                    onHandback = onHandback,
                    onReply = onReply,
                    onVote = onVote,
                    voteState = votes[bubble.id].orEmpty(),
                )
            }
        }
        GbotComposer(
            draft = draft,
            placeholder = "Message ${agent.name}… (Enter = new line)",
            busy = busy,
            onDraft = onDraft,
            onSend = onSend,
            running = running,
            onStop = onStop,
            onAttach = onAttach,
            pendingNames = pendingNames,
            onRemoveAttach = onRemoveAttach,
            replyLabel = replyLabel,
            onClearReply = onClearReply,
        )
    }
}

@Composable
private fun GbotInboxTab(
    agents: List<GbotAgent>,
    pending: List<GbotPendingCard>,
    busy: Boolean,
    answerDraftOf: (GbotPendingCard) -> String,
    secretDraftOf: (GbotPendingCard) -> String,
    onAnswerDraft: (GbotPendingCard, String) -> Unit,
    onSecretDraft: (GbotPendingCard, String) -> Unit,
    onOpen: (String) -> Unit,
    onApprove: (GbotPendingCard, Boolean) -> Unit,
    onDeny: (GbotPendingCard, Boolean) -> Unit,
    onAnswer: (GbotPendingCard, String) -> Unit,
    onDismiss: (GbotPendingCard) -> Unit,
    onSecret: (GbotPendingCard, String) -> Unit,
    onHandback: (GbotPendingCard, Boolean) -> Unit,
) {
    val names = agents.associate { it.id to it.name }
    if (pending.isEmpty()) {
        Text(
            "Nothing waiting. Approvals, questions, and secrets land here.",
            color = GrokifyColors.TextDim,
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(pending, key = { it.key() }) { card ->
            GbotPendingCardView(
                card = card,
                agentName = names[card.agentId] ?: card.agentId.take(8),
                busy = busy,
                answerDraft = answerDraftOf(card),
                secretDraft = secretDraftOf(card),
                onAnswerDraft = { onAnswerDraft(card, it) },
                onSecretDraft = { onSecretDraft(card, it) },
                onApprove = onApprove,
                onDeny = onDeny,
                onAnswer = onAnswer,
                onDismiss = onDismiss,
                onSecret = onSecret,
                onHandback = onHandback,
                onOpen = { onOpen(card.agentId) },
            )
        }
    }
}

@Composable
private fun GbotBoxTab(
    agent: GbotAgent?,
    box: GbotBoxStatus?,
    busy: Boolean,
    onEnsure: () -> Unit,
    onHandback: () -> Unit,
    onOpenVnc: (String) -> Unit,
    teach: GbotTeachStatus? = null,
    hostUpdateAvailable: Boolean = false,
    onStartTeach: () -> Unit = {},
    onStopTeach: () -> Unit = {},
    onDiskSaver: () -> Unit = {},
    onHostUpdate: () -> Unit = {},
    onBoxImage: () -> Unit = {},
    onResetBox: () -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(agent?.name ?: "No bot selected", color = GrokifyColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Spacer(Modifier.height(8.dp))
        GbotInfoRow("State", box?.state ?: "—")
        GbotInfoRow("Windows", (box?.windowCount ?: 0).toString())
        GbotInfoRow("Host", box?.hostVersion?.ifBlank { "—" } ?: "—")
        if (box?.imageUpdateAvailable == true) GbotInfoRow("Image", "update available")
        GbotInfoRow("Teach", teach?.state?.ifBlank { "idle" } ?: "idle")
        if (box?.handoffInstruction?.isNotBlank() == true) {
            Text(
                box.handoffInstruction,
                color = GrokifyColors.GlowAmber,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onOpenVnc(box?.vncUrl.orEmpty()) },
            enabled = !busy && agent != null,
            colors = ButtonDefaults.buttonColors(containerColor = GrokifyColors.GlowCyan, contentColor = Color(0xFF041016)),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Default.Computer, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Open computer", fontWeight = FontWeight.SemiBold)
        }
        if ((box?.windows?.size ?: 0) > 1) {
            Spacer(Modifier.height(8.dp))
            box!!.windows.forEach { win ->
                TextButton(onClick = { onOpenVnc(win.vncUrl) }, enabled = !busy) {
                    Text("Window ${win.index}", color = GrokifyColors.GlowMint)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onEnsure,
            enabled = !busy && agent != null,
            colors = ButtonDefaults.buttonColors(containerColor = GrokifyColors.PanelSoft),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) { Text("Ensure computer", color = GrokifyColors.TextPrimary) }
        TextButton(onClick = onHandback, enabled = !busy && agent != null) {
            Text("Hand back", color = GrokifyColors.GlowAmber)
        }
        Spacer(Modifier.height(8.dp))
        GbotSectionLabel("TEACH")
        Text(
            if (teach?.isRecording == true) {
                "Recording the desktop so the bot can learn a skill (10 min cap)."
            } else {
                "Record the box desktop, then stop to turn the capture into a taught skill."
            },
            color = GrokifyColors.TextDim,
            fontSize = 12.sp,
        )
        if (teach?.isRecording == true) {
            Button(
                onClick = onStopTeach,
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = GrokifyColors.GlowRose, contentColor = Color(0xFF041016)),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) { Text("Stop recording", fontWeight = FontWeight.SemiBold) }
        } else {
            Button(
                onClick = onStartTeach,
                enabled = !busy && agent != null,
                colors = ButtonDefaults.buttonColors(containerColor = GrokifyColors.PanelSoft),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) { Text("Start teach recording", color = GrokifyColors.TextPrimary) }
        }
        TextButton(onClick = onDiskSaver, enabled = !busy && agent != null) {
            Text("Disk saver audit", color = GrokifyColors.GlowViolet)
        }
        if (hostUpdateAvailable) {
            TextButton(onClick = onHostUpdate, enabled = !busy) {
                Text("Update VM host", color = GrokifyColors.GlowAmber)
            }
        }
        if (box?.imageUpdateAvailable == true) {
            TextButton(onClick = onBoxImage, enabled = !busy) {
                Text("Update box image (reboots computer)", color = GrokifyColors.GlowRose)
            }
        }
        TextButton(onClick = onResetBox, enabled = !busy && agent != null) {
            Text("Reset computer", color = GrokifyColors.GlowRose)
        }
        Text(
            "Desktop is the same noVNC session `gbot box vnc` opens. Approvals still go through Inbox.",
            color = GrokifyColors.TextDim,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun GbotSetupTab(
    snapshot: GbotSnapshot?,
    listeners: List<GbotListener>,
    skills: List<GbotSkill>,
    busy: Boolean,
    rawMethod: String,
    rawArgs: String,
    rawOut: String,
    tzDraft: String,
    loginHint: String,
    watchEnabled: Boolean,
    onWatchEnabled: (Boolean) -> Unit,
    onRawMethod: (String) -> Unit,
    onRawArgs: (String) -> Unit,
    onTzDraft: (String) -> Unit,
    onSetToolPerm: (String) -> Unit,
    onSetAutoReview: (Boolean) -> Unit,
    onSetTimezone: (String) -> Unit,
    onLogin: () -> Unit,
    onRefreshMcp: () -> Unit,
    onConnect: (String) -> Unit,
    onRawSend: () -> Unit,
    onHostUpdate: () -> Unit = {},
    sharing: GbotSharingState? = null,
    secrets: GbotSecretsStatus? = null,
    skillPublish: GbotSkillPublish? = null,
    selectedName: String = "",
    onImportCookies: () -> Unit = {},
    onDeleteBot: () -> Unit = {},
    onWipeBots: () -> Unit = {},
    onResetBox: () -> Unit = {},
    onJoinRoom: () -> Unit = {},
    onInviteRoom: (String) -> Unit = {},
    onLeaveRoom: (String) -> Unit = {},
    onApproveJoin: (String, Boolean) -> Unit = { _, _ -> },
    onPublishSkill: (String) -> Unit = {},
    onShareBot: () -> Unit = {},
    onRegisterComputer: () -> Unit = {},
) {
    val uriHandler = LocalUriHandler.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        GbotSectionLabel("DAEMON")
        GbotInfoRow("Health", if (snapshot?.health?.ok == true) "ok" else snapshot?.error ?: "down")
        GbotInfoRow("Upstream", snapshot?.health?.upstreamOk?.let { if (it) "ok" else "no" } ?: "—")
        GbotInfoRow("Auth", snapshot?.auth?.kind ?: "—")
        GbotInfoRow("Auth id", snapshot?.auth?.authId?.takeLast(18) ?: "—")
        GbotInfoRow("gbotd pid", snapshot?.health?.pid?.toString() ?: "—")
        GbotInfoRow("Host", snapshot?.host?.hostVersion ?: "—")
        if (snapshot?.host?.hostUpdateAvailable == true) {
            GbotInfoRow("Host update", snapshot.host.latestHostVersion.ifBlank { "available" })
            TextButton(onClick = onHostUpdate, enabled = !busy) {
                Text("Update VM host now", color = GrokifyColors.GlowAmber)
            }
        }
        Spacer(Modifier.height(16.dp))
        GbotSectionLabel("THIS COMPUTER")
        val computer = snapshot?.computer
        GbotInfoRow(
            "Status",
            when {
                computer?.registered == true -> "registered"
                computer?.enabled == true -> "starting"
                computer != null -> "not registered"
                else -> "—"
            },
        )
        GbotInfoRow("Id", computer?.computerId?.ifBlank { "—" } ?: "—")
        GbotInfoRow("Label", computer?.computerLabel?.ifBlank { "—" } ?: "—")
        GbotInfoRow("Host name", computer?.hostname?.ifBlank { "—" } ?: "—")
        GbotInfoRow(
            "Sidecar",
            when {
                computer?.pid != null -> "pid ${computer.pid}"
                computer?.overlayPresent == false -> "overlay missing"
                else -> "stopped"
            },
        )
        Text(
            "Bots can only use the webserver on this box after it is registered as a local computer.",
            color = GrokifyColors.TextDim,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
        )
        Button(
            onClick = onRegisterComputer,
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(containerColor = GrokifyColors.GlowMint, contentColor = Color(0xFF041016)),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (computer?.registered == true) "Re-register this computer" else "Register this computer") }
        Spacer(Modifier.height(16.dp))
        GbotSectionLabel("ALERTS")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Background alerts", color = GrokifyColors.TextPrimary)
                Text(
                    "Notify when an automation runs, finishes, or needs you — even if this app is closed.",
                    color = GrokifyColors.TextDim,
                    fontSize = 12.sp,
                )
            }
            Switch(
                checked = watchEnabled,
                onCheckedChange = onWatchEnabled,
                enabled = !busy,
                colors = SwitchDefaults.colors(checkedTrackColor = GrokifyColors.GlowMint),
            )
        }
        val signedIn = snapshot?.auth?.kind == "logged-in"
        if (!signedIn) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onLogin,
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = GrokifyColors.GlowCyan, contentColor = Color(0xFF041016)),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Sign in (gbot login)") }
            if (loginHint.isNotBlank()) {
                Text(
                    "Waiting on browser login · $loginHint",
                    color = GrokifyColors.TextDim,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        GbotSectionLabel("LOCAL TOOLS")
        val perm = snapshot?.settings?.localToolPermission ?: "ask"
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("ask", "always", "never").forEach { v ->
                FilterChip(
                    selected = perm == v,
                    onClick = { onSetToolPerm(v) },
                    enabled = !busy,
                    label = { Text(v) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = GrokifyColors.GlowAmber.copy(alpha = 0.2f),
                        selectedLabelColor = GrokifyColors.GlowAmber,
                    ),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Auto-review instructions", color = GrokifyColors.TextPrimary, modifier = Modifier.weight(1f))
            Switch(
                checked = snapshot?.settings?.autoReviewEnabled == true,
                onCheckedChange = onSetAutoReview,
                enabled = !busy,
                colors = SwitchDefaults.colors(checkedTrackColor = GrokifyColors.GlowMint),
            )
        }
        GbotInfoRow("Time zone", snapshot?.settings?.userTimeZone?.ifBlank { "—" } ?: "—")
        OutlinedTextField(
            value = tzDraft,
            onValueChange = onTzDraft,
            label = { Text("Override (IANA)") },
            placeholder = { Text("America/Denver", color = GrokifyColors.TextDim) },
            singleLine = true,
            colors = gbotFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            onClick = { onSetTimezone(tzDraft.trim()) },
            enabled = !busy && tzDraft.isNotBlank(),
        ) { Text("Set timezone", color = GrokifyColors.GlowCyan) }
        Spacer(Modifier.height(16.dp))
        GbotSectionLabel("MCP / LISTENERS")
        listeners.forEach { listener ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(listener.platform, color = GrokifyColors.TextPrimary, fontWeight = FontWeight.Medium)
                    Text(
                        if (listener.connected) "connected · ${listener.state}" else listener.state.ifBlank { "idle" },
                        color = if (listener.connected) GrokifyColors.GlowMint else GrokifyColors.TextDim,
                        fontSize = 12.sp,
                    )
                }
                TextButton(onClick = { onConnect(listener.platform) }, enabled = !busy) {
                    Text("Connect", color = GrokifyColors.GlowCyan)
                }
            }
        }
        TextButton(onClick = onRefreshMcp, enabled = !busy) {
            Text("Refresh MCP", color = GrokifyColors.GlowMint)
        }
        Spacer(Modifier.height(16.dp))
        GbotSectionLabel("SHARED ROOMS")
        if (sharing?.enabled != true) {
            Text(
                sharing?.message?.ifBlank { null }
                    ?: "Sharing is off on this Cursor account. Join still works if someone sends you a link.",
                color = GrokifyColors.TextDim,
                fontSize = 12.sp,
            )
        } else {
            Text("Sharing is on.", color = GrokifyColors.GlowMint, fontSize = 12.sp)
        }
        TextButton(onClick = onShareBot, enabled = !busy && selectedName.isNotBlank()) {
            Text("Share ${selectedName.ifBlank { "this bot" }}", color = GrokifyColors.GlowCyan)
        }
        TextButton(onClick = onJoinRoom, enabled = !busy) {
            Text("Join room link", color = GrokifyColors.GlowMint)
        }
        sharing?.pending.orEmpty().forEach { req ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        req.requesterName.ifBlank { "Someone" },
                        color = GrokifyColors.TextPrimary,
                        fontSize = 13.sp,
                    )
                    Text(
                        "wants ${req.roomName.ifBlank { "a room" }}",
                        color = GrokifyColors.TextDim,
                        fontSize = 11.sp,
                    )
                }
                TextButton(onClick = { onApproveJoin(req.requestId, true) }, enabled = !busy) {
                    Text("Allow", color = GrokifyColors.GlowMint, fontSize = 12.sp)
                }
                TextButton(onClick = { onApproveJoin(req.requestId, false) }, enabled = !busy) {
                    Text("Deny", color = GrokifyColors.GlowRose, fontSize = 12.sp)
                }
            }
        }
        sharing?.rooms.orEmpty().forEach { room ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(room.name, color = GrokifyColors.TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { onInviteRoom(room.id) }, enabled = !busy) {
                    Text("Invite", color = GrokifyColors.GlowCyan, fontSize = 12.sp)
                }
                TextButton(onClick = { onLeaveRoom(room.id) }, enabled = !busy) {
                    Text("Leave", color = GrokifyColors.GlowAmber, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        GbotSectionLabel(if (skills.isEmpty()) "SKILLS" else "SKILLS · ${skills.size}")
        val publishReason = skillPublish?.unavailableReason.orEmpty()
        if (publishReason.isNotBlank()) {
            Text(publishReason, color = GrokifyColors.TextDim, fontSize = 12.sp)
        } else if (skillPublish?.teams?.isNotEmpty() == true) {
            Text(
                "Publishable teams: ${skillPublish!!.teams.joinToString { it.name }}",
                color = GrokifyColors.GlowMint,
                fontSize = 12.sp,
            )
        }
        if (skills.isEmpty()) {
            Text("None reported", color = GrokifyColors.TextDim, fontSize = 12.sp)
        } else {
            skills.take(24).forEach { skill ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(skill.name, color = GrokifyColors.TextPrimary, fontSize = 13.sp)
                    Text(
                        buildString {
                            if (skill.source.isNotBlank()) append(skill.source)
                            if (skill.description.isNotBlank()) {
                                if (isNotEmpty()) append(" · ")
                                append(skill.description)
                            }
                        },
                        color = GrokifyColors.TextDim,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val local = skill.source.contains("local", ignoreCase = true) ||
                        skill.source.contains("library", ignoreCase = true) ||
                        skill.source.contains("taught", ignoreCase = true)
                    if (local && skill.id.isNotBlank()) {
                        TextButton(onClick = { onPublishSkill(skill.id) }, enabled = !busy) {
                            Text("Publish", color = GrokifyColors.GlowViolet, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        GbotSectionLabel("BOX SECRETS / COOKIES")
        GbotInfoRow("Cookie keys", secrets?.keys?.size?.toString() ?: "—")
        GbotInfoRow("Applied", if (secrets?.applied == true) "yes" else "no")
        Text(
            "Paste a Chrome cookie-editor JSON export (or Netscape TSV). This signs the box browser into sites you already use. Treat it as a password.",
            color = GrokifyColors.TextDim,
            fontSize = 12.sp,
        )
        TextButton(onClick = onImportCookies, enabled = !busy) {
            Text("Import Chrome cookies", color = GrokifyColors.GlowAmber)
        }
        Spacer(Modifier.height(16.dp))
        GbotSectionLabel("DANGER")
        Text(
            "These confirm first. Reset wipes the cloud desktop. Delete/wipe remove bots and their chats.",
            color = GrokifyColors.TextDim,
            fontSize = 12.sp,
        )
        TextButton(onClick = onResetBox, enabled = !busy && selectedName.isNotBlank()) {
            Text("Reset computer", color = GrokifyColors.GlowRose)
        }
        TextButton(onClick = onDeleteBot, enabled = !busy && selectedName.isNotBlank()) {
            Text("Delete ${selectedName.ifBlank { "this bot" }}", color = GrokifyColors.GlowRose)
        }
        TextButton(onClick = onWipeBots, enabled = !busy) {
            Text("Wipe all bots", color = GrokifyColors.GlowRose)
        }
        Spacer(Modifier.height(16.dp))
        GbotSectionLabel("RAW GATEWAY")
        Text(
            "Same as gbot gateway -- METHOD [JSON]. Box-secret writes and store wipe stay blocked.",
            color = GrokifyColors.TextDim,
            fontSize = 12.sp,
        )
        OutlinedTextField(
            value = rawMethod,
            onValueChange = onRawMethod,
            label = { Text("Method") },
            singleLine = true,
            colors = gbotFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = rawArgs,
            onValueChange = onRawArgs,
            label = { Text("JSON args") },
            minLines = 3,
            colors = gbotFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = onRawSend, enabled = !busy) {
            Text("Call", color = GrokifyColors.GlowViolet)
        }
        if (rawOut.isNotBlank()) {
            Text(
                rawOut,
                color = GrokifyColors.TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(GrokifyColors.CodeBg)
                    .padding(10.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = { uriHandler.openUri("https://cursor.com") }) {
            Text("Cursor account", color = GrokifyColors.TextDim, fontSize = 12.sp)
        }
    }
}
