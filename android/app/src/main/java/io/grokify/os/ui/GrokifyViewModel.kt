package io.grokify.os.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.grokify.os.BuildConfig
import io.grokify.os.GrokifyApp
import io.grokify.os.apps.plugin.BuiltinPluginCatalog
import io.grokify.os.apps.plugin.isInternalAppSessionTitle
import io.grokify.os.chat.BridgeClient
import io.grokify.os.chat.ChatHistoryWindow
import io.grokify.os.chat.GrokReasoning
import io.grokify.os.data.ApiKeyEntry
import io.grokify.os.data.ApiKeyIds
import io.grokify.os.data.ApiKeyPresets
import io.grokify.os.data.GrokifyApi
import io.grokify.os.permission.AppPermissionId
import io.grokify.os.permission.PermissionHelper
import io.grokify.os.permission.PermissionStatus
import io.grokify.os.service.NotificationMirror
import io.grokify.os.update.ApkUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

enum class ChatRole { User, Assistant, System, Tool, Thinking, Media, PermissionRequest }

/** Lifecycle of an in-chat permission card. */
enum class PermissionRequestStatus { Pending, Granted, Denied, Dismissed }

data class ChatLine(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRole,
    val text: String,
    val streaming: Boolean = false,
    val toolName: String = "",
    /** Tool call input / args (pretty-printed when possible). */
    val toolDetail: String = "",
    /** Tool result body (pretty-printed when possible). */
    val toolResult: String = "",
    val toolSuccess: Boolean? = null,
    val expanded: Boolean = false,
    val serverMsgId: Int = 0,
    /** When true, message stays in the thread but is not sent as agent context. */
    val excludedFromContext: Boolean = false,
    /** Absolute or site-relative URL for Imagine media. */
    val mediaUrl: String = "",
    /** "image" or "video" */
    val mediaKind: String = "",
    /**
     * User-attached photo URLs (durable media-cache or content URIs for the current turn).
     * Shown as thumbnails inside the user bubble.
     */
    val userMediaUrls: List<String> = emptyList(),
    /** Logical permission id for [ChatRole.PermissionRequest] (e.g. camera). */
    val permissionId: String = "",
    val permissionStatus: PermissionRequestStatus = PermissionRequestStatus.Pending,
    /** Epoch ms when the message was created (server [created_at] or local clock). */
    val createdAtMs: Long = System.currentTimeMillis(),
)

/** In-memory image ready to send with a chat prompt (base64 + durable URL for UI). */
data class ChatImageAttachment(
    val mimeType: String,
    val base64: String,
    val displayUrl: String,
    val byteSize: Int,
)

data class SessionItem(
    val id: String,
    val title: String,
    val updatedAt: String,
    val messageCount: Int = 0,
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val lastContextTokens: Long = 0L,
    val wallTimeS: Long = 0L,
    val toolCalls: Int = 0,
    val tokensEstimated: Boolean = false,
)

data class NoteItem(
    val id: Int,
    val text: String,
    val enabled: Boolean,
)

data class ModelItem(
    val id: String,
    val name: String,
    val provider: String,
    val reasoningEfforts: List<String> = emptyList(),
    val defaultReasoningEffort: String = "",
)

/** Directory entry from bridge work-dir list API. */
data class WorkDirEntry(
    val name: String,
    val path: String,
)

data class UsageProduct(
    val product: String,
    val usagePercent: Double? = null,
)

data class UsageDayPoint(
    val day: String,
    val agentSessions: Long = 0L,
    val modelLoops: Long = 0L,
    val toolCalls: Long = 0L,
    val wallTimeS: Long = 0L,
    val modelTimeS: Long = 0L,
    val lastContextTokens: Long = 0L,
    val estimatedInputTokens: Long = 0L,
    val estimatedOutputTokens: Long = 0L,
    val messageCount: Long = 0L,
)

data class UsageTrackerInfo(
    val ok: Boolean = true,
    val agentSessions: Long = 0L,
    val modelLoops: Long = 0L,
    val toolCalls: Long = 0L,
    val wallTimeS: Long = 0L,
    val modelTimeS: Long = 0L,
    val lastContextTokens: Long = 0L,
    val estimatedInputTokens: Long = 0L,
    val estimatedOutputTokens: Long = 0L,
    val reasoningTokens: Long = 0L,
    val messageCount: Long = 0L,
    val tools: List<String> = emptyList(),
    val daily: List<UsageDayPoint> = emptyList(),
    val timezone: String = "",
    val fetchedAt: String = "",
)

/** SuperGrok / Grok Build weekly usage pool (CLI `/usage`). */
data class UsageInfo(
    val usagePercent: Double = 0.0,
    val remainingPercent: Double = 100.0,
    val resetAt: String = "",
    val periodStart: String = "",
    val subscriptionTier: String = "",
    val products: List<UsageProduct> = emptyList(),
    val prepaidBalance: Double = 0.0,
    val fetchedAt: String = "",
    val label: String = "",
    val error: String? = null,
    /** True when CLI/xAI OAuth is missing or revoked. */
    val loginNeeded: Boolean = false,
    /** Device-code status: pending | complete | denied | expired | error | idle */
    val loginStatus: String? = null,
    /** One-tap OIDC device URL (accounts.x.ai) — open in browser to approve. */
    val loginUrl: String? = null,
    val loginUserCode: String? = null,
    val loginMessage: String? = null,
    val tracker: UsageTrackerInfo? = null,
)

enum class ChatPanel { None, History, Notes }

data class UiState(
    val token: String = "",
    val tokenSaved: Boolean = false,
    val connected: Boolean = false,
    val statusText: String = "Not connected",
    val bridgeDetail: String? = null,
    val userLabel: String = "",
    val model: String = "",
    val models: List<ModelItem> = emptyList(),
    /** Grok Build CLI reasoning effort for the selected model (low|medium|high|xhigh). */
    val reasoningEffort: String = "",
    /** Allowed efforts for [model] — xhigh omitted on grok-4.5. */
    val reasoningEfforts: List<String> = emptyList(),
    val messages: List<ChatLine> = emptyList(),
    /** True when older messages exist above the currently loaded window. */
    val hasMoreMessages: Boolean = false,
    val loadingOlder: Boolean = false,
    /** Smallest server message id currently in memory (for older-page cursor). */
    val oldestServerMsgId: Int = 0,
    /**
     * Incremented when the UI should jump to the latest message
     * (session open / initial history load). Streaming uses pin-to-bottom instead.
     */
    val scrollToBottomNonce: Int = 0,
    val draft: String = "",
    val updateInfo: String = "",
    /** True when server has a newer version_code than this install. */
    val updateAvailable: Boolean = false,
    val updateVersionName: String = "",
    val updateVersionCode: Int = 0,
    val updateChangelog: String = "",
    val updateSizeBytes: Long = 0L,
    val updateSha256: String = "",
    val updateDownloadUrl: String = "",
    val updateDownloading: Boolean = false,
    /** 0f..1f while downloading; -1f indeterminate; 0 when idle. */
    val updateProgress: Float = 0f,
    val busy: Boolean = false,
    val error: String? = null,
    val sessionId: String = "",
    val sessionTitle: String = "New chat",
    val sessions: List<SessionItem> = emptyList(),
    val notes: List<NoteItem> = emptyList(),
    val useHistory: Boolean = true,
    val keepScreenOn: Boolean = true,
    /** When true, Enter inserts a newline; when false, Enter sends. */
    val enterForNewline: Boolean = true,
    /** Share active phone notifications with Grok as prompt notes. */
    val shareNotifications: Boolean = true,
    /** Show tool call cards in the chat transcript. */
    val showTools: Boolean = true,
    /** Show thinking / thoughts cards in the chat transcript. */
    val showThoughts: Boolean = true,
    /** System Notification access (NotificationListenerService) granted. */
    val notificationAccessGranted: Boolean = false,
    /** Listener service currently bound (receiving posts). */
    val notificationListenerBound: Boolean = false,
    /** How many active notifications are currently mirrored. */
    val notificationCount: Int = 0,
    /** Runtime permission groups for Settings toggles (camera, mic, …). */
    val permissions: List<PermissionStatus> = emptyList(),
    val panel: ChatPanel = ChatPanel.None,
    /** Full-screen Settings page (not a bottom-nav tab, not a chat overlay). */
    val showSettings: Boolean = false,
    val loadingPanel: Boolean = false,
    val usage: UsageInfo? = null,
    val usageLoading: Boolean = false,
    /** Bridge agent working directory (server path). Empty until loaded. */
    val workDir: String = "",
    val workDirDefault: String = "",
    val workDirIsDefault: Boolean = true,
    val workDirStatus: String = "",
    val workDirLoading: Boolean = false,
    val workDirBrowsePath: String = "",
    val workDirBrowseParent: String? = null,
    val workDirEntries: List<WorkDirEntry> = emptyList(),
    val workDirBrowserOpen: Boolean = false,
    /** Mapbox public access token (pk.…) from Settings / API key vault. Empty disables maps. */
    val mapboxAccessToken: String = "",
    /**
     * Host API key vault (user-facing keys for built-in apps). Values are full
     * secrets held only in process memory while Settings is open.
     */
    val apiKeys: List<ApiKeyEntry> = emptyList(),
    /**
     * User order of built-in app ids in the Apps hub.
     * Empty → default [BuiltinPluginCatalog] order.
     */
    val appOrder: List<String> = emptyList(),
)

class GrokifyViewModel(app: Application) : AndroidViewModel(app) {
    private val store = (app as GrokifyApp).tokenStore
    private val api = GrokifyApi { _token }
    private val apkUpdater = ApkUpdater(app.applicationContext) { _token }
    private var _token: String? = null
    private var wsToken: String = ""
    private var sessionId: String = ""
    private var lastWsUrl: String = BuildConfig.WS_URL

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var bridge: BridgeClient? = null
    private var streamBuf = StringBuilder()
    private var thinkingBuf = StringBuilder()
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private var updateJob: Job? = null
    private var usageJob: Job? = null
    private var loadOlderJob: Job? = null

    /**
     * Bound from MainActivity: launches the OS multi-permission dialog.
     * Signature: (permissions, resultCallback) -> Unit
     */
    private var permissionRequester: ((Array<String>, (Map<String, Boolean>) -> Unit) -> Unit)? = null

    companion object {
        /** Initial + page size for chat history windows (memory). */
        const val MESSAGE_PAGE_SIZE = 40
    }

    init {
        viewModelScope.launch {
            val t = store.tokenFlow.first()
            val useHist = store.useHistoryFlow.first()
            val keepOn = store.keepScreenOnFlow.first()
            val enterNl = store.enterForNewlineFlow.first()
            val shareNotifs = store.shareNotificationsFlow.first()
            val showTools = store.showToolsFlow.first()
            val showThoughts = store.showThoughtsFlow.first()
            val savedModel = store.modelFlow.first()
            val savedEffort = store.reasoningEffortFlow.first()
            val savedSession = store.sessionIdFlow.first()
            val mapboxTok = store.mapboxAccessTokenFlow.first().orEmpty()
            val vault = store.apiKeyVaultFlow.first()
            val appOrder = normalizeAppOrder(store.appOrderFlow.first())
            if (!savedSession.isNullOrBlank()) sessionId = savedSession
            NotificationMirror.setShareEnabled(shareNotifs)
            _state.update {
                it.copy(
                    useHistory = useHist,
                    keepScreenOn = keepOn,
                    enterForNewline = enterNl,
                    shareNotifications = shareNotifs,
                    showTools = showTools,
                    showThoughts = showThoughts,
                    model = savedModel?.takeIf { it.startsWith("gb:") || it.startsWith("grok:") } ?: "",
                    reasoningEffort = savedEffort.orEmpty(),
                    reasoningEfforts = GrokReasoning.effortsFor(savedModel.orEmpty()),
                    sessionId = sessionId,
                    mapboxAccessToken = mapboxTok,
                    apiKeys = vaultForUi(vault, mapboxTok),
                    appOrder = appOrder,
                )
            }
            refreshNotificationAccessState()
            refreshPermissions()

            // Keep vault UI in sync
            viewModelScope.launch {
                store.apiKeyVaultFlow.collect { v ->
                    val mapbox = store.mapboxAccessTokenFlow.first().orEmpty()
                    _state.update {
                        it.copy(
                            apiKeys = vaultForUi(v, mapbox),
                            mapboxAccessToken = mapbox,
                        )
                    }
                }
            }

            if (!t.isNullOrBlank()) {
                _token = t
                _state.update { it.copy(token = t, tokenSaved = true) }
                refresh()
            }
        }
    }

    /** Persist Apps hub tile order after long-press rearrange. */
    fun setAppOrder(ids: List<String>) {
        val cleaned = normalizeAppOrder(ids)
        viewModelScope.launch {
            store.setAppOrder(cleaned)
            _state.update { it.copy(appOrder = cleaned) }
        }
    }

    private fun normalizeAppOrder(ids: List<String>): List<String> {
        val known = BuiltinPluginCatalog.all.map { it.id }
        val knownSet = known.toSet()
        val ordered = ids
            .map { if (it == "spotify_dj") BuiltinPluginCatalog.SPOTIFY_CONTROLLER else it.trim() }
            .filter { it in knownSet }
            .distinct()
            .toMutableList()
        for (id in known) {
            if (id !in ordered) ordered.add(id)
        }
        return ordered
    }

    fun bindPermissionRequester(
        requester: (Array<String>, (Map<String, Boolean>) -> Unit) -> Unit,
    ) {
        permissionRequester = requester
    }

    fun refreshPermissions() {
        val ctx = getApplication<Application>()
        _state.update { it.copy(permissions = PermissionHelper.snapshot(ctx)) }
    }

    /**
     * Settings toggle: ON → system permission dialog when missing;
     * OFF → open app details (Android does not allow silent revoke).
     */
    fun togglePermission(permissionKey: String) {
        val id = AppPermissionId.fromId(permissionKey) ?: return
        val ctx = getApplication<Application>()
        val status = PermissionHelper.status(ctx, id)
        if (!status.requestable) return
        if (status.granted) {
            PermissionHelper.openAppDetailsSettings(ctx)
            return
        }
        requestPermissionGroup(id, chatLineId = null)
    }

    /**
     * Request a permission group only if not already granted (never opens settings to revoke).
     * Used by mini-apps such as Wi‑Fi Scanner.
     */
    fun ensurePermission(permissionKey: String) {
        ensurePermissions(listOf(permissionKey))
    }

    /** Batch-request missing groups in a single system dialog when possible. */
    fun ensurePermissions(permissionKeys: List<String>) {
        val ctx = getApplication<Application>()
        val missing = linkedSetOf<String>()
        for (key in permissionKeys) {
            val id = AppPermissionId.fromId(key) ?: continue
            val status = PermissionHelper.status(ctx, id)
            if (!status.requestable || status.granted) continue
            missing += PermissionHelper.missing(ctx, id).toList()
        }
        if (missing.isEmpty()) {
            refreshPermissions()
            return
        }
        val requester = permissionRequester
        if (requester == null) {
            appendSystem("Cannot open permission dialog — try again from Settings.")
            return
        }
        requester(missing.toTypedArray()) {
            viewModelScope.launch { refreshPermissions() }
        }
    }

    /** User tapped Allow on an in-chat permission card. */
    fun allowPermissionRequest(lineId: String) {
        val line = _state.value.messages.firstOrNull { it.id == lineId } ?: return
        if (line.role != ChatRole.PermissionRequest) return
        if (line.permissionStatus != PermissionRequestStatus.Pending) return
        val id = AppPermissionId.fromId(line.permissionId) ?: return
        val ctx = getApplication<Application>()
        if (PermissionHelper.isGranted(ctx, id)) {
            resolvePermissionCard(lineId, PermissionRequestStatus.Granted)
            refreshPermissions()
            return
        }
        requestPermissionGroup(id, chatLineId = lineId)
    }

    /** User tapped Not now on an in-chat permission card. */
    fun denyPermissionRequest(lineId: String) {
        resolvePermissionCard(lineId, PermissionRequestStatus.Dismissed)
    }

    private fun requestPermissionGroup(id: AppPermissionId, chatLineId: String?) {
        val ctx = getApplication<Application>()
        val missing = PermissionHelper.missing(ctx, id)
        if (missing.isEmpty()) {
            refreshPermissions()
            if (chatLineId != null) {
                resolvePermissionCard(chatLineId, PermissionRequestStatus.Granted)
            }
            return
        }
        val requester = permissionRequester
        if (requester == null) {
            if (chatLineId != null) {
                resolvePermissionCard(chatLineId, PermissionRequestStatus.Denied)
            }
            appendSystem("Cannot open permission dialog — try again from Settings.")
            return
        }
        requester(missing) { _ ->
            viewModelScope.launch {
                refreshPermissions()
                val granted = PermissionHelper.isGranted(getApplication(), id)
                if (chatLineId != null) {
                    resolvePermissionCard(
                        chatLineId,
                        if (granted) PermissionRequestStatus.Granted
                        else PermissionRequestStatus.Denied,
                    )
                } else if (!granted) {
                    // Settings toggle path: still denied after dialog
                    appendSystem("${id.title} was not granted.")
                }
            }
        }
    }

    private fun resolvePermissionCard(lineId: String, status: PermissionRequestStatus) {
        _state.update { st ->
            st.copy(
                messages = st.messages.map { m ->
                    if (m.id == lineId && m.role == ChatRole.PermissionRequest) {
                        m.copy(permissionStatus = status)
                    } else m
                },
            )
        }
    }

    /**
     * Show an in-chat Allow / Not now card for [id] (from AI marker or WS event).
     * Skips if already granted or an open pending card exists for the same permission.
     */
    fun pushPermissionRequest(id: AppPermissionId, reason: String = "") {
        val ctx = getApplication<Application>()
        if (PermissionHelper.isGranted(ctx, id)) {
            // Already allowed — no card needed
            return
        }
        val pendingExists = _state.value.messages.any {
            it.role == ChatRole.PermissionRequest &&
                it.permissionId == id.id &&
                it.permissionStatus == PermissionRequestStatus.Pending
        }
        if (pendingExists) return
        val body = reason.ifBlank {
            "Grok needs ${id.title} to continue."
        }
        _state.update { st ->
            st.copy(
                messages = st.messages + ChatLine(
                    role = ChatRole.PermissionRequest,
                    text = body,
                    permissionId = id.id,
                    permissionStatus = PermissionRequestStatus.Pending,
                ),
                scrollToBottomNonce = st.scrollToBottomNonce + 1,
            )
        }
    }

    /** Extract markers from assistant text, strip them from the bubble, emit cards. */
    private fun consumePermissionMarkers(raw: String): String {
        val requests = PermissionHelper.parseRequestMarkers(raw)
        for ((id, reason) in requests) {
            pushPermissionRequest(id, reason)
        }
        return if (requests.isEmpty()) raw else PermissionHelper.stripRequestMarkers(raw)
    }

    fun refreshNotificationAccessState() {
        val ctx = getApplication<Application>()
        val granted = NotificationMirror.isNotificationAccessEnabled(ctx)
        val wasGranted = _state.value.notificationAccessGranted
        if (granted && !wasGranted) {
            // User just enabled access — rebind so onListenerConnected fires promptly.
            NotificationMirror.requestRebind(ctx)
        }
        _state.update {
            it.copy(
                notificationAccessGranted = granted,
                notificationCount = NotificationMirror.snapshot().size,
                notificationListenerBound = NotificationMirror.isListenerBound(),
            )
        }
        if (granted && _state.value.shareNotifications) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    NotificationMirror.uploadNow()
                } catch (_: Exception) { /* ignore */ }
            }
        }
    }

    /** Called from MainActivity.onResume after returning from system settings. */
    fun onResumeFromSettings() {
        refreshNotificationAccessState()
        refreshPermissions()
    }

    fun saveToken(token: String) {
        viewModelScope.launch {
            val cleaned = token.trim()
            store.setToken(cleaned)
            _token = cleaned.ifBlank { null }
            _state.update {
                it.copy(
                    token = cleaned,
                    tokenSaved = cleaned.isNotBlank(),
                    error = null,
                )
            }
            if (cleaned.isNotBlank()) refresh()
        }
    }

    fun setPanel(panel: ChatPanel) {
        _state.update { it.copy(panel = panel, showSettings = false) }
        when (panel) {
            ChatPanel.History -> loadSessions()
            ChatPanel.Notes -> loadNotes()
            ChatPanel.None -> {}
        }
    }

    /** Open the dedicated Settings page (full content, not a chat sheet). */
    fun openSettings() {
        _state.update { it.copy(showSettings = true, panel = ChatPanel.None) }
        loadModels()
        loadWorkDir()
        refreshUsage(force = false)
        refreshPermissions()
        refreshNotificationAccessState()
    }

    fun closeSettings() {
        _state.update { it.copy(showSettings = false) }
    }

    /** Save or clear the Mapbox public access token used by map views. */
    fun saveMapboxAccessToken(token: String) {
        viewModelScope.launch {
            val cleaned = token.trim()
            store.setMapboxAccessToken(cleaned.ifEmpty { null })
            val vault = store.apiKeyVaultFlow.first()
            _state.update {
                it.copy(
                    mapboxAccessToken = cleaned,
                    apiKeys = vaultForUi(vault, cleaned),
                )
            }
        }
    }

    fun clearMapboxAccessToken() {
        viewModelScope.launch {
            store.setMapboxAccessToken(null)
            val vault = store.apiKeyVaultFlow.first()
            _state.update {
                it.copy(
                    mapboxAccessToken = "",
                    apiKeys = vaultForUi(vault, ""),
                )
            }
        }
    }

    /** Upsert a host API key (Settings "Add API key" + plugin gate). */
    fun saveApiKey(id: String, value: String, label: String? = null, description: String? = null) {
        viewModelScope.launch {
            store.setApiKeyValue(id, value, label, description)
            val vault = store.apiKeyVaultFlow.first()
            val mapbox = store.mapboxAccessTokenFlow.first().orEmpty()
            _state.update {
                it.copy(
                    apiKeys = vaultForUi(vault, mapbox),
                    mapboxAccessToken = mapbox,
                )
            }
        }
    }

    fun clearApiKey(id: String) {
        viewModelScope.launch {
            store.removeApiKey(id)
            val vault = store.apiKeyVaultFlow.first()
            val mapbox = store.mapboxAccessTokenFlow.first().orEmpty()
            _state.update {
                it.copy(
                    apiKeys = vaultForUi(vault, mapbox),
                    mapboxAccessToken = mapbox,
                )
            }
        }
    }

    private fun vaultForUi(
        vault: Map<String, ApiKeyEntry>,
        mapboxTok: String,
    ): List<ApiKeyEntry> {
        val merged = vault.toMutableMap()
        // Always surface known presets (even empty) so Settings shows where to paste keys.
        for (preset in ApiKeyPresets.all) {
            if (preset.id !in merged) {
                merged[preset.id] = preset
            } else {
                val cur = merged[preset.id]!!
                // Keep stored value; refresh label/description from preset when blank.
                merged[preset.id] = cur.copy(
                    label = cur.label.ifBlank { preset.label },
                    description = cur.description.ifBlank { preset.description },
                    preset = true,
                )
            }
        }
        if (mapboxTok.isNotBlank() && merged[ApiKeyIds.MAPBOX]?.value.isNullOrBlank()) {
            merged[ApiKeyIds.MAPBOX] = ApiKeyEntry(
                id = ApiKeyIds.MAPBOX,
                label = ApiKeyPresets.labelFor(ApiKeyIds.MAPBOX),
                value = mapboxTok,
                description = ApiKeyPresets.descriptionFor(ApiKeyIds.MAPBOX),
                preset = true,
            )
        }
        return merged.values
            .filter { it.id !in ApiKeyIds.INTERNAL }
            // Mapbox has its own dedicated card above the vault list.
            .filter { it.id != ApiKeyIds.MAPBOX }
            .sortedWith(
                compareBy(
                    { it.id != ApiKeyIds.SPACEXAI }, // inference key first
                    { it.id != ApiKeyIds.SPACEXAI_MANAGEMENT },
                    { it.id != ApiKeyIds.SPOTIFY_CLIENT_ID },
                    { !it.preset },
                    { it.label.lowercase() },
                    { it.id },
                ),
            )
    }

    fun toggleUseHistory() {
        viewModelScope.launch {
            val next = !_state.value.useHistory
            store.setUseHistory(next)
            _state.update { it.copy(useHistory = next) }
        }
    }

    fun toggleKeepScreenOn() {
        viewModelScope.launch {
            val next = !_state.value.keepScreenOn
            store.setKeepScreenOn(next)
            _state.update { it.copy(keepScreenOn = next) }
        }
    }

    fun toggleEnterForNewline() {
        viewModelScope.launch {
            val next = !_state.value.enterForNewline
            store.setEnterForNewline(next)
            _state.update { it.copy(enterForNewline = next) }
        }
    }

    fun toggleShareNotifications() {
        viewModelScope.launch {
            val next = !_state.value.shareNotifications
            store.setShareNotifications(next)
            NotificationMirror.setShareEnabled(next)
            _state.update { it.copy(shareNotifications = next) }
            refreshNotificationAccessState()
            if (next) {
                val ctx = getApplication<Application>()
                if (NotificationMirror.isNotificationAccessEnabled(ctx)) {
                    NotificationMirror.requestRebind(ctx)
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            NotificationMirror.uploadNow()
                        } catch (_: Exception) { /* ignore */ }
                    }
                }
            }
        }
    }

    fun toggleShowTools() {
        viewModelScope.launch {
            val next = !_state.value.showTools
            store.setShowTools(next)
            _state.update { it.copy(showTools = next) }
        }
    }

    fun toggleShowThoughts() {
        viewModelScope.launch {
            val next = !_state.value.showThoughts
            store.setShowThoughts(next)
            _state.update { it.copy(showThoughts = next) }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val me = withContext(Dispatchers.IO) { api.me() }
                if (me.optBoolean("ok")) {
                    val user = me.optJSONObject("user")
                    val name = user?.optString("display_name")
                        ?: user?.optString("username")
                        ?: "admin"
                    wsToken = me.optString("ws_token")
                    val models = withContext(Dispatchers.IO) { api.models() }
                    if (models.optBoolean("ok")) {
                        wsToken = models.optString("ws_token", wsToken)
                        parseModels(models)
                    }
                    if (sessionId.isBlank() || !isValidSessionId(sessionId)) {
                        ensureSession("New Chat")
                    } else {
                        val title = resolveSessionTitle(sessionId)
                        // Never open Live DJ / plugin bridge sessions as the main Chat thread.
                        if (isInternalAppSessionTitle(title)) {
                            sessionId = ""
                            store.setSessionId("")
                            ensureSession("New Chat")
                        } else {
                            _state.update {
                                it.copy(
                                    sessionId = sessionId,
                                    sessionTitle = title?.ifBlank { it.sessionTitle } ?: it.sessionTitle,
                                )
                            }
                            loadMessagesInternal(sessionId, clearError = false)
                        }
                    }
                    if (wsToken.isBlank()) {
                        _state.update {
                            it.copy(
                                busy = false,
                                error = "No WS token from server",
                                statusText = "Auth incomplete",
                            )
                        }
                        return@launch
                    }
                    lastWsUrl = me.optString("ws_url", BuildConfig.WS_URL).ifBlank { BuildConfig.WS_URL }
                    _state.update {
                        it.copy(
                            userLabel = name,
                            statusText = "API OK · connecting…",
                            sessionId = sessionId,
                            busy = false,
                        )
                    }
                    reconnectAttempts = 0
                    connectBridge(lastWsUrl)
                    loadNotes()
                    refreshUsage()
                } else {
                    _state.update {
                        it.copy(
                            busy = false,
                            error = me.optString("error", "auth_failed"),
                            statusText = "Auth failed",
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(busy = false, error = e.message, statusText = "Network error")
                }
            }
        }
    }

    private fun parseModels(json: JSONObject) {
        val arr = json.optJSONArray("models") ?: return
        val list = mutableListOf<ModelItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val provider = o.optString("provider")
            // Grok Build only — skip any legacy Cursor entries
            if (provider == "cursor") continue
            if (provider.isNotBlank() && provider != "grok-build" && !id.startsWith("gb:")) continue
            val advertised = parseEffortList(o.optJSONArray("reasoning_efforts"))
            list += ModelItem(
                id = id,
                name = o.optString("name").ifBlank { id.removePrefix("gb:") },
                provider = if (provider.isBlank()) "grok-build" else provider,
                reasoningEfforts = GrokReasoning.effortsFor(id, advertised),
                defaultReasoningEffort = o.optString("default_reasoning_effort"),
            )
        }
        val defaultModel = json.optString("default_model").ifBlank {
            list.firstOrNull()?.id.orEmpty()
        }
        val current = _state.value.model
        val previousEffort = _state.value.reasoningEffort
        val resolved = when {
            current.isNotBlank() && list.any { it.id == current } -> current
            else -> json.optString("selected").ifBlank { defaultModel }
        }
        val item = list.find { it.id == resolved }
        val serverEffort = json.optString("selected_reasoning_effort")
        val effort = GrokReasoning.clamp(
            resolved,
            previousEffort.ifBlank { serverEffort },
            item?.reasoningEfforts.orEmpty(),
            item?.defaultReasoningEffort.orEmpty(),
        )
        val efforts = GrokReasoning.effortsFor(resolved, item?.reasoningEfforts.orEmpty())
        _state.update {
            it.copy(
                models = list,
                model = resolved,
                reasoningEffort = effort,
                reasoningEfforts = efforts,
            )
        }
        if (resolved.isNotBlank() && resolved != current) {
            viewModelScope.launch { store.setModel(resolved) }
        }
        if (effort.isNotBlank() && effort != previousEffort) {
            viewModelScope.launch { store.setReasoningEffort(effort, resolved) }
        }
    }

    private fun parseEffortList(arr: org.json.JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val v = arr.optString(i).trim().lowercase()
            if (v.isNotEmpty()) out += v
        }
        return out
    }

    private fun isValidSessionId(id: String) = Regex("^[a-f0-9]{32}$").matches(id)

    private suspend fun ensureSession(title: String = "New Chat"): Boolean {
        return try {
            val sess = withContext(Dispatchers.IO) { api.createChatSession(title) }
            if (sess.optBoolean("ok")) {
                sessionId = sess.optString("id")
                store.setSessionId(sessionId)
                _state.update {
                    it.copy(
                        sessionId = sessionId,
                        sessionTitle = sess.optString("title", title).ifBlank { "New Chat" },
                        messages = emptyList(),
                        hasMoreMessages = false,
                        oldestServerMsgId = 0,
                        loadingOlder = false,
                        scrollToBottomNonce = it.scrollToBottomNonce + 1,
                    )
                }
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }

    fun newChat() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            if (ensureSession("New Chat")) {
                _state.update { it.copy(busy = false, panel = ChatPanel.None) }
            } else {
                _state.update { it.copy(busy = false, error = "Could not create session") }
            }
        }
    }

    fun renameSession(title: String) {
        viewModelScope.launch {
            val cleaned = title.trim().take(255)
            if (cleaned.isEmpty()) {
                _state.update { it.copy(error = "Title cannot be empty") }
                return@launch
            }
            val sid = sessionId
            if (sid.isBlank() || !isValidSessionId(sid)) {
                _state.update { it.copy(error = "No active session") }
                return@launch
            }
            try {
                val res = withContext(Dispatchers.IO) { api.renameChatSession(sid, cleaned) }
                if (res.optBoolean("ok")) {
                    val next = res.optString("title", cleaned).ifBlank { cleaned }
                    applySessionTitle(sid, next)
                } else {
                    _state.update {
                        it.copy(error = res.optString("error", "rename_failed"))
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    private fun applySessionTitle(sid: String, title: String) {
        val t = title.ifBlank { "Chat" }
        _state.update { st ->
            st.copy(
                sessionTitle = if (st.sessionId.equals(sid, true)) t else st.sessionTitle,
                sessions = st.sessions.map {
                    if (it.id.equals(sid, true)) it.copy(title = t) else it
                },
            )
        }
    }

    /** Resolve title for the active session from the sessions list (or keep current). */
    private suspend fun resolveSessionTitle(sid: String): String? {
        return try {
            val data = withContext(Dispatchers.IO) { api.listChatSessions() }
            if (!data.optBoolean("ok", true) && data.has("error")) return null
            val arr = data.optJSONArray("sessions") ?: return null
            val list = mutableListOf<SessionItem>()
            var found: String? = null
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").trim()
                if (id.isBlank()) continue
                val title = o.optString("title").ifBlank { "Chat" }
                if (id.equals(sid, true)) found = title
                // Keep Live DJ / plugin sessions out of the Chat history panel.
                if (isInternalAppSessionTitle(title)) continue
                list += o.toSessionItem(id, title)
            }
            _state.update { it.copy(sessions = list) }
            found
        } catch (_: Exception) {
            null
        }
    }

    fun loadSessions() {
        viewModelScope.launch {
            _state.update { it.copy(loadingPanel = true, error = null) }
            try {
                val data = withContext(Dispatchers.IO) { api.listChatSessions() }
                if (!data.optBoolean("ok", false) && data.has("error")) {
                    _state.update {
                        it.copy(
                            loadingPanel = false,
                            error = "History: ${data.optString("error", "load_failed")}",
                        )
                    }
                    return@launch
                }
                val arr = data.optJSONArray("sessions")
                val list = mutableListOf<SessionItem>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val sid = o.optString("id").trim()
                        if (sid.isBlank()) continue
                        val title = o.optString("title").ifBlank { "Chat" }
                        // Plugin / Spotify Live DJ bridge sessions stay inside those apps.
                        if (isInternalAppSessionTitle(title)) continue
                        list += o.toSessionItem(sid, title)
                    }
                }
                _state.update { it.copy(sessions = list, loadingPanel = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loadingPanel = false, error = e.message) }
            }
        }
    }

    fun selectSession(id: String) {
        viewModelScope.launch {
            val sid = id.trim().lowercase()
            if (!isValidSessionId(sid)) {
                _state.update { it.copy(error = "Invalid session id") }
                return@launch
            }
            val known = _state.value.sessions.find { it.id == sid || it.id.equals(sid, true) }
            if (known != null && isInternalAppSessionTitle(known.title)) {
                _state.update {
                    it.copy(error = "That session belongs to an inner app (e.g. Spotify Live DJ).")
                }
                return@launch
            }
            val prevId = sessionId
            val prevEmpty = _state.value.messages.isEmpty() &&
                (_state.value.sessions.find { it.id == prevId }?.messageCount ?: 0) == 0

            // Drop empty shell sessions (same as web System Chat)
            if (prevId.isNotBlank() && prevId != sid && prevEmpty && isValidSessionId(prevId)) {
                try {
                    withContext(Dispatchers.IO) { api.deleteChatSession(prevId) }
                } catch (_: Exception) { /* ignore */ }
            }

            sessionId = sid
            store.setSessionId(sid)
            _state.update {
                it.copy(
                    sessionId = sid,
                    sessionTitle = known?.title?.ifBlank { "Chat" } ?: "Chat",
                    busy = true,
                    panel = ChatPanel.None,
                    messages = emptyList(),
                    hasMoreMessages = false,
                    oldestServerMsgId = 0,
                    loadingOlder = false,
                    error = null,
                )
            }
            val loaded = loadMessagesInternal(sid)
            if (!loaded) {
                // keep error from loader; still leave session selected
            }
            _state.update { it.copy(busy = false) }
            // refresh list counts in background
            loadSessions()
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { api.deleteChatSession(id.trim()) }
                if (sessionId == id.trim() || sessionId.equals(id.trim(), true)) {
                    ensureSession("New Chat")
                }
                loadSessions()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    /**
     * Load the latest page of messages for a session (memory-friendly).
     * @return true if the API call succeeded (even when the session has 0 messages)
     */
    private suspend fun loadMessagesInternal(
        id: String,
        clearError: Boolean = true,
        jumpToBottom: Boolean = true,
    ): Boolean {
        return try {
            val data = withContext(Dispatchers.IO) {
                api.listMessages(id, limit = MESSAGE_PAGE_SIZE)
            }
            if (!data.optBoolean("ok", false)) {
                val err = data.optString("error", "load_failed")
                _state.update {
                    it.copy(error = "Could not load conversation: $err")
                }
                return false
            }
            val arr = data.optJSONArray("messages")
            val lines = mutableListOf<ChatLine>()
            var oldestId = 0
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    val sid = m.optInt("id", 0)
                    if (sid > 0 && (oldestId == 0 || sid < oldestId)) oldestId = sid
                    lines += mapServerMessage(m)
                }
            }
            if (oldestId == 0) {
                oldestId = data.optInt("oldest_id", 0)
            }
            val hasMore = data.optBoolean("has_more", false)
            var title = _state.value.sessions.find {
                it.id == id || it.id.equals(id, ignoreCase = true)
            }?.title
            // If sessions list is stale/empty, resolve title from API
            if (title.isNullOrBlank() || isDefaultSessionTitle(title)) {
                val resolved = resolveSessionTitle(id)
                if (!resolved.isNullOrBlank()) title = resolved
            }
            // History is always finalized; live stream is rebuilt from WS events
            val finalized = lines.map { line ->
                when {
                    line.streaming -> line.copy(streaming = false)
                    line.role == ChatRole.Tool && line.toolSuccess == null ->
                        line.copy(toolSuccess = true)
                    else -> line
                }
            }
            _state.update {
                it.copy(
                    messages = finalized,
                    hasMoreMessages = hasMore,
                    oldestServerMsgId = oldestId,
                    loadingOlder = false,
                    scrollToBottomNonce = if (jumpToBottom) it.scrollToBottomNonce + 1 else it.scrollToBottomNonce,
                    sessionTitle = title?.ifBlank { it.sessionTitle } ?: it.sessionTitle,
                    error = if (clearError) null else it.error,
                )
            }
            true
        } catch (e: Exception) {
            _state.update { it.copy(error = "Could not load conversation: ${e.message}") }
            false
        }
    }

    /** Prepend older history when the user scrolls near the top. */
    fun loadOlderMessages() {
        val sid = sessionId.ifBlank { _state.value.sessionId }
        if (sid.isBlank() || !isValidSessionId(sid)) return
        val st = _state.value
        if (!st.hasMoreMessages || st.loadingOlder || st.busy) return
        val beforeId = st.oldestServerMsgId
        if (beforeId < 1) return
        if (loadOlderJob?.isActive == true) return
        loadOlderJob = viewModelScope.launch {
            _state.update { it.copy(loadingOlder = true) }
            try {
                val data = withContext(Dispatchers.IO) {
                    api.listMessages(sid, limit = MESSAGE_PAGE_SIZE, beforeId = beforeId)
                }
                if (!data.optBoolean("ok", false)) {
                    _state.update {
                        it.copy(
                            loadingOlder = false,
                            error = "Could not load older messages: ${data.optString("error")}",
                        )
                    }
                    return@launch
                }
                val arr = data.optJSONArray("messages")
                val older = mutableListOf<ChatLine>()
                var newOldest = beforeId
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val m = arr.optJSONObject(i) ?: continue
                        val mid = m.optInt("id", 0)
                        if (mid > 0 && mid < newOldest) newOldest = mid
                        older += mapServerMessage(m).map { line ->
                            when {
                                line.streaming -> line.copy(streaming = false)
                                line.role == ChatRole.Tool && line.toolSuccess == null ->
                                    line.copy(toolSuccess = true)
                                else -> line
                            }
                        }
                    }
                }
                if (data.optInt("oldest_id", 0) > 0) {
                    newOldest = data.optInt("oldest_id")
                }
                val hasMore = data.optBoolean("has_more", false)
                _state.update { cur ->
                    // Avoid duplicating server messages already present
                    val existingIds = cur.messages.map { it.serverMsgId }.filter { it > 0 }.toSet()
                    val filtered = older.filter { it.serverMsgId == 0 || it.serverMsgId !in existingIds }
                    cur.copy(
                        messages = filtered + cur.messages,
                        hasMoreMessages = hasMore,
                        oldestServerMsgId = if (filtered.isNotEmpty()) newOldest else cur.oldestServerMsgId,
                        loadingOlder = false,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(loadingOlder = false, error = "Could not load older messages: ${e.message}")
                }
            }
        }
    }

    private var loginPollJob: Job? = null

    fun refreshUsage(force: Boolean = false) {
        if (usageJob?.isActive == true && !force) return
        usageJob = viewModelScope.launch {
            _state.update { it.copy(usageLoading = true) }
            try {
                val data = withContext(Dispatchers.IO) { api.usage(refresh = force) }
                if (!data.optBoolean("ok", false)) {
                    val err = data.optString("error", "usage_failed").ifBlank { "usage_failed" }
                    val msg = data.optString("message").ifBlank { err }
                    val loginFields = parseLoginFields(data)
                    val tracker = parseUsageTracker(data.optJSONObject("tracker"))
                    _state.update {
                        val prev = it.usage
                        it.copy(
                            usageLoading = false,
                            usage = (prev ?: UsageInfo()).copy(
                                // Keep last good label if we had one; surface short error for the chip.
                                error = msg,
                                label = if (loginFields.loginNeeded && !loginFields.loginUrl.isNullOrBlank()) {
                                    "Usage: tap to re-login"
                                } else {
                                    prev?.label?.takeIf { l -> l.isNotBlank() }
                                        ?: shortUsageError(err, msg)
                                },
                                loginNeeded = loginFields.loginNeeded,
                                loginStatus = loginFields.loginStatus,
                                loginUrl = loginFields.loginUrl,
                                loginUserCode = loginFields.loginUserCode,
                                loginMessage = loginFields.loginMessage,
                                tracker = tracker ?: prev?.tracker,
                            ),
                        )
                    }
                    if (loginFields.loginNeeded) {
                        maybeStartLoginPoll(loginFields.loginStatus)
                    }
                    return@launch
                }
                val products = mutableListOf<UsageProduct>()
                val arr = data.optJSONArray("products")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val p = arr.optJSONObject(i) ?: continue
                        val pct = if (p.has("usage_percent") && !p.isNull("usage_percent")) {
                            p.optDouble("usage_percent")
                        } else {
                            null
                        }
                        products += UsageProduct(
                            product = p.optString("product"),
                            usagePercent = pct,
                        )
                    }
                }
                val pct = data.optDouble("usage_percent", 0.0)
                val resetAt = data.optString("reset_at")
                val tier = data.optString("subscription_tier")
                val tracker = parseUsageTracker(data.optJSONObject("tracker"))
                val label = formatUsageLabel(pct, resetAt, tracker)
                stopLoginPoll()
                _state.update {
                    it.copy(
                        usageLoading = false,
                        usage = UsageInfo(
                            usagePercent = pct,
                            remainingPercent = data.optDouble("remaining_percent", 100.0 - pct),
                            resetAt = resetAt,
                            periodStart = data.optString("period_start"),
                            subscriptionTier = tier,
                            products = products,
                            prepaidBalance = data.optDouble("prepaid_balance", 0.0),
                            fetchedAt = data.optString("fetched_at"),
                            label = label,
                            error = null,
                            loginNeeded = false,
                            loginStatus = null,
                            loginUrl = null,
                            loginUserCode = null,
                            loginMessage = null,
                            tracker = tracker,
                        ),
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    val prev = it.usage
                    it.copy(
                        usageLoading = false,
                        usage = (prev ?: UsageInfo()).copy(
                            error = e.message,
                            label = prev?.label?.takeIf { l -> l.isNotBlank() }
                                ?: shortUsageError("network", e.message ?: "network error"),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Ensure a device-code login is running and return the browser URL to open.
     * Call when the user taps "re-login" / "Sign in with Grok".
     */
    suspend fun ensureGrokLoginUrl(forceNew: Boolean = false): String? {
        return try {
            val data = withContext(Dispatchers.IO) { api.startGrokLogin(force = forceNew) }
            applyLoginResponse(data, defaultError = "Grok re-login needed")
        } catch (_: Exception) {
            _state.value.usage?.loginUrl
        }
    }

    /**
     * Sign out of the current Grok/xAI account on the bridge and start a new OAuth
     * device-code flow. Returns the browser URL so the user can log in as another account.
     */
    suspend fun logoutGrokAndGetLoginUrl(): String? {
        return try {
            val data = withContext(Dispatchers.IO) { api.logoutGrokAndStartLogin() }
            applyLoginResponse(
                data,
                defaultError = data.optString("message").ifBlank {
                    "Signed out — open the login link"
                },
            )
        } catch (_: Exception) {
            _state.value.usage?.loginUrl
        }
    }

    private fun applyLoginResponse(data: JSONObject, defaultError: String): String? {
        val fields = parseLoginFields(data)
        val msg = data.optString("message").ifBlank {
            fields.loginMessage ?: defaultError
        }
        _state.update {
            val prev = it.usage ?: UsageInfo()
            it.copy(
                usage = prev.copy(
                    loginNeeded = true,
                    loginStatus = fields.loginStatus ?: "pending",
                    loginUrl = fields.loginUrl ?: prev.loginUrl,
                    loginUserCode = fields.loginUserCode ?: prev.loginUserCode,
                    loginMessage = msg,
                    label = "Usage: tap to re-login",
                    error = msg,
                    // Clear pool numbers so the card shows the re-login UI, not stale usage.
                    usagePercent = 0.0,
                    remainingPercent = 0.0,
                    products = emptyList(),
                ),
            )
        }
        maybeStartLoginPoll(fields.loginStatus ?: "pending")
        return fields.loginUrl ?: _state.value.usage?.loginUrl
    }

    private data class LoginFields(
        val loginNeeded: Boolean,
        val loginStatus: String?,
        val loginUrl: String?,
        val loginUserCode: String?,
        val loginMessage: String?,
    )

    private fun parseLoginFields(data: JSONObject): LoginFields {
        val loginObj = data.optJSONObject("login")
        val url = data.optString("login_url").ifBlank {
            loginObj?.optString("verification_uri_complete").orEmpty()
        }.ifBlank { null }
        val userCode = data.optString("login_user_code").ifBlank {
            loginObj?.optString("user_code").orEmpty()
        }.ifBlank { null }
        val status = loginObj?.optString("status")?.ifBlank { null }
        val message = loginObj?.optString("message")?.ifBlank { null }
        val needed = when {
            loginObj?.has("needed") == true -> loginObj.optBoolean("needed", false)
            !url.isNullOrBlank() -> true
            else -> {
                val err = data.optString("error").lowercase()
                err.contains("auth") || err.contains("billing_auth") || err.contains("revoked")
            }
        }
        return LoginFields(
            loginNeeded = needed || !url.isNullOrBlank(),
            loginStatus = status,
            loginUrl = url,
            loginUserCode = userCode,
            loginMessage = message,
        )
    }

    private fun maybeStartLoginPoll(status: String?) {
        if (status != "pending" && status != null && status != "idle") {
            // Still poll briefly after start so "complete" is picked up.
            if (status != "pending") return
        }
        if (loginPollJob?.isActive == true) return
        loginPollJob = viewModelScope.launch {
            // Poll for up to ~20 minutes (device codes last ~30m).
            repeat(240) {
                delay(5_000)
                val st = try {
                    withContext(Dispatchers.IO) { api.grokLoginStatus(start = false) }
                } catch (_: Exception) {
                    null
                }
                val login = st?.optJSONObject("login")
                val loginStatus = login?.optString("status").orEmpty()
                if (loginStatus == "complete") {
                    stopLoginPoll()
                    refreshUsage(force = true)
                    return@launch
                }
                if (loginStatus == "denied" || loginStatus == "expired" || loginStatus == "error") {
                    val fields = parseLoginFields(st ?: JSONObject())
                    _state.update {
                        val prev = it.usage ?: UsageInfo()
                        it.copy(
                            usage = prev.copy(
                                loginNeeded = true,
                                loginStatus = fields.loginStatus,
                                loginUrl = fields.loginUrl ?: prev.loginUrl,
                                loginUserCode = fields.loginUserCode ?: prev.loginUserCode,
                                loginMessage = fields.loginMessage,
                                label = "Usage: re-login needed",
                                error = fields.loginMessage ?: prev.error,
                            ),
                        )
                    }
                    stopLoginPoll()
                    return@launch
                }
                // Also try usage in case tokens landed via another worker.
                if (it % 3 == 2) {
                    refreshUsage(force = true)
                    if (_state.value.usage?.error == null && _state.value.usage?.loginNeeded != true) {
                        stopLoginPoll()
                        return@launch
                    }
                }
            }
        }
    }

    private fun stopLoginPoll() {
        loginPollJob?.cancel()
        loginPollJob = null
    }

    private fun shortUsageError(code: String, message: String): String {
        val c = code.lowercase()
        val m = message.lowercase()
        return when {
            c.contains("auth_refresh_revoked") || m.contains("revoked") || m.contains("re-login") ->
                "Usage: tap to re-login"
            c.contains("auth") || m.contains("credential") || m.contains("sync-grok-auth") ->
                "Usage: tap to re-login"
            c.contains("billing_auth") -> "Usage: tap to re-login"
            c.contains("billing") -> "Usage: billing error"
            c.contains("network") || c.contains("timeout") || m.contains("timeout") ->
                "Usage: network error"
            message.contains("auth", ignoreCase = true) -> "Usage: tap to re-login"
            else -> "Usage unavailable"
        }
    }

    private fun formatUsageLabel(
        percent: Double,
        resetAt: String,
        tracker: UsageTrackerInfo? = null,
    ): String {
        val parts = mutableListOf("${formatPct(percent)} used")
        val wall = tracker?.wallTimeS ?: 0L
        if (wall > 0L) parts += UsageFormat.compactDuration(wall)
        val input = tracker?.estimatedInputTokens ?: 0L
        if (input > 0L) parts += "${UsageFormat.compactTokens(input)} in"
        parts += formatResetRelative(resetAt)
        return parts.joinToString(" · ")
    }

    private fun parseUsageTracker(obj: org.json.JSONObject?): UsageTrackerInfo? {
        if (obj == null || !obj.optBoolean("ok", false)) return null
        val totals = obj.optJSONObject("totals") ?: obj
        val tools = mutableListOf<String>()
        val toolsArr = totals.optJSONArray("tools")
        if (toolsArr != null) {
            for (i in 0 until toolsArr.length()) {
                val t = toolsArr.optString(i)
                if (t.isNotBlank()) tools += t
            }
        }
        val daily = mutableListOf<UsageDayPoint>()
        val dailyArr = obj.optJSONArray("daily")
        if (dailyArr != null) {
            for (i in 0 until dailyArr.length()) {
                val d = dailyArr.optJSONObject(i) ?: continue
                daily += UsageDayPoint(
                    day = d.optString("day"),
                    agentSessions = d.optLong("agent_sessions", 0L),
                    modelLoops = d.optLong("model_loops", 0L),
                    toolCalls = d.optLong("tool_calls", 0L),
                    wallTimeS = d.optLong("wall_time_s", 0L),
                    modelTimeS = d.optLong("model_time_s", 0L),
                    lastContextTokens = d.optLong("last_context_tokens", 0L),
                    estimatedInputTokens = d.optLong("estimated_input_tokens", 0L),
                    estimatedOutputTokens = d.optLong("estimated_output_tokens", 0L),
                    messageCount = d.optLong("message_count", 0L),
                )
            }
        }
        return UsageTrackerInfo(
            ok = true,
            agentSessions = totals.optLong("agent_sessions", 0L),
            modelLoops = totals.optLong("model_loops", 0L),
            toolCalls = totals.optLong("tool_calls", 0L),
            wallTimeS = totals.optLong("wall_time_s", 0L),
            modelTimeS = totals.optLong("model_time_s", 0L),
            lastContextTokens = totals.optLong("last_context_tokens", 0L),
            estimatedInputTokens = totals.optLong("estimated_input_tokens", 0L),
            estimatedOutputTokens = totals.optLong("estimated_output_tokens", 0L),
            reasoningTokens = totals.optLong("reasoning_tokens", 0L),
            messageCount = totals.optLong("message_count", 0L),
            tools = tools,
            daily = daily,
            timezone = obj.optString("timezone"),
            fetchedAt = obj.optString("fetched_at"),
        )
    }

    private fun JSONObject.toSessionItem(id: String, title: String): SessionItem =
        SessionItem(
            id = id,
            title = title,
            updatedAt = optString("updated_at"),
            messageCount = optInt("message_count", 0),
            inputTokens = optLong("input_tokens", 0L),
            outputTokens = optLong("output_tokens", 0L),
            lastContextTokens = optLong("last_context_tokens", 0L),
            wallTimeS = optLong("wall_time_s", 0L),
            toolCalls = optInt("tool_calls", 0),
            tokensEstimated = optBoolean("tokens_estimated", false),
        )

    private fun formatPct(percent: Double): String =
        if (percent == percent.toLong().toDouble()) {
            "${percent.toLong()}%"
        } else {
            String.format("%.1f%%", percent)
        }

    private fun formatResetRelative(resetAt: String): String {
        if (resetAt.isBlank()) return "reset unknown"
        return try {
            // e.g. 2026-07-16T18:39:23.720146+00:00
            val cleaned = resetAt
                .replace("Z", "+00:00")
                .let { if (it.contains('.')) it.replace(Regex("\\.\\d+"), "") else it }
            val instant = java.time.OffsetDateTime.parse(cleaned).toInstant()
            val now = java.time.Instant.now()
            val secs = java.time.Duration.between(now, instant).seconds
            when {
                secs <= 0 -> "resets soon"
                secs < 3600 -> "resets in ${secs / 60}m"
                secs < 86400 -> "resets in ${secs / 3600}h"
                else -> {
                    val days = secs / 86400
                    val hours = (secs % 86400) / 3600
                    if (hours > 0) "resets in ${days}d ${hours}h" else "resets in ${days}d"
                }
            }
        } catch (_: Exception) {
            "resets $resetAt"
        }
    }

    private fun isDefaultSessionTitle(title: String): Boolean {
        val t = title.trim()
        return t.isEmpty() || t.equals("New Chat", true) || t.equals("Grokify", true) || t.equals("Chat", true)
    }

    /** Expand a server row into one or more UI lines (thinking / tools / text). */
    private fun mapServerMessage(m: JSONObject): List<ChatLine> {
        val roleStr = m.optString("role")
        val content = m.optString("content")
        val serverId = m.optInt("id", 0)
        val excluded = m.optInt("excluded_from_context", 0) != 0
        val createdAtMs = parseServerCreatedAtMs(m.optString("created_at"))
        val meta = m.optJSONObject("metadata")
        // Never leave historical rows in a "live streaming" spinner state.
        // Active turns are driven by WebSocket events after agent_resume / send.
        val streaming = false

        when (roleStr) {
            "user" -> {
                val userMedia = mutableListOf<String>()
                val mediaArr = meta?.optJSONArray("media")
                if (mediaArr != null) {
                    for (i in 0 until mediaArr.length()) {
                        val m = mediaArr.optJSONObject(i) ?: continue
                        val url = resolveMediaUrl(m.optString("url"))
                        if (url.isNotBlank()) userMedia += url
                    }
                }
                return listOf(
                    ChatLine(
                        role = ChatRole.User,
                        text = content,
                        serverMsgId = serverId,
                        excludedFromContext = excluded,
                        createdAtMs = createdAtMs,
                        userMediaUrls = userMedia,
                    ),
                )
            }
            "system" -> return listOf(
                ChatLine(
                    role = ChatRole.System,
                    text = content,
                    serverMsgId = serverId,
                    excludedFromContext = excluded,
                    createdAtMs = createdAtMs,
                ),
            )
            "assistant" -> {
                val out = mutableListOf<ChatLine>()
                val timeline = meta?.optJSONArray("timeline")
                if (timeline != null && timeline.length() > 0) {
                    for (i in 0 until timeline.length()) {
                        val seg = timeline.optJSONObject(i) ?: continue
                        when (seg.optString("type")) {
                            "thinking" -> {
                                val t = seg.optString("content")
                                if (t.isNotBlank()) {
                                    out += ChatLine(
                                        role = ChatRole.Thinking,
                                        text = t,
                                        expanded = false,
                                        serverMsgId = serverId,
                                        excludedFromContext = excluded,
                                        createdAtMs = createdAtMs,
                                    )
                                }
                            }
                            "tool" -> {
                                val detail = prettyToolText(seg.optString("detail"))
                                val result = prettyToolText(seg.optString("info"))
                                val success: Boolean? = when {
                                    !seg.has("success") || seg.isNull("success") -> true
                                    else -> seg.optBoolean("success")
                                }
                                out += ChatLine(
                                    role = ChatRole.Tool,
                                    text = result.ifBlank { detail },
                                    toolName = seg.optString("tool", "tool"),
                                    toolDetail = detail,
                                    toolResult = result,
                                    toolSuccess = success,
                                    expanded = false,
                                    serverMsgId = serverId,
                                    excludedFromContext = excluded,
                                    createdAtMs = createdAtMs,
                                )
                            }
                            "media" -> {
                                val url = resolveMediaUrl(seg.optString("url"))
                                if (url.isNotBlank()) {
                                    out += ChatLine(
                                        role = ChatRole.Media,
                                        text = jsonNonNullString(seg, "name"),
                                        toolName = jsonNonNullString(seg, "tool"),
                                        mediaUrl = url,
                                        mediaKind = if (seg.optString("kind") == "video") "video" else "image",
                                        serverMsgId = serverId,
                                        excludedFromContext = excluded,
                                        createdAtMs = createdAtMs,
                                    )
                                }
                            }
                            "text" -> {
                                val t = seg.optString("content")
                                if (t.isNotBlank()) {
                                    out += ChatLine(
                                        role = ChatRole.Assistant,
                                        text = t,
                                        serverMsgId = serverId,
                                        streaming = streaming && i == timeline.length() - 1,
                                        excludedFromContext = excluded,
                                        createdAtMs = createdAtMs,
                                    )
                                }
                            }
                        }
                    }
                    // Attach metadata.media if timeline omitted some
                    appendMediaFromMeta(out, meta, serverId, excluded, createdAtMs)
                    if (out.none { it.role == ChatRole.Assistant } && content.isNotBlank()) {
                        out += ChatLine(
                            role = ChatRole.Assistant,
                            text = content,
                            serverMsgId = serverId,
                            streaming = streaming,
                            excludedFromContext = excluded,
                            createdAtMs = createdAtMs,
                        )
                    }
                    if (out.isNotEmpty()) return out
                }
                // Legacy metadata (thinking + tools + content)
                val thinking = meta?.optString("thinking").orEmpty()
                if (thinking.isNotBlank()) {
                    out += ChatLine(
                        role = ChatRole.Thinking,
                        text = thinking,
                        expanded = false,
                        serverMsgId = serverId,
                        excludedFromContext = excluded,
                        createdAtMs = createdAtMs,
                    )
                }
                val tools = meta?.optJSONArray("tools")
                if (tools != null) {
                    for (i in 0 until tools.length()) {
                        val t = tools.optJSONObject(i) ?: continue
                        val detail = prettyToolText(
                            t.optString("detail").ifBlank { t.optString("input") },
                        )
                        val result = prettyToolText(
                            t.optString("info").ifBlank {
                                t.optString("result").ifBlank { t.optString("content") }
                            },
                        )
                        out += ChatLine(
                            role = ChatRole.Tool,
                            text = result.ifBlank { detail },
                            toolName = t.optString("tool", t.optString("name", "tool")),
                            toolDetail = detail,
                            toolResult = result,
                            toolSuccess = if (t.has("success") && !t.isNull("success")) {
                                t.optBoolean("success")
                            } else {
                                true
                            },
                            expanded = false,
                            serverMsgId = serverId,
                            excludedFromContext = excluded,
                            createdAtMs = createdAtMs,
                        )
                    }
                }
                appendMediaFromMeta(out, meta, serverId, excluded, createdAtMs)
                val body = content.ifBlank {
                    expandAssistantContent(content, meta ?: JSONObject())
                }
                if (body.isNotBlank() || out.isEmpty()) {
                    out += ChatLine(
                        role = ChatRole.Assistant,
                        text = body.ifBlank { content },
                        serverMsgId = serverId,
                        streaming = streaming,
                        excludedFromContext = excluded,
                        createdAtMs = createdAtMs,
                    )
                }
                return out
            }
            else -> return listOf(
                ChatLine(
                    role = ChatRole.System,
                    text = content,
                    serverMsgId = serverId,
                    excludedFromContext = excluded,
                    createdAtMs = createdAtMs,
                ),
            )
        }
    }

    /**
     * Parse server `created_at` (MySQL DATETIME / ISO-8601) to epoch ms.
     * Falls back to now when missing or unparsable.
     */
    private fun parseServerCreatedAtMs(raw: String?): Long {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return System.currentTimeMillis()
        // Epoch seconds / millis
        s.toLongOrNull()?.let { n ->
            return if (n < 1_000_000_000_000L) n * 1000L else n
        }
        val normalized = s.replace('T', ' ').removeSuffix("Z")
        val patterns = arrayOf(
            "yyyy-MM-dd HH:mm:ss.SSS",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
        )
        for (p in patterns) {
            val ms = runCatching {
                val fmt = SimpleDateFormat(p, Locale.US)
                // Server timestamps are typically UTC-ish wall clock; display uses local TZ.
                fmt.isLenient = true
                fmt.parse(normalized.substringBefore('+').trim())?.time
            }.getOrNull()
            if (ms != null && ms > 0L) return ms
        }
        return System.currentTimeMillis()
    }

    private fun resolveMediaUrl(raw: String): String {
        val u = raw.trim()
        if (u.isEmpty()) return ""
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        val base = BuildConfig.SITE_URL.trimEnd('/')
        return if (u.startsWith("/")) base + u else "$base/$u"
    }

    /**
     * [JSONObject.optString] turns JSON `null` into the literal string `"null"`.
     * Use this for optional media/tool labels so the UI never shows "null · title".
     */
    private fun jsonNonNullString(obj: JSONObject, key: String): String {
        if (!obj.has(key) || obj.isNull(key)) return ""
        val raw = obj.opt(key) ?: return ""
        if (raw === JSONObject.NULL) return ""
        val s = raw.toString().trim()
        return if (s.isEmpty() || s.equals("null", ignoreCase = true)) "" else s
    }

    private fun appendMediaFromMeta(
        out: MutableList<ChatLine>,
        meta: JSONObject?,
        serverId: Int,
        excluded: Boolean,
        createdAtMs: Long = System.currentTimeMillis(),
    ) {
        val media = meta?.optJSONArray("media") ?: return
        val existing = out.filter { it.role == ChatRole.Media }.map { it.mediaUrl }.toSet()
        for (i in 0 until media.length()) {
            val m = media.optJSONObject(i) ?: continue
            val url = resolveMediaUrl(m.optString("url"))
            if (url.isBlank() || existing.contains(url)) continue
            out += ChatLine(
                role = ChatRole.Media,
                text = jsonNonNullString(m, "name"),
                toolName = jsonNonNullString(m, "tool"),
                createdAtMs = createdAtMs,
                mediaUrl = url,
                mediaKind = if (m.optString("kind") == "video") "video" else "image",
                serverMsgId = serverId,
                excludedFromContext = excluded,
            )
        }
    }

    /**
     * Hide / show a message from future agent context (same as admin System Chat exclude).
     * Applies to every UI segment that shares the same server message id.
     */
    fun toggleMessageExclude(lineId: String) {
        viewModelScope.launch {
            val line = _state.value.messages.find { it.id == lineId } ?: return@launch
            if (line.streaming) return@launch
            val next = !line.excludedFromContext
            val sid = line.serverMsgId
            _state.update { st ->
                st.copy(
                    messages = st.messages.map { m ->
                        when {
                            sid > 0 && m.serverMsgId == sid -> m.copy(excludedFromContext = next)
                            m.id == lineId -> m.copy(excludedFromContext = next)
                            else -> m
                        }
                    },
                )
            }
            if (sid > 0) {
                try {
                    val res = withContext(Dispatchers.IO) {
                        api.toggleMessageExclude(sid, next)
                    }
                    if (!res.optBoolean("ok", false)) {
                        // Revert on failure
                        _state.update { st ->
                            st.copy(
                                messages = st.messages.map { m ->
                                    when {
                                        m.serverMsgId == sid -> m.copy(excludedFromContext = !next)
                                        m.id == lineId -> m.copy(excludedFromContext = !next)
                                        else -> m
                                    }
                                },
                                error = res.optString("error", "exclude_failed"),
                            )
                        }
                    }
                } catch (e: Exception) {
                    _state.update { st ->
                        st.copy(
                            messages = st.messages.map { m ->
                                when {
                                    m.serverMsgId == sid -> m.copy(excludedFromContext = !next)
                                    m.id == lineId -> m.copy(excludedFromContext = !next)
                                    else -> m
                                }
                            },
                            error = e.message,
                        )
                    }
                }
            }
        }
    }

    /** Permanently delete message from session (DB + UI). */
    fun deleteMessage(lineId: String) {
        viewModelScope.launch {
            val line = _state.value.messages.find { it.id == lineId } ?: return@launch
            if (line.streaming) return@launch
            val sid = line.serverMsgId
            _state.update { st ->
                st.copy(
                    messages = if (sid > 0) {
                        st.messages.filterNot { it.serverMsgId == sid }
                    } else {
                        st.messages.filterNot { it.id == lineId }
                    },
                )
            }
            if (sid > 0) {
                try {
                    val res = withContext(Dispatchers.IO) { api.deleteMessage(sid) }
                    if (!res.optBoolean("ok", false)) {
                        _state.update {
                            it.copy(error = res.optString("error", "delete_failed"))
                        }
                        // Reload so UI matches server
                        if (sessionId.isNotBlank()) loadMessagesInternal(sessionId, clearError = false)
                    }
                } catch (e: Exception) {
                    _state.update { it.copy(error = e.message) }
                    if (sessionId.isNotBlank()) loadMessagesInternal(sessionId, clearError = false)
                }
            }
        }
    }

    /** Edit a user message body (server + local). */
    fun editMessage(lineId: String, newText: String) {
        val trimmed = newText.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val line = _state.value.messages.find { it.id == lineId } ?: return@launch
            if (line.role != ChatRole.User || line.streaming) return@launch
            val prev = line.text
            val sid = line.serverMsgId
            _state.update { st ->
                st.copy(
                    messages = st.messages.map {
                        if (it.id == lineId) it.copy(text = trimmed) else it
                    },
                )
            }
            if (sid > 0) {
                try {
                    val res = withContext(Dispatchers.IO) { api.editMessage(sid, trimmed) }
                    if (!res.optBoolean("ok", false)) {
                        _state.update { st ->
                            st.copy(
                                messages = st.messages.map {
                                    if (it.id == lineId) it.copy(text = prev) else it
                                },
                                error = res.optString("error", "edit_failed"),
                            )
                        }
                    }
                } catch (e: Exception) {
                    _state.update { st ->
                        st.copy(
                            messages = st.messages.map {
                                if (it.id == lineId) it.copy(text = prev) else it
                            },
                            error = e.message,
                        )
                    }
                }
            }
        }
    }

    fun loadNotes() {
        viewModelScope.launch {
            try {
                val data = withContext(Dispatchers.IO) { api.listNotes() }
                val arr = data.optJSONArray("notes")
                val list = mutableListOf<NoteItem>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        list += NoteItem(
                            id = o.optInt("id"),
                            text = o.optString("note_text"),
                            enabled = o.optInt("enabled", 1) == 1,
                        )
                    }
                }
                _state.update { it.copy(notes = list, loadingPanel = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loadingPanel = false, error = e.message) }
            }
        }
    }

    fun addNote(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { api.createNote(t) }
                loadNotes()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun toggleNote(id: Int, enabled: Boolean) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { api.toggleNote(id, enabled) }
                _state.update { st ->
                    st.copy(notes = st.notes.map {
                        if (it.id == id) it.copy(enabled = enabled) else it
                    })
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun deleteNote(id: Int) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { api.deleteNote(id) }
                _state.update { st -> st.copy(notes = st.notes.filter { it.id != id }) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun loadModels() {
        viewModelScope.launch {
            _state.update { it.copy(loadingPanel = true) }
            try {
                val models = withContext(Dispatchers.IO) { api.models() }
                if (models.optBoolean("ok")) {
                    parseModels(models)
                    val t = models.optString("ws_token")
                    if (t.isNotBlank()) wsToken = t
                }
                _state.update { it.copy(loadingPanel = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loadingPanel = false, error = e.message) }
            }
        }
    }

    fun selectModel(modelId: String) {
        viewModelScope.launch {
            try {
                val item = _state.value.models.find { it.id == modelId }
                val byModel = store.reasoningByModelFlow.first()
                val effort = GrokReasoning.clamp(
                    modelId,
                    byModel[modelId] ?: _state.value.reasoningEffort,
                    item?.reasoningEfforts.orEmpty(),
                    item?.defaultReasoningEffort.orEmpty(),
                )
                val efforts = GrokReasoning.effortsFor(modelId, item?.reasoningEfforts.orEmpty())
                store.setModel(modelId)
                store.setReasoningEffort(effort, modelId)
                _state.update {
                    it.copy(model = modelId, reasoningEffort = effort, reasoningEfforts = efforts)
                }
                withContext(Dispatchers.IO) { api.setModel(modelId, effort) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun selectReasoningEffort(effort: String) {
        viewModelScope.launch {
            try {
                val model = _state.value.model
                val item = _state.value.models.find { it.id == model }
                val clamped = GrokReasoning.clamp(
                    model,
                    effort,
                    item?.reasoningEfforts.orEmpty(),
                    item?.defaultReasoningEffort.orEmpty(),
                )
                store.setReasoningEffort(clamped, model)
                _state.update { it.copy(reasoningEffort = clamped) }
                if (model.isNotBlank()) {
                    withContext(Dispatchers.IO) { api.setModel(model, clamped) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun loadWorkDir() {
        viewModelScope.launch {
            _state.update { it.copy(workDirLoading = true, workDirStatus = "") }
            try {
                val json = withContext(Dispatchers.IO) { api.workDir() }
                if (json.optBoolean("ok")) {
                    applyWorkDirJson(json)
                    _state.update { it.copy(workDirLoading = false, workDirStatus = "") }
                } else {
                    _state.update {
                        it.copy(
                            workDirLoading = false,
                            workDirStatus = json.optString("error", "Could not load working directory"),
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        workDirLoading = false,
                        workDirStatus = e.message ?: "Could not load working directory",
                    )
                }
            }
        }
    }

    private fun applyWorkDirJson(json: JSONObject) {
        _state.update {
            it.copy(
                workDir = json.optString("path", ""),
                workDirDefault = json.optString("default_path", ""),
                workDirIsDefault = json.optBoolean("is_default", true),
            )
        }
    }

    fun setWorkDir(path: String) {
        viewModelScope.launch {
            _state.update { it.copy(workDirLoading = true, workDirStatus = "Saving…") }
            try {
                val json = withContext(Dispatchers.IO) { api.setWorkDir(path = path.trim(), reset = false) }
                if (json.optBoolean("ok")) {
                    applyWorkDirJson(json)
                    _state.update {
                        it.copy(
                            workDirLoading = false,
                            workDirStatus = "Saved — new chats use this folder",
                            workDirBrowserOpen = false,
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            workDirLoading = false,
                            workDirStatus = json.optString(
                                "message",
                                json.optString("error", "Save failed"),
                            ),
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(workDirLoading = false, workDirStatus = e.message ?: "Save failed")
                }
            }
        }
    }

    fun resetWorkDir() {
        viewModelScope.launch {
            _state.update { it.copy(workDirLoading = true, workDirStatus = "Resetting…") }
            try {
                val json = withContext(Dispatchers.IO) { api.setWorkDir(reset = true) }
                if (json.optBoolean("ok")) {
                    applyWorkDirJson(json)
                    _state.update {
                        it.copy(
                            workDirLoading = false,
                            workDirStatus = "Reset to default",
                            workDirBrowserOpen = false,
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            workDirLoading = false,
                            workDirStatus = json.optString("error", "Reset failed"),
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(workDirLoading = false, workDirStatus = e.message ?: "Reset failed")
                }
            }
        }
    }

    fun toggleWorkDirBrowser() {
        val open = !_state.value.workDirBrowserOpen
        if (!open) {
            _state.update { it.copy(workDirBrowserOpen = false) }
            return
        }
        val start = _state.value.workDir.ifBlank { _state.value.workDirDefault }
        browseWorkDir(start)
    }

    fun browseWorkDir(path: String) {
        viewModelScope.launch {
            _state.update { it.copy(workDirLoading = true) }
            try {
                val json = withContext(Dispatchers.IO) { api.listWorkDir(path) }
                if (json.optBoolean("ok")) {
                    val arr = json.optJSONArray("entries")
                    val entries = buildList {
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val o = arr.optJSONObject(i) ?: continue
                                val name = o.optString("name", "").trim()
                                val p = o.optString("path", "").trim()
                                if (name.isNotEmpty() && p.isNotEmpty()) {
                                    add(WorkDirEntry(name = name, path = p))
                                }
                            }
                        }
                    }
                    val parent = json.optString("parent", "").trim().ifEmpty { null }
                    _state.update {
                        it.copy(
                            workDirLoading = false,
                            workDirBrowserOpen = true,
                            workDirBrowsePath = json.optString("path", path),
                            workDirBrowseParent = parent,
                            workDirEntries = entries,
                            workDirStatus = "",
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            workDirLoading = false,
                            workDirStatus = json.optString(
                                "message",
                                json.optString("error", "Browse failed"),
                            ),
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(workDirLoading = false, workDirStatus = e.message ?: "Browse failed")
                }
            }
        }
    }

    fun useBrowsedWorkDir() {
        val p = _state.value.workDirBrowsePath
        if (p.isNotBlank()) setWorkDir(p)
    }

    private fun connectBridge(wsUrl: String) {
        if (wsToken.isBlank()) return
        reconnectJob?.cancel()
        bridge?.disconnect(notify = false)
        bridge = BridgeClient(
            onEvent = { evt -> viewModelScope.launch { handleEvent(evt) } },
            onState = { ok, detail ->
                viewModelScope.launch {
                    _state.update {
                        it.copy(
                            connected = ok,
                            statusText = if (ok) "Bridge connected" else "Bridge disconnected",
                            bridgeDetail = detail,
                        )
                    }
                    if (ok) {
                        reconnectAttempts = 0
                        reconnectJob?.cancel()
                        // Re-attach to in-flight agent after worker/gateway failover
                        if (sessionId.isNotBlank() && isValidSessionId(sessionId)) {
                            bridge?.reconnect(sessionId)
                        }
                    } else if (!ok && _token != null && wsToken.isNotBlank()) {
                        scheduleReconnect()
                    }
                }
            },
        ).also { it.connect(wsToken, wsUrl.ifBlank { BuildConfig.WS_URL }) }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            reconnectAttempts++
            val wait = minOf(30_000L, 2_000L * reconnectAttempts)
            _state.update {
                it.copy(statusText = "Reconnecting in ${wait / 1000}s…")
            }
            delay(wait)
            try {
                val me = withContext(Dispatchers.IO) { api.me() }
                if (me.optBoolean("ok")) {
                    val t = me.optString("ws_token")
                    if (t.isNotBlank()) wsToken = t
                    lastWsUrl = me.optString("ws_url", lastWsUrl).ifBlank { lastWsUrl }
                }
            } catch (_: Exception) { /* use existing token */ }
            connectBridge(lastWsUrl)
        }
    }

    private fun handleEvent(evt: JSONObject) {
        when (evt.optString("type")) {
            "chunk", "text_delta" -> {
                val c = evt.optString("content")
                if (c.isNotEmpty()) {
                    // Text after thinking: collapse open thought so order stays chronological
                    if (thinkingBuf.isNotEmpty() ||
                        _state.value.messages.any { it.role == ChatRole.Thinking && it.streaming }
                    ) {
                        finalizeThinking(thinkingBuf.toString())
                    }
                    streamBuf.append(c)
                    // Surface AI permission markers as soon as a complete tag is present
                    val cleaned = consumePermissionMarkers(streamBuf.toString())
                    if (cleaned != streamBuf.toString()) {
                        streamBuf = StringBuilder(cleaned)
                    }
                    pushStream()
                }
            }
            "permission_request" -> {
                // Explicit bridge/WS protocol: {type, permission, reason?}
                val key = evt.optString("permission")
                    .ifBlank { evt.optString("id") }
                    .ifBlank { evt.optString("capability") }
                val id = AppPermissionId.fromId(key)
                if (id != null) {
                    pushPermissionRequest(id, evt.optString("reason").ifBlank {
                        evt.optString("message")
                    })
                }
            }
            "thinking_delta" -> {
                val c = evt.optString("content")
                if (c.isNotEmpty()) {
                    // New thought block after text: seal the prior text segment
                    sealOpenAssistantSegment()
                    thinkingBuf.append(c)
                    pushThinking()
                }
            }
            "thinking_done" -> {
                val final = evt.optString("content").ifBlank { thinkingBuf.toString() }
                finalizeThinking(final)
            }
            "text_replace" -> {
                // Seal thinking so text appears after tools/thoughts in order
                if (thinkingBuf.isNotEmpty() ||
                    _state.value.messages.any { it.role == ChatRole.Thinking && it.streaming }
                ) {
                    finalizeThinking(thinkingBuf.toString())
                }
                val replaced = fixSentenceSpacing(evt.optString("content"))
                streamBuf = StringBuilder(consumePermissionMarkers(replaced))
                pushStream()
            }
            "partial_msg_id" -> {
                val mid = evt.optInt("message_id", 0)
                if (mid > 0) {
                    _state.update { st ->
                        val msgs = st.messages.toMutableList()
                        val idx = msgs.indexOfLast { it.role == ChatRole.Assistant && it.streaming }
                        if (idx >= 0) {
                            msgs[idx] = msgs[idx].copy(serverMsgId = mid)
                            st.copy(messages = msgs)
                        } else st
                    }
                }
            }
            "init" -> {
                // New agent started — keep busy flag
                _state.update { it.copy(busy = true, statusText = "Agent running…") }
            }
            "tool_start" -> {
                // Seal prior text/thinking so tools land *after* them chronologically
                if (thinkingBuf.isNotEmpty() ||
                    _state.value.messages.any { it.role == ChatRole.Thinking && it.streaming }
                ) {
                    finalizeThinking(thinkingBuf.toString())
                }
                sealOpenAssistantSegment()
                val tool = evt.optString("tool", "tool")
                val detail = prettyToolText(evt.optString("detail"))
                _state.update {
                    it.copy(
                        messages = it.messages + ChatLine(
                            role = ChatRole.Tool,
                            text = detail,
                            toolName = tool,
                            toolDetail = detail,
                            toolResult = "",
                            toolSuccess = null,
                            expanded = false,
                        ),
                        busy = true,
                    )
                }
            }
            "tool_done" -> {
                val tool = evt.optString("tool", "tool")
                val ok = if (evt.has("success")) evt.optBoolean("success") else true
                val result = prettyToolText(
                    evt.optString("result").ifBlank {
                        evt.optString("info").ifBlank { evt.optString("content") }
                    },
                )
                _state.update { st ->
                    val msgs = st.messages.toMutableList()
                    // Prefer matching open tool by name; fall back to any open tool
                    var idx = msgs.indexOfLast {
                        it.role == ChatRole.Tool && it.toolName == tool && it.toolSuccess == null
                    }
                    if (idx < 0) {
                        idx = msgs.indexOfLast {
                            it.role == ChatRole.Tool && it.toolSuccess == null
                        }
                    }
                    if (idx >= 0) {
                        val prev = msgs[idx]
                        msgs[idx] = prev.copy(
                            toolSuccess = ok,
                            text = result.ifBlank { prev.text },
                            toolResult = result.ifBlank { prev.toolResult },
                            // Keep original input args in toolDetail
                            toolDetail = prev.toolDetail,
                        )
                    } else {
                        msgs += ChatLine(
                            role = ChatRole.Tool,
                            text = result,
                            toolName = tool,
                            toolDetail = "",
                            toolResult = result,
                            toolSuccess = ok,
                        )
                    }
                    st.copy(messages = msgs)
                }
            }
            "media" -> {
                val url = resolveMediaUrl(evt.optString("url"))
                if (url.isNotBlank()) {
                    _state.update { st ->
                        if (st.messages.any { it.role == ChatRole.Media && it.mediaUrl == url }) st
                        else st.copy(
                            messages = st.messages + ChatLine(
                                role = ChatRole.Media,
                                text = jsonNonNullString(evt, "name"),
                                toolName = jsonNonNullString(evt, "tool"),
                                mediaUrl = url,
                                mediaKind = if (evt.optString("kind") == "video") "video" else "image",
                            )
                        )
                    }
                }
            }
            "done" -> {
                // Always seal thinking (even if buffer empty — cards may still be streaming)
                if (thinkingBuf.isNotEmpty()) {
                    finalizeThinking(thinkingBuf.toString())
                } else {
                    finalizeThinking("")
                }
                val hadError = evt.optBoolean("error", false)
                val pending = streamBuf.toString()
                // Keep interleaved segments — only seal remaining streamBuf, do NOT
                // replace earlier text bubbles with the full agent output (that scrambled order).
                finalizeAssistant(pending)
                // Empty silent finish (legacy path before bridge auth errors) → notice in chat
                if (!hadError && pending.isBlank()) {
                    val msgs = _state.value.messages
                    val lastUser = msgs.indexOfLast { it.role == ChatRole.User }
                    val hasReply = lastUser >= 0 && msgs.drop(lastUser + 1).any {
                        (it.role == ChatRole.Assistant && it.text.isNotBlank()) ||
                            it.role == ChatRole.System ||
                            it.role == ChatRole.Tool ||
                            it.role == ChatRole.Media
                    }
                    if (lastUser >= 0 && !hasReply) {
                        appendSystem(
                            "No reply from the agent. If Grok Build auth expired on the server, " +
                                "run `grok login --device-code` and check with " +
                                "`php scripts/check-grok-auth.php`.",
                        )
                        _state.update {
                            it.copy(
                                error = "No agent reply — check Grok Build auth on server",
                                statusText = "No reply",
                            )
                        }
                    }
                }
            }
            // Keep partial text across bridge restarts / WS drops
            "interrupted", "bridge_stopping" -> {
                val agentsSurvive = evt.optBoolean("agents_survive", false) ||
                    evt.optString("reason") == "worker_restart"
                if (thinkingBuf.isNotEmpty()) {
                    finalizeThinking(thinkingBuf.toString())
                }
                val content = evt.optString("content")
                if (content.isNotEmpty() && content.length > streamBuf.length) {
                    streamBuf = StringBuilder(fixSentenceSpacing(content))
                }
                // Detached agents keep running — stay in streaming mode and reconnect
                if (agentsSurvive && (streamBuf.isNotEmpty() || _state.value.busy)) {
                    pushStream()
                    _state.update {
                        it.copy(
                            busy = true,
                            statusText = "Bridge failing over — agent still running…",
                        )
                    }
                    appendSystem("Bridge worker restarting — agent keeps going; reconnecting…")
                    scheduleReconnect()
                    return
                }
                val final = streamBuf.toString().ifBlank { content }
                if (final.isNotBlank()) {
                    finalizeAssistant(fixSentenceSpacing(final))
                    appendSystem(
                        if (evt.optString("type") == "bridge_stopping") {
                            "Bridge restarting — reply saved so far."
                        } else {
                            "Stream interrupted — reply kept."
                        },
                    )
                } else {
                    // Seal any dangling spinners even with empty content
                    finalizeAssistant("")
                    if (sessionId.isNotBlank()) {
                        viewModelScope.launch {
                            loadMessagesInternal(sessionId, clearError = false)
                        }
                    }
                }
            }
            "no_agent" -> {
                // Agent died with bridge; keep whatever we already streamed locally
                if (streamBuf.isNotEmpty() || thinkingBuf.isNotEmpty() ||
                    _state.value.messages.any { it.streaming || (it.role == ChatRole.Tool && it.toolSuccess == null) }
                ) {
                    if (thinkingBuf.isNotEmpty()) finalizeThinking(thinkingBuf.toString())
                    else finalizeThinking("")
                    finalizeAssistant(streamBuf.toString())
                    appendSystem("Bridge restarted — kept partial reply.")
                } else {
                    finalizeAssistant("")
                    // Reload session so server-persisted partial (if any) appears
                    if (sessionId.isNotBlank()) {
                        viewModelScope.launch {
                            loadMessagesInternal(sessionId, clearError = false)
                        }
                    }
                }
            }
            "agent_resume" -> {
                // Live agent still running after reconnect — clear partial bubble for replay
                streamBuf = StringBuilder()
                thinkingBuf = StringBuilder()
                _state.update { st ->
                    st.copy(
                        busy = true,
                        statusText = "Resumed live agent",
                        messages = st.messages.filterNot { it.streaming || it.role == ChatRole.Thinking && it.streaming },
                    )
                }
            }
            "error" -> {
                val content = evt.optString("content", "unknown")
                val code = evt.optString("code")
                val authish = code == "auth_required" ||
                    content.contains("not signed in", ignoreCase = true) ||
                    content.contains("Grok Build is not signed in", ignoreCase = true) ||
                    content.contains("grok login", ignoreCase = true)
                // Seal any streaming spinners without inventing an assistant body
                finalizeAssistant("")
                if (authish) {
                    appendSystem(
                        "⚠️ Grok Build needs sign-in on the server — no reply was produced.\n" +
                            "On the host run:\n" +
                            "  grok login --device-code\n" +
                            "Check: php scripts/check-grok-auth.php\n" +
                            "Then send your message again.",
                    )
                    _state.update {
                        it.copy(
                            error = "Grok Build auth required on server",
                            statusText = "Auth required",
                            busy = false,
                        )
                    }
                } else {
                    appendSystem("Error: $content")
                    _state.update {
                        it.copy(
                            error = content.take(200),
                            statusText = if (it.connected) "Bridge connected" else it.statusText,
                            busy = false,
                        )
                    }
                }
            }
            "status" -> {
                val msg = evt.optString("content").ifBlank { evt.optString("message") }
                if (msg.isNotBlank()) {
                    appendSystem(msg)
                }
            }
        }
    }

    private fun pushStream() {
        val text = streamBuf.toString()
        _state.update { st ->
            val msgs = st.messages.toMutableList()
            // Only update an assistant bubble that is still the latest open segment
            // (after tools/thinking we seal prior text so a new bubble is created)
            val idx = msgs.indexOfLast { it.role == ChatRole.Assistant && it.streaming }
            if (idx >= 0 && idx == msgs.lastIndex) {
                msgs[idx] = msgs[idx].copy(text = text)
            } else if (idx >= 0) {
                // Stale open assistant behind tools — seal it and append fresh
                msgs[idx] = msgs[idx].copy(streaming = false)
                msgs += ChatLine(role = ChatRole.Assistant, text = text, streaming = true)
            } else {
                msgs += ChatLine(role = ChatRole.Assistant, text = text, streaming = true)
            }
            st.copy(messages = msgs, busy = true)
        }
    }

    /**
     * Close the current assistant text segment so later tools/thoughts appear after it
     * in the list (mirrors web timeline closeActiveTextSegment).
     */
    private fun sealOpenAssistantSegment() {
        val pending = streamBuf.toString()
        streamBuf = StringBuilder()
        _state.update { st ->
            val msgs = st.messages.toMutableList()
            val idx = msgs.indexOfLast { it.role == ChatRole.Assistant && it.streaming }
            if (idx >= 0) {
                val existing = msgs[idx]
                val finalText = pending.ifBlank { existing.text }
                if (finalText.isBlank()) {
                    msgs.removeAt(idx)
                } else {
                    msgs[idx] = existing.copy(text = finalText, streaming = false)
                }
            } else if (pending.isNotBlank()) {
                msgs += ChatLine(role = ChatRole.Assistant, text = pending, streaming = false)
            }
            st.copy(messages = msgs)
        }
    }

    /** Pretty-print JSON tool payloads when possible. */
    private fun prettyToolText(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        if ((text.startsWith("{") && text.endsWith("}")) ||
            (text.startsWith("[") && text.endsWith("]"))
        ) {
            return try {
                org.json.JSONTokener(text).nextValue().let { value ->
                    when (value) {
                        is org.json.JSONObject -> value.toString(2)
                        is org.json.JSONArray -> value.toString(2)
                        else -> raw
                    }
                }
            } catch (_: Exception) {
                raw
            }
        }
        return raw
    }

    private fun pushThinking() {
        val text = thinkingBuf.toString()
        _state.update { st ->
            val msgs = st.messages.toMutableList()
            val idx = msgs.indexOfLast { it.role == ChatRole.Thinking && it.streaming }
            if (idx >= 0) {
                msgs[idx] = msgs[idx].copy(text = text)
            } else {
                msgs += ChatLine(role = ChatRole.Thinking, text = text, streaming = true, expanded = true)
            }
            st.copy(messages = msgs, busy = true)
        }
    }

    private fun finalizeThinking(text: String) {
        thinkingBuf = StringBuilder()
        if (text.isBlank()) {
            // Still collapse any dangling streaming thinking cards
            _state.update { st ->
                st.copy(
                    messages = st.messages.map { m ->
                        if (m.role == ChatRole.Thinking && m.streaming) {
                            m.copy(streaming = false, expanded = false)
                        } else m
                    },
                )
            }
            return
        }
        _state.update { st ->
            val msgs = st.messages.toMutableList()
            val idx = msgs.indexOfLast { it.role == ChatRole.Thinking && it.streaming }
            if (idx >= 0) {
                msgs[idx] = msgs[idx].copy(text = text, streaming = false, expanded = false)
            } else {
                // Prefer updating the most recent thinking card if present
                val anyIdx = msgs.indexOfLast { it.role == ChatRole.Thinking }
                if (anyIdx >= 0 && msgs[anyIdx].streaming) {
                    msgs[anyIdx] = msgs[anyIdx].copy(text = text, streaming = false, expanded = false)
                } else if (anyIdx < 0) {
                    msgs += ChatLine(role = ChatRole.Thinking, text = text, streaming = false, expanded = false)
                }
            }
            st.copy(messages = msgs)
        }
    }

    /**
     * End the assistant turn: seal text, collapse thinking, stop tool spinners.
     * Must clear *all* live indicators even when tools/thinking are not the last row.
     * Does not rewrite earlier text segments with the full agent dump (preserves order).
     */
    private fun finalizeAssistant(text: String) {
        streamBuf = StringBuilder()
        thinkingBuf = StringBuilder()
        val cleaned = consumePermissionMarkers(text)
        _state.update { st ->
            val msgs = st.messages.toMutableList()
            var appliedText = false
            for (i in msgs.indices.reversed()) {
                val m = msgs[i]
                when {
                    m.role == ChatRole.Assistant && m.streaming -> {
                        // Keep segmented text; only fill if the open bubble is still empty
                        val body = m.text.ifBlank { cleaned }.let { PermissionHelper.stripRequestMarkers(it) }
                        msgs[i] = m.copy(
                            text = body,
                            streaming = false,
                        )
                        appliedText = true
                    }
                    m.role == ChatRole.Thinking && m.streaming -> {
                        msgs[i] = m.copy(streaming = false, expanded = false)
                    }
                    m.role == ChatRole.Tool && m.toolSuccess == null -> {
                        msgs[i] = m.copy(toolSuccess = true)
                    }
                }
            }
            if (!appliedText && cleaned.isNotBlank()) {
                msgs += ChatLine(role = ChatRole.Assistant, text = cleaned, streaming = false)
            }
            val sealed = msgs.map { m ->
                when {
                    m.streaming -> m.copy(streaming = false)
                    m.role == ChatRole.Tool && m.toolSuccess == null -> m.copy(toolSuccess = true)
                    else -> m
                }
            }
            st.copy(
                messages = sealed,
                busy = false,
                statusText = if (st.connected) "Bridge connected" else st.statusText,
            )
        }
    }

    private fun clientAutoTitle(prompt: String): String {
        val cleaned = prompt.trim().replace(Regex("\\s+"), " ")
        if (cleaned.isEmpty()) return "Chat"
        return if (cleaned.length <= 48) cleaned else cleaned.take(47).trimEnd() + "…"
    }

    private fun appendSystem(text: String) {
        _state.update {
            it.copy(messages = it.messages + ChatLine(role = ChatRole.System, text = text))
        }
    }

    /** Mirror web system-chat.js fixSentenceSpacing — periods need a following space. */
    private fun fixSentenceSpacing(text: String): String {
        if (text.isEmpty()) return text
        var s = text
        // "end.Next" → "end. Next" (skip decimals / common abbreviations)
        s = s.replace(
            Regex("""(?<!\d)([.!?])(["')\]]*)(?=[A-Za-z*_"'(\[])"""),
        ) { m ->
            val before = s.substring(0, m.range.first).takeLast(8)
            if (Regex("""\b(?:Mr|Mrs|Ms|Dr|Prof|vs|etc|e\.g|i\.e|U\.S)$""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(before + m.groupValues[1])
            ) {
                m.value
            } else {
                m.groupValues[1] + m.groupValues[2] + " "
            }
        }
        return s
    }

    fun toggleExpand(id: String) {
        _state.update { st ->
            st.copy(
                messages = st.messages.map {
                    if (it.id == id) it.copy(expanded = !it.expanded) else it
                }
            )
        }
    }

    fun sendMessage(text: String, images: List<ChatImageAttachment> = emptyList()) {
        val prompt = text.trim().ifBlank {
            when {
                images.isEmpty() -> ""
                images.size == 1 -> "Please analyze the attached image and describe what you see."
                else -> "Please analyze the attached images and describe what you see."
            }
        }
        if (prompt.isEmpty() && images.isEmpty()) return
        viewModelScope.launch {
            val localUserId = UUID.randomUUID().toString()
            val displayUrls = images.map { it.displayUrl }.filter { it.isNotBlank() }
            _state.update {
                it.copy(
                    messages = it.messages + ChatLine(
                        id = localUserId,
                        role = ChatRole.User,
                        text = prompt,
                        userMediaUrls = displayUrls,
                    ),
                    draft = "",
                    busy = true,
                    error = null,
                    // Sending always re-pins chat to bottom (user expects to follow reply).
                    scrollToBottomNonce = it.scrollToBottomNonce + 1,
                )
            }
            if (sessionId.isBlank() || !isValidSessionId(sessionId)) {
                ensureSession("New Chat")
            }
            if (sessionId.isBlank() || !isValidSessionId(sessionId)) {
                _state.update {
                    it.copy(busy = false, error = "Could not create chat session — check token")
                }
                return@launch
            }
            if (bridge == null || !_state.value.connected) {
                try {
                    val me = withContext(Dispatchers.IO) { api.me() }
                    if (me.optBoolean("ok")) {
                        wsToken = me.optString("ws_token", wsToken)
                        lastWsUrl = me.optString("ws_url", lastWsUrl).ifBlank { lastWsUrl }
                        connectBridge(lastWsUrl)
                        delay(800)
                    }
                } catch (_: Exception) { /* fall through */ }
            }

            // Persist user message (same as admin System Chat) and attach server id
            val meta = if (displayUrls.isNotEmpty()) {
                val mediaArr = org.json.JSONArray()
                for ((idx, url) in displayUrls.withIndex()) {
                    mediaArr.put(
                        JSONObject()
                            .put("kind", "image")
                            .put("url", url)
                            .put("name", "Photo ${idx + 1}"),
                    )
                }
                JSONObject().put("media", mediaArr)
            } else {
                null
            }
            try {
                val saved = withContext(Dispatchers.IO) {
                    api.createMessage(sessionId, "user", prompt, meta)
                }
                val mid = saved.optInt("id", 0)
                if (mid > 0) {
                    _state.update { st ->
                        st.copy(
                            messages = st.messages.map {
                                if (it.id == localUserId) it.copy(serverMsgId = mid) else it
                            },
                        )
                    }
                }
                // Server auto-names on first user message — reflect it in the chrome
                val autoTitle = saved.optString("session_title")
                if (autoTitle.isNotBlank()) {
                    applySessionTitle(sessionId, autoTitle)
                } else if (isDefaultSessionTitle(_state.value.sessionTitle)) {
                    // Offline fallback: mirror server auto-title client-side
                    applySessionTitle(sessionId, clientAutoTitle(prompt))
                }
            } catch (_: Exception) {
                if (isDefaultSessionTitle(_state.value.sessionTitle)) {
                    applySessionTitle(sessionId, clientAutoTitle(prompt))
                }
            }

            // Build history for context (exclude current user msg already saved)
            var historyPayload = emptyList<JSONObject>()
            if (_state.value.useHistory) {
                historyPayload = buildHistoryPayload(sessionId)
            }
            val notes = buildNotesForPrompt()

            val imagePayload = images.map { img ->
                JSONObject()
                    .put("data", img.base64)
                    .put("mimeType", img.mimeType.ifBlank { "image/jpeg" })
            }

            streamBuf = StringBuilder()
            thinkingBuf = StringBuilder()
            val ok = bridge?.sendPrompt(
                prompt = prompt,
                sessionId = sessionId,
                model = _state.value.model,
                notes = notes,
                history = historyPayload,
                images = imagePayload,
                reasoningEffort = _state.value.reasoningEffort,
            ) == true
            if (!ok) {
                _state.update {
                    it.copy(
                        busy = false,
                        error = "Not connected to bridge — ${_state.value.bridgeDetail ?: "tap Refresh on Home"}",
                    )
                }
            }
        }
    }

    /**
     * Read + compress a content/file URI into a JPEG attachment for chat vision.
     * Also mirrors bytes into media-cache when online so history can show the photo.
     */
    suspend fun prepareChatImage(uri: android.net.Uri): ChatImageAttachment? {
        val app = getApplication<Application>()
        return withContext(Dispatchers.IO) {
            try {
                val jpeg = loadAndCompressChatJpeg(app, uri) ?: return@withContext null
                // Grok drops images under 512 total pixels — already enforced via min scale in loader
                val b64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
                var displayUrl = uri.toString()
                try {
                    val cached = api.cacheMediaBytes(jpeg, "image/jpeg")
                    if (cached.optBoolean("ok")) {
                        val u = cached.optString("url", "")
                        if (u.isNotBlank()) displayUrl = resolveMediaUrl(u)
                    }
                } catch (_: Exception) {
                    // Offline / cache fail: keep local content URI for this session only
                }
                ChatImageAttachment(
                    mimeType = "image/jpeg",
                    base64 = b64,
                    displayUrl = displayUrl,
                    byteSize = jpeg.size,
                )
            } catch (e: Exception) {
                android.util.Log.w("GrokifyVM", "prepareChatImage failed: ${e.message}")
                null
            }
        }
    }

    private fun loadAndCompressChatJpeg(
        ctx: android.content.Context,
        uri: android.net.Uri,
        maxEdge: Int = 1600,
        quality: Int = 85,
    ): ByteArray? {
        return try {
            val resolver = ctx.contentResolver
            // Bounds pass
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, bounds)
            }
            var sample = 1
            val srcW = bounds.outWidth.coerceAtLeast(1)
            val srcH = bounds.outHeight.coerceAtLeast(1)
            val maxDim = maxOf(srcW, srcH)
            while (maxDim / sample > maxEdge * 2) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
            val decoded = resolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, opts)
            } ?: return null
            var working = decoded
            val dim = maxOf(working.width, working.height)
            if (dim > maxEdge) {
                val scale = maxEdge.toFloat() / dim
                val w = maxOf(1, (working.width * scale).toInt())
                val h = maxOf(1, (working.height * scale).toInt())
                val scaled = android.graphics.Bitmap.createScaledBitmap(working, w, h, true)
                if (scaled !== working) {
                    working.recycle()
                    working = scaled
                }
            }
            // Ensure at least ~512 total pixels so Grok vision accepts it
            val px = working.width.toLong() * working.height.toLong()
            if (px < 512L) {
                val scale = kotlin.math.ceil(kotlin.math.sqrt(512.0 / px.toDouble())).toFloat()
                val w = maxOf(1, (working.width * scale).toInt())
                val h = maxOf(1, (working.height * scale).toInt())
                val up = android.graphics.Bitmap.createScaledBitmap(working, w, h, true)
                if (up !== working) {
                    working.recycle()
                    working = up
                }
            }
            val out = java.io.ByteArrayOutputStream()
            working.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
            if (working !== decoded) working.recycle()
            else working.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            android.util.Log.w("GrokifyVM", "loadAndCompressChatJpeg: ${e.message}")
            null
        }
    }

    /**
     * Enabled user notes plus live phone notifications (when sharing is on).
     * Notifications are also uploaded to the server for web/dashboard pull.
     */
    private fun buildNotesForPrompt(): List<String> {
        val notes = _state.value.notes.filter { it.enabled }.map { it.text }.toMutableList()
        // Always inject live permission snapshot so the agent can request toggles when needed
        val ctx = getApplication<Application>()
        val permLines = PermissionHelper.noteLines(ctx)
        if (permLines.isNotEmpty()) {
            notes += permLines.joinToString("\n")
        }
        if (_state.value.shareNotifications) {
            val notifLines = NotificationMirror.noteLines()
            if (notifLines.isNotEmpty()) {
                // One multi-line note keeps the additional_notes block tidy for the agent
                notes += notifLines.joinToString("\n")
            }
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    NotificationMirror.uploadNow()
                } catch (_: Exception) { /* ignore */ }
            }
            refreshNotificationAccessState()
        }
        return notes
    }

    private suspend fun buildHistoryPayload(sid: String): List<JSONObject> {
        return try {
            val h = withContext(Dispatchers.IO) {
                api.listMessages(sid, limit = ChatHistoryWindow.MAX_MESSAGES + 2)
            }
            val arr = h.optJSONArray("messages") ?: return emptyList()
            val raw = mutableListOf<ChatHistoryWindow.Turn>()
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                if (m.optInt("excluded_from_context", 0) != 0) continue
                val role = m.optString("role")
                if (role != "user" && role != "assistant" && role != "system") continue
                val meta = m.optJSONObject("metadata")
                if (role == "assistant" && meta?.optBoolean("streaming") == true) continue
                var content = m.optString("content")
                if (role == "assistant" && meta != null) {
                    content = expandAssistantContent(content, meta, forContext = true)
                }
                raw += ChatHistoryWindow.Turn(role, content)
            }
            // Drop last if it's the just-sent user message (keep prior context)
            if (raw.isNotEmpty() && raw.last().role == "user") {
                raw.removeAt(raw.lastIndex)
            }
            ChatHistoryWindow.fit(raw).map { t ->
                JSONObject().put("role", t.role).put("content", t.content)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun expandAssistantContent(
        content: String,
        meta: JSONObject,
        forContext: Boolean = false,
    ): String {
        val timeline = meta.optJSONArray("timeline")
        if (timeline != null && timeline.length() > 0) {
            val parts = mutableListOf<String>()
            for (i in 0 until timeline.length()) {
                val seg = timeline.optJSONObject(i) ?: continue
                when (seg.optString("type")) {
                    "thinking" -> {
                        if (forContext) continue
                        val c = seg.optString("content")
                        if (c.isNotBlank()) parts += "<thinking>\n$c\n</thinking>"
                    }
                    "tool" -> {
                        val tool = seg.optString("tool")
                        val detail = seg.optString("detail")
                        val info = seg.optString("info").ifBlank {
                            if (seg.optBoolean("success")) "ok" else ""
                        }
                        if (forContext) {
                            val shortDetail = if (detail.length > 240) detail.take(240) + "…" else detail
                            parts += "[$tool] $shortDetail"
                        } else {
                            parts += "[$tool] $detail → $info"
                        }
                    }
                    "text" -> {
                        val c = seg.optString("content")
                        if (c.isNotBlank()) parts += c
                    }
                }
            }
            if (parts.isNotEmpty()) return parts.joinToString("\n\n")
        }
        var result = content
        if (!forContext) {
            val thinking = meta.optString("thinking")
            if (thinking.isNotBlank()) {
                result = "<thinking>\n$thinking\n</thinking>\n\n$result"
            }
        }
        return result
    }

    fun checkUpdate() {
        viewModelScope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    api.checkUpdate(
                        BuildConfig.VERSION_CODE,
                        BuildConfig.VERSION_NAME,
                        channel = GrokifyApi.CHANNEL_PHONE,
                    )
                }
                if (json.optBoolean("update_available")) {
                    val latest = json.optJSONObject("latest")
                    val name = latest?.optString("version_name") ?: "?"
                    val code = latest?.optInt("version_code") ?: 0
                    val changelog = latest?.optString("changelog").orEmpty()
                    val size = latest?.optLong("file_size") ?: 0L
                    val sha = latest?.optString("sha256").orEmpty()
                    val url = latest?.optString("download_url").orEmpty().ifBlank {
                        api.apkDownloadUrl(GrokifyApi.CHANNEL_PHONE)
                    }
                    val sizeLabel = formatBytes(size)
                    _state.update {
                        it.copy(
                            updateAvailable = true,
                            updateVersionName = name,
                            updateVersionCode = code,
                            updateChangelog = changelog,
                            updateSizeBytes = size,
                            updateSha256 = sha,
                            updateDownloadUrl = url,
                            updateInfo = "Update available: $name (code $code) · $sizeLabel",
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            updateAvailable = false,
                            updateVersionName = "",
                            updateVersionCode = 0,
                            updateChangelog = "",
                            updateSizeBytes = 0L,
                            updateSha256 = "",
                            updateDownloadUrl = "",
                            updateInfo = "Up to date (${BuildConfig.VERSION_NAME})",
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        updateInfo = "Update check failed: ${e.message}",
                        updateAvailable = false,
                    )
                }
            }
        }
    }

    /**
     * Download latest APK over the device token and open the system installer.
     * User must approve the install prompt (and “Install unknown apps” once if needed).
     */
    fun downloadAndInstallUpdate() {
        if (updateJob?.isActive == true) return
        updateJob = viewModelScope.launch {
            val st = _state.value
            if (!st.updateAvailable && st.updateDownloadUrl.isBlank()) {
                // Re-check first so Install works even before a manual check.
                try {
                    val json = withContext(Dispatchers.IO) {
                        api.checkUpdate(
                            BuildConfig.VERSION_CODE,
                            BuildConfig.VERSION_NAME,
                            channel = GrokifyApi.CHANNEL_PHONE,
                        )
                    }
                    if (!json.optBoolean("update_available")) {
                        _state.update {
                            it.copy(updateInfo = "Already on latest (${BuildConfig.VERSION_NAME})")
                        }
                        return@launch
                    }
                    val latest = json.optJSONObject("latest")
                    _state.update {
                        it.copy(
                            updateAvailable = true,
                            updateVersionName = latest?.optString("version_name") ?: "?",
                            updateVersionCode = latest?.optInt("version_code") ?: 0,
                            updateChangelog = latest?.optString("changelog").orEmpty(),
                            updateSizeBytes = latest?.optLong("file_size") ?: 0L,
                            updateSha256 = latest?.optString("sha256").orEmpty(),
                            updateDownloadUrl = latest?.optString("download_url").orEmpty()
                                .ifBlank { api.apkDownloadUrl(GrokifyApi.CHANNEL_PHONE) },
                        )
                    }
                } catch (e: Exception) {
                    _state.update { it.copy(updateInfo = "Update check failed: ${e.message}") }
                    return@launch
                }
            }

            val meta = _state.value
            val versionLabel = meta.updateVersionName.ifBlank { "update" }
            _state.update {
                it.copy(
                    updateDownloading = true,
                    updateProgress = 0f,
                    updateInfo = "Downloading $versionLabel…",
                    error = null,
                )
            }
            try {
                val downloadUrl = meta.updateDownloadUrl.ifBlank {
                    api.apkDownloadUrl(GrokifyApi.CHANNEL_PHONE)
                }
                val expectedSha = meta.updateSha256.ifBlank { null }
                val result = withContext(Dispatchers.IO) {
                    apkUpdater.download(
                        downloadUrl = downloadUrl,
                        expectedSha256 = expectedSha,
                        channel = ApkUpdater.CHANNEL_PHONE,
                    ) { progress ->
                        // OkHttp callback thread — StateFlow is thread-safe
                        _state.update {
                            it.copy(
                                updateProgress = if (progress < 0f) 0f else progress.coerceIn(0f, 1f),
                                updateInfo = if (progress < 0f) {
                                    "Downloading…"
                                } else {
                                    "Downloading… ${(progress * 100).toInt()}%"
                                },
                            )
                        }
                    }
                }
                _state.update {
                    it.copy(
                        updateProgress = 1f,
                        updateInfo = "Opening installer…",
                    )
                }
                withContext(Dispatchers.Main) {
                    apkUpdater.install(result.file)
                }
                _state.update {
                    it.copy(
                        updateDownloading = false,
                        updateInfo = "Install prompt opened — approve to update to $versionLabel",
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        updateDownloading = false,
                        updateProgress = 0f,
                        updateInfo = "Update failed: ${e.message}",
                        error = e.message,
                    )
                }
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "—"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) String.format("%.1f MB", mb) else String.format("%.0f KB", kb)
    }

    override fun onCleared() {
        reconnectJob?.cancel()
        updateJob?.cancel()
        bridge?.disconnect()
        super.onCleared()
    }
}
