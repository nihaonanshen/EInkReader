package com.einkreader.ui.reader

import android.content.Context
import android.content.SharedPreferences
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
 *
 * [P3] 常驻 su 管道：亮度步进（亮+/亮-）频繁调用时，复用同一个 su 交互进程
 * （stdin 逐行写命令），省掉每次 100-300ms 的 su 启动开销；
 * 管道不可用（ROM 不支持交互 su / 进程退出 / 写失败）自动回退一次性 su -c。
 */
object FrontlightController {

    private const val BRIGHTNESS_PATH = "/sys/class/backlight/mxc_msp430_fl.0/brightness"

    /** 当前前光亮度（0-100）；未知时为默认 50 */
    @Volatile
    var brightness: Int = 50

    // [review-#31] 前光亮度持久化（eink_reader_prefs.frontlight_brightness）：
    // 旧实现每次进阅读界面 brightness 重置 50，用户上次的亮度丢失
    @Volatile
    private var appContext: Context? = null

    /** [review-#31] 在 ReaderActivity onCreate 早期调用：绑定 appContext + 回读持久化亮度 */
    fun init(context: Context) {
        appContext = context.applicationContext
        brightness = context.applicationContext
            .getSharedPreferences("eink_reader_prefs", Context.MODE_PRIVATE)
            .getInt("frontlight_brightness", brightness)
    }

    /** [review-#31] 写 sysfs 成功后同步持久化（后台线程，prefs 写入量极小） */
    private fun persist(value: Int) {
        appContext?.getSharedPreferences("eink_reader_prefs", Context.MODE_PRIVATE)
            ?.edit()?.putInt("frontlight_brightness", value)?.apply()
    }

    // [P3] 常驻 su 管道句柄（非线程安全，统一在 synchronized(this) 内访问）
    @Volatile
    private var suProcess: java.lang.Process? = null
    @Volatile
    private var suCmd: java.io.BufferedWriter? = null

    /**
     * 后台读取当前 sysfs 前光亮度作为初始值（只读，不写入）。
     * 读取需要命令 stdout → 走一次性 su -c（管道路径丢弃输出，不能用于读）。
     * @param onRead 读取成功回调（可能未发生，如无 su 或文件缺失）
     */
    fun readFromSysfs(onRead: (Int) -> Unit) {
        Thread(Runnable {
            val text = execOneShot("cat $BRIGHTNESS_PATH").trim()
            val value = text.toIntOrNull()
            if (value != null) {
                brightness = value.coerceIn(0, 100)
                DebugLog.log("Brightness", "readFrontlight sysfs=$value")
                // [review-#31] 真实读到的 sysfs 值为准并持久化（比 prefs 旧值新）
                persist(brightness)
                onRead(brightness)
            }
        }).start()
    }

    /**
     * 后台写入前光亮度（0-100 步进由调用方计算）。
     * 写入不需要回读 → 优先常驻管道。
     */
    fun set(value: Int) {
        val v = value.coerceIn(0, 100)
        brightness = v
        // [review-#31] 用户显式调光即持久化
        persist(v)
        Thread(Runnable {
            if (!tryPipe("echo $v > $BRIGHTNESS_PATH")) {
                execOneShot("echo $v > $BRIGHTNESS_PATH")
            }
        }).start()
    }

    /** 阅读界面销毁时调用：关管道，避免 su 残留进程 */
    fun close() {
        synchronized(this) { killPipeLocked() }
    }

    // ==================== [P3] 常驻 su 管道 ====================

    /**
     * 尝试把命令写入常驻 su 管道。
     * 管道存活直接写；未建则尝试拉起交互 su（stdout/stderr → /dev/null，
     * 防止 shell 提示输出撑爆管道缓冲区导致命令停摆）并以 `true` 探测；
     * 任何失败都清管道并返回 false，调用方回退一次性路径。
     */
    private fun tryPipe(cmd: String): Boolean {
        synchronized(this) {
            if (isPipeAliveLocked()) {
                return sendOnPipeLocked(cmd)
            }
            killPipeLocked()
            try {
                val pb = java.lang.ProcessBuilder("su")
                pb.redirectOutput(java.lang.ProcessBuilder.Redirect.to(java.io.File("/dev/null")))
                pb.redirectError(java.lang.ProcessBuilder.Redirect.to(java.io.File("/dev/null")))
                val p = pb.start()
                val w = p.outputStream.bufferedWriter()
                // 探测：交互 su 可用时 shell 存活；不支持时 su 立即退出
                Thread.sleep(300)
                if (!p.isAlive) {
                    p.destroyForcibly()
                    return false
                }
                suProcess = p
                suCmd = w
                return sendOnPipeLocked(cmd)
            } catch (_: Exception) {
                return false
            }
        }
    }

    private fun sendOnPipeLocked(cmd: String): Boolean {
        return try {
            checkNotNull(suCmd).apply {
                write(cmd)
                write('\n'.code)
                flush()
            }
            true
        } catch (_: Exception) {
            killPipeLocked()
            false
        }
    }

    private fun isPipeAliveLocked(): Boolean {
        val p = suProcess
        val w = suCmd
        return p != null && w != null && p.isAlive
    }

    private fun killPipeLocked() {
        try { suCmd?.close() } catch (_: Exception) {}
        try { suProcess?.destroy() } catch (_: Exception) {}
        suCmd = null
        suProcess = null
    }

    /** 一次性 su -c（waitFor/destroyForcibly 防护，原实现保留） */
    private fun execOneShot(cmd: String): String {
        var p: java.lang.Process? = null
        try {
            p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val text = p.inputStream.use { it.bufferedReader().readText() }
            p.waitFor()
            return text
        } catch (e: Exception) {
            DebugLog.log("Brightness", "su exec failed: ${e.message}")
            p?.destroyForcibly()
            return ""
        }
    }
}
