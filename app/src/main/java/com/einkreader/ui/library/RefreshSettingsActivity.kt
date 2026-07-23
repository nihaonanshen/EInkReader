package com.einkreader.ui.library

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class RefreshSettingsActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var seekRefreshInterval: SeekBar
    private lateinit var labelInterval: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(20, 20, 20, 20)
        }

        TextView(this).apply {
            text = "刷新设置"
            setTextSize(22f)
            setTextColor(0xFF000000.toInt())
            setPadding(0, 0, 0, 20)
        }.also { root.addView(it) }

        TextView(this).apply {
            text = "墨水屏翻页后会有残影，需要定期全屏刷新清除残影。"
            textSize = 15f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 0, 0, 8)
        }.also { root.addView(it) }

        TextView(this).apply {
            text = "设置每翻几页做一次全屏刷新（越小越清晰，但闪烁越多）："
            textSize = 15f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 0, 0, 20)
        }.also { root.addView(it) }

        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        labelInterval = TextView(this).apply {
            textSize = 18f
            setTextColor(0xFF000000.toInt())
            setPadding(0, 0, 16, 0)
        }
        row.addView(labelInterval)

        seekRefreshInterval = SeekBar(this).apply {
            max = 20
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(seekRefreshInterval)
        root.addView(row)

        setContentView(root)

        prefs = getSharedPreferences("eink_reader_prefs", MODE_PRIVATE)
        val saved = prefs.getInt("refresh_interval", 8)
        seekRefreshInterval.progress = saved
        labelInterval.text = "每 $saved 页"

        seekRefreshInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val p = if (progress < 1) 1 else progress
                labelInterval.text = "每 $p 页"
                prefs.edit().putInt("refresh_interval", p).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                Toast.makeText(this@RefreshSettingsActivity, "已保存", Toast.LENGTH_SHORT).show()
            }
        })
    }
}
