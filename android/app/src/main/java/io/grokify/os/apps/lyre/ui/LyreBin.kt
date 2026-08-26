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
fun LyreBinPane(
    board: BoardData,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onApply: (RuleResult) -> Unit = {},
) {
    val applyLatest = rememberUpdatedState(onApply)
    val dumped = remember(board) {
        board.refFolders.flatMap { it.images }.filter {
            !it.fromFrameId.isNullOrEmpty() || !it.fromSceneId.isNullOrEmpty()
        }
    }
    var pendingId by remember { mutableStateOf<String?>(null) }
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
                        .clickable(enabled = enabled) {
                            applyLatest.value(LyreRules.recycle(board, image.id))
                        },
                ) {
                    Text(
                        image.caption.ifBlank { image.id },
                        color = GrokifyColors.TextPrimary,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                    )
                    val from = image.fromSceneTitle?.ifBlank { null } ?: image.fromSceneId
                    Text(
                        if (!from.isNullOrEmpty()) "From $from · tap to recycle" else "Tap to recycle",
                        color = GrokifyColors.TextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                TextButton(
                    onClick = { pendingId = image.id },
                    enabled = enabled,
                ) {
                    Text("Delete", fontSize = 12.sp, color = GrokifyColors.GlowRose)
                }
            }
        }
    }
    val confirmId = pendingId
    val confirm = confirmId?.let { id -> dumped.firstOrNull { it.id == id } }
    if (confirm != null) {
        DeleteConfirmDialog(
            label = confirm.caption.ifBlank { confirm.id },
            onDismiss = { pendingId = null },
            onConfirm = {
                applyLatest.value(LyreRules.deleteRefImage(board, confirm.id))
                pendingId = null
            },
        )
    }
}
