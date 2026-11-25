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
    /** 正文文字黑度（0-100，100=纯黑；低值=灰色文字，E-ink 更柔和） */
    private var textBlackLevel = 100
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
    // [P3] 跨章节页码待恢复（书签跳转等）：切章后新章节未排版完成，页码先暂存、排版落地后消费
    private var pendingPage: Int = -1

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
    // 待执行的 EPDC 刷新（onDraw 绘制完成后消费）
    private var pendingRefreshMode: EinkRefreshManager.RefreshMode? = null
    private var pendingRefreshArea: android.graphics.Rect? = null

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
        var bold: Boolean,
        /** 两端对齐目标行宽（0=不 justify；绘制时按实际字体测量计算间距） */
        var justifyWidth: Float = 0f
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
        /** 双指缩放：delta=1 放大，-1 缩小 */
        fun onPinchZoom(delta: Int) {}
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
        // ★ E-ink 文字渲染优化：关闭原生 hinting（灰度渲染下 hinting 会强制像素对齐，
        //   低分辨率 E-ink 上可能造成字形边缘生硬；KOReader 默认 light/none）
        textPaint!!.setHinting(Paint.HINTING_OFF)
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
     * 根据翻页方向和缓存命中状态，计算刷新模式并安排在绘制完成后执行。
     * ★ 关键：EPDC 硬件刷新必须在 onDraw（framebuffer 更新）之后执行，
     *   否则刷出来的是旧帧（导致"点击多次才显示下一页"）。
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
        // ★ 局部刷新区域：限定在正文内容区（顶部让开状态栏、底部让开页码区），
        //   翻页时驱动只刷新该矩形，避免整屏闪烁；GC16 全刷时忽略此区域。
        val refreshPadTop = paddingTop + 60
        val refreshPadBottom = 80
        area.top = (area.top + refreshPadTop).coerceAtMost(area.bottom - 100)
        area.bottom = (area.bottom - refreshPadBottom).coerceAtLeast(area.top + 100)
        area.left += paddingLeft
        area.right -= paddingRight
        if (area.right <= area.left) area.right = area.left + 1
        if (area.bottom <= area.top) area.bottom = area.top + 1
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
        pendingRefreshArea = area
        pendingRefreshMode = refreshManager!!.computeRefreshMode(isForward, cacheHit)
        // 触发重绘：onDraw 完成后在 ReaderView 内部调用 performEpdcRefresh
        // ★ 刷新合帧：单次翻页立即刷新；0.3s 内快速连续翻页合并为一次刷新（显示最终页）
        requestRender()
    }

    // ★ 刷新合帧（借鉴 KOReader postInvalidateDelayed 思路）：
    //   快速连续翻页时合并 EPDC 刷新，减少刷新次数/残影/功耗。
    //   单次操作立即刷新（无迟滞感），连续操作推迟到停顿后统一刷新。
    private var lastRenderRequestTime = 0L
    private val RENDER_COALESCE_MS = 500L
    private val coalescedRenderRunnable = Runnable { invalidate() }

    private fun requestRender() {
        val now = System.currentTimeMillis()
        if (lastRenderRequestTime > 0 && now - lastRenderRequestTime < RENDER_COALESCE_MS) {
            // 快速连续操作：合并刷新，推迟到停顿后显示最终页
            removeCallbacks(coalescedRenderRunnable)
            postDelayed(coalescedRenderRunnable, RENDER_COALESCE_MS)
        } else {
            // 单次操作：立即刷新
            invalidate()
        }
        lastRenderRequestTime = now
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

    /**
     * 设置正文文字黑度（0-100，100=纯黑）。
     * 低对比度（灰色文字）在 E-ink 上更柔和（KOReader font_gamma 思路）。
     * 只改颜色不重排版：清页 Bitmap 缓存后重绘。
     */
    fun setTextContrast(blackLevel: Int) {
        val v = blackLevel.coerceIn(0, 100)
        if (v == this.textBlackLevel) return
        this.textBlackLevel = v
        // gray = 255 - 黑度比例，clamp 到 [30,255]（0=纯黑，避免调到纯白）
        val g = (255 - v * 255 / 100).coerceIn(30, 255)
        fgColor = Color.rgb(g, g, g)
        textPaint!!.setColor(fgColor)
        clearPageBitmapCache()
        invalidate()
    }

    /** 字体 hinting 开关（E-ink 灰度渲染下建议关闭） */
    fun setFontHinting(enabled: Boolean) {
        textPaint!!.setHinting(if (enabled) Paint.HINTING_ON else Paint.HINTING_OFF)
        invalidate()
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
        // ★ 立即清空旧章节页面，避免布局完成前 onDraw 显示上一章内容
        //   （翻章时先显示旧章第一页、再刷新成新章第一页的 bug）
        pages.clear()
        totalPages = 0
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
        // ★ 布局重新开始（章节切换/字号/段距等变化）：页 Bitmap 缓存全部失效
        clearPageBitmapCache()
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
        // [P1-2] 混合门控：纯文字章节走 Rust（含段落样式），含图片章节回退 Java 渲染
        val hasImages = content.indexOf("[[IMAGE:") >= 0
        if (FeatureFlags.USE_RUST_LAYOUT || (FeatureFlags.USE_RUST_LAYOUT_HYBRID && !hasImages)) {
            // [Layout fix] Rust 内部 content_width = max_width_px - padding_left，
            // 调用方须传完整宽（cw+padL）/完整高（ch+padT），使可用区与 Java 路径一致
            val rustResult: NativeBridge.LayoutResult = NativeBridge.bridgeInstance.layoutTextBinary(
                content,
                contentWidth.toFloat() + padL, contentHeight.toFloat() + padT,
                ts * dens, ls, ps, indentEnabled, padL.toFloat(), padT.toFloat(),
                chapter.paragraphTypes.toIntArray()
            )
            if (rustResult != null && rustResult.totalPages > 0) {
                val rustPages: MutableList<Page?> = ArrayList<Page?>(rustResult.pages.size)
                // 段落样式（与 Java 路径一致）：H1 放大居中 / H2 / H3 放大加粗 / 引用缩进
                val indentPx = (ts * dens * 2).toInt()
                for (pi in rustResult.pages.indices) {
                    val pd = rustResult.pages.get(pi)
                    val page = Page()
                    for (lm in pd.lines) {
                        val pt = lm.paraType
                        val pTS = when (pt) {
                            1 -> ts * 1.8f
                            2 -> ts * 1.5f
                            3 -> ts * 1.2f
                            4 -> ts * 0.9f
                            else -> ts
                        }
                        val pBold = (pt == 1 || pt == 2 || pt == 3)
                        // 两端对齐目标宽（与 Java 路径 justifyW=actCW 一致，绘制时按实际字距展开）
                        val actCW = if (pt == 4 && indentPx * 2 < contentWidth) contentWidth - indentPx * 2 else contentWidth
                        val pJustify = if (pt != 1 && !lm.isParagraphEnd) actCW.toFloat() else 0f
                        page.lines.add(
                            ReaderView.TextLine(
                                lm.text ?: "",
                                lm.x,  // Rust 精确 x 坐标（像素）
                                lm.y,  // Rust 精确 y 坐标（像素）
                                pTS,  // fontSize (sp)
                                pBold,
                                pJustify
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
                        }
                        // [P3] 跨章节页码恢复（书签跳转的显式意图优先于指纹）
                        val pp = pendingPage
                        if (pp >= 0) {
                            pendingPage = -1
                            currentPage = if (pp < totalPages) pp else max(0, totalPages - 1)
                        }
                        // ★ 越界兜底：currentPage 超过新章节页数时重置到章末
                        if (currentPage >= totalPages) currentPage = max(0, totalPages - 1)
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
        // ✅ [P2-4 2026-10-15] content 中图片标记集合在循环外只算一次（惰性：
        //   原实现在每个图片块里重复 content.lines() 全量扫描 → O(图片数×行数)；
        //   结果仅用于 debug 日志，首次进入图片块时算一次，之后复用同一集合）
        var imageMarkers: Set<String>? = null

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
                // 打印 content 中的图片标记（循环外预计算，首图命中时 O(n) 扫描一次，之后复用）
                val markers = imageMarkers ?: content.lines().filter { it.startsWith("[[IMAGE:") }
                    .map { it.substringBefore("]]") + "]]" }.toSet().also { imageMarkers = it }
                log("Image", "content 中的图片标记: $markers")
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
                    val firstOnly = TextWrapper.wrap(trimmed, actCW - indentPx, bp)
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
                                y + ceil(-fm.ascent.toDouble()).toFloat(), paraTS, bold
                            )
                        )
                        y += lineH
                    }
                }
            }

            if (!remaining.isEmpty()) {
                val wrappedLines = TextWrapper.wrap(remaining, actCW, bp)
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
                    // ★ 两端对齐（KOReader 借鉴）：非段末行、非居中时，
                    //   记录目标行宽，绘制时按实际字体测量计算字间距（右侧对齐）
                    var justifyW = 0f
                    if (!centered && li < wrappedLines.size - 1 && line.length > 1) {
                        val lineW = bp.measureText(line)
                        if (lineW < actCW - 1) {
                            justifyW = actCW.toFloat()
                        }
                    }
                    curPd.lines.add(
                        TextLine(
                            line, x,
                            y + ceil(-fm.ascent.toDouble()).toFloat(), paraTS, bold, justifyW
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
                    }
                }

                // [P3] 跨章节页码恢复（书签跳转的显式意图优先于指纹）
                val pp = pendingPage
                if (pp >= 0) {
                    pendingPage = -1
                    currentPage = if (pp < totalPages) pp else max(0, totalPages - 1)
                }
                // ★ 越界兜底：currentPage 超过新章节页数时重置到章末
                //   （修复：切上一章时 goToPage(旧 totalPages-1) 立即执行，
                //    导致 currentPage 残留旧页码越界，onDraw 显示"排版中"）
                if (currentPage >= totalPages) {
                    currentPage = max(0, totalPages - 1)
                }

                // ★ 图片解码已移至翻页方法，但首次布局后仍需解码第一页
                decodeCurrentPageImages()
                invalidate()
                notifyPageChanged()
            }
        })
    }

    // 换行引擎（mcw/msw/wrapText/isForbiddenLineStart）已抽到 TextWrapper（纯函数，可单测）

    // ==================== 绘图 ====================

    // ★ 页 Bitmap 缓存（已抽到 PageBitmapCache）：翻页从 ~30 次 drawText（i.MX6 软渲染慢）
    //   降为 1 次 drawBitmap；按页数 LRU（默认 3 页，RGB_565 每页约 5.2MB）
    private val pageBitmapCache = PageBitmapCache()

    private fun getPageBitmap(idx: Int): Bitmap? = pageBitmapCache.get(idx)

    private fun cachePageBitmap(idx: Int, bmp: Bitmap) {
        pageBitmapCache.put(idx, bmp)
    }

    /** 章节切换/设置变化（layoutVersion 递增）时清空页缓存并回收位图 */
    private fun clearPageBitmapCache() {
        pageBitmapCache.clearAndRecycle()
    }

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

        // ★ 页 Bitmap 缓存：首次渲染成位图并缓存，后续直接 drawBitmap
        var bmp = getPageBitmap(currentPage)
        if (bmp == null) {
            bmp = try {
                val b = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.RGB_565)
                val c = Canvas(b)
                c.drawColor(bgColor)
                drawPageDirect(c, pages.get(currentPage))
                b
            } catch (e: OutOfMemoryError) {
                null   // 内存不足：回退直接绘制
            }
            if (bmp != null) {
                cachePageBitmap(currentPage, bmp)
            }
        }
        if (bmp != null) {
            canvas.drawBitmap(bmp, 0f, 0f, null)
        } else {
            drawPageDirect(canvas, pages.get(currentPage))
        }

        // ★ EPDC 硬件刷新：必须在绘制完成（framebuffer 已更新）后执行，
        //   否则驱动刷新的是旧帧（导致"点击多次才显示下一页"）
        val refreshMode = pendingRefreshMode
        if (refreshMode != null && refreshManager != null) {
            pendingRefreshMode = null
            val area = pendingRefreshArea ?: Rect()
            pendingRefreshArea = null
            refreshManager!!.performEpdcRefresh(this, area, refreshMode)
        }
    }

    /** 直接绘制一页内容（文字/图片/页码）到指定 canvas（Bitmap 渲染与兜底共用） */
    private fun drawPageDirect(c: Canvas, page: Page) {
        textPaint!!.setColor(fgColor)
        textPaint!!.setTypeface(typeface)
        for (line in page.lines) {
            textPaint!!.setTextSize(line.fontSize * density)
            textPaint!!.setFakeBoldText(line.bold || boldMode)
            if (line.justifyWidth > 0f && line.text.length > 1) {
                // 两端对齐：按实际字体测量整行宽，计算每字间距后逐字绘制
                val t = line.text
                val totalW = textPaint!!.measureText(t)
                val step = if (totalW < line.justifyWidth) {
                    (line.justifyWidth - totalW) / (t.length - 1)
                } else 0f
                if (step == 0f) {
                    // 无需加间距：整行一次 drawText（省去逐字 drawText）
                    c.drawText(t, line.x, line.y, textPaint!!)
                } else {
                    // ★ 逐字符宽度用 getTextWidths 一次批量取（单次调用），
                    //   替代原先 n 次 measureText(ch.toString()) + n 次 O(n) toString 分配
                    //   （i.MX6 软渲染下逐字测量是两端对齐行的大热点）
                    val widths = FloatArray(t.length)
                    textPaint!!.getTextWidths(t, 0, t.length, widths)
                    var cx = line.x
                    for (i in 0 until t.length) {
                        // [P2-1] 区间 drawText(t, i, i+1, ...)：每字零 String 分配
                        // （原 t[i].toString() 每帧每字新建一个 String 对象）
                        c.drawText(t, i, i + 1, cx, line.y, textPaint!!)
                        cx += widths[i] + step
                    }
                }
            } else {
                c.drawText(line.text, line.x, line.y, textPaint!!)
            }
        }

        for (img in page.images) {
            if (img.bitmap != null && !img.bitmap!!.isRecycled()) {
                c.drawRect(img.rect!!, imageBgPaint!!)
                c.drawBitmap(img.bitmap!!, null, img.rect!!, null)
            } else if (img.rect != null) {
                c.drawRect(img.rect!!, imageBgPaint!!)
            }
        }

        if (totalPages > 0) {
            pageNumPaint!!.setColor(mutedColor)
            val pageTxt = (currentPage + 1).toString() + " / " + totalPages
            c.drawText(
                pageTxt,
                getWidth() / 2f,
                (getHeight() - 10).toFloat(),
                pageNumPaint!!
            )
            // ★ 底部阅读进度（KOReader footer 借鉴）：全书百分比
            if (bookProgressPercent >= 0) {
                c.drawText(
                    "全书 " + bookProgressPercent + "%",
                    getWidth() / 2f,
                    (getHeight() - 30).toFloat(),
                    pageNumPaint!!
                )
            }
        }
    }

    /** 全书阅读进度（百分比，-1=不显示；由 ReaderActivity 传入） */
    @Volatile
    private var bookProgressPercent = -1

    /** 全局伪粗体模式：所有文字 fake bold（E-ink 对比度增强；软件渲染下比真实字重快） */
    @Volatile
    private var boldMode = false

    fun setBoldMode(enabled: Boolean) {
        if (boldMode == enabled) return
        boldMode = enabled
        clearPageBitmapCache()
        invalidate()
    }

    fun setBookProgress(percent: Int) {
        bookProgressPercent = percent
        // 进度变化只影响底部文本：清页缓存重绘（无需重排版）
        clearPageBitmapCache()
        invalidate()
    }

    /**
     * 两步解码：按目标尺寸一步解码到位（inSampleSize 降采样 + RGB_565 省半内存），
     * 避免"原尺寸全图解码 + createScaledBitmap"的中间大 Bitmap。
     * @return 目标尺寸 (targetW×targetH) 的 RGB_565 位图；解码失败返回 null
     */
    private fun decodeScaledBitmap(data: ByteArray, targetW: Int, targetH: Int): Bitmap? {
        if (targetW <= 0 || targetH <= 0) return null
        // 第一步：只取原图尺寸，不分配像素
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, boundsOpts)
        val ow = boundsOpts.outWidth
        val oh = boundsOpts.outHeight
        if (ow <= 0 || oh <= 0) return null
        // 降采样倍数（2 的幂，Android 要求）：原图 > 目标时按比例降，避免分配超过需要的像素
        var sample = 1
        while (ow / (sample * 2) >= targetW && oh / (sample * 2) >= targetH) {
            sample *= 2
        }
        // 第二步：解码到降采样尺寸（若已比目标小则用目标尺寸，让 BitmapFactory 直接给近似大小）
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565 // 墨水屏无色彩需求，省一半内存
        }
        val decoded = BitmapFactory.decodeByteArray(data, 0, data.size, decodeOpts) ?: return null
        if (decoded.width == targetW && decoded.height == targetH) return decoded
        val scaled = Bitmap.createScaledBitmap(decoded, targetW, targetH, true)
        if (scaled != decoded) decoded.recycle()
        return scaled
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
        // 释放页 Bitmap 缓存，避免 Activity 销毁后泄漏
        clearPageBitmapCache()
        removeCallbacks(coalescedRenderRunnable)
        if (layoutThread != null) {
            layoutThread!!.quitSafely()
            layoutThread = null
            backgroundHandler = null
        }
    }

    // ==================== 触摸翻页 ====================
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // ★ 双指缩放（pinch）：改变字号（KOReader 借鉴）
        if (event.getPointerCount() >= 2) {
            handlePinch(event)
            return true
        }
        val x = event.getX()
        val y = event.getY()
        val width = getWidth().toFloat()
        var zone: Int = calcTapZone(x, width)
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
                val dx = x - downX
                val dy = y - downY
                // ★ 滑动手势翻页（KOReader 借鉴）：水平滑动超过阈值触发翻页
                if (abs(dx) > SWIPE_THRESHOLD_PX && abs(dy) < SWIPE_VERTICAL_LIMIT_PX) {
                    val now = System.currentTimeMillis()
                    if (dx < 0) {
                        if (tryConsume(now)) nextPage()
                    } else {
                        if (tryConsume(now)) prevPage()
                    }
                    return true
                }
                if (abs(dx) > 25f || abs(dy) > 25f) return true
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

    // ★ 翻页触摸区域可配置（KOReader page_turns_tap_zones 借鉴）
    //   tapZoneMode：0=默认(左30翻页/中央菜单/右30翻页)，1=左右50/50全翻页，2=全屏翻下一页
    private var tapZoneMode = 0

    fun setTapZoneMode(mode: Int) {
        tapZoneMode = mode.coerceIn(0, 2)
    }

    private fun calcTapZone(x: Float, width: Float): Int {
        if (width <= 0f) return ZONE_MENU
        return when (tapZoneMode) {
            1 -> if (x < width * 0.5f) ZONE_PREV else ZONE_NEXT
            2 -> ZONE_NEXT
            else -> {
                if (x < width * 0.3f) ZONE_PREV
                else if (x > width * 0.7f) ZONE_NEXT
                else ZONE_MENU
            }
        }
    }

    // ★ 双指缩放字号
    private var lastPinchDist = 0f
    private val PINCH_THRESHOLD = 30f

    private fun handlePinch(event: MotionEvent) {
        val action = event.getActionMasked()
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_MOVE) {
            if (event.getPointerCount() >= 2) {
                val dx = event.getX(0) - event.getX(1)
                val dy = event.getY(0) - event.getY(1)
                val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                if (lastPinchDist > 0 && abs(dist - lastPinchDist) > PINCH_THRESHOLD) {
                    val delta = if (dist > lastPinchDist) 1 else -1
                    pageChangeListener?.onPinchZoom(delta)
                }
                lastPinchDist = dist
            }
        } else if (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP) {
            lastPinchDist = 0f
        }
    }

    private fun tryConsume(now: Long): Boolean {
        if (now - lastPageChangeTime < PAGE_DEBOUNCE_MS) return false
        lastPageChangeTime = now
        return true
    }

    // ==================== 翻页 ====================
    /**
     * 菜单开着时翻页拦截器（由 ReaderActivity 注入）：
     * 返回 true 表示本次翻页被拦截——先收起菜单，不翻页（用户 R6 需求：菜单开着时
     * 第一次翻页请求只缩回菜单，第二次才真正翻页）。
     */
    var menuTurnInterceptor: (() -> Boolean)? = null

    fun prevPage(): Boolean {
        if (menuTurnInterceptor?.invoke() == true) return false
        if (currentPage > 0) {
            currentPage--
            triggerSmartRefresh(false)
            notifyPageChanged()
            decodeCurrentPageImages()
            return true
        }
        if (pageChangeListener != null) pageChangeListener!!.onNeedPrevChapter()
        return false
    }

    fun nextPage(): Boolean {
        if (menuTurnInterceptor?.invoke() == true) return false
        if (currentPage < totalPages - 1) {
            currentPage++
            triggerSmartRefresh(true)
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
        // ★ 真全刷：走 refreshManager 的 GC16 路径（sysfs 全屏刷新，清残影）。
        //   旧实现只 invalidate，系统自动刷新用局部波形、清不掉残影（用户反馈"手动刷新不起效"）。
        val rm = refreshManager
        if (rm != null) {
            val area = Rect()
            getDrawingRect(area)
            if (area.width() <= 0 || area.height() <= 0) {
                area.set(0, 0, getWidth(), getHeight())
            }
            rm.performEpdcRefresh(this, area, EinkRefreshManager.RefreshMode.GC16_FULL)
        } else {
            if (getParent() is View) (getParent() as View).postInvalidate()
            invalidate()
        }
    }

    /** [P3] 暂存目标页码：新章节布局完成并落地到 pages 后自动恢复（书签跳转等跨章目标） */
    fun setPendingPage(p: Int) {
        if (p >= 0) pendingPage = p
    }

    fun setOnPageChangeListener(l: OnPageChangeListener?) {
        this.pageChangeListener = l
    }

    private fun notifyPageChanged() {
        // ★ 页面内容变化后确保 EPDC 刷新显示新帧：
        //   若翻页路径已设置 pending（triggerSmartRefresh 设的局部/全刷模式），不覆盖；
        //   否则（布局完成、章节切换、排版重排等）安排一次 GC16 全刷。
        //   实际 EPDC 命令在 onDraw 绘制完成后统一发出。
        if (pendingRefreshMode == null && refreshManager != null) {
            val area = Rect()
            getDrawingRect(area)
            area.top = (area.top + paddingTop + 60).coerceAtMost(area.bottom - 100)
            area.bottom = (area.bottom - 80).coerceAtLeast(area.top + 100)
            area.left += paddingLeft
            area.right -= paddingRight
            if (area.right <= area.left) area.right = area.left + 1
            if (area.bottom <= area.top) area.bottom = area.top + 1
            pendingRefreshArea = area
            pendingRefreshMode = EinkRefreshManager.RefreshMode.GC16_FULL
            invalidate()
        }
        if (pageChangeListener != null) pageChangeListener!!.onPageChanged(currentPage, totalPages)
    }

    /** 在调用线程解码当前页的图片（懒解码） */
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
            val rw = ib.rect?.width() ?: continue
            val rh = ib.rect?.height() ?: continue
            // ★ inSampleSize 两步解码：先 inJustDecodeBounds 取原图尺寸（不分配像素），
            //   计算降采样倍数后直接解码到接近目标尺寸，避免"原尺寸全图解码 + createScaledBitmap"
            //   的中间全尺寸 Bitmap（i.MX6 单核低内存场景省一次 ~5MB 级临时对象）
            val scaled = decodeScaledBitmap(imgData, rw, rh)
            val dimDesc = if (scaled != null) "${scaled.width}x${scaled.height}" else "null"
            log("Image", "解码结果: bmp=$dimDesc")
            if (scaled == null) continue
            ib.bitmap = scaled
            bitmapCache?.put("img_" + (ib.path ?: System.identityHashCode(this)), scaled)
            needsInvalidate = true
        }
        log("Image", "invalidate=$needsInvalidate")
        if (needsInvalidate) invalidate()
    }

    companion object {
        // 页缓存：按屏幕实际像素 × RGB_565(2B) × 3 页（前/当前/后）。
        // 1404×1872×2×3 ≈ 15.8MB；单核无 swap 下兼顾命中率与内存上限。
        private val MAX_BITMAP_CACHE_BYTES: Int by lazy {
            val dm = android.content.res.Resources.getSystem().displayMetrics
            (dm.widthPixels * dm.heightPixels * 2 * 3).coerceIn(6 * 1024 * 1024, 16 * 1024 * 1024)
        }

        // ==================== 点击区域 ====================
        private const val ZONE_PREV = 0
        private const val ZONE_NEXT = 1
        private const val ZONE_MENU = 2

        // ★ 滑动翻页阈值（KOReader 借鉴）
        private const val SWIPE_THRESHOLD_PX = 60f
        private const val SWIPE_VERTICAL_LIMIT_PX = 40f

        private const val PAGE_DEBOUNCE_MS = 250L
    }
}

