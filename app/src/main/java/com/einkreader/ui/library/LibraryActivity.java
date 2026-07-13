package com.einkreader.ui.library;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.einkreader.EInkReaderApp;
import com.einkreader.R;
import com.einkreader.core.storage.BookStorage;
import com.einkreader.ui.reader.DebugLog;
import com.einkreader.ui.settings.AboutActivity;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 书库首页 —— APP 启动后看到的第一个页面
 *
 * 显示 SD 卡上的书籍列表，支持导入、删除、排序和过滤
 */
public class LibraryActivity extends Activity {

    private static final String PREFS_NAME = "eink_reader_prefs";
    private static final String PREFS_LIBRARY_PATH = "library_path";
    private static final String PREFS_SORT_MODE = "sort_mode";

    private ListView bookList;
    private TextView btnImport, btnRefreshSettings, btnAbout;
    private TextView btnSort;
    private BookListAdapter adapter;
    private List<BookInfo> books = new ArrayList<BookInfo>();
    private boolean scanning = false;
    private int currentSortMode = 0; // 0=按时间 1=按名称 2=按格式

    // 支持的文件扩展名
    private static final String[] SUPPORTED_EXT = {".txt", ".epub"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_library);

        bookList = (ListView) findViewById(R.id.book_list);
        btnImport = (TextView) findViewById(R.id.btn_import);
        btnRefreshSettings = (TextView) findViewById(R.id.btn_refresh_settings);
        btnAbout = (TextView) findViewById(R.id.btn_about);

        TextView btnRecent = (TextView) findViewById(R.id.btn_recent);
        btnRecent.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showRecentBooks();
            }
        });

        adapter = new BookListAdapter(this, books);
        bookList.setAdapter(adapter);

        // 点击书籍打开阅读
        bookList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= books.size()) return;
                BookInfo book = books.get(position);
                openBook(book.file);
            }
        });

        // 长按书籍弹出删除菜单
        bookList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, final int position, long id) {
                if (position < 0 || position >= books.size()) return false;
                final BookInfo book = books.get(position);
                new AlertDialog.Builder(LibraryActivity.this)
                    .setTitle(book.title)
                    .setMessage("确定要删除这本书吗？")
                    .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            book.file.delete();
                            BookStorage storage = EInkReaderApp.getBookStorage();
                            if (storage != null) {
                                storage.deleteBook(book.fileKey);
                            }
                            scanBooks();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
                return true;
            }
        });

        // 导入按钮
        btnImport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(LibraryActivity.this, FilePickerActivity.class);
                startActivityForResult(intent, 1001);
            }
        });

        // 刷新设置
        btnRefreshSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(LibraryActivity.this, RefreshSettingsActivity.class);
                startActivity(intent);
            }
        });

        // 关于
        btnAbout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(LibraryActivity.this, AboutActivity.class);
                startActivity(intent);
            }
        });

        // 排序按钮
        final String[] sortLabels = {"按时间", "按名称", "按格式"};
        currentSortMode = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(PREFS_SORT_MODE, 0);
        btnSort = (TextView) findViewById(R.id.btn_sort);
        btnSort.setText(sortLabels[currentSortMode]);
        btnSort.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                currentSortMode = (currentSortMode + 1) % 3;
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putInt(PREFS_SORT_MODE, currentSortMode).apply();
                btnSort.setText(sortLabels[currentSortMode]);
                scanBooks();
            }
        });

        // 扫描书籍（后台线程避免主线程 I/O 卡顿）
        new Thread(new Runnable() {
            @Override
            public void run() {
                scanBooks();
            }
        }).start();
    }

    /**
     * 扫描 SD 卡上的书籍
     *
     * 策略：
     * 1. 先从数据库读取已知书籍（包含阅读进度元信息）
     * 2. 再扫描文件系统，把新增书籍合并进来（用 fileKey 去重）
     * 3. 对扫描路径失效（文件已被删除）的数据库记录进行清理
     * 4. try/finally 确保 scanning 锁总能释放，防异常永久锁死
     */
    private void scanBooks() {
        if (scanning) return;
        scanning = true;
        try {
            books.clear();

            // 从数据库加载已知书籍
            BookStorage storage = EInkReaderApp.getBookStorage();
            java.util.HashMap<String, BookInfo> dbMap = new java.util.HashMap<String, BookInfo>();
            if (storage != null) {
                List<BookStorage.BookRecord> dbBooks = storage.listAllBooks();
                for (BookStorage.BookRecord rec : dbBooks) {
                    File f = new File(rec.filePath);
                    if (!f.exists()) {
                        // 文件已被删除，清理数据库记录
                        storage.deleteBook(rec.fileKey);
                        DebugLog.log("Lib", "清理失效记录: " + rec.filePath);
                        continue;
                    }
                    BookInfo info = new BookInfo(f);
                    info.dbRecord = rec;
                    dbMap.put(rec.fileKey, info);
                }
            }

            // 从设置读取上次的书籍目录，没有则用默认目录
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String savedPath = prefs.getString(PREFS_LIBRARY_PATH, "");

            List<File> searchDirs = new ArrayList<File>();
            if (!savedPath.isEmpty()) {
                File savedDir = new File(savedPath);
                if (savedDir.exists() && savedDir.isDirectory()) {
                    searchDirs.add(savedDir);
                }
            }

            // 总是扫描 /sdcard 下的常见阅读目录
            File sdcard = Environment.getExternalStorageDirectory();
            searchDirs.add(sdcard);
            searchDirs.add(new File(sdcard, "Books"));
            searchDirs.add(new File(sdcard, "books"));
            searchDirs.add(new File(sdcard, "eBooks"));
            searchDirs.add(new File(sdcard, "EInkReader"));
            searchDirs.add(new File(sdcard, "Download"));

            // 扫描外置 SD 卡常见路径（Android 4.4+ 外置卡通常挂载在 /storage/ 下）
            searchDirs.add(new File("/storage/emulated/0/books"));
            searchDirs.add(new File("/storage/emulated/0/Books"));
            searchDirs.add(new File("/storage/emulated/0/epub"));
            searchDirs.add(new File("/storage/emulated/0/EInkReader"));
            searchDirs.add(new File("/mnt/sdcard/epub"));
            searchDirs.add(new File("/mnt/external_sd/epub"));
            searchDirs.add(new File("/mnt/external_sd/books"));
            searchDirs.add(new File("/mnt/external_sd/Books"));

            java.util.HashSet<String> seenPaths = new java.util.HashSet<String>();
            for (File dir : searchDirs) {
                if (dir.exists() && dir.isDirectory()) {
                    scanDir(dir, seenPaths, dbMap);
                }
            }

            books.addAll(dbMap.values());

            // 按当前排序模式排序
            if (currentSortMode == 1) {
                java.util.Collections.sort(books, new Comparator<BookInfo>() {
                    @Override public int compare(BookInfo a, BookInfo b) {
                        return a.title.compareToIgnoreCase(b.title);
                    }
                });
            } else if (currentSortMode == 2) {
                java.util.Collections.sort(books, new Comparator<BookInfo>() {
                    @Override public int compare(BookInfo a, BookInfo b) {
                        String extA = a.file.getName().toLowerCase();
                        String extB = b.file.getName().toLowerCase();
                        int cmp = extA.compareTo(extB);
                        if (cmp != 0) return cmp;
                        return a.title.compareToIgnoreCase(b.title);
                    }
                });
            } else {
                // 按时间：最近阅读的在前
                java.util.Collections.sort(books, new Comparator<BookInfo>() {
                    @Override public int compare(BookInfo a, BookInfo b) {
                        long ta = a.dbRecord != null ? a.dbRecord.lastReadTime : a.file.lastModified();
                        long tb = b.dbRecord != null ? b.dbRecord.lastReadTime : b.file.lastModified();
                        return Long.compare(tb, ta);
                    }
                });
            }

            DebugLog.log("Lib", "扫描完成: " + books.size() + "本书 排序模式=" + currentSortMode);
        } finally {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (adapter != null) adapter.notifyDataSetChanged();
                }
            });
            scanning = false;
        }
    }

    /**
     * 扫描单个目录下的书籍（递归扫描子目录），结果合并到 dbMap（已存在的不覆盖）
     */
    private static final int MAX_SCAN_DEPTH = 4;

    private void scanDir(File dir, java.util.HashSet<String> seenPaths,
                         java.util.HashMap<String, BookInfo> dbMap) {
        scanDir(dir, seenPaths, dbMap, 0);
    }

    private void scanDir(File dir, java.util.HashSet<String> seenPaths,
                         java.util.HashMap<String, BookInfo> dbMap, int depth) {
        if (depth > MAX_SCAN_DEPTH) return;
        // 第一步：扫描当前目录下的所有书籍文件
        File[] files = dir.listFiles(new FileFilter() {
            @Override
            public boolean accept(File file) {
                if (file.isDirectory()) return false;
                String name = file.getName().toLowerCase();
                for (String ext : SUPPORTED_EXT) {
                    if (name.endsWith(ext)) return true;
                }
                return false;
            }
        });

        if (files != null) {
            Arrays.sort(files, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return Long.compare(b.lastModified(), a.lastModified());
                }
            });

            for (File file : files) {
                String absPath = file.getAbsolutePath();
                if (!seenPaths.contains(absPath)) {
                    seenPaths.add(absPath);
                    BookInfo info = new BookInfo(file);
                    if (!dbMap.containsKey(info.fileKey)) {
                        dbMap.put(info.fileKey, info);
                    }
                }
            }
        }

        // 第二步：递归扫描子目录（支持 epub/txt 放在多级目录下）
        File[] subDirs = dir.listFiles(new FileFilter() {
            @Override
            public boolean accept(File f) {
                return f.isDirectory();
            }
        });
        if (subDirs != null) {
            for (File subDir : subDirs) {
                String dirPath = subDir.getAbsolutePath();
                if (!seenPaths.contains(dirPath)) {
                    seenPaths.add(dirPath);
                    scanDir(subDir, seenPaths, dbMap, depth + 1);
                }
            }
        }
    }

    /**
     * 显示最近阅读的书籍（按 lastReadTime 降序取前 10 本）
     */
    private void showRecentBooks() {
        java.util.ArrayList<BookInfo> recent = new java.util.ArrayList<BookInfo>();
        long now = System.currentTimeMillis();
        for (BookInfo b : books) {
            long t = b.dbRecord != null ? b.dbRecord.lastReadTime : 0;
            if (t > 0 && t <= now) {
                recent.add(b);
            }
        }
        java.util.Collections.sort(recent, new Comparator<BookInfo>() {
            @Override public int compare(BookInfo a, BookInfo b) {
                long ta = a.dbRecord != null ? a.dbRecord.lastReadTime : 0;
                long tb = b.dbRecord != null ? b.dbRecord.lastReadTime : 0;
                return Long.compare(tb, ta);
            }
        });
        if (recent.isEmpty()) {
            AlertDialog.Builder ab = new AlertDialog.Builder(this);
            ab.setTitle("最近阅读");
            ab.setMessage("暂无阅读记录。打开一本书阅读后即可在此处快速返回。");
            ab.setPositiveButton("好的", null);
            ab.show();
            return;
        }
        final BookInfo[] items = recent.toArray(new BookInfo[0]);
        CharSequence[] labels = new CharSequence[items.length];
        for (int i = 0; i < items.length; i++) {
            labels[i] = items[i].title + "  ·  " + formatTime(items[i].dbRecord.lastReadTime);
        }
        AlertDialog.Builder ab = new AlertDialog.Builder(this);
        ab.setTitle("最近阅读");
        ab.setItems(labels, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                openBook(items[which].file);
            }
        });
        ab.setNegativeButton("取消", null);
        ab.show();
    }

    private static String formatTime(long t) {
        long delta = System.currentTimeMillis() - t;
        long sec = delta / 1000;
        if (sec < 60) return "刚刚";
        long min = sec / 60;
        if (min < 60) return min + "分钟前";
        long hour = min / 60;
        if (hour < 24) return hour + "小时前";
        long day = hour / 24;
        if (day < 30) return day + "天前";
        long month = day / 30;
        if (month < 12) return month + "个月前";
        return (day / 365) + "年前";
    }

    /**
     * 打开书籍
     */
    // 常量统一键名，防止拼写不一致
public static final String EXTRA_FILE_PATH = "file_path";
public static final String EXTRA_FILE_URI = "file_uri";

private void openBook(File file) {
        Intent intent = new Intent(this, com.einkreader.ui.reader.ReaderActivity.class);
        intent.putExtra(EXTRA_FILE_PATH, file.getAbsolutePath());
        startActivity(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            String filePath = data.getStringExtra(EXTRA_FILE_PATH);
            String fileUri = data.getStringExtra(EXTRA_FILE_URI);

            if (filePath != null) {
                File file = new File(filePath);
                if (file.exists()) {
                    SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                    prefs.edit().putString(PREFS_LIBRARY_PATH, file.getParent()).apply();
                    openBook(file);
                    return;
                }
            }

            // 如果文件路径不存在，用 URI 方式打开
            if (fileUri != null) {
                openBookByUri(fileUri);
                return;
            }
        }
        // 重新扫描
        scanBooks();
    }

    /**
     * 通过 URI 打开书籍（Android 11+）
     */
    private void openBookByUri(String uri) {
        Intent intent = new Intent(this, com.einkreader.ui.reader.ReaderActivity.class);
        intent.putExtra(EXTRA_FILE_URI, uri);
        intent.putExtra(EXTRA_FILE_PATH, uri);  // 兼容旧代码
        startActivity(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (books.isEmpty()) {
            scanBooks();
        }
    }

    /**
     * 书籍信息
     */
    static class BookInfo {
        File file;
        String fileKey;
        String title;
        String info;
        BookStorage.BookRecord dbRecord;
        public BookStorage.BookProgress preloadedProgress;

        BookInfo(File file) {
            this.file = file;
            this.fileKey = file.getName() + "_" + file.length() + "_" + file.lastModified();

            String name = file.getName();
            int dot = name.lastIndexOf('.');
            this.title = (dot > 0) ? name.substring(0, dot) : name;

            long size = file.length();
            String sizeStr;
            if (size < 1024) sizeStr = size + "B";
            else if (size < 1024 * 1024) sizeStr = (size / 1024) + "KB";
            else sizeStr = String.format("%.1fMB", size / (1024.0 * 1024.0));

            String ext = name.substring(name.lastIndexOf('.') + 1).toUpperCase();
            this.info = ext + " | " + sizeStr;
        }
    }
}