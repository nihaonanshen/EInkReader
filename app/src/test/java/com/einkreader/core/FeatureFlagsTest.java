package com.einkreader.core;

import org.junit.Test;

import static com.google.common.truth.Truth.assertThat;

public class FeatureFlagsTest {

    @Test
    public void eNUMERABLE_RUST_defined() {
        // ENABLE_RUST 编译时常量，由 BuildConfig 提供
        // 在测试中 BuildConfig.ENABLE_RUST 默认为 false（因为未设置 enableRust）
        assertThat(FeatureFlags.ENABLE_RUST).isFalse();
    }

    @Test
    public void volatileFlags_defaultToEnabledRust() {
        // 默认值等于 ENABLE_RUST（测试中为 false）
        assertThat(FeatureFlags.USE_RUST_ENCODING_DETECTOR).isFalse();
        assertThat(FeatureFlags.USE_RUST_TXT_PARSER).isFalse();
        assertThat(FeatureFlags.USE_RUST_EPUB_PARSER).isFalse();
        assertThat(FeatureFlags.USE_RUST_LAYOUT).isFalse();
    }

    @Test
    public void isRustAvailable_withoutLibrary_returnsFalse() {
        // 本地无 .so 库，NativeBridge.isLibraryLoaded() 返回 false
        assertThat(FeatureFlags.isRustAvailable()).isFalse();
    }

    @Test
    public void useRustTxtParser_withoutLibrary_returnsFalse() {
        // 即使 USE_RUST_TXT_PARSER=true，库未加载也应返回 false
        assertThat(FeatureFlags.useRustTxtParser()).isFalse();
    }

    @Test
    public void useRustEpubParser_withoutLibrary_returnsFalse() {
        assertThat(FeatureFlags.useRustEpubParser()).isFalse();
    }
}