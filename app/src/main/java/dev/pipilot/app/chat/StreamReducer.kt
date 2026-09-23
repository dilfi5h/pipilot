package dev.pipilot.app.chat

import dev.pipilot.app.rpc.ChatMessage
import dev.pipilot.app.rpc.PiEvent

/**
 * Reduce the RPC event stream into UI items.
 *
 * Streaming assembly (see docs/rpc.md):
 * - message_update has no snapshot; accumulate with contentIndex + delta
 * - tool_execution_update.partialResult is cumulative output; replace in place
 * - message_end.message is authoritative: emit the final bubble; the VM drops live (see RemoveLive)
 *
 * Generation speed (TTFT / tok/s) is timed on this client from event arrival:
 * [nowMs] is injectable so tests can pin timestamps. History items never go through
 * this path and stay without speed.
 */
class StreamReducer(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    /** Sentinel: VM drops the live bubble after receiving this (used when message_end commits the final). */
    data object RemoveLive : ChatItem {
        override val key: String = "live"
    }

    // Assistant message currently streaming: key = "live"
    private var liveText = StringBuilder()
    private var liveThinking = StringBuilder()
    // In-flight toolcall: name plus accumulated args
    private val liveToolCalls = LinkedHashMap<String, LiveToolCall>()
    private var streaming = false

    // Timing window for the assistant message currently streaming. startMs is
    // null until agent_start / the first assistant message_start; 0 is a valid clock.
    private var startMs: Long? = null
    private var firstMs: Long? = null
    private var lastMs: Long? = null
    private var outputTokens: Long? = null
    private var usageFromProvider: Boolean = false
    private var firstOfTurn: Boolean = true

    private data class LiveToolCall(
        val id: String,
        var name: String,
        var args: StringBuilder,
    )

    fun onEvent(event: PiEvent, sink: (ChatItem) -> Unit) {
        when (event.type) {
            "agent_start" -> {
                streaming = true
                liveText = StringBuilder()
                liveThinking = StringBuilder()
                liveToolCalls.clear()
                resetTiming(firstOfTurn = true)
                // Plant an empty bubble as soon as the agent starts: the pre-token thinking
                // window (10–40s) still needs a blinking cursor, or the UI looks dead.
                sink(
                    ChatItem.AssistantText(
                        text = "",
                        thinking = null,
                        streaming = true,
                        key = "live",
                        speed = currentSpeed(streaming = true),
                    ),
                )
            }

            "message_start" -> {
                val msgObj = event.message ?: return
                val msg = ChatMessage.from(msgObj)
                if (msg.role != "assistant") return
                // First assistant of the run keeps agent_start as t_start (user-perceived TTFT).
                // After tools, this is a new LLM call: restart the window and omit TTFT.
                // No agent_start (reconnect mid-stream): start the window here.
                if (startMs == null) {
                    resetTiming(firstOfTurn = true)
                } else if (!firstOfTurn || firstMs != null) {
                    resetTiming(firstOfTurn = false)
                }
            }

            "message_update" -> {
                val d = event.delta ?: return
                noteProviderUsage(event.usage)
                when (d.type) {
                    "text_delta" -> {
                        liveText.append(d.delta ?: "")
                        stampToken()
                        emitLive(sink)
                    }
                    "thinking_delta" -> {
                        liveThinking.append(d.delta ?: "")
                        stampToken()
                        emitLive(sink)
                    }
                    "text_end" -> {
                        // Authoritative full text; overwrite
                        if (d.content != null) {
                            liveText = StringBuilder(d.content)
                            if (firstMs == null) stampToken()
                            emitLive(sink)
                        }
                    }
                    "toolcall_start" -> {
                        val id = d.toolCallId ?: return
                        liveToolCalls.getOrPut(id) { LiveToolCall(id, d.toolName ?: "?", StringBuilder()) }
                        emitLive(sink)
                    }
                    "toolcall_delta" -> {
                        val id = d.toolCallId ?: return
                        liveToolCalls[id]?.args?.append(d.delta ?: "")
                        emitLive(sink)
                    }
                    "toolcall_end" -> {
                        val id = d.toolCallId ?: return
                        liveToolCalls[id]?.let { live ->
                            emitToolCard(sink, live.id, live.name, live.args.toString(), output = null, running = true)
                        }
                    }
                }
            }

            "tool_execution_start" -> {
                val id = event.toolCallId ?: return
                val args = extractArgsText(event.raw["args"])
                emitToolCard(sink, id, event.toolName ?: "?", args, output = null, running = true)
                // Assistant text was already committed on toolcall_start; clear live text but do not emit an empty bubble
                liveText = StringBuilder()
                liveThinking = StringBuilder()
            }

            "tool_execution_update" -> {
                val id = event.toolCallId ?: return
                val partial = event.partialToolText ?: return
                emitToolCard(sink, id, event.toolName ?: "?", null, output = partial, running = true)
            }

            "tool_execution_end" -> {
                val id = event.toolCallId ?: return
                emitToolCard(sink, id, event.toolName ?: "?", null, output = event.resultToolText ?: "", running = false, isError = event.isError)
            }

            "message_end" -> {
                // Authoritative final: persist the whole message and have the VM drop live
                // (so one reply is not shown twice). Only on assistant close: a user
                // message_end echo arrives right after prompt and must not clear live
                // (that would kill the waiting-cursor bubble planted on agent_start).
                val msgObj = event.message ?: return
                val msg = ChatMessage.from(msgObj)
                if (msg.role != "assistant") return
                noteProviderUsage(msgObj["usage"] as? kotlinx.serialization.json.JsonObject)
                // Close the decode window at message_end so a single fat delta still gets a rate.
                if (firstMs != null) lastMs = nowMs()
                flushAssistantFromMessage(msg, sink)
                liveText = StringBuilder()
                liveThinking = StringBuilder()
                liveToolCalls.clear()
                streaming = false
                firstOfTurn = false
                firstMs = null
                lastMs = null
                outputTokens = null
                usageFromProvider = false
                sink(RemoveLive)
            }

            "agent_end", "agent_settled" -> {
                streaming = false
            }

            "bash_execution_update" -> {
                val delta = event.raw.str("delta") ?: return
                val id = event.raw.str("id") ?: "direct"
                sink(ChatItem.BashOutput(command = null, output = delta, running = true, key = "bash-$id"))
            }

            // Keys must be unique: LazyColumn crashes on duplicates; system notes always include a timestamp
            "compaction_start" -> sink(ChatItem.SystemNote("Compacting context…", key = "compaction-start-${System.nanoTime()}"))
            "compaction_end" -> {
                val ok = event.raw["result"] != null
                sink(ChatItem.SystemNote(if (ok) "Context compacted" else "Context compaction failed", key = "compaction-end-${System.nanoTime()}"))
            }
            "auto_retry_start" -> sink(
                ChatItem.SystemNote(
                    "Transient error, retrying (attempt ${event.raw.intOrNull("attempt")})…",
                    key = "retry-${System.nanoTime()}",
                )
            )
            "auto_retry_end" -> {
                // Final failure must surface: success=false carries finalError, otherwise the user only sees a hang
                if (event.raw.boolOrNull("success") == false) {
                    val err = event.raw.str("finalError") ?: "unknown error"
                    sink(ChatItem.SystemNote("Request failed (retried ${event.raw.intOrNull("attempt") ?: "?"} times): ${err.take(300)}", key = "retry-final-${System.nanoTime()}"))
                }
            }
            "extension_error" -> sink(
                ChatItem.SystemNote("Extension error: ${event.raw.str("error")}", key = "ext-err-${System.nanoTime()}")
            )
        }
    }

    private fun emitLive(sink: (ChatItem) -> Unit) {
        // Do not emit empty text: avoids blank bubbles in streaming gaps
        if (liveText.isEmpty() && liveThinking.isEmpty()) return
        val text = liveText.toString()
        val thinking = liveThinking.toString().ifEmpty { null }
        sink(
            ChatItem.AssistantText(
                text = text,
                thinking = thinking,
                streaming = true,
                key = "live",
                speed = currentSpeed(streaming = true),
            ),
        )
    }

    private fun emitToolCard(
        sink: (ChatItem) -> Unit,
        id: String,
        name: String,
        argsSummary: String?,
        output: String?,
        running: Boolean,
        isError: Boolean = false,
    ) {
        sink(ChatItem.ToolCard(toolCallId = id, toolName = name, argsSummary = argsSummary, output = output, running = running, isError = isError))
    }

    private fun flushAssistantFromMessage(msg: ChatMessage, sink: (ChatItem) -> Unit) {
        val text = StringBuilder()
        val thinking = StringBuilder()
        for (b in msg.blocks) {
            when (b.type) {
                "text" -> text.append(b.text ?: "")
                "thinking" -> thinking.append(b.text ?: "")
            }
        }
        // Skip a bubble if both body and thinking are empty (tool-only turns use ToolCard)
        if (text.isEmpty() && thinking.isEmpty()) return
        sink(
            ChatItem.AssistantText(
                text = text.toString(),
                thinking = thinking.toString().ifEmpty { null },
                streaming = false,
                key = "assistant-final-${msg.timestamp ?: nowMs()}-${System.nanoTime()}",
                timeMs = msg.timestamp ?: nowMs(),
                speed = currentSpeed(streaming = false),
            )
        )
    }

    private fun resetTiming(firstOfTurn: Boolean) {
        this.firstOfTurn = firstOfTurn
        startMs = nowMs()
        firstMs = null
        lastMs = null
        outputTokens = null
        usageFromProvider = false
    }

    private fun stampToken() {
        val t = nowMs()
        if (firstMs == null) firstMs = t
        lastMs = t
    }

    private fun noteProviderUsage(usage: kotlinx.serialization.json.JsonObject?) {
        val n = usage.numberLong("output") ?: return
        if (n > 0) {
            outputTokens = n
            usageFromProvider = true
        }
    }

    private fun currentSpeed(streaming: Boolean): GenerationSpeed? {
        val tokens = when {
            usageFromProvider -> outputTokens
            else -> {
                val chars = liveText.length + liveThinking.length
                if (chars <= 0) null else (chars / 4L).coerceAtLeast(1L)
            }
        }
        return computeGenerationSpeed(
            startMs = startMs ?: firstMs ?: 0L,
            firstMs = firstMs,
            lastMs = lastMs,
            outputTokens = tokens,
            firstOfTurn = firstOfTurn,
            estimated = !usageFromProvider,
            streaming = streaming,
        )
    }

    /** Human-readable summary from an args JsonElement. */
    fun extractArgsText(args: kotlinx.serialization.json.JsonElement?): String? {
        val obj = args as? kotlinx.serialization.json.JsonObject ?: return args?.toString()?.take(300)
        // Common tool display fields
        obj["command"]?.let { return (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        obj["path"]?.let { return (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        obj["file_path"]?.let { return (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        return obj.toString().take(300)
    }
}

private fun kotlinx.serialization.json.JsonObject.str(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content

private fun kotlinx.serialization.json.JsonObject.intOrNull(key: String): Int? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()

private fun kotlinx.serialization.json.JsonObject.boolOrNull(key: String): Boolean? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.let {
        when (it) { "true" -> true; "false" -> false; else -> null }
    }

private fun kotlinx.serialization.json.JsonObject?.numberLong(key: String): Long? {
    val p = this?.get(key) as? kotlinx.serialization.json.JsonPrimitive ?: return null
    return p.content.toLongOrNull() ?: p.content.toDoubleOrNull()?.toLong()
}
