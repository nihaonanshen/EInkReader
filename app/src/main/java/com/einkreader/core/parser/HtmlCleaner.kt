package com.einkreader.core.parser

/**
 * HTML/XHTML → 纯文本 清洗工具（Java fallback 路径共用）。
 *
 * 历史上同样的 7 步正则清洗在 3 处重复（EpubParserFallback.extractText、
 * ReaderActivity.loadChapterText 懒加载兜底、以及 Rust xhtml.rs 的独立扫描器）。
 * Java 侧两处统一收敛到这里；Rust 侧走手写字节扫描器（性能优先），不共用本类。
 *
 * 清洗步骤（顺序不可随意调换）：
 * 1) 去 <style>/<script>（含内容）
 * 2) 其余标签 → \n
 * 3) HTML 实体 → 空格（无外部解析器，保守替换）
 * 4) CRLF/CR → LF
 * 5) 行首尾空白修剪
 * 6) 按调用方决定空行策略：
 *    - collapseAllNewlines=true  ：全部连续空行压成单行（排版引擎每个空行会多排一整行高度）
 *    - collapseAllNewlines=false ：段落间保留一个空行（阅读性更好的纯文本输出）
 */
object HtmlCleaner {

    private val RE_STYLE = Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val RE_SCRIPT = Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val RE_TAG = Regex("<[^>]+>")
    private val RE_ENTITY = Regex("&[a-zA-Z#][a-zA-Z0-9#]{1,7};")
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
            .replace(RE_TAG, "\n")
            .replace(RE_ENTITY, " ")
            .replace(RE_CRLF, "\n")
            .replace(RE_NL_SPACE, "\n")
        s = if (collapseAllNewlines) s.replace(RE_NL_2PLUS, "\n") else s.replace(RE_NL_3PLUS, "\n\n")
        return s.trim()
    }

    /** 段落间保留一个空行（EpubParserFallback 文本提取路径） */
    fun extractText(html: String): String = clean(html, collapseAllNewlines = false)

    /** 压平全部空行（ReaderActivity 懒加载兜底路径，布局引擎不允许空行） */
    fun cleanForLayout(html: String): String = clean(html, collapseAllNewlines = true)
}
