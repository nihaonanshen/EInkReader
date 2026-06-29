package com.einkreader.ui.reader;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import com.einkreader.R;
import com.einkreader.core.model.Chapter;
import com.einkreader.core.parser.EpubParser;
import com.einkreader.core.parser.TxtParser;
import com.einkreader.core.refresh.EinkRefreshManager;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ReaderActivity extends Activity {

    private static final String PREFS_NAME = "eink_reader_prefs";
    private ReaderView readerView;
    private View topStatusBar, bottomMenu;
    private TextView statusTime, statusChapter, statusBattery, statusPage;
    private TextView btnBack, btnToc, btnFontMinus, btnFontPlus, btnSettings;
    private EinkRefreshManager refreshManager;
    private List<Chapter> chapters;
    private int currentChapterIndex = 0;
    private SharedPreferences prefs;
    private boolean menuVisible = false;
    private String filePath;
    private String fileKey;  // ★ I-7: 文件哈希标识（替代路径，防止文件移动后进度丢失）
    private Handler uiHandler;
    private long readingStartTime; // 本次阅读开始时间
    private volatile boolean isDestroyed = false;  // ★ I-5: 生命周期标记

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        // ★ 告诉系统：我自己处理音量键
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        setContentView(R.layout.activity_reader);

        uiHandler = new Handler(Looper.getMainLooper());
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        float savedBrightness = prefs.getFloat("screen_brightness", 0.5f);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = savedBrightness;
        getWindow().setAttributes(lp);

        refreshManager = new EinkRefreshManager(this);
        // 用 Log 记录 sysfs 检测结果（避免传 null 丢失信息）
        refreshManager.initialize(new EinkRefreshManager.RefreshCallback() {
            @Override public void onRefreshStart(EinkRefreshManager.RefreshMode mode) {}
            @Override public void onRefreshComplete(EinkRefreshManager.RefreshMode mode) {}
            @Override public void onModeDetected(Set<EinkRefreshManager.RefreshMode> modes) {
                DebugLog.log("Eink", "刷新模式: " + modes);
            }
            @Override public void onSysfsUnavailable() {
                DebugLog.log("Eink", "sysfs 不可用，使用标准刷新");
            }
        });

        readerView = (ReaderView) findViewById(R.id.reader_view);
        topStatusBar = findViewById(R.id.top_status_bar);
        bottomMenu = findViewById(R.id.bottom_menu);
        statusTime = (TextView) findViewById(R.id.status_time);
        statusChapter = (TextView) findViewById(R.id.status_chapter);
        statusBattery = (TextView) findViewById(R.id.status_battery);
        statusPage = (TextView) findViewById(R.id.status_page);
        btnBack = (TextView) findViewById(R.id.btn_back);
        btnToc = (TextView) findViewById(R.id.btn_toc);
        btnFontMinus = (TextView) findViewById(R.id.btn_font_minus);
        btnFontPlus = (TextView) findViewById(R.id.btn_font_plus);
        btnSettings = (TextView) findViewById(R.id.btn_settings);

        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });
        btnToc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { toggleMenu(false); openToc(); }
        });
        btnFontMinus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { adjustFontSize(-1f); }
        });
        btnFontPlus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { adjustFontSize(1f); }
        });
        btnSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleMenu(false);
                startActivityForResult(new Intent(ReaderActivity.this, ReadingSettingsActivity.class), 2001);
            }
        });

        // 夜间模式按钮
        final TextView btnNight = (TextView) findViewById(R.id.btn_night);
        if (btnNight != null) {
            final boolean isNight = prefs.getBoolean("night_mode", false);
            btnNight.setText(isNight ? "日间" : "夜间");
            if (isNight) { readerView.setNightMode(true); }
            btnNight.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean nowNight = !prefs.getBoolean("night_mode", false);
                    prefs.edit().putBoolean("night_mode", nowNight).apply();
                    readerView.setNightMode(nowNight);
                    btnNight.setText(nowNight ? "日间" : "夜间");
                    // 同步更新顶部状态栏和底部菜单颜色
                    int menuBg = nowNight ? 0xFF222222 : Color.WHITE;
                    int menuFg = nowNight ? 0xFFBBBBBB : Color.BLACK;
                    topStatusBar.setBackgroundColor(menuBg);
                    bottomMenu.setBackgroundColor(menuBg);
                    for (int id : new int[]{R.id.status_time, R.id.status_chapter, R.id.status_page, R.id.status_battery,
                            R.id.btn_back, R.id.btn_toc, R.id.btn_font_minus, R.id.btn_font_plus, R.id.btn_settings, R.id.btn_night}) {
                        View menuItem = findViewById(id);
                        if (menuItem instanceof TextView) ((TextView) menuItem).setTextColor(menuFg);
                    }
                    toggleMenu(false);
                }
            });
        }

        readerView.setOnPageChangeListener(new ReaderView.OnPageChangeListener() {
            @Override public void onPageChanged(int p, int t) { saveProgress(); updateStatusBar(); if (refreshManager != null) refreshManager.onPageTurn(readerView); }
            @Override public void onChapterChanged(int i) { }
            @Override public void onTapCenter() { toggleMenu(!menuVisible); }
            @Override public void onNeedPrevChapter() { goToPrevChapter(); }
            @Override public void onNeedNextChapter() { goToNextChapter(); }
        });

        // 取回焦点确保接收按键
        readerView.setFocusable(true);
        readerView.setFocusableInTouchMode(true);
        readerView.requestFocus();

        // 记录阅读开始时间
        readingStartTime = System.currentTimeMillis();

        loadBook();
    }

    // ★ dispatchKeyEvent：在系统处理音量键之前最外层拦截
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int c = event.getKeyCode();
        boolean isVolumeKey = (c == KeyEvent.KEYCODE_VOLUME_UP || c == KeyEvent.KEYCODE_VOLUME_DOWN);
        boolean isPageKey = (c == KeyEvent.KEYCODE_PAGE_UP || c == KeyEvent.KEYCODE_PAGE_DOWN);
        if (isVolumeKey || isPageKey) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                DebugLog.log("Key", "dispatch: " + (c == KeyEvent.KEYCODE_VOLUME_UP || c == KeyEvent.KEYCODE_PAGE_UP ? "上一页" : "下一页"));
                if (c == KeyEvent.KEYCODE_VOLUME_UP || c == KeyEvent.KEYCODE_PAGE_UP) {
                    if (readerView != null) readerView.prevPage();
                } else {
                    if (readerView != null) readerView.nextPage();
                }
            }
            // ACTION_DOWN 和 ACTION_UP 都消费掉
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
            || keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_PAGE_DOWN) return true;
        return super.onKeyUp(keyCode, event);
    }

    private void loadBook() {
        filePath = getIntent().getStringExtra("file_path");
        String fileUri = getIntent().getStringExtra("file_uri");
        DebugLog.log("Sys", "设备=" + android.os.Build.MODEL + " SDK=" + android.os.Build.VERSION.SDK_INT + " 系统=" + android.os.Build.VERSION.RELEASE);
        DebugLog.log("Sys", "屏幕=" + getResources().getDisplayMetrics().widthPixels + "x" + getResources().getDisplayMetrics().heightPixels + " dens=" + getResources().getDisplayMetrics().density + " dpi=" + getResources().getDisplayMetrics().densityDpi);
        DebugLog.log("Sys", "APP版本=" + "1.0.7");
        DebugLog.log("Reader", "loadBook: " + filePath);
        if ((filePath == null || filePath.isEmpty()) && fileUri == null) {
            Toast.makeText(this, "书籍路径为空", Toast.LENGTH_SHORT).show(); finish(); return;
        }
        final boolean isUri = (fileUri != null && fileUri.startsWith("content://"));
        if (isUri) filePath = fileUri;
        final String fp = filePath, fu = (fileUri != null) ? fileUri : filePath;
        final boolean isContent = isUri;

        // ★ I-7: 计算文件稳定标识（文件名 + 大小 + 修改时间），防止文件移动后进度丢失
        if (!isContent) {
            File f = new File(fp);
            if (f.exists()) {
                fileKey = f.getName() + "_" + f.length() + "_" + f.lastModified();
            } else {
                fileKey = fp.hashCode() + "";
            }
        } else {
            fileKey = fu.hashCode() + "";
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Chapter> pc;
                    String nl = fp != null ? fp.toLowerCase() : "";
                    boolean isEpub = nl.contains(".epub") || fu.contains(".epub");
                    boolean isTxt = nl.contains(".txt") || fu.contains(".txt");

                    DebugLog.log("Reader", "loadBook: " + (isEpub ? "EPUB" : "TXT") + " path=" + fp + " isUri=" + isUri);

                    if (isEpub) {
                        if (isContent) {
                            java.io.InputStream is = getContentResolver().openInputStream(android.net.Uri.parse(fu));
                            if (is == null) { showToastOnUi("无法读取"); return; }
                            File tf = new File(getCacheDir(), "t" + System.currentTimeMillis() + ".epub");
                            java.io.FileOutputStream fos = new java.io.FileOutputStream(tf);
                            byte[] buf = new byte[8192]; int n;
                            while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                            fos.close(); is.close();
                            EpubParser.EpubResult r = EpubParser.parse(tf); pc = r != null ? r.chapters : null;
                            tf.delete();
                        } else {
                            EpubParser.EpubResult r = EpubParser.parse(new File(fp)); pc = r != null ? r.chapters : null;
                        }
                    } else if (isTxt) {
                        if (isContent) {
                            java.io.InputStream is = getContentResolver().openInputStream(android.net.Uri.parse(fu));
                            if (is == null) { showToastOnUi("无法读取"); return; }
                            File tf = new File(getCacheDir(), "t" + System.currentTimeMillis() + ".txt");
                            java.io.FileOutputStream fos = new java.io.FileOutputStream(tf);
                            byte[] buf = new byte[8192]; int n;
                            while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                            fos.close(); is.close();
                            TxtParser.ParseResult r = TxtParser.parse(tf); pc = r != null ? r.chapters : null;
                            tf.delete();
                        } else {
                            TxtParser.ParseResult r = TxtParser.parse(new File(fp)); pc = r != null ? r.chapters : null;
                        }
                    } else {
                        uiHandler.post(new Runnable() {
                            @Override public void run() {
                                if (isDestroyed) return;
                                Toast.makeText(ReaderActivity.this, "不支持格式", Toast.LENGTH_SHORT).show();
                                finish();
                            }
                        });
                        return;
                    }

                    final List<Chapter> fch = pc;
                    uiHandler.post(new Runnable() {
                        @Override public void run() {
                            // ★ I-5: 检查 Activity 是否已销毁
                            if (isDestroyed) return;
                            if (fch == null || fch.isEmpty()) { Toast.makeText(ReaderActivity.this, "解析失败", Toast.LENGTH_SHORT).show(); finish(); return; }
                            chapters = fch;
                            try {
                                int sc = prefs.getInt("lc_" + fileKey, 0), sp = prefs.getInt("lp_" + fileKey, 0);
                                if (TocActivity.sSelectedChapter >= 0 && TocActivity.sSelectedChapter < chapters.size()) {
                                    // 目录跳转优先
                                    sc = TocActivity.sSelectedChapter;
                                    sp = 0;
                                    TocActivity.sSelectedChapter = -1;
                                    DebugLog.log("Reader", "tocJumpOK: idx=" + sc);
                                }
                                if (sc < 0 || sc >= chapters.size()) { sc = 0; sp = 0; }
                                currentChapterIndex = sc;
                                readerView.setChapter(chapters.get(currentChapterIndex));
                                readerView.applySettings();
                                if (sp > 0 && sp < readerView.getTotalPages()) readerView.goToPage(sp);
                            } catch (Exception e) { currentChapterIndex = 0; if (!chapters.isEmpty()) { readerView.setChapter(chapters.get(0)); readerView.applySettings(); } }
                            updateStatusBar();
                            DebugLog.log("Reader", "loadOK: ch=" + chapters.size() + " start=" + currentChapterIndex);
                        }
                    });
                } catch (final Exception e) {
                    uiHandler.post(new Runnable() {
                        @Override public void run() {
                            if (isDestroyed) return;
                            Toast.makeText(ReaderActivity.this, "加载失败", Toast.LENGTH_LONG).show();
                            DebugLog.log("Reader", "ERR: " + e.getMessage());
                            finish();
                        }
                    });
                }
            }
        }).start();
    }

    private void showToastOnUi(final String m) { uiHandler.post(new Runnable() { @Override public void run() { if (isDestroyed) return; Toast.makeText(ReaderActivity.this, m, Toast.LENGTH_SHORT).show(); finish(); } }); }


    private void switchChapter(int d) {
        if (chapters == null || chapters.isEmpty()) return;
        int ni = currentChapterIndex + d;
        if (ni < 0 || ni >= chapters.size()) return;
        currentChapterIndex = ni;
        readerView.setChapter(chapters.get(currentChapterIndex));
        updateStatusBar();
        DebugLog.log("Reader", "switchChapter: " + d);
    }

    private void goToPrevChapter() {
        if (currentChapterIndex > 0) { switchChapter(-1); readerView.goToPage(readerView.getTotalPages() - 1); }
        else Toast.makeText(this, "已经是第一章了", Toast.LENGTH_SHORT).show();
    }

    private void goToNextChapter() {
        if (currentChapterIndex < chapters.size() - 1) switchChapter(1);
        else Toast.makeText(this, "已经是最后一章了", Toast.LENGTH_SHORT).show();
    }

    private void toggleMenu(boolean s) { menuVisible = s; topStatusBar.setVisibility(s ? View.VISIBLE : View.GONE); bottomMenu.setVisibility(s ? View.VISIBLE : View.GONE); if (s) updateStatusBar(); }

    private void updateStatusBar() {
        if (statusTime != null) statusTime.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
        if (statusChapter != null && chapters != null && currentChapterIndex < chapters.size()) {
            String t = chapters.get(currentChapterIndex).getTitle();
            String chText = (t != null ? t : "第" + (currentChapterIndex + 1) + "章");
            // 追加章节进度：第3章/共20章
            statusChapter.setText(chText + "  (" + (currentChapterIndex + 1) + "/" + chapters.size() + ")");
        }
        if (statusPage != null && readerView != null) {
            statusPage.setText(readerView.getCurrentPage() + "/" + readerView.getTotalPages() + "页");
        }
        if (statusBattery != null) {
            try {
                Intent bi = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (bi != null) { int l = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), s = bi.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                    if (l >= 0 && s > 0) statusBattery.setText((int)((l / (float)s) * 100) + "%"); }
            } catch (Exception e) { }
        }
    }

    private void adjustFontSize(float d) {
        float c = Math.max(12f, Math.min(40f, prefs.getFloat("text_size", 26f) + d));
        prefs.edit().putFloat("text_size", c).apply();
        readerView.setTextSize(c);
        DebugLog.log("Reader", "fontSize: " + (int)c);
    }

    private void openToc() {
        if (chapters == null || chapters.isEmpty()) return;
        ArrayList<String> t = new ArrayList<>();
        for (Chapter ch : chapters) t.add(ch.getTitle() != null ? ch.getTitle() : "无标题");
        Intent i = new Intent(this, TocActivity.class);
        i.putStringArrayListExtra("chapter_titles", t);
        // ★ 修复：key 名必须与 TocActivity 的 EXTRA_CURRENT_CHAPTER ("current_chapter") 一致
        i.putExtra("current_chapter", currentChapterIndex);
        startActivityForResult(i, 1001);
        DebugLog.log("Reader", "openToc: " + chapters.size() + "ch");
    }

    private void saveProgress() {
        if (chapters == null || fileKey == null) return;
        try {
            int chIdx = currentChapterIndex;
            int pageIdx = readerView.getCurrentPage();
            prefs.edit()
                .putInt("lc_" + fileKey, chIdx)
                .putInt("lp_" + fileKey, pageIdx)
                .putInt("total_ch_" + fileKey, chapters.size())
                .apply();
            DebugLog.log("Reader", "saveProgress: ch=" + chIdx + "/" + chapters.size() + " page=" + pageIdx);
        } catch (Exception e) { }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 2001) { readerView.applySettings(); }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_PAGE_UP) {
            DebugLog.log("Key", "DOWN:上一页");
            if (readerView != null) { readerView.prevPage(); }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_PAGE_DOWN) {
            DebugLog.log("Key", "DOWN:下一页");
            if (readerView != null) { readerView.nextPage(); }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        DebugLog.log("Reader", "onResume sSel=" + TocActivity.sSelectedChapter + " ch=" + (chapters != null ? chapters.size() : 0));
        // ★ 跳转逻辑移到 loadOK 处（下文的 uiHandler.post 内），等 chapters 加载完再执行
        readerView.setTextSize(prefs.getFloat("text_size", 26f));
        readerView.setLineSpacing(prefs.getInt("line_spacing", 15) / 10f);
        readerView.setParagraphSpacing(prefs.getInt("para_spacing", 18) / 10f);
        readerView.setHorizontalMargin(prefs.getInt("horizontal_margin", 10));
        DebugLog.log("Reader", "onResume: ts=" + prefs.getFloat("text_size", 20) + " hm=" + prefs.getInt("horizontal_margin", 10));
        String fp = prefs.getString("font_path", "");
        if (!fp.isEmpty()) { File ff = new File(fp); if (ff.exists()) readerView.setCustomTypeface(Typeface.createFromFile(ff)); }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveProgress();
        // 统计阅读时间
        if (readingStartTime > 0 && fileKey != null) {
            long elapsed = System.currentTimeMillis() - readingStartTime;
            // ★ M-6: 过滤异常短时间（快速后台/前台切换）
            if (elapsed >= 1000) {
                long total = prefs.getLong("read_time_" + fileKey, 0);
                prefs.edit().putLong("read_time_" + fileKey, total + elapsed)
                        .putLong("total_read_time", prefs.getLong("total_read_time", 0) + elapsed).apply();
            }
            readingStartTime = System.currentTimeMillis();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // ★ I-5: 标记销毁，后台线程不再更新 UI
        isDestroyed = true;
    }
}
