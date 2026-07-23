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
        private val NOOK_CLASSES = arrayOf(
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
            callback!!.onModeDetected(modes)
            if (!reflectionReady) callback!!.onSysfsUnavailable()
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
            val epdMode = java.io.File("/sys/class/graphics/fb0/epd_mode")
            epdMode.exists() && epdMode.canWrite()
        } catch (e: Exception) {
            false
        }
    }

    fun onPageTurn(view: View?) {
        if (view == null) return
        val area = Rect()
        view.getDrawingRect(area)
        requestSmartRefresh(view, area, false, false)
    }

    fun requestSmartRefresh(view: View, area: Rect, isForward: Boolean, isCacheHit: Boolean) {
        val mode = determineModeFromInstance(isForward, isCacheHit)
        val startNs = System.nanoTime()

        callback?.onRefreshStart(mode)

        var success = false
        if (reflectionReady && isNookDevice) {
            success = applyNookRefresh(area, mode)
        }
        if (!success) {
            applyFallbackRefresh(view, area, mode)
        }
        callback?.onRefreshComplete(mode)

        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        DebugLog.log(TAG, "mode=${mode.getTag()} forward=$isForward cacheHit=$isCacheHit elapsed=${elapsedMs}ms area=[${area.left},${area.top},${area.right},${area.bottom}]")
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
                    updateRegionMethod!!.invoke(einkManagerInstance,
                        area.left, area.top, area.right, area.bottom,
                        getNookModeCode(mode))
                }
                true
            } catch (e: Exception) {
                DebugLog.log(TAG, "Nook reflection call failed: ${e.message}")
                false
            }
        }
        return writeSysfs(getNookModeCode(mode))
    }

    private fun writeSysfs(modeCode: Int): Boolean {
        return try {
            val fw = java.io.FileWriter("/sys/class/graphics/fb0/epd_mode")
            fw.write(modeCode.toString())
            fw.close()
            true
        } catch (e: Exception) {
            false
        }
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
