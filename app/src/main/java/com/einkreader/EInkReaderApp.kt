package com.einkreader

import android.app.Application
import com.einkreader.core.parser.EpubParser
import com.einkreader.core.parser.TxtParser
import com.einkreader.di.ServiceLocator

/** 应用入口 */
class EInkReaderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TxtParser.initCacheDir(cacheDir)
        EpubParser.initCacheDir(cacheDir)
        ServiceLocator.init(this)
    }
}
