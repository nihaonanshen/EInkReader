package com.einkreader.ui.library

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
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

    /** 封面位图内存缓存（按封面文件路径），避免滚动时主线程重复解码。
     *  [P3] 字节预算 LRU（原先无界 HashMap，滚多几本书即 OOM 风险）。
     *  与 ReaderView.bitmapCache / DatabaseHelper.progressCache 同源，用 android.util.LruCache：
     *  本地 android-35 桩 jar 缺少 android.os.LruCache（P2-4 原代码误导入，且 os 版无零参构造） */
    private val coverBitmapCache: LruCache<String, Bitmap> = createCoverCache()

    /** [review-#39] 封面文件路径字符串缓存：getView 不再每次 bind 拼接路径 + 创建 File 对象 */
    private val coverPathCache = HashMap<String, String>()

    init {
        preloadProgress()
    }

    /** 封面解码：[P3] inSampleSize 按 72×96dp 目标尺寸降采样（原封面常 1000px+ 全分辨率解码，滚动峰值内存巨大） */
    private fun decodeCover(path: String): Bitmap? {
        val f = JavaFile(path)
        if (!f.exists()) return null
        return try {
            val targetW = ctx.resources.getDimensionPixelSize(R.dimen.cover_width)
            val targetH = ctx.resources.getDimensionPixelSize(R.dimen.cover_height)
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / sample / 2 >= targetW && bounds.outHeight / sample / 2 >= targetH) {
                sample *= 2
            }
            // 墨水屏无色彩需求：RGB_565 省一半内存（973MB 无 swap，书库滚动更流畅）
            val opts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            }
            android.graphics.BitmapFactory.decodeFile(path, opts)
        } catch (_: Exception) { null }
    }

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

        // 夜间模式：列表项卡片背景 + 文字（运行时 prefs 切换，走 *_night 系列资源）
        if (nightMode) {
            view.setBackgroundResource(R.drawable.bg_card_night)
            view.findViewById<TextView>(R.id.book_title).setTextColor(ctx.resources.getColor(R.color.ink_night))
            view.findViewById<TextView>(R.id.book_info).setTextColor(ctx.resources.getColor(R.color.ink_muted_night))
            view.findViewById<TextView>(R.id.book_progress_text).setTextColor(ctx.resources.getColor(R.color.ink_soft_night))
        } else {
            view.setBackgroundResource(R.drawable.bg_card)
        }

        view.findViewById<TextView>(R.id.book_title).text = book.title

        val tvCover = view.findViewById<TextView>(R.id.book_cover)
        val ivCover = view.findViewById<ImageView>(R.id.book_cover_img)
        // ✅ [Phase 7] format 为 null 时从文件路径推断
        val isEpub = (rec != null && "epub".equals(rec.format, ignoreCase = true)) ||
                     (rec?.filePath != null && checkNotNull(rec.filePath).lowercase().endsWith(".epub"))
        // 优先显示缓存封面图（ReaderActivity 打开书时写入 cacheDir/covers/<key>.jpg）
        val coverKey = checkNotNull(book.fileKey).hashCode().toString(16) + ".jpg"
        val coverPath = coverPathCache.getOrPut(coverKey) { JavaFile(ctx.cacheDir, "covers/$coverKey").absolutePath }
        val coverBmp: Bitmap? = coverBitmapCache.get(coverPath)
            ?: decodeCover(coverPath).also { if (it != null) coverBitmapCache.put(coverPath, it) }
        if (coverBmp != null) {
            ivCover.setImageBitmap(coverBmp)
            ivCover.visibility = View.VISIBLE
            tvCover.visibility = View.GONE
        } else {
            ivCover.visibility = View.GONE
            tvCover.visibility = View.VISIBLE
            // 无封面时显示类型占位：圆角底色 + 柔和文字（不再用硬编码纯色）
            if (isEpub) {
                tvCover.text = "EPUB"
                tvCover.setBackgroundResource(if (nightMode) R.drawable.bg_cover_placeholder_night else R.drawable.bg_cover_placeholder)
            } else {
                tvCover.text = "TXT"
                tvCover.setBackgroundResource(if (nightMode) R.drawable.bg_cover_txt_night else R.drawable.bg_cover_txt)
            }
            tvCover.setTextColor(ctx.resources.getColor(if (nightMode) R.color.placeholder_ink_night else R.color.placeholder_ink))
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
        /** 字节预算 LRU：预算 = 进程最大内存的 1/8，封顶 8MB（墨水屏设备无 swap，滚动时给 GC 留余量） */
        private fun createCoverCache(): LruCache<String, Bitmap> {
            val budget = minOf(Runtime.getRuntime().maxMemory() / 8, 8L * 1024 * 1024L).toInt()
            return object : LruCache<String, Bitmap>(budget) {
                override fun sizeOf(key: String, value: Bitmap): Int = value.getByteCount()
            }
        }

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
