package com.einkreader.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FeatureFlagsTest {

    @Test
    fun enableRust_defaultIsTrue() {
        // ENABLE_RUST 编译时常量，由 BuildConfig 提供。
        // build.gradle 中未传 -PenableRust 时默认 true（Rust 核心是主打功能，默认开启），
        // 因此 debug 构建的 BuildConfig.ENABLE_RUST 恒为 true。
        assertThat(FeatureFlags.ENABLE_RUST).isTrue()
    }

    @Test
    fun volatileFlags_defaultToEnabledRust() {
        // 默认值为 USE_RUST_LAYOUT = false（为支持图片渲染而改为 false）
        assertThat(FeatureFlags.USE_RUST_ENCODING_DETECTOR).isTrue()
        assertThat(FeatureFlags.USE_RUST_TXT_PARSER).isTrue()
        assertThat(FeatureFlags.USE_RUST_EPUB_PARSER).isTrue()
        assertThat(FeatureFlags.USE_RUST_LAYOUT).isFalse()
    }

    @Test
    fun isRustAvailable_withoutLibrary_returnsFalse() {
        // 本地无 .so 库，NativeBridge.isLibraryLoaded() 返回 false
        assertThat(FeatureFlags.isRustAvailable()).isFalse()
    }

    @Test
    fun useRustTxtParser_withoutLibrary_returnsFalse() {
        // 即使 USE_RUST_TXT_PARSER=true，库未加载也应返回 false
        assertThat(FeatureFlags.useRustTxtParser()).isFalse()
    }

    @Test
    fun useRustEpubParser_withoutLibrary_returnsFalse() {
        assertThat(FeatureFlags.useRustEpubParser()).isFalse()
    }
}
