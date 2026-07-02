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
import android.widget.Switch;
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

    private SeekBar seekLineSpacing, seekParaSpacing;
        private TextView labelTextSize, labelLineSpacing, labelParaSpacing;
        private TextView btnFontMinus, btnFontPlus;
        private ListView fontList;
        private List<FontItem> fonts = new ArrayList<FontItem>();
        private Switch switchFirstLineIndent;

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

        seekLineSpacing = (SeekBar) findViewById(R.id.seek_line_spacing);
                seekParaSpacing = (SeekBar) findViewById(R.id.seek_para_spacing);
                labelTextSize = (TextView) findViewById(R.id.label_text_size);
                labelLineSpacing = (TextView) findViewById(R.id.label_line_spacing);
                labelParaSpacing = (TextView) findViewById(R.id.label_para_spacing);
                btnFontMinus = (TextView) findViewById(R.id.btn_font_minus);
                btnFontPlus = (TextView) findViewById(R.id.btn_font_plus);
                fontList = (ListView) findViewById(R.id.font_list);
                switchFirstLineIndent = (Switch) findViewById(R.id.switch_first_line_indent);
        
                // ★ 加载首行缩进设置
                boolean indentEnabled = prefs.getBoolean("first_line_indent", false); // 默认关闭
                switchFirstLineIndent.setChecked(indentEnabled);
        
                switchFirstLineIndent.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                        prefs.edit().putBoolean("first_line_indent", isChecked).apply();
                        setResult(RESULT_OK);
                    }
                });

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
        int savedTextSize = (int) prefs.getFloat("text_size", 28f);
        int savedLineSpacing = prefs.getInt("line_spacing", 15);
        int savedParaSpacing = prefs.getInt("para_spacing", 18);

        seekLineSpacing.setProgress(savedLineSpacing);
        seekParaSpacing.setProgress(savedParaSpacing);

        labelTextSize.setText(String.valueOf(savedTextSize));
        labelLineSpacing.setText(String.format("%.1f", savedLineSpacing / 10f));
        labelParaSpacing.setText(String.format("%.1f", savedParaSpacing / 10f));

        // 字号快捷按钮
        btnFontMinus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                float cur = prefs.getFloat("text_size", 28f);
                float next = Math.max(14f, cur - 1);
                prefs.edit().putFloat("text_size", next).apply();
                labelTextSize.setText(String.valueOf((int) next));
                setResult(RESULT_OK);
            }
        });
        btnFontPlus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                float cur = prefs.getFloat("text_size", 28f);
                float next = Math.min(64f, cur + 1);
                prefs.edit().putFloat("text_size", next).apply();
                labelTextSize.setText(String.valueOf((int) next));
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


