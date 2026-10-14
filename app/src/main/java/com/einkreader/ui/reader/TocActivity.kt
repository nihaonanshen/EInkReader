package com.einkreader.ui.reader

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.einkreader.R

class TocActivity : Activity() {

    // ✅ [P2-11 2026-10-15] sSelectedChapter 已删：全仓无任何写入点
    //   （本类已不被 startActivity 打开——目录/书签已内嵌进 ReaderActivity 的 fullOverlay，
    //    其 onActivityResult 也是空壳），该字段是唯一的跨 Activity 静态跳转通道，属死链。
    //   类主体（独立全屏目录页 UI）保留，未来想重新启用可直接接回。

    companion object {
        const val EXTRA_CHAPTERS = "chapter_titles"
        const val EXTRA_CURRENT_CHAPTER = "current_chapter"
        const val RESULT_CHAPTER_INDEX = "chapter_index"
    }

    private var ITEMS_PER_PAGE = 10
    private lateinit var chapterTitles: ArrayList<String>
    private var currentChapter = 0
    private var totalPages = 1
    private var currentPage = 0
    private lateinit var listContainer: LinearLayout
    private lateinit var tvPageInfo: TextView
    private lateinit var btnPrev: TextView
    private lateinit var btnNext: TextView

    // 设计令牌（日/夜）——与书库/阅读页一致
    private var night = false
    private var colorBg = 0
    private var colorSurface = 0
    private var colorSurfaceMuted = 0
    private var colorInk = 0
    private var colorInkMuted = 0
    private var colorDivider = 0

    private fun dp(dpVal: Int): Int = Math.round(dpVal * resources.displayMetrics.density.toFloat())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)

        // 夜间模式：与阅读页同源 prefs 切换
        night = getSharedPreferences("eink_reader_prefs", MODE_PRIVATE).getBoolean("night_mode", false)
        val r = resources
        colorBg = r.getColor(if (night) R.color.background_night else R.color.background)
        colorSurface = r.getColor(if (night) R.color.surface_night else R.color.surface)
        colorSurfaceMuted = r.getColor(if (night) R.color.surface_muted_night else R.color.surface_muted)
        colorInk = r.getColor(if (night) R.color.ink_night else R.color.ink)
        colorInkMuted = r.getColor(if (night) R.color.ink_muted_night else R.color.ink_muted)
        colorDivider = r.getColor(if (night) R.color.divider_night else R.color.divider)

        buildLayout()
        val availableHeight = (resources.displayMetrics.heightPixels / resources.displayMetrics.density).toInt() - 120
        ITEMS_PER_PAGE = kotlin.math.max(3, availableHeight / 48)
        chapterTitles = intent.getStringArrayListExtra(EXTRA_CHAPTERS) ?: ArrayList()
        currentChapter = intent.getIntExtra(EXTRA_CURRENT_CHAPTER, 0)
        if (chapterTitles.isEmpty()) {
            Toast.makeText(this, "无目录数据", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        totalPages = kotlin.math.max(1, (chapterTitles.size + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE)
        currentPage = currentChapter / ITEMS_PER_PAGE
        listContainer.post { renderPage() }
    }

    private fun buildLayout() {
        val r = resources
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(colorBg)
        }

        // 顶栏：暖纸底 + 返回 + 居中标题
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(colorSurfaceMuted); setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        titleBar.addView(TextView(this).apply {
            text = "\u2190 返回"; textSize = 18f; setTextColor(colorInk)
            setOnClickListener { finish() }
        })
        titleBar.addView(TextView(this).apply {
            text = "目录"; textSize = 20f; setTextColor(colorInk); setTypeface(Typeface.DEFAULT_BOLD); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(titleBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))

        // 列表区
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(listContainer)

        // 底栏：上一页 / 页码 / 下一页（按压瞬时反色）
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(colorSurfaceMuted); setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        fun makeNavButton(text: String): TextView = TextView(this).apply {
            this.text = text; textSize = 18f; gravity = Gravity.CENTER
            minHeight = dp(48); setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundResource(if (night) R.drawable.bg_button_eink_night else R.drawable.bg_button_eink)
            setTextColor(r.getColorStateList(if (night) R.color.btn_text_eink_night else R.color.btn_text_eink))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        btnPrev = makeNavButton("\u25C0 上一页")
        btnNext = makeNavButton("下一页 \u25B6")
        tvPageInfo = TextView(this).apply {
            textSize = 18f; setTextColor(colorInk); gravity = Gravity.CENTER
            setTypeface(Typeface.DEFAULT_BOLD); minHeight = dp(48)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        bottomBar.apply { addView(btnPrev); addView(tvPageInfo); addView(btnNext) }
        root.addView(bottomBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)))
        btnPrev.setOnClickListener { prevPage() }; btnNext.setOnClickListener { nextPage() }
        setContentView(root)
    }

    private fun renderPage() {
        listContainer.removeAllViews()
        val startIdx = currentPage * ITEMS_PER_PAGE
        val endIdx = kotlin.math.min(startIdx + ITEMS_PER_PAGE, chapterTitles.size)
        val pad = dp(16); var totalHeight = listContainer.height
        if (totalHeight <= 0) totalHeight = dp(400)
        val itemCount = endIdx - startIdx
        var itemHeight = if (itemCount > 0) totalHeight / itemCount else dp(50)
        val minH = dp(40); if (itemHeight < minH) itemHeight = minH
        for (i in startIdx until endIdx) {
            val isCurrent = i == currentChapter
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, dp(8), pad, dp(8))
                // 当前章节：浅色高亮；其余：透出暖纸背景
                setBackgroundColor(if (isCurrent) colorSurfaceMuted else android.graphics.Color.TRANSPARENT)
            }
            row.addView(TextView(this).apply {
                text = "${i + 1}."
                textSize = 18f; width = dp(44)
                setTextColor(if (isCurrent) colorInk else colorInkMuted)
                if (isCurrent) setTypeface(Typeface.DEFAULT_BOLD)
            })
            row.addView(TextView(this).apply {
                text = chapterTitles.getOrElse(i) { "第${i + 1}章" }; textSize = 18f
                setTextColor(colorInk)
                if (isCurrent) setTypeface(Typeface.DEFAULT_BOLD)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val fi = i
            row.setOnTouchListener { _, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> return@setOnTouchListener true
                    android.view.MotionEvent.ACTION_UP -> {
                        setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_CHAPTER_INDEX, fi))
                        DebugLog.log("Toc", "click idx=$fi total=${chapterTitles.size}")
                        finish(); true
                    }
                    else -> false
                }
            }
            listContainer.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, itemHeight))
            if (i < endIdx - 1) {
                val div = View(this).apply {
                    setBackgroundColor(colorDivider)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.round(1f * resources.displayMetrics.density))
                }
                listContainer.addView(div)
            }
        }
        tvPageInfo.text = "${currentPage + 1} / $totalPages"
        val pe = currentPage > 0; val ne = currentPage < totalPages - 1
        btnPrev.isEnabled = pe; btnPrev.alpha = if (pe) 1.0f else 0.3f
        btnNext.isEnabled = ne; btnNext.alpha = if (ne) 1.0f else 0.3f
        DebugLog.log("Toc", "renderPage: cp=$currentPage tp=$totalPages")
    }

    private fun prevPage() { if (currentPage > 0) { currentPage--; renderPage() } }
    private fun nextPage() { if (currentPage < totalPages - 1) { currentPage++; renderPage() } }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PAGE_UP -> { prevPage(); true }
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { nextPage(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return keyCode in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN)
    }
}
