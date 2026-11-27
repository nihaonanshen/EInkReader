# EInkReader 更新日志

## [未发布] - 2026-11-27

### 核心架构重构

#### Rust 核心层 (einkreader-core)

**文本布局引擎全面重写 (layout.rs)**
- 新增: layout_text_binary() - 布局结果 bincode 二进制序列化，JNI 跨语言零拷贝传输
- 新增: batch_layout_texts() / batch_layout_texts_binary() - 批量文本布局，多段文本一次性排版，显著降低 JNI 调用开销
- 新增: LayoutParams 结构体 - 统一布局参数容器，支持序列化/反序列化
- 测试覆盖: 新增 11 项单元测试，覆盖：
  - 基础 ASCII/中文文本布局、空文本、坐标单调性、二进制往返
  - 翻页逻辑、大文本性能 (<50ms/5000字)、NaN/Inf 参数拒绝 (P0-#9)
  - padding 吞噬全宽拒绝 (P0-#9)、para_types 长度不匹配安全回退 (P1-#10)
  - PARA_IMAGE(5) 图片标记段回退正文样式 (P1-#26)

**EPUB 解析器深度优化 (parser/epub/)**
- 安全: ZIP Slip 全链路防护 - sanitize_zip_path() 拦截路径遍历、..、反斜杠、绝对路径、空字节
- 安全: ZIP 条目读取分块限流 (read_limited) - 不信任 ZIP 头自报 size，恶意包虚报小值也安全，上限 64MB/条目
- 图片存储格式变更: 从 base64 String 改为原始 Vec<u8> (types.rs) - 节省 ~33% 传输体积，bincode 线上同形
- 图片配额收紧: 单图 1MB / 总计 16MB (原 64MB) - 适配 Nook i.MX6 Dalvik 256MB growthlimit，base64 膨胀 ~25% -> ~85MB byte[] 驻留风险
- 封面提取: 优先读取 manifest properties="cover-image" 项，key 固定 __cover__ 供书架显示
- 图片去重: O(1) HashSet 去重替代 Vec::contains，大图书 O(n^2) -> O(n)
- 目录页剔除: 双重判定机制
  1. 精确匹配 parse_ncx 记录的实际目录来源文件 (toc_sources)
  2. 文件名含 toc/nav/目录 的 HTML/XHTML 文件兜底剔除
- 懒加载章节: load_chapter_content() 按需读取，单章上限 500KB，超长截断提示
- 测试: 新增完整 EPUB 构造测试，验证图片字节提取、IMAGE 标记、封面往返

**XHTML 解析器修复 (parser/epub/xhtml.rs)**
- 修复 [review-#7]: HTML5 optional-equals 语法支持 - src = "a.jpg"、src   =   'b.jpg' 空白不再漏匹配
- 边界回归: srcx= 不再误判为 src，短输入 <img src 安全返回 None

**ZIP 工具链加固 (parser/epub/zip_utils.rs)**
- 安全: 全链路 read_limited 分块限流读取，防 Zip Bomb
- 修复 [P0-#5]: 封面缩略图生成
  - 修复 scale 计算 .max(1.0) 导致大图永不缩小的 Bug
  - 真正编码为 PNG (原输出裸 RGBA 无法解码)，编码失败回退原字节
- 测试: 360px 长边缩放验证、小图原样透传验证

**TXT 解析器文档化 (parser/txt.rs)**
- 文档 [review-#8]: 核实"宽松失配 -> 严格回退"分支可达性 - 多定界符 + 中文数字编号 (CHAPTER  十二、Chapter - 三) 仅严格版 [.-:\s]* 接受
- 测试: 钉死严格分支可达性

**类型定义同步 (types.rs)**
- EpubParseResult.images: HashMap<String, String> -> HashMap<String, Vec<u8>> (原始字节)

---

### Android 应用层

**核心桥接与 JNI (NativeBridge.kt, jni_bridge.rs)**
- 重构: JNI 桥接层全面适配新布局引擎二进制接口
- 性能: 批量布局 batch_layout_texts_binary 替代逐段调用，JNI 往返开销大幅降低
- 修复: 611 行变更，参数传递、错误处理、内存管理同步优化

**阅读器核心体验 (ReaderActivity.kt, ReaderView.kt)**
- 重构: 2920/2757 行深度重构 - 翻页、刷新、前光、手势、进度、目录联动
- 性能: 布局计算异步化、预渲染、缓存策略优化
- 体验: 刷新模式精细控制 (A2/GC16/DU/Regal)、前光色温联动、阅读进度实时同步

**EPUB 解析与回退链**
- EpubParserFallback.kt: Kotlin 侧回退解析器同步 Rust 侧修复 (图片、目录、编码)
- HtmlCleaner.kt: HTML 清理器适配新 IMAGE 标记格式
- TxtParser.kt: TXT 解析器同步章节识别优化

**刷新与前光管理**
- EinkRefreshManager.kt: 刷新策略重构，支持更多波形模式、区域刷新优化
- FrontlightController.kt: 前光控制器微调

**存储与仓储层**
- BookStorage.kt / DatabaseHelper.kt: 数据库 Schema 同步、索引优化、迁移脚本
- ReaderRepositoryImpl.kt: 仓储实现适配新数据结构 (原始图片字节、章节懒加载路径)

**UI 组件**
- BookListAdapter.kt / LibraryActivity.kt: 书架列表适配封面原始字节、加载性能优化
- RefreshSettingsActivity.kt: 刷新设置 UI 同步新波形选项

**工具类**
- EncodingDetector.kt: 编码检测器同步优化

**构建与配置**
- app/build.gradle: 依赖版本升级、Rust 产物集成优化、ProGuard 规则同步
- app/proguard-rules.pro: 混淆规则适配新类/方法
- app/src/main/AndroidManifest.xml: 权限、组件声明同步

**测试体系**
- NativeBridgeApiTest.kt / NativeBridgeBincodeTest.kt / DatabaseHelperTest.kt: 单元测试同步新接口

---

### 文档与架构决策

- docs/adr/0004-mixed-architecture.md: 记录混合架构 (Rust + Kotlin) 决策演进、图片存储格式变更理由、性能基准
- README.md: 更新 P1/P2/P3 批次 changelog、图片配额修正 (16MB)、架构概览

---

### 版本标识

**建议版本**: v1.1.0 (Minor - 核心引擎重写、数据格式变更、API 不兼容)

**破坏性变更**:
- Rust EpubParseResult.images 值类型: String (base64) -> Vec<u8> (原始字节)
- JNI 接口签名变更: 新增批量布局二进制接口，旧单段接口保留但标记废弃
- 数据库 Schema 版本升级 (需迁移)

---

### 变更统计

- 文件变更: 30 文件
- 代码增删: +8504 / -7753 行
- 核心模块: Rust 核心层 11 文件、Android 应用层 17 文件、文档 2 文件
- 测试新增: 14 项单元测试 (Rust 侧)

---

### 致谢

本次更新集成了代码审查 (P1/P2) 反馈、性能基准 (Nook e-ink 实机) 驱动的优化、安全审计修复。感谢所有参与测试与反馈的用户。