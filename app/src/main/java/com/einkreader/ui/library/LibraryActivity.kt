package com.einkreader.ui.library

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.AdapterView
import android.widget.ListView
import android.widget.TextView
import com.einkreader.EInkReaderApp
import com.einkreader.di.ServiceLocator
import com.einkreader.R
import com.einkreader.core.storage.BookStorage
import com.einkreader.ui.reader.DebugLog
import com.einkreader.ui.settings.AboutActivity
import java.io.File as JFile
import java.util.ArrayList as JArrayList


class LibraryActivity : Activity() {

    companion object {
        private const val PREFS_NAME = "eink_reader_prefs"
        private const val PREFS_LIBRARY_PATH = "library_path"
        private const val PREFS_SORT_MODE = "sort_mode"
        private val SUPPORTED_EXT = arrayOf(".txt", ".epub")
        private const val MAX_SCAN_DEPTH = 4
    }

    private lateinit var bookList: ListView
    private lateinit var btnImport: TextView
    private lateinit var btnRefreshSettings: TextView
    private lateinit var btnAbout: TextView
    private lateinit var btnSort: TextView
    private lateinit var adapter: BookListAdapter
    private val books = JArrayList<BookInfo>()
    private var scanning = false
    private var currentSortMode = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)

        bookList = findViewById(R.id.book_list)
        btnImport = findViewById(R.id.btn_import)
        btnRefreshSettings = findViewById(R.id.btn_refresh_settings)
        btnAbout = findViewById(R.id.btn_about)

        findViewById<TextView>(R.id.btn_recent).setOnClickListener { showRecentBooks() }

        // 夜间模式：书库背景与文字（与阅读页一致的黑底灰字）
        val night = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean("night_mode", false)
        if (night) {
            val root = findViewById<android.view.View>(android.R.id.content)
            root.setBackgroundColor(-0xddddde) // 0xFF222222
            val fg = -0x444445 // 0xFFBBBBBB
            for (id in intArrayOf(
                R.id.btn_import, R.id.btn_recent, R.id.btn_sort, R.id.btn_refresh_settings, R.id.btn_about
            )) {
                val v = findViewById<android.view.View>(id)
                if (v is TextView) v.setTextColor(fg)
            }
        }

        adapter = BookListAdapter(this, books, night)
        bookList.adapter = adapter

        bookList.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            if (position in books.indices) openBook(checkNotNull(books[position].file))
        }

        bookList.setOnItemLongClickListener { _, _, position, _ ->
            if (position !in books.indices) return@setOnItemLongClickListener false
            val book = books[position]
            AlertDialog.Builder(this).apply {
                setTitle(book.title)
                setMessage("确定要删除这本书吗？")
                setPositiveButton("删除") { _, _ ->
                    book.file?.delete()
                    ServiceLocator.getBookStorage()?.deleteBook(checkNotNull(book.fileKey))
                    scanBooks()
                }
                setNegativeButton("取消", null)
            }.show()
            true
        }

        btnImport.setOnClickListener {
            startActivityForResult(Intent(this, FilePickerActivity::class.java), 1001)
        }
        btnRefreshSettings.setOnClickListener {
            startActivity(Intent(this, RefreshSettingsActivity::class.java))
        }
        btnAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        val sortLabels = arrayOf("按时间", "按名称", "按格式")
        currentSortMode = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(PREFS_SORT_MODE, 0)
        btnSort = findViewById(R.id.btn_sort)
        btnSort.text = sortLabels[currentSortMode]
        btnSort.setOnClickListener {
            currentSortMode = (currentSortMode + 1) % 3
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putInt(PREFS_SORT_MODE, currentSortMode).apply()
            btnSort.text = sortLabels[currentSortMode]
            scanBooks()
        }

        Thread { scanBooks() }.start()
    }

    private fun scanBooks() {
        if (scanning) return
        scanning = true
        try {
            books.clear()
            val storage = ServiceLocator.getBookStorage() ?: return
            val dbMap = LinkedHashMap<String, BookInfo>()

            for (rec in storage.listAllBooks()) {
                val filePath = rec.filePath ?: continue
                val f = JFile(filePath)
                if (!f.exists()) {
                    storage.deleteBook(checkNotNull(rec.fileKey))
                    DebugLog.log("Lib", "清理失效记录: $filePath")
                    continue
                }
                val info = BookInfo(f)
                info.dbRecord = rec
                dbMap[checkNotNull(rec.fileKey)] = info
            }

            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val savedPath = prefs.getString(PREFS_LIBRARY_PATH, "") ?: ""

            val searchDirs = JArrayList<JFile>()
            if (savedPath.isNotEmpty()) {
                val savedDir = JFile(savedPath)
                if (savedDir.exists() && savedDir.isDirectory) searchDirs.add(savedDir)
            }

            val sdcard = Environment.getExternalStorageDirectory()
            searchDirs.add(sdcard)
            searchDirs.add(JFile(sdcard, "Books"))
            searchDirs.add(JFile(sdcard, "books"))
            searchDirs.add(JFile(sdcard, "eBooks"))
            searchDirs.add(JFile(sdcard, "EInkReader"))
            searchDirs.add(JFile(sdcard, "Download"))
            searchDirs.add(JFile("/storage/emulated/0/books"))
            searchDirs.add(JFile("/storage/emulated/0/Books"))
            searchDirs.add(JFile("/storage/emulated/0/epub"))
            searchDirs.add(JFile("/storage/emulated/0/EInkReader"))
            searchDirs.add(JFile("/mnt/sdcard/epub"))
            searchDirs.add(JFile("/mnt/external_sd/epub"))
            searchDirs.add(JFile("/mnt/external_sd/books"))
            searchDirs.add(JFile("/mnt/external_sd/Books"))

            val seenPaths = HashSet<String>()
            for (dir in searchDirs) {
                if (dir.exists() && dir.isDirectory) scanDir(dir, seenPaths, dbMap)
            }

            books.addAll(dbMap.values)

            when (currentSortMode) {
                1 -> books.sortBy { it.title.lowercase() }
                2 -> books.sortedWith(compareBy({ it.file?.name?.lowercase() ?: "" }, { it.title.lowercase() })).toTypedArray().let { arr -> books.clear(); books.addAll(arr.asList()) }
                else -> books.sortByDescending { it.dbRecord?.lastReadTime ?: (it.file?.lastModified() ?: 0L) }
            }

            DebugLog.log("Lib", "扫描完成: ${books.size}本书 排序模式=$currentSortMode")
        } finally {
            runOnUiThread {
                adapter.notifyDataSetChanged()
                scanning = false
            }
        }
    }

    private fun scanDir(dir: JFile, seenPaths: MutableSet<String>, dbMap: MutableMap<String, BookInfo>) {
        dir.listFiles { _, name ->
            val lower = name.lowercase()
            SUPPORTED_EXT.any { lower.endsWith(it) }
        }?.also { files ->
            val sortedFiles = files.toList().sortedWith(compareByDescending { it.lastModified() })
            for (file in sortedFiles) {
                val absPath = file.absolutePath
                if (!seenPaths.contains(absPath)) {
                    seenPaths.add(absPath)
                    val info = BookInfo(file)
                    if (!dbMap.containsKey(info.fileKey)) dbMap[info.fileKey] = info
                }
            }
        }

        dir.listFiles { f: JFile -> f.isDirectory }?.also { subDirs ->
            for (subDir in subDirs) {
                val dirPath = subDir.absolutePath
                if (!seenPaths.contains(dirPath)) {
                    seenPaths.add(dirPath)
                    scanDir(subDir, seenPaths, dbMap)
                }
            }
        }
    }

    private fun showRecentBooks() {
        val recent = JArrayList<BookInfo>()
        val now = System.currentTimeMillis()
        for (b in books) {
            val t = b.dbRecord?.lastReadTime ?: 0L
            if (t > 0 && t <= now) recent.add(b)
        }
        recent.sortByDescending { it.dbRecord?.lastReadTime ?: 0L }

        if (recent.isEmpty()) {
            AlertDialog.Builder(this).apply {
                setTitle("最近阅读")
                setMessage("暂无阅读记录。打开一本书阅读后即可在此处快速返回。")
                setPositiveButton("好的", null)
            }.show()
            return
        }

        val labels = recent.map { "${it.title}  ·  ${formatTime(it.dbRecord?.lastReadTime ?: 0)}" }.toTypedArray()
        AlertDialog.Builder(this).apply {
            setTitle("最近阅读")
            setItems(labels) { _, which -> openBook(checkNotNull(recent[which].file)) }
            setNegativeButton("取消", null)
        }.show()
    }

    private fun formatTime(t: Long): String {
        val delta = System.currentTimeMillis() - t
        return if (delta < 0) "刚刚" else BookListAdapter.formatMs(delta)
    }

    private fun openBook(file: JFile) {
        val intent = Intent(this, com.einkreader.ui.reader.ReaderActivity::class.java)
        intent.putExtra("file_path", file.absolutePath)
        startActivity(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            val filePath = data.getStringExtra("file_path")
            val fileUri = data.getStringExtra("file_uri")

            if (filePath != null) {
                val file = JFile(filePath)
                if (file.exists()) {
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putString(PREFS_LIBRARY_PATH, file.parent).apply()
                    openBook(file)
                    return
                }
            }

            if (fileUri != null) {
                openBookByUri(fileUri)
                return
            }
        }
        scanBooks()
    }

    private fun openBookByUri(uri: String) {
        val intent = Intent(this, com.einkreader.ui.reader.ReaderActivity::class.java)
        intent.putExtra("file_uri", uri)
        intent.putExtra("file_path", uri)
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        // 从阅读页返回时可能有新的封面缓存，始终刷新列表
        if (books.isEmpty()) {
            scanBooks()
        } else {
            adapter.notifyDataSetChanged()
        }
    }

    class BookInfo(@JvmField val file: JFile?) {
        @JvmField val fileKey: String = if (file != null) file.name + "_" + file.length().toString() + "_" + file.lastModified().toString() else ""
        @JvmField val title: String
        @JvmField val info: String
        @JvmField var dbRecord: BookStorage.BookRecord? = null
        @JvmField var preloadedProgress: BookStorage.BookProgress? = null

        init {
            if (file != null) {
                val name = file.name
                val dot = name.lastIndexOf('.')
                title = if (dot > 0) name.substring(0, dot) else name

                val size = file.length()
                val sizeStr = when {
                    size < 1024 -> "${size}B"
                    size < 1024 * 1024 -> "${size / 1024}KB"
                    else -> String.format("%.1fMB", size / (1024.0 * 1024.0))
                }

                val ext = name.substring(name.lastIndexOf('.') + 1).uppercase()
                info = "$ext | $sizeStr"
            } else {
                title = "Unknown"
                info = "unknown"
            }
        }
    }
}
