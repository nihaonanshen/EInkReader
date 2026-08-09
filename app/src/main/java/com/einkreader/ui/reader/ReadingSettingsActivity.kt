package com.einkreader.ui.reader

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Environment
import android.widget.*
import com.einkreader.R
import java.io.File
import java.io.FileFilter

/**
 * Reading Settings Activity - Kotlin version
 * Uses button-based increment/decrement for all spacing values (fixes ink screen lag)
 */
class ReadingSettingsActivity : Activity() {

    companion object {
        const val KEY_PREV_KEYCODE = "key_prev"
        const val KEY_NEXT_KEYCODE = "key_next"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var labelTextSize: TextView
    private lateinit var labelLineSpacing: TextView
    private lateinit var labelParaSpacing: TextView
    private lateinit var labelHorizontalMargin: TextView
    private lateinit var btnFontMinus: TextView
    private lateinit var btnFontPlus: TextView
    private lateinit var btnLineMinus: TextView
    private lateinit var btnLinePlus: TextView
    private lateinit var btnParaMinus: TextView
    private lateinit var btnParaPlus: TextView
    private lateinit var btnMarginMinus: TextView
    private lateinit var btnMarginPlus: TextView
    private lateinit var fontList: ListView
    private lateinit var switchFirstLineIndent: Switch
    private val fonts = mutableListOf<FontItem>()

    data class FontItem(val displayName: String, val filePath: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = getSharedPreferences("eink_reader_prefs", MODE_PRIVATE)

        // 夜间模式：设置页背景与文字（与阅读页一致的黑底灰字）
        if (prefs.getBoolean("night_mode", false)) {
            findViewById<android.view.View>(android.R.id.content).setBackgroundColor(-0xddddde)
            applyNightTextColor(findViewById<android.view.View>(android.R.id.content), -0x444445)
        }

        // Initialize UI elements
        labelTextSize = findViewById(R.id.label_text_size)
        labelLineSpacing = findViewById(R.id.label_line_spacing)
        labelParaSpacing = findViewById(R.id.label_para_spacing)
        labelHorizontalMargin = findViewById(R.id.label_horizontal_margin)
        btnFontMinus = findViewById(R.id.btn_font_minus)
        btnFontPlus = findViewById(R.id.btn_font_plus)
        btnLineMinus = findViewById(R.id.btn_line_minus)
        btnLinePlus = findViewById(R.id.btn_line_plus)
        btnParaMinus = findViewById(R.id.btn_para_minus)
        btnParaPlus = findViewById(R.id.btn_para_plus)
        btnMarginMinus = findViewById(R.id.btn_margin_minus)
        btnMarginPlus = findViewById(R.id.btn_margin_plus)
        fontList = findViewById(R.id.font_list)
        switchFirstLineIndent = findViewById(R.id.switch_first_line_indent)

        // --- First line indent ---
        val indentEnabled = prefs.getBoolean("first_line_indent", false)
        switchFirstLineIndent.isChecked = indentEnabled
        switchFirstLineIndent.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("first_line_indent", isChecked).apply()
            setResult(RESULT_OK)
        }

        // --- Load saved settings ---
        val savedTextSize = prefs.getFloat("text_size", 28f).toInt()
        val savedLS = prefs.getInt("line_spacing", 14)   // 1.4 default (×10 for 0.1 precision, range 0.1-3.0)
        val savedPS = prefs.getInt("para_spacing", 10)   // 1.0 default (adjusted for 6-inch e-ink)
        val savedHM = prefs.getInt("horizontal_margin", 10)

        labelTextSize.text = savedTextSize.toString()
        labelLineSpacing.text = String.format("%.1f", savedLS / 10f)
        labelParaSpacing.text = String.format("%.1f", savedPS / 10f)
        labelHorizontalMargin.text = savedHM.toString()

        // --- Text size buttons (±1 step) ---
        btnFontMinus.setOnClickListener {
            val cur = prefs.getFloat("text_size", 28f)
            val next = maxOf(14f, cur - 1)
            prefs.edit().putFloat("text_size", next).apply()
            labelTextSize.text = next.toInt().toString()
            setResult(RESULT_OK)
        }
        btnFontPlus.setOnClickListener {
            val cur = prefs.getFloat("text_size", 28f)
            val next = minOf(64f, cur + 1)
            prefs.edit().putFloat("text_size", next).apply()
            labelTextSize.text = next.toInt().toString()
            setResult(RESULT_OK)
        }

        // --- Line spacing buttons (step = 0.1, range 0.1-3.0 → 1-30) ---
        fun updateLineSpacing(value: Int) {
            val clamped = value.coerceIn(1, 30)
            labelLineSpacing.text = String.format("%.1f", clamped / 10f)
            prefs.edit().putInt("line_spacing", clamped).apply()
            setResult(RESULT_OK)
        }
        btnLineMinus.setOnClickListener {
            val current = prefs.getInt("line_spacing", 14)
            updateLineSpacing(current - 1)
        }
        btnLinePlus.setOnClickListener {
            val current = prefs.getInt("line_spacing", 14)
            updateLineSpacing(current + 1)
        }

        // --- Paragraph spacing buttons (step = 0.1, range 0.1-3.0 → 1-30) ---
        fun updateParaSpacing(value: Int) {
            val clamped = value.coerceIn(1, 30)
            labelParaSpacing.text = String.format("%.1f", clamped / 10f)
            prefs.edit().putInt("para_spacing", clamped).apply()
            setResult(RESULT_OK)
        }
        btnParaMinus.setOnClickListener {
            val current = prefs.getInt("para_spacing", 10)
            updateParaSpacing(current - 1)
        }
        btnParaPlus.setOnClickListener {
            val current = prefs.getInt("para_spacing", 10)
            updateParaSpacing(current + 1)
        }

        // --- Horizontal margin buttons (step = 1, range 10-60) ---
        fun updateMargin(value: Int) {
            val clamped = value.coerceIn(10, 60)
            labelHorizontalMargin.text = clamped.toString()
            prefs.edit().putInt("horizontal_margin", clamped).apply()
            setResult(RESULT_OK)
        }
        btnMarginMinus.setOnClickListener {
            val current = prefs.getInt("horizontal_margin", 10)
            updateMargin(current - 1)
        }
        btnMarginPlus.setOnClickListener {
            val current = prefs.getInt("horizontal_margin", 10)
            updateMargin(current + 1)
        }

        // --- Scan fonts ---
        scanFonts()

        // --- Font list click listener ---
        fontList.setOnItemClickListener { parent, view, position, id ->
            if (position < 0 || position >= fonts.size) return@setOnItemClickListener
            val font = fonts[position]
            if (font.filePath.isNotEmpty()) {
                prefs.edit().putString("font_path", font.filePath).apply()
            } else {
                prefs.edit().remove("font_path").apply()
            }
            setResult(RESULT_OK)
            Toast.makeText(this@ReadingSettingsActivity,
                "Selected: ${font.displayName}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 递归遍历视图树，将所有 TextView 设为指定颜色（夜间模式用；Switch 继承 TextView，需排除） */
    private fun applyNightTextColor(view: android.view.View, color: Int) {
        if (view is TextView && view !is Switch) {
            view.setTextColor(color)
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                applyNightTextColor(view.getChildAt(i), color)
            }
        }
    }

    private fun scanFonts() {
        fonts.clear()
        fonts.add(FontItem("Default System Font", ""))
        val sdcard = Environment.getExternalStorageDirectory()
        val searchDirs = arrayOf(
            sdcard,
            File(sdcard, "fonts"),
            File(sdcard, "Fonts"),
            File(sdcard, "EInkReader/fonts"),
            File(sdcard, "Download")
        )
        for (dir in searchDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            val ttfFiles = dir.listFiles(FileFilter { file ->
                val name = file.name.lowercase()
                name.endsWith(".ttf") || name.endsWith(".otf")
            }) ?: continue
            for (f in ttfFiles) {
                val name = f.name
                val dot = name.lastIndexOf(".")
                val display = if (dot > 0) name.substring(0, dot) else name
                fonts.add(FontItem(display, f.absolutePath))
            }
        }
        fonts.sortBy { it.displayName.lowercase() }
    }
}