package com.einkreader.core.model

import com.einkreader.core.model.Chapter

/**
 * EPUB 目录项（树形结构）
 */
class TocItem {
    @JvmField var title: String = ""
    @JvmField var href: String = ""
    @JvmField var children: MutableList<TocItem> = ArrayList()
}

/**
 * EPUB 解析结果数据类
 *
 * 原为 EpubParser.java 的内部类，提取为独立文件以便 Rust 解析器使用。
 */
class EpubResult {
    @JvmField var title: String = ""
    @JvmField var author: String = ""
    @JvmField var encoding: String = "UTF-8"
    @JvmField var chapters: MutableList<Chapter> = ArrayList()
    @JvmField var spineOrder: MutableList<String> = ArrayList()
    @JvmField var images: MutableMap<String, ByteArray> = HashMap()
    @JvmField var tocItems: MutableList<TocItem> = ArrayList()
}
