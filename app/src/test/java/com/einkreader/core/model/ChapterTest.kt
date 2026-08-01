package com.einkreader.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChapterTest {
    @Test
    fun constructor_setsTitleAndContent() {
        val c = Chapter("第一章 初入江湖", "正文内容")
        assertThat(c.title).isEqualTo("第一章 初入江湖")
        assertThat(c.content).isEqualTo("正文内容")
    }

    @Test
    fun constructor_withLineNumbers() {
        val c = Chapter("第一章", "正文", 10, 50)
        assertThat(c.lineStart).isEqualTo(10)
        assertThat(c.lineEnd).isEqualTo(50)
    }

    @Test
    fun defaultValues() {
        val c = Chapter("", "")
        assertThat(c.index).isEqualTo(0)
        assertThat(c.imagePaths).isEmpty()
        assertThat(c.paragraphTypes).isEmpty()
    }

    @Test
    fun setIndex_getIndex() {
        val c = Chapter("", "")
        c.index = 5
        assertThat(c.index).isEqualTo(5)
    }

    @Test
    fun addImagePath() {
        val c = Chapter("", "")
        c.addImagePath("images/cover.jpg")
        c.addImagePath("images/fig1.png")
        assertThat(c.imagePaths).containsExactly("images/cover.jpg", "images/fig1.png")
    }

    @Test
    fun addParagraphType() {
        val c = Chapter("", "")
        c.addParagraphType(Chapter.PARA_H1)
        c.addParagraphType(Chapter.PARA_NORMAL)
        assertThat(c.paragraphTypes).containsExactly(Chapter.PARA_H1, Chapter.PARA_NORMAL)
    }

    @Test
    fun setParagraphTypes() {
        val c = Chapter("", "")
        val types = ArrayList<Int>()
        types.add(Chapter.PARA_H2)
        types.add(Chapter.PARA_BLOCKQUOTE)
        c.setParagraphTypes(types)
        assertThat(c.paragraphTypes).containsExactly(Chapter.PARA_H2, Chapter.PARA_BLOCKQUOTE)
    }

    @Test
    fun paragraphConstants_defined() {
        assertThat(Chapter.PARA_NORMAL).isEqualTo(0)
        assertThat(Chapter.PARA_H1).isEqualTo(1)
        assertThat(Chapter.PARA_H2).isEqualTo(2)
        assertThat(Chapter.PARA_H3).isEqualTo(3)
        assertThat(Chapter.PARA_BLOCKQUOTE).isEqualTo(4)
        assertThat(Chapter.PARA_IMAGE).isEqualTo(5)
    }

    @Test
    fun setContent() {
        val c = Chapter("", "")
        c.content = "new content"
        assertThat(c.content).isEqualTo("new content")
    }

    @Test
    fun setTitle() {
        val c = Chapter("", "")
        c.title = "新标题"
        assertThat(c.title).isEqualTo("新标题")
    }

    @Test
    fun testChapterWithXhtmlPath() {
        val ch = Chapter("第三章", "", 0, 0)
        ch.xhtmlPath = "Text/chapter03.xhtml"
        assertThat(ch.xhtmlPath).isEqualTo("Text/chapter03.xhtml")
    }
}
