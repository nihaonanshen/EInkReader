package com.einkreader.core

import android.util.Base64
import android.util.Log
import com.einkreader.core.model.Chapter
import com.einkreader.core.model.EpubResult
import com.einkreader.core.parser.EpubParserFallback
import com.einkreader.core.parser.TxtParser
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
    external fun nativeParseTxtBinary(filePath: String, forcedEncoding: String): ByteArray
    external fun nativeParseEpubBinary(filePath: String): ByteArray
    external fun nativeLayoutTextBinary(
        text: String, maxWidthPx: Float, maxHeightPx: Float,
        fontSizePx: Float, lineSpacing: Float, paragraphSpacing: Float,
        firstLineIndent: Boolean, paddingLeft: Float, paddingTop: Float
    ): ByteArray

    // 🔥 按需加载 EPUB 章节内容
    external fun nativeLoadEpubChapterContent(filePath: String, chapterXhtmlPath: String): String

    // 🔥 批量布局（bincode 版）- 输入输出均为 bincode 二进制
    external fun nativeLayoutTextsBatchBinary(texts: ByteArray, params: ByteArray): ByteArray

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

        /** 检测 ByteArray 的编码，优先使用 Rust JNI，失败后回退到 Java 实现 */
    fun detectEncoding(data: ByteArray, len: Int): String {
        if (sLibraryLoaded) {
            try {
                return nativeDetectEncoding(data, len)
            } catch (e: Exception) {
                Log.w(TAG, "Rust encoding detection failed for ByteArray", e)
            }
        }
        // 回退到 Java 实现，已有 EncodingDetector.detect(data, len)
        return com.einkreader.utils.EncodingDetector.detect(data, len)
    }



    fun parseTxt(file: File): TxtParser.ParseResult {
        return parseTxt(file, null)
    }

    fun parseTxt(file: File, forcedEncoding: String?): TxtParser.ParseResult {
        if (sLibraryLoaded) {
            try {
                // 使用二进制路径（bincode 序列化）
                val binary = nativeParseTxtBinary(file.absolutePath, forcedEncoding ?: "")
                val result = parseTxtBinary(binary, file)
                if (result.chapters.isNotEmpty()) return result
            } catch (e: Exception) {
                Log.w(TAG, "Rust TXT binary parse failed, falling back to Java", e)
            }
        }
        // 回退到 Java 实现
        return TxtParser.parse(file, forcedEncoding)
    }

    @Throws(Exception::class)
    fun parseEpub(file: File): EpubResult {
        if (sLibraryLoaded) {
            try {
                // 使用二进制路径（bincode 序列化）
                val binary = nativeParseEpubBinary(file.absolutePath)
                val result = parseEpubBinary(binary, file)
                if (result.chapters.isNotEmpty()) return result
            } catch (e: Exception) {
                Log.w(TAG, "Rust EPUB binary parse failed, falling back to Java", e)
            }
        }
        // 回退到 Java 实现
        return EpubParserFallback.parse(file)
    }

    // 🔥 按需加载 EPUB 章节内容
    fun loadEpubChapterContent(filePath: String, chapterXhtmlPath: String): String {
        if (!sLibraryLoaded) {
            throw IllegalStateException("Rust core library not available")
        }
        return nativeLoadEpubChapterContent(filePath, chapterXhtmlPath)
    }

    // 🔥 批量布局（JSON版）— texts: JSON array of strings, params: JSON object
    /**
     * 批量布局（bincode 版）：对多个文本段应用相同参数，返回解析后的 List<LayoutResult>
     */
    fun batchLayoutTextsParsed(
        texts: List<String>, maxWidthPx: Float, maxHeightPx: Float,
        fontSizePx: Float, lineSpacing: Float, paragraphSpacing: Float,
        firstLineIndent: Boolean, paddingLeft: Float, paddingTop: Float
    ): List<LayoutResult> {
        if (!sLibraryLoaded) return emptyList()
        return try {
            val textsBin = encodeBincodeStringList(texts)
            val paramsBin = encodeLayoutParamsBinary(
                maxWidthPx, maxHeightPx, fontSizePx, lineSpacing, paragraphSpacing,
                firstLineIndent, paddingLeft, paddingTop
            )
            val binary = nativeLayoutTextsBatchBinary(textsBin, paramsBin)
            parseBatchLayoutBinary(binary)
        } catch (e: Exception) {
            Log.w(TAG, "batchLayoutTextsParsed failed", e)
            emptyList()
        }
    }

    /** 将 List<String> 编码为 bincode Vec<String>（u64 长度 + 每项 u64 字节长度 + UTF-8 字节） */
    private fun encodeBincodeStringList(texts: List<String>): ByteArray {
        val sizes = texts.sumOf { 8 + it.toByteArray(StandardCharsets.UTF_8).size }
        val bb = ByteBuffer.allocate(8 + sizes).order(ByteOrder.LITTLE_ENDIAN)
        bb.putLong(texts.size.toLong())
        for (t in texts) {
            val bytes = t.toByteArray(StandardCharsets.UTF_8)
            bb.putLong(bytes.size.toLong())
            bb.put(bytes)
        }
        return bb.array()
    }

    /** 将布局参数编码为 bincode 序列化的 LayoutParams */
    private fun encodeLayoutParamsBinary(
        maxWidthPx: Float, maxHeightPx: Float, fontSizePx: Float,
        lineSpacing: Float, paragraphSpacing: Float, firstLineIndent: Boolean,
        paddingLeft: Float, paddingTop: Float
    ): ByteArray {
        val bb = ByteBuffer.allocate(4 * 6 + 1 + 4 * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.putFloat(maxWidthPx)
        bb.putFloat(maxHeightPx)
        bb.putFloat(fontSizePx)
        bb.putFloat(lineSpacing)
        bb.putFloat(paragraphSpacing)
        bb.put(if (firstLineIndent) 1 else 0)
        bb.putFloat(paddingLeft)
        bb.putFloat(paddingTop)
        return bb.array()
    }

    /**
     * 解析批量布局 bincode 结果：bincode Vec<LayoutResult>
     * 格式：u64 数量 + 每个 LayoutResult（与 parseLayoutBinary 相同的单元素布局）
     */
    fun parseBatchLayoutBinary(data: ByteArray?): List<LayoutResult> {
        val results = ArrayList<LayoutResult>()
        if (data == null || data.isEmpty()) return results
        try {
            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val count = bb.long.toInt()
            for (i in 0 until count) {
                results.add(parseSingleLayoutBinary(bb))
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseBatchLayoutBinary failed", e)
        }
        return results
    }

    /** 从 ByteBuffer 当前位置解析单个 LayoutResult（bincode 单元素格式） */
    private fun parseSingleLayoutBinary(bb: ByteBuffer): LayoutResult {
        val result = LayoutResult()
        val pageCount = bb.long.toInt()
        result.pages = ArrayList(pageCount)
        for (pi in 0 until pageCount) {
            val pd = PageData()
            pd.content = readBincodeString(bb)
            pd.lineCount = bb.long.toInt()   // line_count 字段
            val lineCount = bb.long.toInt()  // Vec<LineMetric> 长度（必须消费，否则指针错位）
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
        return result
    }

    // ========== bincode 二进制解析（精确匹配 Rust bincode v1 格式） ==========

    fun parseLayoutBinary(data: ByteArray?): LayoutResult {
        val result = LayoutResult()
        if (data == null || data.isEmpty()) return result
        try {
            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            return parseSingleLayoutBinary(bb)
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

    /** 读取 bincode 中的 Option<String> (tag: 0=None, 1=Some) */
    private fun readOptionalBincodeString(bb: ByteBuffer): String? {
        val tag = bb.get().toInt()
        if (tag == 0) return null
        return readBincodeString(bb)
    }

    /** 读取 bincode 中的 boolean (u8: 0=false, else=true) */
    private fun readBincodeBoolean(bb: ByteBuffer): Boolean {
        return bb.get().toInt() != 0
    }

        // ========== bincode 二进制 TXT/EPUB 解析（替代 JSON 路径） ==========

    /** 从 bincode 二进制解析 TxtParser.ParseResult */
    fun parseTxtBinary(data: ByteArray?, file: File): TxtParser.ParseResult {
        val result = TxtParser.ParseResult()
        if (data == null || data.isEmpty()) return result
        if (data[0].toInt() == 1) {
            val errMsg = try { String(data.copyOfRange(1, data.size), StandardCharsets.UTF_8) } catch (e: Exception) { "Unknown error" }
            Log.w(TAG, "Rust TXT binary parser error: $errMsg")
            return result
        }
        try {
            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            result.bookTitle = readBincodeString(bb)
            result.encoding = readBincodeString(bb)
            val chapterCount = bb.long.toInt()
            result.chapters = ArrayList(chapterCount)
            for (i in 0 until chapterCount) {
                val title = readBincodeString(bb)
                val content = readBincodeString(bb)
                val lineStart = if (readBincodeBoolean(bb)) bb.long.toInt() else 0
                val lineEnd = if (readBincodeBoolean(bb)) bb.long.toInt() else 0
                val index = if (readBincodeBoolean(bb)) bb.long.toInt() else 0
                val chapter = Chapter(title, content, lineStart, lineEnd)
                chapter.index = index
                result.chapters.add(chapter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse bincode TXT result", e)
        }
        return result
    }

    /** 从 bincode 二进制解析 EpubResult */
    fun parseEpubBinary(data: ByteArray?, file: File): EpubResult {
        val result = EpubResult()
        if (data == null || data.isEmpty()) return result
        if (data[0].toInt() == 1) {
            val errMsg = try { String(data.copyOfRange(1, data.size), StandardCharsets.UTF_8) } catch (e: Exception) { "Unknown error" }
            Log.w(TAG, "Rust EPUB binary parser error: $errMsg")
            return result
        }
        try {
            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            result.title = readBincodeString(bb)
            result.author = readBincodeString(bb)
            result.encoding = readBincodeString(bb)
            val chapterCount = bb.long.toInt()
            result.chapters = ArrayList(chapterCount)
            for (i in 0 until chapterCount) {
                val title = readBincodeString(bb)
                val content = readOptionalBincodeString(bb) ?: ""
                val imageCount = bb.long.toInt()
                val imagePaths = ArrayList<String>(imageCount)
                for (j in 0 until imageCount) { imagePaths.add(readBincodeString(bb)) }
                val paraTypeCount = bb.long.toInt()
                val paragraphTypes = ArrayList<Int>(paraTypeCount)
                // Rust 侧为 Vec<i32>：每元素 4 字节，必须用 bb.int 读取（原 bb.long 8 字节导致后续章节全部错位）
                for (j in 0 until paraTypeCount) { paragraphTypes.add(bb.int) }
                val xhtmlPath = readOptionalBincodeString(bb)
                val chapter = Chapter(title, content)
                chapter.index = i
                chapter.xhtmlPath = xhtmlPath
                chapter.setImagePaths(imagePaths)
                chapter.setParagraphTypes(paragraphTypes)
                result.chapters.add(chapter)
            }
            val imageMapCount = bb.long.toInt()
            result.images.clear()
            for (i in 0 until imageMapCount) {
                val key = readBincodeString(bb)
                val value = readBincodeString(bb)
                result.images[key] = Base64.decode(value, Base64.DEFAULT)
            }
            if (result.title.isEmpty() && file != null) {
                val name = file.name
                val dot = name.lastIndexOf('.')
                result.title = if (dot > 0) name.substring(0, dot) else name
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse bincode EPUB result", e)
        }
        return result
    }

    /** 使用二进制路径解析 TXT（纯 bincode 路径，无 JSON fallback） */
    fun parseTxtWithBinary(file: File, forcedEncoding: String?): TxtParser.ParseResult {
        if (!sLibraryLoaded) throw IllegalStateException("Rust core library not loaded")
        try {
            val binary = nativeParseTxtBinary(file.absolutePath, forcedEncoding ?: "")
            return parseTxtBinary(binary, file)
        } catch (e: Exception) {
            Log.e(TAG, "Rust TXT binary parse failed", e)
            throw RuntimeException("TXT binary parse failed", e)
        }
    }

    /** 使用二进制路径解析 EPUB（纯 bincode 路径，无 JSON fallback） */
    fun parseEpubWithBinary(file: File): EpubResult {
        if (!sLibraryLoaded) throw IllegalStateException("Rust core library not loaded")
        try {
            val binary = nativeParseEpubBinary(file.absolutePath)
            return parseEpubBinary(binary, file)
        } catch (e: Exception) {
            Log.e(TAG, "Rust EPUB binary parse failed", e)
            throw RuntimeException("EPUB binary parse failed", e)
        }
    }

    // ========== Rust 文本布局（双路径 + LRU 缓存） ==========

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

    // ========== Benchmark 工具类 ==========

    /** 性能对比结果 */
    data class BenchmarkResult(
        var javaNs: Long = 0,
        var binaryNs: Long = 0,
        var pages: Int = 0,
        var tag: String? = null
    ) {
        fun summary(): String {
            return String.format("[%s] pages=%d  Java=%.2fms  Binary=%.2fms  speedup=%.1fx",
                    tag, pages, javaNs / 1e6, binaryNs / 1e6,
                    if (javaNs > 0 && binaryNs > 0) javaNs.toDouble() / binaryNs else 0.0)
        }
    }

    fun benchmarkLayout(text: String, width: Int, height: Int,
           fontSize: Float, lineSpacing: Float, paragraphSpacing: Float,
           indent: Boolean, paddingLeft: Float, paddingTop: Float): BenchmarkResult {
        val br = BenchmarkResult()
        br.tag = "LayoutBench"
        br.javaNs = 0

        synchronized(this@NativeBridge) { lruCache.clear() }
        val start2 = System.nanoTime()
        val r2 = layoutTextBinary(text, width.toFloat(), height.toFloat(),
                fontSize, lineSpacing, paragraphSpacing, indent, paddingLeft, paddingTop)
        br.binaryNs = System.nanoTime() - start2
        br.pages = r2.totalPages

        Log.i(TAG, br.summary())
        return br
    }

    // ========== 缓存私有方法 ==========

    private fun cacheGet(key: LayoutKey): LayoutResult? = lruCache[key]
    private fun cachePut(key: LayoutKey, result: LayoutResult) { lruCache[key] = result }
}

