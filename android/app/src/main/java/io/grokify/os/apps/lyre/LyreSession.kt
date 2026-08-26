package io.grokify.os.apps.lyre

import android.app.Application
import android.net.Uri
import android.util.Log
import io.grokify.os.GrokifyApp
import io.grokify.os.apps.plugin.HostApiKeyStore
import io.grokify.os.data.ApiKeyIds
import io.grokify.os.data.TokenStore
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

data class LyreJob(
    val kind: CutKind,
    val boardId: String,
    val startedAtMs: Long = System.currentTimeMillis(),
)

class CutFailedException(reason: String) : Exception(reason)

class LyreSession(
    app: Application,
    val api: LyreApi = defaultApi(app),
    val cache: LyreCache = LyreCache(app, api),
    cutterOverride: LyreCutter? = null,
    imagineOverride: LyreImagineClient? = null,
) {
    private val appCtx = app.applicationContext
    private val store = LyreStore(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val flushMutex = Mutex()
    private val boardEpoch = AtomicInteger(0)
    private val museLock = AtomicBoolean(false)
    private val cutter: LyreCutter = cutterOverride ?: Media3LyreCutter(appCtx) {
        File(cache.boardDir(requireBoardId()), "tmp")
    }
    private val undo = LyreUndo(cache)
    private val gens = LyreMovieGens(cache, { file -> cutter.probe(file) }) { bid, op ->
        enqueuePendingFor(bid, op)
    }
    private val imagine: LyreImagineClient = imagineOverride ?: LyreImagineClient(
        api,
        xaiKey = { HostApiKeyStore.getValue(appCtx, ApiKeyIds.SPACEXAI) },
    )

    private val _board = MutableStateFlow<BoardData?>(null)
    val board: StateFlow<BoardData?> = _board

    private val _boardId = MutableStateFlow<String?>(null)
    val boundBoardId: StateFlow<String?> = _boardId

    private val _busy = MutableStateFlow<LyreJob?>(null)
    val busy: StateFlow<LyreJob?> = _busy

    private val _imagineBusy = MutableStateFlow(false)
    val imagineBusy: StateFlow<Boolean> = _imagineBusy

    private val _project = MutableStateFlow<LyreProject?>(null)
    val project: StateFlow<LyreProject?> = _project

    private val _watchBusy = MutableStateFlow(false)
    val watchBusy: StateFlow<Boolean> = _watchBusy

    private val _watchError = MutableStateFlow<String?>(null)
    val watchError: StateFlow<String?> = _watchError

    private val _museMessages = MutableStateFlow<List<LyreMuseMessage>>(emptyList())
    val museMessages: StateFlow<List<LyreMuseMessage>> = _museMessages

    private val _museBusy = MutableStateFlow(false)
    val museBusy: StateFlow<Boolean> = _museBusy

    @Volatile
    private var running: Job? = null

    init {
        LyreCutService.timeoutHandler = {
            running?.cancel(CancellationException("cut_failed: timeout"))
        }
    }

    fun bind(boardId: String, board: BoardData) {
        boardEpoch.incrementAndGet()
        _boardId.value = boardId
        _board.value = board
        cache.writeBoardJson(boardId, LyreBoardCodec.encode(board))
        seedMuse(boardId, board)
        scope.launch { flushPending(boardId) }
    }

    fun bindProject(project: LyreProject?) {
        _project.value = project
        _watchError.value = null
    }

    fun insertAudioUri(frameId: String, uri: Uri, name: String) {
        if (_busy.value != null || _imagineBusy.value) return
        val epoch = boardEpoch.get()
        running = scope.launch {
            mutex.withLock {
                val tmp = cache.tmpFile(requireBoardId(), ".bin")
                val copied = runCatching {
                    appCtx.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                    tmp
                }.getOrNull()
                if (copied == null || !copied.isFile || copied.length() <= 0L) {
                    val id = _boardId.value ?: return@withLock
                    val board = _board.value ?: return@withLock
                    fail(id, board, "cut_failed: extract", frameId)
                    return@withLock
                }
                insertAudioLocked(frameId, copied, name.ifBlank { "Audio" }, epoch)
            }
        }
    }

    fun insertAudioFile(frameId: String, srcFile: File, name: String) {
        if (_busy.value != null || _imagineBusy.value) return
        val epoch = boardEpoch.get()
        running = scope.launch {
            mutex.withLock { insertAudioLocked(frameId, srcFile, name, epoch) }
        }
    }

    fun askMuse(userText: String, playheadSec: Double) {
        val prompt = userText.trim()
        if (prompt.isEmpty()) return
        if (!museLock.compareAndSet(false, true)) return
        _museBusy.value = true
        scope.launch {
            val id = _boardId.value
            try {
                if (id == null) return@launch
                appendMuse(id, LyreMuseMessage("user", prompt))
                val board = _board.value ?: return@launch
                val projectName = _project.value?.name?.ifBlank { board.title } ?: board.title
                val digest = LyreMuse.digest(
                    board,
                    projectName,
                    playheadSec,
                    LyreActivity.last(cache, id, 8),
                )
                val raw = LyreMuse.complete(appCtx, prompt, digest)
                val (ok, text) = LyreMuse.parseReply(raw)
                appendMuse(id, LyreMuseMessage(if (ok) "muse" else "error", text))
                persistWebMuse(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                id?.let { appendMuse(it, LyreMuseMessage("error", e.message ?: "muse_failed")) }
            } finally {
                _museBusy.value = false
                museLock.set(false)
            }
        }
    }

    fun publish(makePublic: Boolean) {
        if (_watchBusy.value) return
        scope.launch {
            mutex.withLock { publishLocked(makePublic) }
        }
    }

    fun apply(result: RuleResult) {
        if (_busy.value != null || _imagineBusy.value) return
        val epoch = boardEpoch.get()
        running = scope.launch {
            mutex.withLock { applyLocked(result, epoch) }
        }
    }

    suspend fun applyAwait(result: RuleResult) {
        val epoch = boardEpoch.get()
        mutex.withLock { applyLocked(result, epoch) }
    }

    fun flushSave() {
        if (_busy.value != null) return
        val id = _boardId.value ?: return
        val b = _board.value ?: return
        val data = LyreBoardCodec.encode(b)
        cache.writeBoardJson(id, data)
        enqueuePendingFor(
            id,
            LyrePendingOp(
                seq = 0,
                type = "save_board",
                createdAtMs = System.currentTimeMillis(),
            ),
            snapshotJson = data,
        )
        scope.launch { flushPending(id) }
    }

    fun enqueuePending(op: LyrePendingOp) {
        val id = _boardId.value ?: return
        enqueuePendingFor(id, op)
    }

    fun undo() {
        scope.launch {
            mutex.withLock {
                if (_busy.value != null) return@withLock
                val id = _boardId.value ?: return@withLock
                val entry = undo.popLast(id) ?: return@withLock
                val restored = LyreBoardCodec.decode(JSONObject(entry.boardBefore))
                boardEpoch.incrementAndGet()
                _board.value = restored
                cache.writeBoardJson(id, JSONObject(entry.boardBefore))
                activity(id, "undo", "Undo ${entry.type.name.lowercase()}", null, null, null)
            }
        }
    }

    fun undoCount(): Int {
        val id = _boardId.value ?: return 0
        return undo.entries(id).size
    }

    fun movieGens(): LyreMovieGens = gens

    private suspend fun insertAudioLocked(frameId: String, srcFile: File, name: String, capturedEpoch: Int) {
        val id = _boardId.value ?: return
        val board = _board.value ?: return
        if (capturedEpoch != boardEpoch.get()) return
        if (_busy.value != null) return
        _busy.value = LyreJob(CutKind.EXTRACT, id)
        try {
            var input = srcFile
            var duration = 0.1
            try {
                val probe = cutter.probe(srcFile)
                if (probe.durationSec > 0.0) duration = probe.durationSec
                if (probe.width > 0 && probe.height > 0 && probe.hasAudio) {
                    val cut = withFgs(probe.durationSec > 15.0) { cutter.extractAudio(srcFile) }
                    input = cut.file
                    if (cut.durationSec > 0.0) duration = cut.durationSec
                }
            } catch (_: Exception) {
                // keep original bytes; duration floor is 0.1
            }
            if (!input.isFile || input.length() <= 0L) {
                fail(id, board, "cut_failed: extract", frameId)
                return
            }
            val key = "boards/$id/audio/${newAudioId()}.m4a"
            cache.importObject(id, key, input)
            enqueuePendingFor(
                id,
                LyrePendingOp(0, "storage_put", key = key, localPath = cache.objectRel(id, key)),
            )
            val result = LyreRules.insertAudio(board, frameId, key, name.ifBlank { "Audio" }, duration)
            if (result.board === board) return
            applyLocked(result, capturedEpoch)
        } finally {
            _busy.value = null
        }
    }

    private suspend fun publishLocked(makePublic: Boolean) {
        val id = _boardId.value ?: return
        val vis = if (makePublic) "public" else "private"
        val compiled = _board.value?.movie?.src?.takeIf { it.isNotBlank() }
        val publishId = _project.value?.id?.takeIf { it.isNotBlank() } ?: id
        _watchBusy.value = true
        _watchError.value = null
        try {
            if (makePublic) {
                if (compiled == null) {
                    _watchError.value = "compiled_missing"
                    return
                }
                val local = cache.resolve(id, compiled)?.takeIf { it.length() > 0L }
                if (local != null) {
                    var putOk = false
                    if (cache.online()) {
                        val put = runCatching { api.putStorage(compiled, local) }.getOrNull()
                        putOk = put != null && put.optBoolean("ok", false)
                    }
                    if (!putOk) {
                        enqueuePendingFor(
                            id,
                            LyrePendingOp(0, "storage_put", key = compiled, localPath = cache.objectRel(id, compiled)),
                        )
                        // Do not enqueue/run publish until this compiled put is ok (PHP copies grokme).
                        _watchError.value = if (cache.online()) "storage_put_failed" else "offline"
                        return
                    }
                } else if (cache.online()) {
                    flushPending(id)
                }
            }
            if (!cache.online()) {
                queuePublish(id, vis, compiled, "offline")
                return
            }
            val resp = api.publish(publishId, vis, compiled)
            if (resp.optBoolean("ok", false)) {
                resp.optJSONObject("project")?.let { _project.value = lyreProjectFromJson(it) }
                Log.i("Lyre", "publish $vis")
                activity(id, "publish", "publish $vis", null, null, compiled)
            } else {
                val err = resp.optString("error").ifBlank { "publish_failed" }
                Log.w("Lyre", "publish failed $err")
                queuePublish(id, vis, compiled, err)
            }
        } catch (e: Exception) {
            Log.w("Lyre", "publish failed")
            queuePublish(id, vis, compiled, e.message ?: "publish_failed")
        } finally {
            _watchBusy.value = false
        }
    }

    private suspend fun queuePublish(id: String, vis: String, compiled: String?, error: String) {
        enqueuePendingFor(
            id,
            LyrePendingOp(0, "publish", key = compiled, visibility = vis),
        )
        if (cache.online()) {
            flushPending(id)
            if (cache.listPending(id).any { it.second.type == "publish" && it.second.visibility == vis }) {
                _watchError.value = error
            }
        } else {
            _watchError.value = error
        }
    }

    private fun newAudioId(): String = "lc_" + UUID.randomUUID().toString().replace("-", "").take(8)


    fun generateStill(
        frameId: String,
        prompt: String,
        extraRefKeys: List<String> = emptyList(),
        aspect: String = "16:9",
    ) {
        if (_busy.value != null || _imagineBusy.value) return
        val id = _boardId.value ?: return
        val board = _board.value ?: return
        if (LyreRules.leftoverFrame(board, frameId) == null) return
        _imagineBusy.value = true
        scope.launch {
            val t0 = System.currentTimeMillis()
            try {
                if (!cache.online()) {
                    mutex.withLock { writeImagineError(id, frameId, still = "offline") }
                    return@launch
                }
                mutex.withLock {
                    val b = _board.value ?: return@withLock
                    val r = LyreRules.patchLeftoverFrame(b, frameId) {
                        it.copy(generating = true, generatingError = null)
                    }
                    if (r.board !== b) commit(id, r.board)
                }
                val live = _board.value
                val frame = live?.let { LyreRules.leftoverFrame(it, frameId) }
                if (frame == null) {
                    mutex.withLock { clearImagineGenerating(id, frameId, still = true) }
                    return@launch
                }
                val keys = buildList {
                    if (frame.src.isNotEmpty()) add(frame.src)
                    addAll(extraRefKeys.take(LyreImagine.MAX_REFS))
                }
                val images = keys.mapNotNull { key ->
                    val f = localOrResolve(id, key) ?: return@mapNotNull null
                    LyreImagine.inlineImage(f)
                }.take(LyreImagine.MAX_IMAGES)
                val dest = cache.tmpFile(id, ".jpg")
                val result = imagine.still(id, id, frameId, prompt, images, aspect, dest)
                when (result) {
                    is ImagineStillResult.Remote -> {
                        val origKey = snapshotStillOrig(id, frame.src, result.path)
                        val remote = cache.objectFile(id, result.path)
                        remote.delete()
                        File(remote.path + ".part").delete()
                        val fetched = cache.resolve(id, result.path)
                        if (fetched == null || fetched.length() <= 0L) {
                            origKey?.let { restoreObject(id, result.path, it) }
                            mutex.withLock { writeImagineError(id, frameId, still = "grokme_unavailable") }
                            return@launch
                        }
                        val key = newStillObjectKey(id, frameId)
                        cache.importObject(id, key, fetched)
                        putNow(id, key)
                        mutex.withLock { applyStill(id, frameId, key, result.provider, t0, origKey) }
                    }
                    is ImagineStillResult.Local -> {
                        val key = newStillObjectKey(id, frameId)
                        cache.importObject(id, key, result.file)
                        putNow(id, key)
                        mutex.withLock { applyStill(id, frameId, key, result.provider, t0) }
                    }
                    is ImagineStillResult.Err -> {
                        mutex.withLock {
                            writeImagineError(id, frameId, still = result.reason)
                            activity(
                                id, "imagine_still",
                                "provider=none kind=still ms=${System.currentTimeMillis() - t0} ok=false ${result.reason}",
                                _board.value?.activeSceneId, frameId, null,
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                mutex.withLock { writeImagineError(id, frameId, still = "request_failed") }
                throw e
            } catch (_: Exception) {
                mutex.withLock { writeImagineError(id, frameId, still = "request_failed") }
            } finally {
                _imagineBusy.value = false
            }
        }
    }

    fun generateClip(
        frameId: String,
        prompt: String,
        duration: Int,
        aspect: String,
        resolution: String,
        extraRefKeys: List<String> = emptyList(),
        voiceIds: List<String> = emptyList(),
    ) {
        startVideoImagine(frameId, prompt, duration, aspect, resolution, extraRefKeys, voiceIds, edit = false)
    }

    fun editClip(
        frameId: String,
        prompt: String,
        duration: Int,
        aspect: String,
        resolution: String,
        extraRefKeys: List<String> = emptyList(),
        voiceIds: List<String> = emptyList(),
    ) {
        startVideoImagine(frameId, prompt, duration, aspect, resolution, extraRefKeys, voiceIds, edit = true)
    }

    fun importUri(afterFrameId: String?, uri: Uri) {
        if (_busy.value != null) return
        val id = _boardId.value ?: return
        scope.launch {
            mutex.withLock {
                val board = _board.value ?: return@withLock
                if (afterFrameId != null && LyreRules.leftoverFrame(board, afterFrameId) == null) return@withLock
                val tmp = cache.tmpFile(id, ".bin")
                if (!copyUriToFile(uri, tmp)) return@withLock
                importLocal(id, board, afterFrameId, tmp, mimeOf(uri, tmp))
            }
        }
    }

    fun importFile(afterFrameId: String?, file: File, mime: String) {
        if (_busy.value != null) return
        val id = _boardId.value ?: return
        scope.launch {
            mutex.withLock {
                val board = _board.value ?: return@withLock
                if (afterFrameId != null && LyreRules.leftoverFrame(board, afterFrameId) == null) return@withLock
                importLocal(id, board, afterFrameId, file, mime)
            }
        }
    }

    fun addRef(frameId: String, uri: Uri) {
        if (_busy.value != null) return
        val id = _boardId.value ?: return
        scope.launch {
            mutex.withLock {
                val board = _board.value ?: return@withLock
                if (LyreRules.leftoverFrame(board, frameId) == null) return@withLock
                val tmp = cache.tmpFile(id, ".jpg")
                if (!copyUriToFile(uri, tmp)) return@withLock
                val refId = LyreRules.newId("rf_")
                val key = "boards/$id/refs/$refId.jpg"
                cache.importObject(id, key, tmp)
                enqueuePendingFor(
                    id,
                    LyrePendingOp(0, "storage_put", key = key, localPath = cache.objectRel(id, key)),
                )
                val r = LyreRules.patchLeftoverFrame(board, frameId) { frame ->
                    val refs = ((frame.videoRefSrcs ?: emptyList()) + key).distinct().take(LyreImagine.MAX_REFS)
                    frame.copy(videoRefSrcs = refs)
                }
                if (r.board !== board) commit(id, r.board)
                scope.launch { flushPending(id) }
            }
        }
    }

    private suspend fun applyLocked(result: RuleResult, capturedEpoch: Int) {
        val id = _boardId.value ?: return
        val boardBefore = _board.value ?: return
        if (capturedEpoch != boardEpoch.get()) return
        if (result.plan == null && result.board === boardBefore) return
        val plan = result.plan
        _busy.value = LyreJob(plan?.kind ?: CutKind.TRIM, id)
        val beforeJson = LyreBoardCodec.encode(boardBefore).toString()
        val liveKeys = changingKeys(boardBefore, result, plan)
        val staging = undo.stage(id, undoTypeOf(plan), beforeJson, liveKeys)
        var patched: BoardData? = null
        try {
            try {
                if (plan?.kind == CutKind.STITCH) {
                    val movie = boardBefore.movie
                    if (movie != null && !gens.ensureCurrent(movie, id)) {
                        undo.discard(staging)
                        fail(id, boardBefore, "cut_failed: stitch_snapshot", stitchFrameId(boardBefore, plan))
                        return
                    }
                }
                patched = if (plan == null) {
                    result.board
                } else {
                    runPlan(id, boardBefore, result, plan)
                }
            } catch (e: CancellationException) {
                abortCut(id, staging, boardBefore, result, plan, e.message)
                throw e
            } catch (e: CutFailedException) {
                abortCut(id, staging, boardBefore, result, plan, e.message)
                return
            } catch (e: Exception) {
                val reason = when (plan?.kind) {
                    CutKind.POP -> "cut_failed: pop_rebuild"
                    CutKind.STITCH -> "cut_failed: stitch"
                    else -> "cut_failed: stitch"
                }
                abortCut(id, staging, boardBefore, result, plan, reason)
                return
            }
            val committed = patched ?: return
            try {
                val afterJson = LyreBoardCodec.encode(committed).toString()
                undo.push(staging, afterJson)
                undo.dropOldestBeyond(id)
                commit(id, committed)
                activity(
                    id,
                    (plan?.kind ?: CutKind.TRIM).name.lowercase(),
                    plan?.kind?.name?.lowercase() ?: "edit",
                    committed.activeSceneId,
                    operatedFrameId(boardBefore, result, plan),
                    plan?.clipKey,
                )
                cache.evict(id, keepKeys(committed, id))
                scope.launch { flushPending(id) }
            } catch (e: CancellationException) {
                finishCutOk(id, staging, committed)
                throw e
            } catch (_: Exception) {
                finishCutOk(id, staging, committed)
            }
        } finally {
            _busy.value = null
        }
    }

    private fun abortCut(
        id: String,
        staging: UndoStaging,
        boardBefore: BoardData,
        result: RuleResult,
        plan: CutPlan?,
        reason: String?,
    ) {
        runCatching { undo.restoreLive(id, staging) }
        undo.discard(staging)
        val msg = reason?.takeIf { it.startsWith("cut_failed:") } ?: "cut_failed: stitch"
        fail(id, boardBefore, msg, operatedFrameId(boardBefore, result, plan))
    }

    private fun finishCutOk(id: String, staging: UndoStaging, committed: BoardData) {
        runCatching {
            undo.push(staging, LyreBoardCodec.encode(committed).toString())
            commit(id, committed)
        }
    }

    private suspend fun runPlan(
        id: String,
        boardBefore: BoardData,
        result: RuleResult,
        plan: CutPlan,
    ): BoardData {
        return when (plan.kind) {
            CutKind.STITCH -> runStitch(id, boardBefore, result, plan)
            CutKind.POP -> runPop(id, boardBefore, result)
            CutKind.TRIM -> runTrim(id, boardBefore, result, plan)
            CutKind.MUTE -> runMute(id, boardBefore, result, plan)
            CutKind.SPLIT -> runSplit(id, boardBefore, result, plan)
            CutKind.EXTRACT -> runExtract(id, boardBefore, result, plan)
            CutKind.BURN_AUDIO -> runBurn(id, boardBefore, result, plan)
        }
    }

    private suspend fun runStitch(
        id: String,
        boardBefore: BoardData,
        result: RuleResult,
        plan: CutPlan,
    ): BoardData {
        val movie = boardBefore.movie ?: throw CutFailedException("cut_failed: stitch")
        val pictureKey = pictureCompileKey(movie) ?: throw CutFailedException("cut_failed: stitch")
        val picture = cache.resolve(id, pictureKey)?.takeIf { it.length() > 0L }
            ?: throw CutFailedException("cut_failed: stitch")
        val clipKey = plan.clipKey ?: throw CutFailedException("cut_failed: stitch")
        val clip = cache.resolve(id, clipKey)?.takeIf { it.length() > 0L }
            ?: throw CutFailedException("cut_failed: stitch")
        val movieKey = plan.movieKey?.takeIf { it.isNotBlank() } ?: "boards/$id/movie.mp4"
        val cut = withFgs(true) {
            cutter.stitch(picture, clip, plan.dropLast, plan.keepSec)
        }
        val dest = cache.objectFile(id, movieKey)
        cache.moveReplace(cut.file, dest)
        enqueueMoviePut(id, movieKey)
        val placed = CutOk(dest, cut.durationSec, cut.fps)
        val n = result.board.movie?.parts?.size?.minus(1) ?: 1
        gens.push(id, n, placed, partCount = n + 1)
        val wasBurn = movie.src.endsWith("movie.burn.mp4")
        val nextMovie = result.board.movie?.copy(
            src = movieKey,
            durationSec = placed.durationSec,
            fps = placed.fps,
            origSrc = if (wasBurn) null else result.board.movie.origSrc,
        )
        return clearError(result.board.copy(movie = nextMovie), stitchFrameId(boardBefore, plan))
    }

    private suspend fun runPop(id: String, boardBefore: BoardData, result: RuleResult): BoardData {
        val remaining = result.board.movie?.parts.orEmpty()
        val wasBurn = boardBefore.movie?.src?.endsWith("movie.burn.mp4") == true
        val compiled = result.board.movie?.src?.takeIf { it.isNotBlank() } ?: "boards/$id/movie.mp4"
        if (remaining.size == 1) {
            val only = remaining.first()
            val file = cache.resolve(id, only.src)?.takeIf { it.length() > 0L }
            val p = file?.let { cutter.probe(it) }
            gens.dropAbove(id, 0)
            val nextMovie = result.board.movie?.copy(
                src = only.src,
                durationSec = p?.durationSec ?: only.durationSec,
                fps = p?.fps,
                origSrc = null,
                playDurationSec = null,
            )
            return clearError(result.board.copy(movie = nextMovie), popFrameId(boardBefore))
        }
        val n = remaining.size - 1
        val restored = gens.restore(n, id)
        if (restored != null) {
            val dest = cache.objectFile(id, compiled)
            cache.copyReplace(restored, dest)
            enqueueMoviePut(id, compiled)
            val meta = gens.durationFps(id, n)
            val p = meta ?: cutter.probe(dest).let { it.durationSec to it.fps }
            gens.dropAbove(id, n)
            val nextMovie = result.board.movie?.copy(
                src = compiled,
                durationSec = p.first,
                fps = p.second,
                origSrc = if (wasBurn) null else result.board.movie.origSrc,
            )
            return clearError(result.board.copy(movie = nextMovie), popFrameId(boardBefore))
        }
        val partFiles = remaining.map { part ->
            cache.resolve(id, part.src)?.takeIf { it.length() > 0L }
        }
        if (partFiles.any { it == null }) {
            throw CutFailedException("cut_failed: pop_missing_gen")
        }
        val cut = withFgs(true) {
            cutter.rebuild(partFiles.filterNotNull(), dropLast = true)
        }
        val dest = cache.objectFile(id, compiled)
        cache.moveReplace(cut.file, dest)
        enqueueMoviePut(id, compiled)
        val placed = CutOk(dest, cut.durationSec, cut.fps)
        gens.push(id, n, placed, partCount = remaining.size)
        gens.dropAbove(id, n)
        val nextMovie = result.board.movie?.copy(
            src = compiled,
            durationSec = placed.durationSec,
            fps = placed.fps,
            origSrc = if (wasBurn) null else result.board.movie.origSrc,
        )
        return clearError(result.board.copy(movie = nextMovie), popFrameId(boardBefore))
    }

    private suspend fun runTrim(
        id: String,
        boardBefore: BoardData,
        result: RuleResult,
        plan: CutPlan,
    ): BoardData {
        val clipKey = plan.clipKey ?: throw CutFailedException("cut_failed: stitch")
        cache.ensureOrig(id, clipKey)
        val input = cache.resolve(id, clipKey)?.takeIf { it.length() > 0L }
            ?: throw CutFailedException("cut_failed: stitch")
        val inn = plan.trimInSec ?: 0.0
        val out = plan.trimOutSec ?: cutter.probe(input).durationSec
        val cut = withFgs(out - inn > 15.0) { cutter.trim(input, inn, out, null) }
        val newKey = derivedKey(clipKey, ".trim")
        cache.moveReplace(cut.file, cache.objectFile(id, newKey))
        enqueuePendingFor(
            id,
            LyrePendingOp(0, "storage_put", key = newKey, localPath = cache.objectRel(id, newKey)),
        )
        return dualWriteClip(result.board, clipKey, newKey, CutOk(cache.objectFile(id, newKey), cut.durationSec, cut.fps), inPointZero = true)
    }

    private suspend fun runMute(
        id: String,
        boardBefore: BoardData,
        result: RuleResult,
        plan: CutPlan,
    ): BoardData {
        val clipKey = plan.clipKey ?: throw CutFailedException("cut_failed: stitch")
        cache.ensureOrig(id, clipKey)
        val input = cache.resolve(id, clipKey)?.takeIf { it.length() > 0L }
            ?: throw CutFailedException("cut_failed: stitch")
        val dur = cutter.probe(input).durationSec
        val cut = withFgs(dur > 15.0) { cutter.mute(input) }
        val newKey = derivedKey(clipKey, ".mute")
        cache.moveReplace(cut.file, cache.objectFile(id, newKey))
        enqueuePendingFor(
            id,
            LyrePendingOp(0, "storage_put", key = newKey, localPath = cache.objectRel(id, newKey)),
        )
        return dualWriteClip(result.board, clipKey, newKey, CutOk(cache.objectFile(id, newKey), cut.durationSec, cut.fps), inPointZero = false)
    }

    private suspend fun runSplit(
        id: String,
        boardBefore: BoardData,
        result: RuleResult,
        plan: CutPlan,
    ): BoardData {
        val clipKey = plan.clipKey ?: throw CutFailedException("cut_failed: stitch")
        cache.ensureOrig(id, clipKey)
        val input = cache.resolve(id, clipKey)?.takeIf { it.length() > 0L }
            ?: throw CutFailedException("cut_failed: stitch")
        val at = plan.splitAtSec ?: throw CutFailedException("cut_failed: stitch")
        val dur = cutter.probe(input).durationSec
        val (left, right) = withFgs(dur > 15.0) { cutter.split(input, at) }
        val leftKey = derivedKey(clipKey, ".l")
        val rightKey = derivedKey(clipKey, ".r")
        cache.moveReplace(left.file, cache.objectFile(id, leftKey))
        cache.moveReplace(right.file, cache.objectFile(id, rightKey))
        enqueuePendingFor(id, LyrePendingOp(0, "storage_put", key = leftKey, localPath = cache.objectRel(id, leftKey)))
        enqueuePendingFor(id, LyrePendingOp(0, "storage_put", key = rightKey, localPath = cache.objectRel(id, rightKey)))
        val oldIds = boardBefore.videoLayers.flatMap { it.clips }.map { it.id }.toSet()
        val leftClip = findClip(boardBefore, clipKey)
        val rightClip = result.board.videoLayers.flatMap { it.clips }.firstOrNull { it.id !in oldIds }
        var next = result.board
        if (leftClip != null) {
            next = dualWriteClipById(
                next,
                leftClip.id,
                leftKey,
                CutOk(cache.objectFile(id, leftKey), left.durationSec, left.fps),
                inPointZero = true,
            )
        }
        if (rightClip != null) {
            next = dualWriteClipById(
                next,
                rightClip.id,
                rightKey,
                CutOk(cache.objectFile(id, rightKey), right.durationSec, right.fps),
                inPointZero = true,
            )
        }
        return LyreRules.retimeLinkedClips(next)
    }

    private suspend fun runExtract(
        id: String,
        boardBefore: BoardData,
        result: RuleResult,
        plan: CutPlan,
    ): BoardData {
        val clipKey = plan.clipKey ?: throw CutFailedException("cut_failed: stitch")
        val input = cache.resolve(id, clipKey)?.takeIf { it.length() > 0L }
            ?: throw CutFailedException("cut_failed: stitch")
        val dur = cutter.probe(input).durationSec
        val cut = withFgs(dur > 15.0) { cutter.extractAudio(input) }
        val oldSrcs = boardBefore.audioLayers.flatMap { it.clips }.map { it.src }.toSet()
        val audioSrc = result.board.audioLayers.flatMap { it.clips }.map { it.src }.firstOrNull { it !in oldSrcs }
            ?: "boards/$id/audio/extract.m4a"
        cache.moveReplace(cut.file, cache.objectFile(id, audioSrc))
        enqueuePendingFor(id, LyrePendingOp(0, "storage_put", key = audioSrc, localPath = cache.objectRel(id, audioSrc)))
        return patchAudioDuration(result.board, audioSrc, cut.durationSec)
    }

    private suspend fun runBurn(
        id: String,
        boardBefore: BoardData,
        result: RuleResult,
        plan: CutPlan,
    ): BoardData {
        val movie = boardBefore.movie ?: throw CutFailedException("cut_failed: stitch")
        val pictureKey = pictureCompileKey(movie) ?: throw CutFailedException("cut_failed: stitch")
        val picture = cache.resolve(id, pictureKey)?.takeIf { it.length() > 0L }
            ?: throw CutFailedException("cut_failed: stitch")
        val beds = plan.beds.mapNotNull { bed ->
            val f = resolveBed(id, bed) ?: return@mapNotNull null
            AudioBed(f, bed.startSec, bed.durationSec)
        }
        if (beds.isEmpty()) throw CutFailedException("cut_failed: stitch")
        val burnKey = plan.movieKey?.takeIf { it.isNotBlank() } ?: "boards/$id/movie.burn.mp4"
        val cut = withFgs(true) { cutter.burnAudio(picture, beds) }
        cache.moveReplace(cut.file, cache.objectFile(id, burnKey))
        enqueuePendingFor(id, LyrePendingOp(0, "storage_put", key = burnKey, localPath = cache.objectRel(id, burnKey)))
        val nextMovie = result.board.movie?.copy(
            src = burnKey,
            origSrc = pictureKey,
            durationSec = cut.durationSec,
            fps = cut.fps,
        )
        return result.board.copy(movie = nextMovie)
    }

    private fun dualWriteClip(
        board: BoardData,
        oldSrc: String,
        newSrc: String,
        cut: CutOk,
        inPointZero: Boolean,
    ): BoardData {
        val layers = board.videoLayers.map { layer ->
            layer.copy(
                clips = layer.clips.map { clip ->
                    if (clip.src != oldSrc && clip.src != newSrc) return@map clip
                    clip.copy(
                        src = newSrc,
                        durationSec = cut.durationSec,
                        sourceDurationSec = cut.durationSec,
                        trimInSec = if (inPointZero) 0.0 else clip.trimInSec,
                    )
                },
            )
        }
        val scenes = board.scenes.map { scene ->
            scene.copy(
                frames = scene.frames.map { frame ->
                    if (frame.videoSrc != oldSrc && frame.videoSrc != newSrc) return@map frame
                    frame.copy(
                        videoSrc = newSrc,
                        durationSec = cut.durationSec,
                        videoDurationSec = cut.durationSec,
                        videoFps = cut.fps,
                        videoInSec = if (inPointZero) 0.0 else frame.videoInSec,
                        videoOutSec = if (inPointZero) cut.durationSec else frame.videoOutSec,
                        videoGeneratingError = null,
                    )
                },
            )
        }
        return LyreRules.retimeLinkedClips(board.copy(videoLayers = layers, scenes = scenes))
    }

    private fun dualWriteClipById(
        board: BoardData,
        clipId: String,
        newSrc: String,
        cut: CutOk,
        inPointZero: Boolean,
    ): BoardData {
        val frameId = board.videoLayers.flatMap { it.clips }.firstOrNull { it.id == clipId }?.linkedFrameId
        val layers = board.videoLayers.map { layer ->
            layer.copy(
                clips = layer.clips.map { clip ->
                    if (clip.id != clipId) return@map clip
                    clip.copy(
                        src = newSrc,
                        durationSec = cut.durationSec,
                        sourceDurationSec = cut.durationSec,
                        trimInSec = if (inPointZero) 0.0 else clip.trimInSec,
                    )
                },
            )
        }
        val scenes = board.scenes.map { scene ->
            scene.copy(
                frames = scene.frames.map { frame ->
                    if (frameId == null || frame.id != frameId) return@map frame
                    frame.copy(
                        videoSrc = newSrc,
                        durationSec = cut.durationSec,
                        videoDurationSec = cut.durationSec,
                        videoFps = cut.fps,
                        videoInSec = if (inPointZero) 0.0 else frame.videoInSec,
                        videoOutSec = if (inPointZero) cut.durationSec else frame.videoOutSec,
                        videoGeneratingError = null,
                    )
                },
            )
        }
        return LyreRules.retimeLinkedClips(board.copy(videoLayers = layers, scenes = scenes))
    }

    private fun patchAudioDuration(board: BoardData, src: String, durationSec: Double): BoardData {
        val layers = board.audioLayers.map { layer ->
            layer.copy(
                clips = layer.clips.map { clip ->
                    if (clip.src != src) clip else clip.copy(durationSec = durationSec, sourceDurationSec = durationSec)
                },
            )
        }
        return board.copy(audioLayers = layers)
    }

    private fun resolveBed(boardId: String, bed: AudioBed): File? {
        if (bed.file.isFile && bed.file.length() > 0L) return bed.file
        val key = bed.file.path.removePrefix("/")
        return cache.resolve(boardId, key)?.takeIf { it.length() > 0L }
    }

    private suspend fun <T> withFgs(long: Boolean, block: suspend () -> T): T {
        if (long) LyreCutService.start(appCtx)
        try {
            return block()
        } finally {
            if (long) LyreCutService.stop(appCtx)
        }
    }

    private fun fail(id: String, boardBefore: BoardData, reason: String, frameId: String?) {
        val next = if (frameId == null) boardBefore else setError(boardBefore, frameId, reason)
        boardEpoch.incrementAndGet()
        _board.value = next
        cache.writeBoardJson(id, LyreBoardCodec.encode(next))
        activity(id, "cut_failed", reason, next.activeSceneId, frameId, null)
    }

    private fun commit(id: String, board: BoardData) {
        boardEpoch.incrementAndGet()
        _board.value = board
        val data = LyreBoardCodec.encode(board)
        cache.writeBoardJson(id, data)
        enqueuePendingFor(
            id,
            LyrePendingOp(
                seq = 0,
                type = "save_board",
                createdAtMs = System.currentTimeMillis(),
            ),
            snapshotJson = data,
        )
    }

    private fun enqueueMoviePut(boardId: String, movieKey: String) {
        enqueuePendingFor(
            boardId,
            LyrePendingOp(
                seq = 0,
                type = "storage_put",
                key = movieKey,
                localPath = cache.objectRel(boardId, movieKey),
            ),
        )
    }

    private fun enqueuePendingFor(boardId: String, op: LyrePendingOp, snapshotJson: JSONObject? = null) {
        cache.writePending(
            boardId,
            op.copy(createdAtMs = if (op.createdAtMs == 0L) System.currentTimeMillis() else op.createdAtMs),
            snapshotJson,
        )
    }

    private suspend fun flushPending(boardId: String) {
        flushMutex.withLock {
            val ops = cache.listPending(boardId)
            val latestSave = ops.filter { it.second.type == "save_board" }.maxOfOrNull { it.second.seq }
            val pendingPutCount = mutableMapOf<String, Int>()
            for ((_, op) in ops) {
                if (op.type != "storage_put") continue
                val k = op.key ?: continue
                pendingPutCount[k] = (pendingPutCount[k] ?: 0) + 1
            }
            val failedPutsThisSweep = mutableSetOf<String>()
            fun isPublicPublish(op: LyrePendingOp): Boolean {
                if (op.type != "publish") return false
                return (op.visibility?.takeIf { it == "public" || it == "private" } ?: "public") == "public"
            }
            // Public publish copies grokme; run compiled puts first, then skip if put pending/failed.
            val ordered = ops.filterNot { isPublicPublish(it.second) } + ops.filter { isPublicPublish(it.second) }
            for ((file, op) in ordered) {
                if (op.type == "save_board" && latestSave != null && op.seq != latestSave) {
                    cache.deletePending(file)
                    continue
                }
                if (isPublicPublish(op)) {
                    val k = op.key
                    if (k != null && ((pendingPutCount[k] ?: 0) > 0 || k in failedPutsThisSweep)) {
                        continue
                    }
                }
                val ok = runCatching {
                    when (op.type) {
                        "save_board" -> {
                            val data = cache.readPendingSnapshot(boardId, op)
                                ?: cache.readBoardJson(boardId)
                                ?: return@runCatching false
                            val resp = api.saveBoard(boardId, data)
                            resp.optBoolean("ok", false)
                        }
                        "storage_put" -> {
                            if (!cache.online()) return@runCatching false
                            val key = op.key ?: return@runCatching false
                            val local = cache.pendingFile(op, boardId) ?: return@runCatching false
                            val resp = api.putStorage(key, local)
                            resp.optBoolean("ok", false)
                        }
                        "publish" -> {
                            if (!cache.online()) return@runCatching false
                            val vis = op.visibility?.takeIf { it == "public" || it == "private" } ?: "public"
                            val publishId = _project.value?.id?.takeIf { it.isNotBlank() } ?: boardId
                            val resp = api.publish(publishId, vis, op.key)
                            if (resp.optBoolean("ok", false)) {
                                resp.optJSONObject("project")?.let { _project.value = lyreProjectFromJson(it) }
                            }
                            resp.optBoolean("ok", false)
                        }
                        else -> false
                    }
                }.getOrDefault(false)
                if (ok) {
                    cache.deletePending(file)
                    if (op.type == "storage_put") {
                        op.key?.let { k -> pendingPutCount[k] = (pendingPutCount[k] ?: 1) - 1 }
                    }
                } else {
                    if (op.type == "storage_put") {
                        op.key?.let { failedPutsThisSweep.add(it) }
                    }
                    val fails = op.failCount + 1
                    if (fails >= 5) {
                        cache.deletePending(file)
                        if (op.type == "storage_put") {
                            op.key?.let { k -> pendingPutCount[k] = (pendingPutCount[k] ?: 1) - 1 }
                        }
                        activity(boardId, "pending_drop", "dropped ${op.type} after 5 failures", null, null, op.key)
                    } else {
                        cache.writePending(boardId, op.copy(failCount = fails))
                    }
                }
            }
        }
    }

    private fun seedMuse(boardId: String, board: BoardData) {
        var local = store.museMessages(boardId)
        if (local.isEmpty()) {
            val fromUi = LyreMuse.parseUiMessages(board.ui)
            if (fromUi.isNotEmpty()) {
                store.setMuseMessages(boardId, fromUi)
                local = store.museMessages(boardId)
            }
        }
        _museMessages.value = local
    }

    private fun appendMuse(boardId: String, msg: LyreMuseMessage) {
        val next = (_museMessages.value + msg).let { list ->
            if (list.size <= LyreMuse.TRANSCRIPT_CAP) list else list.takeLast(LyreMuse.TRANSCRIPT_CAP)
        }
        _museMessages.value = next
        store.setMuseMessages(boardId, next)
    }

    private suspend fun persistWebMuse(boardId: String) {
        mutex.withLock {
            val board = _board.value ?: return@withLock
            if (_boardId.value != boardId) return@withLock
            val ui = board.ui ?: return@withLock
            if (!ui.has("museMessages")) return@withLock
            val arr = JSONArray()
            _museMessages.value.forEach { msg ->
                val role = if (msg.role == "user") "user" else "muse"
                arr.put(JSONObject().put("role", role).put("text", msg.text))
            }
            val nextUi = JSONObject(ui.toString()).put("museMessages", arr)
            commit(boardId, board.copy(ui = nextUi))
        }
    }

    private fun activity(
        boardId: String,
        type: String,
        summary: String,
        sceneId: String?,
        frameId: String?,
        clipId: String?,
    ) {
        val o = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("type", type)
            .put("projectId", boardId)
            .put("summary", summary)
        if (sceneId != null) o.put("sceneId", sceneId)
        if (frameId != null) o.put("frameId", frameId)
        if (clipId != null) o.put("clipId", clipId)
        runCatching { cache.appendActivity(boardId, o) }
    }

    private fun changingKeys(before: BoardData, result: RuleResult, plan: CutPlan?): List<String> {
        val keys = LinkedHashSet<String>()
        before.movie?.src?.takeIf { it.isNotBlank() }?.let { keys.add(it) }
        before.movie?.origSrc?.takeIf { it.isNotBlank() }?.let { keys.add(it) }
        plan?.movieKey?.let { keys.add(it) }
        plan?.clipKey?.let { keys.add(it) }
        result.board.movie?.src?.takeIf { it.isNotBlank() }?.let { keys.add(it) }
        return keys.toList()
    }

    private fun keepKeys(board: BoardData, boardId: String): Set<String> {
        val keys = LinkedHashSet<String>()
        fun add(v: String?) {
            if (!v.isNullOrBlank()) keys.add(v)
        }
        add(board.movie?.src)
        add(board.movie?.origSrc)
        board.movie?.parts?.forEach { add(it.src) }
        board.videoLayers.forEach { layer ->
            layer.clips.forEach {
                add(it.src)
                add(it.origSrc)
            }
        }
        board.audioLayers.forEach { layer ->
            layer.clips.forEach {
                add(it.src)
                add(it.origSrc)
            }
        }
        board.scenes.forEach { scene ->
            scene.frames.forEach { frame ->
                add(frame.src)
                add(frame.videoSrc)
                add(frame.origVideoSrc)
                add(frame.extra?.optString("origSrc"))
            }
        }
        undo.entries(boardId).forEach { e -> e.files.forEach { keys.add(it.liveRel) } }
        cache.gensDir(boardId).listFiles()?.forEach { f ->
            val m = Regex("""g(\d+)\.mp4""").matchEntire(f.name) ?: return@forEach
            keys.add("boards/$boardId/movie.g${m.groupValues[1]}.mp4")
        }
        return keys
    }

    private fun stitchFrameId(board: BoardData, plan: CutPlan?): String? {
        val clip = plan?.clipKey?.let { findClip(board, it) }
            ?: LyreMovie.nextStitchTarget(board.videoLayers, board.movie)
        return clip?.linkedFrameId
    }

    private fun popFrameId(board: BoardData): String? {
        val part = board.movie?.parts?.lastOrNull() ?: return null
        return board.videoLayers.flatMap { it.clips }.firstOrNull { it.id == part.clipId }?.linkedFrameId
    }

    private fun operatedFrameId(before: BoardData, result: RuleResult, plan: CutPlan?): String? {
        return when (plan?.kind) {
            CutKind.STITCH -> stitchFrameId(before, plan)
            CutKind.POP -> popFrameId(before)
            else -> plan?.clipKey?.let { findClip(before, it)?.linkedFrameId }
                ?: findClipBySrc(result.board, plan?.clipKey ?: "")?.linkedFrameId
        }
    }

    private fun findClip(board: BoardData, srcOrId: String): LayerClip? {
        val clips = board.videoLayers.flatMap { it.clips }
        return clips.firstOrNull { it.src == srcOrId } ?: clips.firstOrNull { it.id == srcOrId }
    }

    private fun findClipBySrc(board: BoardData, src: String): LayerClip? {
        return board.videoLayers.flatMap { it.clips }.firstOrNull { it.src == src }
    }

    private fun setError(board: BoardData, frameId: String, reason: String): BoardData {
        return board.copy(
            scenes = board.scenes.map { scene ->
                scene.copy(
                    frames = scene.frames.map { frame ->
                        if (frame.id != frameId) frame else frame.copy(
                            videoGeneratingError = reason,
                            videoGenerating = false,
                        )
                    },
                )
            },
        )
    }

    private fun clearError(board: BoardData, frameId: String?): BoardData {
        if (frameId == null) return board
        return board.copy(
            scenes = board.scenes.map { scene ->
                scene.copy(
                    frames = scene.frames.map { frame ->
                        if (frame.id != frameId) frame else frame.copy(videoGeneratingError = null, videoGenerating = false)
                    },
                )
            },
        )
    }

    private fun derivedKey(src: String, tag: String): String {
        val dot = src.lastIndexOf('.')
        return if (dot > 0) src.substring(0, dot) + tag + src.substring(dot) else src + tag
    }

    private fun requireBoardId(): String = _boardId.value ?: "lyre"

    private fun startVideoImagine(
        frameId: String,
        prompt: String,
        duration: Int,
        aspect: String,
        resolution: String,
        extraRefKeys: List<String>,
        voiceIds: List<String>,
        edit: Boolean,
    ) {
        if (_busy.value != null || _imagineBusy.value) return
        val id = _boardId.value ?: return
        val board = _board.value ?: return
        val frame = LyreRules.leftoverFrame(board, frameId) ?: return
        if (edit) {
            val clipSrc = frame.videoSrc.orEmpty()
            if (clipSrc.isEmpty()) return
        }
        _imagineBusy.value = true
        scope.launch {
            val t0 = System.currentTimeMillis()
            try {
                if (!cache.online()) {
                    mutex.withLock { writeImagineError(id, frameId, video = "offline") }
                    return@launch
                }
                mutex.withLock {
                    val b = _board.value ?: return@withLock
                    val r = LyreRules.patchLeftoverFrame(b, frameId) {
                        it.copy(
                            videoGenerating = true,
                            videoGeneratingError = null,
                            videoPrompt = prompt,
                            videoRefSrcs = extraRefKeys.take(LyreImagine.MAX_REFS).ifEmpty { it.videoRefSrcs },
                            videoVoices = LyreImagine.filterVoices(voiceIds).ifEmpty { it.videoVoices },
                        )
                    }
                    if (r.board !== b) commit(id, r.board)
                }
                val live = _board.value
                val cur = live?.let { LyreRules.leftoverFrame(it, frameId) }
                if (cur == null) {
                    mutex.withLock { clearImagineGenerating(id, frameId, video = true) }
                    return@launch
                }
                val posterKey = cur.src
                val refs = extraRefKeys.filter { it.isNotBlank() }.distinct().take(LyreImagine.MAX_REFS)
                val videoKey = cur.videoSrc.orEmpty()
                val putKeys = buildList {
                    if (posterKey.isNotEmpty()) add(posterKey)
                    addAll(refs)
                    if (edit && videoKey.isNotEmpty()) add(videoKey)
                }
                val grokmeReady = awaitPuts(id, putKeys)
                val poster = localOrResolve(id, posterKey)
                val clipFile = if (edit && videoKey.isNotEmpty()) localOrResolve(id, videoKey) else null
                val refFiles = refs.mapNotNull { localOrResolve(id, it) }
                val dest = cache.tmpFile(id, ".mp4")
                val voices = LyreImagine.filterVoices(voiceIds)
                val result = if (edit) {
                    imagine.edit(
                        id, id, prompt, duration, aspect, resolution,
                        videoKey, posterKey.takeIf { it.isNotEmpty() }, refs, voices,
                        poster, clipFile, refFiles, dest,
                    )
                } else if (grokmeReady || poster != null) {
                    imagine.video(
                        id, id, prompt, duration, aspect, resolution,
                        posterKey, refs, voices, poster, refFiles, dest,
                    )
                } else {
                    ImagineVideoResult.Err("grokme_unavailable")
                }
                when (result) {
                    is ImagineVideoResult.Ready -> applyClip(id, frameId, result.file, result.provider, t0, edit)
                    is ImagineVideoResult.Err -> {
                        mutex.withLock {
                            writeImagineError(id, frameId, video = result.reason)
                            activity(
                                id, if (edit) "imagine_edit" else "imagine_video",
                                "provider=none kind=video ms=${System.currentTimeMillis() - t0} ok=false ${result.reason}",
                                _board.value?.activeSceneId, frameId, null,
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                mutex.withLock { writeImagineError(id, frameId, video = "request_failed") }
                throw e
            } catch (_: Exception) {
                mutex.withLock { writeImagineError(id, frameId, video = "request_failed") }
            } finally {
                _imagineBusy.value = false
            }
        }
    }

    private fun applyStill(
        id: String,
        frameId: String,
        path: String,
        provider: String,
        t0: Long,
        origSrc: String? = null,
    ) {
        val b = _board.value ?: return
        if (LyreRules.leftoverFrame(b, frameId) == null) {
            clearImagineGenerating(id, frameId, still = true)
            return
        }
        val r = LyreRules.setStill(b, frameId, path, origSrc)
        if (r.board !== b) commit(id, r.board)
        activity(
            id, "imagine_still",
            "provider=$provider kind=still ms=${System.currentTimeMillis() - t0} ok=true",
            r.board.activeSceneId, frameId, null,
        )
    }

    private suspend fun applyClip(
        id: String,
        frameId: String,
        file: File,
        provider: String,
        t0: Long,
        edit: Boolean,
    ) {
        val key = newClipObjectKey(id)
        cache.importObject(id, key, file)
        putNow(id, key)
        val probe = runCatching { cutter.probe(cache.objectFile(id, key)) }.getOrNull()
        val dur = probe?.durationSec?.takeIf { it > 0.0 } ?: 6.0
        mutex.withLock {
            val b = _board.value ?: return
            if (LyreRules.leftoverFrame(b, frameId) == null) {
                clearImagineGenerating(id, frameId, video = true)
                return
            }
            val r = LyreRules.attachClip(b, frameId, key, dur, probe?.fps)
            if (r.board !== b) commit(id, r.board)
            val clipId = r.board.videoLayers.flatMap { it.clips }
                .firstOrNull { it.linkedFrameId == frameId }?.id
            activity(
                id, if (edit) "imagine_edit" else "imagine_video",
                "provider=$provider kind=video ms=${System.currentTimeMillis() - t0} ok=true",
                r.board.activeSceneId, frameId, clipId,
            )
        }
    }

    private suspend fun importLocal(
        id: String,
        board: BoardData,
        afterFrameId: String?,
        file: File,
        mime: String,
    ) {
        if (!file.isFile || file.length() <= 0L) return
        val isVideo = mime.startsWith("video/") || file.extension.lowercase() in setOf("mp4", "mov", "webm", "m4v")
        val frameId = LyreRules.newId("fr_")
        if (isVideo) {
            val clipId = LyreRules.newId("lc_")
            val key = "boards/$id/clips/$clipId.mp4"
            cache.importObject(id, key, file)
            enqueuePendingFor(id, LyrePendingOp(0, "storage_put", key = key, localPath = cache.objectRel(id, key)))
            val probe = runCatching { cutter.probe(cache.objectFile(id, key)) }.getOrNull()
            val dur = probe?.durationSec?.takeIf { it > 0.0 } ?: 6.0
            val frame = Frame(
                id = frameId,
                src = "",
                caption = "Clip",
                durationSec = dur,
                videoSrc = key,
                videoDurationSec = dur,
                videoFps = probe?.fps,
                videoInSec = 0.0,
                videoOutSec = dur,
            )
            val clip = LayerClip(
                id = clipId,
                src = key,
                name = "Clip",
                startSec = 0.0,
                durationSec = dur,
                sourceDurationSec = dur,
                linkedFrameId = frameId,
            )
            applyLocked(LyreRules.insertLeftover(board, afterFrameId, frame, clip), boardEpoch.get())
        } else {
            val ext = when {
                mime.contains("png") || file.extension.equals("png", true) -> "png"
                mime.contains("webp") -> "webp"
                else -> "jpg"
            }
            val key = "boards/$id/frames/$frameId.$ext"
            cache.importObject(id, key, file)
            enqueuePendingFor(id, LyrePendingOp(0, "storage_put", key = key, localPath = cache.objectRel(id, key)))
            val frame = Frame(
                id = frameId,
                src = key,
                caption = "Still",
                durationSec = 2.0,
            )
            applyLocked(LyreRules.insertLeftover(board, afterFrameId, frame, null), boardEpoch.get())
        }
        scope.launch { flushPending(id) }
    }

    private fun writeImagineError(id: String, frameId: String, still: String? = null, video: String? = null) {
        updateFrame(id, frameId, still ?: video ?: "failed") { frame ->
            frame.copy(
                generating = if (still != null) false else frame.generating,
                generatingError = still ?: frame.generatingError,
                videoGenerating = if (video != null) false else frame.videoGenerating,
                videoGeneratingError = video ?: frame.videoGeneratingError,
            )
        }
    }

    private fun clearImagineGenerating(id: String, frameId: String, still: Boolean = false, video: Boolean = false) {
        updateFrame(id, frameId, failed = null) { frame ->
            frame.copy(
                generating = if (still) false else frame.generating,
                generatingError = if (still) null else frame.generatingError,
                videoGenerating = if (video) false else frame.videoGenerating,
                videoGeneratingError = if (video) null else frame.videoGeneratingError,
            )
        }
    }

    private fun updateFrame(id: String, frameId: String, failed: String?, transform: (Frame) -> Frame) {
        val b = _board.value ?: return
        var found = false
        val next = b.copy(
            scenes = b.scenes.map { scene ->
                scene.copy(
                    frames = scene.frames.map { frame ->
                        if (frame.id != frameId) {
                            frame
                        } else {
                            found = true
                            transform(frame)
                        }
                    },
                )
            },
        )
        if (!found) return
        boardEpoch.incrementAndGet()
        _board.value = next
        val data = LyreBoardCodec.encode(next)
        cache.writeBoardJson(id, data)
        enqueuePendingFor(
            id,
            LyrePendingOp(0, "save_board", createdAtMs = System.currentTimeMillis()),
            snapshotJson = data,
        )
        if (failed != null) {
            activity(id, "imagine_failed", failed, next.activeSceneId, frameId, null)
        }
        scope.launch { flushPending(id) }
    }

    private fun restoreObject(boardId: String, destKey: String, srcKey: String) {
        val src = cache.objectFile(boardId, srcKey)
        if (!src.isFile || src.length() <= 0L) return
        cache.importObject(boardId, destKey, src)
    }

    private fun snapshotStillOrig(boardId: String, liveSrc: String, remotePath: String): String? {
        if (liveSrc.isEmpty()) return null
        val liveFile = cache.objectFile(boardId, liveSrc)
        if (!liveFile.isFile || liveFile.length() <= 0L) return null
        val remoteFile = cache.objectFile(boardId, remotePath)
        if (liveSrc != remotePath && liveFile.absolutePath != remoteFile.absolutePath) return null
        val origKey = derivedKey(liveSrc, ".orig")
        if (origKey == liveSrc) return null
        val origFile = cache.objectFile(boardId, origKey)
        if (!origFile.isFile || origFile.length() <= 0L) {
            cache.importObject(boardId, origKey, liveFile)
            putNow(boardId, origKey)
        }
        return origKey
    }

    private fun newStillObjectKey(boardId: String, frameId: String): String {
        return "boards/$boardId/frames/$frameId.${LyreRules.newId("g")}.jpg"
    }

    private fun newClipObjectKey(boardId: String): String {
        return "boards/$boardId/clips/${LyreRules.newId("g")}.mp4"
    }

    private suspend fun awaitPuts(boardId: String, keys: Collection<String>): Boolean {
        if (keys.isEmpty()) return true
        if (!cache.online()) return false
        return flushMutex.withLock {
            var ok = true
            for (key in keys.distinct().filter { it.isNotBlank() }) {
                val pending = cache.listPending(boardId)
                    .filter { it.second.type == "storage_put" && it.second.key == key }
                val local = pending.firstNotNullOfOrNull { cache.pendingFile(it.second, boardId) }
                    ?: cache.objectFile(boardId, key).takeIf { it.isFile && it.length() > 0L }
                    ?: cache.resolve(boardId, key)?.takeIf { it.length() > 0L }
                if (local == null) {
                    ok = false
                    continue
                }
                val resp = runCatching { api.putStorage(key, local) }.getOrNull()
                if (resp?.optBoolean("ok", false) == true) {
                    pending.forEach { cache.deletePending(it.first) }
                } else {
                    ok = false
                    if (pending.isEmpty()) {
                        enqueuePendingFor(
                            boardId,
                            LyrePendingOp(0, "storage_put", key = key, localPath = cache.objectRel(boardId, key)),
                        )
                    }
                }
            }
            ok
        }
    }

    private fun putNow(boardId: String, key: String) {
        val local = cache.objectFile(boardId, key)
        if (!local.isFile || local.length() <= 0L) return
        val resp = runCatching { api.putStorage(key, local) }.getOrNull()
        if (resp?.optBoolean("ok", false) != true) {
            enqueuePendingFor(
                boardId,
                LyrePendingOp(0, "storage_put", key = key, localPath = cache.objectRel(boardId, key)),
            )
        }
    }

    private fun localOrResolve(boardId: String, key: String): File? {
        if (key.isBlank()) return null
        val local = cache.objectFile(boardId, key)
        if (local.isFile && local.length() > 0L) return local
        return cache.resolve(boardId, key)?.takeIf { it.length() > 0L }
    }

    private fun copyUriToFile(uri: Uri, dest: File): Boolean {
        dest.parentFile?.mkdirs()
        return try {
            appCtx.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            } ?: return false
            dest.isFile && dest.length() > 0L
        } catch (_: Exception) {
            false
        }
    }

    private fun mimeOf(uri: Uri, file: File): String {
        val fromUri = appCtx.contentResolver.getType(uri)?.trim().orEmpty()
        if (fromUri.isNotEmpty()) return fromUri
        return LyreImagine.mimeFor(file)
    }

    companion object {
        private fun defaultApi(app: Application): LyreApi {
            val tokens = (app as? GrokifyApp)?.tokenStore ?: TokenStore(app)
            return LyreApi {
                runBlocking { tokens.tokenFlow.first() }
            }
        }
    }
}
