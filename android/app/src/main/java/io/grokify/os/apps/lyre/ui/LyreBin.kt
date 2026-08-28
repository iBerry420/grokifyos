package io.grokify.os.apps.lyre.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.grokify.os.apps.lyre.BoardData
import io.grokify.os.apps.lyre.LyreStorageKeys
import io.grokify.os.apps.lyre.RefImage
import io.grokify.os.ui.theme.GrokifyColors
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LyreBinPane(
    board: BoardData,
    stills: Map<String, File> = emptyMap(),
    modifier: Modifier = Modifier,
    onLongImage: (RefImage) -> Unit = {},
) {
    val dumped = remember(board) {
        val deletedFolder = board.refFolders.filter {
            it.id == "rf_deleted" || it.name.equals("deleted", ignoreCase = true)
        }.flatMap { it.images }
        if (deletedFolder.isNotEmpty()) deletedFolder
        else {
            board.refFolders.flatMap { it.images }.filter {
                !it.fromFrameId.isNullOrEmpty() || !it.fromSceneId.isNullOrEmpty()
            }
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
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(GrokifyColors.Panel, RoundedCornerShape(10.dp))
                    .combinedClickable(onClick = {}, onLongClick = { onLongImage(image) })
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LyreStillImage(
                    file = LyreStorageKeys.file(stills, image.src),
                    contentDescription = image.caption,
                    contentScale = ContentScale.Crop,
                    fallback = image.caption,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(6.dp)),
                )
                Column(Modifier.weight(1f)) {
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
}
