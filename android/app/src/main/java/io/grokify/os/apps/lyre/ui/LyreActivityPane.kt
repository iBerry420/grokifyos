package io.grokify.os.apps.lyre.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.grokify.os.apps.lyre.BoardData
import io.grokify.os.apps.lyre.LyreActivity
import io.grokify.os.apps.lyre.LyreActivityLine
import io.grokify.os.apps.lyre.LyreCache
import io.grokify.os.apps.lyre.LyreUndo
import io.grokify.os.ui.theme.GrokifyColors

@Composable
fun LyreActivityPane(
    cache: LyreCache,
    boardId: String,
    board: BoardData,
    onJump: (LyreActivityLine) -> Unit,
    modifier: Modifier = Modifier,
) {
    val jumpLatest = rememberUpdatedState(onJump)
    val lines = remember(board, boardId) { LyreActivity.read(cache, boardId) }
    val oldestUndoMs = remember(board, boardId) {
        LyreUndo(cache).entries(boardId).minOfOrNull { it.atMs }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) runCatching { listState.scrollToItem(lines.lastIndex) }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (lines.isEmpty()) {
            item {
                Text("No activity yet", color = GrokifyColors.TextMuted, fontSize = 13.sp)
            }
        }
        itemsIndexed(lines, key = { i, line -> "$i-${line.ts}-${line.type}-${line.summary}" }) { _, line ->
            val recent = oldestUndoMs == null || line.ts >= oldestUndoMs
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(GrokifyColors.Panel, RoundedCornerShape(10.dp))
                    .clickable(enabled = recent) { jumpLatest.value(line) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    line.summary.ifBlank { line.type.ifBlank { "event" } },
                    color = GrokifyColors.TextPrimary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                )
                Text(
                    if (recent) line.type.ifBlank { "event" } else "${line.type.ifBlank { "event" }} · inspect only",
                    color = GrokifyColors.TextMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
