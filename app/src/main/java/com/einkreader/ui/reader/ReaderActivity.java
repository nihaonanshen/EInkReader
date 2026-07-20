package com.einkreader.ui.reader;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.einkreader.R;
import com.einkreader.core.model.Chapter;
import com.einkreader.core.refresh.EinkRefreshManager;
import com.einkreader.di.ServiceLocator;
import com.einkreader.repository.BookResult;
import com.einkreader.repository.ReaderRepository;

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
    private View topStatusBar;
    private View bottomMenu;
    private View fullOverlay;
    private View loadingOverlay;
    // EPUB 图片数据
    private Map<String, byte[]> epubImageBytes;
    // 翻页模式的目录/书签
    private TextView fullTocText;
    private TextView fullTocPage;
    private TextView fullBookmarkText;
    private TextView fullBookmarkPage;
    private android.widget.FrameLayout fullTocContainer;
    private android.widget.FrameLayout fullBookmarkContainer;

    // 目录/书签的翻页状态
    private java.util.List<String> tocItems = new java.util.ArrayList<>();
    private int tocPageSize = 15;  // 每页显示条目数（动态计算）
    private int tocItemHeightPx = 72;  // 每个条目的高度（像素，动态计算）
    private int tocCurrentPage = 0;
    private float tocTextSizeSp = 18f;  // 目录文字大小（SP）
    private boolean tocLayoutValid = false;  // ★ TOC 布局缓存标记
    private java.util.List<String> bookmarkItems = new java.util.ArrayList<>();
    private int bookmarkCurrentPage = 0;

    private TextView statusTime, statusChapter, statusBattery;
    private TextView btnBack, btnToc, btnBookmark, btnSettings, btnShowLog;
    private TextView btnFontMinus, btnFontPlus, btnBrightMinus, btnBrightPlus, btnFullRefresh;
    private TextView progressLabel, loadingFilename, fullOverlayTitle;
    private TextView fullOverlayBack;
    private SeekBar progressSeekBar;

    private EinkRefreshManager refreshManager;
    private List<Chapter> chapters;
    private int currentChapterIndex = 0;
    private SharedPreferences prefs;
    private boolean menuVisible = false;
    private String filePath;
    private String fileKey;
    private Handler uiHandler;
    private long readingStartTime;
    private volatile boolean isDestroyed = false;
    private volatile boolean bookLoaded = false;
    private static final long LOADING_TIMEOUT_MS = 30000L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DebugLog.init();
        DebugLog.log("Lifecycle", "onCreate: savedInstanceState=" + (savedInstanceState != null));
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        setContentView(R.layout.activity_reader);

        applyImmersiveMode();

        uiHandler = new Handler(Looper.getMainLooper());
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        float savedBrightness = prefs.getFloat("screen_brightness", 0.5f);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = savedBrightness;
        getWindow().setAttributes(lp);

        refreshManager = new EinkRefreshManager(this);
        refreshManager.initialize(new EinkRefreshManager.RefreshCallback() {
            @Override public void onRefreshStart(EinkRefreshManager.RefreshMode mode) {}
            @Override public void onRefreshComplete(EinkRefreshManager.RefreshMode mode) {}
            @Override public void onModeDetected(Set<EinkRefreshManager.RefreshMode> modes) {}
            @Override public void onSysfsUnavailable() {}
        });

        readerView = (ReaderView) findViewById(R.id.reader_view);
        topStatusBar = findViewById(R.id.top_status_bar);
        bottomMenu = findViewById(R.id.bottom_menu);

        fullOverlay = findViewById(R.id.full_overlay);
        fullTocContainer = (android.widget.FrameLayout) findViewById(R.id.full_toc_container);
        fullTocText = (TextView) findViewById(R.id.full_toc_text);
        fullTocPage = (TextView) findViewById(R.id.full_toc_page);
        fullBookmarkContainer = (android.widget.FrameLayout) findViewById(R.id.full_bookmark_container);
        fullBookmarkText = (TextView) findViewById(R.id.full_bookmark_text);
        fullBookmarkPage = (TextView) findViewById(R.id.full_bookmark_page);
        fullOverlayBack = (TextView) findViewById(R.id.full_overlay_back);
        fullOverlayTitle = (TextView) findViewById(R.id.full_overlay_title);
        if (fullOverlayBack != null) {
            fullOverlayBack.setOnClickListener(v -> exitFullOverlay());
        }
        // 目录/书签容器触摸翻页
        android.view.View.OnTouchListener touchListener = (v, event) -> {
            int w = v.getWidth();
            int h = v.getHeight();
            float x = event.getX();
            float y = event.getY();
            switch (event.getAction()) {
                case android.view.MotionEvent.ACTION_UP:
                    float density = getResources().getDisplayMetrics().density;
                    int paddingPx = (int)(16 * density);  // TextView 的 padding 是 16dp
                    if (fullOverlayMode == FullOverlayMode.TOC) {
                        // 计算点击的条目位置（考虑容器内 TextView 的实际布局）
                        int textViewTop = 0;
                        if (fullTocText != null) {
                            int[] loc = new int[2];
                            fullTocText.getLocationOnScreen(loc);
                            int[] vloc = new int[2];
                            v.getLocationOnScreen(vloc);
                            textViewTop = loc[1] - vloc[1];
                        }
                        int itemTop = textViewTop + paddingPx;
                        int row = (int)((y - itemTop) / tocItemHeightPx);
                        int startIdx = tocCurrentPage * tocPageSize;
                        int clickIdx = startIdx + row;
                        if (clickIdx >= startIdx && clickIdx < startIdx + tocPageSize && clickIdx < tocItems.size() && chapters != null && clickIdx < chapters.size()) {
                            DebugLog.log("TOC", "点击目录项: row=" + row + " idx=" + clickIdx + " item=" + tocItems.get(clickIdx) + " y=" + y + " itemTop=" + itemTop + " itemH=" + tocItemHeightPx);
                            switchChapterTo(clickIdx);
                            exitFullOverlay();
                        } else if (x < w * 0.33f) {
                            tocPrevPage();
                        } else if (x > w * 0.67f) {
                            tocNextPage();
                        } else {
                            tocNextPage();
                        }
                    } else if (fullOverlayMode == FullOverlayMode.BOOKMARK) {
                        int textViewTop = 0;
                        if (fullBookmarkText != null) {
                            int[] loc = new int[2];
                            fullBookmarkText.getLocationOnScreen(loc);
                            int[] vloc = new int[2];
                            v.getLocationOnScreen(vloc);
                            textViewTop = loc[1] - vloc[1];
                        }
                        int itemTop = textViewTop + paddingPx;
                        int row = (int)((y - itemTop) / tocItemHeightPx);
                        int startIdx = bookmarkCurrentPage * tocPageSize;
                        int clickIdx = startIdx + row;
                        if (clickIdx >= startIdx && clickIdx < startIdx + tocPageSize && clickIdx < bookmarkItems.size()) {
                            DebugLog.log("Bookmark", "点击书签项: row=" + row + " idx=" + clickIdx);
                            jumpToBookmark(clickIdx);
                            exitFullOverlay();
                        } else if (x < w * 0.33f) {
                            bookmarkPrevPage();
                        } else if (x > w * 0.67f) {
                            bookmarkNextPage();
                        } else {
                            bookmarkNextPage();
                        }
                    }
                    break;
            }
            return true;
        };
        if (fullTocContainer != null) fullTocContainer.setOnTouchListener(touchListener);
        if (fullBookmarkContainer != null) fullBookmarkContainer.setOnTouchListener(touchListener);

        loadingOverlay = findViewById(R.id.loading_overlay);
        loadingFilename = (TextView) findViewById(R.id.loading_filename);

        statusTime = (TextView) findViewById(R.id.status_time);
        statusChapter = (TextView) findViewById(R.id.status_chapter);
        statusBattery = (TextView) findViewById(R.id.status_battery);

        btnBack = (TextView) findViewById(R.id.btn_back);
        btnToc = (TextView) findViewById(R.id.btn_toc);
        btnBookmark = (TextView) findViewById(R.id.btn_bookmark);
        btnSettings = (TextView) findViewById(R.id.btn_settings);
        btnShowLog = (TextView) findViewById(R.id.btn_show_log);

        btnFontMinus = (TextView) findViewById(R.id.btn_font_minus);
        btnFontPlus = (TextView) findViewById(R.id.btn_font_plus);
        btnBrightMinus = (TextView) findViewById(R.id.btn_bright_minus);
        btnBrightPlus = (TextView) findViewById(R.id.btn_bright_plus);
        btnFullRefresh = (TextView) findViewById(R.id.btn_full_refresh);

        progressSeekBar = (SeekBar) findViewById(R.id.progress_seekbar);
        progressLabel = (TextView) findViewById(R.id.progress_label);

        progressSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        if (fromUser && readerView != null) {
                            int total = readerView.getTotalPages();
                            if (total > 0) {
                                int targetPage = (int) (progress * (total - 1) / 100f);
                                if (targetPage >= 0 && targetPage < total) {
                                    readerView.goToPage(targetPage);
                                    updateProgressLabel();
                                }
                            }
                        }
                    }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        btnBack.setOnClickListener(v -> finish());
        btnToc.setOnClickListener(v -> { openFullOverlay(FullOverlayMode.TOC); loadTocList(); });
        btnBookmark.setOnClickListener(v -> { addBookmark(); openFullOverlay(FullOverlayMode.BOOKMARK); loadBookmarks(); });
        btnSettings.setOnClickListener(v -> {
            Intent intent = new Intent(ReaderActivity.this, ReadingSettingsActivity.class);
            startActivity(intent);
        });
        btnShowLog.setOnClickListener(v -> showLogDialog());
        btnFontMinus.setOnClickListener(v -> adjustFontSize(-1));
        btnFontPlus.setOnClickListener(v -> adjustFontSize(1));
        btnBrightMinus.setOnClickListener(v -> adjustBrightness(-0.1f));
        btnBrightPlus.setOnClickListener(v -> adjustBrightness(0.1f));
        btnFullRefresh.setOnClickListener(v -> {
            if (readerView != null) readerView.performFullRefresh();
        });

        float savedSize = prefs.getFloat("text_size", 28f);

        readerView.setOnPageChangeListener(new ReaderView.OnPageChangeListener() {
            @Override public void onPageChanged(int pageIndex, int totalPages) {
                DebugLog.log("Page", "onPageChanged: " + pageIndex + "/" + totalPages);
                if (!bookLoaded) return;
                saveProgress();
                updateStatusBar();
                updateProgressBar(readerView.getCurrentPage(), readerView.getTotalPages());
                updateProgressLabel();
                if (refreshManager != null) refreshManager.onPageTurn(readerView);
            }

            @Override public void onChapterChanged(int chapterIndex) {
                DebugLog.log("Chapter", "onChapterChanged: chapter=" + chapterIndex);
                if (!bookLoaded) return;
                updateProgressBar(readerView.getCurrentPage(), readerView.getTotalPages());
                updateProgressLabel();
            }

            @Override public void onTapCenter() {
                DebugLog.log("UI", "onTapCenter: menuVisible=" + menuVisible);
                if (!bookLoaded) return;
                toggleMenu(!menuVisible);
            }

            @Override public void onNeedPrevChapter() {
                DebugLog.log("Nav", "onNeedPrevChapter");
                if (!bookLoaded) return;
                goToPrevChapter();
            }
            @Override public void onNeedNextChapter() {
                DebugLog.log("Nav", "onNeedNextChapter");
                if (!bookLoaded) return;
                goToNextChapter();
            }
        });

        readerView.setFocusable(true);
        readerView.setFocusableInTouchMode(true);
        readerView.requestFocus();

        readingStartTime = System.currentTimeMillis();
        loadBook();
    }

    private void adjustFontSize(int delta) {
        float current = prefs.getFloat("text_size", 28f);
        float next = Math.max(14f, Math.min(64f, current + delta));
        prefs.edit().putFloat("text_size", next).apply();
        if (readerView != null) readerView.setTextSize(next);
    }

    private void adjustBrightness(float delta) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        float cur = lp.screenBrightness > 0 ? lp.screenBrightness : 0.5f;
        float next = Math.max(0.05f, Math.min(1.0f, cur + delta));
        lp.screenBrightness = next;
        getWindow().setAttributes(lp);
        prefs.edit().putFloat("screen_brightness", next).apply();
    }

    private enum FullOverlayMode { NONE, TOC, BOOKMARK }
    private FullOverlayMode fullOverlayMode = FullOverlayMode.NONE;

    private void openFullOverlay(FullOverlayMode mode) {
        fullOverlayMode = mode;
        if (fullOverlay != null) fullOverlay.setVisibility(View.VISIBLE);
        if (fullTocContainer != null) fullTocContainer.setVisibility(mode == FullOverlayMode.TOC ? View.VISIBLE : View.GONE);
        if (fullBookmarkContainer != null) fullBookmarkContainer.setVisibility(mode == FullOverlayMode.BOOKMARK ? View.VISIBLE : View.GONE);
        if (fullOverlayTitle != null) {
            fullOverlayTitle.setText(mode == FullOverlayMode.TOC ? "目录" : "书签");
        }
        // 全屏时隐藏状态栏和底部菜单，避免遮挡
        if (topStatusBar != null) topStatusBar.setVisibility(View.GONE);
        if (bottomMenu != null) bottomMenu.setVisibility(View.GONE);
        // 恢复 ReaderView 铺满
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) readerView.getLayoutParams();
        if (lp != null) {
            lp.topMargin = 0;
            lp.bottomMargin = 0;
            readerView.setLayoutParams(lp);
        }
    }

    private void exitFullOverlay() {
        fullOverlayMode = FullOverlayMode.NONE;
        if (fullOverlay != null) fullOverlay.setVisibility(View.GONE);
        if (readerView != null) readerView.requestFocus();
        // 恢复菜单显示
        if (menuVisible) {
            if (topStatusBar != null) topStatusBar.setVisibility(View.VISIBLE);
            if (bottomMenu != null) bottomMenu.setVisibility(View.VISIBLE);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) readerView.getLayoutParams();
            if (lp != null) {
                lp.topMargin = (int) (32 * getResources().getDisplayMetrics().density);
                lp.bottomMargin = (int) (160 * getResources().getDisplayMetrics().density);
                readerView.setLayoutParams(lp);
            }
        }
    }

    private void loadTocList() {
        if (chapters == null || chapters.isEmpty()) {
            Toast.makeText(this, "暂无章节", Toast.LENGTH_SHORT).show();
            return;
        }
        // ★ 动态计算目录布局，适配不同屏幕尺寸
        calculateTocLayout();
        ArrayList<String> titles = new ArrayList<>();
        for (int i = 0; i < chapters.size(); i++) {
            Chapter c = chapters.get(i);
            String t = c.getTitle() != null ? c.getTitle() : ("第" + (i + 1) + "章");
            if (i == currentChapterIndex) t = "▶ " + t;
            titles.add(t);
        }
        tocItems = titles;
        tocCurrentPage = 0;
        renderTocPage();
    }

    /**
     * ★ 动态计算目录每页条目数和行高
     * 根据屏幕高度、字号、行距自动计算，适配不同尺寸的墨水屏
     */
    private void calculateTocLayout() {
        // ★ 缓存命中则跳过重算（屏幕尺寸在 Activity 生命周期内不变）
        if (tocLayoutValid) return;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        float density = dm.density;
        int screenHeight = dm.heightPixels;

        // 标题栏高度: 48dp
        int headerHeight = (int)(48 * density);
        // 可用高度 = 屏幕高度 - 标题栏
        int availHeight = screenHeight - headerHeight;
        // TextView padding: 16dp 上下
        int padding = (int)(16 * density * 2);
        int textAvailHeight = availHeight - padding;
        if (textAvailHeight < 200) textAvailHeight = 200; // 安全下限

        // 目录文字大小: 根据屏幕密度和高度动态计算
        if (screenHeight >= 1800) {
            tocTextSizeSp = 26f;   // 7.8 寸大屏
        } else if (screenHeight >= 1400) {
            tocTextSizeSp = 22f;   // 6 寸中屏
        } else {
            tocTextSizeSp = 18f;   // 小屏
        }
        float tocTextSizePx = tocTextSizeSp * density;
        // 行距额外: 8dp
        float lineSpacingExtraPx = 8 * density;
        // 单行高度 = 字号 * 字体metrics(约1.2倍) + 行距额外
        float lineHeightPx = tocTextSizePx * 1.2f + lineSpacingExtraPx;
        // 每个条目占 2 行（1行文字 + 1行空行 \n\n）
        tocItemHeightPx = (int)(lineHeightPx * 2);
        if (tocItemHeightPx < 30) tocItemHeightPx = 30;

        // 每页条目数 = 可用高度 / 条目高度（向下取整，留底部页码空间）
        int pageLabelHeight = (int)(30 * density); // 底部页码预留
        int usableHeight = textAvailHeight - pageLabelHeight;
        tocPageSize = usableHeight / tocItemHeightPx;
        if (tocPageSize < 5) tocPageSize = 5;
        if (tocPageSize > 30) tocPageSize = 30;

        DebugLog.log("TOC", "calculateTocLayout: screenH=" + screenHeight + " density=" + density
                + " headerH=" + headerHeight + " availH=" + availHeight + " textAvailH=" + textAvailHeight
                + " lineH=" + lineHeightPx + " itemH=" + tocItemHeightPx + " pageSize=" + tocPageSize);
        tocLayoutValid = true;
    }

    private void renderTocPage() {
        if (fullTocText == null || fullTocPage == null) return;
        // 应用目录字体大小
        fullTocText.setTextSize(tocTextSizeSp);
        int totalPages = (tocItems.size() + tocPageSize - 1) / tocPageSize;
        if (tocCurrentPage >= totalPages) tocCurrentPage = totalPages - 1;
        if (tocCurrentPage < 0) tocCurrentPage = 0;
        int start = tocCurrentPage * tocPageSize;
        int end = Math.min(start + tocPageSize, tocItems.size());
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            sb.append(tocItems.get(i));
            if (i < end - 1) sb.append("\n\n");
        }
        fullTocText.setText(sb.toString());
        fullTocPage.setText((tocCurrentPage + 1) + " / " + totalPages);
    }

    private void tocPrevPage() {
        if (tocCurrentPage > 0) { tocCurrentPage--; renderTocPage(); }
    }

    private void tocNextPage() {
        int totalPages = (tocItems.size() + tocPageSize - 1) / tocPageSize;
        if (tocCurrentPage < totalPages - 1) { tocCurrentPage++; renderTocPage(); }
    }

    private void loadBookmarks() {
        List<String> list = new ArrayList<>();
        try {
            list = ServiceLocator.getReaderRepository().loadBookmarks(fileKey);
        } catch (Exception e) {
            DebugLog.error("Bookmark", "loadBookmarks failed", e);
        }
        bookmarkItems = new ArrayList<>(list);
        bookmarkCurrentPage = 0;
        renderBookmarkPage();
    }

    private void renderBookmarkPage() {
        if (fullBookmarkText == null || fullBookmarkPage == null) return;
        int totalPages = (bookmarkItems.size() + tocPageSize - 1) / tocPageSize;
        if (bookmarkCurrentPage >= totalPages) bookmarkCurrentPage = totalPages - 1;
        if (bookmarkCurrentPage < 0) bookmarkCurrentPage = 0;
        int start = bookmarkCurrentPage * tocPageSize;
        int end = Math.min(start + tocPageSize, bookmarkItems.size());
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            sb.append(bookmarkItems.get(i));
            if (i < end - 1) sb.append("\n\n");
        }
        if (bookmarkItems.isEmpty()) sb.append("暂无书签");
        fullBookmarkText.setText(sb.toString());
        fullBookmarkPage.setText((bookmarkCurrentPage + 1) + " / " + Math.max(1, totalPages));
    }

    private void bookmarkPrevPage() {
        if (bookmarkCurrentPage > 0) { bookmarkCurrentPage--; renderBookmarkPage(); }
    }

    private void bookmarkNextPage() {
        int totalPages = (bookmarkItems.size() + tocPageSize - 1) / tocPageSize;
        if (bookmarkCurrentPage < totalPages - 1) { bookmarkCurrentPage++; renderBookmarkPage(); }
    }

    private void jumpToBookmark(int idx) {
        if (chapters == null || fileKey == null) return;
        try {
            int ch = ServiceLocator.getReaderRepository().jumpToBookmark(fileKey, idx, chapters.size());
            if (ch >= 0 && ch < chapters.size()) {
                switchChapterTo(ch);
            }
        } catch (Exception e) {
            DebugLog.error("Bookmark", "jumpToBookmark failed", e);
        }
    }

    private void addBookmark() {
        if (fileKey == null || readerView == null) return;
        try {
            String title = "";
            if (chapters != null && currentChapterIndex < chapters.size()) {
                Chapter c = chapters.get(currentChapterIndex);
                title = c.getTitle() != null ? c.getTitle() : ("第" + (currentChapterIndex + 1) + "章");
            }
            ServiceLocator.getReaderRepository().addBookmark(
                    fileKey, currentChapterIndex, readerView.getCurrentPage(), title);
            Toast.makeText(this, "已加书签", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            DebugLog.error("Bookmark", "addBookmark failed", e);
            Toast.makeText(this, "书签保存失败", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int c = event.getKeyCode();
        boolean isVolumeKey = (c == KeyEvent.KEYCODE_VOLUME_UP || c == KeyEvent.KEYCODE_VOLUME_DOWN);
        boolean isPageKey   = (c == KeyEvent.KEYCODE_PAGE_UP   || c == KeyEvent.KEYCODE_PAGE_DOWN);
        if (isVolumeKey || isPageKey) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                // 全屏目录/书签覆盖层显示时，翻页键用于翻目录
                if (fullOverlayMode == FullOverlayMode.TOC) {
                    if (c == KeyEvent.KEYCODE_VOLUME_UP || c == KeyEvent.KEYCODE_PAGE_UP) {
                        tocPrevPage();
                    } else {
                        tocNextPage();
                    }
                    return true;
                }
                if (fullOverlayMode == FullOverlayMode.BOOKMARK) {
                    if (c == KeyEvent.KEYCODE_VOLUME_UP || c == KeyEvent.KEYCODE_PAGE_UP) {
                        bookmarkPrevPage();
                    } else {
                        bookmarkNextPage();
                    }
                    return true;
                }
                // 书籍尚未加载时，屏蔽翻页键，防止空指针导致 ANR 崩溃
                if (!bookLoaded) {
                    return true;
                }
                if (c == KeyEvent.KEYCODE_VOLUME_UP || c == KeyEvent.KEYCODE_PAGE_UP) {
                    if (readerView != null) readerView.prevPage();
                } else {
                    if (readerView != null) readerView.nextPage();
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_PAGE_UP   || keyCode == KeyEvent.KEYCODE_PAGE_DOWN)
            return true;
        return super.onKeyUp(keyCode, event);
    }

    private void loadBook() {
            DebugLog.log("Load", "loadBook start");
            bookLoaded = false;
            epubImageBytes = null; // ★ 重置图片数据，防止跨书泄露
            if (loadingFilename != null) {
                String name = filePath != null ? filePath : "";
                int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
                loadingFilename.setText(slash >= 0 ? name.substring(slash + 1) : name);
            }
            if (loadingOverlay != null) {
                loadingOverlay.setVisibility(View.VISIBLE);
                // 解析超时兜底——30秒后自动隐藏 loading，防止永久白屏
                uiHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (isDestroyed) return;
                        if (loadingOverlay != null && loadingOverlay.getVisibility() == View.VISIBLE) {
                            loadingOverlay.setVisibility(View.GONE);
                            Toast.makeText(ReaderActivity.this, "加载超时", Toast.LENGTH_LONG).show();
                        }
                    }
                }, LOADING_TIMEOUT_MS);
            }
            filePath = getIntent().getStringExtra("file_path");
            String fileUri = getIntent().getStringExtra("file_uri");
            if ((filePath == null || filePath.isEmpty()) && fileUri == null) {
                DebugLog.error("Load", "书籍路径为空");
                Toast.makeText(this, "书籍路径为空", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }

            final String fp = filePath;
            final String fu = (fileUri != null) ? fileUri : filePath;

            // ★ 后台线程执行解析 —— 使用 ReaderRepository 解耦所有业务逻辑
            new Thread(() -> {
                try {
                    ReaderRepository repo = ServiceLocator.getReaderRepository();
                    BookResult result = repo.loadBook(fp, fu);

                    if (result == null || !result.isValid()) {
                        showToastOnUi("解析失败");
                        return;
                    }

                    final List<Chapter> fch = result.chapters;

                    uiHandler.post(() -> {
                        try {
                            if (isDestroyed) return;
                            if (fch == null || fch.isEmpty()) {
                                DebugLog.error("Parse", "解析失败: chapters=" + (fch == null ? "null" : "empty"));
                                Toast.makeText(ReaderActivity.this, "解析失败", Toast.LENGTH_SHORT).show();
                                finish();
                                return;
                            }
                            DebugLog.log("Load", "书籍加载成功: chapters=" + fch.size());
                            chapters = fch;
                            fileKey = result.fileKey;
                            epubImageBytes = result.images;

                            // 恢复阅读进度
                            int sc = result.savedChapter;
                            int sp = result.savedPage;

                            if (TocActivity.sSelectedChapter >= 0 && TocActivity.sSelectedChapter < fch.size()) {
                                sc = TocActivity.sSelectedChapter;
                                sp = 0;
                                TocActivity.sSelectedChapter = -1;
                            }
                            if (sc < 0 || sc >= fch.size()) { sc = 0; sp = 0; }
                            currentChapterIndex = sc;
                            readerView.setChapter(chapters.get(currentChapterIndex));
                            // ★ 传递图片字节数据给 ReaderView，用于 [[IMAGE:path]] 渲染
                            if (epubImageBytes != null) {
                                readerView.setChapterImages(epubImageBytes);
                            }
                            readerView.applySettings();
                            if (sp > 0 && sp < readerView.getTotalPages())
                                readerView.goToPage(sp);
                            updateStatusBar();
                            updateProgressBar(readerView.getCurrentPage(), readerView.getTotalPages());
                            updateProgressLabel();
                            bookLoaded = true;
                            if (loadingOverlay != null) loadingOverlay.setVisibility(View.GONE);
                        } catch (Throwable t) {
                            DebugLog.error("Load", "UI post failed: type=" + t.getClass().getSimpleName(), t);
                            if (!isDestroyed) {
                                Toast.makeText(ReaderActivity.this, "加载失败", Toast.LENGTH_LONG).show();
                                finish();
                            }
                        }
                    });
                } catch (final Exception e) {
                    DebugLog.error("Reader", "后台线程加载失败", e);
                    uiHandler.post(() -> {
                        if (isDestroyed) return;
                        Toast.makeText(ReaderActivity.this, "加载失败", Toast.LENGTH_LONG).show();
                        finish();
                    });
                }
            }).start();
        }

    private void showToastOnUi(final String m) {
        uiHandler.post(() -> {
            if (isDestroyed) return;
            Toast.makeText(ReaderActivity.this, m, Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    private void switchChapterTo(int index) {
        if (chapters == null || chapters.isEmpty()) return;
        if (index < 0 || index >= chapters.size()) return;
        currentChapterIndex = index;
        readerView.setChapter(chapters.get(currentChapterIndex));
        readerView.goToPage(0);
        updateStatusBar();
        updateProgressBar(0, readerView.getTotalPages());
        updateProgressLabel();
    }

    private void switchChapter(int d) {
        if (chapters == null || chapters.isEmpty()) return;
        int ni = currentChapterIndex + d;
        if (ni < 0 || ni >= chapters.size()) return;
        currentChapterIndex = ni;
        readerView.setChapter(chapters.get(currentChapterIndex));
        updateStatusBar();
    }

    private void goToPrevChapter() {
        if (currentChapterIndex > 0) {
            switchChapter(-1);
            readerView.goToPage(readerView.getTotalPages() - 1);
        } else {
            Toast.makeText(this, "已经是第一章了", Toast.LENGTH_SHORT).show();
        }
    }

    private void goToNextChapter() {
        if (currentChapterIndex < chapters.size() - 1) {
            switchChapter(1);
            readerView.goToPage(0);
        } else {
            Toast.makeText(this, "已经是最后一章了", Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleMenu(boolean show) {
        menuVisible = show;
        int vis = show ? View.VISIBLE : View.GONE;
        topStatusBar.setVisibility(vis);
        bottomMenu.setVisibility(vis);
        if (show) {
            updateStatusBar();
            updateProgressLabel();
            // 调整 ReaderView 以避开状态栏和底部菜单的遮挡
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) readerView.getLayoutParams();
            if (lp != null) {
                lp.topMargin = (int) (32 * getResources().getDisplayMetrics().density);
                lp.bottomMargin = (int) (160 * getResources().getDisplayMetrics().density);
                readerView.setLayoutParams(lp);
            }
        } else {
            exitFullOverlay();
            // 恢复 ReaderView 铺满整屏
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) readerView.getLayoutParams();
            if (lp != null) {
                lp.topMargin = 0;
                lp.bottomMargin = 0;
                readerView.setLayoutParams(lp);
            }
        }
        applyImmersiveMode();
    }

    private void applyImmersiveMode() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 14) {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                );
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                );
            }
        } catch (Exception e) {
            DebugLog.error("UI", "applyImmersiveMode failed", e);
        }
    }

    private void showLogDialog() {
        try {
            String log = DebugLog.getLog();
            StringBuilder sb = new StringBuilder();
            sb.append("日志文件路径：\n").append(DebugLog.getLogFilePath()).append("\n\n");
            if (log != null && log.length() > 0) {
                // 限制显示长度，避免弹窗过长
                if (log.length() > 30000) {
                    sb.append("（日志过长，仅显示最后部分）\n\n");
                    sb.append(log.substring(log.length() - 30000));
                } else {
                    sb.append(log);
                }
            } else {
                sb.append("暂无日志");
            }
            new AlertDialog.Builder(this)
                    .setTitle("调试日志")
                    .setMessage(sb.toString())
                    .setPositiveButton("清除", (dlg, w) -> { DebugLog.clear(); Toast.makeText(this, "已清除", Toast.LENGTH_SHORT).show(); })
                    .setNegativeButton("关闭", null)
                    .show();
        } catch (Exception e) {
            Toast.makeText(this, "无法读取日志", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateStatusBar() {
        if (statusTime != null)
            statusTime.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));

        if (statusChapter != null && chapters != null && currentChapterIndex < chapters.size()) {
            String title = chapters.get(currentChapterIndex).getTitle();
            String chapText = (title != null) ? title : ("第" + (currentChapterIndex + 1) + "章");
            statusChapter.setText(chapText + "  (" + (currentChapterIndex + 1) + "/" + chapters.size() + ")");
        }

        if (readerView != null) {
            String pageText = readerView.getCurrentPage() + "/" + readerView.getTotalPages();
            if (statusChapter != null) {
                CharSequence existing = statusChapter.getText();
                if (existing != null && existing.length() > 0) {
                    statusChapter.setText(existing + "  ·  " + pageText);
                } else {
                    statusChapter.setText(pageText);
                }
            }
        }

        if (statusBattery != null) {
            try {
                android.content.Intent bi = registerReceiver(null,
                        new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (bi != null) {
                    int level = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                    if (level >= 0 && scale > 0)
                        statusBattery.setText(String.format(Locale.getDefault(), "%d%%",
                                (int) ((level / (float) scale) * 100)));
                }
            } catch (Exception e) {
                DebugLog.error("UI", "updateStatusBar battery failed", e);
            }
        }
    }

    private void updateProgressBar(int currentPage, int totalPages) {
            if (progressSeekBar == null) return;
            int progress = 0;
            if (totalPages > 0) {
                progress = (int) ((currentPage * 100f) / Math.max(1, totalPages - 1));
                if (progress > 100) progress = 100;
            }
            progressSeekBar.setProgress(progress);
        }

    private void updateProgressLabel() {
        if (progressLabel != null && readerView != null) {
            progressLabel.setText(readerView.getCurrentPage() + " / " + readerView.getTotalPages() + " 页");
        }
    }

    private void saveProgress() {
            if (chapters == null || fileKey == null) return;
            final int chIdx = currentChapterIndex;
            final int pageIdx = readerView.getCurrentPage();
            final int totalCh = chapters.size();

            // 异步保存，避免阻塞 UI 翻页
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        ServiceLocator.getReaderRepository().saveProgress(fileKey, chIdx, pageIdx, totalCh);
                    } catch (Exception e) {
                        DebugLog.error("Progress", "saveProgress failed", e);
                    }
                }
            }).start();
        }

    private void persistBookRecord(String fp, String fu, boolean isContent, int totalChapters) {
            if (fileKey == null) return;
            try {
                String format = (fp != null && fp.toLowerCase().endsWith(".epub"))
                        || (fu != null && fu.toLowerCase().endsWith(".epub")) ? "epub" : "txt";
                ServiceLocator.getReaderRepository().persistBookRecord(
                        fileKey, fp, fu, isContent, totalChapters, format);
            } catch (Exception e) {
                DebugLog.error("Progress", "persistBookRecord failed", e);
            }
        }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
    }

    @Override protected void onResume() {
        super.onResume();
        if (readerView != null) {
            // ★ 批量应用所有设置，只触发一次 layoutPages（原先 5+ 次重排→1 次）
            // 位置恢复由 layoutPages 内部基于文字指纹自动完成
            readerView.beginBatchUpdate();
            readerView.setTextSize(prefs.getFloat("text_size", 28f));
            readerView.setLineSpacing(prefs.getInt("line_spacing", 15) / 10f);
            readerView.setParagraphSpacing(prefs.getInt("para_spacing", 18) / 10f);
            readerView.setHorizontalMargin(prefs.getInt("horizontal_margin", 10));
            readerView.setFirstLineIndent(prefs.getBoolean("first_line_indent", false));
            String fp = prefs.getString("font_path", "");
            if (!fp.isEmpty()) {
                File ff = new File(fp);
                if (ff.exists())
                    readerView.setCustomTypeface(Typeface.createFromFile(ff));
            }
            readerView.commitBatchUpdate();
        }
        boolean night = prefs.getBoolean("night_mode", false);
        applyNightMode(night);

        // 确保沉浸式全屏不被系统/Activity 恢复覆盖
        applyImmersiveMode();
    }

    @Override protected void onPause() {
        DebugLog.log("Lifecycle", "onPause");
        super.onPause();
        saveProgress();
        if (readingStartTime > 0 && fileKey != null) {
            long elapsed = System.currentTimeMillis() - readingStartTime;
            if (elapsed >= 1000) {
                long total = prefs.getLong("read_time_" + fileKey, 0);
                prefs.edit()
                        .putLong("read_time_" + fileKey, total + elapsed)
                        .putLong("total_read_time",
                                prefs.getLong("total_read_time", 0) + elapsed)
                        .apply();
            }
            readingStartTime = System.currentTimeMillis();
        }
    }

    @Override public void onBackPressed() {
        DebugLog.log("Nav", "onBackPressed: fullOverlayMode=" + fullOverlayMode);
        if (fullOverlayMode != FullOverlayMode.NONE) {
            exitFullOverlay();
            return;
        }
        // ★ 中断后台布局，避免布局结果回调已销毁的 Activity
        if (readerView != null) readerView.cancelLayout();
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        DebugLog.log("Lifecycle", "onDestroy");
        super.onDestroy();
        isDestroyed = true;
    }

    private void applyNightMode(boolean night) {
        int bg = night ? 0xFF222222 : Color.WHITE;
        int fg = night ? 0xFFBBBBBB : Color.BLACK;

        View root = findViewById(R.id.root_container);
        if (root != null) root.setBackgroundColor(bg);

        if (topStatusBar != null) topStatusBar.setBackgroundColor(bg);
        if (bottomMenu != null) bottomMenu.setBackgroundColor(night ? 0xFF2A2A2A : 0xFFF5F5F5);
        if (fullOverlay != null) fullOverlay.setBackgroundColor(bg);
        if (loadingOverlay != null) loadingOverlay.setBackgroundColor(bg);

        for (int id : new int[]{
                R.id.status_time, R.id.status_chapter, R.id.status_battery,
                R.id.btn_back, R.id.btn_toc, R.id.btn_bookmark, R.id.btn_settings, R.id.btn_show_log,
                R.id.btn_font_minus, R.id.btn_font_plus, R.id.btn_bright_minus, R.id.btn_bright_plus, R.id.btn_full_refresh,
                R.id.progress_label, R.id.full_overlay_title, R.id.full_overlay_back
        }) {
            View v = findViewById(id);
            if (v instanceof TextView) ((TextView) v).setTextColor(fg);
        }
    }
}
