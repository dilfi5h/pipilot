package dev.pipilot.app.chat

import dev.pipilot.app.rpc.ChatMessage
import dev.pipilot.app.rpc.ContentBlock
import dev.pipilot.app.rpc.PiEvent

/**
 * 把 RPC 事件流归约成 UI 条目。
 *
 * 流式拼装规则(按 docs/rpc.md):
 * - message_update 不带快照,要用 contentIndex + delta 自行累积
 * - tool_execution_update.partialResult 是累计输出,直接替换
 * - message_end.message 是权威终态:落 final 气泡,VM 层负责删 live(见 RemoveLive 标记)
 */
class StreamReducer {

    /** 特殊标记:VM 收到后删除 live 气泡(message_end 落 final 时用)。*/
    data object RemoveLive : ChatItem {
        override val key: String = "live"
    }

    // 正在流式生成的助手消息:key = "live"
    private var liveText = StringBuilder()
    private var liveThinking = StringBuilder()
    // 流式中的 toolcall:name 累计参数
    private val liveToolCalls = LinkedHashMap<String, LiveToolCall>()
    private var streaming = false

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
                // agent 一启动就放空气泡占位:首 token 前的服务端思考期(可达 10-40s)
                // 也要有闪动光标,否则用户面对的是一片死寂
                sink(ChatItem.AssistantText(text = "", thinking = null, streaming = true, key = "live"))
            }

            "message_update" -> {
                val d = event.delta ?: return
                when (d.type) {
                    "text_delta" -> {
                        liveText.append(d.delta ?: "")
                        emitLive(sink)
                    }
                    "thinking_delta" -> {
                        liveThinking.append(d.delta ?: "")
                        emitLive(sink)
                    }
                    "text_end" -> {
                        // 权威全文,直接覆盖
                        if (d.content != null) {
                            liveText = StringBuilder(d.content)
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
                        // toolcall_end 里 contentIndex 对应的完整 toolCall 在 raw.assistantMessageEvent.toolCall
                        val tc = event.raw["assistantMessageEvent"]?.let { it::class.simpleName; null }
                        // 保底:用已累计的参数
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
                // 助手文本部分随 toolcall_start 已固化,清空 live 文本但不 emit 空气泡
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
                // 权威终态:整条消息落库,同时让 VM 删掉 live 气泡(避免一条回复显示两次)。
                // 只对 assistant 收尾:user 消息的 message_end 回显紧跟 prompt 到达,
                // 不能清 live 状态(会掐掉 agent_start 刚放的等待光标气泡)
                val msgObj = event.message ?: return
                val msg = ChatMessage.from(msgObj)
                if (msg.role != "assistant") return
                flushAssistantFromMessage(msg, sink)
                liveText = StringBuilder()
                liveThinking = StringBuilder()
                liveToolCalls.clear()
                streaming = false
                sink(RemoveLive)
            }

            "agent_end", "agent_settled" -> {
                streaming = false
            }

            "bash_execution_update" -> {
                val delta = event.raw.str("delta") ?: return
                sink(ChatItem.BashOutput(command = null, output = delta, running = true))
            }

            // 注意 key 必须唯一:LazyColumn 重复 key 会直接崩,系统类提示一律带时间戳
            "compaction_start" -> sink(ChatItem.SystemNote("正在压缩上下文…", key = "compaction-start-${System.nanoTime()}"))
            "compaction_end" -> {
                val ok = event.raw["result"] != null
                sink(ChatItem.SystemNote(if (ok) "上下文压缩完成" else "上下文压缩失败", key = "compaction-end-${System.nanoTime()}"))
            }
            "auto_retry_start" -> sink(
                ChatItem.SystemNote(
                    "临时错误,自动重试中(第 ${event.raw.intOrNull("attempt")} 次)…",
                    key = "retry-${System.nanoTime()}",
                )
            )
            "auto_retry_end" -> {
                // 最终失败必须浮出:success=false 带 finalError,否则用户只见"卡住"不知何故
                if (event.raw.boolOrNull("success") == false) {
                    val err = event.raw.str("finalError") ?: "未知错误"
                    sink(ChatItem.SystemNote("请求失败(已重试 ${event.raw.intOrNull("attempt") ?: "?"} 次): ${err.take(300)}", key = "retry-final-${System.nanoTime()}"))
                }
            }
            "extension_error" -> sink(
                ChatItem.SystemNote("扩展错误: ${event.raw.str("error")}", key = "ext-err-${System.nanoTime()}")
            )
        }
    }

    private fun emitLive(sink: (ChatItem) -> Unit) {
        // 空文本不 emit:避免流式间隙出现空白气泡
        if (liveText.isEmpty() && liveThinking.isEmpty()) return
        val text = liveText.toString()
        val thinking = liveThinking.toString().ifEmpty { null }
        sink(ChatItem.AssistantText(text = text, thinking = thinking, streaming = true, key = "live"))
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
        // 正文思考都空就不落气泡(纯工具调用由 ToolCard 展示,避免空白条目)
        if (text.isEmpty() && thinking.isEmpty()) return
        sink(
            ChatItem.AssistantText(
                text = text.toString(),
                thinking = thinking.toString().ifEmpty { null },
                streaming = false,
                key = "assistant-final-${msg.timestamp ?: System.currentTimeMillis()}-${System.nanoTime()}",
                timeMs = msg.timestamp ?: System.currentTimeMillis(),
            )
        )
    }

    /** 从 args JsonElement 提取人类可读摘要。*/
    fun extractArgsText(args: kotlinx.serialization.json.JsonElement?): String? {
        val obj = args as? kotlinx.serialization.json.JsonObject ?: return args?.toString()?.take(300)
        // 常见工具的展示字段
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
