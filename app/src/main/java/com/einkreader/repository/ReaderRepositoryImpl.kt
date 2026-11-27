package com.einkreader.repository

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import com.einkreader.core.FeatureFlags
import com.einkreader.core.NativeBridge
import com.einkreader.core.model.Chapter
import com.einkreader.core.model.TocItem

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
        var tocItems: List<TocItem>? = null

        try {
            if (isEpub) {
                val r = if (isContent && fileUri != null) {
                    val tf = copyToTempFile(fileUri, ".epub")
                    val epubResult = try {
                        if (tf == null) {
                            Log.e(TAG, "Failed to copy temp EPUB file from URI: $fileUri")
                            null
                        } else {
                            NativeBridge.bridgeInstance.parseEpub(tf)
                        }
                    } finally { tf?.delete() }
                    epubResult
                } else {
                    val f = File(fp)
                    NativeBridge.bridgeInstance.parseEpub(f)
                }
                if (r != null) {
                    chapters = r.chapters
                    images = r.images
                    bookTitle = r.title ?: ""
                    // 使用 Rust 解析的目录树（如果有）
                    tocItems = if (r.tocItems.isNotEmpty()) r.tocItems else null
                }
            } else if (isTxt) {
                val r = if (isContent && fileUri != null) {
                    val tf = copyToTempFile(fileUri, ".txt")
                    if (tf == null) {
                        Log.e(TAG, "Failed to copy temp TXT file from URI: $fileUri")
                        null
                    } else {
                        try {
                            if (FeatureFlags.useRustTxtParser()) NativeBridge.bridgeInstance.parseTxt(tf)
                            else TxtParser.parse(tf)
                        } finally { tf.delete() }
                    }
                } else {
                    val f = File(fp)
                    if (FeatureFlags.useRustTxtParser()) NativeBridge.bridgeInstance.parseTxt(f)
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
        // [P1-#25] 进度读取单轨化：仅 DB。旧"DB 未命中 → prefs lc_/lp_ 回退"删除——
        // saveProgress 已不写 prefs，回退只会读到过期旧数据；legacy 只读保留在 loadProgress
        bookStorage?.loadProgress(fileKey)?.let { prog ->
            if (prog.chapterIndex < chapters.size) {
                savedChapter = prog.chapterIndex
                savedPage = prog.pageIndex
            }
        }

        if (savedChapter < 0 || savedChapter >= chapters.size) {
            savedChapter = 0; savedPage = 0
        }

        return BookResult(chapters, images ?: LinkedHashMap(), bookTitle, fileKey, savedChapter, savedPage, tocItems ?: emptyList())
    }

    override fun saveProgress(fileKey: String, chapterIndex: Int, pageIndex: Int, totalChapters: Int) {
        // [P1-#25] 进度写入单轨化：只写 DB（BookStorage）。旧 prefs 双写
        // （lc_/lp_/total_ch_）已删除——prefs 降为 legacy 只读，新数据不再进 prefs
        if (fileKey == null) return
        bookStorage?.saveProgress(BookStorage.BookProgress().apply {
            this.fileKey = fileKey
            this.chapterIndex = chapterIndex
            this.pageIndex = pageIndex
            this.totalChapters = totalChapters
            updatedAt = System.currentTimeMillis()
        })
    }

    // [P1-#25] prefs 通道为 legacy 只读（单轨化前的旧数据；total_ch_ 不再被写入，
    // 仅在已存在时读到）——新增数据一律经 saveProgress 写 DB
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

    // [P1-#25] 书签迁移 DB（BookStorage.bookmarks 表，PK 去重）；legacy bookmarks_ prefs
    // 降为只读合并（旧数据仍可查可跳转，不再新写）
    override fun addBookmark(fileKey: String, chapterIndex: Int, pageIndex: Int, chapterTitle: String?) {
        if (fileKey == null) return
        val bs = bookStorage
        if (bs != null) {
            bs.addBookmark(BookStorage.BookBookmark().apply {
                this.fileKey = fileKey
                this.chapterIndex = chapterIndex
                this.pageIndex = pageIndex
                this.chapterTitle = chapterTitle ?: ""
                createdAt = System.currentTimeMillis()
            })
            return
        }
        // bookStorage 不可用（理论不应发生）时兜底 legacy prefs，保证功能不丢
        val key = "$chapterIndex" + "_" + pageIndex
        val value = (chapterTitle ?: "") + " P" + pageIndex
        bmPrefs(fileKey).edit().putString(key, value).apply()
    }

    /** [P1-#25] 合并书签键：DB 书签 + legacy prefs 只读去重，按章/页排序（面板顺序与跳转一致） */
    private fun mergedBookmarkPairs(fileKey: String): List<Pair<Int, Int>> {
        val pairs = LinkedHashSet<Pair<Int, Int>>()
        bookStorage?.listBookmarks(fileKey)?.forEach { b -> pairs.add(b.chapterIndex to b.pageIndex) }
        try {
            bmPrefs(fileKey).getAll().keys.forEach { k ->
                val ch = k.substringBefore("_", "0").toIntOrNull()
                val pg = k.substringAfter("_", "0").toIntOrNull()
                if (ch != null && pg != null) pairs.add(ch to pg)
            }
        } catch (e: Exception) { Log.e(TAG, "legacy bookmark read failed", e) }
        return pairs.sortedWith(compareBy({ it.first }, { it.second }))
    }

    override fun loadBookmarks(fileKey: String): List<String> {
        // [P1-#25] DB 优先 + legacy prefs 只读合并（新数据不再写入 prefs）
        val dbTitles = HashMap<String, String>()
        bookStorage?.listBookmarks(fileKey)?.forEach { b ->
            dbTitles["${b.chapterIndex}_${b.pageIndex}"] = b.chapterTitle + " P" + b.pageIndex
        }
        val legacyVals = HashMap<String, String>()
        try {
            bmPrefs(fileKey).getAll().forEach { (k, v) -> legacyVals[k] = v?.toString() ?: "" }
        } catch (e: Exception) { Log.e(TAG, "loadBookmarks legacy read failed", e) }
        val list = JArrayList<String>()
        mergedBookmarkPairs(fileKey).forEach { (ch, pg) ->
            val key = ch.toString() + "_" + pg
            val display = dbTitles[key] ?: legacyVals[key] ?: (ch.toString() + " P" + pg)
            list.add("$key : $display")
        }
        return list
    }

    override fun jumpToBookmark(fileKey: String, bookmarkIndex: Int, totalChapters: Int): Int {
        return try {
            val pairs = mergedBookmarkPairs(fileKey)
            if (bookmarkIndex in pairs.indices) pairs[bookmarkIndex].first
            else 0
        } catch (e: Exception) { Log.e(TAG, "jumpToBookmark failed", e); 0 }
    }

    override fun bookmarkPage(fileKey: String, bookmarkIndex: Int): Int {
        // [P1-#25] 合并书签键（DB + legacy，见 mergedBookmarkPairs）
        return try {
            val pairs = mergedBookmarkPairs(fileKey)
            if (bookmarkIndex !in pairs.indices) return -1
            pairs[bookmarkIndex].second
        } catch (e: Exception) { Log.e(TAG, "bookmarkPage failed", e); -1 }
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
            // use{} 保证异常时也关闭输入流（之前裸 close()，IO 出错会泄漏）
            input.use { ins ->
                val tf = File(appContext.cacheDir, "t${System.nanoTime()}${System.currentTimeMillis()}$suffix")
                java.io.FileOutputStream(tf).use { fos ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (ins.read(buf).also { n = it } != -1) fos.write(buf, 0, n)
                }
                tf
            }
        } catch (e: Exception) { Log.e(TAG, "copyToTempFile failed", e); null }
    }
}
