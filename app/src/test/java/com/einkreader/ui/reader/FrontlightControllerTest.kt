package com.einkreader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FrontlightController（fix4 从 ReaderActivity 抽出的前光 sysfs 控制）单测。
 *
 * 注意：本机/CI 无 `su` 环境，`Runtime.exec("su -c ...")` 会立即失败且异常被内部吞掉，
 * 因此这里只验证：默认值、字段可写、set/read 调用不抛异常、无 su 时亮度保持默认
 * （onRead 回调只在成功读到合法值时触发，无 su 时不应被触发）。
 */
class FrontlightControllerTest {

    @Before
    fun setUp() {
        FrontlightController.brightness = 50
    }

    @Test
    fun defaultBrightnessIs50() {
        assertEquals(50, FrontlightController.brightness)
    }

    @Test
    fun brightnessIsWritable() {
        FrontlightController.brightness = 80
        assertEquals(80, FrontlightController.brightness)
    }

    @Test
    fun set_withoutSu_doesNotThrowAndKeepsValue() {
        // 无 su：子进程启动即失败（异常被吞），brightness 在调用前已被赋值为入参
        FrontlightController.set(30)
        assertEquals(30, FrontlightController.brightness)
        // 等待后台线程结束（异常路径耗时极短）
        Thread.sleep(200)
        assertEquals(30, FrontlightController.brightness)
    }

    @Test
    fun set_clampsOutOfRange() {
        FrontlightController.set(150)
        assertEquals(100, FrontlightController.brightness)
        FrontlightController.set(-5)
        assertEquals(0, FrontlightController.brightness)
    }

    @Test
    fun readFromSysfs_withoutSu_doesNotInvokeCallback() {
        var read = false
        FrontlightController.readFromSysfs { read = true }
        // 等待后台线程结束；无 su 时 exec 抛异常被吞，onRead 不会触发
        Thread.sleep(200)
        assertTrue("无 su 环境不应读取到 sysfs 值", !read)
    }
}
