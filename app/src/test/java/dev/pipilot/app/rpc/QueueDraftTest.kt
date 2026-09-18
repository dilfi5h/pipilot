package dev.pipilot.app.rpc

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueDraftTest {
    @Test
    fun joinQueueDraftPutsSteeringBeforeFollowUp() {
        assertEquals(
            "fix Auth\n\nrun lint when done",
            joinQueueDraft(listOf("fix Auth"), listOf("run lint when done")),
        )
    }

    @Test
    fun joinQueueDraftDropsBlanks() {
        assertEquals("keep", joinQueueDraft(listOf("  ", "keep"), listOf("")))
        assertEquals("", joinQueueDraft(emptyList(), emptyList()))
    }

    @Test
    fun steerAndFollowUpCommandsCarryIdMessageAndImages() {
        val steer = PiCommands.steer("stop", images = listOf("abc" to "image/jpeg"), id = "s1")
        val follow = PiCommands.followUp("later", id = "f1")
        val steerObj = piJson.parseToJsonElement(steer).jsonObject
        val followObj = piJson.parseToJsonElement(follow).jsonObject
        assertEquals("steer", steerObj.str("type"))
        assertEquals("s1", steerObj.str("id"))
        assertEquals("stop", steerObj.str("message"))
        assertEquals(1, (steerObj["images"] as kotlinx.serialization.json.JsonArray).size)
        assertEquals("follow_up", followObj.str("type"))
        assertEquals("f1", followObj.str("id"))
        assertEquals("later", followObj.str("message"))
        assertTrue(!followObj.containsKey("images"))
    }

    @Test
    fun stringListReadsClearQueuePayload() {
        val obj = piJson.parseToJsonElement(
            """{"steering":["a","b"],"followUp":["c"],"other":1}""",
        ).jsonObject
        assertEquals(listOf("a", "b"), obj.stringList("steering"))
        assertEquals(listOf("c"), obj.stringList("followUp"))
        assertEquals(emptyList<String>(), obj.stringList("missing"))
    }

    @Test
    fun queueUpdateEventExposesBothQueues() {
        val line = parseLine("""{"type":"queue_update","steering":["steer"],"followUp":["queue"]}""")
        val ev = PiEvent(type = "queue_update", raw = (line as PiLine.Event).value)
        assertEquals(listOf("steer"), ev.steeringQueue)
        assertEquals(listOf("queue"), ev.followUpQueue)
    }
}
