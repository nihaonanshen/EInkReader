package com.einkreader.core.refresh;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;

import com.einkreader.ui.reader.DebugLog;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * 墨水屏智能刷新管理器
 *
 * 核心职责：根据翻页方向和缓存命中状态，自动选择最优刷新模式
 *
 * 刷新策略表：
 *   ┌────────────┬──────────────┬────────────────┐
 *   │ 方向       │ 缓存状态     │ 选用的刷新模式  │
 *   ├────────────┼──────────────┼────────────────┤
 *   │ 向后翻页   │ 缓存未命中   │ GC16 (全刷)     │
 *   │ 向后翻页   │ 缓存命中     │ A2 (快刷)       │
 *   │ 向前翻页   │ 必定命中     │ DU (极简刷)     │
 *   └────────────┴──────────────┴────────────────┘
 *
 * Nook 驱动适配（5 级反射链，按优先级递减）：
 *   1. com.nook.eink.EinkManager          — 标准 Nook SDK
 *   2. com.nook.kids.app.eink.EinkManager — Nook Kids 变体
 *   3. com.ebookintegrated.EinkController  — 通用 E-Ink 控制器
 *   4. /sys/class/graphics/fb0/epd_mode   — sysfs 底层接口
 *   5. View.invalidate()                   — Android 原生降级
 */
public class EinkRefreshManager {

    // ==================== 刷新模式枚举 ====================

    public enum RefreshMode {
        GC16_FULL,          // 全刷——GC16 16灰阶，彻底消除残影
        A2_FAST,            // 快刷——A2 2灰阶，快速翻页
        DU_MINIMAL,         // 极简刷——DU，仅更新变化像素
        INVALIDATE_FALLBACK; // 降级——Android View.invalidate()

        public String getTag() { return name(); }

        public boolean isFullRefresh() { return this == GC16_FULL; }

        public boolean isFallback() { return this == INVALIDATE_FALLBACK; }
    }

    // ==================== 回调接口（兼容旧调用者） ====================

    public interface RefreshCallback {
        void onRefreshStart(RefreshMode mode);
        void onRefreshComplete(RefreshMode mode);
        void onModeDetected(Set<RefreshMode> modes);
        void onSysfsUnavailable();
    }

    private static final String TAG = "EinkRefresh";

    // ==================== Nook 反射目标类 ====================

    private static final String[] NOOK_CLASSES = {
        "com.nook.eink.EinkManager",
        "com.nook.kids.app.eink.EinkManager",
        "com.ebookintegrated.EinkController",
    };

    private static final int MODE_GC16 = 1;
    private static final int MODE_A2   = 2;
    private static final int MODE_DU   = 3;
    private static final int MODE_INIT = 0;

    // ==================== 反射缓存 ====================

    private Object einkManagerInstance;
    private Method setModeMethod;
    private Method updateRegionMethod;
    private boolean reflectionReady = false;
    private boolean isNookDevice = false;
    private RefreshCallback callback;

    // ==================== 构造 ====================

    public EinkRefreshManager(Context context) {
        initNookReflection();
    }

    // ==================== 初始化 ====================

    public void initialize(RefreshCallback cb) {
        this.callback = cb;
        if (callback != null) {
            Set<RefreshMode> modes = new HashSet<RefreshMode>();
            modes.add(RefreshMode.GC16_FULL);
            modes.add(RefreshMode.A2_FAST);
            modes.add(RefreshMode.DU_MINIMAL);
            callback.onModeDetected(modes);
            if (!reflectionReady) {
                callback.onSysfsUnavailable();
            }
        }
    }

    private void initNookReflection() {
        for (String className : NOOK_CLASSES) {
            if (tryReflect(className)) {
                reflectionReady = true;
                isNookDevice = true;
                DebugLog.log(TAG, "Reflection OK: " + className);
                return;
            }
        }
        if (trySysfs()) {
            DebugLog.log(TAG, "Reflection via sysfs");
            reflectionReady = true;
            isNookDevice = true;
            return;
        }
        reflectionReady = false;
        isNookDevice = false;
        DebugLog.log(TAG, "No E-Ink driver found, using Android fallback");
    }

    private boolean tryReflect(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            try {
                Method getInstance = clazz.getMethod("getInstance");
                einkManagerInstance = getInstance.invoke(null);
            } catch (NoSuchMethodException e1) {
                try {
                    Method getInstance = clazz.getMethod("getDefault");
                    einkManagerInstance = getInstance.invoke(null);
                } catch (NoSuchMethodException e2) {
                    try {
                        einkManagerInstance = clazz.getDeclaredConstructor(Context.class)
                                .newInstance((Context) null);
                    } catch (Exception e3) {
                        einkManagerInstance = null;
                    }
                }
            }
            try {
                setModeMethod = clazz.getMethod("setMode", int.class);
            } catch (NoSuchMethodException ignored) {}
            try {
                updateRegionMethod = clazz.getMethod("updateRegion", int.class, int.class, int.class, int.class, int.class);
            } catch (NoSuchMethodException e1) {
                try {
                    updateRegionMethod = clazz.getMethod("refreshRegion", int.class, int.class, int.class, int.class, int.class);
                } catch (NoSuchMethodException e2) {
                    try {
                        updateRegionMethod = clazz.getMethod("refreshScreen", int.class, int.class, int.class, int.class, int.class);
                    } catch (NoSuchMethodException e3) {
                        updateRegionMethod = null;
                    }
                }
            }
            return true;
        } catch (Exception e) {
            DebugLog.log(TAG, "Reflection failed for " + className + ": " + e.getMessage());
            return false;
        }
    }

    private boolean trySysfs() {
        try {
            java.io.File epdMode = new java.io.File("/sys/class/graphics/fb0/epd_mode");
            return epdMode.exists() && epdMode.canWrite();
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 旧 API 兼容层 ====================

    /**
     * 旧版翻页调用（兼容 ReaderActivity）
     * 新代码应直接调用 requestSmartRefresh
     */
    public void onPageTurn(View view) {
        if (view == null) return;
        Rect area = new Rect();
        view.getDrawingRect(area);
        // 无方向/缓存信息时，默认认为是向后翻页且缓存未命中 → GC16 全刷
        requestSmartRefresh(view, area, false, false);
    }

    // ==================== 核心调度方法 ====================

    /**
     * 智能刷新调度入口
     *
     * @param view       需要刷新的 View
     * @param area       刷新区域（像素坐标）
     * @param isForward  true=向前翻页, false=向后翻页
     * @param isCacheHit true=缓存命中
     */
    public void requestSmartRefresh(View view, Rect area, boolean isForward, boolean isCacheHit) {
        RefreshMode mode = determineMode(isForward, isCacheHit);
        long startNs = System.nanoTime();

        if (callback != null) callback.onRefreshStart(mode);

        boolean success = false;
        if (reflectionReady && isNookDevice) {
            success = applyNookRefresh(area, mode);
        }
        if (!success) {
            applyFallbackRefresh(view, area, mode);
        }

        if (callback != null) callback.onRefreshComplete(mode);

        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
        DebugLog.log(TAG, "mode=" + mode.getTag()
                + " forward=" + isForward + " cacheHit=" + isCacheHit
                + " elapsed=" + elapsedMs + "ms"
                + " area=[" + area.left + "," + area.top + "," + area.right + "," + area.bottom + "]");
    }

    // ==================== 刷新策略 ====================

    public static RefreshMode determineMode(boolean isForward, boolean isCacheHit) {
        if (isForward) return RefreshMode.DU_MINIMAL;
        if (isCacheHit) return RefreshMode.A2_FAST;
        return RefreshMode.GC16_FULL;
    }

    // ==================== Nook 驱动调用 ====================

    private boolean applyNookRefresh(Rect area, RefreshMode mode) {
        if (reflectionReady && einkManagerInstance != null) {
            try {
                if (setModeMethod != null) {
                    setModeMethod.invoke(einkManagerInstance, getNookModeCode(mode));
                }
                if (updateRegionMethod != null) {
                    updateRegionMethod.invoke(einkManagerInstance,
                            area.left, area.top, area.right, area.bottom,
                            getNookModeCode(mode));
                }
                return true;
            } catch (Exception e) {
                DebugLog.log(TAG, "Nook reflection call failed: " + e.getMessage());
                return false;
            }
        }
        return writeSysfs(getNookModeCode(mode));
    }

    private boolean writeSysfs(int modeCode) {
        try {
            java.io.FileWriter fw = new java.io.FileWriter("/sys/class/graphics/fb0/epd_mode");
            fw.write(String.valueOf(modeCode));
            fw.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 降级刷新 ====================

    private void applyFallbackRefresh(View view, Rect area, RefreshMode mode) {
        if (view == null) return;
        if (mode.isFullRefresh()) {
            view.postInvalidate();
            view.postInvalidate(area.left, area.top, area.right, area.bottom);
        } else if (area != null) {
            view.postInvalidate(area.left, area.top, area.right, area.bottom);
        } else {
            view.postInvalidate();
        }
    }

    // ==================== 模式映射 ====================

    public static int getNookModeCode(RefreshMode mode) {
        switch (mode) {
            case GC16_FULL:     return MODE_GC16;
            case A2_FAST:       return MODE_A2;
            case DU_MINIMAL:    return MODE_DU;
            case INVALIDATE_FALLBACK:
            default:            return MODE_A2;
        }
    }

    // ==================== 公开状态查询 ====================

    public boolean isNookDriverAvailable() { return reflectionReady; }
    public boolean isNookDevice() { return isNookDevice; }
}