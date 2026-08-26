package io.grokify.os.apps.lyre

import java.io.File
import java.util.UUID
import kotlin.math.max
import org.json.JSONObject

/** Pure leftover/movie-prefix JSON rules. No cutter, IO, or duration probe. */
object LyreRules {
    fun stitch(board: BoardData, clipId: String): RuleResult {
        val clip = findVideoClip(board, clipId) ?: return RuleResult(board, null)
        if (!LyreMovie.canStitchClip(clipId, clip.src, board.videoLayers, board.movie)) {
            return RuleResult(board, null)
        }
        val live = LyreMovie.resolvedMovie(board.movie, board.videoLayers) ?: return RuleResult(board, null)
        val part = MoviePart(clipId = clip.id, src = clip.src, durationSec = clip.durationSec)
        val parts = when {
            live.parts.isNotEmpty() -> live.parts + part
            else -> {
                val first = LyreMovie.orderedVideoClips(board.videoLayers).firstOrNull()
                if (first == null || first.id == clip.id) return RuleResult(board, null)
                listOf(MoviePart(first.id, first.src, first.durationSec), part)
            }
        }
        val compiled = movieObjectKey(board, "movie.mp4")
        val fps = clip.linkedFrameId?.let { findFrame(board, it)?.videoFps } ?: live.fps
        val movie = live.copy(
            src = compiled,
            parts = parts,
            fps = fps,
            origSrc = if (isBurnSrc(live.src)) null else live.origSrc,
        )
        return RuleResult(
            board.copy(movie = movie),
            CutPlan(kind = CutKind.STITCH, movieKey = compiled, clipKey = clip.src, dropLast = true),
        )
    }

    fun pop(board: BoardData): RuleResult {
        val movie = board.movie ?: return RuleResult(board, null)
        if (movie.parts.size <= 1) return RuleResult(board, null)
        val remaining = movie.parts.dropLast(1)
        val compiled = movieObjectKey(board, "movie.mp4")
        val wasBurn = isBurnSrc(movie.src)
        val newMovie = if (remaining.size == 1) {
            val only = remaining.first()
            movie.copy(
                src = only.src,
                durationSec = only.durationSec,
                fps = null,
                playDurationSec = null,
                origSrc = null,
                parts = remaining,
            )
        } else {
            val pictureSrc = when {
                wasBurn -> movie.origSrc?.takeIf { it.isNotEmpty() } ?: compiled
                movie.src.endsWith("/movie.mp4") || movie.src.endsWith("movie.mp4") -> movie.src
                else -> compiled
            }
            movie.copy(
                src = pictureSrc,
                origSrc = null,
                parts = remaining,
            )
        }
        return RuleResult(
            board.copy(movie = newMovie),
            CutPlan(kind = CutKind.POP, movieKey = newMovie.src),
        )
    }

    fun trim(board: BoardData, clipId: String, inn: Double, out: Double): RuleResult {
        val clip = leftoverVideoClip(board, clipId) ?: return RuleResult(board, null)
        val start = max(0.0, inn)
        if (out <= start) return RuleResult(board, null)
        val length = max(0.1, out - start)
        val frame = clip.linkedFrameId?.let { findFrame(board, it) }
        val (backed, backedFrame) = withVideoOrig(clip, frame)
        val nextClip = backed.copy(trimInSec = start, durationSec = length)
        val nextFrame = backedFrame?.copy(
            videoSrc = nextClip.src,
            videoInSec = start,
            videoOutSec = out,
            durationSec = length,
        )
        return RuleResult(
            retimeLinkedClips(replaceClipAndFrame(board, nextClip, nextFrame)),
            CutPlan(kind = CutKind.TRIM, clipKey = nextClip.src, trimInSec = start, trimOutSec = out),
        )
    }

    fun mute(board: BoardData, clipId: String): RuleResult {
        val clip = leftoverVideoClip(board, clipId) ?: return RuleResult(board, null)
        val frame = clip.linkedFrameId?.let { findFrame(board, it) }
        val (backed, backedFrame) = withVideoOrig(clip, frame)
        val nextFrame = backedFrame?.copy(videoSrc = backed.src, videoMuted = true)
            ?: return RuleResult(board, null)
        return RuleResult(
            replaceClipAndFrame(board, backed, nextFrame),
            CutPlan(kind = CutKind.MUTE, clipKey = backed.src),
        )
    }

    fun split(board: BoardData, clipId: String, atSec: Double): RuleResult {
        val clip = leftoverVideoClip(board, clipId) ?: return RuleResult(board, null)
        val frame = clip.linkedFrameId?.let { findFrame(board, it) } ?: return RuleResult(board, null)
        val inn = clip.trimInSec ?: frame.videoInSec ?: 0.0
        val native = jsOr(clip.sourceDurationSec, jsOr(frame.videoDurationSec, inn + clip.durationSec))
        val out = frame.videoOutSec ?: (inn + clip.durationSec)
        if (atSec <= inn || atSec >= out || atSec >= native) return RuleResult(board, null)
        val leftLen = max(0.1, atSec - inn)
        val rightLen = max(0.1, out - atSec)
        val (backed, backedFrame) = withVideoOrig(clip, frame)
        val base = backedFrame ?: frame
        val leftClip = backed.copy(durationSec = leftLen, trimInSec = inn)
        val leftFrame = base.copy(
            videoSrc = leftClip.src,
            videoInSec = inn,
            videoOutSec = atSec,
            durationSec = leftLen,
        )
        val rightFrame = base.copy(
            id = newId("fr_"),
            videoSrc = backed.src,
            videoInSec = atSec,
            videoOutSec = out,
            durationSec = rightLen,
            origVideoSrc = leftClip.origSrc ?: base.origVideoSrc,
            origVideoDurationSec = jsOr(leftClip.origDurationSec, jsOr(base.origVideoDurationSec, 0.0)),
        )
        val rightClip = backed.copy(
            id = newId("lc_"),
            startSec = clip.startSec + leftLen,
            durationSec = rightLen,
            trimInSec = atSec,
            linkedFrameId = rightFrame.id,
        )
        var next = replaceClipAndFrame(board, leftClip, leftFrame)
        next = insertFrameAfter(next, frame.id, rightFrame)
        next = insertClipAfter(next, leftClip.id, rightClip)
        return RuleResult(
            retimeLinkedClips(next),
            CutPlan(kind = CutKind.SPLIT, clipKey = backed.src, splitAtSec = atSec),
        )
    }

    fun insertHold(board: BoardData, afterFrameId: String?): RuleResult {
        val hold = Frame(
            id = newId("fr_"),
            src = "",
            caption = "Hold",
            durationSec = 2.0,
        )
        return insertLeftover(board, afterFrameId, hold, clip = null)
    }

    /** Leftover-only. Dual-writes [clip] when attached. */
    fun insertLeftover(
        board: BoardData,
        afterFrameId: String?,
        frame: Frame,
        clip: LayerClip?,
    ): RuleResult {
        if (afterFrameId != null) {
            val after = findFrame(board, afterFrameId) ?: return RuleResult(board, null)
            if (LyreMovie.frameInMovie(board.movie, board.videoLayers, after.id)) {
                return RuleResult(board, null)
            }
        }
        if (LyreMovie.frameInMovie(board.movie, board.videoLayers, frame.id)) {
            return RuleResult(board, null)
        }
        var next = board.copy(scenes = insertFrameInScenes(board, afterFrameId, frame))
        if (clip != null) next = appendVideoClip(next, clip)
        return RuleResult(retimeLinkedClips(next), null)
    }

    fun setStill(board: BoardData, frameId: String, src: String, origSrc: String? = null): RuleResult {
        val frame = leftoverFrame(board, frameId) ?: return RuleResult(board, null)
        if (src.isEmpty()) return RuleResult(board, null)
        if (src == frame.src) {
            return RuleResult(
                replaceFrame(board, frame.copy(generating = false, generatingError = null)),
                null,
            )
        }
        val extra = JSONObject(frame.extra?.toString() ?: "{}")
        val existingOrig = extra.optString("origSrc").takeIf { it.isNotEmpty() }
        val orig = existingOrig ?: origSrc?.takeIf { it.isNotEmpty() } ?: frame.src
        if (orig.isNotEmpty() && orig != src) extra.put("origSrc", orig)
        val next = frame.copy(
            src = src,
            extra = extra,
            generating = false,
            generatingError = null,
        )
        return RuleResult(replaceFrame(board, next), null)
    }

    fun attachClip(
        board: BoardData,
        frameId: String,
        src: String,
        durationSec: Double,
        fps: Double?,
    ): RuleResult {
        val frame = leftoverFrame(board, frameId) ?: return RuleResult(board, null)
        if (src.isEmpty()) return RuleResult(board, null)
        val existing = board.videoLayers.asSequence()
            .flatMap { it.clips.asSequence() }
            .firstOrNull { it.linkedFrameId == frameId }
        if (existing != null) {
            if (LyreMovie.clipInMovie(board.movie, existing.id, board.videoLayers)) {
                return RuleResult(board, null)
            }
            val (backed, backedFrame) = withVideoOrig(existing, frame)
            val nextClip = backed.copy(
                src = src,
                durationSec = durationSec,
                sourceDurationSec = durationSec,
                trimInSec = 0.0,
            )
            val nextFrame = (backedFrame ?: frame).copy(
                videoSrc = src,
                videoDurationSec = durationSec,
                durationSec = durationSec,
                videoFps = fps,
                videoInSec = 0.0,
                videoOutSec = durationSec,
                videoGenerating = false,
                videoGeneratingError = null,
            )
            return RuleResult(retimeLinkedClips(replaceClipAndFrame(board, nextClip, nextFrame)), null)
        }
        val clip = LayerClip(
            id = newId("lc_"),
            src = src,
            name = frame.caption.ifBlank { "Clip" },
            startSec = 0.0,
            durationSec = durationSec,
            sourceDurationSec = durationSec,
            linkedFrameId = frame.id,
        )
        val nextFrame = frame.copy(
            videoSrc = src,
            videoDurationSec = durationSec,
            durationSec = durationSec,
            videoFps = fps,
            videoInSec = 0.0,
            videoOutSec = durationSec,
            videoGenerating = false,
            videoGeneratingError = null,
        )
        val next = appendVideoClip(replaceFrame(board, nextFrame), clip)
        return RuleResult(retimeLinkedClips(next), null)
    }

    fun patchLeftoverFrame(board: BoardData, frameId: String, transform: (Frame) -> Frame): RuleResult {
        val frame = leftoverFrame(board, frameId) ?: return RuleResult(board, null)
        return RuleResult(replaceFrame(board, transform(frame)), null)
    }

    fun dumpScene(board: BoardData, sceneId: String): RuleResult {
        val index = board.scenes.indexOfFirst { it.id == sceneId }
        if (index < 0) return RuleResult(board, null)
        val scene = board.scenes[index]
        val images = scene.frames.mapIndexed { i, frame -> frameToBin(frame, scene, i) }
        val dropped = scene.frames.map { it.id }.toSet()
        val remaining = board.scenes.filterIndexed { i, _ -> i != index }
        val scenes = remaining.ifEmpty { listOf(emptyScene()) }
        val active = when {
            board.activeSceneId != sceneId -> board.activeSceneId
            else -> scenes.first().id
        }
        val video = dropClipsForFrames(board.videoLayers, dropped)
        val audio = dropClipsForFrames(board.audioLayers, dropped)
        return RuleResult(
            retimeLinkedClips(
                board.copy(
                    scenes = scenes,
                    activeSceneId = active,
                    videoLayers = video,
                    audioLayers = audio,
                    movie = pruneMovieParts(board.movie, video),
                    refFolders = stashBin(board, images),
                ),
            ),
            null,
        )
    }

    fun restoreClip(board: BoardData, clipId: String): RuleResult {
        val clip = leftoverVideoClip(board, clipId) ?: return RuleResult(board, null)
        val frame = clip.linkedFrameId?.let { findFrame(board, it) }
        val backup = LyreClip.clipBackup(clip, frame) ?: return RuleResult(board, null)
        val liveSrc = clip.src.ifEmpty { frame?.videoSrc.orEmpty() }
        val liveDur = jsOr(clip.sourceDurationSec, clip.durationSec)
        val nextClip = clip.copy(
            src = backup.first,
            origSrc = liveSrc,
            origDurationSec = liveDur,
            durationSec = backup.second,
            sourceDurationSec = backup.second,
        )
        val nextFrame = frame?.copy(
            videoSrc = nextClip.src,
            origVideoSrc = liveSrc,
            origVideoDurationSec = liveDur,
            videoDurationSec = nextClip.sourceDurationSec,
            durationSec = nextClip.durationSec,
        )
        return RuleResult(retimeLinkedClips(replaceClipAndFrame(board, nextClip, nextFrame)), null)
    }

    fun restorePicture(board: BoardData, frameId: String): RuleResult {
        val frame = findFrame(board, frameId) ?: return RuleResult(board, null)
        val extra = frame.extra ?: return RuleResult(board, null)
        if (!extra.has("origSrc") || extra.isNull("origSrc")) return RuleResult(board, null)
        val orig = extra.optString("origSrc")
        if (orig.isEmpty() || orig == frame.src) return RuleResult(board, null)
        val nextExtra = JSONObject(extra.toString()).put("origSrc", frame.src)
        val next = frame.copy(src = orig, extra = nextExtra)
        return RuleResult(replaceFrame(board, next), null)
    }

    fun removeClip(board: BoardData, clipId: String): RuleResult {
        val clip = leftoverVideoClip(board, clipId) ?: return RuleResult(board, null)
        val frame = clip.linkedFrameId?.let { findFrame(board, it) }
        val nextFrame = frame?.copy(
            videoSrc = null,
            videoInSec = null,
            videoOutSec = null,
            videoDurationSec = null,
            videoFps = null,
            videoMuted = null,
        )
        val layers = board.videoLayers.map { layer ->
            layer.copy(clips = layer.clips.filterNot { it.id == clipId })
        }
        val next = board.copy(
            videoLayers = layers,
            scenes = if (nextFrame == null) board.scenes else replaceFrameInScenes(board.scenes, nextFrame),
        )
        return RuleResult(retimeLinkedClips(next), null)
    }

    fun removeBeat(board: BoardData, frameId: String): RuleResult {
        val located = locateFrame(board, frameId) ?: return RuleResult(board, null)
        if (LyreMovie.frameInMovie(board.movie, board.videoLayers, frameId)) {
            return RuleResult(board, null)
        }
        val (sceneIndex, frameIndex, scene, frame) = located
        val image = frameToBin(frame, scene, frameIndex)
        val scenes = board.scenes.mapIndexed { i, sc ->
            if (i != sceneIndex) sc else sc.copy(frames = sc.frames.filterNot { it.id == frameId })
        }
        val video = dropClipsForFrames(board.videoLayers, setOf(frameId))
        val audio = dropClipsForFrames(board.audioLayers, setOf(frameId))
        return RuleResult(
            retimeLinkedClips(
                board.copy(
                    scenes = scenes,
                    videoLayers = video,
                    audioLayers = audio,
                    movie = pruneMovieParts(board.movie, video),
                    refFolders = stashBin(board, listOf(image)),
                ),
            ),
            null,
        )
    }

    fun extractAudio(board: BoardData, clipId: String): RuleResult {
        val clip = leftoverVideoClip(board, clipId) ?: return RuleResult(board, null)
        if (clip.src.isEmpty()) return RuleResult(board, null)
        val audioId = newId("lc_")
        val audioClip = LayerClip(
            id = audioId,
            src = audioKey(board, audioId),
            name = clip.name.ifBlank { "Audio" },
            startSec = clip.startSec,
            durationSec = clip.durationSec,
            sourceDurationSec = jsOr(clip.sourceDurationSec, clip.durationSec),
            linkedFrameId = clip.linkedFrameId,
        )
        val layers = board.audioLayers.toMutableList()
        if (layers.isEmpty()) {
            layers.add(MediaLayer(id = newId("ly_"), kind = "audio", name = "Audio", clips = listOf(audioClip)))
        } else {
            val last = layers.last()
            layers[layers.lastIndex] = last.copy(clips = last.clips + audioClip)
        }
        return RuleResult(
            board.copy(audioLayers = layers),
            CutPlan(kind = CutKind.EXTRACT, clipKey = clip.src),
        )
    }

    fun burnAudio(board: BoardData): RuleResult {
        val movie = board.movie ?: return RuleResult(board, null)
        val beds = board.audioLayers.flatMap { it.clips }.mapNotNull { clip ->
            if (clip.src.isEmpty()) return@mapNotNull null
            AudioBed(file = File(clip.src), startSec = clip.startSec, durationSec = clip.durationSec)
        }
        if (beds.isEmpty()) return RuleResult(board, null)
        val burnKey = movieObjectKey(board, "movie.burn.mp4")
        val pictureSrc = if (isBurnSrc(movie.src)) {
            movie.origSrc?.takeIf { it.isNotEmpty() } ?: movieObjectKey(board, "movie.mp4")
        } else {
            movie.src
        }
        val next = movie.copy(
            src = burnKey,
            origSrc = if (isBurnSrc(movie.src)) movie.origSrc ?: pictureSrc else pictureSrc,
        )
        return RuleResult(
            board.copy(movie = next),
            CutPlan(kind = CutKind.BURN_AUDIO, movieKey = burnKey, beds = beds),
        )
    }

    private data class LocatedFrame(
        val sceneIndex: Int,
        val frameIndex: Int,
        val scene: Scene,
        val frame: Frame,
    )

    fun insertAudio(
        board: BoardData,
        frameId: String,
        src: String,
        name: String,
        durationSec: Double,
    ): RuleResult {
        if (src.isEmpty()) return RuleResult(board, null)
        if (findFrame(board, frameId) == null) return RuleResult(board, null)
        if (LyreMovie.frameInMovie(board.movie, board.videoLayers, frameId)) {
            return RuleResult(board, null)
        }
        val linked = board.videoLayers.asSequence()
            .flatMap { it.clips.asSequence() }
            .firstOrNull { it.linkedFrameId == frameId }
        val startSec = linked?.startSec ?: LyreClip.clipOf(board.scenes, frameId)?.start ?: 0.0
        val audioClip = LayerClip(
            id = newId("lc_"),
            src = src,
            name = name.ifBlank { "Audio" },
            startSec = startSec,
            durationSec = durationSec,
            sourceDurationSec = durationSec,
            linkedFrameId = frameId,
        )
        return RuleResult(appendAudioClip(board, audioClip), null)
    }

    fun addScene(board: BoardData): RuleResult {
        val scene = Scene(
            id = newId("sc_"),
            title = "Scene ${board.scenes.size + 1}",
            book = "",
            durationTargetSec = 0.0,
            logline = "",
            dialogue = "",
            notes = "",
            frames = emptyList(),
        )
        return RuleResult(
            board.copy(scenes = board.scenes + scene, activeSceneId = scene.id),
            null,
        )
    }

    fun renameScene(board: BoardData, sceneId: String, title: String): RuleResult {
        val index = board.scenes.indexOfFirst { it.id == sceneId }
        if (index < 0) return RuleResult(board, null)
        val name = title.trim()
        if (name.isEmpty() || name == board.scenes[index].title) return RuleResult(board, null)
        val scenes = board.scenes.toMutableList()
        scenes[index] = scenes[index].copy(title = name)
        return RuleResult(board.copy(scenes = scenes), null)
    }

    fun moveScene(board: BoardData, sceneId: String, toIndex: Int): RuleResult {
        val from = board.scenes.indexOfFirst { it.id == sceneId }
        if (from < 0 || board.scenes.isEmpty()) return RuleResult(board, null)
        val dest = toIndex.coerceIn(0, board.scenes.lastIndex)
        if (from == dest) return RuleResult(board, null)
        val scenes = board.scenes.toMutableList()
        val scene = scenes.removeAt(from)
        scenes.add(dest, scene)
        return RuleResult(board.copy(scenes = scenes), null)
    }

    fun insertLibraryStill(board: BoardData, image: RefImage, afterFrameId: String?): RuleResult {
        if (!image.fromFrameId.isNullOrEmpty() || !image.fromSceneId.isNullOrEmpty()) {
            return RuleResult(board, null)
        }
        if (image.src.isEmpty() && image.videoSrc.isNullOrEmpty()) return RuleResult(board, null)
        val dur = image.holdSec?.takeIf { it > 0.0 && !it.isNaN() } ?: 2.0
        val frame = Frame(
            id = newId("fr_"),
            src = image.src,
            caption = image.caption.ifBlank { "Still" },
            durationSec = dur,
            videoPrompt = image.videoPrompt,
            dialogue = image.dialogue,
            notes = image.notes,
            videoSrc = image.videoSrc,
            origVideoSrc = image.origVideoSrc,
            origVideoDurationSec = image.origVideoDurationSec,
            videoInSec = image.videoInSec,
            videoOutSec = image.videoOutSec,
            videoDurationSec = image.videoDurationSec,
            videoFps = image.videoFps,
            videoMuted = image.videoMuted,
            audioClips = image.audioClips,
            videoRefSrcs = image.videoRefSrcs,
            videoVoices = image.videoVoices,
        )
        val clip = image.videoSrc?.takeIf { it.isNotEmpty() }?.let { src ->
            LayerClip(
                id = newId("lc_"),
                src = src,
                name = frame.caption.ifBlank { "Clip" },
                startSec = 0.0,
                durationSec = dur,
                sourceDurationSec = jsOr(image.videoDurationSec, dur),
                linkedFrameId = frame.id,
            )
        }
        return insertLeftover(board, afterFrameId, frame, clip)
    }

    fun insertLibraryVideo(board: BoardData, item: LibraryVideo, afterFrameId: String?): RuleResult {
        if (item.src.isEmpty() || item.deletedAt != null) return RuleResult(board, null)
        val dur = item.durationSec.takeIf { it > 0.0 && !it.isNaN() } ?: 0.1
        val frame = Frame(
            id = newId("fr_"),
            src = "",
            caption = item.name.ifBlank { "Clip" },
            durationSec = dur,
            videoSrc = item.src,
            videoDurationSec = dur,
            videoInSec = 0.0,
            videoOutSec = dur,
        )
        val clip = LayerClip(
            id = newId("lc_"),
            src = item.src,
            name = frame.caption,
            startSec = 0.0,
            durationSec = dur,
            sourceDurationSec = dur,
            linkedFrameId = frame.id,
        )
        return insertLeftover(board, afterFrameId, frame, clip)
    }

    fun deleteLibraryAudio(board: BoardData, audioId: String): RuleResult {
        if (board.libraryAudio.none { it.id == audioId }) return RuleResult(board, null)
        return RuleResult(board.copy(libraryAudio = board.libraryAudio.filterNot { it.id == audioId }), null)
    }

    fun deleteLibraryVideo(board: BoardData, videoId: String): RuleResult {
        if (board.libraryVideo.none { it.id == videoId }) return RuleResult(board, null)
        return RuleResult(board.copy(libraryVideo = board.libraryVideo.filterNot { it.id == videoId }), null)
    }

    fun deleteRefImage(board: BoardData, imageId: String): RuleResult {
        if (board.refFolders.none { folder -> folder.images.any { it.id == imageId } }) {
            return RuleResult(board, null)
        }
        val folders = board.refFolders.map { folder ->
            folder.copy(images = folder.images.filterNot { it.id == imageId })
        }
        return RuleResult(board.copy(refFolders = folders), null)
    }

    fun recycle(board: BoardData, imageId: String): RuleResult {
        val dumped = board.refFolders.asSequence()
            .flatMap { it.images.asSequence() }
            .firstOrNull { it.id == imageId }
            ?: return RuleResult(board, null)
        if (dumped.fromFrameId.isNullOrEmpty() && dumped.fromSceneId.isNullOrEmpty()) {
            return RuleResult(board, null)
        }
        val frameId = dumped.fromFrameId?.takeIf { findFrame(board, it) == null } ?: newId("fr_")
        val frame = refToFrame(dumped, frameId)
        val sceneId = dumped.fromSceneId?.takeIf { id -> board.scenes.any { it.id == id } }
            ?: board.scenes.lastOrNull()?.id
            ?: return RuleResult(board, null)
        val scenes = board.scenes.map { sc ->
            if (sc.id != sceneId) sc else sc.copy(frames = sc.frames + frame)
        }
        var next = board.copy(
            scenes = scenes,
            refFolders = board.refFolders.map { folder ->
                folder.copy(images = folder.images.filterNot { it.id == imageId })
            },
        )
        val src = frame.videoSrc
        if (!src.isNullOrEmpty()) {
            next = appendVideoClip(
                next,
                LayerClip(
                    id = newId("lc_"),
                    src = src,
                    name = frame.caption.ifBlank { "Clip" },
                    startSec = 0.0,
                    durationSec = jsOr(frame.videoDurationSec, frame.durationSec),
                    sourceDurationSec = jsOr(frame.videoDurationSec, frame.durationSec),
                    origSrc = frame.origVideoSrc,
                    origDurationSec = frame.origVideoDurationSec,
                    linkedFrameId = frame.id,
                ),
            )
        }
        if (LyreMovie.frameInMovie(next.movie, next.videoLayers, frame.id)) {
            return RuleResult(board, null)
        }
        return RuleResult(retimeLinkedClips(next), null)
    }

    internal fun leftoverFrame(board: BoardData, frameId: String): Frame? {
        val frame = findFrame(board, frameId) ?: return null
        if (LyreMovie.frameInMovie(board.movie, board.videoLayers, frame.id)) return null
        return frame
    }

    private fun appendAudioClip(board: BoardData, audioClip: LayerClip): BoardData {
        val layers = board.audioLayers.toMutableList()
        if (layers.isEmpty()) {
            layers.add(MediaLayer(id = newId("ly_"), kind = "audio", name = "Audio", clips = listOf(audioClip)))
        } else {
            val last = layers.last()
            layers[layers.lastIndex] = last.copy(clips = last.clips + audioClip)
        }
        return board.copy(audioLayers = layers)
    }

    private fun leftoverVideoClip(board: BoardData, clipId: String): LayerClip? {
        val clip = findVideoClip(board, clipId) ?: return null
        if (LyreMovie.clipInMovie(board.movie, clip.id, board.videoLayers)) return null
        return clip
    }

    private fun insertFrameInScenes(board: BoardData, afterFrameId: String?, frame: Frame): List<Scene> {
        return if (afterFrameId == null) {
            if (board.scenes.isEmpty()) {
                listOf(emptyScene().copy(frames = listOf(frame)))
            } else {
                board.scenes.mapIndexed { i, sc ->
                    if (i == board.scenes.lastIndex) sc.copy(frames = sc.frames + frame) else sc
                }
            }
        } else {
            board.scenes.map { sc ->
                val idx = sc.frames.indexOfFirst { it.id == afterFrameId }
                if (idx < 0) sc else sc.copy(frames = sc.frames.toMutableList().apply { add(idx + 1, frame) })
            }
        }
    }

    private fun appendVideoClip(board: BoardData, clip: LayerClip): BoardData {
        val layers = board.videoLayers.toMutableList()
        val idx = layers.indexOfLast { it.kind == "video" }
        if (idx < 0) {
            layers.add(MediaLayer(id = newId("ly_"), kind = "video", name = "Video", clips = listOf(clip)))
        } else {
            val layer = layers[idx]
            layers[idx] = layer.copy(clips = layer.clips + clip)
        }
        return board.copy(videoLayers = layers)
    }

    private fun findVideoClip(board: BoardData, clipId: String): LayerClip? {
        return board.videoLayers.asSequence()
            .filter { it.kind == "video" }
            .flatMap { it.clips.asSequence() }
            .firstOrNull { it.id == clipId }
    }

    private fun findFrame(board: BoardData, frameId: String): Frame? {
        return board.scenes.asSequence().flatMap { it.frames.asSequence() }.firstOrNull { it.id == frameId }
    }

    private fun locateFrame(board: BoardData, frameId: String): LocatedFrame? {
        board.scenes.forEachIndexed { si, scene ->
            val fi = scene.frames.indexOfFirst { it.id == frameId }
            if (fi >= 0) return LocatedFrame(si, fi, scene, scene.frames[fi])
        }
        return null
    }

    private fun withVideoOrig(clip: LayerClip, frame: Frame?): Pair<LayerClip, Frame?> {
        val nextClip = if (clip.origSrc.isNullOrEmpty() && clip.src.isNotEmpty()) {
            clip.copy(origSrc = clip.src, origDurationSec = jsOr(clip.sourceDurationSec, clip.durationSec))
        } else {
            clip
        }
        val nextFrame = if (frame != null && frame.origVideoSrc.isNullOrEmpty() && !frame.videoSrc.isNullOrEmpty()) {
            frame.copy(origVideoSrc = frame.videoSrc, origVideoDurationSec = frame.videoDurationSec)
        } else {
            frame
        }
        return nextClip to nextFrame
    }

    private fun replaceClipAndFrame(board: BoardData, clip: LayerClip, frame: Frame?): BoardData {
        val layers = board.videoLayers.map { layer ->
            layer.copy(clips = layer.clips.map { if (it.id == clip.id) clip else it })
        }
        return board.copy(
            videoLayers = layers,
            scenes = if (frame == null) board.scenes else replaceFrameInScenes(board.scenes, frame),
        )
    }

    private fun replaceFrame(board: BoardData, frame: Frame): BoardData {
        return board.copy(scenes = replaceFrameInScenes(board.scenes, frame))
    }

    private fun replaceFrameInScenes(scenes: List<Scene>, frame: Frame): List<Scene> {
        return scenes.map { scene ->
            scene.copy(frames = scene.frames.map { if (it.id == frame.id) frame else it })
        }
    }

    private fun insertFrameAfter(board: BoardData, afterId: String, frame: Frame): BoardData {
        return board.copy(
            scenes = board.scenes.map { scene ->
                val idx = scene.frames.indexOfFirst { it.id == afterId }
                if (idx < 0) scene else scene.copy(
                    frames = scene.frames.toMutableList().apply { add(idx + 1, frame) },
                )
            },
        )
    }

    private fun insertClipAfter(board: BoardData, afterId: String, clip: LayerClip): BoardData {
        return board.copy(
            videoLayers = board.videoLayers.map { layer ->
                val idx = layer.clips.indexOfFirst { it.id == afterId }
                if (idx < 0) layer else layer.copy(
                    clips = layer.clips.toMutableList().apply { add(idx + 1, clip) },
                )
            },
        )
    }

    private fun dropClipsForFrames(layers: List<MediaLayer>, frameIds: Set<String>): List<MediaLayer> {
        return layers.map { layer ->
            layer.copy(clips = layer.clips.filterNot { it.linkedFrameId != null && it.linkedFrameId in frameIds })
        }
    }

    internal fun retimeLinkedClips(board: BoardData): BoardData {
        val startByFrame = LyreClip.movieClips(board.scenes).associate { it.frame.id to it.start }
        fun retime(layers: List<MediaLayer>): List<MediaLayer> {
            return layers.map { layer ->
                layer.copy(
                    clips = layer.clips.map { clip ->
                        val start = clip.linkedFrameId?.let { startByFrame[it] } ?: return@map clip
                        clip.copy(startSec = start)
                    },
                )
            }
        }
        return board.copy(
            videoLayers = retime(board.videoLayers),
            audioLayers = retime(board.audioLayers),
        )
    }

    private fun pruneMovieParts(movie: BoardMovie?, layers: List<MediaLayer>): BoardMovie? {
        if (movie == null) return null
        val live = layers.flatMap { it.clips }.map { it.id }.toSet()
        val parts = movie.parts.filter { it.clipId in live }
        if (parts.size == movie.parts.size) return movie
        return movie.copy(parts = parts)
    }

    private fun refToFrame(image: RefImage, id: String): Frame {
        return Frame(
            id = id,
            src = image.src,
            caption = image.caption,
            durationSec = image.holdSec?.takeIf { it > 0.0 && !it.isNaN() } ?: 2.0,
            videoPrompt = image.videoPrompt,
            dialogue = image.dialogue,
            notes = image.notes,
            videoSrc = image.videoSrc,
            origVideoSrc = image.origVideoSrc,
            origVideoDurationSec = image.origVideoDurationSec,
            videoInSec = image.videoInSec,
            videoOutSec = image.videoOutSec,
            videoDurationSec = image.videoDurationSec,
            videoFps = image.videoFps,
            videoMuted = image.videoMuted,
            audioClips = image.audioClips,
            videoRefSrcs = image.videoRefSrcs,
            videoVoices = image.videoVoices,
            generating = false,
            generatingError = null,
            videoGenerating = false,
            videoGeneratingError = null,
            uploading = image.uploading,
            createdAt = image.createdAt,
            extra = image.extra,
        )
    }

    private fun stashBin(board: BoardData, images: List<RefImage>): List<RefFolder> {
        if (images.isEmpty()) return board.refFolders
        if (board.refFolders.isEmpty()) {
            return listOf(RefFolder(id = "lib", name = "Library", images = images))
        }
        val folders = board.refFolders.toMutableList()
        val i = folders.indexOfFirst { it.id == board.activeFolderId }.takeIf { it >= 0 } ?: 0
        val folder = folders[i]
        folders[i] = folder.copy(images = folder.images + images)
        return folders
    }

    private fun frameToBin(frame: Frame, scene: Scene, index: Int): RefImage {
        return RefImage(
            id = frame.id,
            src = frame.src,
            caption = frame.caption,
            videoPrompt = frame.videoPrompt,
            dialogue = frame.dialogue,
            notes = frame.notes,
            videoSrc = frame.videoSrc,
            origVideoSrc = frame.origVideoSrc,
            origVideoDurationSec = frame.origVideoDurationSec,
            videoInSec = frame.videoInSec,
            videoOutSec = frame.videoOutSec,
            videoDurationSec = frame.videoDurationSec,
            videoFps = frame.videoFps,
            videoMuted = frame.videoMuted,
            audioClips = frame.audioClips,
            videoRefSrcs = frame.videoRefSrcs,
            videoVoices = frame.videoVoices,
            fromSceneId = scene.id,
            fromSceneTitle = scene.title,
            fromFrameId = frame.id,
            fromIndex = index,
            holdSec = frame.durationSec,
            generating = frame.generating,
            generatingError = frame.generatingError,
            videoGenerating = frame.videoGenerating,
            videoGeneratingError = frame.videoGeneratingError,
            uploading = frame.uploading,
            createdAt = frame.createdAt,
            extra = frame.extra,
        )
    }

    private fun emptyScene(): Scene {
        return Scene(
            id = "sc_1",
            title = "Scene 1",
            book = "",
            durationTargetSec = 0.0,
            logline = "",
            dialogue = "",
            notes = "",
            frames = emptyList(),
        )
    }

    private fun movieObjectKey(board: BoardData, file: String): String {
        val existing = board.movie?.src
        if (file == "movie.mp4" && existing != null && (existing.endsWith("/movie.mp4") || existing == "movie.mp4")) {
            return existing
        }
        val probes = buildList {
            existing?.let { add(it) }
            board.movie?.origSrc?.let { add(it) }
            board.videoLayers.forEach { layer -> layer.clips.forEach { add(it.src) } }
            board.scenes.forEach { scene ->
                scene.frames.forEach { frame ->
                    frame.videoSrc?.let { add(it) }
                    if (frame.src.isNotEmpty()) add(frame.src)
                }
            }
        }
        for (path in probes) {
            val match = BOARD_PREFIX.find(path) ?: continue
            return "${match.groupValues[1]}/$file"
        }
        return "boards/lyre/$file"
    }

    private fun audioKey(board: BoardData, clipId: String): String {
        val prefix = BOARD_PREFIX.find(movieObjectKey(board, "movie.mp4"))?.groupValues?.get(1) ?: "boards/lyre"
        return "$prefix/audio/$clipId.m4a"
    }

    private fun isBurnSrc(src: String): Boolean = src.endsWith("movie.burn.mp4")

    internal fun newId(prefix: String): String {
        val hex = UUID.randomUUID().toString().replace("-", "").take(8)
        return prefix + hex
    }

    private fun jsOr(value: Double?, fallback: Double): Double {
        return if (value != null && value != 0.0 && !value.isNaN()) value else fallback
    }

    private val BOARD_PREFIX = Regex("""^(boards/[^/]+)""")
}
