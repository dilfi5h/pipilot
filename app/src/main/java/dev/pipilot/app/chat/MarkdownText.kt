package dev.pipilot.app.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Lightweight Markdown rendering: pi replies are mostly markdown, and plain Text turns them into a blob.
 * Supports headings, unordered/ordered lists, quotes, rules, fenced code (simple highlighting),
 * GFM tables, inline code, bold, italic, strikethrough, and links.
 * No third-party library on purpose (deps would need a proxy; this subset is enough for chat);
 * text that does not parse as structure is shown as-is.
 */

// ---------- Block parse ----------

private sealed interface MdBlock {
    data class Para(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Code(val lang: String?, val code: String) : MdBlock
    data class ListItem(val marker: String, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock
    /** LaTeX display math, kept as source text (not rendered); [latex] includes its delimiters. */
    data class Formula(val latex: String) : MdBlock
    data object Rule : MdBlock
}

private val fenceRegex = Regex("^```\\s*(\\S*)\\s*$")
private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
private val bulletRegex = Regex("^\\s*[-*+]\\s+(.*)$")
private val orderedRegex = Regex("^\\s*(\\d{1,3})[.)]\\s+(.*)$")
private val quoteRegex = Regex("^\\s*>\\s?(.*)$")
private val ruleRegex = Regex("^\\s*([-*_])\\1{2,}\\s*$")

private fun parseBlocks(src: String): List<MdBlock> {
    val blocks = ArrayList<MdBlock>()
    val para = StringBuilder()
    val lines = src.lines()
    fun flushPara() {
        if (para.isNotBlank()) blocks.add(MdBlock.Para(para.toString().trim()))
        para.clear()
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val fenceM = fenceRegex.matchEntire(line)
        val headM = headingRegex.matchEntire(line)
        val bulletM = bulletRegex.matchEntire(line)
        val orderedM = orderedRegex.matchEntire(line)
        val quoteM = quoteRegex.matchEntire(line)
        val ruleM = ruleRegex.matchEntire(line)
        val table = parseTable(lines, i)
        val formula = displayMathAt(lines, i)
        when {
            formula != null -> {
                flushPara()
                blocks.add(formula.first)
                i = formula.second
            }
            fenceM != null -> {
                flushPara()
                val lang = fenceM.groupValues[1].takeIf { it.isNotBlank() }
                val code = StringBuilder()
                i++
                while (i < lines.size && fenceRegex.matchEntire(lines[i]) == null) {
                    code.appendLine(lines[i])
                    i++
                }
                i++ // skip closing ``` (or EOF)
                blocks.add(MdBlock.Code(lang, code.toString().trimEnd('\n', '\r')))
            }
            table != null -> {
                flushPara()
                blocks.add(table.first)
                i = table.second
            }
            ruleM != null -> { flushPara(); blocks.add(MdBlock.Rule); i++ }
            headM != null -> {
                flushPara()
                blocks.add(MdBlock.Heading(headM.groupValues[1].length, headM.groupValues[2].trim()))
                i++
            }
            bulletM != null -> { flushPara(); blocks.add(MdBlock.ListItem("•", bulletM.groupValues[1])); i++ }
            orderedM != null -> {
                flushPara()
                blocks.add(MdBlock.ListItem("${orderedM.groupValues[1]}.", orderedM.groupValues[2]))
                i++
            }
            quoteM != null -> { flushPara(); blocks.add(MdBlock.Quote(quoteM.groupValues[1])); i++ }
            line.isBlank() -> { flushPara(); i++ }
            else -> { para.appendLine(line); i++ }
        }
    }
    flushPara()
    return blocks
}

/** Test hook: whether [src] parsed at least one GFM table. */
internal fun markdownContainsTable(src: String): Boolean =
    parseBlocks(src).any { it is MdBlock.Table }

/**
 * Test hook: LaTeX formula blocks parsed out of [src], source text with delimiters included.
 */
internal fun markdownFormulaBlocks(src: String): List<String> =
    parseBlocks(src).filterIsInstance<MdBlock.Formula>().map { it.latex }

/** Test hook: inline `$...$` runs kept verbatim (delimiters included). */
internal fun markdownInlineMathRuns(src: String): List<String> =
    parseInline(src).filter { it.second.math }.map { it.first }

/** Test hook: block kinds in order (e.g. `listOf("Para", "Formula", "Code")`). */
internal fun markdownBlockKinds(src: String): List<String> =
    parseBlocks(src).map { it::class.simpleName ?: "?" }

// ---------- LaTeX detection (no rendering, copy the source) ----------

private data class MathDelim(val open: String, val close: String) {
    fun wrap(body: String) = open + body + close
    fun unwrap(single: String) = single.substring(2, single.length - 2)
}

private fun mathDelim(trimmed: String): MathDelim? = when {
    trimmed == "\\[" || (trimmed.length > 4 && trimmed.startsWith("\\[") && trimmed.endsWith("\\]")) ->
        MathDelim("\\[", "\\]")
    trimmed == "$$" || (trimmed.length > 4 && trimmed.startsWith("$$") && trimmed.endsWith("$$")) ->
        MathDelim("$$", "$$")
    else -> null
}

/**
 * Display math at [start], or null when this line is not a formula.
 *
 * Deliberately conservative: an unclosed delimiter (no closing line, a blank line, or a
 * code fence in between) falls back to plain text, so a stray `$$` can never swallow the
 * rest of the message or break a fenced code block.
 */
private fun displayMathAt(lines: List<String>, start: Int): Pair<MdBlock.Formula, Int>? {
    val line = lines[start].trim()
    val delim = mathDelim(line) ?: return null
    if (line == delim.open) {
        var end = start + 1
        while (end < lines.size) {
            val t = lines[end].trim()
            if (t == delim.close) break
            if (t.isEmpty() || t.startsWith("```")) return null
            end++
        }
        if (end >= lines.size) return null
        val inner = lines.subList(start + 1, end).joinToString("\n")
        if (inner.isBlank()) return null
        return MdBlock.Formula(delim.wrap(inner)) to end + 1
    }
    val inner = delim.unwrap(line)
    if (inner.isBlank()) return null
    return MdBlock.Formula(delim.wrap(inner)) to start + 1
}

private val tableSepRegex = Regex("""^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?\s*$""")

private fun looksLikeTableRow(line: String): Boolean {
    val t = line.trim()
    return t.startsWith("|") && t.endsWith("|") && t.indexOf('|', 1) > 0
}

private fun splitTableRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

/** GFM table starting at [start]; null if this line is not a header+separator pair. */
private fun parseTable(lines: List<String>, start: Int): Pair<MdBlock.Table, Int>? {
    if (start + 1 >= lines.size) return null
    if (!looksLikeTableRow(lines[start]) || !tableSepRegex.matches(lines[start + 1])) return null
    val headers = splitTableRow(lines[start])
    if (headers.isEmpty()) return null
    val rows = ArrayList<List<String>>()
    var i = start + 2
    while (i < lines.size && looksLikeTableRow(lines[i])) {
        rows.add(splitTableRow(lines[i]))
        i++
    }
    return MdBlock.Table(headers, rows) to i
}

// ---------- Inline parse ----------

private data class InlineStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    val link: Boolean = false,
    /** Inline LaTeX `$...$`: kept verbatim, only styled. */
    val math: Boolean = false,
)

/** Boundary chars that may follow inline math (mirrors the HTML preview's punctuation set). */
private fun isMathBoundary(c: Char?): Boolean =
    c == null || c.isWhitespace() || c in "，。！？、：；）】」》…—\"'.,;:!?)*"

/** Split into (text, style) runs; unknown markers are kept as-is. */
private fun parseInline(text: String, base: InlineStyle = InlineStyle()): List<Pair<String, InlineStyle>> {
    val runs = ArrayList<Pair<String, InlineStyle>>()
    val lit = StringBuilder()
    fun flush() {
        if (lit.isNotEmpty()) {
            runs.add(lit.toString() to base)
            lit.clear()
        }
    }
    fun inner(from: Int, to: Int, style: InlineStyle): List<Pair<String, InlineStyle>> =
        parseInline(text.substring(from, to), style)

    var i = 0
    val n = text.length
    while (i < n) {
        val c = text[i]
        when {
            c == '`' -> {
                val close = text.indexOf('`', i + 1)
                if (close > i + 1) {
                    flush()
                    runs.add(text.substring(i + 1, close) to base.copy(code = true))
                    i = close + 1
                } else { lit.append(c); i++ }
            }
            text.startsWith("**", i) -> {
                val close = text.indexOf("**", i + 2)
                if (close > i + 2 && !text[i + 2].isWhitespace()) {
                    flush()
                    runs.addAll(inner(i + 2, close, base.copy(bold = true)))
                    i = close + 2
                } else { lit.append(c); i++ }
            }
            text.startsWith("~~", i) -> {
                val close = text.indexOf("~~", i + 2)
                if (close > i + 2) {
                    flush()
                    runs.addAll(inner(i + 2, close, base.copy(strike = true)))
                    i = close + 2
                } else { lit.append(c); i++ }
            }
            c == '*' -> {
                val close = text.indexOf('*', i + 1)
                if (close > i + 1 && !text[i + 1].isWhitespace() && (i == 0 || text[i - 1].isWhitespace() || text[i - 1] == '(')) {
                    flush()
                    runs.addAll(inner(i + 1, close, base.copy(italic = true)))
                    i = close + 1
                } else { lit.append(c); i++ }
            }
            c == '_' -> {
                val close = text.indexOf('_', i + 1)
                val boundaryBefore = i == 0 || !text[i - 1].isLetterOrDigit()
                val boundaryAfter = close + 1 >= n || !text[close + 1].isLetterOrDigit()
                if (close > i + 1 && boundaryBefore && boundaryAfter && !text[i + 1].isWhitespace()) {
                    flush()
                    runs.addAll(inner(i + 1, close, base.copy(italic = true)))
                    i = close + 1
                } else { lit.append(c); i++ }
            }
            c == '$' -> {
                val close = text.indexOf('$', i + 1)
                val body = if (close > i + 1) text.substring(i + 1, close) else null
                val before = if (i == 0) null else text[i - 1]
                val boundaryBefore = before == null || !(before.isLetterOrDigit() || before == '_' || before == '$')
                // 明显是金额的（$10）不要当公式
                val isCurrency = body != null && body.first().isDigit() &&
                    body.all { it.isDigit() || it.isWhitespace() || it in ".,%$€£" }
                if (body != null && !body.contains('\n') && boundaryBefore && isMathBoundary(text.getOrNull(close + 1)) && !isCurrency) {
                    flush()
                    runs.add(text.substring(i, close + 1) to base.copy(math = true))
                    i = close + 1
                } else { lit.append(c); i++ }
            }
            c == '[' -> {
                val closeB = text.indexOf("](", i + 1)
                val closeP = if (closeB > i) text.indexOf(')', closeB + 2) else -1
                if (closeB > i && closeP > closeB) {
                    flush()
                    runs.add(text.substring(i + 1, closeB) to base.copy(link = true))
                    i = closeP + 1
                } else { lit.append(c); i++ }
            }
            else -> { lit.append(c); i++ }
        }
    }
    flush()
    return runs
}

@Composable
private fun inlineText(text: String): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBg = Color(0x1F888888)
    return remember(text, linkColor) {
        buildAnnotatedString {
            for ((t, s) in parseInline(text)) {
                pushStyle(
                    SpanStyle(
                        fontWeight = if (s.bold) FontWeight.Bold else null,
                        fontStyle = if (s.italic) FontStyle.Italic else null,
                        textDecoration = if (s.strike) TextDecoration.LineThrough else null,
                        fontFamily = if (s.code || s.math) FontFamily.Monospace else null,
                        background = if (s.code || s.math) codeBg else Color.Unspecified,
                        color = if (s.link) linkColor else Color.Unspecified,
                    )
                )
                append(t)
                pop()
            }
        }
    }
}

// ---------- Simple code highlighting ----------

// Mixed keywords from common languages; good enough for chat code blocks; inaccurate coloring is fine
private val CODE_KEYWORDS = setOf(
    "abstract", "and", "as", "async", "await", "break", "case", "catch", "class", "const",
    "continue", "def", "default", "do", "elif", "else", "enum", "export", "extends", "false",
    "finally", "fn", "for", "from", "func", "function", "go", "if", "impl", "import", "in",
    "instanceof", "interface", "is", "lambda", "let", "match", "module", "new", "not", "null",
    "object", "or", "package", "pass", "private", "protected", "public", "raise", "return",
    "self", "static", "struct", "super", "switch", "this", "throw", "throws", "trait", "true",
    "try", "type", "typeof", "use", "val", "var", "void", "when", "where", "while", "with", "yield",
    "None", "True", "False",
)
private val HASH_COMMENT_LANGS = setOf(
    "sh", "bash", "zsh", "py", "python", "yaml", "yml", "ruby", "rb",
    "toml", "ini", "conf", "dockerfile", "makefile", "perl", "pl", "r",
)
private val CodeDefault = Color(0xFFD4D4D4)
private val CodeKeyword = Color(0xFF569CD6)
private val CodeString = Color(0xFFCE9178)
private val CodeComment = Color(0xFF6A9955)
private val CodeNumber = Color(0xFFB5CEA8)

private fun highlightCode(code: String, lang: String?): AnnotatedString = buildAnnotatedString {
    val hashComment = lang == null || lang.lowercase() in HASH_COMMENT_LANGS
    val n = code.length
    val lit = StringBuilder()
    fun flush() {
        if (lit.isNotEmpty()) {
            append(lit.toString())
            lit.clear()
        }
    }
    fun styled(from: Int, to: Int, color: Color) {
        pushStyle(SpanStyle(color = color))
        append(code.substring(from, to.coerceAtMost(n)))
        pop()
    }
    var i = 0
    while (i < n) {
        val c = code[i]
        when {
            // line comment
            (c == '/' && i + 1 < n && code[i + 1] == '/') || (hashComment && c == '#') -> {
                flush()
                val end = code.indexOf('\n', i)
                val stop = if (end == -1) n else end
                styled(i, stop, CodeComment)
                i = stop
            }
            // block comment
            c == '/' && i + 1 < n && code[i + 1] == '*' -> {
                flush()
                val end = code.indexOf("*/", i + 2)
                val stop = if (end == -1) n else end + 2
                styled(i, stop, CodeComment)
                i = stop
            }
            // string (with escapes; unclosed strings stop at EOL; backtick templates may span lines)
            c == '"' || c == '\'' || c == '`' -> {
                flush()
                var j = i + 1
                while (j < n) {
                    when {
                        code[j] == '\\' -> j += 2
                        code[j] == c -> { j++; break }
                        code[j] == '\n' && c != '`' -> break
                        else -> j++
                    }
                }
                val stop = j.coerceAtMost(n)
                styled(i, stop, CodeString)
                i = stop
            }
            c.isDigit() -> {
                flush()
                var j = i
                while (j < n && (code[j].isDigit() || code[j] == '.')) j++
                styled(i, j, CodeNumber)
                i = j
            }
            c.isLetter() || c == '_' -> {
                var j = i
                while (j < n && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                val word = code.substring(i, j)
                flush()
                if (word in CODE_KEYWORDS) styled(i, j, CodeKeyword) else lit.append(word)
                i = j
            }
            else -> { lit.append(c); i++ }
        }
    }
    flush()
}

// ---------- Render ----------

/** Markdown entry point for pi replies; blank text renders nothing. */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    if (markdown.isBlank()) return
    val blocks = remember(markdown) { parseBlocks(markdown) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { b ->
            when (b) {
                is MdBlock.Para -> Text(inlineText(b.text), style = MaterialTheme.typography.bodyMedium)
                is MdBlock.Heading -> Text(
                    inlineText(b.text),
                    style = when (b.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        3 -> MaterialTheme.typography.titleSmall
                        else -> MaterialTheme.typography.bodyLarge
                    },
                    fontWeight = FontWeight.SemiBold,
                )
                is MdBlock.Code -> CodeBlock(b)
                is MdBlock.ListItem -> Row {
                    Text(
                        b.marker + " ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        inlineText(b.text),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
                is MdBlock.Quote -> Row(Modifier.height(intrinsicSize = androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        inlineText(b.text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MdBlock.Rule -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                is MdBlock.Table -> MdTable(b)
                is MdBlock.Formula -> FormulaBlock(b)
            }
        }
    }
}

/**
 * LaTeX block: not rendered. Shows the source plus a copy button so it can be pasted
 * into a math-capable viewer. Header stays one line tall — no hint text, no extra padding.
 */
@Composable
private fun FormulaBlock(block: MdBlock.Formula) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 2.dp, top = 1.dp, bottom = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "LaTeX",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                CopyItemButton(
                    text = block.latex,
                    contentDescription = "Copy LaTeX",
                )
            }
            Surface(
                color = Color(0xFF1E1E1E),
                shape = RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    block.latex,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = CodeDefault,
                    softWrap = false,
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun MdTable(block: MdBlock.Table) {
    val cols = block.headers.size
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        Row {
            block.headers.forEach { h ->
                Text(
                    inlineText(h),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        block.rows.forEach { row ->
            Row {
                for (c in 0 until cols) {
                    Text(
                        inlineText(row.getOrElse(c) { "" }),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(block: MdBlock.Code) {
    val clipboard = LocalClipboardManager.current
    Surface(color = Color(0xFF1E1E1E), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DisableSelection {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        block.lang ?: "code",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF757575),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { clipboard.setText(AnnotatedString(block.code)) },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = "Copy code",
                            modifier = Modifier.size(14.dp),
                            tint = Color(0xFFBDBDBD),
                        )
                    }
                }
            }
            Text(
                remember(block.code, block.lang) { highlightCode(block.code, block.lang) },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = CodeDefault,
                softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
    }
}
