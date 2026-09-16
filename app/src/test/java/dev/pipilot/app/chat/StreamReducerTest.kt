package dev.pipilot.app.chat

import dev.pipilot.app.rpc.PiEvent
import dev.pipilot.app.rpc.PiLine
import dev.pipilot.app.rpc.parseLine
import dev.pipilot.app.rpc.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
