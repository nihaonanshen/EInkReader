use einkreader_core::parser::{parse_txt, parse_epub};
use std::fs;
use std::path::Path;

fn main() {
    let books_dir = "G:/epub";
    
    if !Path::new(books_dir).exists() {
        println!("目录不存在: {}", books_dir);
        return;
    }
    
    println!("========================================");
    println!("📚 EInkReader Rust 解析器测试");
    println!("扫描目录: {}", books_dir);
    println!("========================================\n");
    
    let mut epub_success = 0;
    let mut epub_fail = 0;
    let mut txt_success = 0;
    let mut txt_fail = 0;
    let mut total_chapters = 0;
    
    match fs::read_dir(books_dir) {
        Ok(entries) => {
            for entry in entries.flatten() {
                let path = entry.path();
                if path.is_file() {
                    let name = path.file_name().unwrap_or_default().to_string_lossy();
                    
                    if name.ends_with(".epub") {
                        println!("\n📖 [EPUB] {}", name);
                        match parse_epub(path.to_str().unwrap()) {
                            Ok(result) => {
                                epub_success += 1;
                                let chapter_count = result.chapters.len();
                                total_chapters += chapter_count;
                                println!("  ✓ 标题: {}", result.title);
                                println!("  ✓ 作者: {}", result.author);
                                println!("  ✓ 章节数: {}", chapter_count);
                                if chapter_count > 0 {
                                    println!("  ✓ 第一章: {}", result.chapters[0].title);
                                }
                            }
                            Err(e) => {
                                epub_fail += 1;
                                println!("  ✗ 错误: {}", e);
                            }
                        }
                    } else if name.ends_with(".txt") {
                        println!("\n📄 [TXT] {}", name);
                        let metadata = fs::metadata(&path).unwrap();
                        if metadata.len() > 50 * 1024 * 1024 {
                            println!("  ⚠️  超过 50MB 限制，跳过");
                            txt_fail += 1;
                            continue;
                        }
                        
                        match parse_txt(path.to_str().unwrap(), None) {
                            Ok(result) => {
                                txt_success += 1;
                                let chapter_count = result.chapters.len();
                                total_chapters += chapter_count;
                                println!("  ✓ 标题: {}", result.book_title);
                                println!("  ✓ 编码: {}", result.encoding);
                                println!("  ✓ 章节数: {}", chapter_count);
                                if chapter_count > 0 {
                                    println!("  ✓ 第一章: {}", result.chapters[0].title);
                                }
                            }
                            Err(e) => {
                                txt_fail += 1;
                                println!("  ✗ 错误: {}", e);
                            }
                        }
                    }
                }
            }
        }
        Err(e) => println!("无法读取目录: {}", e),
    }
    
    println!("\n\n========================================");
    println!("📊 测试报告总结");
    println!("========================================");
    println!("总文件数: {}", epub_success + epub_fail + txt_success + txt_fail);
    println!("  ├─ EPUB: {} (成功: {}, 失败: {})", 
        epub_success + epub_fail, epub_success, epub_fail);
    println!("  └─ TXT:  {} (成功: {}, 失败: {})", 
        txt_success + txt_fail, txt_success, txt_fail);
    println!("解析章节总数: {}", total_chapters);
    
    let total = epub_success + epub_fail + txt_success + txt_fail;
    if total > 0 {
        let success = epub_success + txt_success;
        println!("解析成功率: {}/{} ({:.1}%)", 
            success, total, success as f64 / total as f64 * 100.0);
    }
    println!("========================================");
}
