package io.grokify.os.apps.gbot

import org.json.JSONArray
import org.json.JSONObject

data class GbotHealth(
    val ok: Boolean,
    val upstreamOk: Boolean?,
    val busy: Boolean,
    val busyOnlyAwaitingApproval: Boolean,
    val localExec: Boolean,
    val pid: Int,
)

data class GbotAuthStatus(
    val kind: String,
    val authId: String,
    val expiresAt: Long,
    val boxConnected: Boolean,
    val localExec: Boolean,
)

data class GbotHostStatus(
    val hostVersion: String,
    val latestHostVersion: String,
    val hostUpdateAvailable: Boolean,
    val busy: Boolean,
)

data class GbotComputer(
    val enabled: Boolean,
    val registered: Boolean,
    val overlayPresent: Boolean,
    val computerId: String,
    val computerLabel: String,
    val hostname: String,
    val pid: Int?,
    val credentialPresent: Boolean,
    val connectionPresent: Boolean,
)

data class GbotSettings(
    val localToolPermission: String,
    val autoReviewEnabled: Boolean,
    val userTimeZone: String,
    val userTimeZoneOverride: String,
    val autoReviewJson: String = "{}",
    val mcpServers: List<GbotConnector> = emptyList(),
)

data class GbotAgent(
    val id: String,
    val name: String,
    val description: String,
    val avatarColor: String,
    val isActive: Boolean,
    val isRunning: Boolean,
    val isComposing: Boolean,
    val hasUnread: Boolean,
    val unreadCount: Int,
    val lastPreview: String,
    val lastActivityAt: Long,
    val newestEntryId: String,
)

data class GbotPendingCard(
    val kind: String,
    val entryId: String,
    val requestId: String,
    val agentId: String,
    val detail: String = "",
    val prompt: String = "",
    val options: List<GbotWidgetOption> = emptyList(),
    val toolAction: String = "",
    val toolTarget: String = "",
    val allowCustom: Boolean = false,
    val skipped: Boolean = false,
) {
    fun key(): String = "$agentId|$entryId|$kind"
}

data class GbotWidgetOption(
    val label: String,
    val value: String,
    val primary: Boolean,
)

enum class GbotBubbleKind {
    User,
    Assistant,
    Widget,
    LocalTool,
    AutoReview,
    Secret,
    Event,
    Connector,
    Other,
}

data class GbotBubble(
    val id: String,
    val kind: GbotBubbleKind,
    val text: String,
    val timestampMs: Long,
    val streaming: Boolean = false,
    val options: List<GbotWidgetOption> = emptyList(),
    val requestId: String = "",
    val toolAction: String = "",
    val toolTarget: String = "",
    val eventLabel: String = "",
    val agentId: String = "",
    val allowCustom: Boolean = false,
    val skipped: Boolean = false,
    val fromAgentName: String = "",
    val toAgentName: String = "",
    val connectorName: String = "",
    val connectorStatus: String = "",
    val connectorPluginId: String = "",
    val feedback: String = "",
)

data class GbotBoxWindow(
    val index: Int,
    val vncUrl: String,
)

data class GbotBoxStatus(
    val agentId: String,
    val state: String,
    val vncUrl: String,
    val windowCount: Int,
    val imageUpdateAvailable: Boolean,
    val hostVersion: String,
    val hostUpdateAvailable: Boolean,
    val handoffInstruction: String,
    val handoffRequestId: String,
    val windows: List<GbotBoxWindow> = emptyList(),
)

data class GbotListener(
    val platform: String,
    val connected: Boolean,
    val state: String,
)

data class GbotConnector(
    val id: String,
    val name: String,
    val status: String = "",
    val source: String = "",
    val platform: String = "",
)

data class GbotChannel(
    val platform: String,
    val displayName: String,
    val availability: String,
    val connected: Boolean,
    val blurb: String = "",
)

data class GbotMemory(
    val id: String,
    val content: String,
    val kind: String,
)

data class GbotAutomationRun(
    val id: String,
    val trigger: String,
    val startedAt: Long,
    val finishedAt: Long,
    val status: String,
) {
    val isActive: Boolean get() = startedAt > 0L && finishedAt <= 0L
    val ok: Boolean
        get() = status.isBlank() ||
            status.equals("ok", ignoreCase = true) ||
            status.equals("success", ignoreCase = true)
}

data class GbotAutomation(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val schedule: String,
    val lastRunAt: Long,
    val nextRunAt: Long = 0L,
    val agentId: String = "",
    val latestRun: GbotAutomationRun? = null,
    val prompt: String = "",
    val cron: String = "",
    val triggerJson: String = "",
    val triggerType: String = "",
    val runs: List<GbotAutomationRun> = emptyList(),
) {
    val isRunning: Boolean get() = latestRun?.isActive == true
    fun runKey(run: GbotAutomationRun? = latestRun): String {
        if (run == null) return ""
        return "$agentId\u0000$id\u0000${run.id}"
    }
}

data class GbotWorkflow(
    val id: String,
    val name: String,
    val description: String,
)

data class GbotSkill(
    val id: String,
    val name: String,
    val description: String,
    val source: String,
)

data class GbotSearchHit(
    val agentId: String,
    val entryId: String,
    val role: String,
    val snippet: String,
    val timestampMs: Long,
)

data class GbotLoginStart(
    val loginUrl: String,
    val uuid: String,
)

data class GbotWebhookCred(
    val url: String,
    val key: String,
)

data class GbotTeachStatus(
    val state: String,
    val detail: String = "",
) {
    val isRecording: Boolean get() = state.equals("recording", ignoreCase = true)
}

data class GbotRoom(
    val id: String,
    val name: String,
)

data class GbotJoinRequest(
    val requestId: String,
    val roomId: String,
    val roomName: String,
    val requesterName: String,
)

data class GbotSharingState(
    val enabled: Boolean,
    val rooms: List<GbotRoom> = emptyList(),
    val pending: List<GbotJoinRequest> = emptyList(),
    val message: String = "",
)

data class GbotShareLink(
    val status: String,
    val url: String = "",
    val roomId: String = "",
    val message: String = "",
    val expiresAtMs: Long = 0L,
)

data class GbotPublishTeam(
    val id: Long,
    val name: String,
)

data class GbotSkillPublish(
    val teams: List<GbotPublishTeam> = emptyList(),
    val unavailableReason: String = "",
)

data class GbotSecretsStatus(
    val keys: List<String> = emptyList(),
    val applied: Boolean = false,
    val lastAppliedAtMs: Long = 0L,
)

data class GbotCookieImportResult(
    val injected: Int,
    val sites: Int = 0,
)

data class GbotSnapshot(
    val health: GbotHealth?,
    val auth: GbotAuthStatus?,
    val host: GbotHostStatus?,
    val settings: GbotSettings?,
    val agents: List<GbotAgent>,
    val pending: List<GbotPendingCard>,
    val automations: List<GbotAutomation> = emptyList(),
    val computer: GbotComputer? = null,
    val error: String? = null,
    val agentsOk: Boolean = true,
)

object GbotJson {
    fun obj(raw: JSONObject?, key: String): JSONObject? {
        if (raw == null || raw.isNull(key)) return null
        return raw.optJSONObject(key)
    }

    fun str(raw: JSONObject?, key: String, fallback: String = ""): String {
        if (raw == null || raw.isNull(key)) return fallback
        return raw.optString(key, fallback).orEmpty()
    }

    fun bool(raw: JSONObject?, key: String, fallback: Boolean = false): Boolean {
        if (raw == null || raw.isNull(key)) return fallback
        return raw.optBoolean(key, fallback)
    }

    fun long(raw: JSONObject?, key: String, fallback: Long = 0L): Long {
        if (raw == null || raw.isNull(key)) return fallback
        return raw.optLong(key, fallback)
    }

    fun int(raw: JSONObject?, key: String, fallback: Int = 0): Int {
        if (raw == null || raw.isNull(key)) return fallback
        return raw.optInt(key, fallback)
    }

    fun arr(raw: JSONObject?, key: String): JSONArray {
        if (raw == null || raw.isNull(key)) return JSONArray()
        return raw.optJSONArray(key) ?: JSONArray()
    }

    fun wrapArray(data: Any?): JSONArray = when (data) {
        is JSONArray -> data
        is JSONObject -> data.optJSONArray("agents")
            ?: data.optJSONArray("entries")
            ?: data.optJSONArray("cards")
            ?: JSONArray()
        else -> JSONArray()
    }
}

object GbotParse {
    fun health(raw: JSONObject?): GbotHealth? {
        if (raw == null) return null
        return GbotHealth(
            ok = GbotJson.bool(raw, "ok"),
            upstreamOk = if (raw.isNull("upstreamOk")) null else GbotJson.bool(raw, "upstreamOk"),
            busy = GbotJson.bool(raw, "isBusy"),
            busyOnlyAwaitingApproval = GbotJson.bool(raw, "busyOnlyAwaitingApproval"),
            localExec = GbotJson.bool(raw, "localExec"),
            pid = GbotJson.int(raw, "pid"),
        )
    }

    fun auth(raw: JSONObject?): GbotAuthStatus? {
        if (raw == null) return null
        val kind = GbotJson.str(raw, "kind")
        if (kind.isBlank()) return null
        return GbotAuthStatus(
            kind = kind,
            authId = GbotJson.str(raw, "authId"),
            expiresAt = GbotJson.long(raw, "expiresAt"),
            boxConnected = GbotJson.bool(raw, "boxConnected"),
            localExec = GbotJson.bool(raw, "localExec"),
        )
    }

    fun computer(raw: JSONObject?): GbotComputer? {
        if (raw == null) return null
        val row = when {
            raw.has("computerId") || raw.has("registered") -> raw
            else -> raw.optJSONArray("computers")?.optJSONObject(0)
        } ?: return null
        val pid = if (row.isNull("pid")) null else GbotJson.int(row, "pid").takeIf { it > 0 }
        return GbotComputer(
            enabled = GbotJson.bool(row, "enabled"),
            registered = GbotJson.bool(row, "registered"),
            overlayPresent = GbotJson.bool(row, "overlayPresent"),
            computerId = GbotJson.str(row, "computerId"),
            computerLabel = GbotJson.str(row, "computerLabel").ifBlank { GbotJson.str(row, "computerId") },
            hostname = GbotJson.str(row, "hostname"),
            pid = pid,
            credentialPresent = GbotJson.bool(row, "credentialPresent"),
            connectionPresent = GbotJson.bool(row, "connectionPresent"),
        )
    }

    fun host(raw: JSONObject?): GbotHostStatus? {
        if (raw == null) return null
        return GbotHostStatus(
            hostVersion = GbotJson.str(raw, "hostVersion"),
            latestHostVersion = GbotJson.str(raw, "latestHostVersion"),
            hostUpdateAvailable = GbotJson.bool(raw, "hostUpdateAvailable"),
            busy = GbotJson.bool(raw, "isBusy"),
        )
    }

    fun settings(raw: JSONObject?): GbotSettings? {
        if (raw == null) return null
        val auto = GbotJson.obj(raw, "autoReviewInstructions")
        return GbotSettings(
            localToolPermission = GbotJson.str(raw, "localToolPermission", "ask").ifBlank { "ask" },
            autoReviewEnabled = GbotJson.bool(auto, "isEnabled"),
            userTimeZone = GbotJson.str(raw, "userTimeZone"),
            userTimeZoneOverride = GbotJson.str(raw, "userTimeZoneOverride"),
            autoReviewJson = auto?.toString() ?: "{}",
            mcpServers = connectors(raw.opt("mcpBoxServers"), source = "box"),
        )
    }

    fun agent(raw: JSONObject): GbotAgent? {
        val id = GbotJson.str(raw, "id")
        if (id.isBlank()) return null
        val lastEntry = GbotJson.obj(raw, "lastEntry")
        val rawPreview = GbotJson.str(raw, "lastMessagePreview").ifBlank {
            GbotJson.str(lastEntry, "text")
        }
        val preview = if (
            rawPreview.equals("Connect undefined", ignoreCase = true) ||
            rawPreview.equals("connector", ignoreCase = true)
        ) {
            GbotJson.str(lastEntry, "text").ifBlank {
                GbotJson.str(lastEntry, "sessionPreview")
            }
        } else {
            rawPreview
        }
        return GbotAgent(
            id = id,
            name = GbotJson.str(raw, "name").ifBlank { id },
            description = GbotJson.str(raw, "description"),
            avatarColor = GbotJson.str(raw, "avatarColor"),
            isActive = GbotJson.bool(raw, "isActive"),
            isRunning = GbotJson.bool(raw, "isRunning"),
            isComposing = GbotJson.bool(raw, "isComposingMessage"),
            hasUnread = GbotJson.bool(raw, "hasUnread"),
            unreadCount = GbotJson.int(raw, "unreadCount"),
            lastPreview = preview.trim(),
            lastActivityAt = GbotJson.long(raw, "lastActivityAt"),
            newestEntryId = GbotJson.str(raw, "newestEntryId"),
        )
    }

    fun agents(data: Any?): List<GbotAgent> {
        val arr = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("agents") ?: JSONArray()
            else -> JSONArray()
        }
        val out = ArrayList<GbotAgent>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            agent(row)?.let { out.add(it) }
        }
        return out
    }

    fun pendingCards(data: Any?): List<GbotPendingCard> {
        val arr = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("cards") ?: JSONArray()
            else -> JSONArray()
        }
        val out = ArrayList<GbotPendingCard>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val kind = GbotJson.str(row, "kind")
            val entryId = GbotJson.str(row, "entryId")
            val agentId = GbotJson.str(row, "agentId")
            if (kind.isBlank() || agentId.isBlank()) continue
            out.add(
                GbotPendingCard(
                    kind = kind,
                    entryId = entryId,
                    requestId = GbotJson.str(row, "requestId"),
                    agentId = agentId,
                    detail = GbotJson.str(row, "detail"),
                    prompt = GbotJson.str(row, "prompt"),
                    options = widgetOptions(row.optJSONArray("options")),
                    toolAction = GbotJson.str(row, "toolAction"),
                    toolTarget = GbotJson.str(row, "toolTarget"),
                    allowCustom = GbotJson.bool(row, "allowCustom"),
                    skipped = GbotJson.bool(row, "widgetSkipped") || GbotJson.bool(row, "skipped"),
                ),
            )
        }
        return out
    }

    fun widgetOptions(raw: JSONArray?): List<GbotWidgetOption> {
        if (raw == null) return emptyList()
        val out = ArrayList<GbotWidgetOption>(raw.length())
        for (i in 0 until raw.length()) {
            val row = raw.optJSONObject(i) ?: continue
            val value = GbotJson.str(row, "value")
            val label = GbotJson.str(row, "label").ifBlank { value }
            if (value.isBlank() && label.isBlank()) continue
            out.add(
                GbotWidgetOption(
                    label = label.ifBlank { value },
                    value = value.ifBlank { label },
                    primary = GbotJson.str(row, "style").equals("primary", ignoreCase = true),
                ),
            )
        }
        return out
    }

    fun bubbles(data: Any?, agentId: String = ""): List<GbotBubble> {
        val arr = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("entries") ?: JSONArray()
            else -> JSONArray()
        }
        val out = ArrayList<GbotBubble>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            if (row.optBoolean("branched", false)) continue
            bubble(row, agentId)?.let { out.add(it) }
        }
        return out
    }

    fun bubble(entry: JSONObject, agentId: String = ""): GbotBubble? {
        val kind = GbotJson.str(entry, "kind")
        val id = GbotJson.str(entry, "id").ifBlank { "e${entry.hashCode()}" }
        val ts = GbotJson.long(entry, "timestampMs")
        val skipped = GbotJson.bool(entry, "widgetSkipped")
        val feedback = entryFeedback(entry)
        val messageObj = GbotJson.obj(entry, "message")
        val fromAgentName = GbotJson.str(GbotJson.obj(entry, "fromAgent"), "name").ifBlank {
            GbotJson.str(GbotJson.obj(messageObj, "fromAgent"), "name")
        }
        val toAgentName = GbotJson.str(GbotJson.obj(entry, "toAgent"), "name").ifBlank {
            GbotJson.str(GbotJson.obj(messageObj, "toAgent"), "name")
        }
        fun tagged(bubble: GbotBubble) = bubble.copy(
            fromAgentName = fromAgentName,
            toAgentName = toAgentName,
        )
        when (kind) {
            "message" -> {
                val role = GbotJson.str(entry, "role")
                val text = GbotJson.str(entry, "content")
                val bubbleKind = if (role.equals("user", ignoreCase = true)) {
                    GbotBubbleKind.User
                } else {
                    GbotBubbleKind.Assistant
                }
                return tagged(
                    GbotBubble(
                        id = id,
                        kind = bubbleKind,
                        text = text,
                        timestampMs = ts,
                        streaming = GbotJson.bool(entry, "isStreaming"),
                        agentId = agentId,
                        skipped = skipped,
                        feedback = feedback,
                    ),
                )
            }
            "send-message" -> {
                val message = messageObj ?: JSONObject()
                val type = GbotJson.str(message, "type")
                return tagged(
                    when (type) {
                    "text" -> GbotBubble(
                        id = id,
                        kind = GbotBubbleKind.Assistant,
                        text = GbotJson.str(message, "content"),
                        timestampMs = ts,
                        streaming = GbotJson.bool(entry, "isStreaming"),
                        agentId = agentId,
                        skipped = skipped,
                        feedback = feedback,
                    )
                    "widget" -> {
                        val widget = GbotJson.obj(message, "widget") ?: JSONObject()
                        GbotBubble(
                            id = id,
                            kind = GbotBubbleKind.Widget,
                            text = GbotJson.str(widget, "prompt"),
                            timestampMs = ts,
                            options = widgetOptions(widget.optJSONArray("options")),
                            agentId = agentId,
                            allowCustom = GbotJson.bool(widget, "allowCustom"),
                            skipped = skipped,
                        )
                    }
                    "local-tool-permission" -> {
                        val ask = GbotJson.obj(message, "ask") ?: JSONObject()
                        GbotBubble(
                            id = id,
                            kind = GbotBubbleKind.LocalTool,
                            text = GbotJson.str(ask, "target").ifBlank { GbotJson.str(ask, "action") },
                            timestampMs = ts,
                            requestId = GbotJson.str(ask, "requestId"),
                            toolAction = GbotJson.str(ask, "action"),
                            toolTarget = GbotJson.str(ask, "target"),
                            agentId = agentId,
                            skipped = skipped,
                        )
                    }
                    "auto-review-approval" -> {
                        val ask = GbotJson.obj(message, "ask") ?: JSONObject()
                        GbotBubble(
                            id = id,
                            kind = GbotBubbleKind.AutoReview,
                            text = GbotJson.str(ask, "instruction").ifBlank {
                                GbotJson.str(message, "instruction")
                            },
                            timestampMs = ts,
                            requestId = GbotJson.str(ask, "requestId"),
                            agentId = agentId,
                            skipped = skipped,
                        )
                    }
                    "secret-request" -> GbotBubble(
                        id = id,
                        kind = GbotBubbleKind.Secret,
                        text = GbotJson.str(message, "prompt").ifBlank { "Secret requested" },
                        timestampMs = ts,
                        agentId = agentId,
                        skipped = skipped,
                    )
                    "connector", "connectors" -> {
                        val nested = GbotJson.obj(message, "connector")
                            ?: GbotJson.obj(message, "plugin")
                            ?: message
                        val name = GbotJson.str(nested, "name").ifBlank {
                            GbotJson.str(message, "name")
                        }.ifBlank {
                            GbotJson.str(nested, "title")
                        }.ifBlank { "Connector" }
                        val status = GbotJson.str(nested, "status").ifBlank {
                            GbotJson.str(message, "status")
                        }
                        val pluginId = GbotJson.str(message, "pluginId").ifBlank {
                            GbotJson.str(nested, "pluginId")
                        }.ifBlank { GbotJson.str(nested, "id") }
                        GbotBubble(
                            id = id,
                            kind = GbotBubbleKind.Connector,
                            text = name,
                            timestampMs = ts,
                            agentId = agentId,
                            skipped = skipped,
                            connectorName = name,
                            connectorStatus = status,
                            connectorPluginId = pluginId,
                        )
                    }
                    else -> GbotBubble(
                        id = id,
                        kind = GbotBubbleKind.Other,
                        text = type.ifBlank { "message" },
                        timestampMs = ts,
                        agentId = agentId,
                        skipped = skipped,
                    )
                },
                )
            }
            "event" -> {
                val event = GbotJson.obj(entry, "event") ?: JSONObject()
                val label = GbotJson.str(event, "type").ifBlank { "event" }
                val name = GbotJson.str(event, "automationName")
                return tagged(
                    GbotBubble(
                        id = id,
                        kind = GbotBubbleKind.Event,
                        text = if (name.isBlank()) label else "$label · $name",
                        timestampMs = ts,
                        eventLabel = label,
                        agentId = agentId,
                        skipped = skipped,
                    ),
                )
            }
            "tool-call" -> {
                val name = GbotJson.str(entry, "name").ifBlank { "tool" }
                val status = GbotJson.str(entry, "status")
                return tagged(
                    GbotBubble(
                        id = id,
                        kind = GbotBubbleKind.Other,
                        text = if (status.isBlank()) name else "$name ($status)",
                        timestampMs = ts,
                        agentId = agentId,
                        skipped = skipped,
                    ),
                )
            }
            else -> {
                if (kind.isBlank()) return null
                return tagged(
                    GbotBubble(
                        id = id,
                        kind = GbotBubbleKind.Other,
                        text = kind,
                        timestampMs = ts,
                        agentId = agentId,
                        skipped = skipped,
                    ),
                )
            }
        }
    }

    fun box(raw: JSONObject?): GbotBoxStatus? {
        if (raw == null) return null
        val windowsArr = GbotJson.arr(raw, "windows")
        val windows = ArrayList<GbotBoxWindow>(windowsArr.length())
        for (i in 0 until windowsArr.length()) {
            val row = windowsArr.optJSONObject(i) ?: continue
            val url = GbotJson.str(row, "vncUrl")
            if (url.isBlank()) continue
            windows.add(
                GbotBoxWindow(
                    index = GbotJson.int(row, "windowIndex", i + 1),
                    vncUrl = url,
                ),
            )
        }
        val handoff = GbotJson.obj(raw, "handoff")
        val primary = GbotJson.str(raw, "vncUrl").ifBlank { windows.firstOrNull()?.vncUrl.orEmpty() }
        return GbotBoxStatus(
            agentId = GbotJson.str(raw, "agentId"),
            state = GbotJson.str(raw, "state").ifBlank { "unknown" },
            vncUrl = primary,
            windowCount = if (windows.isNotEmpty()) windows.size else GbotJson.int(raw, "windowCount"),
            imageUpdateAvailable = GbotJson.bool(raw, "imageUpdateAvailable"),
            hostVersion = GbotJson.str(raw, "hostVersion"),
            hostUpdateAvailable = GbotJson.bool(raw, "hostUpdateAvailable"),
            handoffInstruction = GbotJson.str(handoff, "instruction"),
            handoffRequestId = GbotJson.str(handoff, "requestId"),
            windows = windows,
        )
    }

    fun listeners(data: Any?): List<GbotListener> {
        val arr = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("integrations") ?: JSONArray()
            else -> JSONArray()
        }
        val out = ArrayList<GbotListener>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val platform = GbotJson.str(row, "platform")
            if (platform.isBlank()) continue
            out.add(
                GbotListener(
                    platform = platform,
                    connected = GbotJson.bool(row, "isConnected"),
                    state = GbotJson.str(row, "state"),
                ),
            )
        }
        return out
    }

    fun memories(data: Any?): List<GbotMemory> {
        val arr = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("memories") ?: JSONArray()
            else -> JSONArray()
        }
        val out = ArrayList<GbotMemory>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val id = GbotJson.str(row, "id")
            val content = GbotJson.str(row, "content")
            if (content.isBlank()) continue
            out.add(GbotMemory(id = id, content = content, kind = GbotJson.str(row, "kind")))
        }
        return out
    }

    fun automations(data: Any?, defaultAgentId: String = ""): List<GbotAutomation> {
        val arr = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("automations") ?: JSONArray()
            else -> JSONArray()
        }
        val out = ArrayList<GbotAutomation>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            automation(row, defaultAgentId)?.let { out.add(it) }
        }
        return out
    }

    fun automation(row: JSONObject, defaultAgentId: String = ""): GbotAutomation? {
        val nested = row.optJSONObject("automation")
        val src = nested ?: row
        val id = GbotJson.str(src, "id")
        if (id.isBlank()) return null
        val runsArr = GbotJson.arr(src, "runs")
        val runs = ArrayList<GbotAutomationRun>(minOf(runsArr.length(), 24))
        for (i in 0 until minOf(runsArr.length(), 24)) {
            automationRun(runsArr.optJSONObject(i))?.let { runs.add(it) }
        }
        val human = GbotJson.str(src, "triggerDescription")
        val scheduleField = GbotJson.str(src, "schedule")
        val triggerObj = src.optJSONObject("trigger")
        val triggerArr = src.optJSONArray("trigger")
        val triggerJson = triggerObj?.toString() ?: triggerArr?.toString().orEmpty()
        val triggerSched = triggerObj?.optString("schedule").orEmpty()
        val cron = when {
            GbotCron.looksLikeCron(scheduleField) -> normalizeCron(scheduleField)
            GbotCron.looksLikeCron(triggerSched) -> normalizeCron(triggerSched)
            else -> ""
        }
        return GbotAutomation(
            id = id,
            name = GbotJson.str(src, "name").ifBlank { id },
            enabled = GbotJson.bool(src, "isEnabled", true),
            schedule = human.ifBlank { cron.ifBlank { scheduleField } },
            lastRunAt = GbotJson.long(src, "lastRunAt"),
            nextRunAt = GbotJson.long(src, "nextRunAt"),
            agentId = GbotJson.str(row, "agentId").ifBlank {
                GbotJson.str(src, "agentId").ifBlank { defaultAgentId }
            },
            latestRun = runs.firstOrNull(),
            prompt = GbotJson.str(src, "prompt"),
            cron = cron,
            triggerJson = triggerJson,
            triggerType = GbotJson.str(triggerObj, "type"),
            runs = runs,
        )
    }

    fun automationRun(raw: JSONObject?): GbotAutomationRun? {
        if (raw == null) return null
        val started = GbotJson.long(raw, "startedAt")
        val id = GbotJson.str(raw, "id").ifBlank {
            if (started > 0L) "run-$started" else ""
        }
        if (id.isBlank() && started <= 0L) return null
        return GbotAutomationRun(
            id = id.ifBlank { "run-$started" },
            trigger = GbotJson.str(raw, "trigger"),
            startedAt = started,
            finishedAt = GbotJson.long(raw, "finishedAt"),
            status = GbotJson.str(raw, "status"),
        )
    }

    fun snapshot(root: JSONObject): GbotSnapshot {
        if (!root.optBoolean("ok", false)) {
            return GbotSnapshot(
                health = null,
                auth = null,
                host = null,
                settings = null,
                agents = emptyList(),
                pending = emptyList(),
                automations = emptyList(),
                error = root.optString("error").ifBlank { "gbot_unavailable" },
                agentsOk = false,
            )
        }
        val data = root.optJSONObject("data") ?: JSONObject()
        val parsedAgents = agents(data.opt("agents"))
        val upstream = data.optJSONObject("upstream")
        val agentsOk = if (upstream == null) {
            true
        } else {
            GbotJson.bool(upstream, "agents_ok", parsedAgents.isNotEmpty())
        }
        return GbotSnapshot(
            health = health(data.optJSONObject("health")),
            auth = auth(data.optJSONObject("status")),
            host = host(data.optJSONObject("host")),
            settings = settings(data.optJSONObject("settings")),
            agents = parsedAgents,
            pending = pendingCards(data.opt("pending")),
            automations = automations(data.opt("automations")),
            computer = computer(data.optJSONObject("computers")),
            agentsOk = agentsOk,
        )
    }

    fun enrichPending(cards: List<GbotPendingCard>, bubbles: List<GbotBubble>): List<GbotPendingCard> {
        if (cards.isEmpty() || bubbles.isEmpty()) return cards
        val byAgentAndId = HashMap<String, GbotBubble>(bubbles.size)
        val uniqueById = HashMap<String, GbotBubble>()
        val idCounts = HashMap<String, Int>()
        for (bubble in bubbles) {
            if (bubble.agentId.isNotBlank()) {
                byAgentAndId["${bubble.agentId}\u0000${bubble.id}"] = bubble
            }
            idCounts[bubble.id] = (idCounts[bubble.id] ?: 0) + 1
            uniqueById[bubble.id] = bubble
        }
        return cards.map { card ->
            val hit = when {
                card.agentId.isNotBlank() -> byAgentAndId["${card.agentId}\u0000${card.entryId}"]
                else -> null
            } ?: if (idCounts[card.entryId] == 1) uniqueById[card.entryId] else null
            if (hit == null) return@map card
            card.copy(
                prompt = card.prompt.ifBlank { hit.text },
                options = if (card.options.isEmpty()) hit.options else card.options,
                requestId = card.requestId.ifBlank { hit.requestId },
                toolAction = card.toolAction.ifBlank { hit.toolAction },
                toolTarget = card.toolTarget.ifBlank { hit.toolTarget },
                detail = card.detail.ifBlank { hit.toolAction.ifBlank { hit.toolTarget } },
                allowCustom = card.allowCustom || hit.allowCustom,
                skipped = card.skipped || hit.skipped,
            )
        }
    }

    fun mergePending(previous: List<GbotPendingCard>, incoming: List<GbotPendingCard>): List<GbotPendingCard> {
        if (incoming.isEmpty()) return emptyList()
        if (previous.isEmpty()) return incoming
        val incomingMap = LinkedHashMap<String, GbotPendingCard>(incoming.size)
        for (card in incoming) incomingMap[card.key()] = card
        val out = ArrayList<GbotPendingCard>(incoming.size)
        val seen = HashSet<String>()
        for (old in previous) {
            val neu = incomingMap[old.key()] ?: continue
            out.add(blendPending(old, neu))
            seen.add(old.key())
        }
        for (neu in incoming) {
            if (seen.add(neu.key())) out.add(neu)
        }
        return out
    }

    private fun blendPending(old: GbotPendingCard, neu: GbotPendingCard): GbotPendingCard {
        return neu.copy(
            prompt = neu.prompt.ifBlank { old.prompt },
            options = if (neu.options.isNotEmpty()) neu.options else old.options,
            requestId = neu.requestId.ifBlank { old.requestId },
            toolAction = neu.toolAction.ifBlank { old.toolAction },
            toolTarget = neu.toolTarget.ifBlank { old.toolTarget },
            detail = neu.detail.ifBlank { old.detail },
            allowCustom = neu.allowCustom || old.allowCustom,
            skipped = neu.skipped,
        )
    }

    fun apiError(root: JSONObject?): String {
        if (root == null) return "no_response"
        val err = root.optString("error")
        if (err.isNotBlank()) return unwrapNestedError(err)
        if (!root.optBoolean("ok", true)) return "request_failed"
        return ""
    }

    fun unwrapNestedError(raw: String): String {
        val t = raw.trim()
        if (t.startsWith("{")) {
            val inner = runCatching { JSONObject(t).optString("error") }.getOrNull()
            if (!inner.isNullOrBlank()) return inner
        }
        return t
    }

    fun connectUrl(data: Any?): String {
        val obj = data as? JSONObject ?: return ""
        return obj.optString("url").orEmpty()
    }

    fun vncUrl(root: JSONObject): String {
        val data = root.optJSONObject("data") ?: root
        return data.optString("url").orEmpty()
    }

    fun createdAgentId(data: Any?): Pair<String, String>? {
        val obj = data as? JSONObject ?: return null
        val agent = obj.optJSONObject("agent") ?: obj
        val id = agent.optString("id")
        if (id.isNullOrBlank()) return null
        val name = agent.optString("name").ifBlank { id }
        return id to name
    }

    fun searchHits(data: Any?): List<GbotSearchHit> {
        val arr = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("hits") ?: data.optJSONArray("results") ?: JSONArray()
            else -> JSONArray()
        }
        val out = ArrayList<GbotSearchHit>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val agentId = GbotJson.str(row, "agentId")
            val snippet = GbotJson.str(row, "snippet")
            if (agentId.isBlank() || snippet.isBlank()) continue
            out.add(
                GbotSearchHit(
                    agentId = agentId,
                    entryId = GbotJson.str(row, "entryId"),
                    role = GbotJson.str(row, "role"),
                    snippet = snippet,
                    timestampMs = GbotJson.long(row, "timestampMs"),
                ),
            )
        }
        return out
    }

    fun workflows(data: Any?): List<GbotWorkflow> {
        val arr = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("workflows") ?: JSONArray()
            else -> JSONArray()
        }
        val out = ArrayList<GbotWorkflow>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val id = GbotJson.str(row, "id")
            if (id.isBlank()) continue
            out.add(
                GbotWorkflow(
                    id = id,
                    name = GbotJson.str(row, "name").ifBlank { id },
                    description = GbotJson.str(row, "description"),
                ),
            )
        }
        return out
    }

    fun skills(data: Any?): List<GbotSkill> {
        val arr = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("skills") ?: data.optJSONArray("catalog") ?: JSONArray()
            else -> JSONArray()
        }
        val out = ArrayList<GbotSkill>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val id = GbotJson.str(row, "id")
            val name = GbotJson.str(row, "name").ifBlank { id }
            if (name.isBlank()) continue
            out.add(
                GbotSkill(
                    id = id,
                    name = name,
                    description = GbotJson.str(row, "description"),
                    source = GbotJson.str(row, "source"),
                ),
            )
        }
        return out
    }

    fun entryFeedback(entry: JSONObject): String {
        val direct = GbotJson.str(entry, "feedback").ifBlank {
            GbotJson.str(entry, "vote")
        }.ifBlank {
            GbotJson.str(entry, "sentiment")
        }
        if (direct.isNotBlank()) return direct.lowercase()
        val obj = GbotJson.obj(entry, "feedback") ?: return ""
        return GbotJson.str(obj, "sentiment").ifBlank {
            GbotJson.str(obj, "state")
        }.lowercase()
    }

    fun webhookCred(data: Any?): GbotWebhookCred? {
        val obj = data as? JSONObject ?: return null
        val url = obj.optString("url").orEmpty()
        val key = obj.optString("key").orEmpty()
        if (url.isBlank()) return null
        return GbotWebhookCred(url = url, key = key)
    }

    fun teachStatus(data: Any?): GbotTeachStatus? {
        val obj = data as? JSONObject ?: return null
        val state = obj.optString("state").orEmpty().ifBlank {
            obj.optString("status")
        }
        if (state.isBlank()) return null
        return GbotTeachStatus(
            state = state,
            detail = obj.optString("detail").orEmpty(),
        )
    }

    fun sharing(data: Any?): GbotSharingState? {
        val obj = data as? JSONObject ?: return null
        val roomsArr = obj.optJSONArray("rooms") ?: JSONArray()
        val rooms = ArrayList<GbotRoom>(roomsArr.length())
        for (i in 0 until roomsArr.length()) {
            val row = roomsArr.optJSONObject(i) ?: continue
            val id = GbotJson.str(row, "roomId").ifBlank { GbotJson.str(row, "id") }
            if (id.isBlank()) continue
            rooms.add(
                GbotRoom(
                    id = id,
                    name = GbotJson.str(row, "name").ifBlank { GbotJson.str(row, "roomName") }.ifBlank { id.take(8) },
                ),
            )
        }
        val pendingArr = obj.optJSONArray("pendingJoinRequests") ?: JSONArray()
        val pending = ArrayList<GbotJoinRequest>(pendingArr.length())
        for (i in 0 until pendingArr.length()) {
            val row = pendingArr.optJSONObject(i) ?: continue
            val requestId = GbotJson.str(row, "requestId")
            if (requestId.isBlank()) continue
            pending.add(
                GbotJoinRequest(
                    requestId = requestId,
                    roomId = GbotJson.str(row, "roomId"),
                    roomName = GbotJson.str(row, "roomName"),
                    requesterName = GbotJson.str(row, "requesterName").ifBlank {
                        GbotJson.str(row, "requesterAuthId").takeLast(8)
                    },
                ),
            )
        }
        return GbotSharingState(
            enabled = GbotJson.bool(obj, "isEnabled"),
            rooms = rooms,
            pending = pending,
            message = GbotJson.str(obj, "message").ifBlank { GbotJson.str(obj, "unavailableReason") },
        )
    }

    fun shareLink(data: Any?): GbotShareLink? {
        val obj = data as? JSONObject ?: return null
        val status = GbotJson.str(obj, "status").ifBlank { "ok" }
        val url = GbotJson.str(obj, "shareUrl").ifBlank { GbotJson.str(obj, "url") }
        val message = GbotJson.str(obj, "message").ifBlank { GbotJson.str(obj, "errorKind") }
        if (url.isBlank() && message.isBlank() && status.isBlank()) return null
        return GbotShareLink(
            status = status,
            url = url,
            roomId = GbotJson.str(obj, "roomId").ifBlank {
                GbotJson.str(GbotJson.obj(obj, "room"), "roomId")
            },
            message = message,
            expiresAtMs = GbotJson.long(obj, "expiresAtMs"),
        )
    }

    fun skillPublish(data: Any?): GbotSkillPublish? {
        val obj = data as? JSONObject ?: return null
        val teamsArr = obj.optJSONArray("teams") ?: JSONArray()
        val teams = ArrayList<GbotPublishTeam>(teamsArr.length())
        for (i in 0 until teamsArr.length()) {
            val row = teamsArr.optJSONObject(i) ?: continue
            val id = when {
                row.has("teamId") -> row.optLong("teamId")
                row.has("id") -> row.optLong("id")
                else -> 0L
            }
            if (id <= 0L) continue
            teams.add(
                GbotPublishTeam(
                    id = id,
                    name = GbotJson.str(row, "name").ifBlank { GbotJson.str(row, "displayName") }.ifBlank { "Team $id" },
                ),
            )
        }
        return GbotSkillPublish(
            teams = teams,
            unavailableReason = GbotJson.str(obj, "unavailableReason"),
        )
    }

    fun secretsStatus(data: Any?): GbotSecretsStatus? {
        val obj = data as? JSONObject ?: return null
        val keysArr = obj.optJSONArray("keys") ?: JSONArray()
        val keys = ArrayList<String>(keysArr.length())
        for (i in 0 until keysArr.length()) {
            val item = keysArr.opt(i)
            when (item) {
                is String -> if (item.isNotBlank()) keys.add(item)
                is JSONObject -> {
                    val name = item.optString("name").ifBlank { item.optString("id") }
                    if (name.isNotBlank()) keys.add(name)
                }
            }
        }
        return GbotSecretsStatus(
            keys = keys,
            applied = GbotJson.bool(obj, "isApplied", true),
            lastAppliedAtMs = GbotJson.long(obj, "lastAppliedAtMs"),
        )
    }

    fun cookieImport(data: Any?): GbotCookieImportResult? {
        val obj = data as? JSONObject ?: return null
        val injected = obj.optInt("injected", -1)
        if (injected < 0) return null
        return GbotCookieImportResult(
            injected = injected,
            sites = obj.optInt("sites"),
        )
    }

    fun uploadedPath(data: Any?): String {
        val obj = data as? JSONObject ?: return ""
        return obj.optString("path").orEmpty().ifBlank { obj.optString("absolutePath") }
    }

    fun loginStart(root: JSONObject): GbotLoginStart? {
        val data = root.optJSONObject("data") ?: root
        val url = data.optString("loginUrl").orEmpty()
        val uuid = data.optString("uuid").orEmpty()
        if (url.isBlank() || uuid.isBlank()) return null
        return GbotLoginStart(loginUrl = url, uuid = uuid)
    }

    fun mergeBubbles(previous: List<GbotBubble>, incoming: List<GbotBubble>): List<GbotBubble> {
        if (incoming.isEmpty()) return previous
        if (previous.isEmpty()) return incoming
        val prevAgent = previous.firstOrNull { it.agentId.isNotBlank() }?.agentId
        val inAgent = incoming.firstOrNull { it.agentId.isNotBlank() }?.agentId
        if (prevAgent != null && inAgent != null && prevAgent != inAgent) return incoming
        val prevNewest = previous.maxByOrNull { it.timestampMs } ?: return incoming
        val inNewest = incoming.maxByOrNull { it.timestampMs } ?: return previous
        val incomingIds = incoming.map { it.id }.toHashSet()
        if (inNewest.timestampMs < prevNewest.timestampMs && prevNewest.id !in incomingIds) {
            return previous
        }
        val incomingFirstTs = incoming.minOf { it.timestampMs }
        val extras = previous.filter { it.id !in incomingIds && it.timestampMs <= incomingFirstTs }
        return extras + incoming
    }

    fun coalesceSnapshot(previous: GbotSnapshot?, incoming: GbotSnapshot): GbotSnapshot {
        if (previous == null) return incoming
        if (incoming.error != null) {
            return previous.copy(error = incoming.error, agentsOk = false)
        }
        val settings = incoming.settings ?: previous.settings
        if (incoming.agents.isEmpty() && previous.agents.isNotEmpty() && !incoming.agentsOk) {
            return incoming.copy(agents = previous.agents, settings = settings)
        }
        return incoming.copy(settings = settings)
    }

    fun channels(data: Any?): List<GbotChannel> {
        val obj = data as? JSONObject ?: return emptyList()
        val manifests = obj.optJSONArray("manifests") ?: JSONArray()
        val connections = obj.optJSONArray("connections") ?: JSONArray()
        val connected = HashSet<String>()
        for (i in 0 until connections.length()) {
            val row = connections.optJSONObject(i) ?: continue
            val platform = GbotJson.str(row, "platform")
            if (platform.isNotBlank()) connected.add(platform)
        }
        val out = ArrayList<GbotChannel>()
        val seen = HashSet<String>()
        for (i in 0 until manifests.length()) {
            val row = manifests.optJSONObject(i) ?: continue
            val platform = GbotJson.str(row, "platform")
            if (platform.isBlank() || !seen.add(platform)) continue
            val blurbObj = row.optJSONObject("blurb")
            val blurb = GbotJson.str(blurbObj, "message").ifBlank { GbotJson.str(row, "blurb") }
            out.add(
                GbotChannel(
                    platform = platform,
                    displayName = GbotJson.str(row, "displayName").ifBlank { platform },
                    availability = GbotJson.str(row, "availability"),
                    connected = platform in connected || GbotJson.bool(row, "isConnected"),
                    blurb = blurb,
                ),
            )
        }
        for (i in 0 until connections.length()) {
            val row = connections.optJSONObject(i) ?: continue
            val platform = GbotJson.str(row, "platform")
            if (platform.isBlank() || !seen.add(platform)) continue
            out.add(
                GbotChannel(
                    platform = platform,
                    displayName = GbotJson.str(row, "displayName").ifBlank { platform },
                    availability = "connected",
                    connected = true,
                ),
            )
        }
        return out
    }

    fun connectors(data: Any?, source: String = "box"): List<GbotConnector> {
        val arr = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("servers")
                ?: data.optJSONArray("mcpBoxServers")
                ?: JSONArray()
            else -> JSONArray()
        }
        val out = ArrayList<GbotConnector>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i)
            if (obj != null) {
                val id = GbotJson.str(obj, "id").ifBlank {
                    GbotJson.str(obj, "identifier")
                }.ifBlank { GbotJson.str(obj, "name") }
                val name = GbotJson.str(obj, "name").ifBlank { id }
                if (name.isBlank()) continue
                out.add(
                    GbotConnector(
                        id = id.ifBlank { name },
                        name = name,
                        status = GbotJson.str(obj, "status"),
                        source = source,
                        platform = GbotJson.str(obj, "platform"),
                    ),
                )
            } else {
                val raw = arr.optString(i)
                if (raw.isNotBlank()) {
                    out.add(GbotConnector(id = raw, name = raw, source = source))
                }
            }
        }
        return out
    }

    fun connectorsFromBubbles(bubbles: List<GbotBubble>): List<GbotConnector> {
        val out = ArrayList<GbotConnector>()
        val seen = HashSet<String>()
        for (bubble in bubbles) {
            if (bubble.kind != GbotBubbleKind.Connector) continue
            val id = bubble.connectorPluginId.ifBlank { bubble.connectorName }.ifBlank { bubble.id }
            if (!seen.add(id)) continue
            out.add(
                GbotConnector(
                    id = id,
                    name = bubble.connectorName.ifBlank { bubble.text }.ifBlank { "Connector" },
                    status = bubble.connectorStatus,
                    source = "card",
                ),
            )
        }
        return out
    }
}
