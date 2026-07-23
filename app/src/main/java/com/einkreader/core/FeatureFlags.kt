package com.einkreader.core

import com.einkreader.BuildConfig

class FeatureFlags {
    companion object {
        @JvmField val ENABLE_RUST = BuildConfig.ENABLE_RUST

        @JvmField var USE_RUST_ENCODING_DETECTOR: Boolean = ENABLE_RUST
        @JvmField var USE_RUST_TXT_PARSER: Boolean = ENABLE_RUST
        @JvmField var USE_RUST_EPUB_PARSER: Boolean = ENABLE_RUST
        @JvmField var USE_RUST_LAYOUT: Boolean = ENABLE_RUST

        @JvmStatic fun isRustAvailable(): Boolean = NativeBridge.isLibraryLoaded()
        @JvmStatic fun useRustTxtParser(): Boolean = USE_RUST_TXT_PARSER && isRustAvailable()
        @JvmStatic fun useRustEpubParser(): Boolean = USE_RUST_EPUB_PARSER && isRustAvailable()
    }
}
