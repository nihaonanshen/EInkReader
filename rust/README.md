# EInkReader Rust Native Core

EInkReader 墨水屏阅读器的 Rust 原生核心库。

## 目录结构

```
rust/
├── build-rust.sh           # macOS/Linux 交叉编译脚本
├── build-rust.ps1          # Windows PowerShell 交叉编译脚本
├── README.md
└── einkreader-core/        # Rust crate
    ├── Cargo.toml
    └── src/
        ├── lib.rs           # 模块导出
        ├── jni_bridge.rs    # JNI 桥接层
        ├── types.rs         # 跨 FFI 数据类型
        ├── encoding/
        │   └── mod.rs       # 编码检测（BOM + 统计 + 解码评分）
        └── parser/
            ├── mod.rs
            └── txt.rs       # TXT 解析器
```

## 开发环境要求

| 工具 | 版本 | 用途 |
|------|------|------|
| Rust | 1.70+ | 编译核心库 |
| Android NDK | r25+ | 交叉编译到 Android |
| Android Rust targets | - | `rustup target add aarch64-linux-android ...` |

## 构建

### 完整交叉编译（生成 .so）

```bash
# macOS/Linux
./build-rust.sh

# Windows PowerShell
.\build-rust.ps1
```

### 仅构建（开发机测试）

```bash
cd einkreader-core
cargo test        # 运行单元测试
cargo build       # 构建主机目标
```

### 针对特定 CPU

```bash
cd einkreader-core
# 仅构建 arm64（最常用）
cargo build --target aarch64-linux-android --release
```

## 模块清单

### 已完成（Phase 1 + 2）

| 模块 | 文件 | 状态 |
|------|------|------|
| 编码检测 | `src/encoding/mod.rs` | ✅ |
| TXT 解析 | `src/parser/txt.rs` | ✅ |
| JNI 桥接 | `src/jni_bridge.rs` | ✅ |
| Java NativeBridge | `NativeBridge.java` | ✅ |

### 待实现（Phase 3+）

| 模块 | 优先级 | 说明 |
|------|--------|------|
| EPUB 解析 | Phase 3 | ZIP + XML + HTML 剥离 |
| 页面排版 | Phase 4 | rustybuzz 字体测量 + 自动换行 |

## 与 Java 实现的对应关系

| Java 类 | Rust 模块 | 接口 |
|---------|-----------|------|
| `EncodingDetector.java` | `encoding::mod` | `nativeDetectEncoding()` |
| `TxtParser.java` | `parser::txt` | `nativeParseTxt()` |
| `EpubParser.java` | `parser::epub` | `nativeParseEpub()` |
| `ReaderView.wrapText()` | `layout` | `nativeLayoutPages()` |
