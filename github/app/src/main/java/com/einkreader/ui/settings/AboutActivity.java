package com.einkreader.ui.settings;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 关于页面 —— 显示版本信息和设备信息
 */
public class AboutActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFFFFFFF);
        root.setPadding(20, 30, 20, 20);

        addText(root, "墨水屏阅读器", 24, true, 0, 6);
        addText(root, "版本 0.0.1", 16, false, 0, 4);
        addText(root, "", 16, false, 0, 6);

        addText(root, "项目说明", 18, true, 0, 4);
        addText(root, "• 作者零基础起步，全程 AI 辅助开发", 15, false, 0xFF666666, 2);
        addText(root, "• AI Agent: Reasonix", 15, false, 0xFF666666, 2);
        addText(root, "• AI 模型: DeepSeek V4", 15, false, 0xFF666666, 2);
        addText(root, "• 开发环境: Android Studio + Gradle", 15, false, 0xFF666666, 10);

        // 阅读统计
        addText(root, "阅读统计", 18, true, 0, 4);
        SharedPreferences prefs = getSharedPreferences("eink_reader_prefs", MODE_PRIVATE);
        long totalMs = prefs.getLong("total_read_time", 0);
        int hours = (int)(totalMs / 3600000);
        int mins = (int)((totalMs % 3600000) / 60000);
        addText(root, "• 累计阅读: " + hours + " 小时 " + mins + " 分钟", 15, false, 0, 2);
        addText(root, "", 16, false, 0, 10);

        addText(root, "功能特点", 18, true, 0, 4);
        addText(root, "• 支持 TXT / EPUB 格式", 15, false, 0, 2);
        addText(root, "• 自动检测文本编码（GBK/UTF-8/Big5）", 15, false, 0, 2);
        addText(root, "• 智能提取中英文书籍目录", 15, false, 0, 2);
        addText(root, "• EPUB 图片显示", 15, false, 0, 2);
        addText(root, "• 可调节字体大小 / 行距 / 段距 / 边距", 15, false, 0, 2);
        addText(root, "• 支持更换字体（TTF/OTF）", 15, false, 0, 2);
        addText(root, "• 墨水屏刷新优化（局部/全局刷新）", 15, false, 0, 2);
        addText(root, "• 夜间模式切换", 15, false, 0, 2);
        addText(root, "• 书架排序（时间/名称/格式）", 15, false, 0, 2);
        addText(root, "• 阅读进度自动保存与恢复", 15, false, 0, 2);
        addText(root, "• 阅读统计（累计阅读时长）", 15, false, 0, 10);

        addText(root, "设备信息", 18, true, 0, 4);
        addText(root, "型号: " + Build.MODEL, 15, false, 0, 2);
        addText(root, "系统: Android " + Build.VERSION.RELEASE, 15, false, 0, 2);
        addText(root, "API: " + Build.VERSION.SDK_INT, 15, false, 0, 10);

        addText(root, "使用提示", 18, true, 0, 4);
        addText(root, "• 将 TXT/EPUB 文件放到 SD 卡上即可自动识别", 14, false, 0xFF666666, 2);
        addText(root, "• 将 TTF 字体文件放到 /sdcard/fonts/ 可在设置中选用", 14, false, 0xFF666666, 2);
        addText(root, "• 长按书籍可删除", 14, false, 0xFF666666, 2);
        addText(root, "• 屏幕中央点击显示菜单和状态栏", 14, false, 0xFF666666, 2);
        addText(root, "• 音量键/翻页键可上下翻页", 14, false, 0xFF666666, 2);

        setContentView(root);
    }

    private void addText(LinearLayout root, String text, int textSize,
                         boolean bold, int color, int marginBottomDp) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(textSize);
        if (color != 0) tv.setTextColor(color);
        else tv.setTextColor(0xFF000000);
        if (bold) tv.getPaint().setFakeBoldText(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        if (marginBottomDp > 0) {
            lp.bottomMargin = (int) (marginBottomDp * getResources().getDisplayMetrics().density + 0.5f);
        }
        root.addView(tv, lp);
    }
}
