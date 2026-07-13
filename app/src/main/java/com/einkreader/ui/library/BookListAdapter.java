package com.einkreader.ui.library;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.einkreader.EInkReaderApp;
import com.einkreader.R;
import com.einkreader.core.storage.BookStorage;

import java.io.File;
import java.util.List;

/**
 * 书籍列表的适配器 —— 把 BookInfo 数据变成列表项显示
 */
public class BookListAdapter extends BaseAdapter {

    private Context context;
    private List<LibraryActivity.BookInfo> books;

    public BookListAdapter(Context context, List<LibraryActivity.BookInfo> books) {
        this.context = context;
        this.books = books;
        preloadProgress();
    }

    private void preloadProgress() {
        if (books == null) return;
        BookStorage storage = EInkReaderApp.getBookStorage();
        if (storage == null) return;
        for (LibraryActivity.BookInfo book : books) {
            if (book.fileKey != null) {
                book.preloadedProgress = storage.loadProgress(book.fileKey);
            }
        }
    }

    @Override
    public int getCount() {
        return books != null ? books.size() : 0;
    }

    @Override
    public Object getItem(int position) {
        return books != null ? books.get(position) : null;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_book, parent, false);
        }

        LibraryActivity.BookInfo book = books.get(position);

        TextView tvTitle = (TextView) convertView.findViewById(R.id.book_title);
        TextView tvInfo = (TextView) convertView.findViewById(R.id.book_info);
        TextView tvProgress = (TextView) convertView.findViewById(R.id.book_progress_text);
        TextView tvCover = (TextView) convertView.findViewById(R.id.book_cover);
        ProgressBar pbProgress = (ProgressBar) convertView.findViewById(R.id.book_progress_bar);

        tvTitle.setText(book.title);

        if (book.dbRecord != null && "epub".equalsIgnoreCase(book.dbRecord.format)) {
            tvCover.setText("EPUB");
            tvCover.setBackgroundColor(0xFF1A548F);
        } else {
            tvCover.setText("TXT");
            tvCover.setBackgroundColor(0xFF333333);
        }

        if (book.dbRecord != null && book.dbRecord.lastReadTime > 0) {
            long t = book.dbRecord.lastReadTime;
            String when = formatRelativeTime(System.currentTimeMillis() - t);
            tvInfo.setText("最近阅读 " + when + "  ·  " + book.info);
        } else {
            tvInfo.setText(book.info);
        }

        int lastChapter = -1;
        int lastPage = -1;
        int totalCh = 0;

        BookStorage.BookProgress prog = book.preloadedProgress;
        if (prog != null) {
            lastChapter = prog.chapterIndex;
            lastPage = prog.pageIndex;
            totalCh = prog.totalChapters;
        }

        if (lastChapter < 0) {
            SharedPreferences prefs = context.getSharedPreferences("eink_reader_prefs", Context.MODE_PRIVATE);
            File f = book.file;
            String fileKey = (f != null) ? f.getName() + "_" + f.length() + "_" + f.lastModified() : "";
            lastChapter = prefs.getInt("lc_" + fileKey, -1);
            lastPage = prefs.getInt("lp_" + fileKey, -1);
            totalCh = prefs.getInt("total_ch_" + fileKey, 0);
            if (lastChapter < 0 && f != null) {
                String filePath = f.getAbsolutePath();
                lastChapter = prefs.getInt("lc_" + filePath, -1);
                lastPage = prefs.getInt("lp_" + filePath, -1);
                totalCh = prefs.getInt("total_ch_" + filePath, 0);
            }
        }

        if (lastChapter >= 0 && totalCh > 0) {
            float pct = (totalCh > 0) ? (lastChapter * 100f / totalCh) : 0;
            pbProgress.setProgress((int) pct);
            pbProgress.setVisibility(View.VISIBLE);
            tvProgress.setText("已读 " + (int)pct + "%  ·  第" + (lastChapter + 1) + "/" + totalCh + "章");
            tvProgress.setVisibility(View.VISIBLE);
        } else if (lastChapter >= 0) {
            pbProgress.setProgress(0);
            pbProgress.setVisibility(View.GONE);
            tvProgress.setText("阅读进度：第" + (lastChapter + 1) + "章");
            tvProgress.setVisibility(View.VISIBLE);
        } else {
            pbProgress.setVisibility(View.GONE);
            tvProgress.setVisibility(View.GONE);
        }

        return convertView;
    }

    /**
     * 把毫秒差值格式化成可读的相对时间
     */
    private static String formatRelativeTime(long deltaMs) {
        if (deltaMs < 0) deltaMs = 0;
        long sec = deltaMs / 1000;
        if (sec < 60) return "刚刚";
        long min = sec / 60;
        if (min < 60) return min + "分钟前";
        long hour = min / 60;
        if (hour < 24) return hour + "小时前";
        long day = hour / 24;
        if (day < 30) return day + "天前";
        long month = day / 30;
        if (month < 12) return month + "个月前";
        return (day / 365) + "年前";
    }
}
