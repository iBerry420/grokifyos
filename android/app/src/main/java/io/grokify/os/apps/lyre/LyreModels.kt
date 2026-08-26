package io.grokify.os.apps.lyre

import org.json.JSONObject
import java.io.File

data class LyreProject(
    val id: String,
    val name: String,
    val boardId: String,
    val visibility: String = "private",
    val isOdysseus: Boolean = false,
    val watchToken: String? = null,
    val compiledKey: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

data class AudioClip(
    val id: String,
    val src: String,
    val name: String,
    val offsetSec: Double,
    val durationSec: Double,
    val trimInSec: Double? = null,
    val extra: JSONObject? = null,
)

data class LayerClip(
    val id: String,
    val src: String,
    val name: String,
    val startSec: Double,
    val durationSec: Double,
    val trimInSec: Double? = null,
    val sourceDurationSec: Double? = null,
    val origSrc: String? = null,
    val origDurationSec: Double? = null,
    val linkedFrameId: String? = null,
    val generating: Boolean? = null,
    val waveform: Any? = null,
    val volume: Double? = null,
    val envelope: JSONObject? = null,
    val extra: JSONObject? = null,
)

data class MediaLayer(
    val id: String,
    val kind: String,
    val name: String,
    val clips: List<LayerClip>,
    val heightPx: Double? = null,
    val extra: JSONObject? = null,
)

data class VideoGenLock(
    val duration: Double,
    val aspectRatio: String,
    val resolution: String,
    val locked: Boolean? = null,
    val extra: JSONObject? = null,
)

data class Frame(
    val id: String,
    val src: String,
    val caption: String,
    val durationSec: Double,
    val videoPrompt: String? = null,
    val dialogue: String? = null,
    val notes: String? = null,
    val videoSrc: String? = null,
    val origVideoSrc: String? = null,
    val origVideoDurationSec: Double? = null,
    val videoInSec: Double? = null,
    val videoOutSec: Double? = null,
    val videoDurationSec: Double? = null,
    val videoFps: Double? = null,
    val videoMuted: Boolean? = null,
    val audioClips: List<AudioClip>? = null,
    val videoRefSrcs: List<String>? = null,
    val videoVoices: List<String>? = null,
    val generating: Boolean? = null,
    val generatingError: String? = null,
    val videoGenerating: Boolean? = null,
    val videoGeneratingError: String? = null,
    val uploading: Boolean? = null,
    val createdAt: Long? = null,
    val extra: JSONObject? = null,
)

data class Scene(
    val id: String,
    val title: String,
    val book: String,
    val durationTargetSec: Double,
    val logline: String,
    val dialogue: String,
    val notes: String,
    val summary: String? = null,
    val frames: List<Frame>,
    val extra: JSONObject? = null,
)

data class RefImage(
    val id: String,
    val src: String,
    val caption: String,
    val videoPrompt: String? = null,
    val dialogue: String? = null,
    val notes: String? = null,
    val videoSrc: String? = null,
    val origVideoSrc: String? = null,
    val origVideoDurationSec: Double? = null,
    val videoInSec: Double? = null,
    val videoOutSec: Double? = null,
    val videoDurationSec: Double? = null,
    val videoFps: Double? = null,
    val videoMuted: Boolean? = null,
    val audioClips: List<AudioClip>? = null,
    val videoRefSrcs: List<String>? = null,
    val videoVoices: List<String>? = null,
    val fromSceneId: String? = null,
    val fromSceneTitle: String? = null,
    val fromFrameId: String? = null,
    val fromIndex: Int? = null,
    val holdSec: Double? = null,
    val generating: Boolean? = null,
    val generatingError: String? = null,
    val videoGenerating: Boolean? = null,
    val videoGeneratingError: String? = null,
    val uploading: Boolean? = null,
    val createdAt: Long? = null,
    val extra: JSONObject? = null,
)

data class RefFolder(
    val id: String,
    val name: String,
    val images: List<RefImage>,
    val extra: JSONObject? = null,
)

data class LibraryAudio(
    val id: String,
    val src: String,
    val name: String,
    val durationSec: Double,
    val waveform: Any? = null,
    val createdAt: Long? = null,
    val deletedAt: Long? = null,
    val uploading: Boolean? = null,
    val extra: JSONObject? = null,
)

data class LibraryVideo(
    val id: String,
    val src: String,
    val name: String,
    val durationSec: Double,
    val createdAt: Long? = null,
    val deletedAt: Long? = null,
    val uploading: Boolean? = null,
    val extra: JSONObject? = null,
)

data class MoviePart(
    val clipId: String,
    val src: String,
    val durationSec: Double,
    val extra: JSONObject? = null,
)

data class BoardMovie(
    val src: String,
    val durationSec: Double,
    val playDurationSec: Double? = null,
    val fps: Double? = null,
    val origSrc: String? = null,
    val parts: List<MoviePart>,
    val extra: JSONObject? = null,
)

data class BoardData(
    val title: String,
    val brainstorm: String,
    val scenes: List<Scene>,
    val activeSceneId: String,
    val refFolders: List<RefFolder>,
    val activeFolderId: String,
    val videoGen: VideoGenLock? = null,
    val videoLayers: List<MediaLayer> = emptyList(),
    val audioLayers: List<MediaLayer> = emptyList(),
    val libraryAudio: List<LibraryAudio> = emptyList(),
    val libraryVideo: List<LibraryVideo> = emptyList(),
    val movie: BoardMovie? = null,
    val ui: JSONObject? = null,
    val extra: JSONObject? = null,
    val source: JSONObject = JSONObject(),
)

enum class CutKind {
    STITCH,
    POP,
    TRIM,
    MUTE,
    SPLIT,
    EXTRACT,
    BURN_AUDIO,
}

data class AudioBed(
    val file: File,
    val startSec: Double,
    val durationSec: Double,
)

data class CutPlan(
    val kind: CutKind,
    val movieKey: String? = null,
    val clipKey: String? = null,
    val dropLast: Boolean = false,
    val keepSec: Double? = null,
    val trimInSec: Double? = null,
    val trimOutSec: Double? = null,
    val splitAtSec: Double? = null,
    val beds: List<AudioBed> = emptyList(),
)

data class RuleResult(val board: BoardData, val plan: CutPlan?)

fun lyreProjectFromJson(obj: JSONObject): LyreProject {
    return LyreProject(
        id = obj.optString("id"),
        name = obj.optString("name"),
        boardId = obj.optString("board_id"),
        visibility = obj.optString("visibility").ifBlank { "private" },
        isOdysseus = jsonTruthy(obj, "is_odysseus"),
        watchToken = obj.optStringOrNull("watch_token"),
        compiledKey = obj.optStringOrNull("compiled_key"),
        createdAt = obj.optStringOrNull("created_at"),
        updatedAt = obj.optStringOrNull("updated_at"),
    )
}

fun lyreProjectsFromJson(obj: JSONObject): List<LyreProject> {
    val arr = obj.optJSONArray("projects") ?: return emptyList()
    val out = ArrayList<LyreProject>(arr.length())
    for (i in 0 until arr.length()) {
        val row = arr.optJSONObject(i) ?: continue
        out.add(lyreProjectFromJson(row))
    }
    return out
}

internal fun jsonTruthy(obj: JSONObject, key: String): Boolean {
    if (!obj.has(key) || obj.isNull(key)) return false
    return when (val v = obj.get(key)) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> v == "1" || v.equals("true", ignoreCase = true)
        else -> false
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val s = optString(key)
    return s.takeIf { it.isNotEmpty() }
}
