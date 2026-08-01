# E-Ink Reader (墨水屏阅读器)

> 专注于墨水屏设备的轻量 TXT/EPUB 阅读器。Kotlin + Rust 双引擎，优化性能和体积。

所有代码均为 AI 生成 + 人工测试迭代，为墨水屏设备提供专注阅读体验。

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
| ⚙️ Rust 核心引擎 | TXT/EPUB 解析 + 文本布局由 Rust 原生库实现（JNI），支持 JSON 与 bincode 双序列化 |
| 🔌 批量布局 | 一次 JNI 调用完成多个段落布局（适用于目录页等场景）|
| 📄 EPUB 懒加载 | 章节内容按需读取，打开大 EPUB 文件时内存下降 30%+ |
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

# 1. 构建 Rust 核心库（生成 .so，需 Rust + Android NDK）
cd rust && ./build-rust.sh && cd ..   # Windows: .\build-rust.ps1

# 2. 构建 APK
./gradlew assembleDebug
```

> `.so` 为构建产物，不入库；CI 会自动交叉编译。仅运行单元测试可跳过第 1 步（解析器自动回退 Java 实现）。

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
| 语言 | Kotlin + Rust |
| Rust 核心 | einkreader-core（编码检测 / 文本解析 / 分页布局） |
| 序列化 | bincode（Rust ⇄ Kotlin JNI） |
| UI | 纯 Canvas 绘制（无 WebView） |
| 许可证 | MIT |

---

## 项目结构

```
app/src/main/java/com/einkreader/
├── core/
│   ├── model/Chapter.kt              # 章节数据模型
│   ├── model/EpubResult.kt           # EPUB 解析结果
│   ├── parser/
│   │   ├── EpubParserFallback.kt     # EPUB 解析器（Java 回退实现）
│   │   └── TxtParser.kt              # TXT 解析器
│   ├── refresh/
│   │   └── EinkRefreshManager.kt     # 墨水屏刷新管理
│   ├── storage/
│   │   ├── BookStorage.kt            # 数据访问接口
│   │   └── DatabaseHelper.kt         # SQLite 持久化实现
│   ├── FeatureFlags.kt               # 功能开关（Rust / Java 解析器切换）
│   └── NativeBridge.kt               # Rust JNI 桥接（bincode 序列化）
├── ui/
│   ├── library/
│   │   ├── LibraryActivity.kt        # 书库首页
│   │   └── BookListAdapter.kt        # 书籍列表适配器
│   ├── reader/
│   │   ├── ReaderActivity.kt         # 阅读界面（Tab 菜单 / 书签 / 全刷）
│   │   ├── ReaderView.kt             # 自定义渲染 View（防抖翻页 / 全刷）
│   │   ├── TocActivity.kt            # 独立目录页
│   │   ├── ReadingSettingsActivity.kt # 阅读设置（A-/A+ 字号快捷）
│   │   └── DebugLog.kt               # 调试日志
│   └── settings/
│       └── AboutActivity.kt          # 关于页面
└── EInkReaderApp.kt                  # Application 入口（初始化 DatabaseHelper）
```

```
rust/einkreader-core/src/
├── lib.rs                 # 模块导出
├── jni_bridge.rs          # JNI 桥接层（bincode 序列化）
├── types.rs               # 跨 FFI 数据类型
├── encoding/              # 编码检测（BOM + 统计 + 解码评分）
├── layout.rs              # 文本布局引擎（分页 + 精确坐标）
└── parser/
    ├── txt.rs             # TXT 解析器
    └── epub/              # EPUB 解析器
        ├── mod.rs         # 入口（parse_epub / load_chapter_content）
        ├── container.rs   # container.xml 解析
        ├── opf.rs         # OPF 元数据 / manifest / spine
        ├── toc.rs         # NCX + EPUB3 nav 目录解析
        ├── title.rs       # 章节标题提取
        ├── xhtml.rs       # 正文内容解析与 HTML 清理
        └── zip_utils.rs   # ZIP 条目安全读取
```

---

# 最近更新

**2026-08-01**：Phase 8 — 仓库整理与序列化收敛

### 序列化收敛
- Rust 与 Kotlin 之间的数据交换统一为 **bincode 二进制序列化**，移除全部 JSON 路径（`nativeParseTxt` / `nativeParseEpub` / `nativeLayoutText` / `nativeLayoutTextsBatch` 的 JSON 版）
- 批量布局改为 bincode：`nativeLayoutTextsBatchBinary` + `parseBatchLayoutBinary`
- 移除 `serde_json` 依赖；解析失败直接回退 Java 实现

### 仓库卫生
- `.so` 构建产物移出版本库（由 `build-rust.sh` / CI 生成），删除冗余的 x86_64 目标
- 删除遗留备份文件（`build.gradle.bak2`、重复的 `ci.yml` 等）
- 清理 `master` / `migrate-kotlin` 已合并分支
- 添加 MIT LICENSE

### 测试
- 全部测试迁移至 Kotlin（删除重复的 `ChapterTest.java` 等）
- 集成测试改为环境变量 `EINKREADER_TEST_BOOKS_DIR` 配置书籍目录，目录不存在时自动跳过（不再硬编码 `G:/epub`）
- Rust 46 个单元测试全部通过

### Rust 代码组织
- `epub.rs`（1559 行）拆分为 `epub/` 子模块：`mod.rs` / `container.rs` / `opf.rs` / `toc.rs` / `title.rs` / `xhtml.rs` / `zip_utils.rs`

---

**2026-07-31**：Phase 7 — 性能优化与新功能

### Rust 核心层
- TXT 章节标题正则合并优化：8 个独立正则 → 1 个组合正则（`combined`），消除 O(n×m) 多次匹配
- EPUB 懒加载骨架完成：`EpubChapter.content` 改为 `Option<String>`，新增 `load_chapter_content()`
- 批量布局 API：`batch_layout_texts()` + JNI 接口 `nativeLayoutTextsBatch()`
- 布局引擎微优化：`char_width_em()` inline、`mem::replace` 替代 clone
- JNI 符号清理：移除未使用的导入

### Android 应用层
- `Chapter` 类新增 `xhtmlPath` 字段，支持 EPUB 章节懒加载跟踪
- `NativeBridge` 新增 bincode 二进制解析器：`parseTxtBinary()` / `parseEpubBinary()`
- `NativeBridge` 新增 `batchLayoutTextsParsed()` 便捷方法，直接返回 `List<LayoutResult>`
- `ReaderActivity` 集成 EPUB 惰性加载：章节内容为空且 xhtmlPath 存在时，自动异步加载
- `Chapter` 类添加 `@JvmOverloads` 注解，保证 Java 调用的多构造器兼容性
- APK 体积优化：启用 shrinkResources + ndk abiFilters

### 测试
- 新增 Kotlin 单元测试：`ChapterTest.kt` / `EpubResultTest.kt` / `NativeBridgeBincodeTest.kt`
- Rust 全部 46 个单元测试通过

---

**2026-07-24**：全面代码审查与优化（Phase 1-6）

### 问题修复
- 修复了 3 个 Critical 级编译错误（NativeBridge.kt 结构断裂、@Volatile 导入错误、缺失 isLayoutCached 方法）
- 移除硬编码签名密码，改用环境变量
- 清理遗留备份文件（*.bak、_original.*）
- 修复 Rust 端 OOM 风险：添加 50MB 文件大小限制（TXT/EPUB）
- 修复 Rust 端 ZIP Slip 漏洞：添加路径遍历防护
- 修复 Rust 端 ZIP Bomb：限制条目数量与章节大小
- 修复 Kotlin 空处理：大量 !! 替换为安全调用 + 默认值
- 修复 Cursor 泄漏：使用 use{} 自动关闭
- 将 Thread(Runnable) 迁移到协程（CoroutineScope.Dispatchers.IO）
- 优化 ProGuard 规则：从全量保留改为精准保留
- 增大 NativeBridge LRU 缓存：3 → 20 条
- 添加单元测试：EpubParserTest（10 个）、RepositoryTest（11 个）
- 添加 GitHub Actions CI/CD：双通道（Android 单元测试 + Rust 检查）
- 添加 Multidex 与 Core Library Desugaring 以支持 Java 8 Lambda 在 Android 4.4

### 技术栈更新
- 语言：Kotlin + Rust（bincode 二进制序列化 + JNI）
- 构建工具：Android Studio + Gradle（启用 Multidex 与 Core Library Desugaring）
- 最低 SDK：Android 4.4 (API 19) 保持不变
- 目标 SDK：Android 15 (API 35)
- UI：纯 Canvas 绘制（无 WebView）

---
