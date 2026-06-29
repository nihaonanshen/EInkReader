package com.einkreader.core;

/**
 * 特性开关：控制使用 Rust 原生实现还是 Java 实现
 *
 * 初期默认关闭 Rust，逐个模块验证通过后打开。
 * 发现 Bug 可立刻切回 Java，不影响用户使用。
 */
public class FeatureFlags {
    // Rust 编码检测
    public static boolean USE_RUST_ENCODING_DETECTOR = true;

    // Rust TXT 解析器（需 NativeBridge 库加载成功才生效）
    public static boolean USE_RUST_TXT_PARSER = true;

    // Rust EPUB 解析器（Phase 3 实现）
    public static boolean USE_RUST_EPUB_PARSER = false;

    // Rust 页面排版引擎（Phase 4 实现）
    public static boolean USE_RUST_LAYOUT = false;

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
        return USE_RUST_TXT_PARSER && isRustAvailable();
    }
}
