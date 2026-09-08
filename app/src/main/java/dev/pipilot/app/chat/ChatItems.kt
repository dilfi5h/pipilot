package dev.pipilot.app.chat

import androidx.compose.runtime.Immutable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 东八区时间戳格式(HH:mm),每条对话气泡下显示。*/
fun formatShanghai(millis: Long): String {
    val f = SimpleDateFormat("HH:mm", Locale.getDefault())
    f.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    return f.format(Date(millis))
}

/** 聊天列表里的一行。把消息和工具执行统一成可渲染的条目。*/
@Immutable
sealed interface ChatItem {
    val key: String
    /** 东八区显示时间(毫秒);0 表示不显示。*/
    val timeMs: Long get() = 0

    @Immutable
    data class UserText(
        val text: String,
        override val key: String,
        override val timeMs: Long = System.currentTimeMillis(),
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
