package com.einkreader.ui.reader

import android.graphics.Bitmap

/**
 * 页位图 LRU 缓存（从 ReaderView 拆出，可单测）。
 *
 * 翻页时把整页渲染到 RGB_565 位图并缓存，后续直接 drawBitmap，
 * 将 i.MX6 软渲染的 ~30 次 drawText 降为 1 次 drawBitmap。
 * 缓存按**页数**上限（默认 3），淘汰（evict）时 recycle 被淘汰页的位图，避免内存泄漏。
 */
class PageBitmapCache(private val maxPages: Int = DEFAULT_MAX_PAGES) {

    private val cache = object : LinkedHashMap<Int, Bitmap>(maxPages, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>?): Boolean {
            if (size > maxPages) {
                eldest?.value?.let { if (!it.isRecycled) it.recycle() }
                return true
            }
            return false
        }
    }

    /** 取某页的缓存位图；不存在或已回收时返回 null */
    fun get(idx: Int): Bitmap? {
        val b = cache[idx]
        return if (b != null && !b.isRecycled) b else null
    }

    /** 存入某页位图（超过上限时 LRU 淘汰并 recycle） */
    fun put(idx: Int, bmp: Bitmap) {
        cache[idx] = bmp
    }

    /** 章节切换/排版重算（layoutVersion 递增）时清空并回收全部缓存位图 */
    fun clearAndRecycle() {
        for (b in cache.values) {
            if (!b.isRecycled) b.recycle()
        }
        cache.clear()
    }

    companion object {
        /** 默认缓存页数：RGB_565 每页约 5.2MB，3 页约 16MB（单核小屏兼顾命中率与内存） */
        const val DEFAULT_MAX_PAGES = 3
    }
}
