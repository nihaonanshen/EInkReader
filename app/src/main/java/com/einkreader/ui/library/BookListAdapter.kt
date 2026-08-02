package com.einkreader.ui.library

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.einkreader.EInkReaderApp
import com.einkreader.di.ServiceLocator
import com.einkreader.R
import java.io.File as JavaFile

class BookListAdapter(
    context: Context,
    private var books: List<LibraryActivity.BookInfo>? = null,
    private val nightMode: Boolean = false
) : BaseAdapter() {

    private val ctx = context.applicationContext
    private val prefs: SharedPreferences = ctx.getSharedPreferences("eink_reader_prefs", Context.MODE_PRIVATE)

    /** 封面位图内存缓存（按封面文件路径），避免滚动时主线程重复解码 */
    private val coverBitmapCache = HashMap<String, android.graphics.Bitmap?>()

    init { preloadProgress() }

    private fun preloadProgress() {
        val bl = books ?: return
        val st = ServiceLocator.getBookStorage() ?: return
        for (b in bl) b.preloadedProgress = st.loadProgress(checkNotNull(b.fileKey))
    }

    override fun getCount(): Int = books?.size ?: 0
    override fun getItem(position: Int): Any? = books?.getOrNull(position)
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(ctx).inflate(R.layout.item_book, parent, false)
        val book = books?.get(position) ?: return view
        val rec = book.dbRecord

        // 夜间模式：列表项背景 + 文字
        if (nightMode) {
            view.setBackgroundColor(-0xddddde)
            view.findViewById<TextView>(R.id.book_title).setTextColor(-0x444445)
            view.findViewById<TextView>(R.id.book_info).setTextColor(-0x555556)
            view.findViewById<TextView>(R.id.book_progress_text).setTextColor(-0x444445)
        }

        view.findViewById<TextView>(R.id.book_title).text = book.title

        val tvCover = view.findViewById<TextView>(R.id.book_cover)
        val ivCover = view.findViewById<ImageView>(R.id.book_cover_img)
        // ✅ [Phase 7] format 为 null 时从文件路径推断
        val isEpub = (rec != null && "epub".equals(rec.format, ignoreCase = true)) ||
                     (rec?.filePath != null && checkNotNull(rec.filePath).lowercase().endsWith(".epub"))
        // 优先显示缓存封面图（ReaderActivity 打开书时写入 cacheDir/covers/<key>.jpg）
        val coverKey = checkNotNull(book.fileKey).hashCode().toString(16) + ".jpg"
        val coverPath = JavaFile(ctx.cacheDir, "covers/$coverKey").absolutePath
        val coverBmp = coverBitmapCache.getOrPut(coverPath) {
            val f = JavaFile(coverPath)
            if (f.exists()) {
                try { BitmapFactory.decodeFile(coverPath) } catch (e: Exception) { null }
            } else null
        }
        if (coverBmp != null) {
            ivCover.setImageBitmap(coverBmp)
            ivCover.visibility = View.VISIBLE
            tvCover.visibility = View.GONE
        } else {
            ivCover.visibility = View.GONE
            tvCover.visibility = View.VISIBLE
            if (isEpub) {
                tvCover.text = "EPUB"
                tvCover.setBackgroundColor(-0x46ab71.toInt())
            } else {
                tvCover.text = "TXT"
                tvCover.setBackgroundColor(-0xcccccc.toInt())
            }
        }

        val tvInfo = view.findViewById<TextView>(R.id.book_info)
        if (rec != null && rec.lastReadTime > 0L) {
            tvInfo.text = "最近阅读 ${formatMs(System.currentTimeMillis() - rec.lastReadTime)} · ${book.info}"
        } else {
            tvInfo.text = book.info
        }

        var ch = -1
        var tch = 0
        book.preloadedProgress?.let { ch = it.chapterIndex; tch = it.totalChapters }
        if (ch < 0) {
            val fileObj = book.file ?: return@getView view
            val fkey = getNameKey(fileObj)
            ch = prefs.getInt("lc_$fkey", -1)
            val tc = prefs.getInt("total_ch_$fkey", 0)
            if (tc > 0) tch = tc
            if (ch < 0) {
                val fp = fileObj.absolutePath
                ch = prefs.getInt("lc_$fp", -1)
                val tc2 = prefs.getInt("total_ch_$fp", 0)
                if (tc2 > 0) tch = tc2
            }
        }

        val pb = view.findViewById<ProgressBar>(R.id.book_progress_bar)
        val tp = view.findViewById<TextView>(R.id.book_progress_text)

        if (ch >= 0 && tch > 0) {
            val pct = (ch * 100f / tch).toInt()
            pb.progress = pct
            pb.visibility = View.VISIBLE
            tp.text = "已读 $pct% · 第${ch + 1}章/$tch 章"
            tp.visibility = View.VISIBLE
        } else if (ch >= 0) {
            pb.progress = 0
            pb.visibility = View.GONE
            tp.text = "阅读进度：第${ch + 1}章"
            tp.visibility = View.VISIBLE
        } else {
            pb.visibility = View.GONE
            tp.visibility = View.GONE
        }

        return view
    }

    companion object {
        fun formatMs(deltaMs: Long): String {
            var d = deltaMs
            if (d < 0) d = 0
            val sec = d / 1000
            if (sec < 60) return "刚刚"
            val min = sec / 60
            if (min < 60) return "${min.toString()}分钟前"
            val hour = min / 60
            if (hour < 24) return "${hour.toString()}小时前"
            val day = hour / 24
            if (day < 30) return "${day.toString()}天前"
            val month = day / 30
            if (month < 12) return "${month.toString()}个月前"
            return "${(day / 365).toString()}年前"
        }
    }
}

private fun getNameKey(file: JavaFile): String = file.name + "_" + file.length().toString() + "_" + file.lastModified().toString()
