@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package io.grokify.os.apps.lyre

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LyreCutterInstrumentedTest {
    @get:Rule
    val timeout: Timeout = Timeout(10, TimeUnit.MINUTES)

    private lateinit var app: Application
    private lateinit var cache: LyreCache
    private lateinit var cutter: Media3LyreCutter
    private lateinit var clip10: File
    private lateinit var clip3: File

    @Before
    fun setUp() {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        cache = LyreCache(app, LyreApi { null }, isOnline = { false })
        clip10 = asset("clip_10f_24fps.mp4")
        clip3 = asset("clip_3f_24fps.mp4")
    }

    @Test
    fun trimShortens() = runBlocking {
        cutter = Media3LyreCutter(app) { File(app.cacheDir, "lyre-tmp").also { it.mkdirs() } }
        val before = cutter.probe(clip10)
        val cut = cutter.trim(clip10, 0.0, before.durationSec / 2.0, before.fps)
        assertTrue(cut.durationSec < before.durationSec - 1.0 / 48.0)
        assertTrue(cut.file.length() > 0L)
    }

    @Test
    fun muteHasNoAudio() = runBlocking {
        cutter = Media3LyreCutter(app) { File(app.cacheDir, "lyre-tmp").also { it.mkdirs() } }
        val before = cutter.probe(clip10)
        assertTrue(before.hasAudio)
        val cut = cutter.mute(clip10)
        val after = cutter.probe(cut.file)
        assertFalse(after.hasAudio)
    }

    @Test
    fun stitchDurationDropsLastMovieFrame() = runBlocking {
        cutter = Media3LyreCutter(app) { File(app.cacheDir, "lyre-tmp").also { it.mkdirs() } }
        val movie = cutter.probe(clip10)
        val clip = cutter.probe(clip3)
        val frames = movie.frameCount ?: Media3LyreCutter.videoFrameCount(clip10) ?: 10
        val expected = (frames - 1).toDouble() / movie.fps + clip.durationSec
        val cut = cutter.stitch(clip10, clip3, dropLast = true, keepSec = null)
        assertEquals(expected, cut.durationSec, FRAME)
    }

    @Test
    fun sessionApplyPatchesMovieDurationFromCutOk() = runBlocking {
        val env = env("apply_dur")
        seed(env, "clips/lc_a.mp4", clip10)
        seed(env, "clips/lc_b.mp4", clip3)
        val a = env.cutter.probe(env.cache.objectFile(env.boardId, key(env, "clips/lc_a.mp4")))
        val b = env.cutter.probe(env.cache.objectFile(env.boardId, key(env, "clips/lc_b.mp4")))
        env.session.bind(env.boardId, twoClipBoard(env.boardId, a.durationSec, b.durationSec, a.fps))
        env.session.applyAwait(LyreRules.stitch(env.session.board.value!!, "lc_b"))
        val movie = env.session.board.value!!.movie!!
        val live = env.cache.objectFile(env.boardId, movie.src)
        val cut = env.cutter.probe(live)
        assertEquals(cut.durationSec, movie.durationSec, FRAME)
        assertEquals(cut.fps, movie.fps!!, 0.5)
        assertEquals(key(env, "movie.mp4"), movie.src)
    }

    @Test
    fun burnAudioTwoBedsWithGapUs() = runBlocking {
        cutter = Media3LyreCutter(app) { File(app.cacheDir, "lyre-tmp").also { it.mkdirs() } }
        val beds = listOf(
            AudioBed(clip3, 0.0, 0.125),
            AudioBed(clip3, 1.5, 0.125),
        )
        val cut = cutter.burnAudio(clip10, beds)
        assertTrue(cut.file.length() > 0L)
        assertTrue(cut.durationSec > 0.0)
    }

    @Test
    fun threePartPopRestoresGenG1() = runBlocking {
        val env = env("pop_g1")
        seed(env, "clips/lc_a.mp4", clip10)
        seed(env, "clips/lc_b.mp4", clip3)
        seed(env, "clips/lc_c.mp4", clip3)
        val a = probeKey(env, "clips/lc_a.mp4")
        val b = probeKey(env, "clips/lc_b.mp4")
        val c = probeKey(env, "clips/lc_c.mp4")
        env.session.bind(env.boardId, threeClipBoard(env.boardId, a.durationSec, b.durationSec, c.durationSec, a.fps))
        env.session.applyAwait(LyreRules.stitch(env.session.board.value!!, "lc_b"))
        env.session.applyAwait(LyreRules.stitch(env.session.board.value!!, "lc_c"))
        val g1 = env.session.movieGens().durationFps(env.boardId, 1)
        assertNotNull(g1)
        assertTrue(env.session.movieGens().has(env.boardId, 2))
        env.session.applyAwait(LyreRules.pop(env.session.board.value!!))
        val movie = env.session.board.value!!.movie!!
        assertEquals(key(env, "movie.mp4"), movie.src)
        assertNotEquals(key(env, "clips/lc_a.mp4"), movie.src)
        assertEquals(g1!!.first, movie.durationSec, FRAME)
        assertTrue(env.session.movieGens().has(env.boardId, 1))
        assertFalse(env.session.movieGens().has(env.boardId, 2))
        assertNull(frame(env, "fr_c").videoGeneratingError)
    }

    @Test
    fun stitchThenPopFromTwoPartWithoutG1() = runBlocking {
        val env = env("snap_pop")
        seed(env, "clips/lc_a.mp4", clip10)
        seed(env, "clips/lc_b.mp4", clip3)
        seed(env, "clips/lc_c.mp4", clip3)
        val a = probeKey(env, "clips/lc_a.mp4")
        val b = probeKey(env, "clips/lc_b.mp4")
        val c = probeKey(env, "clips/lc_c.mp4")
        env.session.bind(env.boardId, threeClipBoard(env.boardId, a.durationSec, b.durationSec, c.durationSec, a.fps))
        env.session.applyAwait(LyreRules.stitch(env.session.board.value!!, "lc_b"))
        val want = env.cutter.probe(env.cache.objectFile(env.boardId, key(env, "movie.mp4")))
        env.session.movieGens().dropAbove(env.boardId, 0)
        env.cache.objectFile(env.boardId, key(env, "movie.g1.mp4")).delete()
        assertFalse(env.session.movieGens().has(env.boardId, 1))
        env.session.applyAwait(LyreRules.stitch(env.session.board.value!!, "lc_c"))
        assertTrue(env.session.movieGens().has(env.boardId, 1))
        env.session.applyAwait(LyreRules.pop(env.session.board.value!!))
        val movie = env.session.board.value!!.movie!!
        assertEquals(key(env, "movie.mp4"), movie.src)
        assertEquals(want.durationSec, movie.durationSec, FRAME)
        assertNotEquals("cut_failed: pop_missing_gen", frame(env, "fr_c").videoGeneratingError)
        assertTrue(env.session.movieGens().has(env.boardId, 1))
    }

    @Test
    fun coldThreePartPopRebuilds() = runBlocking {
        val env = env("cold_rebuild")
        seed(env, "clips/lc_a.mp4", clip10)
        seed(env, "clips/lc_b.mp4", clip3)
        seed(env, "clips/lc_c.mp4", clip3)
        seed(env, "movie.mp4", clip10)
        val aFile = env.cache.objectFile(env.boardId, key(env, "clips/lc_a.mp4"))
        val bFile = env.cache.objectFile(env.boardId, key(env, "clips/lc_b.mp4"))
        val expected = env.cutter.stitch(aFile, bFile, dropLast = true, keepSec = null)
        val a = env.cutter.probe(aFile)
        val b = env.cutter.probe(bFile)
        val c = probeKey(env, "clips/lc_c.mp4")
        val board = threeClipBoard(env.boardId, a.durationSec, b.durationSec, c.durationSec, a.fps).let {
            it.copy(
                movie = it.movie!!.copy(
                    src = key(env, "movie.mp4"),
                    parts = listOf(
                        MoviePart("lc_a", key(env, "clips/lc_a.mp4"), a.durationSec),
                        MoviePart("lc_b", key(env, "clips/lc_b.mp4"), b.durationSec),
                        MoviePart("lc_c", key(env, "clips/lc_c.mp4"), c.durationSec),
                    ),
                ),
            )
        }
        env.session.bind(env.boardId, board)
        env.session.applyAwait(LyreRules.pop(env.session.board.value!!))
        val movie = env.session.board.value!!.movie!!
        assertEquals(key(env, "movie.mp4"), movie.src)
        assertEquals(expected.durationSec, movie.durationSec, FRAME)
        assertNotEquals("cut_failed: pop_missing_gen", frame(env, "fr_c").videoGeneratingError)
    }

    @Test
    fun coldThreePartMissingPartsIsPopMissingGen() = runBlocking {
        val env = env("missing_gen")
        val marker = seed(env, "movie.mp4", clip10)
        val before = marker.readBytes()
        val board = threeClipBoard(env.boardId, 0.4, 0.125, 0.125, 24.0).let {
            it.copy(
                movie = it.movie!!.copy(
                    src = key(env, "movie.mp4"),
                    parts = listOf(
                        MoviePart("lc_a", key(env, "clips/lc_a.mp4"), 0.4),
                        MoviePart("lc_b", key(env, "clips/lc_b.mp4"), 0.125),
                        MoviePart("lc_c", key(env, "clips/lc_c.mp4"), 0.125),
                    ),
                ),
            )
        }
        env.session.bind(env.boardId, board)
        env.session.applyAwait(LyreRules.pop(board))
        val after = env.session.board.value!!
        assertEquals("cut_failed: pop_missing_gen", frameOf(after, "fr_c").videoGeneratingError)
        assertEquals(3, after.movie!!.parts.size)
        assertTrue(marker.readBytes().contentEquals(before))
        assertEquals(0, env.session.undoCount())
    }

    @Test
    fun failureLeavesOrigAndSkipsUndo() = runBlocking {
        val env = env("fail_orig")
        val live = seed(env, "clips/lc_a.mp4", clip10)
        val orig = env.cache.ensureOrig(env.boardId, key(env, "clips/lc_a.mp4"))!!
        val origBytes = orig.readBytes()
        val a = env.cutter.probe(live)
        env.session.bind(env.boardId, twoClipBoard(env.boardId, a.durationSec, 0.125, a.fps))
        env.session.applyAwait(LyreRules.stitch(env.session.board.value!!, "lc_b"))
        assertEquals("cut_failed: stitch", frame(env, "fr_b").videoGeneratingError)
        assertTrue(orig.readBytes().contentEquals(origBytes))
        assertEquals(0, env.session.undoCount())
        assertEquals(key(env, "clips/lc_a.mp4"), env.session.board.value!!.movie!!.src)
    }

    private data class Env(
        val boardId: String,
        val cache: LyreCache,
        val cutter: Media3LyreCutter,
        val session: LyreSession,
    )

    private fun env(tag: String): Env {
        val boardId = "lyre_it_${tag}_${System.nanoTime()}"
        val c = LyreCache(app, LyreApi { null }, isOnline = { false })
        val cut = Media3LyreCutter(app) { File(c.boardDir(boardId), "tmp") }
        val session = LyreSession(app, LyreApi { null }, c, cut)
        return Env(boardId, c, cut, session)
    }

    private fun seed(env: Env, rel: String, src: File): File {
        return env.cache.importObject(env.boardId, key(env, rel), src)
    }

    private fun key(env: Env, rel: String) = "boards/${env.boardId}/$rel"

    private suspend fun probeKey(env: Env, rel: String): Probe {
        return env.cutter.probe(env.cache.objectFile(env.boardId, key(env, rel)))
    }

    private fun frame(env: Env, id: String): Frame = frameOf(env.session.board.value!!, id)

    private fun frameOf(board: BoardData, id: String): Frame {
        return board.scenes.flatMap { it.frames }.first { it.id == id }
    }

    private fun asset(name: String): File {
        val instr = InstrumentationRegistry.getInstrumentation()
        val out = File(app.cacheDir, name)
        val stream = runCatching { instr.context.assets.open("lyre/$name") }
            .getOrElse { instr.targetContext.assets.open("lyre/$name") }
        stream.use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return out
    }

    private fun twoClipBoard(boardId: String, aDur: Double, bDur: Double, fps: Double): BoardData {
        val prefix = "boards/$boardId"
        return BoardData(
            title = "t",
            brainstorm = "",
            scenes = listOf(
                Scene(
                    id = "sc_1",
                    title = "Scene 1",
                    book = "",
                    durationTargetSec = 0.0,
                    logline = "",
                    dialogue = "",
                    notes = "",
                    frames = listOf(
                        Frame(id = "fr_a", src = "", caption = "A", durationSec = aDur, videoSrc = "$prefix/clips/lc_a.mp4", videoDurationSec = aDur, videoFps = fps),
                        Frame(id = "fr_hold", src = "", caption = "Hold", durationSec = 0.1),
                        Frame(id = "fr_b", src = "", caption = "B", durationSec = bDur, videoSrc = "$prefix/clips/lc_b.mp4", videoDurationSec = bDur, videoFps = fps),
                    ),
                ),
            ),
            activeSceneId = "sc_1",
            refFolders = emptyList(),
            activeFolderId = "lib",
            videoLayers = listOf(
                MediaLayer(
                    id = "ly_v",
                    kind = "video",
                    name = "V",
                    clips = listOf(
                        LayerClip(id = "lc_a", src = "$prefix/clips/lc_a.mp4", name = "A", startSec = 0.0, durationSec = aDur, sourceDurationSec = aDur, linkedFrameId = "fr_a"),
                        LayerClip(id = "lc_b", src = "$prefix/clips/lc_b.mp4", name = "B", startSec = aDur + 0.1, durationSec = bDur, sourceDurationSec = bDur, linkedFrameId = "fr_b"),
                    ),
                ),
            ),
            movie = BoardMovie(
                src = "$prefix/clips/lc_a.mp4",
                durationSec = aDur,
                fps = fps,
                parts = listOf(MoviePart("lc_a", "$prefix/clips/lc_a.mp4", aDur)),
            ),
        )
    }

    private fun threeClipBoard(
        boardId: String,
        aDur: Double,
        bDur: Double,
        cDur: Double,
        fps: Double,
    ): BoardData {
        val two = twoClipBoard(boardId, aDur, bDur, fps)
        val prefix = "boards/$boardId"
        val frC = Frame(id = "fr_c", src = "", caption = "C", durationSec = cDur, videoSrc = "$prefix/clips/lc_c.mp4", videoDurationSec = cDur, videoFps = fps)
        val lcC = LayerClip(id = "lc_c", src = "$prefix/clips/lc_c.mp4", name = "C", startSec = aDur + 0.1 + bDur, durationSec = cDur, sourceDurationSec = cDur, linkedFrameId = "fr_c")
        val scene = two.scenes[0]
        return two.copy(
            scenes = listOf(scene.copy(frames = scene.frames + frC)),
            videoLayers = listOf(two.videoLayers[0].copy(clips = two.videoLayers[0].clips + lcC)),
        )
    }

    companion object {
        private const val FRAME = 1.0 / 24.0
    }
}
