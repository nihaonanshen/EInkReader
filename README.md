# E-Ink Reader (墨水屏阅读器)

> **零基础开发者 × AI 辅助 = 一个能用的墨水屏阅读器**

本项目由一位零 Android 开发经验的作者，全程使用 AI 编程助手 **Reasonix**（基于 **DeepSeek V4** 模型）辅助完成。所有代码均为 AI 生成+人工测试迭代，旨在为墨水屏设备提供一个轻量、快速、专注阅读的 TXT/EPUB 阅读器。

---

## 功能特点

| 功能 | 说明 |
|------|------|
| 📖 格式支持 | TXT / EPUB |
| 🔤 编码检测 | 自动识别 GBK / UTF-8 / Big5 / GB18030 |
| 📑 智能目录 | 中文章节（第X章/回/卷）+ 英文 Chapter + 特殊章节提取 |
| 🖼️ 图片显示 | EPUB 内嵌图片渲染 |
| 🔠 字体设置 | 可调节字号 / 行距 / 段距 / 边距 |
| 🆒 自定义字体 | TTF/OTF 字体文件支持 |
| 🌙 夜间模式 | 黑底灰字，适合暗光阅读 |
| ⚡ 刷新优化 | 局部刷新翻页，定时全局刷新清除残影 |
| 📚 书架管理 | 按时间 / 名称 / 格式排序，阅读进度显示 |
| ⏱️ 阅读统计 | 累计阅读时长统计 |
| 💾 进度保存 | 自动保存章节和页码，下次打开续读 |
| 🔍 调试日志 | 内置日志记录，方便问题定位 |

---

## 快速开始

### 下载 APK

前往 [Releases](https://github.com/xfl1996/EInkReader/releases) 页面下载最新 APK 安装包。

### 从源码构建

```bash
git clone https://github.com/xfl1996/EInkReader.git
cd EInkReader
./gradlew assembleDebug
```

### 使用方式

1. 将 TXT/EPUB 文件放到设备 SD 卡的 `Books/`、`eBooks/`、`EInkReader/` 等目录
2. 打开 APP，书库自动扫描
3. 点击书籍开始阅读
4. 屏幕中央点击显示菜单和状态栏
5. 音量键 / 翻页键上下翻页

---

## 技术栈

| 组件 | 选择 |
|------|------|
| 开发工具 | Android Studio + Gradle |
| 最低 SDK | Android 4.4 (API 19) |
| 目标 SDK | Android 15 (API 35) |
| AI Agent | Reasonix |
| AI 模型 | DeepSeek V4 |
| 语言 | Java 8 |
| UI | 纯 Canvas 绘制（无 WebView） |

## 项目结构

```
app/src/main/java/com/einkreader/
├── core/
│   ├── model/Chapter.java        # 章节数据模型
│   ├── parser/
│   │   ├── EpubParser.java       # EPUB 解析器
│   │   └── TxtParser.java        # TXT 解析器
│   └── refresh/
│       └── EinkRefreshManager.java # 墨水屏刷新管理
├── ui/
│   ├── library/
│   │   ├── LibraryActivity.java  # 书库首页
│   │   └── BookListAdapter.java  # 书籍列表适配器
│   ├── reader/
│   │   ├── ReaderActivity.java   # 阅读界面
│   │   ├── ReaderView.java       # 自定义渲染 View
│   │   ├── TocActivity.java      # 目录页面
│   │   ├── ReadingSettingsActivity.java # 阅读设置
│   │   ├── DebugLog.java         # 调试日志
│   │   └── DebugLogActivity.java # 日志查看页面
│   └── settings/
│       └── AboutActivity.java    # 关于页面
└── utils/
    └── EncodingDetector.java     # 文本编码检测
```

## 开发背景

这个项目的作者是 Android 开发的零基础新手，完全依靠 AI 编程助手来完成代码编写。整个过程展示了 AI 辅助编程的可能性——从零开始构建一个功能完整的 Android 应用。

**开发过程：**
1. 用自然语言向 AI 描述需求
2. AI 生成代码和修改方案
3. 构建 APK 安装到墨水屏设备上测试
4. 发现 Bug 后描述现象，AI 定位并修复
5. 功能迭代：从基本阅读到书架管理、搜索、夜间模式等

## 最近更新

- 2026-06-29：将代码推送到 GitHub 仓库 nihaonanshen/EInkReader（使用个人访问令牌推送），并更新了此 README 以记录推送过程。

---

## 最近更新

- **2026-06-29**：相比最初版本，进行了以下主要改进：
  1. **核心解析器改用 Rust 重写**：EPUB 与 TXT 解析器已迁移至 Rust（通过 JNI 调用），显著提升解析速度与内存安全，修复了之前由 Java 实现导致的编码检测异常和段落解析错误。
  2. **默认字号调整**：根据用户反馈，将默认字号从 20sp 提升至 26sp（在 ReaderView.java、ReaderActivity.java 中统一修改），使得在 Nook 6 Plus 等墨水屏设备上阅读更加舒适。
  3. **目录（TOC）生成增强**：改进了智能目录算法，现在能够更准确地识别中文章节（第X章/回/卷）、英文 Chapter 以及特殊章节标题，并修正了因 EPUB 命名空间处理不当导致的目录缺失问题。
  4. **刷新机制优化**：修复了局部刷新残影及定时全局刷新时机的 bug，增加了对不同墨水屏控制器的兼容性，减少了翻页时的鬼影。
  5. **MD5 缓存与文件系统路径修正**：重写了进度保存的 MD5 缓存策略，并纠正了 sysfs 路径引用，确保在不同 Android 版本上读写书籍进度更为可靠。
  6. **代码结构与注释清理**：统一文件**：UTF-8，移除的日志打印，并为核心类（如 Chapter、EpubParser、TxtParser、EinkRefreshManager）添加了详细的中文注释，便于后续维护。
  7. **构建脚本更新**：升级 Gradle 包装器至最新版本，并修复了在 Windows 上使用 Rust 交叉编译时的链接器冲突（已在 README 中的技术栈部分说明了所需的 Visual Studio Build Tools）。

  本次更新还同步了此 README 文档，使其与当前代码库保持一致。

---

## License

本项目采用 GPL-3.0 License 开源。