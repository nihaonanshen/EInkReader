package com.einkreader.core;

import android.util.Log;

import com.einkreader.core.model.Chapter;
import com.einkreader.core.parser.EpubParser;
import com.einkreader.core.parser.TxtParser;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Rust 原生库 JNI 桥接层
 *
 * 提供对 Rust einkreader-core 库的 Java 接口。
 * 所有 native 方法的实现位于 libeinkreader_core.so 中。
 *
 * 设计原则：
 * 1. Rust 侧返回 JSON 字符串（解析是一次性操作，JSON 开销可忽略）
 * 2. 此层将 JSON 反序列化为 Java 对象，上层代码无感知
 * 3. 提供 FeatureFlags 开关，可随时切回纯 Java 实现
 */
public class NativeBridge {
    private static final String TAG = "NativeBridge";

    private static boolean sLibraryLoaded = false;

    static {
        try {
            System.loadLibrary("einkreader_core");
            sLibraryLoaded = true;
            Log.i(TAG, "Rust core library loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            sLibraryLoaded = false;
            Log.w(TAG, "Rust core library not available, using Java fallback", e);
        }
    }

    /** 检查 Rust 库是否已加载 */
    public static boolean isLibraryLoaded() {
        return sLibraryLoaded;
    }

    // ========== JNI 原生方法声明 ==========

    /**
     * 检测文本编码
     * @param data 文件字节数据
     * @param len 数据长度
     * @return 编码名称，如 "UTF-8", "GBK", "Big5"
     */
    public static native String nativeDetectEncoding(byte[] data, int len);

    /**
     * 解析 TXT 文件
     * @param filePath 文件绝对路径
     * @param forcedEncoding 强制编码（可为空字符串）
     * @return JSON 字符串，结构为 TxtParseResult
     */
    public static native String nativeParseTxt(String filePath, String forcedEncoding);

    /**
     * 解析 EPUB 文件
     * @param filePath 文件绝对路径
     * @return JSON 字符串，结构为 EpubParseResult
     */
    public static native String nativeParseEpub(String filePath);

    // ========== 编码检测（带 fallback） ==========

    /**
     * 检测编码，优先使用 Rust 实现
     */
    public static String detectEncoding(File file) {
        if (sLibraryLoaded) {
            try {
                // 读取文件前 64KB 用于检测
                int readSize = (int) Math.min(file.length(), 65536);
                byte[] header = new byte[readSize];
                java.io.FileInputStream fis = new java.io.FileInputStream(file);
                try {
                    int actualRead = fis.read(header, 0, readSize);
                    if (actualRead > 0) {
                        return nativeDetectEncoding(header, actualRead);
                    }
                } finally {
                    fis.close();
                }
            } catch (Exception e) {
                Log.w(TAG, "Rust encoding detection failed, falling back", e);
            }
        }
        // Fallback to Java implementation
        return com.einkreader.utils.EncodingDetector.detect(file);
    }

    // ========== TXT 解析（带 fallback） ==========

    /**
     * 解析 TXT 文件，优先使用 Rust 实现
     */
    public static TxtParser.ParseResult parseTxt(File file) throws Exception {
        return parseTxt(file, null);
    }

    /**
     * 解析 TXT 文件，可指定编码
     */
    public static TxtParser.ParseResult parseTxt(File file, String forcedEncoding) throws Exception {
        if (sLibraryLoaded) {
            try {
                String json = nativeParseTxt(
                    file.getAbsolutePath(),
                    forcedEncoding != null ? forcedEncoding : ""
                );
                return parseTxtJson(json, file);
            } catch (Exception e) {
                Log.w(TAG, "Rust TXT parser failed, falling back to Java", e);
            }
        }
        // Fallback to Java implementation
        return TxtParser.parse(file, forcedEncoding);
    }

    /**
     * 将 Rust 返回的 JSON 解析为 Java 的 ParseResult
     */
    private static TxtParser.ParseResult parseTxtJson(String json, File file) throws Exception {
        JSONObject root = new JSONObject(json);

        if (root.has("error")) {
            throw new Exception("Rust parser error: " + root.getString("error"));
        }

        TxtParser.ParseResult result = new TxtParser.ParseResult();
        result.bookTitle = root.optString("book_title", "");
        result.encoding = root.optString("encoding", "UTF-8");
        result.chapters = new ArrayList<>();

        JSONArray chapters = root.getJSONArray("chapters");
        for (int i = 0; i < chapters.length(); i++) {
            JSONObject ch = chapters.getJSONObject(i);
            String title = ch.optString("title", "第" + (i + 1) + "章");
            String content = ch.optString("content", "");
            int lineStart = ch.optInt("line_start", -1);
            int lineEnd = ch.optInt("line_end", -1);

            Chapter chapter = new Chapter(title, content, lineStart, lineEnd);
            chapter.setIndex(i);
            result.chapters.add(chapter);

            // 构建全文
            if (result.fullContent == null) {
                result.fullContent = content;
            } else {
                result.fullContent += content;
            }
        }

        return result;
    }

    // ========== EPUB 解析（带 fallback） ==========

    /**
     * 解析 EPUB 文件，优先使用 Rust 实现
     */
    public static EpubParser.EpubResult parseEpub(File file) throws Exception {
        if (sLibraryLoaded) {
            try {
                String json = nativeParseEpub(file.getAbsolutePath());
                return parseEpubJson(json, file);
            } catch (Exception e) {
                Log.w(TAG, "Rust EPUB parser failed, falling back to Java", e);
            }
        }
        // Fallback to Java implementation
        return EpubParser.parse(file);
    }

    /**
     * 将 Rust 返回的 EPUB JSON 解析为 Java 的 EpubResult
     */
    private static EpubParser.EpubResult parseEpubJson(String json, File file) throws Exception {
        JSONObject root = new JSONObject(json);

        if (root.has("error")) {
            throw new Exception("Rust EPUB parser error: " + root.getString("error"));
        }

        EpubParser.EpubResult result = new EpubParser.EpubResult();
        result.title = root.optString("title", "");
        result.author = root.optString("author", "");
        result.encoding = root.optString("encoding", "UTF-8");

        JSONArray chapters = root.getJSONArray("chapters");
        for (int i = 0; i < chapters.length(); i++) {
            JSONObject ch = chapters.getJSONObject(i);
            String title = ch.optString("title", "第" + (i + 1) + "章");
            String content = ch.optString("content", "");
            Chapter chapter = new Chapter(title, content);
            chapter.setIndex(i);
            result.chapters.add(chapter);
        }

        return result;
    }
}
