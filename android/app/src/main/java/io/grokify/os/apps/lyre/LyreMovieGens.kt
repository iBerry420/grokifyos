package io.grokify.os.apps.lyre

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

fun pictureCompileKey(movie: BoardMovie): String? {
    return when {
        movie.src.endsWith("/movie.burn.mp4") ->
            movie.origSrc?.takeIf { it.isNotBlank() }
        else -> movie.src.takeIf { it.isNotBlank() }
    }
}

class LyreMovieGens(
    private val cache: LyreCache,
    private val probe: suspend (File) -> Probe,
    private val enqueue: (boardId: String, op: LyrePendingOp) -> Unit,
) {
    fun has(boardId: String, n: Int): Boolean {
        val f = cache.genFile(boardId, n)
        return f.isFile && f.length() > 0L
    }

    fun genFile(boardId: String, n: Int): File = cache.genFile(boardId, n)

    /**
     * Call on STITCH after undo staging, before Transformer may replace movie.mp4.
     * [preStitch] is LyreSession.board.movie — not RuleResult.board.movie.
     */
    suspend fun ensureCurrent(preStitch: BoardMovie, boardId: String): Boolean {
        if (preStitch.parts.size < 2) return true
        val n = preStitch.parts.size - 1
        if (has(boardId, n)) return true
        cache.resolve(boardId, "boards/$boardId/movie.g$n.mp4")
            ?.takeIf { it.length() > 0L }
            ?.let { uploaded ->
                copyIntoGen(boardId, n, uploaded, partCount = preStitch.parts.size)
                return true
            }
        val pictureKey = pictureCompileKey(preStitch) ?: return false
        val picture = cache.resolve(boardId, pictureKey)?.takeIf { it.length() > 0L } ?: return false
        copyIntoGen(boardId, n, picture, partCount = preStitch.parts.size)
        enqueue(
            boardId,
            LyrePendingOp(
                seq = 0,
                type = "storage_put",
                key = "boards/$boardId/movie.g$n.mp4",
                localPath = "movie-gens/g$n.mp4",
                createdAtMs = System.currentTimeMillis(),
            ),
        )
        return true
    }

    suspend fun restore(n: Int, boardId: String): File? {
        if (has(boardId, n)) return genFile(boardId, n)
        val uploaded = cache.resolve(boardId, "boards/$boardId/movie.g$n.mp4")
            ?.takeIf { it.length() > 0L } ?: return null
        copyIntoGen(boardId, n, uploaded, partCount = n + 1)
        return genFile(boardId, n)
    }

    suspend fun push(boardId: String, n: Int, cut: CutOk, partCount: Int) {
        copyIntoGen(boardId, n, cut.file, partCount, cut.durationSec, cut.fps)
        enqueue(
            boardId,
            LyrePendingOp(
                seq = 0,
                type = "storage_put",
                key = "boards/$boardId/movie.g$n.mp4",
                localPath = "movie-gens/g$n.mp4",
                createdAtMs = System.currentTimeMillis(),
            ),
        )
    }

    /** Delete local g{k} for k > n (n=0 drops all). Local only. */
    fun dropAbove(boardId: String, n: Int) {
        val dir = cache.gensDir(boardId)
        dir.listFiles()?.forEach { f ->
            val m = GEN_NAME.matchEntire(f.name) ?: return@forEach
            val k = m.groupValues[1].toIntOrNull() ?: return@forEach
            if (k > n) f.delete()
        }
        val meta = loadMeta(boardId)
        val kept = meta.filter { it.optInt("n") <= n }
        saveMeta(boardId, kept)
    }

    fun durationFps(boardId: String, n: Int): Pair<Double, Double>? {
        val row = loadMeta(boardId).firstOrNull { it.optInt("n") == n } ?: return null
        val dur = row.optDouble("durationSec", Double.NaN)
        val fps = row.optDouble("fps", Double.NaN)
        if (dur.isNaN() || fps.isNaN()) return null
        return dur to fps
    }

    private suspend fun copyIntoGen(
        boardId: String,
        n: Int,
        src: File,
        partCount: Int,
        durationSec: Double? = null,
        fps: Double? = null,
    ) {
        val dest = cache.genFile(boardId, n)
        cache.copyReplace(src, dest)
        val p = if (durationSec != null && fps != null) {
            null
        } else {
            probe(dest)
        }
        val dur = durationSec ?: p!!.durationSec
        val rate = fps ?: p!!.fps
        val key = "boards/$boardId/movie.g$n.mp4"
        cache.copyReplace(dest, cache.objectFile(boardId, key))
        val rows = loadMeta(boardId).filterNot { it.optInt("n") == n }.toMutableList()
        rows.add(
            JSONObject()
                .put("n", n)
                .put("partCount", partCount)
                .put("durationSec", dur)
                .put("fps", rate)
                .put("key", key),
        )
        rows.sortBy { it.optInt("n") }
        saveMeta(boardId, rows)
    }

    private fun loadMeta(boardId: String): List<JSONObject> {
        val f = File(cache.gensDir(boardId), "meta.json")
        if (!f.isFile || f.length() <= 0L) return emptyList()
        return try {
            val arr = JSONObject(f.readText()).optJSONArray("generations") ?: JSONArray()
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveMeta(boardId: String, rows: List<JSONObject>) {
        val arr = JSONArray()
        rows.forEach { arr.put(it) }
        File(cache.gensDir(boardId), "meta.json").writeText(
            JSONObject().put("generations", arr).toString(),
        )
    }

    companion object {
        private val GEN_NAME = Regex("""g(\d+)\.mp4""")
    }
}
