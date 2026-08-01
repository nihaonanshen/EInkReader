package com.einkreader.test

import android.util.Log
import com.einkreader.core.parser.EpubParserFallback
import com.einkreader.core.parser.TxtParser
import com.einkreader.core.model.EpubResult
import com.einkreader.core.model.Chapter
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * EInkReader 真实书籍集成测试
 * 
 * 从指定目录扫描所有 EPUB/TXT 文件，直接测试解析器对真实文件的处理能力。
 * 运行方式: ./gradlew testDebugUnitTest --tests "EbookIntegrationTest"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EbookIntegrationTest {

    private val TAG = "EbookIntegrationTest"

    // 书籍目录：优先从环境变量 EINKREADER_TEST_BOOKS_DIR 读取，未设置时使用本机默认路径
    // 目录不存在时测试自动跳过（CI 上无该目录，避免空跑）
    private val BOOKS_DIR = System.getenv().getOrDefault("EINKREADER_TEST_BOOKS_DIR", "G:/txt")

    private var totalFiles = 0
    private var parsedSuccess = 0
    private var parsedFail = 0
    private var chaptersFound = 0

    /**
     * 扫描目录下所有文件并逐个测试解析
     */
    @Test
    fun scanAndTestAllBooks() {
        val booksDir = File(BOOKS_DIR)

        // 目录不存在时跳过测试（本机书籍目录，不提交到仓库）
        assumeTrue("书籍目录不存在，跳过集成测试: $BOOKS_DIR", booksDir.exists())

        Log.i(TAG, "开始扫描目录: $BOOKS_DIR")
        scanDirectory(booksDir)

        // 输出统计报告
        Log.i(TAG, "========== 测试报告 ==========")
        Log.i(TAG, "总文件数: $totalFiles")
        Log.i(TAG, "EPUB 解析成功: $parsedSuccess")
        Log.i(TAG, "EPUB 解析失败: $parsedFail")
        Log.i(TAG, "发现章节总数: $chaptersFound")
        Log.i(TAG, "解析成功率: ${if (totalFiles > 0) parsedSuccess * 100 / totalFiles else 0}%")
        Log.i(TAG, "================================")

        // 断言：至少应该能打开一些文件（如果目录存在且有 epub 文件）
        assertThat(totalFiles).isGreaterThan(0)
        assertThat(parsedSuccess).isGreaterThan(0)
    }

    private fun scanDirectory(dir: File) {
        val files = dir.listFiles() ?: return

        for (file in files) {
            if (file.isDirectory) {
                // 递归扫描子目录
                scanDirectory(file)
            } else {
                testBookFile(file)
            }
        }
    }

    private fun testBookFile(file: File) {
        val name = file.name.lowercase()

        if (name.endsWith(".epub")) {
            totalFiles++
            testEpub(file)
        } else if (name.endsWith(".txt") || name.endsWith(".txt.gz")) {
            totalFiles++
            testTxt(file)
        }
    }

    private fun testEpub(file: File) {
        try {
            Log.i(TAG, "正在解析 EPUB: ${file.name}")

            val result = EpubParserFallback.parse(file)

            if (result != null && result.chapters != null) {
                parsedSuccess++
                chaptersFound += result.chapters.size

                Log.i(TAG, "  ✓ 标题: ${result.title}")
                Log.i(TAG, "  ✓ 作者: ${result.author}")
                Log.i(TAG, "  ✓ 章节数: ${result.chapters.size}")

                // 验证第一章内容不为空
                if (result.chapters.isNotEmpty()) {
                    val firstChapter = result.chapters[0]
                    Log.i(TAG, "  ✓ 第一章标题: ${firstChapter.title}")
                    assertThat(firstChapter.content).isNotEmpty()
                }
            } else {
                parsedFail++
                Log.w(TAG, "  ✗ 返回结果为空: ${file.name}")
            }

        } catch (e: Exception) {
            parsedFail++
            Log.e(TAG, "  ✗ 解析失败: ${file.name}", e)
        }
    }

    private fun testTxt(file: File) {
        try {
            Log.i(TAG, "正在解析 TXT: ${file.name}")

            val result = TxtParser.parse(file)

            if (result != null && result.chapters != null) {
                parsedSuccess++
                chaptersFound += result.chapters.size

                Log.i(TAG, "  ✓ 编码: ${result.encoding}")
                Log.i(TAG, "  ✓ 章节数: ${result.chapters.size}")

                if (result.chapters.isNotEmpty()) {
                    Log.i(TAG, "  ✓ 第一章标题: ${result.chapters[0].title}")
                }
            } else {
                parsedFail++
                Log.w(TAG, "  ✗ 返回结果为空: ${file.name}")
            }

        } catch (e: Exception) {
            parsedFail++
            Log.e(TAG, "  ✗ 解析失败: ${file.name}", e)
        }
    }
}
