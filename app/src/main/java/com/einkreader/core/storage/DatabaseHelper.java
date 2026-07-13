package com.einkreader.core.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Build;

import com.einkreader.ui.reader.DebugLog;
import android.util.LruCache;

import java.util.ArrayList;
import java.util.List;

/**
 * DatabaseHelper —— 轻量级 SQLite 存储实现
 *
 * 设计要点：
 * 1. 继承 SQLiteOpenHelper，兼容 Android 4.4（API 19），无需 Room
 * 2. 两张表：books（书籍元信息）、progress（阅读进度与统计）
 * 3. 使用 fileKey（文件名+大小+修改时间的指纹）作为主键，避免重复入库
 * 4. 所有方法均为线程安全的同步实现（单机阅读无需高并发）
 */
public class DatabaseHelper extends SQLiteOpenHelper implements BookStorage {

    private static final String TAG = "DatabaseHelper";

    private static final String DB_NAME = "einkreader.db";
    private static final int DB_VERSION = 1;

    private static final String TABLE_BOOKS = "books";
    private static final String TABLE_PROGRESS = "progress";

    private static final String KEY_FILE_KEY = "file_key";
    private static final String KEY_FILE_PATH = "file_path";
    private static final String KEY_TITLE = "title";
    private static final String KEY_FORMAT = "format";
    private static final String KEY_FILE_SIZE = "file_size";
    private static final String KEY_LAST_MODIFIED = "last_modified";
    private static final String KEY_ADDED_AT = "added_at";
    private static final String KEY_TOTAL_CHAPTERS = "total_chapters";
    private static final String KEY_LAST_READ_TIME = "last_read_time";
    private static final String KEY_TOTAL_READ_MS = "total_read_ms";

    private static final String KEY_CHAPTER_INDEX = "chapter_index";
    private static final String KEY_PAGE_INDEX = "page_index";
    private static final String KEY_UPDATED_AT = "updated_at";

    private Context context;
    private volatile boolean initialized = false;
    private final LruCache<String, BookProgress> progressCache = new LruCache<>(64);

    public DatabaseHelper(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
        this.context = context.getApplicationContext();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createBooks = "CREATE TABLE IF NOT EXISTS " + TABLE_BOOKS + " ("
                + KEY_FILE_KEY + " TEXT PRIMARY KEY,"
                + KEY_FILE_PATH + " TEXT NOT NULL,"
                + KEY_TITLE + " TEXT,"
                + KEY_FORMAT + " TEXT,"
                + KEY_FILE_SIZE + " INTEGER DEFAULT 0,"
                + KEY_LAST_MODIFIED + " INTEGER DEFAULT 0,"
                + KEY_ADDED_AT + " INTEGER DEFAULT 0,"
                + KEY_TOTAL_CHAPTERS + " INTEGER DEFAULT 0,"
                + KEY_LAST_READ_TIME + " INTEGER DEFAULT 0,"
                + KEY_TOTAL_READ_MS + " INTEGER DEFAULT 0"
                + ")";

        String createProgress = "CREATE TABLE IF NOT EXISTS " + TABLE_PROGRESS + " ("
                + KEY_FILE_KEY + " TEXT PRIMARY KEY,"
                + KEY_CHAPTER_INDEX + " INTEGER DEFAULT 0,"
                + KEY_PAGE_INDEX + " INTEGER DEFAULT 0,"
                + KEY_TOTAL_CHAPTERS + " INTEGER DEFAULT 0,"
                + KEY_UPDATED_AT + " INTEGER DEFAULT 0"
                + ")";

        db.execSQL(createBooks);
        db.execSQL(createProgress);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 1) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_BOOKS + " ("
                    + KEY_FILE_KEY + " TEXT PRIMARY KEY,"
                    + KEY_FILE_PATH + " TEXT NOT NULL,"
                    + KEY_TITLE + " TEXT,"
                    + KEY_FORMAT + " TEXT,"
                    + KEY_FILE_SIZE + " INTEGER DEFAULT 0,"
                    + KEY_LAST_MODIFIED + " INTEGER DEFAULT 0,"
                    + KEY_ADDED_AT + " INTEGER DEFAULT 0,"
                    + KEY_TOTAL_CHAPTERS + " INTEGER DEFAULT 0,"
                    + KEY_LAST_READ_TIME + " INTEGER DEFAULT 0,"
                    + KEY_TOTAL_READ_MS + " INTEGER DEFAULT 0"
                    + ")");
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_PROGRESS + " ("
                    + KEY_FILE_KEY + " TEXT PRIMARY KEY,"
                    + KEY_CHAPTER_INDEX + " INTEGER DEFAULT 0,"
                    + KEY_PAGE_INDEX + " INTEGER DEFAULT 0,"
                    + KEY_TOTAL_CHAPTERS + " INTEGER DEFAULT 0,"
                    + KEY_UPDATED_AT + " INTEGER DEFAULT 0"
                    + ")");
        }
        // 未来升级在此处按 oldVersion 分段处理
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            db.enableWriteAheadLogging();
        } else {
            db.execSQL("PRAGMA journal_mode=WAL");
        }
    }

    @Override
    public void initialize() {
        if (initialized) return;
        try {
            getWritableDatabase(); // 触发 onCreate 或 onUpgrade
            initialized = true;
            DebugLog.log(TAG, "Database initialized");
        } catch (Exception e) {
            DebugLog.log(TAG, "Database init failed: " + e.getMessage());
        }
    }

    // ==================== BookStorage 实现 ====================

    @Override
    public synchronized void upsertBook(BookRecord record) {
        if (record == null || record.fileKey == null) return;
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put(KEY_FILE_KEY, record.fileKey);
            cv.put(KEY_FILE_PATH, record.filePath);
            cv.put(KEY_TITLE, record.title);
            cv.put(KEY_FORMAT, record.format);
            cv.put(KEY_FILE_SIZE, record.fileSize);
            cv.put(KEY_LAST_MODIFIED, record.lastModified);
            cv.put(KEY_ADDED_AT, record.addedAt > 0 ? record.addedAt : System.currentTimeMillis());
            cv.put(KEY_TOTAL_CHAPTERS, record.totalChapters);
            cv.put(KEY_LAST_READ_TIME, record.lastReadTime);
            cv.put(KEY_TOTAL_READ_MS, record.totalReadMs);
            db.insertWithOnConflict(TABLE_BOOKS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception e) {
            DebugLog.log(TAG, "upsertBook failed: " + e.getMessage());
        }
    }

    @Override
    public synchronized BookRecord getBook(String fileKey) {
        if (fileKey == null) return null;
        Cursor c = null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            c = db.query(TABLE_BOOKS, null, KEY_FILE_KEY + "=?", new String[]{fileKey}, null, null, null);
            if (c != null && c.moveToFirst()) {
                return cursorToBookRecord(c);
            }
        } catch (Exception e) {
            DebugLog.log(TAG, "getBook failed: " + e.getMessage());
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    @Override
    public synchronized List<BookRecord> listAllBooks() {
        List<BookRecord> result = new ArrayList<BookRecord>();
        Cursor c = null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            c = db.query(TABLE_BOOKS, null, null, null, null, null,
                    KEY_LAST_READ_TIME + " DESC, " + KEY_ADDED_AT + " DESC");
            if (c != null && c.moveToFirst()) {
                do {
                    result.add(cursorToBookRecord(c));
                } while (c.moveToNext());
            }
        } catch (Exception e) {
            DebugLog.log(TAG, "listAllBooks failed: " + e.getMessage());
        } finally {
            if (c != null) c.close();
        }
        return result;
    }

    @Override
    public synchronized void deleteBook(String fileKey) {
        if (fileKey == null) return;
        progressCache.remove(fileKey);
        SQLiteDatabase db = getWritableDatabase();
        try {
            db.beginTransaction();
            db.delete(TABLE_BOOKS, KEY_FILE_KEY + "=?", new String[]{fileKey});
            db.delete(TABLE_PROGRESS, KEY_FILE_KEY + "=?", new String[]{fileKey});
            db.setTransactionSuccessful();
        } catch (Exception e) {
            DebugLog.log(TAG, "deleteBook failed: " + e.getMessage());
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public synchronized void saveProgress(BookProgress progress) {
        if (progress == null || progress.fileKey == null) return;
        progressCache.put(progress.fileKey, progress);
        SQLiteDatabase db = getWritableDatabase();
        try {
            db.beginTransaction();
            ContentValues cv = new ContentValues();
            cv.put(KEY_FILE_KEY, progress.fileKey);
            cv.put(KEY_CHAPTER_INDEX, progress.chapterIndex);
            cv.put(KEY_PAGE_INDEX, progress.pageIndex);
            cv.put(KEY_TOTAL_CHAPTERS, progress.totalChapters);
            cv.put(KEY_UPDATED_AT, System.currentTimeMillis());
            db.insertWithOnConflict(TABLE_PROGRESS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);

            // 同步更新 books 表的最近阅读时间
            ContentValues bcv = new ContentValues();
            bcv.put(KEY_LAST_READ_TIME, System.currentTimeMillis());
            db.update(TABLE_BOOKS, bcv, KEY_FILE_KEY + "=?", new String[]{progress.fileKey});
            db.setTransactionSuccessful();
        } catch (Exception e) {
            DebugLog.log(TAG, "saveProgress failed: " + e.getMessage());
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public synchronized BookProgress loadProgress(String fileKey) {
        if (fileKey == null) return null;
        BookProgress cached = progressCache.get(fileKey);
        if (cached != null) return cached;
        Cursor c = null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            c = db.query(TABLE_PROGRESS, null, KEY_FILE_KEY + "=?", new String[]{fileKey}, null, null, null);
            if (c != null && c.moveToFirst()) {
                BookProgress p = new BookProgress();
                p.fileKey = c.getString(c.getColumnIndex(KEY_FILE_KEY));
                p.chapterIndex = c.getInt(c.getColumnIndex(KEY_CHAPTER_INDEX));
                p.pageIndex = c.getInt(c.getColumnIndex(KEY_PAGE_INDEX));
                p.totalChapters = c.getInt(c.getColumnIndex(KEY_TOTAL_CHAPTERS));
                p.updatedAt = c.getLong(c.getColumnIndex(KEY_UPDATED_AT));
                progressCache.put(fileKey, p);
                return p;
            }
        } catch (Exception e) {
            DebugLog.log(TAG, "loadProgress failed: " + e.getMessage());
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    @Override
    public synchronized void addReadTime(String fileKey, long deltaMs) {
        if (fileKey == null || deltaMs <= 0) return;
        try {
            SQLiteDatabase db = getWritableDatabase();
            // SQLite 不支持原生累加，用 SQL 表达式
            db.execSQL("UPDATE " + TABLE_BOOKS + " SET " + KEY_TOTAL_READ_MS
                    + " = " + KEY_TOTAL_READ_MS + " + ?," + KEY_LAST_READ_TIME + " = ? WHERE "
                    + KEY_FILE_KEY + " = ?",
                    new Object[]{deltaMs, System.currentTimeMillis(), fileKey});
        } catch (Exception e) {
            DebugLog.log(TAG, "addReadTime failed: " + e.getMessage());
        }
    }

    @Override
    public synchronized long getTotalReadTime() {
        Cursor c = null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            c = db.rawQuery("SELECT SUM(" + KEY_TOTAL_READ_MS + ") AS total FROM " + TABLE_BOOKS, null);
            if (c != null && c.moveToFirst()) {
                return c.getLong(0);
            }
        } catch (Exception e) {
            DebugLog.log(TAG, "getTotalReadTime failed: " + e.getMessage());
        } finally {
            if (c != null) c.close();
        }
        return 0L;
    }

    // ==================== 内部工具 ====================

    private BookRecord cursorToBookRecord(Cursor c) {
        BookRecord r = new BookRecord();
        r.fileKey = c.getString(c.getColumnIndex(KEY_FILE_KEY));
        r.filePath = c.getString(c.getColumnIndex(KEY_FILE_PATH));
        r.title = c.getString(c.getColumnIndex(KEY_TITLE));
        r.format = c.getString(c.getColumnIndex(KEY_FORMAT));
        r.fileSize = c.getLong(c.getColumnIndex(KEY_FILE_SIZE));
        r.lastModified = c.getLong(c.getColumnIndex(KEY_LAST_MODIFIED));
        r.addedAt = c.getLong(c.getColumnIndex(KEY_ADDED_AT));
        r.totalChapters = c.getInt(c.getColumnIndex(KEY_TOTAL_CHAPTERS));
        r.lastReadTime = c.getLong(c.getColumnIndex(KEY_LAST_READ_TIME));
        r.totalReadMs = c.getLong(c.getColumnIndex(KEY_TOTAL_READ_MS));
        return r;
    }
}
