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
fun LyreBinPane(board: BoardData, modifier: Modifier = Modifier) {
    val dumped = remember(board) {
        board.refFolders.flatMap { it.images }.filter {
            !it.fromFrameId.isNullOrEmpty() || !it.fromSceneId.isNullOrEmpty()
        }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (dumped.isEmpty()) {
            item {
                Text("Bin is empty", color = GrokifyColors.TextMuted, fontSize = 13.sp)
            }
        }
        items(dumped, key = { it.id }) { image ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(GrokifyColors.Panel, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    image.caption.ifBlank { image.id },
                    color = GrokifyColors.TextPrimary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                )
                val from = image.fromSceneTitle?.ifBlank { null } ?: image.fromSceneId
                if (!from.isNullOrEmpty()) {
                    Text(
                        "From $from",
                        color = GrokifyColors.TextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}
