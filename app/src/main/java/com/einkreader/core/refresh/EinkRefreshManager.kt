package com.einkreader.core.refresh

import android.content.Context
import android.graphics.Rect
import android.view.View
import com.einkreader.ui.reader.DebugLog
import java.lang.reflect.Method
import java.util.HashSet

/**
 * 墨水屏智能刷新管理器
 */
class EinkRefreshManager(context: Context) {

    enum class RefreshMode {
        GC16_FULL, A2_FAST, DU_MINIMAL, INVALIDATE_FALLBACK;

        fun getTag() = name
        fun isFullRefresh() = this == GC16_FULL
        fun isFallback() = this == INVALIDATE_FALLBACK
    }

    interface RefreshCallback {
        fun onRefreshStart(mode: RefreshMode)
        fun onRefreshComplete(mode: RefreshMode)
        @JvmSuppressWildcards
        fun onModeDetected(modes: Set<RefreshMode>)
        fun onSysfsUnavailable()
    }

    companion object {
        private const val TAG = "EinkRefresh"
        private const val MODE_GC16 = 1
        private const val MODE_A2   = 2
        private const val MODE_DU   = 3
        /** ntx_6sl (mxc_epdc_fb) 的 EPDC 刷新命令入口：666 权限，普通 app 进程可写，无需 root */
        private const val EPDC_UPDATE_PATH = "/sys/class/graphics/fb0/update"

        /**
         * 触发一次全屏 GC16 全刷（清残影）。
         * 实测（nook6plus）：`echo gc16 > /sys/class/graphics/fb0/update` 有效——
         * 屏幕明显全刷闪烁、残影全部清除；与系统自带全刷等效，且无需 root。
         * 供 ReaderActivity/ReadingSettingsActivity 等任意位置直接调用。
         * @return true=已写入（驱动已接受）
         */
        @JvmStatic
        fun performSysfsFullRefresh(waveform: String = "gc16"): Boolean {
            return try {
                val f = java.io.File(EPDC_UPDATE_PATH)
                if (f.exists()) {
                    f.writeText(waveform)
                    DebugLog.log(TAG, "sysfs $waveform full refresh OK")
                    true
                } else {
                    DebugLog.log(TAG, "sysfs update file missing")
                    false
                }
            } catch (e: Exception) {
                DebugLog.log(TAG, "sysfs $waveform failed: ${e.message}")
                false
            }
        }

        private val NOOK_CLASSES = arrayOf(
            // ★ ntx_6sl (Nook GlowLight 4) 定制 ROM 的系统 E-ink 管理类（KOReader 用它刷新，
            //   支持 DU/A2/GC16 等完整波形；我们此前只试了 com.nook.eink.EinkManager 导致失败）
            "android.os.EinkManager",
            "com.nook.eink.EinkManager",
            "com.nook.kids.app.eink.EinkManager",
            "com.ebookintegrated.EinkController",
        )

        @JvmStatic
        fun determineMode(isForward: Boolean, isCacheHit: Boolean): RefreshMode {
            return if (isForward) RefreshMode.DU_MINIMAL
            else if (isCacheHit) RefreshMode.A2_FAST
            else RefreshMode.GC16_FULL
        }

        @JvmStatic
        fun getNookModeCode(mode: RefreshMode): Int {
            return when (mode) {
                RefreshMode.GC16_FULL -> MODE_GC16
                RefreshMode.A2_FAST -> MODE_A2
                RefreshMode.DU_MINIMAL -> MODE_DU
                else -> MODE_A2
            }
        }
    }

    private var einkManagerInstance: Any? = null
    private var setModeMethod: Method? = null
    private var updateRegionMethod: Method? = null
    private var reflectionReady = false
    private var isNookDevice = false
    private var callback: RefreshCallback? = null
    private val appContext: Context? = context.applicationContext

    // 连续翻页计数：每 N 次强制一次 GC16 全刷，清除累积残影（N 来自设置页 refresh_interval）
    private var consecutiveTurns = 0

    init {
        initNookReflection()
    }

    fun initialize(cb: RefreshCallback?) {
        callback = cb
        if (callback != null) {
            val modes = HashSet<RefreshMode>().apply {
                add(RefreshMode.GC16_FULL)
                add(RefreshMode.A2_FAST)
                add(RefreshMode.DU_MINIMAL)
            }
            checkNotNull(callback).onModeDetected(modes)
            if (!reflectionReady) checkNotNull(callback).onSysfsUnavailable()
        }
    }

    private fun initNookReflection() {
        for (className in NOOK_CLASSES) {
            if (tryReflect(className)) {
                reflectionReady = true
                isNookDevice = true
                DebugLog.log(TAG, "Reflection OK: $className")
                return
            }
        }
        if (trySysfs()) {
            DebugLog.log(TAG, "Reflection via sysfs")
            reflectionReady = true
            isNookDevice = true
            return
        }
        reflectionReady = false
        isNookDevice = false
        DebugLog.log(TAG, "No E-Ink driver found, using Android fallback")
    }

    private fun tryReflect(className: String): Boolean {
        return try {
            val clazz = Class.forName(className)
            // ★ 调试：打印 EinkManager 的全部方法，确认可用 API（KOReader 走 android.os.EinkManager）
            val methodNames = StringBuilder()
            for (m in clazz.declaredMethods) {
                methodNames.append(m.name).append(", ")
            }
            DebugLog.log(TAG, "类 $className 方法: $methodNames")
            try {
                val getInstance = clazz.getMethod("getInstance")
                einkManagerInstance = getInstance.invoke(null)
            } catch (e1: NoSuchMethodException) {
                try {
                    val getInstance = clazz.getMethod("getDefault")
                    einkManagerInstance = getInstance.invoke(null)
                } catch (e2: NoSuchMethodException) {
                    try {
                        einkManagerInstance = clazz.getDeclaredConstructor(Context::class.java).newInstance(null as Context?)
                    } catch (e3: Exception) {
                        einkManagerInstance = null
                    }
                }
            }
            try {
                setModeMethod = clazz.getMethod("setMode", Int::class.javaPrimitiveType)
            } catch (ignored: NoSuchMethodException) {}
            try {
                updateRegionMethod = clazz.getMethod("updateRegion",
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType)
            } catch (e1: NoSuchMethodException) {
                try {
                    updateRegionMethod = clazz.getMethod("refreshRegion",
                        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType)
                } catch (e2: NoSuchMethodException) {
                    try {
                        updateRegionMethod = clazz.getMethod("refreshScreen",
                            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType)
                    } catch (e3: NoSuchMethodException) {
                        updateRegionMethod = null
                    }
                }
            }
            true
        } catch (e: Exception) {
            DebugLog.log(TAG, "Reflection failed for $className: ${e.message}")
            false
        }
    }

    private fun trySysfs(): Boolean {
        return try {
            // nook6plus (ntx_6sl, Linux 3.0.35 mxc_epdc_fb)：刷新接口是 update 文件，
            // 格式：echo "<波形名> [mode top left width height]" > /sys/class/graphics/fb0/update
            // 波形名：gc16（16 级灰度全刷，清残影）、au（A2 快速）、du（最小）、gc4 等
            val updateFile = java.io.File("/sys/class/graphics/fb0/update")
            updateFile.exists() && updateFile.canWrite()
        } catch (e: Exception) {
            false
        }
    }

    fun computeRefreshMode(isForward: Boolean, isCacheHit: Boolean): RefreshMode {
        var mode = determineModeFromInstance(isForward, isCacheHit)
        // 连续向前翻页每 N 次强制一次 GC16 全刷，清除累积残影
        // N 来自设置页「刷新设置」的 refresh_interval（默认 8），不再硬编码
        if (isForward) {
            consecutiveTurns++
            if (consecutiveTurns >= currentFullRefreshInterval()) {
                consecutiveTurns = 0
                mode = RefreshMode.GC16_FULL
            }
        } else {
            consecutiveTurns = 0
        }
        return mode
    }

    /**
     * 读取设置页配置的全刷波形：gc16（默认，标准 16 级灰度全刷，彻底但闪烁多）
     * 或 gl16（无灰度快速全刷，闪烁小、速度快，清残影略弱）。
     * 设置页「刷新设置 → 全刷波形」开关写入 eink_reader_prefs.refresh_waveform_gl16（Boolean）。
     */
    fun currentWaveform(): String {
        return try {
            if (appContext?.getSharedPreferences("eink_reader_prefs", Context.MODE_PRIVATE)
                    ?.getBoolean("refresh_waveform_gl16", false) == true) "gl16" else "gc16"
        } catch (e: Exception) {
            "gc16"
        }
    }

    /** 读取设置页配置的全刷间隔（每翻 N 页全刷一次；范围 1-50，默认 8） */
    private fun currentFullRefreshInterval(): Int {
        return try {
            appContext?.getSharedPreferences("eink_reader_prefs", Context.MODE_PRIVATE)
                ?.getInt("refresh_interval", 8)?.coerceIn(1, 50) ?: 8
        } catch (e: Exception) {
            8
        }
    }

    /**
     * 执行 EPDC 硬件刷新。必须在 View.onDraw 完成之后调用
     * （软件渲染下 onDraw 返回时 framebuffer 已包含新页面）。
     * 返回是否成功；失败时调用方应回退普通重绘。
     */
    fun performEpdcRefresh(view: View, area: Rect, mode: RefreshMode): Boolean {
        val startNs = System.nanoTime()
        callback?.onRefreshStart(mode)

        var success = false
        if (reflectionReady && isNookDevice) {
            success = applyNookRefresh(area, mode)
        }
        if (!success) {
            success = applyEpdcSysfs(area, mode)
        }
        if (!success) {
            applyFallbackRefresh(view, area, mode)
        }
        callback?.onRefreshComplete(mode)

        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        DebugLog.log(TAG, "mode=${mode.getTag()} elapsed=${elapsedMs}ms area=[${area.left},${area.top},${area.right},${area.bottom}]")
        return success
    }

    private fun determineModeFromInstance(isForward: Boolean, isCacheHit: Boolean): RefreshMode {
        if (isForward) return RefreshMode.DU_MINIMAL
        if (isCacheHit) return RefreshMode.A2_FAST
        return RefreshMode.GC16_FULL
    }

    private fun applyNookRefresh(area: Rect, mode: RefreshMode): Boolean {
        if (reflectionReady && einkManagerInstance != null) {
            return try {
                setModeMethod?.invoke(einkManagerInstance, getNookModeCode(mode))
                if (updateRegionMethod != null) {
                    checkNotNull(updateRegionMethod).invoke(einkManagerInstance,
                        area.left, area.top, area.right, area.bottom,
                        getNookModeCode(mode))
                }
                true
            } catch (e: Exception) {
                DebugLog.log(TAG, "Nook reflection call failed: ${e.message}")
                false
            }
        }
        return false
    }

    /**
     * ntx_6sl (Nook 6 Plus) 的 E-ink 刷新（mxc_epdc_fb，Linux 3.0.35）：
     *
     * 【翻页（DU/A2）】不写 sysfs —— 该 ROM 的 hwcomposer 已集成 EPDC 自动刷新，
     * 应用只需 invalidate 重绘，系统自动选波形（KOReader 同思路）。手动写局部
     * sysfs 刷新（gc4/du 实测）反而引入新残影，故局部刷新返回 false 走纯 invalidate。
     *
     * 【全刷（GC16）】写 `/sys/class/graphics/fb0/update` 触发全屏 GC16 清残影。
     * 实测有效：与系统自带全刷等效（屏幕明显闪烁、残影全清），文件 666 权限
     * 普通进程可写、无需 root。每次翻页间隔 N 页、手动刷新、菜单/覆盖层切换
     * 都会走到这里。
     */
    private fun applyEpdcSysfs(area: Rect, mode: RefreshMode): Boolean {
        if (mode != RefreshMode.GC16_FULL) return false
        return performSysfsFullRefresh(currentWaveform())
    }

    private fun applyFallbackRefresh(view: View?, area: Rect?, mode: RefreshMode) {
        if (view == null) return
        if (mode.isFullRefresh()) {
            view.postInvalidate()
            area?.let { view.postInvalidate(it.left, it.top, it.right, it.bottom) }
        } else if (area != null) {
            view.postInvalidate(area.left, area.top, area.right, area.bottom)
        } else {
            view.postInvalidate()
        }
    }
}
