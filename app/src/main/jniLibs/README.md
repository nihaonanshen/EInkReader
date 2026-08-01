# JNI Native Libraries

此目录存放 Rust 交叉编译生成的 .so 文件。

⚠️ `.so` 为构建产物，**不提交到 git**（见 `.gitignore`）。克隆仓库后需先构建：

```bash
cd rust
./build-rust.sh      # macOS/Linux
# 或
.\build-rust.ps1     # Windows PowerShell
```

构建前需确保：
1. Rust 已安装：https://rustup.rs
2. Android NDK 已安装
3. Android Rust targets：
   ```
   rustup target add aarch64-linux-android
   rustup target add armv7-linux-androideabi
   ```

生成的文件：
- arm64-v8a/libeinkreader_core.so  (Android 8+ 主流设备)
- armeabi-v7a/libeinkreader_core.so (老设备)

> 说明：`app/build.gradle` 的 `abiFilters` 仅打包 `armeabi-v7a` 与 `arm64-v8a`，故不构建 x86 目标（模拟器如需 Rust 引擎可临时在 build 脚本中追加 `x86_64-linux-android`）。
