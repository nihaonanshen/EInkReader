# ProGuard 规则（暂不混淆）
-keepattributes *Annotation*
# EInkReader ProGuard Rules
# ⚠️ SECURITY: 仅保留 JNI 和序列化所需类，不再全量保留

# --- JNI 桥接层 ---
-keep class com.einkreader.core.NativeBridge { *; }
-keepclassmembers class com.einkreader.core.NativeBridge {
    public static *;
}

# --- Rust/Java 共享数据类型 ---
-keep class com.einkreader.core.model.** { *; }
-keep class com.einkreader.core.*Result { *; }

# --- 序列化支持 ---
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    void writeObject(java.io.ObjectOutputStream, java.io.ObjectOutputStream.Field);
    void readObject(java.io.ObjectInputStream, java.io.ObjectInputStream.Field);
}

# --- Android 组件 ---
-keep class * extends android.app.Activity
-keep class * extends android.app.Application
-keep class * extends android.content.BroadcastReceiver
-keep class * extends android.app.Service

# --- 第三方库 ---
-dontwarn org.json.**
