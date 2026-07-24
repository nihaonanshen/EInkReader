package com.einkreader.core

import android.util.Base64
import android.util.Log
import com.einkreader.core.model.Chapter
import com.einkreader.core.parser.EpubParser
import com.einkreader.core.parser.TxtParser
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Rust 原生库 JNI 桥接层
 *
 * 提供对 Rust einkreader-core 库的 Java 接口。
 * 所有 native 方法的实现位于 libeinkreader_core.so 中。
 */
class NativeBridge {
    companion object {
        private const val TAG = "NativeBridge"
        @Volatile var sLibraryLoaded = false
            private set
        
        // ✅ [Phase 6] Singleton instance for static access
        val bridgeInstance: NativeBridge by lazy { NativeBridge() }

        init {
            try {
                System.loadLibrary("einkreader_core")
                sLibraryLoaded = true
                Log.i(TAG, "Rust core library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                sLibraryLoaded = false
                Log.w(TAG, "Rust core library not available, using Java fallback", e)
            }
        }
    }

    // ========== 布局结果 POJO（支持 JSON + 二进制双路径） ==========

    /** 单行坐标（由 Rust 计算，Java 直接绘制） */
    class LineMetric {
        var text: String? = null
        var x: Float = 0f
        var y: Float = 0f
        var width: Float = 0f
        var height: Float = 0f
        var isParagraphEnd: Boolean = false
        var isFirstInParagraph: Boolean = false

        constructor()
        constructor(text: String?, x: Float, y: Float, width: Float, height: Float,
                    isParagraphEnd: Boolean, isFirstInParagraph: Boolean) {
            this.text = text
            this.x = x; this.y = y
            this.width = width; this.height = height
            this.isParagraphEnd = isParagraphEnd
            this.isFirstInParagraph = isFirstInParagraph
        }
    }

    /** 单页数据 */
    class PageData {
        var content: String? = null
        var lineCount: Int = 0
        var lines: MutableList<LineMetric> = ArrayList()
    }

    /** 布局结果 */
    class LayoutResult {
        var pages: MutableList<PageData> = ArrayList()
        var totalLines: Int = 0
        var totalPages: Int = 0
        var elapsedNs: Long = 0L
    }

    // ========== LRU 缓存 key ==========

    data class LayoutKey(
        val text: String?,
        val maxWidthPx: Float,
        val maxHeightPx: Float,
        val fontSizePx: Float,
        val lineSpacing: Float,
        val paragraphSpacing: Float,
        val firstLineIndent: Boolean,
        val paddingLeft: Float,
        val paddingTop: Float
    ) {
        override fun hashCode(): Int {
            var hc = text?.hashCode() ?: 0
            hc = 31 * hc + maxWidthPx.toInt()
            hc = 31 * hc + maxHeightPx.toInt()
            hc = 31 * hc + fontSizePx.toInt()
            hc = 31 * hc + lineSpacing.toInt()
            hc = 31 * hc + paragraphSpacing.toInt()
            hc = 31 * hc + if (firstLineIndent) 1 else 0
            hc = 31 * hc + paddingLeft.toInt()
            hc = 31 * hc + paddingTop.toInt()
            return hc
        }
    }

    // ========== LRU Cache ==========

    private val lruCache: MutableMap<LayoutKey, LayoutResult> =
        object : LinkedHashMap<LayoutKey, LayoutResult>(4, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LayoutKey, LayoutResult>?): Boolean {
                // ✅ [Phase 3] 从 3 增大到 20，减少翻页 cache miss
                return size > 20
            }
        }

    // ========== JNI 原生方法声明 ==========

    external fun nativeDetectEncoding(data: ByteArray, len: Int): String
    external fun nativeParseTxt(filePath: String, forcedEncoding: String): String
    external fun nativeParseEpub(filePath: String): String

    /** JSON 版（兼容） */
    external fun nativeLayoutText(
        text: String, maxWidthPx: Float, maxHeightPx: Float,
        fontSizePx: Float, lineSpacing: Float, paragraphSpacing: Float,
        firstLineIndent: Boolean
    ): String

    /** 二进制版 JNI 原生方法 */
    external fun nativeLayoutTextBinary(
        text: String, maxWidthPx: Float, maxHeightPx: Float,
        fontSizePx: Float, lineSpacing: Float, paragraphSpacing: Float,
        firstLineIndent: Boolean, paddingLeft: Float, paddingTop: Float
    ): ByteArray

    /** 检查布局结果是否在 LRU 缓存中 (内部调用) */
    fun isLayoutCachedInternal(
        text: String?, maxWidthPx: Float, maxHeightPx: Float,
        fontSizePx: Float, lineSpacing: Float, paragraphSpacing: Float,
        firstLineIndent: Boolean, paddingLeft: Float, paddingTop: Float
    ): Boolean {
        val key = LayoutKey(text, maxWidthPx, maxHeightPx, fontSizePx,
                lineSpacing, paragraphSpacing, firstLineIndent, paddingLeft, paddingTop)
        return lruCache.containsKey(key)
    }

    // ========== 编码检测（带 fallback） ==========

    fun detectEncoding(file: File): String {
        if (sLibraryLoaded) {
            try {
                val readSize = Math.min(file.length(), 65536).toInt()
                val header = ByteArray(readSize)
                java.io.FileInputStream(file).use { fis ->
                    val actualRead = fis.read(header, 0, readSize)
                    if (actualRead > 0) return nativeDetectEncoding(header, actualRead)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Rust encoding detection failed", e)
            }
        }
        return com.einkreader.utils.EncodingDetector.detect(file)
    }

    // ========== TXT/EPUB 解析（带 fallback，不变） ==========

    fun parseTxt(file: File): TxtParser.ParseResult {
        return parseTxt(file, null)
    }

    fun parseTxt(file: File, forcedEncoding: String?): TxtParser.ParseResult {
        if (sLibraryLoaded) {
            try {
                val json = nativeParseTxt(file.absolutePath, forcedEncoding ?: "")
                return parseTxtJson(json, file)
            } catch (e: Exception) {
                Log.w(TAG, "Rust TXT parser failed, falling back", e)
            }
        }
        return TxtParser.parse(file, forcedEncoding)
    }

    @Throws(Exception::class)
    private fun parseTxtJson(json: String, file: File): TxtParser.ParseResult {
        val root = JSONObject(json)
        if (root.has("error")) throw Exception("Rust parser error: " + root.getString("error"))
        val result = TxtParser.ParseResult()
        result.bookTitle = root.optString("book_title", "")
        result.encoding = root.optString("encoding", "UTF-8")
        result.chapters = java.util.ArrayList()
        val chapters = root.getJSONArray("chapters")
        for (i in 0 until chapters.length()) {
            val ch = chapters.getJSONObject(i)
            val title = ch.optString("title", "第" + (i + 1) + "章")
            val content = ch.optString("content", "")
            val chapter = Chapter(title, content)
            chapter.index = i
            result.chapters.add(chapter)
        }
        return result
    }

    @Throws(Exception::class)
    fun parseEpub(file: File): EpubParser.EpubResult {
        if (sLibraryLoaded) {
            try {
                val json = nativeParseEpub(file.absolutePath)
                return parseEpubJson(json, file)
            } catch (e: Exception) {
                Log.w(TAG, "Rust EPUB parser failed", e)
            }
        }
        return EpubParser.parse(file)
    }

    @Throws(Exception::class)
    private fun parseEpubJson(json: String, file: File): EpubParser.EpubResult {
        val root = JSONObject(json)
        if (root.has("error")) throw Exception("Rust EPUB error: " + root.getString("error"))
        val result = EpubParser.EpubResult()
        result.title = root.optString("title", "")
        result.author = root.optString("author", "")
        result.chapters.clear()
        val chapters = root.getJSONArray("chapters")
        for (i in 0 until chapters.length()) {
            val ch = chapters.getJSONObject(i)
            val title = ch.optString("title", "第" + (i + 1) + "章")
            val content = ch.optString("content", "")
            val chapter = Chapter(title, content)
            chapter.index = i
            result.chapters.add(chapter)
        }
        result.images.clear(); result.images.putAll(HashMap<String, ByteArray>())
        if (root.has("images")) {
            val imgs = root.getJSONObject("images")
            val keys = imgs.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                result.images!![k] = Base64.decode(imgs.getString(k), Base64.DEFAULT)
            }
        }
        if ((result.title == null || result.title!!.isEmpty()) && file != null) {
            val name = file.name
            val dot = name.lastIndexOf('.')
            result.title = if (dot > 0) name.substring(0, dot) else name
        }
        return result
    }

    // ========== bincode 二进制解析（精确匹配 Rust bincode v1 格式） ==========

    fun parseLayoutBinary(data: ByteArray?): LayoutResult {
        val result = LayoutResult()
        if (data == null || data.isEmpty()) return result
        try {
            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val pageCount = bb.long.toInt()
            result.pages = ArrayList(pageCount)
            for (pi in 0 until pageCount) {
                val pd = PageData()
                pd.content = readBincodeString(bb)
                pd.lineCount = bb.long.toInt()
                val lineCount = bb.long.toInt()
                pd.lines = ArrayList(lineCount)
                for (li in 0 until lineCount) {
                    val lm = LineMetric()
                    lm.text = readBincodeString(bb)
                    lm.x = bb.float
                    lm.y = bb.float
                    lm.width = bb.float
                    lm.height = bb.float
                    lm.isParagraphEnd = bb.get() != 0.toByte()
                    lm.isFirstInParagraph = bb.get() != 0.toByte()
                    pd.lines.add(lm)
                }
                result.pages.add(pd)
            }
            result.totalLines = bb.long.toInt()
            result.totalPages = bb.long.toInt()
            result.elapsedNs = bb.long
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse bincode layout", e)
        }
        return result
    }

    private fun readBincodeString(bb: ByteBuffer): String {
        val byteLen = bb.long.toInt()
        if (byteLen == 0) return ""
        val strBytes = ByteArray(byteLen)
        bb.get(strBytes)
        return String(strBytes, StandardCharsets.UTF_8)
    }

    // ========== Rust 文本布局（双路径 + LRU 缓存） ==========

    /** JSON 版布局（兼容旧调用） */
    fun layoutText(
        text: String, maxWidthPx: Int, maxHeightPx: Int,
        fontSizePx: Float, lineSpacing: Float, paragraphSpacing: Float,
        firstLineIndent: Boolean
    ): LayoutResult {
        val key = LayoutKey(text, maxWidthPx.toFloat(), maxHeightPx.toFloat(), fontSizePx,
                lineSpacing, paragraphSpacing, firstLineIndent, 0f, 0f)
        cacheGet(key)?.let { return it }
        if (!sLibraryLoaded) return LayoutResult()
        return try {
            val json = nativeLayoutText(text, maxWidthPx.toFloat(), maxHeightPx.toFloat(),
                    fontSizePx, lineSpacing, paragraphSpacing, firstLineIndent)
            val r = parseLayoutJson(json)
            if (r.totalPages > 0) cachePut(key, r)
            r
        } catch (e: Exception) {
            Log.w(TAG, "Rust layout(JSON) failed", e)
            LayoutResult()
        }
    }

    /** 二进制版布局（主要入口，含 LRU 缓存） */
    fun layoutTextBinary(
        text: String, maxWidthPx: Float, maxHeightPx: Float,
        fontSizePx: Float, lineSpacing: Float, paragraphSpacing: Float,
        firstLineIndent: Boolean, paddingLeft: Float, paddingTop: Float
    ): LayoutResult {
        val key = LayoutKey(text, maxWidthPx, maxHeightPx, fontSizePx,
                lineSpacing, paragraphSpacing, firstLineIndent, paddingLeft, paddingTop)
        cacheGet(key)?.let { return it }
        if (!sLibraryLoaded) return LayoutResult()
        return try {
            val binary = nativeLayoutTextBinary(text, maxWidthPx, maxHeightPx,
                    fontSizePx, lineSpacing, paragraphSpacing, firstLineIndent,
                    paddingLeft, paddingTop)
            val r = parseLayoutBinary(binary)
            if (r.totalPages > 0) cachePut(key, r)
            r
        } catch (e: Exception) {
            Log.w(TAG, "Rust layout(binary) failed", e)
            LayoutResult()
        }
    }

    /** JSON 解析（保留兼容） */
    fun parseLayoutJson(json: String): LayoutResult {
        val result = LayoutResult()
        try {
            val root = JSONObject(json)
            if (root.has("error")) return result
            val pagesArr = root.getJSONArray("pages")
            result.pages = ArrayList(pagesArr.length())
            for (i in 0 until pagesArr.length()) {
                val p = pagesArr.getJSONObject(i)
                val pd = PageData()
                pd.content = p.optString("content", "")
                pd.lineCount = p.optInt("line_count", 0)
                if (p.has("lines")) {
                    val linesArr = p.getJSONArray("lines")
                    pd.lines = ArrayList(linesArr.length())
                    for (j in 0 until linesArr.length()) {
                        val l = linesArr.getJSONObject(j)
                        val lm = LineMetric()
                        lm.text = l.optString("text", "")
                        lm.x = l.optDouble("x", 0.0).toFloat()
                        lm.y = l.optDouble("y", 0.0).toFloat()
                        lm.width = l.optDouble("width", 0.0).toFloat()
                        lm.height = l.optDouble("height", 0.0).toFloat()
                        lm.isParagraphEnd = l.optBoolean("is_paragraph_end", false)
                        lm.isFirstInParagraph = l.optBoolean("is_first_in_paragraph", false)
                        pd.lines.add(lm)
                    }
                }
                result.pages.add(pd)
            }
            result.totalLines = root.optInt("total_lines", 0)
            result.totalPages = root.optInt("total_pages", 0)
        } catch (e: Exception) {
            Log.w(TAG, "JSON layout parse failed", e)
        }
        return result
    }

    // ========== Benchmark 工具类 ==========

    /** 性能对比结果 */
    data class BenchmarkResult(
        var javaNs: Long = 0,
        var jsonNs: Long = 0,
        var binaryNs: Long = 0,
        var pages: Int = 0,
        var tag: String? = null
    ) {
        fun summary(): String {
            return String.format("[%s] pages=%d  Java=%.2fms  JSON=%.2fms  Binary=%.2fms  speedup=%.1fx",
                    tag, pages, javaNs / 1e6, jsonNs / 1e6, binaryNs / 1e6,
                    if (javaNs > 0 && binaryNs > 0) javaNs.toDouble() / binaryNs else 0.0)
        }
    }

    fun benchmarkLayout(text: String, width: Int, height: Int,
           fontSize: Float, lineSpacing: Float, paragraphSpacing: Float,
           indent: Boolean, paddingLeft: Float, paddingTop: Float): BenchmarkResult {
        val br = BenchmarkResult()
        br.tag = "LayoutBench"
        br.javaNs = 0

        val start = System.nanoTime()
        val r1 = layoutText(text, width, height, fontSize, lineSpacing, paragraphSpacing, indent)
        br.jsonNs = System.nanoTime() - start
        br.pages = r1.totalPages

        synchronized(this@NativeBridge) { lruCache.clear() }
        val start2 = System.nanoTime()
        val r2 = layoutTextBinary(text, width.toFloat(), height.toFloat(),
                fontSize, lineSpacing, paragraphSpacing, indent, paddingLeft, paddingTop)
        br.binaryNs = System.nanoTime() - start2
        if (br.pages == 0) br.pages = r2.totalPages

        Log.i(TAG, br.summary())
        return br
    }

    // ========== 缓存私有方法 ==========

    private fun cacheGet(key: LayoutKey): LayoutResult? = lruCache[key]
    private fun cachePut(key: LayoutKey, result: LayoutResult) { lruCache[key] = result }
}
