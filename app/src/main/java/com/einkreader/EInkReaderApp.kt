package com.einkreader


import androidx.multidex.MultiDexApplication
import com.einkreader.core.parser.TxtParser
import com.einkreader.di.ServiceLocator

/** 应用入口 */
class EInkReaderApp : MultiDexApplication() {
    override fun onCreate() {
        super.onCreate()
        TxtParser.initCacheDir(cacheDir)
        ServiceLocator.init(this)
    }
}
