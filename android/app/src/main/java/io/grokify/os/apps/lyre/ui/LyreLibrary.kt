package io.grokify.os.apps.lyre.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.grokify.os.apps.lyre.BoardData
import io.grokify.os.apps.lyre.LyreRules
import io.grokify.os.apps.lyre.RuleResult
import io.grokify.os.ui.theme.GrokifyColors

@Composable
fun LyreLibraryPane(
    board: BoardData,
    modifier: Modifier = Modifier,
    afterFrameId: String? = null,
    enabled: Boolean = true,
    onApply: (RuleResult) -> Unit = {},
) {
    val applyLatest = rememberUpdatedState(onApply)
    val stills = remember(board) {
        board.refFolders.flatMap { folder ->
            folder.images.filter { it.fromFrameId.isNullOrEmpty() && it.fromSceneId.isNullOrEmpty() }
                .map { folder.name to it }
        }
    }
    val videos = remember(board) { board.libraryVideo.filter { it.deletedAt == null } }
    val audios = remember(board) { board.libraryAudio.filter { it.deletedAt == null } }
    var pending by remember { mutableStateOf<ConfirmDelete?>(null) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (videos.isEmpty() && audios.isEmpty() && stills.isEmpty()) {
            item {
                Text("Library is empty", color = GrokifyColors.TextMuted, fontSize = 13.sp)
            }
        }
        items(videos, key = { "v-" + it.id }) { item ->
            LibraryRow(
                title = item.name.ifBlank { item.id },
                subtitle = "Video · ${"%.1f".format(item.durationSec)}s",
                enabled = enabled,
                onInsert = { applyLatest.value(LyreRules.insertLibraryVideo(board, item, afterFrameId)) },
                onDelete = {
                    pending = ConfirmDelete(item.name.ifBlank { item.id }) {
                        applyLatest.value(LyreRules.deleteLibraryVideo(board, item.id))
                    }
                },
            )
        }
        items(audios, key = { "a-" + it.id }) { item ->
            LibraryRow(
                title = item.name.ifBlank { item.id },
                subtitle = "Audio · ${"%.1f".format(item.durationSec)}s",
                enabled = enabled,
                onInsert = null,
                onDelete = {
                    pending = ConfirmDelete(item.name.ifBlank { item.id }) {
                        applyLatest.value(LyreRules.deleteLibraryAudio(board, item.id))
                    }
                },
            )
        }
        items(stills, key = { (_, image) -> "s-" + image.id }) { (folder, image) ->
            LibraryRow(
                title = image.caption.ifBlank { image.id },
                subtitle = folder,
                enabled = enabled,
                onInsert = { applyLatest.value(LyreRules.insertLibraryStill(board, image, afterFrameId)) },
                onDelete = {
                    pending = ConfirmDelete(image.caption.ifBlank { image.id }) {
                        applyLatest.value(LyreRules.deleteRefImage(board, image.id))
                    }
                },
            )
        }
    }
    val confirm = pending
    if (confirm != null) {
        DeleteConfirmDialog(
            label = confirm.label,
            onDismiss = { pending = null },
            onConfirm = {
                confirm.run()
                pending = null
            },
        )
    }
}

private data class ConfirmDelete(val label: String, val run: () -> Unit)

@Composable
private fun LibraryRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onInsert: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(GrokifyColors.Panel, RoundedCornerShape(10.dp))
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(vertical = 4.dp)
                .then(
                    if (onInsert != null) Modifier.clickable(enabled = enabled, onClick = onInsert)
                    else Modifier,
                ),
        ) {
            Text(title, color = GrokifyColors.TextPrimary, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            Text(subtitle, color = GrokifyColors.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        }
        TextButton(onClick = onDelete, enabled = enabled) {
            Text("Delete", fontSize = 12.sp, color = GrokifyColors.GlowRose)
        }
    }
}

@Composable
internal fun DeleteConfirmDialog(
    label: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete permanently?") },
        text = { Text(label) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
