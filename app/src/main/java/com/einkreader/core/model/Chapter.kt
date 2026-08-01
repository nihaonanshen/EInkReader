package com.einkreader.core.model

/**
 * 章节数据模型
 *
 * Kotlin 属性天然对 Java 可访问，无需 Java 风格的 getter/setter。
 * 构造器签名兼容 Java 调用：Chapter(title, content) 和 Chapter(title, content, lineStart, lineEnd)
 */
class Chapter @JvmOverloads constructor(
    var title: String = "",
    var content: String = "",
    val lineStart: Int = 0,
    val lineEnd: Int = 0,
    var xhtmlPath: String? = null,
) {

    // Kotlin 属性自动为 Java 生成 getter/setter，无需手写 getTitle() 等
    val imagePaths = mutableListOf<String>()
    val paragraphTypes = mutableListOf<Int>()

    var index: Int = 0

    fun addImagePath(path: String) { imagePaths.add(path) }
    fun addParagraphType(type: Int) { paragraphTypes.add(type) }
    fun setImagePaths(paths: List<String>) { imagePaths.clear(); imagePaths.addAll(paths) }
    fun setParagraphTypes(types: List<Int>) { paragraphTypes.clear(); paragraphTypes.addAll(types) }

    companion object {
        const val PARA_NORMAL = 0
        const val PARA_H1 = 1
        const val PARA_H2 = 2
        const val PARA_H3 = 3
        const val PARA_BLOCKQUOTE = 4
        const val PARA_IMAGE = 5
    }
}
