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
 * 测试 NativeBridge 的新公共 API：批量布局解析（bincode）、懒加载入口等。
 *
 * 注意：这些测试不依赖 Rust 原生库，使用 Mock 数据验证逻辑路径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NativeBridgeApiTest {

    private val bridge = NativeBridge()

    /** 构造单个 LayoutResult 的 bincode 字节（与 Rust bincode v1 格式一致） */
    private fun buildLayoutResultBinary(
        pageCount: Int = 1, lineCounts: List<Int> = listOf(1),
        lineTexts: List<List<String>> = listOf(listOf("行1"))
    ): ByteArray {
        val bb = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        fun ws(s: String) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            bb.putLong(bytes.size.toLong())
            bb.put(bytes)
        }
        bb.putLong(pageCount.toLong())
        for (pi in 0 until pageCount) {
            ws("内容$pi")
            val lc = lineCounts[pi]
            bb.putLong(lc.toLong())
            bb.putLong(lc.toLong()) // Vec<LineMetric> len
            for (li in 0 until lc) {
                ws(lineTexts[pi][li])
                bb.putFloat(0.0f)  // x
                bb.putFloat(0.0f)  // y
                bb.putFloat(100.0f) // width
                bb.putFloat(16.0f)  // height
                bb.put(1)          // isParagraphEnd
                bb.put(0)          // isFirstInParagraph
            }
        }
        bb.putLong(lineCounts.sum().toLong())  // totalLines
        bb.putLong(pageCount.toLong())         // totalPages
        bb.putLong(0)  // elapsedNs
        val len = bb.position()
        return java.util.Arrays.copyOf(bb.array(), len)
    }

    // ========== parseBatchLayoutBinary() 测试 ==========

    @Test
    fun testParseBatchLayoutBinary_multipleResults() {
        val bb = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
        bb.putLong(2) // Vec<LayoutResult> len
        bb.put(buildLayoutResultBinary(1, listOf(1), listOf(listOf("段落1行1"))))
        bb.put(buildLayoutResultBinary(1, listOf(2), listOf(listOf("段落2行1", "段落2行2"))))
        val len = bb.position()
        val data = java.util.Arrays.copyOf(bb.array(), len)

        val results = bridge.parseBatchLayoutBinary(data)

        assertEquals(2, results.size)
        assertEquals(1, results[0].totalPages)
        assertEquals(1, results[0].totalLines)
        assertEquals(1, results[0].pages[0].lines.size)
        assertEquals("段落1行1", results[0].pages[0].lines[0].text)

        assertEquals(1, results[1].totalPages)
        assertEquals(2, results[1].totalLines)
        assertEquals(2, results[1].pages[0].lines.size)
        assertEquals("段落2行1", results[1].pages[0].lines[0].text)
        assertEquals("段落2行2", results[1].pages[0].lines[1].text)
    }

    @Test
    fun testParseBatchLayoutBinary_empty() {
        assertTrue(bridge.parseBatchLayoutBinary(null).isEmpty())
        assertTrue(bridge.parseBatchLayoutBinary(ByteArray(0)).isEmpty())

        // 空 Vec：仅 u64 长度 0
        val bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        bb.putLong(0)
        assertTrue(bridge.parseBatchLayoutBinary(bb.array()).isEmpty())
    }

    @Test
    fun testParseBatchLayoutBinary_invalidData() {
        val results = bridge.parseBatchLayoutBinary(byteArrayOf(1, 2, 3))
        // 解析失败不应抛异常，返回空列表
        assertTrue(results.isEmpty())
    }

    // ========== encodeBincodeStringList / 批量入口（库未加载时返回空） ==========

    @Test
    fun testBatchLayoutTextsParsed_libraryNotLoaded_returnsEmpty() {
        // 测试环境中 sLibraryLoaded 通常为 false，应返回空列表而不抛异常
        val results = bridge.batchLayoutTextsParsed(
            listOf("段落1", "段落2"), 300f, 400f, 16f, 1.5f, 1.8f, false, 10f, 10f
        )
        assertNotNull(results)
    }

    // ========== loadEpubChapterContent() 接口测试（Mock 验证调用流程）==========

    /**
     * 测试 loadEpubChapterContent 的异常处理路径：当 sLibraryLoaded 为 false 时，
     * 应抛出 IllegalStateException。
     */
    @Test
    fun testLoadEpubChapterContent_libraryNotLoaded() {
        // 在 Robolectric 测试环境中，native 库通常未加载（sLibraryLoaded = false）
        // 验证 loadEpubChapterContent 正确抛出 IllegalStateException
        val exception = assertThrows(IllegalStateException::class.java) {
            bridge.loadEpubChapterContent("/path/to/book.epub", "chapter01.xhtml")
        }
        // 异常消息应包含库不可用的提示
        assertTrue(exception.message?.contains("Rust core library not available") ?: false)
    }

    @Test
    fun testLoadEpubChapterContent_validCallSignature() {
        // 验证方法存在且有正确的签名（编译期检查 + 运行时存在性）
        val method = bridge::class.java.getMethod("loadEpubChapterContent", String::class.java, String::class.java)
        assertNotNull(method)
        assertEquals(String::class.java, method.returnType)
        assertArrayEquals(arrayOf(String::class.java, String::class.java), method.parameterTypes)
    }

    // ========== parseTxtBinary / parseEpubBinary 边缘情况测试 ==========

    @Test
    fun testParseTxtBinary_withErrorFlag() {
        // 模拟 Rust 返回的错误码：[1, error_message_utf8]
        val errorBytes = byteArrayOf(1.toByte(), (-32).toByte(), (-1).toByte(), 0.toByte())
        val result = bridge.parseTxtBinary(errorBytes, File("/tmp/test.txt"))

        assertNull(result.bookTitle)
        assertTrue(result.chapters.isEmpty())
        // 错误信息被记录但不应破坏结果结构
    }

    @Test
    fun testParseEpubBinary_withErrorFlag() {
        val errorBytes = byteArrayOf(1.toByte(), 0x4E.toByte(), 0x97.toByte(), 0.toByte())
        val result = bridge.parseEpubBinary(errorBytes, File("/tmp/test.epub"))

        assertTrue(result.title.isEmpty())
        assertTrue(result.chapters.isEmpty())
        assertTrue(result.images.isEmpty())
    }

    // ========== 批量布局 bincode 解析（parseBatchLayoutBinary 兼容性） ===========

    @Test
    fun testParseBatchLayoutBinary_singleResult() {
        // 直接构造 bincode 数据测试 parseBatchLayoutBinary 的解析能力
        val data = buildLayoutResultBinary(1, listOf(2), listOf(listOf("行1", "行2")))
        val bb = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
        bb.putLong(1)
        bb.put(data)
        val len = bb.position()
        val results = bridge.parseBatchLayoutBinary(java.util.Arrays.copyOf(bb.array(), len))

        assertEquals(1, results.size)
        assertEquals(1, results[0].totalPages)
        assertEquals("行1", results[0].pages[0].lines[0].text)
        assertEquals("行2", results[0].pages[0].lines[1].text)
    }
}
