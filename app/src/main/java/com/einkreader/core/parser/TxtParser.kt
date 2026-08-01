package com.einkreader.core.parser

import android.util.Log
import com.einkreader.core.model.Chapter
import com.einkreader.core.NativeBridge
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * TXT 文件解析器 (Kotlin)
 *
 * 功能：
 * 1. 自动检测编码（GBK/UTF-8/Big5）
 * 2. 按中文章节标题自动分章（如"第一章"、"第一回"等）
 * 3. 解析结果缓存到文件，第二次打开秒开
 */
class TxtParser {
    companion object {
        private const val TAG = "TxtParser"
        private const val DEFAULT_CHAPTER_SIZE = 3000 // 无标题时按3000字一章
        private const val CACHE_DIR_NAME = "txt_parse_cache"
        private const val CACHE_VERSION = "v3"  // v3: 移除 fullContent 急切加载，改为懒加载
        private const val MAX_FILE_SIZE = 50L * 1024 * 1024  // 50 MB 上限

        // ===== 章节标题正则（综合版，覆盖主流中文小说格式）=====
        @JvmField
        val CHAPTER_PATTERN: Pattern = Pattern.compile(
            "^[\\s\\u3000]*[【\\-―※（(\\[]*" +
            "第[零一二三四五六七八九十百千万亿\\d]{1,8}[章节回卷集篇部折]" +
            "[）)〕\\]}]?[\\s\\u3000]*" +
            "(?:[】\\-―※]*[\\s\\u3000]*(\\S.*))?" +
            "[\\s\\u3000]*$"
        )

        @JvmField
        val LOOSE_CHAPTER_PATTERN: Pattern = Pattern.compile(
            "^\\s*第[零一二三四五六七八九十百千万亿\\d]{1,8}[章节回卷集篇部折]$"
        )

        // 新增：匹配英文 Chapter 1 / Chapter One / Ch.1 格式
        @JvmField
        val ENG_CHAPTER_PATTERN: Pattern = Pattern.compile(
            "^(?i)(chapter|chap|ch|section|sec|part|lesson|unit|volume|vol|module|lecture)" +
            "[.\\-:\\s]+(\\d+|[a-z]+(?:\\s[a-z]+){0,3})" +
            "(?:[.\\-:\\s]+[A-Za-z].*)?[\\s\\u3000]*$"
        )

        // 新增：匹配 "VOL.1" "Volume 1" 格式
        @JvmField
        val VOLUME_PATTERN: Pattern = Pattern.compile(
            "^(?i)(volume|vol)\\s*\\.?\\s*[\\d]+(?:[.:\\s]+.*)?$"
        )

        // 新增：匹配 "楔子" "序章" "引子" "后记" "尾声" "番外" 等
        @JvmField
        val SPECIAL_CHAPTER_PATTERN: Pattern = Pattern.compile(
            "^[\\s\\u3000]*(?:楔子|序章|序言|引子|前言|前奏|序幕|开篇|开场|写在前面|题记)" +
            "[\\s\\u3000]*(?:[\\S\\u3000].*)?[\\s\\u3000]*$|" +
            "^[\\s\\u3000]*(?:后记|尾声|终章|结局|结语|番外|外传|特别篇|附录|附注|致谢)" +
            "[\\s\\u3000]*(?:[\\S\\u3000].*)?[\\s\\u3000]*$"
        )

        // 编码回退列表
        val FALLBACK_ENCODINGS = arrayOf("GBK", "GB18030", "UTF-8", "Big5", "GB2312")

        // 收紧：匹配纯数字章节（数字+分隔符+汉字标题）。
        // 阿拉伯数字编号只允许顿号/句点分隔且标题至少 8 字，避免把正文数字列表（如"1.他们来了"、"3.然后搅拌均匀"）误判为章节；
        // 中文数字编号（一、/一百零八、）在正文中罕见，保留空格分隔，标题至少 2 字。
        @JvmField
        val NUM_CHAPTER_PATTERN: Pattern = Pattern.compile(
            "^[\\s\\u3000]*(?:" +
            "[零一二三四五六七八九十百千万亿]{1,8}[、．.\\s\\u3000][\\u4e00-\\u9fff]{2,30}" +
            "|[\\d]{1,3}[、．.][\\u4e00-\\u9fff]{8,30}" +
            ")[\\s\\u3000]*$"
        )

        // 装饰性标题
        @JvmField
        val DECORATED_CHAPTER_PATTERN: Pattern = Pattern.compile(
            "^[\\s\\u3000]*" +
            "[\\u2500-\\u257F\\u25c6\\u25c7\\u25ce\\u25b2\\u25b3\\u25bd\\u25bc\\u25cb\\u25cf\\u25a1\\u25a4\\u2606\\u2605\\u203b\\u203c\\u2049\\u2a2f\\u2217\\u2261\\u005f\\u002a\\u0023\\-\\s\\u3000]{0,15}" +
            "第[零一二三四五六七八九十百千万亿\\d]{1,8}[章节回卷集篇部折]" +
            ".*$"
        )

        // 第X章必须在行首附近（补齐 .*$ 尾部，使整行匹配语义下能带副标题/后缀）
        @JvmField
        val ANYWHERE_CHAPTER_PATTERN: Pattern = Pattern.compile(
            "^[\\s\\u3000\\u2500-\\u257F\\u25c6\\u25c7\\u25ce\\u25b2\\u25b3\\u25bd\\u25bc\\u25cb\\u25cf\\u25a1\\u25a4\\u2606\\u2605\\u203b\\u203c\\u2049\\u2a2f\\u2217\\u2261\\u005f\\u002a\\u0023\\-]{0,10}" +
            "第[零一二三四五六七八九十百千万亿\\d]{1,8}[章节回卷集篇部折].*$"
        )

        // 严格版模式
        @JvmField
        val STRICT_CN_PATTERN: Pattern = Pattern.compile(
            "^[\\s\\u3000]*[【\\-―※（(\\[]*" +
            "第[零一二三四五六七八九十百千万亿\\d]{1,8}[章节回卷集篇部折]" +
            "[）)〕\\]}]?[\\s\\u3000]*" +
            "(?:[】\\-―※]*[\\s\\u3000]*(\\S.*))?" +
            "[\\s\\u3000]*$"
        )

        @JvmField
        val STRICT_EN_PATTERN: Pattern = Pattern.compile(
            "^(?i)(chapter|chap|ch|section|sec|part|lesson|unit|volume|vol|module|lecture)" +
            "[.\\-:\\s]*[\\d零一二三四五六七八九十百千]+" +
            "(?:[.\\-:\\s]+[A-Za-z].*)?[\\s\\u3000]*$"
        )

        // 🔥 组合正则（所有8种模式合并为一个），消除 O(n×m) 多次匹配
        @JvmField
        val COMBINED_CHAPTER_PATTERN: Pattern = Pattern.compile(
            "(?:" + CHAPTER_PATTERN.pattern() + ")|" +
            "(?:" + LOOSE_CHAPTER_PATTERN.pattern() + ")|" +
            "(?:" + ENG_CHAPTER_PATTERN.pattern() + ")|" +
            "(?:" + SPECIAL_CHAPTER_PATTERN.pattern() + ")|" +
            "(?:" + VOLUME_PATTERN.pattern() + ")|" +
            "(?:" + NUM_CHAPTER_PATTERN.pattern() + ")|" +
            "(?:" + DECORATED_CHAPTER_PATTERN.pattern() + ")|" +
            "(?:" + ANYWHERE_CHAPTER_PATTERN.pattern() + ")"
        )

        // 🔥 严格模式组合正则
        @JvmField
        val COMBINED_STRICT_PATTERN: Pattern = Pattern.compile(
            "(?:" + STRICT_CN_PATTERN.pattern() + ")|" +
            "(?:" + STRICT_EN_PATTERN.pattern() + ")|" +
            "(?:" + SPECIAL_CHAPTER_PATTERN.pattern() + ")"
        )

        // 缓存目录
        private var sCacheBaseDir: File? = null

        // 文件级解析锁
        private val sParseLocks: MutableMap<String, Any> = ConcurrentHashMap()

        @JvmStatic
        fun initCacheDir(appCacheDir: File) {
            sCacheBaseDir = File(appCacheDir, CACHE_DIR_NAME)
            if (!checkNotNull(sCacheBaseDir).exists() && !checkNotNull(sCacheBaseDir).mkdirs()) {
                Log.w(TAG, "缓存目录创建失败: ${checkNotNull(sCacheBaseDir).absolutePath}")
            }
        }

        /** 解析 TXT 文件 */
        @JvmStatic
        @Throws(IOException::class)
        fun parse(file: File): ParseResult {
            return parse(file, null)
        }

        @JvmStatic
        @Throws(IOException::class)
        fun parse(file: File, forcedEncoding: String?): ParseResult {
            val lockKey = file.absolutePath
            val lock = sParseLocks.computeIfAbsent(lockKey) { Any() }
            synchronized(lock) {
                try {
                    val cached = readCache(file, forcedEncoding)
                    if (cached != null) return cached
                    val result = doParse(file, forcedEncoding)
                    writeCache(file, result, forcedEncoding)
                    return result
                } finally {
                    sParseLocks.remove(lockKey, lock)
                }
            }
        }

        @JvmStatic
        fun extractTitle(file: File): String {
            val name = file.name
            val dot = name.lastIndexOf('.')
            return if (dot > 0) name.substring(0, dot) else name
        }

        @JvmStatic
        @Throws(IOException::class)
        private fun doParse(file: File, forcedEncoding: String?): ParseResult {
            val result = ParseResult()
            result.bookTitle = extractTitle(file)
            com.einkreader.ui.reader.DebugLog.log("Txt", "解析: " + file.name + " 大小=" + file.length())

            if (file.length() > MAX_FILE_SIZE) {
                throw IOException("File too large: " + file.length() + " bytes")
            }

            // 一次读取全文件到 byte[]
            val fileBytes: ByteArray
            FileInputStream(file).use { fis ->
                val baos = ByteArrayOutputStream(Math.max(8192, Math.min(file.length(), Int.MAX_VALUE.toLong()).toInt()))
                val buf = ByteArray(8192)
                var n: Int
                while (fis.read(buf).also { n = it } != -1) {
                    baos.write(buf, 0, n)
                }
                fileBytes = baos.toByteArray()
            }

            // 检测编码
            val encoding = if (forcedEncoding != null && forcedEncoding.isNotEmpty()) {
                forcedEncoding
            } else {
                NativeBridge.bridgeInstance.detectEncoding(fileBytes, fileBytes.size)
            }
            result.encoding = encoding

            // 尝试解码 + 章节检测，编码不对时自动回退
            var fullText = tryDecodeAndDetect(fileBytes, fileBytes.size, encoding, result)
            if (fullText == null) {
                for (fallback in FALLBACK_ENCODINGS) {
                    if (fallback.equals(encoding, ignoreCase = true)) continue
                    val decoded = tryDecodeAndDetect(fileBytes, fileBytes.size, fallback, result)
                    if (decoded != null) {
                        result.encoding = fallback
                        break
                    }
                }
            }
            if (fullText == null) {
                // 所有编码都失败，用 UTF-8 兜底
                try {
                    fullText = String(fileBytes, Charset.forName("UTF-8"))
                    result.encoding = "UTF-8"
                } catch (e: Exception) {
                    fullText = ""
                }
                return result
            }

            // 构建行偏移数组
            val lineOffsets = buildLineOffsets(fullText)
            val lineCount = lineOffsets.size - 1

            // 检测章节标题
            val chapterBreaks: MutableList<IntArray> = ArrayList()
            val chapterTitles: MutableList<String> = ArrayList()
            val detectedCount = scanChapterTitles(fullText, lineOffsets, chapterBreaks, chapterTitles, false)

            // 后处理：如果检测到章节数量异常多，或相邻章节间隔过密，可能误判。
            // 保留宽松模式结果，用严格模式重扫；仅当严格模式标题数显著减少（确认宽松误判）时才采用严格结果，
            // 避免 200+ 章的长篇小说（网文常见）被误切后丢失 "VOL.1" 等宽松格式标题。
            if (detectedCount > 200 || hasSuspiciousDensity(chapterBreaks, lineCount)) {
                com.einkreader.ui.reader.DebugLog.log("Txt", "章节密度异常 ($detectedCount)，尝试严格模式重扫")
                val looseBreaks: MutableList<IntArray> = ArrayList(chapterBreaks)
                val looseTitles: MutableList<String> = ArrayList(chapterTitles)
                chapterBreaks.clear()
                chapterTitles.clear()
                val strictCount = scanChapterTitles(fullText, lineOffsets, chapterBreaks, chapterTitles, true)
                if (strictCount * 10 > looseBreaks.size * 9) {
                    // 严格模式没有显著减少 → 大概率是真实长书，保留宽松结果
                    chapterBreaks.clear()
                    chapterTitles.clear()
                    chapterBreaks.addAll(looseBreaks)
                    chapterTitles.addAll(looseTitles)
                }
            }
            if (chapterBreaks.isNotEmpty()) {
                val last = chapterBreaks[chapterBreaks.size - 1]
                last[1] = lineCount
            }

            // 合并多行标题：仅当下一行是"短副标题"形态才合并（不含句读/引号/括号标点、
            // 不以句末标点结尾、长度 <= 12）。防止 "第X章" 独占行 + 正文紧跟时把正文第一行吞进标题。
            for (i in chapterTitles.indices) {
                val title = chapterTitles[i] ?: continue
                if (title.length < 12 && LOOSE_CHAPTER_PATTERN.matcher(title).matches()) {
                    val breaks = chapterBreaks[i]
                    val firstContentLine = breaks[0]
                    if (firstContentLine >= 0 && firstContentLine < lineCount) {
                        val nextLine = extractLine(fullText, lineOffsets, firstContentLine)
                        val trimmedNext = nextLine.trim()
                        if (trimmedNext.isNotEmpty() && trimmedNext.length <= 12 &&
                            !isChapterTitle(trimmedNext) && !trimmedNext.startsWith("[[IMAGE:") &&
                            !trimmedNext.any { it in "。，！？；、：…“”‘’\"'「」『』()（）" }) {
                            chapterTitles[i] = title + " " + trimmedNext
                            breaks[0] = firstContentLine + 1
                        }
                    }
                }
            }

            // 构建章节列表
            if (chapterBreaks.isNotEmpty()) {
                for (i in chapterBreaks.indices) {
                    val startLine = chapterBreaks[i][0]
                    val endLine = chapterBreaks[i][1].takeIf { it >= 0 } ?: lineCount
                    val clampedEnd = if (endLine > lineCount) lineCount else endLine
                    val content = extractLines(fullText, lineOffsets, startLine, clampedEnd)
                    val title = chapterTitles[i]
                    val finalTitle = if (title.isNullOrEmpty()) {
                        if (i == 0) "引子" else "第${i + 1}章"
                    } else {
                        title
                    }
                    result.chapters.add(Chapter(finalTitle, content, startLine, clampedEnd))
                }
            }

            // 没找到章节标题时，按固定字数分割
            if (result.chapters.isEmpty()) {
                com.einkreader.ui.reader.DebugLog.log("Txt", "未检测到章节标题！回退到按字数分割。")
                val allLines = ArrayList<String>(lineCount)
                for (li in 0 until lineCount) {
                    allLines.add(extractLine(fullText, lineOffsets, li))
                }
                result.chapters = splitBySize(allLines, DEFAULT_CHAPTER_SIZE) as MutableList<Chapter>
            } else {
                com.einkreader.ui.reader.DebugLog.log("Txt", "检测到章节: " + result.chapters.size + "个")
            }

            Log.i(TAG, "解析完成: " + file.name + " \u2192 " + result.chapters.size + "章 编码=" + result.encoding)
            return result
        }

        private fun tryDecodeAndDetect(data: ByteArray, len: Int, encoding: String, resultHint: ParseResult): String? {
            try {
                val text = String(data, 0, len, Charset.forName(encoding))
                if (text.isEmpty()) return null

                var chineseCount = 0
                var replacementCount = 0
                val totalChars = Math.min(text.length, 10000)
                for (i in 0 until totalChars) {
                    val c = text[i]
                    if (c in '\u4E00'..'\u9FFF') chineseCount++
                    else if (c == '\uFFFD') replacementCount++
                }

                if (totalChars > 200 && replacementCount * 100 / totalChars > 10) {
                    com.einkreader.ui.reader.DebugLog.log("Txt", "编码 $encoding 替换字符过多 ($replacementCount/$totalChars)，尝试下一个")
                    return null
                }

                if (totalChars > 200 && chineseCount == 0) {
                    var asciiCount = 0
                    for (i in 0 until totalChars) {
                        if (text[i].code <= 0x7F) asciiCount++
                    }
                    val asciiRatio = asciiCount * 100 / totalChars
                    if (asciiRatio < 90) {
                        com.einkreader.ui.reader.DebugLog.log("Txt", "编码 $encoding 无中文也非纯 ASCII (ASCII 占比=$asciiRatio%)，尝试下一个")
                        return null
                    }
                }

                resultHint.encoding = encoding
                return text
            } catch (e: Exception) {
                return null
            }
        }

        private fun buildLineOffsets(text: String): IntArray {
            var lineCount = 1
            for (i in text.indices) {
                if (text[i] == '\n') lineCount++
            }
            val offsets = IntArray(lineCount + 1)
            var idx = 0
            offsets[idx++] = 0
            for (i in text.indices) {
                if (text[i] == '\n') offsets[idx++] = i + 1
            }
            offsets[idx] = text.length
            return offsets
        }

        private fun extractLine(text: String, offsets: IntArray, lineIdx: Int): String {
            var start = offsets[lineIdx]
            var end = offsets[lineIdx + 1]
            if (end > start && text[end - 1] == '\n') end--
            if (end > start && text[end - 1] == '\r') end--
            return text.substring(start, end)
        }

        private fun extractLines(text: String, offsets: IntArray, startLine: Int, endLine: Int): String {
            val start = offsets[startLine]
            val end = if (endLine < offsets.size) offsets[endLine] else text.length
            return text.substring(start, end)
        }

        private fun scanChapterTitles(fullText: String, lineOffsets: IntArray,
                                      chapterBreaks: MutableList<IntArray>,
                                      chapterTitles: MutableList<String>,
                                      strict: Boolean): Int {
            var count = 0
            val lineCount = lineOffsets.size - 1
            for (li in 0 until lineCount) {
                val lineText = extractLine(fullText, lineOffsets, li)
                val isTitle = if (strict) isStrictChapterTitle(lineText) else isChapterTitle(lineText)
                if (isTitle) {
                    count++
                    if (chapterBreaks.isEmpty() && li > 0) {
                        chapterBreaks.add(intArrayOf(0, li))
                        chapterTitles.add("")
                    } else if (chapterBreaks.isNotEmpty()) {
                        val prev = chapterBreaks[chapterBreaks.size - 1]
                        prev[1] = li
                    }
                    chapterBreaks.add(intArrayOf(li + 1, -1))
                    chapterTitles.add(cleanTitle(extractChapterTitle(lineText)) ?: "")
                }
            }
            return count
        }

        private fun hasSuspiciousDensity(chapterBreaks: List<IntArray>, lineCount: Int): Boolean {
            if (chapterBreaks.size < 10) return false
            var shortGap = 0
            var totalGap = 0
            for (i in 0 until chapterBreaks.size - 1) {
                val cur = chapterBreaks[i]
                val next = chapterBreaks[i + 1]
                val gap = next[0] - cur[0]
                if (gap <= 0) continue
                totalGap++
                if (gap < 3) shortGap++
            }
            return totalGap > 0 && shortGap * 100 > totalGap * 30
        }

        private fun isChapterTitle(line: String?): Boolean {
            if (line.isNullOrEmpty()) return false
            val trimmed = line.trim()
            if (trimmed.length > 80) return false
            // 使用单一组合正则，覆盖所有章节标题格式
            return COMBINED_CHAPTER_PATTERN.matcher(trimmed).matches()
        }

        private fun isStrictChapterTitle(line: String?): Boolean {
            if (line.isNullOrEmpty()) return false
            val trimmed = line.trim()
            if (trimmed.length > 80) return false
            // 使用严格模式组合正则
            return COMBINED_STRICT_PATTERN.matcher(trimmed).matches()
        }

        private fun extractChapterTitle(line: String?): String {
            if (line.isNullOrEmpty()) return ""
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return trimmed
            if (trimmed.length > 80) return ""
            // 使用单一组合正则提取标题
            if (COMBINED_CHAPTER_PATTERN.matcher(trimmed).matches()) return trimmed
            // 宽松模式未命中时回退严格模式（覆盖 "Chapter 三" 等严格支持、宽松不支持的格式）
            if (COMBINED_STRICT_PATTERN.matcher(trimmed).matches()) return trimmed
            return ""
        }

        private fun cleanTitle(title: String?): String? {
            if (title.isNullOrEmpty()) return title
            var cleaned = title.trim()
            cleaned = cleaned.replaceFirst("^[【\\[\\-―※（(]+".toRegex(), "")
            cleaned = cleaned.replaceFirst("[】\\]\\-―※）)]+$".toRegex(), "")
            cleaned = cleaned.replaceFirst("[(（][完上中下续终]?[)）]$".toRegex(), "")
            cleaned = cleaned.replace("[\\s\\u3000]+".toRegex(), " ").trim()
            return cleaned
        }

        private fun splitBySize(lines: List<String>, charsPerChapter: Int): List<Chapter> {
            val chapters = ArrayList<Chapter>()
            val current = StringBuilder()
            var chapterNum = 1
            var lineStart = 0
            for (i in lines.indices) {
                current.append(lines[i]).append("\n")
                if (current.length >= charsPerChapter) {
                    chapters.add(Chapter("第${chapterNum}段", current.toString(), lineStart, i + 1))
                    current.setLength(0)
                    lineStart = i + 1
                    chapterNum++
                }
            }
            if (current.length > 0) {
                chapters.add(Chapter("第${chapterNum}段", current.toString(), lineStart, lines.size))
            }
            return chapters
        }

        // ===== 缓存机制 =====

        private fun getCacheKey(file: File, forcedEncoding: String?): String {
            return "$CACHE_VERSION|${file.absolutePath}|${file.length()}|${file.lastModified()}|${forcedEncoding ?: "auto"}"
        }

        private fun getCacheDir(txtFile: File): File {
            return sCacheBaseDir ?: run {
                val cacheDir = File(txtFile.parentFile, CACHE_DIR_NAME)
                if (!cacheDir.exists()) cacheDir.mkdirs()
                cacheDir
            }
        }

        private fun getCacheFile(txtFile: File): File {
            val path = txtFile.absolutePath
            return try {
                val md = MessageDigest.getInstance("MD5")
                val digest = md.digest(path.toByteArray(Charset.forName("UTF-8")))
                val hash = digest.joinToString("") { "%02x".format((it.toInt() and 0xFF)) }
                File(getCacheDir(txtFile), "$hash${txtFile.length()}_${txtFile.lastModified()}.cache")
            } catch (e: Exception) {
                val fallback = "${Math.abs(path.hashCode())}_${txtFile.length()}_${txtFile.lastModified()}.cache"
                File(getCacheDir(txtFile), fallback)
            }
        }

        private fun readCache(file: File, forcedEncoding: String?): ParseResult? {
            val cacheFile = getCacheFile(file)
            if (!cacheFile.exists()) return null
            BufferedReader(InputStreamReader(FileInputStream(cacheFile), "UTF-8")).use { reader ->
                val cachedKey = reader.readLine()
                if (getCacheKey(file, forcedEncoding) != cachedKey) return null
                val title = unescapeFromCache(reader.readLine())
                val encoding = reader.readLine()
                val chapterCount = Integer.parseInt(reader.readLine())
                val result = ParseResult()
                result.bookTitle = title
                result.encoding = encoding
                val fullBuilder = StringBuilder()
                for (i in 0 until chapterCount) {
                    val chTitle = unescapeFromCache(reader.readLine())
                    val lineStart = Integer.parseInt(reader.readLine())
                    val lineEnd = Integer.parseInt(reader.readLine())
                    val contentLen = Integer.parseInt(reader.readLine())
                    val buf = CharArray(contentLen)
                    var read = 0
                    while (read < contentLen) {
                        val n = reader.read(buf, read, contentLen - read)
                        if (n < 0) break
                        read += n
                    }
                    val content = String(buf, 0, read)
                    if (contentLen != content.length && contentLen > 0) {
                        // adjust not needed for correctness
                    }
                    result.chapters.add(Chapter(chTitle, content, lineStart, lineEnd))
                    fullBuilder.append(content)
                }
                result.fullContent = fullBuilder.toString()
                return result
            }
        }

        private fun escapeForCache(s: String?): String {
            if (s == null) return ""
            return s.replace("\r", "\uE002").replace("\n", "\uE003")
        }

        private fun unescapeFromCache(s: String?): String {
            if (s == null) return ""
            return s.replace("\uE003", "\n").replace("\uE002", "\r")
        }

        private fun writeCache(file: File, result: ParseResult?, forcedEncoding: String?) {
            if (result == null || result.chapters.isNullOrEmpty()) return
            val cacheFile = getCacheFile(file)
            val tmpFile = File(cacheFile.absolutePath + ".tmp")
            OutputStreamWriter(FileOutputStream(tmpFile), "UTF-8").use { writer ->
                writer.write(getCacheKey(file, forcedEncoding) + "\n")
                writer.write(escapeForCache(result.bookTitle) + "\n")
                writer.write((result.encoding ?: "") + "\n")
                writer.write(result.chapters.size.toString() + "\n")
                for (ch in result.chapters) {
                    writer.write(escapeForCache(ch.title) + "\n")
                    writer.write(ch.lineStart.toString() + "\n")
                    writer.write(ch.lineEnd.toString() + "\n")
                    val content = ch.content ?: ""
                    writer.write(content.length.toString() + "\n")
                    writer.write(content)
                }
                writer.flush()
            }
            if (cacheFile.exists()) cacheFile.delete()
            tmpFile.renameTo(cacheFile)
        }
    }

    /** 解析结果 */
    class ParseResult {
        @JvmField var bookTitle: String? = null
        @JvmField var encoding: String? = null
        /** 全文内容（懒加载：仅在需要时构建，节省内存和 CPU） */
        @JvmField var fullContent: String? = null
        private var fullContentBuilt = false
        @JvmField var chapters: MutableList<Chapter> = ArrayList()

        /** 获取全文，仅在首次构建 */
        fun getFullContent(): String? {
            if (!fullContentBuilt && fullContent == null && chapters.isNotEmpty()) {
                val sb = StringBuilder()
                for (c in chapters) {
                    if (c.content != null) sb.append(c.content)
                }
                fullContent = sb.toString()
                fullContentBuilt = true
            }
            return fullContent
        }
    }
}

