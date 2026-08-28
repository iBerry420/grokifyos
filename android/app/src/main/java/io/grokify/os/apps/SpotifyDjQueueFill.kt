package io.grokify.os.apps

/**
 * Live DJ refill policy.
 *
 * Skip / handoff must play the next already-queued cut immediately. Pool crawl +
 * AI rank can take tens of seconds — that work belongs **after** the transition
 * lock is released, never inside it.
 */
const val DJ_QUEUE_TOP_UP_BELOW = 4

/** Only block a skip/handoff when there is nothing to play. */
fun djMustWaitForFillBeforeAdvance(upcomingCount: Int): Boolean = upcomingCount <= 0

/** After a handoff, top up in the background so a 3-song list does not stall Skip. */
fun djShouldTopUpQueue(upcomingCount: Int): Boolean = upcomingCount < DJ_QUEUE_TOP_UP_BELOW
