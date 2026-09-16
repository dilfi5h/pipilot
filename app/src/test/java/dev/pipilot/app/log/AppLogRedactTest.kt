package dev.pipilot.app.log

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogRedactTest {
    @Test
    fun redactKeepsTypeAndIdDropsPromptBody() {
        val line = """{"id":"app-1","type":"prompt","message":"super-secret-token","password":"hunter2"}"""
        val out = AppLog.redactCommand(line)
        assertTrue(out.contains("type=prompt"))
        assertTrue(out.contains("id=app-1"))
        assertFalse(out.contains("super-secret-token"))
        assertFalse(out.contains("hunter2"))
    }

    @Test
    fun redactFallsBackOnPlainText() {
        val out = AppLog.redactCommand("not json at all")
        assertTrue(out.contains("not json"))
    }
}
