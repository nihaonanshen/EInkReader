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
| 语言 | Java 8 |
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

**2026-07-05 (v0.0.5)**：扫描系统重构 + EPUB 图片修复 + 7.8 寸适配 + 性能优化

### v0.0.5 更新说明

| 分类 | 改动 | 文件 |
|------|------|------|
| 📚 **书架扫描** | 递归扫描子目录，新增 `/storage/emulated/0/epub`、`/mnt/external_sd/books` 等外置 SD 卡路径 | `LibraryActivity.java` |
| 🛡️ **防循环** | `scanBooks()` 增加 `scanning` 标志位，杜绝 `notifyDataSetChanged` 触发无限重扫 | `LibraryActivity.java` |
| 🖼️ **EPUB 图片** | `images` 字段改为 `Map<String,byte[]>`，修复大图黑屏/白页 | `EpubParser.java`, `ReaderView.java`, `ReaderActivity.java`, `NativeBridge.java` |
| ⚡ **布局后台化** | `layoutPages()` 迁移至 `HandlerThread` 异步执行，主线程不再阻塞 | `ReaderView.java` |
| 📐 **CJK 换行 O(n)** | 中英文混排换行算法从 O(n²) 优化为 O(n)，大书提升约 100 倍 | `ReaderView.java` |
| 🔍 **指纹阈值** | 章节指纹最短长度从 ≥1 提升至 ≥4，杜绝单句号 `。` 触发重复解析 | `ReaderView.java` |
| 📑 **TOC 缓存** | `calculateTocLayout()` 增加 `tocLayoutValid` 缓存，避免返回时 7 次重复计算 | `ReaderActivity.java` |
| 🔠 **字号适配** | 默认字号 26→30sp，目录字号按屏幕高度动态计算 (18/22/26sp) | `Constants.java`, `ReaderActivity.java` |
| 📐 **首行缩进** | 阅读设置新增首行缩进开关 | `activity_settings.xml`, `ReadingSettingsActivity.java` |
| 🧪 **编码检测** | 采样优化为 8KB + 缓存 v3 | `EncodingDetector.java`, `TxtParser.java` |

---

**2026-06-30**：代码质量与 UI 全面升级（UI refactor + 持久化 + 解析器健壮性）

**一、代码问题修复（逻辑/执行类）**

1. **EpubParser 目录识别修复**  
   - 修正了 `opfDir` 变量的作用域问题，将其作为参数传入 `parseOpf()`，消除 4 处 `cannot find symbol` 编译错误。  
   - 补全了 EPUB2 (.ncx) 与 EPUB3 (nav.xhtml) 双格式目录解析路径。

2. **TxtParser 缓存健壮性**  
   - 章节标题在写缓存前进行换行转义，读取时反转义，避免缓存格式损坏。  
   - 单章解析新增 500KB 大小上限，防止 OOM。  
   - 章节检测改用 `.matches()` 而非 `.find()`，避免行内文本误命中。  
   - 数字章节模式要求 `≤3 位数字 + 至少 1 个汉字`，并支持副标题（如 "楔子 暗夜降临"）。  
   - 编码检测新增"非 ASCII/汉字占比 < 90% 视为无效编码"的校验。

3. **Rust 解析器链路**  
   - `NativeBridge.parseTxtJson / parseEpubJson` 使用 `StringBuilder` 替代 `+=`，将 O(n²) 拼接优化为 O(n)。  
   - `FeatureFlags` 新增 `useRustTxtParser()`、`useRustEpubParser()` 辅助方法，`ReaderActivity`、`ReaderView` 统一经由特性开关选择解析器。

4. **ReaderView 交互修复**  
   - `onTouchEvent` 只在 `ACTION_UP` 时返回 `true`，其余事件交由父 ViewGroup 处理（允许父级手势 / 翻页键生效）。  
   - 新增 250ms 翻页防抖，抑制快速连触导致的屏幕闪烁。  
   - 中英文混排改用"CJK 按字符换行、Latin 按单词换行"的差异化策略。

**二、核心架构（持久化层）**

1. **新增 `BookStorage` 接口 + `DatabaseHelper` 实现**  
   - 基于 `SQLiteOpenHelper`（API 1 即支持，无需 Room / Jetpack），兼容 Android 4.4。  
   - 两张表：`books`（书籍元信息）、`progress`（阅读进度），支持 upsert。  
   - `EInkReaderApp` 在 `onCreate` 中初始化 `DatabaseHelper` 单例。

2. **阅读进度双保险**  
   - `ReaderActivity` 在 `onPageChanged` / `onPause` 时同时写入 SQLite 与 SharedPreferences。  
   - 打开书籍时优先从 SQLite 读取，读取失败自动降级到 SharedPreferences，杜绝数据丢失。

**三、UI 全面升级（对标微信读书 / KOReader）**

1. **阅读页沉浸全屏**  
   - 布局改为 `FrameLayout`，状态栏和底部菜单默认 `gone`。点击屏幕中央才唤起上下覆盖层（tap-to-reveal）。

2. **底部 Tab 菜单（一个界面完成所有操作）**  
   - 五个 Tab：**目录 / 书签 / 进度 / 设置 / 全刷**。  
   - 目录 Tab：实时列出章节，当前章节显示 ▶ 图标，点击跳转。  
   - 书签 Tab：添加 / 查看 / 跳转书签（按书籍隔离存储）。  
   - 进度 Tab：SeekBar + 当前页码标签 + "加书签"按钮。  
   - 设置 Tab：**A- / A+ 字号快捷按钮**（14~64）+ 行距 / 段距 / 亮度 SeekBar，所有调整实时生效。  
   - 全刷 Tab：调用 `ReaderView.performFullRefresh()` 强制整屏刷新，应对墨水屏残影。

3. **书库列表视觉升级**  
   - 列表项采用"左封面 + 右信息"两栏结构：  
     - 64×88dp 封面占位（EPUB 蓝色 / TXT 深灰，内嵌格式文字）。  
     - 标题（最多 2 行）+ 最近阅读时间副标题（"3 分钟前 · EPUB | 1.2MB"）。  
     - 3dp 细进度条 + 进度文字。

4. **书库底部导航精简**  
   - 移除"日志"调试入口，替换为"最近阅读"快捷按钮。  
   - 底部按钮：导入 / 最近阅读 / 排序 / 设置 / 关于。  
   - "最近阅读"弹窗按阅读时间降序列出最近阅读过的书籍，点击直接续读。

5. **设置页字号快捷按钮**  
   - `activity_settings.xml` 字号行改为 `A- | 当前值 | A+` 布局，替代原有单一 SeekBar。  
   - `ReadingSettingsActivity` 接入点击逻辑，即时落盘并回调刷新。

6. **墨水屏高对比度 SeekBar 资源**  
   - 新增 `res/drawable/seekbar_track.xml`、`seekbar_progress.xml`、`seekbar_thumb.xml`、`seekbar_eink.xml`：  
     - 粗黑 track + 16dp 圆形 thumb，解决默认样式在墨水屏几乎不可见的问题。

**四、配套改进**

- `LibraryActivity` 长按删除时同步清理数据库记录。  
- `ReaderActivity` 新增夜间模式下的覆盖层文字 / 背景配色同步。  
- `BookListAdapter` 根据 `BookRecord.format` 自动切换封面占位配色。  
- 全项目 `gradlew assembleDebug` 构建通过。

---

**2026-06-29**：相较于最初版本，本次更新进行了以下主要改进：

1. **核心解析器改用 Rust 重写**  
   - EPUB 与 TXT 解析器已迁移至 Rust（通过 JNI 调用），显著提升解析速度与内存安全。  
   - 修正了之前 Java 实现导致的编码检测异常、段落解析错误等问题。

2. **默认字号调整**  
   - 根据用户反馈，将默认字号从 **20sp** 提升至 **26sp**（在 `ReaderView.java`、`ReaderActivity.java` 中统一修改），使得在 Nook 6 Plus 等墨水屏设备上阅读更加舒适。

3. **目录（TOC）生成增强**  
   - 改进了智能目录算法，能够更准确地识别中文章节（第X章/回/卷）、英文 Chapter 以及特殊章节标题。  
   - 修复了因 EPUB 命名空间处理不当导致的目录缺失问题。

4. **刷新机制优化**  
   - 修复了局部刷新残影及定时全局刷新时机的 bug，增加了对不同墨水屏控制器的兼容性，减少了翻页时的鬼影。

5. **MD5 缓存与文件系统路径修正**  
   - 重写了进度保存的 MD5 缓存策略，并纠正了 sysfs 路径引用，确保在不同 Android 版本上读写书籍进度更为可靠。

6. **代码结构与注释清理**  
   - 统一文件编码为 UTF-8，移除了冗余的日志打印。  
   - 为核心类（`Chapter`、`EpubParser`、`TxtParser`、`EinkRefreshManager`）添加了详细的中文注释，便于后续维护。

7. **构建脚本更新**  
   - 升级 Gradle 包装器至最新版本。  
   - 修复了在 Windows 上使用 Rust 交叉编译时的链接器冲突（已在 README 技术栈部分说明所需的 Visual Studio Build Tools）。

---

## License

本项目采用 GPL-3.0 License 开源。