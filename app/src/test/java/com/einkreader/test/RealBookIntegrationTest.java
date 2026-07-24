package com.einkreader.test;

import android.util.Log;

import com.einkreader.core.parser.EpubParser;
import com.einkreader.core.parser.TxtParser;
import com.einkreader.core.model.Chapter;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 真实 EPUB/TXT 书籍集成测试
 * 
 * 从 G:\epub 目录扫描所有书籍文件，验证解析器在真实数据上的表现。
 * 运行: ./gradlew testDebugUnitTest --tests "RealBookIntegrationTest"
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class RealBookIntegrationTest {

    private static final String TAG = "RealBookIntegrationTest";
    
    // ✅ 你的真实书籍目录
    private static final String BOOKS_DIR = "G:/epub";
    
    private int epubCount = 0;
    private int txtCount = 0;
    private int epubSuccess = 0;
    private int txtSuccess = 0;
    private int totalChapters = 0;
    private List<String> errors = new ArrayList<>();

    @Test
    public void scanAndParseAllBooks() throws Exception {
        File booksDir = new File(BOOKS_DIR);
        
        if (!booksDir.exists()) {
            Log.w(TAG, "目录不存在: " + BOOKS_DIR);
            return;
        }
        
        Log.i(TAG, "========================================");
        Log.i(TAG, "开始扫描真实书籍目录: " + BOOKS_DIR);
        Log.i(TAG, "========================================");
        
        File[] files = booksDir.listFiles();
        if (files == null || files.length == 0) {
            Log.w(TAG, "目录为空");
            return;
        }
        
        for (File file : files) {
            if (file.isFile()) {
                String name = file.getName().toLowerCase();
                
                if (name.endsWith(".epub")) {
                    epubCount++;
                    testEpubFile(file);
                } else if (name.endsWith(".txt")) {
                    txtCount++;
                    testTxtFile(file);
                }
            }
        }
        
        // 输出最终报告
        Log.i(TAG, "\n========================================");
        Log.i(TAG, "          📊 测试报告总结");
        Log.i(TAG, "========================================");
        Log.i(TAG, "总扫描文件数: " + (epubCount + txtCount));
        Log.i(TAG, "  ├─ EPUB: " + epubCount + " (成功: " + epubSuccess + ")");
        Log.i(TAG, "  └─ TXT:  " + txtCount + " (成功: " + txtSuccess + ")");
        Log.i(TAG, "解析章节总数: " + totalChapters);
        Log.i(TAG, "解析成功率: " + calculateSuccessRate());
        Log.i(TAG, "========================================");
        
        if (!errors.isEmpty()) {
            Log.w(TAG, "失败原因:");
            for (String error : errors) {
                Log.w(TAG, "  - " + error);
            }
        }
        
        // 断言：至少成功解析了一些文件
        assertThat(epubSuccess + txtSuccess).isGreaterThan(0);
    }

    private void testEpubFile(File file) {
        try {
            long startTime = System.currentTimeMillis();
            Log.i(TAG, "\n📖 [EPUB] " + file.getName() + " (" + formatFileSize(file.length()) + ")");
            
            EpubParser.EpubResult result = EpubParser.parse(file);
            
            if (result != null && result.chapters != null && !result.chapters.isEmpty()) {
                epubSuccess++;
                totalChapters += result.chapters.size();
                
                Log.i(TAG, "  ✓ 标题: " + result.title);
                Log.i(TAG, "  ✓ 作者: " + (result.author != null ? result.author : "(无)"));
                Log.i(TAG, "  ✓ 编码: " + result.encoding);
                Log.i(TAG, "  ✓ 章节数: " + result.chapters.size());
                Log.i(TAG, "  ✓ 图片数: " + result.images.size());
                
                // 显示前3章标题
                for (int i = 0; i < Math.min(3, result.chapters.size()); i++) {
                    Chapter ch = result.chapters.get(i);
                    String contentPreview = ch.getContent() != null ? 
                        ch.getContent().substring(0, Math.min(50, ch.getContent().length())) + "..." : "(空)";
                    Log.i(TAG, "    [" + (i+1) + "] " + ch.getTitle() + " → " + contentPreview);
                }
                
                if (result.chapters.size() > 3) {
                    Log.i(TAG, "    ... 共 " + result.chapters.size() + " 章");
                }
                
                Log.i(TAG, "  ⏱️  耗时: " + (System.currentTimeMillis() - startTime) + "ms");
            } else {
                errors.add(file.getName() + ": 返回结果为空或无章节");
                Log.w(TAG, "  ✗ 返回结果为空或无章节");
            }
            
        } catch (Exception e) {
            errors.add(file.getName() + ": " + e.getMessage());
            Log.e(TAG, "  ✗ 解析失败: " + e.getMessage(), e);
        }
    }

    private void testTxtFile(File file) {
        try {
            long startTime = System.currentTimeMillis();
            Log.i(TAG, "\n📄 [TXT] " + file.getName() + " (" + formatFileSize(file.length()) + ")");
            
            TxtParser.ParseResult result = TxtParser.parse(file, null);
            
            if (result != null && result.chapters != null && !result.chapters.isEmpty()) {
                txtSuccess++;
                totalChapters += result.chapters.size();
                
                Log.i(TAG, "  ✓ 标题: " + result.bookTitle);
                Log.i(TAG, "  ✓ 编码: " + result.encoding);
                Log.i(TAG, "  ✓ 章节数: " + result.chapters.size());
                
                // 显示前2章
                for (int i = 0; i < Math.min(2, result.chapters.size()); i++) {
                    Chapter ch = result.chapters.get(i);
                    String contentPreview = ch.getContent() != null ? 
                        ch.getContent().substring(0, Math.min(50, ch.getContent().length())) + "..." : "(空)";
                    Log.i(TAG, "    [" + (i+1) + "] " + ch.getTitle() + " → " + contentPreview);
                }
                
                Log.i(TAG, "  ⏱️  耗时: " + (System.currentTimeMillis() - startTime) + "ms");
            } else {
                errors.add(file.getName() + ": 返回结果为空或无章节");
                Log.w(TAG, "  ✗ 返回结果为空或无章节");
            }
            
        } catch (Exception e) {
            errors.add(file.getName() + ": " + e.getMessage());
            Log.e(TAG, "  ✗ 解析失败: " + e.getMessage(), e);
        }
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private String calculateSuccessRate() {
        int total = epubCount + txtCount;
        if (total == 0) return "N/A (无文件)";
        int success = epubSuccess + txtSuccess;
        return String.format("%d/%d (%.1f%%)", success, total, success * 100.0 / total);
    }
}
