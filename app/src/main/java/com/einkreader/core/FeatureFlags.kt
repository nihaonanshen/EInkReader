package com.einkreader.core

import com.einkreader.BuildConfig

class FeatureFlags {
    companion object {
        @JvmField val ENABLE_RUST = BuildConfig.ENABLE_RUST

        @JvmField var USE_RUST_ENCODING_DETECTOR: Boolean = true
        @JvmField var USE_RUST_TXT_PARSER: Boolean = true
        @JvmField var USE_RUST_EPUB_PARSER: Boolean = true
        @JvmField var USE_RUST_LAYOUT: Boolean = false  // 默认为 false 以支持图片渲染，启用可提升布局性能但图片不显示

        @JvmStatic fun isRustAvailable(): Boolean = NativeBridge.sLibraryLoaded
        @JvmStatic fun useRustTxtParser(): Boolean = USE_RUST_TXT_PARSER && isRustAvailable()
        @JvmStatic fun useRustEpubParser(): Boolean = USE_RUST_EPUB_PARSER && isRustAvailable()
    }
}
