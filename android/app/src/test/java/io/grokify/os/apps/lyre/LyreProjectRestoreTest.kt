package io.grokify.os.apps.lyre

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyreProjectRestoreTest {
    private val odysseus = LyreProject(
        id = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        name = "Odysseus",
        boardId = "lyre",
        isOdysseus = true,
        updatedAt = "2026-01-01 00:00:00",
    )
    private val phoneNew = LyreProject(
        id = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        name = "Bot board",
        boardId = "lyre_phone_11111111-1111-1111-1111-111111111111",
        isOdysseus = false,
        updatedAt = "2026-08-28 12:00:00",
    )
    private val phoneOld = LyreProject(
        id = "cccccccccccccccccccccccccccccccc",
        name = "Older phone",
        boardId = "lyre_phone_22222222-2222-2222-2222-222222222222",
        isOdysseus = false,
        updatedAt = "2026-08-27 12:00:00",
    )

    @Test
    fun missingStoredIdLoadsOdysseus() {
        val list = listOf(phoneNew, odysseus, phoneOld)
        val hit = lyreRestoreProject(list, "")
        assertEquals(odysseus.id, hit?.id)
        assertTrue(hit!!.isOdysseus)
    }

    @Test
    fun staleStoredIdLoadsOdysseus() {
        val list = listOf(phoneNew, odysseus)
        val hit = lyreRestoreProject(list, "dddddddddddddddddddddddddddddddd")
        assertEquals(odysseus.id, hit?.id)
    }

    @Test
    fun listedIdRestoredEvenIfNotOdysseus() {
        val list = listOf(phoneNew, odysseus, phoneOld)
        val hit = lyreRestoreProject(list, phoneOld.id)
        assertEquals(phoneOld.id, hit?.id)
    }

    @Test
    fun neverAutoPicksLatestPhoneBoard() {
        val list = listOf(phoneNew, phoneOld, odysseus)
        val hit = lyreRestoreProject(list, "")
        assertEquals(odysseus.id, hit?.id)
        assertTrue(hit!!.boardId != phoneNew.boardId)
    }

    @Test
    fun emptyListReturnsNull() {
        assertNull(lyreRestoreProject(emptyList(), "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
    }

    @Test
    fun copyLinkCopiesWhenConnectorLinkPresent() {
        val json = JSONObject()
            .put("ok", true)
            .put("has_connector", true)
            .put("connector_link", "https://grokifyos.grokpot.io/mcp/lyre_mcp_aaa")
        val d = lyreCopyLinkDecision(json)
        assertEquals(LyreCopyLinkKind.COPY, d.kind)
        assertEquals("https://grokifyos.grokpot.io/mcp/lyre_mcp_aaa", d.link)
    }

    @Test
    fun copyLinkPromptsRotateOnlyWhenHasConnectorAndNoLink() {
        val json = JSONObject()
            .put("ok", true)
            .put("has_connector", true)
            .put("connector_link", JSONObject.NULL)
        val d = lyreCopyLinkDecision(json)
        assertEquals(LyreCopyLinkKind.PROMPT_ROTATE, d.kind)
    }

    @Test
    fun copyLinkDoesNotRotateOnError() {
        val json = JSONObject()
            .put("ok", false)
            .put("error", "confirm_required")
            .put("has_connector", true)
        val d = lyreCopyLinkDecision(json)
        assertEquals(LyreCopyLinkKind.ERROR, d.kind)
        assertEquals("confirm_required", d.error)
    }

    @Test
    fun copyLinkDoesNotRotateWhenEnsureOkButNoConnector() {
        val json = JSONObject()
            .put("ok", true)
            .put("has_connector", false)
        val d = lyreCopyLinkDecision(json)
        assertEquals(LyreCopyLinkKind.ERROR, d.kind)
    }
}
