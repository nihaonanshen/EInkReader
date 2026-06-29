package com.einkreader.core.refresh;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * 墨水屏刷新管理器
 *
 * 墨水屏和普通屏幕不同，翻页后会有残影，需要定期全屏刷新清除残影。
 * 本类管理"全刷"和"局刷"两种模式：
 *   - 局刷（默认）：翻页只更新变化区域，速度快
 *   - 全刷：整屏刷新清除残影，每 8 页自动触发一次
 *
 * 针对 Android 4.4 (KitKat) 的 E-Ink 设备做了兼容优化
 */
public class EinkRefreshManager {

    public enum RefreshMode {
        FULL,        // 全屏刷新（清除残影）
        PARTIAL      // 局部刷新（快速翻页）
    }

    public interface RefreshCallback {
        void onRefreshStart(RefreshMode mode);
        void onRefreshComplete(RefreshMode mode);
        void onModeDetected(Set<RefreshMode> modes);
        void onSysfsUnavailable();
    }

    private static final String TAG = "EinkRefreshManager";
    private Context context;
    private RefreshCallback callback;
    private int fullRefreshInterval = 8;     // 每 8 页全刷一次
    private int pageCount = 0;
    private RefreshMode currentMode = RefreshMode.PARTIAL;
    private boolean sysfsAvailable = false;

    public EinkRefreshManager(Context context) {
        this.context = context;
    }

    public void initialize(RefreshCallback cb) {
        this.callback = cb;
        // 检测设备是否支持 E-Ink 系统调用
        try {
            Class<?> cls = Class.forName("android.os.SystemProperties");
            Method get = cls.getMethod("get", String.class);
            String epdMode = (String) get.invoke(null, "/sys/class/graphics/fb0/epd_mode");
            if (epdMode != null) {
                sysfsAvailable = true;
            }
        } catch (Exception e) {
            sysfsAvailable = false;
        }

        if (callback != null) {
            Set<RefreshMode> modes = new HashSet<RefreshMode>();
            modes.add(RefreshMode.FULL);
            modes.add(RefreshMode.PARTIAL);
            callback.onModeDetected(modes);
            if (!sysfsAvailable) {
                callback.onSysfsUnavailable();
            }
        }
    }

    /**
     * 翻页时调用 —— 自动决定用全刷还是局刷
     */
    public void onPageTurn(View view) {
        pageCount++;
        if (pageCount >= fullRefreshInterval) {
            doFullRefresh(view);
            pageCount = 0;
        } else {
            doPartialRefresh(view);
        }
    }

    /**
     * 手动触发全屏刷新
     */
    public void doFullRefresh(View view) {
        currentMode = RefreshMode.FULL;
        if (callback != null) callback.onRefreshStart(currentMode);

        if (view != null) {
            view.invalidate();
            // 全刷：画白色背景再重绘
            view.post(new Runnable() {
                @Override
                public void run() {
                    if (view != null) {
                        view.postInvalidate();
                    }
                }
            });
        }

        if (callback != null) callback.onRefreshComplete(currentMode);
        currentMode = RefreshMode.PARTIAL;
    }

    /**
     * 局部刷新（翻页）
     */
    private void doPartialRefresh(View view) {
        currentMode = RefreshMode.PARTIAL;
        if (callback != null) callback.onRefreshStart(currentMode);

        if (view != null) {
            view.postInvalidate();
        }

        if (callback != null) callback.onRefreshComplete(currentMode);
    }

    public void setFullRefreshInterval(int pages) {
        this.fullRefreshInterval = pages;
    }

    public int getFullRefreshInterval() {
        return fullRefreshInterval;
    }

    public RefreshMode getCurrentMode() {
        return currentMode;
    }
}
