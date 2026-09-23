package dev.pipilot.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationSpeedTest {
    @Test
    fun waitingUntilFirstTokenWhileStreaming() {
        val s = computeGenerationSpeed(
            startMs = 0,
            firstMs = null,
            lastMs = null,
            outputTokens = null,
            firstOfTurn = true,
            estimated = true,
            streaming = true,
        )
        assertEquals(GenerationSpeed(waiting = true), s)
        assertEquals("waiting…", formatGenerationSpeed(s))
    }

    @Test
    fun finishedWithoutTokensHasNoFootnote() {
        assertNull(
            computeGenerationSpeed(
                startMs = 0,
                firstMs = null,
                lastMs = null,
                outputTokens = 10,
                firstOfTurn = true,
                estimated = false,
                streaming = false,
            ),
        )
        assertEquals("", formatGenerationSpeed(null))
    }

    @Test
    fun firstOfTurnRecordsTtftAndDecodeRate() {
        val s = computeGenerationSpeed(
            startMs = 1000,
            firstMs = 2200,
            lastMs = 3200,
            outputTokens = 21,
            firstOfTurn = true,
            estimated = false,
            streaming = false,
        )!!
        assertEquals(1200L, s.ttftMs)
        assertEquals(20.0, s.toksPerSec!!, 0.01)
        assertFalse(s.estimated)
        assertEquals("TTFT 1.20s · 20.0 tok/s", formatGenerationSpeed(s))
    }

    @Test
    fun continuationOmitsTtft() {
        val s = computeGenerationSpeed(
            startMs = 5000,
            firstMs = 5300,
            lastMs = 6300,
            outputTokens = 11,
            firstOfTurn = false,
            estimated = false,
            streaming = false,
        )!!
        assertNull(s.ttftMs)
        assertEquals(10.0, s.toksPerSec!!, 0.01)
        assertEquals("10.0 tok/s", formatGenerationSpeed(s))
    }

    @Test
    fun estimatedRateGetsTilde() {
        val s = computeGenerationSpeed(
            startMs = 0,
            firstMs = 1000,
            lastMs = 2000,
            outputTokens = 9,
            firstOfTurn = true,
            estimated = true,
            streaming = true,
        )!!
        assertTrue(s.estimated)
        assertEquals("TTFT 1.00s · ~8.0 tok/s", formatGenerationSpeed(s))
    }

    @Test
    fun singleDeltaHasNoRate() {
        val s = computeGenerationSpeed(
            startMs = 0,
            firstMs = 1500,
            lastMs = 1500,
            outputTokens = 40,
            firstOfTurn = true,
            estimated = false,
            streaming = false,
        )!!
        assertEquals(1500L, s.ttftMs)
        assertNull(s.toksPerSec)
        assertEquals("TTFT 1.50s · —", formatGenerationSpeed(s))
        assertEquals("TTFT 1.50s", formatGenerationSpeed(s, streaming = true))
    }

    @Test
    fun durationAndRateFormatting() {
        assertEquals("420ms", formatDuration(420))
        assertEquals("1.18s", formatDuration(1180))
        assertEquals("10.5s", formatDuration(10_500))
        assertEquals("9.9", formatToksPerSec(9.9))
        assertEquals("24.3", formatToksPerSec(24.3))
        assertEquals("100", formatToksPerSec(100.4))
    }
}
