package io.grokify.os.apps.discord

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.grokify.os.ui.theme.GrokifyColors

@Composable
internal fun DiscordProfileSheet(
    profile: DiscordUserProfile?,
    messages: List<DiscordMessage>,
    messageHasMore: Boolean,
    busy: Boolean,
    headers: Map<String, String>,
    onBack: () -> Unit,
    onMoreMessages: () -> Unit,
    onOpenGuild: (String) -> Unit = {},
    extraTags: List<DiscordTagCount> = emptyList(),
    tagsHasMore: Boolean = false,
    onMoreTags: () -> Unit = {},
    onTag: (Int) -> Unit = {},
    onAnalyze: (Int, String) -> Unit = { _, _ -> },
    analyzing: Boolean = false,
) {
    var previewAvatar by remember { mutableStateOf<String?>(null) }
    var showTag by remember { mutableStateOf(false) }
    var showAnalyze by remember { mutableStateOf(false) }
    BackHandler(enabled = previewAvatar != null || showTag || showAnalyze) {
        when {
            previewAvatar != null -> previewAvatar = null
            showTag -> showTag = false
            showAnalyze -> showAnalyze = false
        }
    }
    var tagLimit by remember { mutableStateOf(50) }
    var analyzeLimit by remember { mutableStateOf(50) }
    var analyzePrompt by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxSize()
            .background(GrokifyColors.Void),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = GrokifyColors.TextPrimary,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    profile?.displayName?.ifBlank { profile.username }?.ifBlank { "Profile" } ?: "Profile",
                    color = GrokifyColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = when {
                    profile == null && busy -> "Loading…"
                    profile?.error != null -> profile.error
                    profile != null -> listOfNotNull(
                        profile.username.takeIf { it.isNotBlank() }?.let { "@$it" },
                        profile.discordId.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                    else -> "User"
                }
                Text(
                    sub,
                    color = if (profile?.error != null) GrokifyColors.GlowRose else GrokifyColors.TextDim,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (profile != null && profile.error == null) {
                TextButton(
                    onClick = { showTag = true },
                    enabled = !busy,
                ) {
                    Text("Tag", color = GrokifyColors.GlowMint, fontSize = 12.sp)
                }
                TextButton(
                    onClick = { showAnalyze = true },
                    enabled = !busy,
                ) {
                    Text("Analyze", color = GrokifyColors.GlowCyan, fontSize = 12.sp)
                }
            }
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 8.dp).size(18.dp),
                    strokeWidth = 2.dp,
                    color = GrokifyColors.GlowCyan,
                )
            }
        }
        if (profile == null && !busy) {
            DiscordEmpty("Could not load this profile.")
            return
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (profile != null) {
                item {
                    DiscordProfileHeader(profile, headers) { url ->
                        if (url.isNotBlank()) previewAvatar = url
                    }
                }
                item { DiscordProfileStats(profile) }
                if (profile.guilds.isNotEmpty()) {
                    item {
                        DiscordProfilePlaces("Guilds", profile.guilds) { place ->
                            if (place.id.isNotBlank()) onOpenGuild(place.id)
                        }
                    }
                }
                if (profile.channels.isNotEmpty()) {
                    item {
                        DiscordProfilePlaces("Channels", profile.channels, channel = true) { place ->
                            val gid = place.guildId.ifBlank { place.id }
                            if (gid.isNotBlank()) onOpenGuild(gid)
                        }
                    }
                }
                item {
                    DiscordNameHistory("Username history", profile.username, profile.usernameChanges)
                }
                item {
                    DiscordNameHistory(
                        "Display name history",
                        profile.displayName.ifBlank { profile.username },
                        profile.displayNameChanges,
                    )
                }
                item {
                    DiscordAvatarHistory(profile, headers) { url ->
                        if (url.isNotBlank()) previewAvatar = url
                    }
                }
                if (profile.topTags.isNotEmpty() || extraTags.isNotEmpty()) {
                    item {
                        DiscordTagChart(
                            profile = profile,
                            extraTags = extraTags,
                            hasMore = tagsHasMore || profile.hasMoreTags,
                            busy = busy,
                            onMore = onMoreTags,
                        )
                    }
                }
                item {
                    Text(
                        "Messages",
                        color = GrokifyColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    )
                    if (profile.messageCount > 0) {
                        DiscordMeta("${profile.messageCount} total")
                    }
                }
            }
            if (messages.isEmpty() && !busy) {
                item { DiscordEmpty("No messages cached for this user.") }
            }
            items(messages, key = { it.lazyKey() }) { msg ->
                DiscordMessageCard(msg, headers)
            }
            if (messageHasMore) {
                item {
                    TextButton(onClick = onMoreMessages, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Load more", color = GrokifyColors.GlowCyan)
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
    if (showTag) {
        AlertDialog(
            onDismissRequest = { showTag = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showTag = false
                        onTag(tagLimit)
                    },
                    enabled = true,
                ) {
                    Text("Tag", color = GrokifyColors.GlowMint)
                }
            },
            dismissButton = {
                TextButton(onClick = { showTag = false }) {
                    Text("Cancel", color = GrokifyColors.TextMuted)
                }
            },
            containerColor = GrokifyColors.Panel,
            title = { Text("Tag this profile", color = GrokifyColors.TextPrimary) },
            text = {
                Column {
                    DiscordMeta("Newest messages across every guild/channel · up to 32 semantic tags each. Runs in the background.")
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        discordAiLimits.forEach { n ->
                            DiscordFilterChip("$n", tagLimit == n) { tagLimit = n }
                        }
                    }
                }
            },
        )
    }
    if (showAnalyze) {
        AlertDialog(
            onDismissRequest = { showAnalyze = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAnalyze = false
                        onAnalyze(analyzeLimit, analyzePrompt)
                    },
                    enabled = true,
                ) {
                    Text("Analyze", color = GrokifyColors.GlowCyan)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAnalyze = false }) {
                    Text("Cancel", color = GrokifyColors.TextMuted)
                }
            },
            containerColor = GrokifyColors.Panel,
            title = { Text("Analyze this profile", color = GrokifyColors.TextPrimary) },
            text = {
                Column {
                    DiscordMeta("Newest messages across every guild/channel. Existing tags are included in the summary.")
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        discordAiAnalyzeLimits.forEach { n ->
                            DiscordFilterChip("$n", analyzeLimit == n) { analyzeLimit = n }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        analyzePrompt,
                        { analyzePrompt = it.take(4000) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Optional prompt") },
                        placeholder = { Text("Focus the analysis, or leave blank") },
                        minLines = 2,
                        maxLines = 4,
                        colors = discordFieldColors(),
                        shape = RoundedCornerShape(12.dp),
                    )
                }
            },
        )
    }
    previewAvatar?.let { url ->
        AlertDialog(
            onDismissRequest = { previewAvatar = null },
            confirmButton = {
                TextButton(onClick = { previewAvatar = null }) {
                    Text("Close", color = GrokifyColors.GlowCyan)
                }
            },
            containerColor = GrokifyColors.Panel,
            title = { Text("Avatar", color = GrokifyColors.TextPrimary) },
            text = {
                AsyncImage(
                    model = rememberDiscordImageRequest(url, headers, crossfade = false),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit,
                )
            },
        )
    }
}

@Composable
private fun DiscordProfileHeader(
    profile: DiscordUserProfile,
    headers: Map<String, String>,
    onAvatar: (String) -> Unit,
) {
    DiscordCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DiscordAvatar(
                url = profile.avatar,
                name = profile.displayName.ifBlank { profile.username },
                size = 72,
                headers = headers,
                onClick = { onAvatar(profile.avatar) },
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    profile.displayName.ifBlank { profile.username }.ifBlank { "unknown" },
                    color = GrokifyColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (profile.username.isNotBlank()) {
                    DiscordMeta("@${profile.username}")
                }
                if (profile.discordId.isNotBlank()) {
                    DiscordMeta(profile.discordId)
                }
                if (profile.lastActiveMs > 0L) {
                    DiscordMeta("Active ${formatDiscordWhen(profile.lastActiveMs)}")
                }
            }
        }
    }
}

@Composable
private fun DiscordProfileStats(profile: DiscordUserProfile) {
    DiscordCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DiscordStatCell("Level", "L${profile.level}")
            DiscordStatCell("Messages", profile.messageCount.toString())
            DiscordStatCell("XP", profile.totalXp.toString())
            DiscordStatCell("Activity", profile.activityScore.toInt().toString())
            if (profile.uniqueTagCount > 0) {
                DiscordStatCell("Tags", profile.uniqueTagCount.toString())
            }
        }
        if (profile.xpToNextLevel > 0) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DiscordMeta("XP this level")
                DiscordMeta("${profile.xp} / ${profile.xpToNextLevel}")
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { (profile.levelProgress / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp)),
                color = GrokifyColors.GlowCyan,
                trackColor = GrokifyColors.PanelSoft,
            )
        }
    }
}

@Composable
private fun DiscordStatCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = GrokifyColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        DiscordMeta(label)
    }
}

@Composable
private fun DiscordProfilePlaces(
    title: String,
    places: List<DiscordProfilePlace>,
    channel: Boolean = false,
    onClick: (DiscordProfilePlace) -> Unit,
) {
    DiscordCard {
        Text(title, color = GrokifyColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            places.take(24).forEach { place ->
                val label = if (channel) {
                    listOfNotNull(
                        place.name.takeIf { it.isNotBlank() }?.let { "#$it" },
                        place.guildName.takeIf { it.isNotBlank() },
                    ).joinToString(" · ").ifBlank { place.id }
                } else {
                    place.name.ifBlank { place.id }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(GrokifyColors.PanelSoft)
                        .clickable { onClick(place) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label,
                        color = GrokifyColors.TextPrimary,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (place.messageCount > 0) {
                        DiscordMeta("${place.messageCount}")
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscordNameHistory(title: String, current: String, changes: List<DiscordNameChange>) {
    DiscordCard {
        Text(title, color = GrokifyColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        if (changes.isEmpty()) {
            DiscordMeta(if (current.isNotBlank()) "$current · current" else "No changes")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                changes.take(20).forEach { ch ->
                    Column {
                        Text(
                            listOf(ch.oldValue, ch.newValue).filter { it.isNotBlank() }.joinToString("  →  ")
                                .ifBlank { ch.newValue.ifBlank { ch.oldValue } },
                            color = GrokifyColors.TextPrimary,
                            fontSize = 13.sp,
                        )
                        DiscordMeta(
                            if (ch.changedAtMs > 0L) formatDiscordWhen(ch.changedAtMs) else "—",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscordAvatarHistory(
    profile: DiscordUserProfile,
    headers: Map<String, String>,
    onAvatar: (String) -> Unit,
) {
    val urls = linkedSetOf<String>()
    profile.avatarChanges.forEach {
        if (it.newValue.isNotBlank()) urls.add(it.newValue)
        if (it.oldValue.isNotBlank()) urls.add(it.oldValue)
    }
    if (profile.avatar.isNotBlank()) urls.add(profile.avatar)
    DiscordCard {
        Text("Avatar history", color = GrokifyColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        if (urls.isEmpty()) {
            DiscordMeta("No avatars cached")
        } else {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                urls.take(24).forEach { url ->
                    DiscordAvatar(
                        url = url,
                        name = profile.displayName.ifBlank { profile.username },
                        size = 52,
                        headers = headers,
                        onClick = { onAvatar(url) },
                    )
                }
            }
            if (profile.avatarChanges.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                DiscordMeta("${profile.avatarChanges.size} recorded changes")
            }
        }
    }
}

@Composable
private fun DiscordTagChart(
    profile: DiscordUserProfile,
    extraTags: List<DiscordTagCount> = emptyList(),
    hasMore: Boolean = false,
    busy: Boolean = false,
    onMore: () -> Unit = {},
) {
    val tags = extraTags.ifEmpty { profile.topTags }
    val max = tags.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
    DiscordCard {
        Text(
            "Semantic tags",
            color = GrokifyColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
        )
        DiscordMeta(
            listOfNotNull(
                profile.uniqueTagCount.takeIf { it > 0 }?.let { "$it unique" },
                profile.totalTagCount.takeIf { it > 0 }?.let { "$it tagged" },
                "${tags.size} shown",
            ).joinToString(" · "),
        )
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            tags.forEach { row ->
                val frac = (row.count.toFloat() / max.toFloat()).coerceIn(0.04f, 1f)
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            row.tag,
                            color = GrokifyColors.GlowMint,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        DiscordMeta(row.count.toString())
                    }
                    Spacer(Modifier.height(3.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(GrokifyColors.PanelSoft),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(frac)
                                .height(6.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(GrokifyColors.GlowMint.copy(alpha = 0.75f)),
                        )
                    }
                }
            }
        }
        if (hasMore || extraTags.isNotEmpty() && tags.size < profile.uniqueTagCount) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onMore, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (busy) "Loading tags…" else "View more",
                    color = GrokifyColors.GlowCyan,
                )
            }
        }
    }
}
