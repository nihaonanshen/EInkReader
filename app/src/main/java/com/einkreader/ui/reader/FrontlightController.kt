package com.einkreader.ui.reader

import kotlin.jvm.Volatile

/**
 * 前光（背光 LED）sysfs 控制器（从 ReaderActivity 拆出）。
 *
 * 目标设备 nook6plus（ntx_6sl, i.MX6）：前光走 sysfs
 * /sys/class/backlight/mxc_msp430_fl.0/brightness（0-100），需 su。
 * Android 窗口亮度（window.screenBrightness）对 E-ink 前光 LED 无效，故独立管理。
 *
 * 设计约束（来自用户 R 系列需求，须保留）：
 * - 进入阅读界面【不自动写背光】：只读取当前值作为步进基准；仅用户点击 亮+/亮- 才写入。
 * - 所有 su 子进程：正常路径 waitFor() 等待结束，异常路径 destroyForcibly()，避免 su 残留。
 */
object FrontlightController {

    private const val BRIGHTNESS_PATH = "/sys/class/backlight/mxc_msp430_fl.0/brightness"

    /** 当前前光亮度（0-100）；未知时为默认 50 */
    @Volatile
    var brightness: Int = 50

    /**
     * 后台读取当前 sysfs 前光亮度作为初始值（只读，不写入）。
     * @param onRead 读取成功回调（可能未发生，如无 su 或文件缺失）
     */
    fun readFromSysfs(onRead: (Int) -> Unit) {
        Thread(Runnable {
            var p: java.lang.Process? = null
            try {
                p = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $BRIGHTNESS_PATH"))
                val text = p.inputStream.use { it.bufferedReader().readText() }.trim()
                val value = text.toIntOrNull()
                if (value != null) {
                    brightness = value.coerceIn(0, 100)
                    DebugLog.log("Brightness", "readFrontlight sysfs=$value")
                    onRead(brightness)
                }
                p.waitFor()
            } catch (e: Exception) {
                DebugLog.log("Brightness", "readFrontlight failed: ${e.message}")
                p?.destroyForcibly()
            }
        }).start()
    }

    /**
     * 后台写入前光亮度（0-100 步进由调用方计算）。
     * su 子进程 waitFor/destroyForcibly 防护与读取分支对齐。
     */
    fun set(value: Int) {
        val v = value.coerceIn(0, 100)
        brightness = v
        Thread(Runnable {
            var p: java.lang.Process? = null
            try {
                p = Runtime.getRuntime().exec(arrayOf("su", "-c", "echo $v > $BRIGHTNESS_PATH"))
                p.waitFor()
            } catch (e: Exception) {
                p?.destroyForcibly()
            }
        }).start()
    }
}
