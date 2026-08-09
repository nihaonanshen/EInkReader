package com.einkreader.ui.reader

import android.app.Activity
import android.app.AlertDialog
import java.util.Date
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.TextView
import android.widget.Toast
import com.einkreader.R
import com.einkreader.core.model.Chapter
import com.einkreader.core.model.TocItem
import com.einkreader.core.NativeBridge
import com.einkreader.core.refresh.EinkRefreshManager
import com.einkreader.core.refresh.EinkRefreshManager.RefreshCallback

import com.einkreader.di.ServiceLocator.Companion.getReaderRepository
import com.einkreader.ui.reader.DebugLog.clear
import com.einkreader.ui.reader.DebugLog.error
import com.einkreader.ui.reader.DebugLog.getLog
import com.einkreader.ui.reader.DebugLog.getLogFilePath
import com.einkreader.ui.reader.DebugLog.init
import com.einkreader.ui.reader.DebugLog.log
import com.einkreader.ui.reader.ReaderView.OnPageChangeListener
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.jvm.Volatile
import kotlin.math.max
import kotlin.math.min

class ReaderActivity : Activity() {
    private var readerView: ReaderView? = null
    private var topStatusBar: View? = null
    private var bottomMenu: View? = null
    private var fullOverlay: View? = null
    private var loadingOverlay: View? = null

    // EPUB 图片数据
    private var epubImageBytes: MutableMap<String?, ByteArray?>? = null

    // 翻页模式的目录/书签
    private var fullTocText: TextView? = null
    private var fullTocPage: TextView? = null
    private var fullBookmarkText: TextView? = null
    private var fullBookmarkPage: TextView? = null
    private var fullTocContainer: FrameLayout? = null
    private var fullBookmarkContainer: FrameLayout? = null

    // 目录/书签的翻页状态
    private var tocItems: MutableList<String?> = ArrayList<String?>()
    private var tocPageSize = 15 // 每页显示条目数（动态计算）
    private var tocItemHeightPx = 72 // 每个条目的高度（像素，动态计算）
    private var tocCurrentPage = 0
    private var tocTextSizeSp = 18f // 目录文字大小（SP）
    private var tocLayoutValid = false // ★ TOC 布局缓存标记
    private var bookmarkItems: MutableList<String?> = ArrayList<String?>()
    private var bookmarkCurrentPage = 0

    private var statusTime: TextView? = null
    private var statusChapter: TextView? = null
    private var statusBattery: TextView? = null
    private var btnBack: TextView? = null
    private var btnToc: TextView? = null
    private var btnBookmark: TextView? = null
    private var btnSettings: TextView? = null
    private var btnShowLog: TextView? = null
    private var btnFontMinus: TextView? = null
    private var btnFontPlus: TextView? = null
    private var btnBrightMinus: TextView? = null
    private var btnBrightPlus: TextView? = null
    private var btnFullRefresh: TextView? = null
    private var tvChapterPage: TextView? = null
    private var tvChapterTitle: TextView? = null
    private var tvGlobalPage: TextView? = null
    private var loadingFilename: TextView? = null
    private var fullOverlayTitle: TextView? = null
    private var fullOverlayBack: TextView? = null

    private var refreshManager: EinkRefreshManager? = null
    private var chapters: MutableList<Chapter>? = null
    private var currentChapterIndex = 0
    private var prefs: SharedPreferences? = null
    private var menuVisible = false
    private var filePath: String? = null
    private var fileKey: String? = null
    private var uiHandler: Handler? = null
    private var readingStartTime: Long = 0
    
    // ✅ [Phase 2] 协程作用域用于后台任务（替代 Thread）
    private var bgScope: CoroutineScope? = null

    @Volatile
    private var isDestroyed = false

    @Volatile
    private var bookLoaded = false

    /** EPUB 懒加载：当前加载的文件路径 */
    @Volatile
    private var currentFilePath: String? = null

    /** EPUB 懒加载：正在加载章节标记 */
    @Volatile
    private var isLoadingChapter = false

    /** EPUB 目录树（来自 Rust 解析） */
    private var epubTocItems: List<TocItem>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        init(this)
        log("Lifecycle", "onCreate: savedInstanceState=" + (savedInstanceState != null))
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setVolumeControlStream(AudioManager.STREAM_MUSIC)
        setContentView(R.layout.activity_reader)

        applyImmersiveMode()

        uiHandler = Handler(Looper.getMainLooper())
        // ✅ [Phase 2] 初始化协程作用域
        bgScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        val savedBrightness = prefs?.getFloat("screen_brightness", 0.5f) ?: 0.5f
        val lp = getWindow().getAttributes()
        lp.screenBrightness = savedBrightness
        getWindow().setAttributes(lp)

        refreshManager = EinkRefreshManager(this)
        refreshManager?.initialize(object : RefreshCallback {
            override fun onRefreshStart(mode: EinkRefreshManager.RefreshMode) {}
            override fun onRefreshComplete(mode: EinkRefreshManager.RefreshMode) {}
            override fun onModeDetected(modes: Set<EinkRefreshManager.RefreshMode>) {}
            override fun onSysfsUnavailable() {}
        })

        readerView = findViewById<View?>(R.id.reader_view) as ReaderView?
        topStatusBar = findViewById<View?>(R.id.top_status_bar)
        bottomMenu = findViewById<View?>(R.id.bottom_menu)

        fullOverlay = findViewById<View?>(R.id.full_overlay)
        fullTocContainer = findViewById<View?>(R.id.full_toc_container) as FrameLayout?
        fullTocText = findViewById<View?>(R.id.full_toc_text) as TextView?
        fullTocPage = findViewById<View?>(R.id.full_toc_page) as TextView?
        fullBookmarkContainer = findViewById<View?>(R.id.full_bookmark_container) as FrameLayout?
        fullBookmarkText = findViewById<View?>(R.id.full_bookmark_text) as TextView?
        fullBookmarkPage = findViewById<View?>(R.id.full_bookmark_page) as TextView?
        fullOverlayBack = findViewById<View?>(R.id.full_overlay_back) as TextView?
        fullOverlayTitle = findViewById<View?>(R.id.full_overlay_title) as TextView?
        if (fullOverlayBack != null) {
            checkNotNull(fullOverlayBack).setOnClickListener(View.OnClickListener { v: View? -> exitFullOverlay() })
        }
        // 目录/书签容器触摸翻页
        val touchListener = OnTouchListener { v: View?, event: MotionEvent? ->
            val w = checkNotNull(v).getWidth()
            val h = v.getHeight()
            val x = checkNotNull(event).getX()
            val y = event.getY()
            when (event.getAction()) {
                MotionEvent.ACTION_UP -> {
                    val density = getResources().getDisplayMetrics().density
                    val paddingPx = (16 * density).toInt() // TextView 的 padding 是 16dp
                    if (fullOverlayMode == FullOverlayMode.TOC) {
                        // 计算点击的条目位置（考虑容器内 TextView 的实际布局）
                        var textViewTop = 0
                        if (fullTocText != null) {
                            val loc = IntArray(2)
                            checkNotNull(fullTocText).getLocationOnScreen(loc)
                            val vloc = IntArray(2)
                            v.getLocationOnScreen(vloc)
                            textViewTop = loc[1] - vloc[1]
                        }
                        val itemTop = textViewTop + paddingPx
                        val row = ((y - itemTop) / tocItemHeightPx).toInt()
                        val startIdx = tocCurrentPage * tocPageSize
                        val clickIdx = startIdx + row
                        if (clickIdx >= startIdx && clickIdx < startIdx + tocPageSize && clickIdx < tocItems.size && chapters != null) {
                            log(
                                "TOC",
                                "点击目录项: row=" + row + " idx=" + clickIdx + " item=" + tocItems.get(
                                    clickIdx
                                ) + " y=" + y + " itemTop=" + itemTop + " itemH=" + tocItemHeightPx
                            )
                            // 尝试根据目录标题找到对应章节
                            val chapterIdx = findChapterByTocIndex(clickIdx)
                            if (chapterIdx >= 0 && chapterIdx < checkNotNull(chapters).size) {
                                switchChapterTo(chapterIdx)
                            }
                            exitFullOverlay()
                        } else if (x < w * 0.33f) {
                            tocPrevPage()
                        } else if (x > w * 0.67f) {
                            tocNextPage()
                        } else {
                            tocNextPage()
                        }
                    } else if (fullOverlayMode == FullOverlayMode.BOOKMARK) {
                        var textViewTop = 0
                        if (fullBookmarkText != null) {
                            val loc = IntArray(2)
                            checkNotNull(fullBookmarkText).getLocationOnScreen(loc)
                            val vloc = IntArray(2)
                            v.getLocationOnScreen(vloc)
                            textViewTop = loc[1] - vloc[1]
                        }
                        val itemTop = textViewTop + paddingPx
                        val row = ((y - itemTop) / tocItemHeightPx).toInt()
                        val startIdx = bookmarkCurrentPage * tocPageSize
                        val clickIdx = startIdx + row
                        if (clickIdx >= startIdx && clickIdx < startIdx + tocPageSize && clickIdx < bookmarkItems.size) {
                            log("Bookmark", "点击书签项: row=" + row + " idx=" + clickIdx)
                            jumpToBookmark(clickIdx)
                            exitFullOverlay()
                        } else if (x < w * 0.33f) {
                            bookmarkPrevPage()
                        } else if (x > w * 0.67f) {
                            bookmarkNextPage()
                        } else {
                            bookmarkNextPage()
                        }
                    }
                }
            }
            true
        }
        if (fullTocContainer != null) checkNotNull(fullTocContainer).setOnTouchListener(touchListener)
        if (fullBookmarkContainer != null) checkNotNull(fullBookmarkContainer).setOnTouchListener(
            touchListener
        )

        loadingOverlay = findViewById<View?>(R.id.loading_overlay)
        loadingFilename = findViewById<View?>(R.id.loading_filename) as TextView?

        statusTime = findViewById<View?>(R.id.status_time) as TextView?
        statusChapter = findViewById<View?>(R.id.status_chapter) as TextView?
        statusBattery = findViewById<View?>(R.id.status_battery) as TextView?

        btnBack = findViewById<View?>(R.id.btn_back) as TextView
        btnToc = findViewById<View?>(R.id.btn_toc) as TextView
        btnBookmark = findViewById<View?>(R.id.btn_bookmark) as TextView
        btnSettings = findViewById<View?>(R.id.btn_settings) as TextView
        btnShowLog = findViewById<View?>(R.id.btn_show_log) as TextView

        btnFontMinus = findViewById<View?>(R.id.btn_font_minus) as TextView
        btnFontPlus = findViewById<View?>(R.id.btn_font_plus) as TextView
        btnBrightMinus = findViewById<View?>(R.id.btn_bright_minus) as TextView
        btnBrightPlus = findViewById<View?>(R.id.btn_bright_plus) as TextView
        btnFullRefresh = findViewById<View?>(R.id.btn_full_refresh) as TextView

        tvChapterPage = findViewById<View?>(R.id.tv_chapter_page) as TextView
        tvChapterTitle = findViewById<View?>(R.id.tv_chapter_title) as TextView
        tvGlobalPage = findViewById<View?>(R.id.tv_global_page) as TextView

        checkNotNull(btnBack).setOnClickListener(View.OnClickListener { v: View? -> finish() })
        checkNotNull(btnToc).setOnClickListener(View.OnClickListener { v: View? ->
            openFullOverlay(FullOverlayMode.TOC)
            loadTocList()
        })
        checkNotNull(btnBookmark).setOnClickListener(View.OnClickListener { v: View? ->
            addBookmark()
            openFullOverlay(FullOverlayMode.BOOKMARK)
            loadBookmarks()
        })
        checkNotNull(btnSettings).setOnClickListener(View.OnClickListener { v: View? ->
            val intent = Intent(this@ReaderActivity, ReadingSettingsActivity::class.java)
            startActivity(intent)
        })
        checkNotNull(btnShowLog).setOnClickListener(View.OnClickListener { v: View? -> showLogDialog() })
        checkNotNull(btnFontMinus).setOnClickListener(View.OnClickListener { v: View? -> adjustFontSize(-1) })
        checkNotNull(btnFontPlus).setOnClickListener(View.OnClickListener { v: View? -> adjustFontSize(1) })
        checkNotNull(btnBrightMinus).setOnClickListener(View.OnClickListener { v: View? -> adjustBrightness(-0.1f) })
        checkNotNull(btnBrightPlus).setOnClickListener(View.OnClickListener { v: View? ->
            adjustBrightness(
                0.1f
            )
        })
        checkNotNull(btnFullRefresh).setOnClickListener(View.OnClickListener { v: View? ->
            if (readerView != null) checkNotNull(readerView).performFullRefresh()
        })

        val savedSize = checkNotNull(prefs).getFloat("text_size", 28f)

        checkNotNull(readerView).setOnPageChangeListener(object : OnPageChangeListener {
            override fun onPageChanged(pageIndex: Int, totalPages: Int) {
                log("Page", "onPageChanged: " + pageIndex + "/" + totalPages)
                if (!bookLoaded) return
                saveProgress()
                updateStatusBar()
                updateInfoBar()
                if (refreshManager != null) checkNotNull(refreshManager).onPageTurn(readerView)
            }

            override fun onChapterChanged(chapterIndex: Int) {
                log("Chapter", "onChapterChanged: chapter=" + chapterIndex)
                if (!bookLoaded) return
                updateInfoBar()
            }

            override fun onTapCenter() {
                log("UI", "onTapCenter: menuVisible=" + menuVisible)
                if (!bookLoaded) return
                toggleMenu(!menuVisible)
            }

            override fun onNeedPrevChapter() {
                log("Nav", "onNeedPrevChapter")
                if (!bookLoaded) return
                goToPrevChapter()
            }

            override fun onNeedNextChapter() {
                log("Nav", "onNeedNextChapter")
                if (!bookLoaded) return
                goToNextChapter()
            }
        })

        checkNotNull(readerView).setFocusable(true)
        checkNotNull(readerView).setFocusableInTouchMode(true)
        checkNotNull(readerView).requestFocus()

        readingStartTime = System.currentTimeMillis()
        loadBook()
    }

    private fun adjustFontSize(delta: Int) {
        val current = checkNotNull(prefs).getFloat("text_size", 28f)
        val next = max(14f, min(64f, current + delta))
        checkNotNull(prefs).edit().putFloat("text_size", next).apply()
        if (readerView != null) checkNotNull(readerView).setTextSize(next)
    }

    private fun adjustBrightness(delta: Float) {
        val lp = getWindow().getAttributes()
        val cur = if (lp.screenBrightness > 0) lp.screenBrightness else 0.5f
        val next = max(0.05f, min(1.0f, cur + delta))
        lp.screenBrightness = next
        getWindow().setAttributes(lp)
        checkNotNull(prefs).edit().putFloat("screen_brightness", next).apply()
    }

    private enum class FullOverlayMode {
        NONE, TOC, BOOKMARK
    }

    private var fullOverlayMode: FullOverlayMode? = FullOverlayMode.NONE

    private fun openFullOverlay(mode: FullOverlayMode?) {
        fullOverlayMode = mode
        if (fullOverlay != null) checkNotNull(fullOverlay).setVisibility(View.VISIBLE)
        if (fullTocContainer != null) checkNotNull(fullTocContainer).setVisibility(if (mode == FullOverlayMode.TOC) View.VISIBLE else View.GONE)
        if (fullBookmarkContainer != null) checkNotNull(fullBookmarkContainer).setVisibility(if (mode == FullOverlayMode.BOOKMARK) View.VISIBLE else View.GONE)
        if (fullOverlayTitle != null) {
            checkNotNull(fullOverlayTitle).setText(if (mode == FullOverlayMode.TOC) "目录" else "书签")
        }
        // 全屏时隐藏状态栏和底部菜单，避免遮挡
        if (topStatusBar != null) checkNotNull(topStatusBar).setVisibility(View.GONE)
        if (bottomMenu != null) checkNotNull(bottomMenu).setVisibility(View.GONE)
        // 恢复 ReaderView 铺满
        val lp = checkNotNull(readerView).getLayoutParams() as FrameLayout.LayoutParams?
        if (lp != null) {
            lp.topMargin = 0
            lp.bottomMargin = 0
            checkNotNull(readerView).setLayoutParams(lp)
        }
    }

    private fun exitFullOverlay() {
        fullOverlayMode = FullOverlayMode.NONE
        if (fullOverlay != null) checkNotNull(fullOverlay).setVisibility(View.GONE)
        if (readerView != null) checkNotNull(readerView).requestFocus()
        // 恢复菜单显示
        if (menuVisible) {
            if (topStatusBar != null) checkNotNull(topStatusBar).setVisibility(View.VISIBLE)
            if (bottomMenu != null) checkNotNull(bottomMenu).setVisibility(View.VISIBLE)
            val lp = checkNotNull(readerView).getLayoutParams() as FrameLayout.LayoutParams?
            if (lp != null) {
                lp.topMargin = (32 * getResources().getDisplayMetrics().density).toInt()
                lp.bottomMargin = (160 * getResources().getDisplayMetrics().density).toInt()
                checkNotNull(readerView).setLayoutParams(lp)
            }
        }
    }

    private fun loadTocList() {
        if (chapters == null || checkNotNull(chapters).isEmpty()) {
            Toast.makeText(this, "暂无章节", Toast.LENGTH_SHORT).show()
            return
        }
        // ★ 动态计算目录布局，适配不同屏幕尺寸
        calculateTocLayout()
        val titles = ArrayList<String?>()

        // 优先使用 Rust 解析的目录树（如果有）
        if (epubTocItems != null && checkNotNull(epubTocItems).isNotEmpty()) {
            flattenTocItems(checkNotNull(epubTocItems), titles, 0)
        } else {
            // 回退到章节列表
            for (i in checkNotNull(chapters).indices) {
                val c = checkNotNull(chapters).get(i)
                var t = if (c.title.isNotEmpty()) c.title else ("第" + (i + 1) + "章")
                if (i == currentChapterIndex) t = "▶ " + t
                titles.add(t)
            }
        }

        tocItems = titles
        // 自动定位到当前章所在页（否则长书目录里 ▶ 标记不可见）
        tocCurrentPage = if (tocPageSize > 0) currentChapterIndex / tocPageSize else 0
        renderTocPage()
    }

    /** 根据目录索引找到对应的章节索引 */
    private fun findChapterByTocIndex(tocIndex: Int): Int {
        if (epubTocItems == null || checkNotNull(epubTocItems).isEmpty()) return tocIndex
        if (chapters == null || chapters.isNullOrEmpty()) return -1

        // 展平目录树，记录每个目录项对应的章节索引
        val tocEntries = mutableListOf<Pair<String, Int>>() // (href, chapterIndex)
        flattenTocWithChapterIndex(checkNotNull(epubTocItems), tocEntries)

        // 根据目录索引找到对应的 href，然后查找章节
        if (tocIndex < tocEntries.size) {
            val (targetHref, _) = tocEntries[tocIndex]
            for ((idx, ch) in checkNotNull(chapters).withIndex()) {
                if (ch.xhtmlPath == targetHref) return idx
            }
        }
        return tocIndex // 回退到直接索引
    }

    /** 递归展平目录树为标题列表（用于显示） */
    private fun flattenTocItems(items: List<TocItem>, out: MutableList<String?>, indent: Int) {
        val prefix = " ".repeat(indent * 2)
        for (item in items) {
            val displayTitle = if (indent > 0) prefix + item.title else item.title
            out.add(displayTitle)
            if (item.children.isNotEmpty()) {
                flattenTocItems(item.children, out, indent + 1)
            }
        }
    }

    /** 递归展平目录树，记录每个目录项的 href 和章节索引 */
    private fun flattenTocWithChapterIndex(items: List<TocItem>, out: MutableList<Pair<String, Int>>) {
        for (item in items) {
            // 查找匹配的章节
            var matchedIndex = -1
            if (chapters != null) {
                for ((idx, ch) in checkNotNull(chapters).withIndex()) {
                    if (ch.xhtmlPath == item.href) {
                        matchedIndex = idx
                        break
                    }
                }
            }
            out.add(Pair(item.href, matchedIndex))
            if (item.children.isNotEmpty()) {
                flattenTocWithChapterIndex(item.children, out)
            }
        }
    }

    /**
     * ★ 动态计算目录每页条目数和行高
     * 根据屏幕高度、字号、行距自动计算，适配不同尺寸的墨水屏
     */
    private fun calculateTocLayout() {
        // ★ 缓存命中则跳过重算（屏幕尺寸在 Activity 生命周期内不变）
        if (tocLayoutValid) return
        val dm = getResources().getDisplayMetrics()
        val density = dm.density
        val screenHeight = dm.heightPixels

        // 标题栏高度: 48dp
        val headerHeight = (48 * density).toInt()
        // 可用高度 = 屏幕高度 - 标题栏
        val availHeight = screenHeight - headerHeight
        // TextView padding: 16dp 上下
        val padding = (16 * density * 2).toInt()
        var textAvailHeight = availHeight - padding
        if (textAvailHeight < 200) textAvailHeight = 200 // 安全下限


        // 目录文字大小: 根据屏幕密度和高度动态计算
        if (screenHeight >= 1800) {
            tocTextSizeSp = 26f // 7.8 寸大屏
        } else if (screenHeight >= 1400) {
            tocTextSizeSp = 22f // 6 寸中屏
        } else {
            tocTextSizeSp = 18f // 小屏
        }
        val tocTextSizePx = tocTextSizeSp * density
        // 行距额外: 8dp
        val lineSpacingExtraPx = 8 * density
        // 单行高度 = 字号 * 字体metrics(约1.2倍) + 行距额外
        val lineHeightPx = tocTextSizePx * 1.2f + lineSpacingExtraPx
        // 每个条目占 2 行（1行文字 + 1行空行 \n\n）
        tocItemHeightPx = (lineHeightPx * 2).toInt()
        if (tocItemHeightPx < 30) tocItemHeightPx = 30

        // 每页条目数 = 可用高度 / 条目高度（向下取整，留底部页码空间）
        val pageLabelHeight = (30 * density).toInt() // 底部页码预留
        val usableHeight = textAvailHeight - pageLabelHeight
        tocPageSize = usableHeight / tocItemHeightPx
        if (tocPageSize < 5) tocPageSize = 5
        if (tocPageSize > 30) tocPageSize = 30

        log(
            "TOC", ("calculateTocLayout: screenH=" + screenHeight + " density=" + density
                    + " headerH=" + headerHeight + " availH=" + availHeight + " textAvailH=" + textAvailHeight
                    + " lineH=" + lineHeightPx + " itemH=" + tocItemHeightPx + " pageSize=" + tocPageSize)
        )
        tocLayoutValid = true
    }

    private fun renderTocPage() {
        if (fullTocText == null || fullTocPage == null) return
        // 应用目录字体大小
        checkNotNull(fullTocText).setTextSize(tocTextSizeSp)
        val totalPages = (tocItems.size + tocPageSize - 1) / tocPageSize
        if (tocCurrentPage >= totalPages) tocCurrentPage = totalPages - 1
        if (tocCurrentPage < 0) tocCurrentPage = 0
        val start = tocCurrentPage * tocPageSize
        val end = min(start + tocPageSize, tocItems.size)
        val sb = StringBuilder()
        for (i in start..<end) {
            sb.append(tocItems.get(i))
            if (i < end - 1) sb.append("\n\n")
        }
        checkNotNull(fullTocText).setText(sb.toString())
        checkNotNull(fullTocPage).setText((tocCurrentPage + 1).toString() + " / " + totalPages)
    }

    private fun tocPrevPage() {
        if (tocCurrentPage > 0) {
            tocCurrentPage--
            renderTocPage()
        }
    }

    private fun tocNextPage() {
        val totalPages = (tocItems.size + tocPageSize - 1) / tocPageSize
        if (tocCurrentPage < totalPages - 1) {
            tocCurrentPage++
            renderTocPage()
        }
    }

    private fun loadBookmarks() {
        var list: MutableList<String?> = java.util.ArrayList<String?>()
        try {
            list.clear(); list.addAll(getReaderRepository().loadBookmarks(checkNotNull(fileKey)))
        } catch (e: Exception) {
            error("Bookmark", "loadBookmarks failed", e)
        }
        bookmarkItems = ArrayList<String?>(list)
        bookmarkCurrentPage = 0
        renderBookmarkPage()
    }

    private fun renderBookmarkPage() {
        if (fullBookmarkText == null || fullBookmarkPage == null) return
        val totalPages = (bookmarkItems.size + tocPageSize - 1) / tocPageSize
        if (bookmarkCurrentPage >= totalPages) bookmarkCurrentPage = totalPages - 1
        if (bookmarkCurrentPage < 0) bookmarkCurrentPage = 0
        val start = bookmarkCurrentPage * tocPageSize
        val end = min(start + tocPageSize, bookmarkItems.size)
        val sb = StringBuilder()
        for (i in start..<end) {
            sb.append(bookmarkItems.get(i))
            if (i < end - 1) sb.append("\n\n")
        }
        if (bookmarkItems.isEmpty()) sb.append("暂无书签")
        checkNotNull(fullBookmarkText).setText(sb.toString())
        checkNotNull(fullBookmarkPage).setText(
            (bookmarkCurrentPage + 1).toString() + " / " + max(
                1,
                totalPages
            )
        )
    }

    private fun bookmarkPrevPage() {
        if (bookmarkCurrentPage > 0) {
            bookmarkCurrentPage--
            renderBookmarkPage()
        }
    }

    private fun bookmarkNextPage() {
        val totalPages = (bookmarkItems.size + tocPageSize - 1) / tocPageSize
        if (bookmarkCurrentPage < totalPages - 1) {
            bookmarkCurrentPage++
            renderBookmarkPage()
        }
    }

    private fun jumpToBookmark(idx: Int) {
        if (chapters == null || fileKey == null) return
        try {
            val ch = getReaderRepository().jumpToBookmark(checkNotNull(fileKey), idx, checkNotNull(chapters).size)
            if (ch >= 0 && ch < checkNotNull(chapters).size) {
                switchChapterTo(ch)
            }
        } catch (e: Exception) {
            error("Bookmark", "jumpToBookmark failed", e)
        }
    }

    private fun addBookmark() {
        if (fileKey == null || readerView == null) return
        try {
            var title = ""
            if (chapters != null && currentChapterIndex < checkNotNull(chapters).size) {
                val c = checkNotNull(chapters).get(currentChapterIndex)
                title =
                    if (c.title.isNotEmpty()) c.title else ("第" + (currentChapterIndex + 1) + "章")
            }
            getReaderRepository().addBookmark(
                checkNotNull(fileKey), currentChapterIndex, checkNotNull(readerView).currentPage, title
            )
            Toast.makeText(this, "已加书签", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            error("Bookmark", "addBookmark failed", e)
            Toast.makeText(this, "书签保存失败", Toast.LENGTH_SHORT).show()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val c = event.getKeyCode()
        val isVolumeKey = (c == KeyEvent.KEYCODE_VOLUME_UP || c == KeyEvent.KEYCODE_VOLUME_DOWN)
        val isPageKey = (c == KeyEvent.KEYCODE_PAGE_UP || c == KeyEvent.KEYCODE_PAGE_DOWN)
        if (isVolumeKey || isPageKey) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                // 全屏目录/书签覆盖层显示时，翻页键用于翻目录
                if (fullOverlayMode == FullOverlayMode.TOC) {
                    if (c == KeyEvent.KEYCODE_VOLUME_UP || c == KeyEvent.KEYCODE_PAGE_UP) {
                        tocPrevPage()
                    } else {
                        tocNextPage()
                    }
                    return true
                }
                if (fullOverlayMode == FullOverlayMode.BOOKMARK) {
                    if (c == KeyEvent.KEYCODE_VOLUME_UP || c == KeyEvent.KEYCODE_PAGE_UP) {
                        bookmarkPrevPage()
                    } else {
                        bookmarkNextPage()
                    }
                    return true
                }
                // 书籍尚未加载时，屏蔽翻页键，防止空指针导致 ANR 崩溃
                if (!bookLoaded) {
                    return true
                }
                if (c == KeyEvent.KEYCODE_VOLUME_UP || c == KeyEvent.KEYCODE_PAGE_UP) {
                    if (readerView != null) checkNotNull(readerView).prevPage()
                } else {
                    if (readerView != null) checkNotNull(readerView).nextPage()
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_PAGE_DOWN) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun loadBook() {
        log("Load", "loadBook start")
        bookLoaded = false
        epubImageBytes = null // ★ 重置图片数据，防止跨书泄露
        if (loadingFilename != null) {
            val name = (filePath ?: "")
            val slash = max(name.lastIndexOf('/'), name.lastIndexOf('\\'))
            checkNotNull(loadingFilename).setText(if (slash >= 0) name.substring(slash + 1) else name)
        }
        if (loadingOverlay != null) {
            checkNotNull(loadingOverlay).setVisibility(View.VISIBLE)
            // 解析超时兜底——30秒后自动隐藏 loading，防止永久白屏
            checkNotNull(uiHandler).postDelayed(object : Runnable {
                override fun run() {
                    if (isDestroyed) return
                    if (loadingOverlay != null && checkNotNull(loadingOverlay).getVisibility() == View.VISIBLE) {
                        checkNotNull(loadingOverlay).setVisibility(View.GONE)
                        Toast.makeText(this@ReaderActivity, "加载超时", Toast.LENGTH_LONG).show()
                    }
                }
            }, LOADING_TIMEOUT_MS)
        }
        filePath = getIntent().getStringExtra("file_path")
        currentFilePath = filePath
        val fileUri = getIntent().getStringExtra("file_uri")
        if ((filePath == null || checkNotNull(filePath).isEmpty()) && fileUri == null) {
            error("Load", "书籍路径为空")
            Toast.makeText(this, "书籍路径为空", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val fp = filePath
        val fu = (fileUri ?: filePath ?: "")

        // ★ 后台线程执行解析 —— 使用 ReaderRepository 解耦所有业务逻辑
        Thread(Runnable {
            try {
                val repo = getReaderRepository()
                val result = repo.loadBook(checkNotNull(fp), fu)

                if (result == null || !result.isValid()) {
                    showToastOnUi("解析失败")
                    return@Runnable
                }

                val fch: MutableList<Chapter> = java.util.ArrayList(result.chapters)

                checkNotNull(uiHandler).post(Runnable {
                    try {
                        if (isDestroyed) return@Runnable
                        if (fch == null || fch.isEmpty()) {
                            error(
                                "Parse",
                                "解析失败: chapters=" + (if (fch == null) "null" else "empty")
                            )
                            Toast.makeText(this@ReaderActivity, "解析失败", Toast.LENGTH_SHORT)
                                .show()
                            finish()
                            return@Runnable
                        }
                        log("Load", "书籍加载成功: chapters=" + fch.size)
                        chapters = fch
                        fileKey = result.fileKey
                        epubImageBytes = HashMap(result.images)
                        // 保存目录树供目录显示使用
                        epubTocItems = if (result.tocItems.isNotEmpty()) result.tocItems else null
                        // ★ 提取封面图缓存到磁盘（书架显示用），images["__cover__"] 为 Rust 解析出的封面
                        cacheCoverToDisk(result.images["__cover__"])
                        // 恢复阅读进度
                        var sc = result.savedChapter
                        var sp = result.savedPage

                        if (TocActivity.sSelectedChapter >= 0 && TocActivity.sSelectedChapter < fch.size) {
                            sc = TocActivity.sSelectedChapter
                            sp = 0
                            TocActivity.sSelectedChapter = -1
                        }
                        if (sc < 0 || sc >= fch.size) {
                            sc = 0
                            sp = 0
                        }
                        currentChapterIndex = sc
                        checkNotNull(readerView).setChapter(checkNotNull(chapters).get(currentChapterIndex))
                        // ★ 传递图片字节数据给 ReaderView，用于 [[IMAGE:path]] 渲染
                        if (epubImageBytes != null) {
                            checkNotNull(readerView).setChapterImages(epubImageBytes)
                        }
                        checkNotNull(readerView).applySettings()
                        // ✅ [Phase 7] 进度条用全书总页数（章节数 * 当前章节预估页数）
                        val approxTotalPages = checkNotNull(chapters).size * checkNotNull(readerView).totalPages
                        updateInfoBar()
                        bookLoaded = true
                        if (loadingOverlay != null) checkNotNull(loadingOverlay).setVisibility(View.GONE)
                    } catch (t: Throwable) {
                        error("Load", "UI post failed: type=" + t.javaClass.getSimpleName(), t)
                        if (!isDestroyed) {
                            Toast.makeText(this@ReaderActivity, "加载失败", Toast.LENGTH_LONG)
                                .show()
                            finish()
                        }
                    }
                })
            } catch (e: Exception) {
                error("Reader", "后台线程加载失败", e)
                checkNotNull(uiHandler).post(Runnable {
                    if (isDestroyed) return@Runnable
                    Toast.makeText(this@ReaderActivity, "加载失败", Toast.LENGTH_LONG).show()
                    finish()
                })
            }
        }).start()
    }

    private fun showToastOnUi(m: String?) {
        checkNotNull(uiHandler).post(Runnable {
            if (isDestroyed) return@Runnable
            Toast.makeText(this@ReaderActivity, m, Toast.LENGTH_SHORT).show()
            finish()
        })
    }

    private fun switchChapterTo(index: Int) {
        if (chapters == null || checkNotNull(chapters).isEmpty()) return
        if (index < 0 || index >= checkNotNull(chapters).size) return
        
        val targetChapter = checkNotNull(chapters)[index]
        
        // EPUB 懒加载：检查章节内容是否为空，若为空且 xhtmlPath 存在则触发按需加载
        if (targetChapter.content.isEmpty() && targetChapter.xhtmlPath != null && !isLoadingChapter) {
            triggerLazyLoadChapter(targetChapter, index)
            return
        }
        currentChapterIndex = index
        checkNotNull(readerView).setChapter(checkNotNull(chapters).get(currentChapterIndex))
        checkNotNull(readerView).goToPage(0)
        updateStatusBar()
        updateInfoBar()
    }

    /** 触发 EPUB 章节懒加载 */
    private fun triggerLazyLoadChapter(chapter: Chapter, index: Int) {
        if (isLoadingChapter) return
        isLoadingChapter = true
        
        // 显示加载提示
        if (loadingOverlay != null) {
            checkNotNull(loadingFilename).setText("加载章节: ${chapter.title}")
            checkNotNull(loadingOverlay).setVisibility(View.VISIBLE)
        }
        
        bgScope?.launch(Dispatchers.IO) {
            try {
                val fp = currentFilePath
                if (fp == null || !NativeBridge.Companion.sLibraryLoaded) {
                    withContext(Dispatchers.Main) { isLoadingChapter = false; hideLoading() }
                    return@launch
                }
                val content = NativeBridge.bridgeInstance.loadEpubChapterContent(fp, checkNotNull(chapter.xhtmlPath))
                // 检查返回内容是否为错误 JSON（如 {"error":"..."}）
                if (content.startsWith("{\"error\"")) {
                    Log.w("ReaderActivity", "Lazy load chapter content error: $content")
                    withContext(Dispatchers.Main) { isLoadingChapter = false; hideLoading() }
                    return@launch
                }
                chapter.content = content
                withContext(Dispatchers.Main) {
                    isLoadingChapter = false
                    hideLoading()
                    // 重新切换到该章节（现在已有内容）
                    switchChapterTo(index)
                }
            } catch (e: Exception) {
                Log.e("ReaderActivity", "Lazy load chapter failed", e)
                withContext(Dispatchers.Main) {
                    isLoadingChapter = false
                    hideLoading()
                    Toast.makeText(this@ReaderActivity, "章节加载失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 隐藏加载 overlay */
    private fun hideLoading() {
        if (loadingOverlay != null && checkNotNull(loadingOverlay).getVisibility() == View.VISIBLE) {
            checkNotNull(loadingOverlay).setVisibility(View.GONE)
        }
    }

    private fun switchChapter(d: Int) {
        if (chapters == null || checkNotNull(chapters).isEmpty()) return
        val ni = currentChapterIndex + d
        if (ni < 0 || ni >= checkNotNull(chapters).size) return
        currentChapterIndex = ni
        checkNotNull(readerView).setChapter(checkNotNull(chapters).get(currentChapterIndex))
        updateStatusBar()
    }

    private fun goToPrevChapter() {
        if (currentChapterIndex > 0) {
            switchChapter(-1)
            checkNotNull(readerView).goToPage(checkNotNull(readerView).totalPages - 1)
        } else {
            Toast.makeText(this, "已经是第一章了", Toast.LENGTH_SHORT).show()
        }
    }

    private fun goToNextChapter() {
        if (currentChapterIndex < checkNotNull(chapters).size - 1) {
            switchChapter(1)
            checkNotNull(readerView).goToPage(0)
        } else {
            Toast.makeText(this, "已经是最后一章了", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleMenu(show: Boolean) {
        menuVisible = show
        val vis = if (show) View.VISIBLE else View.GONE
        checkNotNull(topStatusBar).setVisibility(vis)
        checkNotNull(bottomMenu).setVisibility(vis)
        if (show) {
            updateStatusBar()
            updateInfoBar()
            // 调整 ReaderView 以避开状态栏和底部菜单的遮挡
            val lp = checkNotNull(readerView).getLayoutParams() as FrameLayout.LayoutParams?
            if (lp != null) {
                lp.topMargin = (32 * getResources().getDisplayMetrics().density).toInt()
                lp.bottomMargin = (160 * getResources().getDisplayMetrics().density).toInt()
                checkNotNull(readerView).setLayoutParams(lp)
            }
        } else {
            exitFullOverlay()
            // 恢复 ReaderView 铺满整屏
            val lp = checkNotNull(readerView).getLayoutParams() as FrameLayout.LayoutParams?
            if (lp != null) {
                lp.topMargin = 0
                lp.bottomMargin = 0
                checkNotNull(readerView).setLayoutParams(lp)
            }
        }
        applyImmersiveMode()
    }

    private fun applyImmersiveMode() {
        try {
            if (Build.VERSION.SDK_INT >= 14) {
                getWindow().getDecorView().setSystemUiVisibility(
                    (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
                )
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                    (View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                )
            }
        } catch (e: Exception) {
            error("UI", "applyImmersiveMode failed", e)
        }
    }

    private fun showLogDialog() {
        try {
            val log = getLog()
            val sb = StringBuilder()
            sb.append("日志文件路径：\n").append(getLogFilePath()).append("\n\n")
            if (log != null && log.length > 0) {
                // 限制显示长度，避免弹窗过长
                if (log.length > 30000) {
                    sb.append("（日志过长，仅显示最后部分）\n\n")
                    sb.append(log.substring(log.length - 30000))
                } else {
                    sb.append(log)
                }
            } else {
                sb.append("暂无日志")
            }
            AlertDialog.Builder(this)
                .setTitle("调试日志")
                .setMessage(sb.toString())
                .setPositiveButton(
                    "清除",
                    DialogInterface.OnClickListener { dlg: DialogInterface?, w: Int ->
                        clear()
                        Toast.makeText(this, "已清除", Toast.LENGTH_SHORT).show()
                    })
                .setNegativeButton("关闭", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "无法读取日志", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStatusBar() {
        if (statusTime != null) checkNotNull(statusTime).setText(
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(
                Date()
            )
        )

        if (statusChapter != null && chapters != null && currentChapterIndex < checkNotNull(chapters).size) {
            val title = checkNotNull(chapters)[currentChapterIndex].title
            val chapText = if (title != null) title else ("第" + (currentChapterIndex + 1) + "章")
            checkNotNull(statusChapter).setText(chapText + "  (" + (currentChapterIndex + 1) + "/" + checkNotNull(chapters).size + ")")
        }

        if (readerView != null) {
            val pageText = checkNotNull(readerView).currentPage.toString() + "/" + checkNotNull(readerView).totalPages
            if (statusChapter != null) {
                val existing = checkNotNull(statusChapter).getText()
                if (existing != null && existing.length > 0) {
                    checkNotNull(statusChapter).setText(existing.toString() + "  ·  " + pageText)
                } else {
                    checkNotNull(statusChapter).setText(pageText)
                }
            }
        }

        if (statusBattery != null) {
            try {
                val bi = registerReceiver(
                    null,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                )
                if (bi != null) {
                    val level = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level >= 0 && scale > 0) checkNotNull(statusBattery).setText(
                        String.format(
                            Locale.getDefault(), "%d%%",
                            ((level / scale.toFloat()) * 100).toInt()
                        )
                    )
                }
            } catch (e: Exception) {
                error("UI", "updateStatusBar battery failed", e)
            }
        }
    }

    /** 更新信息栏（替换进度条+标签） */
    private fun updateInfoBar() {
        if (tvChapterPage == null || tvChapterTitle == null || tvGlobalPage == null || readerView == null) return
        val rv = checkNotNull(readerView)
        val globalPage = rv.currentPage + 1
        val globalTotal = max(1, getTotalBookPages())
        val chPage = rv.currentPage + 1
        val chTotal = max(1, rv.totalPages)

        tvGlobalPage!!.text = "$globalPage/$globalTotal"
        tvChapterPage!!.text = "$chPage/$chTotal"
        
        // 中间章节名
        val chapterName = if (chapters != null && currentChapterIndex < chapters!!.size) {
            chapters!![currentChapterIndex].title
        } else {
            ""
        }
        tvChapterTitle!!.text = chapterName
    }

    /** 计算全书预估总页数（章节数 * 当前章节页码） */
    private fun getTotalBookPages(): Int {
        if (chapters == null || checkNotNull(chapters).isEmpty()) return 1
        val chapterPages = readerView?.totalPages ?: 1
        return checkNotNull(chapters).size * chapterPages
    }

    private fun saveProgress() {
        if (chapters == null || fileKey == null) return
        val chIdx = currentChapterIndex
        val pageIdx = checkNotNull(readerView).currentPage
        val totalCh = checkNotNull(chapters).size

        // ✅ [Phase 2] 使用协程替代 Thread，避免频繁创建销毁线程
        bgScope?.launch(Dispatchers.IO) {
            try {
                getReaderRepository().saveProgress(checkNotNull(fileKey), chIdx, pageIdx, totalCh)
            } catch (e: Exception) {
                error("Progress", "saveProgress failed", e)
            }
        }
    }

    private fun persistBookRecord(
        fp: String?,
        fu: String?,
        isContent: Boolean,
        totalChapters: Int
    ) {
        if (fileKey == null) return
        try {
            val format = if ((fp != null && fp.lowercase(Locale.getDefault()).endsWith(".epub"))
                || (fu != null && fu.lowercase(Locale.getDefault()).endsWith(".epub"))
            ) "epub" else "txt"
            getReaderRepository().persistBookRecord(
                checkNotNull(fileKey), fp, fu, isContent, totalChapters, format
            )
        } catch (e: Exception) {
            error("Progress", "persistBookRecord failed", e)
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
    }

    override fun onResume() {
        super.onResume()
        if (readerView != null) {
            // ★ 批量应用所有设置，只触发一次 layoutPages（原先 5+ 次重排→1 次）
            // 位置恢复由 layoutPages 内部基于文字指纹自动完成
            checkNotNull(readerView).beginBatchUpdate()
            checkNotNull(readerView).setTextSize(checkNotNull(prefs).getFloat("text_size", 28f))
            checkNotNull(readerView).setLineSpacing(checkNotNull(prefs).getInt("line_spacing", 14) / 10f)
            checkNotNull(readerView).setParagraphSpacing(checkNotNull(prefs).getInt("para_spacing", 10) / 10f)
            checkNotNull(readerView).setHorizontalMargin(checkNotNull(prefs).getInt("horizontal_margin", 10))
            checkNotNull(readerView).setFirstLineIndent(checkNotNull(prefs).getBoolean("first_line_indent", false))
            val fp: String = checkNotNull(prefs).getString("font_path", "") ?: ""
            if (!fp.isEmpty()) {
                val ff = File(fp)
                if (ff.exists()) checkNotNull(readerView).setCustomTypeface(Typeface.createFromFile(ff))
            }
            checkNotNull(readerView).commitBatchUpdate()
        }
        val night = checkNotNull(prefs).getBoolean("night_mode", false)
        applyNightMode(night)

        // 确保沉浸式全屏不被系统/Activity 恢复覆盖
        applyImmersiveMode()
    }

    override fun onPause() {
        log("Lifecycle", "onPause")
        super.onPause()
        saveProgress()
        if (readingStartTime > 0 && fileKey != null) {
            val elapsed = System.currentTimeMillis() - readingStartTime
            if (elapsed >= 1000) {
                val total = checkNotNull(prefs).getLong("read_time_" + fileKey, 0)
                checkNotNull(prefs).edit()
                    .putLong("read_time_" + fileKey, total + elapsed)
                    .putLong(
                        "total_read_time",
                        checkNotNull(prefs).getLong("total_read_time", 0) + elapsed
                    )
                    .apply()
            }
            readingStartTime = System.currentTimeMillis()
        }
    }

    override fun onBackPressed() {
        log("Nav", "onBackPressed: fullOverlayMode=" + fullOverlayMode)
        if (fullOverlayMode != FullOverlayMode.NONE) {
            exitFullOverlay()
            return
        }
        // ★ 中断后台布局，避免布局结果回调已销毁的 Activity
        if (readerView != null) checkNotNull(readerView).cancelLayout()
        super.onBackPressed()
    }

    override fun onDestroy() {
        log("Lifecycle", "onDestroy")
        // ✅ [Phase 2] 清理后台协程，避免内存泄漏
        bgScope?.cancel("Activity destroyed")
        bgScope = null
        
        // ✅ [Phase 2] 移除 Handler 中所有 pending 消息
        uiHandler?.removeCallbacksAndMessages(null)
        
        isDestroyed = true
        super.onDestroy()
    }

    private fun applyNightMode(night: Boolean) {
        val bg = if (night) -0xddddde else Color.WHITE
        val fg = if (night) -0x444445 else Color.BLACK

        val root = findViewById<View?>(R.id.root_container)
        if (root != null) root.setBackgroundColor(bg)

        if (topStatusBar != null) checkNotNull(topStatusBar).setBackgroundColor(bg)
        if (bottomMenu != null) checkNotNull(bottomMenu).setBackgroundColor(if (night) -0xd5d5d6 else -0xa0a0b)
        if (fullOverlay != null) checkNotNull(fullOverlay).setBackgroundColor(bg)
        if (loadingOverlay != null) checkNotNull(loadingOverlay).setBackgroundColor(bg)

        for (id in intArrayOf(
            R.id.status_time,
            R.id.status_chapter,
            R.id.status_battery,
            R.id.btn_back,
            R.id.btn_toc,
            R.id.btn_bookmark,
            R.id.btn_settings,
            R.id.btn_show_log,
            R.id.btn_font_minus,
            R.id.btn_font_plus,
            R.id.btn_bright_minus,
            R.id.btn_bright_plus,
            R.id.btn_full_refresh,
            R.id.tv_chapter_page,
            R.id.tv_chapter_title,
            R.id.tv_global_page,
            R.id.full_overlay_title,
            R.id.full_overlay_back
        )) {
            val v = findViewById<View?>(id)
            if (v is TextView) v.setTextColor(fg)
        }
    }

    /** 将封面图字节写入磁盘缓存（书架列表显示用） */
    private fun cacheCoverToDisk(coverBytes: ByteArray?) {
        if (coverBytes == null || coverBytes.isEmpty()) return
        val key = fileKey ?: return
        try {
            val dir = File(cacheDir, "covers")
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, key.hashCode().toString(16) + ".jpg")
            FileOutputStream(f).use { it.write(coverBytes) }
            log("Cover", "封面已缓存: " + f.absolutePath)
        } catch (e: Exception) {
            log("Cover", "封面缓存失败: " + e.message)
        }
    }

    companion object {
        private const val PREFS_NAME = "eink_reader_prefs"

        private const val LOADING_TIMEOUT_MS = 30000L
    }
}

