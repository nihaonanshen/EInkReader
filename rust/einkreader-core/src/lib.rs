//! EInkReader Core Library
//!
//! 本 crate 是 EInkReader Android 应用的 Rust 原生核心。
//! 提供编码检测、TXT/EPUB 解析、页面排版功能。
//!
//! 通过 JNI 桥接层 (`jni_bridge`) 暴露给 Java 调用。

pub mod encoding;
pub mod jni_bridge;
pub mod parser;
pub mod types;
