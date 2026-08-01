# ProGuard Rules
# EInkReader ProGuard Rules - 精简版，仅保留必要的类和方法

# --- JNI 桥接层 ---
-keep class com.einkreader.core.NativeBridge { *; }
-keepclassmembers class com.einkreader.core.NativeBridge { public static *; }

# --- 精确保留实际使用的 model 类（而非所有 **）---
-keep class com.einkreader.core.model.Chapter { *; }
-keep class com.einkreader.core.model.EpubResult { *; }
-keep class com.einkreader.core.model.TxtParseResult { *; }

# --- 序列化支持 - 仅保留实际需要的类 ---
# 移除宽泛的 "所有 implements Serializable" 规则，改为显式列出
# -keepclassmembers class * implements java.io.Serializable { ... }
# （已删除：过于保守，增加 APK 体积）

# --- Android 组件 ---
-keep class * extends android.app.Activity
-keep class * extends android.app.Application
-keep class * extends android.content.BroadcastReceiver
-keep class * extends android.app.Service

# --- 第三方库 ---
-dontwarn org.json.**
-dontwarn com.einkreader.**