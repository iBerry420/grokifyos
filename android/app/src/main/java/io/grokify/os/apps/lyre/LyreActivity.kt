package io.grokify.os.apps.lyre

import org.json.JSONObject
import java.io.File

data class LyreActivityLine(
    val ts: Long,
    val type: String,
    val projectId: String,
    val sceneId: String? = null,
    val frameId: String? = null,
    val clipId: String? = null,
    val summary: String,
    val actor: String? = null,
) {
    val jumpable: Boolean get() = !frameId.isNullOrEmpty() || !clipId.isNullOrEmpty() || !sceneId.isNullOrEmpty()

    fun displaySummary(): String =
        if (actor == "bot") "Bot · $summary" else summary

    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("ts", ts)
            .put("type", type)
            .put("projectId", projectId)
            .put("summary", summary)
        sceneId?.takeIf { it.isNotEmpty() }?.let { o.put("sceneId", it) }
        frameId?.takeIf { it.isNotEmpty() }?.let { o.put("frameId", it) }
        clipId?.takeIf { it.isNotEmpty() }?.let { o.put("clipId", it) }
        actor?.takeIf { it.isNotEmpty() }?.let { o.put("actor", it) }
        return o
    }

    companion object {
        fun fromJson(obj: JSONObject): LyreActivityLine? {
            val summary = obj.optString("summary").trim().ifEmpty {
                obj.optString("text").trim()
            }
            if (summary.isEmpty()) return null
            val ts = when (val v = obj.opt("ts")) {
                is Number -> v.toLong()
                is String -> v.toLongOrNull() ?: return null
                else -> return null
            }
            if (ts <= 0L) return null
            return LyreActivityLine(
                ts = ts,
                type = obj.optString("type").ifBlank { "event" },
                projectId = obj.optString("projectId"),
                sceneId = obj.optStringOrNull("sceneId"),
                frameId = obj.optStringOrNull("frameId"),
                clipId = obj.optStringOrNull("clipId"),
                summary = summary,
                actor = obj.optStringOrNull("actor"),
            )
        }
    }
}

class LyreActivity(private val file: File) {
    private val lock = Any()

    fun append(line: LyreActivityLine) {
        synchronized(lock) {
            file.parentFile?.mkdirs()
            file.appendText(line.toJson().toString() + "\n", Charsets.UTF_8)
        }
    }

    fun readNewestFirst(): List<LyreActivityLine> {
        val lines = readAll()
        return lines.asReversed()
    }

    fun isEmpty(): Boolean = synchronized(lock) { !file.isFile || file.length() <= 0L }

    fun replaceFromServer(newestFirst: List<LyreActivityLine>) {
        synchronized(lock) {
            file.parentFile?.mkdirs()
            if (newestFirst.isEmpty()) {
                file.writeText("", Charsets.UTF_8)
                return
            }
            val oldestFirst = newestFirst.asReversed()
            val body = oldestFirst.joinToString(separator = "\n", postfix = "\n") { it.toJson().toString() }
            file.writeText(body, Charsets.UTF_8)
        }
    }

    fun readAll(): List<LyreActivityLine> {
        synchronized(lock) {
            if (!file.isFile || file.length() <= 0L) return emptyList()
            val out = ArrayList<LyreActivityLine>()
            file.forEachLine(Charsets.UTF_8) { raw ->
                val text = raw.trim()
                if (text.isEmpty()) return@forEachLine
                val parsed = try {
                    LyreActivityLine.fromJson(JSONObject(text))
                } catch (_: Exception) {
                    null
                }
                if (parsed != null) out += parsed
            }
            return out
        }
    }

    fun seedFromBoardIfEmpty(board: BoardData, projectId: String) {
        synchronized(lock) {
            file.parentFile?.mkdirs()
            if (file.isFile && file.length() > 0L) return
            val now = System.currentTimeMillis()
            val pid = projectId
            val lines = ArrayList<LyreActivityLine>()
            val title = board.title.ifBlank { "Untitled" }
            lines += LyreActivityLine(
                ts = now - 1_000L,
                type = "seed",
                projectId = pid,
                summary = "Loaded $title from desktop LYRE",
            )
            board.scenes.forEachIndexed { sceneIndex, scene ->
                val sceneTs = scene.frames.mapNotNull { it.createdAt }.minOrNull()
                    ?: (now - (board.scenes.size - sceneIndex) * 60_000L)
                lines += LyreActivityLine(
                    ts = sceneTs,
                    type = "scene",
                    projectId = pid,
                    sceneId = scene.id,
                    summary = "Scene · ${scene.title.ifBlank { "Untitled" }} · ${scene.frames.size} stills",
                )
                scene.frames.forEachIndexed { i, frame ->
                    val clip = board.videoLayers.flatMap { it.clips }.firstOrNull { it.linkedFrameId == frame.id }
                    val kind = if (!frame.videoSrc.isNullOrEmpty() || clip != null) "clip" else "still"
                    val label = frame.caption.ifBlank { frame.id }
                    val extra = if (kind == "clip") " · video" else ""
                    lines += LyreActivityLine(
                        ts = frame.createdAt ?: (sceneTs + i + 1L),
                        type = kind,
                        projectId = pid,
                        sceneId = scene.id,
                        frameId = frame.id,
                        clipId = clip?.id,
                        summary = label + extra,
                    )
                }
            }
            board.movie?.takeIf { it.src.isNotEmpty() }?.let { movie ->
                lines += LyreActivityLine(
                    ts = now - 500L,
                    type = "movie",
                    projectId = pid,
                    clipId = "lc_movie",
                    summary = "Movie · ${"%.1f".format(movie.durationSec)}s · ${movie.parts.size} parts",
                )
            }
            board.libraryVideo.filter { it.deletedAt == null }.forEach { item ->
                lines += LyreActivityLine(
                    ts = item.createdAt ?: now,
                    type = "library",
                    projectId = pid,
                    summary = "Library video · ${item.name.ifBlank { item.id }}",
                )
            }
            board.libraryAudio.filter { it.deletedAt == null }.forEach { item ->
                lines += LyreActivityLine(
                    ts = item.createdAt ?: now,
                    type = "library",
                    projectId = pid,
                    summary = "Library audio · ${item.name.ifBlank { item.id }}",
                )
            }
            val dumped = board.refFolders.flatMap { it.images }.count {
                !it.fromFrameId.isNullOrEmpty() || !it.fromSceneId.isNullOrEmpty()
            }
            if (dumped > 0) {
                lines += LyreActivityLine(
                    ts = now - 250L,
                    type = "bin",
                    projectId = pid,
                    summary = "Bin · $dumped dumped stills",
                )
            }
            if (file.isFile && file.length() > 0L) return
            val body = lines.joinToString(separator = "\n", postfix = "\n") { it.toJson().toString() }
            file.writeText(body, Charsets.UTF_8)
        }
    }
}

fun lyreJumpTime(board: BoardData, line: LyreActivityLine): Double? {
    val frameId = line.frameId
    if (!frameId.isNullOrEmpty()) {
        return LyreClip.clipOf(board.scenes, frameId)?.start
    }
    val clipId = line.clipId
    if (!clipId.isNullOrEmpty()) {
        if (clipId == "lc_movie") {
            val first = LyreClip.movieClips(board.scenes).firstOrNull {
                LyreMovie.frameInMovie(board.movie, board.videoLayers, it.frame.id)
            }
            return first?.start ?: 0.0
        }
        val linked = board.videoLayers.asSequence().flatMap { it.clips.asSequence() }
            .firstOrNull { it.id == clipId }?.linkedFrameId
        if (!linked.isNullOrEmpty()) {
            return LyreClip.clipOf(board.scenes, linked)?.start
        }
    }
    val sceneId = line.sceneId
    if (!sceneId.isNullOrEmpty()) {
        return LyreClip.movieClips(board.scenes).firstOrNull { it.sceneId == sceneId }?.start
    }
    return null
}
