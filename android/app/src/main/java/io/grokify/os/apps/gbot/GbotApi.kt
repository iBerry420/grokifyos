package io.grokify.os.apps.gbot

import io.grokify.os.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class GbotApi(
    private val tokenProvider: () -> String?,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun snapshot(): JSONObject = get("snapshot")

    fun computers(): JSONObject = get("computers")

    fun registerComputer(): JSONObject {
        return post(JSONObject().put("action", "computers_register"))
    }

    fun health(): JSONObject = get("health")

    fun pending(agentId: String? = null): JSONObject {
        val extra = if (agentId.isNullOrBlank()) {
            ""
        } else {
            "&agent_id=" + java.net.URLEncoder.encode(agentId, "UTF-8")
        }
        return get("pending$extra")
    }

    fun vnc(agentId: String): JSONObject {
        val q = java.net.URLEncoder.encode(agentId, "UTF-8")
        return get("vnc&agent_id=$q")
    }

    fun rpc(method: String, args: JSONObject = JSONObject()): JSONObject {
        val body = JSONObject()
            .put("action", "rpc")
            .put("method", method)
            .put("args", args)
        return post(body)
    }

    fun listAgents(): JSONObject = rpc("listAgents")

    fun tail(agentId: String, limit: Int = 80): JSONObject {
        return rpc(
            "getAgentTranscriptTail",
            JSONObject().put("id", agentId).put("limit", limit.coerceIn(8, 400)),
        )
    }

    fun transcript(agentId: String, limit: Int = 200): JSONObject {
        return rpc(
            "getAgentTranscript",
            JSONObject().put("id", agentId).put("limit", limit.coerceIn(8, 400)),
        )
    }

    fun send(
        agentId: String,
        prompt: String,
        attachmentPaths: List<String> = emptyList(),
        attachmentNames: List<String> = emptyList(),
        replyToId: String? = null,
        isFork: Boolean = false,
    ): JSONObject {
        val args = JSONObject()
            .put("agentId", agentId)
            .put("prompt", prompt)
            .put("clientNonce", UUID.randomUUID().toString())
            .put("enterEpochMs", System.currentTimeMillis())
        if (attachmentPaths.isNotEmpty()) {
            val paths = org.json.JSONArray()
            attachmentPaths.forEach { paths.put(it) }
            args.put("attachmentPaths", paths)
        }
        if (attachmentNames.isNotEmpty()) {
            val names = org.json.JSONArray()
            attachmentNames.forEach { names.put(it) }
            args.put("attachmentNames", names)
        }
        if (!replyToId.isNullOrBlank()) args.put("replyToId", replyToId)
        if (isFork) args.put("isFork", true)
        return rpc("sendPrompt", args)
    }

    fun interrupt(agentId: String): JSONObject {
        return rpc("interruptAgentRun", JSONObject().put("id", agentId))
    }

    fun vote(agentId: String, entryId: String, action: String, comment: String = ""): JSONObject {
        val args = JSONObject()
            .put("agentId", agentId)
            .put("entryId", entryId)
            .put("action", action)
        if (action == "submit" && comment.isNotBlank()) args.put("comment", comment)
        return rpc("voteFeedback", args)
    }

    fun uploadAttachment(agentId: String, filename: String, bytesBase64: String): JSONObject {
        return rpc(
            "uploadAttachment",
            JSONObject()
                .put("agentId", agentId)
                .put("filename", filename)
                .put("bytesBase64", bytesBase64),
        )
    }

    fun webhookCredential(agentId: String, automationId: String): JSONObject {
        return rpc(
            "getAutomationWebhookCredential",
            JSONObject().put("id", agentId).put("automationId", automationId),
        )
    }

    fun deleteMemory(agentId: String, memoryId: String): JSONObject {
        return rpc(
            "deleteAgentMemory",
            JSONObject().put("id", agentId).put("memoryId", memoryId),
        )
    }

    fun clearMemories(agentId: String): JSONObject {
        return rpc("clearAgentMemories", JSONObject().put("id", agentId))
    }

    fun createAutomation(agentId: String, name: String, prompt: String, trigger: JSONObject, enabled: Boolean): JSONObject {
        val spec = JSONObject()
            .put("name", name)
            .put("prompt", prompt)
            .put("trigger", trigger)
            .put("isEnabled", enabled)
        return rpc("createAgentAutomation", JSONObject().put("id", agentId).put("spec", spec))
    }

    fun deleteAutomation(agentId: String, automationId: String): JSONObject {
        return rpc(
            "deleteAgentAutomation",
            JSONObject().put("id", agentId).put("automationId", automationId),
        )
    }

    fun importWorkflowText(agentId: String, markdown: String, name: String = ""): JSONObject {
        val args = JSONObject().put("id", agentId).put("markdown", markdown)
        if (name.isNotBlank()) args.put("name", name)
        return rpc("importAgentWorkflowText", args)
    }

    fun teachStatus(): JSONObject = rpc("getTeachRecordingStatus")

    fun startTeach(agentId: String = ""): JSONObject {
        val args = JSONObject()
        if (agentId.isNotBlank()) args.put("id", agentId)
        return rpc("startTeachRecording", args)
    }

    fun stopTeach(agentId: String = ""): JSONObject {
        val args = JSONObject()
        if (agentId.isNotBlank()) args.put("id", agentId)
        return rpc("stopTeachRecording", args)
    }

    fun updateHost(force: Boolean = true): JSONObject {
        return rpc("updateHostNow", JSONObject().put("force", force))
    }

    fun autoUpdateBox(): JSONObject = rpc("autoUpdateBoxNow")

    fun updateBoxImage(agentId: String): JSONObject {
        return rpc("updateForeverBox", JSONObject().put("id", agentId))
    }

    fun diskSaver(agentId: String): JSONObject {
        return rpc("requestDiskSaverAudit", JSONObject().put("id", agentId))
    }

    fun sharingState(): JSONObject = rpc("getSharingState")

    fun createAgent(name: String, description: String, kickstart: Boolean): JSONObject {
        return rpc(
            "createAgent",
            JSONObject()
                .put("name", name)
                .put("description", description)
                .put("isKickstartRequested", kickstart),
        )
    }

    fun openAgent(agentId: String): JSONObject {
        return rpc("openAgent", JSONObject().put("id", agentId))
    }

    fun boxStatus(agentId: String): JSONObject {
        return rpc("getForeverBoxStatus", JSONObject().put("id", agentId))
    }

    fun ensureBox(agentId: String): JSONObject {
        return rpc("ensureForeverBox", JSONObject().put("id", agentId))
    }

    fun handBack(agentId: String, dismiss: Boolean): JSONObject {
        return rpc(
            "handBackForeverBox",
            JSONObject()
                .put("id", agentId)
                .put("trigger", if (dismiss) "dismissed" else "button"),
        )
    }

    fun approveLocalTool(agentId: String, entryId: String, requestId: String, always: Boolean): JSONObject {
        return rpc(
            "resolveLocalToolPermission",
            JSONObject()
                .put("agentId", agentId)
                .put("entryId", entryId)
                .put("requestId", requestId)
                .put("resolution", if (always) "always" else "allow-once"),
        )
    }

    fun denyLocalTool(agentId: String, entryId: String, requestId: String, never: Boolean): JSONObject {
        return rpc(
            "resolveLocalToolPermission",
            JSONObject()
                .put("agentId", agentId)
                .put("entryId", entryId)
                .put("requestId", requestId)
                .put("resolution", if (never) "never" else "deny"),
        )
    }

    fun approveReview(agentId: String, entryId: String, requestId: String, always: Boolean): JSONObject {
        return rpc(
            "resolveAutoReviewApproval",
            JSONObject()
                .put("agentId", agentId)
                .put("entryId", entryId)
                .put("requestId", requestId)
                .put("resolution", if (always) "always" else "approved"),
        )
    }

    fun denyReview(agentId: String, entryId: String, requestId: String): JSONObject {
        return rpc(
            "resolveAutoReviewApproval",
            JSONObject()
                .put("agentId", agentId)
                .put("entryId", entryId)
                .put("requestId", requestId)
                .put("resolution", "denied"),
        )
    }

    fun answerWidget(agentId: String, entryId: String, value: String): JSONObject {
        return rpc(
            "respondToWidget",
            JSONObject()
                .put("agentId", agentId)
                .put("entryId", entryId)
                .put("value", value),
        )
    }

    fun dismissWidget(agentId: String, entryId: String): JSONObject {
        return rpc(
            "dismissWidget",
            JSONObject().put("agentId", agentId).put("entryId", entryId),
        )
    }

    fun submitSecret(agentId: String, entryId: String, value: String): JSONObject {
        return rpc(
            "submitSecret",
            JSONObject()
                .put("agentId", agentId)
                .put("entryId", entryId)
                .put("value", value),
        )
    }

    fun setLocalToolPermission(value: String): JSONObject {
        return rpc("setHostSettings", JSONObject().put("localToolPermission", value))
    }

    fun setAutoReview(enabled: Boolean, current: GbotSettings?): JSONObject {
        val auto = try {
            JSONObject(current?.autoReviewJson?.ifBlank { "{}" } ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        auto.put("isEnabled", enabled)
        if (!auto.has("allowInstructions")) auto.put("allowInstructions", org.json.JSONArray())
        if (!auto.has("blockInstructions")) auto.put("blockInstructions", org.json.JSONArray())
        return rpc("setHostSettings", JSONObject().put("autoReviewInstructions", auto))
    }

    fun setTimezone(iana: String): JSONObject {
        return rpc("setHostSettings", JSONObject().put("userTimeZoneOverride", iana))
    }

    fun search(query: String, limit: Int = 20): JSONObject {
        return rpc(
            "searchAgents",
            JSONObject().put("query", query).put("limit", limit.coerceIn(1, 40)),
        )
    }

    fun kickstart(agentId: String): JSONObject {
        return rpc("kickstartAgent", JSONObject().put("id", agentId))
    }

    fun duplicate(agentId: String): JSONObject {
        return rpc("duplicateAgent", JSONObject().put("id", agentId))
    }

    fun workflows(agentId: String): JSONObject {
        return rpc("getAgentWorkflows", JSONObject().put("id", agentId))
    }

    fun runWorkflow(agentId: String, workflowId: String): JSONObject {
        return rpc(
            "runAgentWorkflowNow",
            JSONObject().put("id", agentId).put("workflowId", workflowId),
        )
    }

    fun skills(): JSONObject = rpc("skillsCatalog")

    fun loginStart(): JSONObject {
        return post(JSONObject().put("action", "login_start"))
    }

    fun loginWait(uuid: String): JSONObject {
        val q = java.net.URLEncoder.encode(uuid, "UTF-8")
        return get("login_wait&uuid=$q")
    }

    fun listeners(): JSONObject = rpc("getListenerIntegrations")

    fun channels(agentId: String): JSONObject {
        return rpc("getAgentChannels", JSONObject().put("id", agentId))
    }

    fun listBoxMcp(serverIds: List<String> = emptyList()): JSONObject {
        val ids = org.json.JSONArray()
        for (id in serverIds.take(40)) {
            if (id.isNotBlank()) ids.put(id)
        }
        return rpc("listBoxMcpServers", JSONObject().put("serverIdentifiers", ids))
    }

    fun refreshChannel(agentId: String, platform: String): JSONObject {
        return rpc(
            "refreshChannel",
            JSONObject().put("id", agentId).put("platform", platform),
        )
    }

    fun mcpConnectUrl(platform: String): JSONObject {
        return rpc("getListenerConnectUrl", JSONObject().put("platform", platform))
    }

    fun refreshMcp(): JSONObject = rpc("refreshMcp")

    fun memories(agentId: String): JSONObject {
        return rpc("getAgentMemories", JSONObject().put("id", agentId))
    }

    fun automations(agentId: String): JSONObject {
        return rpc("getAgentAutomations", JSONObject().put("id", agentId))
    }

    fun setAutomationEnabled(agentId: String, automationId: String, enabled: Boolean): JSONObject {
        return rpc(
            "setAgentAutomationEnabled",
            JSONObject()
                .put("id", agentId)
                .put("automationId", automationId)
                .put("isEnabled", enabled),
        )
    }

    fun runAutomation(agentId: String, automationId: String): JSONObject {
        return rpc(
            "runAgentAutomationNow",
            JSONObject().put("id", agentId).put("automationId", automationId),
        )
    }

    fun updateAutomation(
        agentId: String,
        automationId: String,
        name: String,
        prompt: String,
        trigger: JSONObject,
        enabled: Boolean,
    ): JSONObject {
        val spec = JSONObject()
            .put("name", name)
            .put("prompt", prompt)
            .put("trigger", trigger)
            .put("isEnabled", enabled)
        return rpc(
            "updateAgentAutomation",
            JSONObject()
                .put("id", agentId)
                .put("automationId", automationId)
                .put("spec", spec),
        )
    }

    fun markRead(agentId: String): JSONObject {
        return rpc(
            "setAgentUnread",
            JSONObject().put("id", agentId).put("hasUnread", false),
        )
    }

    fun deleteAgent(agentId: String): JSONObject {
        return rpc("deleteAgent", JSONObject().put("id", agentId))
    }

    fun deleteAgents(ids: List<String>): JSONObject {
        val arr = org.json.JSONArray()
        ids.filter { it.isNotBlank() }.distinct().take(40).forEach { arr.put(it) }
        return rpc("deleteAgents", JSONObject().put("ids", arr))
    }

    fun resetBox(agentId: String): JSONObject {
        return rpc("resetForeverBox", JSONObject().put("id", agentId))
    }

    fun snapshotBoxStore(): JSONObject = rpc("snapshotBoxStoreNow")

    fun secretsStatus(): JSONObject = rpc("getBoxSecretsStatus")

    fun injectCookies(cookies: org.json.JSONArray): JSONObject {
        return rpc("injectChromeCookies", JSONObject().put("cookies", cookies))
    }

    fun createRoomFromAgent(agentId: String): JSONObject {
        return rpc("createRoomFromAgent", JSONObject().put("agentId", agentId))
    }

    fun createRoomInvite(roomId: String): JSONObject {
        return rpc("createRoomInvite", JSONObject().put("roomId", roomId))
    }

    fun joinRoom(link: String): JSONObject {
        return rpc("joinSharedRoom", JSONObject().put("link", link))
    }

    fun leaveRoom(roomId: String): JSONObject {
        return rpc("leaveSharedRoom", JSONObject().put("roomId", roomId))
    }

    fun respondJoin(requestId: String, approved: Boolean): JSONObject {
        return rpc(
            "respondToRoomJoinRequest",
            JSONObject().put("requestId", requestId).put("isApproved", approved),
        )
    }

    fun addAgentToRoom(roomId: String, agentId: String): JSONObject {
        return rpc(
            "addOwnAgentToSharedRoom",
            JSONObject().put("roomId", roomId).put("agentId", agentId),
        )
    }

    fun skillPublishTargets(): JSONObject = rpc("getSkillPublishTargets")

    fun publishSkill(workflowId: String, teamId: Long): JSONObject {
        return rpc(
            "publishSkill",
            JSONObject().put("workflowId", workflowId).put("teamId", teamId),
        )
    }

    fun unpublishSkill(workflowId: String): JSONObject {
        return rpc("unpublishSkill", JSONObject().put("workflowId", workflowId))
    }

    private fun get(actionQuery: String): JSONObject {
        val path = "/gbot.php?action=$actionQuery"
        val req = auth(path).get().build()
        return execute(req)
    }

    private fun post(body: JSONObject): JSONObject {
        val req = auth("/gbot.php")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        return execute(req)
    }

    private fun auth(path: String): Request.Builder {
        val b = Request.Builder().url(BuildConfig.API_BASE + path)
        tokenProvider()?.takeIf { it.isNotBlank() }?.let {
            b.header("Authorization", "Bearer $it")
        }
        return b
    }

    private fun execute(req: Request): JSONObject {
        return try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = try {
                    JSONObject(if (text.isBlank()) "{}" else text)
                } catch (_: Exception) {
                    JSONObject().put("ok", false).put("error", "invalid_json")
                }
                if (!resp.isSuccessful && !json.has("error")) {
                    json.put("error", "http_${resp.code}")
                }
                if (!resp.isSuccessful) json.put("ok", false)
                json
            }
        } catch (e: Exception) {
            JSONObject()
                .put("ok", false)
                .put("error", e.message ?: "request_failed")
        }
    }
}
