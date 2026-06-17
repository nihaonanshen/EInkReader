package com.einkreader.ui.reader;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.einkreader.R;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.List;

/**
 * 阅读设置页面
 *
 * 可调整：字体大小、行距、段距、更换字体
 */
public class ReadingSettingsActivity extends Activity {

    public static final String KEY_PREV_KEYCODE = "key_prev";
    public static final String KEY_NEXT_KEYCODE = "key_next";

    private SharedPreferences prefs;

    private SeekBar seekTextSize, seekLineSpacing, seekParaSpacing;
    private TextView labelTextSize, labelLineSpacing, labelParaSpacing;
    private ListView fontList;
    private List<FontItem> fonts = new ArrayList<FontItem>();

    static class FontItem {
        String displayName;
        String filePath;
        Typeface typeface;

        FontItem(String displayName, String filePath) {
            this.displayName = displayName;
            this.filePath = filePath;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences("eink_reader_prefs", MODE_PRIVATE);

        seekTextSize = (SeekBar) findViewById(R.id.seek_text_size);
        seekLineSpacing = (SeekBar) findViewById(R.id.seek_line_spacing);
        seekParaSpacing = (SeekBar) findViewById(R.id.seek_para_spacing);
        labelTextSize = (TextView) findViewById(R.id.label_text_size);
        labelLineSpacing = (TextView) findViewById(R.id.label_line_spacing);
        labelParaSpacing = (TextView) findViewById(R.id.label_para_spacing);
        fontList = (ListView) findViewById(R.id.font_list);

        // ★ 左右边距
        SeekBar seekHorizontalMargin = (SeekBar) findViewById(R.id.seek_horizontal_margin);
        final TextView labelHorizontalMargin = (TextView) findViewById(R.id.label_horizontal_margin);

        int savedMargin = prefs.getInt("horizontal_margin", 10);
        seekHorizontalMargin.setProgress(savedMargin);
        labelHorizontalMargin.setText(String.valueOf(savedMargin));

        seekHorizontalMargin.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (progress < 10) progress = 10;
                if (progress > 60) progress = 60;
                labelHorizontalMargin.setText(String.valueOf(progress));
                prefs.edit().putInt("horizontal_margin", progress).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                setResult(RESULT_OK);
            }
        });

        // 加载保存的值
        int savedTextSize = (int) prefs.getFloat("text_size", 20f);
        int savedLineSpacing = prefs.getInt("line_spacing", 15);
        int savedParaSpacing = prefs.getInt("para_spacing", 18);

        seekTextSize.setProgress(savedTextSize);
        seekLineSpacing.setProgress(savedLineSpacing);
        seekParaSpacing.setProgress(savedParaSpacing);

        labelTextSize.setText(String.valueOf(savedTextSize));
        labelLineSpacing.setText(String.format("%.1f", savedLineSpacing / 10f));
        labelParaSpacing.setText(String.format("%.1f", savedParaSpacing / 10f));

        // 字体大小滑块
        seekTextSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (progress < 12) progress = 12;
                if (progress > 40) progress = 40;
                labelTextSize.setText(String.valueOf(progress));
                prefs.edit().putFloat("text_size", (float) progress).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                setResult(RESULT_OK);
            }
        });

        // 行距滑块
        seekLineSpacing.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (progress < 10) progress = 10;
                if (progress > 30) progress = 30;
                labelLineSpacing.setText(String.format("%.1f", progress / 10f));
                prefs.edit().putInt("line_spacing", progress).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                setResult(RESULT_OK);
            }
        });

        // 段距滑块
        seekParaSpacing.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (progress < 10) progress = 10;
                if (progress > 30) progress = 30;
                labelParaSpacing.setText(String.format("%.1f", progress / 10f));
                prefs.edit().putInt("para_spacing", progress).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                setResult(RESULT_OK);
            }
        });

        // 扫描字体
        scanFonts();

        fontList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= fonts.size()) return;
                FontItem font = fonts.get(position);
                if (font.filePath != null && !font.filePath.isEmpty()) {
                    prefs.edit().putString("font_path", font.filePath).apply();
                } else {
                    prefs.edit().remove("font_path").apply();
                }
                setResult(RESULT_OK);
                Toast.makeText(ReadingSettingsActivity.this,
                        "已选择: " + font.displayName, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * 扫描 SD 卡上的 TTF 字体文件
     */
    private void scanFonts() {
        fonts.clear();

        // 默认字体
        fonts.add(new FontItem("系统默认字体", ""));

        // 扫描常见目录
        File sdcard = Environment.getExternalStorageDirectory();
        File[] searchDirs = {
            sdcard,
            new File(sdcard, "fonts"),
            new File(sdcard, "Fonts"),
            new File(sdcard, "EInkReader/fonts"),
            new File(sdcard, "Download"),
        };

        for (File dir : searchDirs) {
            if (!dir.exists() || !dir.isDirectory()) continue;
            File[] ttfFiles = dir.listFiles(new FileFilter() {
                @Override
                public boolean accept(File file) {
                    String name = file.getName().toLowerCase();
                    return name.endsWith(".ttf") || name.endsWith(".otf");
                }
            });
            if (ttfFiles == null) continue;
            for (File f : ttfFiles) {
                String name = f.getName();
                int dot = name.lastIndexOf('.');
                String display = (dot > 0) ? name.substring(0, dot) : name;
                fonts.add(new FontItem(display, f.getAbsolutePath()));
            }
        }

        // 显示字体列表
        List<String> names = new ArrayList<String>();
        for (FontItem font : fonts) {
            names.add(font.displayName);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_list_item_1, names);
        fontList.setAdapter(adapter);
    }
}


