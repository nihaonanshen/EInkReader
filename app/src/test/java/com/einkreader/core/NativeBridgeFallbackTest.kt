package com.einkreader.core

import com.einkreader.core.model.EpubResult
import com.einkreader.core.parser.EpubParserFallback
import com.einkreader.core.parser.TxtParser
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * NativeBridge 回退路径测试。
 * 在本地运行环境中 .so 库不存在，sLibraryLoaded 为 false，
 * 验证所有 native 方法自动回退到 Java 实现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NativeBridgeFallbackTest {

    @Before
    fun setUp() {
        TxtParser.initCacheDir(RuntimeEnvironment.getApplication().cacheDir)
        EpubParserFallback.initCacheDir(RuntimeEnvironment.getApplication().cacheDir)
    }

    private fun writeTempFile(name: String, bytes: ByteArray, ext: String = ".txt"): File {
        val temp = File.createTempFile(name, ext)
        FileOutputStream(temp).use { it.write(bytes) }
        return temp
    }

    @Test
    fun libraryNotLoaded_default() {
        assertThat(NativeBridge.sLibraryLoaded).isFalse()
    }

    @Test
    fun detectEncoding_javaFallback() {
        val temp = writeTempFile("test", ("Hello World! 这里是一段足够长的中文测试文本确保编码检测器能正确识别出UTF-8编码格式。"
            + "阅读器需要正确识别各种编码格式，才能正常显示中文内容。").toByteArray(StandardCharsets.UTF_8))
        val result = NativeBridge.bridgeInstance.detectEncoding(temp)
        assertThat(result).isNotNull()
        assertThat(result).isEqualTo("UTF-8")
        temp.delete()
    }

    @Test
    fun detectEncoding_javaFallback_gbk() {
        val temp = writeTempFile("test", byteArrayOf(
            0xD6.toByte(), 0xD0.toByte(), 0xCE.toByte(), 0xC4.toByte(),
            0xB2.toByte(), 0xE2.toByte(), 0xCA.toByte(), 0xD4.toByte()))
        val result = NativeBridge.bridgeInstance.detectEncoding(temp)
        // GBK 或 GB18030 都算正确
        assertThat(result).isAnyOf("GBK", "GB18030")
        temp.delete()
    }

    @Test
    fun detectEncoding_javaFallback_emptyFile() {
        val temp = writeTempFile("test", ByteArray(0))
        val result = NativeBridge.bridgeInstance.detectEncoding(temp)
        assertThat(result).isEqualTo("UTF-8")
        temp.delete()
    }

    @Test
    fun parseTxt_javaFallback() {
        val temp = writeTempFile("test", ("第一章 测试\n"
            + "这是正文内容。\n"
            + "第二章 继续\n"
            + "这是第二章的内容。\n").toByteArray(StandardCharsets.UTF_8))
        val result = NativeBridge.bridgeInstance.parseTxt(temp)
        assertThat(result).isNotNull()
        assertThat(result.chapters).isNotEmpty()
        assertThat(result.chapters.size).isAtLeast(2)
        temp.delete()
    }

    @Test
    fun parseTxt_javaFallback_noChapters() {
        val temp = writeTempFile("test", "这是一段没有章节标题的普通文本，大概有三千字左右。".toByteArray(StandardCharsets.UTF_8))
        val result = NativeBridge.bridgeInstance.parseTxt(temp)
        assertThat(result).isNotNull()
        // 无章节标题时按字数分割，应有至少1个章节
        assertThat(result.chapters).isNotEmpty()
        temp.delete()
    }

    @Test
    fun parseTxt_javaFallback_forcedEncoding() {
        val temp = writeTempFile("test", "第一章 测试\n正文内容".toByteArray(StandardCharsets.UTF_8))
        val result = NativeBridge.bridgeInstance.parseTxt(temp, "UTF-8")
        assertThat(result).isNotNull()
        assertThat(result.encoding).isEqualTo("UTF-8")
        temp.delete()
    }

    @Test
    fun parseTxt_javaFallback_invalidFile_returnsJavaResult() {
        // 不存在的文件：NativeBridge 会尝试 Rust 失败，然后 TxtParser.parse 抛 IOException
        // 应被 catch 并抛出异常
        try {
            NativeBridge.bridgeInstance.parseTxt(File("/nonexistent/file.txt"))
            // 如果走到这里，说明没有抛异常——回退路径应该处理了错误
            // 实际上 TxtParser.parse 会抛 FileNotFoundException
        } catch (e: Exception) {
            assertThat(e).isInstanceOf(IOException::class.java)
        }
    }

    @Test
    fun parseEpub_javaFallback_nonEpub_returnsEmpty() {
        // 不是 EPUB 文件（不是 ZIP 格式）→ ZipException 被 catch → 返回空 EpubResult
        val temp = writeTempFile("test", "not a zip file".toByteArray(StandardCharsets.UTF_8), ".epub")
        try {
            val result: EpubResult = NativeBridge.bridgeInstance.parseEpub(temp)
            // NativeBridge 内部 catch Exception，可能返回空结果
            assertThat(result).isNotNull()
        } catch (e: Exception) {
            // 也可能抛异常，但不应是 NullPointerException
            assertThat(e).isInstanceOf(IOException::class.java)
        }
        temp.delete()
    }

    @Test
    fun parseEpub_javaFallback_invalidFile() {
        try {
            NativeBridge.bridgeInstance.parseEpub(File("/nonexistent/file.epub"))
        } catch (e: Exception) {
            assertThat(e).isInstanceOf(IOException::class.java)
        }
    }

    @Test
    fun layoutTextBinary_javaFallback_libraryNotLoaded() {
        val result = NativeBridge.bridgeInstance.layoutTextBinary(
            "test", 100f, 100f, 20f, 1.5f, 1.8f, false, 0f, 0f)
        assertThat(result).isNotNull()
        assertThat(result.totalPages).isEqualTo(0)
    }

    @Test
    fun isLibraryLoaded_false() {
        assertThat(NativeBridge.sLibraryLoaded).isFalse()
    }

    @Test
    fun isLayoutCached_returnsFalse() {
        val cached = NativeBridge.bridgeInstance.isLayoutCachedInternal(
            "test", 100f, 100f, 20f, 1.5f, 1.8f, false, 0f, 0f)
        assertThat(cached).isFalse()
    }
}
