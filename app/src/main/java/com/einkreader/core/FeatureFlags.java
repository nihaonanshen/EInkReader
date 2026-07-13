package com.einkreader.core;

import com.einkreader.BuildConfig;

/**
 * 特性开关：控制使用 Rust 原生实现还是 Java 实现
 *
 * Debug 构建默认启用 Rust，Release 构建默认关闭。
 * 如需在 Release 中启用，请在 gradle.properties 中设置 enableRust=true
 */
public class FeatureFlags {
    // 从 Gradle 构建配置读取，默认：Debug=true, Release=false
    public static final boolean ENABLE_RUST = BuildConfig.ENABLE_RUST;

    // Rust 编码检测
    private static volatile boolean sUseRustEncodingDetector = ENABLE_RUST;

    // Rust TXT 解析器（需 NativeBridge 库加载成功才生效）
    private static volatile boolean sUseRustTxtParser = ENABLE_RUST;

    // Rust EPUB 解析器（Phase 3 实现）
    private static volatile boolean sUseRustEpubParser = ENABLE_RUST;

    // Rust 页面排版引擎（Phase 4 实现）
    private static volatile boolean sUseRustLayout = ENABLE_RUST;

    public static boolean isUseRustEncodingDetector() { return sUseRustEncodingDetector; }
    public static void setUseRustEncodingDetector(boolean v) { sUseRustEncodingDetector = v; }

    public static boolean isUseRustTxtParser() { return sUseRustTxtParser; }
    public static void setUseRustTxtParser(boolean v) { sUseRustTxtParser = v; }

    public static boolean isUseRustEpubParser() { return sUseRustEpubParser; }
    public static void setUseRustEpubParser(boolean v) { sUseRustEpubParser = v; }

    public static boolean isUseRustLayout() { return sUseRustLayout; }
    public static void setUseRustLayout(boolean v) { sUseRustLayout = v; }

    /**
     * 检查 Rust 实现是否可用
     */
    public static boolean isRustAvailable() {
        return NativeBridge.isLibraryLoaded();
    }

    /**
     * 是否使用 Rust TXT 解析器
     */
    public static boolean useRustTxtParser() {
        return sUseRustTxtParser && isRustAvailable();
    }

    /**
     * 是否使用 Rust EPUB 解析器
     */
    public static boolean useRustEpubParser() {
        return sUseRustEpubParser && isRustAvailable();
    }
}
