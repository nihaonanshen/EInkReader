package com.einkreader.ui.reader;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 调试日志查看页面
 */
public class DebugLogActivity extends Activity {

    private TextView logView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFFFFFFF);

        // 标题栏
        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setBackgroundColor(0xFF333333);
        titleBar.setPadding(dp(16), dp(10), dp(16), dp(10));

        TextView btnBack = new TextView(this);
        btnBack.setText("← 返回");
        btnBack.setTextColor(0xFFFFFFFF);
        btnBack.setTextSize(18);
        btnBack.setPadding(dp(8), dp(6), dp(8), dp(6));
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });
        titleBar.addView(btnBack);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("调试日志");
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setTextSize(20);
        tvTitle.setGravity(Gravity.CENTER);
        titleBar.addView(tvTitle, new LinearLayout.LayoutParams(0, -2, 1f));

        root.addView(titleBar, new LinearLayout.LayoutParams(-1, dp(50)));

        // 清空按钮
        TextView btnClear = new TextView(this);
        btnClear.setText("清空日志");
        btnClear.setTextColor(0xFF000000);
        btnClear.setTextSize(16);
        btnClear.setPadding(dp(16), dp(8), dp(16), dp(8));
        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                DebugLog.clear();
                logView.setText("日志已清空\n");
            }
        });
        root.addView(btnClear);

        // 日志内容
        ScrollView scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setTextColor(0xFF000000);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setPadding(dp(8), dp(8), dp(8), dp(8));
        scroll.addView(logView);

        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        String log = DebugLog.getLog();
        StringBuilder sb = new StringBuilder();
        sb.append("【日志文件位置】\n").append(DebugLog.getLogFilePath()).append("\n");
        sb.append("(在文件管理器-下载 里可以找到)\n");
        sb.append("设备: " + android.os.Build.MODEL + " SDK:" + android.os.Build.VERSION.SDK_INT + " Android:" + android.os.Build.VERSION.RELEASE + "\n");
        sb.append("----\n\n");
        if (log.isEmpty()) {
            sb.append("(暂无日志)\n提示：打开一本书触发布局操作后日志才会出现");
        } else {
            sb.append(log);
        }
        logView.setText(sb.toString());
    }

    private int dp(int dp) {
        return (int)(dp * getResources().getDisplayMetrics().density + 0.5f);
    }
}

