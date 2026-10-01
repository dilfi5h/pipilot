package dev.pipilot.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Takeover waits longer when the quit was queued behind a running tool. */
class TakeoverTest {

    @Test
    fun takeoverTimeoutMsTwoTiers() {
        assertEquals(15_000, takeoverTimeoutMs(false))
        assertEquals(90_000, takeoverTimeoutMs(true))
    }

    /**
     * Regression: every takeover exit must clear tuiTakeover. A `return@launch`
     * that skipped the reset wedged the banner on "正在退出…" forever and hid
     * the 接管 button, leaving no way to retry after a mid-takeover disconnect.
     */
    @Test
    fun takeoverFailedClearsBothFlagsAndSetsError() {
        val busy = UiState(tuiTakeover = true, tuiTakeoverQueued = true)
        val out = busy.takeoverFailed("Takeover failed: 已断开连接")
        assertFalse(out.tuiTakeover)
        assertFalse(out.tuiTakeoverQueued)
        assertEquals("Takeover failed: 已断开连接", out.error)
    }

    @Test
    fun takeoverFailedClearsFlagsEvenWhenNotBusy() {
        // Defensive: a failure path reached with the flags already false must
        // not resurrect them, and must still surface the message.
        val out = UiState().takeoverFailed("Takeover timed out")
        assertFalse(out.tuiTakeover)
        assertFalse(out.tuiTakeoverQueued)
        assertEquals("Takeover timed out", out.error)
    }

    @Test
    fun takeoverFailedPreservesUnrelatedState() {
        val s = UiState(
            connected = true,
            items = listOf(),
            error = "stale",
            tuiTakeover = true,
        )
        val out = s.takeoverFailed("new")
        assertTrue(out.connected)
        assertEquals("new", out.error)
    }

    /**
     * The 接管 button is hidden while tuiTakeover is set, so a stuck flag is
     * not merely cosmetic — the user loses the ability to retry takeover.
     */
    @Test
    fun takeoverFailedMakesRetryPossible() {
        val wedged = UiState(tuiTakeover = true)
        assertFalse(wedged.takeoverFailed("x").tuiTakeover)
    }
}
