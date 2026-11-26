package dev.pipilot.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStatsTest {

    @Test
    fun tokenCountUsesFooterThresholds() {
        assertEquals("0", formatTokenCount(0))
        assertEquals("999", formatTokenCount(999))
        assertEquals("1.0k", formatTokenCount(1_000))
        assertEquals("7.4k", formatTokenCount(7_415))
        assertEquals("34k", formatTokenCount(34_048))
        assertEquals("1.2M", formatTokenCount(1_234_567))
    }

    @Test
    fun cacheHitPercentUsesPromptTokensOnly() {
        // output is not part of the prompt denominator
        assertEquals(82.1, UsageStats(input = 7_415, cacheRead = 34_048, output = 3_129).cacheHitPercent!!, 0.05)
        assertEquals(50.0, UsageStats(input = 100, cacheRead = 100).cacheHitPercent!!, 0.001)
        // cacheWrite counts as uncached prompt tokens: 100/(100+100+100)
        assertEquals(33.333, UsageStats(input = 100, cacheRead = 100, cacheWrite = 100).cacheHitPercent!!, 0.01)
        assertNull(UsageStats().cacheHitPercent)
    }

    @Test
    fun usageLineMatchesPreviewFormat() {
        val line = formatUsageLine(UsageStats(input = 7_415, cacheRead = 34_048, output = 3_129))
        assertEquals("↑7.4k · R34k · ↓3.1k · CHT82.1%", line)
    }

    @Test
    fun usageLineHidesZeroCacheWriteAndCost() {
        val line = formatUsageLine(UsageStats(input = 4_035, cacheRead = 256, output = 580, cost = 0.0))
        assertEquals("↑4.0k · R256 · ↓580 · CHT6.0%", line)
    }

    @Test
    fun usageLineShowsCacheWriteAndCostWhenPresent() {
        val line = formatUsageLine(
            UsageStats(input = 1_000, cacheRead = 2_000, cacheWrite = 512, output = 300, cost = 0.42),
        )
        assertEquals("↑1.0k · R2.0k · W512 · ↓300 · CHT56.9% · \$0.420", line)
    }

    @Test
    fun isEmptyOnlyForFreshSession() {
        assertTrue(UsageStats().isEmpty)
        assertFalse(UsageStats(output = 1).isEmpty)
        assertFalse(UsageStats(cacheRead = 1).isEmpty)
    }
}
