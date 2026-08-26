package io.grokify.os.apps.lyre.ui

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.grokify.os.apps.lyre.BoardData
import io.grokify.os.apps.lyre.Frame
import io.grokify.os.apps.lyre.LayerClip
import io.grokify.os.apps.lyre.LibraryAudio
import io.grokify.os.apps.lyre.LyreMovie
import io.grokify.os.apps.lyre.LyreRules
import io.grokify.os.apps.lyre.RuleResult
import io.grokify.os.ui.theme.GrokifyColors

/** PR 5 mix test compiled; never device-run (`adb devices` empty). */
internal const val LYRE_BURN_AUDIO_DEVICE_GREEN = false

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyreClipSheet(
    board: BoardData,
    frame: Frame,
    clip: LayerClip?,
    preferClip: Boolean,
    nativeAtSec: Double? = null,
    enabled: Boolean = true,
    onApply: (RuleResult) -> Unit = {},
    onPickAudio: (() -> Unit)? = null,
    onPickAudioFile: (() -> Unit)? = null,
    onPickLibraryAudio: ((LibraryAudio) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember {
        mutableStateOf(
            if (preferClip || frame.videoSrc.isNullOrEmpty()) "clip" else "picture",
        )
    }
    val leftoverFrame = !LyreMovie.frameInMovie(board.movie, board.videoLayers, frame.id)
    val canStitch = clip != null &&
        LyreMovie.canStitchClip(clip.id, clip.src, board.videoLayers, board.movie)
    val canPop = (board.movie?.parts?.size ?: 0) > 1
    val canBurn = board.movie != null && board.audioLayers.any { it.clips.isNotEmpty() }

    fun run(result: RuleResult) {
        if (!enabled) return
        if (result.plan == null && result.board === board) return
        onApply(result)
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = GrokifyColors.VoidElevated,
        contentColor = GrokifyColors.TextPrimary,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = 20.dp),
        ) {
            Text(
                frame.caption.ifBlank { frame.id },
                color = GrokifyColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
            Text(
                if (tab == "clip") "Clip" else "Picture",
                color = GrokifyColors.TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SheetChip("Picture", selected = tab == "picture") { tab = "picture" }
                SheetChip("Clip", selected = tab == "clip") { tab = "clip" }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (tab == "clip") {
                    if (canStitch) {
                        SheetChip("Stitch", selected = false, onClick = {
                            val id = clip?.id ?: return@SheetChip
                            run(LyreRules.stitch(board, id))
                        })
                    }
                    if (canPop) {
                        SheetChip("Pop", selected = false, onClick = {
                            run(LyreRules.pop(board))
                        })
                    }
                    if (clip != null) {
                        SheetChip("Trim", selected = false, onClick = {
                            val inn = clip.trimInSec ?: frame.videoInSec ?: 0.0
                            val at = nativeAtSec ?: return@SheetChip
                            run(LyreRules.trim(board, clip.id, inn, at))
                        })
                        SheetChip("Split", selected = false, onClick = {
                            val at = nativeAtSec ?: return@SheetChip
                            run(LyreRules.split(board, clip.id, at))
                        })
                        SheetChip("Mute", selected = false, onClick = {
                            run(LyreRules.mute(board, clip.id))
                        })
                        SheetChip("Extract", selected = false, onClick = {
                            run(LyreRules.extractAudio(board, clip.id))
                        })
                        SheetChip("Restore", selected = false, onClick = {
                            run(LyreRules.restoreClip(board, clip.id))
                        })
                        SheetChip("Remove", selected = false, onClick = {
                            run(LyreRules.removeClip(board, clip.id))
                        })
                    }
                    if (leftoverFrame && onPickAudio != null) {
                        SheetChip("Audio", selected = false, onClick = {
                            if (!enabled) return@SheetChip
                            onPickAudio()
                            onDismiss()
                        })
                    }
                    if (leftoverFrame && onPickAudioFile != null) {
                        SheetChip("Files", selected = false, onClick = {
                            if (!enabled) return@SheetChip
                            onPickAudioFile()
                            onDismiss()
                        })
                    }
                    if (leftoverFrame && onPickLibraryAudio != null) {
                        board.libraryAudio.filter { it.deletedAt == null && it.src.isNotEmpty() }
                            .forEach { item ->
                                SheetChip(item.name.ifBlank { "Library" }, selected = false, onClick = {
                                    if (!enabled) return@SheetChip
                                    onPickLibraryAudio(item)
                                    onDismiss()
                                })
                            }
                    }
                    if (canBurn) {
                        SheetChip("Burn", selected = false, onClick = {
                            if (!enabled) return@SheetChip
                            if (!LYRE_BURN_AUDIO_DEVICE_GREEN) {
                                Toast.makeText(
                                    context,
                                    "Burn-audio mix isn't device-green",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                return@SheetChip
                            }
                            run(LyreRules.burnAudio(board))
                        })
                    }
                } else {
                    SheetChip("Restore", selected = false, onClick = {
                        run(LyreRules.restorePicture(board, frame.id))
                    })
                    SheetChip("Remove", selected = false, onClick = {
                        run(LyreRules.removeBeat(board, frame.id))
                    })
                }
            }
        }
    }
}

@Composable
private fun SheetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp, maxLines = 1) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = GrokifyColors.GlowRose.copy(alpha = 0.22f),
            selectedLabelColor = GrokifyColors.GlowRose,
            containerColor = GrokifyColors.PanelSoft,
            labelColor = GrokifyColors.TextPrimary,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = GrokifyColors.PanelBorder,
            selectedBorderColor = GrokifyColors.GlowRose,
        ),
    )
}
