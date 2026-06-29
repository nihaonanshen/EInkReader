#!/bin/bash
# EInkReader Rust 交叉编译脚本
# 需要在安装了 Android NDK + Rust Android targets 的 macOS/Linux 环境中运行
# 或手动配置 Android NDK 路径

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CRATE_DIR="$SCRIPT_DIR/einkreader-core"
JNILIB_DIR="$SCRIPT_DIR/../app/src/main/jniLibs"

echo "=== EInkReader Rust Core Build ==="
echo "Crate dir: $CRATE_DIR"
echo "Output dir: $JNILIB_DIR"

# 检查 cargo
if ! command -v cargo &> /dev/null; then
    echo "ERROR: cargo not found. Install Rust: https://rustup.rs"
    exit 1
fi

# 确保 Android 目标已安装
echo "=== Checking Rust Android targets ==="
for target in aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android; do
    if ! rustup target list --installed | grep -q "$target"; then
        echo "Installing target: $target"
        rustup target add "$target"
    fi
done

# 根据环境设置 NDK
if [ -z "$ANDROID_NDK_HOME" ]; then
    # 尝试常见路径
    if [ -d "$HOME/Android/Sdk/ndk" ]; then
        ANDROID_NDK_HOME=$(ls -d "$HOME/Android/Sdk/ndk/"*/ 2>/dev/null | head -1)
    elif [ -d "$ANDROID_HOME/ndk" ]; then
        ANDROID_NDK_HOME=$(ls -d "$ANDROID_HOME/ndk/"*/ 2>/dev/null | head -1)
    elif [ -d "/usr/local/lib/android/sdk/ndk" ]; then
        ANDROID_NDK_HOME=$(ls -d "/usr/local/lib/android/sdk/ndk/"*/ 2>/dev/null | head -1)
    fi
fi

if [ -z "$ANDROID_NDK_HOME" ]; then
    echo "WARNING: ANDROID_NDK_HOME not set, will use cargo's built-in linker."
    echo "Set ANDROID_NDK_HOME for better cross-compilation support."
fi

export ANDROID_NDK_HOME

# 获取 NDK 工具链路径
if [ -n "$ANDROID_NDK_HOME" ]; then
    HOST_OS="linux-x86_64"
    if [ "$(uname)" = "Darwin" ]; then
        HOST_OS="darwin-x86_64"
    fi
    TOOLCHAIN_DIR="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_OS"
    echo "NDK toolchain: $TOOLCHAIN_DIR"

    # 配置链接器
    export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOLCHAIN_DIR/bin/aarch64-linux-android21-clang"
    export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$TOOLCHAIN_DIR/bin/armv7a-linux-androideabi21-clang"
    export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$TOOLCHAIN_DIR/bin/x86_64-linux-android21-clang"
    export CARGO_TARGET_I686_LINUX_ANDROID_LINKER="$TOOLCHAIN_DIR/bin/i686-linux-android21-clang"
fi

cd "$CRATE_DIR"

# 构建（仅 arm64 + armv7，x86 仅用于模拟器）
echo "=== Building for arm64-v8a ==="
cargo build --target aarch64-linux-android --release

echo "=== Building for armeabi-v7a ==="
cargo build --target armv7-linux-androideabi --release

echo "=== Building for x86_64 (emulator) ==="
cargo build --target x86_64-linux-android --release

# 复制 .so 到 jniLibs
echo "=== Copying .so files ==="
mkdir -p "$JNILIB_DIR/arm64-v8a"
mkdir -p "$JNILIB_DIR/armeabi-v7a"
mkdir -p "$JNILIB_DIR/x86_64"

cp "$CRATE_DIR/target/aarch64-linux-android/release/libeinkreader_core.so" "$JNILIB_DIR/arm64-v8a/"
cp "$CRATE_DIR/target/armv7-linux-androideabi/release/libeinkreader_core.so" "$JNILIB_DIR/armeabi-v7a/"
cp "$CRATE_DIR/target/x86_64-linux-android/release/libeinkreader_core.so" "$JNILIB_DIR/x86_64/"

echo "=== Done! .so files copied to $JNILIB_DIR ==="
ls -la "$JNILIB_DIR"/*/libeinkreader_core.so
