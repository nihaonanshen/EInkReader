package com.einkreader.ui.reader

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLog {
    private val log = StringBuilder()
    private var sAppContext: android.content.Context? = null
    private val sdf by lazy { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    private val dateFormat by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    private var fileReady = false
    private var logFile: File? = null
    private const val MAX_LOG_LEN = 50000
    private var lastFlushedLength = 0

    @JvmField var ENABLED = true

    @JvmStatic
    fun init(context: android.content.Context) {
        sAppContext = context.applicationContext
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
    fun getLog(): String = synchronized(log) { log.toString() }

    @JvmStatic
    fun getLogFilePath(): String {
        ensureFile(false)
        return logFile?.absolutePath ?: "(未初始化)"
    }

    @JvmStatic
    fun clear() {
        synchronized(log) {
            log.setLength(0)
            lastFlushedLength = 0
        }
        ensureFile(true)
    }

    // ============ internal ============

    private fun formatLine(tag: String, msg: String): String {
        return "${sdf.format(Date())} [$tag] $msg"
    }

    private fun logLine(line: String) {
        synchronized(log) {
            log.append(line).append('\n')
            if (log.length > MAX_LOG_LEN) {
                log.delete(0, log.length / 2)
                lastFlushedLength = 0
            }
        }
        android.util.Log.d("EInkReader", line)
    }

    private fun ensureFile(overwrite: Boolean) {
        if (fileReady && logFile != null && checkNotNull(logFile).exists() && !overwrite) return
        val context = sAppContext
        if (context == null) { fileReady = false; return }
        try {
            // ✅ 应用专属外部目录（/sdcard/Android/data/<pkg>/files），无需存储权限、Android 11+ 也可访问
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            logFile = File(dir, "debug/${dateFormat.format(Date())}.log")

            // 确保父目录存在
            checkNotNull(logFile).parentFile?.mkdirs()

            if (overwrite && checkNotNull(logFile).exists()) checkNotNull(logFile).delete()
            fileReady = true
        } catch (e: Exception) {
            android.util.Log.e("EInkReader", "ensureFile primary failed", e)
            fileReady = false
        }
    }

    private fun flushToFile() {
        if (!ENABLED) return
        if (!fileReady) ensureFile(false)
        if (logFile == null) return
        try {
            val data = synchronized(log) { log.toString() }
            val newData = if (lastFlushedLength < data.length) data.substring(lastFlushedLength) else ""
            if (newData.isEmpty()) return
            FileOutputStream(checkNotNull(logFile), true).use { fos ->
                OutputStreamWriter(fos, "UTF-8").use { writer ->
                    writer.write(newData)
                }
            }
            synchronized(log) { lastFlushedLength = data.length }
        } catch (e: Exception) {
            android.util.Log.e("EInkReader", "flushToFile failed", e)
        }
    }
}
