package io.grokify.os.apps.gbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GbotParseTest {
    @Test
    fun agentsFromArray() {
        val raw = JSONArray(
            """
            [
              {"id":"a1","name":"CexBot","description":"trades","isRunning":true,"hasUnread":true,"unreadCount":2,"lastMessagePreview":"hi"},
              {"id":"a2","name":"gIRC"}
            ]
            """.trimIndent(),
        )
        val agents = GbotParse.agents(raw)
        assertEquals(2, agents.size)
        assertEquals("CexBot", agents[0].name)
        assertTrue(agents[0].isRunning)
        assertEquals(2, agents[0].unreadCount)
        assertEquals("gIRC", agents[1].name)
    }

    @Test
    fun pendingAndTranscriptEnrichment() {
        val cards = GbotParse.pendingCards(
            JSONObject(
                """{"cards":[{"kind":"widget","entryId":"t1s3","agentId":"bot-1"}]}""",
            ),
        )
        assertEquals(1, cards.size)
        val bubbles = GbotParse.bubbles(
            JSONObject(
                """
                {"entries":[
                  {"kind":"send-message","id":"t1s3","timestampMs":1,
                   "message":{"type":"widget","widget":{"prompt":"Patch it?","options":[{"label":"Yes","value":"yes","style":"primary"}]}}}
                ]}
                """.trimIndent(),
            ),
        )
        val enriched = GbotParse.enrichPending(cards, bubbles)
        assertEquals("Patch it?", enriched[0].prompt)
        assertEquals("yes", enriched[0].options[0].value)
        assertTrue(enriched[0].options[0].primary)
    }

    @Test
    fun userAndAssistantBubbles() {
        val bubbles = GbotParse.bubbles(
            JSONArray(
                """
                [
                  {"kind":"message","id":"t1u","role":"user","content":"hello","timestampMs":10},
                  {"kind":"send-message","id":"t1s1","timestampMs":11,"message":{"type":"text","content":"hi back"}},
                  {"kind":"send-message","id":"t1s2","timestampMs":12,
                   "message":{"type":"local-tool-permission","ask":{"requestId":"r1","action":"run-command","target":"curl localhost"}}},
                  {"kind":"event","id":"e1","timestampMs":13,"event":{"type":"automation-changed","automationName":"Desk"}}
                ]
                """.trimIndent(),
            ),
        )
        assertEquals(GbotBubbleKind.User, bubbles[0].kind)
        assertEquals("hello", bubbles[0].text)
        assertEquals(GbotBubbleKind.Assistant, bubbles[1].kind)
        assertEquals(GbotBubbleKind.LocalTool, bubbles[2].kind)
        assertEquals("r1", bubbles[2].requestId)
        assertEquals("curl localhost", bubbles[2].toolTarget)
        assertEquals(GbotBubbleKind.Event, bubbles[3].kind)
    }

    @Test
    fun snapshotError() {
        val snap = GbotParse.snapshot(JSONObject("""{"ok":false,"error":"gbotd_unreachable"}"""))
        assertEquals("gbotd_unreachable", snap.error)
        assertTrue(snap.agents.isEmpty())
    }

    @Test
    fun unwrapNestedError() {
        assertEquals("Invalid Sand agent id: undefined", GbotParse.unwrapNestedError("""{"error":"Invalid Sand agent id: undefined"}"""))
    }

    @Test
    fun branchedEntriesSkipped() {
        val bubbles = GbotParse.bubbles(
            JSONArray("""[{"kind":"message","id":"x","role":"user","content":"nope","branched":true}]"""),
        )
        assertTrue(bubbles.isEmpty())
    }

    @Test
    fun searchHitsAndWorkflows() {
        val hits = GbotParse.searchHits(
            JSONArray(
                """[{"agentId":"a1","entryId":"e1","role":"assistant","snippet":"hello CexBot","timestampMs":9}]""",
            ),
        )
        assertEquals(1, hits.size)
        assertEquals("a1", hits[0].agentId)
        assertEquals("hello CexBot", hits[0].snippet)
        val wfs = GbotParse.workflows(
            JSONArray("""[{"id":"add-connector","name":"add-connector","description":"MCP"}]"""),
        )
        assertEquals("add-connector", wfs[0].id)
        val skills = GbotParse.skills(
            JSONArray("""[{"id":"s1","name":"waves","description":"charts","source":"marketplace"}]"""),
        )
        assertEquals("waves", skills[0].name)
        assertEquals("marketplace", skills[0].source)
    }

    @Test
    fun loginStartAndAutoReviewPreserve() {
        val login = GbotParse.loginStart(
            JSONObject("""{"ok":true,"data":{"loginUrl":"https://cursor.com/loginDeepControl?x=1","uuid":"abc-def-12"}}"""),
        )
        assertEquals("abc-def-12", login!!.uuid)
        assertTrue(login.loginUrl.startsWith("https://"))
        val settings = GbotParse.settings(
            JSONObject(
                """{"localToolPermission":"ask","autoReviewInstructions":{"isEnabled":true,"allowInstructions":["ok"],"blockInstructions":[]},"userTimeZone":"America/Denver"}""",
            ),
        )
        assertTrue(settings!!.autoReviewEnabled)
        assertTrue(settings.autoReviewJson.contains("allowInstructions"))
    }

    @Test
    fun enrichPendingDoesNotCrossAgentsWithSameEntryId() {
        val cards = listOf(
            GbotPendingCard(kind = "widget", entryId = "t1s3", requestId = "", agentId = "agent-a"),
            GbotPendingCard(kind = "widget", entryId = "t1s3", requestId = "", agentId = "agent-b"),
        )
        val bubbles = listOf(
            GbotBubble(
                id = "t1s3",
                kind = GbotBubbleKind.Widget,
                text = "Patch it here?",
                timestampMs = 1L,
                agentId = "agent-a",
                options = listOf(GbotWidgetOption("Patch", "patch", true)),
            ),
            GbotBubble(
                id = "t1s3",
                kind = GbotBubbleKind.Widget,
                text = "What's next?",
                timestampMs = 2L,
                agentId = "agent-b",
                options = listOf(GbotWidgetOption("Git init", "git", false)),
                allowCustom = true,
            ),
        )
        val enriched = GbotParse.enrichPending(cards, bubbles)
        assertEquals("Patch it here?", enriched[0].prompt)
        assertEquals("patch", enriched[0].options[0].value)
        assertEquals("What's next?", enriched[1].prompt)
        assertEquals("git", enriched[1].options[0].value)
        assertTrue(enriched[1].allowCustom)
    }

    @Test
    fun mergePendingKeepsStableOrderAndPriorEnrichment() {
        val previous = listOf(
            GbotPendingCard(kind = "widget", entryId = "t1s3", requestId = "", agentId = "a", prompt = "Patch"),
            GbotPendingCard(kind = "widget", entryId = "t2s4", requestId = "", agentId = "b", prompt = "Where next"),
        )
        val incoming = listOf(
            GbotPendingCard(kind = "widget", entryId = "t2s4", requestId = "", agentId = "b"),
            GbotPendingCard(kind = "widget", entryId = "t1s3", requestId = "", agentId = "a"),
            GbotPendingCard(kind = "widget", entryId = "t9s1", requestId = "", agentId = "c", prompt = "New"),
        )
        val merged = GbotParse.mergePending(previous, incoming)
        assertEquals(listOf("t1s3", "t2s4", "t9s1"), merged.map { it.entryId })
        assertEquals("Patch", merged[0].prompt)
        assertEquals("Where next", merged[1].prompt)
        assertEquals("New", merged[2].prompt)
    }

    @Test
    fun vncRoutingStampsTokenOnSameHostAssets() {
        val page = "https://pod.example.cursorvm.com/vnc.html?network_token=nto-abc" +
            "&resume_lower_s=900&resume_upper_s=18000" +
            "&path=websockify%3Ftoken%3D4%26network_token%3Dnto-abc"
        val session = GbotVncRouting.sessionFromPageUrl(page)!!
        assertEquals("pod.example.cursorvm.com", session.host)
        assertEquals("nto-abc", session.token)
        val css = GbotVncRouting.applyRouting("https://pod.example.cursorvm.com/app/styles/base.css", session)
        assertTrue(css.contains("network_token=nto-abc"))
        assertTrue(css.contains("resume_lower_s=900"))
        val other = GbotVncRouting.applyRouting("https://cdn.example/app/ui.js", session)
        assertEquals("https://cdn.example/app/ui.js", other)
        val decorated = GbotVncRouting.decoratePageUrl(page)
        assertTrue(decorated.contains("autoconnect=true"))
        assertTrue(decorated.contains("resize=scale"))
    }

    @Test
    fun automationsNestedAndRunningRun() {
        val nested = GbotParse.automations(
            JSONArray(
                """
                [{"agentId":"bot-1","automation":{
                  "id":"desk-book-check","name":"Desk book check","isEnabled":true,
                  "triggerDescription":"Every 15 minutes","lastRunAt":9,"nextRunAt":20,
                  "runs":[{"id":"r1","trigger":"schedule","startedAt":9,"finishedAt":0,"status":""}]
                }}]
                """.trimIndent(),
            ),
        )
        assertEquals(1, nested.size)
        assertEquals("bot-1", nested[0].agentId)
        assertEquals("desk-book-check", nested[0].id)
        assertTrue(nested[0].isRunning)
        assertEquals("r1", nested[0].latestRun!!.id)

        val flat = GbotParse.automations(
            JSONArray(
                """
                [{"id":"desk-book-check","name":"Desk book check","agentId":"bot-1",
                  "isEnabled":true,"schedule":"Every 15 minutes","lastRunAt":9,
                  "runs":[{"id":"r1","trigger":"schedule","startedAt":9,"finishedAt":11,"status":"ok"}]}]
                """.trimIndent(),
            ),
        )
        assertEquals("bot-1", flat[0].agentId)
        assertTrue(!flat[0].isRunning)
        assertTrue(flat[0].latestRun!!.ok)
    }

    @Test
    fun snapshotIncludesAutomations() {
        val snap = GbotParse.snapshot(
            JSONObject(
                """
                {"ok":true,"data":{
                  "agents":[{"id":"bot-1","name":"CexBot","isRunning":true}],
                  "pending":{"cards":[]},
                  "automations":[{"id":"desk-book-check","name":"Desk book check","agentId":"bot-1",
                    "runs":[{"id":"r1","startedAt":1,"finishedAt":0,"status":""}]}]
                }}
                """.trimIndent(),
            ),
        )
        assertEquals("CexBot", snap.agents[0].name)
        assertTrue(snap.automations[0].isRunning)
    }

    @Test
    fun watchEvalSeedsRunningThenFinished() {
        val running = GbotSnapshot(
            health = null,
            auth = null,
            host = null,
            settings = null,
            agents = listOf(
                GbotAgent(
                    id = "bot-1",
                    name = "CexBot",
                    description = "",
                    avatarColor = "",
                    isActive = true,
                    isRunning = true,
                    isComposing = false,
                    hasUnread = false,
                    unreadCount = 0,
                    lastPreview = "checking desk",
                    lastActivityAt = 1L,
                    newestEntryId = "e1",
                ),
            ),
            pending = emptyList(),
            automations = listOf(
                GbotAutomation(
                    id = "desk-book-check",
                    name = "Desk book check",
                    enabled = true,
                    schedule = "Every 15 minutes",
                    lastRunAt = 1L,
                    agentId = "bot-1",
                    latestRun = GbotAutomationRun("r1", "schedule", 1L, 0L, ""),
                ),
            ),
        )
        val (seeded, startNotices) = GbotWatchEval.diff(GbotWatchState(), running, nowMs = 10L)
        assertTrue(seeded.seeded)
        assertEquals(1, startNotices.count { it.kind == GbotWatchNotice.Kind.AutoRunning })
        assertTrue(GbotWatchEval.hasActiveWork(running))

        val done = running.copy(
            agents = running.agents.map { it.copy(isRunning = false, lastPreview = "book unchanged") },
            automations = running.automations.map {
                it.copy(latestRun = it.latestRun!!.copy(finishedAt = 61_000L, status = "ok"))
            },
        )
        val (_, doneNotices) = GbotWatchEval.diff(seeded, done, nowMs = 61_000L)
        assertEquals(1, doneNotices.count { it.kind == GbotWatchNotice.Kind.AutoFinished })
        assertTrue(doneNotices.any { it.title.contains("finished") })
        assertTrue(!GbotWatchEval.hasActiveWork(done))
    }

    @Test
    fun watchEvalNewPending() {
        val idle = GbotSnapshot(
            health = null,
            auth = null,
            host = null,
            settings = null,
            agents = listOf(
                GbotAgent(
                    id = "bot-1",
                    name = "CexBot",
                    description = "",
                    avatarColor = "",
                    isActive = true,
                    isRunning = false,
                    isComposing = false,
                    hasUnread = true,
                    unreadCount = 1,
                    lastPreview = "?",
                    lastActivityAt = 1L,
                    newestEntryId = "t1",
                ),
            ),
            pending = emptyList(),
        )
        val (seeded, _) = GbotWatchEval.diff(GbotWatchState(), idle)
        val waiting = idle.copy(
            pending = listOf(
                GbotPendingCard(
                    kind = "widget",
                    entryId = "t1s3",
                    requestId = "",
                    agentId = "bot-1",
                    prompt = "Patch it?",
                ),
            ),
        )
        val (_, notices) = GbotWatchEval.diff(seeded, waiting)
        assertEquals(1, notices.size)
        assertEquals(GbotWatchNotice.Kind.Pending, notices[0].kind)
        assertTrue(notices[0].title.contains("needs you"))
    }

    @Test
    fun automationsParsePromptAndRunHistory() {
        val parsed = GbotParse.automations(
            JSONArray(
                """
                [{"agentId":"bot-1","automation":{
                  "id":"desk-book-check","name":"Desk book check","isEnabled":true,
                  "triggerDescription":"Every 15 minutes","schedule":"*/15 * * * *",
                  "trigger":{"type":"cron","schedule":"*/15 * * * *"},
                  "lastRunAt":90,"nextRunAt":120,
                  "prompt":"You are CexBot. Check the demo desk.",
                  "runs":[
                    {"id":"r2","trigger":"schedule","startedAt":90,"finishedAt":99,"status":"ok"},
                    {"id":"r1","trigger":"schedule","startedAt":10,"finishedAt":20,"status":"ok"}
                  ]
                }}]
                """.trimIndent(),
            ),
        )
        assertEquals(1, parsed.size)
        assertEquals("You are CexBot. Check the demo desk.", parsed[0].prompt)
        assertEquals("*/15 * * * *", parsed[0].cron)
        assertEquals("cron", JSONObject(parsed[0].triggerJson).optString("type"))
        assertEquals("*/15 * * * *", JSONObject(parsed[0].triggerJson).optString("schedule"))
        assertEquals(120L, parsed[0].nextRunAt)
        assertEquals(2, parsed[0].runs.size)
        assertEquals("r2", parsed[0].latestRun!!.id)
        assertEquals("r1", parsed[0].runs[1].id)
    }

    @Test
    fun watchEvalFinishedOmitsStalePreview() {
        val running = deskSnap(
            running = true,
            preview = "ADA eased hours ago",
            newest = "t24s40",
            runFinishedAt = 0L,
        )
        val (seeded, _) = GbotWatchEval.diff(GbotWatchState(), running, nowMs = 10L)
        val done = deskSnap(
            running = false,
            preview = "ADA eased hours ago",
            newest = "t24s40",
            runFinishedAt = 61_000L,
        )
        val (_, notices) = GbotWatchEval.diff(seeded, done, nowMs = 61_000L)
        val finished = notices.filter { it.kind == GbotWatchNotice.Kind.AutoFinished }
        assertEquals(1, finished.size)
        assertTrue(!finished[0].body.contains("ADA eased hours ago"))
    }

    @Test
    fun watchEvalFinishedIncludesPreviewOnlyWhenEntryChanged() {
        val running = deskSnap(
            running = true,
            preview = "ADA eased hours ago",
            newest = "t24s40",
            runFinishedAt = 0L,
        )
        val (seeded, _) = GbotWatchEval.diff(GbotWatchState(), running, nowMs = 10L)
        val done = deskSnap(
            running = false,
            preview = "book unchanged this cycle",
            newest = "t24s41",
            runFinishedAt = 61_000L,
        )
        val (_, notices) = GbotWatchEval.diff(seeded, done, nowMs = 61_000L)
        val finished = notices.filter { it.kind == GbotWatchNotice.Kind.AutoFinished }
        assertEquals(1, finished.size)
        assertTrue(finished[0].body.contains("book unchanged this cycle"))
    }

    @Test
    fun watchEvalAgentRunningOmitsStalePreview() {
        val snap = GbotSnapshot(
            health = null,
            auth = null,
            host = null,
            settings = null,
            agents = listOf(
                testAgent(
                    running = true,
                    preview = "hello from last week",
                    newest = "t1s1",
                ),
            ),
            pending = emptyList(),
        )
        val (_, notices) = GbotWatchEval.diff(GbotWatchState(), snap, nowMs = 10L)
        val running = notices.filter { it.kind == GbotWatchNotice.Kind.AgentRunning }
        assertEquals(1, running.size)
        assertTrue(!running[0].body.contains("hello from last week"))
    }

    @Test
    fun watchEvalAgentIdleOmitsStalePreview() {
        val running = GbotSnapshot(
            health = null,
            auth = null,
            host = null,
            settings = null,
            agents = listOf(
                testAgent(running = true, preview = "hello from last week", newest = "t1s1"),
            ),
            pending = emptyList(),
        )
        val (seeded, _) = GbotWatchEval.diff(GbotWatchState(), running, nowMs = 10L)
        val idle = running.copy(
            agents = running.agents.map { it.copy(isRunning = false) },
        )
        val (_, notices) = GbotWatchEval.diff(seeded, idle, nowMs = 20L)
        val done = notices.filter { it.kind == GbotWatchNotice.Kind.AgentIdle }
        assertEquals(1, done.size)
        assertTrue(!done[0].body.contains("hello from last week"))
    }

    @Test
    fun connectorAndPeerBubblesParse() {
        val bubbles = GbotParse.bubbles(
            JSONArray(
                """
                [
                  {"kind":"message","id":"t46u","role":"user","content":"NO WASH","timestampMs":10,
                   "fromAgent":{"id":"wave","name":"Wave Analyst"}},
                  {"kind":"send-message","id":"t46s0","timestampMs":11,
                   "message":{"type":"text","content":"Coordinator.","toAgent":{"id":"wave","name":"Wave Analyst"}}},
                  {"kind":"send-message","id":"t46s2","timestampMs":12,
                   "message":{"type":"connector","name":"GitHub","pluginId":"plugin:github",
                     "status":"needsAuth","connector":{"name":"GitHub","status":"needsAuth"}}}
                ]
                """.trimIndent(),
            ),
            "bot-1",
        )
        assertEquals(3, bubbles.size)
        assertEquals("Wave Analyst", bubbles[0].fromAgentName)
        assertEquals(GbotBubbleKind.User, bubbles[0].kind)
        assertEquals("Wave Analyst", bubbles[1].toAgentName)
        assertEquals(GbotBubbleKind.Connector, bubbles[2].kind)
        assertEquals("GitHub", bubbles[2].connectorName)
        assertEquals("needsAuth", bubbles[2].connectorStatus)
        assertEquals("plugin:github", bubbles[2].connectorPluginId)
    }

    @Test
    fun agentPreviewSkipsConnectUndefined() {
        val agent = GbotParse.agent(
            JSONObject(
                """{"id":"bot-1","name":"CexBot","lastMessagePreview":"Connect undefined",
                    "lastMessagePreviewSource":{"kind":"connector_connect"},
                    "lastEntry":{"kind":"text","text":"Coordinator. Stand by."}}""",
            ),
        )!!
        assertEquals("Coordinator. Stand by.", agent.lastPreview)
    }

    @Test
    fun mergeBubblesDropsStaleShorterTail() {
        fun b(id: String, ts: Long, text: String) = GbotBubble(
            id = id,
            kind = GbotBubbleKind.Assistant,
            text = text,
            timestampMs = ts,
            agentId = "bot-1",
        )
        val current = listOf(b("a", 1, "old-1"), b("b", 2, "old-2"), b("c", 30, "now"))
        val stale = listOf(b("a", 1, "old-1"), b("b", 2, "old-2"))
        val merged = GbotParse.mergeBubbles(current, stale)
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
        assertEquals("now", merged.last().text)
    }

    @Test
    fun mergeBubblesKeepsEarlierHistoryWhenPollReturnsSuffix() {
        fun b(id: String, ts: Long, text: String) = GbotBubble(
            id = id,
            kind = GbotBubbleKind.Assistant,
            text = text,
            timestampMs = ts,
            agentId = "bot-1",
        )
        val loaded = listOf(b("a", 1, "earlier"), b("b", 2, "mid"), b("c", 3, "now"))
        val poll = listOf(b("b", 2, "mid"), b("c", 3, "now updated"))
        val merged = GbotParse.mergeBubbles(loaded, poll)
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
        assertEquals("earlier", merged[0].text)
        assertEquals("now updated", merged[2].text)
    }

    @Test
    fun mergeBubblesIgnoresOtherAgentIncoming() {
        fun b(id: String, agent: String, ts: Long) = GbotBubble(
            id = id,
            kind = GbotBubbleKind.Assistant,
            text = id,
            timestampMs = ts,
            agentId = agent,
        )
        val cex = listOf(b("c1", "cex", 10), b("c2", "cex", 20))
        val wave = listOf(b("w1", "wave", 1), b("w2", "wave", 2))
        val merged = GbotParse.mergeBubbles(cex, wave)
        assertEquals(listOf("w1", "w2"), merged.map { it.id })
    }

    @Test
    fun coalesceSnapshotKeepsAgentsWhenUpstreamListFails() {
        val previous = GbotSnapshot(
            health = null,
            auth = null,
            host = null,
            settings = null,
            agents = listOf(testAgent(running = false, preview = "ok", newest = "t1")),
            pending = emptyList(),
        )
        val failed = GbotParse.snapshot(
            JSONObject(
                """{"ok":true,"data":{"agents":[],"pending":{"cards":[]},
                    "upstream":{"agents_ok":false}}}""",
            ),
        )
        val kept = GbotParse.coalesceSnapshot(previous, failed)
        assertEquals("bot-1", kept.agents[0].id)
        assertTrue(!kept.agentsOk)
    }

    @Test
    fun coalesceSnapshotKeepsAgentsOnHardError() {
        val previous = GbotSnapshot(
            health = null,
            auth = null,
            host = null,
            settings = null,
            agents = listOf(testAgent(running = false, preview = "ok", newest = "t1")),
            pending = emptyList(),
        )
        val failed = GbotParse.snapshot(JSONObject("""{"ok":false,"error":"gbotd_unreachable"}"""))
        val kept = GbotParse.coalesceSnapshot(previous, failed)
        assertEquals(1, kept.agents.size)
        assertEquals("gbotd_unreachable", kept.error)
    }

    @Test
    fun settingsAndChannelsParseConnectors() {
        val settings = GbotParse.settings(
            JSONObject(
                """{"localToolPermission":"ask","mcpBoxServers":[
                    {"id":"mcp-gh","name":"GitHub","status":"connected"},
                    "stdio-files"
                  ]}""",
            ),
        )!!
        assertEquals(2, settings.mcpServers.size)
        assertEquals("GitHub", settings.mcpServers[0].name)
        assertEquals("connected", settings.mcpServers[0].status)
        assertEquals("stdio-files", settings.mcpServers[1].name)
        val channels = GbotParse.channels(
            JSONObject(
                """{"manifests":[{"platform":"discord","displayName":"Discord","availability":"coming-soon",
                    "blurb":{"message":"DMs soon"}}],
                    "connections":[{"platform":"slack"}]}""",
            ),
        )
        assertEquals(2, channels.size)
        assertEquals("discord", channels[0].platform)
        assertEquals("coming-soon", channels[0].availability)
        assertTrue(channels[1].connected)
    }

    @Test
    fun watchStateRoundTripNewestAtStart() {
        val state = GbotWatchState(
            seeded = true,
            runningAutoKeys = setOf("bot-1\u0000desk\u0000r1"),
            newestAtRunStart = mapOf("bot-1\u0000desk\u0000r1" to "t24s40"),
        )
        val back = GbotWatchEval.decode(GbotWatchEval.encode(state))
        assertEquals(state.newestAtRunStart, back.newestAtRunStart)
        assertEquals(state.runningAutoKeys, back.runningAutoKeys)
    }

    private fun testAgent(
        running: Boolean,
        preview: String,
        newest: String,
        activity: Long = 1L,
    ) = GbotAgent(
        id = "bot-1",
        name = "CexBot",
        description = "",
        avatarColor = "",
        isActive = true,
        isRunning = running,
        isComposing = false,
        hasUnread = false,
        unreadCount = 0,
        lastPreview = preview,
        lastActivityAt = activity,
        newestEntryId = newest,
    )

    private fun deskSnap(
        running: Boolean,
        preview: String,
        newest: String,
        runFinishedAt: Long,
    ) = GbotSnapshot(
        health = null,
        auth = null,
        host = null,
        settings = null,
        agents = listOf(testAgent(running = running, preview = preview, newest = newest)),
        pending = emptyList(),
        automations = listOf(
            GbotAutomation(
                id = "desk-book-check",
                name = "Desk book check",
                enabled = true,
                schedule = "Every 15 minutes",
                lastRunAt = 1L,
                agentId = "bot-1",
                latestRun = GbotAutomationRun("r1", "schedule", 1L, runFinishedAt, if (runFinishedAt > 0L) "ok" else ""),
            ),
        ),
    )
}
