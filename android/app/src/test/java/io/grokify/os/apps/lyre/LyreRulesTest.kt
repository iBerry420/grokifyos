package io.grokify.os.apps.lyre

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LyreRulesTest {
    @Test
    fun unstitchedMembership() {
        val board = fixture("unstitched")
        val next = LyreMovie.nextStitchTarget(board.videoLayers, board.movie)
        assertEquals("lc_b", next?.id)
        assertTrue(LyreMovie.canStitchClip("lc_b", next?.src, board.videoLayers, board.movie))
        assertFalse(LyreMovie.canStitchClip("fr_hold", null, board.videoLayers, board.movie))
        assertEquals(3, LyreClip.movieClips(board.scenes).size)
        assertTrue(LyreMovie.frameInMovie(board.movie, board.videoLayers, "fr_a"))
        assertFalse(LyreMovie.frameInMovie(board.movie, board.videoLayers, "fr_b"))
    }

    @Test
    fun stitchAppendsPartsKeepsClips() {
        val unstitched = fixture("unstitched")
        val result = LyreRules.stitch(unstitched, "lc_b")
        val board = result.board
        val want = fixture("after_stitch")
        assertEquals(listOf("lc_a", "lc_b"), partIds(board))
        assertEquals(clipIds(want), clipIds(board))
        assertTrue(clipIds(board).contains("lc_a"))
        assertTrue(clipIds(board).contains("lc_b"))
        assertEquals("boards/lyre/movie.mp4", board.movie?.src)
        assertNull(LyreMovie.nextStitchTarget(board.videoLayers, board.movie))
        assertEquals(CutKind.STITCH, result.plan?.kind)
        assertEquals(true, result.plan?.dropLast)
        val stills = LyreClip.movieClips(board.scenes)
        assertEquals("fr_hold", stills[1].frame.id)
        assertFalse(LyreMovie.frameInMovie(board.movie, board.videoLayers, "fr_hold"))
        assertDualWrite(board)
    }

    @Test
    fun stitchInvalidTargetsLeaveBoardUnchanged() {
        val unstitched = fixture("unstitched")
        val hold = LyreRules.stitch(unstitched, "fr_hold")
        assertNull(hold.plan)
        assertSame(unstitched, hold.board)

        val already = LyreRules.stitch(unstitched, "lc_a")
        assertNull(already.plan)
        assertSame(unstitched, already.board)

        val stitched = LyreRules.stitch(unstitched, "lc_b").board
        val again = LyreRules.stitch(stitched, "lc_b")
        assertNull(again.plan)
        assertSame(stitched, again.board)
    }

    @Test
    fun popAfterStitchMatchesUnstitchedMembership() {
        val popped = LyreRules.pop(fixture("after_stitch"))
        val board = popped.board
        val unstitched = fixture("unstitched")
        assertEquals(CutKind.POP, popped.plan?.kind)
        assertEquals(unstitched.movie?.src, board.movie?.src)
        assertEquals(partIds(unstitched), partIds(board))
        assertEquals("lc_b", LyreMovie.nextStitchTarget(board.videoLayers, board.movie)?.id)
        assertTrue(clipIds(board).contains("lc_b"))
        assertEquals(clipIds(unstitched), clipIds(board))
    }

    @Test
    fun threePartStitchThenPopKeepsCompiledSrc() {
        val unstitched = fixture("unstitched_3")
        val afterB = LyreRules.stitch(unstitched, "lc_b")
        val afterC = LyreRules.stitch(afterB.board, "lc_c")
        assertEquals(listOf("lc_a", "lc_b", "lc_c"), partIds(afterC.board))
        assertEquals("boards/lyre/movie.mp4", afterC.board.movie?.src)
        assertNull(LyreMovie.nextStitchTarget(afterC.board.videoLayers, afterC.board.movie))
        assertEquals(clipIds(fixture("after_stitch_3")), clipIds(afterC.board))

        val popped = LyreRules.pop(afterC.board)
        val want = fixture("after_pop_3")
        assertEquals(partIds(want), partIds(popped.board))
        assertEquals("boards/lyre/movie.mp4", popped.board.movie?.src)
        assertTrue(clipIds(popped.board).contains("lc_c"))
        assertEquals("lc_c", LyreMovie.nextStitchTarget(popped.board.videoLayers, popped.board.movie)?.id)

        val fromFixture = LyreRules.pop(fixture("after_stitch_3"))
        assertEquals(partIds(want), partIds(fromFixture.board))
        assertEquals(want.movie?.src, fromFixture.board.movie?.src)
        assertTrue(clipIds(fromFixture.board).contains("lc_c"))
    }

    @Test
    fun popWithOnePartIsNoop() {
        val unstitched = fixture("unstitched")
        val result = LyreRules.pop(unstitched)
        assertNull(result.plan)
        assertSame(unstitched, result.board)
    }

    @Test
    fun insertHoldInsidePrefixRefused() {
        val unstitched = fixture("unstitched")
        val inside = LyreRules.insertHold(unstitched, "fr_a")
        assertNull(inside.plan)
        assertSame(unstitched, inside.board)

        val afterLeftover = LyreRules.insertHold(unstitched, "fr_b")
        assertNull(afterLeftover.plan)
        assertEquals(4, afterLeftover.board.scenes[0].frames.size)
        assertEquals("fr_b", afterLeftover.board.scenes[0].frames[2].id)
        assertTrue(afterLeftover.board.scenes[0].frames[3].videoSrc.isNullOrEmpty())
        assertFalse(
            LyreMovie.frameInMovie(
                afterLeftover.board.movie,
                afterLeftover.board.videoLayers,
                afterLeftover.board.scenes[0].frames[3].id,
            ),
        )

        val atEnd = LyreRules.insertHold(unstitched, null)
        assertEquals(4, atEnd.board.scenes[0].frames.size)
        assertTrue(atEnd.board.scenes[0].frames.last().videoSrc.isNullOrEmpty())
    }

    @Test
    fun insertStillAndAttachClipLeftoverOnly() {
        val unstitched = fixture("unstitched")
        val incoming = Frame(
            id = "fr_x",
            src = "boards/lyre/frames/fr_x.jpg",
            caption = "X",
            durationSec = 2.0,
        )
        val prefix = LyreRules.insertLeftover(unstitched, "fr_a", incoming, null)
        assertNull(prefix.plan)
        assertSame(unstitched, prefix.board)

        val still = LyreRules.insertLeftover(unstitched, "fr_b", incoming, null)
        assertEquals("fr_x", still.board.scenes[0].frames[3].id)
        assertFalse(
            LyreMovie.frameInMovie(still.board.movie, still.board.videoLayers, "fr_x"),
        )

        val movieStill = LyreRules.setStill(unstitched, "fr_a", "boards/lyre/frames/new.jpg")
        assertSame(unstitched, movieStill.board)

        val holdStill = LyreRules.setStill(unstitched, "fr_hold", "boards/lyre/frames/hold2.jpg")
        assertEquals("boards/lyre/frames/hold2.jpg", frameOf(holdStill.board, "fr_hold").src)
        assertEquals(
            "boards/lyre/frames/fr_hold.jpg",
            frameOf(holdStill.board, "fr_hold").extra?.optString("origSrc"),
        )
        assertNull(frameOf(holdStill.board, "fr_hold").generatingError)

        val again = LyreRules.setStill(holdStill.board, "fr_hold", "boards/lyre/frames/hold3.jpg")
        assertEquals("boards/lyre/frames/hold3.jpg", frameOf(again.board, "fr_hold").src)
        assertEquals(
            "boards/lyre/frames/fr_hold.jpg",
            frameOf(again.board, "fr_hold").extra?.optString("origSrc"),
        )

        val snap = LyreRules.setStill(
            unstitched,
            "fr_hold",
            "boards/lyre/frames/hold_g.jpg",
            origSrc = "boards/lyre/frames/fr_hold.orig.jpg",
        )
        assertEquals("boards/lyre/frames/hold_g.jpg", frameOf(snap.board, "fr_hold").src)
        assertEquals(
            "boards/lyre/frames/fr_hold.orig.jpg",
            frameOf(snap.board, "fr_hold").extra?.optString("origSrc"),
        )
        val snap2 = LyreRules.setStill(
            snap.board,
            "fr_hold",
            "boards/lyre/frames/hold_g2.jpg",
            origSrc = "boards/lyre/frames/hold_g.jpg",
        )
        assertEquals("boards/lyre/frames/hold_g2.jpg", frameOf(snap2.board, "fr_hold").src)
        assertEquals(
            "boards/lyre/frames/fr_hold.orig.jpg",
            frameOf(snap2.board, "fr_hold").extra?.optString("origSrc"),
        )

        val movieClip = LyreRules.attachClip(unstitched, "fr_a", "boards/lyre/clips/new.mp4", 6.0, 24.0)
        assertSame(unstitched, movieClip.board)

        val created = LyreRules.attachClip(unstitched, "fr_hold", "boards/lyre/clips/hold.mp4", 6.0, 24.0)
        val hold = frameOf(created.board, "fr_hold")
        assertEquals("boards/lyre/clips/hold.mp4", hold.videoSrc)
        val linked = created.board.videoLayers.flatMap { it.clips }.first { it.linkedFrameId == "fr_hold" }
        assertEquals(hold.videoSrc, linked.src)
        assertDualWrite(created.board)

        val replaced = LyreRules.attachClip(unstitched, "fr_b", "boards/lyre/clips/lc_b_gen.mp4", 6.0, 24.0)
        val clip = clipOf(replaced.board, "lc_b")
        assertEquals("boards/lyre/clips/lc_b_gen.mp4", clip.src)
        assertEquals("boards/lyre/clips/lc_b.mp4", clip.origSrc)
        assertEquals(clip.src, frameOf(replaced.board, "fr_b").videoSrc)
        assertNull(frameOf(replaced.board, "fr_b").videoGeneratingError)
        assertDualWrite(replaced.board)

        val replaced2 = LyreRules.attachClip(replaced.board, "fr_b", "boards/lyre/clips/lc_b_gen2.mp4", 6.0, 24.0)
        assertEquals("boards/lyre/clips/lc_b_gen2.mp4", clipOf(replaced2.board, "lc_b").src)
        assertEquals("boards/lyre/clips/lc_b.mp4", clipOf(replaced2.board, "lc_b").origSrc)
        assertEquals("boards/lyre/clips/lc_b.mp4", frameOf(replaced2.board, "fr_b").origVideoSrc)
        assertDualWrite(replaced2.board)
    }

    @Test
    fun patchLeftoverErrorLeavesMoviePrefix() {
        val unstitched = fixture("unstitched")
        val movie = LyreRules.patchLeftoverFrame(unstitched, "fr_a") {
            it.copy(generatingError = "offline")
        }
        assertSame(unstitched, movie.board)
        val leftover = LyreRules.patchLeftoverFrame(unstitched, "fr_hold") {
            it.copy(generatingError = "offline", videoGeneratingError = "edit_unavailable")
        }
        assertEquals("offline", frameOf(leftover.board, "fr_hold").generatingError)
        assertEquals("edit_unavailable", frameOf(leftover.board, "fr_hold").videoGeneratingError)
        assertNull(frameOf(leftover.board, "fr_a").generatingError)
    }

    @Test
    fun muteLeftoverDualWritesWithoutAudioRail() {
        val unstitched = fixture("unstitched")
        val muted = LyreRules.mute(unstitched, "lc_b")
        val frame = frameOf(muted.board, "fr_b")
        val clip = clipOf(muted.board, "lc_b")
        assertEquals(true, frame.videoMuted)
        assertEquals(frame.videoSrc, clip.src)
        assertTrue(muted.board.audioLayers.isEmpty())
        assertEquals(unstitched.audioLayers.size, muted.board.audioLayers.size)
        assertEquals(CutKind.MUTE, muted.plan?.kind)
        assertEquals(clip.src, muted.plan?.clipKey)
        assertDualWrite(muted.board)

        val movieMute = LyreRules.mute(unstitched, "lc_a")
        assertNull(movieMute.plan)
        assertSame(unstitched, movieMute.board)
        assertTrue(movieMute.board.audioLayers.isEmpty())
    }

    @Test
    fun loopIsNotALyreRulesFunction() {
        // Loop is player-only (LyreStore.loopClip); not a leftover JSON rule.
        val names = LyreRules::class.java.declaredMethods.map { it.name }.toSet()
        assertFalse(names.contains("loop"))
        assertFalse(names.contains("loopClip"))
        assertFalse(names.contains("setLoop"))
    }

    @Test
    fun trimAndSplitLeftoverDualWriteRefuseMovieMember() {
        val unstitched = fixture("unstitched")
        val trimmed = LyreRules.trim(unstitched, "lc_b", 0.5, 2.5)
        assertEquals(CutKind.TRIM, trimmed.plan?.kind)
        assertEquals(frameOf(trimmed.board, "fr_b").videoSrc, clipOf(trimmed.board, "lc_b").src)
        assertEquals(0.5, frameOf(trimmed.board, "fr_b").videoInSec!!, 0.0)
        assertEquals(2.5, frameOf(trimmed.board, "fr_b").videoOutSec!!, 0.0)
        assertDualWrite(trimmed.board)

        val trimMovie = LyreRules.trim(unstitched, "lc_a", 0.0, 2.0)
        assertNull(trimMovie.plan)
        assertSame(unstitched, trimMovie.board)

        val three = fixture("unstitched_3")
        val trimmed3 = LyreRules.trim(three, "lc_b", 0.0, 2.0)
        assertEquals(CutKind.TRIM, trimmed3.plan?.kind)
        assertEquals(2.0, clipOf(trimmed3.board, "lc_b").durationSec, 0.0)
        assertEquals(
            LyreClip.clipOf(trimmed3.board.scenes, "fr_c")!!.start,
            clipOf(trimmed3.board, "lc_c").startSec,
            0.0,
        )

        val split = LyreRules.split(unstitched, "lc_b", 1.0)
        assertEquals(CutKind.SPLIT, split.plan?.kind)
        assertEquals(4, split.board.scenes[0].frames.size)
        assertEquals(3, clipIds(split.board).size)
        assertDualWrite(split.board)
        val right = split.board.scenes[0].frames[3]
        val rightClip = split.board.videoLayers[0].clips.first { it.linkedFrameId == right.id }
        assertEquals(right.src, rightClip.let { frameOf(split.board, it.linkedFrameId!!).src })
        assertEquals(right.videoSrc, rightClip.src)
        assertEquals(frameOf(split.board, "fr_b").src, right.src)
        assertEquals(0.0, clipOf(split.board, "lc_b").trimInSec!!, 0.0)
        assertEquals(frameOf(split.board, "fr_b").videoInSec, clipOf(split.board, "lc_b").trimInSec)

        val zeroSource = replaceClipAndFrame(
            unstitched,
            clipOf(unstitched, "lc_b").copy(sourceDurationSec = 0.0),
            frameOf(unstitched, "fr_b"),
        )
        val splitZero = LyreRules.split(zeroSource, "lc_b", 1.0)
        assertEquals(CutKind.SPLIT, splitZero.plan?.kind)
        assertEquals(4, splitZero.board.scenes[0].frames.size)

        val splitMovie = LyreRules.split(unstitched, "lc_a", 1.0)
        assertNull(splitMovie.plan)
        assertSame(unstitched, splitMovie.board)
    }

    @Test
    fun restoreClipAndPictureSwapOrigLive() {
        val unstitched = fixture("unstitched")
        val liveSrc = "boards/lyre/clips/lc_b_trim.mp4"
        val origSrc = "boards/lyre/clips/lc_b.mp4"
        val withOrig = replaceClipAndFrame(
            unstitched,
            clipOf(unstitched, "lc_b").copy(
                src = liveSrc,
                origSrc = origSrc,
                origDurationSec = 3.0,
                durationSec = 2.0,
                sourceDurationSec = 2.0,
            ),
            frameOf(unstitched, "fr_b").copy(
                videoSrc = liveSrc,
                origVideoSrc = origSrc,
                origVideoDurationSec = 3.0,
                videoDurationSec = 2.0,
                durationSec = 2.0,
            ),
        )
        val restored = LyreRules.restoreClip(withOrig, "lc_b")
        assertNull(restored.plan)
        assertEquals(origSrc, clipOf(restored.board, "lc_b").src)
        assertEquals(liveSrc, clipOf(restored.board, "lc_b").origSrc)
        assertEquals(origSrc, frameOf(restored.board, "fr_b").videoSrc)
        assertEquals(liveSrc, frameOf(restored.board, "fr_b").origVideoSrc)
        assertDualWrite(restored.board)

        val stillLive = "boards/lyre/frames/fr_b.jpg"
        val stillOrig = "boards/lyre/frames/fr_b_orig.jpg"
        val pictured = replaceFrame(
            unstitched,
            frameOf(unstitched, "fr_b").copy(
                extra = JSONObject().put("origSrc", stillOrig),
            ),
        )
        val pic = LyreRules.restorePicture(pictured, "fr_b")
        assertNull(pic.plan)
        assertEquals(stillOrig, frameOf(pic.board, "fr_b").src)
        assertEquals(stillLive, frameOf(pic.board, "fr_b").extra?.optString("origSrc"))
    }

    @Test
    fun extractAudioAddsRailMuteDoesNot() {
        val unstitched = fixture("unstitched")
        val extracted = LyreRules.extractAudio(unstitched, "lc_b")
        assertEquals(CutKind.EXTRACT, extracted.plan?.kind)
        assertEquals(1, extracted.board.audioLayers.size)
        assertEquals(1, extracted.board.audioLayers[0].clips.size)
        val bed = extracted.board.audioLayers[0].clips[0]
        assertEquals("fr_b", bed.linkedFrameId)
        assertEquals(clipOf(unstitched, "lc_b").startSec, bed.startSec, 0.0)
        assertEquals("boards/lyre/audio/${bed.id}.m4a", bed.src)
        assertTrue(unstitched.audioLayers.isEmpty())

        val twice = LyreRules.extractAudio(extracted.board, "lc_b")
        val srcs = twice.board.audioLayers.flatMap { it.clips }.map { it.src }
        assertEquals(2, srcs.size)
        assertEquals(2, srcs.toSet().size)

        val muted = LyreRules.mute(unstitched, "lc_b")
        assertTrue(muted.board.audioLayers.isEmpty())
        assertEquals(true, frameOf(muted.board, "fr_b").videoMuted)

        val movieExtract = LyreRules.extractAudio(unstitched, "lc_a")
        assertNull(movieExtract.plan)
        assertSame(unstitched, movieExtract.board)
    }

    @Test
    fun insertAudioLeftoverOnlyZeroStartOk() {
        val unstitched = fixture("unstitched")
        val leftover = LyreRules.insertAudio(
            unstitched,
            "fr_b",
            "boards/lyre/audio/bed.m4a",
            "Bed",
            2.0,
        )
        assertNull(leftover.plan)
        assertEquals(1, leftover.board.audioLayers.size)
        val bed = leftover.board.audioLayers[0].clips[0]
        assertEquals("fr_b", bed.linkedFrameId)
        assertEquals(clipOf(unstitched, "lc_b").startSec, bed.startSec, 0.0)
        assertEquals("boards/lyre/audio/bed.m4a", bed.src)

        val movie = LyreRules.insertAudio(
            unstitched,
            "fr_a",
            "boards/lyre/audio/bed.m4a",
            "Bed",
            2.0,
        )
        assertSame(unstitched, movie.board)

        val hold = LyreRules.insertAudio(
            unstitched,
            "fr_hold",
            "boards/lyre/audio/bed.m4a",
            "Bed",
            1.0,
        )
        assertEquals(1, hold.board.audioLayers.size)
        assertEquals("fr_hold", hold.board.audioLayers[0].clips[0].linkedFrameId)
        assertEquals(4.0, hold.board.audioLayers[0].clips[0].startSec, 0.0)

        val holdAtZero = unstitched.copy(
            scenes = unstitched.scenes.map { sc ->
                sc.copy(
                    frames = listOf(Frame(id = "fr_zero", src = "", caption = "Hold", durationSec = 2.0)) + sc.frames,
                )
            },
        )
        assertFalse(LyreMovie.frameInMovie(holdAtZero.movie, holdAtZero.videoLayers, "fr_zero"))
        val zero = LyreRules.insertAudio(
            holdAtZero,
            "fr_zero",
            "boards/lyre/audio/bed.m4a",
            "Bed",
            2.0,
        )
        assertEquals(0.0, zero.board.audioLayers[0].clips[0].startSec, 0.0)
        assertEquals("fr_zero", zero.board.audioLayers[0].clips[0].linkedFrameId)
    }

    @Test
    fun scenesLibraryBinRulesAndDualWrite() {
        val unstitched = fixture("unstitched")
        val added = LyreRules.addScene(unstitched)
        assertEquals(2, added.board.scenes.size)
        assertEquals(added.board.scenes.last().id, added.board.activeSceneId)
        assertTrue(added.board.scenes.last().frames.isEmpty())

        val renamed = LyreRules.renameScene(added.board, "sc_1", "Prologue")
        assertEquals("Prologue", renamed.board.scenes[0].title)
        assertSame(added.board, LyreRules.renameScene(added.board, "sc_1", "  ").board)

        val lastId = renamed.board.scenes.last().id
        val moved = LyreRules.moveScene(renamed.board, lastId, 0)
        assertEquals(lastId, moved.board.scenes[0].id)
        assertEquals("sc_1", moved.board.scenes[1].id)
        assertSame(moved.board, LyreRules.moveScene(moved.board, lastId, 0).board)

        val still = RefImage(id = "rf_lib", src = "boards/lyre/frames/lib.jpg", caption = "LibStill")
        val prefixStill = LyreRules.insertLibraryStill(unstitched, still, "fr_a")
        assertSame(unstitched, prefixStill.board)
        val dumpedStill = still.copy(fromFrameId = "fr_x", fromSceneId = "sc_1")
        assertSame(unstitched, LyreRules.insertLibraryStill(unstitched, dumpedStill, "fr_b").board)
        val insertedStill = LyreRules.insertLibraryStill(unstitched, still, "fr_b")
        assertEquals("LibStill", insertedStill.board.scenes[0].frames.last().caption)
        assertFalse(
            LyreMovie.frameInMovie(
                insertedStill.board.movie,
                insertedStill.board.videoLayers,
                insertedStill.board.scenes[0].frames.last().id,
            ),
        )

        val item = LibraryVideo(id = "lv_1", src = "boards/lyre/clips/lib.mp4", name = "Lib", durationSec = 5.0)
        val prefixVideo = LyreRules.insertLibraryVideo(unstitched, item, "fr_a")
        assertSame(unstitched, prefixVideo.board)
        val insertedVideo = LyreRules.insertLibraryVideo(unstitched, item, "fr_b")
        val videoFrame = insertedVideo.board.scenes[0].frames.last()
        val videoClip = insertedVideo.board.videoLayers.flatMap { it.clips }
            .first { it.linkedFrameId == videoFrame.id }
        assertEquals(videoFrame.videoSrc, videoClip.src)
        assertEquals(item.src, videoFrame.videoSrc)
        assertDualWrite(insertedVideo.board)

        val zeroVideo = LibraryVideo(id = "lv_0", src = "boards/lyre/clips/zero.mp4", name = "Zero", durationSec = 0.0)
        val zeroInserted = LyreRules.insertLibraryVideo(unstitched, zeroVideo, "fr_b")
        assertEquals(0.1, zeroInserted.board.scenes[0].frames.last().durationSec, 0.0)

        val dumped = LyreRules.dumpScene(unstitched, "sc_1")
        assertEquals(1, dumped.board.scenes.size)
        assertTrue(dumped.board.scenes[0].frames.isEmpty())
        assertTrue(dumped.board.videoLayers.flatMap { it.clips }.isEmpty())
        val bin = dumped.board.refFolders.flatMap { it.images }
        assertTrue(bin.any { it.fromFrameId == "fr_b" && it.fromSceneId == "sc_1" })
        val dumpedBeat = bin.first { it.id == "fr_b" }
        assertEquals("boards/lyre/clips/lc_b.mp4", dumpedBeat.videoSrc)

        val recycled = LyreRules.recycle(dumped.board, dumpedBeat.id)
        val restored = recycled.board.scenes.flatMap { it.frames }.first { it.id == "fr_b" }
        val restoredClip = recycled.board.videoLayers.flatMap { it.clips }
            .first { it.linkedFrameId == restored.id }
        assertEquals(restored.videoSrc, restoredClip.src)
        assertEquals(dumpedBeat.videoSrc, restored.videoSrc)
        assertFalse(LyreMovie.frameInMovie(recycled.board.movie, recycled.board.videoLayers, restored.id))
        assertDualWrite(recycled.board)
        assertTrue(recycled.board.refFolders.flatMap { it.images }.none { it.id == dumpedBeat.id })

        val withLib = unstitched.copy(
            refFolders = listOf(RefFolder(id = "lib", name = "Library", images = listOf(still))),
        )
        val deleted = LyreRules.deleteRefImage(withLib, still.id)
        assertTrue(deleted.board.refFolders.all { folder -> folder.images.none { it.id == still.id } })
        assertSame(withLib, LyreRules.deleteRefImage(withLib, "missing").board)
        assertSame(dumped.board, LyreRules.recycle(dumped.board, still.id).board)
    }

    @Test
    fun leftoverMutationsKeepAudioLayersInSync() {
        val three = fixture("unstitched_3")
        val extracted = LyreRules.extractAudio(three, "lc_c")
        assertEquals(clipOf(three, "lc_c").startSec, extracted.board.audioLayers[0].clips[0].startSec, 0.0)

        val trimmed = LyreRules.trim(extracted.board, "lc_b", 0.0, 1.0)
        val wantStart = LyreClip.clipOf(trimmed.board.scenes, "fr_c")!!.start
        assertEquals(7.0, wantStart, 0.0)
        assertEquals(wantStart, trimmed.board.audioLayers[0].clips[0].startSec, 0.0)

        val dumped = LyreRules.dumpScene(extracted.board, "sc_1")
        assertTrue(dumped.board.audioLayers.flatMap { it.clips }.isEmpty())

        val beatGone = LyreRules.removeBeat(extracted.board, "fr_c")
        assertTrue(beatGone.board.audioLayers.flatMap { it.clips }.none { it.linkedFrameId == "fr_c" })
    }

    @Test
    fun burnAudioWritesBurnKeepsCaptionJson() {
        val unstitched = fixture("unstitched")
        val extracted = LyreRules.extractAudio(unstitched, "lc_b")
        val burned = LyreRules.burnAudio(extracted.board)
        assertEquals(CutKind.BURN_AUDIO, burned.plan?.kind)
        assertEquals("boards/lyre/movie.burn.mp4", burned.board.movie?.src)
        assertEquals(unstitched.movie?.src, burned.board.movie?.origSrc)
        assertEquals("A", frameOf(burned.board, "fr_a").caption)
        assertEquals("B", frameOf(burned.board, "fr_b").caption)
        assertEquals(frameOf(unstitched, "fr_a").dialogue, frameOf(burned.board, "fr_a").dialogue)
        assertEquals(frameOf(unstitched, "fr_a").notes, frameOf(burned.board, "fr_a").notes)

        val empty = LyreRules.burnAudio(unstitched)
        assertSame(unstitched, empty.board)
        assertNull(empty.plan)
    }

    private fun fixture(name: String): BoardData {
        val path = "/io/grokify/os/apps/lyre/fixtures/$name.json"
        val text = LyreRulesTest::class.java.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
            ?: error("missing $path")
        return LyreBoardCodec.decode(JSONObject(text))
    }

    private fun partIds(board: BoardData) = board.movie?.parts.orEmpty().map { it.clipId }

    private fun clipIds(board: BoardData) = board.videoLayers.flatMap { it.clips }.map { it.id }

    private fun clipOf(board: BoardData, id: String) =
        board.videoLayers.flatMap { it.clips }.first { it.id == id }

    private fun frameOf(board: BoardData, id: String) =
        board.scenes.flatMap { it.frames }.first { it.id == id }

    private fun assertDualWrite(board: BoardData) {
        val clips = board.videoLayers.flatMap { it.clips }
        for (frame in board.scenes.flatMap { it.frames }) {
            val src = frame.videoSrc
            if (src.isNullOrEmpty()) continue
            val linked = clips.filter { it.linkedFrameId == frame.id }
            assertEquals("exactly one clip for ${frame.id}", 1, linked.size)
            assertEquals(src, linked[0].src)
        }
    }

    private fun replaceClipAndFrame(board: BoardData, clip: LayerClip, frame: Frame): BoardData {
        return board.copy(
            videoLayers = board.videoLayers.map { layer ->
                layer.copy(clips = layer.clips.map { if (it.id == clip.id) clip else it })
            },
            scenes = board.scenes.map { scene ->
                scene.copy(frames = scene.frames.map { if (it.id == frame.id) frame else it })
            },
        )
    }

    private fun replaceFrame(board: BoardData, frame: Frame): BoardData {
        return board.copy(
            scenes = board.scenes.map { scene ->
                scene.copy(frames = scene.frames.map { if (it.id == frame.id) frame else it })
            },
        )
    }
}
