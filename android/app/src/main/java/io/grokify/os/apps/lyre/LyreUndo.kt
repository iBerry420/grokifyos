package io.grokify.os.apps.lyre

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class UndoType {
    STITCH,
    POP,
    TRIM,
    MUTE,
    SPLIT,
    EXTRACT,
    BURN_AUDIO,
    RESTORE,
    REMOVE,
    OTHER,
}

data class FileSwap(val liveRel: String, val savedRel: String)

data class UndoEntry(
    val seq: Int,
    val type: UndoType,
    val atMs: Long,
    val boardBefore: String,
    val boardAfter: String,
    val files: List<FileSwap>,
)

data class UndoStaging(
    val seq: Int,
    val dir: File,
    val files: List<FileSwap>,
    val boardBefore: String,
    val type: UndoType,
    val atMs: Long,
)

class LyreUndo(private val cache: LyreCache) {
    fun nextSeq(boardId: String): Int {
        val dirs = cache.undoDir(boardId).listFiles()?.filter { it.isDirectory }.orEmpty()
        val max = dirs.maxOfOrNull { it.name.toIntOrNull() ?: 0 } ?: 0
        return max + 1
    }

    fun stage(
        boardId: String,
        type: UndoType,
        boardBefore: String,
        liveKeys: List<String>,
    ): UndoStaging {
        val seq = nextSeq(boardId)
        val dir = File(cache.undoDir(boardId), seq.toString())
        dir.mkdirs()
        val swaps = ArrayList<FileSwap>()
        for (key in liveKeys.distinct()) {
            val live = cache.objectFile(boardId, key)
            if (!live.isFile || live.length() <= 0L) continue
            val savedName = "${live.name}"
            val saved = File(dir, savedName)
            cache.linkOrCopy(live, saved)
            val savedRel = "undo/$seq/$savedName"
            swaps.add(FileSwap(liveRel = key, savedRel = savedRel))
        }
        val meta = JSONObject()
            .put("seq", seq)
            .put("type", type.name)
            .put("atMs", System.currentTimeMillis())
            .put("boardBefore", boardBefore)
            .put("boardAfter", "")
            .put("files", swapsToJson(swaps))
        File(dir, "meta.json").writeText(meta.toString())
        return UndoStaging(
            seq = seq,
            dir = dir,
            files = swaps,
            boardBefore = boardBefore,
            type = type,
            atMs = System.currentTimeMillis(),
        )
    }

    fun push(staging: UndoStaging, boardAfter: String) {
        val meta = JSONObject()
            .put("seq", staging.seq)
            .put("type", staging.type.name)
            .put("atMs", staging.atMs)
            .put("boardBefore", staging.boardBefore)
            .put("boardAfter", boardAfter)
            .put("files", swapsToJson(staging.files))
        File(staging.dir, "meta.json").writeText(meta.toString())
    }

    fun discard(staging: UndoStaging) {
        staging.dir.deleteRecursively()
    }

    fun restoreLive(boardId: String, staging: UndoStaging) {
        staging.files.forEach { swap ->
            val saved = File(cache.boardDir(boardId), swap.savedRel)
            if (!saved.isFile || saved.length() <= 0L) return@forEach
            cache.copyReplace(saved, cache.objectFile(boardId, swap.liveRel))
        }
    }

    fun entries(boardId: String): List<UndoEntry> {
        return cache.undoDir(boardId).listFiles()
            ?.filter { it.isDirectory && File(it, "meta.json").isFile }
            ?.mapNotNull { readEntry(it) }
            ?.sortedBy { it.seq }
            .orEmpty()
    }

    fun popLast(boardId: String): UndoEntry? {
        val last = entries(boardId).lastOrNull() ?: return null
        val dir = File(cache.undoDir(boardId), last.seq.toString())
        last.files.forEach { swap ->
            val saved = File(cache.boardDir(boardId), swap.savedRel)
            if (!saved.isFile || saved.length() <= 0L) return@forEach
            cache.copyReplace(saved, cache.objectFile(boardId, swap.liveRel))
        }
        dir.deleteRecursively()
        return last
    }

    fun dropOldestBeyond(boardId: String, cap: Int = LyreCache.UNDO_CAP) {
        val all = entries(boardId)
        if (all.size <= cap) return
        all.dropLast(cap).forEach { e ->
            File(cache.undoDir(boardId), e.seq.toString()).deleteRecursively()
        }
    }

    private fun readEntry(dir: File): UndoEntry? {
        return try {
            val o = JSONObject(File(dir, "meta.json").readText())
            val files = o.optJSONArray("files") ?: JSONArray()
            val swaps = ArrayList<FileSwap>(files.length())
            for (i in 0 until files.length()) {
                val s = files.optJSONObject(i) ?: continue
                swaps.add(FileSwap(s.optString("liveRel"), s.optString("savedRel")))
            }
            UndoEntry(
                seq = o.optInt("seq"),
                type = runCatching { UndoType.valueOf(o.optString("type")) }.getOrDefault(UndoType.OTHER),
                atMs = o.optLong("atMs"),
                boardBefore = o.optString("boardBefore"),
                boardAfter = o.optString("boardAfter"),
                files = swaps,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun swapsToJson(swaps: List<FileSwap>): JSONArray {
        val arr = JSONArray()
        swaps.forEach { s ->
            arr.put(JSONObject().put("liveRel", s.liveRel).put("savedRel", s.savedRel))
        }
        return arr
    }
}

fun undoTypeOf(plan: CutPlan?): UndoType {
    return when (plan?.kind) {
        CutKind.STITCH -> UndoType.STITCH
        CutKind.POP -> UndoType.POP
        CutKind.TRIM -> UndoType.TRIM
        CutKind.MUTE -> UndoType.MUTE
        CutKind.SPLIT -> UndoType.SPLIT
        CutKind.EXTRACT -> UndoType.EXTRACT
        CutKind.BURN_AUDIO -> UndoType.BURN_AUDIO
        null -> UndoType.OTHER
    }
}
