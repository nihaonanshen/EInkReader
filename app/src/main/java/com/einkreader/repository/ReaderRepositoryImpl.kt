package com.einkreader.repository

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import com.einkreader.core.FeatureFlags
import com.einkreader.core.NativeBridge
import com.einkreader.core.model.Chapter
import com.einkreader.core.parser.EpubParser
import com.einkreader.core.parser.TxtParser
import com.einkreader.core.storage.BookStorage
import java.io.File
import java.util.ArrayList as JArrayList
import java.util.LinkedHashMap

class ReaderRepositoryImpl(
    private val appContext: Context,
    private val bookStorage: BookStorage?
) : ReaderRepository {

    companion object {
        private const val TAG = "ReaderRepository"
        private const val PREFS_NAME = "eink_reader_prefs"
        private const val BOOKMARKS_PREFIX = "bookmarks_"
    }

    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun loadBook(filePath: String, fileUri: String?): BookResult {
        val fp = filePath
        val fu = fileUri ?: filePath
        val isContent = fileUri != null && fileUri.startsWith("content://")

        val fileKey: String = if (!isContent) {
            val f = File(fp)
            if (f.exists()) "${f.name}_${f.length()}_${f.lastModified()}"
            else "$fp.hashCode()"
        } else {
            "$fu.hashCode()"
        }

        val nl = fp.lowercase()
        val isEpub = ".epub" in nl || (fileUri?.contains(".epub") == true)
        val isTxt = ".txt" in nl || (fileUri?.contains(".txt") == true)

        var chapters: List<Chapter>? = null
        var images: Map<String, ByteArray>? = null
        var bookTitle = ""

        try {
            if (isEpub) {
                val r = if (isContent && fileUri != null) {
                    val tf = copyToTempFile(fileUri, ".epub")
                    try {
                        if (FeatureFlags.useRustEpubParser()) NativeBridge.parseEpub(tf!!)
                        else EpubParser.parse(tf!!)
                    } finally { tf?.delete() }
                } else {
                    val f = File(fp)
                    if (FeatureFlags.useRustEpubParser()) NativeBridge.parseEpub(f)
                    else EpubParser.parse(f)
                }
                if (r != null) {
                    chapters = r.chapters
                    images = r.images
                    bookTitle = r.title ?: ""
                }
            } else if (isTxt) {
                val r = if (isContent && fileUri != null) {
                    val tf = copyToTempFile(fileUri, ".txt")
                    try {
                        if (FeatureFlags.useRustTxtParser()) NativeBridge.parseTxt(tf!!)
                        else TxtParser.parse(tf!!)
                    } finally { tf?.delete() }
                } else {
                    val f = File(fp)
                    if (FeatureFlags.useRustTxtParser()) NativeBridge.parseTxt(f)
                    else TxtParser.parse(f)
                }
                if (r != null) {
                    chapters = r.chapters
                    bookTitle = r.bookTitle ?: ""
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadBook parse failed", e)
            return BookResult(emptyList(), emptyMap(), "", fileKey, 0, 0)
        }

        if (bookTitle.isEmpty()) bookTitle = extractBookTitle(fp)
        if (chapters.isNullOrEmpty()) return BookResult(emptyList(), emptyMap(), bookTitle, fileKey, 0, 0)

        persistBookRecord(fileKey, fp, fileUri, isContent, chapters.size, if (isEpub) "epub" else "txt")

        var savedChapter = 0
        var savedPage = 0
        bookStorage?.loadProgress(fileKey)?.let { prog ->
            if (prog.chapterIndex < chapters.size) {
                savedChapter = prog.chapterIndex
                savedPage = prog.pageIndex
            }
        } ?: run {
            savedChapter = prefs.getInt("lc_$fileKey", 0)
            savedPage = prefs.getInt("lp_$fileKey", 0)
        }

        if (savedChapter < 0 || savedChapter >= chapters.size) {
            savedChapter = 0; savedPage = 0
        }

        return BookResult(chapters, images ?: LinkedHashMap(), bookTitle, fileKey, savedChapter, savedPage)
    }

    override fun saveProgress(fileKey: String, chapterIndex: Int, pageIndex: Int, totalChapters: Int) {
        if (fileKey == null) return
        prefs.edit().apply {
            putInt("lc_$fileKey", chapterIndex)
            putInt("lp_$fileKey", pageIndex)
            putInt("total_ch_$fileKey", totalChapters)
        }.apply()
        bookStorage?.saveProgress(BookStorage.BookProgress().apply {
            this.fileKey = fileKey
            this.chapterIndex = chapterIndex
            this.pageIndex = pageIndex
            this.totalChapters = totalChapters
            updatedAt = System.currentTimeMillis()
        })
    }

    override fun loadProgress(fileKey: String): BookStorage.BookProgress? {
        if (fileKey == null) return null
        bookStorage?.loadProgress(fileKey)?.let { return it }
        val ch = prefs.getInt("lc_$fileKey", 0)
        val pg = prefs.getInt("lp_$fileKey", 0)
        if (ch > 0 || pg > 0) {
            return BookStorage.BookProgress().apply {
                this.fileKey = fileKey
                this.chapterIndex = ch
                this.pageIndex = pg
            }
        }
        return null
    }

    override fun addBookmark(fileKey: String, chapterIndex: Int, pageIndex: Int, chapterTitle: String?) {
        if (fileKey == null) return
        val key = "$chapterIndex" + "_" + pageIndex
        val value = (chapterTitle ?: "") + " P" + pageIndex
        bmPrefs(fileKey).edit().putString(key, value).apply()
    }

    override fun loadBookmarks(fileKey: String): List<String> {
        val list = JArrayList<String>()
        try {
            for ((k, v) in bmPrefs(fileKey).getAll().entries) list.add("$k : $v")
        } catch (e: Exception) { Log.e(TAG, "loadBookmarks failed", e) }
        return list
    }

    override fun jumpToBookmark(fileKey: String, bookmarkIndex: Int, totalChapters: Int): Int {
        return try {
            var i = 0
            for (k in bmPrefs(fileKey).getAll().keys) {
                if (i == bookmarkIndex) return k.substringBefore("_", k).toInt()
                i++
            }
            0
        } catch (e: Exception) { Log.e(TAG, "jumpToBookmark failed", e); 0 }
    }

    override fun persistBookRecord(fileKey: String, filePath: String?, fileUri: String?,
                                   isContent: Boolean, totalChapters: Int, format: String?) {
        if (fileKey == null || bookStorage == null) return
        val rec = BookStorage.BookRecord().apply {
            this.fileKey = fileKey
            this.filePath = filePath ?: fileUri ?: ""
            this.format = format
            this.totalChapters = totalChapters
            addedAt = System.currentTimeMillis()
        }
        filePath?.let { fpath ->
            val fObj = File(fpath)
            if (fObj.exists()) {
                rec.fileSize = fObj.length()
                rec.lastModified = fObj.lastModified()
                val name = fObj.name
                val dot = name.lastIndexOf('.')
                rec.title = if (dot > 0) name.substring(0, dot) else name
            }
        }
        if (rec.title.isNullOrEmpty()) {
            rec.title = filePath?.let { extractBookTitle(it) } ?: "Unknown"
        }
        bookStorage.upsertBook(rec)
    }

    override fun addReadTime(fileKey: String, deltaMs: Long) {
        if (fileKey == null || deltaMs <= 0L || bookStorage == null) return
        bookStorage.addReadTime(fileKey, deltaMs)
    }

    override fun extractBookTitle(filePath: String): String {
        if (filePath.isEmpty()) return ""
        val slash = maxOf(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'))
        val name = if (slash >= 0) filePath.substring(slash + 1) else filePath
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    private fun bmPrefs(fileKey: String) = appContext.getSharedPreferences(BOOKMARKS_PREFIX + fileKey, Context.MODE_PRIVATE)

    private fun copyToTempFile(uri: String, suffix: String): File? {
        return try {
            val input = appContext.contentResolver.openInputStream(Uri.parse(uri)) ?: return null
            val tf = File(appContext.cacheDir, "t${System.currentTimeMillis()}$suffix")
            java.io.FileOutputStream(tf).use { fos ->
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } != -1) fos.write(buf, 0, n)
            }
            input.close()
            tf
        } catch (e: Exception) { Log.e(TAG, "copyToTempFile failed", e); null }
    }
}
