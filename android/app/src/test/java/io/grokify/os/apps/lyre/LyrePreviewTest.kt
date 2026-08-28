package io.grokify.os.apps.lyre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyrePreviewTest {
    @Test
    fun coverWithStillOnlyOnHoldsWhilePlaying() {
        val hold = LyreClockTarget.Hold(
            Frame(id = "fr_hold", src = "h.jpg", caption = "Hold", durationSec = 2.0),
        )
        val movie = LyreClockTarget.Movie(1.0)
        val leftover = LyreClockTarget.Leftover("lc_b", 0.2)
        assertTrue(LyrePreview.coverWithStill(hold, playing = true, videoReady = false))
        assertTrue(LyrePreview.coverWithStill(null, playing = false, videoReady = false))
        assertFalse(LyrePreview.coverWithStill(movie, playing = true, videoReady = false))
        assertFalse(LyrePreview.coverWithStill(leftover, playing = true, videoReady = false))
        assertFalse(LyrePreview.coverWithStill(leftover, playing = true, videoReady = true))
        assertTrue(LyrePreview.coverWithStill(leftover, playing = false, videoReady = false))
        assertFalse(LyrePreview.coverWithStill(leftover, playing = false, videoReady = true))
    }

    @Test
    fun preloadNextVideoAfterHoldAndCurrentClip() {
        val board = unstitched()
        assertEquals("lc_b", LyrePreview.preloadTargetId(board, 3.9))
        assertEquals("lc_b", LyrePreview.preloadTargetId(board, 4.2))
        assertNull(LyrePreview.preloadTargetId(board, 6.5))
        assertNull(LyrePreview.preloadTargetId(board, 8.9))
    }

    @Test
    fun promoteRamWhenClockLeavesFrontForPreparedNext() {
        assertFalse(
            LyrePreview.shouldPromoteRam(
                playing = true,
                targetId = "lc_movie",
                frontId = "lc_movie",
                ended = false,
                ramId = "lc_b",
                ramReady = true,
            ),
        )
        assertFalse(
            LyrePreview.shouldPromoteRam(
                playing = true,
                targetId = null,
                frontId = "lc_movie",
                ended = true,
                ramId = "lc_b",
                ramReady = true,
            ),
        )
        assertTrue(
            LyrePreview.shouldPromoteRam(
                playing = true,
                targetId = "lc_b",
                frontId = "lc_movie",
                ended = true,
                ramId = "lc_b",
                ramReady = true,
            ),
        )
        assertTrue(
            LyrePreview.shouldPromoteRam(
                playing = true,
                targetId = "lc_b",
                frontId = "lc_movie",
                ended = false,
                ramId = "lc_b",
                ramReady = true,
            ),
        )
        assertFalse(
            LyrePreview.shouldPromoteRam(
                playing = true,
                targetId = "lc_b",
                frontId = "lc_movie",
                ended = true,
                ramId = "lc_b",
                ramReady = false,
            ),
        )
        assertFalse(
            LyrePreview.shouldPromoteRam(
                playing = false,
                targetId = "lc_b",
                frontId = "lc_movie",
                ended = false,
                ramId = "lc_b",
                ramReady = true,
            ),
        )
    }

    @Test
    fun seekFallsBackWhenRamCannotPromote() {
        assertFalse(
            LyrePreview.shouldSeekFront(
                promoteRam = true,
                currentId = "lc_movie",
                currentPos = 4.0,
                targetId = "lc_b",
                targetPos = 0.0,
                prepared = true,
                seekInFlight = false,
            ),
        )
        assertTrue(
            LyrePreview.shouldSeekFront(
                promoteRam = false,
                currentId = "lc_movie",
                currentPos = 4.0,
                targetId = "lc_b",
                targetPos = 0.0,
                prepared = true,
                seekInFlight = false,
            ),
        )
    }

    private fun unstitched(): BoardData {
        val frames = listOf(
            Frame(
                id = "fr_a",
                src = "a.jpg",
                caption = "A",
                durationSec = 4.0,
                videoSrc = "a.mp4",
                videoDurationSec = 4.0,
            ),
            Frame(id = "fr_hold", src = "h.jpg", caption = "Hold", durationSec = 2.0),
            Frame(
                id = "fr_b",
                src = "b.jpg",
                caption = "B",
                durationSec = 3.0,
                videoSrc = "b.mp4",
                videoDurationSec = 3.0,
            ),
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
            LayerClip("lc_a", "a.mp4", "A", 0.0, 4.0, sourceDurationSec = 4.0, linkedFrameId = "fr_a"),
            LayerClip("lc_b", "b.mp4", "B", 6.0, 3.0, sourceDurationSec = 3.0, linkedFrameId = "fr_b"),
        )
        return BoardData(
            title = "T",
            brainstorm = "",
            scenes = listOf(scene),
            activeSceneId = "sc_1",
            refFolders = emptyList(),
            activeFolderId = "",
            videoLayers = listOf(MediaLayer("ly_v", "video", "V", clips)),
            movie = BoardMovie(
                src = "a.mp4",
                durationSec = 4.0,
                parts = listOf(MoviePart("lc_a", "a.mp4", 4.0)),
            ),
        )
    }
}
