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
    private float textSize = 20f;                // 字体大小（sp）
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

    // ==================== 绘图工具 ====================
    private Paint textPaint;
    private Paint imageBgPaint;
    private float density;

    // ==================== 点击区域 ====================
    private static final int ZONE_PREV = 0;
    private static final int ZONE_NEXT = 1;
    private static final int ZONE_MENU = 2;

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

        // 文字画笔
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(fgColor);
        textPaint.setTypeface(typeface);

        // 图片背景画笔
        imageBgPaint = new Paint();
        imageBgPaint.setColor(0xFFF0F0F0);
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
        this.paddingRight = px;
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
        applySettings();
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
     */
    public void applySettings() {
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
        layoutPages();
        invalidate();
        notifyPageChanged();
    }

    // ==================== 核心分页算法 ====================

    private void layoutPages() {
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
            totalPages = 0;
            return;
        }

        int contentWidth = viewWidth - paddingLeft - paddingRight;
        int contentHeight = viewHeight - paddingTop - paddingBottom;
        if (contentWidth <= 0 || contentHeight <= 0) {
            totalPages = 0;
            return;
        }

        textPaint.setTextSize(textSize * density);
        Paint.FontMetrics pfm = textPaint.getFontMetrics();
        float lineHeight = (float) Math.ceil(pfm.descent - pfm.ascent) * lineSpacing;
        float paraSpacingPx = textSize * density * (paragraphSpacing - lineSpacing);

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
            float firstLineIndent = paraTextSize * density; // 首行缩进1字

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
                int indent = 0;
                actualContentWidth = contentWidth - indent * 2;
                actualPaddingLeft = paddingLeft + indent;
            }

            // ★ 首行缩进：第一行用短宽度，后续行正常
            boolean hasIndent = (firstLineIndent > 0 && paraType == com.einkreader.core.model.Chapter.PARA_NORMAL);
            String remaining = trimmed;
            if (hasIndent && !isCentered) {
                int indentPx = (int)firstLineIndent;
                if (indentPx < actualContentWidth - 10) {
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
    }

    /**
     * 文字自动换行
     */
    private List<String> wrapText(String text, int maxWidthPx) {
        List<String> lines = new ArrayList<String>();
        if (text == null || text.isEmpty()) {
            lines.add("");
            return lines;
        }

        // 按字符逐个测量，遇到宽度超限则换行
        StringBuilder currentLine = new StringBuilder();
        float currentWidth = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            float charWidth = textPaint.measureText(String.valueOf(c));

            if (currentWidth + charWidth > maxWidthPx && currentLine.length() > 0) {
                // 换行
                lines.add(currentLine.toString());
                currentLine = new StringBuilder();
                currentWidth = 0;
            }

            currentLine.append(c);
            currentWidth += charWidth;
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

        DebugLog.log("Draw", "page=" + currentPage + "/" + totalPages + " lines=" + (pages.size() > currentPage && pages.get(currentPage) != null ? pages.get(currentPage).lines.size() : 0) + " imgs=" + (pages.size() > currentPage && pages.get(currentPage) != null ? pages.get(currentPage).images.size() : 0));
        if (pages.isEmpty() || currentPage >= pages.size()) return;

        Page page = pages.get(currentPage);

        // 绘制文字
        textPaint.setColor(fgColor);
        textPaint.setTypeface(typeface);

        for (TextLine line : page.lines) {
            textPaint.setTextSize(line.fontSize * density);
            textPaint.setFakeBoldText(line.bold);
            canvas.drawText(line.text, line.x, line.y, textPaint);
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
            Paint pageNumPaint = new Paint();
            pageNumPaint.setColor(mutedColor);
            pageNumPaint.setTextSize(14 * density);
            pageNumPaint.setTextAlign(Paint.Align.CENTER);
            String pageText = (currentPage + 1) + " / " + totalPages;
            canvas.drawText(pageText, getWidth() / 2f, getHeight() - 10, pageNumPaint);
        }
    }

    // ==================== 尺寸变化 ====================

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
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            float x = event.getX();
            float width = getWidth();

            int zone;
            if (x < width * 0.3f) {
                zone = ZONE_PREV;
            } else if (x > width * 0.7f) {
                zone = ZONE_NEXT;
            } else {
                zone = ZONE_MENU;
            }

            switch (zone) {
                case ZONE_PREV:
                    prevPage();
                    break;
                case ZONE_NEXT:
                    nextPage();
                    break;
                case ZONE_MENU:
                    if (pageChangeListener != null) {
                        pageChangeListener.onTapCenter();
                    }
                    break;
            }
            return true;
        }
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