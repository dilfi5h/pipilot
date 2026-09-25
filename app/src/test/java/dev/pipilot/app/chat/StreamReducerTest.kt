package dev.pipilot.app.chat

import dev.pipilot.app.rpc.PiEvent
import dev.pipilot.app.rpc.PiLine
import dev.pipilot.app.rpc.parseLine
import dev.pipilot.app.rpc.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamReducerTest {
    private fun ev(json: String): PiEvent {
        return when (val line = parseLine(json)) {
            is PiLine.Event -> PiEvent(type = line.value.str("type") ?: "unknown", raw = line.value)
            is PiLine.ExtensionUiRequest -> PiEvent(type = "extension_ui_request", raw = line.value)
            else -> error("not an event: $json")
        }
    }

    private fun collect(vararg json: String): List<ChatItem> {
        val reducer = StreamReducer()
        val out = ArrayList<ChatItem>()
        for (line in json) reducer.onEvent(ev(line)) { out += it }
        return out
    }

    @Test
    fun agentStartPlantsLiveCursorThenTextEndOverwrites() {
        val items = collect(
            """{"type":"agent_start"}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"Hel"}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"lo"}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"text_end","content":"Hello"}}""",
        )
        val lives = items.filterIsInstance<ChatItem.AssistantText>().filter { it.key == "live" }
        assertEquals("", lives.first().text)
        assertEquals("Hello", lives.last().text)
        assertTrue(lives.last().streaming)
    }

    @Test
    fun messageEndRemovesLiveAndEmitsFinal() {
        val items = collect(
            """{"type":"agent_start"}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"Hi"}}""",
            """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"Hi"}],"timestamp":1}}""",
        )
        assertTrue(items.any { it is StreamReducer.RemoveLive })
        val finals = items.filterIsInstance<ChatItem.AssistantText>().filter { it.key != "live" }
        assertEquals(1, finals.size)
        assertEquals("Hi", finals[0].text)
        assertFalse(finals[0].streaming)
    }

    @Test
    fun toolCallEndRetainsFullWriteArguments() {
        val items = collect(
            """{"type":"message_update","assistantMessageEvent":{"type":"toolcall_start","id":"call-write","toolName":"write"}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"toolcall_end","id":"call-write","toolCall":{"id":"call-write","name":"write","arguments":{"path":"/tmp/a.txt","content":"hello"}}}}""",
        )
        val card = items.filterIsInstance<ChatItem.ToolCard>().last()
        assertEquals("/tmp/a.txt", card.argsSummary)
        assertEquals("hello", ChatCollapse.writeContent(card.argsJson))
    }

    @Test
    fun streamedToolArgumentsSurviveWithoutToolCallEndObject() {
        val items = collect(
            """{"type":"message_update","assistantMessageEvent":{"type":"toolcall_start","id":"call-edit","toolName":"edit"}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"toolcall_delta","id":"call-edit","delta":"{\"edits\":[{"}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"toolcall_delta","id":"call-edit","delta":"\"oldText\":\"before\",\"newText\":\"after\"}]}"}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"toolcall_end","id":"call-edit"}}""",
        )
        val card = items.filterIsInstance<ChatItem.ToolCard>().last()
        assertEquals(listOf("before" to "after"), ChatCollapse.editChanges(card.argsJson))
    }

    @Test
    fun bashUpdatesUseCommandIdAsKey() {
        val items = collect(
            """{"type":"bash_execution_update","id":"req-1","delta":"a"}""",
            """{"type":"bash_execution_update","id":"req-2","delta":"b"}""",
        )
        val bash = items.filterIsInstance<ChatItem.BashOutput>()
        assertEquals(listOf("bash-req-1", "bash-req-2"), bash.map { it.key })
    }

    @Test
    fun userMessageEndDoesNotClearLive() {
        val items = collect(
            """{"type":"agent_start"}""",
            """{"type":"message_end","message":{"role":"user","content":"hi"}}""",
        )
        assertFalse(items.any { it is StreamReducer.RemoveLive })
        assertTrue(items.any { it is ChatItem.AssistantText && it.key == "live" })
    }

    private class FakeClock(var t: Long) {
        fun now(): Long = t
    }

    private fun collectTimed(clock: FakeClock, vararg steps: Pair<Long, String>): List<ChatItem> {
        val reducer = StreamReducer(nowMs = clock::now)
        val out = ArrayList<ChatItem>()
        for ((t, line) in steps) {
            clock.t = t
            reducer.onEvent(ev(line)) { out += it }
        }
        return out
    }

    @Test
    fun agentStartShowsWaitingThenTtftOnFirstDelta() {
        val clock = FakeClock(0)
        val items = collectTimed(
            clock,
            1000L to """{"type":"agent_start"}""",
            2200L to """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"Hel"}}""",
        )
        val lives = items.filterIsInstance<ChatItem.AssistantText>().filter { it.key == "live" }
        assertEquals(true, lives.first().speed?.waiting)
        val after = lives.last().speed!!
        assertEquals(1200L, after.ttftMs)
        // One 3-char delta is ~1 token with dt=0, so no rate yet (and thus not marked estimated).
        assertNull(after.toksPerSec)
        assertFalse(after.estimated)
        assertFalse(after.waiting)
    }

    @Test
    fun thinkingDeltaCountsAsFirstToken() {
        val clock = FakeClock(0)
        val items = collectTimed(
            clock,
            0L to """{"type":"agent_start"}""",
            800L to """{"type":"message_update","assistantMessageEvent":{"type":"thinking_delta","delta":"hmm"}}""",
            1800L to """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"Hi"}}""",
        )
        val live = items.filterIsInstance<ChatItem.AssistantText>().last { it.key == "live" }
        assertEquals(800L, live.speed?.ttftMs)
    }

    @Test
    fun messageEndUsesProviderOutputTokensAndDropsEstimate() {
        val clock = FakeClock(0)
        val items = collectTimed(
            clock,
            0L to """{"type":"agent_start"}""",
            1000L to """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"Hi"}}""",
            2000L to """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"Hi there world"}],"usage":{"output":21},"timestamp":1}}""",
        )
        val finals = items.filterIsInstance<ChatItem.AssistantText>().filter { it.key != "live" }
        assertEquals(1, finals.size)
        val speed = finals[0].speed!!
        assertEquals(1000L, speed.ttftMs)
        assertEquals(20.0, speed.toksPerSec!!, 0.01)
        assertFalse(speed.estimated)
        assertFalse(speed.waiting)
    }

    @Test
    fun firstAssistantMessageStartDoesNotResetTtftWindow() {
        val clock = FakeClock(0)
        val items = collectTimed(
            clock,
            0L to """{"type":"agent_start"}""",
            50L to """{"type":"message_start","message":{"role":"assistant","content":[]}}""",
            1200L to """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"Hi"}}""",
        )
        val live = items.filterIsInstance<ChatItem.AssistantText>().last { it.key == "live" }
        assertEquals(1200L, live.speed?.ttftMs)
    }

    @Test
    fun liveEstimateUsesCharCountAfterSecondDelta() {
        val clock = FakeClock(0)
        val items = collectTimed(
            clock,
            0L to """{"type":"agent_start"}""",
            1000L to """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"Hello world, this is a longer chunk."}}""",
            2000L to """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":" more"}}""",
        )
        val live = items.filterIsInstance<ChatItem.AssistantText>().last { it.key == "live" }
        val speed = live.speed!!
        assertEquals(1000L, speed.ttftMs)
        assertTrue(speed.estimated)
        assertTrue((speed.toksPerSec ?: 0.0) > 0.0)
    }

    @Test
    fun continuationAfterToolsOmitsTtft() {
        val clock = FakeClock(0)
        val items = collectTimed(
            clock,
            0L to """{"type":"agent_start"}""",
            500L to """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"ok"}}""",
            1500L to """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"ok"}],"usage":{"output":5},"timestamp":1}}""",
            4000L to """{"type":"message_start","message":{"role":"assistant","content":[]}}""",
            4300L to """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"more"}}""",
            5300L to """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"more text here"}],"usage":{"output":11},"timestamp":2}}""",
        )
        val finals = items.filterIsInstance<ChatItem.AssistantText>().filter { it.key != "live" }
        assertEquals(2, finals.size)
        assertEquals(500L, finals[0].speed?.ttftMs)
        assertNull(finals[1].speed?.ttftMs)
        assertEquals(10.0, finals[1].speed?.toksPerSec!!, 0.01)
    }
}
