package dev.pipilot.app.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Data models for pi RPC mode (packages/coding-agent/docs/rpc.md).
 * Fields stay loose: unknown keys are ignored, optional fields get defaults, so protocol evolution does not crash the client.
 */

val piJson = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = false
}

// ---------- Top-level line ----------

/** One JSON line read from stdout. */
sealed interface PiLine {
    data class Response(val value: JsonObject) : PiLine
    data class Event(val value: JsonObject) : PiLine
    data class ExtensionUiRequest(val value: JsonObject) : PiLine
    data class Unknown(val value: JsonObject) : PiLine
}

fun parseLine(line: String): PiLine {
    val obj = piJson.parseToJsonElement(line).jsonObject
    return when (obj["type"]?.jsonPrimitive?.contentOrNull) {
        "response" -> PiLine.Response(obj)
        "extension_ui_request" -> PiLine.ExtensionUiRequest(obj)
        // Everything else is an event, including unknown types, so debug UI can still show them
        else -> PiLine.Event(obj)
    }
}

// ---------- Response ----------

data class PiResponse(
    val id: String?,
    val command: String,
    val success: Boolean,
    val error: String?,
    val data: JsonObject?,
) {
    companion object {
        fun from(obj: JsonObject): PiResponse = PiResponse(
            id = obj.str("id"),
            command = obj.str("command") ?: "",
            success = obj.boolOrNull("success") ?: false,
            error = obj.str("error"),
            data = obj["data"] as? JsonObject,
        )
    }
}

fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

fun JsonObject.longOrNull(key: String): Long? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

fun JsonObject.boolOrNull(key: String): Boolean? = when (val e = this[key]) {
    null, is JsonNull -> null
    is JsonPrimitive -> when (e.content) {
        "true" -> true
        "false" -> false
        else -> null
    }
    else -> null
}

fun JsonObject.stringList(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

/** Join clear_queue / local queues back into the composer: steering first, follow-up after, blank line between. */
fun joinQueueDraft(steering: List<String>, followUp: List<String>): String =
    (steering + followUp).map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")

// ---------- get_state ----------

data class PiModel(
    val id: String,
    val name: String?,
    val provider: String,
    val reasoning: Boolean?,
    val contextWindow: Long?,
) {
    companion object {
        fun from(obj: JsonObject): PiModel = PiModel(
            id = obj.str("id") ?: "",
            name = obj.str("name"),
            provider = obj.str("provider") ?: "",
            reasoning = obj.boolOrNull("reasoning"),
            contextWindow = obj.longOrNull("contextWindow"),
        )
    }

    val displayName: String get() = name ?: id
}

data class PiState(
    val model: PiModel?,
    val thinkingLevel: String?,
    val isStreaming: Boolean,
    val sessionFile: String?,
    val sessionId: String?,
    val sessionName: String?,
) {
    companion object {
        fun from(obj: JsonObject): PiState {
            val d = obj["data"] as? JsonObject ?: JsonObject(emptyMap())
            return PiState(
                model = (d["model"] as? JsonObject)?.let { PiModel.from(it) },
                thinkingLevel = d.str("thinkingLevel"),
                isStreaming = d.boolOrNull("isStreaming") ?: false,
                sessionFile = d.str("sessionFile"),
                sessionId = d.str("sessionId"),
                sessionName = d.str("sessionName"),
            )
        }
    }
}

// ---------- Events ----------

data class DeltaEvent(
    val type: String,
    val contentIndex: Int?,
    val delta: String?,
    val toolName: String?,
    val toolCallId: String?,
    val content: String?, // text_end carries the full text
    /** toolcall_end carries the authoritative complete toolCall object. */
    val toolCall: JsonObject? = null,
)

data class PiEvent(
    val type: String,
    val raw: JsonObject,
) {
    val usage: JsonObject? get() = raw["usage"] as? JsonObject

    val delta: DeltaEvent?
        get() = (raw["assistantMessageEvent"] as? JsonObject)?.let { d ->
            DeltaEvent(
                type = d.str("type") ?: "",
                contentIndex = d.intOrNull("contentIndex"),
                delta = d.str("delta"),
                toolName = d.str("toolName"),
                toolCallId = d.str("id"),
                content = d.str("content"),
                toolCall = d["toolCall"] as? JsonObject,
            )
        }

    val toolCallId: String? get() = raw.str("toolCallId")
    val toolName: String? get() = raw.str("toolName")

    /** Concatenate tool_execution_update partialResult.content[].text (cumulative output; replace the display). */
    val partialToolText: String?
        get() = (raw["partialResult"] as? JsonObject)
            ?.let { it["content"] as? JsonArray }
            ?.joinToString("") { block ->
                (block as? JsonObject)?.str("text") ?: ""
            }
            ?.takeIf { it.isNotEmpty() }

    /** Concatenate tool_execution_end result.content[].text. */
    val resultToolText: String?
        get() = (raw["result"] as? JsonObject)
            ?.let { it["content"] as? JsonArray }
            ?.joinToString("") { block ->
                (block as? JsonObject)?.str("text") ?: ""
            }
            ?.takeIf { it.isNotEmpty() }

    val isError: Boolean get() = raw.boolOrNull("isError") ?: false

    /** Full message on message_start / message_end. */
    val message: JsonObject? get() = raw["message"] as? JsonObject

    /** willRetry on agent_end. */
    val willRetry: Boolean get() = raw.boolOrNull("willRetry") ?: false

    /** Queues on queue_update. */
    val steeringQueue: List<String>
        get() = (raw["steering"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
    val followUpQueue: List<String>
        get() = (raw["followUp"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    /** extension_ui_request fields */
    val uiMethod: String? get() = raw.str("method")
    val uiTitle: String? get() = raw.str("title")
    val uiMessage: String? get() = raw.str("message")
    val uiOptions: List<String>
        get() = (raw["options"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
    val uiPlaceholder: String? get() = raw.str("placeholder")
    val uiPrefill: String? get() = raw.str("prefill")
}

// ---------- Messages (get_messages / message_end) ----------

data class ContentBlock(
    val type: String, // text / thinking / toolCall / image
    val text: String?,
    val toolCallId: String?,
    val toolName: String?,
    val argumentsJson: String?,
)

data class ChatMessage(
    val role: String, // user / assistant / toolResult / bashExecution
    val blocks: List<ContentBlock>,
    val timestamp: Long?,
    val model: String?,
    val isError: Boolean = false,
) {
    companion object {
        fun from(obj: JsonObject): ChatMessage {
            val role = obj.str("role") ?: ""
            val content = obj["content"]
            val blocks = when (content) {
                is JsonPrimitive -> listOf(ContentBlock("text", content.contentOrNull, null, null, null))
                is JsonArray -> content.mapNotNull { el ->
                    val b = el as? JsonObject ?: return@mapNotNull null
                    when (b.str("type")) {
                        "text" -> ContentBlock("text", b.str("text"), null, null, null)
                        "thinking" -> ContentBlock("thinking", b.str("thinking"), null, null, null)
                        "image" -> ContentBlock("image", null, null, null, null)
                        "toolCall" -> ContentBlock(
                            type = "toolCall",
                            text = null,
                            toolCallId = b.str("id"),
                            toolName = b.str("name"),
                            argumentsJson = b["arguments"]?.toString(),
                        )
                        else -> null
                    }
                }
                else -> emptyList()
            }
            // bashExecution messages are not the standard content structure
            if (role == "bashExecution") {
                return ChatMessage(
                    role = role,
                    blocks = listOf(
                        ContentBlock("bash", obj.str("output"), null, obj.str("command"), null)
                    ),
                    timestamp = obj.longOrNull("timestamp"),
                    model = null,
                )
            }
            return ChatMessage(
                role = role,
                blocks = blocks,
                timestamp = obj.longOrNull("timestamp"),
                model = obj.str("model"),
                isError = obj.boolOrNull("isError") ?: false,
            )
        }
    }
}

// ---------- Session entries ----------

data class PiEntry(
    val type: String,
    val id: String,
    val parentId: String?,
    val message: JsonObject?,
) {
    companion object {
        fun from(obj: JsonObject): PiEntry = PiEntry(
            type = obj.str("type") ?: "",
            id = obj.str("id") ?: "",
            parentId = obj.str("parentId"),
            message = obj["message"] as? JsonObject,
        )
    }
}

// ---------- Command builders ----------

object PiCommands {
    private val counter = java.util.concurrent.atomic.AtomicInteger(0)
    fun nextId(): String = "app-${System.currentTimeMillis()}-${counter.getAndIncrement()}"

    fun prompt(
        message: String,
        streamingBehavior: String? = null,
        images: List<Pair<String, String>> = emptyList(),
        id: String = nextId(),
    ): String {
        val obj = buildMap {
            put("id", JsonPrimitive(id))
            put("type", JsonPrimitive("prompt"))
            put("message", JsonPrimitive(message))
            if (streamingBehavior != null) put("streamingBehavior", JsonPrimitive(streamingBehavior))
            if (images.isNotEmpty()) {
                put("images", imageArray(images))
            }
        }
        return JsonObject(obj).toString()
    }

    fun steer(
        message: String,
        images: List<Pair<String, String>> = emptyList(),
        id: String = nextId(),
    ): String {
        val obj = buildMap {
            put("id", JsonPrimitive(id))
            put("type", JsonPrimitive("steer"))
            put("message", JsonPrimitive(message))
            if (images.isNotEmpty()) put("images", imageArray(images))
        }
        return JsonObject(obj).toString()
    }

    fun followUp(
        message: String,
        images: List<Pair<String, String>> = emptyList(),
        id: String = nextId(),
    ): String {
        val obj = buildMap {
            put("id", JsonPrimitive(id))
            put("type", JsonPrimitive("follow_up"))
            put("message", JsonPrimitive(message))
            if (images.isNotEmpty()) put("images", imageArray(images))
        }
        return JsonObject(obj).toString()
    }

    private fun imageArray(images: List<Pair<String, String>>): JsonArray =
        JsonArray(images.map { (base64, mimeType) ->
            JsonObject(mapOf(
                "type" to JsonPrimitive("image"),
                "data" to JsonPrimitive(base64),
                "mimeType" to JsonPrimitive(mimeType),
            ))
        })

    fun named(type: String, extra: Map<String, JsonElement> = emptyMap(), id: String = nextId()): String {
        val obj = buildMap {
            put("id", JsonPrimitive(id))
            put("type", JsonPrimitive(type))
            putAll(extra)
        }
        return JsonObject(obj).toString()
    }

    fun setModel(provider: String, modelId: String) =
        named("set_model", mapOf("provider" to JsonPrimitive(provider), "modelId" to JsonPrimitive(modelId)))

    fun setThinkingLevel(level: String) =
        named("set_thinking_level", mapOf("level" to JsonPrimitive(level)))

    fun switchSession(path: String) =
        named("switch_session", mapOf("sessionPath" to JsonPrimitive(path)))

    fun getEntries(since: String? = null, id: String = nextId()): String = named(
        "get_entries",
        if (since != null) mapOf("since" to JsonPrimitive(since)) else emptyMap(),
        id = id,
    )

    fun setSessionNameReq(name: String, id: String = nextId()): String =
        named("set_session_name", mapOf("name" to JsonPrimitive(name)), id = id)

    fun uiResponseValue(id: String, value: String): String =
        JsonObject(mapOf("type" to JsonPrimitive("extension_ui_response"), "id" to JsonPrimitive(id), "value" to JsonPrimitive(value))).toString()

    fun uiResponseConfirm(id: String, confirmed: Boolean): String =
        JsonObject(mapOf("type" to JsonPrimitive("extension_ui_response"), "id" to JsonPrimitive(id), "confirmed" to JsonPrimitive(confirmed))).toString()

    fun uiResponseCancel(id: String): String =
        JsonObject(mapOf("type" to JsonPrimitive("extension_ui_response"), "id" to JsonPrimitive(id), "cancelled" to JsonPrimitive(true))).toString()
}
