package io.grokify.os.apps.lyre

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyreTransportTest {
    @Test
    fun doesNotFollowPlayerWhenPlaylistItemDoesNotMatchClock() {
        val board = unstitched()
        val leftover = item("lc_b", LyrePlayItem.Kind.LEFTOVER, 3.0, "fr_b")
        val target = lyreClockTarget(board, 3.9)
        assertTrue(target is LyreClockTarget.Movie)
        assertFalse(
            LyreTransport.followPlayer(
                target = target,
                item = leftover,
                exoPlaying = true,
                ended = false,
            ),
        )
        val next = LyreTransport.nextPlayhead(
            board = board,
            playhead = 3.9,
            dt = 0.04,
            duration = 9.0,
            item = leftover,
            playerPos = 0.0,
            follow = false,
        )
        assertEquals(3.94, next, 1e-6)
    }

    @Test
    fun followsMatchingLeftoverInsteadOfWallClock() {
        val board = unstitched()
        val leftover = item("lc_b", LyrePlayItem.Kind.LEFTOVER, 3.0, "fr_b")
        val target = lyreClockTarget(board, 6.5)
        assertTrue(target is LyreClockTarget.Leftover)
        assertTrue(LyreTransport.itemMatches(target, leftover))
        assertTrue(LyreTransport.followPlayer(target, leftover, exoPlaying = true, ended = false))
        val next = LyreTransport.nextPlayhead(board, 6.4, 0.04, 9.0, leftover, 0.55, follow = true)
        assertEquals(6.55, next, 1e-6)
    }

    @Test
    fun doesNotRestartVideoAfterClipEndsDuringHoldTail() {
        val leftover = item("lc_b", LyrePlayItem.Kind.LEFTOVER, 3.0, "fr_b")
        val target = LyreClockTarget.Leftover("lc_b", 3.0)
        val ended = LyreTransport.playerEnded(leftover, 2.98, stateEnded = true)
        assertTrue(ended)
        assertFalse(LyreTransport.wantVideoPlay(resume = true, target, leftover, ended = true))
        assertFalse(LyreTransport.followPlayer(target, leftover, exoPlaying = false, ended = true))
    }

    @Test
    fun pauseStopsVideoEvenIfExoWouldKeepGoing() {
        val movie = item("lc_movie", LyrePlayItem.Kind.MOVIE, 4.0, "fr_a")
        val target = LyreClockTarget.Movie(1.0)
        assertFalse(LyreTransport.wantVideoPlay(resume = false, target, movie, ended = false))
        assertTrue(LyreTransport.wantVideoPlay(resume = true, target, movie, ended = false))
        assertFalse(
            LyreTransport.wantVideoPlay(
                resume = true,
                target = LyreClockTarget.Hold(
                    Frame(id = "fr_hold", src = "x.jpg", caption = "Hold", durationSec = 2.0),
                ),
                item = movie,
                ended = false,
            ),
        )
    }

    @Test
    fun playsNextLeftoverAfterMovieEndsWithoutFollowingWrongItem() {
        val board = unstitched()
        val movie = item("lc_movie", LyrePlayItem.Kind.MOVIE, 4.0, "fr_a")
        val target = lyreClockTarget(board, 6.1)
        assertTrue(target is LyreClockTarget.Leftover)
        assertFalse(LyreTransport.itemMatches(target, movie))
        assertTrue(LyreTransport.wantVideoPlay(resume = true, target, movie, ended = true))
        assertTrue(LyreTransport.shouldSeek("lc_movie", 4.0, "lc_b", 0.1, prepared = true))
        assertFalse(
            LyreTransport.shouldSeek(
                "lc_movie",
                4.0,
                "lc_b",
                0.1,
                prepared = true,
                seekInFlight = true,
            ),
        )
        assertFalse(LyreTransport.shouldSeek("lc_b", 0.12, "lc_b", 0.16, prepared = false))
        assertFalse(LyreTransport.shouldSeek("lc_b", 0.12, "lc_b", 0.16, prepared = true))
        assertTrue(LyreTransport.shouldSeek("lc_b", 0.12, "lc_b", 0.80, prepared = true))
    }

    @Test
    fun holdUsesWallClockSoGapsDoNotJump() {
        val board = unstitched()
        val movie = item("lc_movie", LyrePlayItem.Kind.MOVIE, 4.0, "fr_a")
        val target = lyreClockTarget(board, 4.2)
        assertTrue(target is LyreClockTarget.Hold)
        assertFalse(LyreTransport.followPlayer(target, movie, exoPlaying = false, ended = true))
        val next = LyreTransport.nextPlayhead(board, 4.2, 0.04, 9.0, movie, 4.0, follow = false)
        assertEquals(4.24, next, 1e-6)
    }

    private fun item(
        id: String,
        kind: LyrePlayItem.Kind,
        dur: Double,
        linked: String?,
    ): LyrePlayItem {
        return LyrePlayItem(
            id = id,
            kind = kind,
            file = File("/tmp/$id.mp4"),
            startUs = 0L,
            endUs = (dur * 1_000_000.0).toLong(),
            playDurationSec = dur,
            linkedFrameId = linked,
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
