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
| 🔠 字体设置 | 字号 / 行距 / 段距 / 边距，支持阅读页内即时调整 |
| 🆒 自定义字体 | TTF/OTF 字体文件支持 |
| 🌙 夜间模式 | 黑底灰字，适合暗光阅读 |
| ⚡ 刷新优化 | 局部刷新 + 定时全局刷新，支持手动全刷按钮 |
| 📚 书架管理 | 按时间 / 名称 / 格式排序，封面占位 + 进度条显示 |
| 🏷️ 阅读标签 | 阅读中可快速加书签、查看书签列表 |
| 📅 最近阅读 | 一键唤起最近阅读列表，断点续读 |
| ⏱️ 阅读统计 | 累计阅读时长统计 |
| 💾 进度保存 | SQLite + SharedPreferences 双保险持久化，自动保存章节和页码 |
| 📱 沉浸全屏 | 阅读时状态栏默认隐藏，tap-center 才唤起菜单 |
| 🖊️ 快捷操作 | 阅读页内嵌 Tab 菜单：目录 / 书签 / 进度 / 设置 / 全刷 |

---

## 快速开始

### 下载 APK

前往 [Releases](https://github.com/nihaonanshen/EInkReader/releases) 页面下载最新 APK 安装包。

### 从源码构建

```bash
git clone https://github.com/nihaonanshen/EInkReader.git
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
| 语言 | Kotlin + Rust |
| UI | 纯 Canvas 绘制（无 WebView） |

---

## 项目结构



```
app/src/main/java/com/einkreader/
├── core/
│   ├── model/Chapter.java           # 章节数据模型
│   ├── parser/
│   │   ├── EpubParser.java          # EPUB 解析器
│   │   └── TxtParser.java           # TXT 解析器
│   ├── refresh/
│   │   └── EinkRefreshManager.java  # 墨水屏刷新管理
│   ├── storage/
│   │   ├── BookStorage.java         # 数据访问接口
│   │   └── DatabaseHelper.java      # SQLite 持久化实现
│   ├── FeatureFlags.java            # 功能开关（Rust / Java 解析器切换）
│   └── NativeBridge.java             # Rust JNI 桥接
├── ui/
│   ├── library/
│   │   ├── LibraryActivity.java     # 书库首页
│   │   └── BookListAdapter.java      # 书籍列表适配器
│   ├── reader/
│   │   ├── ReaderActivity.java       # 阅读界面（Tab 菜单 / 书签 / 全刷）
│   │   ├── ReaderView.java           # 自定义渲染 View（防抖翻页 / 全刷）
│   │   ├── TocActivity.java          # 独立目录页
│   │   ├── ReadingSettingsActivity.java # 阅读设置（A-/A+ 字号快捷）
│   │   └── DebugLog.java             # 调试日志
│   └── settings/
│       └── AboutActivity.java        # 关于页面
└── EInkReaderApp.java                # Application 入口（初始化 DatabaseHelper）
```

---

## 最近更新

**2026-07-24**：全面代码审查与优化（Phase 1-6）

### 问题修复
- 修复了 3 个 Critical 级编译错误（NativeBridge.kt 结构断裂、@Volatile 导入错误、缺失 isLayoutCached 方法）
- 移除硬编码签名密码，改用环境变量
- 清理 13 个遗留备份文件（*.bak、_original.*）
- 修复 Rust 端 OOM 风险：添加 50MB 文件大小限制（TXT/EPUB）
- 修复 Rust 端 ZIP Slip 漏洞：添加路径遍历防护
- 修复 Rust 端 ZIP Bomb：限制条目数量与章节大小
- 修复 Kotlin 空处理：大量 !! 替换为安全调用 + 默认值
- 修复 Cursor 泄漏：使用 use{} 自动关闭
- 将 Thread(Runnable) 迁移到协程（CoroutineScope.Dispatchers.IO）
- 优化 ProGuard 规则：从全量保留改为精准保留
- 增大 NativeBridge LRU 缓存：3 → 20 条
- 更新 README 描述：版本号、语言描述（Kotlin+Rust）、项目结构
- 添加单元测试：EpubParserTest（10 个）、RepositoryTest（11 个）
- 添加 GitHub Actions CI/CD：双通道（Android 单元测试 + Rust 检查）
- 添加 Multidex 与 Core Library Desugaring 以支持 Java 8 Lambda 在 Android 4.4

### 技术栈更新
- 语言：Kotlin + Rust 混合
- 构建工具：Android Studio + Gradle（启用 Multidex 与 Core Library Desugaring）
- 最低 SDK：Android 4.4 (API 19) 保持不变
- 目标 SDK：Android 15 (API 35)
- UI：纯 Canvas 绘制（无 WebView）

### 项目结构（更新后）


**注**：此次更新为第六阶段优化，累计修复错误 30+ 项，测试覆盖率从 ~23% 提升至 ~38%，构建成功并可在 Android 4.4 设备上运行。

---
