package io.grokify.os.apps.discord

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.grokify.os.ui.theme.GrokifyColors
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun DiscordDiscogramHost(
    feed: List<DiscordAttachment>,
    feedHasMore: Boolean,
    feedStartIndex: Int = 0,
    profile: DiscordAttachment?,
    profileItems: List<DiscordAttachment>,
    profileHasMore: Boolean,
    profilePagerStart: DiscordAttachment?,
    busy: Boolean,
    analyzing: Boolean,
    emptyMessage: String = "",
    headers: Map<String, String>,
    onClose: () -> Unit,
    onCloseProfile: () -> Unit,
    onCloseProfilePager: () -> Unit,
    onMoreFeed: () -> Unit,
    onMoreProfile: () -> Unit,
    onOpenCreator: (DiscordAttachment) -> Unit,
    onOpenProfileItem: (DiscordAttachment) -> Unit,
    onLike: (DiscordAttachment) -> Unit,
    onFollow: (DiscordAttachment) -> Unit,
    onDownload: (DiscordAttachment) -> Unit,
    onTag: (DiscordAttachment) -> Unit,
    onAnalyze: (DiscordAttachment, String) -> Unit,
    onOpenGuild: (DiscordAttachment) -> Unit = {},
    onOpenChannel: (DiscordAttachment) -> Unit = {},
    onFeedCursor: (Int, DiscordAttachment?) -> Unit = { _, _ -> },
) {
    when {
        profile != null && profilePagerStart != null -> {
            DiscordDiscogramPager(
                items = profileItems,
                startId = profilePagerStart.id,
                startIndex = profileItems.indexOfFirst { it.id == profilePagerStart.id }.coerceAtLeast(0),
                hasMore = profileHasMore,
                busy = busy,
                analyzing = analyzing,
                headers = headers,
                onBack = onCloseProfilePager,
                onMore = onMoreProfile,
                onOpenCreator = {},
                creatorLocked = true,
                onLike = onLike,
                onFollow = onFollow,
                onDownload = onDownload,
                onTag = onTag,
                onAnalyze = onAnalyze,
                onOpenGuild = onOpenGuild,
                onOpenChannel = onOpenChannel,
            )
        }
        profile != null -> {
            DiscordDiscogramProfile(
                seed = profile,
                items = profileItems,
                hasMore = profileHasMore,
                busy = busy,
                headers = headers,
                onBack = onCloseProfile,
                onMore = onMoreProfile,
                onOpenItem = onOpenProfileItem,
                onFollow = onFollow,
            )
        }
        else -> {
            DiscordDiscogramPager(
                items = feed,
                startId = feed.getOrNull(feedStartIndex)?.id ?: feed.firstOrNull()?.id ?: 0,
                startIndex = feedStartIndex,
                hasMore = feedHasMore,
                busy = busy,
                analyzing = analyzing,
                emptyMessage = emptyMessage,
                headers = headers,
                onBack = onClose,
                onMore = onMoreFeed,
                onOpenCreator = onOpenCreator,
                creatorLocked = false,
                onLike = onLike,
                onFollow = onFollow,
                onDownload = onDownload,
                onTag = onTag,
                onAnalyze = onAnalyze,
                onOpenGuild = onOpenGuild,
                onOpenChannel = onOpenChannel,
                onCursor = onFeedCursor,
            )
        }
    }
}

@Composable
private fun DiscordDiscogramProfile(
    seed: DiscordAttachment,
    items: List<DiscordAttachment>,
    hasMore: Boolean,
    busy: Boolean,
    headers: Map<String, String>,
    onBack: () -> Unit,
    onMore: () -> Unit,
    onOpenItem: (DiscordAttachment) -> Unit,
    onFollow: (DiscordAttachment) -> Unit,
) {
    val name = seed.displayName.ifBlank { seed.username }.ifBlank { "creator" }
    val following = items.firstOrNull { it.discordId == seed.discordId }?.following ?: seed.following
    BackHandler(onBack = onBack)
    Column(
        Modifier
            .fillMaxSize()
            .background(GrokifyColors.Void)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = GrokifyColors.TextPrimary)
            }
            DiscordAvatar(seed.avatar, name, size = 36, headers = headers)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = GrokifyColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val handle = seed.username.takeIf { it.isNotBlank() }?.let { "@$it" } ?: ""
                if (handle.isNotBlank()) DiscordMeta(handle)
            }
            if (seed.discordId.isNotBlank()) {
                TextButton(onClick = { onFollow(seed.copy(following = following)) }) {
                    Text(if (following) "Following" else "Follow", color = GrokifyColors.GlowCyan)
                }
            }
        }
        if (items.isEmpty() && !busy) {
            DiscordEmpty("No media from this creator.")
            return
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(items, key = { it.lazyKey() }) { att ->
                Box(Modifier.clickable { onOpenItem(att) }) {
                    DiscordMediaThumb(att, headers)
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
private fun DiscordDiscogramPager(
    items: List<DiscordAttachment>,
    startId: Int,
    startIndex: Int = 0,
    hasMore: Boolean,
    busy: Boolean,
    analyzing: Boolean,
    emptyMessage: String = "",
    headers: Map<String, String>,
    onBack: () -> Unit,
    onMore: () -> Unit,
    onOpenCreator: (DiscordAttachment) -> Unit,
    creatorLocked: Boolean,
    onLike: (DiscordAttachment) -> Unit,
    onFollow: (DiscordAttachment) -> Unit,
    onDownload: (DiscordAttachment) -> Unit,
    onTag: (DiscordAttachment) -> Unit,
    onAnalyze: (DiscordAttachment, String) -> Unit,
    onOpenGuild: (DiscordAttachment) -> Unit = {},
    onOpenChannel: (DiscordAttachment) -> Unit = {},
    onCursor: (Int, DiscordAttachment?) -> Unit = { _, _ -> },
) {
    BackHandler(onBack = onBack)
    val initial = remember(startId, startIndex, items.firstOrNull()?.id) {
        val fromId = if (startId > 0) items.indexOfFirst { it.id == startId } else -1
        when {
            fromId >= 0 -> fromId
            items.isNotEmpty() -> startIndex.coerceIn(0, items.lastIndex)
            else -> 0
        }
    }
    val pagerState = rememberPagerState(initialPage = initial, pageCount = { items.size.coerceAtLeast(1) })
    var analyzeFor by remember { mutableStateOf<DiscordAttachment?>(null) }
    var didSeek by remember(startId, initial) { mutableStateOf(false) }
    LaunchedEffect(items.size, initial) {
        if (didSeek || items.isEmpty()) return@LaunchedEffect
        val idx = initial.coerceIn(0, items.lastIndex)
        if (idx != pagerState.currentPage) {
            pagerState.scrollToPage(idx)
        }
        didSeek = true
    }
    LaunchedEffect(pagerState, items.size, hasMore, busy) {
        var lastSent = -1
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page ->
                if (lastSent >= 0) {
                    onCursor(page, items.getOrNull(page))
                }
                lastSent = page
                if (hasMore && !busy && items.isNotEmpty() && page >= items.size - 5) {
                    onMore()
                }
            }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(28.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.dp,
                            color = GrokifyColors.GlowCyan,
                        )
                        Spacer(Modifier.height(14.dp))
                        Text("Loading mix…", color = Color.White.copy(alpha = 0.88f), fontSize = 14.sp)
                    } else {
                        Text(
                            emptyMessage.ifBlank { "No media yet." },
                            color = Color.White.copy(alpha = 0.78f),
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        } else {
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                key = { page -> items.getOrNull(page)?.lazyKey() ?: "empty-$page" },
            ) { page ->
                val att = items[page]
                DiscordDiscogramPage(
                    att = att,
                    active = pagerState.currentPage == page,
                    headers = headers,
                    analyzing = analyzing,
                    creatorLocked = creatorLocked,
                    onOpenCreator = { onOpenCreator(att) },
                    onLike = { onLike(att) },
                    onFollow = { onFollow(att) },
                    onDownload = { onDownload(att) },
                    onTag = { onTag(att) },
                    onAnalyze = { analyzeFor = att },
                    onOpenGuild = { onOpenGuild(att) },
                    onOpenChannel = { onOpenChannel(att) },
                )
            }
        }
    }
    analyzeFor?.let { att ->
        DiscordDiscogramAnalyzeDialog(
            onDismiss = { analyzeFor = null },
            onRun = { prompt ->
                analyzeFor = null
                onAnalyze(att, prompt)
            },
        )
    }
}

@Composable
private fun DiscordDiscogramAnalyzeDialog(
    onDismiss: () -> Unit,
    onRun: (String) -> Unit,
) {
    var prompt by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Analyze", color = GrokifyColors.TextPrimary) },
        text = {
            Column {
                Text(
                    "Optional prompt to steer the summary of this post.",
                    color = GrokifyColors.TextMuted,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it.take(4000) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Prompt") },
                    placeholder = { Text("Leave blank for a full summary") },
                    minLines = 3,
                    maxLines = 5,
                    colors = discordFieldColors(),
                    shape = RoundedCornerShape(12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onRun(prompt.trim()) }) {
                Text("Analyze", color = GrokifyColors.GlowViolet)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = GrokifyColors.TextMuted)
            }
        },
        containerColor = GrokifyColors.Panel,
    )
}

@Composable
private fun DiscordDiscogramPage(
    att: DiscordAttachment,
    active: Boolean,
    headers: Map<String, String>,
    analyzing: Boolean,
    creatorLocked: Boolean,
    onOpenCreator: () -> Unit,
    onLike: () -> Unit,
    onFollow: () -> Unit,
    onDownload: () -> Unit,
    onTag: () -> Unit,
    onAnalyze: () -> Unit,
    onOpenGuild: () -> Unit = {},
    onOpenChannel: () -> Unit = {},
) {
    val name = att.displayName.ifBlank { att.username }.ifBlank { "unknown" }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (!att.playable || att.url.isBlank()) {
                DiscordMeta("Not cached locally.")
            } else {
                when (att.kind) {
                    "image", "gif" -> DiscordZoomableImage(att, headers)
                    "video" -> DiscordExoVideo(att, headers, active = active, controls = false)
                    "audio" -> DiscordExoAudio(att, headers, active = active)
                    else -> DiscordMediaBlock(att, headers, large = true)
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(280.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f)),
                    ),
                ),
        )
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 14.dp, end = 72.dp, bottom = 18.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val openCreator = !creatorLocked && discordProfileKey(id = att.userId, discordId = att.discordId).id.isNotBlank()
                    DiscordAvatar(att.avatar, name, size = 40, headers = headers, onClick = if (openCreator) onOpenCreator else null)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f, fill = false)) {
                        Text(
                            name,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (openCreator) Modifier.clickable(onClick = onOpenCreator) else Modifier,
                        )
                        val handle = att.username.takeIf { it.isNotBlank() }?.let { "@$it" } ?: ""
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (handle.isNotBlank()) {
                                Text(
                                    handle,
                                    color = Color.White.copy(alpha = 0.75f),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    modifier = if (openCreator) Modifier.clickable(onClick = onOpenCreator) else Modifier,
                                )
                            }
                            if (att.discordId.isNotBlank()) {
                                val following = att.following
                                Text(
                                    if (following) "Following" else "Follow",
                                    color = if (following) GrokifyColors.GlowMint else Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier
                                        .border(
                                            1.dp,
                                            if (following) GrokifyColors.GlowMint else Color.White.copy(alpha = 0.7f),
                                            RoundedCornerShape(4.dp),
                                        )
                                        .clickable(onClick = onFollow)
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                }
                val guildLabel = att.guildName.ifBlank { att.guildId }
                val channelLabel = att.channelName.ifBlank { att.channelId }.let { if (it.isBlank()) "" else "#$it" }
                if (guildLabel.isNotBlank() || channelLabel.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (guildLabel.isNotBlank()) {
                            Text(
                                guildLabel,
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = if (att.guildId.isNotBlank()) Modifier.clickable(onClick = onOpenGuild) else Modifier,
                            )
                        }
                        if (guildLabel.isNotBlank() && channelLabel.isNotBlank()) {
                            Text("·", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                        }
                        if (channelLabel.isNotBlank()) {
                            Text(
                                channelLabel,
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = if (att.channelId.isNotBlank()) Modifier.clickable(onClick = onOpenChannel) else Modifier,
                            )
                        }
                    }
                }
                val whenAbs = formatDiscordWhen(att.createdAtMs)
                if (whenAbs != "—") {
                    Text(whenAbs, color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp)
                }
                if (att.filename.isNotBlank()) {
                    Text(att.filename, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 8.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DiscordDiscogramAction(
                icon = if (att.liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                label = "Like",
                tint = if (att.liked) GrokifyColors.GlowRose else Color.White,
                onClick = onLike,
                enabled = att.id > 0,
            )
            DiscordDiscogramAction(
                icon = Icons.Default.Tag,
                label = "Tag",
                tint = GrokifyColors.GlowMint,
                onClick = onTag,
                enabled = att.messageId.isNotBlank() && !analyzing,
            )
            DiscordDiscogramAction(
                icon = Icons.Default.AutoAwesome,
                label = "Analyze",
                tint = GrokifyColors.GlowViolet,
                onClick = onAnalyze,
                enabled = att.messageId.isNotBlank() && !analyzing,
            )
            DiscordDiscogramAction(
                icon = Icons.Default.Download,
                label = "Save",
                tint = Color.White,
                onClick = onDownload,
                enabled = att.url.isNotBlank(),
            )
        }
    }
}

@Composable
private fun DiscordDiscogramAction(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(22.dp))
        }
        Text(label, color = Color.White.copy(alpha = 0.9f), fontSize = 10.sp)
    }
}
