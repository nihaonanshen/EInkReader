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

        // ★ 根据屏幕高度动态计算每页显示章节数（每项约60dp高）
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int itemHeightDp = 42;
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
        titleBar.setBackgroundColor(0xFF333333);
        titleBar.setPadding(dp(16), dp(10), dp(16), dp(10));

        TextView btnBack = new TextView(this);
        btnBack.setText("← 返回");
        btnBack.setTextColor(Color.WHITE);
        btnBack.setTextSize(18);
        btnBack.setPadding(dp(8), dp(6), dp(8), dp(6));
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });
        titleBar.addView(btnBack);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("目录");
        tvTitle.setTextColor(Color.WHITE);
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

        // 底部翻页栏
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setBackgroundColor(0xFFF5F5F5);
        bottomBar.setPadding(dp(12), dp(12), dp(12), dp(12));

        btnPrev = new TextView(this);
        btnPrev.setText("◀ 上一页");
        btnPrev.setTextColor(Color.BLACK);
        btnPrev.setTextSize(18);
        btnPrev.setPadding(dp(16), dp(12), dp(16), dp(12));
        btnPrev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { prevPage(); }
        });
        bottomBar.addView(btnPrev);

        tvPageInfo = new TextView(this);
        tvPageInfo.setTextSize(18);
        tvPageInfo.setTextColor(Color.BLACK);
        tvPageInfo.setGravity(Gravity.CENTER);
        tvPageInfo.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        infoLp.leftMargin = dp(20);
        infoLp.rightMargin = dp(20);
        bottomBar.addView(tvPageInfo, infoLp);

        btnNext = new TextView(this);
        btnNext.setText("下一页 ▶");
        btnNext.setTextColor(Color.BLACK);
        btnNext.setTextSize(18);
        btnNext.setPadding(dp(16), dp(12), dp(16), dp(12));
        btnNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { nextPage(); }
        });
        bottomBar.addView(btnNext);

        root.addView(bottomBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(64)));

        setContentView(root);
    }

    private void renderPage() {
        listContainer.removeAllViews();

        int startIdx = currentPage * ITEMS_PER_PAGE;
        int endIdx = Math.min(startIdx + ITEMS_PER_PAGE, chapterTitles.size());
        int pad = dp(16);

        // ★ 计算每一项的高度，均匀占满可用空间
        int totalHeight = listContainer.getHeight();
        if (totalHeight <= 0) totalHeight = dp(400); // 后备值
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
                row.setBackgroundColor(Color.BLACK);
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
                tvTitle.setTextColor(Color.WHITE);
                tvNum.setTextColor(0xFFCCCCCC);
            } else {
                tvTitle.setTextColor(0xFF333333);
                tvNum.setTextColor(0xFF666666);
            }

            row.addView(tvNum);
            row.addView(tvTitle, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            // ★ 行点击：用 OnTouchListener（墨水屏兼容性更好）
            final int fi = i;
            row.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, android.view.MotionEvent event) {
                    int action = event.getAction();
                    DebugLog.log("Toc", "touch row=" + fi + " action=" + (action == android.view.MotionEvent.ACTION_DOWN ? "DOWN" : action == android.view.MotionEvent.ACTION_UP ? "UP" : "MOVE"));
                    if (action == android.view.MotionEvent.ACTION_DOWN) {
                        v.setPressed(true);
                        return true;
                    }
                    if (action == android.view.MotionEvent.ACTION_UP) {
                        sSelectedChapter = fi;
                        DebugLog.log("Toc", "click idx=" + fi + " total=" + chapterTitles.size());
                        finish();
                        return true;
                    }
                    return false;
                }
            });

            listContainer.addView(row);

            // 分隔线
            if (i < endIdx - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(0xFFEEEEEE);
                listContainer.addView(divider, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1));
            }
        }

        tvPageInfo.setText((currentPage + 1) + " / " + totalPages);
        btnPrev.setEnabled(currentPage > 0);
        btnPrev.setAlpha(currentPage > 0 ? 1.0f : 0.3f);
        btnNext.setEnabled(currentPage < totalPages - 1);
        btnNext.setAlpha(currentPage < totalPages - 1 ? 1.0f : 0.3f);
    }

    private void prevPage() {
        if (currentPage > 0) {
            currentPage--;
            renderPage();
        }
    }

    private void nextPage() {
        if (currentPage < totalPages - 1) {
            currentPage++;
            renderPage();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_PAGE_UP) {
            prevPage();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_PAGE_DOWN) {
            nextPage();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }
}









