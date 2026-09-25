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

/**
 * Per-assistant-bubble generation speed, timed on the client from RPC events.
 * `null` on the item means "don't show" (history / no timing). A non-null value
 * with [waiting] is the pre-token cursor footnote.
 */
@Immutable
data class GenerationSpeed(
    val ttftMs: Long? = null,
    val toksPerSec: Double? = null,
    val estimated: Boolean = false,
    val waiting: Boolean = false,
)

fun formatGenerationSpeed(speed: GenerationSpeed?, streaming: Boolean = false): String {
    if (speed == null) return ""
    if (speed.waiting) return "waiting…"
    val parts = ArrayList<String>(2)
    speed.ttftMs?.let { parts.add("TTFT ${formatDuration(it)}") }
    val rate = speed.toksPerSec
    if (rate != null && rate > 0.0) {
        val prefix = if (speed.estimated) "~" else ""
        parts.add("$prefix${formatToksPerSec(rate)} tok/s")
    } else if (speed.ttftMs != null && !streaming) {
        parts.add("—")
    }
    return parts.joinToString(" · ")
}

fun formatDuration(ms: Long): String {
    if (ms < 1000) return "${ms}ms"
    val s = ms / 1000.0
    return if (s >= 10.0) {
        String.format(Locale.US, "%.1fs", s)
    } else {
        String.format(Locale.US, "%.2fs", s)
    }
}

fun formatToksPerSec(n: Double): String =
    if (n >= 100.0) String.format(Locale.US, "%.0f", n)
    else String.format(Locale.US, "%.1f", n)

/**
 * Decode rate from client-side timestamps.
 * TTFT is only filled for the first LLM call of a turn.
 * toks/s uses (tokens - 1) / (last - first) so the wait for the first token is not counted twice.
 */
fun computeGenerationSpeed(
    startMs: Long,
    firstMs: Long?,
    lastMs: Long?,
    outputTokens: Long?,
    firstOfTurn: Boolean,
    estimated: Boolean,
    streaming: Boolean,
): GenerationSpeed? {
    if (firstMs == null) {
        return if (streaming) GenerationSpeed(waiting = true) else null
    }
    val ttft = if (firstOfTurn) (firstMs - startMs).coerceAtLeast(0) else null
    val last = lastMs ?: firstMs
    val dt = last - firstMs
    val toks = if (outputTokens != null && outputTokens >= 2 && dt > 0) {
        (outputTokens - 1).toDouble() / (dt / 1000.0)
    } else {
        null
    }
    return GenerationSpeed(
        ttftMs = ttft,
        toksPerSec = toks,
        estimated = estimated && toks != null,
        waiting = false,
    )
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
        val speed: GenerationSpeed? = null,
    ) : ChatItem

    @Immutable
    data class ToolCard(
        val toolCallId: String,
        val toolName: String,
        val argsSummary: String?,
        val output: String?,
        val running: Boolean,
        val isError: Boolean,
        /** Full tool argument JSON, retained for write/edit content and diffs. */
        val argsJson: String? = null,
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
        when (toolName.trim().lowercase()) {
            "write" -> ChatCollapse.writeContent(argsJson)?.let { append("\n\n").append(it) }
            "edit" -> ChatCollapse.editChanges(argsJson).forEach { (oldText, newText) ->
                append("\n\n− ").append(oldText).append("\n+ ").append(newText)
            }
        }
        output?.takeIf { it.isNotBlank() }?.let { append('\n').append(it) }
    }.takeIf { it.isNotBlank() }
    is ChatItem.BashOutput -> buildString {
        command?.takeIf { it.isNotBlank() }?.let { append("$ ").append(it).append('\n') }
        append(output)
    }.trim().takeIf { it.isNotEmpty() }
    is ChatItem.SystemNote -> text.takeIf { it.isNotBlank() }
    is StreamReducer.RemoveLive -> null
}
