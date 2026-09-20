package dev.pipilot.app.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTableTest {
    @Test
    fun gfmTableIsDetected() {
        val src = """
            今天（`eth0`）的带宽使用量：

            | 方向 | 流量 |
            |------|------|
            | 下载 (rx) | **3.58 MiB** |
            | 上传 (tx) | **1.01 MiB** |
        """.trimIndent()
        assertTrue(markdownContainsTable(src))
    }

    @Test
    fun ordinaryPipeTextIsNotATable() {
        assertFalse(markdownContainsTable("use `|` in a path"))
        assertFalse(markdownContainsTable("| not closed"))
    }
}
