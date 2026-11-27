package com.einkreader


import android.util.Log
import androidx.multidex.MultiDexApplication
import com.einkreader.core.NativeBridge
import com.einkreader.core.parser.TxtParser
import com.einkreader.di.ServiceLocator

/** 应用入口 */
class EInkReaderApp : MultiDexApplication() {
    override fun onCreate() {
        super.onCreate()
        // [review-#32] ServiceLocator 先于 TxtParser 初始化（TxtParser 依赖其缓存目录能力）；
        // 各模块独立 try/catch：单模块失败只记日志，不拖垮整个 App 启动
        try {
            ServiceLocator.init(this)
        } catch (e: Exception) {
            Log.e("EInkReaderApp", "ServiceLocator init failed", e)
        }
        try {
            TxtParser.initCacheDir(cacheDir)
        } catch (e: Exception) {
            Log.e("EInkReaderApp", "TxtParser init failed", e)
        }
        try {
            // [review-#41] 记录实际生效的核心路径开关组合（rust-core / java-fallback），便于现场排障
            val loaded = NativeBridge.sLibraryLoaded
            Log.i("EInkReaderApp", "core path: ENABLE_RUST=" + BuildConfig.ENABLE_RUST
                + " rustLoaded=" + loaded
                + " effective=" + if (loaded) "rust-core" else "java-fallback")
        } catch (e: Exception) {
            Log.e("EInkReaderApp", "switch-log init failed", e)
        }
    }
}
