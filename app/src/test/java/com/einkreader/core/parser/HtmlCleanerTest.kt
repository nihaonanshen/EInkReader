package com.einkreader.core.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** HtmlCleaner（fix5 收敛后的 HTML→纯文本清洗）单测 */
class HtmlCleanerTest {

    @Test
    fun extractText_stripsStyleAndScript() {
        val html = "<html><head><style>body{}</style><script>x()</script></head><body><p>正文</p></body></html>"
        val out = HtmlCleaner.extractText(html)
        assertTrue(out.contains("正文"))
        assertTrue(!out.contains("body{}"))
        assertTrue(!out.contains("x()"))
    }

    @Test
    fun extractText_preservesOneBlankLineForRunOfNewlines() {
        // 段落提取路径：连续 3+ 个换行收敛为恰好一个空行（保留段落感）
        val out = HtmlCleaner.extractText("a\n\n\nb")
        assertEquals("a\n\nb", out)
    }

    @Test
    fun cleanForLayout_collapsesAllBlankLines() {
        // 布局路径：所有连续空行压平（空行在排版引擎里会多占整行高度）
        val out = HtmlCleaner.cleanForLayout("a\n\n\nb")
        assertEquals("a\nb", out)
    }

    @Test
    fun cleanForLayout_crlfNormalized() {
        val out = HtmlCleaner.cleanForLayout("a\r\n\r\nb")
        assertEquals("a\nb", out)
    }

    @Test
    fun clean_replacesEntitiesWithSpace() {
        val out = HtmlCleaner.cleanForLayout("<p>a &amp; b</p>")
        assertTrue(out.contains("a") && out.contains("b"))
    }

    @Test
    fun clean_tagsBecomeNewlines() {
        val out = HtmlCleaner.cleanForLayout("<h1>标题</h1><p>内容</p>")
        assertEquals("标题\n内容", out)
    }

    @Test
    fun clean_emptyInput() {
        assertEquals("", HtmlCleaner.cleanForLayout(""))
        assertEquals("", HtmlCleaner.extractText("   "))
    }
}
