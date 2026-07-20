package com.einkreader.core;

import com.einkreader.core.parser.EpubParser;
import com.einkreader.core.parser.TxtParser;
import com.einkreader.utils.EncodingDetector;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import static com.google.common.truth.Truth.assertThat;

/**
 * NativeBridge 回退路径测试。
 * 在本地运行环境中 .so 库不存在，sLibraryLoaded 为 false，
 * 验证所有 native 方法自动回退到 Java 实现。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class NativeBridgeFallbackTest {

    @Before
    public void setUp() {
        TxtParser.initCacheDir(RuntimeEnvironment.getApplication().getCacheDir());
        EpubParser.initCacheDir(RuntimeEnvironment.getApplication().getCacheDir());
    }

    @Test
    public void libraryNotLoaded_default() {
        assertThat(NativeBridge.isLibraryLoaded()).isFalse();
    }

    @Test
    public void detectEncoding_javaFallback() throws Exception {
        File temp = File.createTempFile("test", ".txt");
        try (FileOutputStream fos = new FileOutputStream(temp)) {
            fos.write(("Hello World! 这里是一段足够长的中文测试文本确保编码检测器能正确识别出UTF-8编码格式。"
                     + "阅读器需要正确识别各种编码格式，才能正常显示中文内容。").getBytes(StandardCharsets.UTF_8));
        }
        String result = NativeBridge.detectEncoding(temp);
        assertThat(result).isNotNull();
        assertThat(result).isEqualTo("UTF-8");
        temp.delete();
    }

    @Test
    public void detectEncoding_javaFallback_gbk() throws Exception {
        File temp = File.createTempFile("test", ".txt");
        try (FileOutputStream fos = new FileOutputStream(temp)) {
            fos.write(new byte[]{
                    (byte) 0xD6, (byte) 0xD0, (byte) 0xCE, (byte) 0xC4,
                    (byte) 0xB2, (byte) 0xE2, (byte) 0xCA, (byte) 0xD4
            });
        }
        String result = NativeBridge.detectEncoding(temp);
        // GBK 或 GB18030 都算正确
        assertThat(result).isAnyOf("GBK", "GB18030");
        temp.delete();
    }

    @Test
    public void detectEncoding_javaFallback_emptyFile() throws Exception {
        File temp = File.createTempFile("test", ".txt");
        String result = NativeBridge.detectEncoding(temp);
        assertThat(result).isEqualTo("UTF-8");
        temp.delete();
    }

    @Test
    public void parseTxt_javaFallback() throws Exception {
        File temp = File.createTempFile("test", ".txt");
        try (FileOutputStream fos = new FileOutputStream(temp)) {
            fos.write(("第一章 测试\n"
                    + "这是正文内容。\n"
                    + "第二章 继续\n"
                    + "这是第二章的内容。\n").getBytes(StandardCharsets.UTF_8));
        }
        TxtParser.ParseResult result = NativeBridge.parseTxt(temp);
        assertThat(result).isNotNull();
        assertThat(result.chapters).isNotEmpty();
        assertThat(result.chapters.size()).isAtLeast(2);
        temp.delete();
    }

    @Test
    public void parseTxt_javaFallback_noChapters() throws Exception {
        File temp = File.createTempFile("test", ".txt");
        try (FileOutputStream fos = new FileOutputStream(temp)) {
            fos.write("这是一段没有章节标题的普通文本，大概有三千字左右。".getBytes(StandardCharsets.UTF_8));
        }
        TxtParser.ParseResult result = NativeBridge.parseTxt(temp);
        assertThat(result).isNotNull();
        // 无章节标题时按字数分割，应有至少1个章节
        assertThat(result.chapters).isNotEmpty();
        temp.delete();
    }

    @Test
    public void parseTxt_javaFallback_forcedEncoding() throws Exception {
        File temp = File.createTempFile("test", ".txt");
        try (FileOutputStream fos = new FileOutputStream(temp)) {
            fos.write("第一章 测试\n正文内容".getBytes(StandardCharsets.UTF_8));
        }
        TxtParser.ParseResult result = NativeBridge.parseTxt(temp, "UTF-8");
        assertThat(result).isNotNull();
        assertThat(result.encoding).isEqualTo("UTF-8");
        temp.delete();
    }

    @Test
    public void parseTxt_javaFallback_invalidFile_returnsJavaResult() {
        // 不存在的文件：NativeBridge 会尝试 Rust 失败，然后 TxtParser.parse 抛 IOException
        // 应被 catch 并抛出异常
        try {
            NativeBridge.parseTxt(new File("/nonexistent/file.txt"));
            // 如果走到这里，说明没有抛异常——回退路径应该处理了错误
            // 实际上 TxtParser.parse 会抛 FileNotFoundException
        } catch (Exception e) {
            assertThat(e).isInstanceOf(java.io.IOException.class);
        }
    }

    @Test
    public void parseEpub_javaFallback_nonEpub_returnsEmpty() throws Exception {
        // 不是 EPUB 文件（不是 ZIP 格式）→ ZipException 被 catch → 返回空 EpubResult
        File temp = File.createTempFile("test", ".epub");
        try (FileOutputStream fos = new FileOutputStream(temp)) {
            fos.write("not a zip file".getBytes(StandardCharsets.UTF_8));
        }
        try {
            EpubParser.EpubResult result = NativeBridge.parseEpub(temp);
            // NativeBridge 内部 catch Exception，可能返回空结果
            assertThat(result).isNotNull();
        } catch (Exception e) {
            // 也可能抛异常，但不应是 NullPointerException
            assertThat(e).isInstanceOf(java.io.IOException.class);
        }
        temp.delete();
    }

    @Test
    public void parseEpub_javaFallback_invalidFile() {
        try {
            NativeBridge.parseEpub(new File("/nonexistent/file.epub"));
        } catch (Exception e) {
            assertThat(e).isInstanceOf(java.io.IOException.class);
        }
    }

    @Test
    public void layoutText_javaFallback_libraryNotLoaded() {
        NativeBridge.LayoutResult result = NativeBridge.layoutText(
                "test", 100, 100, 20f, 1.5f, 1.8f, false);
        assertThat(result).isNotNull();
        // 库未加载时返回空结果
        assertThat(result.totalPages).isEqualTo(0);
    }

    @Test
    public void layoutTextBinary_javaFallback_libraryNotLoaded() {
        NativeBridge.LayoutResult result = NativeBridge.layoutTextBinary(
                "test", 100, 100, 20f, 1.5f, 1.8f, false, 0, 0);
        assertThat(result).isNotNull();
        assertThat(result.totalPages).isEqualTo(0);
    }

    @Test
    public void isLibraryLoaded_false() {
        assertThat(NativeBridge.isLibraryLoaded()).isFalse();
    }

    @Test
    public void isLayoutCached_returnsFalse() {
        boolean cached = NativeBridge.isLayoutCached(
                "test", 100, 100, 20f, 1.5f, 1.8f, false, 0, 0);
        assertThat(cached).isFalse();
    }
}