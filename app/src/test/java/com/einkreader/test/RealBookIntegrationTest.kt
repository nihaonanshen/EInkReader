package com.einkreader.test

import android.util.Log
import com.einkreader.core.parser.EpubParserFallback
import com.einkreader.core.parser.TxtParser
import com.einkreader.core.model.EpubResult
import com.einkreader.core.model.Chapter
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assume.assumeTrue
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.ArrayList

/**
 * 真实 EPUB/TXT 书籍集成测试
 *
 * 从 G:\epub 目录扫描所有书籍文件，验证解析器在真实数据上的表现。
 * 运行: ./gradlew testDebugUnitTest --tests "RealBookIntegrationTest"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RealBookIntegrationTest {

    private val TAG = "RealBookIntegrationTest"

    // 书籍目录：优先从环境变量 EINKREADER_TEST_BOOKS_DIR 读取，未设置时使用本机默认路径
    // 目录不存在时测试自动跳过（CI 上无该目录，避免空跑）
    private val BOOKS_DIR = System.getenv().getOrDefault("EINKREADER_TEST_BOOKS_DIR", "G:/epub")

    private var epubCount = 0
    private var txtCount = 0
    private var epubSuccess = 0
    private var txtSuccess = 0
    private var totalChapters = 0
    private val errors = ArrayList<String>()

    @Test
    fun scanAndParseAllBooks() {
        val booksDir = File(BOOKS_DIR)

        // 目录不存在时跳过测试（本机书籍目录，不提交到仓库）
        assumeTrue("书籍目录不存在，跳过集成测试: $BOOKS_DIR", booksDir.exists())

        Log.i(TAG, "========================================")
        Log.i(TAG, "开始扫描真实书籍目录: $BOOKS_DIR")
        Log.i(TAG, "========================================")

        val files = booksDir.listFiles() ?: return

        if (files.isEmpty()) {
            Log.w(TAG, "目录为空")
            return
        }

        for (file in files) {
            if (file.isFile()) {
                val name = file.name.lowercase()

                if (name.endsWith(".epub")) {
                    epubCount++
                    testEpubFile(file)
                } else if (name.endsWith(".txt")) {
                    txtCount++
                    testTxtFile(file)
                }
            }
        }

        // 输出最终报告
        Log.i(TAG, "\n========================================")
        Log.i(TAG, "          📊 测试报告总结")
        Log.i(TAG, "========================================")
        Log.i(TAG, "总扫描文件数: ${epubCount + txtCount}")
        Log.i(TAG, "  ├─ EPUB: $epubCount (成功: $epubSuccess)")
        Log.i(TAG, "  └─ TXT:  $txtCount (成功: $txtSuccess)")
        Log.i(TAG, "解析章节总数: $totalChapters")
        Log.i(TAG, "解析成功率: ${calculateSuccessRate()}")
        Log.i(TAG, "========================================")

        if (errors.isNotEmpty()) {
            Log.w(TAG, "失败原因:")
            for (error in errors) {
                Log.w(TAG, "  - $error")
            }
        }

        // 断言：至少成功解析了一些文件
        assertThat(epubSuccess + txtSuccess).isGreaterThan(0)
    }

    private fun testEpubFile(file: File) {
        try {
            val startTime = System.currentTimeMillis()
            Log.i(TAG, "\n📖 [EPUB] ${file.name} (${formatFileSize(file.length())})")

            val result = EpubParserFallback.parse(file)

            if (result != null && result.chapters != null && result.chapters.isNotEmpty()) {
                epubSuccess++
                totalChapters += result.chapters.size

                Log.i(TAG, "  ✓ 标题: ${result.title}")
                Log.i(TAG, "  ✓ 作者: ${result.author ?: "(无)"}")
                Log.i(TAG, "  ✓ 编码: ${result.encoding}")
                Log.i(TAG, "  ✓ 章节数: ${result.chapters.size}")
                Log.i(TAG, "  ✓ 图片数: ${result.images.size}")

                // 显示前3章标题
                for (i in 0 until Math.min(3, result.chapters.size)) {
                    val ch = result.chapters[i]
                    val contentPreview = if (ch.content != null) {
                        ch.content!!.substring(0, Math.min(50, ch.content!!.length)) + "..."
                    } else "(空)"
                    Log.i(TAG, "    [$i] ${ch.title} → $contentPreview")
                }

                if (result.chapters.size > 3) {
                    Log.i(TAG, "    ... 共 ${result.chapters.size} 章")
                }

                Log.i(TAG, "  ⏱️  耗时: ${System.currentTimeMillis() - startTime}ms")
            } else {
                errors.add("${file.name}: 返回结果为空或无章节")
                Log.w(TAG, "  ✗ 返回结果为空或无章节")
            }

        } catch (e: Exception) {
            errors.add("${file.name}: ${e.message}")
            Log.e(TAG, "  ✗ 解析失败: ${e.message}", e)
        }
    }

    private fun testTxtFile(file: File) {
        try {
            val startTime = System.currentTimeMillis()
            Log.i(TAG, "\n📄 [TXT] ${file.name} (${formatFileSize(file.length())})")

            val result = TxtParser.parse(file, null)

            if (result != null && result.chapters != null && result.chapters.isNotEmpty()) {
                txtSuccess++
                totalChapters += result.chapters.size

                Log.i(TAG, "  ✓ 标题: ${result.bookTitle}")
                Log.i(TAG, "  ✓ 编码: ${result.encoding}")
                Log.i(TAG, "  ✓ 章节数: ${result.chapters.size}")

                // 显示前2章
                for (i in 0 until Math.min(2, result.chapters.size)) {
                    val ch = result.chapters[i]
                    val contentPreview = if (ch.content != null) {
                        ch.content!!.substring(0, Math.min(50, ch.content!!.length)) + "..."
                    } else "(空)"
                    Log.i(TAG, "    [$i] ${ch.title} → $contentPreview")
                }

                Log.i(TAG, "  ⏱️  耗时: ${System.currentTimeMillis() - startTime}ms")
            } else {
                errors.add("${file.name}: 返回结果为空或无章节")
                Log.w(TAG, "  ✗ 返回结果为空或无章节")
            }

        } catch (e: Exception) {
            errors.add("${file.name}: ${e.message}")
            Log.e(TAG, "  ✗ 解析失败: ${e.message}", e)
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024L * 1024 -> String.format("%.2f KB", bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }

    private fun calculateSuccessRate(): String {
        val total = epubCount + txtCount
        if (total == 0) return "N/A (无文件)"
        val success = epubSuccess + txtSuccess
        return String.format("%d/%d (%.1f%%)", success, total, success * 100.0 / total)
    }
}
