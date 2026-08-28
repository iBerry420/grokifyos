package io.grokify.os.apps.lyre

import java.io.File

/**
 * Desktop LYRE stores objects as `me:stills/…`, `me:videos/…`, `me:audio/…`
 * (grokme `/v1/storage/{stills|videos|audio}/…`). Seed stills use `/stills/….jpg`.
 * Phone-only writes use `boards/{id}/…`.
 */
object LyreStorageKeys {
    private val BOARD = Regex("^boards/[A-Za-z0-9_./-]+$")
    private val WATCH = Regex("^public/watch/[a-f0-9]{32}\\.mp4$")
    private val MEDIA = Regex("^(stills|videos|audio|seed/stills|public/stills)/[A-Za-z0-9_./-]+$")
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif")
    private val AUDIO_EXT = setOf("wav", "mp3", "m4a", "aac", "ogg", "flac")
    private val VIDEO_EXT = setOf("mp4", "webm", "mov", "m4v")

    fun normalize(raw: String): String? {
        var key = raw.trim()
        if (key.isEmpty()) return null

        val storage = key.indexOf("/v1/storage/")
        if (storage >= 0) {
            key = decodePath(key.substring(storage + "/v1/storage/".length).substringBefore('?'))
        } else {
            val api = key.indexOf("/api/storage/")
            if (api >= 0) {
                key = decodePath(key.substring(api + "/api/storage/".length).substringBefore('?'))
            } else if (key.contains("/api/media")) {
                val q = key.substringAfter('?', "")
                key = q.split('&').firstNotNullOfOrNull { part ->
                    val eq = part.indexOf('=')
                    if (eq < 0) null
                    else {
                        val name = part.substring(0, eq)
                        val value = part.substring(eq + 1)
                        if (name == "p") decodePath(value) else null
                    }
                }.orEmpty()
            }
        }

        if (key.startsWith("me:", ignoreCase = true)) {
            key = key.substring(3)
        }
        key = key.replace('\\', '/').trimStart('/')
        if (key.isEmpty() || key.contains("..") || key.contains('\u0000')) return null
        if (BOARD.matches(key) || WATCH.matches(key) || MEDIA.matches(key)) return key
        return null
    }

    fun stillKeys(board: BoardData): List<String> = imageKeys(board)

    fun imageKeys(board: BoardData): List<String> = objectKeys(board).filter(::isStillSrc)

    fun audioKeys(board: BoardData): List<String> = objectKeys(board).filter(::isAudioSrc)

    fun isStillSrc(raw: String): Boolean {
        if (raw.isBlank()) return false
        val n = (normalize(raw) ?: raw.trim().replace('\\', '/').trimStart('/')).lowercase()
        val ext = n.substringAfterLast('.', "")
        if (ext in VIDEO_EXT || ext in AUDIO_EXT) return false
        if (ext in IMAGE_EXT) return true
        return n.startsWith("stills/") || n.startsWith("seed/stills/") || n.startsWith("public/stills/")
    }

    fun isAudioSrc(raw: String): Boolean {
        if (raw.isBlank()) return false
        val n = (normalize(raw) ?: raw.trim().replace('\\', '/').trimStart('/')).lowercase()
        if (n.startsWith("audio/")) return true
        return n.substringAfterLast('.', "") in AUDIO_EXT
    }

    fun isStillFile(file: File): Boolean = file.extension.lowercase() in IMAGE_EXT

    /** Odysseus keeps root `me:stills|videos|audio/`. New projects write `me:boards/{boardId}/…`. */
    fun writeKey(boardId: String, isOdysseus: Boolean, kind: String, id: String, ext: String): String {
        val path = if (isOdysseus) "$kind/$id.$ext" else "boards/$boardId/$kind/$id.$ext"
        return "me:$path"
    }

    /** Scene stills first, then picture-compile / leftover video, then library stills, then audio. */
    fun objectKeys(board: BoardData): List<String> {
        val out = LinkedHashSet<String>()
        fun add(raw: String?) {
            if (raw.isNullOrEmpty()) return
            out += raw
        }
        board.scenes.forEach { scene ->
            scene.frames.forEach { add(it.src) }
        }
        board.movie?.src?.let { add(it) }
        board.videoLayers.forEach { layer ->
            layer.clips.forEach { add(it.src) }
        }
        board.scenes.forEach { scene ->
            scene.frames.forEach { add(it.videoSrc) }
        }
        board.refFolders.forEach { folder ->
            folder.images.forEach { add(it.src) }
        }
        board.audioLayers.forEach { layer ->
            layer.clips.forEach { add(it.src) }
        }
        board.libraryAudio.filter { it.deletedAt == null }.forEach { add(it.src) }
        board.libraryVideo.filter { it.deletedAt == null }.forEach { add(it.src) }
        board.refFolders.forEach { folder ->
            folder.images.forEach { add(it.videoSrc) }
        }
        return out.toList()
    }

    fun index(dest: MutableMap<String, File>, raw: String, file: File) {
        dest[raw] = file
        val n = normalize(raw) ?: return
        dest[n] = file
        dest["me:$n"] = file
        dest["/$n"] = file
    }

    fun file(files: Map<String, File>, src: String?): File? {
        if (src.isNullOrEmpty()) return null
        files[src]?.let { return it }
        val n = normalize(src) ?: return null
        return files[n] ?: files["me:$n"] ?: files["/$n"]
    }

    fun posterSrc(board: BoardData, videoSrc: String?): String? {
        if (videoSrc.isNullOrEmpty()) return null
        val n = normalize(videoSrc)
        board.refFolders.forEach { folder ->
            folder.images.forEach { image ->
                if (image.src.isEmpty()) return@forEach
                if (image.videoSrc == videoSrc) return image.src
                if (n != null && normalize(image.videoSrc ?: "") == n) return image.src
            }
        }
        board.scenes.forEach { scene ->
            scene.frames.forEach { frame ->
                if (frame.src.isEmpty()) return@forEach
                if (frame.videoSrc == videoSrc) return frame.src
                if (n != null && normalize(frame.videoSrc ?: "") == n) return frame.src
            }
        }
        return null
    }

    fun timelineDuration(board: BoardData): Double {
        val picture = LyreClip.movieDuration(board.scenes)
        var end = picture
        board.videoLayers.forEach { layer ->
            layer.clips.forEach { clip ->
                val e = clip.startSec + clip.durationSec
                if (e > end) end = e
            }
        }
        board.audioLayers.forEach { layer ->
            layer.clips.forEach { clip ->
                val e = clip.startSec + clip.durationSec
                if (e > end) end = e
            }
        }
        board.movie?.let { movie ->
            val play = LyreMovie.moviePlayDuration(movie)
            if (play > end) end = play
        }
        return end.coerceAtLeast(0.1)
    }

    private fun decodePath(value: String): String {
        return try {
            java.net.URLDecoder.decode(value.replace('+', ' '), "UTF-8")
        } catch (_: Exception) {
            value
        }
    }
}
