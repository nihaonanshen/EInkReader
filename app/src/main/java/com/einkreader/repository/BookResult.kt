package com.einkreader.repository

import com.einkreader.core.model.Chapter

class BookResult(
    @JvmField val chapters: List<Chapter>,
    @JvmField val images: Map<String, ByteArray>,
    @JvmField val bookTitle: String,
    @JvmField val fileKey: String,
    @JvmField val savedChapter: Int,
    @JvmField val savedPage: Int
) {
    fun isValid() = chapters.isNotEmpty()
}
