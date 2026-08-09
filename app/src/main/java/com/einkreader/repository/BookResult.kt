package com.einkreader.repository

import com.einkreader.core.model.Chapter
import com.einkreader.core.model.TocItem

class BookResult(
    @JvmField val chapters: List<Chapter>,
    @JvmField val images: Map<String, ByteArray>,
    @JvmField val bookTitle: String,
    @JvmField val fileKey: String,
    @JvmField val savedChapter: Int,
    @JvmField val savedPage: Int,
    @JvmField val tocItems: List<TocItem> = emptyList()
) {
    fun isValid() = chapters.isNotEmpty()
}
