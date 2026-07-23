package com.einkreader.ui.reader

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class DebugLogActivity : Activity() {
    private var logView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(-1) // 0xFFFFFFFF as signed
        }

        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0x33333333.toInt())
            setPadding(dp(16), dp(10), dp(16), dp(10))

            val btnBack = TextView(this@DebugLogActivity).apply {
                text = "\u2190 \u8fd4\u56de"
                setTextColor(-1)
                textSize = 18f
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener { finish() }
            }
            addView(btnBack)

            val tvTitle = TextView(this@DebugLogActivity).apply {
                text = "\u8c03\u8bd5\u65e5\u5fd7"
                setTextColor(-1)
                textSize = 20f
                gravity = Gravity.CENTER
            }
            addView(tvTitle, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(titleBar, LinearLayout.LayoutParams(-1, dp(50)))

        val btnClear = TextView(this).apply {
            text = "\u6e05\u7a7a\u65e5\u5fd7"
            setTextColor((-0xffffff01).toInt())
            textSize = 16f
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener {
                DebugLog.clear()
                logView?.setText("\u65e5\u5fd7\u5df2\u6e05\u7a7a\n")
            }
        }
        root.addView(btnClear)

        val scroll = ScrollView(this)
        logView = TextView(this).apply {
            textSize = 12f
            setTextColor((-0xffffff01).toInt())
            typeface = Typeface.MONOSPACE
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        scroll.addView(logView!!)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val log = DebugLog.getLog()
        val sb = StringBuilder().apply {
            append("\u3010\u65e5\u5fd7\u6587\u4ef6\u4f4d\u7f6e\u3011\n")
            append(DebugLog.getLogFilePath()).append("\n")
            append("\uff08\u5728\u6587\u4ef6\u7ba1\u7406\u5668-\u4e0b\u8f7d \u91cc\u53ef\u4ee5\u627e\u5230\uff09\n")
            append("Device: ${android.os.Build.MODEL} SDK:${android.os.Build.VERSION.SDK_INT} Android:${android.os.Build.VERSION.RELEASE}\n")
            append("----\n\n")
            if (log.isEmpty()) {
                append("\uff08\u6682\u65e0\u65e5\u5fd7\uff09\n\u63d0\u793a\uff1a\u6253\u5f00\u4e00\u672c\u4e66\u89e6\u53d1\u5e03\u5c40\u64cd\u4f5c\u540e\u65e5\u5fd7\u624d\u4f1a\u51fa\u73b0")
            } else {
                append(log)
            }
        }
        logView?.text = sb.toString()
    }

    private fun dp(dpVal: Int): Int {
        return (dpVal * resources.displayMetrics.density + 0.5f).toInt()
    }
}
