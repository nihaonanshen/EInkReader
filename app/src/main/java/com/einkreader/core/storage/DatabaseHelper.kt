package com.einkreader.core.storage

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
import android.util.LruCache
import com.einkreader.ui.reader.DebugLog
import java.util.ArrayList

class DatabaseHelper(
    context: Context
) : android.database.sqlite.SQLiteOpenHelper(
        context.applicationContext, "einkreader.db", null, 1
    ), BookStorage {

    companion object {
        private const val TAG = "DatabaseHelper"
        private const val KEY_FILE_KEY = "file_key"
        private const val KEY_FILE_PATH = "file_path"
        private const val KEY_TITLE = "title"
        private const val KEY_FORMAT = "format"
        private const val KEY_FILE_SIZE = "file_size"
        private const val KEY_LAST_MODIFIED = "last_modified"
        private const val KEY_ADDED_AT = "added_at"
        private const val KEY_TOTAL_CHAPTERS = "total_chapters"
        private const val KEY_LAST_READ_TIME = "last_read_time"
        private const val KEY_TOTAL_READ_MS = "total_read_ms"
        private const val KEY_CHAPTER_INDEX = "chapter_index"
        private const val KEY_PAGE_INDEX = "page_index"
        private const val KEY_UPDATED_AT = "updated_at"
    }

    private var initialized = false
    private val progressCache = LruCache<String, BookStorage.BookProgress>(64)

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN) {
            db.enableWriteAheadLogging()
        } else {
            db.execSQL("PRAGMA journal_mode=WAL")
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createBooks = """
            CREATE TABLE IF NOT EXISTS books (
                file_key TEXT PRIMARY KEY, file_path TEXT NOT NULL,
                title TEXT, format TEXT, file_size INTEGER DEFAULT 0,
                last_modified INTEGER DEFAULT 0, added_at INTEGER DEFAULT 0,
                total_chapters INTEGER DEFAULT 0, last_read_time INTEGER DEFAULT 0,
                total_read_ms INTEGER DEFAULT 0
            )
        """.trimIndent()
        val createProgress = """
            CREATE TABLE IF NOT EXISTS progress (
                file_key TEXT PRIMARY KEY, chapter_index INTEGER DEFAULT 0,
                page_index INTEGER DEFAULT 0, total_chapters INTEGER DEFAULT 0,
                updated_at INTEGER DEFAULT 0
            )
        """.trimIndent()
        db.execSQL(createBooks)
        db.execSQL(createProgress)
    }

    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
        // V1 only
    }

    override fun initialize() {
        if (initialized) return
        try {
            writableDatabase
            initialized = true
            DebugLog.log(TAG, "Database initialized")
        } catch (e: Exception) {
            DebugLog.error(TAG, "Database init failed", e)
        }
    }

    override fun upsertBook(record: BookStorage.BookRecord) {
        if (record.fileKey == null) return
        try {
            val db = writableDatabase
            val cv = android.content.ContentValues().apply {
                put(KEY_FILE_KEY, record.fileKey)
                put(KEY_FILE_PATH, record.filePath)
                put(KEY_TITLE, record.title)
                put(KEY_FORMAT, record.format)
                put(KEY_FILE_SIZE, record.fileSize)
                put(KEY_LAST_MODIFIED, record.lastModified)
                put(KEY_ADDED_AT, if (record.addedAt > 0) record.addedAt else System.currentTimeMillis())
                put(KEY_TOTAL_CHAPTERS, record.totalChapters)
                put(KEY_LAST_READ_TIME, record.lastReadTime)
                put(KEY_TOTAL_READ_MS, record.totalReadMs)
            }
            db.insertWithOnConflict("books", null, cv, CONFLICT_REPLACE)
        } catch (e: Exception) {
            DebugLog.error(TAG, "upsertBook failed", e)
        }
    }

    override fun getBook(fileKey: String): BookStorage.BookRecord? {
        if (fileKey == null) return null
        return try {
            val c = readableDatabase.query("books", null, "$KEY_FILE_KEY=?", arrayOf(fileKey), null, null, null)
            c?.use { cursor ->
                if (cursor.moveToFirst()) cursorToBookRecord(cursor) else null
            } ?: null
        } catch (e: Exception) {
            DebugLog.error(TAG, "getBook failed", e)
            null
        }
    }

    override fun listAllBooks(): List<BookStorage.BookRecord> {
        val result = ArrayList<BookStorage.BookRecord>()
        try {
            val c = readableDatabase.query(
                "books", null, null, null, null, null,
                "$KEY_LAST_READ_TIME DESC, $KEY_ADDED_AT DESC"
            )
            c?.use { cursor ->
                while (cursor.moveToNext()) result.add(cursorToBookRecord(cursor))
            }
        } catch (e: Exception) {
            DebugLog.error(TAG, "listAllBooks failed", e)
        }
        return result
    }

    override fun deleteBook(fileKey: String) {
        if (fileKey == null) return
        progressCache.remove(fileKey)
        val db = writableDatabase
        try {
            db.beginTransaction()
            db.delete("books", "$KEY_FILE_KEY=?", arrayOf(fileKey))
            db.delete("progress", "$KEY_FILE_KEY=?", arrayOf(fileKey))
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            DebugLog.log(TAG, "deleteBook failed: ${e.message}")
        } finally {
            db.endTransaction()
        }
    }

    override fun saveProgress(progress: BookStorage.BookProgress) {
        if (progress.fileKey == null) return
        progressCache.put(checkNotNull(progress.fileKey), progress)
        val db = writableDatabase
        try {
            db.beginTransaction()
            val cv = android.content.ContentValues().apply {
                put(KEY_FILE_KEY, progress.fileKey)
                put(KEY_CHAPTER_INDEX, progress.chapterIndex)
                put(KEY_PAGE_INDEX, progress.pageIndex)
                put(KEY_TOTAL_CHAPTERS, progress.totalChapters)
                put(KEY_UPDATED_AT, System.currentTimeMillis())
            }
            db.insertWithOnConflict("progress", null, cv, CONFLICT_REPLACE)
            val bcv = android.content.ContentValues()
            bcv.put(KEY_LAST_READ_TIME, System.currentTimeMillis())
            db.update("books", bcv, "$KEY_FILE_KEY=?", arrayOf(checkNotNull(progress.fileKey)))
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            DebugLog.log(TAG, "saveProgress failed: ${e.message}")
        } finally {
            db.endTransaction()
        }
    }

    override fun loadProgress(fileKey: String): BookStorage.BookProgress? {
        if (fileKey == null) return null
        progressCache.get(fileKey)?.let { return it }
        return try {
            val c = readableDatabase.query("progress", null, "$KEY_FILE_KEY=?", arrayOf(fileKey), null, null, null)
            c?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val p = BookStorage.BookProgress().apply {
                        this.fileKey = cursor.getString(cursor.getColumnIndexOrThrow(KEY_FILE_KEY))
                        chapterIndex = cursor.getInt(cursor.getColumnIndexOrThrow(KEY_CHAPTER_INDEX))
                        pageIndex = cursor.getInt(cursor.getColumnIndexOrThrow(KEY_PAGE_INDEX))
                        totalChapters = cursor.getInt(cursor.getColumnIndexOrThrow(KEY_TOTAL_CHAPTERS))
                        updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(KEY_UPDATED_AT))
                    }
                    progressCache.put(fileKey, p)
                    p
                } else null
            } ?: null
        } catch (e: Exception) {
            DebugLog.log(TAG, "loadProgress failed: ${e.message}")
            null
        }
    }

    override fun addReadTime(fileKey: String, deltaMs: Long) {
        if (fileKey == null || deltaMs <= 0L) return
        try {
            val sql = "UPDATE books SET $KEY_TOTAL_READ_MS = $KEY_TOTAL_READ_MS + ?, $KEY_LAST_READ_TIME = ? WHERE $KEY_FILE_KEY = ?"
            writableDatabase.execSQL(sql, arrayOf<Any>(deltaMs, System.currentTimeMillis(), fileKey))
        } catch (e: Exception) {
            DebugLog.log(TAG, "addReadTime failed: ${e.message}")
        }
    }

    override fun getTotalReadTime(): Long {
        val c = readableDatabase.rawQuery("SELECT SUM($KEY_TOTAL_READ_MS) AS total FROM books", null)
        return try {
            if (c != null && c.moveToFirst()) c.getLong(0) else 0L
        } finally {
            c?.close()
        }
    }

    private fun cursorToBookRecord(c: android.database.Cursor): BookStorage.BookRecord {
        return BookStorage.BookRecord().apply {
            fileKey = c.getString(c.getColumnIndexOrThrow(KEY_FILE_KEY))
            filePath = c.getString(c.getColumnIndexOrThrow(KEY_FILE_PATH))
            title = c.getString(c.getColumnIndexOrThrow(KEY_TITLE))
            format = c.getString(c.getColumnIndexOrThrow(KEY_FORMAT))
            fileSize = c.getLong(c.getColumnIndexOrThrow(KEY_FILE_SIZE))
            lastModified = c.getLong(c.getColumnIndexOrThrow(KEY_LAST_MODIFIED))
            addedAt = c.getLong(c.getColumnIndexOrThrow(KEY_ADDED_AT))
            totalChapters = c.getInt(c.getColumnIndexOrThrow(KEY_TOTAL_CHAPTERS))
            lastReadTime = c.getLong(c.getColumnIndexOrThrow(KEY_LAST_READ_TIME))
            totalReadMs = c.getLong(c.getColumnIndexOrThrow(KEY_TOTAL_READ_MS))
        }
    }
}
