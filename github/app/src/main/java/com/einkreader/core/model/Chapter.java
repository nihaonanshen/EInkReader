package com.einkreader.core.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 章节数据模型
 * 每本书被解析成一个一个的 Chapter，每个 Chapter 包含标题、正文、图片
 */
public class Chapter {
    private String title;           // 章节标题，如"第一章 初入江湖"
    private String content;          // 章节正文（纯文本）
    private int lineStart;           // TXT: 起始行号
    private int lineEnd;             // TXT: 结束行号
    private int index;               // 章节序号（第几章）
    private List<String> imagePaths; // 本章包含的图片路径（EPUB用）
    /** 段落类型列表：对应 content 按 \\n 分割后的每个段落 */
    private List<Integer> paragraphTypes;

    public static final int PARA_NORMAL = 0;      // 普通正文
    public static final int PARA_H1 = 1;           // 一级标题（大号居中）
    public static final int PARA_H2 = 2;           // 二级标题
    public static final int PARA_H3 = 3;           // 三级标题
    public static final int PARA_BLOCKQUOTE = 4;   // 引用（缩进+小字）
    public static final int PARA_IMAGE = 5;        // 图片（[[IMAGE:xxx]]）

    public Chapter(String title, String content) {
        this.title = title;
        this.content = content;
        this.imagePaths = new ArrayList<String>();
        this.paragraphTypes = new ArrayList<Integer>();
    }

    public Chapter(String title, String content, int lineStart, int lineEnd) {
        this(title, content);
        this.lineStart = lineStart;
        this.lineEnd = lineEnd;
    }

    // ===== getter 和 setter =====

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public int getLineStart() { return lineStart; }
    public void setLineStart(int lineStart) { this.lineStart = lineStart; }

    public int getLineEnd() { return lineEnd; }
    public void setLineEnd(int lineEnd) { this.lineEnd = lineEnd; }

    public int getIndex() { return index; }
    public void setIndex(int index) { this.index = index; }

    public List<String> getImagePaths() { return imagePaths; }
    public void addImagePath(String path) { imagePaths.add(path); }

    public List<Integer> getParagraphTypes() { return paragraphTypes; }
    public void setParagraphTypes(List<Integer> types) { this.paragraphTypes = types; }
    public void addParagraphType(int type) { paragraphTypes.add(type); }
}

