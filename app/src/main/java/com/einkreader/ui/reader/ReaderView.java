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
import android.util.LruCache;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
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
 * ★ v2: 分页计算在后台 HandlerThread 执行（主线程捕获尺寸后传入，不阻塞 UI）
 *   使用 layoutVersion 递增丢弃过期结果
 */
public class ReaderView extends View {

    // ==================== 排版设置 ====================
    private float textSize = 26f;
    private float lineSpacing = 1.5f;
    private float paragraphSpacing = 1.8f;
    private Typeface typeface = Typeface.DEFAULT;
    private int bgColor = Color.WHITE;
    private volatile int fgColor = Color.BLACK;
    private int mutedColor = 0xFF999999;
    private int paddingLeft = 20;
    private int paddingRight = 20;
    private int paddingTop = 16;
    private int paddingBottom = 16;

    // ==================== 分页数据 ====================
    private List<Page> pages = new ArrayList<Page>();
    private int currentPage = 0;
    private Chapter currentChapter;
    private int totalPages = 0;

    // ==================== 图片数据 ====================
    private Map<String, byte[]> chapterImages;
    private static final int MAX_BITMAP_CACHE_BYTES = 8 * 1024 * 1024;
    private LruCache<String, Bitmap> bitmapCache;

    // ==================== 后台分页线程 ====================
    private HandlerThread layoutThread;
    private Handler backgroundHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile int layoutVersion = 0;

    // ==================== 监听器 ====================
    private OnPageChangeListener pageChangeListener;
    private boolean isLayoutReady = false;
    private boolean enableFirstLineIndent = false;
    private boolean batchMode = false;

    // ==================== 绘图工具 ====================
    private Paint textPaint;
    private Paint imageBgPaint;
    private Paint pageNumPaint;
    private float density;

    // ==================== 点击区域 ====================
    private static final int ZONE_PREV = 0;
    private static final int ZONE_NEXT = 1;
    private static final int ZONE_MENU = 2;

    // ==================== 防抖 ====================
    private long lastPageChangeTime = 0;
    private static final long PAGE_DEBOUNCE_MS = 250L;
    private float downX = 0, downY = 0;
    private int lastZone = ZONE_MENU;

    // ==================== 分页数据模型 ====================

    static class Page {
        List<TextLine> lines = new ArrayList<TextLine>();
        List<ImageBlock> images = new ArrayList<ImageBlock>();
    }

    static class TextLine {
        String text;
        float x, y;
        float fontSize;
        boolean bold;

        TextLine(String text, float x, float y, float fontSize, boolean bold) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.fontSize = fontSize;
            this.bold = bold;
        }
    }

    static class ImageBlock {
        Bitmap bitmap;
        Rect rect;
        String path; // 图片路径，用于后台→UI 线程传递

        ImageBlock(Bitmap bitmap, Rect rect) {
            this.bitmap = bitmap;
            this.rect = rect;
        }

        ImageBlock(String path, Rect rect) {
            this.path = path;
            this.rect = rect;
            this.bitmap = null;
        }
    }

    // ==================== 监听器接口 ====================

    /** 当异步布局完成后，跳转到此页面，-1 表示不跳转 */
    private int mPendingTargetPage = -1;

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
        DebugLog.log("Init", "density=" + density);

        float defaultDp = 10f, rightDp = 14f;
        paddingLeft = (int)(defaultDp * density + 0.5f);
        paddingRight = (int)(rightDp * density + 0.5f);
        paddingTop = (int)(defaultDp * density + 0.5f);
        paddingBottom = (int)(defaultDp * density + 0.5f);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(fgColor);
        textPaint.setTypeface(typeface);
        textPaint.setTextAlign(Paint.Align.LEFT);

        imageBgPaint = new Paint();
        imageBgPaint.setColor(0xFFF0F0F0);

        pageNumPaint = new Paint();
        pageNumPaint.setColor(mutedColor);
        pageNumPaint.setTextSize(Constants.PAGE_NUMBER_TEXT_SIZE_SP * density);
        pageNumPaint.setTextAlign(Paint.Align.CENTER);

        layoutThread = new HandlerThread("layout-bg");
        layoutThread.start();
        backgroundHandler = new Handler(layoutThread.getLooper());

        bitmapCache = new LruCache<String, Bitmap>(MAX_BITMAP_CACHE_BYTES) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount();
            }
        };
    }

    // ==================== 公开设置方法 ====================

    public void setTextSize(float sp) { this.textSize = sp; applySettings(); }
    public void setLineSpacing(float spacing) { this.lineSpacing = spacing; applySettings(); }
    public void setParagraphSpacing(float spacing) { this.paragraphSpacing = spacing; applySettings(); }
    public void setFirstLineIndent(boolean enable) { this.enableFirstLineIndent = enable; applySettings(); }

    public void setHorizontalMargin(int dp) {
        int px = (int)(dp * density + 0.5f);
        this.paddingLeft = px;
        this.paddingRight = (int)((dp + 4f) * density + 0.5f);
        applySettings();
    }

    public void beginBatchUpdate() { batchMode = true; }

    public void commitBatchUpdate() {
        batchMode = false;
        applySettings();
    }

    public void setCustomTypeface(Typeface tf) {
        this.typeface = (tf != null) ? tf : Typeface.DEFAULT;
        textPaint.setTypeface(this.typeface);
        applySettings();
    }

    public void setNightMode(boolean night) {
        if (night) {
            bgColor = 0xFF000000; fgColor = 0xFFBBBBBB; mutedColor = 0xFF555555;
        } else {
            bgColor = Color.WHITE; fgColor = Color.BLACK; mutedColor = 0xFF999999;
        }
        setBackgroundColor(bgColor);
        textPaint.setColor(fgColor);
        pageNumPaint.setColor(mutedColor);
        if (!batchMode) invalidate();
    }

    public void setChapterImages(Map<String, byte[]> images) {
        for (Bitmap bmp : bitmapCache.snapshot().values()) {
            if (bmp != null && !bmp.isRecycled()) bmp.recycle();
        }
        bitmapCache.evictAll();
        this.chapterImages = images;
    }

    public void applySettings() {
        if (batchMode) return;
        if (currentChapter != null) scheduleLayout();
    }

    // ==================== 设置篇章 ====================

    /** 设置布局完成后要跳转的目标页，-1 表示不跳转（用于翻到上一章末尾） */
    public void setPendingTargetPage(int page) {
        this.mPendingTargetPage = page;
    }

    public void setChapter(Chapter chapter) {
        this.currentChapter = chapter;
        this.currentPage = 0;
        // ★ 立即清空旧页面数据，防止异步分页完成前绘制旧章内容
        pages.clear();
        totalPages = 0;
        for (Bitmap bmp : bitmapCache.snapshot().values()) {
            if (bmp != null && !bmp.isRecycled()) bmp.recycle();
        }
        bitmapCache.evictAll();
        scheduleLayout();
        // 触发立即重绘，onDraw 会显示"排版中..."，而不是旧章内容
        invalidate();
    }

    // ==================== 后台分页调度 ====================

    /**
     * ★ 在主线程捕获所有布局参数，传入后台线程执行分页
     */
    private void scheduleLayout() {
            layoutPending = false;
            // ---- 以下是主线程安全捕获的所有值 ----
        final int capWidth = getWidth();
        final int capHeight = getHeight();
        final float capDensity = this.density;
        final int capPadL = paddingLeft, capPadR = paddingRight;
        final int capPadT = paddingTop, capPadB = paddingBottom;
        final boolean capIndent = enableFirstLineIndent;
        final float capTextSize = textSize;
        final float capLineSp = lineSpacing;
        final float capParaSp = paragraphSpacing;
        final Typeface capTypeface = typeface;
        final Chapter capChapter = currentChapter;
        final Map<String, byte[]> capImages = chapterImages;
        final int capCurPage = currentPage;
        final List<Page> capOldPages = pages;
        // ------------------------------------

        layoutVersion++;
        final int version = layoutVersion;
        backgroundHandler.removeCallbacksAndMessages(null);
        backgroundHandler.post(new Runnable() {
            @Override
            public void run() {
                doLayoutPages(version,
                        capWidth, capHeight, capDensity,
                        capPadL, capPadR, capPadT, capPadB,
                        capIndent, capTextSize, capLineSp, capParaSp,
                        capTypeface, capChapter, capImages,
                        capCurPage, capOldPages);
            }
        });
    }

    /**
     * ★ 后台线程执行分页计算
     * 所有需要用到的值都已作为参数传入，不读成员变量
     */
    private void doLayoutPages(final int version,
                               final int viewWidth, final int viewHeight,
                               final float dens,
                               final int padL, final int padR,
                               final int padT, final int padB,
                               final boolean indentEnabled,
                               final float ts, final float ls, final float ps,
                               final Typeface tf,
                               final Chapter chapter,
                               final Map<String, byte[]> images,
                               final int curPage,
                               final List<Page> oldPages) {
        if (version != layoutVersion) return;

        // ★ 保存指纹
        final String pageFingerprint;
        if (curPage > 0 && curPage < oldPages.size()) {
            Page cp = oldPages.get(curPage);
            if (cp != null && !cp.lines.isEmpty()) {
                String t = cp.lines.get(0).text;
                if (t != null && t.length() >= 4) {
                    pageFingerprint = t.substring(0, Math.min(24, t.length()));
                } else {
                    pageFingerprint = null;
                }
            } else {
                pageFingerprint = null;
            }
        } else {
            pageFingerprint = null;
        }

        if (chapter == null) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (version != layoutVersion) return;
                    pages.clear(); totalPages = 0;
                    invalidate(); notifyPageChanged();
                }
            });
            return;
        }

        if (viewWidth <= 0 || viewHeight <= 0) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (version != layoutVersion) return;
                    totalPages = 0;
                    invalidate(); notifyPageChanged();
                }
            });
            return;
        }

        int cw = viewWidth - padL - padR;
        int ch = viewHeight - padT - padB;
        if (cw <= 0 || ch <= 0) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (version != layoutVersion) return;
                    totalPages = 0;
                    invalidate(); notifyPageChanged();
                }
            });
            return;
        }

        final int contentWidth = cw;
        final int contentHeight = ch;

        // 获取内容
        String content = chapter.getContent();
        if (content == null) content = "";
        content = content.trim();
        if (content.isEmpty()) content = "(本章内容为空)";

        // 后台线程 Paint
        Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
        bp.setColor(fgColor);
        bp.setTypeface(tf);
        bp.setTextAlign(Paint.Align.LEFT);
        bp.setTextSize(ts * dens);
        Paint.FontMetrics bpFm = bp.getFontMetrics();
        float lineHeight = (float) Math.ceil(bpFm.descent - bpFm.ascent) * ls;
        float paraSpacingPx = ts * dens * (ps - ls);

        String[] paragraphs = content.split("\\n", -1);
        List<Integer> paraTypes = chapter.getParagraphTypes();

        final List<Page> resultPages = new ArrayList<Page>();
        Page curPd = new Page();
        float y = padT;

        for (int pi = 0; pi < paragraphs.length; pi++) {
            String para = paragraphs[pi];
            String trimmed = para.trim();

            int paraType = Chapter.PARA_NORMAL;
            if (paraTypes != null && pi < paraTypes.size()) {
                paraType = paraTypes.get(pi);
            }

            float paraTS = ts, paraLS = ls, paraExtra = paraSpacingPx;
            boolean centered = false, bold = false;
            float firstIndent = indentEnabled ? paraTS * dens * 2 : 0;

            switch (paraType) {
                case Chapter.PARA_H1:
                    paraTS = ts * 1.8f; paraLS = 1.2f;
                    paraExtra = ts * dens * 1.5f; centered = true; bold = true;
                    break;
                case Chapter.PARA_H2:
                    paraTS = ts * 1.5f; bold = true;
                    paraExtra = ts * dens * 1.2f;
                    break;
                case Chapter.PARA_H3:
                    paraTS = ts * 1.2f; bold = true;
                    break;
                case Chapter.PARA_BLOCKQUOTE:
                    paraTS = ts * 0.9f;
                    break;
            }

            bp.setTextSize(paraTS * dens);
            bp.setFakeBoldText(bold);
            Paint.FontMetrics fm = bp.getFontMetrics();
            float lineH = (float) Math.ceil(fm.descent - fm.ascent) * paraLS;

            // 图片标记
            if (trimmed.startsWith("[[IMAGE:") && trimmed.endsWith("]]")) {
                String imgPath = trimmed.substring(8, trimmed.length() - 2).trim();
                int imgW = contentWidth;
                int imgH = contentHeight / 2;
                // 检查图片原始宽高比（从字节数据解码后缩放）
                if (images != null) {
                    byte[] imgData = images.get(imgPath);
                    if (imgData != null) {
                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        opts.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(imgData, 0, imgData.length, opts);
                        if (opts.outWidth > 0 && opts.outHeight > 0) {
                            float scale = (float) contentWidth / opts.outWidth;
                            imgW = contentWidth;
                            imgH = (int) (opts.outHeight * scale);
                            if (imgH > contentHeight / 2) {
                                scale = (float) (contentHeight / 2) / opts.outHeight;
                                imgW = (int) (opts.outWidth * scale);
                                imgH = contentHeight / 2;
                            }
                        }
                    }
                }

                if (y + imgH > padT + contentHeight) {
                    resultPages.add(curPd);
                    curPd = new Page();
                    y = padT;
                }
                int imgX = padL + (contentWidth - imgW) / 2;
                curPd.images.add(new ImageBlock(imgPath,
                        new Rect(imgX, (int) y, imgX + imgW, (int) (y + imgH))));
                y += imgH + paraSpacingPx;
                continue;
            }

            // 空行
            if (trimmed.isEmpty()) {
                y += lineH;
                continue;
            }

            // 引用缩进
            int actCW = contentWidth;
            float actPL = padL;
            if (paraType == Chapter.PARA_BLOCKQUOTE) {
                int indent = (int)(ts * dens * 2);
                if (indent * 2 < contentWidth) {
                    actCW = contentWidth - indent * 2;
                    actPL = padL + indent;
                }
            }

            // 首行缩进
            boolean hasIndent = (firstIndent > 0 && paraType == Chapter.PARA_NORMAL);
            String remaining = trimmed;
            if (hasIndent && !centered) {
                int indentPx = (int) firstIndent;
                if (indentPx < actCW - Constants.MIN_INDENT_AVAILABLE_WIDTH_PX) {
                    List<String> firstOnly = wrapText(trimmed, actCW - indentPx, bp);
                    if (!firstOnly.isEmpty()) {
                        String firstLine = firstOnly.get(0);
                        if (firstLine.length() < trimmed.length()) {
                            remaining = trimmed.substring(firstLine.length());
                        } else {
                            remaining = "";
                        }
                        if (y + lineH > padT + contentHeight) {
                            resultPages.add(curPd);
                            curPd = new Page();
                            y = padT;
                        }
                        curPd.lines.add(new TextLine(firstLine, actPL + indentPx,
                                y + (float)Math.ceil(-bpFm.ascent), paraTS, bold));
                        y += lineH;
                    }
                }
            }

            if (!remaining.isEmpty()) {
                List<String> wrappedLines = wrapText(remaining, actCW, bp);
                for (int li = 0; li < wrappedLines.size(); li++) {
                    String line = wrappedLines.get(li);
                    if (y + lineH > padT + contentHeight) {
                        resultPages.add(curPd);
                        curPd = new Page();
                        y = padT;
                    }
                    float x = actPL;
                    if (centered) {
                        float lw = bp.measureText(line);
                        x = (viewWidth - lw) / 2f;
                    }
                    curPd.lines.add(new TextLine(line, x,
                            y + (float)Math.ceil(-bpFm.ascent), paraTS, bold));
                    y += lineH;
                }
            }

            y += paraExtra;
        }

        // 最后一页
        if (!curPd.lines.isEmpty() || !curPd.images.isEmpty()) {
            resultPages.add(curPd);
        }

        final int total = resultPages.size();
        final String fp = pageFingerprint;
        final int fVersion = version;

        // post 回主线程
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (fVersion != layoutVersion) return;

                pages.clear();
                pages.addAll(resultPages);
                totalPages = total;
                if (totalPages == 0) {
                    pages.add(new Page());
                    totalPages = 1;
                }

                // 处理待跳转的目标页（优先于指纹恢复）
                if (mPendingTargetPage >= 0 && mPendingTargetPage < totalPages) {
                    currentPage = mPendingTargetPage;
                    mPendingTargetPage = -1;
                } else if (mPendingTargetPage == Integer.MAX_VALUE) {
                    // 特殊值：跳到最后一页（用于回到上一章末尾）
                    currentPage = Math.max(0, totalPages - 1);
                    mPendingTargetPage = -1;
                } else {
                    mPendingTargetPage = -1;
                    // 指纹恢复
                    if (fp != null) {
                        int found = -1;
                        for (int i = 0; i < pages.size(); i++) {
                            Page p = pages.get(i);
                            if (p != null && !p.lines.isEmpty()) {
                                String t = p.lines.get(0).text;
                                if (t != null && t.startsWith(fp)) {
                                    found = i;
                                    break;
                                }
                            }
                        }
                        if (found >= 0) {
                            currentPage = found;
                        }
                    }
                }
                if (currentPage >= totalPages) {
                    currentPage = Math.max(0, totalPages - 1);
                }

                // ★ 图片解码已移至翻页方法，但首次布局后仍需解码第一页
                                decodeCurrentPageImages();
                                invalidate();
                                notifyPageChanged();
            }
        });
    }

    // ==================== 文字自动换行 ====================

    private static final float TEXT_SAFETY_MARGIN = 3.0f;

    private float mcw(char c, Paint p) {
        char[] buf = {c};
        return p.measureText(buf, 0, 1);
    }

    private float msw(String s, Paint p) {
        if (s == null || s.isEmpty()) return 0f;
        return p.measureText(s);
    }

    private List<String> wrapText(String text, int maxW, Paint p) {
        List<String> lines = new ArrayList<String>();
        if (text == null || text.isEmpty()) { lines.add(""); return lines; }

        boolean isCjk = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF) || (c >= 0x3040 && c <= 0x30FF)) {
                isCjk = true; break;
            } else if (Character.isLetterOrDigit(c)) { break; }
        }

        StringBuilder cl = new StringBuilder();
        if (!isCjk) {
            String[] words = text.split(" ", -1);
            for (String word : words) {
                float ww = msw(word, p);
                if (ww + TEXT_SAFETY_MARGIN > maxW) {
                    if (cl.length() > 0) { lines.add(cl.toString()); cl.setLength(0); }
                    for (int ci = 0; ci < word.length(); ci++) {
                        char c = word.charAt(ci);
                        cl.append(c);
                        if (msw(cl.toString(), p) + TEXT_SAFETY_MARGIN > maxW && cl.length() > 1) {
                            cl.deleteCharAt(cl.length() - 1);
                            lines.add(cl.toString());
                            cl.setLength(0);
                            cl.append(c);
                        }
                    }
                    continue;
                }
                String tryL = cl.length() > 0 ? cl.toString() + " " + word : word;
                if (msw(tryL, p) + TEXT_SAFETY_MARGIN > maxW && cl.length() > 0) {
                    lines.add(cl.toString()); cl.setLength(0); cl.append(word);
                } else {
                    cl.setLength(0); cl.append(tryL);
                }
            }
        } else {
            float cw = 0f;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                float cw1 = mcw(c, p);
                if (cw + cw1 + TEXT_SAFETY_MARGIN > maxW && cl.length() > 0) {
                    lines.add(cl.toString()); cl.setLength(0); cw = 0f;
                }
                cl.append(c); cw += cw1;
            }
        }
        if (cl.length() > 0) lines.add(cl.toString());
        if (lines.isEmpty()) lines.add("");
        return lines;
    }

    // ==================== 绘图 ====================

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(bgColor);

        if (!isLayoutReady || getWidth() <= 0 || getHeight() <= 0) return;

        if (pages.isEmpty() || currentPage >= pages.size()) {
            if (currentChapter != null) {
                textPaint.setTextSize(textSize * density);
                textPaint.setColor(mutedColor);
                String msg = "排版中...";
                canvas.drawText(msg, (getWidth() - textPaint.measureText(msg)) / 2f, getHeight() / 2f, textPaint);
                textPaint.setColor(fgColor);
            }
            return;
        }

        Page page = pages.get(currentPage);
        textPaint.setColor(fgColor);
        textPaint.setTypeface(typeface);
        for (TextLine line : page.lines) {
            textPaint.setTextSize(line.fontSize * density);
            textPaint.setFakeBoldText(line.bold);
            canvas.drawText(line.text, line.x, line.y, textPaint);
        }

        for (ImageBlock img : page.images) {
            if (img.bitmap != null && !img.bitmap.isRecycled()) {
                canvas.drawRect(img.rect, imageBgPaint);
                canvas.drawBitmap(img.bitmap, null, img.rect, null);
            } else if (img.rect != null) {
                canvas.drawRect(img.rect, imageBgPaint);
            }
        }

        if (totalPages > 0) {
            pageNumPaint.setColor(mutedColor);
            canvas.drawText((currentPage + 1) + " / " + totalPages, getWidth() / 2f, getHeight() - 10, pageNumPaint);
        }
    }

    // ==================== 尺寸变化 ====================

    private volatile boolean layoutPending = false;
    private int layoutGeneration = 0;
    private int lastMeasuredW = 0, lastMeasuredH = 0;

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int w = getMeasuredWidth(), h = getMeasuredHeight();
        if (w > 0 && h > 0 && (w != lastMeasuredW || h != lastMeasuredH)) {
            lastMeasuredW = w; lastMeasuredH = h;
            isLayoutReady = true;
            if (currentChapter != null && !layoutPending) {
                layoutPending = true;
                final int gen = ++layoutGeneration;
                scheduleLayout();
            }
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        isLayoutReady = true;
        if (currentChapter != null && !layoutPending) {
            layoutPending = true;
            scheduleLayout();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        layoutVersion = Integer.MAX_VALUE;
        if (layoutThread != null) {
            layoutThread.quitSafely();
            layoutThread = null;
            backgroundHandler = null;
        }
    }

    // ==================== 触摸翻页 ====================

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        float x = event.getX(), y = event.getY(), width = getWidth();
        int zone = ZONE_MENU;
        if (width > 0) {
            if (x < width * 0.3f) zone = ZONE_PREV;
            else if (x > width * 0.7f) zone = ZONE_NEXT;
        }
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downX = x; downY = y; lastZone = zone; return true;
            case MotionEvent.ACTION_MOVE: return true;
            case MotionEvent.ACTION_CANCEL: return true;
            case MotionEvent.ACTION_UP:
                if (Math.abs(x - downX) > 25f || Math.abs(y - downY) > 25f) return true;
                int uz = (width > 0) ? zone : lastZone;
                long now = System.currentTimeMillis();
                switch (uz) {
                    case ZONE_PREV: if (tryConsume(now)) prevPage(); break;
                    case ZONE_NEXT: if (tryConsume(now)) nextPage(); break;
                    case ZONE_MENU: default:
                        if (pageChangeListener != null) pageChangeListener.onTapCenter();
                }
                return true;
        }
        return true;
    }

    private boolean tryConsume(long now) {
        if (now - lastPageChangeTime < PAGE_DEBOUNCE_MS) return false;
        lastPageChangeTime = now;
        return true;
    }

    // ==================== 翻页 ====================

    public boolean prevPage() {
            if (currentPage > 0) { currentPage--; invalidate(); notifyPageChanged(); decodeCurrentPageImages(); return true; }
        if (pageChangeListener != null) pageChangeListener.onNeedPrevChapter();
        return false;
    }

    public boolean nextPage() {
            if (currentPage < totalPages - 1) { currentPage++; invalidate(); notifyPageChanged(); decodeCurrentPageImages(); return true; }
        if (pageChangeListener != null) pageChangeListener.onNeedNextChapter();
        return false;
    }

    public void goToPage(int p) {
            if (p >= 0 && p < totalPages) { currentPage = p; invalidate(); notifyPageChanged(); decodeCurrentPageImages(); }
        }

        public void goToPageSafe(int p) {
            if (totalPages <= 0) return;
            if (p < 0) p = 0;
            if (p >= totalPages) p = totalPages - 1;
            currentPage = p; invalidate(); notifyPageChanged(); decodeCurrentPageImages();
        }

    public int getCurrentPage() { return currentPage; }
    public int getTotalPages() { return totalPages; }
    public void cancelFullRefresh() {}
        /**
         * ★ 中断正在进行的后台分页（Activity 被销毁时调用，避免 layout result 回调已销毁的 Activity）
         */
        public void cancelLayout() {
            layoutVersion = Integer.MAX_VALUE;
        }
        public void performFullRefresh() {
        if (getParent() instanceof View) ((View) getParent()).postInvalidate();
        invalidate();
    }
    public void setOnPageChangeListener(OnPageChangeListener l) { this.pageChangeListener = l; }

        private void notifyPageChanged() {
            if (pageChangeListener != null) pageChangeListener.onPageChanged(currentPage, totalPages);
        }

        /** 在 UI 线程解码当前页的图片（懒解码） */
        private void decodeCurrentPageImages() {
            if (chapterImages == null || currentPage >= pages.size()) return;
            Page p = pages.get(currentPage);
            for (int i = 0; i < p.images.size(); i++) {
                ImageBlock ib = p.images.get(i);
                if (ib.bitmap != null || ib.path == null) continue;
                byte[] imgData = chapterImages.get(ib.path);
                if (imgData == null) continue;
                Bitmap bmp = BitmapFactory.decodeByteArray(imgData, 0, imgData.length);
                if (bmp == null) continue;
                int rw = ib.rect.width();
                int rh = ib.rect.height();
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, rw, rh, true);
                if (scaled != bmp) bmp.recycle();
                ib.bitmap = scaled;
                bitmapCache.put("img_" + System.nanoTime(), scaled);
            }
        }

        public void simulateLeftTap() {
            if (currentPage > 0) prevPage();
            else if (pageChangeListener != null) pageChangeListener.onNeedPrevChapter();
        }

        public void simulateRightTap() {
            if (currentPage < totalPages - 1) nextPage();
            else if (pageChangeListener != null) pageChangeListener.onNeedNextChapter();
        }
    }