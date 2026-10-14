package com.einkreader.ui.reader

import com.einkreader.core.model.Chapter
import com.einkreader.core.model.TocItem
import java.util.Locale

/**
 * 目录（TOC）索引映射的纯逻辑抽取物（从 ReaderActivity 拆出，降低巨型类体积并使其可单测）。
 *
 * 职责：
 * - 展平 EPUB 目录树为标题列表（显示用）与 (href, title) 条目列表（跳转匹配用）
 * - 规范化 EPUB 内路径（去锚点/./、统一小写），使 href 与章节 xhtmlPath 可比对
 * - 将目录树索引映射到章节索引（href 精确匹配 → 标题回退 → 直接索引）
 *
 * 全部为纯函数（无 Android/UI 依赖，除 DebugLog 日志），可用 JUnit 直接测试。
 */
object TocIndexMapper {

    /** 规范化 EPUB 路径：去 # 锚点、去开头 ./、统一小写（href 与 xhtmlPath 的常见差异） */
    fun normalizePath(p: String?): String {
        if (p == null) return ""
        var s = p.trim()
        val hash = s.indexOf('#')
        if (hash >= 0) s = s.substring(0, hash)
        s = s.replace('\\', '/')
        while (s.startsWith("./")) s = s.substring(2)
        return s.lowercase(Locale.ROOT)
    }

    /** 递归展平目录树为标题列表（用于显示） */
    fun flattenTitles(items: List<TocItem>, out: MutableList<String?>, indent: Int = 0) {
        val prefix = " ".repeat(indent * 2)
        for (item in items) {
            val displayTitle = if (indent > 0) prefix + item.title else item.title
            out.add(displayTitle)
            if (item.children.isNotEmpty()) {
                flattenTitles(item.children, out, indent + 1)
            }
        }
    }

    /** 递归展平目录树，记录每个目录项的 (href, title) 条目（供跳转匹配） */
    fun flattenEntries(items: List<TocItem>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        appendEntries(items, out)
        return out
    }

    private fun appendEntries(items: List<TocItem>, out: MutableList<Pair<String, String>>) {
        for (item in items) {
            out.add(Pair(item.href, item.title))
            if (item.children.isNotEmpty()) {
                appendEntries(item.children, out)
            }
        }
    }

    /**
     * 根据目录树索引找到对应的章节索引。
     *
     * 匹配顺序：
     * 1) href 规范化后精确匹配章节 xhtmlPath（EPUB 内 href 常有 "#锚点"、"Text/chap.xhtml" 等写法，
     *    直接 == 比较常失败导致错位跳转）
     * 2) 标题回退匹配：spine 顺序与 toc 顺序不一致时按章节标题定位
     * 3) 均失败时回退为直接索引（保持旧行为）
     *
     * @return 章节索引；chapters 为空时返回 -1（调用方需校验）
     */
    fun findChapterIndex(tocIndex: Int, tocItems: List<TocItem>?, chapters: List<Chapter>?): Int {
        if (tocItems.isNullOrEmpty()) return tocIndex
        if (chapters.isNullOrEmpty()) return -1

        val tocEntries = flattenEntries(tocItems)
        if (tocIndex < tocEntries.size) {
            val (targetHref, targetTitle) = tocEntries[tocIndex]
            val normHref = normalizePath(targetHref)
            if (normHref.isNotEmpty()) {
                for ((idx, ch) in chapters.withIndex()) {
                    if (normalizePath(ch.xhtmlPath) == normHref) {
                        return idx
                    }
                }
            }
            if (targetTitle.isNotEmpty()) {
                for ((idx, ch) in chapters.withIndex()) {
                    if (ch.title.isNotEmpty() && ch.title.trim() == targetTitle.trim()) {
                        DebugLog.log("TOC", "findChapterIndex title-match: tocIndex=$tocIndex -> chapter=$idx title=$targetTitle")
                        return idx
                    }
                }
            }
            DebugLog.log("TOC", "findChapterIndex fallback: tocIndex=$tocIndex href=$targetHref title=$targetTitle")
        }
        return tocIndex
    }
}
