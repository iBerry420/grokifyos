package io.grokify.os.apps.lyre

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyrePollTest {
    private fun head(
        updatedAt: String,
        activityBytes: Long = 0L,
        ok: Boolean = true,
    ): LyreHead = LyreHead(
        ok = ok,
        boardId = "lyre_phone_x",
        updatedAt = updatedAt,
        activityBytes = activityBytes,
    )

    @Test
    fun skipFetchWhenScrubbingOrGenBusy() {
        assertFalse(LyrePoll.shouldFetchHead(saveInFlight = false, boardDirty = false, scrubbing = true, genBusy = false))
        assertFalse(LyrePoll.shouldFetchHead(saveInFlight = false, boardDirty = false, scrubbing = false, genBusy = true))
        assertTrue(LyrePoll.shouldFetchHead(saveInFlight = false, boardDirty = false, scrubbing = false, genBusy = false))
        assertTrue(LyrePoll.shouldFetchHead(saveInFlight = true, boardDirty = true, scrubbing = false, genBusy = false))
    }

    @Test
    fun skipTickMatchesDebounceWindow() {
        assertTrue(LyrePoll.skipTick(saveInFlight = true, boardDirty = false, scrubbing = false, genBusy = false))
        assertTrue(LyrePoll.skipTick(saveInFlight = false, boardDirty = true, scrubbing = false, genBusy = false))
        assertFalse(LyrePoll.skipTick(saveInFlight = false, boardDirty = false, scrubbing = false, genBusy = false))
    }

    @Test
    fun cleanStampChangeReloads() {
        val d = LyrePoll.decide(false, false, "stamp-1", 0L, head("stamp-2"))
        assertEquals(LyrePollKind.RELOAD, d.kind)
        assertTrue(d.reloadBoard)
        assertFalse(d.pullActivity)
    }

    @Test
    fun dirtyStampChangeBannersAndDoesNotReload() {
        val d = LyrePoll.decide(false, true, "stamp-1", 0L, head("stamp-2"))
        assertEquals(LyrePollKind.BANNER, d.kind)
        assertEquals(LyrePoll.BANNER, d.banner)
        assertFalse(d.reloadBoard)
        val inFlight = LyrePoll.decide(true, false, "stamp-1", 0L, head("stamp-2"))
        assertEquals(LyrePollKind.BANNER, inFlight.kind)
    }

    @Test
    fun activityBytesPullWithoutBoardReload() {
        val d = LyrePoll.decide(false, false, "stamp-1", 10L, head("stamp-1", activityBytes = 40L))
        assertEquals(LyrePollKind.PULL_ACTIVITY, d.kind)
        assertTrue(d.pullActivity)
        assertFalse(d.reloadBoard)
    }

    @Test
    fun unchangedHeadIsNoop() {
        val d = LyrePoll.decide(false, false, "stamp-1", 4L, head("stamp-1", 4L))
        assertEquals(LyrePollKind.NOOP, d.kind)
    }

    @Test
    fun saveConflictDropsInFlight() {
        assertEquals(LyreSavePoll.SAVED, LyrePoll.onSaveResponse(true, null))
        assertEquals(LyreSavePoll.CONFLICT_RELOAD, LyrePoll.onSaveResponse(false, "conflict"))
        assertEquals(LyreSavePoll.KEEP_DIRTY, LyrePoll.onSaveResponse(false, "network"))
    }

    @Test
    fun headParsesEnvelope() {
        val json = JSONObject()
            .put("ok", true)
            .put("board_id", "lyre_phone_x")
            .put("updated_at", "2026-08-28 16:01:02.184572+00")
            .put("activity_bytes", 128)
            .put("movie_locked", true)
            .put("next_stitch_clip_id", "lc_b")
        val h = LyreHead.fromJson(json)
        assertEquals("2026-08-28 16:01:02.184572+00", h.updatedAt)
        assertEquals(128L, h.activityBytes)
        assertTrue(h.movieLocked)
        assertEquals("lc_b", h.nextStitchClipId)
    }

    @Test
    fun firstOpenPlan() {
        assertEquals(LyreActivityFirstOpen.PUSH_LOCAL, LyreActivitySync.firstOpenPlan(0L, true))
        assertEquals(LyreActivityFirstOpen.SEED_LOCAL, LyreActivitySync.firstOpenPlan(0L, false))
        assertEquals(LyreActivityFirstOpen.PULL_SERVER, LyreActivitySync.firstOpenPlan(12L, true))
        assertEquals(LyreActivityFirstOpen.PULL_SERVER, LyreActivitySync.firstOpenPlan(12L, false))
    }

    @Test
    fun activityLinesFromJsonNewestFirst() {
        val json = JSONObject().put(
            "lines",
            JSONArray()
                .put(JSONObject().put("ts", 2).put("type", "place").put("projectId", "p").put("summary", "newer").put("actor", "bot"))
                .put(JSONObject().put("ts", 1).put("type", "edit").put("projectId", "p").put("summary", "older").put("actor", "phone")),
        )
        val lines = LyreActivitySync.linesFromJson(json)
        assertEquals(2, lines.size)
        assertEquals("newer", lines[0].summary)
        assertEquals("bot", lines[0].actor)
    }
}
