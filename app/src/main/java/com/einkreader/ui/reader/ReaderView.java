package com.einkreader.ui.reader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.einkreader.core.model.Chapter;
import com.einkreader.core.Constants;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ReaderView —— 墨水屏阅读器的核心自绘控件
 *
 * 功能：
 * 1. 文字排版：支持自定义字体大小、行距、段距、字体
 * 2. 自动分页：根据屏幕尺寸自动把内容分成多页
 * 3. 图片显示：支持 EPUB 内嵌图片（[[IMAGE:xxx]] 标记）
 * 4. 点击翻页：左侧点上一页，右侧点下一页，中间弹菜单
 */
public class ReaderView extends View {

    // ==================== 排版设置 ====================
    private float textSize = 26f;                // 字体大小（sp）
    private float lineSpacing = 1.5f;            // 行距倍率（1.0=单倍行距）
    private float paragraphSpacing = 1.8f;       // 段距倍率
    private Typeface typeface = Typeface.DEFAULT; // 当前字体
    // 颜色方案（支持夜间模式切换）
    private int bgColor = Color.WHITE;
    private int fgColor = Color.BLACK;
    private int mutedColor = 0xFF999999;
    private int paddingLeft = 20;                 // 左内边距
    private int paddingRight = 20;                // 右内边距
    private int paddingTop = 16;                  // 上内边距
    private int paddingBottom = 16;               // 下内边距

    // ==================== 分页数据 ====================
    private List<Page> pages = new ArrayList<Page>();
    private int currentPage = 0;
    private Chapter currentChapter;
    private int totalPages = 0;

    // ==================== 图片数据 ====================
    private Map<String, byte[]> chapterImages;   // 当前章的图片
    private List<Bitmap> loadedBitmaps = new ArrayList<Bitmap>();

    // ==================== 监听器 ====================
    private OnPageChangeListener pageChangeListener;
    private boolean isLayoutReady = false;
    private boolean enableFirstLineIndent = false;  // ★ 默认关闭首行缩进，避免首行短造成"偏右"错觉
    private boolean batchMode = false;  // ★ 批量更新模式：延迟 applySettings，避免重复 layoutPages

    // ==================== 绘图工具 ====================
    private Paint textPaint;
    private Paint imageBgPaint;
    private Paint pageNumPaint;  // ★ I-2: 复用页码画笔，避免每帧创建
    private float density;

    // ==================== 点击区域 ====================
    private static final int ZONE_PREV = 0;
    private static final int ZONE_NEXT = 1;
    private static final int ZONE_MENU = 2;

    // ==================== 防抖：防止墨水屏连续快速翻页导致屏幕闪烁 ====================
    private long lastPageChangeTime = 0;
    private static final long PAGE_DEBOUNCE_MS = 250L;

    // ==================== 触摸跟踪 ====================
    private float downX = 0;
    private float downY = 0;
    private int lastZone = ZONE_MENU;

    // ==================== 分页数据模型 ====================

    /** 一页的内容 */
    static class Page {
        List<TextLine> lines = new ArrayList<TextLine>();
        List<ImageBlock> images = new ArrayList<ImageBlock>();
    }

    /** 一行文字 */
    static class TextLine {
        String text;
        float x, y;
        float fontSize;      // 本行字号
        boolean bold;        // 是否加粗

        TextLine(String text, float x, float y, float fontSize, boolean bold) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.fontSize = fontSize;
            this.bold = bold;
        }
    }

    /** 一张图片 */
    static class ImageBlock {
        Bitmap bitmap;
        Rect rect;

        ImageBlock(Bitmap bitmap, Rect rect) {
            this.bitmap = bitmap;
            this.rect = rect;
        }
    }

    // ==================== 监听器接口 ====================

    public interface OnPageChangeListener {
        void onPageChanged(int pageIndex, int totalPages);
        void onChapterChanged(int chapterIndex);
        void onTapCenter();
        void onNeedPrevChapter();
        void onNeedNextChapter();
    }

    // ==================== 构造 ====================

    public ReaderView(Context context) {
        super(context);
        init();
    }

    public ReaderView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
            setBackgroundColor(bgColor);
            density = getResources().getDisplayMetrics().density;
            int densityDpi = getResources().getDisplayMetrics().densityDpi;
        
            DebugLog.log("Init", "density=" + density + " densityDpi=" + densityDpi);
        
            // ★ 使用 dp 初始化 padding，右侧略大以缓冲抗锯齿外扩
            float defaultDp = 10f;
            float rightDp = 14f;  // 右侧多 4dp，防止文字边缘被裁切
            paddingLeft = (int)(defaultDp * density + 0.5f);
            paddingRight = (int)(rightDp * density + 0.5f);
            paddingTop = (int)(defaultDp * density + 0.5f);
            paddingBottom = (int)(defaultDp * density + 0.5f);
        
            DebugLog.log("Init", "padding: left=" + paddingLeft + " right=" + paddingRight + " top=" + paddingTop + " bottom=" + paddingBottom);

        // 文字画笔
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(fgColor);
        textPaint.setTypeface(typeface);
        textPaint.setTextAlign(Paint.Align.LEFT);  // ★ 确保左对齐

        // 图片背景画笔
        imageBgPaint = new Paint();
        imageBgPaint.setColor(0xFFF0F0F0);

        // ★ I-2: 页码画笔（复用，避免每帧创建）
                pageNumPaint = new Paint();
                pageNumPaint.setColor(mutedColor);
                pageNumPaint.setTextSize(Constants.PAGE_NUMBER_TEXT_SIZE_SP * density);
                pageNumPaint.setTextAlign(Paint.Align.CENTER);
    }

    // ==================== 公开设置方法 ====================

    public void setTextSize(float sp) {
        this.textSize = sp;
        applySettings();
    }

    public void setLineSpacing(float spacing) {
        this.lineSpacing = spacing;
        applySettings();
    }

    public void setParagraphSpacing(float spacing) {
        this.paragraphSpacing = spacing;
        applySettings();
    }

    public void setHorizontalMargin(int dp) {
        int px = (int)(dp * density + 0.5f);
        this.paddingLeft = px;
        // ★ 右侧多 4dp 安全余量，防止文字边缘被裁切（与 init() 保持一致）
        this.paddingRight = (int)((dp + 4f) * density + 0.5f);
        applySettings();
    }

    /**
     * ★ 批量更新模式：开始批量设置
     * 调用后所有 setter 只更新字段值，不触发 layoutPages
     * 必须与 commitBatchUpdate() 配对使用
     */
    public void beginBatchUpdate() {
        batchMode = true;
    }

    /**
     * ★ 批量更新模式：提交批量设置
     * 统一触发一次 layoutPages，避免重复分页
     */
    public void commitBatchUpdate() {
        batchMode = false;
        applySettings();
    }

    public void setFirstLineIndent(boolean enable) {
        this.enableFirstLineIndent = enable;
        applySettings();
    }

    public void setCustomTypeface(Typeface tf) {
        this.typeface = (tf != null) ? tf : Typeface.DEFAULT;
        textPaint.setTypeface(this.typeface);
        applySettings();
    }

    public void setNightMode(boolean night) {
        if (night) {
            bgColor = 0xFF000000;
            fgColor = 0xFFBBBBBB;
            mutedColor = 0xFF555555;
        } else {
            bgColor = Color.WHITE;
            fgColor = Color.BLACK;
            mutedColor = 0xFF999999;
        }
        setBackgroundColor(bgColor);
        textPaint.setColor(fgColor);
        pageNumPaint.setColor(mutedColor);
        // ★ 夜间模式只改变颜色，不影响分页，无需 layoutPages
        if (!batchMode) invalidate();
    }

    public void setChapterImages(Map<String, byte[]> images) {
        // 释放旧图片
        for (Bitmap bmp : loadedBitmaps) {
            if (bmp != null && !bmp.isRecycled()) bmp.recycle();
        }
        loadedBitmaps.clear();
        this.chapterImages = images;
    }

    /**
     * 重新应用所有设置并重新分页
     * ★ 批量模式下延迟执行（由 commitBatchUpdate 统一触发）
     * ★ 位置恢复：layoutPages 内部会基于文字指纹恢复阅读位置
     */
    public void applySettings() {
        if (batchMode) return;  // 批量模式下延迟
        if (currentChapter != null) {
            layoutPages();
            if (currentPage >= totalPages) currentPage = Math.max(0, totalPages - 1);
            invalidate();
            notifyPageChanged();
        }
    }

    // ==================== 设置篇章 ====================

    public void setChapter(Chapter chapter) {
        this.currentChapter = chapter;
        this.currentPage = 0;
        // ★ I-9: 切换章节时回收旧图片，防止 Bitmap 堆积
        for (Bitmap bmp : loadedBitmaps) {
            if (bmp != null && !bmp.isRecycled()) bmp.recycle();
        }
        loadedBitmaps.clear();
        this.chapterImages = null;
        layoutPages();
        invalidate();
        notifyPageChanged();
    }

    // ==================== 核心分页算法 ====================

    private void layoutPages() {
        // ★ 保存当前页第一行文字指纹，用于重新分页后恢复阅读位置
        // currentPage=0 时不保存（如 setChapter 新章节），避免错误恢复
        String pageFingerprint = null;
        if (currentPage > 0 && currentPage < pages.size()) {
            Page cp = pages.get(currentPage);
            if (cp != null && !cp.lines.isEmpty()) {
                String t = cp.lines.get(0).text;
                if (t != null && t.length() > 0) {
                    pageFingerprint = t.substring(0, Math.min(24, t.length()));
                }
            }
        }
        pages.clear();

        if (currentChapter == null) {
            totalPages = 0;
            return;
        }

        String content = currentChapter.getContent();
        if (content == null) content = "";
        DebugLog.log("Layout", "w=" + getWidth() + " h=" + getHeight() + " pad=" + paddingLeft + "/" + paddingRight + " cw=" + (getWidth() - paddingLeft - paddingRight) + " ch=" + (getHeight() - paddingTop - paddingBottom) + " ts=" + textSize + " dens=" + density + " ls=" + lineSpacing + " ps=" + paragraphSpacing);
        content = content.trim();
        if (content.isEmpty()) {
            content = "(本章内容为空)";
        }

        int viewWidth = getWidth();
                int viewHeight = getHeight();
                if (viewWidth <= 0 || viewHeight <= 0) {
                    DebugLog.log("Layout", "skip: view size not ready yet w=" + viewWidth + " h=" + viewHeight);
                    totalPages = 0;
                    return;
                }
        
                DebugLog.log("Layout", "★★★ viewSize: " + viewWidth + "x" + viewHeight + ", padding: " + paddingLeft + "/" + paddingRight + "/" + paddingTop + "/" + paddingBottom);
        
                int contentWidth = viewWidth - paddingLeft - paddingRight;
                int contentHeight = viewHeight - paddingTop - paddingBottom;
        
                DebugLog.log("Layout", "★★★ contentSize: " + contentWidth + "x" + contentHeight);
        
                if (contentWidth <= 0 || contentHeight <= 0) {
                    totalPages = 0;
                    return;
                }
        DebugLog.log("Layout", "calc: viewW=" + viewWidth + " padL=" + paddingLeft + " padR=" + paddingRight + " contentW=" + contentWidth);

        textPaint.setTextSize(textSize * density);
        Paint.FontMetrics pfm = textPaint.getFontMetrics();
        float lineHeight = (float) Math.ceil(pfm.descent - pfm.ascent) * lineSpacing;
        float paraSpacingPx = textSize * density * (paragraphSpacing - lineSpacing);
        DebugLog.log("Layout", "metrics: textSizePx=" + (textSize * density) + " firstIndentEnabled=" + enableFirstLineIndent + " lineHeightPx=" + lineHeight);

        // 按段落分割
        String[] paragraphs = content.split("\n", -1);

        Page currentPageData = new Page();
        float y = paddingTop;

        // ★ 获取段落类型列表
        List<Integer> paraTypes = (currentChapter != null)
                ? currentChapter.getParagraphTypes() : null;

        for (int pi = 0; pi < paragraphs.length; pi++) {
            String para = paragraphs[pi];
            String trimmed = para.trim();

            // ★ 获取本段类型
            int paraType = com.einkreader.core.model.Chapter.PARA_NORMAL;
            if (paraTypes != null && pi < paraTypes.size()) {
                paraType = paraTypes.get(pi);
            }

            // ★ 根据段落类型设置样式
            float paraTextSize = textSize;
            float paraLineSpacing = lineSpacing;
            float paraExtraSpacing = paraSpacingPx;
            boolean isCentered = false;
            boolean isBold = false;
            float firstLineIndent = enableFirstLineIndent ? paraTextSize * density : 0; // 首行缩进1字（默认关闭）

            switch (paraType) {
                case com.einkreader.core.model.Chapter.PARA_H1:
                    paraTextSize = textSize * 1.8f;   // 一号标题：1.8倍
                    paraLineSpacing = 1.2f;
                    paraExtraSpacing = textSize * density * 1.5f;
                    isCentered = true;
                    isBold = true;
                    break;
                case com.einkreader.core.model.Chapter.PARA_H2:
                    paraTextSize = textSize * 1.5f;   // 二号标题：1.5倍
                    isBold = true;
                    paraExtraSpacing = textSize * density * 1.2f;
                    break;
                case com.einkreader.core.model.Chapter.PARA_H3:
                    paraTextSize = textSize * 1.2f;   // 三号标题：1.2倍
                    isBold = true;
                    break;
                case com.einkreader.core.model.Chapter.PARA_BLOCKQUOTE:
                    paraTextSize = textSize * 0.9f;   // 引用：0.9倍
                    // 通过增加左右缩进来模拟引用效果
                    break;
                default:

                    break;
            }

            textPaint.setTextSize(paraTextSize * density);
            if (isBold) textPaint.setFakeBoldText(true);
            else textPaint.setFakeBoldText(false);

            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float lineH = (float) Math.ceil(fm.descent - fm.ascent) * paraLineSpacing;

            // 检查是否为图片标记
            if (trimmed.startsWith("[[IMAGE:") && trimmed.endsWith("]]")) {
                String imgPath = trimmed.substring(8, trimmed.length() - 2).trim();
                Bitmap bitmap = loadBitmap(imgPath);
                if (bitmap != null) {
                    // 按比例缩放图片到内容宽度
                    float scale = (float) contentWidth / bitmap.getWidth();
                    int imgW = contentWidth;
                    int imgH = (int) (bitmap.getHeight() * scale);
                    if (imgH > contentHeight / 2) {
                        // 图片太大，限制最大高度为页面的一半
                        scale = (float) (contentHeight / 2) / bitmap.getHeight();
                        imgW = (int) (bitmap.getWidth() * scale);
                        imgH = contentHeight / 2;
                    }

                    if (y + imgH > paddingTop + contentHeight) {
                        // 放不下了，新起一页
                        pages.add(currentPageData);
                        currentPageData = new Page();
                        y = paddingTop;
                    }

                    int imgX = paddingLeft + (contentWidth - imgW) / 2; // 居中
                    Rect imgRect = new Rect(imgX, (int) y, imgX + imgW, (int) (y + imgH));
                    currentPageData.images.add(new ImageBlock(bitmap, imgRect));
                    y += imgH + paraSpacingPx;
                }
                continue;
            }

            // 文字段落：自动换行
            if (trimmed.isEmpty()) {
                y += lineH; // 空行
                continue;
            }

            // ★ 计算实际可用宽度（引用的左右缩进）
            int actualContentWidth = contentWidth;
            float actualPaddingLeft = paddingLeft;
            if (paraType == com.einkreader.core.model.Chapter.PARA_BLOCKQUOTE) {
                int indent = (int)(textSize * density * 2);
                if (indent * 2 < contentWidth) {
                    actualContentWidth = contentWidth - indent * 2;
                    actualPaddingLeft = paddingLeft + indent;
                }
            }

            // ★ 首行缩进：第一行用短宽度，后续行正常
            boolean hasIndent = (firstLineIndent > 0 && paraType == com.einkreader.core.model.Chapter.PARA_NORMAL);
            String remaining = trimmed;
                        if (hasIndent && !isCentered) {
                            int indentPx = (int)firstLineIndent;
                            if (indentPx < actualContentWidth - Constants.MIN_INDENT_AVAILABLE_WIDTH_PX) {
                                int shortWidth = actualContentWidth - indentPx;
                    List<String> firstOnly = wrapText(trimmed, shortWidth);
                    if (!firstOnly.isEmpty()) {
                        String firstLine = firstOnly.get(0);
                        if (firstLine.length() < trimmed.length()) {
                            remaining = trimmed.substring(firstLine.length());
                        } else { remaining = ""; }
                        if (y + lineH > paddingTop + contentHeight) {
                            pages.add(currentPageData);
                            currentPageData = new Page();
                            y = paddingTop;
                        }
                        float fx = actualPaddingLeft + indentPx;
                        currentPageData.lines.add(new TextLine(firstLine, fx, y + (float)Math.ceil(-pfm.ascent), paraTextSize, isBold));
                        y += lineH;
                    }
                }
            }

            if (!remaining.isEmpty()) {
                List<String> wrappedLines = wrapText(remaining, actualContentWidth);
                for (int li = 0; li < wrappedLines.size(); li++) {
                    String line = wrappedLines.get(li);
                    if (y + lineH > paddingTop + contentHeight) {
                        pages.add(currentPageData);
                        currentPageData = new Page();
                        y = paddingTop;
                    }
                    float x = actualPaddingLeft;
                    if (isCentered) {
                        float lw = textPaint.measureText(line);
                        x = (getWidth() - lw) / 2f;
                    }
                    currentPageData.lines.add(new TextLine(line, x, y + (float)Math.ceil(-pfm.ascent), paraTextSize, isBold));
                    y += lineH;
                }
            }

            // 段后间距
            y += paraExtraSpacing;

            // 恢复 textPaint 到默认样式
            textPaint.setTextSize(textSize * density);
            textPaint.setFakeBoldText(false);
        }

        // 最后一页
        if (!currentPageData.lines.isEmpty() || !currentPageData.images.isEmpty()) {
            pages.add(currentPageData);
        }

        totalPages = pages.size();
        if (totalPages == 0) {
            DebugLog.log("Layout", "totalPages=" + totalPages + " chapters=" + (currentChapter != null ? currentChapter.getContent().length() : 0) + "chars");
            // 至少有一页
            pages.add(new Page());
            totalPages = 1;
        }

        // ★ 恢复阅读位置：查找文字指纹所在的新页码
        if (pageFingerprint != null) {
            int foundPage = -1;
            for (int i = 0; i < pages.size(); i++) {
                Page p = pages.get(i);
                if (p != null && !p.lines.isEmpty()) {
                    String t = p.lines.get(0).text;
                    if (t != null && t.startsWith(pageFingerprint)) {
                        foundPage = i;
                        break;
                    }
                }
            }
            if (foundPage >= 0) {
                currentPage = foundPage;
                DebugLog.log("Layout", "位置恢复: fingerprint='" + pageFingerprint + "' -> page " + foundPage);
            } else {
                DebugLog.log("Layout", "位置恢复失败: fingerprint='" + pageFingerprint + "' 未找到");
                if (currentPage >= totalPages) currentPage = Math.max(0, totalPages - 1);
            }
        }
    }

    /**
     * 文字自动换行
     *
     * 策略：
     * - 对 CJK 字符：逐字添加，每次用整行 measureText 校验，确保精度
     * - 对拉丁字母：按单词宽度换行（遇空格时优先在空格处断开），超长 word 逐字截断
     * - 混合场景：以当前段的整体第一个字符判定主要语言
     *
     * 精度保障：
     * - 使用 measureText()（float 精度）而非 getTextBounds()（int 精度），避免取整误差
     * - 每次添加字符后整行测量，杜绝逐字累加与整行渲染的偏差
     * - TEXT_SAFETY_MARGIN 安全余量覆盖抗锯齿外扩
     */
    private static final float TEXT_SAFETY_MARGIN = 3.0f;

    private float measureCharWidth(char c) {
        char[] buf = {c};
        return textPaint.measureText(buf, 0, 1);
    }

    private float measureStringWidth(String s) {
        if (s == null || s.isEmpty()) return 0f;
        return textPaint.measureText(s);
    }

    private List<String> wrapText(String text, int maxWidthPx) {
        List<String> lines = new ArrayList<String>();
        if (text == null || text.isEmpty()) {
            lines.add("");
            return lines;
        }

        // 判定语言：跳过标点符号和空格，找第一个字母/汉字来判定
        // ★ 修复：双引号 " (0x22) 等标点不应误判为非 CJK，否则中文对话开头的 " 会走英文模式导致不换行
        boolean isCjkFirst = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x4E00 && c <= 0x9FFF)
                    || (c >= 0x3400 && c <= 0x4DBF)
                    || (c >= 0x3040 && c <= 0x30FF)) {
                isCjkFirst = true;
                break;
            } else if (Character.isLetterOrDigit(c)) {
                // 第一个字母/数字是非 CJK → 英文模式
                isCjkFirst = false;
                break;
            }
            // 标点符号、空格、引号等：继续找下一个实质字符
        }

        StringBuilder currentLine = new StringBuilder();

        if (!isCjkFirst) {
            // 英文模式：按空格分词，整行测量校验
            String[] words = text.split(" ", -1);
            for (int wi = 0; wi < words.length; wi++) {
                String word = words[wi];
                float wordWidth = measureStringWidth(word);

                // ★ 超长 word 逐字符截断（无论 currentLine 是否为空，都要截断）
                if (wordWidth + TEXT_SAFETY_MARGIN > maxWidthPx) {
                    if (currentLine.length() > 0) {
                        lines.add(currentLine.toString());
                        currentLine.setLength(0);
                    }
                    for (int ci = 0; ci < word.length(); ci++) {
                        char c = word.charAt(ci);
                        currentLine.append(c);
                        float actualWidth = measureStringWidth(currentLine.toString());
                        if (actualWidth + TEXT_SAFETY_MARGIN > maxWidthPx && currentLine.length() > 1) {
                            currentLine.deleteCharAt(currentLine.length() - 1);
                            lines.add(currentLine.toString());
                            currentLine.setLength(0);
                            currentLine.append(c);
                        }
                    }
                    continue;
                }

                // 正常 word：尝试添加到当前行
                String tryLine = currentLine.length() > 0
                        ? currentLine.toString() + " " + word
                        : word;
                float tryWidth = measureStringWidth(tryLine);

                if (tryWidth + TEXT_SAFETY_MARGIN > maxWidthPx && currentLine.length() > 0) {
                    // 当前行放不下，换行
                    lines.add(currentLine.toString());
                    currentLine.setLength(0);
                    currentLine.append(word);
                } else {
                    // 放得下，更新 currentLine
                    currentLine.setLength(0);
                    currentLine.append(tryLine);
                }
            }
        } else {
            // CJK 模式：逐字添加 + 每次整行测量校验
            // 不再用 currentWidth 累加（避免逐字测量与整行渲染的偏差），直接整行测量确保精度
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                currentLine.append(c);
                float actualWidth = measureStringWidth(currentLine.toString());
                if (actualWidth + TEXT_SAFETY_MARGIN > maxWidthPx && currentLine.length() > 1) {
                    // 整行测量超出，回退最后一个字符到下一行
                    currentLine.deleteCharAt(currentLine.length() - 1);
                    lines.add(currentLine.toString());
                    currentLine.setLength(0);
                    currentLine.append(c);
                }
            }
        }

        // 最后一行
        if (currentLine.length() > 0) {
            lines.add(currentLine.toString());
        }

        if (lines.isEmpty()) lines.add("");
        return lines;
    }

    /**
     * 加载图片（从缓存 byte[] 解码）
     */
    private Bitmap loadBitmap(String path) {
        if (chapterImages == null) return null;
        byte[] data = chapterImages.get(path);
        if (data == null) return null;
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (bitmap != null) {
            loadedBitmaps.add(bitmap);
        }
        return bitmap;
    }

    // ==================== 绘图 ====================

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // 背景色（日间白色省电/夜间黑色护眼）
        canvas.drawColor(bgColor);

        if (!isLayoutReady || getWidth() <= 0 || getHeight() <= 0) {
            DebugLog.log("Draw", "skip: layout not ready yet w=" + getWidth() + " h=" + getHeight());
            return;
        }

        DebugLog.log("Draw", "page=" + currentPage + "/" + totalPages + " lines=" + (pages.size() > currentPage && pages.get(currentPage) != null ? pages.get(currentPage).lines.size() : 0) + " imgs=" + (pages.size() > currentPage && pages.get(currentPage) != null ? pages.get(currentPage).images.size() : 0));
        if (pages.isEmpty() || currentPage >= pages.size()) return;

        Page page = pages.get(currentPage);

        // 绘制文字
        textPaint.setColor(fgColor);
        textPaint.setTypeface(typeface);

        int linesDrawn = 0;
        for (TextLine line : page.lines) {
            textPaint.setTextSize(line.fontSize * density);
            textPaint.setFakeBoldText(line.bold);
            canvas.drawText(line.text, line.x, line.y, textPaint);
            linesDrawn++;
        }
        if (currentPage == 0 && linesDrawn > 0) {
            DebugLog.log("Draw", "page0: lines=" + linesDrawn + " firstLine.x=" + page.lines.get(0).x + " text=" + page.lines.get(0).text.substring(0, Math.min(10, page.lines.get(0).text.length())) + "...");
        }

        // 绘制图片
        for (ImageBlock img : page.images) {
            if (img.bitmap != null && !img.bitmap.isRecycled()) {
                // 画灰色背景（有些图片有透明背景）
                canvas.drawRect(img.rect, imageBgPaint);
                canvas.drawBitmap(img.bitmap, null, img.rect, null);
            }
        }

        // 绘制页码（底部居中）
        if (totalPages > 0) {
            pageNumPaint.setColor(mutedColor);
            String pageText = (currentPage + 1) + " / " + totalPages;
            canvas.drawText(pageText, getWidth() / 2f, getHeight() - 10, pageNumPaint);
        }
    }

    // ==================== 尺寸变化 ====================

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int w = getMeasuredWidth();
        int h = getMeasuredHeight();
        if (w > 0 && h > 0 && (w != lastMeasuredW || h != lastMeasuredH)) {
            lastMeasuredW = w;
            lastMeasuredH = h;
            isLayoutReady = true;
            DebugLog.log("Measure", "onMeasure: w=" + w + " h=" + h + " pw=" + paddingLeft + " ph=" + paddingRight);
            if (currentChapter != null) {
                layoutPages();
                invalidate();
                notifyPageChanged();
            }
        }
    }

    private int lastMeasuredW = 0;
    private int lastMeasuredH = 0;

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        isLayoutReady = true;
        if (currentChapter != null) {
            layoutPages();
            invalidate();
            notifyPageChanged();
        }
    }

    // ==================== 触摸翻页 ====================

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        float width = getWidth();

        int zone = ZONE_MENU;
        if (width > 0) {
            if (x < width * 0.3f) {
                zone = ZONE_PREV;
            } else if (x > width * 0.7f) {
                zone = ZONE_NEXT;
            }
        }

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                lastZone = zone;
                return true;

            case MotionEvent.ACTION_MOVE:
                return true;

            case MotionEvent.ACTION_CANCEL:
                return true;

            case MotionEvent.ACTION_UP:
                float dx = Math.abs(x - downX);
                float dy = Math.abs(y - downY);
                if (dx > 25f || dy > 25f) {
                    return true;
                }

                int upZone = (width > 0) ? zone : lastZone;

                long now = System.currentTimeMillis();
                switch (upZone) {
                    case ZONE_PREV:
                        if (tryConsumePageTurn(now)) prevPage();
                        break;
                    case ZONE_NEXT:
                        if (tryConsumePageTurn(now)) nextPage();
                        break;
                    case ZONE_MENU:
                    default:
                        if (pageChangeListener != null) {
                            pageChangeListener.onTapCenter();
                        }
                        break;
                }
                return true;
        }

        return true;
    }

    /**
     * 尝试消费一次翻页事件（防抖）
     * @param now 当前时间戳
     * @return true 表示允许翻页；false 表示处于冷却期
     */
    private boolean tryConsumePageTurn(long now) {
        if (now - lastPageChangeTime < PAGE_DEBOUNCE_MS) {
            return false;
        }
        lastPageChangeTime = now;
        return true;
    }

    // ==================== 翻页控制 ====================

    /**
     * 上一页
     * @return true=翻页成功，false=已经是第一页
     */
    public boolean prevPage() {
        if (currentPage > 0) {
            currentPage--;
            invalidate();
            notifyPageChanged();
            return true;
        }
        // 已经是第一页，回调让 Activity 切换到上一章
        if (pageChangeListener != null) {
            pageChangeListener.onNeedPrevChapter();
        }
        return false;
    }

    /**
     * 下一页
     * @return true=翻页成功，false=已经是最后一页
     */
    public boolean nextPage() {
        if (currentPage < totalPages - 1) {
            currentPage++;
            invalidate();
            notifyPageChanged();
            return true;
        }
        // 已经是最后一页，回调让 Activity 切换到下一章
        if (pageChangeListener != null) {
            pageChangeListener.onNeedNextChapter();
        }
        return false;
    }

    /**
     * 跳到指定页
     */
    public void goToPage(int page) {
        if (page >= 0 && page < totalPages) {
            currentPage = page;
            invalidate();
            notifyPageChanged();
        }
    }

    public void goToPageSafe(int page) {
        if (totalPages <= 0) return;
        if (page < 0) page = 0;
        if (page >= totalPages) page = totalPages - 1;
        currentPage = page;
        invalidate();
        notifyPageChanged();
    }

    public int getCurrentPage() { return currentPage; }
    public int getTotalPages() { return totalPages; }

    public void cancelFullRefresh() {
        // 预留：取消正在进行的全屏刷新
    }

    public void performFullRefresh() {
        // 手动触发全刷（画白再重绘）
        if (getParent() instanceof View) {
            ((View) getParent()).postInvalidate();
        }
        invalidate();
    }

    // ==================== 回调 ====================

    public void setOnPageChangeListener(OnPageChangeListener listener) {
        this.pageChangeListener = listener;
    }

    private void notifyPageChanged() {
        if (pageChangeListener != null) {
            pageChangeListener.onPageChanged(currentPage, totalPages);
        }
    }




    public void simulateLeftTap() {
        DebugLog.log("Sim", "leftTap");
        if (currentPage > 0) { prevPage(); }
        else if (pageChangeListener != null) pageChangeListener.onNeedPrevChapter();
    }

    public void simulateRightTap() {
        DebugLog.log("Sim", "rightTap");
        if (currentPage < totalPages - 1) { nextPage(); }
        else if (pageChangeListener != null) pageChangeListener.onNeedNextChapter();
    }
}