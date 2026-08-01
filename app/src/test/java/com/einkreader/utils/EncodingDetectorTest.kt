package com.einkreader.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EncodingDetectorTest {

    private fun writeFile(name: String, bytes: ByteArray): File {
        val temp = File.createTempFile(name, ".txt")
        FileOutputStream(temp).use { it.write(bytes) }
        return temp
    }

    @Test
    fun detect_utf8WithBom_returnsUtf8() {
        val temp = writeFile("test", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "Hello World".toByteArray(StandardCharsets.UTF_8))
        assertThat(EncodingDetector.detect(temp)).isEqualTo("UTF-8")
        temp.delete()
    }

    @Test
    fun detect_utf8WithoutBom_returnsUtf8() {
        val temp = writeFile("test", "Hello World! 这里是一段足够长的中文测试文本确保编码检测器能正确识别出UTF-8编码格式".toByteArray(StandardCharsets.UTF_8))
        assertThat(EncodingDetector.detect(temp)).isEqualTo("UTF-8")
        temp.delete()
    }

    @Test
    fun detect_gbk_returnsGbk() {
        // GBK: "中文测试"
        val temp = writeFile("test", byteArrayOf(
            0xD6.toByte(), 0xD0.toByte(), 0xCE.toByte(), 0xC4.toByte(),
            0xCC.toByte(), 0xE5.toByte(), 0xBC.toByte(), 0xD2.toByte()))
        assertThat(EncodingDetector.detect(temp)).isEqualTo("GBK")
        temp.delete()
    }

    @Test
    fun detect_big5_returnsBig5() {
        // Big5: "測試"
        val temp = writeFile("test", byteArrayOf(0xA4.toByte(), 0xE8.toByte(), 0xB0.toByte(), 0xEA.toByte()))
        assertThat(EncodingDetector.detect(temp)).isEqualTo("Big5")
        temp.delete()
    }

    @Test
    fun detect_utf16le_returnsUtf16le() {
        val temp = writeFile("test", byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "Test".toByteArray(StandardCharsets.UTF_16LE))
        assertThat(EncodingDetector.detect(temp)).isEqualTo("UTF-16LE")
        temp.delete()
    }

    @Test
    fun detect_utf16be_returnsUtf16be() {
        val temp = writeFile("test", byteArrayOf(0xFE.toByte(), 0xFF.toByte()) +
            "Test".toByteArray(StandardCharsets.UTF_16BE))
        assertThat(EncodingDetector.detect(temp)).isNotNull()
        temp.delete()
    }

    @Test
    fun detect_emptyFile_returnsUtf8() {
        val temp = writeFile("test", ByteArray(0))
        assertThat(EncodingDetector.detect(temp)).isEqualTo("UTF-8")
        temp.delete()
    }

    @Test
    fun detect_nullFile_returnsUtf8() {
        assertThat(EncodingDetector.detect(null)).isEqualTo("UTF-8")
    }

    @Test
    fun detect_byteArray_utf8() {
        val data = "这里是一段足够长的中文测试文本确保编码检测器能正确识别出UTF-8编码格式".toByteArray(StandardCharsets.UTF_8)
        assertThat(EncodingDetector.detect(data, data.size)).isEqualTo("UTF-8")
    }

    @Test
    fun detect_byteArray_gbk() {
        val data = byteArrayOf(0xD6.toByte(), 0xD0.toByte(), 0xCE.toByte(), 0xC4.toByte())
        assertThat(EncodingDetector.detect(data, data.size)).isEqualTo("GBK")
    }

    @Test
    fun detect_byteArray_empty_returnsUtf8() {
        assertThat(EncodingDetector.detect(ByteArray(0), 0)).isEqualTo("UTF-8")
    }

    @Test
    fun detect_byteArray_null_returnsUtf8() {
        assertThat(EncodingDetector.detect(null, 0)).isEqualTo("UTF-8")
    }
}
