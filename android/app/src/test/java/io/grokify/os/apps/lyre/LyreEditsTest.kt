package io.grokify.os.apps.lyre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LyreEditsTest {
    @Test
    fun timelineDurationFollowsLongestAudioBed() {
        val board = sample()
        assertEquals(90.0, LyreStorageKeys.timelineDuration(board), 0.001)
    }

    @Test
    fun leftoverStartSnapsPastMovie() {
        val board = sample()
        assertEquals(10.0, LyreEdits.leftoverStart(board, 3.0), 0.001)
        assertEquals(12.0, LyreEdits.leftoverStart(board, 12.0), 0.001)
    }

    @Test
    fun placeAudioAtPlayhead() {
        val r = Random(1)
        val next = LyreEdits.placeAudioAt(sample(), "me:audio/new.wav", "Bed", 4.0, 5.5, random = r)
        assertEquals(1, next.audioLayers.size)
        assertEquals(2, next.audioLayers[0].clips.size)
        val clip = next.audioLayers[0].clips.last()
        assertEquals(5.5, clip.startSec, 0.0)
        assertEquals(4.0, clip.durationSec, 0.0)
        assertEquals("me:audio/new.wav", clip.src)
        assertTrue(clip.id.startsWith("lc_"))
        val after = LyreEdits.placeAudioAt(sample(), "me:audio/tail.wav", "Tail", 4.0, 91.0, random = r)
        assertEquals(1, after.audioLayers.size)
        assertEquals(91.0, after.audioLayers[0].clips.last().startSec, 0.0)
    }

    @Test
    fun addAudioTrackKeepsEmptyLane() {
        val next = LyreEdits.addAudioTrack(sample(), Random(7))
        assertEquals(2, next.audioLayers.size)
        assertEquals("A2", next.audioLayers[1].name)
        assertTrue(next.audioLayers[1].clips.isEmpty())
        assertEquals(1, next.audioLayers[0].clips.size)
        val again = LyreEdits.addAudioTrack(next, Random(8))
        assertEquals(3, again.audioLayers.size)
        assertEquals("A3", again.audioLayers[2].name)
    }

    @Test
    fun moveAudioClipOntoOccupiedTrackStaysThere() {
        val overlap = sample().copy(
            audioLayers = listOf(
                MediaLayer(
                    id = "ly_a",
                    kind = "audio",
                    name = "A1",
                    clips = listOf(
                        LayerClip("lc_a1", "me:audio/a.wav", "A", 0.0, 90.0, volume = 1.0),
                        LayerClip("lc_a2", "me:audio/b.wav", "B", 24.0, 80.0, volume = 1.0),
                    ),
                ),
                MediaLayer(id = "ly_a2", kind = "audio", name = "A2", clips = emptyList()),
            ),
        )
        val onA2 = LyreEdits.moveAudioClip(overlap, "lc_a2", 24.0, preferLane = 1, random = Random(4))
        assertEquals(2, onA2.audioLayers.size)
        assertEquals(listOf("lc_a1"), onA2.audioLayers[0].clips.map { it.id })
        assertEquals(listOf("lc_a2"), onA2.audioLayers[1].clips.map { it.id })
        assertEquals(24.0, onA2.audioLayers[1].clips.single().startSec, 0.0)

        val back = LyreEdits.moveAudioClip(onA2, "lc_a2", 10.0, preferLane = 0, random = Random(5))
        assertEquals(2, back.audioLayers.size)
        assertEquals(setOf("lc_a1", "lc_a2"), back.audioLayers[0].clips.map { it.id }.toSet())
        assertTrue(back.audioLayers[1].clips.isEmpty())
        assertEquals(10.0, back.audioLayers[0].clips.first { it.id == "lc_a2" }.startSec, 0.0)
    }

    @Test
    fun trimAudioKeepsEmptyTrack() {
        val withTrack = LyreEdits.addAudioTrack(sample(), Random(9))
        val next = LyreEdits.trimClip(withTrack, "lc_a1", 10.0, 40.0)
        assertEquals(2, next.audioLayers.size)
        assertTrue(next.audioLayers[1].clips.isEmpty())
        val clip = next.audioLayers[0].clips.single()
        assertEquals(10.0, clip.startSec, 0.001)
        assertEquals(30.0, clip.durationSec, 0.001)
    }

    @Test
    fun packOverlappingAudioOntoSeparateLanes() {
        val packed = LyreEdits.packedAudioLanes(sample().audioLayers)
        assertEquals(1, packed.size)
        val overlap = sample().copy(
            audioLayers = listOf(
                MediaLayer(
                    id = "ly_a",
                    kind = "audio",
                    name = "A1",
                    clips = listOf(
                        LayerClip("lc_a1", "me:audio/a.wav", "A", 0.0, 90.0, volume = 1.0),
                        LayerClip("lc_a2", "me:audio/b.wav", "B", 24.0, 80.0, volume = 1.0),
                    ),
                ),
                MediaLayer(id = "ly_a2", kind = "audio", name = "A2", clips = emptyList()),
            ),
        )
        val lanes = LyreEdits.packedAudioLanes(overlap.audioLayers)
        assertEquals(2, lanes.size)
        assertEquals("lc_a1", lanes[0].single().id)
        assertEquals("lc_a2", lanes[1].single().id)
        val persisted = LyreEdits.packAudioLayers(overlap, Random(3))
        assertEquals("lc_a1", persisted.audioLayers[0].clips.single().id)
        assertEquals("lc_a2", persisted.audioLayers[1].clips.single().id)
        lanes.forEach { lane ->
            for (i in lane.indices) {
                for (j in i + 1 until lane.size) {
                    assertFalse(LyreEdits.overlaps(lane[i], lane[j]))
                }
            }
        }
    }

    @Test
    fun moveAudioKeepsLanesClear() {
        val overlap = sample().copy(
            audioLayers = listOf(
                MediaLayer(
                    id = "ly_a",
                    kind = "audio",
                    name = "A1",
                    clips = listOf(
                        LayerClip("lc_a1", "me:audio/a.wav", "A", 0.0, 90.0, volume = 1.0),
                        LayerClip("lc_a2", "me:audio/b.wav", "B", 24.0, 80.0, volume = 1.0),
                    ),
                ),
            ),
        )
        val moved = LyreEdits.moveAudioClip(overlap, "lc_a2", 100.0, preferLane = 0, random = Random(4))
        assertEquals(1, moved.audioLayers.size)
        assertEquals(2, moved.audioLayers[0].clips.size)
        assertEquals(100.0, moved.audioLayers[0].clips.first { it.id == "lc_a2" }.startSec, 0.0)
    }

    @Test
    fun trimAudioMovesInPoint() {
        val next = LyreEdits.trimClip(sample(), "lc_a1", 10.0, 40.0)
        val clip = next.audioLayers[0].clips.single()
        assertEquals(10.0, clip.startSec, 0.001)
        assertEquals(30.0, clip.durationSec, 0.001)
        assertEquals(10.0, clip.trimInSec!!, 0.001)
    }

    @Test
    fun trimStillRightLengthensHold() {
        val next = LyreEdits.trimStillRight(sample(), "fr_a", 16.0)
        assertEquals(16.0, next.scenes[0].frames[0].durationSec, 0.0)
        val left = LyreEdits.trimStillLeft(next, "fr_b", 18.0)
        assertEquals(18.0, left.scenes[0].frames[0].durationSec, 0.001)
        assertEquals(6.0, left.scenes[1].frames[0].durationSec, 0.001)
    }

    @Test
    fun moveStillIntoLaterScene() {
        val next = LyreEdits.moveStillTo(sample(), "fr_a", 14.0)
        assertTrue(next.scenes[0].frames.none { it.id == "fr_a" })
        assertEquals("fr_a", next.scenes[1].frames.last().id)
    }

    @Test
    fun pictureVideoClipsSitUnderStills() {
        val drawn = LyreEdits.pictureVideoClips(paired())
        assertEquals(listOf("lc_a", "lc_c"), drawn.map { it.id })
        assertEquals(0.0, drawn[0].startSec, 0.0)
        assertEquals(6.0, drawn[0].durationSec, 0.0)
        assertEquals(10.0, drawn[1].startSec, 0.0)
        assertEquals(8.0, drawn[1].durationSec, 0.0)
        assertFalse(LyreEdits.overlaps(drawn[0], drawn[1]))
    }

    @Test
    fun syncLinkedVideoWritesStillTimes() {
        val next = LyreEdits.syncLinkedVideo(paired())
        val a = next.videoLayers[0].clips.first { it.id == "lc_a" }
        val c = next.videoLayers[0].clips.first { it.id == "lc_c" }
        assertEquals(0.0, a.startSec, 0.0)
        assertEquals(6.0, a.durationSec, 0.0)
        assertEquals(10.0, c.startSec, 0.0)
        assertEquals(8.0, c.durationSec, 0.0)
        assertFalse(LyreEdits.overlaps(a, c))
    }

    @Test
    fun moveStillReordersPairedVideo() {
        val next = LyreEdits.moveStillTo(paired(), "fr_a", 12.0)
        assertEquals(listOf("fr_b", "fr_c", "fr_a"), next.scenes[0].frames.map { it.id })
        val clips = LyreClip.movieClips(next.scenes)
        val vids = LyreEdits.pictureVideoClips(next)
        assertEquals(clips.first { it.frame.id == "fr_a" }.start, vids.first { it.id == "lc_a" }.startSec, 0.001)
        assertEquals(clips.first { it.frame.id == "fr_c" }.start, vids.first { it.id == "lc_c" }.startSec, 0.001)
        assertEquals(clips.first { it.frame.id == "fr_a" }.length, vids.first { it.id == "lc_a" }.durationSec, 0.001)
        assertFalse(LyreEdits.overlaps(vids[0], vids[1]))
    }

    @Test
    fun moveVideoReordersStillSequenceNotFreeTime() {
        val next = LyreEdits.moveVideoClip(paired(), "lc_a", 12.0)
        assertEquals(listOf("fr_b", "fr_c", "fr_a"), next.scenes[0].frames.map { it.id })
        val a = next.videoLayers[0].clips.first { it.id == "lc_a" }
        val sc = LyreClip.clipOf(next.scenes, "fr_a")!!
        assertEquals(sc.start, a.startSec, 0.001)
        assertEquals(sc.length, a.durationSec, 0.001)
        val vids = next.videoLayers[0].clips.filter { !it.linkedFrameId.isNullOrEmpty() }
        for (i in vids.indices) {
            for (j in i + 1 until vids.size) {
                assertFalse(LyreEdits.overlaps(vids[i], vids[j]))
            }
        }
    }

    @Test
    fun trimVideoRightMatchesStillAndRipples() {
        val next = LyreEdits.trimClip(paired(), "lc_a", 0.0, 10.0)
        assertEquals(10.0, next.scenes[0].frames[0].durationSec, 0.001)
        val a = next.videoLayers[0].clips.first { it.id == "lc_a" }
        val c = next.videoLayers[0].clips.first { it.id == "lc_c" }
        assertEquals(0.0, a.startSec, 0.0)
        assertEquals(10.0, a.durationSec, 0.001)
        assertEquals(14.0, LyreClip.clipOf(next.scenes, "fr_c")!!.start, 0.001)
        assertEquals(14.0, c.startSec, 0.001)
        assertEquals(8.0, c.durationSec, 0.001)
        assertFalse(LyreEdits.overlaps(a, c))
    }

    @Test
    fun trimVideoLeftRollsCutWithPrevious() {
        val next = LyreEdits.trimClip(paired(), "lc_c", 12.0, 18.0)
        assertEquals(6.0, next.scenes[0].frames[0].durationSec, 0.001)
        assertEquals(6.0, next.scenes[0].frames[1].durationSec, 0.001)
        assertEquals(6.0, next.scenes[0].frames[2].durationSec, 0.001)
        val c = next.videoLayers[0].clips.first { it.id == "lc_c" }
        assertEquals(12.0, c.startSec, 0.001)
        assertEquals(6.0, c.durationSec, 0.001)
        assertEquals(2.0, c.trimInSec!!, 0.001)
        assertEquals(LyreClip.clipOf(next.scenes, "fr_c")!!.start, c.startSec, 0.001)
        assertEquals(
            LyreClip.clipOf(next.scenes, "fr_a")!!.length,
            next.videoLayers[0].clips.first { it.id == "lc_a" }.durationSec,
            0.001,
        )
        assertFalse(LyreEdits.overlaps(next.videoLayers[0].clips[0], next.videoLayers[0].clips[1]))
    }

    @Test
    fun trimFirstPairLeftCutsHeadKeepsPackedStart() {
        val next = LyreEdits.trimClip(paired(), "lc_a", 2.0, 6.0)
        val sc = LyreClip.clipOf(next.scenes, "fr_a")!!
        assertEquals(0.0, sc.start, 0.0)
        assertEquals(4.0, sc.length, 0.001)
        val a = next.videoLayers[0].clips.first { it.id == "lc_a" }
        assertEquals(0.0, a.startSec, 0.0)
        assertEquals(4.0, a.durationSec, 0.001)
        assertEquals(2.0, a.trimInSec!!, 0.001)
        assertEquals(4.0, LyreClip.clipOf(next.scenes, "fr_b")!!.start, 0.001)
        val c = next.videoLayers[0].clips.first { it.id == "lc_c" }
        assertEquals(8.0, c.startSec, 0.001)
        assertFalse(LyreEdits.overlaps(a, c))
    }

    @Test
    fun trimStillRightUpdatesLinkedVideo() {
        val next = LyreEdits.trimStillRight(paired(), "fr_a", 9.0)
        val a = next.videoLayers[0].clips.first { it.id == "lc_a" }
        assertEquals(9.0, next.scenes[0].frames[0].durationSec, 0.0)
        assertEquals(9.0, a.durationSec, 0.0)
        assertEquals(0.0, a.startSec, 0.0)
        assertEquals(13.0, next.videoLayers[0].clips.first { it.id == "lc_c" }.startSec, 0.001)
    }

    @Test
    fun rearrangingMoviePairClearsStitch() {
        val next = LyreEdits.moveStillTo(sample(), "fr_a", 14.0)
        assertNull(next.movie)
        val clip = next.videoLayers[0].clips.first { it.linkedFrameId == "fr_a" }
        val sc = LyreClip.clipOf(next.scenes, "fr_a")!!
        assertEquals(sc.start, clip.startSec, 0.001)
        assertEquals(sc.length, clip.durationSec, 0.001)
    }

    @Test
    fun attachGeneratedVideoLandsUnderStill() {
        val r = Random(5)
        val next = LyreEdits.attachGeneratedVideo(paired(), "fr_b", "me:videos/vid_hold.mp4", 4.0, "Hold", r)
        val clip = next.videoLayers[0].clips.first { it.linkedFrameId == "fr_b" }
        val sc = LyreClip.clipOf(next.scenes, "fr_b")!!
        assertEquals(sc.start, clip.startSec, 0.001)
        assertEquals(sc.length, clip.durationSec, 0.001)
        assertEquals("me:videos/vid_hold.mp4", clip.src)
        val vids = next.videoLayers[0].clips
        for (i in vids.indices) {
            for (j in i + 1 until vids.size) {
                assertFalse(LyreEdits.overlaps(vids[i], vids[j]))
            }
        }
    }

    @Test
    fun envelopeGainAndFade() {
        val faded = LyreEdits.fadeAudio(sample(), "lc_a1", fadeInSec = 9.0)
        val clip = faded.audioLayers[0].clips[0]
        val env = LyreEnvelope.parseClip(clip)!!
        assertTrue(env.on)
        assertEquals(0.0, LyreEnvelope.gainAt(clip, 0.0), 0.001)
        assertTrue(LyreEnvelope.gainAt(clip, 9.0) > 0.9)
        val moved = LyreEnvelope.movePoint(clip, 0, 0.0, 0.5)
        assertEquals(0.5, moved.points.first().v, 0.0)
    }

    @Test
    fun insertStillAfterKeepsOrder() {
        val r = Random(4)
        val next = LyreEdits.insertStillAfter(sample(), "fr_a", "me:stills/st_next.jpg", "Next", 5.0, random = r)
        assertEquals(2, next.scenes[0].frames.size)
        assertEquals("fr_a", next.scenes[0].frames[0].id)
        assertEquals("me:stills/st_next.jpg", next.scenes[0].frames[1].src)
        assertEquals(5.0, next.scenes[0].frames[1].durationSec, 0.0)
    }

    @Test
    fun nextBreakStaysWithStillThatOwnsPlayhead() {
        assertEquals("fr_a", LyreEdits.nextBreakFrameId(sample(), 3.0))
        assertEquals("fr_a", LyreEdits.nextBreakFrameId(sample(), 10.0))
        assertEquals("fr_b", LyreEdits.nextBreakFrameId(sample(), 10.1))
        assertEquals("fr_b", LyreEdits.nextBreakFrameId(sample(), 90.0))
        assertNull(LyreEdits.nextBreakFrameId(sample().copy(scenes = emptyList()), 0.0))
    }

    @Test
    fun insertPictureAfterBreakChainsUploads() {
        val r = Random(4)
        val host = LyreEdits.nextBreakFrameId(sample(), 3.0)
        assertEquals("fr_a", host)
        val a = LyreEdits.insertPictureAfter(sample(), host, "me:stills/st_1.jpg", "One", 6.0, random = r)
        assertEquals(listOf("fr_a", a.frameId), a.board.scenes[0].frames.map { it.id })
        val b = LyreEdits.insertPictureAfter(a.board, a.frameId, "me:stills/st_2.jpg", "Two", 6.0, random = r)
        assertEquals(listOf("fr_a", a.frameId, b.frameId), b.board.scenes[0].frames.map { it.id })
        assertEquals("fr_b", b.board.scenes[1].frames.single().id)
        val empty = sample().copy(scenes = sample().scenes.map { it.copy(frames = emptyList()) })
        val first = LyreEdits.insertPictureAfter(empty, null, "me:stills/st_empty.jpg", "First", 6.0, random = r)
        assertEquals("me:stills/st_empty.jpg", first.board.scenes.first { it.id == empty.activeSceneId }.frames.single().src)
        assertTrue(first.frameId.isNotEmpty())
    }

    @Test
    fun insertPictureAfterPairsVideoUnderStill() {
        val r = Random(11)
        val ins = LyreEdits.insertPictureAfter(
            paired(),
            "fr_a",
            "me:stills/st_new.jpg",
            "New",
            5.0,
            videoSrc = "me:videos/new.mp4",
            videoDurationSec = 5.0,
            random = r,
        )
        assertEquals("me:videos/new.mp4", ins.board.scenes[0].frames[1].videoSrc)
        val clip = LyreEdits.pictureVideoClips(ins.board).first { it.linkedFrameId == ins.frameId }
        val sc = LyreClip.clipOf(ins.board.scenes, ins.frameId)!!
        assertEquals(sc.start, clip.startSec, 0.001)
        assertEquals(5.0, clip.durationSec, 0.0)
        assertEquals("me:videos/new.mp4", clip.src)
        assertFalse(LyreEdits.overlaps(LyreEdits.pictureVideoClips(ins.board)[0], clip))
    }

    @Test
    fun placeAudioOnNewTrackStartsAtZero() {
        val next = LyreEdits.placeAudioOnNewTrack(sample(), "me:audio/new.wav", "Voice", 4.0, random = Random(1))
        assertEquals(2, next.audioLayers.size)
        assertEquals(1, next.audioLayers[0].clips.size)
        val clip = next.audioLayers[1].clips.single()
        assertEquals(0.0, clip.startSec, 0.0)
        assertEquals(4.0, clip.durationSec, 0.0)
        assertEquals("me:audio/new.wav", clip.src)
        val empty = sample().copy(audioLayers = emptyList())
        val first = LyreEdits.placeAudioOnNewTrack(empty, "me:audio/x.wav", "X", 2.0, random = Random(2))
        assertEquals(1, first.audioLayers.size)
        assertEquals(0.0, first.audioLayers[0].clips.single().startSec, 0.0)
        val again = LyreEdits.placeAudioOnNewTrack(next, "me:audio/two.wav", "Two", 3.0, random = Random(3))
        assertEquals(3, again.audioLayers.size)
        assertEquals(0.0, again.audioLayers[2].clips.single().startSec, 0.0)
    }

    @Test
    fun mediaKindFromMimeAndName() {
        assertEquals("still", LyreEdits.mediaKind("image/jpeg", "a.jpg"))
        assertEquals("video", LyreEdits.mediaKind("video/mp4", "b.mp4"))
        assertEquals("audio", LyreEdits.mediaKind("audio/mpeg", "c.mp3"))
        assertEquals("video", LyreEdits.mediaKind("", "clip.MOV"))
        assertEquals("audio", LyreEdits.mediaKind("application/octet-stream", "bed.m4a"))
        assertNull(LyreEdits.mediaKind("application/pdf", "notes.pdf"))
    }

    @Test
    fun replaceStillAndAttachVideo() {
        val r = Random(5)
        val edited = LyreEdits.replaceStillSrc(sample(), "fr_a", "me:stills/st_edit.jpg")
        assertEquals("me:stills/st_edit.jpg", edited.scenes[0].frames[0].src)
        val withVid = LyreEdits.attachGeneratedVideo(edited, "fr_b", "me:videos/vid_new.mp4", 6.0, "Ship", r)
        assertEquals("me:videos/vid_new.mp4", withVid.scenes[1].frames[0].videoSrc)
        assertTrue(withVid.videoLayers[0].clips.any { it.src == "me:videos/vid_new.mp4" && it.linkedFrameId == "fr_b" })
        val replaced = LyreEdits.replaceVideoSrc(withVid, withVid.videoLayers[0].clips.last { it.linkedFrameId == "fr_b" }.id, "me:videos/vid_edit.mp4", 8.0)
        val leftover = replaced.videoLayers[0].clips.last { it.linkedFrameId == "fr_b" }
        assertEquals("me:videos/vid_edit.mp4", leftover.src)
        assertEquals("me:videos/vid_new.mp4", leftover.origSrc)
    }

    @Test
    fun libraryAdds() {
        val r = Random(6)
        val still = LyreEdits.addStillToLibrary(sample(), "me:stills/st_lib.jpg", "Lib", r)
        assertTrue(still.refFolders.first { it.id == "rf_env" }.images.any { it.src == "me:stills/st_lib.jpg" })
        val vid = LyreEdits.addVideoToLibrary(still, "me:videos/v.mp4", "V", 4.0, r)
        assertEquals(1, vid.libraryVideo.size)
        val aud = LyreEdits.addAudioToLibrary(vid, "me:audio/a.wav", "A", 3.0, random = r)
        assertEquals(1, aud.libraryAudio.size)
    }

    @Test
    fun addStillToNamedScene() {
        val r = Random(2)
        val next = LyreEdits.addStillToScene(sample(), "sc_2", "me:stills/st_new.jpg", "New", 6.0, random = r)
        assertEquals(1, next.scenes[0].frames.size)
        assertEquals(2, next.scenes[1].frames.size)
        assertEquals("me:stills/st_new.jpg", next.scenes[1].frames[1].src)
        assertEquals("sc_2", next.activeSceneId)
    }

    @Test
    fun removeAudioLeavesMovie() {
        val board = sample()
        val next = LyreEdits.removeClip(board, "lc_a1")
        assertTrue(next.audioLayers[0].clips.none { it.id == "lc_a1" })
        assertEquals(1, next.videoLayers[0].clips.size)
        assertTrue(LyreEdits.isMovieLocked(next, "lc_v"))
        assertEquals(board, LyreEdits.removeClip(board, "lc_movie"))
    }

    @Test
    fun muteAudioVolume() {
        val next = LyreEdits.setClipVolume(sample(), "lc_a1", 0.0)
        assertEquals(0.0, next.audioLayers[0].clips[0].volume!!, 0.0)
    }

    @Test
    fun objectKeysListSceneStillsFirst() {
        val keys = LyreStorageKeys.objectKeys(sample())
        assertEquals("me:stills/st_a.jpg", keys.first())
        assertTrue(keys.contains("me:audio/bed.wav"))
        assertTrue(keys.contains("/stills/ship-01.jpg"))
    }

    @Test
    fun imageKeysSkipVideoAndAudio() {
        val keys = LyreStorageKeys.imageKeys(sample())
        assertEquals("me:stills/st_a.jpg", keys.first())
        assertTrue(keys.contains("/stills/ship-01.jpg"))
        assertTrue(keys.none { it.contains("videos") || it.endsWith(".mp4") })
        assertTrue(keys.none { it.contains("audio") || it.endsWith(".wav") })
    }

    @Test
    fun audioKeysAreAudioOnly() {
        val keys = LyreStorageKeys.audioKeys(sample())
        assertTrue(keys.contains("me:audio/bed.wav"))
        assertTrue(keys.none { LyreStorageKeys.isStillSrc(it) })
        assertTrue(keys.none { it.contains("videos") })
    }

    @Test
    fun fileLookupAliases() {
        val f = java.io.File("/tmp/st_a.jpg")
        val map = HashMap<String, java.io.File>()
        LyreStorageKeys.index(map, "me:stills/st_a.jpg", f)
        assertEquals(f, LyreStorageKeys.file(map, "stills/st_a.jpg"))
        assertEquals(f, LyreStorageKeys.file(map, "/stills/st_a.jpg"))
        assertEquals(f, LyreStorageKeys.file(map, "me:stills/st_a.jpg"))
    }

    private fun paired(): BoardData {
        val scene = Scene(
            id = "sc_p",
            title = "Paired",
            book = "",
            durationTargetSec = 0.0,
            logline = "",
            dialogue = "",
            notes = "",
            frames = listOf(
                Frame(
                    id = "fr_a",
                    src = "me:stills/st_a.jpg",
                    caption = "A",
                    durationSec = 6.0,
                    videoSrc = "me:videos/a.mp4",
                    videoDurationSec = 6.0,
                ),
                Frame(
                    id = "fr_b",
                    src = "me:stills/st_b.jpg",
                    caption = "B",
                    durationSec = 4.0,
                ),
                Frame(
                    id = "fr_c",
                    src = "me:stills/st_c.jpg",
                    caption = "C",
                    durationSec = 8.0,
                    videoSrc = "me:videos/c.mp4",
                    videoDurationSec = 8.0,
                ),
            ),
        )
        return BoardData(
            title = "Paired",
            brainstorm = "",
            scenes = listOf(scene),
            activeSceneId = "sc_p",
            refFolders = emptyList(),
            activeFolderId = "",
            videoLayers = listOf(
                MediaLayer(
                    id = "ly_v",
                    kind = "video",
                    name = "V1",
                    clips = listOf(
                        LayerClip(
                            id = "lc_a",
                            src = "me:videos/a.mp4",
                            name = "A",
                            startSec = 80.0,
                            durationSec = 6.0,
                            sourceDurationSec = 6.0,
                            linkedFrameId = "fr_a",
                        ),
                        LayerClip(
                            id = "lc_c",
                            src = "me:videos/c.mp4",
                            name = "C",
                            startSec = 90.0,
                            durationSec = 8.0,
                            sourceDurationSec = 8.0,
                            linkedFrameId = "fr_c",
                        ),
                    ),
                ),
            ),
        )
    }

    private fun sample(): BoardData {
        val scene1 = Scene(
            id = "sc_1",
            title = "Hall",
            book = "",
            durationTargetSec = 0.0,
            logline = "",
            dialogue = "",
            notes = "",
            frames = listOf(
                Frame(id = "fr_a", src = "me:stills/st_a.jpg", caption = "A", durationSec = 10.0, videoSrc = "me:videos/a.mp4"),
            ),
        )
        val scene2 = Scene(
            id = "sc_2",
            title = "Ship",
            book = "",
            durationTargetSec = 0.0,
            logline = "",
            dialogue = "",
            notes = "",
            frames = listOf(
                Frame(id = "fr_b", src = "/stills/ship-01.jpg", caption = "Ship", durationSec = 8.0),
            ),
        )
        return BoardData(
            title = "Odysseus",
            brainstorm = "",
            scenes = listOf(scene1, scene2),
            activeSceneId = "sc_1",
            refFolders = listOf(
                RefFolder(
                    id = "rf_env",
                    name = "Environment",
                    images = listOf(RefImage(id = "ri_1", src = "/stills/ship-01.jpg", caption = "Ship")),
                ),
            ),
            activeFolderId = "rf_env",
            videoLayers = listOf(
                MediaLayer(
                    id = "ly_v",
                    kind = "video",
                    name = "V1",
                    clips = listOf(
                        LayerClip(
                            id = "lc_v",
                            src = "me:videos/a.mp4",
                            name = "A",
                            startSec = 0.0,
                            durationSec = 10.0,
                            linkedFrameId = "fr_a",
                        ),
                    ),
                ),
            ),
            audioLayers = listOf(
                MediaLayer(
                    id = "ly_a",
                    kind = "audio",
                    name = "A1",
                    clips = listOf(
                        LayerClip(
                            id = "lc_a1",
                            src = "me:audio/bed.wav",
                            name = "Bed",
                            startSec = 0.0,
                            durationSec = 90.0,
                            volume = 1.0,
                        ),
                    ),
                ),
            ),
            movie = BoardMovie(
                src = "me:videos/movie.mp4",
                durationSec = 10.0,
                parts = listOf(MoviePart("lc_v", "me:videos/a.mp4", 10.0)),
            ),
        )
    }
}
