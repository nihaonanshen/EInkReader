package com.einkreader.ui.reader;

import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DebugLog {
    private static StringBuilder log = new StringBuilder();
    private static SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private static boolean fileReady = false;
    private static File logFile = null;

    private static void ensureFile() {
        if (fileReady) return;
        try {
            // ★ 日志保存到下载目录（所有设备可访问，不需要root）
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            logFile = new File(dir, "EInkReader_debug.txt");
            if (logFile.exists()) logFile.delete();  // 每次启动清空旧日志
            fileReady = true;
        } catch (Exception e) { }
    }

    public static void log(String tag, String msg) {
        String line = sdf.format(new Date()) + " [" + tag + "] " + msg;
        log.append(line).append("\n");
        if (log.length() > 20000) {
            log.delete(0, log.length() / 2);
        }
        android.util.Log.d(tag, msg);

        ensureFile();
        if (logFile != null) {
            try {
                FileOutputStream fos = new FileOutputStream(logFile, true);
                OutputStreamWriter writer = new OutputStreamWriter(fos, "UTF-8");
                writer.write(line + "\n");
                writer.close();
                fos.close();
            } catch (Exception e) { }
        }
    }

    public static String getLog() {
        return log.toString();
    }

    public static String getLogFilePath() {
        ensureFile();
        return logFile != null ? logFile.getAbsolutePath() : "(未初始化)";
    }

    public static void clear() {
        log.setLength(0);
    }
}
