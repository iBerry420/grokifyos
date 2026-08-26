package io.grokify.os.apps.lyre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyreClipMovieTest {
    @Test
    fun movieClipsDurationAndClipAtTime() {
        val board = unstitched()
        val clips = LyreClip.movieClips(board.scenes)
        assertEquals(3, clips.size)
        assertEquals("fr_a", clips[0].frame.id)
        assertEquals(0.0, clips[0].start, 0.0)
        assertEquals(4.0, clips[0].length, 0.0)
        assertEquals("fr_hold", clips[1].frame.id)
        assertEquals(4.0, clips[1].start, 0.0)
        assertEquals(2.0, clips[1].length, 0.0)
        assertEquals("fr_b", clips[2].frame.id)
        assertEquals(6.0, clips[2].start, 0.0)
        assertEquals(3.0, clips[2].length, 0.0)
        assertEquals(9.0, LyreClip.movieDuration(board.scenes), 0.0)

        assertEquals("fr_a", LyreClip.clipAtTime(clips, 0.0)?.frame?.id)
        assertEquals("fr_a", LyreClip.clipAtTime(clips, 3.9)?.frame?.id)
        assertEquals("fr_hold", LyreClip.clipAtTime(clips, 4.0)?.frame?.id)
        assertEquals("fr_hold", LyreClip.clipAtTime(clips, 5.9)?.frame?.id)
        assertEquals("fr_b", LyreClip.clipAtTime(clips, 6.0)?.frame?.id)
        assertEquals("fr_b", LyreClip.clipAtTime(clips, 8.9)?.frame?.id)
        assertEquals("fr_b", LyreClip.clipAtTime(clips, 100.0)?.frame?.id)
        assertNull(LyreClip.clipAtTime(emptyList(), 0.0))
        assertEquals("fr_a", LyreClip.clipOf(board.scenes, "fr_a")?.frame?.id)
        assertEquals("fr_hold", LyreClip.nextClipAfter(board.scenes, "fr_a")?.frame?.id)
        assertNull(LyreClip.nextClipAfter(board.scenes, "fr_b"))
    }

    @Test
    fun movieProgramLayersHidesMembersKeepsLeftovers() {
        val board = unstitched()
        assertTrue(LyreMovie.clipInMovie(board.movie, "lc_a", board.videoLayers))
        assertFalse(LyreMovie.clipInMovie(board.movie, "lc_b", board.videoLayers))
        assertTrue(LyreMovie.frameInMovie(board.movie, board.videoLayers, "fr_a"))
        assertFalse(LyreMovie.frameInMovie(board.movie, board.videoLayers, "fr_b"))
        assertFalse(LyreMovie.frameInMovie(board.movie, board.videoLayers, "fr_hold"))

        val program = LyreMovie.movieProgramLayers(board.movie, board.videoLayers)
        assertEquals(1, program.size)
        assertEquals("ly_movie", program[0].id)
        val ids = program[0].clips.map { it.id }
        assertEquals(listOf("lc_movie", "lc_b"), ids)
        assertFalse(ids.contains("lc_a"))
        val leftover = program[0].clips.first { it.id == "lc_b" }
        assertEquals(false, leftover.generating)
        assertEquals("boards/lyre/clips/lc_b.mp4", leftover.src)

        val stitched = board.copy(
            movie = BoardMovie(
                src = "boards/lyre/movie.mp4",
                durationSec = 6.958,
                fps = 24.0,
                parts = listOf(
                    MoviePart("lc_a", "boards/lyre/clips/lc_a.mp4", 4.0),
                    MoviePart("lc_b", "boards/lyre/clips/lc_b.mp4", 3.0),
                ),
            ),
        )
        assertTrue(LyreMovie.clipInMovie(stitched.movie, "lc_a", stitched.videoLayers))
        assertTrue(LyreMovie.clipInMovie(stitched.movie, "lc_b", stitched.videoLayers))
        val after = LyreMovie.movieProgramLayers(stitched.movie, stitched.videoLayers)
        assertEquals(listOf("lc_movie"), after[0].clips.map { it.id })
        assertEquals("Movie · 2", after[0].clips[0].name)
        assertEquals(2, stitched.videoLayers[0].clips.size)
        assertTrue(stitched.videoLayers[0].clips.any { it.id == "lc_a" })
        assertTrue(stitched.videoLayers[0].clips.any { it.id == "lc_b" })
    }

    @Test
    fun nextStitchTargetSkipsStillOnlyHolds() {
        val board = unstitched()
        val ordered = LyreMovie.orderedVideoClips(board.videoLayers)
        assertEquals(listOf("lc_a", "lc_b"), ordered.map { it.id })
        val next = LyreMovie.nextStitchTarget(board.videoLayers, board.movie)
        assertNotNull(next)
        assertEquals("lc_b", next!!.id)
        assertTrue(LyreMovie.canStitchClip("lc_b", next.src, board.videoLayers, board.movie))
        assertFalse(LyreMovie.canStitchClip("lc_a", "boards/lyre/clips/lc_a.mp4", board.videoLayers, board.movie))
        assertFalse(LyreMovie.canStitchClip("lc_hold", null, board.videoLayers, board.movie))

        val stitched = board.copy(
            movie = BoardMovie(
                src = "boards/lyre/movie.mp4",
                durationSec = 6.958,
                parts = listOf(
                    MoviePart("lc_a", "boards/lyre/clips/lc_a.mp4", 4.0),
                    MoviePart("lc_b", "boards/lyre/clips/lc_b.mp4", 3.0),
                ),
            ),
        )
        assertNull(LyreMovie.nextStitchTarget(stitched.videoLayers, stitched.movie))
    }

    @Test
    fun moviePartDurationsEatsFromTheRight() {
        val full = LyreMovie.moviePartDurations(listOf(4.0, 3.0), 7.0)
        assertEquals(2, full.size)
        assertEquals(4.0, full[0], 1e-9)
        assertEquals(3.0, full[1], 1e-9)

        val trim1 = LyreMovie.moviePartDurations(listOf(4.0, 3.0), 6.0)
        assertEquals(4.0, trim1[0], 1e-9)
        assertEquals(2.0, trim1[1], 1e-9)

        val dropFrame = LyreMovie.moviePartDurations(listOf(4.0, 3.0), 6.958)
        assertEquals(4.0, dropFrame[0], 1e-9)
        assertEquals(2.958, dropFrame[1], 1e-9)

        val squeezed = LyreMovie.moviePartDurations(listOf(4.0, 3.0, 2.0), 5.0)
        assertEquals(4.0, squeezed[0], 1e-9)
        assertEquals(0.9, squeezed[1], 1e-9)
        assertEquals(0.1, squeezed[2], 1e-9)

        assertTrue(LyreMovie.moviePartDurations(emptyList(), 4.0).isEmpty())
    }

    @Test
    fun clipLengthFloorAndSpokenEstimate() {
        val hold = frame("fr_z", "Hold", 0.0)
        assertEquals(0.1, LyreClip.clipLength(hold), 0.0)
        assertEquals(0.8, LyreClip.spokenSeconds("hello world"), 1e-9)
        assertEquals(0.0, LyreClip.spokenSeconds("   "), 0.0)
    }

    private fun unstitched(): BoardData {
        val frames = listOf(
            frame("fr_a", "A", 4.0, videoSrc = "boards/lyre/clips/lc_a.mp4", videoDurationSec = 4.0, videoFps = 24.0),
            frame("fr_hold", "Hold", 2.0),
            frame("fr_b", "B", 3.0, videoSrc = "boards/lyre/clips/lc_b.mp4", videoDurationSec = 3.0, videoFps = 24.0),
        )
        val scene = Scene(
            id = "sc_1",
            title = "Scene 1",
            book = "",
            durationTargetSec = 0.0,
            logline = "",
            dialogue = "",
            notes = "",
            frames = frames,
        )
        val clips = listOf(
            LayerClip(
                id = "lc_a",
                src = "boards/lyre/clips/lc_a.mp4",
                name = "A",
                startSec = 0.0,
                durationSec = 4.0,
                sourceDurationSec = 4.0,
                linkedFrameId = "fr_a",
            ),
            LayerClip(
                id = "lc_b",
                src = "boards/lyre/clips/lc_b.mp4",
                name = "B",
                startSec = 6.0,
                durationSec = 3.0,
                sourceDurationSec = 3.0,
                linkedFrameId = "fr_b",
            ),
        )
        val layer = MediaLayer(id = "ly_v", kind = "video", name = "V", clips = clips)
        val movie = BoardMovie(
            src = "boards/lyre/clips/lc_a.mp4",
            durationSec = 4.0,
            parts = listOf(MoviePart("lc_a", "boards/lyre/clips/lc_a.mp4", 4.0)),
        )
        return BoardData(
            title = "Odysseus",
            brainstorm = "",
            scenes = listOf(scene),
            activeSceneId = "sc_1",
            refFolders = listOf(RefFolder(id = "lib", name = "Library", images = emptyList())),
            activeFolderId = "lib",
            videoLayers = listOf(layer),
            movie = movie,
        )
    }

    private fun frame(
        id: String,
        caption: String,
        durationSec: Double,
        videoSrc: String? = null,
        videoDurationSec: Double? = null,
        videoFps: Double? = null,
    ): Frame {
        return Frame(
            id = id,
            src = "boards/lyre/frames/$id.jpg",
            caption = caption,
            durationSec = durationSec,
            videoSrc = videoSrc,
            videoDurationSec = videoDurationSec,
            videoFps = videoFps,
        )
    }
}
