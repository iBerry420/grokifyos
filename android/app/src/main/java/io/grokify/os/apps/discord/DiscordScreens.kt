package io.grokify.os.apps.discord

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.grokify.os.ui.chat.MarkdownText
import io.grokify.os.ui.theme.GrokifyColors

private val timeframes = listOf(
    "1h" to "1h",
    "6h" to "6h",
    "1d" to "24h",
    "3d" to "3d",
    "1w" to "7d",
    "1m" to "1m",
    "all" to "all",
)

@Composable
private fun discordSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = GrokifyColors.Void,
    checkedTrackColor = GrokifyColors.GlowMint,
    uncheckedThumbColor = GrokifyColors.TextMuted,
    uncheckedTrackColor = GrokifyColors.PanelSoft,
)

@Composable
internal fun DiscordSearchRow(
    value: String,
    placeholder: String,
    onValue: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, color = GrokifyColors.TextDim) },
        singleLine = true,
        trailingIcon = {
            IconButton(onClick = onSubmit) {
                Icon(Icons.Default.Search, contentDescription = "Search", tint = GrokifyColors.GlowCyan)
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        colors = discordFieldColors(),
        shape = RoundedCornerShape(14.dp),
    )
}

@Composable
internal fun DiscordChannelKindBar(
    kind: String,
    channelCount: Int,
    threadCount: Int,
    query: String,
    onKind: (String) -> Unit,
    onQuery: (String) -> Unit,
    onSubmitQuery: () -> Unit,
) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val channelLabel = if (channelCount > 0) "Channels $channelCount" else "Channels"
        val threadLabel = if (threadCount > 0) "Threads $threadCount" else "Threads"
        DiscordFilterChip(channelLabel, kind == "channels") { onKind("channels") }
        DiscordFilterChip(threadLabel, kind == "threads") { onKind("threads") }
    }
    if (kind == "threads" || channelCount > 40) {
        Spacer(Modifier.height(6.dp))
        DiscordSearchRow(
            query,
            if (kind == "threads") "Search forum posts" else "Filter channels",
            onQuery,
            onSubmitQuery,
        )
    }
}

@Composable
internal fun DiscordGuildFilterRow(
    guilds: List<DiscordGuild>,
    selectedGuildId: String,
    onGuild: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val shown = remember(guilds, q) {
        val filtered = if (q.isBlank()) {
            val watched = guilds.filter { it.isWatched }
            if (watched.isNotEmpty()) watched else guilds
        } else {
            guilds.filter {
                it.name.contains(q, ignoreCase = true) || it.guildId.contains(q)
            }
        }
        filtered.take(30)
    }
    Column {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Filter guilds", color = GrokifyColors.TextDim) },
            singleLine = true,
            colors = discordFieldColors(),
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DiscordFilterChip("All guilds", selectedGuildId.isBlank()) { onGuild("") }
            shown.forEach { g ->
                DiscordFilterChip(g.name.take(18), selectedGuildId == g.guildId) { onGuild(g.guildId) }
            }
        }
    }
}

@Composable
internal fun DiscordFeedTab(
    messages: List<DiscordMessage>,
    guilds: List<DiscordGuild>,
    bots: List<DiscordBot>,
    timeframe: String,
    selectedGuildId: String,
    selectedBotId: Int,
    search: String,
    hasMore: Boolean,
    total: Int,
    busy: Boolean,
    onSearch: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onTimeframe: (String) -> Unit,
    onGuild: (String) -> Unit,
    onBot: (Int) -> Unit,
    onMore: () -> Unit,
    headers: Map<String, String> = emptyMap(),
    onOpenUser: (DiscordProfileKey) -> Unit = {},
    aroundActive: Boolean = false,
    onClearAround: () -> Unit = {},
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            DiscordSearchRow(search, "Search tag, user, channel id, or id", onSearch, onSubmitSearch)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (aroundActive) {
                    DiscordFilterChip("Around post", true) { onClearAround() }
                }
                timeframes.forEach { (id, label) ->
                    DiscordFilterChip(label, timeframe == id) { onTimeframe(id) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DiscordFilterChip("All guilds", selectedGuildId.isBlank()) { onGuild("") }
                guilds.filter { it.isWatched }.take(24).forEach { g ->
                    DiscordFilterChip(g.name.take(18), selectedGuildId == g.guildId) { onGuild(g.guildId) }
                }
            }
            if (bots.size > 1) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DiscordFilterChip("All bots", selectedBotId == 0) { onBot(0) }
                    bots.forEach { b ->
                        DiscordFilterChip(b.name, selectedBotId == b.id) { onBot(b.id) }
                    }
                }
            }
            if (total > 0) {
                Spacer(Modifier.height(6.dp))
                DiscordMeta("$total in range")
            }
        }
        if (messages.isEmpty() && !busy) {
            item { DiscordEmpty("No messages for these filters.") }
        }
        items(messages, key = { it.lazyKey() }) { msg ->
            DiscordMessageCard(msg, headers, onOpenUser)
        }
        if (hasMore) {
            item {
                TextButton(onClick = onMore, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Load more", color = GrokifyColors.GlowCyan)
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
internal fun DiscordMessageCard(
    msg: DiscordMessage,
    headers: Map<String, String> = emptyMap(),
    onOpenUser: (DiscordProfileKey) -> Unit = {},
) {
    val key = discordProfileKey(id = msg.userId, discordId = msg.discordId)
    DiscordCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DiscordAvatar(
                msg.avatar,
                msg.displayName.ifBlank { msg.username },
                headers = headers,
                onClick = { if (key.id.isNotBlank()) onOpenUser(key) },
            )
            Spacer(Modifier.width(10.dp))
            Column(
                Modifier
                    .weight(1f)
                    .clickable(enabled = key.id.isNotBlank()) { onOpenUser(key) },
            ) {
                Text(
                    msg.displayName.ifBlank { msg.username }.ifBlank { "unknown" },
                    color = GrokifyColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                DiscordMeta(
                    listOf(msg.guildName, msg.channelName, formatDiscordWhen(msg.createdAtMs))
                        .filter { it.isNotBlank() && it != "—" }
                        .joinToString(" · "),
                )
            }
            if (msg.level > 0) {
                Text("L${msg.level}", color = GrokifyColors.GlowViolet, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        }
        if (msg.content.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            MarkdownText(
                markdown = discordToMarkdown(msg.content),
                modifier = Modifier.fillMaxWidth(),
                textColor = GrokifyColors.TextPrimary,
            )
        }
        if (msg.attachments.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                msg.attachments.take(6).forEach { att ->
                    DiscordMediaBlock(att, headers)
                }
            }
        }
        if (msg.tags.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                msg.tags.take(8).forEach { tag ->
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
        }
    }
}

@Composable
internal fun DiscordBotsTab(
    bots: List<DiscordBot>,
    busy: Boolean,
    onStart: (Int) -> Unit,
    onStop: (Int) -> Unit,
    onToggleMentions: (DiscordBot, Boolean) -> Unit,
    onToggleReplies: (DiscordBot, Boolean) -> Unit,
    onCreate: (String, String, String, Boolean, Boolean) -> Unit,
    onDelete: (Int) -> Unit,
) {
    var showCreate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<DiscordBot?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Bots & selfbots", color = GrokifyColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showCreate = true }) {
                    Icon(Icons.Default.Add, null, tint = GrokifyColors.GlowCyan, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add", color = GrokifyColors.GlowCyan)
                }
            }
        }
        if (bots.isEmpty() && !busy) {
            item { DiscordEmpty("No bots in Avalynn yet.") }
        }
        items(bots, key = { it.id }) { bot ->
            DiscordCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(bot.name, color = GrokifyColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        DiscordMeta(
                            buildString {
                                append(if (bot.clientType == "selfbot") "selfbot" else "bot")
                                if (bot.agentName.isNotBlank()) append(" · ").append(bot.agentName)
                                append(" · last ").append(formatDiscordWhen(bot.lastOnlineMs))
                            },
                        )
                    }
                    DiscordStatusDot(bot.isRunning, bot.isActive)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (bot.isRunning) {
                        TextButton(onClick = { onStop(bot.id) }, enabled = !busy) {
                            Icon(Icons.Default.Stop, null, tint = GrokifyColors.GlowRose, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Stop", color = GrokifyColors.GlowRose)
                        }
                    } else {
                        TextButton(onClick = { onStart(bot.id) }, enabled = !busy) {
                            Icon(Icons.Default.PlayArrow, null, tint = GrokifyColors.GlowMint, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Start", color = GrokifyColors.GlowMint)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { confirmDelete = bot }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = GrokifyColors.TextDim)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Mentions", color = GrokifyColors.TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Switch(bot.respondMentions, { onToggleMentions(bot, it) }, colors = discordSwitchColors())
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Replies", color = GrokifyColors.TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Switch(bot.respondReplies, { onToggleReplies(bot, it) }, colors = discordSwitchColors())
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    if (showCreate) {
        DiscordCreateBotDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, token, type, mentions, replies ->
                showCreate = false
                onCreate(name, token, type, mentions, replies)
            },
        )
    }
    confirmDelete?.let { bot ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${bot.name}?") },
            text = { Text("Removes the bot from Avalynn. Tokens stay on the server until deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    val id = bot.id
                    confirmDelete = null
                    onDelete(id)
                }) { Text("Delete", color = GrokifyColors.GlowRose) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel", color = GrokifyColors.TextMuted) }
            },
            containerColor = GrokifyColors.Panel,
        )
    }
}

@Composable
private fun DiscordCreateBotDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, String, Boolean, Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var selfbot by remember { mutableStateOf(false) }
    var mentions by remember { mutableStateOf(false) }
    var replies by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add bot", color = GrokifyColors.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, colors = discordFieldColors())
                OutlinedTextField(
                    token,
                    { token = it },
                    label = { Text("Token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = discordFieldColors(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Selfbot", color = GrokifyColors.TextMuted, modifier = Modifier.weight(1f))
                    Switch(selfbot, { selfbot = it }, colors = discordSwitchColors())
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Respond to mentions", color = GrokifyColors.TextMuted, modifier = Modifier.weight(1f))
                    Switch(mentions, { mentions = it }, colors = discordSwitchColors())
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Respond to replies", color = GrokifyColors.TextMuted, modifier = Modifier.weight(1f))
                    Switch(replies, { replies = it }, colors = discordSwitchColors())
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCreate(name.trim(), token.trim(), if (selfbot) "selfbot" else "discord.js", mentions, replies)
                },
                enabled = name.isNotBlank() && token.isNotBlank(),
            ) { Text("Create", color = GrokifyColors.GlowCyan) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = GrokifyColors.TextMuted) }
        },
        containerColor = GrokifyColors.Panel,
    )
}

@Composable
internal fun DiscordGuildsTab(
    guilds: List<DiscordGuild>,
    bots: List<DiscordBot>,
    selectedBotIds: Set<Int>,
    channelsByGuild: Map<String, DiscordChannelBundle>,
    expandedId: String?,
    busy: Boolean,
    search: String,
    filter: String,
    sort: String,
    channelKind: String,
    channelQuery: String,
    onToggleBot: (Int) -> Unit,
    onSearch: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onFilter: (String) -> Unit,
    onSort: (String) -> Unit,
    onExpand: (String) -> Unit,
    onChannelKind: (String) -> Unit,
    onChannelQuery: (String) -> Unit,
    onSubmitChannelQuery: () -> Unit,
    onMoreChannels: () -> Unit,
    onWatch: (DiscordGuild, Boolean) -> Unit,
    onMentions: (DiscordGuild, Boolean) -> Unit,
    onAutoTag: (DiscordGuild, Boolean) -> Unit,
    onOpenFeed: (String) -> Unit,
    onChannelWatch: (DiscordGuild, DiscordChannel, Boolean) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            if (bots.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    bots.forEach { b ->
                        DiscordFilterChip(b.name, b.id in selectedBotIds) { onToggleBot(b.id) }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            DiscordSearchRow(search, "Filter guilds", onSearch, onSubmitSearch)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DiscordFilterChip("Watched", filter == "watched") { onFilter("watched") }
                DiscordFilterChip("All", filter == "all") { onFilter("all") }
                DiscordFilterChip("Off", filter == "off") { onFilter("off") }
                DiscordFilterChip("Name", sort == "name") { onSort("name") }
                DiscordFilterChip("Watched first", sort == "watched") { onSort("watched") }
            }
        }
        if (bots.isNotEmpty() && selectedBotIds.isEmpty() && !busy) {
            item { DiscordEmpty("Select a bot to list guilds.") }
        } else if (guilds.isEmpty() && !busy) {
            item { DiscordEmpty("No guilds matched.") }
        }
        items(guilds, key = { it.guildId }) { g ->
            val open = expandedId == g.guildId
            val bundle = if (open) channelsByGuild.bundleFor(g.guildId, channelKind) else DiscordChannelBundle()
            val chans = bundle.items
            DiscordCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DiscordAvatar(g.icon, g.name, size = 32)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f).clickable { onOpenFeed(g.guildId) }) {
                        Text(g.name, color = GrokifyColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        DiscordMeta(
                            buildString {
                                append(if (g.isWatched) "watched" else "off")
                                if (g.bots.isNotEmpty()) {
                                    append(" · ")
                                    append(g.bots.count { it.isWatched })
                                    append("/")
                                    append(g.bots.size)
                                    append(" bots")
                                }
                            },
                        )
                    }
                    Switch(g.isWatched, { onWatch(g, it) }, colors = discordSwitchColors())
                    IconButton(onClick = { onExpand(g.guildId) }) {
                        Icon(
                            if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = GrokifyColors.TextMuted,
                        )
                    }
                }
                if (open) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Mentions", color = GrokifyColors.TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Switch(g.respondToMentions, { onMentions(g, it) }, colors = discordSwitchColors())
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Auto tagging", color = GrokifyColors.TextMuted, fontSize = 12.sp)
                            DiscordMeta("Tag each captured message")
                        }
                        Switch(g.semanticTagging, { onAutoTag(g, it) }, colors = discordSwitchColors())
                    }
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
                    shown.forEach { ch ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                discordChannelLabel(ch, channelKind),
                                color = GrokifyColors.TextPrimary,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                ch.watchedFor(selectedBotIds),
                                { onChannelWatch(g, ch, it) },
                                colors = discordSwitchColors(),
                            )
                        }
                    }
                    when {
                        chans.isEmpty() -> DiscordMeta(
                            if (busy) {
                                "Loading…"
                            } else if (channelKind == "threads") {
                                if (channelQuery.isBlank()) "No forum posts stored" else "No forum posts matched"
                            } else {
                                "No stored channels"
                            },
                        )
                        bundle.hasMore -> {
                            DiscordMeta("Showing ${chans.size} of ${bundle.total}")
                            TextButton(onClick = onMoreChannels, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Text("More", color = GrokifyColors.GlowCyan)
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
internal fun DiscordUsersTab(
    users: List<DiscordUserRow>,
    total: Int,
    search: String,
    sort: String,
    order: String,
    hasMore: Boolean,
    busy: Boolean,
    headers: Map<String, String>,
    guilds: List<DiscordGuild> = emptyList(),
    selectedGuildId: String = "",
    onSearch: (String) -> Unit,
    onSubmit: () -> Unit,
    onSort: (String) -> Unit,
    onOrder: (String) -> Unit,
    onGuild: (String) -> Unit = {},
    onMore: () -> Unit,
    onOpenUser: (DiscordProfileKey) -> Unit = {},
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            DiscordSearchRow(search, "Search username or Discord id", onSearch, onSubmit)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DiscordFilterChip("Active", sort == "lastActive") { onSort("lastActive") }
                DiscordFilterChip("Level", sort == "level") { onSort("level") }
                DiscordFilterChip("Name", sort == "username") { onSort("username") }
                DiscordFilterChip("XP", sort == "totalXp") { onSort("totalXp") }
                DiscordFilterChip(if (order == "asc") "Asc" else "Desc", true) {
                    onOrder(if (order == "asc") "desc" else "asc")
                }
            }
            Spacer(Modifier.height(8.dp))
            DiscordGuildFilterRow(guilds = guilds, selectedGuildId = selectedGuildId, onGuild = onGuild)
            if (total > 0) {
                Spacer(Modifier.height(6.dp))
                DiscordMeta("$total users")
            }
        }
        if (users.isEmpty() && !busy) {
            item {
                DiscordEmpty(
                    if (selectedGuildId.isNotBlank()) "No users in this guild." else "No users matched.",
                )
            }
        }
        items(users, key = { u -> u.lazyKey() }) { u ->
            val key = discordProfileKey(id = u.id, discordId = u.discordId)
            DiscordCard(onClick = { if (key.id.isNotBlank()) onOpenUser(key) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DiscordAvatar(
                        u.avatar,
                        u.displayName.ifBlank { u.username },
                        headers = headers,
                        onClick = { if (key.id.isNotBlank()) onOpenUser(key) },
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            u.displayName.ifBlank { u.username },
                            color = GrokifyColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        DiscordMeta("@${u.username} · ${u.discordId}")
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("L${u.level}", color = GrokifyColors.GlowViolet, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        DiscordMeta("${u.messageCount} msgs")
                    }
                }
            }
        }
        if (hasMore) {
            item {
                TextButton(onClick = onMore, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Load more", color = GrokifyColors.GlowCyan)
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
internal fun DiscordMediaTab(
    items: List<DiscordAttachment>,
    total: Int,
    kind: String,
    order: String,
    likedOnly: Boolean,
    hideStale: Boolean,
    guilds: List<DiscordGuild>,
    selectedGuildId: String,
    hasMore: Boolean,
    busy: Boolean,
    headers: Map<String, String>,
    onKind: (String) -> Unit,
    onOrder: (String) -> Unit,
    onLikedOnly: (Boolean) -> Unit,
    onHideStale: (Boolean) -> Unit,
    onDiscogram: () -> Unit,
    onGuild: (String) -> Unit,
    onMore: () -> Unit,
) {
    val openMedia = LocalDiscordOpenMedia.current
    val newest = order != "asc" && !order.equals("oldest", true)
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("image" to "Images", "gif" to "GIFs", "video" to "Video", "audio" to "Audio", "" to "All").forEach { (id, label) ->
                    DiscordFilterChip(label, kind == id) { onKind(id) }
                }
                if (total > 0) {
                    DiscordMeta("$total")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DiscordFilterChip("Newest", newest) { onOrder("desc") }
                DiscordFilterChip("Oldest", !newest) { onOrder("asc") }
                DiscordFilterChip("Liked", likedOnly) { onLikedOnly(!likedOnly) }
                DiscordFilterChip("Hide stale", hideStale) { onHideStale(!hideStale) }
                DiscordFilterChip("Discogram", false) { onDiscogram() }
            }
            Spacer(Modifier.height(8.dp))
            DiscordGuildFilterRow(guilds = guilds, selectedGuildId = selectedGuildId, onGuild = onGuild)
        }
        if (items.isEmpty() && !busy) {
            DiscordEmpty(
                if (hideStale) "No playable media for this guild and type."
                else "No media for this guild and type.",
            )
            return
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items, key = { it.lazyKey() }) { att ->
                Column(Modifier.clickable { openMedia?.invoke(att) }) {
                    DiscordMediaThumb(att, headers)
                    DiscordMeta(att.guildName.ifBlank { att.filename })
                }
            }
            if (hasMore) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    TextButton(onClick = onMore, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Load more", color = GrokifyColors.GlowCyan)
                    }
                }
            }
        }
    }
}

@Composable
internal fun DiscordRolesTab(
    pickers: List<DiscordRolePicker>,
    bots: List<DiscordBot>,
    guilds: List<DiscordGuild>,
    channels: List<DiscordChannel>,
    selectedBotId: Int,
    selectedGuildId: String,
    busy: Boolean,
    onBot: (Int) -> Unit,
    onGuild: (String) -> Unit,
    onDeploy: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onCreate: (Int, String, String, String, String, List<Pair<String, String>>, Boolean) -> Unit,
) {
    var showCreate by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Role pickers", color = GrokifyColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showCreate = true }) {
                    Text("New", color = GrokifyColors.GlowCyan)
                }
            }
        }
        if (pickers.isEmpty() && !busy) {
            item { DiscordEmpty("No role pickers yet.") }
        }
        items(pickers, key = { it.id }) { p ->
            DiscordCard {
                Text(p.title.ifBlank { "Role picker #${p.id}" }, color = GrokifyColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                DiscordMeta(
                    buildString {
                        append(if (p.deployed) "deployed" else "draft")
                        append(" · guild ").append(p.guildId.takeLast(6))
                    },
                )
                if (p.description.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(p.description, color = GrokifyColors.TextMuted, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Row {
                    if (!p.deployed) {
                        TextButton(onClick = { onDeploy(p.id) }, enabled = !busy) {
                            Text("Deploy", color = GrokifyColors.GlowMint)
                        }
                    }
                    TextButton(onClick = { onDelete(p.id) }, enabled = !busy) {
                        Text("Delete", color = GrokifyColors.GlowRose)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
    if (showCreate) {
        DiscordCreatePickerDialog(
            bots = bots,
            guilds = guilds,
            channels = channels,
            selectedBotId = selectedBotId,
            selectedGuildId = selectedGuildId,
            onBot = onBot,
            onGuild = onGuild,
            onDismiss = { showCreate = false },
            onCreate = { botId, gid, cid, title, desc, roles, deploy ->
                showCreate = false
                onCreate(botId, gid, cid, title, desc, roles, deploy)
            },
        )
    }
}

@Composable
private fun DiscordCreatePickerDialog(
    bots: List<DiscordBot>,
    guilds: List<DiscordGuild>,
    channels: List<DiscordChannel>,
    selectedBotId: Int,
    selectedGuildId: String,
    onBot: (Int) -> Unit,
    onGuild: (String) -> Unit,
    onDismiss: () -> Unit,
    onCreate: (Int, String, String, String, String, List<Pair<String, String>>, Boolean) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var channelId by remember { mutableStateOf(channels.firstOrNull()?.channelId.orEmpty()) }
    var rolesText by remember { mutableStateOf("") }
    var deploy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New role picker", color = GrokifyColors.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    bots.forEach { b ->
                        DiscordFilterChip(b.name, selectedBotId == b.id) { onBot(b.id) }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    guilds.take(12).forEach { g ->
                        DiscordFilterChip(g.name.take(16), selectedGuildId == g.guildId) { onGuild(g.guildId) }
                    }
                }
                if (channels.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        channels.take(16).forEach { ch ->
                            DiscordFilterChip("#${ch.name.take(14)}", channelId == ch.channelId) { channelId = ch.channelId }
                        }
                    }
                }
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, colors = discordFieldColors())
                OutlinedTextField(desc, { desc = it }, label = { Text("Description") }, colors = discordFieldColors())
                OutlinedTextField(
                    rolesText,
                    { rolesText = it },
                    label = { Text("Roles: emoji roleId per line") },
                    colors = discordFieldColors(),
                    minLines = 3,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Deploy now", color = GrokifyColors.TextMuted, modifier = Modifier.weight(1f))
                    Switch(deploy, { deploy = it }, colors = discordSwitchColors())
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val roles = rolesText.lines().mapNotNull { line ->
                        val parts = line.trim().split(Regex("\\s+"))
                        if (parts.size < 2) null else parts[0] to parts[1]
                    }
                    onCreate(selectedBotId, selectedGuildId, channelId, title, desc, roles, deploy)
                },
                enabled = selectedBotId > 0 && selectedGuildId.isNotBlank() && channelId.isNotBlank() && rolesText.isNotBlank(),
            ) { Text("Create", color = GrokifyColors.GlowCyan) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = GrokifyColors.TextMuted) }
        },
        containerColor = GrokifyColors.Panel,
    )
}

@Composable
internal fun DiscordCaptchasTab(
    captchas: List<DiscordCaptcha>,
    bots: List<DiscordBot>,
    guilds: List<DiscordGuild>,
    channels: List<DiscordChannel>,
    selectedBotId: Int,
    selectedGuildId: String,
    busy: Boolean,
    onBot: (Int) -> Unit,
    onGuild: (String) -> Unit,
    onDeploy: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onCreate: (Int, String, String, String, String, String, Boolean) -> Unit,
) {
    var showCreate by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Captchas", color = GrokifyColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showCreate = true }) {
                    Text("New", color = GrokifyColors.GlowCyan)
                }
            }
        }
        if (captchas.isEmpty() && !busy) {
            item { DiscordEmpty("No captcha configs.") }
        }
        items(captchas, key = { it.id }) { c ->
            DiscordCard {
                Text(c.title.ifBlank { "Captcha #${c.id}" }, color = GrokifyColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                DiscordMeta(
                    buildString {
                        append(if (c.deployed) "deployed" else "draft")
                        if (c.postRoleName.isNotBlank()) append(" · pass ").append(c.postRoleName)
                        if (c.preRoleName.isNotBlank()) append(" · join ").append(c.preRoleName)
                    },
                )
                Row {
                    if (!c.deployed) {
                        TextButton(onClick = { onDeploy(c.id) }, enabled = !busy) {
                            Text("Deploy", color = GrokifyColors.GlowMint)
                        }
                    }
                    TextButton(onClick = { onDelete(c.id) }, enabled = !busy) {
                        Text("Delete", color = GrokifyColors.GlowRose)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
    if (showCreate) {
        var title by remember { mutableStateOf("Verify") }
        var desc by remember { mutableStateOf("") }
        var postRole by remember { mutableStateOf("") }
        var channelId by remember { mutableStateOf(channels.firstOrNull()?.channelId.orEmpty()) }
        var deploy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("New captcha", color = GrokifyColors.TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        bots.forEach { b -> DiscordFilterChip(b.name, selectedBotId == b.id) { onBot(b.id) } }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        guilds.take(12).forEach { g ->
                            DiscordFilterChip(g.name.take(16), selectedGuildId == g.guildId) { onGuild(g.guildId) }
                        }
                    }
                    if (channels.isNotEmpty()) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            channels.take(16).forEach { ch ->
                                DiscordFilterChip("#${ch.name.take(14)}", channelId == ch.channelId) { channelId = ch.channelId }
                            }
                        }
                    }
                    OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, colors = discordFieldColors())
                    OutlinedTextField(desc, { desc = it }, label = { Text("Description") }, colors = discordFieldColors())
                    OutlinedTextField(
                        postRole,
                        { postRole = it },
                        label = { Text("Pass role id") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = discordFieldColors(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Deploy now", color = GrokifyColors.TextMuted, modifier = Modifier.weight(1f))
                        Switch(deploy, { deploy = it }, colors = discordSwitchColors())
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCreate = false
                        onCreate(selectedBotId, selectedGuildId, channelId, postRole.trim(), title, desc, deploy)
                    },
                    enabled = selectedBotId > 0 && selectedGuildId.isNotBlank() && channelId.isNotBlank() && postRole.isNotBlank(),
                ) { Text("Create", color = GrokifyColors.GlowCyan) }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text("Cancel", color = GrokifyColors.TextMuted) }
            },
            containerColor = GrokifyColors.Panel,
        )
    }
}

@Composable
internal fun DiscordEmojisTab(
    files: List<DiscordEmojiFile>,
    total: Int,
    guildEmojis: List<DiscordGuildEmoji>,
    guilds: List<DiscordGuild>,
    bots: List<DiscordBot>,
    selectedGuildId: String,
    selectedBotId: Int,
    search: String,
    busy: Boolean,
    emojiUrl: (String) -> String,
    headers: Map<String, String>,
    onSearch: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onGuild: (String) -> Unit,
    onBot: (Int) -> Unit,
    onAdd: (String) -> Unit,
    onDeleteGuild: (String) -> Unit,
) {
    val ctx = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            DiscordSearchRow(search, "Search captured emoji", onSearch, onSubmitSearch)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                bots.forEach { b -> DiscordFilterChip(b.name, selectedBotId == b.id) { onBot(b.id) } }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                guilds.filter { it.isWatched }.take(20).forEach { g ->
                    DiscordFilterChip(g.name.take(16), selectedGuildId == g.guildId) { onGuild(g.guildId) }
                }
            }
            DiscordMeta("$total captured · tap to add to selected guild")
        }
        if (guildEmojis.isNotEmpty()) {
            item {
                Text("On guild", color = GrokifyColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            items(guildEmojis, key = { it.id }) { e ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(GrokifyColors.Panel)
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(model = e.url, contentDescription = e.name, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(":${e.name}:", color = GrokifyColors.TextPrimary, modifier = Modifier.weight(1f), maxLines = 1)
                    TextButton(onClick = { onDeleteGuild(e.id) }, enabled = !busy) {
                        Text("Remove", color = GrokifyColors.GlowRose, fontSize = 12.sp)
                    }
                }
            }
        }
        item {
            Text("Library", color = GrokifyColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
        if (files.isEmpty() && !busy) {
            item { DiscordEmpty("No captured emoji matched.") }
        }
        items(files, key = { it.filename }) { f ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(GrokifyColors.Panel)
                    .clickable(enabled = !busy) { onAdd(f.filename) }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val req = ImageRequest.Builder(ctx).data(emojiUrl(f.filename)).apply {
                    headers.forEach { (k, v) -> addHeader(k, v) }
                }.build()
                AsyncImage(model = req, contentDescription = f.name, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.name, color = GrokifyColors.TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    DiscordMeta(f.filename)
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
internal fun DiscordAuditsTab(
    events: List<DiscordAudit>,
    guilds: List<DiscordGuild>,
    selectedGuildId: String,
    action: String,
    timeframe: String,
    hasMore: Boolean,
    busy: Boolean,
    headers: Map<String, String> = emptyMap(),
    onGuild: (String) -> Unit,
    onAction: (String) -> Unit,
    onTimeframe: (String) -> Unit,
    onMore: () -> Unit,
    onOpenUser: (DiscordProfileKey) -> Unit = {},
) {
    val actions = listOf(
        "" to "All",
        "message_delete" to "Deletes",
        "message_edit" to "Edits",
        "avatar_change" to "Avatars",
        "role_assign" to "Roles+",
        "role_remove" to "Roles−",
        "nickname_change" to "Nicks",
        "displayname_change" to "Names",
        "username_change" to "Users",
    )
    val tfs = listOf("1h" to "1h", "1d" to "24h", "7d" to "7d", "30d" to "30d", "all" to "all")
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tfs.forEach { (id, label) -> DiscordFilterChip(label, timeframe == id) { onTimeframe(id) } }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                actions.forEach { (id, label) -> DiscordFilterChip(label, action == id) { onAction(id) } }
            }
            Spacer(Modifier.height(8.dp))
            DiscordGuildFilterRow(guilds = guilds, selectedGuildId = selectedGuildId, onGuild = onGuild)
        }
        if (events.isEmpty() && !busy) {
            item { DiscordEmpty("No audit events in range.") }
        }
        items(events, key = { "aud-${it.id}-${it.createdAtMs}" }) { ev ->
            DiscordAuditCard(ev, headers, onOpenUser)
        }
        if (hasMore) {
            item {
                TextButton(onClick = onMore, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Load more", color = GrokifyColors.GlowCyan)
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun DiscordAuditCard(
    ev: DiscordAudit,
    headers: Map<String, String>,
    onOpenUser: (DiscordProfileKey) -> Unit = {},
) {
    val who = ev.target.ifBlank { ev.actor }.ifBlank { "unknown" }
    val whoAvatar = ev.targetAvatar.ifBlank { ev.actorAvatar }
    val key = discordProfileKey(
        discordId = ev.targetId.ifBlank { ev.actorId },
        targetType = ev.targetType,
    )
    DiscordCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DiscordAvatar(
                whoAvatar,
                who,
                headers = headers,
                onClick = { if (key.id.isNotBlank()) onOpenUser(key) },
            )
            Spacer(Modifier.width(10.dp))
            Column(
                Modifier
                    .weight(1f)
                    .clickable(enabled = key.id.isNotBlank()) { onOpenUser(key) },
            ) {
                Text(
                    discordAuditLabel(ev.action),
                    color = GrokifyColors.GlowAmber,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                DiscordMeta(
                    listOf(
                        who,
                        ev.guildName,
                        ev.channelName.takeIf { it.isNotBlank() }?.let { "#$it" }.orEmpty(),
                        formatDiscordWhen(ev.createdAtMs),
                    ).filter { it.isNotBlank() && it != "—" }.joinToString(" · "),
                )
            }
        }
        if (ev.action == "avatar_change" && (ev.beforeAvatar.isNotBlank() || ev.afterAvatar.isNotBlank())) {
            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DiscordAuditAvatarSide("Before", ev.beforeAvatar, headers)
                Text("→", color = GrokifyColors.TextDim, fontSize = 16.sp)
                DiscordAuditAvatarSide("After", ev.afterAvatar, headers)
            }
        }
        when (ev.action) {
            "message_edit" -> {
                Spacer(Modifier.height(8.dp))
                DiscordAuditTextBlock(
                    "Before",
                    discordAuditMessageText(ev.beforeText, ev.beforeAttachments),
                    headers,
                    ev.beforeAttachments.map { it.withAuditContext(ev) },
                )
                Spacer(Modifier.height(6.dp))
                DiscordAuditTextBlock(
                    "After",
                    discordAuditMessageText(ev.afterText, ev.afterAttachments),
                    headers,
                    ev.afterAttachments.map { it.withAuditContext(ev) },
                )
            }
            "message_delete" -> {
                Spacer(Modifier.height(8.dp))
                DiscordAuditTextBlock(
                    label = null,
                    text = discordAuditMessageText(ev.beforeText, ev.beforeAttachments),
                    headers = headers,
                    attachments = ev.beforeAttachments.map { it.withAuditContext(ev) },
                )
            }
            "avatar_change" -> Unit
            else -> {
                if (ev.beforeText.isNotBlank() || ev.afterText.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    val left = ev.beforeText.ifBlank { "—" }
                    val right = ev.afterText.ifBlank { "—" }
                    Text(
                        "$left  →  $right",
                        color = GrokifyColors.TextPrimary,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscordAuditAvatarSide(label: String, url: String, headers: Map<String, String>) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        DiscordMeta(label)
        Spacer(Modifier.height(4.dp))
        DiscordAvatar(url, label, size = 56, headers = headers)
    }
}

@Composable
private fun DiscordAuditTextBlock(
    label: String?,
    text: String,
    headers: Map<String, String>,
    attachments: List<DiscordAttachment>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!label.isNullOrBlank()) {
            Text(label, color = GrokifyColors.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
        if (text.isNotBlank()) {
            MarkdownText(
                markdown = discordToMarkdown(text),
                modifier = Modifier.fillMaxWidth(),
                textColor = GrokifyColors.TextPrimary,
            )
        }
        attachments.take(8).forEach { att ->
            DiscordMediaBlock(
                att,
                headers,
                large = att.kind == "image" || att.kind == "gif" || att.kind == "video",
            )
        }
    }
}
