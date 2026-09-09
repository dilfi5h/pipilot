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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 * 轻量 Markdown 渲染:pi 的回复以 markdown 为主,纯 Text 显示会糊成一片。
 * 支持:标题/无序有序列表/引用/分隔线/围栏代码块(带简易语法着色)/行内 code、粗体、斜体、删除线、链接。
 * 有意不引第三方库(依赖下载要走代理,聊天场景这套子集够用);解析不出结构的文本原样显示。
 */

// ---------- 块级解析 ----------

private sealed interface MdBlock {
    data class Para(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Code(val lang: String?, val code: String) : MdBlock
    data class ListItem(val marker: String, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
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
        when {
            fenceM != null -> {
                flushPara()
                val lang = fenceM.groupValues[1].takeIf { it.isNotBlank() }
                val code = StringBuilder()
                i++
                while (i < lines.size && fenceRegex.matchEntire(lines[i]) == null) {
                    code.appendLine(lines[i])
                    i++
                }
                i++ // 跳过收尾 ```(或 EOF)
                blocks.add(MdBlock.Code(lang, code.toString().trimEnd('\n', '\r')))
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

// ---------- 行内解析 ----------

private data class InlineStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    val link: Boolean = false,
)

/** 切成 (文本, 样式) 序列;不认识的记号原样保留。*/
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
                        fontFamily = if (s.code) FontFamily.Monospace else null,
                        background = if (s.code) codeBg else Color.Unspecified,
                        color = if (s.link) linkColor else Color.Unspecified,
                    )
                )
                append(t)
                pop()
            }
        }
    }
}

// ---------- 代码块简易语法着色 ----------

// 主流语言关键字混编一套,聊天代码块够用;着色不准确也无伤大雅
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
            // 行注释
            (c == '/' && i + 1 < n && code[i + 1] == '/') || (hashComment && c == '#') -> {
                flush()
                val end = code.indexOf('\n', i)
                val stop = if (end == -1) n else end
                styled(i, stop, CodeComment)
                i = stop
            }
            // 块注释
            c == '/' && i + 1 < n && code[i + 1] == '*' -> {
                flush()
                val end = code.indexOf("*/", i + 2)
                val stop = if (end == -1) n else end + 2
                styled(i, stop, CodeComment)
                i = stop
            }
            // 字符串(含转义;未闭合保守断到行尾,反引号模板串允许跨行)
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

// ---------- 渲染 ----------

/** pi 回复用的 markdown 渲染入口;空文本不渲染任何东西。*/
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
            }
        }
    }
}

@Composable
private fun CodeBlock(block: MdBlock.Code) {
    Surface(color = Color(0xFF1E1E1E), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            block.lang?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF757575),
                )
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
