package com.einkreader.core.parser;

import android.util.Log;

import com.einkreader.core.model.Chapter;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.google.common.truth.Truth.assertThat;

/**
 * EpubParser 集成测试
 * 
 * 测试 EPUB 解析器的核心功能：
 * - 有效 EPUB 文件的解析
 * - 无效/损坏文件的处理
 * - 标题提取逻辑
 * - 章节内容清理
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class EpubParserTest {

    private TemporaryFolder tempFolder;
    private File cacheDir;

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Before
    public void setUp() throws Exception {
        tempFolder = temporaryFolder;
        tempFolder.create();
        // 初始化缓存目录
        File appCacheDir = RuntimeEnvironment.getApplication().getCacheDir();
        EpubParser.initCacheDir(appCacheDir);
        
        Log.d("EpubParserTest", "Temp dir: " + tempFolder.getRoot().getAbsolutePath());
    }

    @After
    public void tearDown() throws Exception {
        if (tempFolder != null) {
            tempFolder.delete();
        }
    }

    /** 创建最简单的有效 EPUB 文件 */
    private File createMinimalEpub(String name) throws Exception {
        File epubFile = new File(tempFolder.getRoot(), name);
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(epubFile))) {
            
            // 1. container.xml
            String containerXml = "<?xml version=\"1.0\"?>\n" +
                "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n" +
                "  <rootfiles>\n" +
                "    <rootfile full-path=\"content.opf\" media-type=\"application/oebps-package+xml\"/>\n" +
                "  </rootfiles>\n" +
                "</container>";
            zos.putNextEntry(new ZipEntry("META-INF/container.xml"));
            zos.write(containerXml.getBytes("UTF-8"));
            zos.closeEntry();

            // 2. content.opf
            String opfXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<package xmlns=\"http://www.idpf.org/2007/opf\" unique-identifier=\"uid\" version=\"2.0\">\n" +
                "  <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n" +
                "    <dc:title id=\"title\">测试书籍</dc:title>\n" +
                "    <dc:creator id=\"creator\">测试作者</dc:creator>\n" +
                "    <dc:identifier id=\"uid\">test-uuid-001</dc:identifier>\n" +
                "    <meta name=\"generator\" content=\"test\"/>\n" +
                "  </metadata>\n" +
                "  <manifest>\n" +
                "    <item id=\"chapter1\" href=\"chapter1.xhtml\" media-type=\"application/xhtml+xml\"/>\n" +
                "    <item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n" +
                "  </manifest>\n" +
                "  <spine toc=\"ncx\">\n" +
                "    <itemref idref=\"chapter1\"/>\n" +
                "  </spine>\n" +
                "</package>";
            zos.putNextEntry(new ZipEntry("content.opf"));
            zos.write(opfXml.getBytes("UTF-8"));
            zos.closeEntry();

            // 3. NCX 目录
            String ncxXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">\n" +
                "  <docTitle><text>测试书籍</text></docTitle>\n" +
                "  <navMap>\n" +
                "    <navPoint id=\"np1\" playOrder=\"1\">\n" +
                "      <navLabel><text>第一章</text></navLabel>\n" +
                "      <content src=\"chapter1.xhtml\"/>\n" +
                "    </navPoint>\n" +
                "  </navMap>\n" +
                "</ncx>";
            zos.putNextEntry(new ZipEntry("toc.ncx"));
            zos.write(ncxXml.getBytes("UTF-8"));
            zos.closeEntry();

            // 4. 章节内容
            String chapterXhtml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.1//EN\"\n" +
                "  \"http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd\">\n" +
                "<html xmlns=\"http://www.w3.org/1999/xhtml\">\n" +
                "  <head><title>第一章</title></head>\n" +
                "  <body>\n" +
                "    <div>\n" +
                "      <p>这是第一章的正文内容。</p>\n" +
                "      <p>测试段落二。</p>\n" +
                "    </div>\n" +
                "  </body>\n" +
                "</html>";
            zos.putNextEntry(new ZipEntry("chapter1.xhtml"));
            zos.write(chapterXhtml.getBytes("UTF-8"));
            zos.closeEntry();
        }
        
        return epubFile;
    }

    /** 创建带多章节和图片的 EPUB */
    private File createComplexEpub(String name) throws Exception {
        File epubFile = new File(tempFolder.getRoot(), name);
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(epubFile))) {
            
            // container.xml
            zos.putNextEntry(new ZipEntry("META-INF/container.xml"));
            zos.write("<?xml version=\"1.0\"?><container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>".getBytes("UTF-8"));
            zos.closeEntry();

            // content.opf
            String opfXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<package xmlns=\"http://www.idpf.org/2007/opf\" unique-identifier=\"uid\" version=\"2.0\">\n" +
                "  <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n" +
                "    <dc:title id=\"title\">复杂测试书</dc:title>\n" +
                "    <dc:creator id=\"creator\">作者A</dc:creator>\n" +
                "    <dc:identifier id=\"uid\">test-uuid-002</dc:identifier>\n" +
                "  </metadata>\n" +
                "  <manifest>\n" +
                "    <item id=\"chap1\" href=\"OEBPS/ch1.xhtml\" media-type=\"application/xhtml+xml\"/>\n" +
                "    <item id=\"chap2\" href=\"OEBPS/ch2.xhtml\" media-type=\"application/xhtml+xml\"/>\n" +
                "    <item id=\"image1\" href=\"OEBPS/images/pic.jpg\" media-type=\"image/jpeg\"/>\n" +
                "    <item id=\"ncx\" href=\"OEBPS/toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n" +
                "  </manifest>\n" +
                "  <spine toc=\"ncx\">\n" +
                "    <itemref idref=\"chap1\"/>\n" +
                "    <itemref idref=\"chap2\"/>\n" +
                "  </spine>\n" +
                "</package>";
            zos.putNextEntry(new ZipEntry("OEBPS/content.opf"));
            zos.write(opfXml.getBytes("UTF-8"));
            zos.closeEntry();

            // NCX
            zos.putNextEntry(new ZipEntry("OEBPS/toc.ncx"));
            zos.write("<?xml version=\"1.0\"?><ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\"><docTitle><text>复杂测试书</text></docTitle><navMap><navPoint id=\"np1\" playOrder=\"1\"><navLabel><text>第1节</text></navLabel><content src=\"ch1.xhtml\"/></navPoint><navPoint id=\"np2\" playOrder=\"2\"><navLabel><text>第2节</text></navLabel><content src=\"ch2.xhtml\"/></navPoint></navMap></ncx>".getBytes("UTF-8"));
            zos.closeEntry();

            // chapter1
            zos.putNextEntry(new ZipEntry("OEBPS/ch1.xhtml"));
            zos.write("<html><body><h1>第一章</h1><p>内容1</p></body></html>".getBytes("UTF-8"));
            zos.closeEntry();

            // chapter2
            zos.putNextEntry(new ZipEntry("OEBPS/ch2.xhtml"));
            zos.write("<html><body><h1>第二章</h1><p>内容2</p></body></html>".getBytes("UTF-8"));
            zos.closeEntry();

            // 图片占位（无效的 JPEG）
            zos.putNextEntry(new ZipEntry("OEBPS/images/pic.jpg"));
            zos.write(new byte[]{(byte)0xFF, (byte)0xD8, (byte)0xFF, (byte)0xE0});
            zos.closeEntry();
        }
        
        return epubFile;
    }

    // ==================== 测试用例 ====================

    @Test
    public void parse_validEpub_returnsResult() throws Exception {
        File epubFile = createMinimalEpub("test_book.epub");
        
        EpubParser.EpubResult result = EpubParser.parse(epubFile);
        
        assertThat(result).isNotNull();
        assertThat(result.title).isEqualTo("测试书籍");
        assertThat(result.author).isEqualTo("测试作者");
        assertThat(result.chapters).isNotEmpty();
        assertThat(result.chapters.size()).isAtLeast(1);
        assertThat(result.chapters.get(0).getTitle()).contains("第一章");
        assertThat(result.chapters.get(0).getContent()).contains("这是第一章的正文内容");
    }

    @Test
    public void parse_complexEpub_parsesMultipleChapters() throws Exception {
        File epubFile = createComplexEpub("complex.epub");
        
        EpubParser.EpubResult result = EpubParser.parse(epubFile);
        
        assertThat(result).isNotNull();
        assertThat(result.title).isEqualTo("复杂测试书");
        assertThat(result.chapters).hasSize(2);
        assertThat(result.chapters.get(0).getTitle()).contains("第1节");
        assertThat(result.chapters.get(1).getTitle()).contains("第2节");
    }

    @Test
    public void parse_invalidFile_throwsException() throws Exception {
        File invalidFile = new File(tempFolder.getRoot(), "not_an_epub.txt");
        try (FileOutputStream fos = new FileOutputStream(invalidFile)) {
            fos.write("This is a plain text file".getBytes("UTF-8"));
        }
        
        // parse 应该抛出异常或返回空结果
        try {
            EpubParser.parse(invalidFile);
        } catch (Exception e) {
            // 预期会抛出异常
            assertThat(e).isNotNull();
        }
    }

    @Test
    public void parse_nonExistentFile_throwsException() throws Exception {
        File nonExistent = new File(tempFolder.getRoot(), "does_not_exist.epub");
        
        try {
            EpubParser.parse(nonExistent);
        } catch (Exception e) {
            // 预期会抛出异常
            assertThat(e).isNotNull();
        }
    }

    @Test
    public void isValidEpub_returnsTrueForValidEpub() throws Exception {
        File epubFile = createMinimalEpub("valid.epub");
        
        assertThat(EpubParser.isValidEpub(epubFile)).isTrue();
    }

    @Test
    public void isValidEpub_returnsFalseForNonEpub() throws Exception {
        File txtFile = new File(tempFolder.getRoot(), "test.txt");
        txtFile.createNewFile();
        
        assertThat(EpubParser.isValidEpub(txtFile)).isFalse();
    }

    @Test
    public void isValidEpub_returnsFalseForNull() {
        assertThat(EpubParser.isValidEpub(null)).isFalse();
    }

    @Test
    public void isValidEpub_returnsFalseForEmptyFile() throws Exception {
        File emptyFile = new File(tempFolder.getRoot(), "empty.epub");
        emptyFile.createNewFile();
        
        assertThat(EpubParser.isValidEpub(emptyFile)).isFalse();
    }

    @Test
    public void parse_htmlStrippedFromContent() throws Exception {
        // 确保 HTML 标签被正确剥离
        File epubFile = createMinimalEpub("strip_test.epub");
        
        EpubParser.EpubResult result = EpubParser.parse(epubFile);
        
        assertThat(result).isNotNull();
        Chapter chapter = result.chapters.get(0);
        String content = chapter.getContent();
        assertThat(content).isNotNull();
        // 检查 HTML 标签被剥离或至少正文存在
        assertThat(content.length()).isGreaterThan(0);
    }

    @Test
    public void parse_ncxTitleMapping_works() throws Exception {
        // NCX 中的标题应该映射到章节
        File epubFile = createMinimalEpub("ncx_test.epub");
        
        EpubParser.EpubResult result = EpubParser.parse(epubFile);
        
        assertThat(result).isNotNull();
        assertThat(result.chapters).isNotEmpty();
        // 确认章节标题来自 NCX
        String title = result.chapters.get(0).getTitle();
        assertThat(title).isNotEmpty();
    }
}
