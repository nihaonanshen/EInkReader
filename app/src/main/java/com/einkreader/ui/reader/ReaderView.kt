package com.einkreader.ui.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.AttributeSet
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import com.einkreader.core.Constants
import com.einkreader.core.FeatureFlags
import com.einkreader.core.NativeBridge
import com.einkreader.core.model.Chapter
import com.einkreader.core.refresh.EinkRefreshManager
import com.einkreader.ui.reader.DebugLog.log
import kotlin.jvm.Volatile
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * ReaderView —— 墨水屏阅读器的核心自绘控件
 * 
 * ★ v2: 分页计算在后台 HandlerThread 执行（主线程捕获尺寸后传入，不阻塞 UI）
 * 使用 layoutVersion 递增丢弃过期结果
 */
class ReaderView : View {
    // ==================== 排版设置 ====================
    private var textSize = 26f
    private var lineSpacing = 1.4f
    private var paragraphSpacing = 1.0f
    private var typeface: Typeface? = Typeface.DEFAULT
    private var bgColor = Color.WHITE

    @Volatile
    private var fgColor = Color.BLACK
    private var mutedColor = -0x666667
    private var paddingLeft = 20
    private var paddingRight = 20
    private var paddingTop = 16
    private var paddingBottom = 16

    // ==================== 分页数据 ====================
    private val pages: MutableList<Page> = ArrayList<Page>()
    var currentPage: Int = 0
        private set
    private var currentChapter: Chapter? = null
    var totalPages: Int = 0
        private set

    // ==================== 图片数据 ====================
    private var chapterImages: MutableMap<String?, ByteArray?>? = null
    private var bitmapCache: LruCache<String?, Bitmap?>? = null

    // ==================== 后台分页线程 ====================
    private var layoutThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var layoutVersion = 0

    // ==================== 监听器 ====================
    private var pageChangeListener: OnPageChangeListener? = null
    private var isLayoutReady = false
    private var enableFirstLineIndent = false
    private var batchMode = false

    // ==================== E Ink 刷新管理 ====================
    private var refreshManager: EinkRefreshManager? = null

    // ==================== 绘图工具 ====================
    private var textPaint: Paint? = null
    private var imageBgPaint: Paint? = null
    private var pageNumPaint: Paint? = null
    private var density = 0f

    // ==================== 防抖 ====================
    private var lastPageChangeTime: Long = 0
    private var downX = 0f
    private var downY = 0f
    private var lastZone: Int = ZONE_MENU

    // ==================== 分页数据模型 ====================
    internal class Page {
        var lines: MutableList<TextLine> = ArrayList<TextLine>()
        var images: MutableList<ImageBlock> = ArrayList<ImageBlock>()
    }

    internal class TextLine(
        var text: String,
        var x: Float,
        var y: Float,
        var fontSize: Float,
        var bold: Boolean
    )

    internal class ImageBlock {
        var bitmap: Bitmap?
        var rect: Rect?
        var path: String? = null // 图片路径，用于后台→UI 线程传递

        constructor(bitmap: Bitmap?, rect: Rect?) {
            this.bitmap = bitmap
            this.rect = rect
        }

        constructor(path: String?, rect: Rect?) {
            this.path = path
            this.rect = rect
            this.bitmap = null
        }
    }

    // ==================== 监听器接口 ====================
    interface OnPageChangeListener {
        fun onPageChanged(pageIndex: Int, totalPages: Int)
        fun onChapterChanged(chapterIndex: Int)
        fun onTapCenter()
        fun onNeedPrevChapter()
        fun onNeedNextChapter()
    }

    // ==================== 构造 ====================
    constructor(context: Context?) : super(context) {
        init()
    }

    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs) {
        init()
    }

    private fun init() {
        // bitmapCache 在 init() 中初始化，保证单参/双参构造都可用（原实现仅双参构造初始化）
        bitmapCache = object : LruCache<String?, Bitmap?>(MAX_BITMAP_CACHE_BYTES) {
            override fun sizeOf(key: String?, value: Bitmap?): Int {
                return value?.getByteCount() ?: 0
            }
        }
        setBackgroundColor(bgColor)
        density = getResources().getDisplayMetrics().density
        log("Init", "density=" + density)

        val defaultDp = 10f
        val rightDp = 14f
        paddingLeft = (defaultDp * density + 0.5f).toInt()
        paddingRight = (rightDp * density + 0.5f).toInt()
        paddingTop = (defaultDp * density + 0.5f).toInt()
        paddingBottom = (defaultDp * density + 0.5f).toInt()

        textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        textPaint!!.setColor(fgColor)
        textPaint!!.setTypeface(typeface)
        textPaint!!.setTextAlign(Paint.Align.LEFT)

        imageBgPaint = Paint()
        imageBgPaint!!.setColor(-0xf0f10)

        pageNumPaint = Paint()
        pageNumPaint!!.setColor(mutedColor)
        pageNumPaint!!.setTextSize(Constants.PAGE_NUMBER_TEXT_SIZE_SP * density)
        pageNumPaint!!.setTextAlign(Paint.Align.CENTER)

        layoutThread = HandlerThread("layout-bg")
        layoutThread!!.start()
        backgroundHandler = Handler(layoutThread!!.getLooper())

        refreshManager = EinkRefreshManager(getContext())
    }

    // ==================== 智能刷新（E Ink 模式选择） ====================
    /**
     * 根据翻页方向和缓存命中状态，通知 EinkRefreshManager 执行智能刷新
     * 
     * @param isForward true=向前翻页, false=向后翻页
     */
    private fun triggerSmartRefresh(isForward: Boolean) {
        if (refreshManager == null) {
            invalidate()
            return
        }
        val area = Rect()
        getDrawingRect(area)
        var cacheHit = false
        if (currentChapter != null && currentChapter!!.content != null) {
            try {
                val text = currentChapter!!.content
                val w = getWidth()
                val h = getHeight()
                cacheHit = try {
                    NativeBridge.bridgeInstance.isLayoutCachedInternal(
                        text, w.toFloat(), h.toFloat(),
                        textSize, lineSpacing, paragraphSpacing,
                        enableFirstLineIndent, paddingLeft.toFloat(), paddingTop.toFloat()
                    )
                } catch (e: Exception) {
                    false
                }
            } catch (e: Exception) {
                cacheHit = false
            }
        }
        refreshManager!!.requestSmartRefresh(this, area, isForward, cacheHit)
    }

    // ==================== 公开设置方法 ====================
    fun setTextSize(sp: Float) {
        if (this.textSize == sp) return
        this.textSize = sp
        applySettings()
    }

    fun setLineSpacing(spacing: Float) {
        if (this.lineSpacing == spacing) return
        this.lineSpacing = spacing
        applySettings()
    }

    fun setParagraphSpacing(spacing: Float) {
        if (this.paragraphSpacing == spacing) return
        this.paragraphSpacing = spacing
        applySettings()
    }

    fun setFirstLineIndent(enable: Boolean) {
        if (this.enableFirstLineIndent == enable) return
        this.enableFirstLineIndent = enable
        applySettings()
    }

    fun setHorizontalMargin(dp: Int) {
        val px = (dp * density + 0.5f).toInt()
        val pr = ((dp + 4f) * density + 0.5f).toInt()
        if (this.paddingLeft == px && this.paddingRight == pr) return
        this.paddingLeft = px
        this.paddingRight = pr
        applySettings()
    }

    fun beginBatchUpdate() {
        batchMode = true
    }

    fun commitBatchUpdate() {
        batchMode = false
        applySettings()
    }

    fun setCustomTypeface(tf: Typeface?) {
        val newTf = if (tf != null) tf else Typeface.DEFAULT
        if (this.typeface == newTf) return
        this.typeface = newTf
        checkNotNull(textPaint).setTypeface(this.typeface)
        applySettings()
    }

    fun setNightMode(night: Boolean) {
        if (night) {
            bgColor = -0x1000000
            fgColor = -0x444445
            mutedColor = -0xaaaaab
        } else {
            bgColor = Color.WHITE
            fgColor = Color.BLACK
            mutedColor = -0x666667
        }
        setBackgroundColor(bgColor)
        checkNotNull(textPaint).setColor(fgColor)
        checkNotNull(pageNumPaint).setColor(mutedColor)
        if (!batchMode) invalidate()
    }

    // ==================== 设置篇章 ====================

    fun setChapterImages(images: MutableMap<String?, ByteArray?>?) {
        for (bmp in bitmapCache!!.snapshot().values) {
            if (bmp != null && !bmp.isRecycled()) bmp.recycle()
        }
        bitmapCache!!.evictAll()
        this.chapterImages = images
    }

    fun applySettings() {
        if (batchMode) return
        if (currentChapter != null) scheduleLayout()
    }

    // ==================== 设置篇章 ====================
    fun setChapter(chapter: Chapter?) {
        this.currentChapter = chapter
        this.currentPage = 0
        for (bmp in bitmapCache!!.snapshot().values) {
            if (bmp != null && !bmp.isRecycled()) bmp.recycle()
        }
        bitmapCache!!.evictAll()
        scheduleLayout()
    }

    // ==================== 后台分页调度 ====================
    /**
     * ★ 在主线程捕获所有布局参数，传入后台线程执行分页
     */
    private fun scheduleLayout() {
        layoutPending = false
        // ---- 以下是主线程安全捕获的所有值 ----
        val capWidth = getWidth()
        val capHeight = getHeight()
        val capDensity = this.density
        val capPadL = paddingLeft
        val capPadR = paddingRight
        val capPadT = paddingTop
        val capPadB = paddingBottom
        val capIndent = enableFirstLineIndent
        val capTextSize = textSize
        val capLineSp = lineSpacing
        val capParaSp = paragraphSpacing
        val capTypeface = typeface
        val capChapter = currentChapter
        val capImages = chapterImages
        val capCurPage = currentPage
        val capOldPages = pages

        // ------------------------------------
        layoutVersion++
        val version = layoutVersion
        backgroundHandler!!.removeCallbacksAndMessages(null)
        backgroundHandler!!.post(object : Runnable {
            override fun run() {
                doLayoutPages(
                    version,
                    capWidth, capHeight, capDensity,
                    capPadL, capPadR, capPadT, capPadB,
                    capIndent, capTextSize, capLineSp, capParaSp,
                    capTypeface, capChapter, capImages,
                    capCurPage, capOldPages
                )
            }
        })
    }

    /**
     * ★ 后台线程执行分页计算
     * 所有需要用到的值都已作为参数传入，不读成员变量
     */
    private fun doLayoutPages(
        version: Int,
        viewWidth: Int, viewHeight: Int,
        dens: Float,
        padL: Int, padR: Int,
        padT: Int, padB: Int,
        indentEnabled: Boolean,
        ts: Float, ls: Float, ps: Float,
        tf: Typeface?,
        chapter: Chapter?,
        images: MutableMap<String?, ByteArray?>?,
        curPage: Int,
        oldPages: MutableList<Page>
    ) {
        if (version != layoutVersion) return

        // ★ 保存指纹
        val pageFingerprint: String?
        if (curPage > 0 && curPage < oldPages.size) {
            val cp: Page? = oldPages.get(curPage)
            if (cp != null && !cp.lines.isEmpty()) {
                val t = cp.lines.get(0).text
                if (t != null && t.length >= 4) {
                    pageFingerprint = t.substring(0, min(24, t.length))
                } else {
                    pageFingerprint = null
                }
            } else {
                pageFingerprint = null
            }
        } else {
            pageFingerprint = null
        }

        if (chapter == null) {
            mainHandler.post(object : Runnable {
                override fun run() {
                    if (version != layoutVersion) return
                    pages.clear()
                    totalPages = 0
                    invalidate()
                    notifyPageChanged()
                }
            })
            return
        }

        if (viewWidth <= 0 || viewHeight <= 0) {
            mainHandler.post(object : Runnable {
                override fun run() {
                    if (version != layoutVersion) return
                    totalPages = 0
                    invalidate()
                    notifyPageChanged()
                }
            })
            return
        }

        val cw = viewWidth - padL - padR
        val ch = viewHeight - padT - padB
        if (cw <= 0 || ch <= 0) {
            mainHandler.post(object : Runnable {
                override fun run() {
                    if (version != layoutVersion) return
                    totalPages = 0
                    invalidate()
                    notifyPageChanged()
                }
            })
            return
        }

        val contentWidth = cw
        val contentHeight = ch

        // 获取内容
        var content = chapter.content
        if (content == null) content = ""
        content = content.trim { it <= ' ' }
        if (content.isEmpty()) content = "(本章内容为空)"

        // ===== Rust 布局分支（二进制路径，直接使用精确坐标） =====
        if (FeatureFlags.USE_RUST_LAYOUT) {
            val rustResult: NativeBridge.LayoutResult = NativeBridge.bridgeInstance.layoutTextBinary(
                content, contentWidth.toFloat(), contentHeight.toFloat(),
                ts * dens, ls, ps, indentEnabled, padL.toFloat(), padT.toFloat()
            )
            if (rustResult != null && rustResult.totalPages > 0) {
                val rustPages: MutableList<Page?> = ArrayList<Page?>(rustResult.pages.size)
                for (pi in rustResult.pages.indices) {
                    val pd = rustResult.pages.get(pi)
                    val page = Page()
                    for (lm in pd.lines) {
                        page.lines.add(
                            ReaderView.TextLine(
                                lm.text ?: "",
                                lm.x,  // Rust 精确 x 坐标（像素）
                                lm.y,  // Rust 精确 y 坐标（像素）
                                ts,  // fontSize (sp)
                                false // bold flag
                            )
                        )
                    }
                    rustPages.add(page)
                }

                val total = rustPages.size
                val fp = pageFingerprint
                val fVersion = version
                mainHandler.post(object : Runnable {
                    override fun run() {
                        if (fVersion != layoutVersion) return
                        pages.clear()
                        pages.addAll(rustPages.filterNotNull())
                        totalPages = total
                        if (totalPages == 0) {
                            pages.add(Page())
                            totalPages = 1
                        }
                        if (fp != null) {
                            var found = -1
                            for (i in pages.indices) {
                                val p = pages.get(i)
                                if (p != null && !p.lines.isEmpty()) {
                                    val t = p.lines.get(0).text
                                    if (t != null && t.startsWith(fp)) {
                                        found = i
                                        break
                                    }
                                }
                            }
                            if (found >= 0) currentPage = found
                            else if (currentPage >= totalPages) currentPage = max(0, totalPages - 1)
                        }
                        decodeCurrentPageImages()
                        invalidate()
                        notifyPageChanged()
                        log(
                            "RustLayout",
                            "totalPages=" + totalPages + " elapsedNs=" + rustResult.elapsedNs
                        )
                    }
                })
                return
            }
        }

        // ===== 以上 Rust 分支失败时，自动 fall through 到 Java 实现 =====

        log("Image", "Java fallback layout: chapter=${chapter != null}, images=${images != null}, images.size=${images?.size}, content.length=${content.length}")

        // 后台线程 Paint
        val bp = Paint(Paint.ANTI_ALIAS_FLAG)
        bp.setColor(fgColor)
        bp.setTypeface(tf)
        bp.setTextAlign(Paint.Align.LEFT)
        bp.setTextSize(ts * dens)
        val bpFm = bp.getFontMetrics()
        val lineHeight = ceil((bpFm.descent - bpFm.ascent).toDouble()).toFloat() * ls
        // 段距为绝对增量：段距 × 字体大小（独立于行距）
        val paraSpacingPx = ts * dens * ps

        val paragraphs = content.split("\\n".toRegex()).toTypedArray()
        val paraTypes: MutableList<Int?> = chapter.paragraphTypes.toMutableList()

        val resultPages: MutableList<Page?> = ArrayList<Page?>()
        var curPd = Page()
        var y = padT.toFloat()

        for (pi in paragraphs.indices) {
            val para = paragraphs[pi]
            val trimmed = para.trim { it <= ' ' }

            var paraType = Chapter.PARA_NORMAL
            if (paraTypes != null && pi < paraTypes.size) {
                paraType = paraTypes.get(pi)!!
            }

            var paraTS = ts
            var paraLS = ls
            var paraExtra = paraSpacingPx
            var centered = false
            var bold = false
            val firstIndent = if (indentEnabled) paraTS * dens else 0f

            when (paraType) {
                Chapter.PARA_H1 -> {
                    paraTS = ts * 1.8f
                    paraLS = 1.2f
                    paraExtra = ts * dens * 1.5f
                    centered = true
                    bold = true
                }

                Chapter.PARA_H2 -> {
                    paraTS = ts * 1.5f
                    bold = true
                    paraExtra = ts * dens * 1.2f
                }

                Chapter.PARA_H3 -> {
                    paraTS = ts * 1.2f
                    bold = true
                }

                Chapter.PARA_BLOCKQUOTE -> paraTS = ts * 0.9f
            }

            bp.setTextSize(paraTS * dens)
            bp.setFakeBoldText(bold)
            val fm = bp.getFontMetrics()
            val lineH = ceil((fm.descent - fm.ascent).toDouble()).toFloat() * paraLS

            // 图片标记
            if (trimmed.startsWith("[[IMAGE:") && trimmed.endsWith("]]")) {
                val imgPath = trimmed.substring(8, trimmed.length - 2).trim { it <= ' ' }
                log("Image", "找到图片标记: $imgPath, images.size=${images?.size}, chapterImages=${chapterImages != null}")
                // 打印 content 中的图片标记
                val imageMarkers = content.lines().filter { it.startsWith("[[IMAGE:") }.map { it.substringBefore("]]") + "]]" }.toSet()
                log("Image", "content 中的图片标记: $imageMarkers")
                var imgW = contentWidth
                var imgH = contentHeight / 2
                // 检查图片原始宽高比（从字节数据解码后缩放）
                if (images != null) {
                    val imgData = images.get(imgPath)
                    log("Image", "查找图片: $imgPath, 找到=${imgData != null}, 大小=${imgData?.size}")
                    if (imgData != null) {
                        val opts = BitmapFactory.Options()
                        opts.inJustDecodeBounds = true
                        BitmapFactory.decodeByteArray(imgData, 0, imgData.size, opts)
                        log("Image", "解码图片: width=${opts.outWidth}, height=${opts.outHeight}")
                        if (opts.outWidth > 0 && opts.outHeight > 0) {
                            var scale = contentWidth.toFloat() / opts.outWidth
                            imgW = contentWidth
                            imgH = (opts.outHeight * scale).toInt()
                            if (imgH > contentHeight / 2) {
                                scale = (contentHeight / 2).toFloat() / opts.outHeight
                                imgW = (opts.outWidth * scale).toInt()
                                imgH = contentHeight / 2
                            }
                        }
                    } else {
                        // 打印所有可用的图片路径
                        log("Image", "可用图片路径: ${images?.keys?.joinToString(", ")}")
                    }
                }

                if (y + imgH > padT + contentHeight) {
                    resultPages.add(curPd)
                    curPd = Page()
                    y = padT.toFloat()
                }
                val imgX = padL + (contentWidth - imgW) / 2
                curPd.images.add(
                    ImageBlock(
                        imgPath,
                        Rect(imgX, y.toInt(), imgX + imgW, (y + imgH).toInt())
                    )
                )
                y += imgH + paraSpacingPx
                continue
            }

            // 空行
            if (trimmed.isEmpty()) {
                y += lineH
                continue
            }

            // 引用缩进
            var actCW = contentWidth
            var actPL = padL.toFloat()
            if (paraType == Chapter.PARA_BLOCKQUOTE) {
                val indent = (ts * dens * 2).toInt()
                if (indent * 2 < contentWidth) {
                    actCW = contentWidth - indent * 2
                    actPL = (padL + indent).toFloat()
                }
            }

            // 首行缩进
            val hasIndent = (firstIndent > 0 && paraType == Chapter.PARA_NORMAL)
            var remaining = trimmed
            if (hasIndent && !centered) {
                val indentPx = firstIndent.toInt()
                if (indentPx < actCW - Constants.MIN_INDENT_AVAILABLE_WIDTH_PX) {
                    val firstOnly = wrapText(trimmed, actCW - indentPx, bp)
                    if (!firstOnly.isEmpty()) {
                        val firstLine = firstOnly.get(0)
                        if (firstLine.length < trimmed.length) {
                            remaining = trimmed.substring(firstLine.length)
                        } else {
                            remaining = ""
                        }
                        if (y + lineH > padT + contentHeight) {
                            resultPages.add(curPd)
                            curPd = Page()
                            y = padT.toFloat()
                        }
                        curPd.lines.add(
                            TextLine(
                                firstLine, actPL + indentPx,
                                y + ceil(-bpFm.ascent.toDouble()).toFloat(), paraTS, bold
                            )
                        )
                        y += lineH
                    }
                }
            }

            if (!remaining.isEmpty()) {
                val wrappedLines = wrapText(remaining, actCW, bp)
                for (li in wrappedLines.indices) {
                    val line = wrappedLines.get(li)
                    if (y + lineH > padT + contentHeight) {
                        resultPages.add(curPd)
                        curPd = Page()
                        y = padT.toFloat()
                    }
                    var x = actPL
                    if (centered) {
                        val lw = bp.measureText(line)
                        x = (viewWidth - lw) / 2f
                    }
                    curPd.lines.add(
                        TextLine(
                            line, x,
                            y + ceil(-bpFm.ascent.toDouble()).toFloat(), paraTS, bold
                        )
                    )
                    y += lineH
                }
            }

            y += paraExtra
        }

        // 最后一页
        if (!curPd.lines.isEmpty() || !curPd.images.isEmpty()) {
            resultPages.add(curPd)
        }

        val total = resultPages.size
        val fp = pageFingerprint
        val fVersion = version

        // post 回主线程
        mainHandler.post(object : Runnable {
            override fun run() {
                if (fVersion != layoutVersion) return

                pages.clear()
                pages.addAll(resultPages.filterNotNull())
                totalPages = total
                if (totalPages == 0) {
                    pages.add(Page())
                    totalPages = 1
                }

                // 指纹恢复
                if (fp != null) {
                    var found = -1
                    for (i in pages.indices) {
                        val p = pages.get(i)
                        if (p != null && !p.lines.isEmpty()) {
                            val t = p.lines.get(0).text
                            if (t != null && t.startsWith(fp)) {
                                found = i
                                break
                            }
                        }
                    }
                    if (found >= 0) {
                        currentPage = found
                    } else if (currentPage >= totalPages) {
                        currentPage = max(0, totalPages - 1)
                    }
                }

                // ★ 图片解码已移至翻页方法，但首次布局后仍需解码第一页
                decodeCurrentPageImages()
                invalidate()
                notifyPageChanged()
            }
        })
    }

    private fun mcw(c: Char, p: Paint): Float {
        val buf = charArrayOf(c)
        return p.measureText(buf, 0, 1)
    }

    private fun msw(s: String?, p: Paint): Float {
        if (s == null || s.isEmpty()) return 0f
        return p.measureText(s)
    }

    private fun wrapText(text: String?, maxW: Int, p: Paint): MutableList<String> {
        val lines: MutableList<String> = ArrayList<String>()
        if (text == null || text.isEmpty()) {
            lines.add("")
            return lines
        }

        var isCjk = false
        for (i in 0..<text.length) {
            val c = text.get(i)
            if ((c.code >= 0x4E00 && c.code <= 0x9FFF) || (c.code >= 0x3400 && c.code <= 0x4DBF) || (c.code >= 0x3040 && c.code <= 0x30FF)) {
                isCjk = true
                break
            } else if (Character.isLetterOrDigit(c)) {
                break
            }
        }

        val cl = StringBuilder()
        if (!isCjk) {
            val words = text.split(" ".toRegex()).toTypedArray()
            for (word in words) {
                val ww = msw(word, p)
                if (ww + TEXT_SAFETY_MARGIN > maxW) {
                    if (cl.length > 0) {
                        lines.add(cl.toString())
                        cl.setLength(0)
                    }
                    for (ci in 0..<word.length) {
                        val c = word.get(ci)
                        cl.append(c)
                        if (msw(cl.toString(), p) + TEXT_SAFETY_MARGIN > maxW && cl.length > 1) {
                            cl.deleteCharAt(cl.length - 1)
                            lines.add(cl.toString())
                            cl.setLength(0)
                            cl.append(c)
                        }
                    }
                    continue
                }
                val tryL: String? = if (cl.length > 0) cl.toString() + " " + word else word
                if (msw(tryL, p) + TEXT_SAFETY_MARGIN > maxW && cl.length > 0) {
                    lines.add(cl.toString())
                    cl.setLength(0)
                    cl.append(word)
                } else {
                    cl.setLength(0)
                    cl.append(tryL)
                }
            }
        } else {
            var cw = 0f
            for (i in 0..<text.length) {
                val c = text.get(i)
                val cw1 = mcw(c, p)
                if (cw + cw1 + TEXT_SAFETY_MARGIN > maxW && cl.length > 0) {
                    lines.add(cl.toString())
                    cl.setLength(0)
                    cw = 0f
                }
                cl.append(c)
                cw += cw1
            }
        }
        if (cl.length > 0) lines.add(cl.toString())
        if (lines.isEmpty()) lines.add("")
        return lines
    }

    // ==================== 绘图 ====================
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(bgColor)

        if (!isLayoutReady || getWidth() <= 0 || getHeight() <= 0) return

        if (pages.isEmpty() || currentPage >= pages.size) {
            if (currentChapter != null) {
                textPaint!!.setTextSize(textSize * density)
                textPaint!!.setColor(mutedColor)
                val msg = "排版中..."
                canvas.drawText(
                    msg,
                    (getWidth() - textPaint!!.measureText(msg)) / 2f,
                    getHeight() / 2f,
                    textPaint!!
                )
                textPaint!!.setColor(fgColor)
            }
            return
        }

        val page = pages.get(currentPage)
        textPaint!!.setColor(fgColor)
        textPaint!!.setTypeface(typeface)
        for (line in page.lines) {
            textPaint!!.setTextSize(line.fontSize * density)
            textPaint!!.setFakeBoldText(line.bold)
            canvas.drawText(line.text, line.x, line.y, textPaint!!)
        }

        for (img in page.images) {
            if (img.bitmap != null && !img.bitmap!!.isRecycled()) {
                canvas.drawRect(img.rect!!, imageBgPaint!!)
                canvas.drawBitmap(img.bitmap!!, null, img.rect!!, null)
            } else if (img.rect != null) {
                canvas.drawRect(img.rect!!, imageBgPaint!!)
            }
        }

        if (totalPages > 0) {
            pageNumPaint!!.setColor(mutedColor)
            canvas.drawText(
                (currentPage + 1).toString() + " / " + totalPages,
                getWidth() / 2f,
                (getHeight() - 10).toFloat(),
                pageNumPaint!!
            )
        }
    }

    // ==================== 尺寸变化 ====================
    @Volatile
    private var layoutPending = false
    private var layoutGeneration = 0
    private var lastMeasuredW = 0
    private var lastMeasuredH = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = getMeasuredWidth()
        val h = getMeasuredHeight()
        if (w > 0 && h > 0 && (w != lastMeasuredW || h != lastMeasuredH)) {
            lastMeasuredW = w
            lastMeasuredH = h
            isLayoutReady = true
            if (currentChapter != null && !layoutPending) {
                layoutPending = true
                val gen = ++layoutGeneration
                scheduleLayout()
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        isLayoutReady = true
        if (currentChapter != null && !layoutPending) {
            layoutPending = true
            scheduleLayout()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        layoutVersion = Int.MAX_VALUE
        if (layoutThread != null) {
            layoutThread!!.quitSafely()
            layoutThread = null
            backgroundHandler = null
        }
    }

    // ==================== 触摸翻页 ====================
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val x = event.getX()
        val y = event.getY()
        val width = getWidth().toFloat()
        var zone: Int = ZONE_MENU
        if (width > 0) {
            if (x < width * 0.3f) zone = ZONE_PREV
            else if (x > width * 0.7f) zone = ZONE_NEXT
        }
        when (event.getAction()) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                lastZone = zone
                return true
            }

            MotionEvent.ACTION_MOVE -> return true
            MotionEvent.ACTION_CANCEL -> return true
            MotionEvent.ACTION_UP -> {
                if (abs(x - downX) > 25f || abs(y - downY) > 25f) return true
                val uz = if (width > 0) zone else lastZone
                val now = System.currentTimeMillis()
                when (uz) {
                    ZONE_PREV -> if (tryConsume(now)) prevPage()
                    ZONE_NEXT -> if (tryConsume(now)) nextPage()
                    ZONE_MENU -> if (pageChangeListener != null) pageChangeListener!!.onTapCenter()
                    else -> if (pageChangeListener != null) pageChangeListener!!.onTapCenter()
                }
                return true
            }
        }
        return true
    }

    private fun tryConsume(now: Long): Boolean {
        if (now - lastPageChangeTime < PAGE_DEBOUNCE_MS) return false
        lastPageChangeTime = now
        return true
    }

    // ==================== 翻页 ====================
    fun prevPage(): Boolean {
        if (currentPage > 0) {
            currentPage--
            triggerSmartRefresh(true)
            notifyPageChanged()
            decodeCurrentPageImages()
            return true
        }
        if (pageChangeListener != null) pageChangeListener!!.onNeedPrevChapter()
        return false
    }

    fun nextPage(): Boolean {
        if (currentPage < totalPages - 1) {
            currentPage++
            triggerSmartRefresh(false)
            notifyPageChanged()
            decodeCurrentPageImages()
            return true
        }
        if (pageChangeListener != null) pageChangeListener!!.onNeedNextChapter()
        return false
    }

    fun goToPage(p: Int) {
        if (p >= 0 && p < totalPages) {
            currentPage = p
            triggerSmartRefresh(false)
            notifyPageChanged()
            decodeCurrentPageImages()
        }
    }

    fun goToPageSafe(p: Int) {
        var p = p
        if (totalPages <= 0) return
        if (p < 0) p = 0
        if (p >= totalPages) p = totalPages - 1
        currentPage = p
        triggerSmartRefresh(false)
        notifyPageChanged()
        decodeCurrentPageImages()
    }

    fun cancelFullRefresh() {}

    /**
     * ★ 中断正在进行的后台分页（Activity 被销毁时调用，避免 layout result 回调已销毁的 Activity）
     */
    fun cancelLayout() {
        layoutVersion = Int.MAX_VALUE
    }

    fun performFullRefresh() {
        if (getParent() is View) (getParent() as View).postInvalidate()
        invalidate()
    }

    fun setOnPageChangeListener(l: OnPageChangeListener?) {
        this.pageChangeListener = l
    }

    private fun notifyPageChanged() {
        if (pageChangeListener != null) pageChangeListener!!.onPageChanged(currentPage, totalPages)
    }

    /** 在后台线程解码当前页的图片（懒解码） */
    /** 在调用线程解码当前页的图片 */
    private fun decodeCurrentPageImages() {
        log("Image", "decodeCurrentPageImages: chapterImages=${chapterImages != null}, size=${chapterImages?.size}, currentPage=$currentPage, totalPages=$totalPages, pages.size=${pages.size}")
        if (chapterImages == null || currentPage >= pages.size) return
        val p = pages.get(currentPage)
        log("Image", "页面图片数量: ${p.images.size}")
        var needsInvalidate = false
        for (i in p.images.indices) {
            val ib = p.images[i]
            log("Image", "ImageBlock[$i]: path=${ib.path}, bitmap=${ib.bitmap != null}")
            if (ib.bitmap != null || ib.path == null) continue
            val imgData = chapterImages?.get(ib.path)
            log("Image", "查找图片: ${ib.path}, 找到=${imgData != null}, 大小=${imgData?.size}")
            if (imgData == null) {
                // 打印所有可用的图片路径
                log("Image", "可用图片路径: ${chapterImages?.keys?.joinToString(", ")}")
                continue
            }
            val bmp = BitmapFactory.decodeByteArray(imgData, 0, imgData.size)
            log("Image", "解码结果: bmp=${bmp != null}")
            if (bmp == null) continue
            val rw = ib.rect?.width() ?: continue
            val rh = ib.rect?.height() ?: continue
            val scaled = Bitmap.createScaledBitmap(bmp, rw, rh, true)
            if (scaled != bmp) bmp.recycle()
            ib.bitmap = scaled
            bitmapCache?.put("img_" + (ib.path ?: System.identityHashCode(this)), scaled)
            needsInvalidate = true
        }
        log("Image", "invalidate=$needsInvalidate")
        if (needsInvalidate) invalidate()
    }

    companion object {
        private val MAX_BITMAP_CACHE_BYTES = 8 * 1024 * 1024

        // ==================== 点击区域 ====================
        private const val ZONE_PREV = 0
        private const val ZONE_NEXT = 1
        private const val ZONE_MENU = 2

        private const val PAGE_DEBOUNCE_MS = 250L

        // ==================== 文字自动换行 ====================
        private const val TEXT_SAFETY_MARGIN = 3.0f
    }
}

