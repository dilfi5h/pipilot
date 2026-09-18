package dev.pipilot.app.chat

import androidx.compose.runtime.Immutable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Timestamp under each chat bubble, formatted as HH:mm:ss in Asia/Shanghai. */
fun formatShanghai(millis: Long): String {
    val f = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    f.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    return f.format(Date(millis))
}

/** One row in the chat list. Messages and tool runs are unified into renderable items. */
@Immutable
sealed interface ChatItem {
    val key: String
    /** Display time in Asia/Shanghai (ms); 0 means hidden. */
    val timeMs: Long get() = 0

    @Immutable
    data class UserText(
        val text: String,
        override val key: String,
        override val timeMs: Long = System.currentTimeMillis(),
        val imageCount: Int = 0,
    ) : ChatItem

    @Immutable
    data class AssistantText(
        val text: String,
        val thinking: String?,
        val streaming: Boolean,
        override val key: String,
        override val timeMs: Long = 0,
    ) : ChatItem

    @Immutable
    data class ToolCard(
        val toolCallId: String,
        val toolName: String,
        val argsSummary: String?,
        val output: String?,
        val running: Boolean,
        val isError: Boolean,
        override val key: String = "tool-$toolCallId",
    ) : ChatItem

    @Immutable
    data class BashOutput(
        val command: String?,
        val output: String,
        val running: Boolean,
        override val key: String = "bash-direct",
    ) : ChatItem

    @Immutable
    data class SystemNote(val text: String, override val key: String) : ChatItem
}
