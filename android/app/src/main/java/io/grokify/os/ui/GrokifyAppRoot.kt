package io.grokify.os.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import io.grokify.os.apps.BluetoothScannerPane
import io.grokify.os.apps.CexBotPane
import io.grokify.os.apps.discord.DiscordPane
import io.grokify.os.apps.gbot.GbotPane
import io.grokify.os.apps.GrokAssistantPane
import io.grokify.os.apps.LocationNotesPane
import io.grokify.os.apps.SpaceXaiUsageAnalyzerPane
import io.grokify.os.apps.SpotifyControllerPane
import io.grokify.os.apps.WifiScannerPane
import io.grokify.os.apps.companion.CompanionPane
import io.grokify.os.apps.plugin.BuiltinPluginCatalog
import io.grokify.os.apps.plugin.PluginAccent
import io.grokify.os.apps.plugin.PluginFavicon
import io.grokify.os.apps.plugin.PluginFaviconImage
import io.grokify.os.apps.plugin.PluginManifest
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material.icons.filled.DragHandle
import io.grokify.os.permission.AppPermissionId
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.viewinterop.AndroidView
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.Image
import coil.compose.AsyncImage
import io.grokify.os.BuildConfig
import io.grokify.os.ui.chat.MarkdownText
import io.grokify.os.ui.theme.GrokifyColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Chat stick-to-bottom: [scrollToItem] only aligns the *top* of a row, so tall
 * streaming bubbles stay clipped. Prefer [scrollBy] when the row is already
 * visible (avoids jump-to-top flicker on every stream token); only jump when
 * the row is off-screen. Optional [extraBottomPx] clears room for action bars.
 */
private data class GrokifyNavFrame(
    val tab: Int,
    val appsScreen: String?,
)

private suspend fun LazyListState.ensureItemBottomVisible(
    index: Int,
    extraBottomPx: Int = 0,
) {
    val total = layoutInfo.totalItemsCount
    if (total <= 0) return
    val safeIndex = index.coerceIn(0, total - 1)

    suspend fun settleOverflow() {
        withFrameNanos { }
        val info = layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == safeIndex } ?: return
        val viewportEnd = info.viewportEndOffset - info.afterContentPadding
        val itemBottom = item.offset + item.size + extraBottomPx
        val overflow = itemBottom - viewportEnd
        if (overflow > 1f) {
            scroll { scrollBy(overflow.toFloat()) }
        }
    }

    // Only hard-jump when the target row isn't already laid out.
    if (layoutInfo.visibleItemsInfo.none { it.index == safeIndex }) {
        scrollToItem(safeIndex)
    }
    settleOverflow()
    // Markdown / AnimatedVisibility often remeasure one frame later
    settleOverflow()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrokifyAppRoot(
    state: UiState,
    onSaveToken: (String) -> Unit,
    onRefresh: () -> Unit,
    onSend: (String, List<ChatImageAttachment>) -> Unit,
    onPrepareChatImage: suspend (android.net.Uri) -> ChatImageAttachment? = { null },
    onCheckUpdate: () -> Unit,
    onDownloadInstallUpdate: () -> Unit = {},
    onToggleExpand: (String) -> Unit = {},
    onSetPanel: (ChatPanel) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onCloseSettings: () -> Unit = {},
    onSaveMapboxAccessToken: (String) -> Unit = {},
    onClearMapboxAccessToken: () -> Unit = {},
    onSaveApiKey: (id: String, value: String, label: String?, description: String?) -> Unit = { _, _, _, _ -> },
    onClearApiKey: (String) -> Unit = {},
    onToggleHistory: () -> Unit = {},
    onToggleKeepScreenOn: () -> Unit = {},
    onToggleEnterForNewline: () -> Unit = {},
    onToggleShareNotifications: () -> Unit = {},
    onToggleShowTools: () -> Unit = {},
    onToggleShowThoughts: () -> Unit = {},
    onOpenNotificationAccess: () -> Unit = {},
    onRefreshNotificationAccess: () -> Unit = {},
    onTogglePermission: (String) -> Unit = {},
    onEnsurePermission: (String) -> Unit = {},
    onEnsurePermissions: (List<String>) -> Unit = {},
    onRefreshPermissions: () -> Unit = {},
    onAllowPermissionRequest: (String) -> Unit = {},
    onDenyPermissionRequest: (String) -> Unit = {},
    onOpenAppPermissionSettings: () -> Unit = {},
    onNewChat: () -> Unit = {},
    onSelectSession: (String) -> Unit = {},
    onDeleteSession: (String) -> Unit = {},
    onAddNote: (String) -> Unit = {},
    onToggleNote: (Int, Boolean) -> Unit = { _, _ -> },
    onDeleteNote: (Int) -> Unit = {},
    onSelectModel: (String) -> Unit = {},
    onSelectReasoningEffort: (String) -> Unit = {},
    onSetWorkDir: (String) -> Unit = {},
    onResetWorkDir: () -> Unit = {},
    onToggleWorkDirBrowser: () -> Unit = {},
    onBrowseWorkDir: (String) -> Unit = {},
    onUseBrowsedWorkDir: () -> Unit = {},
    onToggleMessageExclude: (String) -> Unit = {},
    onDeleteMessage: (String) -> Unit = {},
    onEditMessage: (String, String) -> Unit = { _, _ -> },
    onRenameSession: (String) -> Unit = {},
    onLoadOlder: () -> Unit = {},
    onRefreshUsage: () -> Unit = {},
    /** Open xAI device-code OAuth link when Grok Build needs re-login. */
    onGrokLogin: () -> Unit = {},
    /** Clear bridge Grok auth and open a fresh login link (account switch). */
    onGrokLogout: () -> Unit = {},
    onSetAppOrder: (List<String>) -> Unit = {},
) {
    var tab by remember { mutableIntStateOf(1) } // default Chat
    /** null = Apps hub; else built-in mini-app id (kept while on other tabs). */
    var appsScreen by remember { mutableStateOf<String?>(null) }
    val navStack = remember { mutableStateListOf<GrokifyNavFrame>() }
    var tokenDraft by remember { mutableStateOf(state.token) }
    var chatDraft by remember { mutableStateOf("") }
    var renameOpen by remember { mutableStateOf(false) }
    var renameDraft by remember { mutableStateOf("") }
    var notifAccessDialogOpen by remember { mutableStateOf(false) }
    var notifAccessPrompted by remember { mutableStateOf(false) }
    /** Chat double-back → minimize; timestamp of first back press. */
    var chatBackPressMs by remember { mutableLongStateOf(0L) }
    fun pushNav(nextTab: Int, nextScreen: String?) {
        if (tab == nextTab && appsScreen == nextScreen) {
            onCloseSettings()
            onSetPanel(ChatPanel.None)
            return
        }
        val cur = GrokifyNavFrame(tab, appsScreen)
        if (navStack.lastOrNull() != cur) {
            navStack.add(cur)
            while (navStack.size > 32) navStack.removeAt(0)
        }
        tab = nextTab
        appsScreen = nextScreen
        onCloseSettings()
        onSetPanel(ChatPanel.None)
        chatBackPressMs = 0L
    }
    fun popNav(): Boolean {
        while (navStack.isNotEmpty()) {
            val prev = navStack.removeAt(navStack.lastIndex)
            if (prev.tab != tab || prev.appsScreen != appsScreen) {
                tab = prev.tab
                appsScreen = prev.appsScreen
                onCloseSettings()
                onSetPanel(ChatPanel.None)
                chatBackPressMs = 0L
                return true
            }
        }
        return false
    }
    // Home-screen widgets can request an inner app (and Spotify tab).
    val widgetNav by io.grokify.os.widgets.WidgetNav.pending.collectAsState()
    LaunchedEffect(widgetNav) {
        val req = widgetNav ?: return@LaunchedEffect
        pushNav(2, req.pluginId)
        io.grokify.os.widgets.WidgetNav.consume()
    }
    val context = LocalContext.current
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val keyboardOpen = imeBottom > 0

    BackHandler {
        when {
            state.showSettings -> onCloseSettings()
            state.panel != ChatPanel.None -> onSetPanel(ChatPanel.None)
            renameOpen -> renameOpen = false
            notifAccessDialogOpen -> notifAccessDialogOpen = false
            popNav() -> Unit
            tab == 2 && appsScreen != null -> {
                appsScreen = null
                chatBackPressMs = 0L
            }
            tab != 1 -> {
                tab = 1
                onCloseSettings()
                onSetPanel(ChatPanel.None)
                chatBackPressMs = 0L
            }
            else -> {
                val now = System.currentTimeMillis()
                if (now - chatBackPressMs in 1 until 2_000L) {
                    chatBackPressMs = 0L
                    (context as? Activity)?.moveTaskToBack(true)
                } else {
                    chatBackPressMs = now
                    Toast.makeText(
                        context,
                        "Press back again to minimize",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    // When the persisted token loads (or is saved), keep the draft field aligned
    // unless the user already typed something different.
    LaunchedEffect(state.token) {
        if (tokenDraft.isBlank() || tokenDraft == state.token) {
            tokenDraft = state.token
        }
    }

    // Android cannot grant Notification access via a runtime permission dialog —
    // we must send the user to system settings. Prompt once when sharing is on.
    LaunchedEffect(
        state.tokenSaved,
        state.shareNotifications,
        state.notificationAccessGranted,
    ) {
        if (
            state.tokenSaved &&
            state.shareNotifications &&
            !state.notificationAccessGranted &&
            !notifAccessPrompted
        ) {
            delay(700)
            notifAccessDialogOpen = true
            notifAccessPrompted = true
        }
        // Reset so we re-prompt next session if they turn share back on without access
        if (!state.shareNotifications) {
            notifAccessPrompted = false
        }
        if (state.notificationAccessGranted) {
            notifAccessDialogOpen = false
        }
    }

    fun openRename() {
        renameDraft = state.sessionTitle.ifBlank { "New Chat" }
        renameOpen = true
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(GrokifyColors.Void)
            .drawBehind {
                // Soft ambient glow
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            GrokifyColors.GlowCyan.copy(alpha = 0.07f),
                            Color.Transparent,
                        ),
                        center = Offset(size.width * 0.15f, size.height * 0.05f),
                        radius = size.minDimension * 0.7f,
                    ),
                    radius = size.minDimension * 0.7f,
                    center = Offset(size.width * 0.15f, size.height * 0.05f),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            GrokifyColors.GlowViolet.copy(alpha = 0.05f),
                            Color.Transparent,
                        ),
                        center = Offset(size.width * 0.9f, size.height * 0.3f),
                        radius = size.minDimension * 0.55f,
                    ),
                    radius = size.minDimension * 0.55f,
                    center = Offset(size.width * 0.9f, size.height * 0.3f),
                )
            }
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                if (!(tab == 1 && keyboardOpen && !state.showSettings)) {
                    TopChrome(
                        state = state,
                        tab = tab,
                        settingsOpen = state.showSettings,
                        onRenameSession = { openRename() },
                    )
                }
            },
            bottomBar = {
                if (!keyboardOpen) {
                    NavigationBar(
                        containerColor = GrokifyColors.VoidElevated.copy(alpha = 0.96f),
                        tonalElevation = 0.dp,
                        modifier = Modifier
                            .navigationBarsPadding()
                            .border(
                                width = 0.5.dp,
                                color = GrokifyColors.PanelBorder,
                                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                            ),
                    ) {
                        val colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = GrokifyColors.GlowCyan,
                            selectedTextColor = GrokifyColors.GlowCyan,
                            unselectedIconColor = GrokifyColors.TextDim,
                            unselectedTextColor = GrokifyColors.TextDim,
                            indicatorColor = GrokifyColors.GlowCyan.copy(alpha = 0.12f),
                        )
                        NavigationBarItem(
                            selected = !state.showSettings && tab == 0,
                            onClick = {
                                pushNav(0, appsScreen)
                            },
                            icon = { Icon(Icons.Default.Home, null) },
                            label = { Text("Home", fontSize = 11.sp) },
                            colors = colors,
                        )
                        NavigationBarItem(
                            selected = !state.showSettings && tab == 1,
                            onClick = {
                                pushNav(1, appsScreen)
                            },
                            icon = { Icon(Icons.AutoMirrored.Filled.Chat, null) },
                            label = { Text("Chat", fontSize = 11.sp) },
                            colors = colors,
                        )
                        // Elsewhere + last app open → show that app's icon/name (resume on tap).
                        // Inside an app → show Apps so one tap returns to the hub drawer.
                        val lastAppManifest = appsScreen?.let { id ->
                            val resolved =
                                if (id == "spotify_dj") BuiltinPluginCatalog.SPOTIFY_CONTROLLER
                                else id
                            BuiltinPluginCatalog.get(resolved)
                        }
                        val showLastAppOnTab = tab != 2 && lastAppManifest != null
                        NavigationBarItem(
                            selected = !state.showSettings && tab == 2,
                            onClick = {
                                if (tab == 2 && appsScreen != null) {
                                    // Viewing an inner app → back to Apps hub.
                                    pushNav(2, null)
                                } else {
                                    // Resume last app (or hub if none).
                                    pushNav(2, appsScreen)
                                }
                            },
                            icon = {
                                if (showLastAppOnTab) {
                                    PluginFaviconImage(
                                        pluginId = lastAppManifest!!.id,
                                        fallback = lastAppManifest.icon,
                                        modifier = Modifier.size(24.dp),
                                    )
                                } else {
                                    Icon(Icons.Default.Apps, contentDescription = null)
                                }
                            },
                            label = {
                                Text(
                                    if (showLastAppOnTab) {
                                        appsNavShortTitle(lastAppManifest!!)
                                    } else {
                                        "Apps"
                                    },
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                )
                            },
                            colors = colors,
                        )
                        NavigationBarItem(
                            selected = !state.showSettings && tab == 3,
                            onClick = {
                                pushNav(3, null)
                            },
                            icon = { Icon(Icons.Default.SystemUpdate, null) },
                            label = { Text("Update", fontSize = 11.sp) },
                            colors = colors,
                        )
                    }
                }
            },
        ) { pad ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .imePadding()
            ) {
                if (state.showSettings) {
                    SettingsPage(
                        state = state,
                        onBack = onCloseSettings,
                        onRefreshUsage = onRefreshUsage,
                        onGrokLogin = onGrokLogin,
                        onGrokLogout = onGrokLogout,
                        onSelectModel = onSelectModel,
                        onSelectReasoningEffort = onSelectReasoningEffort,
                        onSetWorkDir = onSetWorkDir,
                        onResetWorkDir = onResetWorkDir,
                        onToggleWorkDirBrowser = onToggleWorkDirBrowser,
                        onBrowseWorkDir = onBrowseWorkDir,
                        onUseBrowsedWorkDir = onUseBrowsedWorkDir,
                        onToggleHistory = onToggleHistory,
                        onToggleKeepScreenOn = onToggleKeepScreenOn,
                        onToggleEnterForNewline = onToggleEnterForNewline,
                        onToggleShareNotifications = onToggleShareNotifications,
                        onToggleShowTools = onToggleShowTools,
                        onToggleShowThoughts = onToggleShowThoughts,
                        onOpenNotificationAccess = onOpenNotificationAccess,
                        onRefreshNotificationAccess = onRefreshNotificationAccess,
                        onTogglePermission = onTogglePermission,
                        onRefreshPermissions = onRefreshPermissions,
                        onOpenAppPermissionSettings = onOpenAppPermissionSettings,
                        onSaveMapboxAccessToken = onSaveMapboxAccessToken,
                        onClearMapboxAccessToken = onClearMapboxAccessToken,
                        onSaveApiKey = onSaveApiKey,
                        onClearApiKey = onClearApiKey,
                    )
                } else when (tab) {
                    0 -> HomePane(
                        state = state,
                        tokenDraft = tokenDraft,
                        onTokenChange = { tokenDraft = it },
                        onSaveToken = { onSaveToken(tokenDraft) },
                        onRefresh = onRefresh,
                        onOpenSettings = onOpenSettings,
                        onOpenNotificationAccess = onOpenNotificationAccess,
                        onRefreshNotificationAccess = onRefreshNotificationAccess,
                    )
                    1 -> ChatPane(
                        state = state,
                        draft = chatDraft,
                        keyboardOpen = keyboardOpen,
                        onDraft = { chatDraft = it },
                        onSend = { images ->
                            onSend(chatDraft, images)
                            chatDraft = ""
                        },
                        onPrepareChatImage = onPrepareChatImage,
                        onToggleExpand = onToggleExpand,
                        onRefresh = onRefresh,
                        onSetPanel = onSetPanel,
                        onOpenSettings = onOpenSettings,
                        onToggleHistory = onToggleHistory,
                        onToggleKeepScreenOn = onToggleKeepScreenOn,
                        onAllowPermissionRequest = onAllowPermissionRequest,
                        onDenyPermissionRequest = onDenyPermissionRequest,
                        onNewChat = onNewChat,
                        onSelectSession = onSelectSession,
                        onDeleteSession = onDeleteSession,
                        onAddNote = onAddNote,
                        onToggleNote = onToggleNote,
                        onDeleteNote = onDeleteNote,
                        onToggleMessageExclude = onToggleMessageExclude,
                        onDeleteMessage = onDeleteMessage,
                        onEditMessage = onEditMessage,
                        onRenameSession = { openRename() },
                        onLoadOlder = onLoadOlder,
                        onRefreshUsage = onRefreshUsage,
                        onGrokLogin = onGrokLogin,
                    )
                    2 -> AppsPane(
                        screen = appsScreen,
                        appOrder = state.appOrder,
                        onOpenApp = { id ->
                            val resolved =
                                if (id == "spotify_dj") "spotify_controller" else id
                            if (BuiltinPluginCatalog.isKnown(resolved) ||
                                resolved == "spotify_controller"
                            ) {
                                pushNav(2, resolved)
                            }
                        },
                        onBackToHub = {
                            if (!popNav()) {
                                appsScreen = null
                            }
                        },
                        onSetAppOrder = onSetAppOrder,
                        onRequestWifiPerms = {
                            // Single system dialog: nearby Wi‑Fi + location (OEM-friendly).
                            onEnsurePermissions(
                                listOf(
                                    AppPermissionId.NEARBY_WIFI.id,
                                    AppPermissionId.LOCATION.id,
                                ),
                            )
                        },
                        onRequestBtPerms = {
                            onEnsurePermissions(
                                listOf(
                                    AppPermissionId.BLUETOOTH.id,
                                    AppPermissionId.LOCATION.id,
                                    AppPermissionId.NOTIFICATIONS.id,
                                ),
                            )
                        },
                        onRequestPlacePerms = {
                            onEnsurePermissions(
                                listOf(
                                    AppPermissionId.LOCATION.id,
                                    AppPermissionId.NOTIFICATIONS.id,
                                    AppPermissionId.MEDIA.id,
                                ),
                            )
                        },
                        onRequestNotifPerms = {
                            onEnsurePermissions(listOf(AppPermissionId.NOTIFICATIONS.id))
                        },
                        onRequestLyrePerms = {
                            onEnsurePermissions(
                                listOf(
                                    AppPermissionId.CAMERA.id,
                                    AppPermissionId.MICROPHONE.id,
                                    AppPermissionId.MEDIA.id,
                                ),
                            )
                        },
                    )
                    3 -> UpdatePane(
                        state = state,
                        onCheckUpdate = onCheckUpdate,
                        onDownloadInstall = onDownloadInstallUpdate,
                        onRefresh = onRefresh,
                    )
                }
            }
        }

        if (renameOpen) {
            AlertDialog(
                onDismissRequest = { renameOpen = false },
                title = {
                    Text("Rename chat", color = GrokifyColors.TextPrimary)
                },
                text = {
                    OutlinedTextField(
                        value = renameDraft,
                        onValueChange = { renameDraft = it },
                        singleLine = true,
                        label = { Text("Session name") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = GrokifyColors.TextPrimary,
                            unfocusedTextColor = GrokifyColors.TextPrimary,
                            focusedBorderColor = GrokifyColors.GlowCyan,
                            unfocusedBorderColor = GrokifyColors.PanelBorder,
                            focusedLabelColor = GrokifyColors.GlowCyan,
                            unfocusedLabelColor = GrokifyColors.TextMuted,
                            cursorColor = GrokifyColors.GlowCyan,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onRenameSession(renameDraft)
                            renameOpen = false
                        },
                    ) {
                        Text("Save", color = GrokifyColors.GlowCyan)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { renameOpen = false }) {
                        Text("Cancel", color = GrokifyColors.TextMuted)
                    }
                },
                containerColor = GrokifyColors.Panel,
            )
        }

        if (notifAccessDialogOpen) {
            AlertDialog(
                onDismissRequest = { notifAccessDialogOpen = false },
                title = {
                    Text("Allow notification access", color = GrokifyColors.TextPrimary)
                },
                text = {
                    Text(
                        "Grok needs Notification access to read your status bar " +
                            "and answer questions like “what’s in my notifications?”.\n\n" +
                            "Tap Open settings, then enable GrokifyOS. This is a system " +
                            "toggle — Android does not allow a one-tap runtime permission.",
                        color = GrokifyColors.TextMuted,
                        fontSize = 13.sp,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            notifAccessDialogOpen = false
                            onOpenNotificationAccess()
                        },
                    ) {
                        Text("Open settings", color = GrokifyColors.GlowCyan)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { notifAccessDialogOpen = false }) {
                        Text("Not now", color = GrokifyColors.TextMuted)
                    }
                },
                containerColor = GrokifyColors.Panel,
            )
        }
    }
}

@Composable
private fun TopChrome(
    state: UiState,
    tab: Int,
    settingsOpen: Boolean = false,
    onRenameSession: () -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(GrokifyColors.HeaderGradient)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .padding(end = 10.dp),
            ) {
                Text(
                    "GROKIFY",
                    style = MaterialTheme.typography.labelSmall,
                    color = GrokifyColors.GlowCyan,
                    letterSpacing = 2.sp,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = if (!settingsOpen && tab == 1) {
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onRenameSession, role = Role.Button)
                            .padding(vertical = 2.dp)
                    } else {
                        Modifier
                    },
                ) {
                    Text(
                        when {
                            settingsOpen -> "Settings"
                            tab == 0 -> "Command center"
                            tab == 1 -> shortenTitle(state.sessionTitle.ifBlank { "Chat" })
                            tab == 2 -> "Inner Apps"
                            else -> "Deploy"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = GrokifyColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (tab == 1) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Rename chat",
                            tint = GrokifyColors.TextDim,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            }
            StatusPill(connected = state.connected, label = state.statusText)
        }
        if (!state.connected && !state.bridgeDetail.isNullOrBlank()) {
            Text(
                state.bridgeDetail,
                color = GrokifyColors.GlowRose,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Compact header title so long session names don't collide with the status pill. */
private fun shortenTitle(title: String, maxChars: Int = 22): String {
    val t = title.trim().replace('\n', ' ')
    if (t.length <= maxChars) return t
    return t.take(maxChars - 1).trimEnd() + "…"
}

@Composable
private fun StatusPill(connected: Boolean, label: String) {
    val color = if (connected) GrokifyColors.GlowMint else GrokifyColors.GlowAmber
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.1f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = if (connected) alpha else 1f))
        )
        Spacer(Modifier.width(6.dp))
        Text(label.take(18), color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun HomePane(
    state: UiState,
    tokenDraft: String,
    onTokenChange: (String) -> Unit,
    onSaveToken: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNotificationAccess: () -> Unit = {},
    onRefreshNotificationAccess: () -> Unit = {},
) {
    var tokenVisible by remember { mutableStateOf(false) }
    var tokenCopiedFlash by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(tokenCopiedFlash) {
        if (tokenCopiedFlash) {
            delay(1400)
            tokenCopiedFlash = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .navigationBarsPadding()
    ) {
        if (state.shareNotifications && !state.notificationAccessGranted) {
            NotificationAccessBanner(
                onGrant = onOpenNotificationAccess,
                onRefresh = onRefreshNotificationAccess,
            )
            Spacer(Modifier.height(12.dp))
        } else if (state.shareNotifications && state.notificationAccessGranted) {
            GlassCard {
                Text(
                    "NOTIFICATIONS",
                    style = MaterialTheme.typography.labelSmall,
                    color = GrokifyColors.GlowCyan,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (state.notificationListenerBound) {
                        "Mirroring ${state.notificationCount} active " +
                            "notification${if (state.notificationCount == 1) "" else "s"} for Grok."
                    } else {
                        "Access granted — waiting for listener to connect " +
                            "(${state.notificationCount} cached)."
                    },
                    color = GrokifyColors.TextMuted,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onRefreshNotificationAccess) {
                    Text("Refresh status", color = GrokifyColors.GlowMint, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        GlassCard {
            val tokenPersisted = state.token.isNotBlank()
            val tokenDirty = tokenDraft.trim() != state.token.trim()
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "DEVICE TOKEN",
                    style = MaterialTheme.typography.labelSmall,
                    color = GrokifyColors.GlowCyan,
                )
                SecretStatusChip(
                    saved = tokenPersisted,
                    dirty = tokenDirty,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    tokenPersisted && !tokenDirty ->
                        "Saved on device · ends …${state.token.takeLast(6)}"
                    tokenPersisted && tokenDirty ->
                        "Saved on device · field has unsaved edits"
                    !tokenPersisted && tokenDraft.isBlank() ->
                        "Empty — paste a token from grokifyos.grokpot.io → Devices"
                    else ->
                        "Empty on device — save to connect"
                },
                color = when {
                    tokenPersisted && !tokenDirty -> GrokifyColors.GlowMint
                    tokenDirty -> GrokifyColors.GlowAmber
                    else -> GrokifyColors.TextMuted
                },
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = tokenDraft,
                onValueChange = onTokenChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("gf_…", color = GrokifyColors.TextDim) },
                singleLine = true,
                visualTransformation = if (tokenVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                        Icon(
                            if (tokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (tokenVisible) "Hide token" else "Show token",
                            tint = GrokifyColors.TextMuted,
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                ),
                colors = fieldColors(),
                shape = RoundedCornerShape(12.dp),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                ),
            )
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onSaveToken,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GrokifyColors.GlowCyan,
                        contentColor = Color(0xFF041016),
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        if (tokenDirty) "Save" else if (tokenPersisted) "Saved" else "Save",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (tokenVisible && tokenDraft.isNotBlank()) {
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(tokenDraft))
                            tokenCopiedFlash = true
                        },
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = null,
                            tint = if (tokenCopiedFlash) GrokifyColors.GlowMint else GrokifyColors.TextMuted,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (tokenCopiedFlash) "Copied" else "Copy",
                            color = if (tokenCopiedFlash) GrokifyColors.GlowMint else GrokifyColors.TextMuted,
                            fontSize = 12.sp,
                        )
                    }
                }
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(GrokifyColors.PanelSoft)
                        .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(10.dp)),
                ) {
                    Icon(Icons.Default.Refresh, "Refresh", tint = GrokifyColors.GlowMint)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        MetricGrid(state)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = GrokifyColors.PanelSoft),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Default.Settings, null, tint = GrokifyColors.GlowCyan)
            Spacer(Modifier.width(8.dp))
            Text("Settings · models · API keys", color = GrokifyColors.TextPrimary)
        }
        state.error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = GrokifyColors.GlowRose, fontSize = 13.sp)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun NotificationAccessBanner(
    onGrant: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(GrokifyColors.GlowRose.copy(alpha = 0.1f))
            .border(1.dp, GrokifyColors.GlowRose.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Notification access required",
            color = GrokifyColors.GlowRose,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
        Text(
            "Enable GrokifyOS under system Notification access so Grok can pull your shade.",
            color = GrokifyColors.TextMuted,
            fontSize = 12.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onGrant,
                colors = ButtonDefaults.buttonColors(
                    containerColor = GrokifyColors.GlowCyan,
                    contentColor = Color(0xFF041016),
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("Grant access", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            TextButton(onClick = onRefresh) {
                Text("I enabled it", color = GrokifyColors.TextMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun MetricGrid(state: UiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("USER", state.userLabel.ifBlank { "—" }, Modifier.weight(1f))
            MetricTile(
                "MODEL",
                buildString {
                    append(state.model.ifBlank { "—" })
                    if (state.reasoningEffort.isNotBlank()) {
                        append(" · ")
                        append(state.reasoningEffort)
                    }
                },
                Modifier.weight(1f),
                mono = true,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(
                "BRIDGE",
                if (state.connected) "ONLINE" else "OFFLINE",
                Modifier.weight(1f),
                accent = if (state.connected) GrokifyColors.GlowMint else GrokifyColors.GlowAmber,
            )
            MetricTile(
                "SESSION",
                state.sessionId.take(8).ifBlank { "—" },
                Modifier.weight(1f),
                mono = true,
            )
        }
        MetricTile(
            "CONTEXT",
            if (state.useHistory) "HISTORY ON" else "HISTORY OFF",
            Modifier.fillMaxWidth(),
            accent = if (state.useHistory) GrokifyColors.GlowCyan else GrokifyColors.TextMuted,
        )
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = GrokifyColors.TextPrimary,
    mono: Boolean = false,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(GrokifyColors.Panel)
            .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = GrokifyColors.TextDim)
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            color = accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.SansSerif,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun GlassCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GrokifyColors.Panel.copy(alpha = 0.9f))
            .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) { content() }
}

@Composable
private fun ChatPane(
    state: UiState,
    draft: String,
    keyboardOpen: Boolean,
    onDraft: (String) -> Unit,
    onSend: (List<ChatImageAttachment>) -> Unit,
    onPrepareChatImage: suspend (android.net.Uri) -> ChatImageAttachment? = { null },
    onToggleExpand: (String) -> Unit,
    onRefresh: () -> Unit,
    onSetPanel: (ChatPanel) -> Unit,
    onOpenSettings: () -> Unit = {},
    onToggleHistory: () -> Unit,
    onToggleKeepScreenOn: () -> Unit,
    onAllowPermissionRequest: (String) -> Unit = {},
    onDenyPermissionRequest: (String) -> Unit = {},
    onNewChat: () -> Unit,
    onSelectSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onAddNote: (String) -> Unit,
    onToggleNote: (Int, Boolean) -> Unit,
    onDeleteNote: (Int) -> Unit,
    onToggleMessageExclude: (String) -> Unit,
    onDeleteMessage: (String) -> Unit,
    onEditMessage: (String, String) -> Unit,
    onRenameSession: () -> Unit = {},
    onLoadOlder: () -> Unit = {},
    onRefreshUsage: () -> Unit = {},
    onGrokLogin: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current
    var menuMsgId by remember { mutableStateOf<String?>(null) }
    var editMsg by remember { mutableStateOf<ChatLine?>(null) }
    var editDraft by remember { mutableStateOf("") }

    // Respect Settings → Chat visibility toggles (data still streamed/stored).
    val visibleMessages = remember(state.messages, state.showTools, state.showThoughts) {
        state.messages.filter { msg ->
            when (msg.role) {
                ChatRole.Tool -> state.showTools
                ChatRole.Thinking -> state.showThoughts
                else -> true
            }
        }
    }

    // Fingerprint tail growth (streaming text/tools/thoughts) for stick-to-bottom.
    // Intentionally omit [ChatLine.expanded]: expanding a card must not yank the list
    // to the newest row while the user is reading it.
    val tailFingerprint = remember(visibleMessages) {
        visibleMessages.takeLast(4).joinToString("|") { m ->
            "${m.id}:${m.role}:${m.text.length}:${m.toolResult.length}:${m.streaming}"
        }
    }

    // Stick-to-bottom: pin only flips from *user* scroll (or explicit force), never
    // from content growth. Layout-driven nearBottom checks race streaming remeasure
    // and falsely unlock mid-token — that was the 0.1.160 regression.
    var pinToBottom by remember { mutableStateOf(true) }
    var prevMessageCount by remember { mutableIntStateOf(0) }
    var lastHandledScrollNonce by remember { mutableIntStateOf(0) }

    fun listPrefixCount(): Int {
        var n = 0
        if (state.hasMoreMessages || state.loadingOlder) n++
        if (visibleMessages.isEmpty() && !state.busy) n++
        return n
    }

    fun isNearBottom(info: androidx.compose.foundation.lazy.LazyListLayoutInfo, slackPx: Int = 160): Boolean {
        val total = info.totalItemsCount
        if (total <= 0) return true
        val last = info.visibleItemsInfo.lastOrNull() ?: return true
        // Last row (or second-to-last while a short spacer/tool is animating in).
        if (last.index < total - 2) return false
        val viewportEnd = info.viewportEndOffset - info.afterContentPadding
        val itemBottom = last.offset + last.size
        // Remaining scroll below the viewport ≤ slack → pinned.
        return itemBottom - viewportEnd <= slackPx
    }

    val menuBottomPadPx = with(density) { 52.dp.roundToPx() }
    val stickBottomPadPx = with(density) { 10.dp.roundToPx() }

    // Only user fling/drag updates pin — mirrors web scroll listener, not layout.
    val stickScrollConnection = remember(listState) {
        object : NestedScrollConnection {
            private fun syncPinFromUserScroll() {
                pinToBottom = isNearBottom(listState.layoutInfo)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source == NestedScrollSource.UserInput && consumed.y != 0f) {
                    syncPinFromUserScroll()
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                // Fling settle: re-evaluate once velocity is applied.
                syncPinFromUserScroll()
                return Velocity.Zero
            }
        }
    }

    // Load older history near the top (independent of pin).
    LaunchedEffect(listState, state.hasMoreMessages, state.loadingOlder) {
        snapshotFlow {
            val info = listState.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull()
            Pair(first?.index ?: -1, info.totalItemsCount)
        }.collect { (firstIndex, total) ->
            if (total > 0 && firstIndex in 0..2 && state.hasMoreMessages && !state.loadingOlder) {
                onLoadOlder()
            }
        }
    }

    // Session open / send / forced jump — once per nonce (NOT on every new message).
    LaunchedEffect(state.scrollToBottomNonce) {
        val nonce = state.scrollToBottomNonce
        if (nonce <= 0 || nonce <= lastHandledScrollNonce) return@LaunchedEffect
        // History / session switch may lag a frame or two behind the nonce bump.
        var waits = 0
        while (visibleMessages.isEmpty() && waits < 12) {
            delay(16)
            waits++
        }
        if (visibleMessages.isEmpty()) return@LaunchedEffect
        lastHandledScrollNonce = nonce
        pinToBottom = true
        val index = listPrefixCount() + visibleMessages.lastIndex
        listState.ensureItemBottomVisible(index, stickBottomPadPx)
        delay(48)
        if (visibleMessages.isNotEmpty()) {
            listState.ensureItemBottomVisible(
                listPrefixCount() + visibleMessages.lastIndex,
                stickBottomPadPx,
            )
        }
        // Programmatic stick must leave us pinned even if layout was mid-growth.
        pinToBottom = true
    }

    // Streaming / new messages while pinned; preserve anchor when prepending older pages
    LaunchedEffect(
        visibleMessages.size,
        tailFingerprint,
        state.loadingOlder,
        state.showTools,
        state.showThoughts,
        pinToBottom,
    ) {
        val size = visibleMessages.size
        // Detect prepend: size grew while not at bottom (user reading history)
        if (size > prevMessageCount && !pinToBottom && prevMessageCount > 0) {
            val added = size - prevMessageCount
            val firstIdx = listState.firstVisibleItemIndex
            if (firstIdx <= 4 && added > 0 && added <= GrokifyViewModel.MESSAGE_PAGE_SIZE + 8) {
                val offset = listState.firstVisibleItemScrollOffset
                listState.scrollToItem(firstIdx + added, offset)
                prevMessageCount = size
                return@LaunchedEffect
            }
        }
        prevMessageCount = size
        // Keep stick-to-bottom during stream; menu open uses its own scroll effect.
        if (visibleMessages.isNotEmpty() && menuMsgId == null && pinToBottom) {
            listState.ensureItemBottomVisible(
                listPrefixCount() + visibleMessages.lastIndex,
                stickBottomPadPx,
            )
        }
    }

    // Drop selection if message was deleted or filtered out
    LaunchedEffect(visibleMessages) {
        val id = menuMsgId
        if (id != null && visibleMessages.none { it.id == id }) {
            menuMsgId = null
        }
    }

    // Bubble action bar sits under the row — scroll it out from under the composer
    LaunchedEffect(menuMsgId) {
        val id = menuMsgId ?: return@LaunchedEffect
        val msgIndex = visibleMessages.indexOfFirst { it.id == id }
        if (msgIndex < 0) return@LaunchedEffect
        // Wait for AnimatedVisibility + layout of the action bar
        delay(60)
        withFrameNanos { }
        listState.ensureItemBottomVisible(
            listPrefixCount() + msgIndex,
            menuBottomPadPx,
        )
        delay(40)
        listState.ensureItemBottomVisible(
            listPrefixCount() + msgIndex,
            menuBottomPadPx,
        )
    }

    fun toggleMenu(msg: ChatLine) {
        if (msg.streaming) return
        menuMsgId = if (menuMsgId == msg.id) null else msg.id
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Toolbar
            ChatToolbar(
                state = state,
                keyboardOpen = keyboardOpen,
                onSetPanel = onSetPanel,
                onOpenSettings = onOpenSettings,
                onToggleHistory = onToggleHistory,
                onToggleKeepScreenOn = onToggleKeepScreenOn,
                onNewChat = onNewChat,
                onRefresh = onRefresh,
                onRefreshUsage = onRefreshUsage,
                onGrokLogin = onGrokLogin,
            )

            // Messages
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(stickScrollConnection)
                        .padding(horizontal = 12.dp)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { menuMsgId = null },
                    // Extra bottom pad when a bubble menu is open so action chips clear the composer
                    contentPadding = PaddingValues(
                        start = 4.dp,
                        end = 4.dp,
                        top = 8.dp,
                        bottom = if (menuMsgId != null) 56.dp else 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.hasMoreMessages || state.loadingOlder) {
                        item(key = "history_header") {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (state.loadingOlder) {
                                    CircularProgressIndicator(
                                        color = GrokifyColors.GlowCyan,
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Loading earlier messages…",
                                        color = GrokifyColors.TextDim,
                                        fontSize = 12.sp,
                                    )
                                } else {
                                    Text(
                                        "Scroll up for earlier messages",
                                        color = GrokifyColors.TextDim,
                                        fontSize = 12.sp,
                                        modifier = Modifier.clickable { onLoadOlder() },
                                    )
                                }
                            }
                        }
                    }
                    if (visibleMessages.isEmpty() && !state.busy) {
                        item {
                            Text(
                                if (state.messages.isEmpty()) {
                                    "No messages yet — say hello."
                                } else {
                                    "Messages hidden by visibility settings."
                                },
                                color = GrokifyColors.TextMuted,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(vertical = 24.dp, horizontal = 8.dp),
                            )
                        }
                    }
                    items(visibleMessages, key = { it.id }) { msg ->
                        val selected = menuMsgId == msg.id
                        when (msg.role) {
                            ChatRole.User -> UserBubble(
                                msg = msg,
                                selected = selected,
                                onTap = { toggleMenu(msg) },
                                actions = {
                                    BubbleActionBar(
                                        alignEnd = true,
                                        excluded = msg.excludedFromContext,
                                        showEdit = true,
                                        onCopy = {
                                            clipboard.setText(AnnotatedString(msg.text))
                                            menuMsgId = null
                                        },
                                        onToggleExclude = {
                                            onToggleMessageExclude(msg.id)
                                            menuMsgId = null
                                        },
                                        onDelete = {
                                            onDeleteMessage(msg.id)
                                            menuMsgId = null
                                        },
                                        onEdit = {
                                            editDraft = msg.text
                                            editMsg = msg
                                            menuMsgId = null
                                        },
                                    )
                                },
                            )
                            ChatRole.Assistant -> AssistantBubble(
                                msg = msg,
                                selected = selected,
                                onTap = { toggleMenu(msg) },
                                actions = {
                                    BubbleActionBar(
                                        alignEnd = false,
                                        excluded = msg.excludedFromContext,
                                        showEdit = false,
                                        onCopy = {
                                            clipboard.setText(AnnotatedString(msg.text))
                                            menuMsgId = null
                                        },
                                        onToggleExclude = {
                                            onToggleMessageExclude(msg.id)
                                            menuMsgId = null
                                        },
                                        onDelete = {
                                            onDeleteMessage(msg.id)
                                            menuMsgId = null
                                        },
                                        onEdit = {},
                                    )
                                },
                            )
                            ChatRole.Thinking -> ThinkingCard(msg) {
                                // Expanding an older thought: unlock stick-to-bottom so
                                // new stream rows don't jump us off the card.
                                val idx = visibleMessages.indexOfFirst { it.id == msg.id }
                                if (!msg.expanded && idx >= 0 && idx < visibleMessages.lastIndex) {
                                    pinToBottom = false
                                }
                                onToggleExpand(msg.id)
                            }
                            ChatRole.Tool -> ToolCard(msg) {
                                val idx = visibleMessages.indexOfFirst { it.id == msg.id }
                                if (!msg.expanded && idx >= 0 && idx < visibleMessages.lastIndex) {
                                    pinToBottom = false
                                }
                                onToggleExpand(msg.id)
                            }
                            ChatRole.Media -> MediaCard(msg)
                            ChatRole.PermissionRequest -> PermissionRequestCard(
                                msg = msg,
                                onAllow = { onAllowPermissionRequest(msg.id) },
                                onDeny = { onDenyPermissionRequest(msg.id) },
                            )
                            ChatRole.System -> SystemLine(
                                msg = msg,
                                selected = selected,
                                onTap = { toggleMenu(msg) },
                                actions = {
                                    BubbleActionBar(
                                        alignEnd = false,
                                        excluded = msg.excludedFromContext,
                                        showEdit = false,
                                        onCopy = {
                                            clipboard.setText(AnnotatedString(msg.text))
                                            menuMsgId = null
                                        },
                                        onToggleExclude = {
                                            onToggleMessageExclude(msg.id)
                                            menuMsgId = null
                                        },
                                        onDelete = {
                                            onDeleteMessage(msg.id)
                                            menuMsgId = null
                                        },
                                        onEdit = {},
                                    )
                                },
                            )
                        }
                    }
                }
                if (state.busy && visibleMessages.isEmpty()) {
                    CircularProgressIndicator(
                        color = GrokifyColors.GlowCyan,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(28.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }

            state.error?.let {
                Text(
                    it,
                    color = GrokifyColors.GlowRose,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }

            // Composer — stays above keyboard via parent imePadding
            ComposerBar(
                draft = draft,
                busy = state.busy,
                connected = state.connected,
                enterForNewline = state.enterForNewline,
                onDraft = onDraft,
                onSend = onSend,
                onPrepareChatImage = onPrepareChatImage,
            )
        }

        // Side panels as overlays (history / notes only — Settings is a full page)
        AnimatedVisibility(
            visible = state.panel != ChatPanel.None,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
        ) {
            when (state.panel) {
                ChatPanel.History -> HistoryPanel(
                    state = state,
                    onClose = { onSetPanel(ChatPanel.None) },
                    onSelect = onSelectSession,
                    onDelete = onDeleteSession,
                    onNew = onNewChat,
                )
                ChatPanel.Notes -> NotesPanel(
                    state = state,
                    onClose = { onSetPanel(ChatPanel.None) },
                    onAdd = onAddNote,
                    onToggle = onToggleNote,
                    onDelete = onDeleteNote,
                )
                ChatPanel.None -> {}
            }
        }

        // Edit user message dialog
        val editing = editMsg
        if (editing != null) {
            AlertDialog(
                onDismissRequest = { editMsg = null },
                containerColor = GrokifyColors.VoidElevated,
                titleContentColor = GrokifyColors.TextPrimary,
                textContentColor = GrokifyColors.TextPrimary,
                title = { Text("Edit message") },
                text = {
                    OutlinedTextField(
                        value = editDraft,
                        onValueChange = { editDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 8,
                        colors = fieldColors(),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onEditMessage(editing.id, editDraft)
                            editMsg = null
                        },
                        enabled = editDraft.trim().isNotEmpty(),
                    ) {
                        Text("Save", color = GrokifyColors.GlowMint)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { editMsg = null }) {
                        Text("Cancel", color = GrokifyColors.TextMuted)
                    }
                },
            )
        }
    }
}

@Composable
private fun BubbleActionBar(
    alignEnd: Boolean,
    excluded: Boolean,
    showEdit: Boolean,
    onCopy: () -> Unit,
    onToggleExclude: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(GrokifyColors.VoidElevated.copy(alpha = 0.98f))
                .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(999.dp))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BubbleIconBtn(
                icon = Icons.Default.ContentCopy,
                label = "Copy",
                tint = GrokifyColors.GlowCyan,
                onClick = onCopy,
            )
            BubbleIconBtn(
                icon = if (excluded) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                label = if (excluded) "Show in context" else "Hide from context",
                tint = GrokifyColors.GlowViolet,
                onClick = onToggleExclude,
            )
            BubbleIconBtn(
                icon = Icons.Default.Delete,
                label = "Delete from context",
                tint = GrokifyColors.GlowRose,
                onClick = onDelete,
            )
            if (showEdit) {
                BubbleIconBtn(
                    icon = Icons.Default.Edit,
                    label = "Edit message",
                    tint = GrokifyColors.GlowMint,
                    onClick = onEdit,
                )
            }
        }
    }
}

@Composable
private fun BubbleIconBtn(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(34.dp),
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun ChatToolbar(
    state: UiState,
    keyboardOpen: Boolean,
    onSetPanel: (ChatPanel) -> Unit,
    onOpenSettings: () -> Unit = {},
    onToggleHistory: () -> Unit,
    onToggleKeepScreenOn: () -> Unit,
    onNewChat: () -> Unit,
    onRefresh: () -> Unit,
    onRefreshUsage: () -> Unit = {},
    onGrokLogin: () -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolbarChip(
                label = "History",
                icon = Icons.Default.History,
                active = state.panel == ChatPanel.History,
                onClick = {
                    onSetPanel(if (state.panel == ChatPanel.History) ChatPanel.None else ChatPanel.History)
                },
            )
            ToolbarChip(
                label = if (state.useHistory) "Context" else "No ctx",
                icon = if (state.useHistory) Icons.Outlined.Link else Icons.Outlined.LinkOff,
                active = state.useHistory,
                onClick = onToggleHistory,
            )
            ToolbarChip(
                label = if (state.keepScreenOn) "Screen on" else "Screen off",
                icon = Icons.Default.ScreenLockPortrait,
                active = state.keepScreenOn,
                onClick = onToggleKeepScreenOn,
            )
            ToolbarChip(
                label = "Notes${state.notes.count { it.enabled }.let { if (it > 0) " · $it" else "" }}",
                icon = Icons.AutoMirrored.Filled.Notes,
                active = state.panel == ChatPanel.Notes,
                onClick = {
                    onSetPanel(if (state.panel == ChatPanel.Notes) ChatPanel.None else ChatPanel.Notes)
                },
            )
            ToolbarChip(
                label = "Settings",
                icon = Icons.Default.Settings,
                active = false,
                onClick = onOpenSettings,
            )
            ToolbarChip(
                label = "New",
                icon = Icons.Default.Add,
                active = false,
                onClick = onNewChat,
            )
            if (!state.connected) {
                ToolbarChip(
                    label = "Reconnect",
                    icon = Icons.Default.Refresh,
                    active = false,
                    onClick = onRefresh,
                )
            }
        }
        if (!keyboardOpen) {
            val usage = state.usage
            val loginNeeded = usage?.loginNeeded == true ||
                (usage?.error?.contains("login", ignoreCase = true) == true) ||
                (usage?.label?.contains("re-login", ignoreCase = true) == true)
            val usageText = when {
                state.usageLoading && usage == null -> "Usage …"
                loginNeeded -> usage?.label?.takeIf { it.isNotBlank() } ?: "Usage: tap to re-login"
                usage != null && usage.label.isNotBlank() -> usage.label
                usage?.error != null -> "Usage unavailable"
                else -> null
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    buildString {
                        append(state.model.removePrefix("gb:").ifBlank { state.model })
                        if (state.reasoningEffort.isNotBlank()) {
                            append(" · ")
                            append(state.reasoningEffort)
                        }
                    },
                    color = GrokifyColors.TextDim,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (usageText != null) {
                    Text(
                        usageText,
                        color = when {
                            loginNeeded -> GrokifyColors.GlowRose
                            (usage?.usagePercent ?: 0.0) >= 90 -> GrokifyColors.GlowRose
                            (usage?.usagePercent ?: 0.0) >= 70 -> GrokifyColors.GlowViolet
                            else -> GrokifyColors.GlowCyan
                        }.copy(alpha = 0.9f),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clickable {
                                if (loginNeeded) onGrokLogin() else onRefreshUsage()
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolbarChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    onClick: () -> Unit,
) {
    val border = if (active) GrokifyColors.GlowCyan.copy(alpha = 0.55f) else GrokifyColors.PanelBorder
    val bg = if (active) GrokifyColors.GlowCyan.copy(alpha = 0.12f) else GrokifyColors.Panel
    val fg = if (active) GrokifyColors.GlowCyan else GrokifyColors.TextMuted
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ComposerBar(
    draft: String,
    busy: Boolean,
    connected: Boolean,
    enterForNewline: Boolean,
    onDraft: (String) -> Unit,
    onSend: (List<ChatImageAttachment>) -> Unit,
    onPrepareChatImage: suspend (android.net.Uri) -> ChatImageAttachment? = { null },
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var pending by remember { mutableStateOf<List<ChatImageAttachment>>(emptyList()) }
    var preparing by remember { mutableStateOf(false) }
    var prepareError by remember { mutableStateOf<String?>(null) }

    val pickImages = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(
            maxItems = 4,
        ),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        preparing = true
        prepareError = null
        scope.launch {
            val ready = mutableListOf<ChatImageAttachment>()
            // Keep existing attachments; fill up to 4
            ready += pending
            for (uri in uris) {
                if (ready.size >= 4) break
                val att = onPrepareChatImage(uri)
                if (att != null) ready += att
            }
            pending = ready.take(4)
            if (pending.isEmpty() && uris.isNotEmpty()) {
                prepareError = "Could not load image"
            }
            preparing = false
        }
    }

    val canSend = (draft.isNotBlank() || pending.isNotEmpty()) && !busy && !preparing
    val hint = when {
        !connected -> "Bridge offline — reconnect first"
        pending.isNotEmpty() -> "Add a note about the photo… (optional)"
        enterForNewline -> "Message Grok… (Enter = new line)"
        else -> "Message Grok… (Enter = send)"
    }

    fun doSend() {
        if (!canSend) return
        val imgs = pending
        pending = emptyList()
        prepareError = null
        onSend(imgs)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(GrokifyColors.VoidElevated.copy(alpha = 0.95f))
            .border(0.5.dp, GrokifyColors.PanelBorder),
    ) {
        if (pending.isNotEmpty() || preparing) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                pending.forEachIndexed { idx, att ->
                    Box(
                        Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(10.dp)),
                    ) {
                        AsyncImage(
                            model = att.displayUrl,
                            contentDescription = "Attachment ${idx + 1}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(2.dp)
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.65f))
                                .clickable {
                                    pending = pending.filterIndexed { i, _ -> i != idx }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove",
                                tint = Color.White,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                }
                if (preparing) {
                    CircularProgressIndicator(
                        Modifier.size(28.dp),
                        strokeWidth = 2.dp,
                        color = GrokifyColors.GlowCyan,
                    )
                }
            }
        }
        prepareError?.let { err ->
            Text(
                err,
                color = GrokifyColors.GlowRose,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Media attach — bottom-left of composer
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(GrokifyColors.PanelSoft)
                    .border(1.dp, GrokifyColors.PanelBorder, CircleShape)
                    .clickable(enabled = !busy && !preparing && pending.size < 4) {
                        try {
                            pickImages.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    androidx.activity.result.contract.ActivityResultContracts
                                        .PickVisualMedia.ImageOnly,
                                ),
                            )
                        } catch (e: Exception) {
                            prepareError = e.message ?: "Picker unavailable"
                            Toast.makeText(context, prepareError, Toast.LENGTH_SHORT).show()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Image,
                    contentDescription = "Add image",
                    tint = if (pending.isNotEmpty()) GrokifyColors.GlowCyan else GrokifyColors.TextMuted,
                    modifier = Modifier.size(22.dp),
                )
            }
            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(hint, color = GrokifyColors.TextDim)
                },
                minLines = 1,
                maxLines = 8,
                keyboardOptions = KeyboardOptions(
                    imeAction = if (enterForNewline) ImeAction.Default else ImeAction.Send,
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
                ),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (canSend && !enterForNewline) doSend()
                    },
                ),
                colors = fieldColors(),
                shape = RoundedCornerShape(16.dp),
            )
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (canSend) {
                            Brush.linearGradient(
                                listOf(GrokifyColors.GlowCyan, GrokifyColors.GlowMint)
                            )
                        } else {
                            Brush.linearGradient(
                                listOf(GrokifyColors.PanelSoft, GrokifyColors.PanelSoft)
                            )
                        }
                    )
                    .clickable(enabled = canSend, onClick = { doSend() }),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = GrokifyColors.GlowCyan,
                    )
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (canSend) Color(0xFF041016) else GrokifyColors.TextDim,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

// ─── Panels ───────────────────────────────────────────────────────────

@Composable
private fun PanelScaffold(
    title: String,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        // Scrim sits behind the sheet so row taps never hit dismiss
        Box(
            Modifier
                .fillMaxSize()
                .background(GrokifyColors.Scrim)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onClose,
                ),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.78f)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(GrokifyColors.VoidElevated)
                .border(
                    1.dp,
                    GrokifyColors.PanelBorder,
                    RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                )
                .navigationBarsPadding()
                .imePadding()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = GrokifyColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, "Close", tint = GrokifyColors.TextMuted)
                }
            }
            HorizontalDivider(color = GrokifyColors.PanelBorder)
            Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun HistoryPanel(
    state: UiState,
    onClose: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNew: () -> Unit,
) {
    PanelScaffold(title = "Session history", onClose = onClose) {
        Column(Modifier.fillMaxSize()) {
            Button(
                onClick = onNew,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GrokifyColors.GlowCyan,
                    contentColor = Color(0xFF041016),
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("New chat", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            if (state.loadingPanel && state.sessions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = GrokifyColors.GlowCyan)
                }
            } else if (state.sessions.isEmpty()) {
                Text("No sessions yet.", color = GrokifyColors.TextMuted)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.sessions, key = { it.id }) { s ->
                        val active = s.id.equals(state.sessionId, ignoreCase = true)
                        val countLabel = when {
                            s.messageCount <= 0 -> "Empty"
                            s.messageCount == 1 -> "1 msg"
                            else -> "${s.messageCount} msgs"
                        }
                        val tokenBits = buildList {
                            if (s.inputTokens > 0L) {
                                val mark = if (s.tokensEstimated) "~" else ""
                                add(mark + UsageFormat.compactTokens(s.inputTokens) + " in")
                            }
                            if (s.lastContextTokens > 0L) {
                                add(UsageFormat.compactTokens(s.lastContextTokens) + " ctx")
                            }
                            if (s.wallTimeS > 0L) {
                                add(UsageFormat.compactDuration(s.wallTimeS))
                            }
                        }.joinToString(" · ")
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (active) GrokifyColors.GlowCyan.copy(alpha = 0.1f)
                                    else GrokifyColors.Panel
                                )
                                .border(
                                    1.dp,
                                    if (active) GrokifyColors.GlowCyan.copy(alpha = 0.4f)
                                    else GrokifyColors.PanelBorder,
                                    RoundedCornerShape(12.dp),
                                )
                                .clickable(role = Role.Button) { onSelect(s.id) }
                                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    s.title,
                                    color = GrokifyColors.TextPrimary,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${s.updatedAt.take(16)} · $countLabel",
                                    color = if (s.messageCount <= 0) GrokifyColors.TextDim
                                    else GrokifyColors.GlowMint.copy(alpha = 0.85f),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                                if (tokenBits.isNotEmpty()) {
                                    Text(
                                        tokenBits,
                                        color = GrokifyColors.GlowCyan.copy(alpha = 0.85f),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            IconButton(onClick = { onDelete(s.id) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    "Delete",
                                    tint = GrokifyColors.GlowRose,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotesPanel(
    state: UiState,
    onClose: () -> Unit,
    onAdd: (String) -> Unit,
    onToggle: (Int, Boolean) -> Unit,
    onDelete: (Int) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    PanelScaffold(title = "System notes", onClose = onClose) {
        Column(Modifier.fillMaxSize()) {
            Text(
                "Active notes are injected into every prompt (shared with admin System Chat).",
                color = GrokifyColors.TextMuted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Add note…", color = GrokifyColors.TextDim) },
                    singleLine = true,
                    colors = fieldColors(),
                    shape = RoundedCornerShape(10.dp),
                )
                Button(
                    onClick = {
                        onAdd(draft)
                        draft = ""
                    },
                    enabled = draft.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GrokifyColors.GlowCyan,
                        contentColor = Color(0xFF041016),
                    ),
                ) { Text("Add") }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.notes, key = { it.id }) { note ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(GrokifyColors.Panel)
                            .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Switch(
                            checked = note.enabled,
                            onCheckedChange = { onToggle(note.id, it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = GrokifyColors.GlowCyan,
                                checkedTrackColor = GrokifyColors.GlowCyan.copy(alpha = 0.35f),
                            ),
                        )
                        Text(
                            note.text,
                            color = if (note.enabled) GrokifyColors.TextPrimary else GrokifyColors.TextDim,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                        IconButton(onClick = { onDelete(note.id) }) {
                            Icon(Icons.Default.Delete, null, tint = GrokifyColors.GlowRose, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPage(
    state: UiState,
    onBack: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectReasoningEffort: (String) -> Unit = {},
    onSetWorkDir: (String) -> Unit = {},
    onResetWorkDir: () -> Unit = {},
    onToggleWorkDirBrowser: () -> Unit = {},
    onBrowseWorkDir: (String) -> Unit = {},
    onUseBrowsedWorkDir: () -> Unit = {},
    onToggleHistory: () -> Unit,
    onToggleKeepScreenOn: () -> Unit,
    onToggleEnterForNewline: () -> Unit,
    onToggleShareNotifications: () -> Unit = {},
    onToggleShowTools: () -> Unit = {},
    onToggleShowThoughts: () -> Unit = {},
    onOpenNotificationAccess: () -> Unit = {},
    onRefreshNotificationAccess: () -> Unit = {},
    onTogglePermission: (String) -> Unit = {},
    onRefreshPermissions: () -> Unit = {},
    onOpenAppPermissionSettings: () -> Unit = {},
    onRefreshUsage: () -> Unit = {},
    onGrokLogin: () -> Unit = {},
    onGrokLogout: () -> Unit = {},
    onSaveMapboxAccessToken: (String) -> Unit = {},
    onClearMapboxAccessToken: () -> Unit = {},
    onSaveApiKey: (id: String, value: String, label: String?, description: String?) -> Unit = { _, _, _, _ -> },
    onClearApiKey: (String) -> Unit = {},
) {
    var workDirDraft by remember(state.workDir) {
        mutableStateOf(state.workDir)
    }
    var mapboxDraft by remember(state.mapboxAccessToken) {
        mutableStateOf(state.mapboxAccessToken)
    }
    var mapboxVisible by remember { mutableStateOf(false) }
    var mapboxSavedFlash by remember { mutableStateOf(false) }
    var addKeyOpen by remember { mutableStateOf(false) }
    var addKeyPresetId by remember { mutableStateOf("") }
    var addKeyCustomId by remember { mutableStateOf("") }
    var addKeyLabel by remember { mutableStateOf("") }
    var addKeyDesc by remember { mutableStateOf("") }
    var addKeyValue by remember { mutableStateOf("") }
    var addKeyVisible by remember { mutableStateOf(false) }
    var keySavedFlash by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        onRefreshNotificationAccess()
        onRefreshPermissions()
    }
    LaunchedEffect(mapboxSavedFlash) {
        if (mapboxSavedFlash) {
            delay(1600)
            mapboxSavedFlash = false
        }
    }
    LaunchedEffect(keySavedFlash) {
        if (keySavedFlash) {
            delay(1600)
            keySavedFlash = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(GrokifyColors.Void)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = GrokifyColors.GlowCyan,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "Settings",
                    color = GrokifyColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                )
                Text(
                    "Chat, permissions, models, and API keys",
                    color = GrokifyColors.TextDim,
                    fontSize = 12.sp,
                )
            }
        }

        UsageCard(
            state = state,
            onRefresh = onRefreshUsage,
            onGrokLogin = onGrokLogin,
            onGrokLogout = onGrokLogout,
        )

        Text("CHAT", style = MaterialTheme.typography.labelSmall, color = GrokifyColors.GlowCyan)
        SettingRow(
            title = "Send history / context",
            subtitle = "Include prior session messages with each prompt",
            checked = state.useHistory,
            onToggle = onToggleHistory,
        )
        SettingRow(
            title = "Keep screen on",
            subtitle = "Prevent sleep while Grokify is open",
            checked = state.keepScreenOn,
            onToggle = onToggleKeepScreenOn,
        )
        SettingRow(
            title = "Enter for newline",
            subtitle = if (state.enterForNewline) {
                "Enter inserts a new line; send with the send button"
            } else {
                "Enter sends the message"
            },
            checked = state.enterForNewline,
            onToggle = onToggleEnterForNewline,
        )
        SettingRow(
            title = "Show tools",
            subtitle = if (state.showTools) {
                "Tool call cards appear in the chat transcript"
            } else {
                "Tool call cards are hidden (still run normally)"
            },
            checked = state.showTools,
            onToggle = onToggleShowTools,
        )
        SettingRow(
            title = "Show thoughts",
            subtitle = if (state.showThoughts) {
                "Thinking / thought cards appear in the chat transcript"
            } else {
                "Thinking cards are hidden (model still thinks)"
            },
            checked = state.showThoughts,
            onToggle = onToggleShowThoughts,
        )

        Text(
            "WORKSPACE",
            style = MaterialTheme.typography.labelSmall,
            color = GrokifyColors.GlowCyan,
        )
        Text(
            if (state.workDirIsDefault) {
                "Default — GrokifyOS install workspace. Agents run with this folder as cwd."
            } else {
                "Custom project directory on the bridge server. Agents run with this as cwd."
            },
            color = GrokifyColors.TextMuted,
            fontSize = 12.sp,
        )
        Text(
            state.workDir.ifBlank { "…" },
            color = GrokifyColors.GlowCyan,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(GrokifyColors.Panel)
                .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(10.dp))
                .padding(12.dp),
        )
        OutlinedTextField(
            value = workDirDraft,
            onValueChange = { workDirDraft = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Absolute path on server") },
            placeholder = { Text("/root/my-project") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = GrokifyColors.TextPrimary,
                unfocusedTextColor = GrokifyColors.TextPrimary,
                focusedBorderColor = GrokifyColors.GlowCyan,
                unfocusedBorderColor = GrokifyColors.PanelBorder,
                focusedLabelColor = GrokifyColors.GlowCyan,
                unfocusedLabelColor = GrokifyColors.TextDim,
                cursorColor = GrokifyColors.GlowCyan,
            ),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = { onSetWorkDir(workDirDraft.trim()) },
                enabled = !state.workDirLoading && workDirDraft.isNotBlank(),
                border = BorderStroke(1.dp, GrokifyColors.GlowCyan.copy(alpha = 0.5f)),
            ) {
                Text("Set", color = GrokifyColors.GlowCyan)
            }
            OutlinedButton(
                onClick = onToggleWorkDirBrowser,
                enabled = !state.workDirLoading,
                border = BorderStroke(1.dp, GrokifyColors.PanelBorder),
            ) {
                Text(
                    if (state.workDirBrowserOpen) "Hide" else "Browse",
                    color = GrokifyColors.TextPrimary,
                )
            }
            OutlinedButton(
                onClick = onResetWorkDir,
                enabled = !state.workDirLoading && !state.workDirIsDefault,
                border = BorderStroke(1.dp, GrokifyColors.PanelBorder),
            ) {
                Text("Default", color = GrokifyColors.TextPrimary)
            }
        }
        if (state.workDirBrowserOpen) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(GrokifyColors.Panel)
                    .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            state.workDirBrowseParent?.let { onBrowseWorkDir(it) }
                        },
                        enabled = !state.workDirBrowseParent.isNullOrBlank() && !state.workDirLoading,
                        border = BorderStroke(1.dp, GrokifyColors.PanelBorder),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text("↑ Up", color = GrokifyColors.TextPrimary, fontSize = 12.sp)
                    }
                    Text(
                        state.workDirBrowsePath,
                        color = GrokifyColors.TextDim,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                    )
                    OutlinedButton(
                        onClick = onUseBrowsedWorkDir,
                        enabled = state.workDirBrowsePath.isNotBlank() && !state.workDirLoading,
                        border = BorderStroke(1.dp, GrokifyColors.GlowMint.copy(alpha = 0.5f)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text("Use", color = GrokifyColors.GlowMint, fontSize = 12.sp)
                    }
                }
                if (state.workDirLoading && state.workDirEntries.isEmpty()) {
                    CircularProgressIndicator(
                        color = GrokifyColors.GlowCyan,
                        modifier = Modifier.size(22.dp),
                    )
                } else if (state.workDirEntries.isEmpty()) {
                    Text("No subfolders", color = GrokifyColors.TextMuted, fontSize = 12.sp)
                } else {
                    state.workDirEntries.forEach { entry ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onBrowseWorkDir(entry.path) }
                                .padding(vertical = 8.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "📁 ${entry.name}",
                                color = GrokifyColors.TextPrimary,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }
        }
        if (state.workDirStatus.isNotBlank()) {
            Text(
                state.workDirStatus,
                color = if (
                    state.workDirStatus.contains("Saved", ignoreCase = true) ||
                    state.workDirStatus.contains("Reset", ignoreCase = true)
                ) {
                    GrokifyColors.GlowMint
                } else if (
                    state.workDirStatus.contains("…") ||
                    state.workDirStatus.contains("Saving", ignoreCase = true) ||
                    state.workDirStatus.contains("Resetting", ignoreCase = true)
                ) {
                    GrokifyColors.TextDim
                } else {
                    GrokifyColors.GlowAmber
                },
                fontSize = 12.sp,
            )
        }

        Text(
            "API KEYS",
            style = MaterialTheme.typography.labelSmall,
            color = GrokifyColors.GlowCyan,
        )
        Text(
            "Keys stay on this device. Built-in apps read only the keys they need. " +
                "Empty fields mean “not set” — save a value to enable that integration.",
            color = GrokifyColors.TextMuted,
            fontSize = 12.sp,
        )

        // —— SpaceXAI (inference + management are different product keys) ——
        Text(
            "SPACEXAI · VOICE + USAGE",
            style = MaterialTheme.typography.labelSmall,
            color = GrokifyColors.GlowViolet,
        )
        Text(
            "Two different key types from console.x.ai — keep both if you use Voice and Usage Analyzer.\n" +
                "• API key (inference) → Grok Voice TTS (Spotify Live DJ)\n" +
                "• Management key (billing) → Apps → SpaceXAI Usage Analyzer\n" +
                "Playlist research uses host Grok Build (device token) — not these keys.",
            color = GrokifyColors.TextMuted,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
        SettingsVaultKeyCard(
            entry = state.apiKeys.firstOrNull { it.id == "spacexai_api_key" },
            fallbackId = "spacexai_api_key",
            fallbackLabel = "SpaceXAI API key",
            emptySubtitle = "Not set · inference key for Voice TTS",
            savedSubtitle = { "Saved · ends $it · api.x.ai Voice TTS" },
            defaultHint = "id spacexai_api_key · console.x.ai → API Keys",
            onSaveApiKey = onSaveApiKey,
            onClearApiKey = onClearApiKey,
            onSavedFlash = { keySavedFlash = true },
        )
        SettingsVaultKeyCard(
            entry = state.apiKeys.firstOrNull { it.id == "spacexai_management_key" },
            fallbackId = "spacexai_management_key",
            fallbackLabel = "SpaceXAI Management key",
            emptySubtitle = "Not set · billing key for Usage Analyzer",
            savedSubtitle = { "Saved · ends $it · management-api.x.ai" },
            defaultHint = "id spacexai_management_key · console.x.ai → Management Keys",
            onSaveApiKey = onSaveApiKey,
            onClearApiKey = onClearApiKey,
            onSavedFlash = { keySavedFlash = true },
        )

        Text(
            "SPOTIFY · MAPS · OTHER",
            style = MaterialTheme.typography.labelSmall,
            color = GrokifyColors.GlowCyan,
        )
        SettingsSpotifyOAuthCard()
        ApiKeyCard(
            title = "Mapbox",
            subtitle = "Optional pk.… token for Mapbox dark tiles (maps work without it)",
            value = mapboxDraft,
            visible = mapboxVisible,
            persistedValue = state.mapboxAccessToken,
            defaultHint = "Optional — free dark map if empty",
            savedFlash = mapboxSavedFlash,
            onValueChange = { mapboxDraft = it },
            onToggleVisible = { mapboxVisible = !mapboxVisible },
            onSave = {
                onSaveMapboxAccessToken(mapboxDraft)
                mapboxSavedFlash = true
            },
            onClear = {
                mapboxDraft = ""
                onClearMapboxAccessToken()
                mapboxSavedFlash = true
            },
            clearLabel = "Remove",
        )

        Text(
            "Keys are stored on this device for built-in apps (Spotify, maps, voice). " +
                "Only the apps that need a key will use it.",
            color = GrokifyColors.TextMuted,
            fontSize = 12.sp,
        )
        state.apiKeys
            .filter {
                it.id != "mapbox_access_token" &&
                    it.id != "spacexai_api_key" &&
                    it.id != "spacexai_management_key"
            }
            .forEach { entry ->
                var draft by remember(entry.id, entry.value) { mutableStateOf(entry.value) }
                var visible by remember(entry.id) { mutableStateOf(false) }
                var flash by remember(entry.id) { mutableStateOf(false) }
                LaunchedEffect(flash) {
                    if (flash) {
                        delay(1400)
                        flash = false
                    }
                }
                LaunchedEffect(entry.value) { draft = entry.value }
                ApiKeyCard(
                    title = entry.label,
                    subtitle = buildString {
                        append(entry.description.ifBlank { entry.id })
                        if (entry.value.isBlank()) append(" · not set")
                        else append(" · saved …${entry.maskedTail()}")
                    },
                    value = draft,
                    visible = visible,
                    persistedValue = entry.value,
                    defaultHint = "Stored for plugins · id ${entry.id}",
                    savedFlash = flash,
                    onValueChange = { draft = it },
                    onToggleVisible = { visible = !visible },
                    onSave = {
                        onSaveApiKey(entry.id, draft, entry.label, entry.description)
                        flash = true
                        keySavedFlash = true
                    },
                    onClear = {
                        draft = ""
                        onClearApiKey(entry.id)
                        flash = true
                    },
                    clearLabel = "Remove",
                )
            }

        Button(
            onClick = {
                addKeyPresetId = ""
                addKeyCustomId = ""
                addKeyLabel = ""
                addKeyDesc = ""
                addKeyValue = ""
                addKeyVisible = false
                addKeyOpen = true
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = GrokifyColors.GlowViolet.copy(alpha = 0.2f),
                contentColor = GrokifyColors.GlowViolet,
            ),
            border = BorderStroke(1.dp, GrokifyColors.GlowViolet.copy(alpha = 0.45f)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add API key", fontWeight = FontWeight.SemiBold)
        }
        if (keySavedFlash) {
            Text("Key saved on device", color = GrokifyColors.GlowMint, fontSize = 12.sp)
        }

        if (addKeyOpen) {
            AddApiKeyDialog(
                presetId = addKeyPresetId,
                customId = addKeyCustomId,
                label = addKeyLabel,
                description = addKeyDesc,
                value = addKeyValue,
                visible = addKeyVisible,
                existingIds = state.apiKeys.map { it.id }.toSet(),
                onPresetChange = { id ->
                    addKeyPresetId = id
                    if (id.isNotBlank() && id != "custom") {
                        val p = io.grokify.os.data.ApiKeyPresets.byId(id)
                        addKeyCustomId = id
                        addKeyLabel = p?.label.orEmpty()
                        addKeyDesc = p?.description.orEmpty()
                    } else if (id == "custom") {
                        addKeyCustomId = ""
                        addKeyLabel = ""
                        addKeyDesc = ""
                    }
                },
                onCustomIdChange = { addKeyCustomId = it },
                onLabelChange = { addKeyLabel = it },
                onDescChange = { addKeyDesc = it },
                onValueChange = { addKeyValue = it },
                onToggleVisible = { addKeyVisible = !addKeyVisible },
                onDismiss = { addKeyOpen = false },
                onSave = {
                    val id = when {
                        addKeyPresetId.isNotBlank() && addKeyPresetId != "custom" -> addKeyPresetId
                        else -> addKeyCustomId.trim()
                    }
                    if (id.isNotBlank() && addKeyValue.isNotBlank()) {
                        onSaveApiKey(
                            id,
                            addKeyValue,
                            addKeyLabel.ifBlank { null },
                            addKeyDesc.ifBlank { null },
                        )
                        keySavedFlash = true
                        addKeyOpen = false
                    }
                },
            )
        }

        Text(
            "PERMISSIONS",
            style = MaterialTheme.typography.labelSmall,
            color = GrokifyColors.GlowCyan,
        )
        Text(
            "Grant only what you need. Turning a switch on asks Android for access; " +
                "turning off opens system settings to revoke.",
            color = GrokifyColors.TextMuted,
            fontSize = 12.sp,
        )
        val requestable = state.permissions.filter { it.requestable }
        if (requestable.isEmpty()) {
            Text(
                "No runtime permissions on this Android version.",
                color = GrokifyColors.TextDim,
                fontSize = 12.sp,
            )
        } else {
            requestable.forEach { perm ->
                SettingRow(
                    title = perm.id.title,
                    subtitle = if (perm.granted) {
                        "Allowed · ${perm.id.description}"
                    } else {
                        "Not granted · ${perm.id.description}"
                    },
                    checked = perm.granted,
                    onToggle = { onTogglePermission(perm.id.id) },
                )
            }
        }
        TextButton(
            onClick = onOpenAppPermissionSettings,
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
        ) {
            Text(
                "Open system app permissions",
                color = GrokifyColors.TextMuted,
                fontSize = 12.sp,
            )
        }

        Text(
            "NOTIFICATIONS",
            style = MaterialTheme.typography.labelSmall,
            color = GrokifyColors.GlowCyan,
        )
        SettingRow(
            title = "Share with Grok",
            subtitle = if (state.shareNotifications) {
                "Grok receives active notification summaries with each message"
            } else {
                "Phone notifications stay local and are not sent to Grok"
            },
            checked = state.shareNotifications,
            onToggle = {
                val enabling = !state.shareNotifications
                onToggleShareNotifications()
                // System permission — open grant screen immediately when turning on.
                if (enabling && !state.notificationAccessGranted) {
                    onOpenNotificationAccess()
                }
            },
        )
        NotificationAccessCard(
            granted = state.notificationAccessGranted,
            count = state.notificationCount,
            onOpenSettings = onOpenNotificationAccess,
            onRefresh = onRefreshNotificationAccess,
            listenerBound = state.notificationListenerBound,
        )

        Text("MODEL", style = MaterialTheme.typography.labelSmall, color = GrokifyColors.GlowCyan)
        if (state.loadingPanel && state.models.isEmpty()) {
            CircularProgressIndicator(color = GrokifyColors.GlowCyan, modifier = Modifier.size(24.dp))
        } else if (state.models.isEmpty()) {
            Text("No models loaded — reconnect.", color = GrokifyColors.TextMuted, fontSize = 13.sp)
        } else {
            state.models.forEach { m ->
                val selected = m.id == state.model
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (selected) GrokifyColors.GlowCyan.copy(alpha = 0.1f)
                            else GrokifyColors.Panel
                        )
                        .border(
                            1.dp,
                            if (selected) GrokifyColors.GlowCyan.copy(alpha = 0.45f)
                            else GrokifyColors.PanelBorder,
                            RoundedCornerShape(10.dp),
                        )
                        .clickable { onSelectModel(m.id) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name, color = GrokifyColors.TextPrimary, fontWeight = FontWeight.Medium)
                        Text(
                            m.id.removePrefix("gb:"),
                            color = GrokifyColors.TextDim,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    if (selected) {
                        Icon(
                            Icons.Default.CheckCircle,
                            null,
                            tint = GrokifyColors.GlowMint,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            val efforts = state.reasoningEfforts.ifEmpty {
                state.models.find { it.id == state.model }?.reasoningEfforts.orEmpty()
            }
            if (efforts.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "REASONING",
                    style = MaterialTheme.typography.labelSmall,
                    color = GrokifyColors.GlowCyan,
                )
                Text(
                    if ("xhigh" in efforts) {
                        "How hard this model thinks. xhigh is grok-4.6+ only."
                    } else {
                        "How hard this model thinks. This model does not support xhigh."
                    },
                    color = GrokifyColors.TextMuted,
                    fontSize = 12.sp,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    efforts.forEach { effort ->
                        val on = effort == state.reasoningEffort
                        FilterChip(
                            selected = on,
                            onClick = { onSelectReasoningEffort(effort) },
                            label = {
                                Text(
                                    effort,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = GrokifyColors.GlowCyan.copy(alpha = 0.22f),
                                selectedLabelColor = GrokifyColors.GlowCyan,
                                containerColor = GrokifyColors.PanelSoft,
                                labelColor = GrokifyColors.TextPrimary,
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = on,
                                borderColor = GrokifyColors.PanelBorder,
                                selectedBorderColor = GrokifyColors.GlowCyan,
                            ),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SecretStatusChip(
    saved: Boolean,
    dirty: Boolean = false,
    emptyLabel: String = "Empty",
    savedLabel: String = "Saved",
) {
    val label = when {
        dirty && saved -> "Unsaved"
        dirty && !saved -> "Unsaved"
        saved -> savedLabel
        else -> emptyLabel
    }
    val color = when {
        dirty -> GrokifyColors.GlowAmber
        saved -> GrokifyColors.GlowMint
        else -> GrokifyColors.TextDim
    }
    Text(
        label,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/**
 * Spotify OAuth lives here so users can re-authorize for new scopes
 * (e.g. Liked Songs) without hunting the Spotify app Account tab.
 */
@Composable
private fun SettingsSpotifyOAuthCard() {
    val context = LocalContext.current
    val appCtx = context.applicationContext
    var loggedIn by remember {
        mutableStateOf(io.grokify.os.apps.plugin.SpotifyOAuth.isLoggedIn(appCtx))
    }
    var authMsg by remember {
        mutableStateOf(io.grokify.os.apps.plugin.SpotifyOAuth.lastAuthMessage.orEmpty())
    }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            loggedIn = io.grokify.os.apps.plugin.SpotifyOAuth.isLoggedIn(appCtx)
            authMsg = io.grokify.os.apps.plugin.SpotifyOAuth.lastAuthMessage.orEmpty()
            delay(1_200L)
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(GrokifyColors.Panel)
            .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Spotify account",
            color = GrokifyColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
        Text(
            if (loggedIn) {
                "Connected — re-authorize anytime when features need new permissions " +
                    "(Liked Songs, library, etc.). Does not require logout."
            } else {
                "Not connected — save Spotify Client ID below, then Connect. " +
                    "You can also do this in Apps → Spotify → Account."
            },
            color = GrokifyColors.TextMuted,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
        if (authMsg.isNotBlank()) {
            Text(authMsg, color = GrokifyColors.TextDim, fontSize = 11.sp)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    val raw = if (loggedIn) {
                        io.grokify.os.apps.plugin.SpotifyOAuth.reauthorize(appCtx)
                    } else {
                        io.grokify.os.apps.plugin.SpotifyOAuth.startLogin(appCtx)
                    }
                    authMsg = runCatching {
                        org.json.JSONObject(raw).optString("error")
                            .ifBlank {
                                io.grokify.os.apps.plugin.SpotifyOAuth.lastAuthMessage.orEmpty()
                            }
                    }.getOrElse {
                        io.grokify.os.apps.plugin.SpotifyOAuth.lastAuthMessage.orEmpty()
                    }
                    if (authMsg.isBlank()) {
                        authMsg = io.grokify.os.apps.plugin.SpotifyOAuth.lastAuthMessage
                            ?: "Browser opened for Spotify"
                    }
                    busy = false
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = GrokifyColors.GlowMint.copy(alpha = 0.22f),
                    contentColor = GrokifyColors.GlowMint,
                    disabledContainerColor = GrokifyColors.PanelSoft,
                    disabledContentColor = GrokifyColors.TextDim,
                ),
                border = BorderStroke(1.dp, GrokifyColors.GlowMint.copy(alpha = 0.45f)),
            ) {
                Text(
                    if (loggedIn) "Re-authorize" else "Connect Spotify",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
            }
            if (loggedIn) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        io.grokify.os.apps.plugin.SpotifyOAuth.logout(appCtx)
                        loggedIn = false
                        authMsg = "Logged out of Spotify"
                    },
                ) {
                    Text("Logout", color = GrokifyColors.GlowAmber, fontSize = 13.sp)
                }
            }
        }
    }
}

/** Dedicated Settings row for a vault key (with empty fallback when not yet seeded). */
@Composable
private fun SettingsVaultKeyCard(
    entry: io.grokify.os.data.ApiKeyEntry?,
    fallbackId: String,
    fallbackLabel: String,
    emptySubtitle: String,
    savedSubtitle: (maskedTail: String) -> String,
    defaultHint: String,
    onSaveApiKey: (id: String, value: String, label: String, description: String?) -> Unit,
    onClearApiKey: (id: String) -> Unit,
    onSavedFlash: () -> Unit,
) {
    val id = entry?.id ?: fallbackId
    val label = entry?.label?.ifBlank { fallbackLabel } ?: fallbackLabel
    val description = entry?.description
    val persisted = entry?.value.orEmpty()
    var draft by remember(id, persisted) { mutableStateOf(persisted) }
    var visible by remember(id) { mutableStateOf(false) }
    var flash by remember(id) { mutableStateOf(false) }
    LaunchedEffect(flash) {
        if (flash) {
            delay(1400)
            flash = false
        }
    }
    LaunchedEffect(persisted) { draft = persisted }
    ApiKeyCard(
        title = label,
        subtitle = if (persisted.isBlank()) {
            emptySubtitle
        } else {
            savedSubtitle(entry?.maskedTail().orEmpty())
        },
        value = draft,
        visible = visible,
        persistedValue = persisted,
        defaultHint = defaultHint,
        savedFlash = flash,
        onValueChange = { draft = it },
        onToggleVisible = { visible = !visible },
        onSave = {
            onSaveApiKey(id, draft, label, description)
            flash = true
            onSavedFlash()
        },
        onClear = {
            draft = ""
            onClearApiKey(id)
            flash = true
        },
        clearLabel = "Remove",
    )
}

@Composable
private fun ApiKeyCard(
    title: String,
    subtitle: String,
    value: String,
    visible: Boolean,
    /** What is actually stored on device (not the draft field). */
    persistedValue: String,
    defaultHint: String,
    savedFlash: Boolean,
    onValueChange: (String) -> Unit,
    onToggleVisible: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    clearLabel: String = "Use default",
) {
    val clipboard = LocalClipboardManager.current
    var copiedFlash by remember { mutableStateOf(false) }
    LaunchedEffect(copiedFlash) {
        if (copiedFlash) {
            delay(1400)
            copiedFlash = false
        }
    }

    val persistedSaved = persistedValue.isNotBlank()
    val dirty = value.trim() != persistedValue.trim()
    val statusLine = when {
        persistedSaved && !dirty ->
            "Saved on device · ends …${persistedValue.takeLast(6)}"
        persistedSaved && dirty ->
            "Saved on device · field has unsaved edits"
        !persistedSaved && value.isBlank() ->
            "Empty · $defaultHint"
        else ->
            "Empty on device · draft not saved yet"
    }
    val statusColor = when {
        persistedSaved && !dirty -> GrokifyColors.GlowMint
        dirty -> GrokifyColors.GlowAmber
        else -> GrokifyColors.TextDim
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(GrokifyColors.Panel)
            .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Key,
                contentDescription = null,
                tint = GrokifyColors.GlowViolet,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = GrokifyColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(subtitle, color = GrokifyColors.TextMuted, fontSize = 12.sp)
            }
            SecretStatusChip(saved = persistedSaved, dirty = dirty)
        }
        Text(
            statusLine,
            color = statusColor,
            fontSize = 11.sp,
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text("pk.eyJ…", color = GrokifyColors.TextDim, fontFamily = FontFamily.Monospace)
            },
            singleLine = true,
            visualTransformation = if (visible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                Row {
                    if (visible && value.isNotBlank()) {
                        IconButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(value))
                                copiedFlash = true
                            },
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Copy key",
                                tint = if (copiedFlash) GrokifyColors.GlowMint else GrokifyColors.TextMuted,
                            )
                        }
                    }
                    IconButton(onClick = onToggleVisible) {
                        Icon(
                            if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (visible) "Hide key" else "Show key",
                            tint = GrokifyColors.TextMuted,
                        )
                    }
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
            colors = fieldColors(),
            shape = RoundedCornerShape(10.dp),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            ),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onSave,
                colors = ButtonDefaults.buttonColors(
                    containerColor = GrokifyColors.GlowCyan,
                    contentColor = Color(0xFF041016),
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    when {
                        savedFlash -> "Saved"
                        dirty -> "Save"
                        persistedSaved -> "Saved"
                        else -> "Save"
                    },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                )
            }
            if (visible && value.isNotBlank()) {
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(value))
                        copiedFlash = true
                    },
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = null,
                        tint = if (copiedFlash) GrokifyColors.GlowMint else GrokifyColors.TextMuted,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (copiedFlash) "Copied" else "Copy",
                        color = if (copiedFlash) GrokifyColors.GlowMint else GrokifyColors.TextMuted,
                        fontSize = 12.sp,
                    )
                }
            }
            if (persistedSaved || value.isNotBlank()) {
                TextButton(onClick = onClear) {
                    Text(clearLabel, color = GrokifyColors.TextMuted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun AddApiKeyDialog(
    presetId: String,
    customId: String,
    label: String,
    description: String,
    value: String,
    visible: Boolean,
    existingIds: Set<String>,
    onPresetChange: (String) -> Unit,
    onCustomIdChange: (String) -> Unit,
    onLabelChange: (String) -> Unit,
    onDescChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onToggleVisible: () -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    val presets = remember { io.grokify.os.data.ApiKeyPresets.all }
    val resolvedId = when {
        presetId.isNotBlank() && presetId != "custom" -> presetId
        else -> customId.trim()
    }
    val canSave = resolvedId.isNotBlank() && value.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GrokifyColors.Panel,
        titleContentColor = GrokifyColors.TextPrimary,
        textContentColor = GrokifyColors.TextMuted,
        title = { Text("Add API key", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Stored on this device. Built-in apps request keys by id.",
                    fontSize = 12.sp,
                    color = GrokifyColors.TextMuted,
                )
                Text("PRESET", fontSize = 11.sp, color = GrokifyColors.GlowCyan, fontWeight = FontWeight.SemiBold)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    presets.forEach { p ->
                        val selected = presetId == p.id
                        Text(
                            p.label,
                            color = if (selected) Color(0xFF041016) else GrokifyColors.TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(
                                    if (selected) GrokifyColors.GlowCyan
                                    else GrokifyColors.Void,
                                )
                                .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(999.dp))
                                .clickable { onPresetChange(p.id) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                    val customSel = presetId == "custom" || presetId.isBlank()
                    Text(
                        "Custom",
                        color = if (customSel && presetId == "custom") Color(0xFF041016) else GrokifyColors.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                if (presetId == "custom") GrokifyColors.GlowCyan
                                else GrokifyColors.Void,
                            )
                            .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(999.dp))
                            .clickable { onPresetChange("custom") }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
                if (presetId == "custom" || presetId.isBlank()) {
                    OutlinedTextField(
                        value = customId,
                        onValueChange = onCustomIdChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Key id") },
                        placeholder = { Text("my_service_key") },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                        ),
                    )
                    OutlinedTextField(
                        value = label,
                        onValueChange = onLabelChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Label") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = onDescChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Description (optional)") },
                        singleLine = true,
                    )
                } else {
                    Text(
                        "id: $resolvedId",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = GrokifyColors.TextDim,
                    )
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Secret value") },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = onToggleVisible) {
                            Icon(
                                if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                                tint = GrokifyColors.TextMuted,
                            )
                        }
                    },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    ),
                )
                if (resolvedId in existingIds) {
                    Text(
                        "This key already exists — saving will replace it.",
                        color = GrokifyColors.GlowAmber,
                        fontSize = 11.sp,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                enabled = canSave,
                colors = ButtonDefaults.buttonColors(
                    containerColor = GrokifyColors.GlowCyan,
                    contentColor = Color(0xFF041016),
                ),
            ) {
                Text("Save", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = GrokifyColors.TextMuted)
            }
        },
    )
}

private fun formatUsagePct(percent: Double): String =
    if (percent == percent.toLong().toDouble()) {
        "${percent.toLong()}%"
    } else {
        String.format("%.1f%%", percent)
    }

private fun usageProductLabel(raw: String): String = when (raw) {
    "GrokBuild" -> "Build"
    "GrokChat" -> "Chat"
    "GrokImagine" -> "Imagine"
    else -> raw.ifBlank { "Other" }
}

private fun usageAccent(percent: Double): Color = when {
    percent >= 90 -> GrokifyColors.GlowRose
    percent >= 70 -> GrokifyColors.GlowViolet
    else -> GrokifyColors.GlowCyan
}

private fun formatResetWhen(resetAt: String): String {
    if (resetAt.isBlank()) return "Reset time unknown"
    return try {
        val cleaned = resetAt
            .replace("Z", "+00:00")
            .let { if (it.contains('.')) it.replace(Regex("\\.\\d+"), "") else it }
        val instant = java.time.OffsetDateTime.parse(cleaned).toInstant()
        val now = java.time.Instant.now()
        val secs = java.time.Duration.between(now, instant).seconds
        val relative = when {
            secs <= 0 -> "soon"
            secs < 3600 -> "in ${secs / 60}m"
            secs < 86400 -> "in ${secs / 3600}h"
            else -> {
                val days = secs / 86400
                val hours = (secs % 86400) / 3600
                if (hours > 0) "in ${days}d ${hours}h" else "in ${days}d"
            }
        }
        val zoned = instant.atZone(java.time.ZoneId.systemDefault())
        val clock = zoned.format(
            java.time.format.DateTimeFormatter.ofPattern("EEE h:mm a", java.util.Locale.getDefault()),
        )
        "Resets $relative · $clock"
    } catch (_: Exception) {
        "Resets $resetAt"
    }
}

@Composable
private fun UsageCard(
    state: UiState,
    onRefresh: () -> Unit,
    onGrokLogin: () -> Unit = {},
    onGrokLogout: () -> Unit = {},
) {
    val usage = state.usage
    val pct = usage?.usagePercent ?: 0.0
    val remaining = usage?.remainingPercent?.takeIf { it > 0 } ?: (100.0 - pct).coerceAtLeast(0.0)
    val accent = usageAccent(pct)
    val loginNeeded = usage?.loginNeeded == true ||
        !usage?.loginUrl.isNullOrBlank() ||
        (usage?.error?.contains("login", ignoreCase = true) == true) ||
        (usage?.error?.contains("auth", ignoreCase = true) == true) ||
        (usage?.error?.contains("signed out", ignoreCase = true) == true)
    val hardError = usage?.error != null &&
        (usage.label.isBlank() || (usage.usagePercent <= 0.0 && usage.products.isEmpty()))

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(GrokifyColors.Panel)
            .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "WEEKLY USAGE",
                    style = MaterialTheme.typography.labelSmall,
                    color = GrokifyColors.GlowCyan,
                    letterSpacing = 0.8.sp,
                )
                val tier = usage?.subscriptionTier?.trim().orEmpty()
                if (tier.isNotEmpty() && !hardError) {
                    Text(
                        tier,
                        color = GrokifyColors.TextDim,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.size(36.dp),
                enabled = !state.usageLoading,
            ) {
                if (state.usageLoading) {
                    CircularProgressIndicator(
                        Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = GrokifyColors.GlowCyan,
                    )
                } else {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Refresh usage",
                        tint = GrokifyColors.GlowCyan,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        when {
            hardError || loginNeeded -> {
                Text(
                    when {
                        usage?.loginStatus == "pending" ->
                            "Waiting for xAI approval…"
                        usage?.loginMessage?.contains("signed out", ignoreCase = true) == true ->
                            "Signed out — re-login to continue"
                        loginNeeded ->
                            "Grok Build session expired"
                        else ->
                            usage?.error ?: "Usage unavailable"
                    },
                    color = GrokifyColors.GlowRose,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                val detail = usage?.loginMessage
                    ?: usage?.error?.takeIf { loginNeeded }
                    ?: "Open the Grok/xAI login page, sign in, and approve this server."
                Text(detail, color = GrokifyColors.TextMuted, fontSize = 11.sp)
                if (!usage?.loginUserCode.isNullOrBlank()) {
                    Text(
                        "Code: ${usage!!.loginUserCode}",
                        color = GrokifyColors.TextPrimary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (loginNeeded) {
                    Button(
                        onClick = onGrokLogin,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GrokifyColors.GlowCyan.copy(alpha = 0.2f),
                            contentColor = GrokifyColors.GlowCyan,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (usage?.loginStatus == "pending") {
                                "Open login link again"
                            } else {
                                "Sign in with Grok / xAI"
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    // Force a brand-new device code (e.g. expired link or switch account mid-flow).
                    TextButton(
                        onClick = onGrokLogout,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "New login link (switch account)",
                            color = GrokifyColors.TextMuted,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
            usage == null && state.usageLoading -> {
                Text("Loading weekly pool…", color = GrokifyColors.TextMuted, fontSize = 13.sp)
            }
            usage == null -> {
                Text(
                    "Tap refresh to load your Grok weekly pool",
                    color = GrokifyColors.TextMuted,
                    fontSize = 13.sp,
                )
                OutlinedButton(
                    onClick = onGrokLogout,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = GrokifyColors.GlowRose,
                    ),
                    border = BorderStroke(1.dp, GrokifyColors.GlowRose.copy(alpha = 0.45f)),
                ) {
                    Text("Log out & get login link", fontWeight = FontWeight.SemiBold)
                }
            }
            else -> {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            formatUsagePct(pct).removeSuffix("%"),
                            color = accent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 32.sp,
                            lineHeight = 34.sp,
                        )
                        Text(
                            "%",
                            color = accent.copy(alpha = 0.85f),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(bottom = 4.dp, start = 1.dp),
                        )
                        Text(
                            " used",
                            color = GrokifyColors.TextMuted,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(bottom = 5.dp, start = 4.dp),
                        )
                    }
                    Text(
                        "${formatUsagePct(remaining)} left",
                        color = GrokifyColors.TextPrimary,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(GrokifyColors.PanelSoft)
                        .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(999.dp)),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth((pct / 100.0).toFloat().coerceIn(0.02f, 1f).takeIf { pct > 0 } ?: 0f)
                            .clip(RoundedCornerShape(999.dp))
                            .background(accent.copy(alpha = 0.9f)),
                    )
                }

                Text(
                    formatResetWhen(usage.resetAt),
                    color = GrokifyColors.TextMuted,
                    fontSize = 12.sp,
                )

                val active = usage.products.filter {
                    it.usagePercent != null && (it.usagePercent ?: 0.0) > 0
                }
                if (active.isNotEmpty()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(GrokifyColors.PanelSoft)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "BY PRODUCT",
                            color = GrokifyColors.TextDim,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.6.sp,
                        )
                        active.forEach { p ->
                            val pPct = p.usagePercent ?: 0.0
                            val pAccent = usageAccent(pPct)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        usageProductLabel(p.product),
                                        color = GrokifyColors.TextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        formatUsagePct(pPct),
                                        color = pAccent,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(GrokifyColors.PanelBorder),
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxHeight()
                                            .fillMaxWidth(
                                                (pPct / 100.0).toFloat().coerceIn(0.02f, 1f),
                                            )
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(pAccent.copy(alpha = 0.85f)),
                                    )
                                }
                            }
                        }
                    }
                }

                UsageTrackerSection(usage.tracker)

                Text(
                    "Switch accounts or re-auth: signs out Grok Build on this server and opens a fresh OAuth link.",
                    color = GrokifyColors.TextDim,
                    fontSize = 11.sp,
                )
                OutlinedButton(
                    onClick = onGrokLogout,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = GrokifyColors.GlowRose,
                    ),
                    border = BorderStroke(1.dp, GrokifyColors.GlowRose.copy(alpha = 0.45f)),
                ) {
                    Text("Log out & get login link", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun NotificationAccessCard(
    granted: Boolean,
    count: Int,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    listenerBound: Boolean = false,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(GrokifyColors.Panel)
            .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (granted) "Notification access enabled" else "Notification access required",
            color = if (granted) GrokifyColors.GlowMint else GrokifyColors.GlowRose,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
        )
        Text(
            when {
                !granted ->
                    "Android only grants this via system settings (not a popup permission). " +
                        "Tap Grant access → enable GrokifyOS."
                listenerBound ->
                    "Listener connected. Mirroring $count active " +
                        "notification${if (count == 1) "" else "s"} so Grok can pull them."
                else ->
                    "Access is on, but the listener is not bound yet. " +
                        "Tap Refresh, or toggle the system switch off/on once. Cached: $count."
            },
            color = GrokifyColors.TextMuted,
            fontSize = 12.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onOpenSettings,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (granted) GrokifyColors.PanelSoft else GrokifyColors.GlowCyan,
                    contentColor = if (granted) GrokifyColors.GlowCyan else Color(0xFF041016),
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    if (granted) "Open system settings" else "Grant access",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                )
            }
            TextButton(onClick = onRefresh) {
                Text("Refresh status", color = GrokifyColors.TextMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(GrokifyColors.Panel)
            .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = GrokifyColors.TextPrimary, fontWeight = FontWeight.Medium)
            Text(subtitle, color = GrokifyColors.TextMuted, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = GrokifyColors.GlowCyan,
                checkedTrackColor = GrokifyColors.GlowCyan.copy(alpha = 0.35f),
            ),
        )
    }
}

/**
 * In-chat card when Grok (or the bridge) requests a runtime permission.
 * Allow → system dialog; Not now → dismiss without granting.
 */
@Composable
private fun PermissionRequestCard(
    msg: ChatLine,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    val title = msg.permissionId
        .replace('_', ' ')
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        .ifBlank { "Permission" }
    val pending = msg.permissionStatus == PermissionRequestStatus.Pending
    val statusLabel = when (msg.permissionStatus) {
        PermissionRequestStatus.Pending -> null
        PermissionRequestStatus.Granted -> "Allowed"
        PermissionRequestStatus.Denied -> "Denied"
        PermissionRequestStatus.Dismissed -> "Not now"
    }
    val accent = when (msg.permissionStatus) {
        PermissionRequestStatus.Pending -> GrokifyColors.GlowViolet
        PermissionRequestStatus.Granted -> GrokifyColors.GlowMint
        PermissionRequestStatus.Denied -> GrokifyColors.GlowRose
        PermissionRequestStatus.Dismissed -> GrokifyColors.TextDim
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(GrokifyColors.Panel)
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "PERMISSION",
                style = MaterialTheme.typography.labelSmall,
                color = accent,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                color = GrokifyColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
            if (statusLabel != null) {
                Spacer(Modifier.weight(1f))
                Text(statusLabel, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
        if (msg.text.isNotBlank()) {
            Text(msg.text, color = GrokifyColors.TextMuted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        if (pending) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onAllow,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GrokifyColors.GlowCyan,
                        contentColor = Color(0xFF041016),
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("Allow", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = onDeny,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, GrokifyColors.PanelBorder),
                ) {
                    Text("Not now", color = GrokifyColors.TextMuted, fontSize = 13.sp)
                }
            }
        }
    }
}

// ─── Message bubbles ──────────────────────────────────────────────────

/** Full local date + time to the second for chat context (e.g. `Jul 16, 2026 · 14:32:05`). */
private fun formatFullChatTimestamp(ms: Long): String {
    if (ms <= 0L) return ""
    return try {
        java.text.SimpleDateFormat("MMM d, yyyy · HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(ms))
    } catch (_: Exception) {
        ""
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(
    msg: ChatLine,
    selected: Boolean,
    onTap: () -> Unit,
    actions: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    val borderColor = when {
        selected -> GrokifyColors.GlowBlue.copy(alpha = 0.55f)
        msg.excludedFromContext -> GrokifyColors.TextDim.copy(alpha = 0.5f)
        else -> GrokifyColors.GlowBlue.copy(alpha = 0.2f)
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(Modifier.fillMaxWidth(0.88f), horizontalAlignment = Alignment.End) {
            // Menu via header tap / long-press — body stays free for link clicks + selection
            Column(
                Modifier
                    .fillMaxWidth()
                    .alpha(if (msg.excludedFromContext) 0.55f else 1f)
                    .clip(shape)
                    .background(GrokifyColors.UserBubble)
                    .border(
                        width = if (selected || msg.excludedFromContext) 1.5.dp else 1.dp,
                        color = borderColor,
                        shape = shape,
                    )
                    .combinedClickable(
                        enabled = !msg.streaming,
                        onClick = onTap,
                        onLongClick = onTap,
                        role = Role.Button,
                    )
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("YOU", style = MaterialTheme.typography.labelSmall, color = GrokifyColors.GlowBlue)
                    Spacer(Modifier.weight(1f))
                    val ts = formatFullChatTimestamp(msg.createdAtMs)
                    if (ts.isNotBlank()) {
                        Text(
                            ts,
                            style = MaterialTheme.typography.labelSmall,
                            color = GrokifyColors.TextDim,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    if (msg.excludedFromContext) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "hidden",
                            style = MaterialTheme.typography.labelSmall,
                            color = GrokifyColors.TextDim,
                            fontSize = 10.sp,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (msg.userMediaUrls.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        msg.userMediaUrls.forEach { url ->
                            AsyncImage(
                                model = url,
                                contentDescription = "Attached image",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(120.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(
                                        1.dp,
                                        GrokifyColors.GlowBlue.copy(alpha = 0.35f),
                                        RoundedCornerShape(10.dp),
                                    )
                                    .clickable {
                                        runCatching {
                                            val ctx = context
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            ctx.startActivity(intent)
                                        }
                                    },
                            )
                        }
                    }
                }
                // Links need to receive taps; consume only non-link presses via parent combinedClickable
                if (msg.text.isNotBlank()) {
                    MarkdownText(msg.text, textColor = GrokifyColors.TextPrimary)
                }
            }
            AnimatedVisibility(
                visible = selected,
                enter = fadeIn() + slideInVertically { -it / 2 },
                exit = fadeOut() + slideOutVertically { -it / 2 },
            ) {
                actions()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantBubble(
    msg: ChatLine,
    selected: Boolean,
    onTap: () -> Unit,
    actions: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    val borderColor = when {
        selected -> GrokifyColors.GlowMint.copy(alpha = 0.5f)
        msg.excludedFromContext -> GrokifyColors.TextDim.copy(alpha = 0.5f)
        else -> GrokifyColors.GlowMint.copy(alpha = 0.18f)
    }
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .alpha(if (msg.excludedFromContext) 0.55f else 1f)
                .clip(shape)
                .background(GrokifyColors.AssistantBubble)
                .border(
                    width = if (selected || msg.excludedFromContext) 1.5.dp else 1.dp,
                    color = borderColor,
                    shape = shape,
                )
                // combinedClickable: short tap still opens menu on empty space;
                // LinkAnnotation on MarkdownText consumes taps on URLs first.
                .combinedClickable(
                    enabled = !msg.streaming,
                    onClick = onTap,
                    onLongClick = onTap,
                    role = Role.Button,
                )
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("GROK", style = MaterialTheme.typography.labelSmall, color = GrokifyColors.GlowMint)
                if (msg.streaming) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(10.dp),
                        strokeWidth = 1.5.dp,
                        color = GrokifyColors.GlowMint,
                    )
                }
                Spacer(Modifier.weight(1f))
                val ts = formatFullChatTimestamp(msg.createdAtMs)
                if (ts.isNotBlank()) {
                    Text(
                        ts,
                        style = MaterialTheme.typography.labelSmall,
                        color = GrokifyColors.TextDim,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (msg.excludedFromContext) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "hidden",
                        style = MaterialTheme.typography.labelSmall,
                        color = GrokifyColors.TextDim,
                        fontSize = 10.sp,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            if (msg.text.isBlank() && msg.streaming) {
                Text("…", color = GrokifyColors.TextMuted, fontSize = 14.sp)
            } else {
                MarkdownText(msg.text, textColor = GrokifyColors.TextPrimary)
            }
        }
        AnimatedVisibility(
            visible = selected,
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 },
        ) {
            actions()
        }
    }
}

@Composable
private fun ThinkingCard(msg: ChatLine, onToggle: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (msg.excludedFromContext) 0.55f else 1f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0C0E16))
            .border(1.dp, GrokifyColors.GlowViolet.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .animateContentSize()
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Psychology, null, tint = GrokifyColors.GlowViolet, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                if (msg.streaming) "Thinking…" else "Thoughts",
                color = GrokifyColors.GlowViolet,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            val ts = formatFullChatTimestamp(msg.createdAtMs)
            if (ts.isNotBlank()) {
                Text(
                    ts,
                    color = GrokifyColors.TextDim,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
            if (msg.streaming) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = GrokifyColors.GlowViolet,
                )
                Spacer(Modifier.width(6.dp))
            }
            Icon(
                if (msg.expanded || msg.streaming) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                null,
                tint = GrokifyColors.TextDim,
                modifier = Modifier.size(18.dp),
            )
        }
        if (msg.expanded || msg.streaming) {
            Spacer(Modifier.height(6.dp))
            if (msg.text.isBlank() && msg.streaming) {
                Text("…", color = GrokifyColors.TextMuted, fontSize = 12.sp)
            } else {
                MarkdownText(
                    msg.text,
                    textColor = GrokifyColors.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun MediaCard(msg: ChatLine) {
    val context = LocalContext.current
    val url = msg.mediaUrl
    if (url.isBlank()) return
    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (msg.excludedFromContext) 0.55f else 1f)
            .clip(RoundedCornerShape(12.dp))
            .background(GrokifyColors.CodeBg)
            .border(1.dp, GrokifyColors.GlowCyan.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
            .padding(8.dp)
    ) {
        val toolLabel = msg.toolName.trim().takeUnless {
            it.isEmpty() || it.equals("null", ignoreCase = true)
        }.orEmpty()
        val nameLabel = msg.text.trim().takeUnless {
            it.isEmpty() || it.equals("null", ignoreCase = true)
        }.orEmpty()
        val label = buildString {
            if (toolLabel.isNotEmpty()) append(toolLabel)
            if (nameLabel.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append(nameLabel)
            }
            if (isEmpty()) append(if (msg.mediaKind == "video") "Video" else "Image")
        }
        Text(
            label,
            color = GrokifyColors.TextDim,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        if (msg.mediaKind == "video") {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoURI(Uri.parse(url))
                        setOnPreparedListener { mp ->
                            mp.isLooping = true
                            start()
                        }
                        setOnClickListener {
                            if (isPlaying) pause() else start()
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Tap video to play/pause · long-press opens externally",
                color = GrokifyColors.TextDim,
                fontSize = 10.sp,
                modifier = Modifier
                    .clickable {
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                    setDataAndType(Uri.parse(url), "video/*")
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        } catch (_: Exception) {
                        }
                    },
            )
        } else {
            AsyncImage(
                model = url,
                contentDescription = msg.text.ifBlank { "Generated image" },
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        } catch (_: Exception) {
                        }
                    },
            )
        }
    }
}

@Composable
private fun ToolCard(msg: ChatLine, onToggle: () -> Unit) {
    val accent = when (msg.toolSuccess) {
        true -> GrokifyColors.GlowMint
        false -> GrokifyColors.GlowRose
        null -> GrokifyColors.GlowBlue
    }
    val statusIcon = when (msg.toolSuccess) {
        true -> Icons.Default.CheckCircle
        false -> Icons.Default.Error
        null -> Icons.Default.Build
    }
    val input = msg.toolDetail
    val result = msg.toolResult.ifBlank {
        // Older rows stored result in text/toolDetail only
        if (msg.toolSuccess != null) msg.text else ""
    }
    val preview = (input.ifBlank { result })
        .replace(Regex("\\s+"), " ")
        .trim()
        .let { if (it.length > 100) it.take(100) + "…" else it }
    val hasBody = input.isNotBlank() || result.isNotBlank()
    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (msg.excludedFromContext) 0.55f else 1f)
            .clip(RoundedCornerShape(12.dp))
            .background(GrokifyColors.CodeBg)
            .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .clickable(enabled = hasBody, onClick = onToggle)
            .animateContentSize()
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(statusIcon, null, tint = accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                msg.toolName.ifBlank { "tool" },
                color = accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
            )
            if (preview.isNotBlank() && !msg.expanded && msg.toolSuccess != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    preview,
                    color = GrokifyColors.TextDim,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            val toolTs = formatFullChatTimestamp(msg.createdAtMs)
            if (toolTs.isNotBlank()) {
                Text(
                    toolTs,
                    color = GrokifyColors.TextDim,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
            if (msg.toolSuccess == null) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = accent)
            } else if (hasBody) {
                Icon(
                    if (msg.expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null,
                    tint = GrokifyColors.TextDim,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        // While running: show input; when done + expanded: input + result
        val showBody = msg.toolSuccess == null || msg.expanded
        if (showBody && hasBody) {
            if (input.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "INPUT",
                    color = GrokifyColors.TextDim,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.6.sp,
                )
                Text(
                    input.take(12000),
                    color = GrokifyColors.TextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 15.sp,
                )
            }
            if (result.isNotBlank() && msg.toolSuccess != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "RESULT",
                    color = GrokifyColors.TextDim,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.6.sp,
                )
                Text(
                    result.take(16000),
                    color = GrokifyColors.TextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 15.sp,
                )
            }
        }
    }
}

@Composable
private fun SystemLine(
    msg: ChatLine,
    selected: Boolean = false,
    onTap: () -> Unit = {},
    actions: @Composable (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .alpha(if (msg.excludedFromContext) 0.55f else 1f)
                .clip(RoundedCornerShape(8.dp))
                .background(GrokifyColors.Panel)
                .border(
                    1.dp,
                    if (selected) GrokifyColors.GlowCyan.copy(alpha = 0.35f)
                    else Color.Transparent,
                    RoundedCornerShape(8.dp),
                )
                .clickable(onClick = onTap, role = Role.Button)
                .padding(8.dp),
        ) {
            val ts = formatFullChatTimestamp(msg.createdAtMs)
            if (ts.isNotBlank()) {
                Text(
                    ts,
                    color = GrokifyColors.TextDim,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
                msg.text,
                color = GrokifyColors.TextDim,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (actions != null) {
            AnimatedVisibility(
                visible = selected,
                enter = fadeIn() + slideInVertically { -it / 2 },
                exit = fadeOut() + slideOutVertically { -it / 2 },
            ) {
                actions()
            }
        }
    }
}

@Composable
private fun AppsPane(
    screen: String?,
    appOrder: List<String>,
    onOpenApp: (String) -> Unit,
    onBackToHub: () -> Unit,
    onSetAppOrder: (List<String>) -> Unit,
    onRequestWifiPerms: () -> Unit,
    onRequestBtPerms: () -> Unit,
    onRequestPlacePerms: () -> Unit,
    onRequestNotifPerms: () -> Unit = {},
    onRequestLyrePerms: () -> Unit = {},
) {
    val resolved = when (screen) {
        "spotify_dj" -> BuiltinPluginCatalog.SPOTIFY_CONTROLLER
        else -> screen
    }
    when (resolved) {
        BuiltinPluginCatalog.WIFI_SCANNER, "wifi_scanner" -> WifiScannerPane(
            onBack = onBackToHub,
            onRequestPermissions = onRequestWifiPerms,
        )
        BuiltinPluginCatalog.BT_SCANNER, "bt_scanner" -> BluetoothScannerPane(
            onBack = onBackToHub,
            onRequestPermissions = onRequestBtPerms,
        )
        BuiltinPluginCatalog.PLACE_NOTES, "place_notes" -> LocationNotesPane(
            onBack = onBackToHub,
            onRequestPermissions = onRequestPlacePerms,
        )
        BuiltinPluginCatalog.SPOTIFY_CONTROLLER, "spotify_controller" -> SpotifyControllerPane(
            onBack = onBackToHub,
            onRequestPermissions = onRequestNotifPerms,
        )
        BuiltinPluginCatalog.SPACEXAI_USAGE, "spacexai_usage_analyzer" -> SpaceXaiUsageAnalyzerPane(
            onBack = onBackToHub,
        )
        BuiltinPluginCatalog.GROK_ASSISTANT, "grok_assistant" -> GrokAssistantPane(
            onBack = onBackToHub,
        )
        BuiltinPluginCatalog.COMPANION, "companion" -> CompanionPane(
            onBack = onBackToHub,
        )
        BuiltinPluginCatalog.WATCH_DEPLOY, "watch_deploy" -> io.grokify.os.apps.watchdeploy.WatchDeployPane(
            onBack = onBackToHub,
        )
        BuiltinPluginCatalog.CEXBOT, "cexbot" -> CexBotPane(
            onBack = onBackToHub,
        )
        BuiltinPluginCatalog.GBOT, "gbot" -> GbotPane(
            onBack = onBackToHub,
        )
        BuiltinPluginCatalog.DISCORD, "discord" -> DiscordPane(
            onBack = onBackToHub,
        )
        BuiltinPluginCatalog.LYRE, "lyre" -> io.grokify.os.apps.lyre.LyrePane(
            onBack = onBackToHub,
            onRequestPermissions = onRequestLyrePerms,
        )
        else -> AppsHub(
            appOrder = appOrder,
            onOpenApp = onOpenApp,
            onSetAppOrder = onSetAppOrder,
        )
    }
}

private fun orderedBuiltinApps(order: List<String>): List<PluginManifest> {
    val byId = BuiltinPluginCatalog.all.associateBy { it.id }
    val known = BuiltinPluginCatalog.all.map { it.id }
    val knownSet = known.toSet()
    val cleaned = order
        .map { if (it == "spotify_dj") BuiltinPluginCatalog.SPOTIFY_CONTROLLER else it }
        .filter { it in knownSet }
        .distinct()
        .toMutableList()
    for (id in known) {
        if (id !in cleaned) cleaned.add(id)
    }
    return cleaned.mapNotNull { byId[it] }
}

@Composable
private fun AppsHub(
    appOrder: List<String>,
    onOpenApp: (String) -> Unit,
    onSetAppOrder: (List<String>) -> Unit,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val itemHeightPx = with(density) { 88.dp.toPx() }
    var orderedIds by remember { mutableStateOf(orderedBuiltinApps(appOrder).map { it.id }) }
    LaunchedEffect(appOrder) {
        orderedIds = orderedBuiltinApps(appOrder).map { it.id }
    }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragFromIndex by remember { mutableIntStateOf(-1) }
    var dragAccum by remember { mutableFloatStateOf(0f) }
    var suppressClick by remember { mutableStateOf(false) }

    val apps = remember(orderedIds) {
        val byId = BuiltinPluginCatalog.all.associateBy { it.id }
        orderedIds.mapNotNull { byId[it] }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        GlassCard {
            Text("APPS", style = MaterialTheme.typography.labelSmall, color = GrokifyColors.GlowCyan)
            Spacer(Modifier.height(6.dp))
            Text(
                "Built-in tools that ship with GrokifyOS. Press and hold a tile, then drag to rearrange.",
                color = GrokifyColors.TextMuted,
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.height(14.dp))

        Text(
            if (draggingId != null) "REARRANGING…" else "YOUR APPS",
            style = MaterialTheme.typography.labelSmall,
            color = if (draggingId != null) GrokifyColors.GlowAmber else GrokifyColors.TextDim,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(8.dp))

        apps.forEachIndexed { index, app ->
            key(app.id) {
                if (index > 0) Spacer(Modifier.height(10.dp))
                val isDragging = draggingId == app.id
                BuiltinAppTile(
                    app = app,
                    isDragging = isDragging,
                    onOpen = {
                        if (!suppressClick) onOpenApp(app.id)
                        suppressClick = false
                    },
                    onLongPressDragStart = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        draggingId = app.id
                        dragFromIndex = orderedIds.indexOf(app.id)
                        dragAccum = 0f
                        suppressClick = true
                    },
                    onLongPressDrag = { dy ->
                        val id = draggingId ?: return@BuiltinAppTile
                        if (id != app.id) return@BuiltinAppTile
                        var from = orderedIds.indexOf(id)
                        if (from < 0) from = dragFromIndex
                        if (from < 0) return@BuiltinAppTile
                        dragAccum += dy
                        val shift = (dragAccum / itemHeightPx).toInt()
                        if (shift == 0) return@BuiltinAppTile
                        val target = (from + shift).coerceIn(0, orderedIds.lastIndex)
                        if (target != from) {
                            val list = orderedIds.toMutableList()
                            list.add(target, list.removeAt(from))
                            orderedIds = list
                            dragFromIndex = target
                            dragAccum -= shift * itemHeightPx
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    },
                    onLongPressDragEnd = {
                        draggingId = null
                        dragFromIndex = -1
                        dragAccum = 0f
                        onSetAppOrder(orderedIds)
                    },
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "Host permissions stay on GrokifyOS — each app requests them when needed.",
            color = GrokifyColors.TextDim,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun BuiltinAppTile(
    app: PluginManifest,
    isDragging: Boolean,
    onOpen: () -> Unit,
    onLongPressDragStart: () -> Unit,
    onLongPressDrag: (Float) -> Unit,
    onLongPressDragEnd: () -> Unit,
) {
    val accent = pluginAccentColor(app.accent)
    val borderColor = if (isDragging) accent.copy(alpha = 0.65f) else GrokifyColors.PanelBorder
    val elevationAlpha = if (isDragging) 0.18f else 0f
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (isDragging) accent.copy(alpha = 0.10f) else GrokifyColors.Panel,
            )
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .pointerInput(app.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { onLongPressDragStart() },
                    onDragEnd = { onLongPressDragEnd() },
                    onDragCancel = { onLongPressDragEnd() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onLongPressDrag(dragAmount.y)
                    },
                )
            }
            .clickable(onClick = onOpen, role = Role.Button)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (PluginFavicon.drawableRes(app.id) == null) {
                        accent.copy(alpha = 0.12f + elevationAlpha)
                    } else {
                        Color.Transparent
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            PluginFaviconImage(
                pluginId = app.id,
                fallback = app.icon,
                modifier = Modifier.size(if (PluginFavicon.drawableRes(app.id) != null) 48.dp else 24.dp),
                fallbackTint = accent,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                app.title,
                color = GrokifyColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                app.subtitle,
                color = GrokifyColors.TextMuted,
                fontSize = 12.sp,
                maxLines = 2,
            )
        }
        Icon(
            Icons.Default.DragHandle,
            contentDescription = "Hold and drag to rearrange",
            tint = if (isDragging) accent else GrokifyColors.TextDim,
            modifier = Modifier.size(22.dp),
        )
    }
}

private fun pluginAccentColor(accent: PluginAccent): Color = when (accent) {
    PluginAccent.Cyan -> GrokifyColors.GlowCyan
    PluginAccent.Mint -> GrokifyColors.GlowMint
    PluginAccent.Violet -> GrokifyColors.GlowViolet
    PluginAccent.Amber -> GrokifyColors.GlowAmber
    PluginAccent.Rose -> GrokifyColors.GlowRose
    PluginAccent.Blue -> GrokifyColors.GlowBlue
}

/** Compact bottom-nav label for a last-opened mini-app (fits under the icon). */
private fun appsNavShortTitle(app: PluginManifest): String = when (app.id) {
    BuiltinPluginCatalog.WIFI_SCANNER -> "Wi‑Fi"
    BuiltinPluginCatalog.BT_SCANNER -> "Bluetooth"
    BuiltinPluginCatalog.PLACE_NOTES -> "Places"
    BuiltinPluginCatalog.SPOTIFY_CONTROLLER -> "Spotify"
    BuiltinPluginCatalog.SPACEXAI_USAGE -> "Usage"
    BuiltinPluginCatalog.GROK_ASSISTANT -> "Assistant"
    BuiltinPluginCatalog.COMPANION -> "Companion"
    BuiltinPluginCatalog.WATCH_DEPLOY -> "Watch"
    BuiltinPluginCatalog.CEXBOT -> "CexBot"
    BuiltinPluginCatalog.GBOT -> "Grok Bot"
    BuiltinPluginCatalog.DISCORD -> "Discord"
    else -> app.title.take(12)
}

@Composable
private fun UpdatePane(
    state: UiState,
    onCheckUpdate: () -> Unit,
    onDownloadInstall: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        GlassCard {
            Text("IN-APP UPDATE", style = MaterialTheme.typography.labelSmall, color = GrokifyColors.GlowCyan)
            Spacer(Modifier.height(8.dp))
            Text(
                "Installed: ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})",
                color = GrokifyColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Download the latest APK from the server and install it here — no browser needed. Your device token is kept.",
                color = GrokifyColors.TextMuted,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onCheckUpdate,
                enabled = !state.updateDownloading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = GrokifyColors.GlowCyan,
                    contentColor = Color(0xFF041016),
                    disabledContainerColor = GrokifyColors.PanelBorder,
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Check for updates", fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onDownloadInstall,
                enabled = !state.updateDownloading && state.tokenSaved,
                colors = ButtonDefaults.buttonColors(
                    containerColor = GrokifyColors.GlowMint,
                    contentColor = Color(0xFF041016),
                    disabledContainerColor = GrokifyColors.PanelBorder,
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        state.updateDownloading -> "Downloading…"
                        state.updateAvailable -> "Download & install ${state.updateVersionName}"
                        else -> "Download & install latest"
                    },
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (state.updateDownloading || state.updateProgress > 0f) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { state.updateProgress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = GrokifyColors.GlowCyan,
                    trackColor = GrokifyColors.PanelBorder,
                )
            }
            if (state.updateAvailable && state.updateChangelog.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text("CHANGELOG", style = MaterialTheme.typography.labelSmall, color = GrokifyColors.TextDim)
                Spacer(Modifier.height(4.dp))
                Text(state.updateChangelog, color = GrokifyColors.TextMuted, fontSize = 13.sp)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRefresh, enabled = !state.updateDownloading, modifier = Modifier.fillMaxWidth()) {
                Text("Refresh session / bridge", color = GrokifyColors.GlowMint)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                state.updateInfo.ifBlank { "No check yet — tap Check or Download & install" },
                color = if (state.error != null && state.updateInfo.startsWith("Update failed")) {
                    Color(0xFFFF8A80)
                } else {
                    GrokifyColors.GlowMint
                },
                fontSize = 14.sp,
            )
            if (!state.tokenSaved) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Save a device token on Home first so the download can authenticate.",
                    color = GrokifyColors.TextDim,
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "First install may ask to allow “Install unknown apps” for Grokify — enable it, then tap Download & install again.",
                color = GrokifyColors.TextDim,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = GrokifyColors.GlowCyan,
    unfocusedBorderColor = GrokifyColors.PanelBorder,
    focusedTextColor = GrokifyColors.TextPrimary,
    unfocusedTextColor = GrokifyColors.TextPrimary,
    cursorColor = GrokifyColors.GlowCyan,
    focusedContainerColor = GrokifyColors.Panel,
    unfocusedContainerColor = GrokifyColors.Panel,
    focusedPlaceholderColor = GrokifyColors.TextDim,
    unfocusedPlaceholderColor = GrokifyColors.TextDim,
)
