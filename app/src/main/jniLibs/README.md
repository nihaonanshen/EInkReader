# JNI Native Libraries

此目录存放 Rust 交叉编译生成的 .so 文件。

构建方法：
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
   rustup target add x86_64-linux-android
   ```

生成的文件：
- arm64-v8a/libeinkreader_core.so  (Android 8+ 主流设备)
- armeabi-v7a/libeinkreader_core.so (老设备)
- x86_64/libeinkreader_core.so     (模拟器)
