package com.einkreader.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FeatureFlagsTest {

    @Test
    fun eNUMERABLE_RUST_defined() {
        // ENABLE_RUST 编译时常量，由 BuildConfig 提供
        // 在测试中 BuildConfig.ENABLE_RUST 默认为 false（因为未设置 enableRust）
        assertThat(FeatureFlags.ENABLE_RUST).isFalse()
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
