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

/** Plain text for the per-item Copy button. System selection still copies a highlight. */
fun ChatItem.copyText(): String? = when (this) {
    is ChatItem.UserText -> text.takeIf { it.isNotBlank() }
    is ChatItem.AssistantText -> text.takeIf { it.isNotBlank() }
    is ChatItem.ToolCard -> buildString {
        append(toolName)
        argsSummary?.takeIf { it.isNotBlank() }?.let { append('\n').append(it) }
        output?.takeIf { it.isNotBlank() }?.let { append('\n').append(it) }
    }.takeIf { it.isNotBlank() }
    is ChatItem.BashOutput -> buildString {
        command?.takeIf { it.isNotBlank() }?.let { append("$ ").append(it).append('\n') }
        append(output)
    }.trim().takeIf { it.isNotEmpty() }
    is ChatItem.SystemNote -> text.takeIf { it.isNotBlank() }
    is StreamReducer.RemoveLive -> null
}
