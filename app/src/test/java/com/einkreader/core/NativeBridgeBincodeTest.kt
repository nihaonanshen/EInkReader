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
        // [P1-#35/#36] content 字段已移除：PageData 只剩 lineCount + lines
        // lineCount: u64 = 1（第一个是 lineCount 字段，第二个是 Vec<LineMetric> 长度）
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
        bb.putInt(0)         // paraType（P1-2 起 bincode LineMetric 末字段，0=正文）

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
        // [P1-#35/#36] PageData.content 已移除（不写 8 字节长度 + 字节）
        bb.putLong(1) // lineCount
        bb.putLong(1) // Vec<LineMetric> len
        ws("行1")
        bb.putFloat(0.0f)
        bb.putFloat(0.0f)
        bb.putFloat(50.0f)
        bb.putFloat(16.0f)
        bb.put(1) // isParagraphEnd
        bb.put(1) // isFirstInParagraph
        bb.putInt(0) // paraType（P1-2 起 bincode LineMetric 末字段）
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
        bb.put(0.toByte()) // [P0-#1] 状态字节：0=成功（Rust 侧统一前置）
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
        bb.put(0.toByte()) // [P0-#1] 状态字节：0=成功
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
        // [P0-#9] paraTypeCount 为 i32 序列（每元素 4 字节），旧测试误写 u64
        bb.putLong(2); bb.putInt(1); bb.putInt(3)
        wb(false)
        bb.putLong(0)
        val len = bb.position()
        val data = java.util.Arrays.copyOf(bb.array(), len)
        val result = bridge.parseEpubBinary(data, java.io.File("/tmp/t.epub"))
        assertEquals(2, result.chapters.size)
        assertEquals("ch01.xhtml", result.chapters[0].xhtmlPath)
        assertTrue(result.chapters[1].content.isEmpty())
        assertEquals(2, result.chapters[1].paragraphTypes.size)
        assertEquals(3, result.chapters[1].paragraphTypes[1])
        // 目录树字段应该为空（测试数据中没有）
        assertTrue(result.tocItems.isEmpty())
    }

    // [P1-#2] 回归：imageMap 值为原始字节（Vec<u8>：u64 长度 + 字节），
    // 不再是 base64 字符串 —— 解析出的 ByteArray 必须与写入字节逐字节一致
    @Test
    fun testParseEpubBinaryRawImageBytes() {
        val bridge = NativeBridge()
        val bb = java.nio.ByteBuffer.allocate(1024).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        fun ws(s: String) {
            val bytes = s.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            bb.putLong(bytes.size.toLong())
            bb.put(bytes)
        }
        bb.put(0.toByte()) // 状态字节：成功
        ws("测试书籍")
        ws("作者")
        ws("UTF-8")
        bb.putLong(0) // 无章节
        // imageMap: HashMap<String, Vec<u8>>，1 个条目
        bb.putLong(1)
        ws("a.png")
        bb.putLong(3) // Vec<u8> 长度
        bb.put(1); bb.put(2); bb.put(3)
        bb.putLong(0) // 无目录
        val data = java.util.Arrays.copyOf(bb.array(), bb.position())
        val result = bridge.parseEpubBinary(data, java.io.File("/tmp/t.epub"))
        assertEquals(1, result.images.size)
        val got = result.images["a.png"]
        assertNotNull(got)
        assertEquals(3, got!!.size)
        assertEquals(1, got[0].toInt())
        assertEquals(2, got[1].toInt())
        assertEquals(3, got[2].toInt())
    }

    @Test
    fun testParseEpubBinaryMultilevelToc() {
        // 验证目录树递归解析（多级 children）
        val bridge = NativeBridge()
        val bb = java.nio.ByteBuffer.allocate(2048).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        fun ws(s: String) {
            val bytes = s.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            bb.putLong(bytes.size.toLong())
            bb.put(bytes)
        }
        bb.put(0.toByte()) // [P0-#1] 状态字节：0=成功
        ws("测试书籍")
        ws("作者")
        ws("UTF-8")
        bb.putLong(0) // 无章节
        bb.putLong(0) // 无图片
        // tocItems: Vec<TocItem> 长度为 1（第一卷，含 2 个子章节）
        bb.putLong(1)
        ws("第一卷")
        ws("vol1.xhtml")
        bb.putLong(2)
        ws("第一章 开始")
        ws("ch01.xhtml")
        bb.putLong(0)
        ws("第二章 继续")
        ws("ch02.xhtml")
        bb.putLong(0)
        val len = bb.position()
        val data = java.util.Arrays.copyOf(bb.array(), len)
        val result = bridge.parseEpubBinary(data, java.io.File("/tmp/t.epub"))
        assertEquals(1, result.tocItems.size)
        assertEquals("第一卷", result.tocItems[0].title)
        assertEquals("vol1.xhtml", result.tocItems[0].href)
        assertEquals(2, result.tocItems[0].children.size)
        assertEquals("第一章 开始", result.tocItems[0].children[0].title)
        assertEquals("第二章 继续", result.tocItems[0].children[1].title)
        assertEquals(0, result.tocItems[0].children[0].children.size)
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
        // [P1-#35/#36] PageData.content 已移除
        bb.putLong(1)
        bb.putLong(1); bb.putLong(1)
        ws("r1")
        bb.putFloat(0.0f); bb.putFloat(0.0f); bb.putFloat(50.0f); bb.putFloat(16.0f)
        bb.put(1); bb.put(1)
        bb.putInt(0) // paraType
        bb.putLong(1); bb.putLong(1); bb.putLong(0)
        // 元素2
        // [P1-#35/#36] PageData.content 已移除
        bb.putLong(1)
        bb.putLong(1); bb.putLong(1)
        ws("r2")
        bb.putFloat(0.0f); bb.putFloat(16.0f); bb.putFloat(60.0f); bb.putFloat(16.0f)
        bb.put(1); bb.put(1)
        bb.putInt(0) // paraType
        bb.putLong(1); bb.putLong(1); bb.putLong(0)
        val len = bb.position()
        val results = bridge.parseBatchLayoutBinary(java.util.Arrays.copyOf(bb.array(), len))
        assertEquals(2, results.size)
        assertEquals("r1", results[0].pages[0].lines[0].text)
        assertEquals("r2", results[1].pages[0].lines[0].text)
    }

    // [P0-#1] 回归：合法 payload 首字节恰好为 1 时旧协议误判为错误，
    // 新协议下 bookTitle 单字符（bincode 字符串长度前缀 u64=1 → 首字节 0x01）必须正常解析
    @Test
    fun testParseTxtBinaryFirstPayloadByteIsOne() {
        val bridge = NativeBridge()
        val bb = java.nio.ByteBuffer.allocate(512).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        fun ws(s: String) {
            val bytes = s.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            bb.putLong(bytes.size.toLong())
            bb.put(bytes)
        }
        bb.put(0.toByte()) // 状态字节：成功
        ws("A") // → 字符串长度前缀首字节 = 1（旧协议在此误判）
        assertEquals(1, java.util.Arrays.copyOf(bb.array(), bb.position())[1].toInt())
        ws("UTF-8")
        bb.putLong(0) // 无章节
        val data = java.util.Arrays.copyOf(bb.array(), bb.position())
        val result = bridge.parseTxtBinary(data, java.io.File("/tmp/t.txt"))
        assertEquals("A", result.bookTitle)
        assertEquals("UTF-8", result.encoding)
        assertTrue(result.chapters.isEmpty())
    }

    // [P0-#12] 回归：截断字节流（伪造的巨大字符串长度前缀）不得引发 OOM / 负数分配，
    // 应被边界检查拦截并返回空结果
    @Test
    fun testParseTxtBinaryTruncatedPayload() {
        val bridge = NativeBridge()
        val bb = java.nio.ByteBuffer.allocate(64).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.put(0.toByte()) // 状态字节：成功
        bb.putLong(Long.MAX_VALUE) // 伪造字符串长度前缀
        val data = java.util.Arrays.copyOf(bb.array(), bb.position())
        val result = bridge.parseTxtBinary(data, java.io.File("/tmp/t.txt"))
        assertTrue(result.chapters.isEmpty())
        assertNull(result.bookTitle)
    }

    // [review-#46] 截断字节流：EPUB payload 中途截断，解析不得崩溃（防异常 JNI 返回长度）
    @Test
    fun testParseEpubBinaryTruncatedPayload() {
        val bridge = NativeBridge()
        val bb = java.nio.ByteBuffer.allocate(1024).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        fun ws(s: String) {
            val bytes = s.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            bb.putLong(bytes.size.toLong())
            bb.put(bytes)
        }
        bb.put(0.toByte()) // 状态字节：成功
        ws("截断书籍")
        ws("作者")
        ws("UTF-8")
        bb.putLong(2) // 声称 2 章，但 payload 将在章中部结束
        ws("第一章")
        val full = java.util.Arrays.copyOf(bb.array(), bb.position() + 96)
        val data = java.util.Arrays.copyOf(full, full.size * 3 / 4) // 截到 3/4
        val result = bridge.parseEpubBinary(data, java.io.File("/tmp/t.epub"))
        // 不抛异常；内部 try/catch 兜底为部分/空结果
        assertNotNull(result)
        assertTrue(result.chapters.size <= 2)
    }

    // [P0-#27] 并发冒烟：多线程同时读 LRU 缓存（含锁路径），无死锁/无异常
    @Test
    fun testLayoutCacheConcurrentAccess() {
        val bridge = NativeBridge()
        val threads = (1..8).map { i ->
            Thread {
                repeat(500) {
                    val k = i % 4
                    bridge.isLayoutCachedInternal(
                        "text$k", 100f + k, 200f, 16f, 1.5f, 1.8f, false, 10f, 10f, null)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() } // 锁损坏（嵌套/缺失）会在此挂起或抛异常
    }
}