package com.einkreader.core.parser

/**
 * HTML/XHTML → 纯文本 清洗工具（Java fallback 路径共用）。
 *
 * 历史上同样的 7 步正则清洗在 3 处重复（EpubParserFallback.extractText、
 * 以及 Rust xhtml.rs 的独立扫描器（原 ReaderActivity.loadChapterText 懒加载兜底已移除）。
 * Java 侧两处统一收敛到这里；Rust 侧走手写字节扫描器（性能优先），不共用本类。
 *
 * 清洗步骤（顺序不可随意调换）：
 * 1) 去 <style>/<script>（含内容）
 * 2) 块级标签 → \n（段落边界）；其余内联标签 → ""（[review-#24] 旧实现全标签→\n，
 *    em/i/span 等内联标签会在词中散落换行、把词语拆断）
 * 3) HTML 实体：已知实体解码、数值实体（&#x..;/&#..;）转字符，未知 → 空格
 *    （手动 Matcher 循环，API19 安全；不依赖 Java9 的 Matcher.replaceAll(Function)）
 * 4) CRLF/CR → LF
 * 5) 行首尾空白修剪
 * 6) 按调用方决定空行策略：
 *    - collapseAllNewlines=true  ：全部连续空行压成单行（排版引擎每个空行会多排一整行高度）
 *    - collapseAllNewlines=false ：段落间保留一个空行（阅读性更好的纯文本输出）
 */
object HtmlCleaner {

    private val RE_STYLE = Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val RE_SCRIPT = Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    // [review-#24] 块级标签：这些标签代表段落/换行边界 → \n
    private val RE_BLOCK_TAG = Regex(
        """<\s*/?\s*(?:p|div|h[1-6]|li|br|blockquote|section|article|header|footer|tr|ul|ol|table|pre|dl|dt|dd)[^>]*>""",
        RegexOption.IGNORE_CASE
    )
    private val RE_TAG = Regex("<[^>]+>")
    private val RE_ENTITY = java.util.regex.Pattern.compile("&[a-zA-Z#][a-zA-Z0-9#]{1,7};")
    // [review-#24] 已知实体表（EPUB 高频子集；未收录实体回退空格，避免泄漏 "&xxx;" 字面量）
    private val KNOWN_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "", "apos" to "'",
        "nbsp" to " ", "mdash" to "—", "ndash" to "–", "hellip" to "…",
        "laquo" to "«", "raquo" to "»", "copy" to "©", "reg" to "®",
        "trade" to "™", "sect" to "§", "para" to "¶", "deg" to "°",
        "times" to "×", "divide" to "÷", "frac12" to "½", "shy" to "",
        "bull" to "•", "middot" to "·"
    )
    private val RE_CRLF = Regex("\\r\\n|\\r")
    private val RE_NL_SPACE = Regex("[ \\t]+\\n|\\n[ \\t]+")
    private val RE_NL_3PLUS = Regex("\\n{3,}")
    private val RE_NL_2PLUS = Regex("\\n{2,}")

    /**
     * @param html 原始 HTML/XHTML 文本
     * @param collapseAllNewlines true=压平全部空行（阅读布局用），false=段落间留一个空行（纯文本提取用）
     */
    fun clean(html: String, collapseAllNewlines: Boolean): String {
        var s = html
            .replace(RE_STYLE, "")
            .replace(RE_SCRIPT, "")
        // [review-#24] 块级标签 → \n；其余（内联）标签 → ""
        s = s.replace(RE_BLOCK_TAG, "\n").replace(RE_TAG, "")
        s = replaceEntities(s)
            .replace(RE_CRLF, "\n")
            .replace(RE_NL_SPACE, "\n")
        s = if (collapseAllNewlines) s.replace(RE_NL_2PLUS, "\n") else s.replace(RE_NL_3PLUS, "\n\n")
        return s.trim()
    }

    /** [review-#24] 实体替换：已知表 + 数值实体解码，未知 → 空格（API19 安全的 Matcher 循环） */
    private fun replaceEntities(s: String): String {
        val m = RE_ENTITY.matcher(s) // [review-#24] Pattern.matcher 手动循环（API19 安全）
        val sb = StringBuilder(s.length)
        var last = 0
        while (m.find()) {
            sb.append(s, last, m.start())
            val raw = m.group(0).substring(1, m.group(0).length - 1) // 去掉 & 与 ;
            val decoded: String? = when {
                raw.startsWith("#x") || raw.startsWith("#X") -> {
                    val v = raw.substring(2).toLongOrNull(16)
                    if (v != null && v >= 0L && v <= 0xFFFFL) v.toInt().toChar().toString() else null
                }
                raw.startsWith("#") -> {
                    val v = raw.substring(1).toLongOrNull(10)
                    if (v != null && v >= 0L && v <= 0xFFFFL) v.toInt().toChar().toString() else null
                }
                else -> KNOWN_ENTITIES[raw.lowercase()]
            }
            sb.append(decoded ?: " ")
            last = m.end()
        }
        sb.append(s, last, s.length)
        return sb.toString()
    }

    /** 段落间保留一个空行（EpubParserFallback 文本提取路径） */
    fun extractText(html: String): String = clean(html, collapseAllNewlines = false)

    /** 压平全部空行（ReaderActivity 懒加载兜底路径，布局引擎不允许空行） */
    fun cleanForLayout(html: String): String = clean(html, collapseAllNewlines = true)
}
