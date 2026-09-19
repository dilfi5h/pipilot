package dev.pipilot.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatCopyTextTest {
    @Test
    fun userAndAssistantCopyBodyOnly() {
        assertEquals("hello", ChatItem.UserText(text = "hello", key = "u").copyText())
        assertNull(ChatItem.UserText(text = "  ", key = "u2").copyText())
        assertEquals(
            "reply",
            ChatItem.AssistantText(text = "reply", thinking = "plan", streaming = false, key = "a").copyText(),
        )
        assertNull(
            ChatItem.AssistantText(text = "", thinking = "plan", streaming = true, key = "live").copyText(),
        )
    }

    @Test
    fun toolAndBashIncludeCommandAndOutput() {
        assertEquals(
            "bash\nls -la\nfile.txt",
            ChatItem.ToolCard(
                toolCallId = "1",
                toolName = "bash",
                argsSummary = "ls -la",
                output = "file.txt",
                running = false,
                isError = false,
            ).copyText(),
        )
        assertEquals(
            "$ echo hi\nhi",
            ChatItem.BashOutput(command = "echo hi", output = "hi", running = false).copyText(),
        )
    }
}
