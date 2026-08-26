package io.grokify.os

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import io.grokify.os.data.TokenStore
import io.grokify.os.wearbridge.WearApiKeySync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class GrokifyApp : Application(), ImageLoaderFactory {
    lateinit var tokenStore: TokenStore
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        tokenStore = TokenStore(this)
        createChannels()
        // Keep watch Carina key in sync with phone vault.
        WearApiKeySync.start(this, tokenStore, appScope)
        // Re-arm place-note geofence location updates after process start.
        runCatching { io.grokify.os.apps.LocationNoteWatcher.sync(this) }
        // Re-arm Hey Grok wake loop if prefs say so.
        runCatching { io.grokify.os.apps.GrokAssistantWakeService.sync(this) }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        // FGS keep-alive: use MIN so it sinks below the fold (no heads-up / badge).
        // New channel id — importance is immutable once a channel exists on device.
        runCatching { nm.deleteNotificationChannel("grokify_assistant") }
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ASSISTANT,
                getString(R.string.notification_channel_assistant),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.notification_channel_assistant_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_UPDATES,
                getString(R.string.notification_channel_updates),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_NEARBY_WIFI,
                getString(R.string.notification_channel_nearby_wifi),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.notification_channel_nearby_wifi_desc)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_NEARBY_BT,
                getString(R.string.notification_channel_nearby_bt),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.notification_channel_nearby_bt_desc)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PLACE_NOTES,
                getString(R.string.notification_channel_place_notes),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_place_notes_desc)
            }
        )
        // Quiet ongoing FGS while area monitoring is on (enter alerts use CHANNEL_PLACE_NOTES).
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PLACE_MONITOR,
                getString(R.string.notification_channel_place_monitor),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_place_monitor_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        // Delete prior channel ids so upgrades pick up HIGH importance +
        // PUBLIC lockscreen (importance is immutable once a channel exists).
        runCatching { nm.deleteNotificationChannel("grokify_spotify_ctrl") }
        runCatching { nm.deleteNotificationChannel("grokify_spotify_ctrl_v2") }
        runCatching { nm.deleteNotificationChannel("grokify_spotify_ctrl_v3") }
        runCatching { nm.deleteNotificationChannel("grokify_spotify_ctrl_v4") }
        runCatching { nm.deleteNotificationChannel("grokify_spotify_ctrl_v5") }
        runCatching { nm.deleteNotificationChannel("grokify_spotify_ctrl_v6") }
        runCatching { nm.deleteNotificationChannel("grokify_spotify_ctrl_v7") }
        runCatching { nm.deleteNotificationChannel("grokify_spotify_ctrl_v8") }
        runCatching { nm.deleteNotificationChannel("grokify_spotify_ctrl_v9") }
        runCatching { nm.deleteNotificationChannel("grokify_spotify_ctrl_v10") }
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SPOTIFY_CTRL,
                getString(R.string.notification_channel_spotify_ctrl),
                // HIGH + PUBLIC: lockscreen Live Notification with prev/play/next.
                // Must not be MIN (disqualifies Android 16 Live Update promotion).
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_spotify_ctrl_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SPOTIFY_DJ,
                getString(R.string.notification_channel_spotify_dj),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_spotify_dj_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SPACEXAI_USAGE,
                getString(R.string.notification_channel_spacexai_usage),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.notification_channel_spacexai_usage_desc)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_GBOT,
                getString(R.string.notification_channel_gbot),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_gbot_desc)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        // Re-arm lockscreen Spotify controller after process start
        runCatching {
            if (io.grokify.os.apps.SpotifyControllerStore(this).enabled) {
                io.grokify.os.apps.setSpotifyControllerEnabled(this, true)
            }
        }
        // Live DJ: resume only when “resume after restart” is on (default).
        // Does not wipe enabled on transient FGS start failures after OTA.
        runCatching { io.grokify.os.apps.maybeResumeLiveDj(this) }
        runCatching {
            if (io.grokify.os.apps.SpaceXaiUsageAlertStore(this).enabled) {
                io.grokify.os.apps.scheduleUsageAlertChecks(this)
            }
        }
        runCatching { io.grokify.os.apps.gbot.GbotWatch.sync(this) }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .crossfade(true)
            .build()
    }

    companion object {
        // v2: IMPORTANCE_MIN quiet keep-alive (old "grokify_assistant" was LOW)
        const val CHANNEL_ASSISTANT = "grokify_assistant_min"
        const val CHANNEL_UPDATES = "grokify_updates"
        const val CHANNEL_NEARBY_WIFI = "grokify_nearby_wifi"
        const val CHANNEL_NEARBY_BT = "grokify_nearby_bt"
        const val CHANNEL_PLACE_NOTES = "grokify_place_notes"
        const val CHANNEL_PLACE_MONITOR = "grokify_place_monitor"
        // v11: Live Notification (BigText + actions, no MediaStyle) for Samsung lockscreen
        const val CHANNEL_SPOTIFY_CTRL = "grokify_spotify_ctrl_v11"
        const val CHANNEL_SPOTIFY_DJ = "grokify_spotify_dj"
        const val CHANNEL_SPACEXAI_USAGE = "grokify_spacexai_usage"
        const val CHANNEL_GBOT = "grokify_gbot"

        lateinit var instance: GrokifyApp
            private set

        fun instanceOrNull(): GrokifyApp? =
            if (::instance.isInitialized) instance else null
    }
}
