package com.einkreader.core.storage

interface BookStorage {
    class BookRecord {
        @JvmField var fileKey: String? = null
        @JvmField var filePath: String? = null
        @JvmField var title: String? = null
        @JvmField var format: String? = null
        @JvmField var fileSize: Long = 0
        @JvmField var lastModified: Long = 0
        @JvmField var addedAt: Long = 0
        @JvmField var totalChapters: Int = 0
        @JvmField var lastReadTime: Long = 0
        @JvmField var totalReadMs: Long = 0
    }

    class BookProgress {
        @JvmField var fileKey: String? = null
        @JvmField var chapterIndex: Int = 0
        @JvmField var pageIndex: Int = 0
        @JvmField var totalChapters: Int = 0
        @JvmField var updatedAt: Long = 0
    }

    fun initialize()
    fun upsertBook(record: BookRecord)
    fun getBook(fileKey: String): BookRecord?
    fun listAllBooks(): List<BookRecord>
    fun deleteBook(fileKey: String)
    fun saveProgress(progress: BookProgress)
    fun loadProgress(fileKey: String): BookProgress?
    fun addReadTime(fileKey: String, deltaMs: Long)
    fun getTotalReadTime(): Long

    // [P1-#25] 书签 DB 存储（自 repo 层 legacy bookmarks_ prefs 迁入）
    fun addBookmark(bm: BookBookmark)
    fun listBookmarks(fileKey: String): List<BookBookmark>
    fun deleteBookmark(fileKey: String, chapterIndex: Int, pageIndex: Int)

    class BookBookmark {
        @JvmField var fileKey: String = ""
        @JvmField var chapterIndex: Int = 0
        @JvmField var pageIndex: Int = 0
        @JvmField var chapterTitle: String = ""
        @JvmField var createdAt: Long = 0L
    }
}
