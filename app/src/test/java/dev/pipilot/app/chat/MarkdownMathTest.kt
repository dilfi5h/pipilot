package dev.pipilot.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** LaTeX is detected but never rendered: blocks keep their source text plus delimiters. */
class MarkdownMathTest {

    @Test
    fun multiLineBracketKeepsWholeBody() {
        val src = "\\[\n\\mathrm{RMS}(x)\n=\n\\sqrt{\\frac{1}{d}}\n\\]"
        assertEquals(listOf("\\[\\mathrm{RMS}(x)\n=\n\\sqrt{\\frac{1}{d}}\\]"), markdownFormulaBlocks(src))
    }

    @Test
    fun multiLineDollarKeepsWholeBody() {
        assertEquals(listOf("\$\$x^2 + y^2\n= z^2\$\$"), markdownFormulaBlocks("\$\$\nx^2 + y^2\n= z^2\n\$\$"))
    }

    @Test
    fun singleLineFormsAreDetected() {
        assertEquals(listOf("\$\$E = mc^2\$\$"), markdownFormulaBlocks("\$\$E = mc^2\$\$"))
        assertEquals(listOf("\\[x = 1\\]"), markdownFormulaBlocks("\\[x = 1\\]"))
    }

    @Test
    fun emptyFormulasProduceNoBlock() {
        assertTrue(markdownFormulaBlocks("\$\$\n\$\$").isEmpty())
        assertTrue(markdownFormulaBlocks("\$\$").isEmpty())
        assertTrue(markdownFormulaBlocks("\$\$ \$\$").isEmpty())
        assertTrue(markdownFormulaBlocks("\\[\\]").isEmpty())
    }

    @Test
    fun unclosedDelimiterFallsBackToText() {
        // no closing line / EOF
        assertTrue(markdownFormulaBlocks("before\n\$\$\nafter").isEmpty())
        assertTrue(markdownFormulaBlocks("\$\$\nx+y\n").isEmpty())
        // a blank line means we already left the formula body
        assertTrue(markdownFormulaBlocks("\\[x=1\n\nprose\n\\]").isEmpty())
    }

    @Test
    fun strayClosingDelimiterDoesNotSwallowProseOrCode() {
        val src = "\\[\nx=1\n\nprose in between\n\n```\n\\]\ncode line\n```\n\ntail prose"
        assertTrue(markdownFormulaBlocks(src).isEmpty())
        // 散文、代码围栏、尾部内容都必须原样保留，代码块不能被破坏
        assertEquals(listOf("Para", "Para", "Code", "Para"), markdownBlockKinds(src))
    }

    @Test
    fun fencedCodeIsNeverParsedAsMath() {
        val src = "```\n\$\$x\$\$\n\\[y\\]\n```"
        assertTrue(markdownFormulaBlocks(src).isEmpty())
        assertEquals(listOf("Code"), markdownBlockKinds(src))
    }

    @Test
    fun inlineMathKeepsSourceVerbatim() {
        assertEquals(listOf("\$x\$"), markdownInlineMathRuns("对隐藏向量 \$x\$："))
        assertEquals(listOf("\$\\epsilon\$", "\$g_i\$"), markdownInlineMathRuns("\$\\epsilon\$ 和 \$g_i\$"))
    }

    @Test
    fun inlineCodeAndCurrencyAreNotMath() {
        assertTrue(markdownInlineMathRuns("`\$a\$` 和 `\$b\$`").isEmpty())
        assertTrue(markdownInlineMathRuns("花了 \$10 和 \$20 一共").isEmpty())
        assertTrue(markdownInlineMathRuns("costs \$10.50 and \$20").isEmpty())
    }

    @Test
    fun rmsNormSampleYieldsSixBlocks() {
        val src = """
            对隐藏向量 ${'$'}x${'$'}：

            \[
            \mathrm{RMS}(x)
            =
            \sqrt{\frac{1}{d}\sum_{i=1}^{d}x_i^2+\epsilon}
            \]

            然后归一化：

            \[
            y_i
            =
            \frac{x_i}{\mathrm{RMS}(x)}g_i
            \]

            \[
            y_i=\frac{x_i}{\sqrt{\frac{1}{d}\sum x_j^2+\epsilon}}g_i
            \]
        """.trimIndent()
        val blocks = markdownFormulaBlocks(src)
        assertEquals(3, blocks.size)
        // 整段公式正文都在块里，不能只剩定界符
        assertTrue(blocks[0].contains("\\sum_{i=1}^{d}x_i^2"))
        assertTrue(blocks[1].contains("\\frac{x_i}{\\mathrm{RMS}(x)}g_i"))
        // 定界符保留，可直接粘到支持数学的编辑器
        assertTrue(blocks.all { it.startsWith("\\[") && it.endsWith("\\]") })
    }

    @Test
    fun formulaBlockKeepsSurroundingMarkdownIntact() {
        val src = "## 公式\n\n\\[\nx=1\n\\]\n\n- 一项\n- 两项"
        assertEquals(listOf("Heading", "Formula", "ListItem", "ListItem"), markdownBlockKinds(src))
    }

    @Test
    fun formulasInsideReadmeStillRenderMarkdown() {
        val src = "# Title\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n\\[\nx=1\n\\]"
        assertTrue(markdownContainsTable(src))
        assertEquals(listOf("Heading", "Table", "Formula"), markdownBlockKinds(src))
        assertFalse(markdownFormulaBlocks(src).isEmpty())
    }
}
