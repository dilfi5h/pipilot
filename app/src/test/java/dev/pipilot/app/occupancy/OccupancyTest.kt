package dev.pipilot.app.occupancy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        // Bounded wait: an old bridge that never replies must not hang the app.
        assertTrue(cmd.contains("settimeout"))
    }

    @Test
    fun takeoverCommandQuotesInboxPathWithQuote() {
        // A quote in the path must be escaped, not passed through to the shell.
        val cmd = Occupancy.takeoverCommand("/tmp/it's here/1.sock")
        assertTrue(cmd, cmd.contains("'/tmp/it'\\''s here/1.sock'"))
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
        val name = Occupancy.handbackScreenName(
            "/root/.pi/agent/sessions/abc/a1b2c3d4e5f6.jsonl",
        )
        assertTrue(name, name.startsWith("pipilot-back-a1b2c3d4e5f6"))
        // No session id -> fallback prefix, still a valid screen name
        assertTrue(
            Occupancy.handbackScreenName("/tmp/.jsonl").startsWith("pipilot-back-x"),
        )
    }

    @Test
    fun handbackScreenNameIsDeterministic() {
        val f = "/root/.pi/agent/sessions/abc/a1b2c3d4e5f6.jsonl"
        assertEquals(Occupancy.handbackScreenName(f), Occupancy.handbackScreenName(f))
    }

    /**
     * The readable prefix is capped at 12 chars, so sessions whose ids share a
     * prefix must still get distinct screen names — otherwise handback's
     * "kill any stale screen with this name" would kill the *other* session's
     * TUI. These two collide on the prefix alone.
     */
    @Test
    fun handbackScreenNameDistinguishesSessionsSharingAPrefix() {
        val a = "/x/2026-10-01T21-33-40-abc.jsonl"
        val b = "/x/2026-10-01T21-33-59-xyz.jsonl"
        assertEquals("pipilot-back-20261001T213", Occupancy.handbackScreenName(a).take(25))
        assertEquals("pipilot-back-20261001T213", Occupancy.handbackScreenName(b).take(25))
        assertNotEquals(Occupancy.handbackScreenName(a), Occupancy.handbackScreenName(b))

        // Same for ids that differ only past the 12-char cut.
        val c = "/x/aabbccddeeff00112233.jsonl"
        val d = "/x/aabbccddeeff00112244.jsonl"
        assertNotEquals(Occupancy.handbackScreenName(c), Occupancy.handbackScreenName(d))
    }

    @Test
    fun handbackScreenNameStaysShellSafe() {
        // Quotes/metacharacters in the path must not reach the screen name,
        // which is interpolated into a shell command.
        val name = Occupancy.handbackScreenName("/x/sess'ion; rm -rf /.jsonl")
        assertFalse(name, name.contains("'"))
        assertFalse(name, name.contains(";"))
        assertFalse(name, name.contains(" "))
    }

    @Test
    fun handbackCommandSpawnsDetachedScreen() {
        val session = "/root/.pi/agent/sessions/abc/a1b2c3d4e5f6.jsonl"
        val name = Occupancy.shellQuote(Occupancy.handbackScreenName(session))
        val cmd = Occupancy.handbackCommand(session)
        assertTrue(cmd, cmd.contains("screen -dmS $name"))
        // The pi invocation is wrapped in `bash -c '...'`, so the session path
        // is shellQuote'd twice: the inner quotes are re-escaped as '\'' for
        // the outer layer. Assert the escaped form, which is what actually
        // reaches the shell.
        val escapedSession = "pi --session '\\''" + session + "'\\''"
        assertTrue(cmd, cmd.contains(escapedSession))
        // Replaces a stale screen with the same name
        assertTrue(cmd, cmd.contains("screen -S $name -X quit"))
        // Self-destructs when pi exits so takeover leaves no stale screen
        assertTrue(cmd, cmd.contains("screen -X quit"))
    }

    @Test
    fun killHandbackScreenCommandTargetsOnlyOurs() {
        val name = Occupancy.handbackScreenName("/x/a1b2c3d4e5f6.jsonl")
        val cmd = Occupancy.killHandbackScreenCommand(name)
        assertTrue(cmd, cmd.contains("screen -S '${name}' -X quit"))
    }
}
