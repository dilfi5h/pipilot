package dev.pipilot.app.rpc

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParseLineTest {
    @Test
    fun classifiesResponseEventAndUiRequest() {
        assertTrue(parseLine("""{"type":"response","id":"1","success":true}""") is PiLine.Response)
        assertTrue(parseLine("""{"type":"agent_start"}""") is PiLine.Event)
        assertTrue(parseLine("""{"type":"extension_ui_request","id":"u1","method":"confirm"}""") is PiLine.ExtensionUiRequest)
    }

    @Test
    fun stripsUnknownKeysAndParsesPiResponse() {
        val line = parseLine("""{"type":"response","id":"a","command":"get_state","success":true,"error":null,"extra":1,"data":{"isStreaming":true}}""")
        val resp = PiResponse.from((line as PiLine.Response).value)
        assertEquals("a", resp.id)
        assertEquals("get_state", resp.command)
        assertTrue(resp.success)
        assertEquals(true, resp.data?.boolOrNull("isStreaming"))
    }

    @Test
    fun boolOrNullReadsJsonBooleans() {
        val obj = piJson.parseToJsonElement("""{"a":true,"b":false,"c":"x"}""").jsonObject
        assertEquals(true, obj.boolOrNull("a"))
        assertEquals(false, obj.boolOrNull("b"))
        assertEquals(null, obj.boolOrNull("c"))
        assertEquals(null, obj.boolOrNull("missing"))
    }
}
