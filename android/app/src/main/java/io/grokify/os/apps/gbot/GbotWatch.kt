package io.grokify.os.apps.gbot

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.grokify.os.GrokifyApp
import io.grokify.os.apps.plugin.BuiltinPluginCatalog
import io.grokify.os.data.TokenStore
import io.grokify.os.widgets.WidgetNav
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

data class GbotWatchState(
    val seeded: Boolean = false,
    val runningAutoKeys: Set<String> = emptySet(),
    val pendingKeys: Set<String> = emptySet(),
    val runningAgentIds: Set<String> = emptySet(),
    val newestAtRunStart: Map<String, String> = emptyMap(),
)

data class GbotWatchNotice(
    val kind: Kind,
    val key: String,
    val agentId: String,
    val agentName: String,
    val title: String,
    val body: String,
    val ongoing: Boolean,
) {
    enum class Kind { AutoRunning, AutoFinished, Pending, AgentRunning, AgentIdle }
}

object GbotWatchEval {
    const val FINISH_WINDOW_MS = 30L * 60L * 1000L

    fun encode(state: GbotWatchState): String {
        val newest = JSONObject()
        for ((k, v) in state.newestAtRunStart) {
            newest.put(k, v)
        }
        val o = JSONObject()
            .put("seeded", state.seeded)
            .put("runningAutoKeys", JSONArray(state.runningAutoKeys.toList()))
            .put("pendingKeys", JSONArray(state.pendingKeys.toList()))
            .put("runningAgentIds", JSONArray(state.runningAgentIds.toList()))
            .put("newestAtRunStart", newest)
        return o.toString()
    }

    fun decode(raw: String): GbotWatchState {
        if (raw.isBlank()) return GbotWatchState()
        return try {
            val o = JSONObject(raw)
            GbotWatchState(
                seeded = o.optBoolean("seeded", false),
                runningAutoKeys = stringSet(o.optJSONArray("runningAutoKeys")),
                pendingKeys = stringSet(o.optJSONArray("pendingKeys")),
                runningAgentIds = stringSet(o.optJSONArray("runningAgentIds")),
                newestAtRunStart = stringMap(o.optJSONObject("newestAtRunStart")),
            )
        } catch (_: Exception) {
            GbotWatchState()
        }
    }

    fun activeAutoKeys(snap: GbotSnapshot): Set<String> {
        val out = LinkedHashSet<String>()
        for (auto in snap.automations) {
            val run = auto.latestRun ?: continue
            if (run.isActive) {
                val key = auto.runKey(run)
                if (key.isNotBlank()) out.add(key)
            }
        }
        return out
    }

    fun pendingKeys(snap: GbotSnapshot): Set<String> =
        snap.pending.map { it.key() }.toSet()

    fun runningAgentIds(snap: GbotSnapshot): Set<String> =
        snap.agents.filter { it.isRunning }.map { it.id }.toSet()

    fun coveredByAuto(snap: GbotSnapshot, agentId: String): Boolean =
        snap.automations.any { it.agentId == agentId && it.latestRun?.isActive == true }

    fun hasActiveWork(snap: GbotSnapshot): Boolean =
        activeAutoKeys(snap).isNotEmpty() || snap.agents.any { it.isRunning }

    fun previewIfNew(agent: GbotAgent?, baselineNewest: String): String {
        if (agent == null) return ""
        val preview = agent.lastPreview.trim()
        if (preview.isBlank()) return ""
        val current = agent.newestEntryId
        if (baselineNewest.isBlank() || current.isBlank() || current == baselineNewest) return ""
        return preview
    }

    fun diff(
        prev: GbotWatchState,
        snap: GbotSnapshot,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<GbotWatchState, List<GbotWatchNotice>> {
        val names = snap.agents.associate { it.id to it.name }
        val agentsById = snap.agents.associateBy { it.id }
        val autoKeys = activeAutoKeys(snap)
        val pending = pendingKeys(snap)
        val agentsRun = runningAgentIds(snap)
        val notices = ArrayList<GbotWatchNotice>()
        val nextNewest = LinkedHashMap<String, String>()

        fun agentName(id: String) = names[id]?.ifBlank { null } ?: "Grok Bot"
        fun rememberNewest(key: String, agentId: String) {
            nextNewest[key] = prev.newestAtRunStart[key]
                ?: agentsById[agentId]?.newestEntryId.orEmpty()
        }

        if (!prev.seeded) {
            for (auto in snap.automations) {
                val run = auto.latestRun ?: continue
                if (run.isActive) {
                    notices.add(autoRunning(auto, agentName(auto.agentId)))
                    rememberNewest(auto.runKey(run), auto.agentId)
                }
            }
            for (agent in snap.agents) {
                if (agent.isRunning && !coveredByAuto(snap, agent.id)) {
                    notices.add(agentRunning(agent))
                    rememberNewest("agent:${agent.id}", agent.id)
                }
            }
            for (card in snap.pending) {
                notices.add(pending(card, agentName(card.agentId)))
            }
            return GbotWatchState(true, autoKeys, pending, agentsRun, nextNewest) to notices
        }

        val seenFinished = HashSet<String>()
        for (auto in snap.automations) {
            val run = auto.latestRun ?: continue
            val key = auto.runKey(run)
            if (key.isBlank()) continue
            if (run.isActive) {
                rememberNewest(key, auto.agentId)
                if (key !in prev.runningAutoKeys) {
                    notices.add(autoRunning(auto, agentName(auto.agentId)))
                }
            } else if (key in prev.runningAutoKeys && recentlyFinished(run, nowMs)) {
                val preview = previewIfNew(agentsById[auto.agentId], prev.newestAtRunStart[key].orEmpty())
                notices.add(autoFinished(auto, run, agentName(auto.agentId), preview))
                seenFinished.add(key)
            }
        }
        for (key in prev.runningAutoKeys) {
            if (key in autoKeys || key in seenFinished) continue
            val parts = key.split('\u0000')
            val agentId = parts.getOrNull(0).orEmpty()
            val autoId = parts.getOrNull(1).orEmpty()
            val auto = snap.automations.firstOrNull { it.agentId == agentId && it.id == autoId }
            val run = auto?.latestRun
            if (run != null && !recentlyFinished(run, nowMs)) continue
            if (run == null) continue
            val preview = previewIfNew(agentsById[agentId], prev.newestAtRunStart[key].orEmpty())
            notices.add(
                GbotWatchNotice(
                    kind = GbotWatchNotice.Kind.AutoFinished,
                    key = key,
                    agentId = agentId,
                    agentName = agentName(agentId),
                    title = "${auto.name} finished",
                    body = buildString {
                        append(
                            listOfNotNull(
                                agentName(agentId).takeIf { it.isNotBlank() },
                                run.status.ifBlank { null },
                            ).joinToString(" · ").ifBlank { "Run ended" },
                        )
                        if (preview.isNotBlank()) {
                            append('\n')
                            append(preview.take(180))
                        }
                    },
                    ongoing = false,
                ),
            )
        }

        for (card in snap.pending) {
            if (card.key() !in prev.pendingKeys) {
                notices.add(pending(card, agentName(card.agentId)))
            }
        }

        for (agent in snap.agents) {
            if (coveredByAuto(snap, agent.id)) continue
            if (notices.any { it.agentId == agent.id && it.kind != GbotWatchNotice.Kind.Pending }) continue
            val akey = "agent:${agent.id}"
            if (agent.isRunning) {
                rememberNewest(akey, agent.id)
                if (agent.id !in prev.runningAgentIds) {
                    notices.add(agentRunning(agent))
                }
            } else if (agent.id in prev.runningAgentIds) {
                val preview = previewIfNew(agent, prev.newestAtRunStart[akey].orEmpty())
                notices.add(
                    GbotWatchNotice(
                        kind = GbotWatchNotice.Kind.AgentIdle,
                        key = akey,
                        agentId = agent.id,
                        agentName = agent.name,
                        title = "${agent.name} finished",
                        body = preview.ifBlank { "Bot went idle" }.take(180),
                        ongoing = false,
                    ),
                )
            }
        }

        return GbotWatchState(true, autoKeys, pending, agentsRun, nextNewest) to notices
    }

    private fun autoRunning(auto: GbotAutomation, agentName: String): GbotWatchNotice {
        val run = auto.latestRun
        val via = run?.trigger?.ifBlank { null } ?: auto.schedule.ifBlank { null }
        return GbotWatchNotice(
            kind = GbotWatchNotice.Kind.AutoRunning,
            key = auto.runKey(),
            agentId = auto.agentId,
            agentName = agentName,
            title = "${auto.name} is running",
            body = listOfNotNull(agentName, via).joinToString(" · "),
            ongoing = true,
        )
    }

    private fun autoFinished(
        auto: GbotAutomation,
        run: GbotAutomationRun,
        agentName: String,
        preview: String,
    ): GbotWatchNotice {
        val dur = if (run.startedAt > 0L && run.finishedAt >= run.startedAt) {
            formatDuration(run.finishedAt - run.startedAt)
        } else {
            null
        }
        val status = if (run.ok) "ok" else run.status.ifBlank { "ended" }
        val body = buildString {
            append(agentName)
            append(" · ")
            append(status)
            if (dur != null) {
                append(" · ")
                append(dur)
            }
            if (preview.isNotBlank()) {
                append('\n')
                append(preview.take(180))
            }
        }
        return GbotWatchNotice(
            kind = GbotWatchNotice.Kind.AutoFinished,
            key = auto.runKey(run),
            agentId = auto.agentId,
            agentName = agentName,
            title = if (run.ok) "${auto.name} finished" else "${auto.name} failed",
            body = body,
            ongoing = false,
        )
    }

    private fun pending(card: GbotPendingCard, agentName: String): GbotWatchNotice {
        val detail = card.prompt.ifBlank { card.detail }.ifBlank { card.kind }.take(180)
        return GbotWatchNotice(
            kind = GbotWatchNotice.Kind.Pending,
            key = card.key(),
            agentId = card.agentId,
            agentName = agentName,
            title = "$agentName needs you",
            body = detail,
            ongoing = false,
        )
    }

    private fun agentRunning(agent: GbotAgent): GbotWatchNotice {
        return GbotWatchNotice(
            kind = GbotWatchNotice.Kind.AgentRunning,
            key = "agent:${agent.id}",
            agentId = agent.id,
            agentName = agent.name,
            title = "${agent.name} is running",
            body = "Working in the box",
            ongoing = true,
        )
    }

    fun recentlyFinished(run: GbotAutomationRun, nowMs: Long): Boolean {
        if (run.finishedAt <= 0L) return false
        val delta = nowMs - run.finishedAt
        return delta in 0L..FINISH_WINDOW_MS
    }

    fun formatDuration(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val m = total / 60L
        val s = total % 60L
        return if (m > 0L) "${m}m ${s}s" else "${s}s"
    }

    private fun stringSet(arr: JSONArray?): Set<String> {
        if (arr == null) return emptySet()
        val out = LinkedHashSet<String>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.optString(i).orEmpty()
            if (v.isNotBlank()) out.add(v)
        }
        return out
    }

    private fun stringMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.isBlank()) continue
            out[k] = obj.optString(k, "")
        }
        return out
    }
}

object GbotWatch {
    private const val TAG = "GbotWatch"
    const val ACTION_CHECK = "io.grokify.os.GBOT_WATCH_CHECK"
    const val ACTION_STOP = "io.grokify.os.GBOT_WATCH_STOP"

    const val NOTIF_RUNNING = 83201
    const val NOTIF_FINISHED = 83202
    const val NOTIF_PENDING = 83203

    const val IDLE_INTERVAL_MS = 45_000L
    const val HOT_INTERVAL_MS = 12_000L

    private val lock = Any()

    @Volatile
    var lastHadActiveWork: Boolean = false
        private set

    fun sync(context: Context) {
        val app = context.applicationContext
        val store = GbotStore(app)
        if (!store.watchEnabled) {
            cancel(app)
            return
        }
        schedule(app, if (lastHadActiveWork) HOT_INTERVAL_MS else IDLE_INTERVAL_MS)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        val store = GbotStore(app)
        store.watchEnabled = enabled
        if (!enabled) {
            store.watchStateJson = ""
            lastHadActiveWork = false
            cancel(app)
            NotificationManagerCompat.from(app).apply {
                cancel(NOTIF_RUNNING)
                cancel(NOTIF_FINISHED)
                cancel(NOTIF_PENDING)
            }
        } else {
            schedule(app, 2_000L)
        }
    }

    fun onSnapshot(context: Context, snap: GbotSnapshot) {
        val app = context.applicationContext
        if (!GbotStore(app).watchEnabled) return
        if (snap.error != null) return
        apply(app, snap)
    }

    fun poll(context: Context) {
        val app = context.applicationContext
        val store = GbotStore(app)
        if (!store.watchEnabled) {
            cancel(app)
            return
        }
        val token = blockingToken(app)
        if (token.isNullOrBlank()) {
            schedule(app, IDLE_INTERVAL_MS)
            return
        }
        val api = GbotApi { token }
        val raw = api.snapshot()
        val snap = GbotParse.snapshot(raw)
        if (snap.error != null) {
            schedule(app, IDLE_INTERVAL_MS)
            return
        }
        apply(app, snap)
    }

    private fun apply(app: Context, snap: GbotSnapshot) {
        val notices: List<GbotWatchNotice>
        val active: Boolean
        synchronized(lock) {
            val store = GbotStore(app)
            val prev = GbotWatchEval.decode(store.watchStateJson)
            val (next, generated) = GbotWatchEval.diff(prev, snap)
            store.watchStateJson = GbotWatchEval.encode(next)
            notices = generated
            active = GbotWatchEval.hasActiveWork(snap)
            lastHadActiveWork = active
        }
        dispatch(app, notices, snap, active)
        schedule(app, if (active) HOT_INTERVAL_MS else IDLE_INTERVAL_MS)
        if (active) {
            val runningNotice = notices.firstOrNull { it.ongoing }
            startWatcher(
                app,
                title = runningNotice?.title ?: "Grok Bot",
                text = runningLine(snap),
            )
        } else {
            stopWatcher(app)
        }
    }

    private fun dispatch(
        app: Context,
        notices: List<GbotWatchNotice>,
        snap: GbotSnapshot,
        active: Boolean,
    ) {
        if (notices.isEmpty() && !active) {
            NotificationManagerCompat.from(app).cancel(NOTIF_RUNNING)
            return
        }
        val running = notices.filter { it.ongoing }
        val finished = notices.filter {
            it.kind == GbotWatchNotice.Kind.AutoFinished ||
                it.kind == GbotWatchNotice.Kind.AgentIdle
        }
        val pending = notices.filter { it.kind == GbotWatchNotice.Kind.Pending }

        if (running.isNotEmpty() || active) {
            val line = running.firstOrNull()?.let { "${it.title} · ${it.body}" }
                ?: runningLine(snap)
            val agentId = running.firstOrNull()?.agentId
                ?: snap.automations.firstOrNull { it.isRunning }?.agentId
                ?: snap.agents.firstOrNull { it.isRunning }?.id.orEmpty()
            post(
                app,
                NOTIF_RUNNING,
                title = running.firstOrNull()?.title ?: "Grok Bot is running",
                body = line.substringAfter(" · ").ifBlank { line },
                agentId = agentId,
                ongoing = true,
                alertOnce = true,
            )
        } else {
            NotificationManagerCompat.from(app).cancel(NOTIF_RUNNING)
        }

        finished.lastOrNull()?.let { n ->
            post(
                app,
                NOTIF_FINISHED,
                title = n.title,
                body = n.body,
                agentId = n.agentId,
                ongoing = false,
                alertOnce = false,
            )
        }
        pending.lastOrNull()?.let { n ->
            post(
                app,
                NOTIF_PENDING,
                title = n.title,
                body = n.body,
                agentId = n.agentId,
                ongoing = false,
                alertOnce = false,
            )
        }
    }

    private fun runningLine(snap: GbotSnapshot): String {
        val autos = snap.automations.filter { it.isRunning }
        if (autos.size == 1) {
            val a = autos.first()
            val name = snap.agents.firstOrNull { it.id == a.agentId }?.name ?: "Grok Bot"
            return "${a.name} is running · $name"
        }
        if (autos.size > 1) return "${autos.size} automations running"
        val bots = snap.agents.filter { it.isRunning }
        return when {
            bots.size == 1 -> "${bots.first().name} is running"
            bots.size > 1 -> "${bots.size} bots running"
            snap.pending.isNotEmpty() -> "${snap.pending.size} waiting on you"
            else -> "Watching Grok Bot"
        }
    }

    private fun post(
        app: Context,
        id: Int,
        title: String,
        body: String,
        agentId: String,
        ongoing: Boolean,
        alertOnce: Boolean,
    ) {
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return
        if (agentId.isNotBlank()) {
            GbotStore(app).selectedAgentId = agentId
        }
        val open = PendingIntent.getActivity(
            app,
            id,
            WidgetNav.openPluginIntent(app, BuiltinPluginCatalog.GBOT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(app, GrokifyApp.CHANNEL_GBOT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(
                if (ongoing) NotificationCompat.PRIORITY_DEFAULT
                else NotificationCompat.PRIORITY_HIGH,
            )
            .setCategory(
                if (ongoing) NotificationCompat.CATEGORY_PROGRESS
                else NotificationCompat.CATEGORY_STATUS,
            )
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(alertOnce)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(app).notify(id, n) }
    }

    private fun schedule(context: Context, delayMs: Long) {
        val app = context.applicationContext
        if (!GbotStore(app).watchEnabled) {
            cancelAlarm(app)
            return
        }
        val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = checkPendingIntent(app)
        val at = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(2_000L)
        runCatching {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }.onFailure {
            runCatching {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            }
        }
    }

    private fun cancel(context: Context) {
        val app = context.applicationContext
        cancelAlarm(app)
        stopWatcher(app)
    }

    private fun cancelAlarm(app: Context) {
        val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(checkPendingIntent(app))
    }

    private fun checkPendingIntent(app: Context): PendingIntent {
        val intent = Intent(app, GbotWatchReceiver::class.java).setAction(ACTION_CHECK)
        return PendingIntent.getBroadcast(
            app,
            83210,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun startWatcher(app: Context, title: String, text: String) {
        val i = Intent(app, GbotWatchService::class.java)
            .putExtra(GbotWatchService.EXTRA_TITLE, title)
            .putExtra(GbotWatchService.EXTRA_TEXT, text)
        runCatching {
            ContextCompat.startForegroundService(app, i)
        }.onFailure { err ->
            Log.w(TAG, "start watch service: ${err.message}")
        }
    }

    private fun stopWatcher(app: Context) {
        runCatching {
            app.stopService(Intent(app, GbotWatchService::class.java))
        }
    }

    private fun blockingToken(app: Context): String? {
        return runCatching {
            runBlocking {
                val store = (app as? GrokifyApp)?.tokenStore ?: TokenStore(app)
                store.tokenFlow.first()
            }
        }.getOrNull()
    }
}

class GbotWatchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()
        when (action) {
            GbotWatch.ACTION_CHECK,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                if (!GbotStore(context).watchEnabled) {
                    GbotWatch.setEnabled(context, false)
                    return
                }
                GbotWatch.sync(context)
                Thread {
                    runCatching { GbotWatch.poll(context) }
                }.start()
            }
            GbotWatch.ACTION_STOP -> GbotWatch.setEnabled(context, false)
        }
    }
}

class GbotWatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!GbotStore(this).watchEnabled || intent?.action == GbotWatch.ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val title = intent?.getStringExtra(EXTRA_TITLE)?.ifBlank { null } ?: "Grok Bot"
        val text = intent?.getStringExtra(EXTRA_TEXT)?.ifBlank { null } ?: "Watching Grok Bot"
        startAsForeground(title, text)
        if (loop?.isActive != true) {
            loop = scope.launch {
                while (isActive) {
                    delay(GbotWatch.HOT_INTERVAL_MS)
                    if (!GbotStore(applicationContext).watchEnabled) break
                    runCatching { GbotWatch.poll(applicationContext) }
                    if (!GbotWatch.lastHadActiveWork) break
                }
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        loop?.cancel()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun startAsForeground(title: String, text: String) {
        val open = PendingIntent.getActivity(
            this,
            GbotWatch.NOTIF_RUNNING,
            WidgetNav.openPluginIntent(this, BuiltinPluginCatalog.GBOT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, GrokifyApp.CHANNEL_GBOT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                GbotWatch.NOTIF_RUNNING,
                n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(GbotWatch.NOTIF_RUNNING, n)
        }
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
    }
}
