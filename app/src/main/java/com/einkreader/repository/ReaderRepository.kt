package com.einkreader.repository

interface ReaderRepository {
    fun loadBook(filePath: String, fileUri: String?): BookResult
    fun saveProgress(fileKey: String, chapterIndex: Int, pageIndex: Int, totalChapters: Int)
    fun loadProgress(fileKey: String): com.einkreader.core.storage.BookStorage.BookProgress?
    fun addBookmark(fileKey: String, chapterIndex: Int, pageIndex: Int, chapterTitle: String?)
    fun loadBookmarks(fileKey: String): List<String>
    fun jumpToBookmark(fileKey: String, bookmarkIndex: Int, totalChapters: Int): Int
    fun persistBookRecord(fileKey: String, filePath: String?, fileUri: String?,
                          isContent: Boolean, totalChapters: Int, format: String?)
    fun addReadTime(fileKey: String, deltaMs: Long)
    fun extractBookTitle(filePath: String): String
}
