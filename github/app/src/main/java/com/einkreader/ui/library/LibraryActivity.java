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

import com.einkreader.R;
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
        TextView btnLog = (TextView) findViewById(R.id.btn_log);
        btnLog.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(LibraryActivity.this, com.einkreader.ui.reader.DebugLogActivity.class);
                startActivity(intent);
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
        btnSort = (TextView) findViewById(R.id.btn_refresh_settings);
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

        // 扫描书籍
        scanBooks();
    }

    /**
     * 扫描 SD 卡上的书籍
     */
    private void scanBooks() {
        books.clear();

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

        java.util.HashSet<String> seenPaths = new java.util.HashSet<String>();
        for (File dir : searchDirs) {
            if (dir.exists() && dir.isDirectory()) {
                scanDir(dir, seenPaths);
            }
        }

        com.einkreader.ui.reader.DebugLog.log("Lib", "扫描完成: " + books.size() + "本书 排序模式=" + currentSortMode + "(" + (currentSortMode == 0 ? "时间" : currentSortMode == 1 ? "名称" : "格式") + ")");

        // 按当前排序模式排序
        if (currentSortMode == 1) {
            // 按文件名排序
            java.util.Collections.sort(books, new Comparator<BookInfo>() {
                @Override public int compare(BookInfo a, BookInfo b) {
                    return a.title.compareToIgnoreCase(b.title);
                }
            });
        } else if (currentSortMode == 2) {
            // 按格式排序（EPUB 在前，TXT 在后，再按文件名）
            java.util.Collections.sort(books, new Comparator<BookInfo>() {
                @Override public int compare(BookInfo a, BookInfo b) {
                    String extA = a.file.getName().toLowerCase();
                    String extB = b.file.getName().toLowerCase();
                    int cmp = extA.compareTo(extB);
                    if (cmp != 0) return cmp;
                    return a.title.compareToIgnoreCase(b.title);
                }
            });
        }
        // 0=按时间（已在 scanDir 中按修改时间排序）

        adapter.notifyDataSetChanged();
    }

    /**
     * 扫描单个目录下的书籍
     */
    private void scanDir(File dir, java.util.HashSet<String> seenPaths) {
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

        if (files == null) return;

        // 按修改时间排序（最新的在前）
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
                books.add(new BookInfo(file));
            }
        }
    }

    /**
     * 打开书籍
     */
    private void openBook(File file) {
        Intent intent = new Intent(this, com.einkreader.ui.reader.ReaderActivity.class);
        intent.putExtra("file_path", file.getAbsolutePath());
        startActivity(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            String filePath = data.getStringExtra("file_path");
            String fileUri = data.getStringExtra("file_uri");

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
        intent.putExtra("file_uri", uri);
        intent.putExtra("file_path", uri);  // 兼容旧代码
        startActivity(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        scanBooks();
    }

    /**
     * 书籍信息
     */
    static class BookInfo {
        File file;
        String title;
        String info;

        BookInfo(File file) {
            this.file = file;
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


