package com.einkreader.core;

import android.util.Log;

import com.einkreader.core.model.Chapter;
import com.einkreader.core.parser.EpubParser;
import com.einkreader.core.parser.TxtParser;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import android.util.Base64;

/**
 * Rust 原生库 JNI 桥接层
 *
 * 提供对 Rust einkreader-core 库的 Java 接口。
 * 所有 native 方法的实现位于 libeinkreader_core.so 中。
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

    public static boolean isLibraryLoaded() { return sLibraryLoaded; }

    // ========== 布局结果 POJO（支持 JSON + 二进制双路径） ==========

    /** 单行坐标（由 Rust 计算，Java 直接绘制） */
    public static class LineMetric {
        public String text;
        public float x, y, width, height;
        public boolean isParagraphEnd, isFirstInParagraph;

        public LineMetric() {}
        public LineMetric(String text, float x, float y, float width, float height,
                          boolean isParagraphEnd, boolean isFirstInParagraph) {
            this.text = text; this.x = x; this.y = y;
            this.width = width; this.height = height;
            this.isParagraphEnd = isParagraphEnd;
            this.isFirstInParagraph = isFirstInParagraph;
        }
    }

    /** 单页数据 */
    public static class PageData {
        public String content;
        public int lineCount;
        public List<LineMetric> lines = new ArrayList<>();
    }

    /** 布局结果 */
    public static class LayoutResult {
        public List<PageData> pages = new ArrayList<>();
        public int totalLines, totalPages;
        public long elapsedNs;
    }

    // ========== LRU 缓存 ==========

    private static class LayoutKey {
        final String text;
        final float maxWidthPx, maxHeightPx, fontSizePx, lineSpacing, paragraphSpacing;
        final boolean firstLineIndent;
        final float paddingLeft, paddingTop;
        final int hash;

        LayoutKey(String text, float w, float h, float fs, float ls, float ps,
                  boolean indent, float pl, float pt) {
            this.text = text;
            this.maxWidthPx = w; this.maxHeightPx = h; this.fontSizePx = fs;
            this.lineSpacing = ls; this.paragraphSpacing = ps;
            this.firstLineIndent = indent;
            this.paddingLeft = pl; this.paddingTop = pt;
            int hc = text.hashCode();
            hc = 31 * hc + Float.floatToIntBits(w);
            hc = 31 * hc + Float.floatToIntBits(h);
            hc = 31 * hc + Float.floatToIntBits(fs);
            hc = 31 * hc + Float.floatToIntBits(ls);
            hc = 31 * hc + Float.floatToIntBits(ps);
            hc = 31 * hc + (indent ? 1 : 0);
            hc = 31 * hc + Float.floatToIntBits(pl);
            hc = 31 * hc + Float.floatToIntBits(pt);
            this.hash = hc;
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof LayoutKey)) return false;
            LayoutKey k = (LayoutKey) o;
            return this.hash == k.hash
                && this.text.equals(k.text)
                && this.maxWidthPx == k.maxWidthPx && this.maxHeightPx == k.maxHeightPx
                && this.fontSizePx == k.fontSizePx && this.lineSpacing == k.lineSpacing
                && this.paragraphSpacing == k.paragraphSpacing
                && this.firstLineIndent == k.firstLineIndent
                && this.paddingLeft == k.paddingLeft && this.paddingTop == k.paddingTop;
        }
    }

    private static final int LRU_MAX_SIZE = 3;
    private static LinkedHashMap<LayoutKey, LayoutResult> sLayoutCache =
        new LinkedHashMap<LayoutKey, LayoutResult>(LRU_MAX_SIZE + 1, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<LayoutKey, LayoutResult> eldest) {
                return size() > LRU_MAX_SIZE;
            }
        };

    private static synchronized LayoutResult cacheGet(LayoutKey key) {
        return sLayoutCache.get(key);
    }
    private static synchronized void cachePut(LayoutKey key, LayoutResult result) {
        sLayoutCache.put(key, result);
    }

    // ========== JNI 原生方法声明 ==========

    public static native String nativeDetectEncoding(byte[] data, int len);
    public static native String nativeParseTxt(String filePath, String forcedEncoding);
    public static native String nativeParseEpub(String filePath);

    /** JSON 版（兼容） */
    public static native String nativeLayoutText(
        String text, float maxWidthPx, float maxHeightPx,
        float fontSizePx, float lineSpacing, float paragraphSpacing,
        boolean firstLineIndent
    );

    /** 二进制版（主要入口） */
    public static native byte[] nativeLayoutTextBinary(
        String text, float maxWidthPx, float maxHeightPx,
        float fontSizePx, float lineSpacing, float paragraphSpacing,
        boolean firstLineIndent, float paddingLeft, float paddingTop
    );

    // ========== 编码检测（带 fallback） ==========

    public static String detectEncoding(File file) {
        if (sLibraryLoaded) {
            java.io.FileInputStream fis = null;
            try {
                int readSize = (int) Math.min(file.length(), 65536);
                byte[] header = new byte[readSize];
                fis = new java.io.FileInputStream(file);
                int actualRead = fis.read(header, 0, readSize);
                if (actualRead > 0) return nativeDetectEncoding(header, actualRead);
            } catch (Exception e) {
                Log.w(TAG, "Rust encoding detection failed", e);
            } finally {
                if (fis != null) try { fis.close(); } catch (Exception ignored) { }
            }
        }
        return com.einkreader.utils.EncodingDetector.detect(file);
    }

    // ========== TXT/EPUB 解析（带 fallback，不变） ==========

    public static TxtParser.ParseResult parseTxt(File file) throws Exception { return parseTxt(file, null); }
    public static TxtParser.ParseResult parseTxt(File file, String forcedEncoding) throws Exception {
        if (sLibraryLoaded) {
            try {
                String json = nativeParseTxt(file.getAbsolutePath(), forcedEncoding != null ? forcedEncoding : "");
                return parseTxtJson(json, file);
            } catch (Exception e) {
                Log.w(TAG, "Rust TXT parser failed, falling back", e);
            }
        }
        return TxtParser.parse(file, forcedEncoding);
    }

    private static TxtParser.ParseResult parseTxtJson(String json, File file) throws Exception {
        JSONObject root = new JSONObject(json);
        if (root.has("error")) throw new Exception("Rust parser error: " + root.getString("error"));
        TxtParser.ParseResult result = new TxtParser.ParseResult();
        result.bookTitle = root.optString("book_title", "");
        result.encoding = root.optString("encoding", "UTF-8");
        result.chapters = new ArrayList<>();
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

    public static EpubParser.EpubResult parseEpub(File file) throws Exception {
        if (sLibraryLoaded) {
            try {
                String json = nativeParseEpub(file.getAbsolutePath());
                return parseEpubJson(json, file);
            } catch (Exception e) {
                Log.w(TAG, "Rust EPUB parser failed", e);
            }
        }
        return EpubParser.parse(file);
    }

    private static EpubParser.EpubResult parseEpubJson(String json, File file) throws Exception {
        JSONObject root = new JSONObject(json);
        if (root.has("error")) throw new Exception("Rust EPUB error: " + root.getString("error"));
        EpubParser.EpubResult result = new EpubParser.EpubResult();
        result.title = root.optString("title", "");
        result.author = root.optString("author", "");
        result.chapters = new ArrayList<>();
        JSONArray chapters = root.getJSONArray("chapters");
        for (int i = 0; i < chapters.length(); i++) {
            JSONObject ch = chapters.getJSONObject(i);
            String title = ch.optString("title", "第" + (i + 1) + "章");
            String content = ch.optString("content", "");
            Chapter chapter = new Chapter(title, content);
            chapter.setIndex(i);
            result.chapters.add(chapter);
        }
        result.images = new HashMap<>();
        if (root.has("images")) {
            JSONObject imgs = root.getJSONObject("images");
            java.util.Iterator<String> keys = imgs.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                result.images.put(k, Base64.decode(imgs.getString(k), Base64.DEFAULT));
            }
        }
        if ((result.title == null || result.title.isEmpty()) && file != null) {
            String name = file.getName();
            int dot = name.lastIndexOf('.');
            result.title = dot > 0 ? name.substring(0, dot) : name;
        }
        return result;
    }

    // ========== bincode 二进制解析（精确匹配 Rust bincode::serialize 格式） ==========

    /**
     * 从 bincode 二进制数据解析 LayoutResult
     *
     * Rust bincode v1 格式说明（Little Endian）：
     * - Vec<T>：u64 length（元素数），后接 N 个元素
     * - String：u64 byte_length（字节数），后接 UTF-8 字节序列
     * - f32：4 字节 IEEE 754 LE
     * - u64：8 字节 LE
     * - bool：1 字节（0/1）
     * - usize：u64（8 字节 LE）
     */
    public static LayoutResult parseLayoutBinary(byte[] data) {
        LayoutResult result = new LayoutResult();
        if (data == null || data.length == 0) return result;

        try {
            ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

            // pages: Vec<PageData> — u64 length
            int pageCount = (int) bb.getLong();
            result.pages = new ArrayList<>(pageCount);

            for (int pi = 0; pi < pageCount; pi++) {
                PageData pd = new PageData();

                // content: String — u64 bytes, then UTF-8
                pd.content = readBincodeString(bb);

                // line_count: usize — u64
                pd.lineCount = (int) bb.getLong();

                // lines: Vec<LineMetric> — u64 length
                int lineCount = (int) bb.getLong();
                pd.lines = new ArrayList<>(lineCount);
                for (int li = 0; li < lineCount; li++) {
                    LineMetric lm = new LineMetric();
                    lm.text = readBincodeString(bb);
                    lm.x = bb.getFloat();
                    lm.y = bb.getFloat();
                    lm.width = bb.getFloat();
                    lm.height = bb.getFloat();
                    lm.isParagraphEnd = bb.get() != 0;
                    lm.isFirstInParagraph = bb.get() != 0;
                    pd.lines.add(lm);
                }
                result.pages.add(pd);
            }

            // total_lines: usize — u64
            result.totalLines = (int) bb.getLong();
            // total_pages: usize — u64
            result.totalPages = (int) bb.getLong();
            // elapsed_ns: u64
            result.elapsedNs = bb.getLong();

        } catch (Exception e) {
            Log.e(TAG, "Failed to parse bincode layout", e);
        }
        return result;
    }

    /** 读取 bincode v1 格式的 String：u64 字节长度 + UTF-8 字节 */
    private static String readBincodeString(ByteBuffer bb) {
        int byteLen = (int) bb.getLong();
        if (byteLen == 0) return "";
        byte[] strBytes = new byte[byteLen];
        bb.get(strBytes);
        return new String(strBytes, StandardCharsets.UTF_8);
    }

    // ========== Rust 文本布局（双路径 + LRU 缓存） ==========

    /** JSON 版布局（兼容旧调用） */
    public static LayoutResult layoutText(
        String text, int maxWidthPx, int maxHeightPx,
        float fontSizePx, float lineSpacing, float paragraphSpacing,
        boolean firstLineIndent
    ) {
        LayoutKey key = new LayoutKey(text, maxWidthPx, maxHeightPx, fontSizePx,
                lineSpacing, paragraphSpacing, firstLineIndent, 0, 0);
        LayoutResult cached = cacheGet(key);
        if (cached != null) return cached;

        if (!sLibraryLoaded) return new LayoutResult();
        try {
            String json = nativeLayoutText(text, maxWidthPx, maxHeightPx,
                    fontSizePx, lineSpacing, paragraphSpacing, firstLineIndent);
            cached = parseLayoutJson(json);
            if (cached.totalPages > 0) cachePut(key, cached);
            return cached;
        } catch (Exception e) {
            Log.w(TAG, "Rust layout(JSON) failed", e);
            return new LayoutResult();
        }
    }

    /** 二进制版布局（主要入口，含 LRU 缓存） */
    public static LayoutResult layoutTextBinary(
        String text, float maxWidthPx, float maxHeightPx,
        float fontSizePx, float lineSpacing, float paragraphSpacing,
        boolean firstLineIndent, float paddingLeft, float paddingTop
    ) {
        LayoutKey key = new LayoutKey(text, maxWidthPx, maxHeightPx, fontSizePx,
                lineSpacing, paragraphSpacing, firstLineIndent, paddingLeft, paddingTop);
        LayoutResult cached = cacheGet(key);
        if (cached != null) return cached;

        if (!sLibraryLoaded) return new LayoutResult();
        try {
            byte[] binary = nativeLayoutTextBinary(text, maxWidthPx, maxHeightPx,
                    fontSizePx, lineSpacing, paragraphSpacing, firstLineIndent,
                    paddingLeft, paddingTop);
            cached = parseLayoutBinary(binary);
            if (cached.totalPages > 0) cachePut(key, cached);
            return cached;
        } catch (Exception e) {
            Log.w(TAG, "Rust layout(binary) failed", e);
            return new LayoutResult();
        }
    }

    /** JSON 解析（保留兼容） */
    public static LayoutResult parseLayoutJson(String json) {
        LayoutResult result = new LayoutResult();
        try {
            JSONObject root = new JSONObject(json);
            if (root.has("error")) return result;
            JSONArray pagesArr = root.getJSONArray("pages");
            result.pages = new ArrayList<>(pagesArr.length());
            for (int i = 0; i < pagesArr.length(); i++) {
                JSONObject p = pagesArr.getJSONObject(i);
                PageData pd = new PageData();
                pd.content = p.optString("content", "");
                pd.lineCount = p.optInt("line_count", 0);
                // JSON 路径：lines 字段（LineMetric 坐标）可用但非必需
                if (p.has("lines")) {
                    JSONArray linesArr = p.getJSONArray("lines");
                    pd.lines = new ArrayList<>(linesArr.length());
                    for (int j = 0; j < linesArr.length(); j++) {
                        JSONObject l = linesArr.getJSONObject(j);
                        LineMetric lm = new LineMetric();
                        lm.text = l.optString("text", "");
                        lm.x = (float) l.optDouble("x", 0);
                        lm.y = (float) l.optDouble("y", 0);
                        lm.width = (float) l.optDouble("width", 0);
                        lm.height = (float) l.optDouble("height", 0);
                        lm.isParagraphEnd = l.optBoolean("is_paragraph_end", false);
                        lm.isFirstInParagraph = l.optBoolean("is_first_in_paragraph", false);
                        pd.lines.add(lm);
                    }
                }
                result.pages.add(pd);
            }
            result.totalLines = root.optInt("total_lines", 0);
            result.totalPages = root.optInt("total_pages", 0);
            result.elapsedNs = root.optLong("elapsed_ns", 0);
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse layout JSON", e);
        }
        return result;
    }

    // ========== 缓存状态查询 ==========

    /**
     * 查询指定参数的布局结果是否已在 LRU 缓存中
     * 用于 EinkRefreshManager 刷新策略决策（缓存命中则无需全刷）
     */
    public static boolean isLayoutCached(String text, float maxWidthPx, float maxHeightPx,
                                          float fontSizePx, float lineSpacing, float paragraphSpacing,
                                          boolean firstLineIndent, float paddingLeft, float paddingTop) {
        LayoutKey key = new LayoutKey(text, maxWidthPx, maxHeightPx, fontSizePx,
                lineSpacing, paragraphSpacing, firstLineIndent, paddingLeft, paddingTop);
        return sLayoutCache.get(key) != null;
    }

    // ========== Benchmarks ==========

    /** 基准测试结果 */
    public static class BenchmarkResult {
        public long javaNs, jsonNs, binaryNs;
        public int pages;
        public String tag;

        public String summary() {
            return String.format("[%s] pages=%d  Java=%.2fms  JSON=%.2fms  Binary=%.2fms  speedup=%.1fx",
                    tag, pages, javaNs / 1e6, jsonNs / 1e6, binaryNs / 1e6,
                    javaNs > 0 && binaryNs > 0 ? (double) javaNs / binaryNs : 0);
        }
    }

    /**
     * 运行三种布局路径的性能对比基准
     * @return BenchmarkResult
     */
    public static BenchmarkResult benchmarkLayout(String text, int width, int height,
           float fontSize, float lineSpacing, float paragraphSpacing,
           boolean indent, float paddingLeft, float paddingTop) {

        BenchmarkResult br = new BenchmarkResult();
        br.tag = "LayoutBench";

        // Java 路径（需要 ReaderView 的 Java 布局方法引用）
        long start = System.nanoTime();
        // 此处用简单的模拟：假设 Java 路径比二进制慢 4x
        // 实际调用会在 ReaderView 中由调度层执行
        br.javaNs = 0; // 由调用者补充

        // JSON 路径
        start = System.nanoTime();
        LayoutResult r1 = layoutText(text, width, height, fontSize, lineSpacing,
                paragraphSpacing, indent);
        br.jsonNs = System.nanoTime() - start;
        br.pages = r1.totalPages;

        // Binary 路径（清缓存确保公平）
        sLayoutCache.clear();
        start = System.nanoTime();
        LayoutResult r2 = layoutTextBinary(text, width, height, fontSize, lineSpacing,
                paragraphSpacing, indent, paddingLeft, paddingTop);
        br.binaryNs = System.nanoTime() - start;
        if (br.pages == 0) br.pages = r2.totalPages;

        Log.i(TAG, br.summary());
        return br;
    }
}