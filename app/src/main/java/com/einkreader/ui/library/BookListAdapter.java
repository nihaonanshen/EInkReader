package com.einkreader.ui.library;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.einkreader.R;

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

        tvTitle.setText(book.title);
        tvInfo.setText(book.info);

        // 读取并显示阅读进度
        SharedPreferences prefs = context.getSharedPreferences("eink_reader_prefs", Context.MODE_PRIVATE);
        // ★ 与 ReaderActivity.saveProgress() 使用相同的 fileKey 逻辑
        File f = book.file;
        String fileKey = f.getName() + "_" + f.length() + "_" + f.lastModified();
        int lastChapter = prefs.getInt("lc_" + fileKey, -1);
        int lastPage = prefs.getInt("lp_" + fileKey, -1);
        int totalCh = prefs.getInt("total_ch_" + fileKey, 0);
        // ★ 兼容旧版（用路径做 key 的进度）
        if (lastChapter < 0) {
            String filePath = book.file.getAbsolutePath();
            lastChapter = prefs.getInt("lc_" + filePath, -1);
            lastPage = prefs.getInt("lp_" + filePath, -1);
            totalCh = prefs.getInt("total_ch_" + filePath, 0);
        }
        if (lastChapter >= 0 && totalCh > 0) {
            float pct = (totalCh > 0) ? (lastChapter * 100f / totalCh) : 0;
            tvProgress.setText("已读 " + (int)pct + "%  ·  第" + (lastChapter + 1) + "/" + totalCh + "章");
            tvProgress.setVisibility(View.VISIBLE);
        } else {
            tvProgress.setVisibility(View.GONE);
        }

        return convertView;
    }
}
