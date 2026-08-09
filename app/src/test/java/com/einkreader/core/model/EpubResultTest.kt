package com.einkreader.core.model

import org.junit.Assert.*
import org.junit.Test

class EpubResultTest {
    @Test
    fun testEpubResultDefaults() {
        val result = EpubResult()
        assertEquals("", result.title)
        assertEquals("", result.author)
        assertEquals("UTF-8", result.encoding)
        assertTrue(result.chapters.isEmpty())
        assertTrue(result.images.isEmpty())
    }

    @Test
    fun testEpubResultWithChapters() {
        val result = EpubResult()
        result.title = "测试书籍"
        result.author = "作者"
        val ch1 = Chapter("第一章", "内容1")
        val ch2 = Chapter("第二章", "内容2")
        ch2.xhtmlPath = "ch02.xhtml"
        result.chapters.add(ch1)
        result.chapters.add(ch2)
        assertEquals(2, result.chapters.size)
        assertEquals("ch02.xhtml", result.chapters[1].xhtmlPath)
    }

    @Test
    fun testEpubResultWithImages() {
        val result = EpubResult()
        result.images["cover.jpg"] = ByteArray(100)
        result.images["img001.png"] = ByteArray(200)
        assertEquals(2, result.images.size)
        assertEquals(100, result.images["cover.jpg"]?.size)
    }

    @Test
    fun testEpubResultWithTocItems() {
        val result = EpubResult()
        val toc1 = TocItem()
        toc1.title = "第一章"
        toc1.href = "ch01.xhtml"
        val toc2 = TocItem()
        toc2.title = "第二章"
        toc2.href = "ch02.xhtml"
        val child = TocItem()
        child.title = "2.1 子章节"
        child.href = "ch02_1.xhtml"
        toc2.children.add(child)
        result.tocItems.add(toc1)
        result.tocItems.add(toc2)
        assertEquals(2, result.tocItems.size)
        assertEquals("第一章", result.tocItems[0].title)
        assertEquals(1, result.tocItems[1].children.size)
        assertEquals("2.1 子章节", result.tocItems[1].children[0].title)
    }
}
