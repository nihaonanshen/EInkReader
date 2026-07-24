use std::fs;
use std::path::Path;

fn main() {
    let books_dir = "G:/epub";
    
    if !Path::new(books_dir).exists() {
        println!("目录不存在: {}", books_dir);
        return;
    }
    
    println!("========================================");
    println!("📚 EInkReader 真实书籍测试");
    println!("扫描目录: {}", books_dir);
    println!("========================================\n");
    
    // 列出所有文件
    match fs::read_dir(books_dir) {
        Ok(entries) => {
            for entry in entries.flatten() {
                let path = entry.path();
                if path.is_file() {
                    let name = path.file_name().unwrap_or_default().to_string_lossy();
                    let size = fs::metadata(&path).map(|m| m.len()).unwrap_or(0);
                    
                    if name.ends_with(".epub") {
                        println!("📖 EPUB: {} ({:.2} MB)", name, size as f64 / 1024.0 / 1024.0);
                    } else if name.ends_with(".txt") {
                        println!("📄 TXT:  {} ({:.2} MB)", name, size as f64 / 1024.0 / 1024.0);
                        
                        // 安全检查：文件大小限制
                        const MAX_FILE_SIZE: u64 = 50 * 1024 * 1024;
                        if size > MAX_FILE_SIZE {
                            println!("  ⚠️  警告: 文件超过 50MB 限制，将被拒绝解析");
                        } else {
                            println!("  ✓ 文件大小: {:.2} MB (< 50MB 限制)", size as f64 / 1024.0 / 1024.0);
                        }
                    }
                }
            }
        }
        Err(e) => println!("无法读取目录: {}", e),
    }
    
    println!("\n========================================");
    println!("✅ 扫描完成！");
    println!("========================================");
}
