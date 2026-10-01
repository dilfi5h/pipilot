package dev.pipilot.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Takeover waits longer when the quit was queued behind a running tool. */
class TakeoverTest {

    @Test
    fun takeoverTimeoutMsTwoTiers() {
        assertEquals(15_000, takeoverTimeoutMs(false))
        assertEquals(90_000, takeoverTimeoutMs(true))
    }
}
