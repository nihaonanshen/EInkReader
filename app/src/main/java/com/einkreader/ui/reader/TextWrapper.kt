package com.einkreader.ui.reader

import android.graphics.Paint

/**
 * 文本换行引擎（从 ReaderView 拆出，降低巨型类体积并使其可单测）。
 *
 * 职责：按最大宽度把段落拆为多行，支持：
 * - 英文按空格分词换行、CJK 逐字换行（`isCjk` 判定基于首字符）
 * - 行首标点禁则（中文排版规范：。，、等不得出现在行首）
 *
 * 纯函数：仅依赖传入的 Paint 测量，无 UI 状态。
 */
object TextWrapper {

    /** 换行安全余量（像素）：避免软渲染边缘抖动导致末字溢出 */
    const val TEXT_SAFETY_MARGIN = 3.0f

    /** 单字符宽度 */
    fun measureChar(c: Char, p: Paint): Float {
        val buf = charArrayOf(c)
        return p.measureText(buf, 0, 1)
    }

    /** 字符串宽度 */
    fun measureString(s: String?, p: Paint): Float {
        if (s == null || s.isEmpty()) return 0f
        return p.measureText(s)
    }

    /** 中文行首禁则标点（标点不得出现在行首；借鉴 KOReader floating punctuation） */
    fun isForbiddenLineStart(c: Char): Boolean {
        return c == '。' || c == '，' || c == '、' || c == '；' || c == '：' ||
            c == '！' || c == '？' || c == '）' || c == '」' || c == '』' ||
            c == '》' || c == '〉' || c == '】' || c == '”' || c == '’' ||
            c == '…' || c == '—' || c == '·' || c == '％' || c == '￥'
    }

    /**
     * 按 maxW（像素）把 text 拆为多行。
     * 英文/数字走"按词"策略，CJK 走逐字符策略（首字符判定）。
     */
    fun wrap(text: String?, maxW: Int, p: Paint): MutableList<String> {
        val lines: MutableList<String> = ArrayList<String>()
        if (text == null || text.isEmpty()) {
            lines.add("")
            return lines
        }

        var isCjk = false
        for (i in 0..<text.length) {
            val c = text.get(i)
            if ((c.code >= 0x4E00 && c.code <= 0x9FFF) || (c.code >= 0x3400 && c.code <= 0x4DBF) || (c.code >= 0x3040 && c.code <= 0x30FF)) {
                isCjk = true
                break
            } else if (Character.isLetterOrDigit(c)) {
                break
            }
        }

        val cl = StringBuilder()
        if (!isCjk) {
            val words = text.split(" ".toRegex()).toTypedArray()
            for (word in words) {
                val ww = measureString(word, p)
                if (ww + TEXT_SAFETY_MARGIN > maxW) {
                    if (cl.length > 0) {
                        lines.add(cl.toString())
                        cl.setLength(0)
                    }
                    // ★ 长词逐字折行：批量取全词字宽后累计判断，替代原先每加一字符就
                    //   measureString(cl.toString()) 整串重测（O(n²) → O(n)，
                    //   单次 getTextWidths 调用；measureText 即逐字 advance 之和，行为等价）
                    val widths = FloatArray(word.length)
                    p.getTextWidths(word, 0, word.length, widths)
                    var acc = 0f
                    for (ci in 0..<word.length) {
                        val c = word.get(ci)
                        cl.append(c)
                        acc += widths[ci]
                        if (acc + TEXT_SAFETY_MARGIN > maxW && cl.length > 1) {
                            cl.deleteCharAt(cl.length - 1)
                            lines.add(cl.toString())
                            cl.setLength(0)
                            cl.append(c)
                            acc = widths[ci]
                        }
                    }
                    continue
                }
                val tryL: String? = if (cl.length > 0) cl.toString() + " " + word else word
                if (measureString(tryL, p) + TEXT_SAFETY_MARGIN > maxW && cl.length > 0) {
                    lines.add(cl.toString())
                    cl.setLength(0)
                    cl.append(word)
                } else {
                    cl.setLength(0)
                    cl.append(tryL)
                }
            }
        } else {
            var cw = 0f
            for (i in 0..<text.length) {
                val c = text.get(i)
                val cw1 = measureChar(c, p)
                if (cw + cw1 + TEXT_SAFETY_MARGIN > maxW && cl.length > 0) {
                    // ★ 行首标点禁则：若换行处下一个字符是禁则标点（。，、等），
                    //   并入当前行（标点悬挂），避免中文标点出现在行首
                    if (!isForbiddenLineStart(c)) {
                        lines.add(cl.toString())
                        cl.setLength(0)
                        cw = 0f
                    }
                }
                cl.append(c)
                cw += cw1
            }
        }
        if (cl.length > 0) lines.add(cl.toString())
        if (lines.isEmpty()) lines.add("")
        return lines
    }
}
