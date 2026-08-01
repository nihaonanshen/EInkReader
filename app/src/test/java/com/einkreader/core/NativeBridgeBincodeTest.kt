package com.einkreader.core

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 测试 NativeBridge 的 bincode 二进制解析逻辑。
 *
 * 注意：这些测试不依赖 Rust 原生库（System.loadLibrary 会在测试环境中失败，
 * 但 NativeBridge 实例仍然可以用于纯 Kotlin 逻辑测试）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NativeBridgeBincodeTest {

    /** 构造一个简单的 bincode 布局结果 ByteArray（1页，1行） */
    private fun buildSimpleLayoutBinary(): ByteArray {
        val bb = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)

        // 辅助函数：写入 bincode 字符串（u64 字节长度 + UTF-8 字节）
        fun writeString(s: String) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            bb.putLong(bytes.size.toLong())
            bb.put(bytes)
        }

        // pageCount: u64 = 1
        bb.putLong(1)

        // PageData[0]
        // content: String (u64 len + bytes)
        writeString("测试页")

        // lineCount: u64 = 1 (duplicated: first is lineCount field, second is Vec len)
        bb.putLong(1)

        // Vec<LineMetric> len = 1
        bb.putLong(1)

        // LineMetric[0]
        writeString("测试行")
        bb.putFloat(10.0f)   // x
        bb.putFloat(20.0f)   // y
        bb.putFloat(100.0f)  // width
        bb.putFloat(16.0f)   // height
        bb.put(1)            // isParagraphEnd
        bb.put(0)            // isFirstInParagraph

        // totalLines: u64 = 1
        bb.putLong(1)

        // totalPages: u64 = 1
        bb.putLong(1)

        // elapsedNs: u64 = 1000
        bb.putLong(1000)

        val len = bb.position()
        val result = ByteArray(len)
        bb.flip()
        bb.get(result)
        return result
    }

    @Test
    fun testParseLayoutBinary() {
        val bridge = NativeBridge()
        val data = buildSimpleLayoutBinary()
        val result = bridge.parseLayoutBinary(data)
        assertEquals(1, result.totalPages)
        assertEquals(1, result.totalLines)
        assertEquals(1, result.pages.size)
        assertEquals(1, result.pages[0].lineCount)
        assertEquals(1, result.pages[0].lines.size)
        assertEquals("测试行", result.pages[0].lines[0].text)
        assertEquals(10.0f, result.pages[0].lines[0].x)
        assertEquals(20.0f, result.pages[0].lines[0].y)
    }

    @Test
    fun testParseLayoutBinaryEmpty() {
        val bridge = NativeBridge()
        val result = bridge.parseLayoutBinary(ByteArray(0))
        assertEquals(0, result.totalPages)
    }

    @Test
    fun testParseLayoutBinaryNull() {
        val bridge = NativeBridge()
        val result = bridge.parseLayoutBinary(null)
        assertEquals(0, result.totalPages)
    }

    @Test
    fun testParseBatchLayoutBinary() {
        val bridge = NativeBridge()
        val bb = java.nio.ByteBuffer.allocate(4096).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        fun ws(s: String) {
            val bytes = s.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            bb.putLong(bytes.size.toLong())
            bb.put(bytes)
        }
        // Vec<LayoutResult> len = 1
        bb.putLong(1)
        // LayoutResult[0]
        bb.putLong(1) // pageCount
        ws("段落1")
        bb.putLong(1) // lineCount
        bb.putLong(1) // Vec<LineMetric> len
        ws("行1")
        bb.putFloat(0.0f)
        bb.putFloat(0.0f)
        bb.putFloat(50.0f)
        bb.putFloat(16.0f)
        bb.put(1) // isParagraphEnd
        bb.put(1) // isFirstInParagraph
        bb.putLong(1) // totalLines
        bb.putLong(1) // totalPages
        bb.putLong(0) // elapsedNs
        val len = bb.position()
        val results = bridge.parseBatchLayoutBinary(java.util.Arrays.copyOf(bb.array(), len))
        assertEquals(1, results.size)
        assertEquals(1, results[0].totalPages)
        assertEquals("行1", results[0].pages[0].lines[0].text)
    }
    @Test
    fun testParseTxtBinary() {
        val bridge = NativeBridge()
        val bb = java.nio.ByteBuffer.allocate(1024).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        fun ws(s: String) {
            val bytes = s.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            bb.putLong(bytes.size.toLong())
            bb.put(bytes)
        }
        fun wb(v: Boolean) { bb.put(if (v) 1 else 0) }
        ws("测试书名")
        ws("UTF-8")
        bb.putLong(1)
        ws("第一章")
        ws("正文内容")
        wb(true); bb.putLong(0)
        wb(true); bb.putLong(5)
        wb(true); bb.putLong(0)
        val len = bb.position()
        val data = java.util.Arrays.copyOf(bb.array(), len)
        val file = java.io.File("/tmp/test.txt")
        val result = bridge.parseTxtBinary(data, file)
        assertEquals("测试书名", result.bookTitle)
        assertEquals("UTF-8", result.encoding)
        assertEquals(1, result.chapters.size)
        assertEquals("第一章", result.chapters[0].title)
    }

    @Test
    fun testParseTxtBinaryEmpty() {
        val bridge = NativeBridge()
        val result = bridge.parseTxtBinary(ByteArray(0), java.io.File("/tmp/t.txt"))
        assertNull(result.bookTitle)
        assertTrue(result.chapters.isEmpty())
    }

    @Test
    fun testParseEpubBinary() {
        val bridge = NativeBridge()
        val bb = java.nio.ByteBuffer.allocate(2048).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        fun ws(s: String) {
            val bytes = s.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            bb.putLong(bytes.size.toLong())
            bb.put(bytes)
        }
        fun wb(v: Boolean) { bb.put(if (v) 1 else 0) }
        ws("测试书籍")
        ws("作者")
        ws("UTF-8")
        bb.putLong(2)
        ws("第一章")
        wb(true); ws("第一章内容")
        bb.putLong(0)
        bb.putLong(0)
        wb(true); ws("ch01.xhtml")
        ws("第二章")
        wb(false)
        bb.putLong(1); ws("img001.png")
        bb.putLong(2); bb.putLong(1); bb.putLong(3)
        wb(false)
        bb.putLong(0)
        val len = bb.position()
        val data = java.util.Arrays.copyOf(bb.array(), len)
        val result = bridge.parseEpubBinary(data, java.io.File("/tmp/t.epub"))
        assertEquals(2, result.chapters.size)
        assertEquals("ch01.xhtml", result.chapters[0].xhtmlPath)
        assertTrue(result.chapters[1].content.isEmpty())
    }

    @Test
    fun testParseBatchLayoutBinaryMultiple() {
        val bridge = NativeBridge()
        val bb = java.nio.ByteBuffer.allocate(4096).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        fun ws(s: String) {
            val bytes = s.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            bb.putLong(bytes.size.toLong())
            bb.put(bytes)
        }
        // 直接构造两个 LayoutResult 元素
        bb.putLong(2) // Vec<LayoutResult> len = 2
        // 元素1
        bb.putLong(1)
        ws("a")
        bb.putLong(1); bb.putLong(1)
        ws("r1")
        bb.putFloat(0.0f); bb.putFloat(0.0f); bb.putFloat(50.0f); bb.putFloat(16.0f)
        bb.put(1); bb.put(1)
        bb.putLong(1); bb.putLong(1); bb.putLong(0)
        // 元素2
        bb.putLong(1)
        ws("b")
        bb.putLong(1); bb.putLong(1)
        ws("r2")
        bb.putFloat(0.0f); bb.putFloat(16.0f); bb.putFloat(60.0f); bb.putFloat(16.0f)
        bb.put(1); bb.put(1)
        bb.putLong(1); bb.putLong(1); bb.putLong(0)
        val len = bb.position()
        val results = bridge.parseBatchLayoutBinary(java.util.Arrays.copyOf(bb.array(), len))
        assertEquals(2, results.size)
        assertEquals("r1", results[0].pages[0].lines[0].text)
        assertEquals("r2", results[1].pages[0].lines[0].text)
    }
}