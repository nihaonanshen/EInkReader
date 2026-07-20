package com.einkreader.core.storage;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DatabaseHelperTest {

    private DatabaseHelper db;

    @Before
    public void setUp() {
        db = new DatabaseHelper(RuntimeEnvironment.getApplication());
        db.initialize();
    }

    @Test
    public void initialize_createsTables() {
        // initialize() 已在 setUp 中调用，验证无异常
        BookStorage.BookRecord r = db.getBook("nonexistent");
        assertThat(r).isNull();
    }

    @Test
    public void upsertBook_insertAndRetrieve() {
        BookStorage.BookRecord rec = new BookStorage.BookRecord();
        rec.fileKey = "test_key_1";
        rec.filePath = "/sdcard/test.txt";
        rec.title = "测试书籍";
        rec.format = "txt";
        rec.fileSize = 1024;
        rec.lastModified = 1000L;
        rec.addedAt = 2000L;
        rec.totalChapters = 10;
        rec.lastReadTime = 3000L;
        rec.totalReadMs = 5000L;

        db.upsertBook(rec);

        BookStorage.BookRecord loaded = db.getBook("test_key_1");
        assertThat(loaded).isNotNull();
        assertThat(loaded.fileKey).isEqualTo("test_key_1");
        assertThat(loaded.filePath).isEqualTo("/sdcard/test.txt");
        assertThat(loaded.title).isEqualTo("测试书籍");
        assertThat(loaded.format).isEqualTo("txt");
        assertThat(loaded.fileSize).isEqualTo(1024);
        assertThat(loaded.totalChapters).isEqualTo(10);
    }

    @Test
    public void upsertBook_updateExisting() {
        BookStorage.BookRecord rec = new BookStorage.BookRecord();
        rec.fileKey = "test_key_1";
        rec.title = "原版标题";
        rec.filePath = "/sdcard/test.txt";
        db.upsertBook(rec);

        rec.title = "更新后标题";
        db.upsertBook(rec);

        BookStorage.BookRecord loaded = db.getBook("test_key_1");
        assertThat(loaded.title).isEqualTo("更新后标题");
    }

    @Test
    public void upsertBook_nullKey_doesNothing() {
        BookStorage.BookRecord rec = new BookStorage.BookRecord();
        rec.fileKey = null;
        db.upsertBook(rec); // 不应抛异常
    }

    @Test
    public void listAllBooks_returnsInsertedBooks() {
        BookStorage.BookRecord r1 = new BookStorage.BookRecord();
        r1.fileKey = "k1"; r1.filePath = "/a.txt"; r1.title = "A";
        BookStorage.BookRecord r2 = new BookStorage.BookRecord();
        r2.fileKey = "k2"; r2.filePath = "/b.txt"; r2.title = "B";
        db.upsertBook(r1);
        db.upsertBook(r2);

        List<BookStorage.BookRecord> list = db.listAllBooks();
        assertThat(list).hasSize(2);
    }

    @Test
    public void listAllBooks_empty_returnsEmptyList() {
        List<BookStorage.BookRecord> list = db.listAllBooks();
        assertThat(list).isEmpty();
    }

    @Test
    public void deleteBook_removesBookAndProgress() {
        BookStorage.BookRecord rec = new BookStorage.BookRecord();
        rec.fileKey = "del_key"; rec.filePath = "/d.txt";
        db.upsertBook(rec);

        BookStorage.BookProgress prog = new BookStorage.BookProgress();
        prog.fileKey = "del_key";
        prog.chapterIndex = 2;
        prog.pageIndex = 5;
        db.saveProgress(prog);

        db.deleteBook("del_key");

        assertThat(db.getBook("del_key")).isNull();
        assertThat(db.loadProgress("del_key")).isNull();
    }

    @Test
    public void deleteBook_nullKey_doesNothing() {
        db.deleteBook(null); // 不应抛异常
    }

    @Test
    public void saveProgress_andLoadProgress() {
        BookStorage.BookProgress prog = new BookStorage.BookProgress();
        prog.fileKey = "prog_key";
        prog.chapterIndex = 3;
        prog.pageIndex = 15;
        prog.totalChapters = 20;
        prog.updatedAt = 1000L;

        db.saveProgress(prog);

        BookStorage.BookProgress loaded = db.loadProgress("prog_key");
        assertThat(loaded).isNotNull();
        assertThat(loaded.fileKey).isEqualTo("prog_key");
        assertThat(loaded.chapterIndex).isEqualTo(3);
        assertThat(loaded.pageIndex).isEqualTo(15);
    }

    @Test
    public void saveProgress_updatesExisting() {
        BookStorage.BookProgress p1 = new BookStorage.BookProgress();
        p1.fileKey = "upd_key"; p1.chapterIndex = 0; p1.pageIndex = 0;
        db.saveProgress(p1);

        BookStorage.BookProgress p2 = new BookStorage.BookProgress();
        p2.fileKey = "upd_key"; p2.chapterIndex = 5; p2.pageIndex = 10;
        db.saveProgress(p2);

        BookStorage.BookProgress loaded = db.loadProgress("upd_key");
        assertThat(loaded.chapterIndex).isEqualTo(5);
        assertThat(loaded.pageIndex).isEqualTo(10);
    }

    @Test
    public void loadProgress_notFound_returnsNull() {
        BookStorage.BookProgress p = db.loadProgress("nonexistent");
        assertThat(p).isNull();
    }

    @Test
    public void saveProgress_nullKey_doesNothing() {
        BookStorage.BookProgress p = new BookStorage.BookProgress();
        p.fileKey = null;
        db.saveProgress(p); // 不应抛异常
    }

    @Test
    public void saveProgress_updatesLastReadTime() {
        BookStorage.BookRecord rec = new BookStorage.BookRecord();
        rec.fileKey = "time_key"; rec.filePath = "/t.txt";
        db.upsertBook(rec);

        BookStorage.BookProgress prog = new BookStorage.BookProgress();
        prog.fileKey = "time_key"; prog.chapterIndex = 1; prog.pageIndex = 1;
        db.saveProgress(prog);

        BookStorage.BookRecord loaded = db.getBook("time_key");
        assertThat(loaded.lastReadTime).isGreaterThan(0);
    }

    @Test
    public void addReadTime_increasesTotal() {
        BookStorage.BookRecord rec = new BookStorage.BookRecord();
        rec.fileKey = "rt_key"; rec.filePath = "/r.txt";
        db.upsertBook(rec);

        db.addReadTime("rt_key", 5000L);
        db.addReadTime("rt_key", 3000L);

        BookStorage.BookRecord loaded = db.getBook("rt_key");
        assertThat(loaded.totalReadMs).isEqualTo(8000L);
    }

    @Test
    public void addReadTime_nullKey_doesNothing() {
        db.addReadTime(null, 1000L); // 不应抛异常
    }

    @Test
    public void addReadTime_zeroDelta_doesNothing() {
        BookStorage.BookRecord rec = new BookStorage.BookRecord();
        rec.fileKey = "z_key"; rec.filePath = "/z.txt";
        db.upsertBook(rec);

        db.addReadTime("z_key", 0L);
        BookStorage.BookRecord loaded = db.getBook("z_key");
        assertThat(loaded.totalReadMs).isEqualTo(0);
    }

    @Test
    public void getTotalReadTime_returnsSum() {
        BookStorage.BookRecord r1 = new BookStorage.BookRecord();
        r1.fileKey = "sum1"; r1.filePath = "/s1.txt"; r1.totalReadMs = 1000L;
        BookStorage.BookRecord r2 = new BookStorage.BookRecord();
        r2.fileKey = "sum2"; r2.filePath = "/s2.txt"; r2.totalReadMs = 2000L;
        db.upsertBook(r1);
        db.upsertBook(r2);

        assertThat(db.getTotalReadTime()).isEqualTo(3000L);
    }

    @Test
    public void getTotalReadTime_empty_returnsZero() {
        assertThat(db.getTotalReadTime()).isEqualTo(0L);
    }

    @Test
    public void deleteBook_cascadesToProgress() {
        BookStorage.BookRecord rec = new BookStorage.BookRecord();
        rec.fileKey = "cascade_key"; rec.filePath = "/c.txt";
        db.upsertBook(rec);

        BookStorage.BookProgress prog = new BookStorage.BookProgress();
        prog.fileKey = "cascade_key"; prog.chapterIndex = 1; prog.pageIndex = 1;
        db.saveProgress(prog);

        db.deleteBook("cascade_key");
        assertThat(db.loadProgress("cascade_key")).isNull();
    }

    @Test
    public void loadProgress_nullKey_returnsNull() {
        assertThat(db.loadProgress(null)).isNull();
    }

    @Test
    public void getBook_nullKey_returnsNull() {
        assertThat(db.getBook(null)).isNull();
    }
}