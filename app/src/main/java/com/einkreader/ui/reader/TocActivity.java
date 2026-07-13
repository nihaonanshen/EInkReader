package com.einkreader.ui.reader;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.einkreader.ui.reader.DebugLog;

import java.util.ArrayList;

/**
 * 目录页面 —— 点击章节跳转
 *
 * 墨水屏不适合滑动，所以用大按钮翻页
 */
public class TocActivity extends Activity {

    /** 静态传值：ReaderActivity 通过这个字段读取选中的章节 */
    public static volatile int sSelectedChapter = -1;

    public static final String EXTRA_CHAPTERS = "chapter_titles";
    public static final String EXTRA_CURRENT_CHAPTER = "current_chapter";
    public static final String RESULT_CHAPTER_INDEX = "chapter_index";

    private int ITEMS_PER_PAGE = 10;  // 将在 onCreate 中根据屏幕动态计算

    private ArrayList<String> chapterTitles;
    private int currentChapter;
    private int totalPages;
    private int currentPage;

    private LinearLayout listContainer;
    private TextView tvPageInfo;
    private TextView btnPrev, btnNext;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        buildLayout();

        // ★ 根据屏幕高度动态计算每页显示章节数（每项约48dp高）
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int itemHeightDp = 48;
        int availableHeight = (int)(screenHeight / getResources().getDisplayMetrics().density) - 120; // 减去顶栏+底栏
        ITEMS_PER_PAGE = Math.max(3, availableHeight / itemHeightDp);

        chapterTitles = getIntent().getStringArrayListExtra(EXTRA_CHAPTERS);
        currentChapter = getIntent().getIntExtra(EXTRA_CURRENT_CHAPTER, 0);

        if (chapterTitles == null || chapterTitles.isEmpty()) {
            Toast.makeText(this, "无目录数据", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        totalPages = Math.max(1, (chapterTitles.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
        currentPage = currentChapter / ITEMS_PER_PAGE;
        // ★ 布局完成后渲染（确保 listContainer 有高度才能均匀分配）
        listContainer.post(new Runnable() {
            @Override
            public void run() {
                renderPage();
            }
        });
    }

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        // 顶部标题
        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.setBackgroundColor(Color.parseColor("#EEEEEE"));
        titleBar.setPadding(dp(16), dp(10), dp(16), dp(10));

        TextView btnBack = new TextView(this);
        btnBack.setText("← 返回");
        btnBack.setTextColor(Color.BLACK);
        btnBack.setTextSize(18);
        btnBack.setPadding(dp(8), dp(6), dp(8), dp(6));
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });
        titleBar.addView(btnBack);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("目录");
        tvTitle.setTextColor(Color.BLACK);
        tvTitle.setTextSize(20);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleBar.addView(tvTitle, titleLp);

        root.addView(titleBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));

        // 章节列表
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(listContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // 底部翻页栏（重新布局）
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setBackgroundColor(Color.parseColor("#EEEEEE"));
        bottomBar.setPadding(dp(12), dp(12), dp(12), dp(12));

        // 上一页按钮
        btnPrev = new TextView(this);
        btnPrev.setText("◀ 上一页");
        btnPrev.setTextSize(18);
        btnPrev.setTextColor(Color.BLACK);
        btnPrev.setGravity(Gravity.CENTER);
        btnPrev.setMinHeight(dp(48));
        btnPrev.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout.LayoutParams prevParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        bottomBar.addView(btnPrev, prevParams);

        // 中间页码信息
        tvPageInfo = new TextView(this);
        tvPageInfo.setTextSize(18);
        tvPageInfo.setTextColor(Color.BLACK);
        tvPageInfo.setGravity(Gravity.CENTER);
        tvPageInfo.setTypeface(Typeface.DEFAULT_BOLD);
        tvPageInfo.setMinHeight(dp(48));
        tvPageInfo.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        bottomBar.addView(tvPageInfo, infoParams);

        // 下一页按钮
        btnNext = new TextView(this);
        btnNext.setText("下一页 ▶");
        btnNext.setTextSize(18);
        btnNext.setTextColor(Color.BLACK);
        btnNext.setGravity(Gravity.CENTER);
        btnNext.setMinHeight(dp(48));
        btnNext.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        bottomBar.addView(btnNext, nextParams);

        root.addView(bottomBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(64)));

        setContentView(root);

        btnPrev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { prevPage(); }
        });
        btnNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { nextPage(); }
        });
    }

    private void renderPage() {
        listContainer.removeAllViews();

        int startIdx = currentPage * ITEMS_PER_PAGE;
        int endIdx = Math.min(startIdx + ITEMS_PER_PAGE, chapterTitles.size());
        int pad = dp(16);

        // 计算每一项的高度，均匀占满可用空间
        int totalHeight = listContainer.getHeight();
        if (totalHeight <= 0) totalHeight = dp(400); // 备用值
        int itemCount = endIdx - startIdx;
        int itemHeight = (itemCount > 0) ? (totalHeight / itemCount) : dp(50);
        int minItemHeight = dp(40);
        if (itemHeight < minItemHeight) itemHeight = minItemHeight;

        for (int i = startIdx; i < endIdx; i++) {
            final int chapterIndex = i;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(pad, dp(8), pad, dp(8));

            if (i == currentChapter) {
                row.setBackgroundColor(Color.parseColor("#EEEEEE")); // 高亮选中项
            } else {
                row.setBackgroundColor(Color.WHITE);
            }

            TextView tvNum = new TextView(this);
            tvNum.setText((i + 1) + ".");
            tvNum.setTextSize(18);
            tvNum.setWidth(dp(44));

            TextView tvTitle = new TextView(this);
            String title = chapterTitles.get(i);
            tvTitle.setText(title != null ? title : "第" + (i + 1) + "章");
            tvTitle.setTextSize(18);

            if (i == currentChapter) {
                tvTitle.setTextColor(Color.BLACK);
                tvNum.setTextColor(Color.GRAY);
            } else {
                tvTitle.setTextColor(Color.BLACK);
                tvNum.setTextColor(Color.DKGRAY);
            }

            row.addView(tvNum);
            row.addView(tvTitle, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            // 行点击：使用 OnTouchListener 以兼容墨水屏
            final int fi = i;
            row.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, android.view.MotionEvent event) {
                    int action = event.getAction();
                    if (action == android.view.MotionEvent.ACTION_DOWN) {
                        v.setPressed(true);
                        return true;
                    }
                    if (action == android.view.MotionEvent.ACTION_UP) {
                        Intent result = new Intent();
                        result.putExtra(RESULT_CHAPTER_INDEX, fi);
                        setResult(Activity.RESULT_OK, result);
                        DebugLog.log("Toc", "click idx=" + fi + " total=" + chapterTitles.size());
                        finish();
                        return true;
                    }
                    return false;
                }
            });

            listContainer.addView(row);

            // 分隔线（淡灰）
            if (i < endIdx - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(Color.parseColor("#EEEEEE"));
                // 1px height
                int height = (int) (1 * getResources().getDisplayMetrics().density + 0.5f);
                LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, height);
                listContainer.addView(divider, divParams);
            }
        }

        // 更新页码信息
        tvPageInfo.setText((currentPage + 1) + " / " + totalPages);
        boolean prevEnabled = currentPage > 0;
        boolean nextEnabled = currentPage < totalPages - 1;
        btnPrev.setEnabled(prevEnabled);
        btnPrev.setAlpha(prevEnabled ? 1.0f : 0.3f);
        btnNext.setEnabled(nextEnabled);
        btnNext.setAlpha(nextEnabled ? 1.0f : 0.3f);

        DebugLog.log("Toc", "renderPage: currentPage=" + currentPage + ", totalPages=" + totalPages +
                ", prevEnabled=" + prevEnabled + ", nextEnabled=" + nextEnabled);
    }

    private void prevPage() {
        if (currentPage > 0) {
            currentPage--;
            DebugLog.log("Toc", "prevPage called, new currentPage=" + currentPage);
            renderPage();
        } else {
            DebugLog.log("Toc", "prevPage called but already at first page");
        }
    }

    private void nextPage() {
        if (currentPage < totalPages - 1) {
            currentPage++;
            DebugLog.log("Toc", "nextPage called, new currentPage=" + currentPage);
            renderPage();
        } else {
            DebugLog.log("Toc", "nextPage called but already at last page");
        }
    }

    /** dp 转 px 辅助方法 */
    private int dp(int dpVal) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dpVal * density);
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
                keyCode == android.view.KeyEvent.KEYCODE_PAGE_UP) {
            prevPage();
            return true;
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN ||
                keyCode == android.view.KeyEvent.KEYCODE_PAGE_DOWN) {
            nextPage();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
                keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN ||
                keyCode == android.view.KeyEvent.KEYCODE_PAGE_UP ||
                keyCode == android.view.KeyEvent.KEYCODE_PAGE_DOWN) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }
}