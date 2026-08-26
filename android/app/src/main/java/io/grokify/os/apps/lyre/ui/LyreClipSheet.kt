package io.grokify.os.apps.lyre.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.grokify.os.apps.GROK_VOICES
import io.grokify.os.apps.lyre.BoardData
import io.grokify.os.apps.lyre.Frame
import io.grokify.os.apps.lyre.LayerClip
import io.grokify.os.apps.lyre.LibraryAudio
import io.grokify.os.apps.lyre.LyreCache
import io.grokify.os.apps.lyre.LyreImagine
import io.grokify.os.apps.lyre.LyreMovie
import io.grokify.os.apps.lyre.LyreRules
import io.grokify.os.apps.lyre.RuleResult
import io.grokify.os.ui.theme.GrokifyColors
import java.io.File

/** false until burnAudioTwoBedsWithGapUs is device-green; chips must not call burnAudio/apply. */
internal const val LYRE_BURN_AUDIO_DEVICE_GREEN = false

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyreClipSheet(
    board: BoardData,
    boardId: String,
    cache: LyreCache,
    frame: Frame,
    clip: LayerClip?,
    preferClip: Boolean,
    nativeAtSec: Double? = null,
    enabled: Boolean = true,
    imagineBusy: Boolean = false,
    onApply: (RuleResult) -> Unit = {},
    onGenerateStill: (prompt: String, refs: List<String>, aspect: String) -> Unit = { _, _, _ -> },
    onGenerateClip: (
        prompt: String,
        duration: Int,
        aspect: String,
        resolution: String,
        refs: List<String>,
        voices: List<String>,
    ) -> Unit = { _, _, _, _, _, _ -> },
    onEditClip: (
        prompt: String,
        duration: Int,
        aspect: String,
        resolution: String,
        refs: List<String>,
        voices: List<String>,
    ) -> Unit = { _, _, _, _, _, _ -> },
    onImportUri: (Uri) -> Unit = {},
    onImportFile: (File, String) -> Unit = { _, _ -> },
    onAddRef: (Uri) -> Unit = {},
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
    val canStitch = clip != null &&
        LyreMovie.canStitchClip(clip.id, clip.src, board.videoLayers, board.movie)
    val canPop = (board.movie?.parts?.size ?: 0) > 1
    val leftover = LyreRules.leftoverFrame(board, frame.id) != null
    val leftoverFrame = leftover
    val canBurn = board.movie != null && board.audioLayers.any { it.clips.isNotEmpty() }
    val canMutate = enabled && !imagineBusy
    val canImagine = canMutate && leftover &&
        frame.generating != true && frame.videoGenerating != true
    val locked = board.videoGen?.locked == true
    var prompt by remember {
        mutableStateOf(frame.videoPrompt.orEmpty().ifBlank { frame.caption })
    }
    var duration by remember {
        mutableIntStateOf(LyreImagine.coerceDuration(board.videoGen?.duration?.toInt() ?: 6))
    }
    var aspect by remember {
        mutableStateOf(LyreImagine.coerceAspect(board.videoGen?.aspectRatio ?: "16:9"))
    }
    var resolution by remember {
        mutableStateOf(LyreImagine.coerceResolution(board.videoGen?.resolution ?: "720p"))
    }
    var voices by remember { mutableStateOf(LyreImagine.filterVoices(frame.videoVoices.orEmpty())) }
    val refs = frame.videoRefSrcs.orEmpty().take(LyreImagine.MAX_REFS)
    val captureFile = remember { cache.tmpFile(boardId, ".jpg") }
    val captureUri = remember(captureFile) {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", captureFile)
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok && captureFile.isFile && captureFile.length() > 0L) {
            onImportFile(captureFile, "image/jpeg")
            onDismiss()
        }
    }
    val pickGallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            onImportUri(uri)
            onDismiss()
        }
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            onImportUri(uri)
            onDismiss()
        }
    }
    val pickRef = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onAddRef(uri)
    }

    fun run(result: RuleResult) {
        if (!canMutate) return
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
                .verticalScroll(rememberScrollState())
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
            if (leftover) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    placeholder = { Text("Imagine prompt", color = GrokifyColors.TextDim) },
                    minLines = 1,
                    maxLines = 3,
                    enabled = canImagine,
                    colors = imagineFieldColors(),
                )
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
                        SheetChip("Stitch", selected = false, enabled = canMutate, onClick = {
                            val id = clip?.id ?: return@SheetChip
                            run(LyreRules.stitch(board, id))
                        })
                    }
                    if (canPop) {
                        SheetChip("Pop", selected = false, enabled = canMutate, onClick = {
                            run(LyreRules.pop(board))
                        })
                    }
                    if (clip != null) {
                        SheetChip("Trim", selected = false, enabled = canMutate, onClick = {
                            val inn = clip.trimInSec ?: frame.videoInSec ?: 0.0
                            val at = nativeAtSec ?: return@SheetChip
                            run(LyreRules.trim(board, clip.id, inn, at))
                        })
                        SheetChip("Split", selected = false, enabled = canMutate, onClick = {
                            val at = nativeAtSec ?: return@SheetChip
                            run(LyreRules.split(board, clip.id, at))
                        })
                        SheetChip("Mute", selected = false, enabled = canMutate, onClick = {
                            run(LyreRules.mute(board, clip.id))
                        })
                        SheetChip("Extract", selected = false, enabled = canMutate, onClick = {
                            run(LyreRules.extractAudio(board, clip.id))
                        })
                        SheetChip("Restore", selected = false, enabled = canMutate, onClick = {
                            run(LyreRules.restoreClip(board, clip.id))
                        })
                        SheetChip("Remove", selected = false, enabled = canMutate, onClick = {
                            run(LyreRules.removeClip(board, clip.id))
                        })
                    }
                    if (leftover) {
                        SheetChip(
                            if (frame.videoGenerating == true) "Generating…" else "Generate clip",
                            selected = false,
                            enabled = canImagine && prompt.isNotBlank(),
                            onClick = {
                                onGenerateClip(prompt.trim(), duration, aspect, resolution, refs, voices)
                            },
                        )
                        if (clip != null && clip.src.isNotBlank()) {
                            SheetChip(
                                "Edit clip",
                                selected = false,
                                enabled = canImagine && prompt.isNotBlank(),
                                onClick = {
                                    onEditClip(prompt.trim(), duration, aspect, resolution, refs, voices)
                                },
                            )
                        }
                        if (!frame.videoGeneratingError.isNullOrBlank()) {
                            SheetChip("Retry", selected = false, enabled = canImagine, onClick = {
                                if (frame.videoGeneratingError == "edit_unavailable") {
                                    onEditClip(prompt.trim(), duration, aspect, resolution, refs, voices)
                                } else {
                                    onGenerateClip(prompt.trim(), duration, aspect, resolution, refs, voices)
                                }
                            })
                        }
                    }
                    if (leftoverFrame) {
                        SheetChip("Camera", selected = false, enabled = canImagine, onClick = {
                            runCatching { takePicture.launch(captureUri) }
                        })
                        SheetChip("Gallery", selected = false, enabled = canImagine, onClick = {
                            pickGallery.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                            )
                        })
                        SheetChip("Files", selected = false, enabled = canImagine, onClick = {
                            pickFiles.launch("*/*")
                        })
                    }
                    if (leftoverFrame && onPickAudio != null) {
                        SheetChip("Audio", selected = false, enabled = canMutate, onClick = {
                            onPickAudio()
                            onDismiss()
                        })
                    }
                    if (leftoverFrame && onPickAudioFile != null) {
                        SheetChip("Files", selected = false, enabled = canMutate, onClick = {
                            onPickAudioFile()
                            onDismiss()
                        })
                    }
                    if (leftoverFrame && onPickLibraryAudio != null) {
                        board.libraryAudio.filter { it.deletedAt == null && it.src.isNotEmpty() }
                            .forEach { item ->
                                SheetChip(item.name.ifBlank { "Library" }, selected = false, enabled = canMutate, onClick = {
                                    onPickLibraryAudio(item)
                                    onDismiss()
                                })
                            }
                    }
                    if (canBurn) {
                        SheetChip("Burn", selected = false, enabled = canMutate, onClick = {
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
                    SheetChip("Restore", selected = false, enabled = canMutate, onClick = {
                        run(LyreRules.restorePicture(board, frame.id))
                    })
                    SheetChip("Remove", selected = false, enabled = canMutate, onClick = {
                        run(LyreRules.removeBeat(board, frame.id))
                    })
                    if (leftover) {
                        SheetChip(
                            if (frame.generating == true) "Generating…" else "Generate still",
                            selected = false,
                            enabled = canImagine && prompt.isNotBlank(),
                            onClick = { onGenerateStill(prompt.trim(), refs, aspect) },
                        )
                        SheetChip("Camera", selected = false, enabled = canImagine, onClick = {
                            runCatching { takePicture.launch(captureUri) }
                        })
                        SheetChip("Gallery", selected = false, enabled = canImagine, onClick = {
                            pickGallery.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                            )
                        })
                        SheetChip("Files", selected = false, enabled = canImagine, onClick = {
                            pickFiles.launch("*/*")
                        })
                        SheetChip("Hold", selected = false, enabled = canMutate, onClick = {
                            run(LyreRules.insertHold(board, frame.id))
                        })
                        if (!frame.generatingError.isNullOrBlank()) {
                            SheetChip("Retry", selected = false, enabled = canImagine, onClick = {
                                onGenerateStill(prompt.trim(), refs, aspect)
                            })
                        }
                    }
                }
            }
            if (leftover && tab == "clip") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SheetChip("6s", selected = duration == 6, enabled = canImagine && !locked) { duration = 6 }
                    SheetChip("10s", selected = duration == 10, enabled = canImagine && !locked) { duration = 10 }
                    SheetChip("16:9", selected = aspect == "16:9", enabled = canImagine && !locked) { aspect = "16:9" }
                    SheetChip("9:16", selected = aspect == "9:16", enabled = canImagine && !locked) { aspect = "9:16" }
                    SheetChip("1:1", selected = aspect == "1:1", enabled = canImagine && !locked) { aspect = "1:1" }
                    SheetChip("720p", selected = resolution == "720p", enabled = canImagine && !locked) {
                        resolution = "720p"
                    }
                    SheetChip("480p", selected = resolution == "480p", enabled = canImagine && !locked) {
                        resolution = "480p"
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SheetChip(
                        "Refs ${refs.size}/${LyreImagine.MAX_REFS}",
                        selected = false,
                        enabled = canImagine && refs.size < LyreImagine.MAX_REFS,
                        onClick = {
                            pickRef.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                    )
                    GROK_VOICES.forEach { v ->
                        val on = voices.any { it.equals(v.id, ignoreCase = true) }
                        SheetChip(v.label, selected = on, enabled = canImagine) {
                            voices = if (on) {
                                voices.filterNot { it.equals(v.id, ignoreCase = true) }
                            } else {
                                (voices + v.id).distinct().take(LyreImagine.MAX_VOICES)
                            }
                        }
                    }
                }
            } else if (leftover && tab == "picture") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SheetChip("16:9", selected = aspect == "16:9", enabled = canImagine) { aspect = "16:9" }
                    SheetChip("9:16", selected = aspect == "9:16", enabled = canImagine) { aspect = "9:16" }
                    SheetChip("1:1", selected = aspect == "1:1", enabled = canImagine) { aspect = "1:1" }
                    SheetChip(
                        "Refs ${refs.size}/${LyreImagine.MAX_REFS}",
                        selected = false,
                        enabled = canImagine && refs.size < LyreImagine.MAX_REFS,
                        onClick = {
                            pickRef.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                    )
                }
            }
            val err = if (tab == "clip") frame.videoGeneratingError else frame.generatingError
            if (!err.isNullOrBlank()) {
                Text(
                    err,
                    color = GrokifyColors.GlowRose,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SheetChip(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label, fontSize = 12.sp, maxLines = 1) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = GrokifyColors.GlowRose.copy(alpha = 0.22f),
            selectedLabelColor = GrokifyColors.GlowRose,
            containerColor = GrokifyColors.PanelSoft,
            labelColor = GrokifyColors.TextPrimary,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = GrokifyColors.PanelBorder,
            selectedBorderColor = GrokifyColors.GlowRose,
        ),
    )
}

@Composable
private fun imagineFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = GrokifyColors.TextPrimary,
    unfocusedTextColor = GrokifyColors.TextPrimary,
    focusedBorderColor = GrokifyColors.GlowRose,
    unfocusedBorderColor = GrokifyColors.PanelBorder,
    cursorColor = GrokifyColors.GlowRose,
    focusedContainerColor = GrokifyColors.PanelSoft,
    unfocusedContainerColor = GrokifyColors.PanelSoft,
    disabledTextColor = GrokifyColors.TextDim,
    disabledBorderColor = GrokifyColors.PanelBorder,
    disabledContainerColor = GrokifyColors.Panel,
)
