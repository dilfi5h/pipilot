package dev.pipilot.app.ui

import dev.pipilot.app.chat.ChatItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A reconnect must not disturb what the user is reading: settled history stays on screen and
 * the rebuild must not yank the list back to the bottom.
 */
class ReconnectUiTest {

    private fun settledText(key: String) = ChatItem.AssistantText(
        text = "done", thinking = null, streaming = false, key = key,
    )
    private fun liveText() = ChatItem.AssistantText(
        text = "partial", thinking = null, streaming = true, key = "live",
    )
    private fun doneTool(key: String) = ChatItem.ToolCard(
        toolCallId = key, toolName = "read", argsSummary = "/a", output = "ok",
        running = false, isError = false,
    )
    private fun runningTool(key: String) = ChatItem.ToolCard(
        toolCallId = key, toolName = "bash", argsSummary = "ls", output = null,
        running = true, isError = false,
    )
    private fun runningBash() = ChatItem.BashOutput(command = "ls", output = "par", running = true)
    private fun doneBash() = ChatItem.BashOutput(command = "ls", output = "a", running = false)

    @Test
    fun reconnectKeepsSettledRowsAndDropsInFlightOnes() {
        val items = listOf(
            settledText("a"), liveText(), doneTool("t1"), runningTool("t2"), doneBash(), runningBash(),
        )
        val kept = withoutInFlight(items)
        assertEquals(listOf("a", "tool-t1", "bash-direct"), kept.map { it.key })
    }

    @Test
    fun withoutInFlightPreservesOrderOfKeptRows() {
        val items = listOf(settledText("a"), doneTool("t1"), settledText("b"), doneBash())
        assertEquals(listOf("a", "tool-t1", "b", "bash-direct"), withoutInFlight(items).map { it.key })
    }

    @Test
    fun withoutInFlightOnEmptyAndAllRunning() {
        assertEquals(emptyList<ChatItem>(), withoutInFlight(emptyList()))
        assertEquals(
            emptyList<ChatItem>(),
            withoutInFlight(listOf(liveText(), runningTool("t"), runningBash())),
        )
    }

    @Test
    fun fullRebuildWithPinBumpsEpoch() {
        assertEquals(4L, nextHistoryEpoch(current = 3L, fullRebuild = true, pinToBottom = true))
    }

    @Test
    fun reconnectRebuildDoesNotBumpEpoch() {
        // 重连：全量重建但不动滚动位置
        assertEquals(3L, nextHistoryEpoch(current = 3L, fullRebuild = true, pinToBottom = false))
    }

    @Test
    fun incrementalSyncNeverBumpsEpoch() {
        assertEquals(3L, nextHistoryEpoch(current = 3L, fullRebuild = false, pinToBottom = true))
        assertEquals(3L, nextHistoryEpoch(current = 3L, fullRebuild = false, pinToBottom = false))
    }
}
