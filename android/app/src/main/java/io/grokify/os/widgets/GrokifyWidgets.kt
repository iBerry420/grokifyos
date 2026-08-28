package io.grokify.os.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import io.grokify.os.R
import io.grokify.os.apps.ACTION_LIKE_TOGGLE
import io.grokify.os.apps.ACTION_MORE_LIKE
import io.grokify.os.apps.ACTION_NEXT
import io.grokify.os.apps.ACTION_PLACE_OPEN_MAPS
import io.grokify.os.apps.ACTION_PLACE_PIN_HERE
import io.grokify.os.apps.ACTION_PLACE_REFRESH
import io.grokify.os.apps.ACTION_PLACE_TOGGLE_MONITOR
import io.grokify.os.apps.ACTION_PLACE_TOGGLE_NOTE
import io.grokify.os.apps.ACTION_PLAY_PAUSE
import io.grokify.os.apps.ACTION_PREV
import io.grokify.os.apps.EXTRA_NOTE_ID
import io.grokify.os.apps.LocationNote
import io.grokify.os.apps.LocationNoteReceiver
import io.grokify.os.apps.LocationNoteStore
import io.grokify.os.apps.SpotifyArtMirror
import io.grokify.os.apps.SpotifyControllerReceiver
import io.grokify.os.apps.checkSpotifyTrackLiked
import io.grokify.os.apps.distanceMeters
import io.grokify.os.apps.enrichNowPlayingFromApi
import io.grokify.os.apps.plugin.BuiltinPluginCatalog
import io.grokify.os.apps.plugin.SpotifyOAuth
import io.grokify.os.apps.readNowPlaying
import io.grokify.os.apps.readPlaceGps
import io.grokify.os.apps.readSessionAlbumArtBitmap
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Home-screen widgets for built-in inner apps.
 *
 * Spotify strip is interactive in-place (no app open required for transport).
 * The full Live AI DJ page widget was removed (rate-limit / refresh cost).
 */
object GrokifyWidgets {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val spotifyBusy = AtomicBoolean(false)
    private val pendingSpotify = AtomicBoolean(false)
    @Volatile private var lastEnrichedUri: String = ""
    @Volatile private var lastEnrichedAt: Long = 0L
    @Volatile private var lastShellSig: String = ""

    private const val MAX_PLACE_ROWS = 8
    /** Trailing coalesce for controller ticks. */
    private const val SPOTIFY_COALESCE_MS = 1_400L

    fun refreshAll(context: Context) {
        val app = context.applicationContext
        refreshInnerApps(app)
        forceRefreshSpotify(app)
        refreshPlaceNotes(app)
    }

    fun refreshInnerApps(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        listOf(
            WifiScannerWidget::class.java,
            BtScannerWidget::class.java,
            PlaceNotesSmallWidget::class.java,
            SpaceXaiUsageWidget::class.java,
        ).forEach { cls ->
            val ids = mgr.getAppWidgetIds(ComponentName(context, cls))
            if (ids.isNotEmpty()) {
                val provider = cls.getDeclaredConstructor().newInstance() as AppWidgetProvider
                provider.onUpdate(context, mgr, ids)
            }
        }
    }

    @Volatile private var coalesceRunnable: Runnable? = null

    fun refreshSpotify(context: Context) {
        val app = context.applicationContext
        // Trailing-edge debounce: many ticks → one paint, final state still lands.
        pendingSpotify.set(true)
        val prev = coalesceRunnable
        if (prev != null) main.removeCallbacks(prev)
        val next = Runnable {
            coalesceRunnable = null
            if (!pendingSpotify.getAndSet(false) && !spotifyBusy.get()) return@Runnable
            runSpotifyUpdate(app, force = false)
        }
        coalesceRunnable = next
        main.postDelayed(next, SPOTIFY_COALESCE_MS)
    }

    /** Bypass coalesce (explicit user action / first paint). */
    fun forceRefreshSpotify(context: Context) {
        val app = context.applicationContext
        pendingSpotify.set(false)
        coalesceRunnable?.let { main.removeCallbacks(it) }
        coalesceRunnable = null
        lastShellSig = ""
        runSpotifyUpdate(app, force = true)
    }

    private fun runSpotifyUpdate(app: Context, force: Boolean) {
        if (!spotifyBusy.compareAndSet(false, true)) {
            pendingSpotify.set(true)
            val prev = coalesceRunnable
            if (prev != null) main.removeCallbacks(prev)
            val next = Runnable {
                coalesceRunnable = null
                if (pendingSpotify.getAndSet(false) || force) {
                    runSpotifyUpdate(app, force = force)
                }
            }
            coalesceRunnable = next
            main.postDelayed(next, 200L)
            return
        }
        io.execute {
            try {
                updateSpotifyWidgets(app, force = force)
            } finally {
                spotifyBusy.set(false)
                if (pendingSpotify.getAndSet(false)) {
                    main.post {
                        runSpotifyUpdate(app, force = false)
                    }
                }
            }
        }
    }

    fun refreshPlaceNotes(context: Context) {
        val app = context.applicationContext
        io.execute {
            val mgr = AppWidgetManager.getInstance(app)
            val smallIds = mgr.getAppWidgetIds(ComponentName(app, PlaceNotesSmallWidget::class.java))
            if (smallIds.isNotEmpty()) {
                PlaceNotesSmallWidget().onUpdate(app, mgr, smallIds)
            }
            val fullIds = mgr.getAppWidgetIds(ComponentName(app, PlaceNotesFullWidget::class.java))
            if (fullIds.isNotEmpty()) {
                updatePlaceNotesFull(app, mgr, fullIds)
            }
        }
    }

    internal fun updateSpotifyWidgets(context: Context, force: Boolean = false) {
        WidgetArt.bindContext(context)
        val mgr = AppWidgetManager.getInstance(context)
        val smallIds = mgr.getAppWidgetIds(ComponentName(context, SpotifySmallWidget::class.java))
        // No Spotify strip placed → zero Web API / CDN work.
        if (smallIds.isEmpty()) return

        var now = readNowPlaying(context)
        // Enrich only when the track changes or album art is still missing.
        // Never periodic re-hit currently-playing while art is already known (429 hole).
        val trackChanged = now.trackUri.isNotBlank() && now.trackUri != lastEnrichedUri
        val missingArt = now.albumArtUrl.isBlank()
        val needEnrich = SpotifyOAuth.isLoggedIn(context) &&
            !SpotifyOAuth.isRateLimited() &&
            (now.hasSession || now.trackUri.isNotBlank()) &&
            (trackChanged || (missingArt && now.trackUri != lastEnrichedUri) ||
                (missingArt && lastEnrichedAt == 0L) ||
                (missingArt && System.currentTimeMillis() - lastEnrichedAt > 90_000L))
        if (needEnrich) {
            now = runCatching { enrichNowPlayingFromApi(context, now) }.getOrDefault(now)
            lastEnrichedUri = now.trackUri
            lastEnrichedAt = System.currentTimeMillis()
        } else if (trackChanged) {
            // Remember URI even when we skipped enrich (rate-limited / logged out).
            lastEnrichedUri = now.trackUri
        }
        // Liked: in-process cache (~2 min). Skip the call path entirely when not logged in.
        // checkSpotifyTrackLiked itself no-ops network while rate-limited.
        val liked = if (
            now.trackUri.isNotBlank() &&
            SpotifyOAuth.isLoggedIn(context)
        ) {
            checkSpotifyTrackLiked(context, now.trackUri) == true
        } else {
            false
        }

        // Prefer host-mirrored / on-disk art over Spotify CDN (rate-limit safe).
        val albumUrl = SpotifyArtMirror.preferredUrl(context, now.albumArtUrl)
        val artistUrl = SpotifyArtMirror.preferredUrl(
            context,
            now.artistArtUrl.ifBlank { now.albumArtUrl },
        )

        // Signatures ignore progressMs — progress ticks were blinking the whole widget.
        val shellSig = listOf(
            now.trackUri,
            now.title,
            now.artist,
            now.isPlaying,
            now.hasSession,
            liked,
            albumUrl,
            artistUrl,
            smallIds.contentHashCode(),
        ).joinToString("|")

        if (!force && shellSig == lastShellSig) return

        val mirrorUrls = listOf(now.albumArtUrl, now.artistArtUrl, albumUrl, artistUrl)
            .filter { it.isNotBlank() }
        if (mirrorUrls.isNotEmpty()) {
            SpotifyArtMirror.mirrorAllAsync(context, mirrorUrls, onAny = null)
        }

        // Cache-first paint; only hit network once per missing cover (not every force).
        val sessionBmp = readSessionAlbumArtBitmap(context, maxEdge = 640)
        val albumSrc = albumUrl.ifBlank { now.albumArtUrl }
        var albumBmp = WidgetArt.loadCachedOnly(albumSrc, maxEdge = 640) ?: sessionBmp
        if (albumBmp == null && albumSrc.isNotBlank()) {
            albumBmp = WidgetArt.loadSync(albumSrc, maxEdge = 640)
        }
        val artistSrc = artistUrl.ifBlank { albumUrl }
        var artistRaw = WidgetArt.loadCachedOnly(artistSrc, maxEdge = 256)
            ?: if (artistSrc.isNotBlank() && artistSrc != albumSrc) {
                WidgetArt.loadSync(artistSrc, maxEdge = 256)
            } else {
                null
            }
            ?: albumBmp
            ?: sessionBmp
        val artistBmp = artistRaw?.let { WidgetArt.circleCrop(it, 160) }
        if (now.albumArtUrl.isBlank() && sessionBmp != null && now.trackUri.isNotBlank()) {
            SpotifyArtMirror.mirrorBitmapAsync(
                context,
                "session:${now.trackUri}",
                sessionBmp,
            )
        }

        val views = buildSpotifySmall(
            context,
            now.title,
            now.artist,
            now.isPlaying,
            liked,
            albumBmp,
            artistBmp,
        )
        smallIds.forEach { id -> mgr.updateAppWidget(id, views) }
        lastShellSig = shellSig
    }

    private fun buildSpotifySmall(
        context: Context,
        title: String,
        artist: String,
        isPlaying: Boolean,
        liked: Boolean,
        albumBmp: Bitmap?,
        artistBmp: Bitmap?,
    ): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_spotify_1x4)
        rv.setTextViewText(
            R.id.spotify_title,
            title.ifBlank { "Nothing playing" },
        )
        rv.setTextViewText(
            R.id.spotify_artist,
            artist.ifBlank { "Spotify · Live DJ controls" },
        )
        if (albumBmp != null) {
            rv.setImageViewBitmap(R.id.spotify_album_bg, albumBmp)
        } else {
            rv.setImageViewResource(R.id.spotify_album_bg, R.drawable.widget_bg_panel)
        }
        if (artistBmp != null) {
            rv.setImageViewBitmap(R.id.spotify_artist_thumb, artistBmp)
        } else {
            rv.setImageViewResource(R.id.spotify_artist_thumb, R.drawable.widget_ic_music)
        }
        rv.setImageViewResource(
            R.id.spotify_btn_play,
            if (isPlaying) R.drawable.widget_ic_pause else R.drawable.widget_ic_play,
        )
        rv.setImageViewResource(
            R.id.spotify_btn_like,
            if (liked) R.drawable.widget_ic_heart_filled else R.drawable.widget_ic_heart,
        )
        // Art + track meta open Spotify Controller; transport buttons stay in-widget.
        val openPi = activityPi(
            context,
            requestCode = 9220,
            intent = WidgetNav.openPluginIntent(
                context,
                BuiltinPluginCatalog.SPOTIFY_CONTROLLER,
            ),
        )
        listOf(
            R.id.spotify_artist_thumb,
            R.id.spotify_meta,
            R.id.spotify_title,
            R.id.spotify_artist,
        ).forEach { id -> rv.setOnClickPendingIntent(id, openPi) }
        wireSpotifyTransport(context, rv)
        return rv
    }

    private fun wireSpotifyTransport(context: Context, rv: RemoteViews) {
        rv.setOnClickPendingIntent(R.id.spotify_btn_prev, broadcastPi(context, ACTION_PREV, 9201))
        rv.setOnClickPendingIntent(R.id.spotify_btn_play, broadcastPi(context, ACTION_PLAY_PAUSE, 9202))
        rv.setOnClickPendingIntent(R.id.spotify_btn_next, broadcastPi(context, ACTION_NEXT, 9203))
        rv.setOnClickPendingIntent(R.id.spotify_btn_like, broadcastPi(context, ACTION_LIKE_TOGGLE, 9204))
        rv.setOnClickPendingIntent(R.id.spotify_btn_more, broadcastPi(context, ACTION_MORE_LIKE, 9205))
    }

    internal fun updatePlaceNotesFull(
        context: Context,
        mgr: AppWidgetManager,
        ids: IntArray,
    ) {
        val store = LocationNoteStore(context)
        val notes = store.list().sortedByDescending { it.createdAtMs }
        val gps = runCatching { readPlaceGps(context) }.getOrNull()
        val monitor = store.monitoringEnabled()
        val summary = buildString {
            append(notes.size)
            append(if (notes.size == 1) " place" else " places")
            val on = notes.count { it.enabled }
            if (on != notes.size) append(" · $on armed")
            val inside = notes.count { store.isInside(it.id) }
            if (inside > 0) append(" · $inside inside")
        }
        val gpsLine = if (gps != null) {
            val acc = if (gps.accuracyM > 0f) " · ±${gps.accuracyM.roundToInt()} m" else ""
            String.format(Locale.US, "GPS · %.5f, %.5f%s", gps.lat, gps.lon, acc)
        } else {
            "GPS · unavailable — grant location or wait for a fix"
        }

        val rv = RemoteViews(context.packageName, R.layout.widget_place_notes_full)
        rv.setTextViewText(R.id.places_summary, summary)
        rv.setTextViewText(R.id.places_gps_line, gpsLine)
        rv.setTextViewText(
            R.id.places_btn_monitor,
            if (monitor) "Monitor ON" else "Monitor off",
        )
        rv.setInt(
            R.id.places_btn_monitor,
            "setBackgroundResource",
            if (monitor) R.drawable.widget_chip_mint else R.drawable.widget_chip,
        )
        rv.setTextColor(
            R.id.places_btn_monitor,
            if (monitor) 0xFF34D399.toInt() else 0xFFA78BFA.toInt(),
        )

        // Toolbar actions — all in-widget
        rv.setOnClickPendingIntent(
            R.id.places_btn_monitor,
            placeBroadcastPi(context, ACTION_PLACE_TOGGLE_MONITOR, 9401),
        )
        rv.setOnClickPendingIntent(
            R.id.places_btn_pin,
            placeBroadcastPi(context, ACTION_PLACE_PIN_HERE, 9402),
        )
        rv.setOnClickPendingIntent(
            R.id.places_btn_refresh,
            placeBroadcastPi(context, ACTION_PLACE_REFRESH, 9403),
        )

        rv.removeAllViews(R.id.places_list_container)
        if (notes.isEmpty()) {
            rv.setViewVisibility(R.id.places_empty, View.VISIBLE)
        } else {
            rv.setViewVisibility(R.id.places_empty, View.GONE)
            notes.take(MAX_PLACE_ROWS).forEachIndexed { idx, note ->
                val row = buildPlaceRow(context, store, note, gps, seed = 9500 + idx * 10)
                rv.addView(R.id.places_list_container, row)
            }
        }
        ids.forEach { id -> mgr.updateAppWidget(id, rv) }
    }

    private fun buildPlaceRow(
        context: Context,
        store: LocationNoteStore,
        note: LocationNote,
        gps: io.grokify.os.apps.GpsFix?,
        seed: Int,
    ): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_place_note_row)
        val inside = store.isInside(note.id)
        rv.setInt(
            R.id.place_row_root,
            "setBackgroundResource",
            if (inside) R.drawable.widget_place_card_inside else R.drawable.widget_place_card,
        )
        rv.setTextViewText(R.id.place_title, note.title.ifBlank { "Place" })
        val dist = if (gps != null) {
            val m = distanceMeters(gps.lat, gps.lon, note.lat, note.lon)
            when {
                inside -> "INSIDE"
                m < 1000 -> "${m.roundToInt()} m"
                else -> String.format(Locale.US, "%.1f km", m / 1000.0)
            }
        } else {
            "—"
        }
        val state = when {
            !note.enabled -> "off"
            inside -> "here"
            else -> "out"
        }
        rv.setTextViewText(
            R.id.place_meta,
            "$dist · $state · ${note.actionSummary()}",
        )
        if (note.body.isNotBlank()) {
            rv.setViewVisibility(R.id.place_body, View.VISIBLE)
            rv.setTextViewText(R.id.place_body, note.body.replace('\n', ' ').take(120))
        } else {
            rv.setViewVisibility(R.id.place_body, View.GONE)
        }
        rv.setImageViewResource(
            R.id.place_btn_toggle,
            if (note.enabled) R.drawable.widget_ic_toggle_on else R.drawable.widget_ic_toggle_off,
        )
        rv.setOnClickPendingIntent(
            R.id.place_btn_toggle,
            placeBroadcastPi(
                context,
                ACTION_PLACE_TOGGLE_NOTE,
                seed + 1,
                noteId = note.id,
            ),
        )
        rv.setOnClickPendingIntent(
            R.id.place_btn_maps,
            placeBroadcastPi(
                context,
                ACTION_PLACE_OPEN_MAPS,
                seed + 2,
                noteId = note.id,
            ),
        )

        val hasApp = note.hasAppAction()
        val hasImg = note.hasImageAction()
        if (hasApp || hasImg) {
            rv.setViewVisibility(R.id.place_actions_row, View.VISIBLE)
            if (hasApp) {
                rv.setViewVisibility(R.id.place_btn_open_app, View.VISIBLE)
                val label = note.openAppLabel.ifBlank { "Open app" }.take(18)
                rv.setTextViewText(R.id.place_btn_open_app, label)
                rv.setOnClickPendingIntent(
                    R.id.place_btn_open_app,
                    placeBroadcastPi(
                        context,
                        "io.grokify.os.PLACE_NOTE_OPEN_APP",
                        seed + 3,
                        noteId = note.id,
                    ),
                )
            } else {
                rv.setViewVisibility(R.id.place_btn_open_app, View.GONE)
            }
            if (hasImg) {
                rv.setViewVisibility(R.id.place_btn_open_image, View.VISIBLE)
                rv.setOnClickPendingIntent(
                    R.id.place_btn_open_image,
                    placeBroadcastPi(
                        context,
                        "io.grokify.os.PLACE_NOTE_OPEN_IMAGE",
                        seed + 4,
                        noteId = note.id,
                    ),
                )
            } else {
                rv.setViewVisibility(R.id.place_btn_open_image, View.GONE)
            }
        } else {
            rv.setViewVisibility(R.id.place_actions_row, View.GONE)
        }
        return rv
    }

    internal fun buildInnerAppViews(
        context: Context,
        pluginId: String,
        title: String,
        subtitle: String,
        iconRes: Int,
        badge: String? = null,
        onClick: PendingIntent? = null,
        /** Optional secondary action (e.g. monitor toggle) on the badge chip only. */
        badgeClick: PendingIntent? = null,
    ): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_inner_app_1x4)
        rv.setTextViewText(R.id.widget_title, title)
        rv.setTextViewText(R.id.widget_subtitle, subtitle)
        rv.setImageViewResource(R.id.widget_icon, iconRes)
        if (!badge.isNullOrBlank()) {
            rv.setViewVisibility(R.id.widget_badge, View.VISIBLE)
            rv.setTextViewText(R.id.widget_badge, badge)
        } else {
            rv.setViewVisibility(R.id.widget_badge, View.GONE)
        }
        val openPi = activityPi(
            context,
            requestCode = pluginId.hashCode() and 0xFFFF,
            intent = WidgetNav.openPluginIntent(context, pluginId),
        )
        // Primary action: open GrokifyOS + this inner app (logical hit targets).
        val mainPi = onClick ?: openPi
        listOf(
            R.id.widget_root,
            R.id.widget_icon,
            R.id.widget_meta,
            R.id.widget_title,
            R.id.widget_subtitle,
        ).forEach { id -> rv.setOnClickPendingIntent(id, mainPi) }
        if (badgeClick != null && !badge.isNullOrBlank()) {
            rv.setOnClickPendingIntent(R.id.widget_badge, badgeClick)
        } else if (!badge.isNullOrBlank()) {
            rv.setOnClickPendingIntent(R.id.widget_badge, mainPi)
        }
        return rv
    }

    private fun activityPi(context: Context, requestCode: Int, intent: Intent): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(context, requestCode, intent, flags)
    }

    private fun broadcastPi(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, SpotifyControllerReceiver::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    private fun placeBroadcastPi(
        context: Context,
        action: String,
        requestCode: Int,
        noteId: String? = null,
    ): PendingIntent {
        val intent = Intent(context, LocationNoteReceiver::class.java).setAction(action)
        if (!noteId.isNullOrBlank()) intent.putExtra(EXTRA_NOTE_ID, noteId)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }
}

// ── Inner-app 1×4 shortcuts ──────────────────────────────────────────────────

class WifiScannerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val views = GrokifyWidgets.buildInnerAppViews(
            context,
            BuiltinPluginCatalog.WIFI_SCANNER,
            "Wi‑Fi Scanner",
            "Nearby nets · GPS · watchlist",
            R.drawable.plugin_ic_wifi_scanner,
        )
        ids.forEach { mgr.updateAppWidget(it, views) }
    }
}

class BtScannerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val views = GrokifyWidgets.buildInnerAppViews(
            context,
            BuiltinPluginCatalog.BT_SCANNER,
            "Bluetooth Tracker",
            "BLE + classic · alerts",
            R.drawable.plugin_ic_bt_scanner,
        )
        ids.forEach { mgr.updateAppWidget(it, views) }
    }
}

class PlaceNotesSmallWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val store = LocationNoteStore(context)
        val n = store.list().size
        val mon = store.monitoringEnabled()
        val monChip = if (mon) "ON" else "off"
        val subtitle = when {
            n == 0 -> "Tap to open · badge toggles monitor"
            mon -> "$n places · monitor on · badge to pause"
            else -> "$n places · monitor off · badge to arm"
        }
        val togglePi = PendingIntent.getBroadcast(
            context,
            9410,
            Intent(context, LocationNoteReceiver::class.java)
                .setAction(ACTION_PLACE_TOGGLE_MONITOR),
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0,
        )
        val views = GrokifyWidgets.buildInnerAppViews(
            context,
            BuiltinPluginCatalog.PLACE_NOTES,
            "Place Notes",
            subtitle,
            R.drawable.plugin_ic_place_notes,
            // Always show badge so monitor can be toggled without blocking open-app taps.
            badge = if (n > 0) "$n · $monChip" else monChip,
            badgeClick = togglePi,
        )
        ids.forEach { mgr.updateAppWidget(it, views) }
    }
}

class SpaceXaiUsageWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val views = GrokifyWidgets.buildInnerAppViews(
            context,
            BuiltinPluginCatalog.SPACEXAI_USAGE,
            "SpaceXAI Usage",
            "Balance · spend · limits",
            R.drawable.plugin_ic_spacexai_usage,
        )
        ids.forEach { mgr.updateAppWidget(it, views) }
    }
}

// ── Spotify ──────────────────────────────────────────────────────────────────

class SpotifySmallWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        GrokifyWidgets.forceRefreshSpotify(context)
    }

    override fun onEnabled(context: Context) {
        GrokifyWidgets.forceRefreshSpotify(context)
    }
}

// ── Place Notes full page ────────────────────────────────────────────────────

class PlaceNotesFullWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        GrokifyWidgets.updatePlaceNotesFull(context, mgr, ids)
    }
}
