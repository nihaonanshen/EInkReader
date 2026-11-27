package com.einkreader.core

import android.util.Log
import com.einkreader.core.Constants
import com.einkreader.core.model.Chapter
import com.einkreader.core.model.EpubResult
import com.einkreader.core.model.TocItem
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

    // ========== 布局结果 POJO（[review-#46] 纯 bincode 二进制路径，旧 JSON 双路径已移除） ==========

    /** 单行坐标（由 Rust 计算，Java 直接绘制） */
    class LineMetric {
        var text: String? = null
        var x: Float = 0f
        var y: Float = 0f
        var width: Float = 0f
        var height: Float = 0f
        var isParagraphEnd: Boolean = false
        var isFirstInParagraph: Boolean = false
        /// 段落样式类型（与 Rust LineMetric.para_type / Java Chapter.PARA_* 一致：
        /// 0 普通 / 1 H1 / 2 H2 / 3 H3 / 4 引用块）
        var paraType: Int = 0

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

    /** 单页数据（[P1-#35/36] 已移除 content 字段：Java 按 LineMetric 逐行绘制，无每页全文消费者） */
    class PageData {
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

    // [P2-7] 缓存键只持文本哈希（4 字节 Int），不再持整章文本——
    // 旧键 val text: String? 使 LRU 8 项 × 整章全文驻留。
    // 碰撞语义：两章不同文本若 String.hashCode() 相同会命中同一缓存项；
    // 8 项 LRU 下概率 ~1e-8，对墨水屏排版可接受。
    data class LayoutKey(
        val textHash: Int,
        val maxWidthPx: Float,
        val maxHeightPx: Float,
        val fontSizePx: Float,
        val lineSpacing: Float,
        val paragraphSpacing: Float,
        val firstLineIndent: Boolean,
        val paddingLeft: Float,
        val paddingTop: Float,
        // [P1-2] 段落样式数组（可空）：同文本不同样式 → 不同布局结果，必须参与缓存键比较
        val paraTypes: IntArray? = null
    ) {
        override fun hashCode(): Int {
            // [review-#13] Float 字段用 floatToIntBits 全位参与，不再 .toInt() 截断——
            // 同整部不同小数（100.4 vs 100.9）不再产生系统性哈希碰撞
            var hc = textHash
            hc = 31 * hc + java.lang.Float.floatToIntBits(maxWidthPx)
            hc = 31 * hc + java.lang.Float.floatToIntBits(maxHeightPx)
            hc = 31 * hc + java.lang.Float.floatToIntBits(fontSizePx)
            hc = 31 * hc + java.lang.Float.floatToIntBits(lineSpacing)
            hc = 31 * hc + java.lang.Float.floatToIntBits(paragraphSpacing)
            hc = 31 * hc + if (firstLineIndent) 1 else 0
            hc = 31 * hc + java.lang.Float.floatToIntBits(paddingLeft)
            hc = 31 * hc + java.lang.Float.floatToIntBits(paddingTop)
            hc = 31 * hc + java.util.Arrays.hashCode(paraTypes)
            return hc
        }

        // data class 对数组字段按引用比较，必须手工按内容比较，否则同键不同数组会命中错误缓存
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is LayoutKey) return false
            return textHash == other.textHash &&
                maxWidthPx == other.maxWidthPx &&
                maxHeightPx == other.maxHeightPx &&
                fontSizePx == other.fontSizePx &&
                lineSpacing == other.lineSpacing &&
                paragraphSpacing == other.paragraphSpacing &&
                firstLineIndent == other.firstLineIndent &&
                paddingLeft == other.paddingLeft &&
                paddingTop == other.paddingTop &&
                java.util.Arrays.equals(paraTypes, other.paraTypes)
        }
    }

    // ========== LRU Cache ==========

    private val lruCache: MutableMap<LayoutKey, LayoutResult> =
        object : LinkedHashMap<LayoutKey, LayoutResult>(4, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LayoutKey, LayoutResult>?): Boolean {
                // ✅ [P2-3 2026-10-15] 容量收敛到 Constants.LAYOUT_CACHE_ENTRIES（原硬编码 20）：
                // 每条 LayoutResult 含整章全部页 LineMetric，小内存设备按页数窗口收敛
                return size > Constants.LAYOUT_CACHE_ENTRIES
            }
        }

    /** [P0-#27] 缓存锁：lruCache 被主线程（isLayoutCachedInternal/draw）与阅读后台线程
     *  （layoutTextBinary）并发访问，accessOrder LinkedHashMap 无同步时 LRU 链会损坏 */
    private val cacheLock = Any()

    // ========== JNI 原生方法声明 ==========

    external fun nativeDetectEncoding(data: ByteArray, len: Int): String
    external fun nativeParseTxtBinary(filePath: String, forcedEncoding: String): ByteArray
    external fun nativeParseEpubBinary(filePath: String): ByteArray
    external fun nativeLayoutTextBinary(
        text: String, paraTypes: IntArray?,
        maxWidthPx: Float, maxHeightPx: Float,
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
        firstLineIndent: Boolean, paddingLeft: Float, paddingTop: Float,
        paraTypes: IntArray? = null
    ): Boolean {
        val key = LayoutKey(text?.hashCode() ?: 0, maxWidthPx, maxHeightPx, fontSizePx,
                lineSpacing, paragraphSpacing, firstLineIndent, paddingLeft, paddingTop, paraTypes)
        return synchronized(cacheLock) { lruCache.containsKey(key) }
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
        return try {
            val result = nativeLoadEpubChapterContent(filePath, chapterXhtmlPath)
            Log.d(TAG, "loadEpubChapterContent: path=$chapterXhtmlPath, resultLen=${result.length}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "loadEpubChapterContent failed for $chapterXhtmlPath: ${e.message}", e)
            throw e
        }
    }

    // 批量布局（[review-#46] bincode 版 — 输入输出均为 bincode 字节流，过期 JSON 注释已清理）
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
            val count = readBincodeCount(bb, "batchCount")
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
        val pageCount = readBincodeCount(bb, "pageCount")
        result.pages = ArrayList(pageCount)
        for (pi in 0 until pageCount) {
            // [P1-#35/36] 与 Rust PageData 对齐：content 字段已移除（不再逐页回传全文）
            val pd = PageData()
            pd.lineCount = bb.long.toInt()   // line_count 字段
            val lineCount = readBincodeCount(bb, "lineCount")  // Vec<LineMetric> 长度（必须消费，否则指针错位）
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
                lm.paraType = bb.int
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
        // [P0-#12] 负数 / 超 remaining 的长度都是截断或损坏的字节流：原代码直接
        // 用负数（按无符号理解即巨大值）分配 ByteArray → OOM / 崩溃
        if (byteLen < 0 || byteLen > bb.remaining()) {
            throw java.io.IOException(
                "Truncated bincode payload: string len=$byteLen, remaining=${bb.remaining()}")
        }
        val strBytes = ByteArray(byteLen)
        bb.get(strBytes)
        return String(strBytes, StandardCharsets.UTF_8)
    }

    /** [P0-#12] 带范围检查地读取 bincode u64 计数前缀：
     *  负数或超过 remaining 的计数视为截断字节流（每个元素至少消耗 1 字节） */
    private fun readBincodeCount(bb: ByteBuffer, what: String): Int {
        val v = bb.long.toInt()
        if (v < 0 || v > bb.remaining()) {
            throw java.io.IOException("Truncated bincode payload: $what=$v, remaining=${bb.remaining()}")
        }
        return v
    }

    /** 读取 bincode 中的 Option<String> (tag: 0=None, 1=Some) */
    private fun readOptionalBincodeString(bb: ByteBuffer): String? {
        val tag = bb.get().toInt()
        if (tag == 0) return null
        return readBincodeString(bb)
    }

    /** 递归读取单个 TocItem（bincode：title/href + children 数量 + 递归 children） */
    private fun readTocItem(bb: ByteBuffer): TocItem {
        val item = TocItem()
        item.title = readBincodeString(bb)
        item.href = readBincodeString(bb)
        val childrenCount = readBincodeCount(bb, "tocChildren")
        item.children = ArrayList(childrenCount)
        for (j in 0 until childrenCount) {
            item.children.add(readTocItem(bb))
        }
        return item
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
        // [P0-#1] 显式状态字节协议：data[0]=0 成功（其余为 bincode payload）；=1 错误（其余为 UTF-8 消息）。
        // 旧版以整个字节流首字节是否为 1 判错，合法 payload 首字节恰好为 1 时会被误判
        val status = data[0].toInt()
        if (status == 1) {
            val errMsg = try { String(data.copyOfRange(1, data.size), StandardCharsets.UTF_8) } catch (e: Exception) { "Unknown error" }
            Log.w(TAG, "Rust TXT binary parser error: $errMsg")
            return result
        }
        if (status != 0) {
            Log.w(TAG, "Rust TXT binary result: unexpected status byte $status")
            return result
        }
        try {
            val bb = ByteBuffer.wrap(data, 1, data.size - 1).order(ByteOrder.LITTLE_ENDIAN)
            result.bookTitle = readBincodeString(bb)
            result.encoding = readBincodeString(bb)
            val chapterCount = readBincodeCount(bb, "chapterCount")
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
        // [P0-#1] 显式状态字节协议：data[0]=0 成功（其余为 bincode payload）；=1 错误（其余为 UTF-8 消息）
        val status = data[0].toInt()
        if (status == 1) {
            val errMsg = try { String(data.copyOfRange(1, data.size), StandardCharsets.UTF_8) } catch (e: Exception) { "Unknown error" }
            Log.w(TAG, "Rust EPUB binary parser error: $errMsg")
            return result
        }
        if (status != 0) {
            Log.w(TAG, "Rust EPUB binary result: unexpected status byte $status")
            return result
        }
        try {
            val bb = ByteBuffer.wrap(data, 1, data.size - 1).order(ByteOrder.LITTLE_ENDIAN)
            result.title = readBincodeString(bb)
            result.author = readBincodeString(bb)
            result.encoding = readBincodeString(bb)
            val chapterCount = readBincodeCount(bb, "chapterCount")
            result.chapters = ArrayList(chapterCount)
            for (i in 0 until chapterCount) {
                val title = readBincodeString(bb)
                val content = readOptionalBincodeString(bb) ?: ""
                val imageCount = readBincodeCount(bb, "imageCount")
                val imagePaths = ArrayList<String>(imageCount)
                for (j in 0 until imageCount) { imagePaths.add(readBincodeString(bb)) }
                val paraTypeCount = readBincodeCount(bb, "paraTypeCount")
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
            // [P1-#2] imageMap: HashMap<String, Vec<u8>>（原始字节，不再 base64）——
            // bincode 线上值与原 String 同形（u64 长度 + 字节），此处直接读字节
            val imageMapCount = readBincodeCount(bb, "imageMapCount")
            result.images.clear()
            for (i in 0 until imageMapCount) {
                val key = readBincodeString(bb)
                val len = readBincodeCount(bb, "imageBytes")
                val bytes = ByteArray(len)
                bb.get(bytes)
                result.images[key] = bytes
            }
            // 解析目录树：Vec<TocItem>（递归支持多级嵌套）
            val tocItemCount = readBincodeCount(bb, "tocItemCount")
            result.tocItems = ArrayList(tocItemCount)
            for (i in 0 until tocItemCount) {
                result.tocItems.add(readTocItem(bb))
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
        firstLineIndent: Boolean, paddingLeft: Float, paddingTop: Float,
        // [P1-2] 段落样式数组（null/空 = 全部普通段；与文本段落 1:1 对齐）
        paraTypes: IntArray? = null
    ): LayoutResult {
        // [P2-7] 键只持文本哈希，不在缓存键上保留整章文本
        val key = LayoutKey(text.hashCode(), maxWidthPx, maxHeightPx, fontSizePx,
                lineSpacing, paragraphSpacing, firstLineIndent, paddingLeft, paddingTop, paraTypes)
        cacheGet(key)?.let { return it }
        if (!sLibraryLoaded) return LayoutResult()
        return try {
            val binary = nativeLayoutTextBinary(text, paraTypes, maxWidthPx, maxHeightPx,
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

        synchronized(cacheLock) { lruCache.clear() }
        val start2 = System.nanoTime()
        val r2 = layoutTextBinary(text, width.toFloat(), height.toFloat(),
                fontSize, lineSpacing, paragraphSpacing, indent, paddingLeft, paddingTop)
        br.binaryNs = System.nanoTime() - start2
        br.pages = r2.totalPages

        Log.i(TAG, br.summary())
        return br
    }

    // ========== 缓存私有方法 ==========

    private fun cacheGet(key: LayoutKey): LayoutResult? = synchronized(cacheLock) { lruCache[key] }
    private fun cachePut(key: LayoutKey, result: LayoutResult) { synchronized(cacheLock) { lruCache[key] = result } }
}

