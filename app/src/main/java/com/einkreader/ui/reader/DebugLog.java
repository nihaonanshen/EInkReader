package com.einkreader.ui.reader;

import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 开发阶段调试日志工具（正式版需删除）
 *
 * 使用方式：
 *   DebugLog.log("TAG", "message");  // 普通信息
 *   DebugLog.error("TAG", "message"); // 错误信息（会带 ERROR 前缀）
 *
 * 日志文件路径：
 *   /sdcard/Download/EInkReader_debug.txt
 *
 * 清理：
 *   DebugLog.clear();
 *
 * 注意：正式版发布时需移除所有 DebugLog.log 调用。
 */
public class DebugLog {
    private static StringBuilder log = new StringBuilder();
    private static SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
    private static SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    private static boolean fileReady = false;
    private static File logFile = null;
    private static final int MAX_LOG_LEN = 50000;

    /** 开发阶段开关：true=开启日志，false=静默（正式版时改为 false） */
    public static boolean ENABLED = true;

    /** 初始化时调用一次，写入启动时间戳 */
    public static void init() {
        log.setLength(0);
        ensureFile(true);
        logLine("===== EInkReader Debug Session " + dateFormat.format(new Date()) + " =====");
        logLine("Device: " + android.os.Build.MODEL + ", SDK=" + android.os.Build.VERSION.SDK_INT);
        logLine("Density: " + android.content.res.Resources.getSystem().getDisplayMetrics().densityDpi + "dpi");
        flushToFile();
    }

    public static void log(String tag, String msg) {
        if (!ENABLED) return;
        logLine(formatLine(tag, msg));
        flushToFile();
    }

    public static void error(String tag, String msg) {
        if (!ENABLED) return;
        logLine(formatLine("ERROR", "[" + tag + "] " + msg));
        flushToFile();
    }

    public static void error(String tag, String msg, Throwable t) {
        if (!ENABLED) return;
        StringBuilder sb = new StringBuilder();
        sb.append(formatLine("ERROR", "[" + tag + "] " + msg));
        if (t != null) {
            sb.append("\n  Exception: ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
            StackTraceElement[] stack = t.getStackTrace();
            if (stack != null) {
                int lines = 0;
                for (StackTraceElement e : stack) {
                    if (lines++ > 3) break;
                    sb.append("\n    at ").append(e.getClassName()).append('.').append(e.getMethodName())
                            .append('(').append(e.getFileName()).append(':').append(e.getLineNumber()).append(')');
                }
            }
        }
        logLine(sb.toString());
        flushToFile();
    }

    public static String getLog() {
        return log.toString();
    }

    public static String getLogFilePath() {
        ensureFile(false);
        return logFile != null ? logFile.getAbsolutePath() : "(未初始化)";
    }

    public static void clear() {
        log.setLength(0);
        ensureFile(true);
    }

    // ============ 内部方法 ============

    private static String formatLine(String tag, String msg) {
        return sdf.format(new Date()) + " [" + tag + "] " + msg;
    }

    private static void logLine(String line) {
        log.append(line).append("\n");

        // 内存日志超长时截断前半部分
        if (log.length() > MAX_LOG_LEN) {
            log.delete(0, log.length() / 2);
        }

        // 同时输出到 Logcat（开发阶段）
        android.util.Log.d("EInkReader", line);
    }

    private static void ensureFile(boolean overwrite) {
        if (fileReady && logFile != null && logFile.exists() && !overwrite) return;
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            logFile = new File(dir, "EInkReader_debug.txt");
            if (overwrite && logFile.exists()) logFile.delete();
            fileReady = true;
        } catch (Exception e) {
            // 内部日志静默，不抛异常
            try {
                logFile = new File(android.os.Environment.getDataDirectory(), "data/com.einkreader/cache/debug.txt");
                File parent = logFile.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                fileReady = true;
            } catch (Exception e2) {
                fileReady = false;
            }
        }
    }

    private static void flushToFile() {
        if (!ENABLED) return;
        if (!fileReady) ensureFile(false);
        if (logFile == null) return;

        try {
            String data = log.toString();
            // 每次写入前清空文件（简化：最后一次 flush 全量写入）
            FileOutputStream fos = new FileOutputStream(logFile, false);
            OutputStreamWriter writer = new OutputStreamWriter(fos, "UTF-8");
            writer.write(data);
            writer.close();
            fos.close();
        } catch (Exception e) {
            // 静默失败
        }
    }
}
