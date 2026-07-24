package com.einkreader.ui.reader

import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLog {
    private val log = StringBuilder()
    private val sdf by lazy { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    private val dateFormat by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    private var fileReady = false
    private var logFile: File? = null
    private const val MAX_LOG_LEN = 50000
    private var lastFlushedLength = 0

    @JvmField var ENABLED = true

    @JvmStatic
    fun init() {
        log.setLength(0)
        ensureFile(true)
        logLine("===== EInkReader Debug Session ${dateFormat.format(Date())} =====")
        logLine("Device: ${android.os.Build.MODEL}, SDK=${android.os.Build.VERSION.SDK_INT}")
        logLine("Density: ${android.content.res.Resources.getSystem().displayMetrics.densityDpi}dpi")
        flushToFile()
    }

    @JvmStatic
    fun log(tag: String, msg: String) {
        if (!ENABLED) return
        logLine(formatLine(tag, msg))
        flushToFile()
    }

    @JvmStatic
    fun error(tag: String, msg: String) {
        if (!ENABLED) return
        logLine(formatLine("ERROR", "[$tag] $msg"))
        flushToFile()
    }

    @JvmStatic
    fun error(tag: String, msg: String, t: Throwable?) {
        if (!ENABLED) return
        val sb = StringBuilder()
        sb.append(formatLine("ERROR", "[$tag] $msg"))
        t?.let {
            sb.append("\n  Exception: ${it.javaClass.simpleName}: ${it.message}")
            it.stackTrace.take(3).forEach { elem ->
                sb.append("\n    at ${elem.className}.${elem.methodName}(${elem.fileName}:${elem.lineNumber})")
            }
        }
        logLine(sb.toString())
        flushToFile()
    }

    @JvmStatic
    fun getLog(): String = log.toString()

    @JvmStatic
    fun getLogFilePath(): String {
        ensureFile(false)
        return logFile?.absolutePath ?: "(未初始化)"
    }

    @JvmStatic
    fun clear() {
        log.setLength(0)
        ensureFile(true)
    }

    // ============ internal ============

    private fun formatLine(tag: String, msg: String): String {
        return "${sdf.format(Date())} [$tag] $msg"
    }

    private fun logLine(line: String) {
        log.append(line).append('\n')
        if (log.length > MAX_LOG_LEN) {
            log.delete(0, log.length / 2)
            lastFlushedLength = 0
        }
        android.util.Log.d("EInkReader", line)
    }

    private fun ensureFile(overwrite: Boolean) {
        if (fileReady && logFile != null && logFile!!.exists() && !overwrite) return
        try {
            // ✅ [Phase 3] 改用应用私有缓存目录而非公共 Download 目录，防止信息泄露
            val dir = android.os.Environment.getExternalStorageDirectory()
                ?: return
            logFile = File(dir, "Android/data/com.einkreader/files/debug/${dateFormat.format(Date())}.log")
            
            // 确保父目录存在
            logFile!!.parentFile?.mkdirs()
            
            if (overwrite && logFile!!.exists()) logFile!!.delete()
            fileReady = true
        } catch (e: Exception) {
            android.util.Log.e("EInkReader", "ensureFile primary failed", e)
            try {
                // 如果公共目录不可写，回退到应用内部缓存目录
                // 需要 Context 才能使用 getCacheDir()，此处仅记录错误
                logFile = null
                fileReady = false
            } catch (e2: Exception) {
                android.util.Log.e("EInkReader", "ensureFile fallback failed", e2)
                fileReady = false
            }
        }
    }

    private fun flushToFile() {
        if (!ENABLED) return
        if (!fileReady) ensureFile(false)
        if (logFile == null) return
        try {
            val data = log.toString()
            val newData = data.substring(lastFlushedLength)
            if (newData.isEmpty()) return
            FileOutputStream(logFile!!, true).use { fos ->
                OutputStreamWriter(fos, "UTF-8").use { writer ->
                    writer.write(newData)
                }
            }
            lastFlushedLength = data.length
        } catch (e: Exception) {
            android.util.Log.e("EInkReader", "flushToFile failed", e)
        }
    }
}
