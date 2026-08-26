package io.grokify.os.apps.lyre.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import io.grokify.os.apps.lyre.LyreClip
import io.grokify.os.apps.lyre.LyreRules
import io.grokify.os.apps.lyre.RuleResult
import io.grokify.os.ui.theme.GrokifyColors

@Composable
fun LyreScenesPane(
    board: BoardData,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onApply: (RuleResult) -> Unit = {},
) {
    val applyLatest = rememberUpdatedState(onApply)
    var renameId by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${board.scenes.size} scenes",
                    color = GrokifyColors.TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { applyLatest.value(LyreRules.addScene(board)) },
                    enabled = enabled,
                ) {
                    Text("+", color = GrokifyColors.GlowRose, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        itemsIndexed(board.scenes, key = { _, scene -> scene.id }) { index, scene ->
            val dur = scene.frames.sumOf { LyreClip.clipLength(it) }
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(GrokifyColors.Panel, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
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
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    TextButton(
                        onClick = { applyLatest.value(LyreRules.moveScene(board, scene.id, index - 1)) },
                        enabled = enabled && index > 0,
                    ) {
                        Text("Up", fontSize = 12.sp, color = GrokifyColors.TextPrimary)
                    }
                    TextButton(
                        onClick = { applyLatest.value(LyreRules.moveScene(board, scene.id, index + 1)) },
                        enabled = enabled && index < board.scenes.lastIndex,
                    ) {
                        Text("Down", fontSize = 12.sp, color = GrokifyColors.TextPrimary)
                    }
                    TextButton(
                        onClick = {
                            renameId = scene.id
                            renameDraft = scene.title
                        },
                        enabled = enabled,
                    ) {
                        Text("Rename", fontSize = 12.sp, color = GrokifyColors.TextPrimary)
                    }
                    TextButton(
                        onClick = { applyLatest.value(LyreRules.dumpScene(board, scene.id)) },
                        enabled = enabled,
                    ) {
                        Text("Dump", fontSize = 12.sp, color = GrokifyColors.GlowRose)
                    }
                }
            }
        }
    }
    val renaming = renameId
    if (renaming != null) {
        AlertDialog(
            onDismissRequest = { renameId = null },
            title = { Text("Rename scene") },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = GrokifyColors.TextPrimary,
                        unfocusedTextColor = GrokifyColors.TextPrimary,
                        focusedBorderColor = GrokifyColors.GlowRose,
                        unfocusedBorderColor = GrokifyColors.PanelBorder,
                        cursorColor = GrokifyColors.GlowRose,
                        focusedContainerColor = GrokifyColors.PanelSoft,
                        unfocusedContainerColor = GrokifyColors.PanelSoft,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        applyLatest.value(LyreRules.renameScene(board, renaming, renameDraft))
                        renameId = null
                    },
                    enabled = enabled && renameDraft.trim().isNotEmpty(),
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameId = null }) { Text("Cancel") }
            },
        )
    }
}
