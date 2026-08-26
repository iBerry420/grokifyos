package io.grokify.os.apps.discord

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.grokify.os.apps.plugin.HostAiClient
import io.grokify.os.ui.theme.GrokifyColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

internal val discordAiLimits = listOf(10, 25, 50, 100, 250, 500)
internal val discordAiAnalyzeLimits = listOf(10, 25, 50, 100, 250)
internal val discordAiTimeframes = listOf(
    "1h" to "1h",
    "6h" to "6h",
    "1d" to "24h",
    "3d" to "3d",
    "1w" to "7d",
    "1m" to "1m",
    "all" to "all",
    "between" to "between",
)

@Composable
private fun discordAiSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = GrokifyColors.Void,
    checkedTrackColor = GrokifyColors.GlowMint,
    uncheckedThumbColor = GrokifyColors.TextMuted,
    uncheckedTrackColor = GrokifyColors.PanelSoft,
)

internal fun discordAiJobRunning(job: DiscordAiJob): Boolean =
    job.status == "queued" || job.status == "running"

internal fun discordAiResultIsJobSummary(row: DiscordAiResult): Boolean =
    row.messageId <= 0 && row.tags.isEmpty()

/** Split a long analysis summary into xAI/device TTS-sized pieces (API cap 2000). */
internal fun discordAiTtsChunks(text: String, maxChars: Int = 1800): List<String> {
    val clean = text.trim()
    if (clean.isEmpty()) return emptyList()
    if (maxChars < 32 || clean.length <= maxChars) return listOf(clean)
    val out = mutableListOf<String>()
    var rest = clean
    while (rest.isNotEmpty()) {
        if (rest.length <= maxChars) {
            out += rest
            break
        }
        val window = rest.take(maxChars)
        val breakAt = listOf("\n\n", "\n", ". ", "? ", "! ", "; ")
            .map { sep ->
                val at = window.lastIndexOf(sep)
                if (at >= maxChars / 3) at + sep.length else -1
            }
            .maxOrNull() ?: -1
        val cut = when {
            breakAt > 0 -> breakAt
            else -> {
                val space = window.lastIndexOf(' ')
                if (space > maxChars / 2) space else maxChars
            }
        }
        out += rest.take(cut).trim()
        rest = rest.drop(cut).trim()
    }
    return out.filter { it.isNotBlank() }
}

@Composable
internal fun DiscordAiTab(
    bots: List<DiscordBot>,
    guilds: List<DiscordGuild>,
    channelsByGuild: Map<String, DiscordChannelBundle>,
    jobs: List<DiscordAiJob>,
    results: List<DiscordAiResult>,
    selectedBotId: Int,
    subTab: String,
    timeframe: String,
    fromDate: String,
    toDate: String,
    limit: Int,
    skipTagged: Boolean,
    prompt: String,
    sort: String,
    resultStatus: String,
    resultGuildId: String,
    search: String,
    expandedGuildId: String?,
    channelKind: String,
    channelQuery: String,
    jobHasMore: Boolean,
    resultHasMore: Boolean,
    busy: Boolean,
    analyzing: Boolean,
    headers: Map<String, String>,
    onBot: (Int) -> Unit,
    onSubTab: (String) -> Unit,
    onTimeframe: (String) -> Unit,
    onFromDate: (String) -> Unit,
    onToDate: (String) -> Unit,
    onLimit: (Int) -> Unit,
    onSkipTagged: (Boolean) -> Unit,
    onPrompt: (String) -> Unit,
    onSort: (String) -> Unit,
    onResultStatus: (String) -> Unit,
    onResultGuild: (String) -> Unit,
    onSearch: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onExpandGuild: (String) -> Unit,
    onChannelKind: (String) -> Unit,
    onChannelQuery: (String) -> Unit,
    onSubmitChannelQuery: () -> Unit,
    onMoreChannels: () -> Unit,
    onMoreJobs: () -> Unit,
    onMoreResults: () -> Unit,
    onStart: (kind: String, guildId: String, channelId: String, userId: String, label: String) -> Unit,
    onCancel: (Int) -> Unit,
    onOpenUser: (DiscordProfileKey) -> Unit,
) {
    val appCtx = LocalContext.current.applicationContext
    val ttsStore = remember { DiscordStore(appCtx) }
    val ttsScope = rememberCoroutineScope()
    val speakGen = remember { AtomicInteger(0) }
    var speakingJobId by remember { mutableIntStateOf(0) }
    var speakStatus by remember { mutableStateOf("") }
    DisposableEffect(Unit) {
        onDispose {
            speakGen.incrementAndGet()
            HostAiClient.stopSpeaking(appCtx)
        }
    }
    fun listenToSummary(jobId: Int, text: String) {
        if (text.isBlank()) return
        if (speakingJobId == jobId) {
            speakGen.incrementAndGet()
            HostAiClient.stopSpeaking(appCtx)
            speakingJobId = 0
            speakStatus = ""
            return
        }
        val gen = speakGen.incrementAndGet()
        HostAiClient.stopSpeaking(appCtx)
        speakingJobId = jobId
        speakStatus = "Playing…"
        ttsScope.launch(Dispatchers.IO) {
            var ok = true
            var err = ""
            try {
                val chunks = discordAiTtsChunks(text)
                if (chunks.isEmpty()) {
                    ok = false
                    err = "empty"
                } else {
                    for (chunk in chunks) {
                        if (speakGen.get() != gen) {
                            ok = false
                            err = ""
                            break
                        }
                        val opts = JSONObject()
                            .put("voice_id", ttsStore.aiVoiceId)
                            .put("prefer_device", ttsStore.aiPreferDeviceTts)
                            .put("language", "en")
                            .put("wait", true)
                            .toString()
                        val raw = HostAiClient.speak(appCtx, chunk, opts)
                        val parsed = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
                        if (!parsed.optBoolean("ok", false)) {
                            ok = false
                            err = parsed.optString("error").ifBlank { "tts_failed" }
                            break
                        }
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (speakGen.get() == gen) {
                        speakingJobId = 0
                        speakStatus = if (ok) "" else err.ifBlank { "" }
                    }
                }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (bots.isEmpty()) {
                    DiscordMeta("Load bots first")
                } else {
                    bots.forEach { b ->
                        DiscordFilterChip(b.name, selectedBotId == b.id) { onBot(b.id) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DiscordFilterChip("Activity", subTab == "activity") { onSubTab("activity") }
                DiscordFilterChip("Tag", subTab == "tag") { onSubTab("tag") }
                DiscordFilterChip("Analyze", subTab == "analyze") { onSubTab("analyze") }
            }
        }
        when (subTab) {
            "tag" -> DiscordAiScopePane(
                kind = "tag",
                actionLabel = "Tag",
                hint = "Tag messages one-by-one (up to 32 semantic tags). Runs in the background — start another job anytime; they share the worker one message at a time.",
                guilds = guilds,
                channelsByGuild = channelsByGuild,
                timeframe = timeframe,
                fromDate = fromDate,
                toDate = toDate,
                limit = limit,
                limits = discordAiLimits,
                skipTagged = skipTagged,
                showSkipTagged = true,
                prompt = prompt,
                showPrompt = false,
                expandedGuildId = expandedGuildId,
                channelKind = channelKind,
                channelQuery = channelQuery,
                busy = busy,
                analyzing = analyzing,
                selectedBotId = selectedBotId,
                onTimeframe = onTimeframe,
                onFromDate = onFromDate,
                onToDate = onToDate,
                onLimit = onLimit,
                onSkipTagged = onSkipTagged,
                onPrompt = onPrompt,
                onExpandGuild = onExpandGuild,
                onChannelKind = onChannelKind,
                onChannelQuery = onChannelQuery,
                onSubmitChannelQuery = onSubmitChannelQuery,
                onMoreChannels = onMoreChannels,
                onStart = onStart,
            )
            "analyze" -> DiscordAiScopePane(
                kind = "analyze",
                actionLabel = "Analyze",
                hint = "Read the newest messages (including tags when present) and write a detailed summary of what took place. Runs on the server.",
                guilds = guilds,
                channelsByGuild = channelsByGuild,
                timeframe = timeframe,
                fromDate = fromDate,
                toDate = toDate,
                limit = if (limit in discordAiAnalyzeLimits) limit else 50,
                limits = discordAiAnalyzeLimits,
                skipTagged = false,
                showSkipTagged = false,
                prompt = prompt,
                showPrompt = true,
                expandedGuildId = expandedGuildId,
                channelKind = channelKind,
                channelQuery = channelQuery,
                busy = busy,
                analyzing = analyzing,
                selectedBotId = selectedBotId,
                onTimeframe = onTimeframe,
                onFromDate = onFromDate,
                onToDate = onToDate,
                onLimit = onLimit,
                onSkipTagged = {},
                onPrompt = onPrompt,
                onExpandGuild = onExpandGuild,
                onChannelKind = onChannelKind,
                onChannelQuery = onChannelQuery,
                onSubmitChannelQuery = onSubmitChannelQuery,
                onMoreChannels = onMoreChannels,
                onStart = onStart,
            )
            else -> DiscordAiActivityPane(
                jobs = jobs,
                results = results,
                guilds = guilds,
                sort = sort,
                resultStatus = resultStatus,
                resultGuildId = resultGuildId,
                search = search,
                jobHasMore = jobHasMore,
                resultHasMore = resultHasMore,
                busy = busy,
                analyzing = analyzing,
                headers = headers,
                speakingJobId = speakingJobId,
                speakStatus = speakStatus,
                onSort = onSort,
                onResultStatus = onResultStatus,
                onResultGuild = onResultGuild,
                onSearch = onSearch,
                onSubmitSearch = onSubmitSearch,
                onMoreJobs = onMoreJobs,
                onMoreResults = onMoreResults,
                onCancel = onCancel,
                onListen = { id, text -> listenToSummary(id, text) },
                onOpenUser = onOpenUser,
            )
        }
    }
}

@Composable
private fun DiscordAiActivityPane(
    jobs: List<DiscordAiJob>,
    results: List<DiscordAiResult>,
    guilds: List<DiscordGuild>,
    sort: String,
    resultStatus: String,
    resultGuildId: String,
    search: String,
    jobHasMore: Boolean,
    resultHasMore: Boolean,
    busy: Boolean,
    analyzing: Boolean,
    headers: Map<String, String>,
    speakingJobId: Int,
    speakStatus: String,
    onSort: (String) -> Unit,
    onResultStatus: (String) -> Unit,
    onResultGuild: (String) -> Unit,
    onSearch: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onMoreJobs: () -> Unit,
    onMoreResults: () -> Unit,
    onCancel: (Int) -> Unit,
    onListen: (Int, String) -> Unit,
    onOpenUser: (DiscordProfileKey) -> Unit,
) {
    val live = jobs.filter { discordAiJobRunning(it) }
    val summaries = jobs.filter {
        it.kind == "analyze" && it.status == "done" && it.summary.isNotBlank() && live.none { liveJob -> liveJob.id == it.id }
    }.take(8)
    val taggedResults = results.filterNot { discordAiResultIsJobSummary(it) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            DiscordSearchRow(search, "Filter user, tag, channel", onSearch, onSubmitSearch)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DiscordFilterChip("Newest", sort == "newest") { onSort("newest") }
                DiscordFilterChip("Oldest", sort == "oldest") { onSort("oldest") }
                DiscordFilterChip("Tags", sort == "tags") { onSort("tags") }
                DiscordFilterChip("All", resultStatus.isBlank()) { onResultStatus("") }
                DiscordFilterChip("Tagged", resultStatus == "ok") { onResultStatus("ok") }
                DiscordFilterChip("Errors", resultStatus == "error") { onResultStatus("error") }
            }
            Spacer(Modifier.height(8.dp))
            DiscordGuildFilterRow(guilds = guilds, selectedGuildId = resultGuildId, onGuild = onResultGuild)
            Spacer(Modifier.height(6.dp))
            DiscordMeta("Jobs keep running if you leave the app. Tap a name or avatar to open that profile. Listen reads the summary with the voice in Settings.")
            if (speakStatus.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                DiscordMeta(speakStatus, color = GrokifyColors.GlowRose)
            }
        }
        if (live.isNotEmpty()) {
            items(live, key = { "job-${it.id}" }) { job ->
                DiscordAiJobCard(
                    job,
                    analyzing,
                    speaking = speakingJobId == job.id,
                    onCancel = onCancel,
                    onListen = onListen,
                )
            }
        }
        if (summaries.isNotEmpty()) {
            item {
                Text("Summaries", color = GrokifyColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            items(summaries, key = { "sum-${it.id}" }) { job ->
                DiscordAiJobCard(
                    job,
                    analyzing = false,
                    speaking = speakingJobId == job.id,
                    onCancel = {},
                    onListen = onListen,
                )
            }
        }
        if (taggedResults.isEmpty() && live.isEmpty() && summaries.isEmpty() && !busy) {
            item { DiscordEmpty("No AI work yet. Open Tag to label messages, or Analyze for a summary.") }
        }
        items(taggedResults, key = { "res-${it.id}" }) { row ->
            DiscordAiResultCard(row, headers, onOpenUser)
        }
        if (resultHasMore) {
            item {
                TextButton(onClick = onMoreResults, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Load more", color = GrokifyColors.GlowCyan)
                }
            }
        }
        if (jobHasMore && live.isEmpty()) {
            item {
                TextButton(onClick = onMoreJobs, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Older jobs", color = GrokifyColors.TextMuted)
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun DiscordAiScopePane(
    kind: String,
    actionLabel: String,
    hint: String,
    guilds: List<DiscordGuild>,
    channelsByGuild: Map<String, DiscordChannelBundle>,
    timeframe: String,
    fromDate: String,
    toDate: String,
    limit: Int,
    limits: List<Int>,
    skipTagged: Boolean,
    showSkipTagged: Boolean,
    prompt: String,
    showPrompt: Boolean,
    expandedGuildId: String?,
    channelKind: String,
    channelQuery: String,
    busy: Boolean,
    analyzing: Boolean,
    selectedBotId: Int,
    onTimeframe: (String) -> Unit,
    onFromDate: (String) -> Unit,
    onToDate: (String) -> Unit,
    onLimit: (Int) -> Unit,
    onSkipTagged: (Boolean) -> Unit,
    onPrompt: (String) -> Unit,
    onExpandGuild: (String) -> Unit,
    onChannelKind: (String) -> Unit,
    onChannelQuery: (String) -> Unit,
    onSubmitChannelQuery: () -> Unit,
    onMoreChannels: () -> Unit,
    onStart: (String, String, String, String, String) -> Unit,
) {
    val canRun = selectedBotId > 0 && !busy
    val actionColor = if (kind == "analyze") GrokifyColors.GlowCyan else GrokifyColors.GlowMint
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            DiscordMeta(hint)
            Spacer(Modifier.height(8.dp))
            Text("Range", color = GrokifyColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                discordAiTimeframes.forEach { (id, label) ->
                    DiscordFilterChip(label, timeframe == id) { onTimeframe(id) }
                }
            }
            if (timeframe == "between") {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    fromDate,
                    onFromDate,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("From (YYYY-MM-DD)") },
                    singleLine = true,
                    colors = discordFieldColors(),
                    shape = RoundedCornerShape(12.dp),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    toDate,
                    onToDate,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("To (YYYY-MM-DD)") },
                    singleLine = true,
                    colors = discordFieldColors(),
                    shape = RoundedCornerShape(12.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text("Limit (newest first)", color = GrokifyColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                limits.forEach { n ->
                    DiscordFilterChip("$n", limit == n) { onLimit(n) }
                }
            }
            if (showSkipTagged) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Skip already tagged", color = GrokifyColors.TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Switch(skipTagged, onSkipTagged, colors = discordAiSwitchColors())
                }
            }
            if (showPrompt) {
                Spacer(Modifier.height(10.dp))
                Text("Prompt", color = GrokifyColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    prompt,
                    onPrompt,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Optional focus") },
                    placeholder = { Text("e.g. who started the argument, action items, trolling…") },
                    minLines = 3,
                    maxLines = 6,
                    colors = discordFieldColors(),
                    shape = RoundedCornerShape(12.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Leave blank for a full summary. A prompt steers the analysis without inventing extra events.",
                    color = GrokifyColors.TextDim,
                    fontSize = 11.sp,
                )
            }
            if (selectedBotId <= 0) {
                DiscordMeta("Pick a bot above")
            }
            if (analyzing) {
                DiscordMeta("A job is already running — you can start another. They take turns, one message at a time.")
            }
        }
        item {
            DiscordCard {
                Text("All channels", color = GrokifyColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                DiscordMeta("Newest $limit messages this bot ingested, any guild")
                TextButton(
                    onClick = { onStart(kind, "", "", "", "all channels") },
                    enabled = canRun,
                ) {
                    Text(actionLabel, color = if (canRun) actionColor else GrokifyColors.TextDim)
                }
            }
        }
        item {
            Text("Guilds", color = GrokifyColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
        if (guilds.isEmpty()) {
            item { DiscordEmpty("No guilds loaded.") }
        }
        items(guilds, key = { it.guildId }) { g ->
            val open = expandedGuildId == g.guildId
            val bundle = if (open) channelsByGuild.bundleFor(g.guildId, channelKind) else DiscordChannelBundle()
            val chans = bundle.items
            DiscordCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DiscordAvatar(g.icon, g.name, size = 32)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            g.name,
                            color = GrokifyColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        DiscordMeta(
                            if (open) {
                                val chN = bundle.channelCount
                                val thN = bundle.threadCount
                                when {
                                    chN + thN > 0 -> "$chN channels · $thN threads"
                                    busy -> "Loading…"
                                    else -> "No stored channels"
                                }
                            } else {
                                "Expand for channels"
                            },
                        )
                    }
                    TextButton(
                        onClick = { onStart(kind, g.guildId, "", "", g.name) },
                        enabled = canRun,
                    ) {
                        Text(actionLabel, color = if (canRun) actionColor else GrokifyColors.TextDim, fontSize = 12.sp)
                    }
                    IconButton(onClick = { onExpandGuild(g.guildId) }) {
                        Icon(
                            if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = GrokifyColors.TextMuted,
                        )
                    }
                }
                if (open) {
                    Spacer(Modifier.height(6.dp))
                    DiscordChannelKindBar(
                        kind = channelKind,
                        channelCount = bundle.channelCount,
                        threadCount = bundle.threadCount,
                        query = channelQuery,
                        onKind = onChannelKind,
                        onQuery = onChannelQuery,
                        onSubmitQuery = onSubmitChannelQuery,
                    )
                    Spacer(Modifier.height(6.dp))
                    val shown = chans.filter {
                        channelQuery.isBlank() || it.name.contains(channelQuery, ignoreCase = true)
                    }
                    if (shown.isEmpty()) {
                        DiscordMeta(
                            if (busy) {
                                "Loading…"
                            } else if (channelKind == "threads") {
                                if (channelQuery.isBlank()) "No forum posts stored" else "No forum posts matched"
                            } else {
                                "No stored channels"
                            },
                        )
                    }
                    shown.forEach { ch ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                discordChannelLabel(ch, channelKind),
                                color = GrokifyColors.TextPrimary,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(
                                onClick = {
                                    val label = if (channelKind == "threads") ch.name else "#${ch.name}"
                                    onStart(kind, g.guildId, ch.channelId, "", label)
                                },
                                enabled = canRun,
                            ) {
                                Text(actionLabel, color = if (canRun) GrokifyColors.GlowCyan else GrokifyColors.TextDim, fontSize = 12.sp)
                            }
                        }
                    }
                    if (bundle.hasMore) {
                        DiscordMeta("Showing ${chans.size} of ${bundle.total}")
                        TextButton(
                            onClick = onMoreChannels,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("More", color = GrokifyColors.GlowCyan)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun DiscordAiJobCard(
    job: DiscordAiJob,
    analyzing: Boolean,
    speaking: Boolean,
    onCancel: (Int) -> Unit,
    onListen: (Int, String) -> Unit,
) {
    val total = job.total.coerceAtLeast(1)
    val frac = (job.processed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    val isAnalyze = job.kind == "analyze"
    DiscordCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    job.label.ifBlank { job.scope },
                    color = GrokifyColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                DiscordMeta(
                    listOf(
                        if (isAnalyze) "analyze" else "tag",
                        job.status,
                        "${job.processed}/${job.total}",
                        if (!isAnalyze && job.tagged > 0) "${job.tagged} tagged" else "",
                        if (job.failed > 0) "${job.failed} failed" else "",
                        job.model,
                        job.reasoningEffort,
                        if (job.provider == "bridge") "bridge" else if (job.provider == "spacexai") "spacexai" else "",
                        if (job.prompt.isNotBlank()) "prompt" else "",
                        formatDiscordWhen(job.updatedAtMs.takeIf { it > 0L } ?: job.createdAtMs),
                    ).filter { it.isNotBlank() }.joinToString(" · "),
                )
            }
            if (job.summary.isNotBlank()) {
                DiscordAiListenButton(
                    speaking = speaking,
                    onClick = { onListen(job.id, job.summary) },
                )
                DiscordAiCopyButton(job.summary)
            }
            if (discordAiJobRunning(job)) {
                TextButton(onClick = { onCancel(job.id) }) {
                    Icon(Icons.Default.Stop, null, tint = GrokifyColors.GlowRose, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Cancel", color = GrokifyColors.GlowRose)
                }
            }
        }
        if (discordAiJobRunning(job)) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { frac },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp)),
                color = if (isAnalyze) GrokifyColors.GlowCyan else GrokifyColors.GlowMint,
                trackColor = GrokifyColors.PanelSoft,
            )
        }
        if (job.prompt.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            DiscordMeta(
                job.prompt.replace('\n', ' ').trim().take(160),
                color = GrokifyColors.GlowCyan,
            )
        }
        if (job.lastError.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            DiscordMeta(job.lastError, color = GrokifyColors.GlowRose)
        }
        if (discordAiJobRunning(job)) {
            Spacer(Modifier.height(4.dp))
            DiscordMeta(
                if (isAnalyze) "Summarizing on the server…" else "Tagging one message at a time on the server…",
            )
        }
        if (job.summary.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                Text(
                    job.summary,
                    color = GrokifyColors.TextPrimary,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun DiscordAiListenButton(speaking: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(
            if (speaking) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = if (speaking) "Stop listening" else "Listen to summary",
            tint = if (speaking) GrokifyColors.GlowRose else GrokifyColors.GlowMint,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            if (speaking) "Stop" else "Listen",
            color = if (speaking) GrokifyColors.GlowRose else GrokifyColors.GlowMint,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun DiscordAiCopyButton(text: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1400)
            copied = false
        }
    }
    TextButton(
        onClick = {
            clipboard.setText(AnnotatedString(text))
            copied = true
        },
    ) {
        Icon(
            Icons.Default.ContentCopy,
            contentDescription = "Copy summary",
            tint = if (copied) GrokifyColors.GlowMint else GrokifyColors.TextMuted,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            if (copied) "Copied" else "Copy",
            color = if (copied) GrokifyColors.GlowMint else GrokifyColors.TextMuted,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun DiscordAiResultCard(
    row: DiscordAiResult,
    headers: Map<String, String>,
    onOpenUser: (DiscordProfileKey) -> Unit,
) {
    val key = discordProfileKey(id = row.userId, discordId = row.discordId)
    val who = row.displayName.ifBlank { row.username }.ifBlank { "unknown" }
    val roleBit = row.roles.filter { it.isNotBlank() }.take(4).joinToString(", ")
    val title = if (roleBit.isBlank()) who else "$who · $roleBit"
    val open = { if (key.id.isNotBlank()) onOpenUser(key) }
    val isSummary = row.messageId == 0 && row.tags.isEmpty() && row.content.isNotBlank()
    DiscordCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DiscordAvatar(
                row.avatar,
                who,
                headers = headers,
                onClick = open,
            )
            Spacer(Modifier.width(10.dp))
            Column(
                Modifier
                    .weight(1f)
                    .clickable(enabled = key.id.isNotBlank(), onClick = open),
            ) {
                Text(
                    title,
                    color = GrokifyColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                DiscordMeta(
                    listOf(
                        if (isSummary) "summary" else "",
                        row.guildName,
                        row.channelName.takeIf { it.isNotBlank() }?.let { "#$it" }.orEmpty(),
                        formatDiscordWhen(row.createdAtMs),
                        if (row.status != "ok") row.status else "",
                    ).filter { it.isNotBlank() }.joinToString(" · "),
                )
            }
        }
        if (row.content.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                row.content,
                color = GrokifyColors.TextPrimary,
                fontSize = 13.sp,
                maxLines = if (isSummary) 24 else 5,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (row.tags.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                row.tags.take(32).forEach { tag ->
                    Text(
                        tag,
                        color = GrokifyColors.GlowMint,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(GrokifyColors.GlowMint.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        } else if (row.error.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            DiscordMeta(row.error, color = GrokifyColors.GlowRose)
        }
    }
}
