package com.einkreader;

import android.app.Application;

import com.einkreader.core.parser.EpubParser;
import com.einkreader.core.parser.TxtParser;
import com.einkreader.core.storage.BookStorage;
import com.einkreader.di.ServiceLocator;

/**
 * 应用入口 —— APP 启动时最先运行这里
 * 做一些全局的初始化工作
 */
public class EInkReaderApp extends Application {

    private static BookStorage bookStorage;

    @Override
    public void onCreate() {
        super.onCreate();

        // 初始化解析器的缓存目录（这样第二次打开同一本书就快很多）
        TxtParser.initCacheDir(getCacheDir());
        EpubParser.initCacheDir(getCacheDir());

        // 初始化 ServiceLocator（统一管理所有依赖）
        ServiceLocator.init(this);
        bookStorage = ServiceLocator.getBookStorage();
    }

    /**
     * 获取全局 BookStorage 实例
     * @deprecated 使用 ServiceLocator.getBookStorage() 替代
     */
    @Deprecated
    public static BookStorage getBookStorage() {
        if (bookStorage == null) {
            throw new IllegalStateException("BookStorage not initialized yet");
        }
        return bookStorage;
    }
}
