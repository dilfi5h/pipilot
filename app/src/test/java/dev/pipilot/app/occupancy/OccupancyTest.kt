package dev.pipilot.app.occupancy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OccupancyTest {
    private val tui = SessionOccupancy(
        pid = 68938,
        sessionFile = "/Users/dilfish/.pi/agent/sessions/--proj--/a.jsonl",
        inbox = "/tmp/pipilot-501/pipilot/68938.sock",
        mode = "tui",
    )
    private val rpc = SessionOccupancy(
        pid = 100,
        sessionFile = "/Users/dilfish/.pi/agent/sessions/--proj--/a.jsonl",
        inbox = "/tmp/pipilot-501/pipilot/100.sock",
        mode = "rpc",
    )
    private val other = SessionOccupancy(
        pid = 2,
        sessionFile = "/Users/dilfish/.pi/agent/sessions/--proj--/b.jsonl",
        inbox = "/tmp/pipilot-501/pipilot/2.sock",
        mode = "tui",
    )

    @Test
    fun parseDumpSkipsNoiseAndReadsRecords() {
        val raw = """
            not json
            {"pid":68938,"sessionFile":"/x/a.jsonl","inbox":"/tmp/a.sock","mode":"tui"}
            {"pid":"nope"}
            {"pid":7,"sessionFile":null,"inbox":null,"mode":"rpc"}
        """.trimIndent()
        val recs = Occupancy.parseDump(raw)
        assertEquals(2, recs.size)
        assertEquals(68938, recs[0].pid)
        assertEquals("tui", recs[0].mode)
        assertEquals("/tmp/a.sock", recs[0].inbox)
        assertEquals(7, recs[1].pid)
        assertNull(recs[1].sessionFile)
    }

    @Test
    fun tuiOccupancyPreferredOverRpcOnSameFile() {
        val recs = listOf(rpc, tui, other)
        val hit = Occupancy.tuiOccupancyFor("/Users/dilfish/.pi/agent/sessions/--proj--/a.jsonl", recs)
        assertEquals(68938, hit?.pid)
        assertTrue(Occupancy.isTuiOccupied("/Users/dilfish/.pi/agent/sessions/--proj--/a.jsonl", recs))
        assertFalse(Occupancy.isTuiOccupied("/Users/dilfish/.pi/agent/sessions/--proj--/missing.jsonl", recs))
        assertEquals("Occupied · TUI pid 68938", Occupancy.statusLabel("/Users/dilfish/.pi/agent/sessions/--proj--/a.jsonl", recs))
        assertEquals("Idle", Occupancy.statusLabel("/Users/dilfish/.pi/agent/sessions/--proj--/c.jsonl", recs))
    }

    @Test
    fun inboxSteerCommandQuotesPathAndPayload() {
        val cmd = Occupancy.inboxSteerCommand("/tmp/pipilot-501/pipilot/1.sock", "hello 'world'")
        assertTrue(cmd.startsWith("python3 -c "))
        assertTrue(cmd.contains("'/tmp/pipilot-501/pipilot/1.sock'"))
        assertTrue(cmd.contains("source"))
        assertFalse(cmd.contains("hello 'world'"))
    }

    @Test
    fun takeoverCommandSendsQuitAndReadsReply() {
        val cmd = Occupancy.takeoverCommand("/tmp/pipilot-501/pipilot/1.sock")
        assertTrue(cmd.startsWith("python3 -c "))
        assertTrue(cmd.contains("'/tmp/pipilot-501/pipilot/1.sock'"))
        assertTrue(cmd.contains("\"command\""))
        assertTrue(cmd.contains("quit"))
        // The bridge replies on the connection; the client must read it back.
        assertTrue(cmd.contains("readline"))
        // No half-close: the bridge writes the reply on the still-open socket
        // (half-close + allowHalfOpen reply is unreliable on some runtimes).
        assertFalse(cmd.contains("SHUT_WR"))
    }

    @Test
    fun parseTakeoverReplyMapsReplies() {
        assertEquals(Occupancy.TakeoverReply.QUIT_NOW, Occupancy.parseTakeoverReply("OK:quit\n"))
        assertEquals(Occupancy.TakeoverReply.QUEUED, Occupancy.parseTakeoverReply("noise\nOK:queued\n"))
        assertEquals(Occupancy.TakeoverReply.FAILED, Occupancy.parseTakeoverReply(""))
        assertEquals(Occupancy.TakeoverReply.FAILED, Occupancy.parseTakeoverReply("ERR:no-session\n"))
        // A bare OK (old steer-style ack) must not count as a quit.
        assertEquals(Occupancy.TakeoverReply.FAILED, Occupancy.parseTakeoverReply("OK\n"))
    }

    @Test
    fun handbackScreenNameDerivesFromSessionFile() {
        assertEquals(
            "pipilot-back-a1b2c3d4e5f6",
            Occupancy.handbackScreenName("/root/.pi/agent/sessions/abc/a1b2c3d4e5f6.jsonl"),
        )
        // No session id -> fallback, still a valid screen name
        assertEquals("pipilot-back-x", Occupancy.handbackScreenName("/tmp/.jsonl"))
    }

    @Test
    fun handbackCommandSpawnsDetachedScreen() {
        val cmd = Occupancy.handbackCommand("/root/.pi/agent/sessions/abc/a1b2c3d4e5f6.jsonl")
        assertTrue(cmd.contains("screen -dmS 'pipilot-back-a1b2c3d4e5f6'"))
        assertTrue(cmd.contains("pi --session '/root/.pi/agent/sessions/abc/a1b2c3d4e5f6.jsonl'"))
        // Replaces a stale screen with the same name
        assertTrue(cmd.contains("screen -S 'pipilot-back-a1b2c3d4e5f6' -X quit"))
        // Self-destructs when pi exits so takeover leaves no stale screen
        assertTrue(cmd.contains("screen -X quit"))
    }

    @Test
    fun killHandbackScreenCommandTargetsOnlyOurs() {
        val cmd = Occupancy.killHandbackScreenCommand("pipilot-back-a1b2c3d4e5f6")
        assertTrue(cmd.contains("screen -S 'pipilot-back-a1b2c3d4e5f6' -X quit"))
    }
}
