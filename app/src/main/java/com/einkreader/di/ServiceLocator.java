package com.einkreader.di;

import android.app.Application;
import android.util.Log;

import com.einkreader.core.storage.BookStorage;
import com.einkreader.core.storage.DatabaseHelper;
import com.einkreader.repository.ReaderRepository;
import com.einkreader.repository.ReaderRepositoryImpl;

/**
 * 轻量级服务定位器 —— 替代 Hilt/Dagger 的零依赖 DI 方案
 *
 * 设计原则：
 * 1. 初始化时注册所有实现，运行时只读
 * 2. 测试模式可通过 reset() 替换为 Mock 实现
 * 3. 线程安全（volatile + 初始化后不可变）
 */
public class ServiceLocator {
    private static final String TAG = "ServiceLocator";

    private static volatile BookStorage sBookStorage;
    private static volatile ReaderRepository sReaderRepository;
    private static volatile boolean sInitialized = false;

    /**
     * 应用启动时调用一次
     */
    public static void init(Application app) {
        if (sInitialized) return;
        Log.i(TAG, "Initializing ServiceLocator");

        sBookStorage = new DatabaseHelper(app);
        sBookStorage.initialize();

        sReaderRepository = new ReaderRepositoryImpl(app, sBookStorage);

        sInitialized = true;
        Log.i(TAG, "ServiceLocator initialized");
    }

    public static BookStorage getBookStorage() {
        if (sBookStorage == null) {
            throw new IllegalStateException("ServiceLocator not initialized. Call init() first.");
        }
        return sBookStorage;
    }

    public static ReaderRepository getReaderRepository() {
        if (sReaderRepository == null) {
            throw new IllegalStateException("ServiceLocator not initialized. Call init() first.");
        }
        return sReaderRepository;
    }

    public static boolean isInitialized() {
        return sInitialized;
    }

    /**
     * 测试模式：注入 Mock 实现
     * 仅在单元测试中调用
     */
    public static void setBookStorage(BookStorage mock) {
        sBookStorage = mock;
    }

    public static void setReaderRepository(ReaderRepository mock) {
        sReaderRepository = mock;
    }

    /**
     * 测试模式：重置所有注册项
     */
    public static void reset() {
        sBookStorage = null;
        sReaderRepository = null;
        sInitialized = false;
    }
}