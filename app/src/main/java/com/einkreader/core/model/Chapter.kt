package com.einkreader.core.model

/** 
 * 章节数据模型
 * 构造器签名兼容 Java 调用：Chapter(title, content) 和 Chapter(title, content, ls, le)
 */
class Chapter {

    @JvmField var title: String
    @JvmField var content: String
    @JvmField val lineStart: Int
    @JvmField val lineEnd: Int
    
    @JvmField val imagePaths = mutableListOf<String>()
    @JvmField val paragraphTypes = mutableListOf<Int>()

    var index: Int = 0

    constructor(title: String, content: String) {
        this.title = title
        this.content = content
        this.lineStart = 0
        this.lineEnd = 0
    }

    constructor(title: String, content: String, lineStart: Int, lineEnd: Int) {
        this.title = title
        this.content = content
        this.lineStart = lineStart
        this.lineEnd = lineEnd
    }

    // Java 兼容的 getter/setter
    fun getTitle(): String = title
    fun setTitle(t: String) { title = t }
    fun getContent(): String = content
    fun setContent(c: String) { content = c }
    fun getLineStart(): Int = lineStart
    fun getLineEnd(): Int = lineEnd

    fun addImagePath(path: String) { imagePaths.add(path) }
    fun addParagraphType(type: Int) { paragraphTypes.add(type) }
    fun setImagePaths(paths: List<String>) { imagePaths.clear(); imagePaths.addAll(paths) }
    fun setParagraphTypes(types: List<Int>) { paragraphTypes.clear(); paragraphTypes.addAll(types) }
    fun getImagePaths(): List<String> = imagePaths
    fun getParagraphTypes(): List<Int> = paragraphTypes

    companion object {
        const val PARA_NORMAL = 0
        const val PARA_H1 = 1
        const val PARA_H2 = 2
        const val PARA_H3 = 3
        const val PARA_BLOCKQUOTE = 4
        const val PARA_IMAGE = 5
    }
}
