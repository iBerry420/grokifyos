package io.grokify.os.apps.lyre

import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

data class LyrePictureInsert(
    val board: BoardData,
    val frameId: String,
)

object LyreEdits {
    private const val ALPHA = "abcdefghijklmnopqrstuvwxyz0123456789"
    const val MIN_DUR = 0.1
    private val STILL_EXT = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp")
    private val VIDEO_EXT = setOf("mp4", "mov", "m4v", "webm", "mkv", "3gp")
    private val AUDIO_EXT = setOf("m4a", "aac", "mp3", "wav", "ogg", "flac", "opus", "aiff", "aif", "caf")

    fun newId(prefix: String, random: Random = Random.Default): String {
        val tail = CharArray(12) { ALPHA[random.nextInt(ALPHA.length)] }.concatToString()
        return "${prefix}_$tail"
    }

    fun leftoverStart(board: BoardData, playhead: Double): Double {
        val live = LyreMovie.resolvedMovie(board.movie, board.videoLayers)
        val movieEnd = if (live != null && live.src.isNotEmpty()) {
            LyreMovie.moviePlayDuration(live)
        } else {
            0.0
        }
        return max(playhead.coerceAtLeast(0.0), movieEnd)
    }

    fun addStillToScene(
        board: BoardData,
        sceneId: String,
        src: String,
        caption: String,
        durationSec: Double = 6.0,
        videoSrc: String? = null,
        videoDurationSec: Double? = null,
        random: Random = Random.Default,
    ): BoardData {
        val scene = board.scenes.firstOrNull { it.id == sceneId } ?: return board
        val frame = Frame(
            id = newId("fr", random),
            src = src,
            caption = caption,
            durationSec = durationSec.coerceAtLeast(0.1),
            videoSrc = videoSrc,
            videoDurationSec = videoDurationSec,
        )
        val scenes = board.scenes.map { sc ->
            if (sc.id != scene.id) sc else sc.copy(frames = sc.frames + frame)
        }
        return finishPictureEdit(board, board.copy(scenes = scenes, activeSceneId = scene.id), random)
    }

    fun placeVideoAt(
        board: BoardData,
        src: String,
        name: String,
        durationSec: Double,
        playhead: Double,
        posterSrc: String? = null,
        random: Random = Random.Default,
    ): BoardData {
        val dur = durationSec.coerceAtLeast(MIN_DUR)
        val poster = posterSrc?.takeIf { it.isNotEmpty() }
        if (poster != null) {
            val start = leftoverStart(board, playhead)
            return addStillToScene(
                board = board,
                sceneId = sceneIdAt(board, start),
                src = poster,
                caption = name,
                durationSec = dur,
                videoSrc = src,
                videoDurationSec = dur,
                random = random,
            )
        }
        val clip = LayerClip(
            id = newId("lc", random),
            src = src,
            name = name,
            startSec = leftoverStart(board, playhead),
            durationSec = dur,
            sourceDurationSec = dur,
        )
        val layers = if (board.videoLayers.isEmpty()) {
            listOf(MediaLayer(id = newId("ly", random), kind = "video", name = "V1", clips = listOf(clip)))
        } else {
            board.videoLayers.mapIndexed { i, layer ->
                if (i != 0) layer else layer.copy(clips = layer.clips + clip)
            }
        }
        return finishPictureEdit(board, board.copy(videoLayers = layers), random)
    }

    fun placeAudioAt(
        board: BoardData,
        src: String,
        name: String,
        durationSec: Double,
        playhead: Double,
        layerId: String? = null,
        waveform: Any? = null,
        random: Random = Random.Default,
    ): BoardData {
        val start = playhead.coerceAtLeast(0.0)
        val dur = durationSec.coerceAtLeast(MIN_DUR)
        val wave = waveform ?: board.libraryAudio.firstOrNull {
            it.deletedAt == null && (it.src == src || LyreStorageKeys.normalize(it.src) == LyreStorageKeys.normalize(src))
        }?.waveform
        val clip = LayerClip(
            id = newId("lc", random),
            src = src,
            name = name,
            startSec = start,
            durationSec = dur,
            sourceDurationSec = dur,
            volume = 1.0,
            waveform = wave,
        )
        val prefer = when {
            layerId != null -> board.audioLayers.indexOfFirst { it.id == layerId }.takeIf { it >= 0 }
            else -> 0
        }
        return insertAudioClip(board, clip, prefer, random)
    }

    fun mediaKind(mime: String, name: String): String? {
        val m = mime.lowercase().trim()
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            m.startsWith("image/") -> "still"
            m.startsWith("video/") -> "video"
            m.startsWith("audio/") -> "audio"
            ext in STILL_EXT -> "still"
            ext in VIDEO_EXT -> "video"
            ext in AUDIO_EXT -> "audio"
            else -> null
        }
    }

    fun nextBreakFrameId(board: BoardData, playhead: Double): String? {
        val clips = LyreClip.movieClips(board.scenes)
        if (clips.isEmpty()) return null
        val t = playhead.coerceAtLeast(0.0)
        for (clip in clips) {
            val end = clip.start + clip.length
            if (t < end - 1e-4) return clip.frame.id
            if (abs(t - end) <= 1e-4) return clip.frame.id
        }
        return clips.last().frame.id
    }

    fun insertPictureAfter(
        board: BoardData,
        afterFrameId: String?,
        src: String,
        caption: String,
        durationSec: Double = 6.0,
        videoSrc: String? = null,
        videoDurationSec: Double? = null,
        random: Random = Random.Default,
    ): LyrePictureInsert {
        val id = newId("fr", random)
        val dur = durationSec.coerceAtLeast(MIN_DUR)
        val frame = Frame(
            id = id,
            src = src,
            caption = caption,
            durationSec = dur,
            videoSrc = videoSrc,
            videoDurationSec = videoDurationSec ?: videoSrc?.let { dur },
        )
        val after = afterFrameId?.takeIf { it.isNotEmpty() }
        var destId: String? = null
        var inserted = after == null
        val scenes = if (after == null) {
            val sceneId = board.activeSceneId.ifBlank { board.scenes.firstOrNull()?.id.orEmpty() }
            if (sceneId.isEmpty()) return LyrePictureInsert(board, "")
            destId = sceneId
            board.scenes.map { scene ->
                if (scene.id != sceneId) scene else scene.copy(frames = scene.frames + frame)
            }
        } else {
            board.scenes.map { scene ->
                val i = scene.frames.indexOfFirst { it.id == after }
                if (i < 0) {
                    scene
                } else {
                    inserted = true
                    destId = scene.id
                    val frames = scene.frames.toMutableList()
                    frames.add(i + 1, frame)
                    scene.copy(frames = frames)
                }
            }
        }
        if (!inserted) {
            val sceneId = board.activeSceneId.ifBlank { board.scenes.firstOrNull()?.id.orEmpty() }
            if (sceneId.isEmpty()) return LyrePictureInsert(board, "")
            destId = sceneId
            val fallback = board.scenes.map { scene ->
                if (scene.id != sceneId) scene else scene.copy(frames = scene.frames + frame)
            }
            val next = finishPictureEdit(
                board,
                board.copy(scenes = fallback, activeSceneId = sceneId),
                random,
            )
            return LyrePictureInsert(next, id)
        }
        val next = finishPictureEdit(
            board,
            board.copy(scenes = scenes, activeSceneId = destId ?: board.activeSceneId),
            random,
        )
        return LyrePictureInsert(next, id)
    }

    fun placeAudioOnNewTrack(
        board: BoardData,
        src: String,
        name: String,
        durationSec: Double,
        waveform: Any? = null,
        random: Random = Random.Default,
    ): BoardData {
        val grown = addAudioTrack(board, random)
        val lane = grown.audioLayers.last()
        return placeAudioAt(
            board = grown,
            src = src,
            name = name,
            durationSec = durationSec,
            playhead = 0.0,
            layerId = lane.id,
            waveform = waveform,
            random = random,
        )
    }

    fun addAudioTrack(board: BoardData, random: Random = Random.Default): BoardData {
        val n = board.audioLayers.size + 1
        val layer = MediaLayer(
            id = newId("ly", random),
            kind = "audio",
            name = "A$n",
            clips = emptyList(),
        )
        return board.copy(audioLayers = board.audioLayers + layer)
    }

    fun packedAudioLanes(layers: List<MediaLayer>): List<List<LayerClip>> {
        val clips = layers.flatMap { it.clips }.sortedWith(compareBy<LayerClip> { it.startSec }.thenBy { it.id })
        val lanes = ArrayList<ArrayList<LayerClip>>()
        for (clip in clips) {
            val idx = lanes.indexOfFirst { lane -> lane.none { overlaps(it, clip) } }
            if (idx >= 0) {
                lanes[idx].add(clip)
            } else {
                lanes.add(arrayListOf(clip))
            }
        }
        if (lanes.isEmpty()) lanes.add(arrayListOf())
        return lanes
    }

    fun packAudioLayers(board: BoardData, random: Random = Random.Default): BoardData {
        val packed = packedAudioLanes(board.audioLayers)
        val layers = packed.mapIndexed { i, clips ->
            val existing = board.audioLayers.getOrNull(i)
            MediaLayer(
                id = existing?.id ?: newId("ly", random),
                kind = "audio",
                name = existing?.name?.ifBlank { "A${i + 1}" } ?: "A${i + 1}",
                clips = clips,
                heightPx = existing?.heightPx,
                extra = existing?.extra,
            )
        }
        return board.copy(audioLayers = layers)
    }

    fun moveAudioClip(
        board: BoardData,
        clipId: String,
        startSec: Double,
        preferLane: Int? = null,
        random: Random = Random.Default,
    ): BoardData {
        val moving = board.audioLayers.asSequence().flatMap { it.clips.asSequence() }
            .firstOrNull { it.id == clipId } ?: return board
        val next = moving.copy(startSec = startSec.coerceAtLeast(0.0))
        val others = board.audioLayers.map { layer ->
            layer.copy(clips = layer.clips.filterNot { it.id == clipId })
        }
        return insertAudioClip(board.copy(audioLayers = others), next, preferLane, random)
    }

    fun pictureVideoClips(board: BoardData): List<LayerClip> {
        val stills = LyreClip.movieClips(board.scenes)
        val byFrame = LinkedHashMap<String, LayerClip>()
        for (layer in board.videoLayers) {
            for (clip in layer.clips) {
                val fid = clip.linkedFrameId ?: continue
                if (fid.isNotEmpty()) byFrame.putIfAbsent(fid, clip)
            }
        }
        val memberIds = board.movie?.parts?.map { it.clipId }?.toSet().orEmpty()
        val stitched = memberIds.size > 1
        val live = if (stitched) LyreMovie.resolvedMovie(board.movie, board.videoLayers) else null
        val out = ArrayList<LayerClip>()
        var movieEmitted = false
        for (sc in stills) {
            val existing = byFrame[sc.frame.id]
            val src = existing?.src?.takeIf { it.isNotEmpty() }
                ?: sc.frame.videoSrc?.takeIf { it.isNotEmpty() }
                ?: continue
            if (stitched && existing != null && existing.id in memberIds) {
                if (!movieEmitted) {
                    val members = stills.filter { byFrame[it.frame.id]?.id in memberIds }
                    val start = members.firstOrNull()?.start ?: sc.start
                    val play = live?.let { LyreMovie.moviePlayDuration(it) }
                        ?: members.sumOf { it.length }.coerceAtLeast(MIN_DUR)
                    out.add(
                        LayerClip(
                            id = "lc_movie",
                            src = live?.src?.takeIf { it.isNotEmpty() } ?: src,
                            name = "Movie · ${memberIds.size}",
                            startSec = start,
                            durationSec = play,
                            trimInSec = 0.0,
                            sourceDurationSec = live?.durationSec ?: play,
                            linkedFrameId = members.firstOrNull()?.frame?.id ?: sc.frame.id,
                        ),
                    )
                    movieEmitted = true
                }
                continue
            }
            val clip = existing ?: LayerClip(
                id = "pv_${sc.frame.id}",
                src = src,
                name = sc.frame.caption.ifBlank { sc.frame.id },
                startSec = sc.start,
                durationSec = sc.length,
                sourceDurationSec = sc.frame.videoDurationSec ?: sc.length,
                trimInSec = sc.frame.videoInSec,
                linkedFrameId = sc.frame.id,
            )
            out.add(
                clip.copy(
                    startSec = sc.start,
                    durationSec = sc.length,
                    src = src,
                    linkedFrameId = sc.frame.id,
                ),
            )
        }
        return out
    }

    fun isPictureLocked(board: BoardData, frameId: String): Boolean {
        return LyreMovie.isStitchedFrame(board.movie, board.videoLayers, frameId)
    }

    fun syncLinkedVideo(board: BoardData, random: Random = Random.Default): BoardData {
        val stills = LyreClip.movieClips(board.scenes)
        val byFrame = LinkedHashMap<String, LayerClip>()
        val unlinked = ArrayList<LayerClip>()
        for (layer in board.videoLayers) {
            for (clip in layer.clips) {
                val fid = clip.linkedFrameId
                if (fid.isNullOrEmpty()) {
                    unlinked.add(clip)
                } else {
                    byFrame.putIfAbsent(fid, clip)
                }
            }
        }
        val packed = ArrayList<LayerClip>()
        for (sc in stills) {
            val prev = byFrame.remove(sc.frame.id)
            val src = prev?.src?.takeIf { it.isNotEmpty() }
                ?: sc.frame.videoSrc?.takeIf { it.isNotEmpty() }
                ?: continue
            val base = prev ?: LayerClip(
                id = newId("lc", random),
                src = src,
                name = sc.frame.caption.ifBlank { sc.frame.id },
                startSec = sc.start,
                durationSec = sc.length,
                sourceDurationSec = sc.frame.videoDurationSec ?: sc.length,
                trimInSec = sc.frame.videoInSec ?: 0.0,
                linkedFrameId = sc.frame.id,
            )
            packed.add(
                base.copy(
                    startSec = sc.start,
                    durationSec = sc.length,
                    src = src,
                    linkedFrameId = sc.frame.id,
                ),
            )
        }
        unlinked.addAll(byFrame.values)
        var t = stills.sumOf { it.length }
        val rest = unlinked.map { clip ->
            val placed = clip.copy(startSec = t)
            t += clip.durationSec.coerceAtLeast(MIN_DUR)
            placed
        }
        val all = packed + rest
        if (all.isEmpty() && board.videoLayers.isEmpty()) return board
        val layers = if (board.videoLayers.isEmpty()) {
            listOf(
                MediaLayer(
                    id = newId("ly", random),
                    kind = "video",
                    name = "V1",
                    clips = all,
                ),
            )
        } else {
            board.videoLayers.mapIndexed { i, layer ->
                if (i == 0) layer.copy(clips = all) else layer.copy(clips = emptyList())
            }
        }
        return board.copy(videoLayers = layers)
    }

    fun moveVideoClip(board: BoardData, clipId: String, startSec: Double): BoardData {
        if (clipId == "lc_movie" || LyreMovie.isStitchedMember(board.movie, clipId)) return board
        val moving = board.videoLayers.asSequence().flatMap { it.clips.asSequence() }
            .firstOrNull { it.id == clipId } ?: return board
        val fid = moving.linkedFrameId
        if (!fid.isNullOrEmpty()) {
            return moveStillTo(board, fid, startSec)
        }
        val next = moving.copy(startSec = startSec.coerceAtLeast(0.0))
        if (pictureVideoClips(board).any { overlaps(it, next) }) return board
        return board.copy(
            videoLayers = board.videoLayers.map { layer ->
                layer.copy(
                    clips = layer.clips.map { clip ->
                        if (clip.id == clipId) next else clip
                    },
                )
            },
        )
    }

    fun moveStillTo(board: BoardData, frameId: String, atSec: Double): BoardData {
        if (isPictureLocked(board, frameId)) return board
        val clips = LyreClip.movieClips(board.scenes)
        val moving = clips.firstOrNull { it.frame.id == frameId } ?: return board
        val target = LyreClip.clipAtTime(clips, atSec) ?: moving
        val sceneId = target.sceneId
        val stripped = board.scenes.map { scene ->
            scene.copy(frames = scene.frames.filterNot { it.id == frameId })
        }
        val dest = stripped.firstOrNull { it.id == sceneId } ?: return board
        val destStart = LyreClip.movieClips(stripped).firstOrNull { it.sceneId == sceneId }?.start ?: 0.0
        var insert = dest.frames.size
        var t = destStart
        for (i in dest.frames.indices) {
            val len = LyreClip.clipLength(dest.frames[i])
            if (atSec < t + len / 2.0) {
                insert = i
                break
            }
            t += len
        }
        val frames = dest.frames.toMutableList()
        frames.add(insert.coerceIn(0, frames.size), moving.frame)
        val scenes = stripped.map { if (it.id == dest.id) dest.copy(frames = frames) else it }
        return finishPictureEdit(board, board.copy(scenes = scenes, activeSceneId = dest.id))
    }

    fun trimStillRight(board: BoardData, frameId: String, newEndSec: Double): BoardData {
        if (isPictureLocked(board, frameId)) return board
        val clip = LyreClip.clipOf(board.scenes, frameId) ?: return board
        val dur = (newEndSec - clip.start).coerceAtLeast(MIN_DUR)
        return setFrameDuration(board, frameId, dur)
    }

    fun trimStillLeft(board: BoardData, frameId: String, newStartSec: Double): BoardData {
        if (isPictureLocked(board, frameId)) return board
        val clips = LyreClip.movieClips(board.scenes)
        val i = clips.indexOfFirst { it.frame.id == frameId }
        if (i < 0) return board
        val cur = clips[i]
        val curEnd = cur.start + cur.length
        if (i == 0) {
            val start = newStartSec.coerceAtLeast(0.0)
            val dur = (curEnd - start).coerceAtLeast(MIN_DUR)
            val cut = cur.length - dur
            var next = setFrameDurationRaw(board, frameId, dur)
            next = bumpVideoIn(next, frameId, cut)
            return finishPictureEdit(board, next)
        }
        val prev = clips[i - 1]
        val boundary = newStartSec.coerceIn(prev.start + MIN_DUR, curEnd - MIN_DUR)
        val cut = boundary - cur.start
        var next = setFrameDurationRaw(board, prev.frame.id, boundary - prev.start)
        next = setFrameDurationRaw(next, frameId, curEnd - boundary)
        next = bumpVideoIn(next, frameId, cut)
        return finishPictureEdit(board, next)
    }

    fun setFrameDuration(board: BoardData, frameId: String, durationSec: Double): BoardData {
        return finishPictureEdit(board, setFrameDurationRaw(board, frameId, durationSec))
    }

    fun trimClip(board: BoardData, clipId: String, newStartSec: Double, newEndSec: Double): BoardData {
        val audio = board.audioLayers.asSequence().flatMap { it.clips.asSequence() }
            .firstOrNull { it.id == clipId }
        if (audio != null) {
            val trimmed = trimmedWindow(audio, newStartSec, newEndSec)
            val layers = board.audioLayers.map { layer ->
                layer.copy(clips = layer.clips.map { if (it.id == clipId) trimmed else it })
            }
            return board.copy(audioLayers = layers)
        }
        if (clipId == "lc_movie" || LyreMovie.isStitchedMember(board.movie, clipId)) return board
        val video = board.videoLayers.asSequence().flatMap { it.clips.asSequence() }
            .firstOrNull { it.id == clipId } ?: return board
        val fid = video.linkedFrameId
        if (!fid.isNullOrEmpty()) {
            if (isPictureLocked(board, fid)) return board
            val sc = LyreClip.clipOf(board.scenes, fid) ?: return board
            val curEnd = sc.start + sc.length
            var next = board
            if (abs(newStartSec - sc.start) > 1e-4) {
                next = trimStillLeft(next, fid, newStartSec)
            }
            if (abs(newEndSec - curEnd) > 1e-4) {
                next = trimStillRight(next, fid, newEndSec)
            }
            return next
        }
        if (isMovieLocked(board, clipId)) return board
        val trimmed = trimmedWindow(video, newStartSec, newEndSec)
        val videoLayers = board.videoLayers.map { layer ->
            layer.copy(clips = layer.clips.map { if (it.id == clipId) trimmed else it })
        }
        return board.copy(videoLayers = videoLayers)
    }

    fun setEnvelope(board: BoardData, clipId: String, envelope: VolumeEnvelope): BoardData {
        return mapAudioClip(board, clipId) { it.copy(envelope = envelope.toJson()) }
    }

    fun setEnvelopeOn(board: BoardData, clipId: String, on: Boolean): BoardData {
        val clip = audioClip(board, clipId) ?: return board
        val env = if (on) LyreEnvelope.enabled(clip) else LyreEnvelope.disabled(clip)
        return setEnvelope(board, clipId, env)
    }

    fun fadeAudio(board: BoardData, clipId: String, fadeInSec: Double? = null, fadeOutSec: Double? = null): BoardData {
        val clip = audioClip(board, clipId) ?: return board
        var envClip = clip
        if (fadeInSec != null) {
            envClip = envClip.copy(envelope = LyreEnvelope.fadeIn(envClip, fadeInSec).toJson())
        }
        if (fadeOutSec != null) {
            envClip = envClip.copy(envelope = LyreEnvelope.fadeOut(envClip, fadeOutSec).toJson())
        }
        return mapAudioClip(board, clipId) { envClip }
    }

    fun setClipWaveform(board: BoardData, clipId: String, waveform: Any?): BoardData {
        return mapAudioClip(board, clipId) { it.copy(waveform = waveform) }
    }

    fun sourceDuration(clip: LayerClip): Double {
        val inn = clip.trimInSec ?: 0.0
        return max(inn + clip.durationSec, clip.sourceDurationSec ?: (inn + clip.durationSec))
    }

    fun removeClip(board: BoardData, clipId: String): BoardData {
        if (clipId == "lc_movie") return board
        val videoClip = board.videoLayers.asSequence().flatMap { it.clips.asSequence() }
            .firstOrNull { it.id == clipId }
        val wasMember = videoClip != null && LyreMovie.clipInMovie(board.movie, clipId, board.videoLayers)
        val audioLayers = board.audioLayers.map { layer ->
            layer.copy(clips = layer.clips.filterNot { it.id == clipId })
        }
        val videoLayers = board.videoLayers.map { layer ->
            layer.copy(clips = layer.clips.filterNot { it.id == clipId })
        }
        val scenes = if (videoClip?.linkedFrameId.isNullOrEmpty()) {
            board.scenes
        } else {
            val frameId = videoClip!!.linkedFrameId
            board.scenes.map { scene ->
                scene.copy(
                    frames = scene.frames.map { frame ->
                        if (frame.id == frameId) {
                            frame.copy(videoSrc = null, videoDurationSec = null, videoMuted = null)
                        } else {
                            frame
                        }
                    },
                )
            }
        }
        val next = board.copy(
            scenes = scenes,
            videoLayers = videoLayers,
            audioLayers = audioLayers,
            movie = if (wasMember) null else board.movie,
        )
        return if (videoClip != null) syncLinkedVideo(next) else next
    }

    fun setClipVolume(board: BoardData, clipId: String, volume: Double): BoardData {
        val v = volume.coerceIn(0.0, 1.0)
        return board.copy(
            audioLayers = board.audioLayers.map { layer ->
                layer.copy(
                    clips = layer.clips.map { clip ->
                        if (clip.id == clipId) clip.copy(volume = v) else clip
                    },
                )
            },
        )
    }

    fun setVideoMuted(board: BoardData, clipId: String, muted: Boolean): BoardData {
        val linked = board.videoLayers.asSequence().flatMap { it.clips.asSequence() }
            .firstOrNull { it.id == clipId }?.linkedFrameId
        val scenes = if (linked.isNullOrEmpty()) {
            board.scenes
        } else {
            board.scenes.map { scene ->
                scene.copy(
                    frames = scene.frames.map { frame ->
                        if (frame.id == linked) frame.copy(videoMuted = muted) else frame
                    },
                )
            }
        }
        return board.copy(scenes = scenes)
    }

    fun sceneIdAt(board: BoardData, t: Double): String {
        val clip = LyreClip.clipAtTime(LyreClip.movieClips(board.scenes), t)
        return clip?.sceneId ?: board.activeSceneId.ifBlank { board.scenes.firstOrNull()?.id.orEmpty() }
    }

    fun insertStillAfter(
        board: BoardData,
        afterFrameId: String,
        src: String,
        caption: String,
        durationSec: Double = 6.0,
        videoSrc: String? = null,
        videoDurationSec: Double? = null,
        random: Random = Random.Default,
    ): BoardData {
        return insertPictureAfter(
            board,
            afterFrameId,
            src,
            caption,
            durationSec,
            videoSrc,
            videoDurationSec,
            random,
        ).board
    }

    fun replaceStillSrc(board: BoardData, frameId: String, src: String): BoardData {
        return board.copy(
            scenes = board.scenes.map { scene ->
                scene.copy(
                    frames = scene.frames.map { frame ->
                        if (frame.id == frameId) {
                            frame.copy(src = src, generating = false, generatingError = null)
                        } else {
                            frame
                        }
                    },
                )
            },
        )
    }

    fun setFrameGenerating(board: BoardData, frameId: String, generating: Boolean, error: String? = null): BoardData {
        return board.copy(
            scenes = board.scenes.map { scene ->
                scene.copy(
                    frames = scene.frames.map { frame ->
                        if (frame.id != frameId) {
                            frame
                        } else {
                            frame.copy(generating = generating, generatingError = error)
                        }
                    },
                )
            },
        )
    }

    fun setFrameVideoGenerating(
        board: BoardData,
        frameId: String,
        generating: Boolean,
        error: String? = null,
    ): BoardData {
        return board.copy(
            scenes = board.scenes.map { scene ->
                scene.copy(
                    frames = scene.frames.map { frame ->
                        if (frame.id != frameId) {
                            frame
                        } else {
                            frame.copy(videoGenerating = generating, videoGeneratingError = error)
                        }
                    },
                )
            },
        )
    }

    fun replaceVideoSrc(
        board: BoardData,
        clipId: String,
        newSrc: String,
        durationSec: Double,
    ): BoardData {
        if (isMovieLocked(board, clipId)) return board
        val dur = durationSec.coerceAtLeast(MIN_DUR)
        var linked: String? = null
        val videoLayers = board.videoLayers.map { layer ->
            layer.copy(
                clips = layer.clips.map { clip ->
                    if (clip.id != clipId) {
                        clip
                    } else {
                        linked = clip.linkedFrameId
                        clip.copy(
                            origSrc = clip.origSrc ?: clip.src,
                            origDurationSec = clip.origDurationSec ?: clip.sourceDurationSec ?: clip.durationSec,
                            src = newSrc,
                            durationSec = dur,
                            sourceDurationSec = dur,
                            trimInSec = 0.0,
                        )
                    }
                },
            )
        }
        val scenes = if (linked.isNullOrEmpty()) {
            board.scenes
        } else {
            val fid = linked
            board.scenes.map { scene ->
                scene.copy(
                    frames = scene.frames.map { frame ->
                        if (frame.id != fid) {
                            frame
                        } else {
                            frame.copy(
                                origVideoSrc = frame.origVideoSrc ?: frame.videoSrc,
                                origVideoDurationSec = frame.origVideoDurationSec ?: frame.videoDurationSec,
                                videoSrc = newSrc,
                                videoDurationSec = dur,
                                videoInSec = 0.0,
                                videoOutSec = dur,
                                videoGenerating = false,
                                videoGeneratingError = null,
                            )
                        }
                    },
                )
            }
        }
        return board.copy(scenes = scenes, videoLayers = videoLayers)
    }

    fun attachGeneratedVideo(
        board: BoardData,
        frameId: String,
        videoSrc: String,
        durationSec: Double,
        name: String = "",
        random: Random = Random.Default,
    ): BoardData {
        val dur = durationSec.coerceAtLeast(MIN_DUR)
        val label = name.ifBlank {
            board.scenes.asSequence().flatMap { it.frames.asSequence() }
                .firstOrNull { it.id == frameId }?.caption?.ifBlank { frameId } ?: frameId
        }
        val scenes = board.scenes.map { scene ->
            scene.copy(
                frames = scene.frames.map { frame ->
                    if (frame.id != frameId) {
                        frame
                    } else {
                        frame.copy(
                            origVideoSrc = frame.origVideoSrc ?: frame.videoSrc,
                            origVideoDurationSec = frame.origVideoDurationSec ?: frame.videoDurationSec,
                            videoSrc = videoSrc,
                            videoDurationSec = dur,
                            videoGenerating = false,
                            videoGeneratingError = null,
                        )
                    }
                },
            )
        }
        val withScenes = board.copy(scenes = scenes)
        val existing = withScenes.videoLayers.asSequence().flatMap { it.clips.asSequence() }
            .firstOrNull { it.linkedFrameId == frameId }
        if (existing != null) {
            val replaced = withScenes.copy(
                videoLayers = withScenes.videoLayers.map { layer ->
                    layer.copy(
                        clips = layer.clips.map { clip ->
                            if (clip.linkedFrameId != frameId) {
                                clip
                            } else {
                                clip.copy(
                                    origSrc = clip.origSrc ?: clip.src,
                                    origDurationSec = clip.origDurationSec ?: clip.sourceDurationSec ?: clip.durationSec,
                                    src = videoSrc,
                                    durationSec = dur,
                                    sourceDurationSec = dur,
                                    trimInSec = 0.0,
                                )
                            }
                        },
                    )
                },
            )
            return finishPictureEdit(board, replaced, random)
        }
        val clip = LayerClip(
            id = newId("lc", random),
            src = videoSrc,
            name = label,
            startSec = 0.0,
            durationSec = dur,
            sourceDurationSec = dur,
            linkedFrameId = frameId,
        )
        val layers = if (withScenes.videoLayers.isEmpty()) {
            listOf(MediaLayer(id = newId("ly", random), kind = "video", name = "V1", clips = listOf(clip)))
        } else {
            withScenes.videoLayers.mapIndexed { i, layer ->
                if (i != 0) layer else layer.copy(clips = layer.clips + clip)
            }
        }
        return finishPictureEdit(board, withScenes.copy(videoLayers = layers), random)
    }

    fun addStillToLibrary(
        board: BoardData,
        src: String,
        caption: String,
        random: Random = Random.Default,
    ): BoardData {
        val image = RefImage(
            id = newId("ri", random),
            src = src,
            caption = caption,
        )
        val folders = if (board.refFolders.isEmpty()) {
            listOf(RefFolder(id = newId("rf", random), name = "Library", images = listOf(image)))
        } else {
            val destId = board.activeFolderId.ifBlank { board.refFolders.first().id }
            board.refFolders.map { folder ->
                if (folder.id != destId) folder else folder.copy(images = folder.images + image)
            }
        }
        return board.copy(refFolders = folders)
    }

    fun addVideoToLibrary(
        board: BoardData,
        src: String,
        name: String,
        durationSec: Double,
        random: Random = Random.Default,
    ): BoardData {
        val item = LibraryVideo(
            id = newId("lv", random),
            src = src,
            name = name,
            durationSec = durationSec.coerceAtLeast(MIN_DUR),
            createdAt = System.currentTimeMillis(),
        )
        return board.copy(libraryVideo = board.libraryVideo + item)
    }

    fun addAudioToLibrary(
        board: BoardData,
        src: String,
        name: String,
        durationSec: Double,
        waveform: Any? = null,
        random: Random = Random.Default,
    ): BoardData {
        val item = LibraryAudio(
            id = newId("la", random),
            src = src,
            name = name,
            durationSec = durationSec.coerceAtLeast(MIN_DUR),
            waveform = waveform,
            createdAt = System.currentTimeMillis(),
        )
        return board.copy(libraryAudio = board.libraryAudio + item)
    }

    fun isMovieLocked(board: BoardData, clipId: String): Boolean {
        if (clipId == "lc_movie") return true
        if (LyreMovie.isStitchedMember(board.movie, clipId)) return true
        return LyreMovie.clipInMovie(board.movie, clipId, board.videoLayers)
    }

    fun overlaps(a: LayerClip, b: LayerClip, epsilon: Double = 1e-4): Boolean {
        if (a.id == b.id) return false
        return a.startSec < b.startSec + b.durationSec - epsilon &&
            b.startSec < a.startSec + a.durationSec - epsilon
    }

    private fun finishPictureEdit(
        before: BoardData,
        after: BoardData,
        random: Random = Random.Default,
    ): BoardData {
        return clearMovieIfPictureChanged(before, syncLinkedVideo(after, random))
    }

    private fun setFrameDurationRaw(board: BoardData, frameId: String, durationSec: Double): BoardData {
        val dur = durationSec.coerceAtLeast(MIN_DUR)
        return board.copy(
            scenes = board.scenes.map { scene ->
                scene.copy(
                    frames = scene.frames.map { frame ->
                        if (frame.id != frameId) {
                            frame
                        } else {
                            val inn = frame.videoInSec ?: 0.0
                            frame.copy(
                                durationSec = dur,
                                videoOutSec = if (frame.videoSrc.isNullOrEmpty()) {
                                    frame.videoOutSec
                                } else {
                                    inn + dur
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    private fun bumpVideoIn(board: BoardData, frameId: String, delta: Double): BoardData {
        if (abs(delta) < 1e-6) return board
        return board.copy(
            scenes = board.scenes.map { scene ->
                scene.copy(
                    frames = scene.frames.map { frame ->
                        if (frame.id != frameId) {
                            frame
                        } else {
                            val inn = ((frame.videoInSec ?: 0.0) + delta).coerceAtLeast(0.0)
                            frame.copy(
                                videoInSec = inn,
                                videoOutSec = inn + frame.durationSec,
                            )
                        }
                    },
                )
            },
            videoLayers = board.videoLayers.map { layer ->
                layer.copy(
                    clips = layer.clips.map { clip ->
                        if (clip.linkedFrameId != frameId) {
                            clip
                        } else {
                            val inn = ((clip.trimInSec ?: 0.0) + delta).coerceAtLeast(0.0)
                            clip.copy(trimInSec = inn)
                        }
                    },
                )
            },
        )
    }

    private fun clearMovieIfPictureChanged(before: BoardData, after: BoardData): BoardData {
        val movie = after.movie ?: return after
        val layers = before.videoLayers
        val memberFrames = layers.asSequence().flatMap { it.clips.asSequence() }
            .filter { LyreMovie.clipInMovie(before.movie, it.id, layers) }
            .mapNotNull { it.linkedFrameId?.takeIf { id -> id.isNotEmpty() } }
            .toSet()
        if (memberFrames.isEmpty()) return after
        fun sig(board: BoardData): List<Triple<String, Double, Double>> {
            return LyreClip.movieClips(board.scenes)
                .filter { it.frame.id in memberFrames }
                .map { Triple(it.frame.id, it.start, it.length) }
        }
        return if (sig(before) != sig(after)) after.copy(movie = null) else after
    }

    private fun insertAudioClip(
        board: BoardData,
        clip: LayerClip,
        preferLane: Int?,
        random: Random,
    ): BoardData {
        val layers = board.audioLayers.toMutableList()
        if (layers.isEmpty()) {
            layers.add(
                MediaLayer(
                    id = newId("ly", random),
                    kind = "audio",
                    name = "A1",
                    clips = emptyList(),
                ),
            )
        }
        val idx = (preferLane ?: 0).coerceAtLeast(0)
        while (layers.size <= idx) {
            val n = layers.size + 1
            layers.add(
                MediaLayer(
                    id = newId("ly", random),
                    kind = "audio",
                    name = "A$n",
                    clips = emptyList(),
                ),
            )
        }
        val dest = layers[idx]
        val clips = (dest.clips.filterNot { it.id == clip.id } + clip)
            .sortedWith(compareBy<LayerClip> { it.startSec }.thenBy { it.id })
        layers[idx] = dest.copy(clips = clips)
        return board.copy(audioLayers = layers.toList())
    }

    private fun trimmedWindow(clip: LayerClip, newStartSec: Double, newEndSec: Double): LayerClip {
        val inn0 = clip.trimInSec ?: 0.0
        val native = sourceDuration(clip)
        val oldStart = clip.startSec
        var start = newStartSec
        var end = newEndSec
        if (end < start + MIN_DUR) end = start + MIN_DUR
        var inn = inn0 + (start - oldStart)
        var dur = end - start
        if (inn < 0.0) {
            start -= inn
            dur += inn
            inn = 0.0
        }
        if (inn + dur > native) {
            dur = (native - inn).coerceAtLeast(MIN_DUR)
        }
        if (start < 0.0) {
            inn += -start
            dur -= -start
            start = 0.0
        }
        dur = dur.coerceAtLeast(MIN_DUR)
        inn = inn.coerceIn(0.0, (native - MIN_DUR).coerceAtLeast(0.0))
        return clip.copy(
            startSec = start.coerceAtLeast(0.0),
            durationSec = dur,
            trimInSec = inn,
            sourceDurationSec = clip.sourceDurationSec ?: native,
        )
    }

    private fun audioClip(board: BoardData, clipId: String): LayerClip? {
        return board.audioLayers.asSequence().flatMap { it.clips.asSequence() }
            .firstOrNull { it.id == clipId }
    }

    private fun mapAudioClip(board: BoardData, clipId: String, fn: (LayerClip) -> LayerClip): BoardData {
        return board.copy(
            audioLayers = board.audioLayers.map { layer ->
                layer.copy(clips = layer.clips.map { if (it.id == clipId) fn(it) else it })
            },
        )
    }
}
