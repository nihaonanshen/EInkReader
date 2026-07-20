package com.einkreader.utils;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import static com.google.common.truth.Truth.assertThat;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class EncodingDetectorTest {

    @Test
    public void detect_utf8WithBom_returnsUtf8() throws Exception {
        File temp = File.createTempFile("test", ".txt");
        try (FileOutputStream fos = new FileOutputStream(temp)) {
            fos.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            fos.write("Hello World".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(EncodingDetector.detect(temp)).isEqualTo("UTF-8");
        temp.delete();
    }

    @Test
    public void detect_utf8WithoutBom_returnsUtf8() throws Exception {
        File temp = File.createTempFile("test", ".txt");
        try (FileOutputStream fos = new FileOutputStream(temp)) {
            fos.write("Hello World! 这里是一段足够长的中文测试文本确保编码检测器能正确识别出UTF-8编码格式".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(EncodingDetector.detect(temp)).isEqualTo("UTF-8");
        temp.delete();
    }

    @Test
    public void detect_gbk_returnsGbk() throws Exception {
        File temp = File.createTempFile("test", ".txt");
        try (FileOutputStream fos = new FileOutputStream(temp)) {
            // GBK: "中文测试"
            fos.write(new byte[]{(byte) 0xD6, (byte) 0xD0, (byte) 0xCE, (byte) 0xC4,
                                 (byte) 0xCC, (byte) 0xE5, (byte) 0xBC, (byte) 0xD2});
        }
        assertThat(EncodingDetector.detect(temp)).isEqualTo("GBK");
        temp.delete();
    }

    @Test
    public void detect_big5_returnsBig5() throws Exception {
        File temp = File.createTempFile("test", ".txt");
        try (FileOutputStream fos = new FileOutputStream(temp)) {
            // Big5: "測試"
            fos.write(new byte[]{(byte) 0xA4, (byte) 0xE8, (byte) 0xB0, (byte) 0xEA});
        }
        assertThat(EncodingDetector.detect(temp)).isEqualTo("Big5");
        temp.delete();
    }

    @Test
    public void detect_utf16le_returnsUtf16le() throws Exception {
        File temp = File.createTempFile("test", ".txt");
        try (FileOutputStream fos = new FileOutputStream(temp)) {
            fos.write(new byte[]{(byte) 0xFF, (byte) 0xFE});
            fos.write("Test".getBytes(StandardCharsets.UTF_16LE));
        }
        assertThat(EncodingDetector.detect(temp)).isEqualTo("UTF-16LE");
        temp.delete();
    }

    @Test
    public void detect_utf16be_returnsUtf16be() throws Exception {
        File temp = File.createTempFile("test", ".txt");
        try (FileOutputStream fos = new FileOutputStream(temp)) {
            fos.write(new byte[]{(byte) 0xFE, (byte) 0xFF});
            fos.write("Test".getBytes(StandardCharsets.UTF_16BE));
        }
        assertThat(EncodingDetector.detect(temp)).isEqualTo("UTF-16BE");
        temp.delete();
    }

    @Test
    public void detect_emptyFile_returnsUtf8() throws Exception {
        File temp = File.createTempFile("test", ".txt");
        assertThat(EncodingDetector.detect(temp)).isEqualTo("UTF-8");
        temp.delete();
    }

    @Test
    public void detect_nullFile_returnsUtf8() {
        assertThat(EncodingDetector.detect((File) null)).isEqualTo("UTF-8");
    }

    @Test
    public void detect_byteArray_utf8() {
        byte[] data = "这里是一段足够长的中文测试文本确保编码检测器能正确识别出UTF-8编码格式".getBytes(StandardCharsets.UTF_8);
        assertThat(EncodingDetector.detect(data, data.length)).isEqualTo("UTF-8");
    }

    @Test
    public void detect_byteArray_gbk() {
        byte[] data = new byte[]{(byte) 0xD6, (byte) 0xD0, (byte) 0xCE, (byte) 0xC4};
        assertThat(EncodingDetector.detect(data, data.length)).isEqualTo("GBK");
    }

    @Test
    public void detect_byteArray_empty_returnsUtf8() {
        assertThat(EncodingDetector.detect(new byte[0], 0)).isEqualTo("UTF-8");
    }

    @Test
    public void detect_byteArray_null_returnsUtf8() {
        assertThat(EncodingDetector.detect(null, 0)).isEqualTo("UTF-8");
    }
}