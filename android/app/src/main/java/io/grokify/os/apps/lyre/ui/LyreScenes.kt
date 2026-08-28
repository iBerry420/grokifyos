package io.grokify.os.apps.lyre.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.grokify.os.apps.lyre.BoardData
import io.grokify.os.apps.lyre.Frame
import io.grokify.os.apps.lyre.LyreClip
import io.grokify.os.apps.lyre.LyreStorageKeys
import io.grokify.os.apps.lyre.Scene
import io.grokify.os.ui.theme.GrokifyColors
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LyreScenesPane(
    board: BoardData,
    stills: Map<String, File> = emptyMap(),
    modifier: Modifier = Modifier,
    onSeekScene: (Scene) -> Unit = {},
    onSeekFrame: (Frame) -> Unit = {},
    onLongFrame: (Scene, Frame) -> Unit = { _, _ -> },
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(board.scenes, key = { it.id }) { scene ->
            val dur = scene.frames.sumOf { LyreClip.clipLength(it) }
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(GrokifyColors.Panel, RoundedCornerShape(10.dp))
                    .combinedClickable(
                        onClick = { onSeekScene(scene) },
                        onLongClick = {
                            scene.frames.firstOrNull()?.let { onLongFrame(scene, it) }
                        },
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    scene.title.ifBlank { "Untitled scene" },
                    color = GrokifyColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
                Text(
                    "${scene.frames.size} stills · ${"%.1f".format(dur)}s",
                    color = GrokifyColors.TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (scene.frames.isNotEmpty()) {
                    Row(
                        Modifier
                            .padding(top = 8.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        scene.frames.forEach { frame ->
                            LyreStillImage(
                                file = LyreStorageKeys.file(stills, frame.src),
                                contentDescription = frame.caption,
                                contentScale = ContentScale.Crop,
                                fallback = frame.caption,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .combinedClickable(
                                        onClick = { onSeekFrame(frame) },
                                        onLongClick = { onLongFrame(scene, frame) },
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}
