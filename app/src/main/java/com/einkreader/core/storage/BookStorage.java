package com.einkreader.core.storage;

import com.einkreader.core.storage.BookStorage.BookProgress;
import com.einkreader.core.storage.BookStorage.BookRecord;

import java.util.List;

/**
 * BookStorage —— 书籍与阅读进度的抽象数据访问层
 *
 * 设计目的：
 * 1. 解耦 UI 层与持久化实现（可在 SQLite / SharedPreferences / 内存 之间切换）
 * 2. 为未来可能的云端同步预留扩展点（当前实现仅本地存储）
 * 3. 兼容 Android 4.4（API 19），不依赖 Room / Jetpack
 */
public interface BookStorage {

    /**
     * 书籍信息记录
     */
    class BookRecord {
        public String fileKey;
        public String filePath;
        public String title;
        public String format;          // "txt" / "epub"
        public long fileSize;
        public long lastModified;
        public long addedAt;
        public int totalChapters;
        public long lastReadTime;
        public long totalReadMs;
    }

    /**
     * 阅读进度
     */
    class BookProgress {
        public String fileKey;
        public int chapterIndex;
        public int pageIndex;
        public int totalChapters;
        public long updatedAt;
    }

    /**
     * 初始化（在 Application 或首个 Activity 的 onCreate 中调用一次）
     */
    void initialize();

    /**
     * 新增或更新书籍记录
     */
    void upsertBook(BookRecord record);

    /**
     * 按 fileKey 查询书籍
     */
    BookRecord getBook(String fileKey);

    /**
     * 查询全部书籍（按最后阅读时间倒序）
     */
    List<BookRecord> listAllBooks();

    /**
     * 删除书籍记录
     */
    void deleteBook(String fileKey);

    /**
     * 保存阅读进度
     */
    void saveProgress(BookProgress progress);

    /**
     * 加载阅读进度
     */
    BookProgress loadProgress(String fileKey);

    /**
     * 累计阅读时长（毫秒）
     */
    void addReadTime(String fileKey, long deltaMs);

    /**
     * 获取累计阅读总时长（毫秒）
     */
    long getTotalReadTime();
}
