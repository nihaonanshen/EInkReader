package com.einkreader.test;

import android.util.Log;

import com.einkreader.core.parser.EpubParser;
import com.einkreader.core.parser.TxtParser;
import com.einkreader.core.model.Chapter;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * EInkReader 真实书籍集成测试
 * 
 * 从指定目录扫描所有 EPUB/TXT 文件，直接测试解析器对真实文件的处理能力。
 * 运行方式: ./gradlew testDebugUnitTest --tests "EbookIntegrationTest"
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class EbookIntegrationTest {

    private static final String TAG = "EbookIntegrationTest";

    // ✅ 修改这里为你的书籍目录路径（Windows 下使用双反斜杠或正斜杠）
    private static final String BOOKS_DIR = "G:\\txt";
    
    // 可替换为正斜杠路径 (推荐跨平台兼容)
    // private static final String BOOKS_DIR = "G:/txt";
    
    private int totalFiles = 0;
    private int parsedSuccess = 0;
    private int parsedFail = 0;
    private int chaptersFound = 0;

    /**
     * 扫描目录下所有文件并逐个测试解析
     */
    @Test
    public void scanAndTestAllBooks() {
        File booksDir = new File(BOOKS_DIR);
        
        if (!booksDir.exists()) {
            Log.w(TAG, "目录不存在: " + BOOKS_DIR);
            // 测试不会失败，仅记录警告
            return;
        }
        
        Log.i(TAG, "开始扫描目录: " + BOOKS_DIR);
        scanDirectory(booksDir);
        
        // 输出统计报告
        Log.i(TAG, "========== 测试报告 ==========");
        Log.i(TAG, "总文件数: " + totalFiles);
        Log.i(TAG, "EPUB 解析成功: " + parsedSuccess);
        Log.i(TAG, "EPUB 解析失败: " + parsedFail);
        Log.i(TAG, "发现章节总数: " + chaptersFound);
        Log.i(TAG, "解析成功率: " + ((totalFiles > 0) ? (parsedSuccess * 100 / totalFiles) : 0) + "%");
        Log.i(TAG, "================================");
        
        // 断言：至少应该能打开一些文件（如果目录存在且有 epub 文件）
        assertThat(totalFiles).isGreaterThan(0);
        assertThat(parsedSuccess).isGreaterThan(0);
    }

    private void scanDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        
        for (File file : files) {
            if (file.isDirectory()) {
                // 递归扫描子目录
                scanDirectory(file);
            } else {
                testBookFile(file);
            }
        }
    }

    private void testBookFile(File file) {
        String name = file.getName().toLowerCase();
        
        if (name.endsWith(".epub")) {
            totalFiles++;
            testEpub(file);
        } else if (name.endsWith(".txt") || name.endsWith(".txt.gz")) {
            totalFiles++;
            testTxt(file);
        }
    }

    private void testEpub(File file) {
        try {
            Log.i(TAG, "正在解析 EPUB: " + file.getName());
            
            EpubParser.EpubResult result = EpubParser.parse(file);
            
            if (result != null && result.chapters != null) {
                parsedSuccess++;
                chaptersFound += result.chapters.size();
                
                Log.i(TAG, "  ✓ 标题: " + result.title);
                Log.i(TAG, "  ✓ 作者: " + result.author);
                Log.i(TAG, "  ✓ 章节数: " + result.chapters.size());
                
                // 验证第一章内容不为空
                if (!result.chapters.isEmpty()) {
                    Chapter firstChapter = result.chapters.get(0);
                    Log.i(TAG, "  ✓ 第一章标题: " + firstChapter.getTitle());
                    assertThat(firstChapter.getContent()).isNotEmpty();
                }
            } else {
                parsedFail++;
                Log.w(TAG, "  ✗ 返回结果为空: " + file.getName());
            }
            
        } catch (Exception e) {
            parsedFail++;
            Log.e(TAG, "  ✗ 解析失败: " + file.getName(), e);
        }
    }

    private void testTxt(File file) {
        try {
            Log.i(TAG, "正在解析 TXT: " + file.getName());
            
            TxtParser.ParseResult result = TxtParser.parse(file);
            
            if (result != null && result.chapters != null) {
                parsedSuccess++;
                chaptersFound += result.chapters.size();
                
                Log.i(TAG, "  ✓ 编码: " + result.encoding);
                Log.i(TAG, "  ✓ 章节数: " + result.chapters.size());
                
                if (!result.chapters.isEmpty()) {
                    Log.i(TAG, "  ✓ 第一章标题: " + result.chapters.get(0).getTitle());
                }
            } else {
                parsedFail++;
                Log.w(TAG, "  ✗ 返回结果为空: " + file.getName());
            }
            
        } catch (Exception e) {
            parsedFail++;
            Log.e(TAG, "  ✗ 解析失败: " + file.getName(), e);
        }
    }
}
