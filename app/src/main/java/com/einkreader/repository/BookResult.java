package com.einkreader.repository;

import com.einkreader.core.model.Chapter;
import com.einkreader.core.storage.BookStorage;

import java.util.List;
import java.util.Map;

/**
 * 书籍加载结果 —— 封装解析 + 元数据 + 进度信息
 */
public class BookResult {
    public final List<Chapter> chapters;
    public final Map<String, byte[]> images;
    public final String bookTitle;
    public final String fileKey;
    public final int savedChapter;
    public final int savedPage;

    public BookResult(List<Chapter> chapters, Map<String, byte[]> images,
                      String bookTitle, String fileKey,
                      int savedChapter, int savedPage) {
        this.chapters = chapters;
        this.images = images;
        this.bookTitle = bookTitle;
        this.fileKey = fileKey;
        this.savedChapter = savedChapter;
        this.savedPage = savedPage;
    }

    public boolean isValid() {
        return chapters != null && !chapters.isEmpty();
    }
}