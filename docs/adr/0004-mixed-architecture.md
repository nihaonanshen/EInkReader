# Rust + Kotlin 混合架构

## Context

EInkReader 需要处理高耗时的文本解析（TXT/EPUB排版）、编码检测、字符串操作等任务，同时需要与 Android 框架（Java/Kotlin）交互，访问文件系统、UI、存储等。纯 Kotlin 方案在解析大文件时可能性能不足且容易阻塞主线程；纯 Rust 方案则与 Android 集成复杂，JNI 开销同样存在。需要在性能和开发效率之间取得平衡。

## Decision

采用 **混合架构**：计算密集型核心逻辑用 Rust 编写，通过 JNI 暴露给 Kotlin 调用；Android 框架层、UI、数据存储、业务流控制用 Kotlin 实现。

具体技术选型：
- **Rust 侧**：`einkreader-core` crate，编译为动态库（.so），提供以下功能：
  - 编码检测 (`encoding.rs`)
  - TXT 解析 (`parser/txt.rs`)
  - EPUB 解析 (`parser/epub.rs`)
  - 文本布局排版 (`layout.rs`)
  - JNI 桥接 (`jni_bridge.rs`)
- **Kotlin 侧**：`com.einkreader.core.NativeBridge` 类通过 `System.loadLibrary("einkreader_core")` 加载原生库，声明 `external fun` 方法调用 Rust 函数
- **数据交换**：目前使用 JSON 序列化（serde_json），未来计划迁移到 bincode 二进制格式以降低序列化和传输开销

## Considered Options

- **纯 Kotlin 实现**：所有逻辑都用 Kotlin/Java 编写。优点是无需 JNI 桥接，开发调试方便；缺点是文本解析和排版性能较差，大文件读取可能卡顿，无法充分利用 Rust 的高性能和内存安全特性。
- **纯 C/C++ 替代 Rust**：C 也可以写高性能代码且 JNI 支持成熟。但 Rust 的所有权模型能在编译期消除许多内存错误，且标准库更现代，长期维护成本更低。
- **WebAssembly (Wasm)**：尝试将 Rust 编译为 Wasm 在 Android 上运行。但目前 Android 对 Wasm 的支持仍在实验阶段，性能不如原生 .so，且增加了构建复杂性。
- **多进程通信**：Rust 作为独立进程通过 socket 或管道与 Android 应用通信。进程间开销过大，不适合高频调用的排版场景。

最终选择 Rust + JNI 是在性能、安全性和开发效率之间的最佳折中。

## Consequences

- ✅ 核心解析路径获得 Rust 的高性能保障（内存安全 + 零成本抽象）
- ✅ Kotlin 端保持简洁，只需关注业务逻辑和 UI 交互
- ⚠️ JNI 桥接带来一定的开发和调试复杂度（类型转换、异常处理、生命周期管理）
- ⚠️ 需要针对不同 ABI（arm64-v8a, armeabi-v7a 等）分别编译 Rust 库
- ✅ 可通过 FeatureFlags 开关动态选择使用 Rust 或 Java fallback，便于兼容和调试
- 架构清晰，核心算法与 Android 框架解耦，便于测试和替换实现
