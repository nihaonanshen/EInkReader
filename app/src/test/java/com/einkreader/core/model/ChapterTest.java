package com.einkreader.core.model;

import org.junit.Test;

import static com.google.common.truth.Truth.assertThat;

public class ChapterTest {

    @Test
    public void constructor_setsTitleAndContent() {
        Chapter c = new Chapter("第一章 初入江湖", "正文内容");
        assertThat(c.getTitle()).isEqualTo("第一章 初入江湖");
        assertThat(c.getContent()).isEqualTo("正文内容");
    }

    @Test
    public void constructor_withLineNumbers() {
        Chapter c = new Chapter("第一章", "正文", 10, 50);
        assertThat(c.getLineStart()).isEqualTo(10);
        assertThat(c.getLineEnd()).isEqualTo(50);
    }

    @Test
    public void defaultValues() {
        Chapter c = new Chapter("", "");
        assertThat(c.getIndex()).isEqualTo(0);
        assertThat(c.getImagePaths()).isEmpty();
        assertThat(c.getParagraphTypes()).isEmpty();
    }

    @Test
    public void setIndex_getIndex() {
        Chapter c = new Chapter("", "");
        c.setIndex(5);
        assertThat(c.getIndex()).isEqualTo(5);
    }

    @Test
    public void addImagePath() {
        Chapter c = new Chapter("", "");
        c.addImagePath("images/cover.jpg");
        c.addImagePath("images/fig1.png");
        assertThat(c.getImagePaths()).containsExactly("images/cover.jpg", "images/fig1.png");
    }

    @Test
    public void addParagraphType() {
        Chapter c = new Chapter("", "");
        c.addParagraphType(Chapter.PARA_H1);
        c.addParagraphType(Chapter.PARA_NORMAL);
        assertThat(c.getParagraphTypes()).containsExactly(Chapter.PARA_H1, Chapter.PARA_NORMAL);
    }

    @Test
    public void setParagraphTypes() {
        Chapter c = new Chapter("", "");
        java.util.List<Integer> types = new java.util.ArrayList<>();
        types.add(Chapter.PARA_H2);
        types.add(Chapter.PARA_BLOCKQUOTE);
        c.setParagraphTypes(types);
        assertThat(c.getParagraphTypes()).containsExactly(Chapter.PARA_H2, Chapter.PARA_BLOCKQUOTE);
    }

    @Test
    public void paragraphConstants_defined() {
        assertThat(Chapter.PARA_NORMAL).isEqualTo(0);
        assertThat(Chapter.PARA_H1).isEqualTo(1);
        assertThat(Chapter.PARA_H2).isEqualTo(2);
        assertThat(Chapter.PARA_H3).isEqualTo(3);
        assertThat(Chapter.PARA_BLOCKQUOTE).isEqualTo(4);
        assertThat(Chapter.PARA_IMAGE).isEqualTo(5);
    }

    @Test
    public void setContent() {
        Chapter c = new Chapter("", "");
        c.setContent("new content");
        assertThat(c.getContent()).isEqualTo("new content");
    }

    @Test
    public void setTitle() {
        Chapter c = new Chapter("", "");
        c.setTitle("新标题");
        assertThat(c.getTitle()).isEqualTo("新标题");
    }
}