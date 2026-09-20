package dev.pipilot.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCollapseTest {
    @Test
    fun defaultFollowsActiveFlag() {
        assertTrue(ChatCollapse.isExpanded(active = true, userExpanded = null))
        assertFalse(ChatCollapse.isExpanded(active = false, userExpanded = null))
    }

    @Test
    fun userOverrideWins() {
        assertFalse(ChatCollapse.isExpanded(active = true, userExpanded = false))
        assertTrue(ChatCollapse.isExpanded(active = false, userExpanded = true))
    }

    @Test
    fun thinkingPreviewUsesFirstNonBlankLine() {
        assertEquals(
            "plan the change",
            ChatCollapse.thinkingPreview("\n\nplan the change\nmore detail"),
        )
        assertEquals("", ChatCollapse.thinkingPreview("   \n\t"))
    }

    @Test
    fun outputPreviewUsesLastNonBlankLine() {
        assertEquals(
            "error: missing file",
            ChatCollapse.outputPreview("step 1\nstep 2\n\nerror: missing file\n"),
        )
    }

    @Test
    fun clipPreviewTruncatesFromTheUsefulEnd() {
        val longHead = "a".repeat(ChatCollapse.PREVIEW_MAX_CHARS + 40)
        val head = ChatCollapse.clipPreview(longHead, fromEnd = false)
        assertTrue(head.endsWith("…"))
        assertEquals(ChatCollapse.PREVIEW_MAX_CHARS, head.length)

        val longTail = "b".repeat(ChatCollapse.PREVIEW_MAX_CHARS + 40)
        val tail = ChatCollapse.clipPreview(longTail, fromEnd = true)
        assertTrue(tail.startsWith("…"))
        assertEquals(ChatCollapse.PREVIEW_MAX_CHARS, tail.length)
        assertTrue(tail.endsWith("b"))
    }

    @Test
    fun expandedBodyPassesShortTextThrough() {
        assertEquals("hello", ChatCollapse.expandedBody("hello", fromEnd = true))
    }

    @Test
    fun toolFamilyMapsBuiltinsAndGeneric() {
        assertEquals(ToolFamily.Bash, ChatCollapse.toolFamily("bash"))
        assertEquals(ToolFamily.Read, ChatCollapse.toolFamily("Read"))
        assertEquals(ToolFamily.Write, ChatCollapse.toolFamily("WRITE"))
        assertEquals(ToolFamily.Edit, ChatCollapse.toolFamily(" edit "))
        assertEquals(ToolFamily.Generic, ChatCollapse.toolFamily("grep"))
        assertEquals(ToolFamily.Generic, ChatCollapse.toolFamily(""))
    }

    @Test
    fun summarizeArgsPrefersCommandThenPath() {
        assertEquals("ls -la", ChatCollapse.summarizeArgs("""{"command":"ls -la","timeout":30}"""))
        assertEquals("/root/README.md", ChatCollapse.summarizeArgs("""{"path":"/root/README.md"}"""))
        assertEquals("/a.kt", ChatCollapse.summarizeArgs("""{"file_path":"/a.kt"}"""))
        assertEquals("plain", ChatCollapse.summarizeArgs("plain"))
        assertEquals(null, ChatCollapse.summarizeArgs("  "))
    }

    @Test
    fun markdownPathLooksAtExtensionOnly() {
        assertTrue(ChatCollapse.isMarkdownPath("/root/docs/README.md"))
        assertTrue(ChatCollapse.isMarkdownPath("notes.markdown"))
        assertTrue(ChatCollapse.isMarkdownPath("foo.mdx"))
        assertFalse(ChatCollapse.isMarkdownPath("/root/app.kt"))
        assertFalse(ChatCollapse.isMarkdownPath("md"))
        assertFalse(ChatCollapse.isMarkdownPath(""))
    }

    @Test
    fun expandedBodyCapsLongOutputFromTheEnd() {
        val src = "head\n" + "x".repeat(ChatCollapse.EXPANDED_MAX_CHARS)
        val body = ChatCollapse.expandedBody(src, fromEnd = true)
        assertTrue(body.startsWith("…(truncated "))
        assertTrue(body.endsWith("x"))
        assertTrue(body.length <= ChatCollapse.EXPANDED_MAX_CHARS + 40)
    }
}
