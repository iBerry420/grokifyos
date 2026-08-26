package io.grokify.os.apps.lyre

import org.json.JSONArray
import org.json.JSONObject

object LyreBoardCodec {
    private val BOARD_KEYS = setOf(
        "title", "brainstorm", "scenes", "activeSceneId", "refFolders", "activeFolderId",
        "videoGen", "videoLayers", "audioLayers", "libraryAudio", "libraryVideo", "movie", "ui",
    )
    private val SCENE_KEYS = setOf(
        "id", "title", "book", "durationTargetSec", "logline", "dialogue", "notes", "summary", "frames",
    )
    private val FRAME_KEYS = setOf(
        "id", "src", "caption", "durationSec", "videoPrompt", "dialogue", "notes",
        "videoSrc", "origVideoSrc", "origVideoDurationSec", "videoInSec", "videoOutSec",
        "videoDurationSec", "videoFps", "videoMuted", "audioClips", "videoRefSrcs", "videoVoices",
        "generating", "generatingError", "videoGenerating", "videoGeneratingError", "uploading", "createdAt",
    )
    private val REF_IMAGE_KEYS = FRAME_KEYS + setOf(
        "fromSceneId", "fromSceneTitle", "fromFrameId", "fromIndex", "holdSec",
    )
    private val FOLDER_KEYS = setOf("id", "name", "images")
    private val LAYER_KEYS = setOf("id", "kind", "name", "clips", "heightPx")
    private val CLIP_KEYS = setOf(
        "id", "src", "name", "startSec", "durationSec", "trimInSec", "sourceDurationSec",
        "origSrc", "origDurationSec", "linkedFrameId", "generating", "waveform", "volume", "envelope",
    )
    private val AUDIO_CLIP_KEYS = setOf("id", "src", "name", "offsetSec", "durationSec", "trimInSec")
    private val MOVIE_KEYS = setOf("src", "durationSec", "playDurationSec", "fps", "origSrc", "parts")
    private val PART_KEYS = setOf("clipId", "src", "durationSec")
    private val VIDEO_GEN_KEYS = setOf("duration", "aspectRatio", "resolution", "locked")
    private val LIBRARY_AUDIO_KEYS = setOf(
        "id", "src", "name", "durationSec", "waveform", "createdAt", "deletedAt", "uploading",
    )
    private val LIBRARY_VIDEO_KEYS = setOf(
        "id", "src", "name", "durationSec", "createdAt", "deletedAt", "uploading",
    )

    fun emptyBoardJson(): JSONObject {
        val scene = JSONObject()
            .put("id", "sc_1")
            .put("title", "Scene 1")
            .put("book", "")
            .put("durationTargetSec", 0)
            .put("logline", "")
            .put("dialogue", "")
            .put("notes", "")
            .put("frames", JSONArray())
        val folder = JSONObject()
            .put("id", "lib")
            .put("name", "Library")
            .put("images", JSONArray())
        return JSONObject()
            .put("title", "Untitled")
            .put("brainstorm", "")
            .put("scenes", JSONArray().put(scene))
            .put("activeSceneId", "sc_1")
            .put("refFolders", JSONArray().put(folder))
            .put("activeFolderId", "lib")
            .put("videoLayers", JSONArray())
            .put("audioLayers", JSONArray())
            .put("libraryAudio", JSONArray())
            .put("libraryVideo", JSONArray())
    }

    fun emptyBoard(): BoardData = decode(emptyBoardJson())

    fun decode(obj: JSONObject): BoardData {
        val source = JSONObject(obj.toString())
        val scenes = obj.optJSONArray("scenes").objects().map { decodeScene(it) }
        val folders = obj.optJSONArray("refFolders").objects().map { decodeFolder(it) }
        return BoardData(
            title = obj.optString("title").ifBlank { "Untitled" },
            brainstorm = obj.optString("brainstorm"),
            scenes = scenes.ifEmpty {
                listOf(
                    Scene(
                        id = "sc_1",
                        title = "Scene 1",
                        book = "",
                        durationTargetSec = 0.0,
                        logline = "",
                        dialogue = "",
                        notes = "",
                        frames = emptyList(),
                    ),
                )
            },
            activeSceneId = obj.optString("activeSceneId").ifBlank { scenes.firstOrNull()?.id ?: "sc_1" },
            refFolders = folders.ifEmpty {
                listOf(RefFolder(id = "lib", name = "Library", images = emptyList()))
            },
            activeFolderId = obj.optString("activeFolderId").ifBlank { "lib" },
            videoGen = obj.optJSONObject("videoGen")?.let { decodeVideoGen(it) },
            videoLayers = obj.optJSONArray("videoLayers").objects().map { decodeLayer(it) },
            audioLayers = obj.optJSONArray("audioLayers").objects().map { decodeLayer(it) },
            libraryAudio = obj.optJSONArray("libraryAudio").objects().map { decodeLibraryAudio(it) },
            libraryVideo = obj.optJSONArray("libraryVideo").objects().map { decodeLibraryVideo(it) },
            movie = obj.optJSONObject("movie")?.let { decodeMovie(it) },
            ui = obj.optJSONObject("ui")?.let { JSONObject(it.toString()) },
            extra = extras(obj, BOARD_KEYS),
            source = source,
        )
    }

    fun encode(board: BoardData): JSONObject {
        val out = try {
            JSONObject(board.source.toString())
        } catch (_: Exception) {
            JSONObject()
        }
        copyExtras(board.extra, out)
        out.put("title", board.title)
        out.put("brainstorm", board.brainstorm)
        out.put("scenes", JSONArray().also { arr -> board.scenes.forEach { arr.put(encodeScene(it)) } })
        out.put("activeSceneId", board.activeSceneId)
        out.put("refFolders", JSONArray().also { arr -> board.refFolders.forEach { arr.put(encodeFolder(it)) } })
        out.put("activeFolderId", board.activeFolderId)
        if (board.videoGen != null) out.put("videoGen", encodeVideoGen(board.videoGen))
        out.put("videoLayers", JSONArray().also { arr -> board.videoLayers.forEach { arr.put(encodeLayer(it)) } })
        out.put("audioLayers", JSONArray().also { arr -> board.audioLayers.forEach { arr.put(encodeLayer(it)) } })
        out.put("libraryAudio", JSONArray().also { arr -> board.libraryAudio.forEach { arr.put(encodeLibraryAudio(it)) } })
        out.put("libraryVideo", JSONArray().also { arr -> board.libraryVideo.forEach { arr.put(encodeLibraryVideo(it)) } })
        if (board.movie != null) {
            out.put("movie", encodeMovie(board.movie))
        }
        if (board.ui != null) {
            out.put("ui", board.ui)
        }
        return out
    }

    private fun decodeScene(obj: JSONObject): Scene {
        return Scene(
            id = obj.optString("id"),
            title = obj.optString("title"),
            book = obj.optString("book"),
            durationTargetSec = obj.doubleOr("durationTargetSec", 0.0),
            logline = obj.optString("logline"),
            dialogue = obj.optString("dialogue"),
            notes = obj.optString("notes"),
            summary = obj.optStringOrNull("summary"),
            frames = obj.optJSONArray("frames").objects().map { decodeFrame(it) },
            extra = extras(obj, SCENE_KEYS),
        )
    }

    private fun encodeScene(scene: Scene): JSONObject {
        val o = JSONObject()
        copyExtras(scene.extra, o)
        o.put("id", scene.id)
        o.put("title", scene.title)
        o.put("book", scene.book)
        o.put("durationTargetSec", scene.durationTargetSec)
        o.put("logline", scene.logline)
        o.put("dialogue", scene.dialogue)
        o.put("notes", scene.notes)
        scene.summary?.let { o.put("summary", it) }
        o.put("frames", JSONArray().also { arr -> scene.frames.forEach { arr.put(encodeFrame(it)) } })
        return o
    }

    private fun decodeFrame(obj: JSONObject): Frame {
        return Frame(
            id = obj.optString("id"),
            src = obj.optString("src"),
            caption = obj.optString("caption"),
            durationSec = obj.doubleOr("durationSec", 0.0),
            videoPrompt = obj.optStringOrNull("videoPrompt"),
            dialogue = obj.optStringOrNull("dialogue"),
            notes = obj.optStringOrNull("notes"),
            videoSrc = obj.optStringOrNull("videoSrc"),
            origVideoSrc = obj.optStringOrNull("origVideoSrc"),
            origVideoDurationSec = obj.doubleOrNull("origVideoDurationSec"),
            videoInSec = obj.doubleOrNull("videoInSec"),
            videoOutSec = obj.doubleOrNull("videoOutSec"),
            videoDurationSec = obj.doubleOrNull("videoDurationSec"),
            videoFps = obj.doubleOrNull("videoFps"),
            videoMuted = obj.boolOrNull("videoMuted"),
            audioClips = obj.optJSONArray("audioClips")?.objects()?.map { decodeAudioClip(it) },
            videoRefSrcs = obj.optJSONArray("videoRefSrcs")?.strings(),
            videoVoices = obj.optJSONArray("videoVoices")?.strings(),
            generating = obj.boolOrNull("generating"),
            generatingError = obj.optStringOrNull("generatingError"),
            videoGenerating = obj.boolOrNull("videoGenerating"),
            videoGeneratingError = obj.optStringOrNull("videoGeneratingError"),
            uploading = obj.boolOrNull("uploading"),
            createdAt = obj.longOrNull("createdAt"),
            extra = extras(obj, FRAME_KEYS),
        )
    }

    private fun encodeFrame(frame: Frame): JSONObject {
        val o = JSONObject()
        copyExtras(frame.extra, o)
        o.put("id", frame.id)
        o.put("src", frame.src)
        o.put("caption", frame.caption)
        o.put("durationSec", frame.durationSec)
        frame.videoPrompt?.let { o.put("videoPrompt", it) }
        frame.dialogue?.let { o.put("dialogue", it) }
        frame.notes?.let { o.put("notes", it) }
        frame.videoSrc?.let { o.put("videoSrc", it) }
        frame.origVideoSrc?.let { o.put("origVideoSrc", it) }
        frame.origVideoDurationSec?.let { o.put("origVideoDurationSec", it) }
        frame.videoInSec?.let { o.put("videoInSec", it) }
        frame.videoOutSec?.let { o.put("videoOutSec", it) }
        frame.videoDurationSec?.let { o.put("videoDurationSec", it) }
        frame.videoFps?.let { o.put("videoFps", it) }
        frame.videoMuted?.let { o.put("videoMuted", it) }
        frame.audioClips?.let { clips ->
            o.put("audioClips", JSONArray().also { arr -> clips.forEach { arr.put(encodeAudioClip(it)) } })
        }
        frame.videoRefSrcs?.let { o.put("videoRefSrcs", stringArray(it)) }
        frame.videoVoices?.let { o.put("videoVoices", stringArray(it)) }
        frame.generating?.let { o.put("generating", it) }
        frame.generatingError?.let { o.put("generatingError", it) }
        frame.videoGenerating?.let { o.put("videoGenerating", it) }
        frame.videoGeneratingError?.let { o.put("videoGeneratingError", it) }
        frame.uploading?.let { o.put("uploading", it) }
        frame.createdAt?.let { o.put("createdAt", it) }
        return o
    }

    private fun decodeRefImage(obj: JSONObject): RefImage {
        return RefImage(
            id = obj.optString("id"),
            src = obj.optString("src"),
            caption = obj.optString("caption"),
            videoPrompt = obj.optStringOrNull("videoPrompt"),
            dialogue = obj.optStringOrNull("dialogue"),
            notes = obj.optStringOrNull("notes"),
            videoSrc = obj.optStringOrNull("videoSrc"),
            origVideoSrc = obj.optStringOrNull("origVideoSrc"),
            origVideoDurationSec = obj.doubleOrNull("origVideoDurationSec"),
            videoInSec = obj.doubleOrNull("videoInSec"),
            videoOutSec = obj.doubleOrNull("videoOutSec"),
            videoDurationSec = obj.doubleOrNull("videoDurationSec"),
            videoFps = obj.doubleOrNull("videoFps"),
            videoMuted = obj.boolOrNull("videoMuted"),
            audioClips = obj.optJSONArray("audioClips")?.objects()?.map { decodeAudioClip(it) },
            videoRefSrcs = obj.optJSONArray("videoRefSrcs")?.strings(),
            videoVoices = obj.optJSONArray("videoVoices")?.strings(),
            fromSceneId = obj.optStringOrNull("fromSceneId"),
            fromSceneTitle = obj.optStringOrNull("fromSceneTitle"),
            fromFrameId = obj.optStringOrNull("fromFrameId"),
            fromIndex = obj.intOrNull("fromIndex"),
            holdSec = obj.doubleOrNull("holdSec"),
            generating = obj.boolOrNull("generating"),
            generatingError = obj.optStringOrNull("generatingError"),
            videoGenerating = obj.boolOrNull("videoGenerating"),
            videoGeneratingError = obj.optStringOrNull("videoGeneratingError"),
            uploading = obj.boolOrNull("uploading"),
            createdAt = obj.longOrNull("createdAt"),
            extra = extras(obj, REF_IMAGE_KEYS),
        )
    }

    private fun encodeRefImage(image: RefImage): JSONObject {
        val o = JSONObject()
        copyExtras(image.extra, o)
        o.put("id", image.id)
        o.put("src", image.src)
        o.put("caption", image.caption)
        image.videoPrompt?.let { o.put("videoPrompt", it) }
        image.dialogue?.let { o.put("dialogue", it) }
        image.notes?.let { o.put("notes", it) }
        image.videoSrc?.let { o.put("videoSrc", it) }
        image.origVideoSrc?.let { o.put("origVideoSrc", it) }
        image.origVideoDurationSec?.let { o.put("origVideoDurationSec", it) }
        image.videoInSec?.let { o.put("videoInSec", it) }
        image.videoOutSec?.let { o.put("videoOutSec", it) }
        image.videoDurationSec?.let { o.put("videoDurationSec", it) }
        image.videoFps?.let { o.put("videoFps", it) }
        image.videoMuted?.let { o.put("videoMuted", it) }
        image.audioClips?.let { clips ->
            o.put("audioClips", JSONArray().also { arr -> clips.forEach { arr.put(encodeAudioClip(it)) } })
        }
        image.videoRefSrcs?.let { o.put("videoRefSrcs", stringArray(it)) }
        image.videoVoices?.let { o.put("videoVoices", stringArray(it)) }
        image.fromSceneId?.let { o.put("fromSceneId", it) }
        image.fromSceneTitle?.let { o.put("fromSceneTitle", it) }
        image.fromFrameId?.let { o.put("fromFrameId", it) }
        image.fromIndex?.let { o.put("fromIndex", it) }
        image.holdSec?.let { o.put("holdSec", it) }
        image.generating?.let { o.put("generating", it) }
        image.generatingError?.let { o.put("generatingError", it) }
        image.videoGenerating?.let { o.put("videoGenerating", it) }
        image.videoGeneratingError?.let { o.put("videoGeneratingError", it) }
        image.uploading?.let { o.put("uploading", it) }
        image.createdAt?.let { o.put("createdAt", it) }
        return o
    }

    private fun decodeFolder(obj: JSONObject): RefFolder {
        return RefFolder(
            id = obj.optString("id"),
            name = obj.optString("name"),
            images = obj.optJSONArray("images").objects().map { decodeRefImage(it) },
            extra = extras(obj, FOLDER_KEYS),
        )
    }

    private fun encodeFolder(folder: RefFolder): JSONObject {
        val o = JSONObject()
        copyExtras(folder.extra, o)
        o.put("id", folder.id)
        o.put("name", folder.name)
        o.put("images", JSONArray().also { arr -> folder.images.forEach { arr.put(encodeRefImage(it)) } })
        return o
    }

    private fun decodeLayer(obj: JSONObject): MediaLayer {
        return MediaLayer(
            id = obj.optString("id"),
            kind = obj.optString("kind"),
            name = obj.optString("name"),
            clips = obj.optJSONArray("clips").objects().map { decodeLayerClip(it) },
            heightPx = obj.doubleOrNull("heightPx"),
            extra = extras(obj, LAYER_KEYS),
        )
    }

    private fun encodeLayer(layer: MediaLayer): JSONObject {
        val o = JSONObject()
        copyExtras(layer.extra, o)
        o.put("id", layer.id)
        o.put("kind", layer.kind)
        o.put("name", layer.name)
        o.put("clips", JSONArray().also { arr -> layer.clips.forEach { arr.put(encodeLayerClip(it)) } })
        layer.heightPx?.let { o.put("heightPx", it) }
        return o
    }

    private fun decodeLayerClip(obj: JSONObject): LayerClip {
        return LayerClip(
            id = obj.optString("id"),
            src = obj.optString("src"),
            name = obj.optString("name"),
            startSec = obj.doubleOr("startSec", 0.0),
            durationSec = obj.doubleOr("durationSec", 0.0),
            trimInSec = obj.doubleOrNull("trimInSec"),
            sourceDurationSec = obj.doubleOrNull("sourceDurationSec"),
            origSrc = obj.optStringOrNull("origSrc"),
            origDurationSec = obj.doubleOrNull("origDurationSec"),
            linkedFrameId = obj.optStringOrNull("linkedFrameId"),
            generating = obj.boolOrNull("generating"),
            waveform = copyJson(obj.opt("waveform")),
            volume = obj.doubleOrNull("volume"),
            envelope = obj.optJSONObject("envelope")?.let { JSONObject(it.toString()) },
            extra = extras(obj, CLIP_KEYS),
        )
    }

    private fun encodeLayerClip(clip: LayerClip): JSONObject {
        val o = JSONObject()
        copyExtras(clip.extra, o)
        o.put("id", clip.id)
        o.put("src", clip.src)
        o.put("name", clip.name)
        o.put("startSec", clip.startSec)
        o.put("durationSec", clip.durationSec)
        clip.trimInSec?.let { o.put("trimInSec", it) }
        clip.sourceDurationSec?.let { o.put("sourceDurationSec", it) }
        clip.origSrc?.let { o.put("origSrc", it) }
        clip.origDurationSec?.let { o.put("origDurationSec", it) }
        clip.linkedFrameId?.let { o.put("linkedFrameId", it) }
        clip.generating?.let { o.put("generating", it) }
        clip.waveform?.let { o.put("waveform", it) }
        clip.volume?.let { o.put("volume", it) }
        clip.envelope?.let { o.put("envelope", it) }
        return o
    }

    private fun decodeAudioClip(obj: JSONObject): AudioClip {
        return AudioClip(
            id = obj.optString("id"),
            src = obj.optString("src"),
            name = obj.optString("name"),
            offsetSec = obj.doubleOr("offsetSec", 0.0),
            durationSec = obj.doubleOr("durationSec", 0.0),
            trimInSec = obj.doubleOrNull("trimInSec"),
            extra = extras(obj, AUDIO_CLIP_KEYS),
        )
    }

    private fun encodeAudioClip(clip: AudioClip): JSONObject {
        val o = JSONObject()
        copyExtras(clip.extra, o)
        o.put("id", clip.id)
        o.put("src", clip.src)
        o.put("name", clip.name)
        o.put("offsetSec", clip.offsetSec)
        o.put("durationSec", clip.durationSec)
        clip.trimInSec?.let { o.put("trimInSec", it) }
        return o
    }

    private fun decodeMovie(obj: JSONObject): BoardMovie {
        return BoardMovie(
            src = obj.optString("src"),
            durationSec = obj.doubleOr("durationSec", 0.0),
            playDurationSec = obj.doubleOrNull("playDurationSec"),
            fps = obj.doubleOrNull("fps"),
            origSrc = obj.optStringOrNull("origSrc"),
            parts = obj.optJSONArray("parts").objects().map { decodePart(it) },
            extra = extras(obj, MOVIE_KEYS),
        )
    }

    private fun encodeMovie(movie: BoardMovie): JSONObject {
        val o = JSONObject()
        copyExtras(movie.extra, o)
        o.put("src", movie.src)
        o.put("durationSec", movie.durationSec)
        movie.playDurationSec?.let { o.put("playDurationSec", it) }
        movie.fps?.let { o.put("fps", it) }
        movie.origSrc?.let { o.put("origSrc", it) }
        o.put("parts", JSONArray().also { arr -> movie.parts.forEach { arr.put(encodePart(it)) } })
        return o
    }

    private fun decodePart(obj: JSONObject): MoviePart {
        return MoviePart(
            clipId = obj.optString("clipId"),
            src = obj.optString("src"),
            durationSec = obj.doubleOr("durationSec", 0.0),
            extra = extras(obj, PART_KEYS),
        )
    }

    private fun encodePart(part: MoviePart): JSONObject {
        val o = JSONObject()
        copyExtras(part.extra, o)
        o.put("clipId", part.clipId)
        o.put("src", part.src)
        o.put("durationSec", part.durationSec)
        return o
    }

    private fun decodeVideoGen(obj: JSONObject): VideoGenLock {
        return VideoGenLock(
            duration = obj.doubleOr("duration", 0.0),
            aspectRatio = obj.optString("aspectRatio"),
            resolution = obj.optString("resolution"),
            locked = obj.boolOrNull("locked"),
            extra = extras(obj, VIDEO_GEN_KEYS),
        )
    }

    private fun encodeVideoGen(lock: VideoGenLock): JSONObject {
        val o = JSONObject()
        copyExtras(lock.extra, o)
        o.put("duration", lock.duration)
        o.put("aspectRatio", lock.aspectRatio)
        o.put("resolution", lock.resolution)
        lock.locked?.let { o.put("locked", it) }
        return o
    }

    private fun decodeLibraryAudio(obj: JSONObject): LibraryAudio {
        return LibraryAudio(
            id = obj.optString("id"),
            src = obj.optString("src"),
            name = obj.optString("name"),
            durationSec = obj.doubleOr("durationSec", 0.0),
            waveform = copyJson(obj.opt("waveform")),
            createdAt = obj.longOrNull("createdAt"),
            deletedAt = obj.longOrNull("deletedAt"),
            uploading = obj.boolOrNull("uploading"),
            extra = extras(obj, LIBRARY_AUDIO_KEYS),
        )
    }

    private fun encodeLibraryAudio(item: LibraryAudio): JSONObject {
        val o = JSONObject()
        copyExtras(item.extra, o)
        o.put("id", item.id)
        o.put("src", item.src)
        o.put("name", item.name)
        o.put("durationSec", item.durationSec)
        item.waveform?.let { o.put("waveform", it) }
        item.createdAt?.let { o.put("createdAt", it) }
        item.deletedAt?.let { o.put("deletedAt", it) }
        item.uploading?.let { o.put("uploading", it) }
        return o
    }

    private fun decodeLibraryVideo(obj: JSONObject): LibraryVideo {
        return LibraryVideo(
            id = obj.optString("id"),
            src = obj.optString("src"),
            name = obj.optString("name"),
            durationSec = obj.doubleOr("durationSec", 0.0),
            createdAt = obj.longOrNull("createdAt"),
            deletedAt = obj.longOrNull("deletedAt"),
            uploading = obj.boolOrNull("uploading"),
            extra = extras(obj, LIBRARY_VIDEO_KEYS),
        )
    }

    private fun encodeLibraryVideo(item: LibraryVideo): JSONObject {
        val o = JSONObject()
        copyExtras(item.extra, o)
        o.put("id", item.id)
        o.put("src", item.src)
        o.put("name", item.name)
        o.put("durationSec", item.durationSec)
        item.createdAt?.let { o.put("createdAt", it) }
        item.deletedAt?.let { o.put("deletedAt", it) }
        item.uploading?.let { o.put("uploading", it) }
        return o
    }

    private fun extras(obj: JSONObject, known: Set<String>): JSONObject? {
        val extra = JSONObject()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k !in known) extra.put(k, copyJson(obj.get(k)))
        }
        return extra.takeIf { it.length() > 0 }
    }

    private fun copyExtras(extra: JSONObject?, dest: JSONObject) {
        if (extra == null) return
        val keys = extra.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            dest.put(k, extra.get(k))
        }
    }

    private fun copyJson(value: Any?): Any? {
        return when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> JSONObject(value.toString())
            is JSONArray -> JSONArray(value.toString())
            else -> value
        }
    }

    private fun stringArray(values: List<String>): JSONArray {
        val arr = JSONArray()
        values.forEach { arr.put(it) }
        return arr
    }
}

private fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    val out = ArrayList<JSONObject>(length())
    for (i in 0 until length()) {
        val o = optJSONObject(i) ?: continue
        out.add(o)
    }
    return out
}

private fun JSONArray.strings(): List<String> {
    val out = ArrayList<String>(length())
    for (i in 0 until length()) {
        if (isNull(i)) continue
        out.add(optString(i))
    }
    return out
}

private fun JSONObject.doubleOrNull(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return try {
        when (val v = get(key)) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

private fun JSONObject.doubleOr(key: String, default: Double): Double = doubleOrNull(key) ?: default

private fun JSONObject.longOrNull(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return try {
        when (val v = get(key)) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull()
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

private fun JSONObject.intOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return try {
        when (val v = get(key)) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull()
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

private fun JSONObject.boolOrNull(key: String): Boolean? {
    if (!has(key) || isNull(key)) return null
    return try {
        when (val v = get(key)) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v == "1" || v.equals("true", ignoreCase = true)
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}
