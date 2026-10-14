package com.einkreader.ui.reader

import com.einkreader.core.model.Chapter
import com.einkreader.core.model.TocItem
import org.junit.Assert.*
import org.junit.Test

/** TocIndexMapper（fix4 从 ReaderActivity 抽出的目录映射纯逻辑）单测 */
class TocIndexMapperTest {

    private fun toc(title: String, href: String, vararg children: TocItem): TocItem =
        TocItem().apply {
            this.title = title
            this.href = href
            this.children.addAll(children)
        }

    // ========== normalizePath ==========

    @Test
    fun normalizePath_nullReturnsEmpty() {
        assertEquals("", TocIndexMapper.normalizePath(null))
    }

    @Test
    fun normalizePath_stripsAnchor() {
        assertEquals("text/ch1.xhtml", TocIndexMapper.normalizePath("Text/ch1.xhtml#sec2"))
    }

    @Test
    fun normalizePath_stripsDotPrefix() {
        assertEquals("a/b.xhtml", TocIndexMapper.normalizePath("./a/b.xhtml"))
        assertEquals("a/b.xhtml", TocIndexMapper.normalizePath("././a/b.xhtml"))
    }

    @Test
    fun normalizePath_backslashToSlash() {
        assertEquals("text/ch1.xhtml", TocIndexMapper.normalizePath("text\\ch1.xhtml"))
    }

    @Test
    fun normalizePath_lowercases() {
        assertEquals("a/b/c.xhtml", TocIndexMapper.normalizePath("A/b/c.Xhtml"))
        assertEquals("a/b/c.xhtml", TocIndexMapper.normalizePath("A/B/C.XHTML"))
    }

    @Test
    fun normalizePath_preservesDotDotSegments() {
        // 实现语义：只做 去锚点/反斜杠转正斜杠/去 ./ 前缀/小写，不折叠 ".." 段
        //（输入路径来自 ZIP 条目，上游 parse_epub 的 sanitize_zip_path 已拦截 ".." 逃逸）
        assertEquals("../x", TocIndexMapper.normalizePath("../x"))
        assertEquals("a/../x", TocIndexMapper.normalizePath("A/../X"))
    }

    // ========== flattenTitles（显示用，缩进前缀） ==========

    @Test
    fun flattenTitles_nestedUsesIndentPrefix() {
        val tree = listOf(
            toc("第一卷", "v1.xhtml",
                toc("第一章", "c1.xhtml"),
                toc("第二章", "c2.xhtml"))
        )
        val out = mutableListOf<String?>()
        TocIndexMapper.flattenTitles(tree, out)
        assertEquals(listOf<String?>("第一卷", "  第一章", "  第二章"), out)
    }

    // ========== flattenEntries（DFS 顺序） ==========

    @Test
    fun flattenEntries_dfsOrder() {
        val tree = listOf(
            toc("卷", "v.xhtml",
                toc("子1", "v/s1.xhtml"),
                toc("子2", "v/s2.xhtml",
                    toc("孙", "v/s2/g.xhtml")))
        )
        val entries = TocIndexMapper.flattenEntries(tree)
        val hrefs = entries.map { it.first }
        assertEquals(listOf("v.xhtml", "v/s1.xhtml", "v/s2.xhtml", "v/s2/g.xhtml"), hrefs)
    }

    // ========== findChapterIndex ==========

    @Test
    fun findChapterIndex_hrefMatchWithAnchor() {
        val chapters = listOf(
            Chapter("引子", "", xhtmlPath = "Text/000.html"),
            Chapter("第一章", "", xhtmlPath = "Text/ch1.xhtml")
        )
        val tocItems = listOf(
            toc("引子", "Text/000.html"),
            toc("第一章", "Text/ch1.xhtml#part2") // 带锚点，规范化后应匹配
        )
        assertEquals(1, TocIndexMapper.findChapterIndex(1, tocItems, chapters))
    }

    @Test
    fun findChapterIndex_titleFallbackWhenHrefMissing() {
        val chapters = listOf(
            Chapter("第一章", "", xhtmlPath = "Text/real1.xhtml")
        )
        val tocItems = listOf(toc("第一章", "missing/wrong.xhtml"))
        // href 匹配失败 → 按标题回退命中 idx 0
        assertEquals(0, TocIndexMapper.findChapterIndex(0, tocItems, chapters))
    }

    @Test
    fun findChapterIndex_outOfRangeFallsBackToDirectIndex() {
        val chapters = listOf(Chapter("第一章", "", xhtmlPath = "a.xhtml"))
        val tocItems = listOf(toc("第一章", "a.xhtml"))
        // 目录索引越界时保持旧行为：直接返回该索引
        assertEquals(3, TocIndexMapper.findChapterIndex(3, tocItems, chapters))
    }

    @Test
    fun findChapterIndex_emptyTocReturnsDirectIndex() {
        val chapters = listOf(Chapter("第一章", ""))
        assertEquals(2, TocIndexMapper.findChapterIndex(2, emptyList(), chapters))
    }

    @Test
    fun findChapterIndex_emptyChaptersReturnsMinusOne() {
        val tocItems = listOf(toc("第一章", "a.xhtml"))
        assertEquals(-1, TocIndexMapper.findChapterIndex(0, tocItems, emptyList()))
    }
}
