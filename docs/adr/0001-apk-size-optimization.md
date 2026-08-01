# APK 体积优化

为减少 EInkReader APK 安装体积，提升下载和安装体验，决定实施以下三项体积优化措施：精简 ProGuard 规则、启用资源收缩、ABI 过滤。

## Context

E-Ink Reader 面向墨水屏设备用户，主要通过 Wi-Fi 直接安装 APK 或从第三方市场下载。当前 APK 体积约 4.3MB，其中原生库（.so）占比较大。用户反馈在带宽受限环境（如老旧阅读器设备）下下载较慢。

## Decision

1. **ProGuard 规则简化**  
   将原本过度保守的 `-keepclassmembers class * implements java.io.Serializable`（保留所有 Serializable 类的序列化成员）改为仅显式保留实际使用的模型类（Chapter、EpubResult、TxtParseResult等）。这减少了 ProGuard 处理的保持规则数量，使 R8/ProGuard 能更好地混淆和移除未使用的代码。

2. **启用 shrinkResources**  
   在 release build 中设置 `shrinkResources true`，让 Gradle 自动删除 APK 中未被引用的资源文件（Drawable、Layout、Value 等），尤其清理掉多密度版本中不需要的图片资源。

3. **ABI 过滤**  
   在 defaultConfig 中添加 `ndk { abiFilters 'armeabi-v7a', 'arm64-v8a' }`，只构建两种主流的 CPU 架构原生库，移除 x86_64（仅用于模拟器，对目标用户无用）。预计 .so 文件体积可减少 30-40%。

## Consequences

- ✅ APK 整体体积预计减少 15-25%（主要受益于资源收缩和原生库缩减）
- ✅ ProGuard 混淆更彻底，反编译难度略微增加
- ⚠️ 需要测试确认没有误删必要资源（特别是不同屏幕密度的图标）
- ⚠️ 不支持 x86 模拟器；如需在 x86 模拟器上调试，可临时调整 abiFilters
- ✅ 所有变更均为声明式配置，无运行时逻辑改动，风险可控

## Alternatives Considered

- **WebP 图片转换**：将所有 PNG 转为 WebP 格式可进一步减小资源体积，但需要批量转换工具且可能影响兼容性，留作后续优化。
- **仅保留 arm64-v8a**：单纯舍弃 armv7a 会失去对旧设备的覆盖，考虑到部分入门级墨水屏仍使用 armeabi-v7a，故同时保留两者。
- **动态 Feature Delivery**：通过 Android App Bundle 的动态模块按需加载原生库，实现更细粒度的体积控制，但引入构建复杂性，当前阶段优先简单方案。
