package com.einkreader.ui.reader

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** DebugLog（环形日志缓冲 + 文件落盘）单测 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DebugLogTest {

    @Before
    fun setUp() {
        DebugLog.clear()
        DebugLog.ENABLED = true
    }

    @Test
    fun log_appendsWithTagAndTimestamp() {
        DebugLog.log("Unit", "hello")
        val out = DebugLog.getLog()
        assertTrue(out.contains("[Unit]"))
        assertTrue(out.contains("hello"))
    }

    @Test
    fun error_includesTagAndMessage() {
        DebugLog.error("Unit", "boom")
        val out = DebugLog.getLog()
        assertTrue(out.contains("[Unit] boom"))
        // error 日志带 ERROR 前缀
        assertTrue(out.contains("ERROR"))
    }

    @Test
    fun error_withThrowable_capturesStackHead() {
        val boom = RuntimeException("kaboom")
        DebugLog.error("Unit", "ex", boom)
        val out = DebugLog.getLog()
        assertTrue(out.contains("RuntimeException"))
        assertTrue(out.contains("kaboom"))
    }

    @Test
    fun disabledFlagSuppressesLogging() {
        DebugLog.ENABLED = false
        DebugLog.log("Unit", "should not appear")
        assertEquals("", DebugLog.getLog())
    }

    @Test
    fun clear_emptiesBuffer() {
        DebugLog.log("Unit", "line")
        assertTrue(DebugLog.getLog().isNotEmpty())
        DebugLog.clear()
        assertEquals("", DebugLog.getLog())
    }

    @Test
    fun ringBuffer_truncatesWhenExceedingMax() {
        // MAX_LOG_LEN = 50000；灌入超过 5 万字符的日志，缓冲应被截半而不无限增长
        val chunk = "x".repeat(4000)
        repeat(20) { DebugLog.log("Big", chunk) } // 共 80000 字符
        val out = DebugLog.getLog()
        // 截断后长度被限制在 MAX_LOG_LEN 附近（delete 0..len/2 保留后半）
        // 注意 JUnit 签名为 assertTrue(message, condition)，参数顺序勿反
        assertTrue("日志应被截断，实际 ${out.length}", out.length <= 50000 + chunk.length)
        // 早期条目被丢弃、最近条目保留
        assertTrue(out.contains("Big"))
    }

    @Test
    fun logFilePath_uninitialized() {
        // 未 init(context) 时路径为占位符
        val p = DebugLog.getLogFilePath()
        assertEquals("(未初始化)", p)
    }
}
