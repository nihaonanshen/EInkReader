package com.einkreader.ui.library;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.einkreader.R;

/**
 * 墨水屏刷新设置
 * 设置每翻几页全屏刷新一次
 */
public class RefreshSettingsActivity extends Activity {

    private SharedPreferences prefs;
    private SeekBar seekRefreshInterval;
    private TextView labelInterval;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 简单布局
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFFFFFFF);
        root.setPadding(20, 20, 20, 20);

        TextView title = new TextView(this);
        title.setText("刷新设置");
        title.setTextSize(22);
        title.setTextColor(0xFF000000);
        title.setPadding(0, 0, 0, 20);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText("墨水屏翻页后会有残影，需要定期全屏刷新清除残影。");
        desc.setTextSize(15);
        desc.setTextColor(0xFF666666);
        desc.setPadding(0, 0, 0, 8);
        root.addView(desc);

        TextView desc2 = new TextView(this);
        desc2.setText("设置每翻几页做一次全屏刷新（越小越清晰，但闪烁越多）：");
        desc2.setTextSize(15);
        desc2.setTextColor(0xFF666666);
        desc2.setPadding(0, 0, 0, 20);
        root.addView(desc2);

        // 滑块
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        labelInterval = new TextView(this);
        labelInterval.setTextSize(18);
        labelInterval.setTextColor(0xFF000000);
        labelInterval.setPadding(0, 0, 16, 0);
        row.addView(labelInterval);

        seekRefreshInterval = new SeekBar(this);
        seekRefreshInterval.setMax(20);
        row.addView(seekRefreshInterval, new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(row);

        setContentView(root);

        prefs = getSharedPreferences("eink_reader_prefs", MODE_PRIVATE);

        int saved = prefs.getInt("refresh_interval", 8);
        seekRefreshInterval.setProgress(saved);
        labelInterval.setText("每 " + saved + " 页");

        seekRefreshInterval.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (progress < 1) progress = 1;
                labelInterval.setText("每 " + progress + " 页");
                prefs.edit().putInt("refresh_interval", progress).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                Toast.makeText(RefreshSettingsActivity.this, "已保存", Toast.LENGTH_SHORT).show();
            }
        });
    }
}
