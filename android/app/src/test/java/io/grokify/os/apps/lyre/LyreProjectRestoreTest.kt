package io.grokify.os.apps.lyre

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
}
