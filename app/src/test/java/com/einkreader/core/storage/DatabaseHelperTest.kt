package com.einkreader.core.storage

import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

import com.google.common.truth.Truth.assertThat

/**
 * DatabaseHelper 单元测试
 * 
 * ⚠️ 注意: 所有测试共享同一个 Robolectric Application，
 * 因此每个测试完成后必须清理数据以避免污染后续测试
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DatabaseHelperTest {

    private lateinit var db: DatabaseHelper

    @Before
    fun setUp() {
        db = DatabaseHelper(RuntimeEnvironment.application)
        db.initialize()
    }
    
    /** ✅ [Phase 2] 测试间数据隔离 - 清理可能污染的书籍和进度 */
    @org.junit.After
    fun tearDown() {
        val keys = arrayOf("test_key_1", "k1", "k2", "del_key", "prog_key", 
                "upd_key", "time_key", "rt_key", "z_key", "sum1", "sum2", "cascade_key", 
                "sample_book", "recent_test_book")
        for (key in keys) {
            db.deleteBook(key)
        }
    }

    @Test
    fun initialize_createsTables() {
        // initialize() 已在 setUp 中调用，验证无异常
        val r = db.getBook("nonexistent")
        assertThat(r).isNull()
    }

    @Test
    fun upsertBook_insertAndRetrieve() {
        val rec = BookStorage.BookRecord().apply {
            fileKey = "test_key_1"
            filePath = "/sdcard/test.txt"
            title = "测试书籍"
            format = "txt"
            fileSize = 1024
            lastModified = 1000L
            addedAt = 2000L
            totalChapters = 10
            lastReadTime = 3000L
            totalReadMs = 5000L
        }

        db.upsertBook(rec)

        val loaded = db.getBook("test_key_1")
        assertThat(loaded).isNotNull()
        assertThat(loaded!!.fileKey).isEqualTo("test_key_1")
        assertThat(loaded.filePath).isEqualTo("/sdcard/test.txt")
        assertThat(loaded.title).isEqualTo("测试书籍")
        assertThat(loaded.format).isEqualTo("txt")
        assertThat(loaded.fileSize).isEqualTo(1024)
        assertThat(loaded.totalChapters).isEqualTo(10)
    }

    @Test
    fun upsertBook_updateExisting() {
        val rec = BookStorage.BookRecord().apply {
            fileKey = "test_key_1"
            title = "原版标题"
            filePath = "/sdcard/test.txt"
        }
        db.upsertBook(rec)

        rec.title = "更新后标题"
        db.upsertBook(rec)

        val loaded = db.getBook("test_key_1")
        assertThat(loaded!!.title).isEqualTo("更新后标题")
    }

    @Test
    fun upsertBook_nullKey_doesNothing() {
        val rec = BookStorage.BookRecord()
        rec.fileKey = null
        db.upsertBook(rec) // 不应抛异常
    }

    @Test
    fun listAllBooks_returnsInsertedBooks() {
        val r1 = BookStorage.BookRecord().apply {
            fileKey = "k1"
            filePath = "/a.txt"
            title = "A"
        }
        val r2 = BookStorage.BookRecord().apply {
            fileKey = "k2"
            filePath = "/b.txt"
            title = "B"
        }
        db.upsertBook(r1)
        db.upsertBook(r2)

        val list = db.listAllBooks()
        assertThat(list).hasSize(2)
    }

    @Test
    fun listAllBooks_empty_returnsEmptyList() {
        val list = db.listAllBooks()
        assertThat(list).isEmpty()
    }

    @Test
    fun deleteBook_removesBookAndProgress() {
        val rec = BookStorage.BookRecord().apply {
            fileKey = "del_key"
            filePath = "/d.txt"
        }
        db.upsertBook(rec)

        val prog = BookStorage.BookProgress().apply {
            fileKey = "del_key"
            chapterIndex = 2
            pageIndex = 5
        }
        db.saveProgress(prog)

        db.deleteBook("del_key")

        assertThat(db.getBook("del_key")).isNull()
        assertThat(db.loadProgress("del_key")).isNull()
    }

    @Test
    fun saveProgress_andLoadProgress() {
        val prog = BookStorage.BookProgress().apply {
            fileKey = "prog_key"
            chapterIndex = 3
            pageIndex = 15
            totalChapters = 20
            updatedAt = 1000L
        }

        db.saveProgress(prog)

        val loaded = db.loadProgress("prog_key")
        assertThat(loaded).isNotNull()
        assertThat(loaded!!.fileKey).isEqualTo("prog_key")
        assertThat(loaded.chapterIndex).isEqualTo(3)
        assertThat(loaded.pageIndex).isEqualTo(15)
    }

    @Test
    fun saveProgress_updatesExisting() {
        val p1 = BookStorage.BookProgress().apply {
            fileKey = "upd_key"
            chapterIndex = 0
            pageIndex = 0
        }
        db.saveProgress(p1)

        val p2 = BookStorage.BookProgress().apply {
            fileKey = "upd_key"
            chapterIndex = 5
            pageIndex = 10
        }
        db.saveProgress(p2)

        val loaded = db.loadProgress("upd_key")
        assertThat(loaded!!.chapterIndex).isEqualTo(5)
        assertThat(loaded.pageIndex).isEqualTo(10)
    }

    @Test
    fun loadProgress_notFound_returnsNull() {
        val p = db.loadProgress("nonexistent")
        assertThat(p).isNull()
    }

    @Test
    fun saveProgress_nullKey_doesNothing() {
        val p = BookStorage.BookProgress()
        p.fileKey = null
        db.saveProgress(p) // 不应抛异常
    }

    @Test
    fun saveProgress_updatesLastReadTime() {
        val rec = BookStorage.BookRecord().apply {
            fileKey = "time_key"
            filePath = "/t.txt"
        }
        db.upsertBook(rec)

        val prog = BookStorage.BookProgress().apply {
            fileKey = "time_key"
            chapterIndex = 1
            pageIndex = 1
        }
        db.saveProgress(prog)

        val loaded = db.getBook("time_key")
        assertThat(loaded!!.lastReadTime).isGreaterThan(0L)
    }

    @Test
    fun addReadTime_increasesTotal() {
        val rec = BookStorage.BookRecord().apply {
            fileKey = "rt_key"
            filePath = "/r.txt"
        }
        db.upsertBook(rec)

        db.addReadTime("rt_key", 5000L)
        db.addReadTime("rt_key", 3000L)

        val loaded = db.getBook("rt_key")
        assertThat(loaded!!.totalReadMs).isEqualTo(8000L)
    }

    @Test
    fun addReadTime_zeroDelta_doesNothing() {
        val rec = BookStorage.BookRecord().apply {
            fileKey = "z_key"
            filePath = "/z.txt"
        }
        db.upsertBook(rec)

        db.addReadTime("z_key", 0L)
        val loaded = db.getBook("z_key")
        assertThat(loaded!!.totalReadMs).isEqualTo(0)
    }

    @Test
    fun getTotalReadTime_returnsSum() {
        val r1 = BookStorage.BookRecord().apply {
            fileKey = "sum1"
            filePath = "/s1.txt"
            totalReadMs = 1000L
        }
        val r2 = BookStorage.BookRecord().apply {
            fileKey = "sum2"
            filePath = "/s2.txt"
            totalReadMs = 2000L
        }
        db.upsertBook(r1)
        db.upsertBook(r2)

        assertThat(db.getTotalReadTime()).isEqualTo(3000L)
    }

    @Test
    fun getTotalReadTime_empty_returnsZero() {
        assertThat(db.getTotalReadTime()).isEqualTo(0L)
    }

    @Test
    fun deleteBook_cascadesToProgress() {
        val rec = BookStorage.BookRecord().apply {
            fileKey = "cascade_key"
            filePath = "/c.txt"
        }
        db.upsertBook(rec)

        val prog = BookStorage.BookProgress().apply {
            fileKey = "cascade_key"
            chapterIndex = 1
            pageIndex = 1
        }
        db.saveProgress(prog)

        db.deleteBook("cascade_key")
        assertThat(db.loadProgress("cascade_key")).isNull()
    }
}
