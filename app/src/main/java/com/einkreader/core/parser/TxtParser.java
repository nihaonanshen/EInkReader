package com.einkreader.core.parser;

import android.util.Log;

import com.einkreader.core.model.Chapter;
import com.einkreader.utils.EncodingDetector;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TXT 文件解析器
 *
 * 功能：
 * 1. 自动检测编码（GBK/UTF-8/Big5）
 * 2. 按中文章节标题自动分章（如"第一章"、"第一回"等）
 * 3. 解析结果缓存到文件，第二次打开秒开
 */
public class TxtParser {
    private static final String TAG = "TxtParser";
    private static final int DEFAULT_CHAPTER_SIZE = 3000; // 无标题时按3000字一章
    private static final String CACHE_DIR_NAME = "txt_parse_cache";

    // ===== 章节标题正则（综合版，覆盖主流中文小说格式）=====
    // 完整版：第X章/回/节 + 可选标题
    private static final Pattern CHAPTER_PATTERN = Pattern.compile(
        "^[\\s\\u3000]*[【\\-―※（(\\[{]*" +
        "第[零一二三四五六七八九十百千万亿\\d]{1,8}[章节回卷集篇部折]" +
        "[）)〕\\]}]?[\\s\\u3000]*" +
        "(?:[】\\-―※]*[\\s\\u3000]*(\\S.*))?" +
        "[\\s\\u3000]*$"
    );

    // 宽松匹配：只要开头是"第X章"就算
    private static final Pattern LOOSE_CHAPTER_PATTERN = Pattern.compile(
        "^\\s*第[零一二三四五六七八九十百千万亿\\d]{1,8}[章节回卷集篇部折]"
    );

    // ★ 新增：匹配英文 Chapter 1 / Chapter One / Ch.1 格式
    private static final Pattern ENG_CHAPTER_PATTERN = Pattern.compile(
        "^(?i)(chapter|chap|ch|section|sec|part|lesson|unit|volume|vol|module|lecture)" +
        "[.\\-:\\s]*[\\d一二三四五六七八九十百千]+(?:[.\\-:\\s]+.*)?$"
    );

    // ★ 新增：匹配 "VOL.1" "Volume 1" 格式
    private static final Pattern VOLUME_PATTERN = Pattern.compile(
        "^(?i)(volume|vol)\\s*\\.?\\s*[\\d]+(?:[.:\\s]+.*)?$"
    );

    // ★ 新增：匹配 "楔子" "序章" "引子" "后记" "尾声" "番外" 等
    private static final Pattern SPECIAL_CHAPTER_PATTERN = Pattern.compile(
        "^[\\s\\u3000]*(?:楔子|序章|序言|引子|前言|前奏|序幕|开篇|开场|写在前面|题记)" +
        "[\\s\\u3000]*$|" +
        "^[\\s\\u3000]*(?:后记|尾声|终章|结局|尾声|结语|番外|外传|特别篇|附录|附注)" +
        "[\\s\\u3000]*$"
    );

    // 编码回退列表
    private static final String[] FALLBACK_ENCODINGS = {"GBK", "GB18030", "UTF-8", "Big5", "GB2312"};

    // ★ 收紧：匹配纯数字章节（要求有明确分隔符，减少正文误匹配）
    private static final Pattern NUM_CHAPTER_PATTERN = Pattern.compile(
        "^[\\s\\u3000]*" +
        "(?:[零一二三四五六七八九十百千万亿]+|[\\d]+)" +
        "[、．.\\s　]" +  // 必须有一个明确的分隔符
        "(?:[\\u4e00-\\u9fff]{1,30})?" +
        "[\\s\\u3000]*$"
    );

    // ★ 装饰性标题（支持不对称装饰）
    private static final Pattern DECORATED_CHAPTER_PATTERN = Pattern.compile(
        "^[\\s\\u3000]*" +
        "[\\u2500-\\u257F◆◇◎▲△▽▼○●□■☆★※＊*#_\\-\\s　]{0,15}" +
        "第[零一二三四五六七八九十百千万亿\\d]{1,8}[章节回卷集篇部折]" +
        ".*$"
    );

    // ★ 收紧：第X章必须在行首附近（前10个字符内）
    private static final Pattern ANYWHERE_CHAPTER_PATTERN = Pattern.compile(
        "^[\\s\\u3000\\u2500-\\u257F◆◇◎▲△▽▼○●□■☆★※＊*#_\\-]{0,10}" +
        "第[零一二三四五六七八九十百千万亿\\d]{1,8}[章节回卷集篇部折]"
    );

    // 解析结果
    public static class ParseResult {
        public String bookTitle;
        public String encoding;
        public String fullContent;      // 全书全文（搜索用）
        public List<Chapter> chapters;

        public ParseResult() {
            chapters = new ArrayList<Chapter>();
        }
    }

    // 缓存目录
    private static File sCacheBaseDir = null;

    public static void initCacheDir(File appCacheDir) {
        sCacheBaseDir = new File(appCacheDir, CACHE_DIR_NAME);
        if (!sCacheBaseDir.exists() && !sCacheBaseDir.mkdirs()) {
            Log.w(TAG, "缓存目录创建失败: " + sCacheBaseDir.getAbsolutePath());
        }
    }

    // ===== 公开 API =====

    // 文件级解析锁，防止同一文件并发重复解析
    private static final ConcurrentHashMap<String, Object> sParseLocks = new ConcurrentHashMap<String, Object>();

    /** 解析 TXT 文件 */
    public static ParseResult parse(File file) throws IOException {
        return parse(file, null);
    }

    public static ParseResult parse(File file, String forcedEncoding) throws IOException {
        String lockKey = file.getAbsolutePath();
        Object lock = sParseLocks.get(lockKey);
        if (lock == null) {
            Object newLock = new Object();
            Object existing = sParseLocks.putIfAbsent(lockKey, newLock);
            lock = (existing != null) ? existing : newLock;
        }
        synchronized (lock) {
            // 先尝试读缓存
            ParseResult cached = readCache(file);
            if (cached != null) return cached;

            // 没有缓存，真解析
            ParseResult result = doParse(file, forcedEncoding);
            writeCache(file, result);
            return result;
        }
    }

    // ===== 解析逻辑 =====

    private static ParseResult doParse(File file, String forcedEncoding) throws IOException {
        ParseResult result = new ParseResult();
        result.bookTitle = extractTitle(file);
        com.einkreader.ui.reader.DebugLog.log("Txt", "解析: " + file.getName() + " 大小=" + file.length());

        // ★ 一次读取全文件到 byte[]，后续所有编码尝试都用同一份字节数组
        byte[] fileBytes;
        InputStream fis = null;
        try {
            fis = new FileInputStream(file);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(8192, (int) Math.min(file.length(), Integer.MAX_VALUE)));
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) baos.write(buf, 0, n);
            fileBytes = baos.toByteArray();
        } finally {
            if (fis != null) try { fis.close(); } catch (IOException e) { }
        }

        // 检测编码
        String encoding = (forcedEncoding != null && !forcedEncoding.isEmpty())
                ? forcedEncoding : EncodingDetector.detect(fileBytes, fileBytes.length);
        result.encoding = encoding;

        // 尝试解码 + 章节检测，编码不对时自动回退
        String fullText = tryDecodeAndDetect(fileBytes, fileBytes.length, encoding, result);
        if (fullText == null) {
            // 首次编码失败，尝试回退
            for (String fallback : FALLBACK_ENCODINGS) {
                if (fallback.equals(encoding)) continue;
                fullText = tryDecodeAndDetect(fileBytes, fileBytes.length, fallback, result);
                if (fullText != null) {
                    result.encoding = fallback;
                    break;
                }
            }
        }
        if (fullText == null) {
            // 所有编码都失败，用 UTF-8 兜底
            fullText = new String(fileBytes, 0, fileBytes.length, Charset.forName("UTF-8"));
            result.encoding = "UTF-8";
        }

        // 构建行偏移数组
        int[] lineOffsets = buildLineOffsets(fullText);
        int lineCount = lineOffsets.length - 1; // 最后一个是终结位置

        // 检测章节标题（基于行偏移，无需逐行 String）
        List<int[]> chapterBreaks = new ArrayList<int[]>();
        List<String> chapterTitles = new ArrayList<String>();
        for (int li = 0; li < lineCount; li++) {
            String lineText = extractLine(fullText, lineOffsets, li);
            if (isChapterTitle(lineText)) {
                if (chapterBreaks.isEmpty() && li > 0) {
                    chapterBreaks.add(new int[]{0, li});
                    chapterTitles.add(null);
                } else if (!chapterBreaks.isEmpty()) {
                    int[] prev = chapterBreaks.get(chapterBreaks.size() - 1);
                    prev[1] = li;
                }
                chapterBreaks.add(new int[]{li + 1, -1});
                chapterTitles.add(cleanTitle(extractChapterTitle(lineText)));
            }
        }
        if (!chapterBreaks.isEmpty()) {
            int[] last = chapterBreaks.get(chapterBreaks.size() - 1);
            last[1] = lineCount;
        }

        // ★ 后处理：合并多行标题（"第一章\n初入江湖" → "第一章 初入江湖"）
        for (int i = 0; i < chapterTitles.size(); i++) {
            String title = chapterTitles.get(i);
            if (title == null) continue;
            // 检查标题是否只有章节编号（如"第一章"、"第二回"），没有描述性文字
            if (title.length() < 12 && LOOSE_CHAPTER_PATTERN.matcher(title).matches()) {
                int[] breaks = chapterBreaks.get(i);
                int firstContentLine = breaks[0];
                if (firstContentLine >= 0 && firstContentLine < lineCount) {
                    String nextLine = extractLine(fullText, lineOffsets, firstContentLine);
                    String trimmedNext = nextLine.trim();
                    if (!trimmedNext.isEmpty() && trimmedNext.length() < 80
                            && !isChapterTitle(trimmedNext)
                            && !trimmedNext.startsWith("[[IMAGE:")) {
                        // 下一行是标题名，合并
                        chapterTitles.set(i, title + " " + trimmedNext);
                        breaks[0] = firstContentLine + 1;  // 标题行从内容中移除
                    }
                }
            }
        }

        // 构建章节列表（从全文 subString，无需额外拷贝行数据）
        if (!chapterBreaks.isEmpty()) {
            for (int i = 0; i < chapterBreaks.size(); i++) {
                int startLine = chapterBreaks.get(i)[0];
                int endLine = chapterBreaks.get(i)[1];
                if (endLine < 0) endLine = lineCount;
                if (endLine > lineCount) endLine = lineCount;
                String content = extractLines(fullText, lineOffsets, startLine, endLine);
                String title = chapterTitles.get(i);
                if (title == null || title.isEmpty()) {
                    title = (i == 0) ? "引子" : ("第" + (i + 1) + "章");
                }
                result.chapters.add(new Chapter(title, content, startLine, endLine));
            }
        }

        // 没找到章节标题时，按固定字数分割
        if (result.chapters.isEmpty()) {
            com.einkreader.ui.reader.DebugLog.log("Txt", "未检测到章节标题！回退到按字数分割。");
            // 将全文按行提取到列表供 splitBySize 使用
            List<String> allLines = new ArrayList<String>(lineCount);
            for (int li = 0; li < lineCount; li++) {
                allLines.add(extractLine(fullText, lineOffsets, li));
            }
            result.chapters = splitBySize(allLines, DEFAULT_CHAPTER_SIZE);
        } else {
            com.einkreader.ui.reader.DebugLog.log("Txt", "检测到章节: " + result.chapters.size() + "个");
        }

        // 全文 = 解码后的字符串（ReaderActivity 未使用，为搜索功能保留）
        result.fullContent = fullText;

        Log.i(TAG, "解析完成: " + file.getName() + " → " + result.chapters.size() + "章 编码=" + result.encoding);
        return result;
    }

    /**
     * 尝试用指定编码解码
     * @return 解码成功返回字符串，失败返回 null
     */
    private static String tryDecodeAndDetect(byte[] data, int len, String encoding, ParseResult resultHint) {
        try {
            String text = new String(data, 0, len, Charset.forName(encoding));
            if (text.isEmpty()) return null;

            // ★ 统计中文比例：只看前 1 万字，不足 1% 判定为编码错误
            int chineseCount = 0;
            int replacementCount = 0;
            int totalChars = Math.min(text.length(), 10000);
            for (int i = 0; i < totalChars; i++) {
                char c = text.charAt(i);
                if (c >= 0x4E00 && c <= 0x9FFF) chineseCount++;
                else if (c == '\uFFFD') replacementCount++; // 非法字符
            }
            // 如果有大量替换字符（>10%）或中文比例极低（<0.5%）且非空文件，尝试下一个编码
            if (totalChars > 200 && replacementCount * 100 / totalChars > 10) {
                com.einkreader.ui.reader.DebugLog.log("Txt", "编码 " + encoding + " 替换字符过多 (" + replacementCount + "/" + totalChars + ")，尝试下一个");
                return null;
            }
            if (totalChars > 500 && chineseCount == 0 && replacementCount == 0) {
                // 无中文也无替换字符——可能是英文或其他，接受当前编码
            }
            // 检测通过
            resultHint.encoding = encoding;
            return text;
        } catch (Exception e) {
            return null;
        }
    }

    /** 构建行偏移数组 */
    private static int[] buildLineOffsets(String text) {
        // 先数行数
        int lineCount = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') lineCount++;
        }
        int[] offsets = new int[lineCount + 1]; // 多一个位置存结尾
        int idx = 0;
        offsets[idx++] = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                offsets[idx++] = i + 1;
            }
        }
        offsets[idx] = text.length();
        return offsets;
    }

    /** 提取指定行的文本（不含换行符） */
    private static String extractLine(String text, int[] offsets, int lineIdx) {
        int start = offsets[lineIdx];
        int end = offsets[lineIdx + 1];
        // 去掉末尾的 \n 或 \r\n
        if (end > start && text.charAt(end - 1) == '\n') end--;
        if (end > start && text.charAt(end - 1) == '\r') end--;
        return text.substring(start, end);
    }

    /** 提取从 startLine 到 endLine（不含）的文本内容，保留换行符 */
    private static String extractLines(String text, int[] offsets, int startLine, int endLine) {
        int start = offsets[startLine];
        int end = (endLine < offsets.length) ? offsets[endLine] : text.length();
        return text.substring(start, end);
    }

    // ===== 工具方法 =====

    private static boolean isChapterTitle(String line) {
        if (line == null || line.isEmpty()) return false;
        String trimmed = line.trim();
        // 太长的行不可能是标题（标题一般不超过 50 字）
        if (trimmed.length() > 80) return false;
        return CHAPTER_PATTERN.matcher(line).find()
            || LOOSE_CHAPTER_PATTERN.matcher(line).find()
            || ENG_CHAPTER_PATTERN.matcher(line).find()
            || SPECIAL_CHAPTER_PATTERN.matcher(line).find()
            || VOLUME_PATTERN.matcher(line).find()
            || NUM_CHAPTER_PATTERN.matcher(line).find()
            || DECORATED_CHAPTER_PATTERN.matcher(line).find()
            || (trimmed.length() < 50 && ANYWHERE_CHAPTER_PATTERN.matcher(line).find());
    }

    private static String extractChapterTitle(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return trimmed;
        if (trimmed.length() > 80) return ""; // 太长的行不可能是标题
        if (CHAPTER_PATTERN.matcher(trimmed).find()) return trimmed;
        if (LOOSE_CHAPTER_PATTERN.matcher(trimmed).find()) return trimmed;
        if (ENG_CHAPTER_PATTERN.matcher(trimmed).find()) return trimmed;
        if (SPECIAL_CHAPTER_PATTERN.matcher(trimmed).find()) return trimmed;
        if (VOLUME_PATTERN.matcher(trimmed).find()) return trimmed;
        if (NUM_CHAPTER_PATTERN.matcher(trimmed).find()) return trimmed;
        if (DECORATED_CHAPTER_PATTERN.matcher(trimmed).find()) return trimmed;
        if (trimmed.length() < 50 && ANYWHERE_CHAPTER_PATTERN.matcher(trimmed).find()) return trimmed;
        return "";
    }

    /** 清理标题中的装饰字符 */
    private static String cleanTitle(String title) {
        if (title == null || title.isEmpty()) return title;
        String cleaned = title.trim();
        cleaned = cleaned.replaceAll("^[【\\[\\-―※（(]+", "");
        cleaned = cleaned.replaceAll("[】\\]\\-―※）)]+$", "");
        cleaned = cleaned.replaceAll("[（(][完上中下续终]?[）)]$", "");
        cleaned = cleaned.replaceAll("[\\s\\u3000]+", " ").trim();
        return cleaned;
    }

    /** 按字数分割章节（后备方案） */
    private static List<Chapter> splitBySize(List<String> lines, int charsPerChapter) {
        List<Chapter> chapters = new ArrayList<Chapter>();
        StringBuilder current = new StringBuilder();
        int chapterNum = 1;
        int lineStart = 0;
        for (int i = 0; i < lines.size(); i++) {
            current.append(lines.get(i)).append("\n");
            if (current.length() >= charsPerChapter) {
                chapters.add(new Chapter("第" + chapterNum + "段", current.toString(), lineStart, i + 1));
                current = new StringBuilder();
                lineStart = i + 1;
                chapterNum++;
            }
        }
        if (current.length() > 0) {
            chapters.add(new Chapter("第" + chapterNum + "段", current.toString(), lineStart, lines.size()));
        }
        return chapters;
    }

    public static String extractTitle(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // ===== 缓存机制 =====

    private static String getCacheKey(File file) {
        return file.getAbsolutePath() + "|" + file.length() + "|" + file.lastModified();
    }

    private static File getCacheDir(File txtFile) {
        if (sCacheBaseDir != null && sCacheBaseDir.exists()) return sCacheBaseDir;
        File cacheDir = new File(txtFile.getParentFile(), CACHE_DIR_NAME);
        if (!cacheDir.exists()) cacheDir.mkdirs();
        return cacheDir;
    }

    private static File getCacheFile(File txtFile) {
        String path = txtFile.getAbsolutePath();
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(path.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b & 0xFF));
            String hash = sb.toString();
            return new File(getCacheDir(txtFile), hash + "_" + txtFile.length() + "_" + txtFile.lastModified() + ".cache");
        } catch (Exception e) {
            // fallback
            String fallback = Math.abs(path.hashCode()) + "_" + txtFile.length() + "_" + txtFile.lastModified() + ".cache";
            return new File(getCacheDir(txtFile), fallback);
        }
    }

    private static ParseResult readCache(File file) {
        File cacheFile = getCacheFile(file);
        if (!cacheFile.exists()) return null;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(cacheFile), "UTF-8"));
            String cachedKey = reader.readLine();
            if (!getCacheKey(file).equals(cachedKey)) return null;
            String title = reader.readLine();
            String encoding = reader.readLine();
            int chapterCount = Integer.parseInt(reader.readLine());
            ParseResult result = new ParseResult();
            result.bookTitle = title;
            result.encoding = encoding;
            StringBuilder fullBuilder = new StringBuilder();
            for (int i = 0; i < chapterCount; i++) {
                String chTitle = reader.readLine();
                int lineStart = Integer.parseInt(reader.readLine());
                int lineEnd = Integer.parseInt(reader.readLine());
                int contentLen = Integer.parseInt(reader.readLine());
                char[] buf = new char[contentLen];
                int read = 0;
                while (read < contentLen) {
                    int n = reader.read(buf, read, contentLen - read);
                    if (n < 0) break;
                    read += n;
                }
                String content = new String(buf, 0, read);
                result.chapters.add(new Chapter(chTitle, content, lineStart, lineEnd));
                fullBuilder.append(content);
            }
            result.fullContent = fullBuilder.toString();
            return result;
        } catch (Exception e) {
            Log.w(TAG, "缓存读取失败", e);
            return null;
        } finally {
            if (reader != null) try { reader.close(); } catch (IOException e) { }
        }
    }

    private static void writeCache(File file, ParseResult result) {
        if (result == null || result.chapters == null || result.chapters.isEmpty()) return;
        File cacheFile = getCacheFile(file);
        File tmpFile = new File(cacheFile.getAbsolutePath() + ".tmp");
        OutputStreamWriter writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(tmpFile), "UTF-8");
            writer.write(getCacheKey(file) + "\n");
            writer.write((result.bookTitle != null ? result.bookTitle : "") + "\n");
            writer.write((result.encoding != null ? result.encoding : "") + "\n");
            writer.write(result.chapters.size() + "\n");
            for (Chapter ch : result.chapters) {
                writer.write((ch.getTitle() != null ? ch.getTitle() : "") + "\n");
                writer.write(ch.getLineStart() + "\n");
                writer.write(ch.getLineEnd() + "\n");
                String content = ch.getContent() != null ? ch.getContent() : "";
                writer.write(content.length() + "\n");
                writer.write(content);
            }
            writer.flush();
            writer.close();
            writer = null;
            if (cacheFile.exists()) cacheFile.delete();
            tmpFile.renameTo(cacheFile);
        } catch (Exception e) {
            Log.w(TAG, "缓存写入失败", e);
            if (tmpFile.exists()) tmpFile.delete();
        } finally {
            if (writer != null) try { writer.close(); } catch (IOException e) { }
        }
    }
}
