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
import io.grokify.os.apps.lyre.LyreMovie
import io.grokify.os.ui.theme.GrokifyColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyreClipSheet(
    board: BoardData,
    frame: Frame,
    clip: LayerClip?,
    preferClip: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember {
        mutableStateOf(
            if (preferClip || frame.videoSrc.isNullOrEmpty()) "clip" else "picture",
        )
    }
    val canStitch = clip != null &&
        LyreMovie.canStitchClip(clip.id, clip.src, board.videoLayers, board.movie)
    val canPop = (board.movie?.parts?.size ?: 0) > 1

    fun later() {
        Toast.makeText(context, "cutter in a later build", Toast.LENGTH_SHORT).show()
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
                if (canStitch) {
                    SheetChip("Stitch", selected = false, onClick = { later() })
                }
                if (canPop) {
                    SheetChip("Pop", selected = false, onClick = { later() })
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
