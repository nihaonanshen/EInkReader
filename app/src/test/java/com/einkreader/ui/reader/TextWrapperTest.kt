package com.einkreader.ui.reader

import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TextWrapper（fix4 从 ReaderView 抽出的换行引擎）单测。
 *
 * 只测确定性分支：禁则标点判定、测量函数 null/empty 边界（不依赖 Robolectric 下
 * Paint.measureText 的具体数值——shadow 不实现真实字体度量，数值断言不稳定）、
 * wrap 的空/null 输入、常量。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TextWrapperTest {

    private val paint: Paint by lazy {
        Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f }
    }

    // ========== isForbiddenLineStart（中文行首禁则） ==========

    @Test
    fun forbiddenLineStart_cjkPunctuation() {
        for (c in "。，、；：！？）】》…—".toList()) {
            assertTrue("「$c」应在禁则列表", TextWrapper.isForbiddenLineStart(c))
        }
    }

    @Test
    fun forbiddenLineStart_normalCharsAllowed() {
        for (c in "中文字母1a（「【".toList()) {
            assertFalse("「$c」不应在禁则列表", TextWrapper.isForbiddenLineStart(c))
        }
    }

    // ========== measureString null/empty 边界（纯逻辑，不经 paint） ==========

    @Test
    fun measureString_nullOrEmptyIsZero() {
        assertEquals(0.0, TextWrapper.measureString(null, paint).toDouble(), 0.0)
        assertEquals(0.0, TextWrapper.measureString("", paint).toDouble(), 0.0)
    }

    // ========== wrap 空/null 输入 ==========

    @Test
    fun wrap_nullReturnsSingleEmptyLine() {
        val lines = TextWrapper.wrap(null, 200, paint)
        assertEquals(1, lines.size)
        assertEquals("", lines[0])
    }

    @Test
    fun wrap_emptyReturnsSingleEmptyLine() {
        val lines = TextWrapper.wrap("", 200, paint)
        assertEquals(listOf(""), lines)
    }

    // ========== TEXT_SAFETY_MARGIN 常量 ==========

    @Test
    fun safetyMarginConstant() {
        assertEquals(3.0, TextWrapper.TEXT_SAFETY_MARGIN.toDouble(), 0.0)
    }
}
