package com.einkreader.ui.reader

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PageBitmapCache（fix4 从 ReaderView 抽出的页位图 LRU 缓存）单测 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PageBitmapCacheTest {

    private fun newBitmap() = Bitmap.createBitmap(4, 4, Bitmap.Config.RGB_565)

    @Test
    fun putAndGet_roundtrip() {
        val cache = PageBitmapCache(2)
        val bmp = newBitmap()
        cache.put(1, bmp)
        assertSame(bmp, cache.get(1))
        assertNull(cache.get(2))
    }

    @Test
    fun get_recycledBitmapReturnsNull() {
        val cache = PageBitmapCache(2)
        val bmp = newBitmap()
        cache.put(1, bmp)
        bmp.recycle()
        assertNull(cache.get(1))
    }

    @Test
    fun lruEviction_recyclesEldestBitmap() {
        val cache = PageBitmapCache(2)
        val b1 = newBitmap()
        val b2 = newBitmap()
        val b3 = newBitmap()
        cache.put(1, b1)
        cache.put(2, b2)
        cache.put(3, b3) // 淘汰最旧的 b1（get 未再访问 1）
        assertTrue(b1.isRecycled)
        assertNotNull(cache.get(2))
        assertNotNull(cache.get(3))
        assertNull(cache.get(1))
    }

    @Test
    fun clearAndRecycle_recyclesAllAndEmpties() {
        val cache = PageBitmapCache(2)
        val b1 = newBitmap()
        val b2 = newBitmap()
        cache.put(1, b1)
        cache.put(2, b2)
        cache.clearAndRecycle()
        assertTrue(b1.isRecycled)
        assertTrue(b2.isRecycled)
        assertNull(cache.get(1))
        assertNull(cache.get(2))
    }

    @Test
    fun defaultMaxPagesIs3() {
        val cache = PageBitmapCache() // DEFAULT_MAX_PAGES = 3
        val bitmaps = List(4) { newBitmap() }
        for (i in bitmaps.indices) cache.put(i, bitmaps[i])
        // 第 4 页入缓存时最旧的 0 被淘汰并回收
        assertTrue(bitmaps[0].isRecycled)
        assertNotNull(cache.get(1))
        assertNotNull(cache.get(3))
    }
}
