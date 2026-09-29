package dev.pipilot.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Links are never opened; the text (plus a trailing copy glyph) copies the URL.
 * Bare URLs in CLI output are recognized too — including right after full-width CJK punctuation.
 */
class MarkdownLinkTest {

    private fun links(src: String) = markdownLinks(src)

    @Test
    fun markdownLinkCarriesItsUrl() {
        assertEquals(
            listOf(Triple("Pi 官网", "https://pi.dev", false)),
            links("看 [Pi 官网](https://pi.dev)"),
        )
    }

    @Test
    fun markdownLinkTextAndUrlAreSeparate() {
        val l = links("see [the PR](https://github.com/dilfi5h/pipilot/pull/1)").single()
        assertEquals("the PR", l.first)
        assertEquals("https://github.com/dilfi5h/pipilot/pull/1", l.second)
        assertEquals(false, l.third)
    }

    @Test
    fun bareUrlIsDetectedAfterFullWidthColon() {
        // 真实场景：中文冒号紧挨 URL，前一个版本的空白/括号前缀会漏掉
        assertEquals(
            listOf(Triple("https://github.com/dilfi5h/pipilot/releases", "https://github.com/dilfi5h/pipilot/releases", true)),
            links("裸链接：https://github.com/dilfi5h/pipilot/releases"),
        )
        assertEquals(
            listOf(Triple("https://example.com/a?b=1&c=2", "https://example.com/a?b=1&c=2", true)),
            links("带参数：https://example.com/a?b=1&c=2"),
        )
    }

    @Test
    fun bareUrlIsDetectedAtLineStartAndAfterSpace() {
        val url = "https://example.com/x"
        assertEquals(1, links(url).size)
        assertEquals(1, links("见 $url 结束").size)
        assertEquals(1, links("中文逗号后，$url。句号后").size)
    }

    @Test
    fun bareUrlStopsAtCjkPunctuation() {
        val l = links("看 https://example.com/x，然后继续").single()
        assertEquals("https://example.com/x", l.second)
    }

    @Test
    fun bareUrlIsNotMatchedRightAfterWordChars() {
        assertTrue(links("紧贴字母abchttps://x.dev 不该匹配").isEmpty())
    }

    @Test
    fun multipleLinksInOneLineKeepOrder() {
        val l = links("先看 [A](https://a.dev) 再看 [B](https://b.dev) 结束")
        assertEquals(listOf("A", "B"), l.map { it.first })
        assertEquals(listOf("https://a.dev", "https://b.dev"), l.map { it.second })
    }

    @Test
    fun mailtoIsDetected() {
        val l = links("[发邮件](mailto:someone@example.com)").single()
        assertEquals("mailto:someone@example.com", l.second)
    }

    @Test
    fun fileAndRelativePathsKeepTheirRawTarget() {
        // 不可点，但复制按钮照样要能拿到原始目标
        assertEquals("/root/.pi/agent/config.json", links("[配置文件](/root/.pi/agent/config.json)").single().second)
        assertEquals("../README.md", links("[README](../README.md)").single().second)
    }

    @Test
    fun javascriptSchemeIsStillCapturedButNeverRenderedAsLink() {
        // 不跳转所以没有执行敞口，但 URL 仍原样保留供复制（括号也不能被截断）
        assertEquals("javascript:alert(1)", links("[点我](javascript:alert(1))").single().second)
    }

    @Test
    fun urlContainingParensIsNotTruncated() {
        val url = "https://en.wikipedia.org/wiki/RMSNorm"
        assertEquals(url, links("[wiki]($url)").single().second)
        val paren = "https://en.wikipedia.org/wiki/Function_(mathematics)"
        assertEquals(paren, links("[函数]($paren)").single().second)
    }

    @Test
    fun urlsInsideInlineCodeAreNotLinks() {
        assertTrue(links("`https://a.b` 和 [x](https://y.z)").let { it.size == 1 && it[0].second == "https://y.z" })
        assertTrue(links("`https://a.b`").isEmpty())
    }

    @Test
    fun urlsInsideLatexAreNotLinks() {
        assertTrue(links("公式 \$x\$ 和 [Pi](https://pi.dev)").let { it.size == 1 })
        assertTrue(links("\$\\frac{https://a}{b}\$").isEmpty())
    }

    @Test
    fun emptyUrlIsNotALink() {
        assertTrue(links("[空]()").isEmpty())
    }

    @Test
    fun plainTextHasNoLinks() {
        assertTrue(links("没有任何链接的一段话").isEmpty())
    }
}
