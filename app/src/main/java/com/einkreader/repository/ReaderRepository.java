package com.einkreader.repository;

import com.einkreader.core.model.Chapter;
import com.einkreader.core.storage.BookStorage;

import java.util.List;
import java.util.Map;

/**
 * 书籍阅读存储库 —— 从 ReaderActivity 提取的核心业务逻辑接口
 *
 * 职责：
 * 1. 书籍加载（解析 + 缓存 + 持久化记录）
 * 2. 阅读进度保存与恢复
 * 3. 书签管理
 */
public interface ReaderRepository {

    /**
     * 加载书籍解析结果
     */
    BookResult loadBook(String filePath, String fileUri);

    /**
     * 保存阅读进度
     */
    void saveProgress(String fileKey, int chapterIndex, int pageIndex, int totalChapters);

    /**
     * 加载阅读进度
     */
    BookStorage.BookProgress loadProgress(String fileKey);

    /**
     * 添加书签
     */
    void addBookmark(String fileKey, int chapterIndex, int pageIndex, String chapterTitle);

    /**
     * 加载书签列表
     */
    List<String> loadBookmarks(String fileKey);

    /**
     * 跳转到指定书签（返回章节索引）
     */
    int jumpToBookmark(String fileKey, int bookmarkIndex, int totalChapters);

    /**
     * 持久化书籍记录
     */
    void persistBookRecord(String fileKey, String filePath, String fileUri,
                           boolean isContent, int totalChapters, String format);

    /**
     * 累计阅读时长
     */
    void addReadTime(String fileKey, long deltaMs);

    /**
     * 获取显示用的书籍标题（从文件名提取）
     */
    String extractBookTitle(String filePath);
}