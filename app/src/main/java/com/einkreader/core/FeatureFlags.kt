package com.einkreader.core

import com.einkreader.BuildConfig

class FeatureFlags {
    companion object {
        @JvmField val ENABLE_RUST = BuildConfig.ENABLE_RUST

        @JvmField var USE_RUST_ENCODING_DETECTOR: Boolean = true
        @JvmField var USE_RUST_TXT_PARSER: Boolean = true
        @JvmField var USE_RUST_EPUB_PARSER: Boolean = true
        @JvmField var USE_RUST_LAYOUT: Boolean = false  // 默认为 false 以支持图片渲染，启用可提升布局性能但图片不显示
        // [P1-2] 混合门控：纯文字章节走 Rust 布局（含段落样式），含图片章节自动回退 Java 渲染
        @JvmField var USE_RUST_LAYOUT_HYBRID: Boolean = true

        @JvmStatic fun isRustAvailable(): Boolean = NativeBridge.sLibraryLoaded
        @JvmStatic fun useRustTxtParser(): Boolean = USE_RUST_TXT_PARSER && isRustAvailable()
        @JvmStatic fun useRustEpubParser(): Boolean = USE_RUST_EPUB_PARSER && isRustAvailable()
    }
}
