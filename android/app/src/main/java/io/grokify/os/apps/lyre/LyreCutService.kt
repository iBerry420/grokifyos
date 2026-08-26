package io.grokify.os.apps.lyre

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.grokify.os.GrokifyApp
import io.grokify.os.apps.plugin.BuiltinPluginCatalog
import io.grokify.os.widgets.WidgetNav

class LyreCutService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        timeoutHandler?.invoke()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startAsForeground() {
        val open = PendingIntent.getActivity(
            this,
            NOTIF_ID,
            WidgetNav.openPluginIntent(this, BuiltinPluginCatalog.LYRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, GrokifyApp.CHANNEL_LYRE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("LYRE is cutting…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        const val NOTIF_ID = 83221
        const val ACTION_STOP = "io.grokify.os.LYRE_CUT_STOP"

        @Volatile
        var timeoutHandler: (() -> Unit)? = null

        fun start(ctx: Context) {
            val app = ctx.applicationContext
            runCatching {
                ContextCompat.startForegroundService(app, Intent(app, LyreCutService::class.java))
            }
        }

        fun stop(ctx: Context) {
            val app = ctx.applicationContext
            runCatching {
                app.stopService(Intent(app, LyreCutService::class.java).setAction(ACTION_STOP))
            }
        }
    }
}
