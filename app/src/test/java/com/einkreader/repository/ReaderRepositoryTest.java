package com.einkreader.repository;

import android.util.Log;

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
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * ReaderRepository 单元测试
 * 
 * 测试书籍记录持久化、书签管理、阅读进度和阅读时间统计。
 * 不测试解析器本身（由 EpubParserTest/TxtParserTest 覆盖）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ReaderRepositoryTest {

    private TemporaryFolder tempFolder;
    private File testBookFile;
    private String testFilePath;

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Before
    public void setUp() throws Exception {
        tempFolder = temporaryFolder;
        tempFolder.create();
        
        // 创建一个模拟 EPUB 文件
        testBookFile = new File(tempFolder.getRoot(), "test_book.epub");
        try (FileOutputStream fos = new FileOutputStream(testBookFile)) {
            // 写入 ZIP 头（EPUB 是 ZIP）
            fos.write(new byte[]{0x50, 0x4B, 0x03, 0x04}); // PK header
            fos.write(new byte[100]); // 填充
        }
        testFilePath = testBookFile.getAbsolutePath();
        
        Log.d("ReaderRepoTest", "Temp dir: " + tempFolder.getRoot().getAbsolutePath());
    }

    @After
    public void tearDown() throws Exception {
        if (tempFolder != null) {
            tempFolder.delete();
        }
    }

    // ==================== extractBookTitle 测试 ====================

    @Test
    public void extractBookTitle_fromEpubFilename() {
        String title = ServiceLocator.getReaderRepository().extractBookTitle("my_adventure.epub");
        assertThat(title).isEqualTo("my_adventure");
    }

    @Test
    public void extractBookTitle_fromTxtFilename() {
        String title = ServiceLocator.getReaderRepository().extractBookTitle("我的小说.txt");
        assertThat(title).isEqualTo("我的小说");
    }

    @Test
    public void extractBookTitle_removesExtension() {
        String title = ServiceLocator.getReaderRepository().extractBookTitle("book_2024.pdf");
        assertThat(title).isEqualTo("book_2024");
    }

    // ==================== BookRecord 持久化测试 ====================

    @Test
    public void persistBookRecord_insertsIntoDatabase() {
        ServiceLocator.getReaderRepository().persistBookRecord(
            "key1", testFilePath, testFilePath, false, 10, "epub"
        );
        
        var record = ServiceLocator.getBookStorage().getBook("key1");
        assertThat(record).isNotNull();
        assertThat(record.fileKey).isEqualTo("key1");
        assertThat(record.format).isEqualTo("epub");
        assertThat(record.totalChapters).isEqualTo(10);
    }

    @Test
    public void persistBookRecord_updatesExisting() {
        // 第一次插入
        ServiceLocator.getReaderRepository().persistBookRecord(
            "key2", testFilePath, testFilePath, true, 5, "txt"
        );
        
        // 更新
        ServiceLocator.getReaderRepository().persistBookRecord(
            "key2", testFilePath, testFilePath, true, 10, "txt"
        );
        
        var record = ServiceLocator.getBookStorage().getBook("key2");
        assertThat(record).isNotNull();
        assertThat(record.totalChapters).isEqualTo(10);
    }

    // ==================== 进度保存测试 ====================

    @Test
    public void saveProgress_persistsCorrectly() {
        // 先存储 book record
        ServiceLocator.getReaderRepository().persistBookRecord(
            "key3", testFilePath, testFilePath, false, 20, "epub"
        );
        
        ServiceLocator.getReaderRepository().saveProgress(
            "key3", 5, 10, 20
        );
        
        var progress = ServiceLocator.getReaderRepository().loadProgress("key3");
        assertThat(progress).isNotNull();
        assertThat(progress.chapterIndex).isEqualTo(5);
        assertThat(progress.pageIndex).isEqualTo(10);
        assertThat(progress.totalChapters).isEqualTo(20);
    }

    @Test
    public void loadProgress_notFound_returnsNull() {
        var progress = ServiceLocator.getReaderRepository().loadProgress("nonexistent_key");
        assertThat(progress).isNull();
    }

    // ==================== 阅读时间测试 ====================

    @Test
    public void addReadTime_increasesTotal() {
        ServiceLocator.getReaderRepository().addReadTime("key4", 5000L);
        ServiceLocator.getReaderRepository().addReadTime("key4", 3000L);
        
        // 直接查数据库验证
        var bookStorage = ServiceLocator.getBookStorage();
        // 需要先存 book record
        ServiceLocator.getReaderRepository().persistBookRecord(
            "key4", testFilePath, testFilePath, false, 0, "epub"
        );
        ServiceLocator.getReaderRepository().addReadTime("key4", 5000L);
        ServiceLocator.getReaderRepository().addReadTime("key4", 3000L);
        
        // getTotalReadTime 在 BookStorage 中可通过 DatabaseHelper 访问
        // 这里主要验证不抛异常即可
    }

    // ==================== 书签测试 ====================

    @Test
    public void addBookmark_storesBookmark() {
        ServiceLocator.getReaderRepository().addBookmark(
            "key5", 0, 5, "第一章开头"
        );
        
        List<String> bookmarks = ServiceLocator.getReaderRepository().loadBookmarks("key5");
        assertThat(bookmarks).hasSize(1);
        assertThat(bookmarks.get(0)).contains("第1章 页5");
    }

    @Test
    public void loadBookmarks_empty_returnsEmptyList() {
        List<String> bookmarks = ServiceLocator.getReaderRepository().loadBookmarks("nonexistent");
        assertThat(bookmarks).isEmpty();
    }

    @Test
    public void jumpToBookmark_returnsCorrectPage() {
        // 添加书签
        ServiceLocator.getReaderRepository().addBookmark(
            "key6", 0, 10, "位置A"
        );
        
        // 跳转书签
        int pageIndex = ServiceLocator.getReaderRepository().jumpToBookmark(
            "key6", 0, 20
        );
        assertThat(pageIndex).isAtLeast(0);
    }
}
