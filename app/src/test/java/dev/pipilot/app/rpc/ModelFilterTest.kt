package dev.pipilot.app.rpc

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelFilterTest {
    private val models = listOf(
        PiModel("gpt-4.1", "GPT-4.1", "openai", reasoning = false, contextWindow = 128_000),
        PiModel("gpt-4.1-20250514", "GPT-4.1 dated", "openai", reasoning = false, contextWindow = 128_000),
        PiModel("claude-sonnet-4", "Sonnet", "anthropic", reasoning = true, contextWindow = 200_000),
        PiModel("gemini-2.5-pro", "Gemini", "google", reasoning = true, contextWindow = 1_000_000),
    )

    @Test
    fun nullOrEmptyKeepsAll() {
        assertEquals(models, filterEnabledModels(models, null))
        assertEquals(models, filterEnabledModels(models, emptyList()))
    }

    @Test
    fun exactProviderSlashId() {
        val out = filterEnabledModels(models, listOf("anthropic/claude-sonnet-4"))
        assertEquals(listOf("claude-sonnet-4"), out.map { it.id })
    }

    @Test
    fun globAndThinkingSuffix() {
        val out = filterEnabledModels(models, listOf("openai/gpt-4.1*:medium", "google/*"))
        assertEquals(listOf("gpt-4.1", "gpt-4.1-20250514", "gemini-2.5-pro"), out.map { it.id })
    }

    @Test
    fun aliasPrefersUndatedId() {
        val out = filterEnabledModels(models, listOf("gpt-4.1"))
        assertEquals(listOf("gpt-4.1"), out.map { it.id })
    }
}
