package io.grokify.os.apps.lyre.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.grokify.os.apps.lyre.BoardData
import io.grokify.os.apps.lyre.LibraryAudio
import io.grokify.os.apps.lyre.LibraryVideo
import io.grokify.os.apps.lyre.LyreStorageKeys
import io.grokify.os.apps.lyre.RefFolder
import io.grokify.os.apps.lyre.RefImage
import io.grokify.os.ui.theme.GrokifyColors
import java.io.File

sealed class LyreLibraryItem {
    data class Still(val folder: RefFolder, val image: RefImage) : LyreLibraryItem()
    data class Video(val item: LibraryVideo) : LyreLibraryItem()
    data class Audio(val item: LibraryAudio) : LyreLibraryItem()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LyreLibraryPane(
    board: BoardData,
    stills: Map<String, File> = emptyMap(),
    modifier: Modifier = Modifier,
    onOpen: (LyreLibraryItem) -> Unit = {},
    onLongItem: (LyreLibraryItem) -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    var collapsed by remember { mutableStateOf(setOf<String>()) }
    val q = query.trim().lowercase()
    fun matches(text: String): Boolean = q.isEmpty() || text.lowercase().contains(q)

    val folders = remember(board) {
        board.refFolders.filter { folder ->
            folder.id != "rf_deleted" && folder.name.lowercase() != "deleted"
        }
    }
    val videos = remember(board) { board.libraryVideo.filter { it.deletedAt == null } }
    val audios = remember(board) { board.libraryAudio.filter { it.deletedAt == null } }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item("search") {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = GrokifyColors.TextPrimary, fontSize = 14.sp),
                cursorBrush = SolidColor(GrokifyColors.GlowRose),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(GrokifyColors.Panel, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text("Search library", color = GrokifyColors.TextDim, fontSize = 14.sp)
                    }
                    inner()
                },
            )
        }
        item("filters") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("all" to "All", "stills" to "Stills", "video" to "Video", "audio" to "Audio").forEach { (id, label) ->
                    val on = filter == id
                    FilterChip(
                        selected = on,
                        onClick = { filter = id },
                        label = { Text(label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = GrokifyColors.GlowRose.copy(alpha = 0.22f),
                            selectedLabelColor = GrokifyColors.GlowRose,
                            containerColor = GrokifyColors.PanelSoft,
                            labelColor = GrokifyColors.TextPrimary,
                        ),
                    )
                }
            }
        }
        if (filter == "all" || filter == "stills") {
            folders.forEach { folder ->
                val images = folder.images.filter { matches(it.caption.ifBlank { it.id }) || matches(folder.name) }
                if (q.isNotEmpty() && images.isEmpty()) return@forEach
                stickyHeader("h-${folder.id}") {
                    FolderHeader(
                        title = folder.name.ifBlank { "Folder" },
                        count = images.size,
                        collapsed = folder.id in collapsed,
                        onToggle = {
                            collapsed = if (folder.id in collapsed) collapsed - folder.id else collapsed + folder.id
                        },
                    )
                }
                if (folder.id !in collapsed) {
                    if (images.isEmpty()) {
                        item("empty-${folder.id}") {
                            Text("Empty", color = GrokifyColors.TextDim, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    items(
                        images.chunked(4),
                        key = { row -> "s-${folder.id}-${row.first().id}" },
                    ) { row ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            row.forEach { image ->
                                val item = LyreLibraryItem.Still(folder, image)
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(GrokifyColors.Panel)
                                        .combinedClickable(
                                            onClick = { onOpen(item) },
                                            onLongClick = { onLongItem(item) },
                                        )
                                        .padding(4.dp),
                                ) {
                                    LyreStillImage(
                                        file = LyreStorageKeys.file(stills, image.src),
                                        contentDescription = image.caption,
                                        contentScale = ContentScale.Crop,
                                        fallback = image.caption,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(64.dp)
                                            .clip(RoundedCornerShape(5.dp)),
                                    )
                                    Text(
                                        image.caption.ifBlank { image.id },
                                        color = GrokifyColors.TextPrimary,
                                        fontSize = 10.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                            }
                            repeat(4 - row.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
        if (filter == "all" || filter == "video") {
            val shown = videos.filter { matches(it.name.ifBlank { it.id }) }
            if (q.isEmpty() || shown.isNotEmpty()) {
                stickyHeader("h-videos") {
                    FolderHeader(
                        title = "Videos",
                        count = shown.size,
                        collapsed = "videos" in collapsed,
                        onToggle = {
                            collapsed = if ("videos" in collapsed) collapsed - "videos" else collapsed + "videos"
                        },
                    )
                }
                if ("videos" !in collapsed) {
                    items(shown, key = { "v-${it.id}" }) { video ->
                        val item = LyreLibraryItem.Video(video)
                        val poster = LyreStorageKeys.posterSrc(board, video.src)
                        val posterFile = LyreStorageKeys.file(stills, poster)
                            ?.takeIf { LyreStorageKeys.isStillFile(it) }
                        LibraryRow(
                            title = video.name.ifBlank { video.id },
                            subtitle = "Video · ${"%.1f".format(video.durationSec)}s",
                            file = posterFile,
                            fallback = video.name,
                            onClick = { onOpen(item) },
                            onLongClick = { onLongItem(item) },
                        )
                    }
                }
            }
        }
        if (filter == "all" || filter == "audio") {
            val shown = audios.filter { matches(it.name.ifBlank { it.id }) }
            if (q.isEmpty() || shown.isNotEmpty()) {
                stickyHeader("h-audio") {
                    FolderHeader(
                        title = "Audio",
                        count = shown.size,
                        collapsed = "audio" in collapsed,
                        onToggle = {
                            collapsed = if ("audio" in collapsed) collapsed - "audio" else collapsed + "audio"
                        },
                    )
                }
                if ("audio" !in collapsed) {
                    items(shown, key = { "a-${it.id}" }) { audio ->
                        val item = LyreLibraryItem.Audio(audio)
                        LibraryRow(
                            title = audio.name.ifBlank { audio.id },
                            subtitle = "Audio · ${"%.1f".format(audio.durationSec)}s",
                            fallback = "♪",
                            onClick = { onOpen(item) },
                            onLongClick = { onLongItem(item) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderHeader(
    title: String,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(GrokifyColors.Void)
            .combinedClickable(onClick = onToggle, onLongClick = onToggle)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (collapsed) "▸ $title" else "▾ $title",
            color = GrokifyColors.GlowRose,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
        )
        Text(
            "  $count",
            color = GrokifyColors.TextDim,
            fontSize = 12.sp,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryRow(
    title: String,
    subtitle: String,
    file: File? = null,
    fallback: String? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(GrokifyColors.Panel, RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LyreStillImage(
            file = file,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            fallback = fallback,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text(title, color = GrokifyColors.TextPrimary, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            Text(subtitle, color = GrokifyColors.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
