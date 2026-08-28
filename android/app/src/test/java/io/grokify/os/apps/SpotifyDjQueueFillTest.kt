package io.grokify.os.apps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyDjQueueFillTest {

    @Test
    fun skip_withThreeUpcoming_doesNotWaitForFill() {
        assertFalse(djMustWaitForFillBeforeAdvance(3))
        assertFalse(djMustWaitForFillBeforeAdvance(2))
        assertFalse(djMustWaitForFillBeforeAdvance(1))
        assertTrue(djMustWaitForFillBeforeAdvance(0))
    }

    @Test
    fun threeSongQueue_shouldTopUpAfterSkip_inBackground() {
        assertTrue(djShouldTopUpQueue(3))
        assertTrue(djShouldTopUpQueue(2))
        assertTrue(djShouldTopUpQueue(0))
        assertFalse(djShouldTopUpQueue(4))
        assertFalse(djShouldTopUpQueue(8))
    }
}
