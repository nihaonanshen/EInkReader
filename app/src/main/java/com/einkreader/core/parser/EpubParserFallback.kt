package com.einkreader.core.parser

import android.util.Log
import com.einkreader.core.model.Chapter
import com.einkreader.core.model.EpubResult
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.util.zip.ZipFile

/**
 * 极简 EPUB 文本提取 fallback（Rust 库不可用时启用）
 *
 * 功能：打开 EPUB（ZIP）、读取 container.xml → OPF → spine → 提取纯文本。
 * 不做：图片提取、NCX 目录匹配、缓存、复杂 EPUB3 支持。
 */
class EpubParserFallback {
    companion object {
        private const val TAG = "EpubFallback"

        @JvmStatic
        fun initCacheDir(cacheDir: java.io.File) {
            // EPUB fallback 无需缓存目录，仅为兼容旧测试提供空实现
        }

        @JvmStatic
        fun isValidEpub(file: java.io.File?): Boolean {
            if (file == null || !file.exists() || file.length() == 0L) return false
            return try {
                val zip = java.util.zip.ZipFile(file)
                val hasContainer = zip.getEntry("META-INF/container.xml") != null
                zip.close()
                hasContainer
            } catch (e: Exception) {
                false
            }
        }

        @JvmStatic
        fun parse(file: File): EpubResult {
            val result = EpubResult()
            try {
                ZipFile(file).use { zip ->
                    // 1. 读取 container.xml
                    val containerEntry = zip.getEntry("META-INF/container.xml")
                        ?: return failResult("未找到 META-INF/container.xml")
                    val opfPath = parseContainerPath(zip.getInputStream(containerEntry).reader().readText())
                        ?: return failResult("无法解析 container.xml")

                    // 2. 读取 OPF
                    val opfEntry = zip.getEntry(opfPath) ?: return failResult("未找到 OPF: $opfPath")
                    val opfDir = opfPath.substringBeforeLast("/", "")
                    val opfXml = zip.getInputStream(opfEntry).reader().readText()
                    val (title, items) = parseOpf(opfXml, opfDir)
                    result.title = title

                    if (title.isEmpty()) {
                        val name = file.name
                        val dot = name.lastIndexOf('.')
                        result.title = if (dot > 0) name.substring(0, dot) else name
                    }

                    // 3. 按 spine 顺序读取每个内容文件
                    for (href in items) {
                        var entry = zip.getEntry(href)
                        if (entry == null) {
                            // 某些 EPUB 的相对路径是相对于 OPF 目录的
                            val altHref = opfDir + "/" + href
                            entry = zip.getEntry(altHref)
                        }
                        if (entry == null) continue
                        val html = zip.getInputStream(entry).reader().readText()
                        val text = extractText(html)
                        if (text.isNotBlank()) {
                            val chTitle = extractTitle(html) ?: "第${result.chapters.size + 1}章"
                            result.chapters.add(Chapter(chTitle, text.trim()))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Epub fallback parse failed", e)
            }
            return result
        }

        private fun failResult(msg: String): EpubResult {
            Log.w(TAG, msg)
            return EpubResult()
        }

        /** 从 container.xml 提取 OPF 路径 */
        private fun parseContainerPath(xml: String): String? {
            try {
                val factory = XmlPullParserFactory.newInstance()
                val parser = factory.newPullParser()
                parser.setInput(StringReader(xml))
                var eventType = parser.eventType
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG &&
                        parser.name == "rootfile" &&
                        "application/oebps-package+xml" == parser.getAttributeValue(null, "media-type")) {
                        return parser.getAttributeValue(null, "full-path")
                    }
                    eventType = parser.next()
                }
            } catch (e: Exception) {
                Log.w(TAG, "parseContainerPath failed", e)
            }
            return null
        }

        /** 解析 OPF：返回 (title, spineHrefs) */
        private fun parseOpf(xml: String, opfDir: String): Pair<String, List<String>> {
            var title = ""
            val spineHrefs = mutableListOf<String>()
            val idToHref = mutableMapOf<String, String>()

            try {
                val factory = XmlPullParserFactory.newInstance()
                val parser = factory.newPullParser()
                parser.setInput(StringReader(xml))
                var eventType = parser.eventType
                var inMetadata = false

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    when (eventType) {
                        XmlPullParser.START_TAG -> {
                            when (parser.name) {
                                "metadata" -> inMetadata = true
                                "dc:title" -> {
                                    eventType = parser.next()
                                    if (eventType == XmlPullParser.TEXT) title = parser.text ?: ""
                                }
                                "item" -> {
                                    val id = parser.getAttributeValue(null, "id") ?: ""
                                    val href = parser.getAttributeValue(null, "href") ?: ""
                                    val mediaType = parser.getAttributeValue(null, "media-type") ?: ""
                                    if (href.isNotEmpty() && (mediaType.contains("xhtml") || mediaType.contains("html"))) {
                                        val fullPath = if (opfDir.isNotEmpty()) "$opfDir/$href" else href
                                        idToHref[id] = fullPath
                                    }
                                }
                                "itemref" -> {
                                    val idref = parser.getAttributeValue(null, "idref") ?: ""
                                    idToHref[idref]?.let { spineHrefs.add(it) }
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            if (parser.name == "metadata") inMetadata = false
                        }
                    }
                    eventType = parser.next()
                }
            } catch (e: Exception) {
                Log.w(TAG, "parseOpf failed", e)
            }
            return Pair(title, spineHrefs)
        }

        /** 从 HTML/XHTML 提取纯文本 */
        private fun extractText(html: String): String {
            // Android 4.4 KXmlParser 性能太差，直接正则提取
            return html
                .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
                .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
                .replace(Regex("<[^>]+>"), "\n")
                .replace(Regex("&[a-zA-Z#][a-zA-Z0-9#]{1,7};"), " ")
                .replace(Regex("[ \\t]+\n|\n[ \\t]+"), "\n")
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
        }

        /** 从 HTML 的 <title> 标签提取章节名（占位符如 "Chapter 1"/"chapter" 跳过，继续尝试 <h1>） */
        private fun extractTitle(html: String): String? {
            // 机器生成 EPUB 的占位标题："chapter"、"Chapter 1"、"chapter_2" 等
            val placeholder = Regex("(?i)^chapter[\\s_\\-]*\\d*$")

            fun clean(raw: String): String? {
                val t = raw.trim()
                    .replace(Regex("<[^>]+>"), "")
                    .replace(Regex("\\s+"), " ")
                return if (t.isNotBlank() && !placeholder.matches(t)) t else null
            }

            val m = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)
            if (m != null) {
                clean(m.groupValues[1])?.let { return it }
            }
            // 尝试从 <h1>-<h6> 提取第一个标题
            val h = Regex("<h[1-6][^>]*>(.*?)</h[1-6]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)
            if (h != null) {
                clean(h.groupValues[1])?.let { return it }
            }
            return null
        }
    }
}
