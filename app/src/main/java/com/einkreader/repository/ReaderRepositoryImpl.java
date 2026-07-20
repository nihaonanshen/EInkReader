package com.einkreader.repository;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.einkreader.core.FeatureFlags;
import com.einkreader.core.NativeBridge;
import com.einkreader.core.model.Chapter;
import com.einkreader.core.parser.EpubParser;
import com.einkreader.core.parser.TxtParser;
import com.einkreader.core.storage.BookStorage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ReaderRepository 实现 —— 从 ReaderActivity 提取的核心业务逻辑
 *
 * 职责：
 * 1. 书籍加载（解析文件/URI + 缓存 + 持久化记录）
 * 2. 阅读进度保存与恢复
 * 3. 书签管理
 */
public class ReaderRepositoryImpl implements ReaderRepository {

    private static final String TAG = "ReaderRepository";
    private static final String PREFS_NAME = "eink_reader_prefs";
    private static final String BOOKMARKS_PREFIX = "bookmarks_";

    private final Context context;
    private final BookStorage bookStorage;
    private final SharedPreferences prefs;

    public ReaderRepositoryImpl(Context context, BookStorage bookStorage) {
        this.context = context.getApplicationContext();
        this.bookStorage = bookStorage;
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    @Override
    public BookResult loadBook(String filePath, String fileUri) {
        String fp = filePath;
        String fu = (fileUri != null) ? fileUri : filePath;
        boolean isContent = (fileUri != null && fileUri.startsWith("content://"));

        // 生成 fileKey
        String fileKey;
        if (!isContent && fp != null) {
            File f = new File(fp);
            if (f.exists()) {
                fileKey = f.getName() + "_" + f.length() + "_" + f.lastModified();
            } else {
                fileKey = String.valueOf(fp.hashCode());
            }
        } else {
            fileKey = String.valueOf(fu.hashCode());
        }

        // 解析文件
        String nl = fp != null ? fp.toLowerCase() : "";
        boolean isEpub = nl.contains(".epub") || (fu != null && fu.contains(".epub"));
        boolean isTxt = nl.contains(".txt") || (fu != null && fu.contains(".txt"));

        List<Chapter> chapters = null;
        Map<String, byte[]> images = null;
        String bookTitle = "";

        try {
            if (isEpub) {
                EpubParser.EpubResult r;
                if (isContent) {
                    File tf = copyContentToTempFile(fu, ".epub");
                    try {
                        r = (FeatureFlags.useRustEpubParser())
                                ? NativeBridge.parseEpub(tf)
                                : EpubParser.parse(tf);
                    } finally {
                        if (tf != null) tf.delete();
                    }
                } else {
                    r = (FeatureFlags.useRustEpubParser())
                            ? NativeBridge.parseEpub(new File(fp))
                            : EpubParser.parse(new File(fp));
                }
                if (r != null) {
                    chapters = r.chapters;
                    images = r.images;
                    bookTitle = r.title != null ? r.title : "";
                }
            } else if (isTxt) {
                TxtParser.ParseResult r;
                if (isContent) {
                    File tf = copyContentToTempFile(fu, ".txt");
                    try {
                        r = (FeatureFlags.useRustTxtParser())
                                ? NativeBridge.parseTxt(tf)
                                : TxtParser.parse(tf);
                    } finally {
                        if (tf != null) tf.delete();
                    }
                } else {
                    r = (FeatureFlags.useRustTxtParser())
                            ? NativeBridge.parseTxt(new File(fp))
                            : TxtParser.parse(new File(fp));
                }
                if (r != null) {
                    chapters = r.chapters;
                    bookTitle = r.bookTitle != null ? r.bookTitle : "";
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "loadBook parse failed", e);
            return new BookResult(null, null, "", fileKey, 0, 0);
        }

        // 书名后备：从文件名提取
        if (bookTitle.isEmpty() && fp != null) {
            bookTitle = extractBookTitle(fp);
        }

        if (chapters == null || chapters.isEmpty()) {
            return new BookResult(null, null, bookTitle, fileKey, 0, 0);
        }

        // 持久化书籍记录
        persistBookRecord(fileKey, fp, fu, isContent, chapters.size(),
                isEpub ? "epub" : "txt");

        // 恢复阅读进度
        int savedChapter = 0, savedPage = 0;
        BookStorage.BookProgress prog = bookStorage.loadProgress(fileKey);
        if (prog != null && prog.chapterIndex < chapters.size()) {
            savedChapter = prog.chapterIndex;
            savedPage = prog.pageIndex;
        } else {
            savedChapter = prefs.getInt("lc_" + fileKey, 0);
            savedPage = prefs.getInt("lp_" + fileKey, 0);
        }

        // 验证章节索引
        if (savedChapter < 0 || savedChapter >= chapters.size()) {
            savedChapter = 0;
            savedPage = 0;
        }

        return new BookResult(chapters, images, bookTitle, fileKey, savedChapter, savedPage);
    }

    @Override
    public void saveProgress(String fileKey, int chapterIndex, int pageIndex, int totalChapters) {
        if (fileKey == null) return;
        // SharedPreferences 备份
        prefs.edit()
                .putInt("lc_" + fileKey, chapterIndex)
                .putInt("lp_" + fileKey, pageIndex)
                .putInt("total_ch_" + fileKey, totalChapters)
                .apply();

        // 数据库持久化
        if (bookStorage != null) {
            BookStorage.BookProgress prog = new BookStorage.BookProgress();
            prog.fileKey = fileKey;
            prog.chapterIndex = chapterIndex;
            prog.pageIndex = pageIndex;
            prog.totalChapters = totalChapters;
            prog.updatedAt = System.currentTimeMillis();
            bookStorage.saveProgress(prog);
        }
    }

    @Override
    public BookStorage.BookProgress loadProgress(String fileKey) {
        if (fileKey == null) return null;
        BookStorage.BookProgress prog = bookStorage != null
                ? bookStorage.loadProgress(fileKey) : null;
        if (prog == null) {
            // 从 SharedPreferences 兜底
            int ch = prefs.getInt("lc_" + fileKey, 0);
            int pg = prefs.getInt("lp_" + fileKey, 0);
            if (ch > 0 || pg > 0) {
                prog = new BookStorage.BookProgress();
                prog.fileKey = fileKey;
                prog.chapterIndex = ch;
                prog.pageIndex = pg;
            }
        }
        return prog;
    }

    @Override
    public void addBookmark(String fileKey, int chapterIndex, int pageIndex, String chapterTitle) {
        if (fileKey == null) return;
        String key = chapterIndex + "_" + pageIndex;
        String value = chapterTitle + " P" + pageIndex;
        SharedPreferences bm = context.getSharedPreferences(BOOKMARKS_PREFIX + fileKey, Context.MODE_PRIVATE);
        bm.edit().putString(key, value).apply();
    }

    @Override
    public List<String> loadBookmarks(String fileKey) {
        List<String> list = new ArrayList<>();
        try {
            SharedPreferences bm = context.getSharedPreferences(BOOKMARKS_PREFIX + fileKey, Context.MODE_PRIVATE);
            java.util.Map<String, ?> all = bm.getAll();
            for (java.util.Map.Entry<String, ?> entry : all.entrySet()) {
                list.add(entry.getKey() + " : " + entry.getValue());
            }
        } catch (Exception e) {
            Log.e(TAG, "loadBookmarks failed", e);
        }
        return list;
    }

    @Override
    public int jumpToBookmark(String fileKey, int bookmarkIndex, int totalChapters) {
        try {
            SharedPreferences bm = context.getSharedPreferences(BOOKMARKS_PREFIX + fileKey, Context.MODE_PRIVATE);
            int i = 0;
            for (String k : bm.getAll().keySet()) {
                if (i == bookmarkIndex) {
                    String chapterKey = k.contains("_") ? k.split("_")[0] : k;
                    return Integer.parseInt(chapterKey);
                }
                i++;
            }
        } catch (Exception e) {
            Log.e(TAG, "jumpToBookmark failed", e);
        }
        return 0;
    }

    @Override
    public void persistBookRecord(String fileKey, String filePath, String fileUri,
                                  boolean isContent, int totalChapters, String format) {
        if (fileKey == null || bookStorage == null) return;
        BookStorage.BookRecord rec = new BookStorage.BookRecord();
        rec.fileKey = fileKey;
        rec.filePath = (filePath != null) ? filePath : (fileUri != null ? fileUri : "");
        rec.format = format;
        rec.totalChapters = totalChapters;
        rec.addedAt = System.currentTimeMillis();

        if (filePath != null) {
            File f = new File(filePath);
            if (f.exists()) {
                rec.fileSize = f.length();
                rec.lastModified = f.lastModified();
                String name = f.getName();
                int dot = name.lastIndexOf('.');
                rec.title = (dot > 0) ? name.substring(0, dot) : name;
            }
        }

        if (rec.title == null || rec.title.isEmpty()) {
            if (filePath != null) {
                rec.title = extractBookTitle(filePath);
            } else {
                rec.title = "Unknown";
            }
        }

        bookStorage.upsertBook(rec);
    }

    @Override
    public void addReadTime(String fileKey, long deltaMs) {
        if (fileKey == null || deltaMs <= 0 || bookStorage == null) return;
        bookStorage.addReadTime(fileKey, deltaMs);
    }

    @Override
    public String extractBookTitle(String filePath) {
        if (filePath == null) return "";
        int slash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        String name = slash >= 0 ? filePath.substring(slash + 1) : filePath;
        int dot = name.lastIndexOf('.');
        return (dot > 0) ? name.substring(0, dot) : name;
    }

    /**
     * 将 content:// URI 的输入流复制到临时文件
     */
    private File copyContentToTempFile(String uri, String suffix) {
        try {
            InputStream is = context.getContentResolver().openInputStream(android.net.Uri.parse(uri));
            if (is == null) return null;
            File tf = new File(context.getCacheDir(), "t" + System.currentTimeMillis() + suffix);
            FileOutputStream fos = new FileOutputStream(tf);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
            fos.close();
            is.close();
            return tf;
        } catch (Exception e) {
            Log.e(TAG, "copyContentToTempFile failed", e);
            return null;
        }
    }
}