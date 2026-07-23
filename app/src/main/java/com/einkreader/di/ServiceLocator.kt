package com.einkreader.di

import android.app.Application
import android.util.Log
import com.einkreader.core.storage.BookStorage
import com.einkreader.core.storage.DatabaseHelper
import com.einkreader.repository.ReaderRepository
import com.einkreader.repository.ReaderRepositoryImpl

/** 轻量级服务定位器 */
class ServiceLocator {
    companion object {
        private const val TAG = "ServiceLocator"

        @Volatile private var _bookStorage: BookStorage? = null
        @Volatile private var _readerRepository: ReaderRepository? = null
        @Volatile private var _initialized = false

        @JvmStatic fun init(app: Application) {
            if (_initialized) return
            Log.i(TAG, "Initializing ServiceLocator")
            _bookStorage = DatabaseHelper(app)
            _bookStorage!!.initialize()
            _readerRepository = ReaderRepositoryImpl(app.applicationContext, _bookStorage)
            _initialized = true
            Log.i(TAG, "ServiceLocator initialized")
        }

        @JvmStatic fun getBookStorage(): BookStorage {
            if (_bookStorage == null) throw IllegalStateException("Not initialized. Call init() first.")
            return _bookStorage!!
        }

        @JvmStatic fun getReaderRepository(): ReaderRepository {
            if (_readerRepository == null) throw IllegalStateException("Not initialized. Call init() first.")
            return _readerRepository!!
        }

        @JvmStatic fun isInitialized(): Boolean = _initialized

        @JvmStatic fun setBookStorage(mock: BookStorage) { _bookStorage = mock }
        @JvmStatic fun setReaderRepository(mock: ReaderRepository) { _readerRepository = mock }

        @JvmStatic fun reset() {
            _bookStorage = null
            _readerRepository = null
            _initialized = false
        }
    }
}
