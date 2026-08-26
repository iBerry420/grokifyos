package io.grokify.os.apps.lyre.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.grokify.os.apps.lyre.BoardData
import io.grokify.os.ui.theme.GrokifyColors

@Composable
fun LyreLibraryPane(board: BoardData, modifier: Modifier = Modifier) {
    val images = remember(board) {
        board.refFolders.flatMap { folder ->
            folder.images.filter { it.fromFrameId.isNullOrEmpty() && it.fromSceneId.isNullOrEmpty() }
                .map { folder.name to it.caption.ifBlank { it.id } }
        }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (board.libraryVideo.isEmpty() && board.libraryAudio.isEmpty() && images.isEmpty()) {
            item {
                Text("Library is empty", color = GrokifyColors.TextMuted, fontSize = 13.sp)
            }
        }
        items(board.libraryVideo, key = { "v-" + it.id }) { item ->
            LibraryRow(item.name.ifBlank { item.id }, "Video · ${"%.1f".format(item.durationSec)}s")
        }
        items(board.libraryAudio, key = { "a-" + it.id }) { item ->
            LibraryRow(item.name.ifBlank { item.id }, "Audio · ${"%.1f".format(item.durationSec)}s")
        }
        items(images.size) { i ->
            val (folder, caption) = images[i]
            LibraryRow(caption, folder)
        }
    }
}

@Composable
private fun LibraryRow(title: String, subtitle: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(GrokifyColors.Panel, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(title, color = GrokifyColors.TextPrimary, fontWeight = FontWeight.Medium, fontSize = 13.sp)
        Text(subtitle, color = GrokifyColors.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
    }
}
