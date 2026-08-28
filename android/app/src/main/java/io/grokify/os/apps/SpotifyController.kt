package io.grokify.os.apps

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.grokify.os.GrokifyApp
import io.grokify.os.apps.plugin.HostAiClient
import io.grokify.os.apps.plugin.HostApiKeyStore
import io.grokify.os.apps.plugin.SpotifyOAuth
import io.grokify.os.data.ApiKeyIds
import io.grokify.os.MainActivity
import io.grokify.os.permission.AppPermissionId
import io.grokify.os.permission.PermissionHelper
import io.grokify.os.service.GrokifyNotificationListener
import io.grokify.os.ui.theme.GrokifyColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private fun setOnMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block()
    else Handler(Looper.getMainLooper()).post(block)
}

private const val TAG = "SpotifyCtrl"
private const val PREFS = "spotify_controller"
private const val KEY_ENABLED = "enabled"
private const val KEY_PREFERRED_DEVICE = "preferred_device_id"
private const val KEY_LAST_STATUS = "last_status"
private const val KEY_LAST_ERROR = "last_error"
/** Master switch: expose Grokify Live DJ as an Android Auto music source. */
private const val KEY_ANDROID_AUTO = "android_auto_v1"
const val SPOTIFY_CTRL_NOTIF_ID = 47001

/** Process-lifetime: true while [SpotifyControllerService] is created. */
@Volatile
private var ctrlServiceAlive: Boolean = false

/** Rate-limit ensure kicks so Control-tab polling cannot thrash FGS. */
@Volatile
private var lastCtrlEnsureMs: Long = 0L

const val ACTION_PREV = "io.grokify.os.SPOTIFY_PREV"
const val ACTION_PLAY_PAUSE = "io.grokify.os.SPOTIFY_PLAY_PAUSE"
const val ACTION_NEXT = "io.grokify.os.SPOTIFY_NEXT"
const val ACTION_STOP = "io.grokify.os.SPOTIFY_STOP"
/** Home-screen widget: toggle Liked Songs for current track. */
const val ACTION_LIKE_TOGGLE = "io.grokify.os.SPOTIFY_LIKE_TOGGLE"
/** Home-screen widget: queue more-like-this via Live DJ. */
const val ACTION_MORE_LIKE = "io.grokify.os.SPOTIFY_MORE_LIKE"

/** Target for the Dislike multi-select modal (Control + chat bubbles). */
private data class DislikeTarget(
    val trackUri: String,
    val name: String,
    val artists: String,
    val artistUri: String = "",
    val artistIds: List<String> = emptyList(),
    /** When true, apply will skip if this cut is currently playing. */
    val skipIfPlaying: Boolean = true,
)
/** Widget: play a specific track URI (history bubble). */
const val ACTION_PLAY_URI = "io.grokify.os.SPOTIFY_PLAY_URI"
/** Widget: like/unlike a specific track URI. */
const val ACTION_LIKE_URI = "io.grokify.os.SPOTIFY_LIKE_URI"
/** Widget: more-like-this for a specific track URI. */
const val ACTION_MORE_LIKE_URI = "io.grokify.os.SPOTIFY_MORE_LIKE_URI"
/** Widget: toggle Live AI DJ booth on/off. */
const val ACTION_DJ_TOGGLE = "io.grokify.os.SPOTIFY_DJ_TOGGLE"
/** Widget: force refresh Spotify / DJ widgets. */
const val ACTION_WIDGET_REFRESH = "io.grokify.os.SPOTIFY_WIDGET_REFRESH"

const val EXTRA_TRACK_URI = "track_uri"
const val EXTRA_TRACK_NAME = "track_name"
const val EXTRA_TRACK_ARTISTS = "track_artists"
const val EXTRA_ARTIST_URI = "artist_uri"
const val EXTRA_ALBUM_ART = "album_art"
const val EXTRA_ARTIST_ART = "artist_art"
const val EXTRA_ALBUM_URI = "album_uri"

internal val SPOTIFY_PACKAGES = listOf(
    "com.spotify.music",
    "com.spotify.lite",
)

/**
 * Coil model for Control-tab art.
 *
 * Prefer **on-disk** bytes (session mirror first, then CDN mirror). Never bind
 * Coil to ephemeral `content://` media-session URIs — they paint once then die
 * (flash-then-empty). Do **not** switch Coil to a host media-cache URL until
 * that file is on disk — otherwise a 404 host URL blanks the card.
 */
private fun controlArtModel(context: Context, sourceUrl: String?, trackUri: String = ""): Any? {
    // Session bitmap mirror is the most durable key we control.
    if (trackUri.isNotBlank()) {
        SpotifyArtMirror.localFile(context, "session:$trackUri")?.let { return it }
    }
    val src = sourceUrl?.trim().orEmpty()
    if (src.isBlank()) return null
    // Spotify often exposes a temporary content:// provider URI that works for
    // one frame then becomes unreadable — never use those as the Coil model.
    if (src.startsWith("content:", ignoreCase = true) ||
        src.startsWith("android.resource:", ignoreCase = true)
    ) {
        return null
    }
    SpotifyArtMirror.localFile(context, src)?.let { return it }
    val preferred = SpotifyArtMirror.preferredUrl(context, src)
    if (preferred.isNotBlank() && preferred != src) {
        SpotifyArtMirror.localFile(context, preferred)?.let { return it }
    }
    // Kick mirror in background; keep loading the original until disk is ready.
    if (SpotifyArtMirror.isSpotifyCdn(src) || src.startsWith("http", ignoreCase = true)) {
        SpotifyArtMirror.mirrorAsync(context, src, onDone = null)
        return src
    }
    return null
}

/** Snapshot of whatever media session we can drive. */
data class SpotifyNowPlaying(
    val title: String = "",
    val artist: String = "",
    val appLabel: String = "",
    val packageName: String = "",
    val isPlaying: Boolean = false,
    val hasSession: Boolean = false,
    /** Playback position in ms (extrapolated while playing). */
    val positionMs: Long = 0L,
    /** Track duration in ms, 0 if unknown. */
    val durationMs: Long = 0L,
    /** Album / track cover (media session URI or Spotify CDN). */
    val albumArtUrl: String = "",
    /** Primary artist portrait when known from Web API. */
    val artistArtUrl: String = "",
    val trackUri: String = "",
    val albumUri: String = "",
    val artistUri: String = "",
)

/** Persists lockscreen widget on/off and last-picked playback device. */
class SpotifyControllerStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** Last device the user selected in Control (used for transfer + Live DJ play). */
    var preferredDeviceId: String
        get() = prefs.getString(KEY_PREFERRED_DEVICE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_PREFERRED_DEVICE, value).apply()

    /** Short human status for Control tab diagnostics. */
    var lastStatus: String
        get() = prefs.getString(KEY_LAST_STATUS, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_LAST_STATUS, value).apply()

    var lastError: String
        get() = prefs.getString(KEY_LAST_ERROR, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_LAST_ERROR, value).apply()

    /**
     * Master switch for the Android Auto media browser ("Grokify Live DJ").
     * When on, the car source is offered only while Live DJ is on air (then may
     * hand off to a thin Spotify mirror until the car disconnects). Changes apply
     * on the next Live DJ start or car reconnect — not mid-session.
     */
    var androidAutoEnabled: Boolean
        get() = prefs.getBoolean(KEY_ANDROID_AUTO, false)
        set(value) = prefs.edit().putBoolean(KEY_ANDROID_AUTO, value).apply()

    fun recordStatus(status: String, error: String = "") {
        prefs.edit()
            .putString(KEY_LAST_STATUS, status)
            .putString(KEY_LAST_ERROR, error)
            .apply()
    }
}

/** Human-readable why the shade card may be missing (channel / app notifs / service). */
fun spotifyControllerDiag(context: Context): String {
    val appCtx = context.applicationContext
    val store = SpotifyControllerStore(appCtx)
    val nm = appCtx.getSystemService(NotificationManager::class.java)
    val nmc = NotificationManagerCompat.from(appCtx)
    val appOk = nmc.areNotificationsEnabled()
    val ch = nm?.getNotificationChannel(GrokifyApp.CHANNEL_SPOTIFY_CTRL)
    val chImp = ch?.importance ?: -1
    val chBlocked = ch == null || chImp == NotificationManager.IMPORTANCE_NONE
    val posted = isSpotifyControllerNotificationPosted(appCtx)
    val parts = mutableListOf<String>()
    parts += if (store.enabled) "enabled" else "disabled"
    parts += if (ctrlServiceAlive) "service=up" else "service=down"
    parts += if (posted) "notif=live" else "notif=missing"
    parts += if (appOk) "appNotif=ok" else "appNotif=BLOCKED"
    parts += when {
        ch == null -> "channel=missing"
        chBlocked -> "channel=BLOCKED(imp=$chImp)"
        else -> "channel=ok(imp=$chImp)"
    }
    if (store.lastStatus.isNotBlank()) parts += "last=${store.lastStatus}"
    if (store.lastError.isNotBlank()) parts += "err=${store.lastError}"
    return parts.joinToString(" · ")
}

/** Ensure channel exists (Application may not have run yet on some OEM paths). */
fun ensureSpotifyCtrlChannel(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java) ?: return
    if (nm.getNotificationChannel(GrokifyApp.CHANNEL_SPOTIFY_CTRL) != null) return
    nm.createNotificationChannel(
        android.app.NotificationChannel(
            GrokifyApp.CHANNEL_SPOTIFY_CTRL,
            context.getString(io.grokify.os.R.string.notification_channel_spotify_ctrl),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(io.grokify.os.R.string.notification_channel_spotify_ctrl_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        },
    )
}

/** Spotify Connect / app playback target from Web API. */
data class SpotifyPlaybackDevice(
    val id: String,
    val name: String,
    val type: String,
    val isActive: Boolean,
    val isRestricted: Boolean,
    val volumePercent: Int,
)

/**
 * List available Spotify Connect devices. Requires Spotify login.
 * @return devices + optional error message (null on success).
 */
fun fetchSpotifyDevices(context: Context): Pair<List<SpotifyPlaybackDevice>, String?> {
    if (!SpotifyOAuth.isLoggedIn(context)) {
        return emptyList<SpotifyPlaybackDevice>() to "Connect Spotify in the Account tab"
    }
    return try {
        val raw = SpotifyOAuth.api(context, "GET", "/v1/me/player/devices", null)
        val o = JSONObject(raw)
        val status = o.optInt("status", 0)
        if (!o.optBoolean("ok", false) && status !in listOf(200, 201, 202, 204)) {
            val err = o.optString("error", "").ifBlank {
                o.optString("body", "").take(120)
            }
            return emptyList<SpotifyPlaybackDevice>() to
                (err.ifBlank { "Couldn’t load devices (HTTP $status)" })
        }
        val bodyStr = o.optString("body", "")
        if (bodyStr.isBlank()) return emptyList<SpotifyPlaybackDevice>() to null
        val data = runCatching { JSONObject(bodyStr) }.getOrNull()
            ?: return emptyList<SpotifyPlaybackDevice>() to "Bad devices response"
        val arr = data.optJSONArray("devices") ?: JSONArray()
        val out = ArrayList<SpotifyPlaybackDevice>(arr.length())
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            val id = d.optString("id", "")
            if (id.isBlank()) continue
            out.add(
                SpotifyPlaybackDevice(
                    id = id,
                    name = d.optString("name", "Device").ifBlank { "Device" },
                    type = d.optString("type", "Unknown").ifBlank { "Unknown" },
                    isActive = d.optBoolean("is_active", false),
                    isRestricted = d.optBoolean("is_restricted", false),
                    volumePercent = d.optInt("volume_percent", -1),
                ),
            )
        }
        // Active first, then name
        out.sortWith(
            compareByDescending<SpotifyPlaybackDevice> { it.isActive }
                .thenBy { it.name.lowercase() },
        )
        out to null
    } catch (e: Exception) {
        Log.w(TAG, "fetchSpotifyDevices", e)
        emptyList<SpotifyPlaybackDevice>() to (e.message ?: "devices_failed")
    }
}

/**
 * Transfer playback to [deviceId]. When [play] is true, start/resume on that device.
 * @return null on success, else error string.
 */
fun transferSpotifyPlayback(
    context: Context,
    deviceId: String,
    play: Boolean = true,
): String? {
    if (deviceId.isBlank()) return "No device id"
    if (!SpotifyOAuth.isLoggedIn(context)) return "Connect Spotify in the Account tab"
    return try {
        val body = JSONObject()
            .put("device_ids", JSONArray().put(deviceId))
            .put("play", play)
            .toString()
        val raw = SpotifyOAuth.api(context, "PUT", "/v1/me/player", body)
        val o = JSONObject(raw)
        val status = o.optInt("status", 0)
        // 204 No Content = success; some clients also 200/202
        if (o.optBoolean("ok", false) || status in listOf(200, 201, 202, 204)) {
            SpotifyControllerStore(context).preferredDeviceId = deviceId
            null
        } else {
            val errBody = o.optString("body", "")
            val parsed = runCatching {
                JSONObject(errBody).optJSONObject("error")?.optString("message")
            }.getOrNull()
            parsed?.takeIf { it.isNotBlank() }
                ?: o.optString("error", "").ifBlank { "Transfer failed (HTTP $status)" }
        }
    } catch (e: Exception) {
        Log.w(TAG, "transferSpotifyPlayback", e)
        e.message ?: "transfer_failed"
    }
}

private fun deviceTypeIcon(type: String): ImageVector {
    return when (type.trim().lowercase()) {
        "computer" -> Icons.Default.Computer
        "tablet" -> Icons.Default.Tablet
        "smartphone" -> Icons.Default.PhoneAndroid
        "speaker" -> Icons.Default.Speaker
        "tv" -> Icons.Default.Tv
        "avr", "stb", "castvideo", "castaudio" -> Icons.Default.Tv
        "audiodongle", "gameconsole" -> Icons.Default.Headphones
        "automobile" -> Icons.Default.DirectionsCar
        else -> Icons.Default.Devices
    }
}

fun isNotificationListenerEnabled(context: Context): Boolean {
    val flat = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners",
    ) ?: return false
    val pkg = context.packageName
    return flat.split(':').any { it.contains(pkg) }
}

fun openNotificationListenerSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

fun isSpotifyInstalled(context: Context): Boolean {
    val pm = context.packageManager
    return SPOTIFY_PACKAGES.any { pkg ->
        try {
            pm.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}

fun openSpotifyApp(context: Context) {
    val pm = context.packageManager
    for (pkg in SPOTIFY_PACKAGES) {
        val launch = pm.getLaunchIntentForPackage(pkg)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            return
        }
    }
    val market = Intent(
        Intent.ACTION_VIEW,
        android.net.Uri.parse("market://details?id=com.spotify.music"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(market) }
}

/** Whether our controller notification is currently posted. */
fun isSpotifyControllerNotificationPosted(context: Context): Boolean {
    return try {
        NotificationManagerCompat.from(context).activeNotifications
            .any { it.id == SPOTIFY_CTRL_NOTIF_ID }
    } catch (_: Exception) {
        false
    }
}

/**
 * Seed a MediaStyle card via [NotificationManager] so the shade shows something
 * even before FGS binds. Same id as the service — startForeground upgrades it.
 */
private fun seedControllerNotification(context: Context) {
    ensureSpotifyCtrlChannel(context)
    val appCtx = context.applicationContext
    val now = runCatching { nowPlayingForNotification(appCtx) }.getOrNull()
        ?: SpotifyNowPlaying(title = "Spotify Controller", artist = "Prev · Play · Next")
    val n = SpotifyMediaNotif.buildMinimal(appCtx, now, subText = "GrokifyOS")
    runCatching {
        appCtx.getSystemService(NotificationManager::class.java)
            ?.notify(SPOTIFY_CTRL_NOTIF_ID, n)
        Log.i(TAG, "seed notify id=$SPOTIFY_CTRL_NOTIF_ID")
    }.onFailure {
        Log.e(TAG, "seed notify failed: ${it.message}", it)
        SpotifyControllerStore(appCtx).recordStatus("seed_fail", it.message ?: "notify")
    }
}

/**
 * Start (or re-assert) the controller FGS **without** stopService.
 *
 * Critical: Control-tab polling used to call [setSpotifyControllerEnabled] every
 * ~1.5s when the notif looked missing; that path previously did stopService first
 * and permanently thrash-killed the FGS on Samsung / Android 14+.
 */
fun ensureSpotifyControllerRunning(context: Context, force: Boolean = false) {
    val appCtx = context.applicationContext
    val store = SpotifyControllerStore(appCtx)
    if (!store.enabled) return
    val now = SystemClock.elapsedRealtime()
    if (!force && ctrlServiceAlive && isSpotifyControllerNotificationPosted(appCtx)) {
        return
    }
    // Rate-limit kicks unless forced (user Repost / toggle).
    if (!force && now - lastCtrlEnsureMs < 8_000L) return
    lastCtrlEnsureMs = now
    ensureSpotifyCtrlChannel(appCtx)
    if (!isSpotifyControllerNotificationPosted(appCtx)) {
        seedControllerNotification(appCtx)
    }
    try {
        ContextCompat.startForegroundService(
            appCtx,
            Intent(appCtx, SpotifyControllerService::class.java),
        )
        store.recordStatus("start_requested")
        Log.i(TAG, "controller FGS ensure requested force=$force alive=$ctrlServiceAlive")
    } catch (e: Exception) {
        Log.e(TAG, "startForegroundService failed: ${e.message}", e)
        store.recordStatus("start_denied", e.javaClass.simpleName + ": " + (e.message ?: ""))
        // Last resort: keep the seed shade notification even without FGS.
        seedControllerNotification(appCtx)
    }
}

/** Start or stop the lockscreen media control notification. */
fun setSpotifyControllerEnabled(context: Context, enabled: Boolean) {
    val appCtx = context.applicationContext
    val store = SpotifyControllerStore(appCtx)
    store.enabled = enabled
    val intent = Intent(appCtx, SpotifyControllerService::class.java)
    if (enabled) {
        // NEVER stopService here — that was a death spiral with Control-tab auto-retry.
        // Seed shade card immediately so status flips to live even before onStartCommand.
        seedControllerNotification(appCtx)
        lastCtrlEnsureMs = 0L
        ensureSpotifyControllerRunning(appCtx, force = true)
        // Soft re-assert once (OEM delay) — still no stop.
        Handler(Looper.getMainLooper()).postDelayed({
            if (SpotifyControllerStore(appCtx).enabled) {
                ensureSpotifyControllerRunning(appCtx, force = true)
            }
        }, 750L)
    } else {
        store.recordStatus("disabled")
        runCatching { appCtx.stopService(intent) }
        val nm = appCtx.getSystemService(NotificationManager::class.java)
        nm?.cancel(SPOTIFY_CTRL_NOTIF_ID)
        // If Live DJ is still armed, poke it so it reclaims a visible media card.
        if (SpotifyDjStore(appCtx).enabled) {
            runCatching {
                ContextCompat.startForegroundService(
                    appCtx,
                    Intent(appCtx, SpotifyLiveDjService::class.java)
                        .setAction(SpotifyLiveDjService.ACTION_DJ_RELOAD_SETTINGS),
                )
            }
        }
    }
}

/**
 * Finds an active media session, preferring Spotify.
 * Requires Notification Listener access for session list.
 */
fun resolveActiveMediaController(context: Context): MediaController? {
    val msm = context.getSystemService(MediaSessionManager::class.java) ?: return null
    if (!isNotificationListenerEnabled(context)) return null
    val listener = ComponentName(context, GrokifyNotificationListener::class.java)
    val sessions = try {
        msm.getActiveSessions(listener)
    } catch (e: SecurityException) {
        Log.w(TAG, "getActiveSessions denied: ${e.message}")
        emptyList()
    }
    if (sessions.isEmpty()) return null
    // Never target our own Live DJ MediaSession (would loop pause/play keys into ourselves).
    val selfPkg = context.packageName
    val foreign = sessions.filter { it.packageName != selfPkg }
    if (foreign.isEmpty()) return null
    return foreign.firstOrNull { it.packageName in SPOTIFY_PACKAGES }
        ?: foreign.firstOrNull { ctrl ->
            val st = ctrl.playbackState?.state
            st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING
        }
        ?: foreign.firstOrNull()
}

/** Extrapolate live position from last PlaybackState update while playing. */
private fun livePositionMs(state: PlaybackState?): Long {
    if (state == null) return 0L
    val base = state.position.coerceAtLeast(0L)
    val st = state.state
    if (st != PlaybackState.STATE_PLAYING && st != PlaybackState.STATE_BUFFERING) {
        return base
    }
    val updatedAt = state.lastPositionUpdateTime
    if (updatedAt <= 0L) return base
    val elapsed = SystemClock.elapsedRealtime() - updatedAt
    if (elapsed <= 0L) return base
    val speed = if (state.playbackSpeed > 0f) state.playbackSpeed else 1f
    return base + (elapsed * speed).toLong()
}

fun formatTrackTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = (ms / 1000L).toInt()
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

fun readNowPlaying(context: Context): SpotifyNowPlaying {
    val ctrl = resolveActiveMediaController(context) ?: return SpotifyNowPlaying()
    val md = ctrl.metadata
    val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        .ifBlank { md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty() }
    val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        .ifBlank { md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty() }
    val pb = ctrl.playbackState
    val state = pb?.state
    val playing = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
    val duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L
    var position = livePositionMs(pb)
    if (duration > 0L) position = position.coerceIn(0L, duration)
    val label = try {
        val ai = context.packageManager.getApplicationInfo(ctrl.packageName, 0)
        context.packageManager.getApplicationLabel(ai).toString()
    } catch (_: Exception) {
        ctrl.packageName
    }
    val albumArt = md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI).orEmpty()
        .ifBlank { md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI).orEmpty() }
        .ifBlank { md?.getString(MediaMetadata.METADATA_KEY_ART_URI).orEmpty() }
    val mediaUri = md?.getString(MediaMetadata.METADATA_KEY_MEDIA_URI).orEmpty()
        .ifBlank { md?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty() }
    val trackUri = when {
        mediaUri.startsWith("spotify:track:") -> mediaUri
        mediaUri.startsWith("https://open.spotify.com/track/") -> {
            val id = mediaUri.removePrefix("https://open.spotify.com/track/")
                .substringBefore('?').substringBefore('/')
            if (id.isNotBlank()) "spotify:track:$id" else ""
        }
        mediaUri.matches(Regex("^[A-Za-z0-9]{22}$")) -> "spotify:track:$mediaUri"
        else -> ""
    }
    return SpotifyNowPlaying(
        title = title.ifBlank { "Unknown track" },
        artist = artist,
        appLabel = label,
        packageName = ctrl.packageName,
        isPlaying = playing,
        hasSession = true,
        positionMs = position,
        durationMs = duration,
        albumArtUrl = albumArt,
        trackUri = trackUri,
    )
}

/**
 * Album art embedded in the active media session (many players only ship a
 * Bitmap, not a URI). Scaled for widgets / RemoteViews parcel size.
 */
fun readSessionAlbumArtBitmap(context: Context, maxEdge: Int = 512): android.graphics.Bitmap? {
    val ctrl = resolveActiveMediaController(context) ?: return null
    val md = ctrl.metadata ?: return null
    val raw = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
        ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        ?: return null
    val edge = maxEdge.coerceAtLeast(64)
    if (raw.width <= edge && raw.height <= edge) return raw
    val scale = edge.toFloat() / maxOf(raw.width, raw.height).toFloat()
    val w = (raw.width * scale).toInt().coerceAtLeast(1)
    val h = (raw.height * scale).toInt().coerceAtLeast(1)
    return try {
        android.graphics.Bitmap.createScaledBitmap(raw, w, h, true)
    } catch (_: Exception) {
        raw
    }
}

/** In-process artist id → portrait URL cache for Control tab enrichment. */
private val controlArtistImageCache = HashMap<String, String>(48)

/**
 * Enrich a media-session snapshot with Spotify Web API art + deep-link URIs
 * (album background, artist thumbnail, clickable track/artist/album).
 *
 * Skips the network while the global Spotify cool-down is active so Control UI
 * / widgets do not dig a deeper 429 hole than Live DJ already has.
 */
fun enrichNowPlayingFromApi(context: Context, base: SpotifyNowPlaying): SpotifyNowPlaying {
    if (!SpotifyOAuth.isLoggedIn(context)) return base
    if (SpotifyOAuth.isRateLimited()) return base
    return try {
        val raw = SpotifyOAuth.api(context, "GET", "/v1/me/player/currently-playing", null)
        val o = JSONObject(raw)
        val status = o.optInt("status", 0)
        if (status == 204) return base
        if (!o.optBoolean("ok", false) && status !in listOf(200, 201, 202)) return base
        val bodyStr = o.optString("body", "")
        if (bodyStr.isBlank()) return base
        val data = runCatching { JSONObject(bodyStr) }.getOrNull() ?: return base
        if (!data.has("item") || data.isNull("item")) return base
        val item = data.getJSONObject("item")
        val title = item.optString("name", "").ifBlank { base.title }
        val trackUri = item.optString("uri", "").ifBlank { base.trackUri }
        val album = item.optJSONObject("album")
        val albumUri = album?.optString("uri", "").orEmpty().ifBlank {
            val id = album?.optString("id", "").orEmpty()
            if (id.isNotBlank()) "spotify:album:$id" else base.albumUri
        }
        val images = album?.optJSONArray("images")
        var albumArt = base.albumArtUrl
        if (images != null) {
            var best = ""
            var bestW = -1
            for (i in 0 until images.length()) {
                val im = images.optJSONObject(i) ?: continue
                val url = im.optString("url", "")
                if (url.isBlank()) continue
                val w = im.optInt("width", 0)
                if (w >= bestW) {
                    bestW = w
                    best = url
                }
            }
            if (best.isNotBlank()) albumArt = best
        }
        val artistsArr = item.optJSONArray("artists")
        val artistNames = ArrayList<String>()
        var artistUri = base.artistUri
        var artistId = ""
        if (artistsArr != null) {
            for (i in 0 until artistsArr.length()) {
                val a = artistsArr.optJSONObject(i) ?: continue
                val n = a.optString("name", "")
                if (n.isNotBlank()) artistNames.add(n)
                if (i == 0) {
                    artistUri = a.optString("uri", "").ifBlank {
                        val id = a.optString("id", "")
                        if (id.isNotBlank()) "spotify:artist:$id" else artistUri
                    }
                    artistId = a.optString("id", "")
                }
            }
        }
        val artistArt = if (artistId.isNotBlank()) {
            controlArtistImageCache[artistId] ?: run {
                val ar = SpotifyOAuth.api(context, "GET", "/v1/artists/$artistId", null)
                val ao = JSONObject(ar)
                val ab = ao.optString("body", "")
                val aj = if (ab.isNotBlank()) runCatching { JSONObject(ab) }.getOrNull() else null
                val imgs = aj?.optJSONArray("images")
                var best = ""
                var bestW = -1
                if (imgs != null) {
                    for (i in 0 until imgs.length()) {
                        val im = imgs.optJSONObject(i) ?: continue
                        val url = im.optString("url", "")
                        if (url.isBlank()) continue
                        val w = im.optInt("width", 0)
                        if (best.isBlank() || (w in 160..640 && w >= bestW) || w > bestW) {
                            bestW = w
                            best = url
                        }
                    }
                }
                if (best.isNotBlank()) controlArtistImageCache[artistId] = best
                best
            }
        } else base.artistArtUrl
        val playing = data.optBoolean("is_playing", base.isPlaying)
        val progress = data.optLong("progress_ms", base.positionMs)
        val duration = item.optLong("duration_ms", base.durationMs)
        base.copy(
            title = title,
            artist = artistNames.joinToString(", ").ifBlank { base.artist },
            isPlaying = playing,
            hasSession = true,
            positionMs = progress,
            durationMs = duration,
            albumArtUrl = albumArt,
            artistArtUrl = artistArt.ifBlank { base.artistArtUrl },
            trackUri = trackUri,
            albumUri = albumUri,
            artistUri = artistUri,
        )
    } catch (e: Exception) {
        Log.w(TAG, "enrichNowPlaying: ${e.message}")
        base
    }
}

/** Open a spotify: / open.spotify.com URI in the Spotify app (or store). */
fun openSpotifyContent(context: Context, uri: String?) {
    val u = uri?.trim().orEmpty()
    if (u.isBlank()) {
        openSpotifyApp(context)
        return
    }
    SpotifyOAuth.openContentUri(context, u)
}

/** Extract a Spotify track id from `spotify:track:…` or open.spotify.com track URLs. */
fun spotifyTrackIdFromUri(uri: String?): String {
    val u = uri?.trim().orEmpty()
    if (u.isBlank()) return ""
    return when {
        u.startsWith("spotify:track:") -> u.removePrefix("spotify:track:").substringBefore('?')
        u.contains("open.spotify.com/track/") ->
            u.substringAfter("open.spotify.com/track/").substringBefore('?').substringBefore('/')
        u.matches(Regex("^[A-Za-z0-9]{22}$")) -> u
        else -> ""
    }.trim()
}

/** Canonical `spotify:track:{id}` for library endpoints (Feb 2026+ Dev Mode). */
fun spotifyTrackUriCanonical(trackUri: String?): String {
    val id = spotifyTrackIdFromUri(trackUri)
    return if (id.isBlank()) "" else "spotify:track:$id"
}

/** trackId → (liked, fetchedAtMs). Widget + Control used to re-hit library every paint. */
private val likedSongsCache =
    object : LinkedHashMap<String, Pair<Boolean, Long>>(64, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Pair<Boolean, Long>>?,
        ): Boolean = size > 100
    }
private val likedCacheLock = Any()
private const val LIKED_CACHE_TTL_MS = 120_000L

/** Update in-process liked cache (optimistic heart toggles). */
fun rememberSpotifyTrackLiked(trackUri: String?, liked: Boolean) {
    val id = spotifyTrackIdFromUri(trackUri)
    if (id.isBlank()) return
    synchronized(likedCacheLock) {
        likedSongsCache[id] = liked to System.currentTimeMillis()
    }
}

/**
 * Whether [trackUri] is in the user's Liked Songs.
 * @return true/false when known, null on auth/network errors.
 */
fun checkSpotifyTrackLiked(context: Context, trackUri: String?): Boolean? {
    val id = spotifyTrackIdFromUri(trackUri)
    if (id.isBlank()) return null
    val map = checkSpotifyTracksLiked(context, listOf(trackUri.orEmpty()))
    return map[id]
}

/**
 * Batch Liked Songs lookup for many track URIs.
 * Prefer [GET /v1/me/library/contains] (≤40 URIs); fall back to legacy
 * [GET /v1/me/tracks/contains] (≤50 ids) for Extended Quota apps.
 * Keys in the result are Spotify track ids.
 *
 * Fresh results are cached ~2 minutes so controller ticks / widgets do not
 * burn library quota every second.
 */
fun checkSpotifyTracksLiked(context: Context, trackUris: List<String>): Map<String, Boolean> {
    if (!SpotifyOAuth.isLoggedIn(context)) return emptyMap()
    val ids = trackUris.map { spotifyTrackIdFromUri(it) }.filter { it.isNotBlank() }.distinct()
    if (ids.isEmpty()) return emptyMap()
    val now = System.currentTimeMillis()
    val out = LinkedHashMap<String, Boolean>(ids.size)
    val missing = ArrayList<String>()
    synchronized(likedCacheLock) {
        for (id in ids) {
            val hit = likedSongsCache[id]
            if (hit != null && now - hit.second < LIKED_CACHE_TTL_MS) {
                out[id] = hit.first
            } else {
                missing.add(id)
            }
        }
    }
    if (missing.isEmpty()) return out
    // During global cool-down return only what we already know — do not dig deeper.
    if (SpotifyOAuth.isRateLimited()) return out
    // New library endpoint hard-caps at 40 URIs.
    for (chunk in missing.chunked(40)) {
        try {
            val uris = chunk.map { "spotify:track:$it" }
            val encoded = uris.joinToString(",") {
                java.net.URLEncoder.encode(it, Charsets.UTF_8.name())
            }
            val raw = SpotifyOAuth.api(
                context,
                "GET",
                "/v1/me/library/contains?uris=$encoded",
                null,
            )
            val o = JSONObject(raw)
            val status = o.optInt("status", 0)
            if (status == 401 || status == 403 || status == 429) continue
            val body = o.optString("body", "").trim()
            if (o.optBoolean("ok", false) || status in listOf(200, 201)) {
                if (body.startsWith("[")) {
                    val arr = JSONArray(body)
                    val fetchedAt = System.currentTimeMillis()
                    synchronized(likedCacheLock) {
                        for (i in chunk.indices) {
                            if (i < arr.length()) {
                                val liked = arr.optBoolean(i)
                                out[chunk[i]] = liked
                                likedSongsCache[chunk[i]] = liked to fetchedAt
                            }
                        }
                    }
                    continue
                }
            }
            // Legacy fallback (Extended Quota still serves the old path).
            if (status in listOf(0, 404, 405, 410, 425) || !body.startsWith("[")) {
                val legacy = SpotifyOAuth.api(
                    context,
                    "GET",
                    "/v1/me/tracks/contains?ids=${chunk.joinToString(",")}",
                    null,
                )
                val lo = JSONObject(legacy)
                val lStatus = lo.optInt("status", 0)
                if (lStatus == 401 || lStatus == 403 || lStatus == 429) continue
                if (!lo.optBoolean("ok", false) && lStatus !in listOf(200, 201)) continue
                val lBody = lo.optString("body", "").trim()
                if (!lBody.startsWith("[")) continue
                val arr = JSONArray(lBody)
                val fetchedAt = System.currentTimeMillis()
                synchronized(likedCacheLock) {
                    for (i in chunk.indices) {
                        if (i < arr.length()) {
                            val liked = arr.optBoolean(i)
                            out[chunk[i]] = liked
                            likedSongsCache[chunk[i]] = liked to fetchedAt
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "check liked batch: ${e.message}")
        }
    }
    return out
}

/**
 * Add or remove [trackUri] from Liked Songs (library).
 * @return null on success, else a short error string for the UI.
 */
/** True when UI should offer in-place Spotify re-OAuth (new scopes / expired session). */
fun spotifyMsgNeedsReauth(msg: String?): Boolean {
    if (msg.isNullOrBlank()) return false
    val m = msg.lowercase()
    return m.contains("library permission") ||
        m.contains("reconnect") ||
        m.contains("re-authorize") ||
        m.contains("reauthorize") ||
        m.contains("session expired") ||
        m.contains("not logged") ||
        m.contains("connect spotify first") ||
        m.contains("insufficient") ||
        m.contains("scope")
}

/**
 * Parse SpotifyOAuth.api JSON envelope into a short UI error, or null on success.
 */
private fun spotifyLibraryResultError(raw: String, liked: Boolean): String? {
    val o = JSONObject(raw)
    val status = o.optInt("status", 0)
    val ok = o.optBoolean("ok", false) || status in listOf(200, 201, 204)
    if (ok) return null
    val err = o.optString("error", "").ifBlank { "HTTP $status" }
    return when {
        status == 403 || err.contains("scope", ignoreCase = true) ||
            err.contains("insufficient", ignoreCase = true) ->
            "Need library permission — tap Re-authorize Spotify"
        status == 401 -> "Spotify session expired — tap Re-authorize Spotify"
        status == 425 || err.contains("http_425", ignoreCase = true) ->
            "Spotify library API updated — retry after update, or Re-authorize Spotify"
        status == 429 -> "Spotify rate limit — try again in a moment"
        else -> err
    }
}

fun setSpotifyTrackLiked(context: Context, trackUri: String?, liked: Boolean): String? {
    val id = spotifyTrackIdFromUri(trackUri)
    if (id.isBlank()) return "No track to like"
    if (!SpotifyOAuth.isLoggedIn(context)) return "Connect Spotify first"
    val canonical = "spotify:track:$id"
    val encoded = java.net.URLEncoder.encode(canonical, Charsets.UTF_8.name())
    val method = if (liked) "PUT" else "DELETE"
    return try {
        // Feb 2026 Dev Mode: type-specific /me/tracks save/remove is gone.
        // Use generic library endpoints with full Spotify URIs (query `uris=`).
        // PUT may include a body for clients that prefer JSON; DELETE is query-only.
        val putBody = JSONObject().put("uris", JSONArray().put(canonical)).toString()
        val raw = SpotifyOAuth.api(
            context,
            method,
            "/v1/me/library?uris=$encoded",
            if (liked) putBody else null,
        )
        val first = spotifyLibraryResultError(raw, liked)
        if (first == null) {
            rememberSpotifyTrackLiked(canonical, liked)
            return null
        }

        val status = JSONObject(raw).optInt("status", 0)
        // Fall back for Extended Quota apps that still only accept the legacy path,
        // or if the new endpoint rejects the body shape.
        if (status in listOf(0, 400, 404, 405, 410, 415, 425)) {
            val legacyBody =
                if (liked) JSONObject().put("ids", JSONArray().put(id)).toString() else null
            val legacy = SpotifyOAuth.api(
                context,
                method,
                "/v1/me/tracks?ids=$id",
                legacyBody,
            )
            val second = spotifyLibraryResultError(legacy, liked)
            if (second == null) {
                rememberSpotifyTrackLiked(canonical, liked)
                return null
            }
            return second
        }
        first
    } catch (e: Exception) {
        Log.w(TAG, "set liked: ${e.message}")
        e.message ?: "like_failed"
    }
}

/**
 * Prefer session transport controls; fall back to media key events.
 *
 * When Live DJ is on, Next / Prev / Play-Pause go through the DJ service so the
 * radio UP NEXT stays aligned. Media-session skip alone advances Spotify’s stale
 * Up Next (append-only) and leaves the Live DJ queue several songs behind.
 */
fun dispatchMediaCommand(context: Context, action: String) {
    val appCtx = context.applicationContext
    if (SpotifyDjStore(appCtx).enabled) {
        when (action) {
            ACTION_NEXT -> {
                spotifyLiveDjSkip(appCtx, forceTalk = false)
                return
            }
            ACTION_PREV -> {
                spotifyLiveDjPrevious(appCtx)
                return
            }
            ACTION_PLAY_PAUSE -> {
                // Booth owns pause/resume so auto-handoff freezes/resumes correctly.
                // Implementation prefers media session, then empty Web API, then
                // re-plays the current track URI (same path as song pick).
                spotifyLiveDjPauseToggle(appCtx)
                return
            }
        }
    }
    val ctrl = resolveActiveMediaController(context)
    if (ctrl != null) {
        try {
            when (action) {
                ACTION_PREV -> {
                    ctrl.transportControls.skipToPrevious()
                    return
                }
                ACTION_NEXT -> {
                    ctrl.transportControls.skipToNext()
                    return
                }
                ACTION_PLAY_PAUSE -> {
                    val st = ctrl.playbackState?.state
                    if (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING) {
                        ctrl.transportControls.pause()
                    } else {
                        ctrl.transportControls.play()
                    }
                    return
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "transportControls failed: ${e.message}")
        }
    }
    // No active session (common after a long pause): try Web API resume/pause so
    // Play still works when media keys have nothing to target.
    if (action == ACTION_PLAY_PAUSE && SpotifyOAuth.isLoggedIn(appCtx)) {
        Thread {
            try {
                resumeOrPauseViaWebApi(appCtx)
            } catch (e: Exception) {
                Log.w(TAG, "web play/pause fallback: ${e.message}")
            }
        }.start()
        return
    }
    // System-wide media keys — works when Spotify (or other player) holds audio focus
    val key = when (action) {
        ACTION_PREV -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        ACTION_NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
        ACTION_PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        else -> return
    }
    val am = context.getSystemService(AudioManager::class.java) ?: return
    val now = SystemClock.uptimeMillis()
    am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0))
    am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0))
}

/**
 * When media session is gone, empty-body play/pause often still works; if not,
 * re-issue the last known track URI (same approach as Live DJ song pick).
 */
private fun resumeOrPauseViaWebApi(context: Context) {
    val appCtx = context.applicationContext
    // Prefer currently-playing snapshot for real is_playing
    var isPlaying = false
    var trackUri = ""
    try {
        val raw = SpotifyOAuth.api(appCtx, "GET", "/v1/me/player/currently-playing", null)
        val o = JSONObject(raw)
        val status = o.optInt("status", 0)
        val bodyStr = o.optString("body", "")
        if (status == 204 || bodyStr.isBlank()) {
            isPlaying = false
        } else if (o.optBoolean("ok", false) || status in listOf(200, 202)) {
            val data = runCatching { JSONObject(bodyStr) }.getOrNull()
            isPlaying = data?.optBoolean("is_playing", false) == true
            trackUri = data?.optJSONObject("item")?.optString("uri", "").orEmpty()
        }
    } catch (e: Exception) {
        Log.w(TAG, "currently-playing for resume: ${e.message}")
    }
    if (trackUri.isBlank()) {
        trackUri = SpotifyDjStore(appCtx).lastCurrentUri
    }
    if (isPlaying) {
        val res = SpotifyOAuth.api(appCtx, "PUT", "/v1/me/player/pause", "{}")
        Log.i(TAG, "web pause: $res")
        return
    }
    // Resume empty body first
    var res = SpotifyOAuth.api(appCtx, "PUT", "/v1/me/player/play", "{}")
    val o = runCatching { JSONObject(res) }.getOrNull()
    val status = o?.optInt("status", 0) ?: 0
    val ok = o?.optBoolean("ok", false) == true || status in listOf(200, 201, 202, 204)
    if (ok) {
        Log.i(TAG, "web empty resume ok")
        return
    }
    // Full URI play — works when empty resume fails (device idle / 429 recovery path)
    if (trackUri.isNotBlank()) {
        val body = JSONObject()
            .put("uris", JSONArray().put(trackUri))
            .put("offset", JSONObject().put("position", 0))
            .toString()
        val preferred = SpotifyControllerStore(appCtx).preferredDeviceId.trim()
        val path = if (preferred.isNotBlank()) {
            "/v1/me/player/play?device_id=${java.net.URLEncoder.encode(preferred, "UTF-8")}"
        } else {
            "/v1/me/player/play"
        }
        res = SpotifyOAuth.api(appCtx, "PUT", path, body)
        val o2 = runCatching { JSONObject(res) }.getOrNull()
        val st2 = o2?.optInt("status", 0) ?: 0
        val ok2 = o2?.optBoolean("ok", false) == true || st2 in listOf(200, 201, 202, 204)
        Log.i(TAG, "web uri resume ok=$ok2 status=$st2 uri=${trackUri.takeLast(22)}")
        if (!ok2 && st2 == 404) {
            SpotifyOAuth.openContentUri(appCtx, trackUri)
        }
        return
    }
    // Absolute last resort
    val am = appCtx.getSystemService(AudioManager::class.java) ?: return
    val now = SystemClock.uptimeMillis()
    am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
    am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
}

/**
 * Foreground service that pins a **live notification** with Previous / Play-Pause /
 * Next action buttons, album art, and progress.
 *
 * Samsung One UI forces traditional MediaStyle players into Now bar / AI boards, so
 * this card is deliberately a normal ongoing BigText notification (Live Update
 * promotion requested) that paints on the lock screen with transport buttons.
 *
 * **Always** owns the visible controls card while enabled — including when Live DJ
 * is armed. Live DJ keeps a quiet FGS on its own id. MediaSession is kept for
 * transport routing but is **not** attached to the notification.
 *
 * specialUse FGS: we control other apps' media, we are not a player.
 */
class SpotifyControllerService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    /** Only push home widgets when track / play state actually changes. */
    @Volatile private var lastWidgetSig: String = ""
    /** Skip nm.notify when shell+progress bucket unchanged (stops shade thrash). */
    @Volatile private var lastPostedSig: String = ""
    /** Last time we re-asserted startForeground (OEM demotion fight). */
    @Volatile private var lastPromoteMs: Long = 0L
    @Volatile private var lastArtTrackKey: String = ""
    @Volatile private var lastArt: SpotifyMediaNotif.ArtPair? = null
    @Volatile private var lastCtrlSessionSig: String = ""
    private var ctrlMediaSession: MediaSessionCompat? = null
    private var trackedController: MediaController? = null
    private val playbackCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            wakeRefresh()
        }

        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
            // Metadata changes = new track details; force a full notif rebuild.
            lastArt = null
            lastArtTrackKey = ""
            lastPostedSig = ""
            wakeRefresh()
        }
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            val now = refreshNotification()
            // Progress text only needs ~2s resolution; faster ticks spazz the shade.
            val delayMs = when {
                now?.isPlaying == true -> 2_000L
                now?.hasSession == true -> 30_000L
                else -> 90_000L
            }
            handler.postDelayed(this, delayMs)
        }
    }

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { _ ->
        wakeRefresh()
    }

    private val artReadyListener: (Context) -> Unit = { ctx ->
        if (ctx.packageName == packageName && SpotifyControllerStore(this).enabled) {
            // Art just landed — rebuild shade card + re-push MediaSession bitmaps
            // so lockscreen background updates (sig used to skip art-only changes).
            lastArt = null
            lastPostedSig = ""
            lastCtrlSessionSig = ""
            handler.post { refreshNotification() }
        }
    }

    /** Immediate notif refresh + reschedule tick (play just started, button tap, etc.). */
    private fun wakeRefresh() {
        handler.removeCallbacks(refreshRunnable)
        lastPostedSig = ""
        refreshNotification()
        handler.post(refreshRunnable)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ctrlServiceAlive = true
        SpotifyMediaNotif.setArtReadyListener(artReadyListener)
        try {
            val msm = getSystemService(MediaSessionManager::class.java)
            if (msm != null && isNotificationListenerEnabled(this)) {
                msm.addOnActiveSessionsChangedListener(
                    sessionListener,
                    ComponentName(this, GrokifyNotificationListener::class.java),
                    handler,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "session listener: ${e.message}")
        }
        handler.post(refreshRunnable)
    }

    /**
     * Promote to foreground with the first type that the OEM accepts.
     *
     * Order: **specialUse first** (we are a remote control, not an audio player) →
     * both → mediaPlayback alone → bare.
     *
     * mediaPlayback-first was killing the FGS on Android 14+: the system expects a
     * real playing MediaSession on *this* service, but Spotify holds audio focus and
     * Live DJ owns the session token — so the controller was demoted/killed and the
     * shade card vanished (“lost it again”).
     */
    private fun promoteForeground(notification: Notification): Boolean {
        ensureSpotifyCtrlChannel(this)
        val store = SpotifyControllerStore(this)
        if (Build.VERSION.SDK_INT < 29) {
            return try {
                startForeground(SPOTIFY_CTRL_NOTIF_ID, notification)
                store.recordStatus("fg_ok_legacy")
                true
            } catch (e: Exception) {
                store.recordStatus("fg_fail_legacy", e.message ?: "")
                false
            }
        }
        val types = mutableListOf<Int>()
        if (Build.VERSION.SDK_INT >= 34) {
            types += ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            types += (
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            types += ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            // API 29–33: type is optional; 0 = use manifest default.
            types += 0
            types += ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        var lastErr: String = ""
        for (t in types) {
            try {
                if (t == 0) {
                    startForeground(SPOTIFY_CTRL_NOTIF_ID, notification)
                } else {
                    ServiceCompat.startForeground(this, SPOTIFY_CTRL_NOTIF_ID, notification, t)
                }
                store.recordStatus("fg_ok_type=$t")
                Log.i(TAG, "startForeground ok type=$t id=$SPOTIFY_CTRL_NOTIF_ID")
                return true
            } catch (e: Exception) {
                lastErr = "type=$t ${e.javaClass.simpleName}:${e.message}"
                Log.e(TAG, "startForeground failed $lastErr", e)
            }
        }
        // Non-FGS shade post — better than nothing; may be less sticky.
        return try {
            getSystemService(NotificationManager::class.java)
                ?.notify(SPOTIFY_CTRL_NOTIF_ID, notification)
            store.recordStatus("fg_fail_notify_only", lastErr)
            true
        } catch (e: Exception) {
            store.recordStatus("fg_fail_all", lastErr + " / " + (e.message ?: ""))
            false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!SpotifyControllerStore(this).enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 1) startForeground FIRST with a bare card — no session / art work before this.
        //    Violating the ~5s FGS contract kills us with no notification on Samsung.
        val bootstrap = SpotifyMediaNotif.buildMinimal(
            this,
            SpotifyNowPlaying(title = "Spotify Controller", artist = "Prev · Play · Next"),
        )
        val promoted = promoteForeground(bootstrap)
        if (!promoted) {
            Log.e(TAG, "could not promote foreground at all")
        }

        // 2) Now safe to resolve session / art and upgrade the card.
        val now = runCatching { nowPlayingForNotification(this) }.getOrNull()
            ?: SpotifyNowPlaying(title = "Spotify Controller", artist = "Prev · Play · Next")
        val token = runCatching { resolveSessionToken(now) }.getOrNull()
        val (n, postSig) = runCatching { buildNotificationWithSig(now) }
            .getOrElse {
                Log.e(TAG, "buildNotification failed: ${it.message}", it)
                SpotifyMediaNotif.buildMinimal(this, now, sessionToken = token) to "minimal"
            }
        lastPostedSig = postSig
        // Upgrade FGS notification (same id) + re-assert via NM for OEMs that
        // hide FGS-only media cards from the expanded shade.
        promoteForeground(n)
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(SPOTIFY_CTRL_NOTIF_ID, n)
        }
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, 400L)
        return START_STICKY
    }

    override fun onDestroy() {
        ctrlServiceAlive = false
        handler.removeCallbacks(refreshRunnable)
        SpotifyMediaNotif.setArtReadyListener(null)
        untrackController()
        releaseCtrlMediaSession()
        try {
            getSystemService(MediaSessionManager::class.java)
                ?.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun untrackController() {
        try {
            trackedController?.unregisterCallback(playbackCallback)
        } catch (_: Exception) {
        }
        trackedController = null
    }

    private fun trackActiveController() {
        if (!isNotificationListenerEnabled(this)) {
            untrackController()
            return
        }
        val ctrl = try {
            resolveActiveMediaController(this)
        } catch (_: Exception) {
            null
        }
        if (ctrl === trackedController) return
        untrackController()
        if (ctrl == null) return
        try {
            ctrl.registerCallback(playbackCallback, handler)
            trackedController = ctrl
        } catch (e: Exception) {
            Log.w(TAG, "register playback callback: ${e.message}")
        }
    }

    /**
     * Always own a MediaSession on **this** service for MediaStyle + lockscreen.
     * Transport routes through [dispatchMediaCommand] (Live DJ when booth is on,
     * else Spotify session / media keys). Do **not** borrow Live DJ’s token —
     * a foreign service token + mediaPlayback FGS is how the card kept vanishing.
     */
    private fun resolveSessionToken(now: SpotifyNowPlaying): MediaSessionCompat.Token? {
        ensureCtrlMediaSession()
        syncCtrlMediaSession(now)
        return try {
            ctrlMediaSession?.sessionToken
        } catch (_: Exception) {
            null
        }
    }

    private fun ensureCtrlMediaSession() {
        if (ctrlMediaSession != null) return
        val session = MediaSessionCompat(this, "GrokifySpotifyCtrl").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            setCallback(
                object : MediaSessionCompat.Callback() {
                    override fun onPlay() {
                        dispatchMediaCommand(this@SpotifyControllerService, ACTION_PLAY_PAUSE)
                        wakeRefresh()
                    }

                    override fun onPause() {
                        dispatchMediaCommand(this@SpotifyControllerService, ACTION_PLAY_PAUSE)
                        wakeRefresh()
                    }

                    override fun onSkipToNext() {
                        dispatchMediaCommand(this@SpotifyControllerService, ACTION_NEXT)
                        wakeRefresh()
                    }

                    override fun onSkipToPrevious() {
                        dispatchMediaCommand(this@SpotifyControllerService, ACTION_PREV)
                        wakeRefresh()
                    }

                    override fun onStop() {
                        dispatchMediaCommand(this@SpotifyControllerService, ACTION_PLAY_PAUSE)
                        wakeRefresh()
                    }
                },
                handler,
            )
            val openApp = PendingIntent.getActivity(
                this@SpotifyControllerService,
                47020,
                Intent(this@SpotifyControllerService, MainActivity::class.java).apply {
                    putExtra("open_app", "spotify_controller")
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            setSessionActivity(openApp)
            // Cold-start media buttons → receiver → re-start service.
            val mbr = PendingIntent.getBroadcast(
                this@SpotifyControllerService,
                47021,
                Intent(Intent.ACTION_MEDIA_BUTTON).setClass(
                    this@SpotifyControllerService,
                    SpotifyControllerReceiver::class.java,
                ),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            setMediaButtonReceiver(mbr)
        }
        ctrlMediaSession = session
        Log.i(TAG, "ctrl MediaSession created")
    }

    private fun syncCtrlMediaSession(now: SpotifyNowPlaying) {
        val session = ctrlMediaSession ?: return
        val title = now.title.ifBlank { "Spotify Controller" }
        val artist = now.artist.ifBlank { "Prev · Play · Next" }
        val playing = now.isPlaying
        val pos = now.positionMs.coerceAtLeast(0L)
        val dur = now.durationMs.coerceAtLeast(0L)
        val artBmp = SpotifyMediaNotif.bestSessionArt(lastArt)
        val artSig = when {
            artBmp != null -> "art${artBmp.width}x${artBmp.height}"
            now.albumArtUrl.isNotBlank() -> "url"
            else -> "noart"
        }
        val sig = "${now.trackUri}|$playing|${pos / 2_000L}|$title|$artist|$dur|$artSig"
        if (sig == lastCtrlSessionSig && session.isActive) return
        lastCtrlSessionSig = sig
        try {
            val meta = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, now.trackUri)
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI, now.trackUri)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, dur)
            if (now.albumArtUrl.isNotBlank()) {
                meta.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, now.albumArtUrl)
                meta.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, now.albumArtUrl)
                meta.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, now.albumArtUrl)
            }
            // Embed large cover — SystemUI lockscreen media uses these bitmaps as background.
            artBmp?.let {
                meta.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
                meta.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
                meta.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
            }
            session.setMetadata(meta.build())
            val actions =
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_STOP
            val state = when {
                playing -> PlaybackStateCompat.STATE_PLAYING
                now.hasSession || now.title.isNotBlank() -> PlaybackStateCompat.STATE_PAUSED
                else -> PlaybackStateCompat.STATE_STOPPED
            }
            session.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(actions)
                    .setState(
                        state,
                        pos,
                        if (playing) 1.0f else 0f,
                        SystemClock.elapsedRealtime(),
                    )
                    .build(),
            )
            if (!session.isActive) {
                session.isActive = true
                Log.i(TAG, "ctrl MediaSession active")
            }
        } catch (e: Exception) {
            Log.w(TAG, "syncCtrlMediaSession: ${e.message}")
        }
    }

    private fun releaseCtrlMediaSession() {
        runCatching {
            ctrlMediaSession?.apply {
                isActive = false
                setCallback(null)
                release()
            }
        }
        ctrlMediaSession = null
        lastCtrlSessionSig = ""
    }

    /** @return latest snapshot (for refresh scheduling), or null if stopped. */
    private fun refreshNotification(): SpotifyNowPlaying? {
        if (!SpotifyControllerStore(this).enabled) {
            stopSelf()
            return null
        }
        trackActiveController()
        val now = nowPlayingForNotification(this)
        resolveSessionToken(now)
        val nm = getSystemService(NotificationManager::class.java) ?: return now
        val (n, postSig) = runCatching { buildNotificationWithSig(now) }
            .getOrElse {
                Log.e(TAG, "refresh build failed: ${it.message}", it)
                val token = resolveSessionToken(now)
                SpotifyMediaNotif.buildMinimal(this, now, sessionToken = token) to "minimal-refresh"
            }
        val missing = !isSpotifyControllerNotificationPosted(this)
        val nowElapsed = SystemClock.elapsedRealtime()
        // Re-assert FGS periodically — Samsung demotes mediaPlayback/specialUse cards
        // and clears them without killing the process; sig-only skip left us silent.
        val duePromote = missing ||
            postSig != lastPostedSig ||
            (nowElapsed - lastPromoteMs) > 45_000L
        if (duePromote) {
            lastPostedSig = postSig
            lastPromoteMs = nowElapsed
            val ok = promoteForeground(n)
            if (!ok) {
                runCatching { nm.notify(SPOTIFY_CTRL_NOTIF_ID, n) }
                    .onFailure {
                        Log.e(TAG, "notify failed, minimal: ${it.message}")
                        val token = resolveSessionToken(now)
                        nm.notify(
                            SPOTIFY_CTRL_NOTIF_ID,
                            SpotifyMediaNotif.buildMinimal(this, now, sessionToken = token),
                        )
                    }
            } else {
                // Also NM-notify so shade shows even when FGS is deprioritized.
                runCatching { nm.notify(SPOTIFY_CTRL_NOTIF_ID, n) }
            }
            if (missing) {
                Log.w(TAG, "controller notif was missing — re-promoted")
                SpotifyControllerStore(this).recordStatus("reposted_missing")
            }
        }
        // Progress ticks must NOT re-enrich Spotify. Widgets only on track/play change.
        val sig = listOf(
            now.trackUri,
            now.title,
            now.artist,
            now.isPlaying,
            now.hasSession,
        ).joinToString("|")
        if (sig != lastWidgetSig) {
            lastWidgetSig = sig
            io.grokify.os.widgets.GrokifyWidgets.refreshSpotify(this)
        }
        return now
    }

    private fun buildNotificationWithSig(now: SpotifyNowPlaying): Pair<Notification, String> {
        // Always post MediaStyle transport. Live DJ keeps a separate quiet FGS and
        // does not steal this card.
        val djBus = SpotifyDjBus.state.value
        val onAir = now.isPlaying
        val queue = if (SpotifyDjStore(this).enabled) djBus.queue else emptyList()
        val queueSig = queue.take(5).joinToString(",") { it.uri }

        // Prefer real track metadata whenever we have a title — lockscreen + shade both
        // read title/artist from the notification (and MediaSession mirror).
        val hasRealTrack = now.title.isNotBlank() && now.title != "Unknown track"
        val display = when {
            onAir && hasRealTrack -> now.copy(isPlaying = true)
            onAir && !hasRealTrack -> now.copy(
                isPlaying = true,
                title = now.title.ifBlank { "Playing" },
                artist = now.artist.ifBlank { now.appLabel.ifBlank { "Spotify" } },
            )
            hasRealTrack -> now.copy(isPlaying = now.isPlaying)
            now.hasSession -> now.copy(
                isPlaying = false,
                title = now.title.ifBlank { "Spotify" },
                artist = now.artist.ifBlank { "Controls ready" },
            )
            else -> SpotifyNowPlaying(
                title = "Spotify Controller",
                artist = "Controls ready · prev / play / next",
                appLabel = now.appLabel,
                packageName = now.packageName,
                isPlaying = false,
                hasSession = now.hasSession,
            )
        }

        val trackKey = listOf(
            display.trackUri,
            display.title,
            display.artist,
            display.albumArtUrl,
            queueSig,
            display.isPlaying,
            onAir,
        ).joinToString("|")
        if (trackKey != lastArtTrackKey) {
            lastArt = null
            lastArtTrackKey = trackKey
        }
        // Bucket progress to 2s so we don't rebuild every second for a 1s clock.
        val progressBucket = display.positionMs / 2_000L
        val art = when {
            !onAir && !display.hasSession -> null
            lastArt == null ->
                SpotifyMediaNotif.resolveArt(this, display, kickNetwork = onAir || display.hasSession)
                    .also { lastArt = it }
            lastArt?.album == null &&
                (display.albumArtUrl.isNotBlank() || display.trackUri.isNotBlank()) ->
                SpotifyMediaNotif.resolveArt(this, display, kickNetwork = onAir || display.hasSession)
                    .also { lastArt = it }
            else -> lastArt
        }
        val sub = when {
            SpotifyDjStore(this).enabled && onAir -> "Live DJ"
            display.appLabel.isNotBlank() -> display.appLabel
            else -> "GrokifyOS"
        }
        val token = resolveSessionToken(display)
        // Keep mirror session metadata (title/artist/art) in lockstep with the card.
        runCatching { syncCtrlMediaSession(display) }
        val n = SpotifyMediaNotif.buildPlaying(
            context = this,
            now = display,
            queue = queue,
            subText = sub,
            art = art,
            sessionToken = token,
        )
        val finalSig = listOf(
            trackKey,
            progressBucket.toString(),
            (display.durationMs / 1000L).toString(),
            if (art?.sessionArt != null || art?.album != null) "art" else "noart",
            if (token != null) "tok" else "notok",
        ).joinToString("|")
        return n to finalSig
    }
}

/** Handles notification action taps and boot re-arm. */
class SpotifyControllerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            ACTION_PREV, ACTION_PLAY_PAUSE, ACTION_NEXT -> {
                dispatchMediaCommand(context, action)
                if (SpotifyControllerStore(context).enabled) {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, SpotifyControllerService::class.java),
                    )
                }
                io.grokify.os.widgets.GrokifyWidgets.refreshSpotify(context)
            }
            ACTION_LIKE_TOGGLE -> {
                val pending = goAsync()
                Thread {
                    try {
                        val appCtx = context.applicationContext
                        var now = readNowPlaying(appCtx)
                        if (SpotifyOAuth.isLoggedIn(appCtx)) {
                            now = enrichNowPlayingFromApi(appCtx, now)
                        }
                        val uri = now.trackUri
                        if (uri.isNotBlank()) {
                            val currently = checkSpotifyTrackLiked(appCtx, uri) == true
                            setSpotifyTrackLiked(appCtx, uri, !currently)
                        }
                        io.grokify.os.widgets.GrokifyWidgets.refreshSpotify(appCtx)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
            ACTION_MORE_LIKE -> {
                val appCtx = context.applicationContext
                var now = readNowPlaying(appCtx)
                if (SpotifyOAuth.isLoggedIn(appCtx)) {
                    now = enrichNowPlayingFromApi(appCtx, now)
                }
                if (now.trackUri.isNotBlank() || now.title.isNotBlank()) {
                    spotifyLiveDjMoreLikeThis(
                        appCtx,
                        trackUri = now.trackUri,
                        name = now.title,
                        artists = now.artist,
                        artistUri = now.artistUri,
                        albumArtUrl = now.albumArtUrl,
                    )
                }
                io.grokify.os.widgets.GrokifyWidgets.refreshSpotify(appCtx)
            }
            ACTION_PLAY_URI -> {
                val appCtx = context.applicationContext
                val uri = intent.getStringExtra(EXTRA_TRACK_URI).orEmpty()
                if (uri.isNotBlank()) {
                    spotifyLiveDjPlayUri(
                        appCtx,
                        trackUri = uri,
                        name = intent.getStringExtra(EXTRA_TRACK_NAME).orEmpty(),
                        artists = intent.getStringExtra(EXTRA_TRACK_ARTISTS).orEmpty(),
                        albumArtUrl = intent.getStringExtra(EXTRA_ALBUM_ART).orEmpty(),
                        artistArtUrl = intent.getStringExtra(EXTRA_ARTIST_ART).orEmpty(),
                        albumUri = intent.getStringExtra(EXTRA_ALBUM_URI).orEmpty(),
                        artistUri = intent.getStringExtra(EXTRA_ARTIST_URI).orEmpty(),
                    )
                }
                io.grokify.os.widgets.GrokifyWidgets.refreshSpotify(appCtx)
            }
            ACTION_LIKE_URI -> {
                val pending = goAsync()
                Thread {
                    try {
                        val appCtx = context.applicationContext
                        val uri = intent.getStringExtra(EXTRA_TRACK_URI).orEmpty()
                        if (uri.isNotBlank()) {
                            val currently = checkSpotifyTrackLiked(appCtx, uri) == true
                            setSpotifyTrackLiked(appCtx, uri, !currently)
                        }
                        io.grokify.os.widgets.GrokifyWidgets.refreshSpotify(appCtx)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
            ACTION_MORE_LIKE_URI -> {
                val appCtx = context.applicationContext
                val uri = intent.getStringExtra(EXTRA_TRACK_URI).orEmpty()
                val name = intent.getStringExtra(EXTRA_TRACK_NAME).orEmpty()
                val artists = intent.getStringExtra(EXTRA_TRACK_ARTISTS).orEmpty()
                if (uri.isNotBlank() || name.isNotBlank() || artists.isNotBlank()) {
                    spotifyLiveDjMoreLikeThis(
                        appCtx,
                        trackUri = uri,
                        name = name,
                        artists = artists,
                        artistUri = intent.getStringExtra(EXTRA_ARTIST_URI).orEmpty(),
                        albumArtUrl = intent.getStringExtra(EXTRA_ALBUM_ART).orEmpty(),
                    )
                }
                io.grokify.os.widgets.GrokifyWidgets.refreshSpotify(appCtx)
            }
            ACTION_DJ_TOGGLE -> {
                val appCtx = context.applicationContext
                val store = SpotifyDjStore(appCtx)
                val next = !store.enabled
                if (next && !SpotifyOAuth.isLoggedIn(appCtx)) {
                    // Can't start booth without Spotify account — leave off.
                    SpotifyDjBus.patch {
                        it.copy(status = "Connect Spotify first (Account)", error = "not_logged_in")
                    }
                } else {
                    setSpotifyLiveDjEnabled(appCtx, next)
                }
                io.grokify.os.widgets.GrokifyWidgets.refreshSpotify(appCtx)
            }
            ACTION_WIDGET_REFRESH -> {
                io.grokify.os.widgets.GrokifyWidgets.forceRefreshSpotify(context)
            }
            ACTION_STOP -> setSpotifyControllerEnabled(context, false)
            Intent.ACTION_MEDIA_BUTTON -> {
                val ke = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                } ?: return
                if (ke.action != KeyEvent.ACTION_DOWN || ke.repeatCount != 0) return
                val mapped = when (ke.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_NEXT,
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                    -> ACTION_NEXT
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                    KeyEvent.KEYCODE_MEDIA_REWIND,
                    -> ACTION_PREV
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_HEADSETHOOK,
                    KeyEvent.KEYCODE_MEDIA_PLAY,
                    KeyEvent.KEYCODE_MEDIA_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_STOP,
                    -> ACTION_PLAY_PAUSE
                    else -> return
                }
                dispatchMediaCommand(context, mapped)
                if (SpotifyControllerStore(context).enabled) {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, SpotifyControllerService::class.java),
                    )
                }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (SpotifyControllerStore(context).enabled) {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, SpotifyControllerService::class.java),
                    )
                }
                io.grokify.os.widgets.GrokifyWidgets.refreshAll(context)
            }
        }
    }
}

@Composable
fun SpotifyControllerPane(
    onBack: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    val context = LocalContext.current
    val appCtx = context.applicationContext
    val store = remember { SpotifyControllerStore(appCtx) }
    val djStore = remember { SpotifyDjStore(appCtx) }
    val scope = rememberCoroutineScope()

    var tab by remember {
        mutableStateOf(io.grokify.os.widgets.WidgetNav.consumeSpotifyTab() ?: 0)
    } // 0 control, 1 live dj, 2 build, 3 account

    var enabled by remember { mutableStateOf(store.enabled) }
    var now by remember { mutableStateOf(readNowPlaying(appCtx)) }
    var listenerOk by remember { mutableStateOf(isNotificationListenerEnabled(appCtx)) }
    var spotifyOk by remember { mutableStateOf(isSpotifyInstalled(appCtx)) }
    var notifPosted by remember { mutableStateOf(isSpotifyControllerNotificationPosted(appCtx)) }
    val notifOk = PermissionHelper.status(appCtx, AppPermissionId.NOTIFICATIONS).granted ||
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

    val djState by SpotifyDjBus.state.collectAsState()
    var clientId by remember {
        mutableStateOf(HostApiKeyStore.getValue(appCtx, ApiKeyIds.SPOTIFY_CLIENT_ID).orEmpty())
    }
    var authMsg by remember { mutableStateOf(SpotifyOAuth.lastAuthMessage.orEmpty()) }
    var loggedIn by remember { mutableStateOf(SpotifyOAuth.isLoggedIn(appCtx)) }
    var busy by remember { mutableStateOf(false) }
    var hasXaiKey by remember {
        mutableStateOf(!HostApiKeyStore.getValue(appCtx, ApiKeyIds.SPACEXAI).isNullOrBlank())
    }
    var djChatDraft by remember { mutableStateOf("") }
    /** 0 = Chat, 1 = Queue, 2 = Blocks, 3 = Settings (inner Live DJ tabs) */
    var djSubTab by remember { mutableStateOf(0) }
    val djChatListState = rememberLazyListState()

    // Restore chat/queue + re-arm service after OTA/process death when resume is on.
    LaunchedEffect(Unit) {
        ensureDjChatHydrated(appCtx)
        maybeResumeLiveDj(appCtx)
    }

    // Research / build / edit playlist
    var researchPrompt by remember { mutableStateOf("") }
    var lastResearch by remember { mutableStateOf<SpotifyPlaylistAi.ResearchResult?>(null) }
    var researchOut by remember { mutableStateOf("") }
    var workStep by remember { mutableStateOf<String?>(null) }
    var buildMsg by remember { mutableStateOf<String?>(null) }
    var buildOk by remember { mutableStateOf(false) }
    var playlists by remember { mutableStateOf<List<SpotifyPlaylistAi.PlaylistRef>>(emptyList()) }
    var selectedPlaylistId by remember { mutableStateOf<String?>(null) }
    var editPrompt by remember { mutableStateOf("") }
    var lastEdit by remember { mutableStateOf<SpotifyPlaylistAi.EditPlan?>(null) }
    var editOut by remember { mutableStateOf("") }
    var playlistLoadMsg by remember { mutableStateOf<String?>(null) }

    // Playback devices (Control tab)
    var devices by remember { mutableStateOf<List<SpotifyPlaybackDevice>>(emptyList()) }
    var devicesMsg by remember { mutableStateOf<String?>(null) }
    var devicesLoading by remember { mutableStateOf(false) }
    var devicesTransferringId by remember { mutableStateOf<String?>(null) }
    var preferredDeviceId by remember { mutableStateOf(store.preferredDeviceId) }
    var androidAutoEnabled by remember { mutableStateOf(store.androidAutoEnabled) }

    // Liked Songs heart (Control + Live DJ chat tracks, including past cuts)
    var trackLiked by remember { mutableStateOf(false) }
    var trackLikedBusy by remember { mutableStateOf(false) }
    var trackLikedMsg by remember { mutableStateOf<String?>(null) }

    /** Pending multi-select dislike modal target (Control transport or chat bubble). */
    var dislikeTarget by remember { mutableStateOf<DislikeTarget?>(null) }
    /** Bumps when a dislike is saved so chat bubble thumbs re-tint. */
    var dislikeRevision by remember { mutableStateOf(0) }

    // Sticky art models live above the tab switcher so Control re-entry keeps cover.
    var stickyAlbumArt by remember { mutableStateOf<Any?>(null) }
    var stickyArtistArt by remember { mutableStateOf<Any?>(null) }
    var stickyArtTrackKey by remember { mutableStateOf("") }
    val trackArtKey = now.trackUri.ifBlank { "${now.title}|${now.artist}" }
    LaunchedEffect(now.albumArtUrl, now.artistArtUrl, trackArtKey, now.hasSession, now.isPlaying) {
        // Always pin media-session bitmap to disk — URL-less sessions (and dying
        // content:// URIs) otherwise flash once then leave the card empty.
        if (now.hasSession && now.trackUri.isNotBlank()) {
            val sessionKey = "session:${now.trackUri}"
            if (SpotifyArtMirror.localFile(appCtx, sessionKey) == null) {
                withContext(Dispatchers.IO) {
                    readSessionAlbumArtBitmap(appCtx, maxEdge = 512)?.let { bmp ->
                        SpotifyArtMirror.mirrorBitmap(appCtx, sessionKey, bmp)
                    }
                }
            }
        }
        val albumModel = controlArtModel(appCtx, now.albumArtUrl, now.trackUri)
        val artistModel = controlArtModel(
            appCtx,
            now.artistArtUrl.ifBlank { now.albumArtUrl },
            now.trackUri,
        )
        if (trackArtKey != stickyArtTrackKey) {
            val prevKey = stickyArtTrackKey
            stickyArtTrackKey = trackArtKey
            // title|artist → spotify:track:… is the same cut; only wipe when two
            // concrete track URIs disagree (skip / next).
            val differentTrackUris =
                prevKey.startsWith("spotify:track:") &&
                    trackArtKey.startsWith("spotify:track:") &&
                    prevKey != trackArtKey
            if (albumModel != null) {
                stickyAlbumArt = albumModel
            } else if (differentTrackUris) {
                stickyAlbumArt = null
            }
            if (artistModel != null) {
                stickyArtistArt = artistModel
            } else if (differentTrackUris) {
                stickyArtistArt = null
            }
        } else {
            // Same track: only upgrade, never wipe on a blank poll / dead content://.
            if (albumModel != null) stickyAlbumArt = albumModel
            if (artistModel != null) stickyArtistArt = artistModel
        }
        // If still blank, re-check after mirror (CDN / session) may have landed.
        if (stickyAlbumArt == null && now.trackUri.isNotBlank() && now.hasSession) {
            withContext(Dispatchers.IO) {
                readSessionAlbumArtBitmap(appCtx, maxEdge = 512)?.let { bmp ->
                    SpotifyArtMirror.mirrorBitmap(appCtx, "session:${now.trackUri}", bmp)
                }
            }
            controlArtModel(appCtx, now.albumArtUrl, now.trackUri)?.let {
                stickyAlbumArt = it
            }
        }
    }

    // Clear sticky "Finding more like this…" / dislike pending once the service finishes.
    LaunchedEffect(djState.status, djState.messages.lastOrNull()?.id) {
        val pendingMore = trackLikedMsg?.startsWith("Finding more") == true
        val pendingDislike = trackLikedMsg?.startsWith("Saving dislike") == true
        if (!pendingMore && !pendingDislike) return@LaunchedEffect
        val st = djState.status
        val lastSys = djState.messages.lastOrNull { it.role == DjChatRole.System }?.text.orEmpty()
        val doneMore = st.startsWith("More like") ||
            st.contains("More like this failed", ignoreCase = true) ||
            lastSys.startsWith("More like") ||
            lastSys.contains("More like this", ignoreCase = true)
        val doneDislike = st.startsWith("Disliked") ||
            lastSys.contains("Disliked") ||
            lastSys.startsWith("👎")
        if ((pendingMore && doneMore) || (pendingDislike && doneDislike)) {
            trackLikedMsg = when {
                pendingDislike && doneDislike ->
                    st.takeIf { it.startsWith("Disliked") }
                        ?: lastSys.removePrefix("👎 ").trim().ifBlank { "Dislike saved" }
                else -> null
            }
        }
    }
    var likedCheckUri by remember { mutableStateOf("") }
    /** Spotify track-id → in Liked Songs (chat history hearts). */
    var likedByTrackId by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    /** Track URI currently toggling like (busy spinner on that bubble). */
    var likeBusyUri by remember { mutableStateOf("") }

    val vibeChips = remember {
        listOf(
            "Sunset rooftop chill: soft R&B, lo-fi edges, warm bass, 80–95 BPM" to "Rooftop",
            "Gym aggression: modern trap + metalcore drops, high energy, clean structure" to "Gym",
            "Focus deep work: instrumental only, minimal lyrics, ambient electronic" to "Focus",
            "Party starter 2020s hits + timeless sing-alongs, upbeat" to "Party",
        )
    }

    LaunchedEffect(enabled) {
        // Media session is free; Web API enrich only on track change or every 45s.
        // Sticky merge (nowPlayingForNotification) keeps art across blank session polls.
        var lastApiUri = ""
        var lastApiAt = 0L
        var cachedApi: SpotifyNowPlaying? = null
        while (true) {
            // DJ-aware + sticky art/title (session alone often drops albumArtUrl after 1 tick).
            val snap = nowPlayingForNotification(appCtx)
            val enriched = if (SpotifyOAuth.isLoggedIn(appCtx) && (snap.hasSession || loggedIn)) {
                val age = System.currentTimeMillis() - lastApiAt
                val wantApi = !SpotifyOAuth.isRateLimited() &&
                    (
                        snap.trackUri != lastApiUri ||
                            (snap.trackUri.isNotBlank() && age > 45_000L) ||
                            (lastApiAt == 0L && snap.hasSession) ||
                            (snap.albumArtUrl.isBlank() && snap.hasSession && age > 8_000L)
                        )
                if (wantApi) {
                    withContext(Dispatchers.IO) { enrichNowPlayingFromApi(appCtx, snap) }.also {
                        lastApiUri = it.trackUri.ifBlank { snap.trackUri }
                        lastApiAt = System.currentTimeMillis()
                        cachedApi = it
                    }
                } else {
                    val c = cachedApi
                    if (c != null &&
                        (c.trackUri == snap.trackUri || snap.trackUri.isBlank())
                    ) {
                        snap.copy(
                            albumArtUrl = snap.albumArtUrl.ifBlank { c.albumArtUrl },
                            artistArtUrl = snap.artistArtUrl.ifBlank { c.artistArtUrl },
                            trackUri = snap.trackUri.ifBlank { c.trackUri },
                            albumUri = snap.albumUri.ifBlank { c.albumUri },
                            artistUri = snap.artistUri.ifBlank { c.artistUri },
                        )
                    } else {
                        snap
                    }
                }
            } else {
                snap
            }
            // Never let a blank poll wipe last-good art for the same cut.
            now = SpotifyNowPlayingSticky.remember(enriched)
            listenerOk = isNotificationListenerEnabled(appCtx)
            spotifyOk = isSpotifyInstalled(appCtx)
            notifPosted = isSpotifyControllerNotificationPosted(appCtx)
            // Soft ensure only — never stopService (that was thrashing the FGS).
            if (enabled && store.enabled && !notifPosted) {
                ensureSpotifyControllerRunning(appCtx, force = false)
            }
            // Control tab: only poll actively while playing; idle when paused.
            delay(if (now.isPlaying) 1_500L else 12_000L)
        }
    }

    // Keep Liked Songs heart in sync with the current track (Control / DJ chat).
    val likeTrackUri = remember(now.trackUri, djState.messages) {
        now.trackUri.ifBlank {
            djState.messages.lastOrNull { it.role == DjChatRole.Track && it.isNowPlaying }
                ?.trackUri.orEmpty()
        }
    }
    LaunchedEffect(likeTrackUri, loggedIn) {
        if (likeTrackUri.isBlank() || !loggedIn) {
            trackLiked = false
            likedCheckUri = ""
            return@LaunchedEffect
        }
        if (likeTrackUri == likedCheckUri) return@LaunchedEffect
        val liked = withContext(Dispatchers.IO) {
            checkSpotifyTrackLiked(appCtx, likeTrackUri)
        }
        if (liked != null) {
            trackLiked = liked
            likedCheckUri = likeTrackUri
            trackLikedMsg = null
            val id = spotifyTrackIdFromUri(likeTrackUri)
            if (id.isNotBlank()) {
                likedByTrackId = likedByTrackId + (id to liked)
            }
        }
    }

    // Batch-check Liked status for past (and current) track bubbles in DJ chat.
    val chatTrackUrisKey = remember(djState.messages) {
        djState.messages
            .asSequence()
            .filter { it.role == DjChatRole.Track }
            .mapNotNull { it.trackUri?.takeIf { u -> u.isNotBlank() } }
            .distinct()
            .joinToString("\n")
    }
    LaunchedEffect(chatTrackUrisKey, loggedIn) {
        if (!loggedIn || chatTrackUrisKey.isBlank()) return@LaunchedEffect
        val uris = chatTrackUrisKey.lineSequence().filter { it.isNotBlank() }.toList()
        val missing = uris.filter { uri ->
            val id = spotifyTrackIdFromUri(uri)
            id.isNotBlank() && !likedByTrackId.containsKey(id)
        }
        if (missing.isEmpty()) return@LaunchedEffect
        val found = withContext(Dispatchers.IO) {
            checkSpotifyTracksLiked(appCtx, missing)
        }
        if (found.isNotEmpty()) {
            likedByTrackId = likedByTrackId + found
        }
    }

    LaunchedEffect(tab) {
        while (true) {
            loggedIn = SpotifyOAuth.isLoggedIn(appCtx)
            authMsg = SpotifyOAuth.lastAuthMessage.orEmpty()
            hasXaiKey = !HostApiKeyStore.getValue(appCtx, ApiKeyIds.SPACEXAI).isNullOrBlank()
            // Host-synced client id (me.php) may land after Account opens.
            if (clientId.isBlank()) {
                val hostCid = HostApiKeyStore.getValue(appCtx, ApiKeyIds.SPOTIFY_CLIENT_ID).orEmpty()
                if (hostCid.isNotBlank()) clientId = hostCid
            }
            delay(1_200L)
        }
    }

    // Refresh device list on Control tab (and when login becomes available).
    // 5s was burning /v1/me/player/devices forever while the tab sat open.
    LaunchedEffect(tab, loggedIn) {
        if (tab != 0) return@LaunchedEffect
        while (true) {
            if (SpotifyOAuth.isLoggedIn(appCtx) && !SpotifyOAuth.isRateLimited()) {
                devicesLoading = devices.isEmpty()
                val (list, err) = withContext(Dispatchers.IO) { fetchSpotifyDevices(appCtx) }
                devices = list
                devicesMsg = err
                preferredDeviceId = store.preferredDeviceId
                devicesLoading = false
            } else if (!SpotifyOAuth.isLoggedIn(appCtx)) {
                devices = emptyList()
                devicesMsg = "Connect Spotify in the Account tab"
                devicesLoading = false
            } else if (SpotifyOAuth.isRateLimited()) {
                val wait = (SpotifyOAuth.rateLimitRemainingMs() / 1000L).coerceAtLeast(1L)
                devicesMsg = "Spotify rate limit — cooling ${wait}s"
            }
            delay(30_000L)
        }
    }

    DisposableEffect(Unit) {
        enabled = store.enabled
        // Publish baseline DJ UI when pane opens (keep chat history if any)
        if (!djStore.enabled) {
            val prev = SpotifyDjBus.state.value
            val q = prev.queue.ifEmpty { djStore.loadQueue() }
            val qLabel = if (q.isNotEmpty()) " · ${q.size} queued" else ""
            SpotifyDjBus.publish(
                SpotifyDjUiState(
                    enabled = false,
                    status = "Booth ready$qLabel",
                    messages = prev.messages.ifEmpty { djStore.loadMessages() },
                    queue = q,
                    loggedIn = SpotifyOAuth.isLoggedIn(appCtx),
                    voiceId = djStore.voiceId,
                    useAiRank = djStore.useAiRank,
                    songsSinceBanter = djStore.songsSinceBanter,
                    banterEvery = djStore.banterEvery,
                    tracksUntilTalk = tracksUntilTalk(djStore.songsSinceBanter, djStore.banterEvery),
                    banterMode = djStore.banterMode,
                    banterFixed = djStore.banterFixed,
                    banterMin = djStore.banterMin,
                    banterMax = djStore.banterMax,
                    allowTalkOver = djStore.allowTalkOver,
                    banterEnabled = djStore.banterEnabled,
                    resumeAfterRestart = djStore.resumeAfterRestart,
                    selectedGenres = djStore.selectedGenres,
                    genreBoard = djStore.genreBoard,
                    behaviorMode = djStore.behaviorMode,
                    listenerCity = djStore.listenerCity,
                    listenerName = djStore.listenerName,
                ),
            )
        } else {
            // Service may have died while enabled=true (OTA / low memory) — re-arm.
            maybeResumeLiveDj(appCtx)
        }
        onDispose { }
    }

    // One-shot: pull Spotify display_name if name is still empty (booth settings).
    LaunchedEffect(loggedIn) {
        if (!loggedIn || djStore.listenerName.isNotBlank()) return@LaunchedEffect
        val (pulled, _) = withContext(Dispatchers.IO) {
            fetchSpotifyDisplayName(appCtx)
        }
        if (!pulled.isNullOrBlank()) {
            djStore.listenerName = pulled
            applyDjBanterSettings(appCtx)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(GrokifyColors.Void)
            .padding(16.dp),
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
                    "Spotify",
                    color = GrokifyColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                )
                Text(
                    "Controller · Live DJ · Build · Account",
                    color = GrokifyColors.TextDim,
                    fontSize = 12.sp,
                )
            }
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                tint = GrokifyColors.GlowMint,
                modifier = Modifier.size(28.dp),
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(GrokifyColors.Panel)
                .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(12.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf("Control", "Live DJ", "Build", "Account").forEachIndexed { i, label ->
                val selected = tab == i
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (selected) GrokifyColors.GlowMint.copy(alpha = 0.18f)
                            else GrokifyColors.Void.copy(alpha = 0f),
                        )
                        .clickable { tab = i }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (selected) GrokifyColors.GlowMint else GrokifyColors.TextMuted,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        when (tab) {
            0 -> Column(Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = true)) {
                // Master toggle card
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(GrokifyColors.Panel)
                        .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(14.dp))
                        .padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Live lockscreen controls",
                                color = GrokifyColors.TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                            )
                            Text(
                                when {
                                    enabled && notifPosted ->
                                        "On — live notification with Prev · Play · Next (Samsung lockscreen; bypasses Now bar)"
                                    enabled && !notifPosted ->
                                        "On — notification missing. Tap Repost, or Notifications → Spotify live controls"
                                    else ->
                                        "Off — pin a live notification with Prev · Play · Next on the lock screen"
                                },
                                color = GrokifyColors.TextDim,
                                fontSize = 12.sp,
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { on ->
                                if (on) {
                                    if (!notifOk) onRequestPermissions()
                                    setSpotifyControllerEnabled(appCtx, true)
                                    enabled = true
                                } else {
                                    setSpotifyControllerEnabled(appCtx, false)
                                    enabled = false
                                }
                                notifPosted = isSpotifyControllerNotificationPosted(appCtx)
                                scope.launch {
                                    delay(400)
                                    notifPosted = isSpotifyControllerNotificationPosted(appCtx)
                                    delay(900)
                                    notifPosted = isSpotifyControllerNotificationPosted(appCtx)
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = GrokifyColors.Void,
                                checkedTrackColor = GrokifyColors.GlowMint,
                                uncheckedThumbColor = GrokifyColors.TextMuted,
                                uncheckedTrackColor = GrokifyColors.PanelSoft,
                            ),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Android Auto master switch — car media browser (booth while Live DJ is on)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(GrokifyColors.Panel)
                        .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(14.dp))
                        .padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Android Auto",
                                color = GrokifyColors.TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                            )
                            Text(
                                if (androidAutoEnabled) {
                                    "On — “Grokify Live DJ” appears in Android Auto media apps " +
                                        "(idle until Live DJ is on air; full booth when live)"
                                } else {
                                    "Off — hidden from the car media list"
                                },
                                color = GrokifyColors.TextDim,
                                fontSize = 12.sp,
                            )
                        }
                        Switch(
                            checked = androidAutoEnabled,
                            onCheckedChange = { on ->
                                store.androidAutoEnabled = on
                                androidAutoEnabled = on
                                SpotifyAndroidAuto.onSettingChanged(appCtx)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = GrokifyColors.Void,
                                checkedTrackColor = GrokifyColors.GlowMint,
                                uncheckedThumbColor = GrokifyColors.TextMuted,
                                uncheckedTrackColor = GrokifyColors.PanelSoft,
                            ),
                        )
                    }
                    if (androidAutoEnabled) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Not seeing it in the car? Grokify is sideloaded, so Android Auto " +
                                "hides it until you enable Unknown sources:\n" +
                                "1. Phone → Settings → Apps → Android Auto → Additional settings in the app\n" +
                                "2. Tap “Version” 10× → open ⋮ Developer settings\n" +
                                "3. Enable “Unknown sources”\n" +
                                "4. Force-stop Android Auto, reopen GrokifyOS, reconnect the car\n" +
                                "Look for “Grokify Live DJ” under media apps (next to Spotify).",
                            color = GrokifyColors.TextMuted,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                val albumArtModel = stickyAlbumArt
                val artistArtModel = stickyArtistArt
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(14.dp)),
                ) {
                    // Album art as full-card background (sticky model survives blank polls)
                    if (albumArtModel != null) {
                        AsyncImage(
                            model = albumArtModel,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(
                                if (albumArtModel != null) {
                                    GrokifyColors.Void.copy(alpha = 0.72f)
                                } else {
                                    GrokifyColors.Panel
                                },
                            ),
                    )
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "NOW PLAYING",
                            style = MaterialTheme.typography.labelSmall,
                            color = GrokifyColors.GlowCyan,
                        )
                        Spacer(Modifier.height(12.dp))
                        // Artist portrait as thumbnail (opens artist in Spotify)
                        Box(
                            Modifier
                                .size(76.dp)
                                .clip(CircleShape)
                                .background(GrokifyColors.GlowMint.copy(alpha = 0.12f))
                                .border(2.dp, GrokifyColors.GlowMint.copy(alpha = 0.45f), CircleShape)
                                .clickable(enabled = now.artistUri.isNotBlank() || now.artist.isNotBlank()) {
                                    when {
                                        now.artistUri.isNotBlank() -> openSpotifyContent(context, now.artistUri)
                                        now.albumUri.isNotBlank() -> openSpotifyContent(context, now.albumUri)
                                        now.trackUri.isNotBlank() -> openSpotifyContent(context, now.trackUri)
                                        else -> openSpotifyApp(context)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (artistArtModel != null) {
                                AsyncImage(
                                    model = artistArtModel,
                                    contentDescription = "Artist",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Icon(
                                    Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = GrokifyColors.GlowMint,
                                    modifier = Modifier.size(36.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (now.hasSession) now.title else "Nothing detected",
                            color = GrokifyColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = now.hasSession) {
                                    when {
                                        now.trackUri.isNotBlank() -> openSpotifyContent(context, now.trackUri)
                                        now.albumUri.isNotBlank() -> openSpotifyContent(context, now.albumUri)
                                        else -> openSpotifyApp(context)
                                    }
                                },
                        )
                        Text(
                            when {
                                now.hasSession && now.artist.isNotBlank() -> now.artist
                                now.hasSession -> now.appLabel
                                else -> "Start Spotify, then use controls below or the lockscreen widget"
                            },
                            color = GrokifyColors.TextMuted,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = now.hasSession && now.artist.isNotBlank()) {
                                    when {
                                        now.artistUri.isNotBlank() -> openSpotifyContent(context, now.artistUri)
                                        now.albumUri.isNotBlank() -> openSpotifyContent(context, now.albumUri)
                                        else -> openSpotifyApp(context)
                                    }
                                },
                        )
                        if (now.hasSession && now.appLabel.isNotBlank() && now.artist.isNotBlank()) {
                            Text(
                                now.appLabel,
                                color = GrokifyColors.TextDim,
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                        if (now.hasSession && now.durationMs > 0L) {
                            Spacer(Modifier.height(12.dp))
                            val frac = (now.positionMs.toFloat() / now.durationMs.toFloat())
                                .coerceIn(0f, 1f)
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(GrokifyColors.PanelSoft.copy(alpha = 0.85f)),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(frac)
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(GrokifyColors.GlowMint),
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    formatTrackTime(now.positionMs),
                                    color = GrokifyColors.TextDim,
                                    fontSize = 11.sp,
                                )
                                Text(
                                    formatTrackTime(now.durationMs),
                                    color = GrokifyColors.TextDim,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TransportButton(
                                icon = Icons.Default.SkipPrevious,
                                label = "Back",
                                onClick = {
                                    dispatchMediaCommand(appCtx, ACTION_PREV)
                                    now = readNowPlaying(appCtx)
                                },
                            )
                            TransportButton(
                                icon = if (now.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                label = if (now.isPlaying) "Pause" else "Play",
                                accent = true,
                                onClick = {
                                    dispatchMediaCommand(appCtx, ACTION_PLAY_PAUSE)
                                    now = readNowPlaying(appCtx)
                                },
                            )
                            TransportButton(
                                icon = Icons.Default.SkipNext,
                                label = "Next",
                                onClick = {
                                    dispatchMediaCommand(appCtx, ACTION_NEXT)
                                    now = readNowPlaying(appCtx)
                                },
                            )
                            // Heart → Liked Songs (library)
                            LikeTrackButton(
                                liked = trackLiked,
                                enabled = now.hasSession &&
                                    now.trackUri.isNotBlank() &&
                                    loggedIn &&
                                    !trackLikedBusy,
                                busy = trackLikedBusy,
                                onClick = {
                                    val uri = now.trackUri
                                    if (uri.isBlank() || trackLikedBusy) return@LikeTrackButton
                                    scope.launch {
                                        trackLikedBusy = true
                                        trackLikedMsg = null
                                        val want = !trackLiked
                                        val err = withContext(Dispatchers.IO) {
                                            setSpotifyTrackLiked(appCtx, uri, want)
                                        }
                                        if (err == null) {
                                            trackLiked = want
                                            likedCheckUri = uri
                                            val tid = spotifyTrackIdFromUri(uri)
                                            if (tid.isNotBlank()) {
                                                likedByTrackId = likedByTrackId + (tid to want)
                                            }
                                            trackLikedMsg = if (want) "Saved to Liked Songs" else "Removed from Liked"
                                        } else {
                                            trackLikedMsg = err
                                        }
                                        trackLikedBusy = false
                                    }
                                },
                            )
                            // More like this → prepend mixed same-artist + similar cuts to DJ UP NEXT
                            TransportButton(
                                icon = Icons.Default.PlaylistAdd,
                                label = "More",
                                onClick = {
                                    val uri = now.trackUri
                                    if (uri.isBlank() && now.title.isBlank()) return@TransportButton
                                    trackLikedMsg = "Finding more like this…"
                                    spotifyLiveDjMoreLikeThis(
                                        appCtx,
                                        trackUri = uri,
                                        name = now.title,
                                        artists = now.artist,
                                        artistUri = now.artistUri,
                                        albumArtUrl = now.albumArtUrl,
                                    )
                                },
                            )
                            // Dislike → multi-select reasons; keeps cut/artist out of Live DJ queue
                            TransportButton(
                                icon = Icons.Default.ThumbDown,
                                label = "Dislike",
                                onClick = {
                                    val uri = now.trackUri
                                    if (uri.isBlank() && now.title.isBlank()) return@TransportButton
                                    dislikeTarget = DislikeTarget(
                                        trackUri = uri,
                                        name = now.title,
                                        artists = now.artist,
                                        artistUri = now.artistUri,
                                        artistIds = listOfNotNull(djSpotifyArtistId(now.artistUri)),
                                        skipIfPlaying = true,
                                    )
                                },
                            )
                        }
                        if (!trackLikedMsg.isNullOrBlank() && tab == 0) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                trackLikedMsg!!,
                                color = if (
                                    trackLikedMsg!!.startsWith("Saved") ||
                                    trackLikedMsg!!.startsWith("Removed") ||
                                    trackLikedMsg!!.startsWith("Finding more") ||
                                    trackLikedMsg!!.startsWith("More like") ||
                                    trackLikedMsg!!.startsWith("Disliked") ||
                                    trackLikedMsg!!.startsWith("Saving dislike")
                                ) {
                                    GrokifyColors.GlowMint
                                } else {
                                    GrokifyColors.GlowAmber
                                },
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (spotifyMsgNeedsReauth(trackLikedMsg)) {
                                TextButton(
                                    enabled = !busy,
                                    onClick = {
                                        busy = true
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                SpotifyOAuth.reauthorize(appCtx)
                                            }
                                            authMsg = SpotifyOAuth.lastAuthMessage
                                                ?: "Browser opened — approve permissions"
                                            trackLikedMsg = authMsg
                                            tab = 3
                                            busy = false
                                        }
                                    },
                                ) {
                                    Text(
                                        "Re-authorize Spotify",
                                        color = GrokifyColors.GlowMint,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Playback device picker (Spotify Connect)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(GrokifyColors.Panel)
                        .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "PLAY ON",
                                style = MaterialTheme.typography.labelSmall,
                                color = GrokifyColors.GlowCyan,
                            )
                            Text(
                                "Choose which device Spotify plays on",
                                color = GrokifyColors.TextDim,
                                fontSize = 11.sp,
                            )
                        }
                        IconButton(
                            onClick = {
                                scope.launch {
                                    devicesLoading = true
                                    val (list, err) = withContext(Dispatchers.IO) {
                                        fetchSpotifyDevices(appCtx)
                                    }
                                    devices = list
                                    devicesMsg = err
                                    preferredDeviceId = store.preferredDeviceId
                                    devicesLoading = false
                                }
                            },
                            enabled = !devicesLoading && devicesTransferringId == null,
                        ) {
                            if (devicesLoading && devices.isEmpty()) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = GrokifyColors.GlowCyan,
                                )
                            } else {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Refresh devices",
                                    tint = GrokifyColors.GlowCyan,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    when {
                        !loggedIn -> {
                            Text(
                                "Connect Spotify in the Account tab to list devices.",
                                color = GrokifyColors.TextMuted,
                                fontSize = 12.sp,
                            )
                            TextButton(onClick = { tab = 3 }) {
                                Text("Open Account", color = GrokifyColors.GlowMint, fontSize = 13.sp)
                            }
                        }
                        devicesMsg != null && devices.isEmpty() -> {
                            Text(
                                devicesMsg ?: "No devices",
                                color = GrokifyColors.GlowAmber,
                                fontSize = 12.sp,
                            )
                        }
                        devices.isEmpty() && devicesLoading -> {
                            Text(
                                "Loading devices…",
                                color = GrokifyColors.TextDim,
                                fontSize = 12.sp,
                            )
                        }
                        devices.isEmpty() -> {
                            Text(
                                "No active devices. Open Spotify on a phone, speaker, or computer, then refresh.",
                                color = GrokifyColors.TextMuted,
                                fontSize = 12.sp,
                            )
                        }
                        else -> {
                            devices.forEach { dev ->
                                val active = dev.isActive
                                val preferred = preferredDeviceId == dev.id
                                val transferring = devicesTransferringId == dev.id
                                val selected = active || preferred
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            when {
                                                active -> GrokifyColors.GlowMint.copy(alpha = 0.14f)
                                                preferred -> GrokifyColors.GlowCyan.copy(alpha = 0.10f)
                                                else -> GrokifyColors.Void.copy(alpha = 0.35f)
                                            },
                                        )
                                        .border(
                                            1.dp,
                                            when {
                                                active -> GrokifyColors.GlowMint.copy(alpha = 0.45f)
                                                preferred -> GrokifyColors.GlowCyan.copy(alpha = 0.35f)
                                                else -> GrokifyColors.PanelBorder
                                            },
                                            RoundedCornerShape(10.dp),
                                        )
                                        .clickable(
                                            enabled = !dev.isRestricted &&
                                                devicesTransferringId == null &&
                                                !active,
                                        ) {
                                            scope.launch {
                                                devicesTransferringId = dev.id
                                                devicesMsg = null
                                                val err = withContext(Dispatchers.IO) {
                                                    transferSpotifyPlayback(appCtx, dev.id, play = true)
                                                }
                                                if (err == null) {
                                                    store.preferredDeviceId = dev.id
                                                    preferredDeviceId = dev.id
                                                    // Optimistically mark active until next poll
                                                    devices = devices.map {
                                                        it.copy(isActive = it.id == dev.id)
                                                    }
                                                    delay(600L)
                                                    val (list, listErr) = withContext(Dispatchers.IO) {
                                                        fetchSpotifyDevices(appCtx)
                                                    }
                                                    if (list.isNotEmpty()) devices = list
                                                    devicesMsg = listErr
                                                } else {
                                                    devicesMsg = err
                                                }
                                                devicesTransferringId = null
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        deviceTypeIcon(dev.type),
                                        contentDescription = dev.type,
                                        tint = if (active) GrokifyColors.GlowMint
                                        else GrokifyColors.TextMuted,
                                        modifier = Modifier.size(22.dp),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            dev.name,
                                            color = GrokifyColors.TextPrimary,
                                            fontWeight = if (selected) FontWeight.SemiBold
                                            else FontWeight.Normal,
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            buildString {
                                                append(dev.type)
                                                if (active) append(" · Playing here")
                                                else if (preferred) append(" · Preferred")
                                                if (dev.isRestricted) append(" · Restricted")
                                                if (dev.volumePercent in 0..100) {
                                                    append(" · ${dev.volumePercent}%")
                                                }
                                            },
                                            color = GrokifyColors.TextDim,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    when {
                                        transferring -> {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp,
                                                color = GrokifyColors.GlowMint,
                                            )
                                        }
                                        active -> {
                                            Icon(
                                                Icons.Default.CheckCircle,
                                                contentDescription = "Active",
                                                tint = GrokifyColors.GlowMint,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }
                                }
                            }
                            if (devicesMsg != null) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    devicesMsg ?: "",
                                    color = GrokifyColors.GlowAmber,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(GrokifyColors.Panel)
                        .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                ) {
                    Text(
                        "SETUP",
                        style = MaterialTheme.typography.labelSmall,
                        color = GrokifyColors.GlowViolet,
                    )
                    Spacer(Modifier.height(8.dp))
                    StatusLine(
                        ok = notifOk,
                        okText = "Notifications allowed",
                        badText = "Notifications blocked — required for live lockscreen controls",
                    )
                    StatusLine(
                        ok = !enabled || notifPosted,
                        okText = if (enabled) "Controller notification is live" else "Widget idle",
                        badText = "Controller notification missing — tap Repost",
                    )
                    StatusLine(
                        ok = listenerOk,
                        okText = "Notification access on (track title + reliable control)",
                        badText = "Notification access off — enable for Spotify metadata",
                    )
                    StatusLine(
                        ok = spotifyOk,
                        okText = "Spotify installed",
                        badText = "Spotify not found — install Spotify Music",
                    )
                    if (enabled) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            spotifyControllerDiag(appCtx),
                            color = GrokifyColors.TextMuted,
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    if (!listenerOk) {
                        TextButton(onClick = { openNotificationListenerSettings(context) }) {
                            Text("Open notification access", color = GrokifyColors.GlowCyan, fontSize = 13.sp)
                        }
                    }
                    if (!notifOk) {
                        TextButton(onClick = onRequestPermissions) {
                            Text("Allow notifications", color = GrokifyColors.GlowAmber, fontSize = 13.sp)
                        }
                    }
                    if (enabled && !notifPosted) {
                        TextButton(onClick = {
                            setSpotifyControllerEnabled(appCtx, true)
                            notifPosted = isSpotifyControllerNotificationPosted(appCtx)
                            scope.launch {
                                delay(500)
                                notifPosted = isSpotifyControllerNotificationPosted(appCtx)
                                delay(1000)
                                notifPosted = isSpotifyControllerNotificationPosted(appCtx)
                            }
                        }) {
                            Text("Repost live controls", color = GrokifyColors.GlowMint, fontSize = 13.sp)
                        }
                    }
                    TextButton(onClick = { openSpotifyApp(context) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.OpenInNew,
                                contentDescription = null,
                                tint = GrokifyColors.GlowMint,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (spotifyOk) "Open Spotify" else "Get Spotify",
                                color = GrokifyColors.GlowMint,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "Native built-in — lockscreen controls stay in-process. Live AI DJ runs as a " +
                        "foreground service (no WebView), so it should not crash the host app.",
                    color = GrokifyColors.TextDim,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Spacer(Modifier.height(24.dp))
            }

            1 -> Column(Modifier.weight(1f, fill = true).fillMaxWidth()) {
                // Slim console header — title + mono status (no ON AIR chip)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(GrokifyColors.Panel)
                        .border(
                            1.dp,
                            if (djState.enabled || djStore.enabled) {
                                GrokifyColors.GlowMint.copy(alpha = 0.35f)
                            } else {
                                GrokifyColors.PanelBorder
                            },
                            RoundedCornerShape(12.dp),
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val liveOn = djState.enabled || djStore.enabled
                    Column(Modifier.weight(1f)) {
                        Text(
                            "LIVE DJ",
                            color = GrokifyColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            letterSpacing = 0.6.sp,
                        )
                        Text(
                            when {
                                djState.transitioning -> "transition"
                                djState.filling -> "filling"
                                djState.chatBusy -> "chat"
                                liveOn -> {
                                    val base = djState.status.ifBlank { "live" }
                                    if (!djState.banterEnabled && !djStore.banterEnabled) {
                                        if (base.contains("banter off")) base.lowercase()
                                        else {
                                            val root = base
                                                .substringBefore(" · talk in")
                                                .substringBefore(" · banter")
                                            "$root · banter off".lowercase()
                                        }
                                    } else {
                                        val cd = banterCountdownLabel(
                                            djState.songsSinceBanter,
                                            djState.banterEvery,
                                        )
                                        if (base.contains("talk in") || base.contains("banter")) {
                                            base.lowercase()
                                        } else {
                                            "$base · $cd".lowercase()
                                        }
                                    }
                                }
                                else -> djState.status.ifBlank {
                                    val n = djState.queue.size
                                    if (n > 0) "ready · $n up next" else "ready"
                                }.lowercase()
                            },
                            color = GrokifyColors.TextDim,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (djState.queue.isNotEmpty()) {
                        Text(
                            "Q${djState.queue.size}",
                            color = GrokifyColors.GlowCyan,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    Switch(
                        checked = liveOn,
                        onCheckedChange = { on ->
                            if (on && !loggedIn) {
                                tab = 3
                                authMsg = "Connect Spotify first (Account tab)"
                                return@Switch
                            }
                            if (on && !notifOk) onRequestPermissions()
                            setSpotifyLiveDjEnabled(appCtx, on)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = GrokifyColors.Void,
                            checkedTrackColor = GrokifyColors.GlowMint,
                            uncheckedThumbColor = GrokifyColors.TextMuted,
                            uncheckedTrackColor = GrokifyColors.PanelSoft,
                        ),
                    )
                }
                if (!djState.error.isNullOrBlank()) {
                    Text(
                        djState.error!!,
                        color = GrokifyColors.GlowAmber,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
                    )
                }

                Spacer(Modifier.height(6.dp))

                // Inner tabs: CHAT · QUEUE · BLOCKS · SETTINGS
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(GrokifyColors.PanelSoft)
                        .border(1.dp, GrokifyColors.PanelBorder.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    val blockCount = remember(dislikeRevision) {
                        val (songs, artists, tired) = djStore.dislikeCounts()
                        songs + artists + tired
                    }
                    listOf("CHAT", "QUEUE", "BLOCKS", "SETTINGS").forEachIndexed { i, label ->
                        val selected = djSubTab == i
                        val badge = when (i) {
                            1 -> if (djState.queue.isNotEmpty()) " ${djState.queue.size}" else ""
                            2 -> if (blockCount > 0) " $blockCount" else ""
                            else -> ""
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) GrokifyColors.GlowMint.copy(alpha = 0.12f)
                                    else androidx.compose.ui.graphics.Color.Transparent,
                                )
                                .border(
                                    1.dp,
                                    if (selected) GrokifyColors.GlowMint.copy(alpha = 0.35f)
                                    else androidx.compose.ui.graphics.Color.Transparent,
                                    RoundedCornerShape(8.dp),
                                )
                                .clickable { djSubTab = i }
                                .padding(vertical = 7.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label + badge,
                                color = if (selected) GrokifyColors.GlowMint else GrokifyColors.TextMuted,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 0.5.sp,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))

                when (djSubTab) {
                    // ── Chat ──────────────────────────────────────────────
                    0 -> {
                        val lastChat = djState.messages.lastOrNull()
                        LaunchedEffect(
                            djState.messages.size,
                            lastChat?.id,
                            lastChat?.text?.length,
                            lastChat?.isNowPlaying,
                            lastChat?.streaming,
                            djSubTab,
                        ) {
                            if (djSubTab != 0 || djState.messages.isEmpty()) return@LaunchedEffect
                            // Tall now-playing cards need a second pass after layout.
                            delay(16)
                            djChatListState.scrollChatToBottom()
                            delay(48)
                            djChatListState.scrollChatToBottom()
                        }
                        LazyColumn(
                            state = djChatListState,
                            modifier = Modifier
                                .weight(1f, fill = true)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                bottom = 12.dp,
                            ),
                        ) {
                            if (djState.messages.isEmpty()) {
                                item {
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(GrokifyColors.PanelSoft)
                                            .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(12.dp))
                                            .padding(12.dp),
                                    ) {
                                        Text(
                                            "BOOTH · IDLE",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = GrokifyColors.GlowCyan,
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            "Chat · ♥ · more-like · dislike · ▶ replay",
                                            color = GrokifyColors.TextMuted,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            "Auto-handoff optional · queue is in-app only",
                                            color = GrokifyColors.TextDim,
                                            fontSize = 11.sp,
                                        )
                                    }
                                }
                            }
                            items(djState.messages, key = { it.id }) { msg ->
                                val msgUri = msg.trackUri.orEmpty()
                                val msgTrackId = spotifyTrackIdFromUri(msgUri)
                                val msgLiked = when {
                                    msg.isNowPlaying -> trackLiked
                                    msgTrackId.isNotBlank() ->
                                        likedByTrackId[msgTrackId] == true
                                    else -> false
                                }
                                val msgLikeBusy = msgUri.isNotBlank() &&
                                    (likeBusyUri == msgUri || (msg.isNowPlaying && trackLikedBusy))
                                val msgDisliked = remember(
                                    msgUri,
                                    msg.trackArtists,
                                    msg.artistUri,
                                    dislikeRevision,
                                ) {
                                    msg.role == DjChatRole.Track &&
                                        (msgUri.isNotBlank() || !msg.trackArtists.isNullOrBlank()) &&
                                        djStore.isDisliked(
                                            uri = msgUri,
                                            artists = msg.trackArtists.orEmpty(),
                                            artistUri = msg.artistUri.orEmpty(),
                                            name = msg.trackName.orEmpty(),
                                        )
                                }
                                DjChatBubble(
                                    msg = msg,
                                    onPrev = { spotifyLiveDjPrevious(appCtx) },
                                    onPauseToggle = { spotifyLiveDjPauseToggle(appCtx) },
                                    onSkip = { spotifyLiveDjSkip(appCtx, forceTalk = false) },
                                    onHardSkip = { spotifyLiveDjSkip(appCtx, forceTalk = false, hardSkip = true) },
                                    liked = msgLiked,
                                    likeBusy = msgLikeBusy,
                                    disliked = msgDisliked,
                                    onLikeToggle = if (
                                        msg.role == DjChatRole.Track &&
                                        (msgUri.isNotBlank() || (msg.isNowPlaying && now.trackUri.isNotBlank()))
                                    ) {
                                        {
                                            val uri = msgUri.ifBlank { now.trackUri }
                                            if (uri.isBlank() || likeBusyUri == uri ||
                                                (msg.isNowPlaying && trackLikedBusy)
                                            ) {
                                                return@DjChatBubble
                                            }
                                            val tid = spotifyTrackIdFromUri(uri)
                                            val currentlyLiked = when {
                                                msg.isNowPlaying -> trackLiked
                                                tid.isNotBlank() -> likedByTrackId[tid] == true
                                                else -> false
                                            }
                                            scope.launch {
                                                likeBusyUri = uri
                                                if (msg.isNowPlaying) trackLikedBusy = true
                                                trackLikedMsg = null
                                                val want = !currentlyLiked
                                                val err = withContext(Dispatchers.IO) {
                                                    setSpotifyTrackLiked(appCtx, uri, want)
                                                }
                                                if (err == null) {
                                                    if (tid.isNotBlank()) {
                                                        likedByTrackId = likedByTrackId + (tid to want)
                                                    }
                                                    if (msg.isNowPlaying ||
                                                        uri == likeTrackUri ||
                                                        uri == now.trackUri
                                                    ) {
                                                        trackLiked = want
                                                        likedCheckUri = uri
                                                    }
                                                    trackLikedMsg =
                                                        if (want) "Saved to Liked Songs" else "Removed from Liked"
                                                } else {
                                                    trackLikedMsg = err
                                                }
                                                if (likeBusyUri == uri) likeBusyUri = ""
                                                if (msg.isNowPlaying) trackLikedBusy = false
                                            }
                                        }
                                    } else null,
                                    onPlayTrack = { m ->
                                        val uri = m.trackUri.orEmpty()
                                        if (uri.isBlank()) return@DjChatBubble
                                        spotifyLiveDjPlayUri(
                                            appCtx,
                                            trackUri = uri,
                                            name = m.trackName.orEmpty(),
                                            artists = m.trackArtists.orEmpty(),
                                            albumArtUrl = m.albumArtUrl.orEmpty(),
                                            artistArtUrl = m.artistArtUrl.orEmpty(),
                                            albumUri = m.albumUri.orEmpty(),
                                            artistUri = m.artistUri.orEmpty(),
                                        )
                                    },
                                    onMoreLikeThis = { m ->
                                        val uri = m.trackUri.orEmpty()
                                            .ifBlank { if (m.isNowPlaying) now.trackUri else "" }
                                        val name = m.trackName.orEmpty()
                                            .ifBlank { if (m.isNowPlaying) now.title else "" }
                                        val artists = m.trackArtists.orEmpty()
                                            .ifBlank { if (m.isNowPlaying) now.artist else "" }
                                        val aUri = m.artistUri.orEmpty()
                                            .ifBlank { if (m.isNowPlaying) now.artistUri else "" }
                                        if (uri.isBlank() && name.isBlank() && artists.isBlank()) {
                                            return@DjChatBubble
                                        }
                                        trackLikedMsg = "Finding more like this…"
                                        spotifyLiveDjMoreLikeThis(
                                            appCtx,
                                            trackUri = uri,
                                            name = name,
                                            artists = artists,
                                            artistUri = aUri,
                                            albumArtUrl = m.albumArtUrl.orEmpty(),
                                        )
                                    },
                                    onDislike = { m ->
                                        val uri = m.trackUri.orEmpty()
                                            .ifBlank { if (m.isNowPlaying) now.trackUri else "" }
                                        val name = m.trackName.orEmpty()
                                            .ifBlank { if (m.isNowPlaying) now.title else "" }
                                        val artists = m.trackArtists.orEmpty()
                                            .ifBlank { if (m.isNowPlaying) now.artist else "" }
                                        val aUri = m.artistUri.orEmpty()
                                            .ifBlank { if (m.isNowPlaying) now.artistUri else "" }
                                        if (uri.isBlank() && name.isBlank() && artists.isBlank()) {
                                            return@DjChatBubble
                                        }
                                        dislikeTarget = DislikeTarget(
                                            trackUri = uri,
                                            name = name,
                                            artists = artists,
                                            artistUri = aUri,
                                            artistIds = listOfNotNull(djSpotifyArtistId(aUri)),
                                            skipIfPlaying = m.isNowPlaying || uri == now.trackUri,
                                        )
                                    },
                                )
                            }
                        }
                        if (!trackLikedMsg.isNullOrBlank() && tab == 1 && djSubTab == 0) {
                            Text(
                                trackLikedMsg!!,
                                color = if (
                                    trackLikedMsg!!.startsWith("Saved") ||
                                    trackLikedMsg!!.startsWith("Removed") ||
                                    trackLikedMsg!!.startsWith("Finding more") ||
                                    trackLikedMsg!!.startsWith("More like") ||
                                    trackLikedMsg!!.startsWith("Disliked") ||
                                    trackLikedMsg!!.startsWith("Saving dislike")
                                ) {
                                    GrokifyColors.GlowMint
                                } else {
                                    GrokifyColors.GlowAmber
                                },
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                            if (spotifyMsgNeedsReauth(trackLikedMsg)) {
                                TextButton(
                                    enabled = !busy,
                                    onClick = {
                                        busy = true
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                SpotifyOAuth.reauthorize(appCtx)
                                            }
                                            authMsg = SpotifyOAuth.lastAuthMessage
                                                ?: "Browser opened — approve permissions"
                                            trackLikedMsg = authMsg
                                            tab = 3
                                            busy = false
                                        }
                                    },
                                ) {
                                    Text(
                                        "Re-authorize Spotify",
                                        color = GrokifyColors.GlowMint,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(GrokifyColors.Panel)
                                .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(14.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            OutlinedTextField(
                                value = djChatDraft,
                                onValueChange = { djChatDraft = it },
                                modifier = Modifier.weight(1f),
                                maxLines = 3,
                                placeholder = {
                                    Text(
                                        "vibes · queue · cut · artist…",
                                        color = GrokifyColors.TextDim,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = GrokifyColors.TextPrimary,
                                    unfocusedTextColor = GrokifyColors.TextPrimary,
                                    focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                                    unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                                    cursorColor = GrokifyColors.GlowMint,
                                ),
                            )
                            IconButton(
                                onClick = {
                                    val t = djChatDraft.trim()
                                    if (t.isEmpty() || djState.chatBusy) return@IconButton
                                    djChatDraft = ""
                                    spotifyLiveDjChat(appCtx, t)
                                },
                                enabled = djChatDraft.isNotBlank() && !djState.chatBusy,
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send to DJ",
                                    tint = if (djChatDraft.isNotBlank() && !djState.chatBusy) {
                                        GrokifyColors.GlowMint
                                    } else {
                                        GrokifyColors.TextDim
                                    },
                                )
                            }
                        }
                    }

                    // ── Queue ─────────────────────────────────────────────
                    1 -> Column(
                        Modifier
                            .weight(1f, fill = true)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            "IN-APP SET · tap title/▶ jump · direct-play only",
                            color = GrokifyColors.TextDim,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            TextButton(
                                onClick = { spotifyLiveDjSyncToSpotify(appCtx) },
                            ) {
                                Text("SYNC", color = GrokifyColors.GlowMint, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                            TextButton(
                                onClick = { spotifyLiveDjNewQueue(appCtx) },
                            ) {
                                Text("NEW", color = GrokifyColors.GlowMint, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                            TextButton(
                                onClick = { spotifyLiveDjRefill(appCtx) },
                            ) {
                                Text("REFILL", color = GrokifyColors.GlowCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                            var lastQueueSkipAt by remember { mutableLongStateOf(0L) }
                            TextButton(
                                onClick = {
                                    val nowMs = android.os.SystemClock.elapsedRealtime()
                                    val hard = lastQueueSkipAt > 0L && nowMs - lastQueueSkipAt < 1_600L
                                    lastQueueSkipAt = nowMs
                                    spotifyLiveDjSkip(
                                        appCtx,
                                        forceTalk = !hard && djState.banterEnabled,
                                        hardSkip = hard,
                                    )
                                },
                            ) {
                                Text(
                                    if (djState.banterEnabled) "SKIP+TALK" else "SKIP",
                                    color = GrokifyColors.GlowCyan,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        if (djState.nowLine.isNotBlank()) {
                            Text(
                                "NOW",
                                style = MaterialTheme.typography.labelSmall,
                                color = GrokifyColors.GlowMint,
                            )
                            Text(
                                djState.nowLine,
                                color = GrokifyColors.TextPrimary,
                                fontSize = 13.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        Text(
                            "UP NEXT (${djState.queue.size})",
                            style = MaterialTheme.typography.labelSmall,
                            color = GrokifyColors.GlowCyan,
                        )
                        Spacer(Modifier.height(4.dp))
                        if (djState.queue.isEmpty()) {
                            Text(
                                "empty · NEW or REFILL",
                                color = GrokifyColors.TextMuted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        } else {
                            djState.queue.forEachIndexed { i, t ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (t.albumArtUrl.isNotBlank() || t.artistArtUrl.isNotBlank()) {
                                        AsyncImage(
                                            model = t.artistArtUrl.ifBlank { t.albumArtUrl },
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .clickable(enabled = t.artistUri.isNotBlank()) {
                                                    openSpotifyContent(context, t.artistUri)
                                                },
                                        )
                                        Spacer(Modifier.width(10.dp))
                                    } else {
                                        Text(
                                            "${i + 1}.",
                                            color = GrokifyColors.TextDim,
                                            fontSize = 12.sp,
                                            modifier = Modifier.width(28.dp),
                                        )
                                    }
                                    Column(
                                        Modifier
                                            .weight(1f)
                                            .clickable {
                                                // Tap title row → play this cut (silent jump)
                                                spotifyLiveDjPlayFromQueue(appCtx, t.uri, i)
                                            },
                                    ) {
                                        Text(
                                            t.name.ifBlank { t.uri },
                                            color = GrokifyColors.TextPrimary,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (t.artists.isNotBlank() || t.reason.isNotBlank()) {
                                            Text(
                                                buildString {
                                                    if (t.artists.isNotBlank()) append(t.artists)
                                                    if (t.reason.isNotBlank()) {
                                                        if (isNotEmpty()) append(" · ")
                                                        append(t.reason)
                                                    }
                                                },
                                                color = GrokifyColors.TextDim,
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.clickable(enabled = t.artistUri.isNotBlank()) {
                                                    openSpotifyContent(context, t.artistUri)
                                                },
                                            )
                                        }
                                    }
                                    // Open in Spotify (track / album)
                                    IconButton(
                                        onClick = {
                                            when {
                                                t.uri.isNotBlank() -> openSpotifyContent(context, t.uri)
                                                t.albumUri.isNotBlank() -> openSpotifyContent(context, t.albumUri)
                                                t.artistUri.isNotBlank() -> openSpotifyContent(context, t.artistUri)
                                            }
                                        },
                                        enabled = t.uri.isNotBlank() || t.albumUri.isNotBlank() || t.artistUri.isNotBlank(),
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.OpenInNew,
                                            contentDescription = "Open in Spotify",
                                            tint = GrokifyColors.TextDim,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    IconButton(
                                        onClick = { spotifyLiveDjPlayFromQueue(appCtx, t.uri, i) },
                                        enabled = t.uri.isNotBlank() || i >= 0,
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.PlayArrow,
                                            contentDescription = "Play now (no talk)",
                                            tint = GrokifyColors.GlowMint,
                                            modifier = Modifier.size(22.dp),
                                        )
                                    }
                                    IconButton(
                                        onClick = { spotifyLiveDjRemoveFromQueue(appCtx, t.uri) },
                                        enabled = t.uri.isNotBlank(),
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "Remove from queue",
                                            tint = GrokifyColors.TextDim,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "double-tap skip cuts research / banter",
                            color = GrokifyColors.TextDim,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    // ── Blocks / dislikes ────────────────────────────────
                    2 -> LiveDjBlocksTab(
                        modifier = Modifier.weight(1f, fill = true),
                        djStore = djStore,
                        revision = dislikeRevision,
                        onRevision = { dislikeRevision += 1 },
                    )

                    // ── Settings ──────────────────────────────────────────
                    else -> LiveDjSettingsTab(
                        modifier = Modifier.weight(1f, fill = true),
                        appCtx = appCtx,
                        djStore = djStore,
                        djState = djState,
                        scope = scope,
                        loggedIn = loggedIn,
                        hasXaiKey = hasXaiKey,
                    )
                }
            }

            2 -> Column(Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = true)) {
                // Research & build new playlist
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(GrokifyColors.Panel)
                        .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                ) {
                    Text(
                        "RESEARCH & BUILD",
                        style = MaterialTheme.typography.labelSmall,
                        color = GrokifyColors.GlowViolet,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Describe a set → Grok Build researches tracks → Build writes a private playlist on Spotify. " +
                            "Uses host device token (same as Chat).",
                        color = GrokifyColors.TextDim,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = researchPrompt,
                        onValueChange = { researchPrompt = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                        placeholder = {
                            Text(
                                "e.g. Late-night cyberpunk drive: dense synths, no vocals, 110–125 BPM…",
                                color = GrokifyColors.TextDim,
                                fontSize = 13.sp,
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = GrokifyColors.TextPrimary,
                            unfocusedTextColor = GrokifyColors.TextPrimary,
                            focusedBorderColor = GrokifyColors.GlowViolet,
                            unfocusedBorderColor = GrokifyColors.PanelBorder,
                            cursorColor = GrokifyColors.GlowViolet,
                        ),
                    )
                    Spacer(Modifier.height(8.dp))
                    val chipScroll = rememberScrollState()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(chipScroll),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        vibeChips.forEach { (fill, label) ->
                            FilterChip(
                                selected = researchPrompt == fill,
                                onClick = { researchPrompt = fill },
                                label = { Text(label, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = GrokifyColors.GlowViolet.copy(alpha = 0.25f),
                                    selectedLabelColor = GrokifyColors.GlowViolet,
                                    containerColor = GrokifyColors.PanelSoft,
                                    labelColor = GrokifyColors.TextPrimary,
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = researchPrompt == fill,
                                    borderColor = GrokifyColors.PanelBorder,
                                    selectedBorderColor = GrokifyColors.GlowViolet,
                                ),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = !busy && researchPrompt.isNotBlank(),
                            onClick = {
                                if (busy) return@TextButton
                                busy = true
                                workStep = "Starting research…"
                                buildMsg = null
                                scope.launch {
                                    val (result, err) = withContext(Dispatchers.IO) {
                                        SpotifyPlaylistAi.research(appCtx, researchPrompt) { step ->
                                            setOnMain { workStep = step }
                                        }
                                    }
                                    if (result != null) {
                                        lastResearch = result
                                        val lines = buildString {
                                            appendLine("▶ ${result.title}")
                                            if (result.description.isNotBlank()) appendLine(result.description)
                                            appendLine()
                                            if (result.rationale.isNotBlank()) appendLine(result.rationale)
                                            if (result.banter.isNotBlank()) {
                                                appendLine()
                                                appendLine("🎙 ${result.banter}")
                                            }
                                            appendLine()
                                            result.tracks.forEachIndexed { i, t ->
                                                appendLine(
                                                    "${i + 1}. ${t.query}" +
                                                        if (t.reason.isNotBlank()) " — ${t.reason}" else "",
                                                )
                                            }
                                        }
                                        researchOut = lines
                                        buildOk = true
                                        buildMsg = "Research ready — ${result.tracks.size} tracks. Tap Build playlist."
                                    } else {
                                        lastResearch = null
                                        researchOut = ""
                                        buildOk = false
                                        buildMsg = err ?: "Research failed"
                                    }
                                    workStep = null
                                    busy = false
                                }
                            },
                        ) {
                            Text("Research set", color = GrokifyColors.GlowViolet, fontSize = 13.sp)
                        }
                        TextButton(
                            enabled = !busy && lastResearch != null && loggedIn,
                            onClick = {
                                val data = lastResearch ?: return@TextButton
                                if (busy) return@TextButton
                                busy = true
                                workStep = "Building playlist…"
                                buildMsg = null
                                scope.launch {
                                    val outcome = withContext(Dispatchers.IO) {
                                        SpotifyPlaylistAi.build(appCtx, data) { step ->
                                            setOnMain { workStep = step }
                                        }
                                    }
                                    buildOk = outcome.ok
                                    buildMsg = outcome.message
                                    workStep = null
                                    busy = false
                                    if (outcome.ok) {
                                        // Refresh playlist list for edit section
                                        val (pls, _) = withContext(Dispatchers.IO) {
                                            SpotifyPlaylistAi.listPlaylists(appCtx)
                                        }
                                        playlists = pls
                                    }
                                }
                            },
                        ) {
                            Text("Build playlist", color = GrokifyColors.GlowMint, fontSize = 13.sp)
                        }
                    }
                    if (!loggedIn) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Connect Spotify under Account to build playlists (research still works).",
                            color = GrokifyColors.GlowAmber,
                            fontSize = 11.sp,
                        )
                    }
                    if (workStep != null) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = GrokifyColors.GlowViolet,
                            trackColor = GrokifyColors.PanelSoft,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(workStep!!, color = GrokifyColors.TextMuted, fontSize = 12.sp)
                    }
                    buildMsg?.let { msg ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            msg,
                            color = if (buildOk) GrokifyColors.GlowMint else GrokifyColors.GlowRose,
                            fontSize = 12.sp,
                        )
                    }
                    if (researchOut.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            researchOut,
                            color = GrokifyColors.TextPrimary,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(GrokifyColors.PanelSoft)
                                .padding(12.dp),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Edit existing playlist with prompt
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(GrokifyColors.Panel)
                        .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                ) {
                    Text(
                        "EDIT PLAYLIST",
                        style = MaterialTheme.typography.labelSmall,
                        color = GrokifyColors.GlowCyan,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Pick a playlist, describe the change (more upbeat, swap ballads, add 5 hip-hop cuts…), " +
                            "then Research edit → Apply.",
                        color = GrokifyColors.TextDim,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = !busy && loggedIn,
                            onClick = {
                                if (busy) return@TextButton
                                busy = true
                                playlistLoadMsg = "Loading playlists…"
                                scope.launch {
                                    val (pls, err) = withContext(Dispatchers.IO) {
                                        SpotifyPlaylistAi.listPlaylists(appCtx)
                                    }
                                    playlists = pls
                                    playlistLoadMsg = err
                                        ?: if (pls.isEmpty()) "No playlists found"
                                        else "${pls.size} playlists"
                                    if (selectedPlaylistId == null && pls.isNotEmpty()) {
                                        selectedPlaylistId = pls.first().id
                                    }
                                    busy = false
                                }
                            },
                        ) {
                            Text("Load playlists", color = GrokifyColors.GlowCyan, fontSize = 13.sp)
                        }
                    }
                    playlistLoadMsg?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, color = GrokifyColors.TextMuted, fontSize = 11.sp)
                    }
                    if (playlists.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        val plScroll = rememberScrollState()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(plScroll),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            playlists.take(40).forEach { pl ->
                                val selected = selectedPlaylistId == pl.id
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        selectedPlaylistId = pl.id
                                        lastEdit = null
                                        editOut = ""
                                    },
                                    label = {
                                        Text(
                                            pl.name.take(28) + if (pl.trackCount > 0) " (${pl.trackCount})" else "",
                                            fontSize = 11.sp,
                                            maxLines = 1,
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
                                        selected = selected,
                                        borderColor = GrokifyColors.PanelBorder,
                                        selectedBorderColor = GrokifyColors.GlowCyan,
                                    ),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = editPrompt,
                        onValueChange = { editPrompt = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        placeholder = {
                            Text(
                                "e.g. Drop slow tracks, add 6 modern trap bangers, keep the vibe dark",
                                color = GrokifyColors.TextDim,
                                fontSize = 13.sp,
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = GrokifyColors.TextPrimary,
                            unfocusedTextColor = GrokifyColors.TextPrimary,
                            focusedBorderColor = GrokifyColors.GlowCyan,
                            unfocusedBorderColor = GrokifyColors.PanelBorder,
                            cursorColor = GrokifyColors.GlowCyan,
                        ),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = !busy && loggedIn &&
                                !selectedPlaylistId.isNullOrBlank() && editPrompt.isNotBlank(),
                            onClick = {
                                val pid = selectedPlaylistId ?: return@TextButton
                                val pl = playlists.firstOrNull { it.id == pid } ?: return@TextButton
                                if (busy) return@TextButton
                                busy = true
                                workStep = "Loading tracks…"
                                buildMsg = null
                                scope.launch {
                                    val (tracks, tErr) = withContext(Dispatchers.IO) {
                                        SpotifyPlaylistAi.loadTracks(appCtx, pid)
                                    }
                                    if (tracks.isEmpty()) {
                                        buildOk = false
                                        buildMsg = tErr ?: "No tracks in playlist"
                                        workStep = null
                                        busy = false
                                        return@launch
                                    }
                                    workStep = "Planning edits…"
                                    val (plan, err) = withContext(Dispatchers.IO) {
                                        SpotifyPlaylistAi.researchEdit(
                                            appCtx,
                                            pl,
                                            tracks,
                                            editPrompt,
                                        ) { step -> setOnMain { workStep = step } }
                                    }
                                    if (plan != null) {
                                        lastEdit = plan
                                        editOut = buildString {
                                            if (plan.notes.isNotBlank()) {
                                                appendLine(plan.notes)
                                                appendLine()
                                            }
                                            if (plan.removeUris.isNotEmpty()) {
                                                appendLine("Remove ${plan.removeUris.size}:")
                                                plan.removeUris.take(12).forEach { appendLine("  − $it") }
                                                appendLine()
                                            }
                                            if (plan.addTracks.isNotEmpty()) {
                                                appendLine("Add ${plan.addTracks.size}:")
                                                plan.addTracks.forEachIndexed { i, t ->
                                                    appendLine(
                                                        "  + ${t.query}" +
                                                            if (t.reason.isNotBlank()) " — ${t.reason}" else "",
                                                    )
                                                }
                                            }
                                            plan.newName?.let { appendLine("\nRename → $it") }
                                            plan.newDescription?.let { appendLine("Desc → $it") }
                                        }
                                        buildOk = true
                                        buildMsg =
                                            "Edit plan ready: −${plan.removeUris.size} · +${plan.addTracks.size}. Tap Apply edit."
                                    } else {
                                        lastEdit = null
                                        editOut = ""
                                        buildOk = false
                                        buildMsg = err ?: "Edit research failed"
                                    }
                                    workStep = null
                                    busy = false
                                }
                            },
                        ) {
                            Text("Research edit", color = GrokifyColors.GlowCyan, fontSize = 13.sp)
                        }
                        TextButton(
                            enabled = !busy && lastEdit != null && !selectedPlaylistId.isNullOrBlank(),
                            onClick = {
                                val pid = selectedPlaylistId ?: return@TextButton
                                val plan = lastEdit ?: return@TextButton
                                if (busy) return@TextButton
                                busy = true
                                workStep = "Applying edits…"
                                scope.launch {
                                    val outcome = withContext(Dispatchers.IO) {
                                        SpotifyPlaylistAi.applyEdit(appCtx, pid, plan) { step ->
                                            setOnMain { workStep = step }
                                        }
                                    }
                                    buildOk = outcome.ok
                                    buildMsg = outcome.message
                                    if (outcome.ok) {
                                        lastEdit = null
                                        val (pls, _) = withContext(Dispatchers.IO) {
                                            SpotifyPlaylistAi.listPlaylists(appCtx)
                                        }
                                        playlists = pls
                                    }
                                    workStep = null
                                    busy = false
                                }
                            },
                        ) {
                            Text("Apply edit", color = GrokifyColors.GlowMint, fontSize = 13.sp)
                        }
                    }
                    if (editOut.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            editOut,
                            color = GrokifyColors.TextPrimary,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(GrokifyColors.PanelSoft)
                                .padding(12.dp),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "Research uses host Grok Build (Home device token). Build/Edit need Spotify login. " +
                        "Live DJ banter still uses xAI Voice or device TTS under Live DJ.",
                    color = GrokifyColors.TextDim,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(24.dp))
            }

            else -> Column(Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = true)) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(GrokifyColors.Panel)
                        .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(14.dp))
                        .padding(16.dp),
                ) {
                    Text(
                        "SPOTIFY API",
                        style = MaterialTheme.typography.labelSmall,
                        color = GrokifyColors.GlowViolet,
                    )
                    Spacer(Modifier.height(8.dp))
                    StatusLine(
                        ok = loggedIn,
                        okText = "Linked",
                        badText = if (clientId.isBlank()) "Need client id (host Keys or below)" else "Not linked",
                    )
                    if (authMsg.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(authMsg, color = GrokifyColors.TextMuted, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Client ID", color = GrokifyColors.TextDim, fontSize = 11.sp)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = clientId,
                        onValueChange = { clientId = it.trim() },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text("from developer.spotify.com", color = GrokifyColors.TextDim)
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = GrokifyColors.TextPrimary,
                            unfocusedTextColor = GrokifyColors.TextPrimary,
                            focusedBorderColor = GrokifyColors.GlowMint,
                            unfocusedBorderColor = GrokifyColors.PanelBorder,
                            cursorColor = GrokifyColors.GlowMint,
                        ),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Redirect URI (exact):\n${SpotifyOAuth.REDIRECT_URI}",
                        color = GrokifyColors.TextDim,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    // Primary action always available — including when already connected.
                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    if (clientId.isNotBlank()) {
                                        HostApiKeyStore.save(
                                            appCtx,
                                            ApiKeyIds.SPOTIFY_CLIENT_ID,
                                            clientId,
                                            label = "Spotify Client ID",
                                        )
                                    }
                                    val raw = if (loggedIn) {
                                        SpotifyOAuth.reauthorize(appCtx)
                                    } else {
                                        SpotifyOAuth.startLogin(appCtx)
                                    }
                                    authMsg = runCatching {
                                        JSONObject(raw).optString("error")
                                            .ifBlank {
                                                JSONObject(raw).optString("status", "opened")
                                            }
                                    }.getOrElse { SpotifyOAuth.lastAuthMessage.orEmpty() }
                                    if (authMsg == "opened" || authMsg.isBlank()) {
                                        authMsg = SpotifyOAuth.lastAuthMessage
                                            ?: if (loggedIn) {
                                                "Browser opened — approve permissions"
                                            } else {
                                                "Browser opened for Spotify login"
                                            }
                                    }
                                }
                                busy = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GrokifyColors.GlowMint.copy(alpha = 0.22f),
                            contentColor = GrokifyColors.GlowMint,
                            disabledContainerColor = GrokifyColors.PanelSoft,
                            disabledContentColor = GrokifyColors.TextDim,
                        ),
                        border = BorderStroke(1.dp, GrokifyColors.GlowMint.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (loggedIn) "Re-authorize Spotify" else "Connect Spotify",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                        )
                    }
                    if (loggedIn) {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                enabled = !busy,
                                onClick = {
                                    SpotifyOAuth.logout(appCtx)
                                    loggedIn = false
                                    authMsg = "Logged out"
                                    setSpotifyLiveDjEnabled(appCtx, false)
                                },
                            ) {
                                Text("Logout", color = GrokifyColors.GlowAmber, fontSize = 13.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "Settings → API keys → Spotify Client ID · Redirect URI exact · PKCE",
                    color = GrokifyColors.TextDim,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    val pendingDislike = dislikeTarget
    if (pendingDislike != null) {
        DislikeReasonsDialog(
            trackTitle = pendingDislike.name.ifBlank { "This track" },
            artists = pendingDislike.artists,
            onDismiss = { dislikeTarget = null },
            onConfirm = { reasons ->
                dislikeTarget = null
                trackLikedMsg = "Saving dislike…"
                spotifyLiveDjDislike(
                    appCtx,
                    trackUri = pendingDislike.trackUri,
                    name = pendingDislike.name,
                    artists = pendingDislike.artists,
                    artistUri = pendingDislike.artistUri,
                    artistIds = pendingDislike.artistIds,
                    reasons = reasons,
                    skipIfPlaying = pendingDislike.skipIfPlaying,
                )
                dislikeRevision += 1
            },
        )
    }
}

/** Multi-select reasons when the user dislikes a cut (Control + chat bubbles). */
@Composable
private fun DislikeReasonsDialog(
    trackTitle: String,
    artists: String,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var pickArtist by remember { mutableStateOf(false) }
    var pickSong by remember { mutableStateOf(false) }
    var pickLyrics by remember { mutableStateOf(false) }
    var pickTired by remember { mutableStateOf(false) }
    val any = pickArtist || pickSong || pickLyrics || pickTired
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GrokifyColors.Panel,
        title = {
            Text("Not feeling this?", color = GrokifyColors.TextPrimary)
        },
        text = {
            Column {
                Text(
                    buildString {
                        append("“${trackTitle.take(60)}”")
                        if (artists.isNotBlank()) append(" — ${artists.take(48)}")
                    },
                    color = GrokifyColors.TextMuted,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Why? Pick any that apply — Live DJ won’t put matching cuts back in UP NEXT.",
                    color = GrokifyColors.TextDim,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(10.dp))
                DislikeReasonRow(
                    checked = pickArtist,
                    onCheckedChange = { pickArtist = it },
                    title = "The artist",
                    subtitle = "Never queue this artist again",
                )
                DislikeReasonRow(
                    checked = pickSong,
                    onCheckedChange = { pickSong = it },
                    title = "This song",
                    subtitle = "Block this track permanently",
                )
                DislikeReasonRow(
                    checked = pickLyrics,
                    onCheckedChange = { pickLyrics = it },
                    title = "The lyrics",
                    subtitle = "Keep this track out of the set",
                )
                DislikeReasonRow(
                    checked = pickTired,
                    onCheckedChange = { pickTired = it },
                    title = "Tired of hearing it for now",
                    subtitle = "Cool-down ~14 days, then it can return",
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = any,
                onClick = {
                    val reasons = buildSet {
                        if (pickArtist) add(DjDislikeReason.ARTIST)
                        if (pickSong) add(DjDislikeReason.SONG)
                        if (pickLyrics) add(DjDislikeReason.LYRICS)
                        if (pickTired) add(DjDislikeReason.TIRED)
                    }
                    onConfirm(reasons)
                },
            ) {
                Text(
                    "Keep out of queue",
                    color = if (any) GrokifyColors.GlowAmber else GrokifyColors.TextDim,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = GrokifyColors.TextMuted)
            }
        },
    )
}

@Composable
private fun DislikeReasonRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    subtitle: String,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = GrokifyColors.GlowAmber,
                uncheckedColor = GrokifyColors.TextDim,
                checkmarkColor = GrokifyColors.Void,
            ),
        )
        Column(Modifier.weight(1f)) {
            Text(title, color = GrokifyColors.TextPrimary, fontSize = 14.sp)
            Text(subtitle, color = GrokifyColors.TextDim, fontSize = 11.sp)
        }
    }
}

/** Scroll a LazyColumn so the last item’s bottom is fully visible (tall track cards). */
private suspend fun LazyListState.scrollChatToBottom() {
    val last = layoutInfo.totalItemsCount - 1
    if (last < 0) return
    // Land on the last item first…
    scrollToItem(last, scrollOffset = 0)
    // …then nudge so its bottom edge sits in the viewport (animateScrollToItem alone
    // only aligns the *top* of a tall now-playing bubble).
    val info = layoutInfo
    val lastInfo = info.visibleItemsInfo.lastOrNull { it.index == last }
        ?: info.visibleItemsInfo.lastOrNull()
        ?: return
    val bottom = lastInfo.offset + lastInfo.size
    val viewportEnd = info.viewportEndOffset
    val gap = (bottom - viewportEnd + info.afterContentPadding).coerceAtLeast(0)
    if (gap > 0) {
        runCatching { animateScrollBy(gap.toFloat() + 16f) }
    }
}

@Composable
private fun LikeTrackButton(
    liked: Boolean,
    enabled: Boolean,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    if (liked) GrokifyColors.GlowMint.copy(alpha = 0.18f)
                    else GrokifyColors.PanelSoft,
                )
                .border(
                    1.dp,
                    if (liked) GrokifyColors.GlowMint.copy(alpha = 0.55f)
                    else GrokifyColors.PanelBorder,
                    CircleShape,
                )
                .clickable(enabled = enabled && !busy, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = GrokifyColors.GlowMint,
                )
            } else {
                Icon(
                    if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (liked) "Unlike" else "Like — save to Liked Songs",
                    tint = when {
                        !enabled -> GrokifyColors.TextDim
                        liked -> GrokifyColors.GlowMint
                        else -> GrokifyColors.TextPrimary
                    },
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            if (liked) "Liked" else "Like",
            color = if (liked) GrokifyColors.GlowMint else GrokifyColors.TextDim,
            fontSize = 10.sp,
        )
    }
}

/** Full local date + time to the second (DJ chat context). */
private fun formatDjChatTimestamp(ms: Long): String {
    if (ms <= 0L) return ""
    return try {
        java.text.SimpleDateFormat("MMM d, yyyy · HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(ms))
    } catch (_: Exception) {
        ""
    }
}

@Composable
private fun DjChatBubble(
    msg: DjChatMessage,
    onPrev: () -> Unit,
    onPauseToggle: () -> Unit,
    onSkip: () -> Unit,
    onHardSkip: () -> Unit = onSkip,
    liked: Boolean = false,
    likeBusy: Boolean = false,
    disliked: Boolean = false,
    onLikeToggle: (() -> Unit)? = null,
    onPlayTrack: (DjChatMessage) -> Unit = {},
    onMoreLikeThis: (DjChatMessage) -> Unit = {},
    onDislike: (DjChatMessage) -> Unit = {},
) {
    val tsLabel = formatDjChatTimestamp(msg.ts)
    when (msg.role) {
        DjChatRole.User -> {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Column(
                    Modifier
                        .fillMaxWidth(0.88f)
                        .clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                        .background(GrokifyColors.UserBubble)
                        .border(1.dp, GrokifyColors.GlowBlue.copy(alpha = 0.25f), RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                        .padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("YOU", style = MaterialTheme.typography.labelSmall, color = GrokifyColors.GlowBlue)
                        if (tsLabel.isNotBlank()) {
                            Spacer(Modifier.weight(1f))
                            Text(
                                tsLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = GrokifyColors.TextDim,
                                fontSize = 10.sp,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(msg.text, color = GrokifyColors.TextPrimary, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
        }
        DjChatRole.Dj -> {
            Column(
                Modifier
                    .fillMaxWidth(0.94f)
                    .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                    .background(GrokifyColors.AssistantBubble)
                    .border(1.dp, GrokifyColors.GlowMint.copy(alpha = 0.22f), RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("LIVE DJ", style = MaterialTheme.typography.labelSmall, color = GrokifyColors.GlowMint)
                    if (msg.streaming) {
                        Spacer(Modifier.width(8.dp))
                        Text("…", color = GrokifyColors.TextMuted, fontSize = 12.sp)
                    }
                    if (tsLabel.isNotBlank()) {
                        Spacer(Modifier.weight(1f))
                        Text(
                            tsLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = GrokifyColors.TextDim,
                            fontSize = 10.sp,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (msg.text.isBlank() && msg.streaming) "…" else msg.text,
                    color = GrokifyColors.TextPrimary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            }
        }
        DjChatRole.Track -> {
            val context = LocalContext.current
            val accent = if (msg.isNowPlaying) GrokifyColors.GlowCyan else GrokifyColors.PanelBorder
            // Smooth progress between Spotify polls (~2.5s) while the track is playing.
            var displayProgress by remember(msg.id, msg.progressMs) {
                mutableLongStateOf(msg.progressMs)
            }
            LaunchedEffect(msg.id, msg.progressMs, msg.isPlaying, msg.isNowPlaying, msg.durationMs) {
                displayProgress = msg.progressMs
                if (!msg.isNowPlaying || !msg.isPlaying || msg.durationMs <= 0L) return@LaunchedEffect
                while (true) {
                    delay(400)
                    displayProgress = (displayProgress + 400L).coerceAtMost(msg.durationMs)
                }
            }
            val progressFrac = if (msg.durationMs > 0L) {
                (displayProgress.toFloat() / msg.durationMs.toFloat()).coerceIn(0f, 1f)
            } else 0f
            val artists = msg.trackArtists.orEmpty().ifBlank {
                msg.text.lineSequence().drop(1).firstOrNull().orEmpty()
            }
            val trackTitle = msg.trackName ?: msg.text.lineSequence().firstOrNull().orEmpty()
            val thumbUrl = msg.artistArtUrl?.takeIf { it.isNotBlank() }
                ?: msg.albumArtUrl?.takeIf { it.isNotBlank() }
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(
                        1.dp,
                        accent.copy(alpha = if (msg.isNowPlaying) 0.55f else 1f),
                        RoundedCornerShape(14.dp),
                    ),
            ) {
                // Song/album art fills the bubble
                if (!msg.albumArtUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = msg.albumArtUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                }
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            when {
                                !msg.albumArtUrl.isNullOrBlank() -> GrokifyColors.Void.copy(alpha = 0.78f)
                                msg.isNowPlaying -> GrokifyColors.GlowCyan.copy(alpha = 0.08f)
                                else -> GrokifyColors.Panel
                            },
                        ),
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        // Artist portrait thumbnail (opens artist)
                        Box(
                            Modifier
                                .size(if (msg.isNowPlaying) 64.dp else 48.dp)
                                .clip(CircleShape)
                                .background(GrokifyColors.PanelSoft)
                                .clickable {
                                    when {
                                        !msg.artistUri.isNullOrBlank() ->
                                            openSpotifyContent(context, msg.artistUri)
                                        !msg.albumUri.isNullOrBlank() ->
                                            openSpotifyContent(context, msg.albumUri)
                                        !msg.trackUri.isNullOrBlank() ->
                                            openSpotifyContent(context, msg.trackUri)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (!thumbUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = thumbUrl,
                                    contentDescription = "Artist",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Icon(
                                    Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = if (msg.isNowPlaying) GrokifyColors.GlowCyan else GrokifyColors.TextMuted,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (msg.isNowPlaying) {
                                        if (msg.isPlaying) "NOW PLAYING" else "PAUSED"
                                    } else {
                                        "PLAYED"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (msg.isNowPlaying) GrokifyColors.GlowCyan else GrokifyColors.TextDim,
                                )
                                if (tsLabel.isNotBlank()) {
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        tsLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = GrokifyColors.TextDim,
                                        fontSize = 10.sp,
                                    )
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                trackTitle,
                                color = GrokifyColors.TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable {
                                    when {
                                        !msg.trackUri.isNullOrBlank() ->
                                            openSpotifyContent(context, msg.trackUri)
                                        !msg.albumUri.isNullOrBlank() ->
                                            openSpotifyContent(context, msg.albumUri)
                                    }
                                },
                            )
                            if (artists.isNotBlank()) {
                                Text(
                                    artists,
                                    color = GrokifyColors.TextMuted,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clickable {
                                        when {
                                            !msg.artistUri.isNullOrBlank() ->
                                                openSpotifyContent(context, msg.artistUri)
                                            !msg.albumUri.isNullOrBlank() ->
                                                openSpotifyContent(context, msg.albumUri)
                                        }
                                    },
                                )
                            }
                        }
                    }
                    // Song progression on the now-playing (and past tracks with known duration)
                    if (msg.isNowPlaying || msg.durationMs > 0L) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { if (msg.isNowPlaying) progressFrac else 1f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = if (msg.isNowPlaying) GrokifyColors.GlowCyan else GrokifyColors.TextDim,
                            trackColor = GrokifyColors.PanelSoft.copy(alpha = 0.9f),
                        )
                        if (msg.isNowPlaying && msg.durationMs > 0L) {
                            Spacer(Modifier.height(4.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    formatTrackClock(displayProgress),
                                    color = GrokifyColors.TextDim,
                                    fontSize = 11.sp,
                                )
                                Text(
                                    formatTrackClock(msg.durationMs),
                                    color = GrokifyColors.TextDim,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                    // Transport on the latest (now-playing) bubble — works in booth mode too
                    if (msg.isNowPlaying) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = onPrev) {
                                Icon(
                                    Icons.Default.SkipPrevious,
                                    contentDescription = "Restart / previous",
                                    tint = GrokifyColors.TextPrimary,
                                )
                            }
                            IconButton(onClick = onPauseToggle) {
                                Icon(
                                    if (msg.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (msg.isPlaying) "Pause" else "Play",
                                    tint = GrokifyColors.GlowMint,
                                )
                            }
                            var lastSkipAt by remember { mutableLongStateOf(0L) }
                            IconButton(
                                onClick = {
                                    val nowMs = android.os.SystemClock.elapsedRealtime()
                                    if (lastSkipAt > 0L && nowMs - lastSkipAt < 1_600L) {
                                        onHardSkip()
                                    } else {
                                        onSkip()
                                    }
                                    lastSkipAt = nowMs
                                },
                            ) {
                                Icon(
                                    Icons.Default.SkipNext,
                                    contentDescription = "Skip · double-tap cuts research/banter",
                                    tint = GrokifyColors.TextPrimary,
                                )
                            }
                            if (onLikeToggle != null) {
                                IconButton(
                                    onClick = onLikeToggle,
                                    enabled = !likeBusy && !msg.trackUri.isNullOrBlank(),
                                ) {
                                    if (likeBusy) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                            color = GrokifyColors.GlowMint,
                                        )
                                    } else {
                                        Icon(
                                            if (liked) Icons.Default.Favorite
                                            else Icons.Default.FavoriteBorder,
                                            contentDescription = if (liked) {
                                                "Unlike — remove from Liked Songs"
                                            } else {
                                                "Like — save to Liked Songs"
                                            },
                                            tint = if (liked) {
                                                GrokifyColors.GlowMint
                                            } else {
                                                GrokifyColors.TextPrimary
                                            },
                                        )
                                    }
                                }
                            }
                            // More like this — same artist + related, prepend to UP NEXT
                            IconButton(
                                onClick = { onMoreLikeThis(msg) },
                                enabled = !msg.trackUri.isNullOrBlank() ||
                                    !msg.trackName.isNullOrBlank() ||
                                    !msg.trackArtists.isNullOrBlank(),
                            ) {
                                Icon(
                                    Icons.Default.PlaylistAdd,
                                    contentDescription = "More like this — queue similar cuts next",
                                    tint = GrokifyColors.GlowCyan,
                                )
                            }
                            // Dislike — white when not disliked, yellow when blocked/tired
                            IconButton(
                                onClick = { onDislike(msg) },
                                enabled = !msg.trackUri.isNullOrBlank() ||
                                    !msg.trackName.isNullOrBlank() ||
                                    !msg.trackArtists.isNullOrBlank(),
                            ) {
                                Icon(
                                    Icons.Default.ThumbDown,
                                    contentDescription = if (disliked) {
                                        "Disliked — blocked from Live DJ queue"
                                    } else {
                                        "Dislike — keep out of Live DJ queue"
                                    },
                                    tint = if (disliked) GrokifyColors.GlowAmber else Color.White,
                                )
                            }
                        }
                    } else if (!msg.trackUri.isNullOrBlank()) {
                        // Replay past songs + heart + more-like-this + dislike from chat history
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (onLikeToggle != null) {
                                IconButton(
                                    onClick = onLikeToggle,
                                    enabled = !likeBusy && !msg.trackUri.isNullOrBlank(),
                                ) {
                                    if (likeBusy) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = GrokifyColors.GlowMint,
                                        )
                                    } else {
                                        Icon(
                                            if (liked) Icons.Default.Favorite
                                            else Icons.Default.FavoriteBorder,
                                            contentDescription = if (liked) {
                                                "Unlike — remove from Liked Songs"
                                            } else {
                                                "Like — save to Liked Songs"
                                            },
                                            tint = if (liked) {
                                                GrokifyColors.GlowMint
                                            } else {
                                                GrokifyColors.TextPrimary
                                            },
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = { onDislike(msg) }) {
                                Icon(
                                    Icons.Default.ThumbDown,
                                    contentDescription = if (disliked) {
                                        "Disliked — blocked from Live DJ queue"
                                    } else {
                                        "Dislike — keep out of Live DJ queue"
                                    },
                                    tint = if (disliked) GrokifyColors.GlowAmber else Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            TextButton(onClick = { onMoreLikeThis(msg) }) {
                                Icon(
                                    Icons.Default.PlaylistAdd,
                                    contentDescription = null,
                                    tint = GrokifyColors.GlowCyan,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("More like this", color = GrokifyColors.GlowCyan, fontSize = 12.sp)
                            }
                            TextButton(onClick = { onPlayTrack(msg) }) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint = GrokifyColors.GlowMint,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Play", color = GrokifyColors.GlowMint, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
        DjChatRole.System -> {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(GrokifyColors.PanelSoft)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    if (tsLabel.isNotBlank()) {
                        Text(
                            tsLabel,
                            color = GrokifyColors.TextDim,
                            fontSize = 10.sp,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                    Text(
                        msg.text,
                        color = GrokifyColors.TextDim,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveDjBlocksTab(
    modifier: Modifier = Modifier,
    djStore: SpotifyDjStore,
    revision: Int,
    onRevision: () -> Unit,
) {
    val songs = remember(revision) { djStore.listBlockedTracks() }
    val artists = remember(revision) { djStore.listBlockedArtists() }
    val tired = remember(revision) { djStore.listTiredTracks() }
    var lookingUp by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val needs =
            djStore.listBlockedTracks().any { djBlockedTrackNeedsLabel(it) } ||
                djStore.listBlockedArtists().any { djBlockedArtistNeedsLabel(it) } ||
                djStore.listTiredTracks().any { djTiredTrackNeedsLabel(it) }
        if (!needs) return@LaunchedEffect
        lookingUp = true
        val changed = withContext(Dispatchers.IO) {
            djStore.resolveBlockedLabelsFromSpotify()
        }
        lookingUp = false
        if (changed) onRevision()
    }
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            "DISLIKES & COOLDOWNS",
            style = MaterialTheme.typography.labelSmall,
            color = GrokifyColors.GlowAmber,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Songs, artists, and 14-day cooldowns Live DJ will not queue. " +
                "Counts + last time help catch repeats. Remasters and “A & B” vs “A, B” " +
                "credit lines match the same block.",
            color = GrokifyColors.TextDim,
            fontSize = 10.sp,
        )
        if (lookingUp) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Looking up song and artist names…",
                color = GrokifyColors.GlowCyan,
                fontSize = 10.sp,
            )
        }
        Spacer(Modifier.height(12.dp))

        Text(
            "SONGS (${songs.size})",
            style = MaterialTheme.typography.labelSmall,
            color = GrokifyColors.GlowCyan,
        )
        Spacer(Modifier.height(4.dp))
        if (songs.isEmpty()) {
            Text(
                "none blocked",
                color = GrokifyColors.TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        } else {
            songs.forEach { t ->
                DjBlockRow(
                    title = djBlockedTrackTitle(t),
                    subtitle = buildString {
                        val who = djBlockedTrackArtists(t)
                        if (who.isNotBlank()) append(who)
                        val why = t.reasons.joinToString(" · ") { DjDislikeReason.label(it) }
                        if (why.isNotBlank()) {
                            if (isNotEmpty()) append(" · ")
                            append(why)
                        }
                        append(" · ×${t.count}")
                        val whenLabel = formatDjRelativeTime(t.lastTs)
                        if (whenLabel.isNotBlank()) append(" · $whenLabel")
                    },
                    onClear = {
                        djStore.removeBlockedTrack(t.uri)
                        onRevision()
                    },
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "ARTISTS (${artists.size})",
            style = MaterialTheme.typography.labelSmall,
            color = GrokifyColors.GlowCyan,
        )
        Spacer(Modifier.height(4.dp))
        if (artists.isEmpty()) {
            Text(
                "none blocked",
                color = GrokifyColors.TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        } else {
            artists.forEach { a ->
                DjBlockRow(
                    title = djBlockedArtistTitle(a),
                    subtitle = buildString {
                        append("×${a.count}")
                        val whenLabel = formatDjRelativeTime(a.lastTs)
                        if (whenLabel.isNotBlank()) append(" · $whenLabel")
                        val also = a.aliases
                            .filter { djIsUsableLabel(it) && it != a.name.trim().lowercase() }
                            .take(3)
                        if (also.isNotEmpty()) {
                            append(" · also ")
                            append(also.joinToString())
                        }
                    },
                    onClear = {
                        djStore.removeBlockedArtist(a.key)
                        onRevision()
                    },
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "TIRED FOR NOW (${tired.size})",
            style = MaterialTheme.typography.labelSmall,
            color = GrokifyColors.GlowCyan,
        )
        Spacer(Modifier.height(4.dp))
        if (tired.isEmpty()) {
            Text(
                "no cooldowns",
                color = GrokifyColors.TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        } else {
            tired.forEach { t ->
                DjBlockRow(
                    title = djTiredTrackTitle(t),
                    subtitle = buildString {
                        val who = djTiredTrackArtists(t)
                        if (who.isNotBlank()) append(who).append(" · ")
                        append(djTiredRemainingLabel(t.until))
                        append(" · ×${t.count}")
                    },
                    clearLabel = "Allow now",
                    onClear = {
                        djStore.removeTiredTrack(t.uri)
                        onRevision()
                    },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DjBlockRow(
    title: String,
    subtitle: String,
    clearLabel: String = "Clear",
    onClear: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = GrokifyColors.TextPrimary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color = GrokifyColors.TextDim,
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TextButton(onClick = onClear) {
            Text(clearLabel, color = GrokifyColors.GlowAmber, fontSize = 11.sp)
        }
    }
}

private fun formatDjRelativeTime(ms: Long, now: Long = System.currentTimeMillis()): String {
    if (ms <= 0L) return ""
    val delta = (now - ms).coerceAtLeast(0L)
    val min = 60_000L
    val hour = 60 * min
    val day = 24 * hour
    return when {
        delta < min -> "just now"
        delta < hour -> "${delta / min}m ago"
        delta < day -> "${delta / hour}h ago"
        delta < 14 * day -> "${delta / day}d ago"
        else -> formatDjChatTimestamp(ms).substringBefore(" ·")
    }
}

@Composable
private fun LiveDjSettingsTab(
    modifier: Modifier = Modifier,
    appCtx: Context,
    djStore: SpotifyDjStore,
    djState: SpotifyDjUiState,
    scope: kotlinx.coroutines.CoroutineScope,
    loggedIn: Boolean,
    hasXaiKey: Boolean,
) {
    var voiceId by remember { mutableStateOf(djStore.voiceId) }
    var useAiRank by remember { mutableStateOf(djStore.useAiRank) }
    var banterMode by remember { mutableStateOf(djStore.banterMode) }
    var banterFixed by remember { mutableStateOf(djStore.banterFixed) }
    var banterMin by remember { mutableStateOf(djStore.banterMin) }
    var banterMax by remember { mutableStateOf(djStore.banterMax) }
    var allowTalkOver by remember { mutableStateOf(djStore.allowTalkOver) }
    var banterEnabled by remember { mutableStateOf(djStore.banterEnabled) }
    var resumeAfterRestart by remember { mutableStateOf(djStore.resumeAfterRestart) }
    var behaviorMode by remember { mutableStateOf(djStore.behaviorMode) }
    var activeBehaviorId by remember { mutableStateOf(djStore.activeBehaviorId) }
    var promptTemplates by remember { mutableStateOf(djStore.loadPromptTemplates()) }
    var editingPromptId by remember { mutableStateOf<String?>(null) }
    var editPromptLabel by remember { mutableStateOf("") }
    var editPromptBlurb by remember { mutableStateOf("") }
    var editPromptBody by remember { mutableStateOf("") }
    var promptEditorKind by remember { mutableStateOf(DjPromptKind.Research) }
    var promptEditorMsg by remember { mutableStateOf<String?>(null) }
    var selectedGenres by remember { mutableStateOf(djStore.selectedGenres) }
    var genreBoard by remember { mutableStateOf(djStore.genreBoard) }
    var listenerCity by remember { mutableStateOf(djStore.listenerCity) }
    var listenerName by remember { mutableStateOf(djStore.listenerName) }
    var genreBoardBusy by remember { mutableStateOf(false) }
    var genreBoardMsg by remember { mutableStateOf<String?>(null) }
    var nameBusy by remember { mutableStateOf(false) }
    var voicePreviewMsg by remember { mutableStateOf<String?>(null) }
    var queueSourcesOn by remember {
        mutableStateOf(djStore.queueSourcesEnabled)
    }
    var queuePlaylistIds by remember {
        mutableStateOf(djStore.queuePlaylistIds)
    }
    var queuePlaylistCache by remember {
        mutableStateOf(djStore.queuePlaylistCache)
    }
    var queuePlaylistBusy by remember { mutableStateOf(false) }
    var queueSystemMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(
        djState.selectedGenres,
        djState.genreBoard,
        djState.behaviorMode,
        djState.listenerCity,
        djState.listenerName,
        djState.banterEnabled,
        djState.banterMode,
    ) {
        selectedGenres = djState.selectedGenres.ifEmpty { djStore.selectedGenres }
        genreBoard = djState.genreBoard.ifEmpty { djStore.genreBoard }
        behaviorMode = djState.behaviorMode
        activeBehaviorId = djStore.activeBehaviorId
        if (djState.listenerCity.isNotBlank() || listenerCity.isBlank()) {
            listenerCity = djState.listenerCity.ifBlank { djStore.listenerCity }
        }
        if (djState.listenerName.isNotBlank() || listenerName.isBlank()) {
            listenerName = djState.listenerName.ifBlank { djStore.listenerName }
        }
        banterEnabled = djState.banterEnabled
        banterMode = djState.banterMode
        allowTalkOver = djState.allowTalkOver
        resumeAfterRestart = djState.resumeAfterRestart
        voiceId = djState.voiceId.ifBlank { djStore.voiceId }
        useAiRank = djState.useAiRank
    }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "AI rank next tracks",
                                color = GrokifyColors.TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                "Host Grok shapes the set (optional)",
                                color = GrokifyColors.TextDim,
                                fontSize = 10.sp,
                            )
                        }
                        Switch(
                            checked = useAiRank,
                            onCheckedChange = {
                                useAiRank = it
                                djStore.useAiRank = it
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = GrokifyColors.Void,
                                checkedTrackColor = GrokifyColors.GlowViolet,
                                uncheckedThumbColor = GrokifyColors.TextMuted,
                                uncheckedTrackColor = GrokifyColors.PanelSoft,
                            ),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (hasXaiKey) "Grok Voice · xAI key found" else "Grok Voice · add xAI key or use device TTS",
                        color = GrokifyColors.TextDim,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    val voiceScroll = rememberScrollState()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(voiceScroll),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GROK_VOICES.forEach { v ->
                            val selected = voiceId.equals(v.id, ignoreCase = true)
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    voiceId = v.id
                                    djStore.voiceId = v.id
                                    voicePreviewMsg = "${v.label} — ${v.tone}"
                                },
                                label = { Text(v.label, fontSize = 12.sp, maxLines = 1) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = GrokifyColors.GlowMint.copy(alpha = 0.25f),
                                    selectedLabelColor = GrokifyColors.GlowMint,
                                    containerColor = GrokifyColors.PanelSoft,
                                    labelColor = GrokifyColors.TextPrimary,
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = selected,
                                    borderColor = GrokifyColors.PanelBorder,
                                    selectedBorderColor = GrokifyColors.GlowMint,
                                ),
                            )
                        }
                    }
                    if (!voicePreviewMsg.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(voicePreviewMsg!!, color = GrokifyColors.TextMuted, fontSize = 11.sp)
                    }

                    Spacer(Modifier.height(16.dp))
                    Text(
                        "BEHAVIOR",
                        style = MaterialTheme.typography.labelSmall,
                        color = GrokifyColors.GlowMint,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "How the DJ talks after research & queueing — pick a template below " +
                            "(edit / add under Prompt templates).",
                        color = GrokifyColors.TextDim,
                        fontSize = 10.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    val behaviorTemplates = remember(promptTemplates) {
                        promptTemplates.filter { it.kind == DjPromptKind.Behavior }
                    }
                    val behaviorScroll = rememberScrollState()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(behaviorScroll),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        behaviorTemplates.forEach { tpl ->
                            val selected = activeBehaviorId == tpl.id
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    activeBehaviorId = tpl.id
                                    djStore.activeBehaviorId = tpl.id
                                    behaviorMode = DjBehaviorMode.fromPref(tpl.id)
                                    applyDjBanterSettings(appCtx)
                                },
                                label = {
                                    Text(tpl.label, fontSize = 12.sp, maxLines = 1)
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = GrokifyColors.GlowMint.copy(alpha = 0.25f),
                                    selectedLabelColor = GrokifyColors.GlowMint,
                                    containerColor = GrokifyColors.PanelSoft,
                                    labelColor = GrokifyColors.TextPrimary,
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = selected,
                                    borderColor = GrokifyColors.PanelBorder,
                                    selectedBorderColor = GrokifyColors.GlowMint,
                                ),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        behaviorTemplates.firstOrNull { it.id == activeBehaviorId }?.blurb
                            ?: behaviorMode.blurb,
                        color = GrokifyColors.TextMuted,
                        fontSize = 11.sp,
                    )

                    Spacer(Modifier.height(16.dp))
                    DjPromptTemplatesSection(
                        appCtx = appCtx,
                        djStore = djStore,
                        promptTemplates = promptTemplates,
                        onTemplatesChanged = { promptTemplates = it },
                        promptEditorKind = promptEditorKind,
                        onKindChange = { k ->
                            promptEditorKind = k
                            editingPromptId = null
                            promptEditorMsg = null
                        },
                        editingPromptId = editingPromptId,
                        onEditingIdChange = { editingPromptId = it },
                        editPromptLabel = editPromptLabel,
                        onEditLabelChange = { editPromptLabel = it },
                        editPromptBlurb = editPromptBlurb,
                        onEditBlurbChange = { editPromptBlurb = it },
                        editPromptBody = editPromptBody,
                        onEditBodyChange = { editPromptBody = it },
                        promptEditorMsg = promptEditorMsg,
                        onMsgChange = { promptEditorMsg = it },
                        activeBehaviorId = activeBehaviorId,
                        onActiveBehavior = { id ->
                            activeBehaviorId = id
                            djStore.activeBehaviorId = id
                            behaviorMode = DjBehaviorMode.fromPref(id)
                            applyDjBanterSettings(appCtx)
                        },
                        onBehaviorModeSync = { mode -> behaviorMode = mode },
                    )

                    Spacer(Modifier.height(16.dp))
                    DjQueueSystemSection(
                        djStore = djStore,
                        loggedIn = loggedIn,
                        queueSourcesOn = queueSourcesOn,
                        onSourcesChange = { queueSourcesOn = it },
                        queuePlaylistIds = queuePlaylistIds,
                        onPlaylistIdsChange = { queuePlaylistIds = it },
                        queuePlaylistCache = queuePlaylistCache,
                        onPlaylistCacheChange = { queuePlaylistCache = it },
                        queuePlaylistBusy = queuePlaylistBusy,
                        onBusyChange = { queuePlaylistBusy = it },
                        queueSystemMsg = queueSystemMsg,
                        onMsgChange = { queueSystemMsg = it },
                        scope = scope,
                        appCtx = appCtx,
                    )

                    Spacer(Modifier.height(16.dp))
                    Text(
                        "GENRE BOARD",
                        style = MaterialTheme.typography.labelSmall,
                        color = GrokifyColors.GlowCyan,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Optional multi-select from genres in your listening history. " +
                            "Empty = full taste blend. Selected genres bias the radio pool.",
                        color = GrokifyColors.TextDim,
                        fontSize = 10.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            onClick = {
                                if (genreBoardBusy) return@TextButton
                                genreBoardBusy = true
                                genreBoardMsg = null
                                scope.launch {
                                    val (board, err) = withContext(Dispatchers.IO) {
                                        refreshDjGenreBoard(appCtx)
                                    }
                                    genreBoardBusy = false
                                    if (err != null) {
                                        genreBoardMsg = err
                                    } else {
                                        genreBoard = board
                                        genreBoardMsg = "Loaded ${board.size} genres from your top artists"
                                        selectedGenres = djStore.selectedGenres
                                        applyDjBanterSettings(appCtx)
                                    }
                                }
                            },
                            enabled = !genreBoardBusy && loggedIn,
                        ) {
                            Text(
                                if (genreBoardBusy) "Refreshing…" else "Refresh from my taste",
                                fontSize = 12.sp,
                                color = GrokifyColors.GlowCyan,
                            )
                        }
                        if (selectedGenres.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    selectedGenres = emptyList()
                                    djStore.selectedGenres = emptyList()
                                    applyDjBanterSettings(appCtx)
                                },
                            ) {
                                Text("Clear", fontSize = 12.sp, color = GrokifyColors.TextDim)
                            }
                        }
                    }
                    if (!genreBoardMsg.isNullOrBlank()) {
                        Text(genreBoardMsg!!, color = GrokifyColors.TextMuted, fontSize = 10.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    val boardChips = remember(genreBoard, selectedGenres) {
                        (genreBoard + selectedGenres).distinct()
                    }
                    if (boardChips.isEmpty()) {
                        Text(
                            if (loggedIn) {
                                "Tap Refresh to build a board from artists you actually play."
                            } else {
                                "Sign in on Account tab, then refresh the genre board."
                            },
                            color = GrokifyColors.TextMuted,
                            fontSize = 11.sp,
                        )
                    } else {
                        val genreScroll = rememberScrollState()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(genreScroll),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            boardChips.forEach { g ->
                                val on = selectedGenres.any { it.equals(g, ignoreCase = true) }
                                FilterChip(
                                    selected = on,
                                    onClick = {
                                        val next = if (on) {
                                            selectedGenres.filterNot { it.equals(g, ignoreCase = true) }
                                        } else {
                                            (selectedGenres + g).distinct().take(MAX_DJ_GENRES)
                                        }
                                        selectedGenres = next
                                        djStore.selectedGenres = next
                                        applyDjBanterSettings(appCtx)
                                    },
                                    label = {
                                        Text(g, fontSize = 11.sp, maxLines = 1)
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
                        if (selectedGenres.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Active: ${selectedGenres.joinToString(" · ")}",
                                color = GrokifyColors.GlowCyan,
                                fontSize = 10.sp,
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Text(
                        "YOUR NAME",
                        style = MaterialTheme.typography.labelSmall,
                        color = GrokifyColors.GlowMint,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "On-mic address · not a place",
                        color = GrokifyColors.TextDim,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = listenerName,
                        onValueChange = { v ->
                            listenerName = v.take(40)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Name or nickname", fontSize = 12.sp) },
                        placeholder = { Text("e.g. Audicle", fontSize = 12.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = GrokifyColors.TextPrimary,
                            unfocusedTextColor = GrokifyColors.TextPrimary,
                            focusedBorderColor = GrokifyColors.GlowMint,
                            unfocusedBorderColor = GrokifyColors.PanelBorder,
                            focusedLabelColor = GrokifyColors.GlowMint,
                            unfocusedLabelColor = GrokifyColors.TextDim,
                            cursorColor = GrokifyColors.GlowMint,
                            focusedPlaceholderColor = GrokifyColors.TextMuted,
                            unfocusedPlaceholderColor = GrokifyColors.TextMuted,
                        ),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = {
                                djStore.listenerName = listenerName.trim()
                                listenerName = djStore.listenerName
                                applyDjBanterSettings(appCtx)
                                genreBoardMsg = if (listenerName.isBlank()) {
                                    "Name cleared"
                                } else {
                                    "Name set · $listenerName"
                                }
                            },
                        ) {
                            Text("Save name", fontSize = 12.sp, color = GrokifyColors.GlowMint)
                        }
                        TextButton(
                            onClick = {
                                if (nameBusy) return@TextButton
                                nameBusy = true
                                genreBoardMsg = null
                                scope.launch {
                                    val (pulled, err) = withContext(Dispatchers.IO) {
                                        fetchSpotifyDisplayName(appCtx)
                                    }
                                    nameBusy = false
                                    if (err != null && pulled.isNullOrBlank()) {
                                        genreBoardMsg = err
                                    } else if (!pulled.isNullOrBlank()) {
                                        listenerName = pulled
                                        djStore.listenerName = pulled
                                        applyDjBanterSettings(appCtx)
                                        genreBoardMsg = "From Spotify · $pulled"
                                    } else {
                                        genreBoardMsg = "No display name on Spotify"
                                    }
                                }
                            },
                            enabled = !nameBusy && loggedIn,
                        ) {
                            Text(
                                if (nameBusy) "Pulling…" else "From Spotify",
                                fontSize = 12.sp,
                                color = GrokifyColors.GlowCyan,
                            )
                        }
                        if (listenerName.isNotBlank() && listenerName != djStore.listenerName) {
                            Text("Unsaved", color = GrokifyColors.TextMuted, fontSize = 10.sp)
                        } else if (djStore.listenerName.isNotBlank()) {
                            Text("Saved", color = GrokifyColors.TextMuted, fontSize = 10.sp)
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Text(
                        "LOCATION",
                        style = MaterialTheme.typography.labelSmall,
                        color = GrokifyColors.GlowViolet,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "City / metro only — used for local show research & discovery. " +
                            "Never used as your name on air.",
                        color = GrokifyColors.TextDim,
                        fontSize = 10.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = listenerCity,
                        onValueChange = { v ->
                            listenerCity = v.take(80)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("City or metro", fontSize = 12.sp) },
                        placeholder = { Text("e.g. Aurora, CO / Denver", fontSize = 12.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = GrokifyColors.TextPrimary,
                            unfocusedTextColor = GrokifyColors.TextPrimary,
                            focusedBorderColor = GrokifyColors.GlowViolet,
                            unfocusedBorderColor = GrokifyColors.PanelBorder,
                            focusedLabelColor = GrokifyColors.GlowViolet,
                            unfocusedLabelColor = GrokifyColors.TextDim,
                            cursorColor = GrokifyColors.GlowViolet,
                            focusedPlaceholderColor = GrokifyColors.TextMuted,
                            unfocusedPlaceholderColor = GrokifyColors.TextMuted,
                        ),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = {
                                djStore.listenerCity = listenerCity.trim()
                                listenerCity = djStore.listenerCity
                                applyDjBanterSettings(appCtx)
                                genreBoardMsg = if (listenerCity.isBlank()) {
                                    "Location cleared"
                                } else {
                                    "Location set · $listenerCity"
                                }
                            },
                        ) {
                            Text("Save location", fontSize = 12.sp, color = GrokifyColors.GlowViolet)
                        }
                        if (listenerCity.isNotBlank() && listenerCity != djStore.listenerCity) {
                            Text("Unsaved", color = GrokifyColors.TextMuted, fontSize = 10.sp)
                        } else if (djStore.listenerCity.isNotBlank()) {
                            Text("Saved", color = GrokifyColors.TextMuted, fontSize = 10.sp)
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Text(
                        "BANTER",
                        style = MaterialTheme.typography.labelSmall,
                        color = GrokifyColors.GlowViolet,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Spoken banter",
                                color = GrokifyColors.TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                if (banterEnabled) {
                                    "DJ talks between songs (Grok Voice / TTS)"
                                } else {
                                    "Silent handoffs only — chat replies stay text"
                                },
                                color = GrokifyColors.TextDim,
                                fontSize = 10.sp,
                            )
                        }
                        Switch(
                            checked = banterEnabled,
                            onCheckedChange = {
                                banterEnabled = it
                                djStore.banterEnabled = it
                                applyDjBanterSettings(appCtx)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = GrokifyColors.Void,
                                checkedTrackColor = GrokifyColors.GlowViolet,
                                uncheckedThumbColor = GrokifyColors.TextMuted,
                                uncheckedTrackColor = GrokifyColors.PanelSoft,
                            ),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "How often the DJ talks between songs",
                        color = if (banterEnabled) GrokifyColors.TextDim else GrokifyColors.TextMuted,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = banterMode == BanterFrequencyMode.Fixed,
                            onClick = {
                                if (!banterEnabled) return@FilterChip
                                banterMode = BanterFrequencyMode.Fixed
                                djStore.banterMode = BanterFrequencyMode.Fixed
                                applyDjBanterSettings(appCtx)
                            },
                            enabled = banterEnabled,
                            label = { Text("Every N songs", fontSize = 12.sp, maxLines = 1) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = GrokifyColors.GlowCyan.copy(alpha = 0.22f),
                                selectedLabelColor = GrokifyColors.GlowCyan,
                                containerColor = GrokifyColors.PanelSoft,
                                labelColor = GrokifyColors.TextPrimary,
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = banterEnabled,
                                selected = banterMode == BanterFrequencyMode.Fixed,
                                borderColor = GrokifyColors.PanelBorder,
                                selectedBorderColor = GrokifyColors.GlowCyan,
                            ),
                        )
                        FilterChip(
                            selected = banterMode == BanterFrequencyMode.Random,
                            onClick = {
                                if (!banterEnabled) return@FilterChip
                                banterMode = BanterFrequencyMode.Random
                                djStore.banterMode = BanterFrequencyMode.Random
                                applyDjBanterSettings(appCtx)
                            },
                            enabled = banterEnabled,
                            label = { Text("Random range", fontSize = 12.sp, maxLines = 1) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = GrokifyColors.GlowCyan.copy(alpha = 0.22f),
                                selectedLabelColor = GrokifyColors.GlowCyan,
                                containerColor = GrokifyColors.PanelSoft,
                                labelColor = GrokifyColors.TextPrimary,
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = banterEnabled,
                                selected = banterMode == BanterFrequencyMode.Random,
                                borderColor = GrokifyColors.PanelBorder,
                                selectedBorderColor = GrokifyColors.GlowCyan,
                            ),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    if (banterMode == BanterFrequencyMode.Fixed) {
                        BanterStepperRow(
                            label = "Talk every",
                            value = banterFixed,
                            suffix = "songs",
                            onDec = {
                                banterFixed = (banterFixed - 1).coerceIn(BANTER_EVERY_MIN, BANTER_EVERY_MAX)
                                djStore.banterFixed = banterFixed
                                applyDjBanterSettings(appCtx)
                            },
                            onInc = {
                                banterFixed = (banterFixed + 1).coerceIn(BANTER_EVERY_MIN, BANTER_EVERY_MAX)
                                djStore.banterFixed = banterFixed
                                applyDjBanterSettings(appCtx)
                            },
                        )
                    } else {
                        BanterStepperRow(
                            label = "Random min",
                            value = banterMin,
                            suffix = "songs",
                            onDec = {
                                banterMin = (banterMin - 1).coerceIn(BANTER_EVERY_MIN, BANTER_EVERY_MAX)
                                djStore.banterMin = banterMin
                                applyDjBanterSettings(appCtx)
                            },
                            onInc = {
                                banterMin = (banterMin + 1).coerceIn(BANTER_EVERY_MIN, BANTER_EVERY_MAX)
                                if (banterMin > banterMax) {
                                    banterMax = banterMin
                                    djStore.banterMax = banterMax
                                }
                                djStore.banterMin = banterMin
                                applyDjBanterSettings(appCtx)
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                        BanterStepperRow(
                            label = "Random max",
                            value = banterMax,
                            suffix = "songs",
                            onDec = {
                                banterMax = (banterMax - 1).coerceIn(BANTER_EVERY_MIN, BANTER_EVERY_MAX)
                                if (banterMax < banterMin) {
                                    banterMin = banterMax
                                    djStore.banterMin = banterMin
                                }
                                djStore.banterMax = banterMax
                                applyDjBanterSettings(appCtx)
                            },
                            onInc = {
                                banterMax = (banterMax + 1).coerceIn(BANTER_EVERY_MIN, BANTER_EVERY_MAX)
                                djStore.banterMax = banterMax
                                applyDjBanterSettings(appCtx)
                            },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (banterEnabled) {
                            "Next line: ${
                                banterCountdownLabel(djState.songsSinceBanter, djState.banterEvery)
                            } (target every ${djState.banterEvery})"
                        } else {
                            "Banter muted — frequency settings saved for when you re-enable"
                        },
                        color = GrokifyColors.TextMuted,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Allow talk over",
                                color = GrokifyColors.TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                if (allowTalkOver) {
                                    "Banter can ride the outro under the music"
                                } else {
                                    "Music pauses so the line is exclusive"
                                },
                                color = GrokifyColors.TextDim,
                                fontSize = 10.sp,
                            )
                        }
                        Switch(
                            checked = allowTalkOver,
                            enabled = banterEnabled,
                            onCheckedChange = {
                                allowTalkOver = it
                                djStore.allowTalkOver = it
                                applyDjBanterSettings(appCtx)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = GrokifyColors.Void,
                                checkedTrackColor = GrokifyColors.GlowMint,
                                uncheckedThumbColor = GrokifyColors.TextMuted,
                                uncheckedTrackColor = GrokifyColors.PanelSoft,
                            ),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Resume after restart",
                                color = GrokifyColors.TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                if (resumeAfterRestart) {
                                    "Keep Live DJ on across OTA, reboot, and process death"
                                } else {
                                    "Live DJ ends when the app restarts (queue still kept)"
                                },
                                color = GrokifyColors.TextDim,
                                fontSize = 10.sp,
                            )
                        }
                        Switch(
                            checked = resumeAfterRestart,
                            onCheckedChange = {
                                resumeAfterRestart = it
                                djStore.resumeAfterRestart = it
                                SpotifyDjBus.patch { s -> s.copy(resumeAfterRestart = it) }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = GrokifyColors.Void,
                                checkedTrackColor = GrokifyColors.GlowCyan,
                                uncheckedThumbColor = GrokifyColors.TextMuted,
                                uncheckedTrackColor = GrokifyColors.PanelSoft,
                            ),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "handoff toggle · booth works off-air · queue in-app only",
                        color = GrokifyColors.TextDim,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(12.dp))
    }
}


/**
 * Queue system: toggle radio-pool sources + multi-select playlists.
 * Default = full blend (all sources on, random playlist sample).
 */
@Composable
private fun DjQueueSystemSection(
    djStore: SpotifyDjStore,
    loggedIn: Boolean,
    queueSourcesOn: Set<String>,
    onSourcesChange: (Set<String>) -> Unit,
    queuePlaylistIds: List<String>,
    onPlaylistIdsChange: (List<String>) -> Unit,
    queuePlaylistCache: List<DjQueuePlaylistRef>,
    onPlaylistCacheChange: (List<DjQueuePlaylistRef>) -> Unit,
    queuePlaylistBusy: Boolean,
    onBusyChange: (Boolean) -> Unit,
    queueSystemMsg: String?,
    onMsgChange: (String?) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
    appCtx: Context,
) {
    val isDefault = remember(queueSourcesOn, queuePlaylistIds) {
        queueSourcesOn == DjQueueSource.defaultEnabledIds() && queuePlaylistIds.isEmpty()
    }
    Text(
        "QUEUE SYSTEM",
        style = MaterialTheme.typography.labelSmall,
        color = GrokifyColors.GlowViolet,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "What feeds New queue / Refill. Default is the full blend " +
            "(liked · top · recent · radio · playlists). Toggle sources, " +
            "and pin specific playlists — empty playlist pick = random sample.",
        color = GrokifyColors.TextDim,
        fontSize = 10.sp,
    )
    Spacer(Modifier.height(8.dp))
    // Source chips (multi-toggle)
    val srcScroll = rememberScrollState()
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(srcScroll),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DjQueueSource.entries.forEach { src ->
            val lockedOn = src == DjQueueSource.Excluded
            val on = lockedOn || src.id in queueSourcesOn
            FilterChip(
                selected = on,
                onClick = {
                    if (lockedOn) {
                        onMsgChange("Dislikes stay on — manage them in the BLOCKS tab")
                        return@FilterChip
                    }
                    val next = queueSourcesOn.toMutableSet()
                    if (on) next.remove(src.id) else next.add(src.id)
                    next.add(DjQueueSource.Excluded.id)
                    onSourcesChange(next)
                    djStore.queueSourcesEnabled = next
                    onMsgChange(
                        if (src.id in next) "On · ${src.label}" else "Off · ${src.label}",
                    )
                },
                label = {
                    Text(src.label, fontSize = 11.sp, maxLines = 1)
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = GrokifyColors.GlowViolet.copy(alpha = 0.22f),
                    selectedLabelColor = GrokifyColors.GlowViolet,
                    containerColor = GrokifyColors.PanelSoft,
                    labelColor = GrokifyColors.TextPrimary,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = on,
                    borderColor = GrokifyColors.PanelBorder,
                    selectedBorderColor = GrokifyColors.GlowViolet,
                ),
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    val activeBlurbs = DjQueueSource.entries
        .filter { it.id in queueSourcesOn }
        .take(3)
        .joinToString(" · ") { it.blurb }
    Text(
        when {
            isDefault -> "Default mix · all sources on"
            queueSourcesOn.isEmpty() -> "No sources — refill may find nothing"
            else -> activeBlurbs.ifBlank { "${queueSourcesOn.size} sources on" }
        },
        color = GrokifyColors.TextMuted,
        fontSize = 10.sp,
    )
    Spacer(Modifier.height(10.dp))
    Text(
        "PLAYLISTS",
        style = MaterialTheme.typography.labelSmall,
        color = GrokifyColors.GlowViolet.copy(alpha = 0.85f),
    )
    Spacer(Modifier.height(2.dp))
    val playlistsOn = DjQueueSource.Playlists.id in queueSourcesOn
    Text(
        if (!playlistsOn) {
            "Playlists source is off — turn it on above to sample sets."
        } else if (queuePlaylistIds.isEmpty()) {
            "None pinned · each fill samples ~3 random playlists (default)."
        } else {
            "Pinned ${queuePlaylistIds.size} — only these feed the pool."
        },
        color = GrokifyColors.TextDim,
        fontSize = 10.sp,
    )
    Spacer(Modifier.height(6.dp))
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(
            onClick = {
                if (queuePlaylistBusy) return@TextButton
                onBusyChange(true)
                onMsgChange(null)
                scope.launch {
                    val (pls, err) = withContext(Dispatchers.IO) {
                        SpotifyPlaylistAi.listPlaylists(appCtx)
                    }
                    onBusyChange(false)
                    if (err != null && pls.isEmpty()) {
                        onMsgChange(err)
                    } else {
                        val cache = pls.map {
                            DjQueuePlaylistRef(
                                id = it.id,
                                name = it.name,
                                trackCount = it.trackCount,
                            )
                        }
                        onPlaylistCacheChange(cache)
                        djStore.queuePlaylistCache = cache
                        // Drop pins that no longer exist
                        val ids = cache.map { it.id }.toSet()
                        val kept = queuePlaylistIds.filter { it in ids }
                        if (kept.size != queuePlaylistIds.size) {
                            onPlaylistIdsChange(kept)
                            djStore.queuePlaylistIds = kept
                        }
                        onMsgChange(
                            err ?: "Loaded ${cache.size} playlists",
                        )
                    }
                }
            },
            enabled = !queuePlaylistBusy && loggedIn,
        ) {
            Text(
                if (queuePlaylistBusy) "Loading…" else "Load playlists",
                fontSize = 12.sp,
                color = GrokifyColors.GlowViolet,
            )
        }
        if (queuePlaylistIds.isNotEmpty()) {
            TextButton(
                onClick = {
                    onPlaylistIdsChange(emptyList())
                    djStore.queuePlaylistIds = emptyList()
                    onMsgChange("Cleared pins · random sample again")
                },
            ) {
                Text("Clear pins", fontSize = 12.sp, color = GrokifyColors.TextDim)
            }
        }
        if (!isDefault) {
            TextButton(
                onClick = {
                    djStore.resetQueueSystemToDefault()
                    onSourcesChange(DjQueueSource.defaultEnabledIds())
                    onPlaylistIdsChange(emptyList())
                    onMsgChange("Reset to default mix")
                },
            ) {
                Text("Default mix", fontSize = 12.sp, color = GrokifyColors.GlowMint)
            }
        }
    }
    if (!queueSystemMsg.isNullOrBlank()) {
        Text(queueSystemMsg, color = GrokifyColors.TextMuted, fontSize = 10.sp)
    }
    Spacer(Modifier.height(6.dp))
    val chips = remember(queuePlaylistCache, queuePlaylistIds) {
        val byId = queuePlaylistCache.associateBy { it.id }
        val pinnedMissing = queuePlaylistIds
            .filter { it !in byId }
            .map { DjQueuePlaylistRef(id = it, name = "Playlist $it") }
        (queuePlaylistCache + pinnedMissing).distinctBy { it.id }
    }
    if (chips.isEmpty()) {
        Text(
            if (loggedIn) {
                "Tap Load playlists to multi-select which sets feed the radio pool."
            } else {
                "Sign in on Account, then load playlists."
            },
            color = GrokifyColors.TextMuted,
            fontSize = 11.sp,
        )
    } else {
        val plScroll = rememberScrollState()
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(plScroll),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            chips.forEach { pl ->
                val pinned = queuePlaylistIds.contains(pl.id)
                FilterChip(
                    selected = pinned,
                    onClick = {
                        if (!playlistsOn) {
                            // Auto-enable playlists source when pinning
                            val next = queueSourcesOn + DjQueueSource.Playlists.id
                            onSourcesChange(next)
                            djStore.queueSourcesEnabled = next
                        }
                        val nextIds = if (pinned) {
                            queuePlaylistIds.filterNot { it == pl.id }
                        } else {
                            (queuePlaylistIds + pl.id)
                                .distinct()
                                .take(MAX_DJ_QUEUE_PLAYLISTS)
                        }
                        onPlaylistIdsChange(nextIds)
                        djStore.queuePlaylistIds = nextIds
                        onMsgChange(
                            if (pl.id in nextIds) {
                                "Pinned · ${pl.name}"
                            } else {
                                "Unpinned · ${pl.name}"
                            },
                        )
                    },
                    label = {
                        val n = if (pl.trackCount > 0) " · ${pl.trackCount}" else ""
                        Text(
                            pl.name.take(28) + n,
                            fontSize = 11.sp,
                            maxLines = 1,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = GrokifyColors.GlowViolet.copy(alpha = 0.22f),
                        selectedLabelColor = GrokifyColors.GlowViolet,
                        containerColor = GrokifyColors.PanelSoft,
                        labelColor = GrokifyColors.TextPrimary,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = pinned,
                        borderColor = GrokifyColors.PanelBorder,
                        selectedBorderColor = GrokifyColors.GlowViolet,
                    ),
                )
            }
        }
    }
}

/**
 * Editable Live DJ prompt templates (research angles, behaviors, system cores).
 * Kept out of [SpotifyControllerPane] so the main composable stays under the JVM method size limit.
 */
@Composable
private fun DjPromptTemplatesSection(
    appCtx: Context,
    djStore: SpotifyDjStore,
    promptTemplates: List<DjPromptTemplate>,
    onTemplatesChanged: (List<DjPromptTemplate>) -> Unit,
    promptEditorKind: DjPromptKind,
    onKindChange: (DjPromptKind) -> Unit,
    editingPromptId: String?,
    onEditingIdChange: (String?) -> Unit,
    editPromptLabel: String,
    onEditLabelChange: (String) -> Unit,
    editPromptBlurb: String,
    onEditBlurbChange: (String) -> Unit,
    editPromptBody: String,
    onEditBodyChange: (String) -> Unit,
    promptEditorMsg: String?,
    onMsgChange: (String?) -> Unit,
    activeBehaviorId: String,
    onActiveBehavior: (String) -> Unit,
    onBehaviorModeSync: (DjBehaviorMode) -> Unit,
) {
    Text(
        "PROMPT TEMPLATES",
        style = MaterialTheme.typography.labelSmall,
        color = GrokifyColors.GlowAmber,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "Full control: research angles, banter bits, behaviors, and the " +
            "system prompts for banter / research / chat / queue rank (AI pick). " +
            "Enabled research angles and banter bits share one lottery each talk " +
            "(1–3 angles; handoff + rotating bits). Custom is a peer, not every " +
            "cycle. Edit any body or add your own.",
        color = GrokifyColors.TextDim,
        fontSize = 10.sp,
    )
    Spacer(Modifier.height(8.dp))
    val kindScroll = rememberScrollState()
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(kindScroll),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DjPromptKind.entries.forEach { kind ->
            val on = promptEditorKind == kind
            FilterChip(
                selected = on,
                onClick = { onKindChange(kind) },
                label = {
                    Text(kind.sectionLabel, fontSize = 11.sp, maxLines = 1)
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = GrokifyColors.GlowAmber.copy(alpha = 0.22f),
                    selectedLabelColor = GrokifyColors.GlowAmber,
                    containerColor = GrokifyColors.PanelSoft,
                    labelColor = GrokifyColors.TextPrimary,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = on,
                    borderColor = GrokifyColors.PanelBorder,
                    selectedBorderColor = GrokifyColors.GlowAmber,
                ),
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        promptEditorKind.sectionBlurb,
        color = GrokifyColors.TextMuted,
        fontSize = 10.sp,
    )
    Spacer(Modifier.height(8.dp))
    val kindTemplates = remember(promptTemplates, promptEditorKind) {
        promptTemplates.filter { it.kind == promptEditorKind }
    }
    kindTemplates.forEach { tpl ->
        val isEditing = editingPromptId == tpl.id
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(GrokifyColors.PanelSoft)
                .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(10.dp))
                .padding(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        tpl.label + if (tpl.builtIn) "" else " · custom",
                        color = GrokifyColors.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    if (tpl.blurb.isNotBlank()) {
                        Text(
                            tpl.blurb,
                            color = GrokifyColors.TextDim,
                            fontSize = 10.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (promptEditorKind == DjPromptKind.Research ||
                    promptEditorKind == DjPromptKind.Banter
                ) {
                    Switch(
                        checked = tpl.enabled,
                        onCheckedChange = { on ->
                            djStore.setTemplateEnabled(tpl.id, on)
                            onTemplatesChanged(djStore.loadPromptTemplates())
                            onMsgChange(
                                if (on) "Enabled · ${tpl.label}" else "Off pool · ${tpl.label}",
                            )
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = GrokifyColors.Void,
                            checkedTrackColor = GrokifyColors.GlowAmber,
                            uncheckedThumbColor = GrokifyColors.TextMuted,
                            uncheckedTrackColor = GrokifyColors.PanelSoft,
                        ),
                    )
                }
                if (promptEditorKind == DjPromptKind.Behavior) {
                    TextButton(
                        onClick = {
                            onActiveBehavior(tpl.id)
                            onMsgChange("Active · ${tpl.label}")
                        },
                    ) {
                        Text(
                            if (activeBehaviorId == tpl.id) "Active" else "Use",
                            fontSize = 11.sp,
                            color = if (activeBehaviorId == tpl.id) {
                                GrokifyColors.GlowMint
                            } else {
                                GrokifyColors.GlowCyan
                            },
                        )
                    }
                }
            }
            if (!isEditing) {
                Text(
                    tpl.body.take(120) + if (tpl.body.length > 120) "…" else "",
                    color = GrokifyColors.TextMuted,
                    fontSize = 10.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            onEditingIdChange(tpl.id)
                            onEditLabelChange(tpl.label)
                            onEditBlurbChange(tpl.blurb)
                            onEditBodyChange(tpl.body)
                            onMsgChange(null)
                        },
                    ) {
                        Text("Edit", fontSize = 11.sp, color = GrokifyColors.GlowCyan)
                    }
                    if (tpl.builtIn) {
                        TextButton(
                            onClick = {
                                djStore.resetPromptTemplate(tpl.id)
                                onTemplatesChanged(djStore.loadPromptTemplates())
                                if (editingPromptId == tpl.id) onEditingIdChange(null)
                                onMsgChange("Reset · ${tpl.label}")
                            },
                        ) {
                            Text("Reset", fontSize = 11.sp, color = GrokifyColors.TextDim)
                        }
                    } else {
                        TextButton(
                            onClick = {
                                djStore.deletePromptTemplate(tpl.id)
                                onTemplatesChanged(djStore.loadPromptTemplates())
                                if (activeBehaviorId == tpl.id) {
                                    onActiveBehavior(djStore.activeBehaviorId)
                                    onBehaviorModeSync(djStore.behaviorMode)
                                }
                                if (editingPromptId == tpl.id) onEditingIdChange(null)
                                onMsgChange("Deleted · ${tpl.label}")
                            },
                        ) {
                            Text("Delete", fontSize = 11.sp, color = GrokifyColors.GlowAmber)
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = editPromptLabel,
                    onValueChange = { onEditLabelChange(it.take(48)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Label", fontSize = 11.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = GrokifyColors.TextPrimary,
                        unfocusedTextColor = GrokifyColors.TextPrimary,
                        focusedBorderColor = GrokifyColors.GlowAmber,
                        unfocusedBorderColor = GrokifyColors.PanelBorder,
                        focusedLabelColor = GrokifyColors.GlowAmber,
                        unfocusedLabelColor = GrokifyColors.TextDim,
                        cursorColor = GrokifyColors.GlowAmber,
                    ),
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = editPromptBlurb,
                    onValueChange = { onEditBlurbChange(it.take(120)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Short blurb", fontSize = 11.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = GrokifyColors.TextPrimary,
                        unfocusedTextColor = GrokifyColors.TextPrimary,
                        focusedBorderColor = GrokifyColors.GlowAmber,
                        unfocusedBorderColor = GrokifyColors.PanelBorder,
                        focusedLabelColor = GrokifyColors.GlowAmber,
                        unfocusedLabelColor = GrokifyColors.TextDim,
                        cursorColor = GrokifyColors.GlowAmber,
                    ),
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = editPromptBody,
                    onValueChange = { onEditBodyChange(it.take(6000)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    label = { Text("Prompt body", fontSize = 11.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = GrokifyColors.TextPrimary,
                        unfocusedTextColor = GrokifyColors.TextPrimary,
                        focusedBorderColor = GrokifyColors.GlowAmber,
                        unfocusedBorderColor = GrokifyColors.PanelBorder,
                        focusedLabelColor = GrokifyColors.GlowAmber,
                        unfocusedLabelColor = GrokifyColors.TextDim,
                        cursorColor = GrokifyColors.GlowAmber,
                    ),
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            val body = editPromptBody.trim()
                            if (body.isBlank()) {
                                onMsgChange("Body can’t be empty")
                                return@TextButton
                            }
                            val updated = tpl.copy(
                                label = editPromptLabel.trim().ifBlank { tpl.label },
                                blurb = editPromptBlurb.trim(),
                                body = body,
                            )
                            djStore.upsertPromptTemplate(updated)
                            onTemplatesChanged(djStore.loadPromptTemplates())
                            onEditingIdChange(null)
                            applyDjBanterSettings(appCtx)
                            onMsgChange("Saved · ${updated.label}")
                        },
                    ) {
                        Text("Save", fontSize = 12.sp, color = GrokifyColors.GlowMint)
                    }
                    TextButton(onClick = {
                        onEditingIdChange(null)
                        onMsgChange(null)
                    }) {
                        Text("Cancel", fontSize = 12.sp, color = GrokifyColors.TextDim)
                    }
                }
            }
        }
    }
    if (promptEditorKind == DjPromptKind.Research ||
        promptEditorKind == DjPromptKind.Behavior ||
        promptEditorKind == DjPromptKind.Banter
    ) {
        Spacer(Modifier.height(4.dp))
        TextButton(
            onClick = {
                val id = "custom_${promptEditorKind.storageKey}_" +
                    System.currentTimeMillis().toString(36)
                val draft = DjPromptTemplate(
                    id = id,
                    kind = promptEditorKind,
                    label = when (promptEditorKind) {
                        DjPromptKind.Research -> "Custom angle"
                        DjPromptKind.Banter -> "Custom banter"
                        else -> "Custom behavior"
                    },
                    blurb = "Your template",
                    body = when (promptEditorKind) {
                        DjPromptKind.Research ->
                            "CUSTOM ANGLE: Research one vivid, verified fact about " +
                                "these artists/songs useful for a radio handoff. " +
                                "≤22 words. City context: {{CITY}}."
                        DjPromptKind.Banter ->
                            "BANTER BIT: Say this on air this cycle. Cover the topic in " +
                                "1–2 short clauses, then still name the next cut. " +
                                "City context: {{CITY}}. Do not invent specific headlines " +
                                "or scores unless RESEARCH lists them."
                        else ->
                            "PERSONALITY: Describe how the DJ should sound on mic. " +
                                "Keep handoffs clear. No hate speech."
                    },
                    enabled = true,
                    builtIn = false,
                )
                djStore.upsertPromptTemplate(draft)
                onTemplatesChanged(djStore.loadPromptTemplates())
                onEditingIdChange(id)
                onEditLabelChange(draft.label)
                onEditBlurbChange(draft.blurb)
                onEditBodyChange(draft.body)
                onMsgChange("New template — edit & save")
            },
        ) {
            Text(
                when (promptEditorKind) {
                    DjPromptKind.Research -> "+ Add research angle"
                    DjPromptKind.Banter -> "+ Add banter bit"
                    else -> "+ Add behavior"
                },
                fontSize = 12.sp,
                color = GrokifyColors.GlowAmber,
            )
        }
    }
    if (!promptEditorMsg.isNullOrBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(
            promptEditorMsg,
            color = GrokifyColors.GlowMint,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun BanterStepperRow(
    label: String,
    value: Int,
    suffix: String,
    onDec: () -> Unit,
    onInc: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            color = GrokifyColors.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onDec,
                enabled = value > BANTER_EVERY_MIN,
            ) {
                Text("−", color = GrokifyColors.GlowCyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "$value $suffix",
                color = GrokifyColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(88.dp),
                textAlign = TextAlign.Center,
            )
            TextButton(
                onClick = onInc,
                enabled = value < BANTER_EVERY_MAX,
            ) {
                Text("+", color = GrokifyColors.GlowCyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun formatTrackClock(ms: Long): String {
    val total = (ms / 1000L).toInt().coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}

@Composable
private fun StatusLine(ok: Boolean, okText: String, badText: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (ok) GrokifyColors.GlowMint else GrokifyColors.GlowAmber),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            if (ok) okText else badText,
            color = if (ok) GrokifyColors.TextMuted else GrokifyColors.GlowAmber,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun TransportButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(if (accent) 64.dp else 52.dp)
                .clip(CircleShape)
                .background(
                    if (accent) GrokifyColors.GlowMint.copy(alpha = 0.18f)
                    else GrokifyColors.PanelSoft,
                )
                .border(
                    1.dp,
                    if (accent) GrokifyColors.GlowMint.copy(alpha = 0.5f)
                    else GrokifyColors.PanelBorder,
                    CircleShape,
                ),
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (accent) GrokifyColors.GlowMint else GrokifyColors.TextPrimary,
                modifier = Modifier.size(if (accent) 32.dp else 26.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = GrokifyColors.TextDim, fontSize = 11.sp)
    }
}
