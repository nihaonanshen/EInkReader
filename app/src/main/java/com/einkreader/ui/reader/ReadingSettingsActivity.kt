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
 * ReadingSettingsActivity Kotlin version
 */
class ReadingSettingsActivity : Activity() {

    companion object {
        const val KEY_PREV_KEYCODE = "key_prev"
        const val KEY_NEXT_KEYCODE = "key_next"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var seekLineSpacing: SeekBar
    private lateinit var seekParaSpacing: SeekBar
    private lateinit var labelTextSize: TextView
    private lateinit var labelLineSpacing: TextView
    private lateinit var labelParaSpacing: TextView
    private lateinit var btnFontMinus: TextView
    private lateinit var btnFontPlus: TextView
    private lateinit var fontList: ListView
    private lateinit var switchFirstLineIndent: Switch
    private val fonts = mutableListOf<FontItem>()

    data class FontItem(val displayName: String, val filePath: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = getSharedPreferences("eink_reader_prefs", MODE_PRIVATE)

        seekLineSpacing = findViewById(R.id.seek_line_spacing)
        seekParaSpacing = findViewById(R.id.seek_para_spacing)
        labelTextSize = findViewById(R.id.label_text_size)
        labelLineSpacing = findViewById(R.id.label_line_spacing)
        labelParaSpacing = findViewById(R.id.label_para_spacing)
        btnFontMinus = findViewById(R.id.btn_font_minus)
        btnFontPlus = findViewById(R.id.btn_font_plus)
        fontList = findViewById(R.id.font_list)
        switchFirstLineIndent = findViewById(R.id.switch_first_line_indent)

        // Init first line indent
        val indentEnabled = prefs.getBoolean("first_line_indent", false)
        switchFirstLineIndent.isChecked = indentEnabled
        switchFirstLineIndent.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("first_line_indent", isChecked).apply()
            setResult(RESULT_OK)
        }

        // Horizontal margin
        val seekHM = findViewById<SeekBar>(R.id.seek_horizontal_margin)
        val labelHM = findViewById<TextView>(R.id.label_horizontal_margin)
        val savedMargin = prefs.getInt("horizontal_margin", 10)
        seekHM.progress = savedMargin
        labelHM.text = savedMargin.toString()
        seekHM.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, newProgress: Int, fromUser: Boolean) {
                var p = newProgress
                if (p < 10) p = 10
                if (p > 60) p = 60
                seekHM.progress = p
                labelHM.text = p.toString()
                prefs.edit().putInt("horizontal_margin", p).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) { setResult(RESULT_OK) }
        })

        // Init sizes
        val savedTextSize = prefs.getFloat("text_size", 28f).toInt()
        val savedLS = prefs.getInt("line_spacing", 15)
        val savedPS = prefs.getInt("para_spacing", 18)
        seekLineSpacing.setProgress(savedLS)
        seekParaSpacing.setProgress(savedPS)
        labelTextSize.text = savedTextSize.toString()
        labelLineSpacing.text = String.format("%.1f", savedLS / 10f)
        labelParaSpacing.text = String.format("%.1f", savedPS / 10f)

        // Font size buttons
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

        // Line spacing seekbar
        seekLineSpacing.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                var p = progress
                if (p < 10) p = 10
                if (p > 30) p = 30
                seekLineSpacing.progress = p
                labelLineSpacing.text = String.format("%.1f", progress / 10f)
                prefs.edit().putInt("line_spacing", progress).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) { setResult(RESULT_OK) }
        })

        // Paragraph spacing seekbar
        seekParaSpacing.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                var p = progress
                if (p < 10) p = 10
                if (p > 30) p = 30
                seekParaSpacing.progress = p
                labelParaSpacing.text = String.format("%.1f", progress / 10f)
                prefs.edit().putInt("para_spacing", progress).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) { setResult(RESULT_OK) }
        })

        // Scan fonts
        scanFonts()

        // Font list click listener
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
    }
}
