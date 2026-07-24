package com.einkreader.ui.settings

import android.app.Activity
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

class AboutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(-0x1) // 0xFFFFFFFF as signed int
            setPadding(20, 30, 20, 20)
        }
        addText(root, "墨水屏阅读器", 24f, true, 0, 6)
        addText(root, "版本 0.0.6 (Build 7)", 16f, false, 0, 4)
        addText(root, "", 16f, false, 0, 6)
        addText(root, "项目说明", 18f, true, 0, 4)
        addText(root, "\u2022 零基础作者，全程 AI 辅助开发（Reasonix）", 15f, false, -0x99999a.toInt(), 2)
        addText(root, "\u2022 纯 Kotlin + Rust 混合架构", 15f, false, -0x99999a.toInt(), 2)
        addText(root, "\u2022 开源协议: GPL-3.0", 15f, false, -0x99999a.toInt(), 2)
        addText(root, "阅读统计", 18f, true, 0, 4)
        val prefs: SharedPreferences = getSharedPreferences("eink_reader_prefs", MODE_PRIVATE)
        val totalMs = prefs.getLong("total_read_time", 0L)
        val hours = (totalMs / 3600000).toInt()
        val mins = ((totalMs % 3600000) / 60000).toInt()
        addText(root, "\u2022 累计阅读: $hours 小时 $mins 分钟", 15f, false, 0, 2)
        addText(root, "", 16f, false, 0, 10)
        addText(root, "功能特点", 18f, true, 0, 4)
        addText(root, "\u2022 支持 TXT / EPUB 格式", 15f, false, 0, 2)
        addText(root, "\u2022 自动检测文本编码（GBK/UTF-8/Big5/GB18030）", 15f, false, 0, 2)
        addText(root, "\u2022 智能提取中英文书籍目录（章节/回/卷/Chapter）", 15f, false, 0, 2)
        addText(root, "\u2022 EPUB 内嵌图片显示", 15f, false, 0, 2)
        addText(root, "\u2022 可调节字体大小 / 行距 / 段距 / 页边距", 15f, false, 0, 2)
        addText(root, "\u2022 支持更换自定义字体（TTF/OTF 扫描）", 15f, false, 0, 2)
        addText(root, "\u2022 墨水屏刷新优化（GC16 / A2 / DU 自适应）", 15f, false, 0, 2)
        addText(root, "\u2022 夜间模式切换", 15f, false, 0, 2)
        addText(root, "\u2022 书架管理（按时间/名称/格式排序）", 15f, false, 0, 2)
        addText(root, "\u2022 书签管理与快速跳转", 15f, false, 0, 2)
        addText(root, "\u2022 阅读进度自动保存与断点续读", 15f, false, 0, 2)
        addText(root, "\u2022 阅读统计（累计阅读时长）", 15f, false, 0, 2)
        addText(root, "设备信息", 18f, true, 0, 4)
        addText(root, "型号: ${Build.MODEL}", 15f, false, 0, 2)
        addText(root, "系统: Android ${Build.VERSION.RELEASE}", 15f, false, 0, 2)
        addText(root, "API: ${Build.VERSION.SDK_INT}", 15f, false, 0, 10)
        addText(root, "使用提示", 18f, true, 0, 4)
        addText(root, "\u2022 将 TXT/EPUB 文件放到 SD 卡任意目录即可自动识别", 14f, false, -0x99999a.toInt(), 2)
        addText(root, "\u2022 将 TTF 字体文件放到 /sdcard/fonts/ 可在阅读设置中选用", 14f, false, -0x99999a.toInt(), 2)
        addText(root, "\u2022 长按书架书籍可删除", 14f, false, -0x99999a.toInt(), 2)
        addText(root, "\u2022 屏幕中央点击显示菜单和状态栏", 14f, false, -0x99999a.toInt(), 2)
        addText(root, "\u2022 音量键 / 翻页键可上下翻页", 14f, false, -0x99999a.toInt(), 2)
        setContentView(root)
    }

    private fun addText(root: LinearLayout, text: String, textSize: Float, bold: Boolean,
                        color: Int, marginBottomDp: Int) {
        TextView(this).apply {
            this.text = text
            this.textSize = textSize
            setTextColor(if (color != 0) color else (-0x1000000).toInt())
            paint.isFakeBoldText = bold
        }.let { tv ->
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (marginBottomDp > 0) {
                    bottomMargin = (marginBottomDp * resources.displayMetrics.density + 0.5f).toInt()
                }
            }
            root.addView(tv, lp)
        }
    }
}
