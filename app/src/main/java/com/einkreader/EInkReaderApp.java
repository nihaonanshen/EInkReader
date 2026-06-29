package com.einkreader;

import android.app.Application;

import com.einkreader.core.parser.TxtParser;
import com.einkreader.core.parser.EpubParser;

/**
 * 应用入口 —— APP 启动时最先运行这里
 * 做一些全局的初始化工作
 */
public class EInkReaderApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        // 初始化解析器的缓存目录（这样第二次打开同一本书就快很多）
        TxtParser.initCacheDir(getCacheDir());
        EpubParser.initCacheDir(getCacheDir());
    }
}
