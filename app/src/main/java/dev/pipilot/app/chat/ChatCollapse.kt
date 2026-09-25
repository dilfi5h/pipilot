package dev.pipilot.app.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Visual family for tool cards. Built-in file tools get their own color; extensions share Generic. */
enum class ToolFamily { Bash, Read, Write, Edit, Generic }

/**
 * Collapse policy for thinking / tool / bash cards.
 *
 * Default: expanded while the item is still running or streaming, collapsed
 * when it settles. A user tap overrides until the item key changes (live → final).
 */
object ChatCollapse {
    const val PREVIEW_MAX_CHARS = 160
    const val EXPANDED_MAX_CHARS = 32_000

    fun toolFamily(name: String): ToolFamily = when (name.trim().lowercase()) {
        "bash" -> ToolFamily.Bash
        "read" -> ToolFamily.Read
        "write" -> ToolFamily.Write
        "edit" -> ToolFamily.Edit
        else -> ToolFamily.Generic
    }

    private val argsJson = Json { ignoreUnknownKeys = true; isLenient = true }

    fun isMarkdownPath(path: String): Boolean {
        val name = path.trim().substringBefore('?').substringBefore('#')
            .substringAfterLast('/').substringAfterLast('\\')
        val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return ext == "md" || ext == "markdown" || ext == "mdx"
    }

    /** Pull command / path from a tool-args JSON object, or return the raw string. */
    fun summarizeArgs(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        val obj = runCatching { argsJson.parseToJsonElement(trimmed) }.getOrNull() as? JsonObject
            ?: return trimmed.take(300)
        fun field(key: String): String? =
            (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        return field("command") ?: field("path") ?: field("file_path") ?: trimmed.take(300)
    }

    fun isExpanded(active: Boolean, userExpanded: Boolean?): Boolean =
        userExpanded ?: active

    /** Full content argument used by the built-in write tool. */
    fun writeContent(raw: String?): String? =
        stringField(parseObject(raw), "content", "text")

    /** Old/new pairs used by pi's edit tool, including empty old/new sides. */
    fun editChanges(raw: String?): List<Pair<String, String>> {
        val obj = parseObject(raw) ?: return emptyList()
        val edits = (obj["edits"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?: listOf(obj)
        return edits.mapNotNull { edit ->
            val oldText = stringField(edit, "oldText", "old_string")
            val newText = stringField(edit, "newText", "new_string")
            if (oldText == null && newText == null) null else (oldText ?: "") to (newText ?: "")
        }
    }

    private fun parseObject(raw: String?): JsonObject? {
        if (raw.isNullOrBlank()) return null
        return runCatching { argsJson.parseToJsonElement(raw.trim()) as? JsonObject }.getOrNull()
    }

    private fun stringField(obj: JsonObject?, vararg keys: String): String? {
        if (obj == null) return null
        for (key in keys) {
            (obj[key] as? JsonPrimitive)?.contentOrNull?.let { return it }
        }
        return null
    }

    fun thinkingPreview(text: String): String =
        clipPreview(firstNonBlankLine(text), fromEnd = false)

    fun outputPreview(text: String): String =
        clipPreview(lastNonBlankLine(text), fromEnd = true)

    /** Full card body after expand; keeps a cap so a multi-MB dump cannot freeze Compose. */
    fun expandedBody(text: String, fromEnd: Boolean, maxChars: Int = EXPANDED_MAX_CHARS): String {
        if (text.length <= maxChars) return text
        val omitted = text.length - maxChars
        return if (fromEnd) {
            "…(truncated $omitted chars)\n" + text.takeLast(maxChars)
        } else {
            text.take(maxChars) + "\n…(truncated $omitted chars)"
        }
    }

    fun firstNonBlankLine(text: String): String =
        text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    fun lastNonBlankLine(text: String): String =
        text.lineSequence().lastOrNull { it.isNotBlank() }?.trim().orEmpty()

    fun clipPreview(line: String, fromEnd: Boolean, maxChars: Int = PREVIEW_MAX_CHARS): String {
        if (line.length <= maxChars) return line
        return if (fromEnd) "…" + line.takeLast(maxChars - 1)
        else line.take(maxChars - 1) + "…"
    }
}
