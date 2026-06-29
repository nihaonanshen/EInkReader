package com.einkreader.core.parser;

import android.util.Log;

import com.einkreader.core.model.Chapter;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * EPUB 文件解析器（全功能版）
 *
 * EPUB 本质是一个 ZIP 包，里面包含：
 *   - META-INF/container.xml  → 找到 OPF 文件路径
 *   - *.opf                  → 书籍元数据 + 文件清单(manifest) + 阅读顺序(spine)
 *   - *.ncx                  → 目录结构（章节标题映射）
 *   - *.xhtml / *.html       → 正文内容
 *   - images/xxx.jpg         → 图片
 *
 * ★ 新增图片支持：提取 <img> 标签，从 ZIP 读取图片数据
 */
public class EpubParser {
    private static final String TAG = "EpubParser";
    private static final String CACHE_DIR_NAME = "epub_parse_cache";

    // 块级 HTML 标签 —— 遇到这些就换行
    private static final java.util.HashSet<String> BLOCK_TAGS = new java.util.HashSet<String>();
    static {
        String[] tags = {
                "p", "div", "h1", "h2", "h3", "h4", "h5", "h6",
                "blockquote", "pre", "li", "section", "article",
                "table", "tr", "hr", "address", "dd", "dt",
                "header", "footer", "nav", "aside", "ol", "ul"
        };
        for (String t : tags) BLOCK_TAGS.add(t);
    }

    // 段落类型标记：嵌入文本中，正则清理不受影响，用于最终构建 paraTypes
    private static final char PARA_MARKER_PREFIX = '\uE000';
    private static final char PARA_MARKER_SUFFIX = '\uE001';

    private static final int MAX_COMMENT_SPAN = 50000;  // 注释最大跨度，防止未闭合导致内容丢失

    // 预编译的正则表达式（避免每次解析重新编译）
    private static final Pattern REGEX_NL_3PLUS = Pattern.compile("\\n{3,}");
    private static final Pattern REGEX_SPACE_2PLUS = Pattern.compile("[ \\t]{2,}");
    private static final Pattern REGEX_NL_TRAIL_SPACE = Pattern.compile("\\n[ \\t]+");
    private static final Pattern REGEX_TRAIL_SPACE_NL = Pattern.compile("[ \\t]+\\n");
    private static final Pattern REGEX_LEADING_CHAP = Pattern.compile("^(chapter|chap|ch|section|sec|part|lesson|unit|volume|vol|module)\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern REGEX_TRAILING_SEP = Pattern.compile("[._\\-\\s]+$");
    private static final Pattern REGEX_LEADING_SEP = Pattern.compile("^[_\\-\\s]+");
    private static final Pattern REGEX_TRAILING_SEP2 = Pattern.compile("[_\\-\\s]+$");
    private static final Pattern REGEX_LEADING_ZERO = Pattern.compile("^0+");
    private static final Pattern REGEX_FAKE_CHAPTER = Pattern.compile("(?i)chapter[_\\-\\s]*\\d+");

    // 需要跳过内容的标签
    private static final java.util.HashSet<String> SKIP_TAGS = new java.util.HashSet<String>();
    static {
        SKIP_TAGS.add("style");
        SKIP_TAGS.add("script");
        SKIP_TAGS.add("head");
    }

    // 解析结果
    public static class EpubResult {
        public String title;
        public String author;
        public String encoding = "UTF-8";
        public List<Chapter> chapters;
        public List<String> spineOrder;
        String ncxHref;

        public EpubResult() {
            chapters = new ArrayList<Chapter>();
            spineOrder = new ArrayList<String>();
        }
    }

    // 缓存目录
    private static File sCacheBaseDir = null;

    public static void initCacheDir(File appCacheDir) {
        // ★ 始终使用应用私有缓存目录，避免数据泄露到外部存储
        sCacheBaseDir = new File(appCacheDir, CACHE_DIR_NAME);
        if (!sCacheBaseDir.exists() && !sCacheBaseDir.mkdirs()) {
            Log.w(TAG, "缓存目录创建失败: " + sCacheBaseDir.getAbsolutePath());
        }
    }

    // ===== 公开 API =====

    // 文件级解析锁，防止同一文件并发重复解析
    private static final ConcurrentHashMap<String, Object> sParseLocks = new ConcurrentHashMap<String, Object>();

    public static EpubResult parse(File file) throws IOException {
        String lockKey = file.getAbsolutePath();
        Object lock = sParseLocks.get(lockKey);
        if (lock == null) {
            Object newLock = new Object();
            Object existing = sParseLocks.putIfAbsent(lockKey, newLock);
            lock = (existing != null) ? existing : newLock;
        }
        synchronized (lock) {
            EpubResult cached = readCache(file);
            if (cached != null) return cached;
            EpubResult result = doParse(file);
            writeCache(file, result);
            return result;
        }
    }

    // ===== 解析入口 =====

    private static EpubResult doParse(File file) throws IOException {
        if (file == null) throw new IllegalArgumentException("file must not be null");
        EpubResult result = new EpubResult();
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(file);

            // 1. 读取 container.xml → 找到 OPF 路径
            String opfPath = parseContainer(zipFile);
            if (opfPath == null) {
                Log.e(TAG, "找不到 container.xml");
                return result;
            }

            // 2. 解析 OPF → 获取元数据、文件清单、spine 顺序
            String opfDir = opfPath.substring(0, opfPath.lastIndexOf('/') + 1);
            parseOpf(zipFile, opfPath, result);

            // 3. 解析 NCX → 获取章节标题映射
            Map<String, String> ncxTitles = parseNcx(zipFile, opfDir, result);

            // 4. 按 spine 顺序读取内容并提取图片
            parseSpineContent(zipFile, opfDir, result, ncxTitles);

            // 5. 书名后备
            if (result.title == null || result.title.isEmpty()) {
                result.title = file.getName();
                if (result.title.endsWith(".epub") || result.title.endsWith(".EPUB")) {
                    result.title = result.title.substring(0, result.title.length() - 5);
                }
            }

            Log.i(TAG, "解析完成: " + result.title + ", " + result.chapters.size() + "章");
            com.einkreader.ui.reader.DebugLog.log("Epub", "解析完成: " + result.title + " " + result.chapters.size() + "章 作者=" + result.author);
        } finally {
            if (zipFile != null) try { zipFile.close(); } catch (IOException e) { }
        }
        return result;
    }

    // ==================== Container / OPF / NCX 解析 ====================

    private static String parseContainer(ZipFile zipFile) throws IOException {
        ZipEntry entry = zipFile.getEntry("META-INF/container.xml");
        if (entry == null) return null;
        InputStream is = null;
        try {
            is = zipFile.getInputStream(entry);
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(is, "UTF-8");
            while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                if (parser.getEventType() == XmlPullParser.START_TAG
                        && "rootfile".equalsIgnoreCase(parser.getName())) {
                    String fullPath = parser.getAttributeValue(null, "full-path");
                    if (fullPath == null) {
                        // 有些 EPUB 用 namespace 前缀的版本
                        fullPath = parser.getAttributeValue("http://www.idpf.org/2007/opf", "full-path");
                    }
                    if (fullPath == null) {
                        // 也可能属性名本身带 namespace 前缀
                        for (int ai = 0; ai < parser.getAttributeCount(); ai++) {
                            String attrName = parser.getAttributeName(ai);
                            if (attrName != null && attrName.endsWith("full-path")) {
                                fullPath = parser.getAttributeValue(ai);
                                break;
                            }
                        }
                    }
                    if (fullPath != null) return fullPath.trim();
                }
                parser.next();
            }
        } catch (Exception e) {
            Log.e(TAG, "解析 container.xml 失败", e);
        } finally {
            if (is != null) try { is.close(); } catch (IOException e) { }
        }
        return null;
    }

    private static void parseOpf(ZipFile zipFile, String opfPath, EpubResult result) throws IOException {
        ZipEntry entry = zipFile.getEntry(opfPath);
        if (entry == null) return;

        Map<String, String> manifest = new HashMap<String, String>();
        List<String> spineIds = new ArrayList<String>();

        InputStream is = null;
        try {
            is = zipFile.getInputStream(entry);
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(is, "UTF-8");

            boolean inMetadata = false, inManifest = false, inSpine = false;
            while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                switch (parser.getEventType()) {
                    case XmlPullParser.START_TAG:
                        String tag = parser.getName();
                        if ("metadata".equalsIgnoreCase(tag)) inMetadata = true;
                        else if ("manifest".equalsIgnoreCase(tag)) inManifest = true;
                        else if ("spine".equalsIgnoreCase(tag)) inSpine = true;
                        else if (inMetadata && "title".equalsIgnoreCase(tag))
                            result.title = readText(parser);
                        else if (inMetadata && "creator".equalsIgnoreCase(tag))
                            result.author = readText(parser);
                        else if (inManifest && "item".equalsIgnoreCase(tag)) {
                            String id = parser.getAttributeValue(null, "id");
                            String href = parser.getAttributeValue(null, "href");
                            String mediaType = parser.getAttributeValue(null, "media-type");
                            if (id != null && href != null) {
                                manifest.put(id, href);
                                // 记录 NCX 路径
                                if (mediaType != null && (mediaType.contains("dtbncx") || mediaType.contains("ncx"))) {
                                    if (result.ncxHref == null) result.ncxHref = href;
                                }
                            }
                        } else if (inSpine && "itemref".equalsIgnoreCase(tag)) {
                            String idref = parser.getAttributeValue(null, "idref");
                            if (idref != null) spineIds.add(idref);
                        }
                        break;
                    case XmlPullParser.END_TAG:
                        String endTag = parser.getName();
                        if ("metadata".equalsIgnoreCase(endTag)) inMetadata = false;
                        else if ("manifest".equalsIgnoreCase(endTag)) inManifest = false;
                        else if ("spine".equalsIgnoreCase(endTag)) inSpine = false;
                        break;
                }
                parser.next();
            }

            // 将 spine id 列表转换为实际文件路径
            for (String id : spineIds) {
                String href = manifest.get(id);
                if (href != null) result.spineOrder.add(href);
            }
        } catch (Exception e) {
            Log.e(TAG, "解析 OPF 失败", e);
        } finally {
            if (is != null) try { is.close(); } catch (IOException e) { }
        }
    }

    private static Map<String, String> parseNcx(ZipFile zipFile, String opfDir, EpubResult result) {
        Map<String, String> ncxTitles = new HashMap<String, String>();

        // 找 NCX 文件：先看 manifest 声明，没有则全 ZIP 扫描
        String ncxHref = result.ncxHref;
        if (ncxHref == null || ncxHref.isEmpty()) {
            // 遍历 ZIP 中所有 .ncx 文件
            java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry ze = entries.nextElement();
                String name = ze.getName();
                if (name.toLowerCase().endsWith(".ncx")) {
                    ncxHref = name;
                    break;
                }
            }
        }
        if (ncxHref == null) return ncxTitles;

        String ncxPath = ncxHref.startsWith(opfDir) ? ncxHref : opfDir + ncxHref;
        ZipEntry ncxEntry = zipFile.getEntry(ncxPath);
        if (ncxEntry == null) ncxEntry = zipFile.getEntry(ncxHref);
        if (ncxEntry == null) return ncxTitles;

        InputStream is = null;
        try {
            is = zipFile.getInputStream(ncxEntry);
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(is, "UTF-8");

            String currentSrc = null, currentLabel = null;
            int navPointDepth = 0;  // ★ 只处理顶层 navpoint，跳过嵌套的页码条目
            while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                if (parser.getEventType() == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("navpoint".equalsIgnoreCase(tag)) {
                        navPointDepth++;
                    } else if ("content".equalsIgnoreCase(tag)) {
                        currentSrc = parser.getAttributeValue(null, "src");
                    } else if ("navlabel".equalsIgnoreCase(tag)) {
                        currentLabel = null;
                    } else if ("text".equalsIgnoreCase(tag) && currentSrc != null) {
                        currentLabel = readNcxText(parser);
                    }
                } else if (parser.getEventType() == XmlPullParser.END_TAG) {
                    String endTag = parser.getName();
                    if ("navpoint".equalsIgnoreCase(endTag)) {
                        navPointDepth--;
                        // ★ 只记录顶层 navpoint（depth == 0 表示刚退出顶层）
                        if (navPointDepth == 0 && currentSrc != null && currentLabel != null) {
                            String href = currentSrc;
                            int hashIdx = href.indexOf('#');
                            if (hashIdx > 0) href = href.substring(0, hashIdx);
                            ncxTitles.put(href, currentLabel.trim());
                        }
                        currentSrc = null;
                        currentLabel = null;
                    }
                }
                parser.next();
            }
        } catch (Exception e) {
            Log.e(TAG, "解析 NCX 失败", e);
        } finally {
            if (is != null) try { is.close(); } catch (IOException e) { }
        }
        return ncxTitles;
    }

    private static String readNcxText(XmlPullParser parser) throws Exception {
        while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
            int event = parser.next();
            if (event == XmlPullParser.TEXT) return parser.getText();
            else if (event == XmlPullParser.END_TAG && "text".equalsIgnoreCase(parser.getName())) break;
        }
        return null;
    }

    // ==================== 内容解析（含图片提取） ====================

    private static void parseSpineContent(ZipFile zipFile, String opfDir,
                                          EpubResult result, Map<String, String> ncxTitles) throws IOException {
        // ★ 使用独立计数器保证章节 index 连续
        int chapterIndex = 0;
        for (int i = 0; i < result.spineOrder.size(); i++) {
            String href = result.spineOrder.get(i);
            String entryPath = opfDir + href;

            // ★ spine 路径多种尝试
            ZipEntry entry = null;
            // 1. 原样
            entry = zipFile.getEntry(entryPath);
            // 2. URL 解码
            if (entry == null) {
                try { entry = zipFile.getEntry(java.net.URLDecoder.decode(entryPath, "UTF-8")); } catch (Exception e) { }
            }
            // 3. 去掉 ./
            if (entry == null && entryPath.startsWith("./")) {
                entry = zipFile.getEntry(entryPath.substring(2));
            }
            // 4. 路径规范化 + 安全检查（防止 ZIP Slip）
            if (entry == null) {
                try {
                    // ★ M-7: ZIP 内部路径始终使用 '/'；手动规范化移除 .. 和 .
                    String[] parts = (opfDir + "/" + href).split("/");
                    java.util.ArrayList<String> stack = new java.util.ArrayList<String>();
                    for (String p : parts) {
                        if (p.isEmpty() || p.equals(".")) continue;
                        if (p.equals("..")) {
                            if (!stack.isEmpty()) stack.remove(stack.size() - 1);
                            continue;
                        }
                        stack.add(p);
                    }
                    StringBuilder norm = new StringBuilder();
                    for (int si = 0; si < stack.size(); si++) {
                        if (si > 0) norm.append('/');
                        norm.append(stack.get(si));
                    }
                    String normalizedPath = norm.toString();
                    // 安全检查：不能跳出 opfDir
                    if (normalizedPath.startsWith(opfDir)) {
                        entry = zipFile.getEntry(normalizedPath);
                    }
                } catch (Exception e) { }
            }
            // 5. 不区分大小写（遍历 ZIP 找匹配的文件名）
            if (entry == null) {
                String targetName = href;
                int ls = targetName.lastIndexOf('/');
                if (ls >= 0) targetName = targetName.substring(ls + 1);
                String targetLower = targetName.toLowerCase();
                java.util.Enumeration<? extends ZipEntry> allEntries = zipFile.entries();
                while (allEntries.hasMoreElements()) {
                    ZipEntry ze = allEntries.nextElement();
                    String zeName = ze.getName();
                    int zs = zeName.lastIndexOf('/');
                    String zeFile = (zs >= 0) ? zeName.substring(zs + 1) : zeName;
                    if (zeFile.equalsIgnoreCase(targetName) || zeFile.equalsIgnoreCase(targetLower)) {
                        entry = ze;
                        break;
                    }
                }
            }

            // 读取 XHTML 内容并提取图片
            ContentWithImages contentWithImages = readXhtmlWithImages(zipFile, entry, opfDir);
            String content = contentWithImages.text;
            // ★ 限制每章内容最大 500KB（防止大文件 OOM）
            if (content != null && content.length() > 500000) {
                content = content.substring(0, 500000) + "\n\n……(篇幅受限)……";
            }
            // ★ 不再跳过空内容章节（保持章节索引与 NCX 目录一致）
            if (content == null) content = "";

            // 确定标题
            String title = null;
            if (ncxTitles != null) {
                // ★ 改进匹配：尝试多种路径格式
                // 1. 原样匹配
                title = ncxTitles.get(href);
                // 2. URL 解码后匹配
                if (title == null) {
                    try { title = ncxTitles.get(java.net.URLDecoder.decode(href, "UTF-8")); } catch (Exception e) { }
                }
                // 3. 去掉 ../ 前缀
                if (title == null && href.startsWith("../"))
                    title = ncxTitles.get(href.substring(3));
                if (title == null && href.startsWith("./"))
                    title = ncxTitles.get(href.substring(2));
                // 4. URL 解码再去掉前缀
                if (title == null && href.startsWith("../")) {
                    try { title = ncxTitles.get(java.net.URLDecoder.decode(href.substring(3), "UTF-8")); } catch (Exception e) { }
                }
                // 5. 只匹配文件名
                if (title == null) {
                    String nameOnly = href;
                    int ls = nameOnly.lastIndexOf('/');
                    if (ls >= 0) nameOnly = nameOnly.substring(ls + 1);
                    title = ncxTitles.get(nameOnly);
                    // URL 解码文件名再试
                    if (title == null) {
                        try { title = ncxTitles.get(java.net.URLDecoder.decode(nameOnly, "UTF-8")); } catch (Exception e) { }
                    }
                }
                // 6. 不区分大小写匹配文件名
                if (title == null) {
                    String nameLower = href.toLowerCase();
                    int ls = nameLower.lastIndexOf('/');
                    if (ls >= 0) nameLower = nameLower.substring(ls + 1);
                    for (String key : ncxTitles.keySet()) {
                        String keyName = key;
                        int ks = keyName.lastIndexOf('/');
                        if (ks >= 0) keyName = keyName.substring(ks + 1);
                        if (keyName.equalsIgnoreCase(nameLower)) {
                            title = ncxTitles.get(key);
                            break;
                        }
                    }
                }
                if (title != null) title = title.trim();
                // 5. 如果标题是 "Chapter 1" 之类的英文，也保留
            }
            if (title == null || title.isEmpty()) {
                title = extractTitleFromHref(href, i);
            }

            // ★ 从原始 HTML 的 <h1>/<h2> 提取标题，替换后备文件名或不完整的 NCX 标题
            if (contentWithImages.rawTitle != null && !contentWithImages.rawTitle.isEmpty()
                    && !contentWithImages.rawTitle.equals(title)) {
                // 仅在当前标题是"后备"时才覆盖：英文 chapter+N 或中文 第N章（无描述）
                boolean isFallback = (title == null || title.isEmpty()
                    || title.matches("(?i)^(chapter|chap|ch|section|sec|part)\\s*\\d+$")
                    || title.matches("第[零一二三四五六七八九十百千万亿\\d]+[章节回卷集篇部]"));
                if (isFallback) {
                    title = contentWithImages.rawTitle;
                }
            }
            // ★ 跳过无内容的 chapter_X 假章节
            if (title != null && REGEX_FAKE_CHAPTER.matcher(title).matches()) {
                continue;
            }
            // ★ 跳过内容重复的章节（同名不同 index 的情况）
            if (!result.chapters.isEmpty()) {
                Chapter lastCh = result.chapters.get(result.chapters.size() - 1);
                if (content.equals(lastCh.getContent())) {
                    continue;
                }
            }
            // 最终保底：标题不应为空
            if (title == null || title.isEmpty()) {
                title = extractTitleFromHref(href, i);
            }
            Chapter chapter = new Chapter(title, content);
            chapter.setIndex(chapterIndex++);
            // ★ 设置段落类型
            if (contentWithImages.paraTypes != null && !contentWithImages.paraTypes.isEmpty()) {
                chapter.setParagraphTypes(contentWithImages.paraTypes);
            }
            result.chapters.add(chapter);
        }
    }

    /**
     * 读取 XHTML 内容 + 提取图片
     * 返回：清理后的文本 + 图片路径列表 + 图片数据
     */
    private static ContentWithImages readXhtmlWithImages(ZipFile zipFile, ZipEntry entry, String opfDir) throws IOException {
        ContentWithImages result = new ContentWithImages();

        InputStream is = null;
        BufferedReader reader = null;
        try {
            is = zipFile.getInputStream(entry);

            // 检测编码：用 BufferedInputStream 的 mark/reset 避免关闭后重新打开
            String encoding = "UTF-8";
            if (is.markSupported()) {
                is.mark(4096);
            } else {
                is = new BufferedInputStream(is, 4096);
                is.mark(4096);
            }
            byte[] firstBytes = new byte[2048];
            int readCount = is.read(firstBytes);
            if (readCount > 0) {
                String header = new String(firstBytes, 0, Math.min(readCount, 500), "ISO-8859-1");
                String lower = header.toLowerCase();
                // ★ I-6: 扩展编码检测覆盖范围
                if (lower.contains("charset=gbk") || lower.contains("charset=gb2312") || lower.contains("charset=gb18030"))
                    encoding = "GBK";
                else if (lower.contains("charset=big5"))
                    encoding = "Big5";
                else if (lower.contains("charset=utf-8") || lower.contains("charset=unicode"))
                    encoding = "UTF-8";
                else if (lower.contains("charset=shift_jis") || lower.contains("charset=sjis"))
                    encoding = "Shift_JIS";
                else if (lower.contains("charset=euc-jp"))
                    encoding = "EUC-JP";
            }
            is.reset();
            reader = new BufferedReader(new InputStreamReader(is, encoding));

            // 读取全部 HTML
            StringBuilder rawHtml = new StringBuilder(4096);
            String line;
            while ((line = reader.readLine()) != null) {
                rawHtml.append(line).append("\n");
            }
            reader.close(); reader = null;
            is.close(); is = null;

            String html = rawHtml.toString();

            // ★ 从原始 HTML 提取标题（正则搜索 <h1>/<h2>/<title>）
            result.rawTitle = extractTitleFromHtml(html);

            // ★ 提取图片 + 清理 HTML
            result.text = cleanHtmlAndExtractImages(html, opfDir, result);

        } catch (Exception e) {
            Log.e(TAG, "读取 XHTML 失败", e);
            result.text = "";
        } finally {
            if (reader != null) try { reader.close(); } catch (IOException e) { }
            if (is != null) try { is.close(); } catch (IOException e) { }
        }

        return result;
    }

    /**
     * 存储：清理后的文本
     */
    static class ContentWithImages {
        String text = "";
        /** 从原始 HTML 中提取的标题（从 <h1>/<h2>/<title> 标签获取） */
        String rawTitle;
        /** 每个段落的类型（对应 text 按 \\n 分割后的段落） */
        List<Integer> paraTypes = new ArrayList<Integer>();
    }

    /**
     * ★ 单遍扫描 HTML：去掉标签、解码实体，在文本中插入换行
     */
    private static String cleanHtmlAndExtractImages(String html, String opfDir, ContentWithImages result) {
        if (html == null || html.isEmpty()) return "";

        StringBuilder out = new StringBuilder(html.length());
        int len = html.length();

        int state = 0;           // 0=文本, 1=在标签内, 2=在跳过标签内, 3=在注释内, 4=在CDATA内
        String skipTagName = null;
        StringBuilder tagNameBuf = new StringBuilder();
        boolean tagNameDone = false;
        boolean tagIsClosing = false;

        // 当前标签的完整字符串（用于提取 img 的属性）
        StringBuilder currentTagContent = new StringBuilder();

        // ★ 段落类型跟踪
        int currentParaType = com.einkreader.core.model.Chapter.PARA_NORMAL;
        int commentStartPos = -1;

        // ★ 初始段落标记（给第一个 block 前的文本）
        out.append(PARA_MARKER_PREFIX).append((char)('0' + currentParaType)).append(PARA_MARKER_SUFFIX);

        for (int i = 0; i < len; i++) {
            char c = html.charAt(i);

            // 状态3：HTML 注释 <!-- ... -->，完全跳过
            if (state == 3) {
                if (c == '-' && i + 2 < len && html.charAt(i + 1) == '-' && html.charAt(i + 2) == '>') {
                    i += 2;
                    state = 0;
                    commentStartPos = -1;
                } else if (commentStartPos >= 0 && i - commentStartPos > MAX_COMMENT_SPAN) {
                    // ★ 注释超过最大跨度，视为未闭合，强制恢复
                    Log.w(TAG, "HTML 注释超过 " + MAX_COMMENT_SPAN + " 字符仍未闭合，强制恢复");
                    state = 0;
                    commentStartPos = -1;
                }
                continue;
            }

            // 状态4：CDATA 节 <![CDATA[ ... ]]>，完全跳过
            if (state == 4) {
                if (c == ']' && i + 2 < len && html.charAt(i + 1) == ']' && html.charAt(i + 2) == '>') {
                    i += 2;
                    state = 0;
                }
                continue;
            }

            // 状态2：在 style/script/head 中，跳过
            if (state == 2) {
                if (c == '<' && i + 1 < len && html.charAt(i + 1) == '/' && skipTagName != null) {
                    int closeStart = i + 2;
                    boolean match = true;
                    for (int k = 0; k < skipTagName.length() && closeStart + k < len; k++) {
                        if (Character.toLowerCase(html.charAt(closeStart + k)) != skipTagName.charAt(k)) {
                            match = false; break;
                        }
                    }
                    if (match) {
                        int gt = html.indexOf('>', closeStart + skipTagName.length());
                        if (gt != -1) {
                            i = gt;
                            state = 0;
                            skipTagName = null;
                        }
                    }
                }
                continue;
            }

            // 状态1：在标签内
            if (state == 1) {
                currentTagContent.append(c);
                if (c == '>') {
                    String tagName = tagNameBuf.toString().toLowerCase();
                    String tagContent = currentTagContent.toString();

                    // 处理 <br> 和块级标签 → 换行
                    // ★ 用内联标记记录段落类型：只在块级闭合时记录类型标记，
                    //   保证标记数 = 实际段数（trim 后可能出现前导空段，最后做对齐）
                    if (tagName.equals("br")) {
                        out.append('\n');
                    } else if (!tagIsClosing && BLOCK_TAGS.contains(tagName)) {
                        // 块级标签开启：换行 + 设置新段落类型
                        out.append('\n');
                        currentParaType = getParagraphType(tagName);
                    } else if (tagIsClosing && BLOCK_TAGS.contains(tagName)) {
                        // 块级标签闭合：记录当前段落的类型标记，重置类型
                        out.append(PARA_MARKER_PREFIX).append((char)('0' + currentParaType)).append(PARA_MARKER_SUFFIX);
                        currentParaType = com.einkreader.core.model.Chapter.PARA_NORMAL;
                    }

                    // 跳过标签检测
                    if (!tagIsClosing && SKIP_TAGS.contains(tagName)) {
                        state = 2;
                        skipTagName = tagName;
                    } else {
                        state = 0;
                    }
                    tagNameBuf.setLength(0);
                    tagNameDone = false;
                    tagIsClosing = false;
                    currentTagContent = new StringBuilder();
                } else {
                    if (!tagNameDone) {
                        if (c == '/' || c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                            tagNameDone = true;
                        } else {
                            tagNameBuf.append(c);
                        }
                    }
                    if (c == '/' && tagNameBuf.length() == 0 && !tagIsClosing && i > 0 && html.charAt(i-1) == '<') {
                        tagIsClosing = true;
                    }
                }
                continue;
            }

            // 状态0：正常文本
            if (c == '<') {
                // 检查 HTML 注释 <!--
                if (i + 3 < len && html.charAt(i + 1) == '!' && html.charAt(i + 2) == '-' && html.charAt(i + 3) == '-') {
                    state = 3;
                    commentStartPos = i;
                    i += 3;
                    continue;
                }
                // 检查 CDATA <![CDATA[
                if (i + 8 < len && html.charAt(i + 1) == '!' && html.charAt(i + 2) == '['
                        && html.charAt(i + 3) == 'C' && html.charAt(i + 4) == 'D'
                        && html.charAt(i + 5) == 'A' && html.charAt(i + 6) == 'T'
                        && html.charAt(i + 7) == 'A' && html.charAt(i + 8) == '[') {
                    state = 4;
                    i += 8;
                    continue;
                }
                state = 1;
                tagNameBuf.setLength(0);
                tagNameDone = false;
                tagIsClosing = false;
                currentTagContent = new StringBuilder();
                currentTagContent.append(c);
                continue;
            }

            // HTML 实体解码
            if (c == '&') {
                int semi = html.indexOf(';', i);
                if (semi != -1 && semi - i <= 12) {
                    String entity = html.substring(i + 1, semi);
                    String decoded = decodeEntity(entity);
                    if (decoded != null) {
                        out.append(decoded);
                        i = semi;
                        continue;
                    }
                }
                out.append(c);
                continue;
            }

            // 普通字符
            if (c != '\r') {
                out.append(c);
            }
        }

        // ★ 从文本中提取段落类型标记，构建 paraTypes，然后剥离标记
        result.paraTypes.clear();
        StringBuilder cleaned = new StringBuilder(out.length());
        int len2 = out.length();
        for (int i = 0; i < len2; i++) {
            char c = out.charAt(i);
            if (c == PARA_MARKER_PREFIX && i + 2 < len2 && out.charAt(i + 2) == PARA_MARKER_SUFFIX) {
                int type = out.charAt(i + 1) - '0';
                result.paraTypes.add(type);
                i += 2;
                continue;
            }
            cleaned.append(c);
        }
        String resultText = cleaned.toString();

        // 最终清理多余空行（使用预编译 Pattern）
        resultText = REGEX_NL_3PLUS.matcher(resultText).replaceAll("\n\n");
        resultText = REGEX_SPACE_2PLUS.matcher(resultText).replaceAll(" ");
        resultText = REGEX_NL_TRAIL_SPACE.matcher(resultText).replaceAll("\n");
        resultText = REGEX_TRAIL_SPACE_NL.matcher(resultText).replaceAll("\n");
        resultText = resultText.trim();

        // ★ 对齐 paraTypes 与最终段落数：trim 删除了前导空段，对应标记从列表头部移除
        String[] paras = resultText.split("\n", -1);
        while (result.paraTypes.size() > paras.length) {
            result.paraTypes.remove(0);  // 优先移除前导标记（trim 导致的空段）
        }
        while (result.paraTypes.size() < paras.length) {
            result.paraTypes.add(com.einkreader.core.model.Chapter.PARA_NORMAL);
        }
        return resultText;
    }

    /** 从原始 HTML 中正则提取标题（搜索 <h1>/<h2>/<h3>，不搜 <title> 因为它通常是文件名） */
    private static String extractTitleFromHtml(String html) {
        if (html == null || html.isEmpty()) return null;
        String[] tags = {"h1", "h2", "h3"};
        for (String tag : tags) {
            String regex = "(?i)<" + tag + "(?:\\s[^>]*)?>(.*?)</" + tag + "\\s*>";
            java.util.regex.Pattern pattern =
                    java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher matcher = pattern.matcher(html);
            if (matcher.find()) {
                String candidate = matcher.group(1)
                        .replaceAll("<[^>]*>", "")
                        .trim();
                if (!candidate.isEmpty() && candidate.length() < 200) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** 根据标签名返回段落类型 */
    private static int getParagraphType(String tagName) {
        String t = tagName.toLowerCase();
        if (t.equals("h1")) return com.einkreader.core.model.Chapter.PARA_H1;
        if (t.equals("h2")) return com.einkreader.core.model.Chapter.PARA_H2;
        if (t.equals("h3") || t.equals("h4") || t.equals("h5") || t.equals("h6"))
            return com.einkreader.core.model.Chapter.PARA_H3;
        if (t.equals("blockquote") || t.equals("quote"))
            return com.einkreader.core.model.Chapter.PARA_BLOCKQUOTE;
        return com.einkreader.core.model.Chapter.PARA_NORMAL;
    }

    /** 解码 HTML 实体 */
    private static String decodeEntity(String entity) {
        if (entity == null || entity.isEmpty()) return null;
        if (entity.startsWith("#")) {
            try {
                int codepoint;
                if (entity.charAt(1) == 'x' || entity.charAt(1) == 'X')
                    codepoint = Integer.parseInt(entity.substring(2), 16);
                else
                    codepoint = Integer.parseInt(entity.substring(1));
                if (codepoint > 0 && codepoint <= Character.MAX_CODE_POINT)
                    return String.valueOf(Character.toChars(codepoint));
            } catch (NumberFormatException e) { return null; }
        }
        if ("amp".equals(entity)) return "&";
        if ("lt".equals(entity)) return "<";
        if ("gt".equals(entity)) return ">";
        if ("quot".equals(entity)) return "\"";
        if ("apos".equals(entity)) return "'";
        if ("nbsp".equals(entity)) return " ";
        if ("mdash".equals(entity)) return "\u2014";
        if ("ndash".equals(entity)) return "\u2013";
        if ("hellip".equals(entity)) return "\u2026";
        if ("ldquo".equals(entity)) return "\u201C";
        if ("rdquo".equals(entity)) return "\u201D";
        if ("lsquo".equals(entity)) return "\u2018";
        if ("rsquo".equals(entity)) return "\u2019";
        if ("laquo".equals(entity)) return "\u00AB";
        if ("raquo".equals(entity)) return "\u00BB";
        if ("copy".equals(entity)) return "\u00A9";
        if ("reg".equals(entity)) return "\u00AE";
        if ("trade".equals(entity)) return "\u2122";
        if ("emsp".equals(entity)) return "\u2003";
        if ("ensp".equals(entity)) return "\u2002";
        if ("thinsp".equals(entity)) return "\u2009";
        return null;
    }

    /** 从文件名提取章节标题 */
    private static String extractTitleFromHref(String href, int index) {
        String name = href;
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) name = name.substring(lastSlash + 1);
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex > 0) name = name.substring(0, dotIndex);

        // 智能文件名提取标题
        if (name.matches("\\d+")) {
            try {
                int num = Integer.parseInt(name);
                return "第" + num + "章";
            } catch (NumberFormatException e) { }
        }

        String lower = name.toLowerCase();
        lower = REGEX_LEADING_CHAP.matcher(lower).replaceFirst("");
        lower = REGEX_TRAILING_SEP.matcher(lower).replaceFirst("");
        if (!lower.isEmpty() && !lower.equals(name.toLowerCase())) {
            try {
                int num = Integer.parseInt(lower);
                return "第" + num + "章";
            } catch (NumberFormatException e) {
                name = lower;
            }
        }

        name = REGEX_LEADING_SEP.matcher(name).replaceFirst("");
        name = REGEX_TRAILING_SEP2.matcher(name).replaceFirst("");
        name = REGEX_LEADING_ZERO.matcher(name).replaceFirst("");

        if (!name.isEmpty()) {
            if (name.matches("(?i)^(prologue|foreword|preface|introduction)$"))
                return "序言";
            if (name.matches("(?i)^(epilogue|afterword|postscript)$"))
                return "后记";
            if (name.matches("(?i)^(appendix|reference|glossary)$"))
                return "附录";
            return name;
        }
        return "第" + (index + 1) + "章";
    }

    private static String readText(XmlPullParser parser) throws Exception {
        StringBuilder sb = new StringBuilder();
        int depth = 1;
        while (depth > 0) {
            int event = parser.next();
            if (event == XmlPullParser.TEXT) sb.append(parser.getText());
            else if (event == XmlPullParser.START_TAG) depth++;
            else if (event == XmlPullParser.END_TAG) depth--;
        }
        return sb.toString().trim();
    }

    // ==================== 缓存机制 ====================

    private static String getCacheKey(File file) {
        return file.getAbsolutePath() + "|" + file.length() + "|" + file.lastModified();
    }

    private static File getCacheDir(File epubFile) {
        if (sCacheBaseDir != null && sCacheBaseDir.exists()) return sCacheBaseDir;
        File cacheDir = new File(epubFile.getParentFile(), CACHE_DIR_NAME);
        if (!cacheDir.exists()) cacheDir.mkdirs();
        return cacheDir;
    }

    private static File getCacheFile(File epubFile) {
        String cacheName = epubFile.getName() + "_" + epubFile.length() + ".cache";
        return new File(getCacheDir(epubFile), cacheName);
    }

    private static EpubResult readCache(File file) {
        File cacheFile = getCacheFile(file);
        if (!cacheFile.exists()) return null;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(cacheFile), "UTF-8"));
            String cachedKey = reader.readLine();
            if (!getCacheKey(file).equals(cachedKey)) return null;
            String title = unescapeFromCache(reader.readLine());
            String author = unescapeFromCache(reader.readLine());
            int chapterCount = Integer.parseInt(reader.readLine().trim());
            EpubResult result = new EpubResult();
            result.title = title;
            result.author = author;
            for (int i = 0; i < chapterCount; i++) {
                String chTitle = unescapeFromCache(reader.readLine());
                int contentLen = Integer.parseInt(reader.readLine().trim());
                // ★ I-4: 限制单章内容最大 500KB，防止 OOM
                if (contentLen > 500000) {
                    Log.w(TAG, "缓存单章内容过大 (" + contentLen + ")，跳过");
                    return null;
                }
                char[] buf = new char[contentLen];
                int read = 0;
                int maxRetries = 0;
                while (read < contentLen && maxRetries < 3) {
                    int n = reader.read(buf, read, contentLen - read);
                    if (n < 0) break;
                    read += n;
                    if (n == 0) maxRetries++; else maxRetries = 0;
                }
                String content = new String(buf, 0, read);
                Chapter chapter = new Chapter(chTitle, content);
                chapter.setIndex(i);
                result.chapters.add(chapter);
            }
            return result;
        } catch (Exception e) {
            Log.w(TAG, "缓存读取失败", e);
            return null;
        } finally {
            if (reader != null) try { reader.close(); } catch (IOException e) { }
        }
    }

    private static void writeCache(File file, EpubResult result) {
        if (result == null || result.chapters == null || result.chapters.isEmpty()) return;
        File cacheFile = getCacheFile(file);
        File tmpFile = new File(cacheFile.getAbsolutePath() + ".tmp");
        OutputStreamWriter writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(tmpFile), "UTF-8");
            writer.write(getCacheKey(file) + "\n");
            // ★ I-3: 使用 JSON 转义标题和作者，防止换行符破坏缓存格式
            writer.write(escapeForCache(result.title != null ? result.title : "") + "\n");
            writer.write(escapeForCache(result.author != null ? result.author : "") + "\n");
            writer.write(result.chapters.size() + "\n");
            for (Chapter ch : result.chapters) {
                writer.write(escapeForCache(ch.getTitle() != null ? ch.getTitle() : "") + "\n");
                String content = ch.getContent() != null ? ch.getContent() : "";
                writer.write(content.length() + "\n");
                writer.write(content);
            }
            writer.flush(); writer.close(); writer = null;
            if (cacheFile.exists()) cacheFile.delete();
            tmpFile.renameTo(cacheFile);
        } catch (Exception e) {
            Log.w(TAG, "缓存写入失败", e);
            if (tmpFile.exists()) tmpFile.delete();
        } finally {
            if (writer != null) try { writer.close(); } catch (IOException e) { }
        }
    }

    /**
     * ★ I-3: 缓存转义——将换行符/回车符替换为占位符，读取时还原。
     * 避免章节标题中的换行符破坏缓存的逐行读取格式。
     */
    private static String escapeForCache(String s) {
        return s.replace("\r", "\uE002").replace("\n", "\uE003");
    }

    private static String unescapeFromCache(String s) {
        return s.replace("\uE003", "\n").replace("\uE002", "\r");
    }

    /** 验证是否为有效的 EPUB 文件 */
    public static boolean isValidEpub(File file) {
        if (file == null || !file.exists()) return false;
        if (!file.getName().toLowerCase().endsWith(".epub")) return false;
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(file);
            return zipFile.getEntry("META-INF/container.xml") != null;
        } catch (IOException e) { return false;
        } finally {
            if (zipFile != null) try { zipFile.close(); } catch (IOException e) { }
        }
    }
}
