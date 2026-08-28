package io.grokify.os.apps.lyre.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.grokify.os.apps.lyre.BoardData
import io.grokify.os.apps.lyre.LyreImagine
import io.grokify.os.apps.lyre.LyreImagineJob
import io.grokify.os.apps.lyre.LyreImagineMode
import io.grokify.os.apps.lyre.LyreStorageKeys
import io.grokify.os.apps.lyre.RefImage
import io.grokify.os.ui.theme.GrokifyColors
import java.io.File

data class LyreImagineDraft(
    val prompt: String,
    val refs: List<RefImage>,
    val voices: List<String>,
    val duration: Int,
    val aspect: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyreImagineSheet(
    job: LyreImagineJob,
    board: BoardData,
    stills: Map<String, File>,
    busy: Boolean,
    status: String?,
    onGenerate: (LyreImagineDraft) -> Unit,
    onPickGalleryRef: () -> Unit = {},
    extraFiles: List<File> = emptyList(),
    onDismiss: () -> Unit,
) {
    val video = job.mode == LyreImagineMode.GEN_VIDEO || job.mode == LyreImagineMode.EDIT_VIDEO
    var prompt by remember { mutableStateOf("") }
    var refs by remember { mutableStateOf<List<RefImage>>(emptyList()) }
    var voices by remember { mutableStateOf<List<String>>(emptyList()) }
    var duration by remember { mutableStateOf(6) }
    var picking by remember { mutableStateOf(false) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val title = when (job.mode) {
        LyreImagineMode.NEXT_STILL -> "Generate image"
        LyreImagineMode.EDIT_STILL -> "Edit still"
        LyreImagineMode.GEN_VIDEO -> "Generate video"
        LyreImagineMode.EDIT_VIDEO -> "Edit video"
    }
    val libraryStills = remember(board) {
        val fromLib = board.refFolders.flatMap { it.images }.filter { it.src.isNotBlank() }
        if (fromLib.isNotEmpty()) {
            fromLib
        } else {
            board.scenes.flatMap { scene ->
                scene.frames.map { frame ->
                    RefImage(id = frame.id, src = frame.src, caption = frame.caption)
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = GrokifyColors.VoidElevated,
        contentColor = GrokifyColors.TextPrimary,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = GrokifyColors.TextPrimary)
            Text(job.title, color = GrokifyColors.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(12.dp))
            Text("Prompt", color = GrokifyColors.TextDim, fontSize = 11.sp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(88.dp)
                    .padding(top = 4.dp)
                    .background(GrokifyColors.PanelSoft, RoundedCornerShape(8.dp))
                    .padding(10.dp),
            ) {
                BasicTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    textStyle = TextStyle(color = GrokifyColors.TextPrimary, fontSize = 14.sp),
                    cursorBrush = SolidColor(GrokifyColors.GlowRose),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                )
                if (prompt.isBlank()) {
                    Text("What should change…", color = GrokifyColors.TextDim, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("References (up to 3)", color = GrokifyColors.TextDim, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { picking = true }, enabled = !busy && refs.size < 3) {
                    Text("Library", color = GrokifyColors.GlowCyan, fontSize = 12.sp)
                }
                TextButton(onClick = onPickGalleryRef, enabled = !busy && refs.size < 3) {
                    Text("Files", color = GrokifyColors.GlowCyan, fontSize = 12.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                repeat(3) { i ->
                    val image = refs.getOrNull(i)
                    val extra = extraFiles.getOrNull(i - refs.size).takeIf { image == null && i >= refs.size }
                    val file = LyreStorageKeys.file(stills, image?.src) ?: extra
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(GrokifyColors.Panel)
                            .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(8.dp))
                            .clickable(enabled = !busy) {
                                if (image != null) refs = refs.filterIndexed { idx, _ -> idx != i }
                                else picking = true
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (file != null) {
                            LyreStillImage(
                                file = file,
                                contentDescription = image?.caption ?: "Ref",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(72.dp),
                                fallback = image?.caption ?: "+",
                            )
                        } else {
                            Text("+", color = GrokifyColors.TextMuted, fontSize = 20.sp)
                        }
                    }
                }
            }
            if (video) {
                Spacer(Modifier.height(14.dp))
                Text("Length", color = GrokifyColors.TextDim, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    listOf(6, 10, 15).forEach { sec ->
                        FilterChip(
                            selected = duration == sec,
                            onClick = { duration = sec },
                            enabled = !busy && job.mode != LyreImagineMode.EDIT_VIDEO,
                            label = { Text("${sec}s", fontSize = 12.sp) },
                            colors = chipColors(duration == sec),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Voices (up to 3)", color = GrokifyColors.TextDim, fontSize = 11.sp)
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LyreImagine.VOICES.forEach { id ->
                        val on = id in voices
                        FilterChip(
                            selected = on,
                            onClick = {
                                voices = if (on) {
                                    voices - id
                                } else if (voices.size < 3) {
                                    voices + id
                                } else {
                                    voices
                                }
                            },
                            enabled = !busy,
                            label = { Text(id, fontSize = 12.sp) },
                            colors = chipColors(on),
                        )
                    }
                }
            }
            if (!status.isNullOrBlank()) {
                Text(
                    status,
                    color = if (busy) GrokifyColors.GlowAmber else GrokifyColors.GlowRose,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss, enabled = !busy) {
                    Text("Cancel", color = GrokifyColors.TextMuted)
                }
                TextButton(
                    onClick = {
                        onGenerate(
                            LyreImagineDraft(
                                prompt = prompt,
                                refs = refs,
                                voices = voices,
                                duration = duration,
                                aspect = "16:9",
                            ),
                        )
                    },
                    enabled = !busy,
                ) {
                    Text(if (busy) "Working…" else "Generate", color = GrokifyColors.GlowRose, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (picking) {
        LyreActionSheet(
            title = "Add reference",
            subtitle = "Pick a still from the library",
            actions = libraryStills.take(40).map { img ->
                LyreMenuAction(img.id, img.caption.ifBlank { img.id })
            } + LyreMenuAction("cancel", "Cancel"),
            onAction = { id ->
                if (id != "cancel") {
                    libraryStills.firstOrNull { it.id == id }?.let { img ->
                        if (refs.none { it.id == img.id } && refs.size < 3) refs = refs + img
                    }
                }
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun chipColors(on: Boolean) = FilterChipDefaults.filterChipColors(
    selectedContainerColor = GrokifyColors.GlowRose.copy(alpha = 0.22f),
    selectedLabelColor = GrokifyColors.GlowRose,
    containerColor = GrokifyColors.PanelSoft,
    labelColor = GrokifyColors.TextPrimary,
)
