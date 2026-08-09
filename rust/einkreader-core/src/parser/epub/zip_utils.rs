//! ZIP 条目读取工具：安全读取（含 Zip Bomb 防护）
//!
//! 提供统一的 ZIP 条目查找和读取函数，支持：
//! - 原路径查找
//! - 大小写不敏感查找
//! - URL 解码查找

use std::fs;
use std::io::Read;
use image::GenericImageView;
use zip::ZipArchive;

/// 单个 ZIP 条目解压后大小上限 (64MB) — 防 Zip Bomb
const MAX_ENTRY_UNCOMPRESSED: u64 = 64 * 1024 * 1024;

/// 封面缩略图最大维度
pub const COVER_THUMB_MAX_DIM: u32 = 360;

/// 支持的图片扩展名
pub const IMAGE_EXTENSIONS: &[&str] = &[".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg"];

/// 标准化路径：统一为正斜杠、去除多余分隔符
pub fn normalize_path(path: &str) -> String {
    let normalized = path.replace('\\', "/");
    let mut parts: Vec<&str> = Vec::new();
    for seg in normalized.split('/') {
        if seg.is_empty() || seg == "." {
            continue;
        } else if seg == ".." {
            if !parts.is_empty() {
                parts.pop();
            }
        } else {
            parts.push(seg);
        }
    }
    parts.join("/")
}

/// 检查是否是支持的图片格式
pub fn is_image_path(path: &str) -> bool {
    let name = path.to_lowercase();
    IMAGE_EXTENSIONS.iter().any(|ext| name.ends_with(ext))
}

/// 从 ZIP 读取指定 entry 的完整文本
pub fn read_zip_entry(archive: &mut ZipArchive<fs::File>, path: &str) -> Option<String> {
    let mut entry = archive.by_name(path).ok()?;
    if entry.size() > MAX_ENTRY_UNCOMPRESSED {
        return None;
    }
    let mut buf = String::new();
    entry.read_to_string(&mut buf).ok()?;
    if buf.is_empty() { None } else { Some(buf) }
}

/// 二进制安全读取 ZIP 条目（用于图片等非 UTF-8 数据）
/// 返回原始字节；条目不存在或超限时返回 None
pub fn read_zip_entry_bytes(archive: &mut ZipArchive<fs::File>, path: &str) -> Option<Vec<u8>> {
    let mut entry = archive.by_name(path).ok()?;
    if entry.size() > MAX_ENTRY_UNCOMPRESSED {
        return None;
    }
    let mut buf = Vec::new();
    entry.read_to_end(&mut buf).ok()?;
    if buf.is_empty() { None } else { Some(buf) }
}

/// 通过索引二进制安全读取 ZIP 条目
pub fn read_zip_entry_bytes_by_index(archive: &mut ZipArchive<fs::File>, index: usize) -> Option<Vec<u8>> {
    let mut entry = archive.by_index(index).ok()?;
    if entry.size() > MAX_ENTRY_UNCOMPRESSED {
        return None;
    }
    let mut buf = Vec::new();
    entry.read_to_end(&mut buf).ok()?;
    if buf.is_empty() { None } else { Some(buf) }
}

/// 尝试多种路径方式查找 ZIP 条目文本内容
/// 优先级：1.原路径 2.URL解码 3.去除./前缀 4.大小写不敏感匹配文件名
pub fn get_raw_html(archive: &mut ZipArchive<fs::File>, entry_path: &str, href: &str) -> String {
    if let Some(raw) = read_zip_entry(archive, entry_path) {
        return raw;
    }
    if let Ok(decoded) = urlencoding::decode(entry_path) {
        if let Some(raw) = read_zip_entry(archive, &decoded) {
            return raw;
        }
    }
    if let Some(stripped) = entry_path.strip_prefix("./") {
        if let Some(raw) = read_zip_entry(archive, stripped) {
            return raw;
        }
    }
    // 大小写不敏感匹配文件名
    let filename = href.rsplit_once('/').map(|(_, f)| f).unwrap_or(href);
    let matched = find_entry_case_insensitive(archive, filename);
    if let Some(idx) = matched {
        return read_zip_entry_by_index(archive, idx).unwrap_or_default();
    }
    String::new()
}

/// 尝试读取 ZIP 条目，失败或超限时返回空字符串
pub fn try_get_entry(archive: &mut ZipArchive<fs::File>, path: &str) -> String {
    if let Ok(mut entry) = archive.by_name(path) {
        if entry.size() > MAX_ENTRY_UNCOMPRESSED {
            return String::new();
        }
        let mut raw = String::new();
        if entry.read_to_string(&mut raw).is_ok() && !raw.is_empty() {
            return raw;
        }
    }
    String::new()
}

/// 通过索引读取 ZIP 条目文本
fn read_zip_entry_by_index(archive: &mut ZipArchive<fs::File>, index: usize) -> Option<String> {
    let mut entry = archive.by_index(index).ok()?;
    if entry.size() > MAX_ENTRY_UNCOMPRESSED {
        return None;
    }
    let mut buf = String::new();
    entry.read_to_string(&mut buf).ok()?;
    if buf.is_empty() { None } else { Some(buf) }
}

/// 大小写不敏感查找 ZIP 条目索引
pub fn find_entry_case_insensitive(archive: &mut ZipArchive<fs::File>, wanted: &str) -> Option<usize> {
    let wanted_lower = wanted.to_lowercase();
    for i in 0..archive.len() {
        if let Ok(entry) = archive.by_index(i) {
            let name = entry.name().to_lowercase();
            if name == wanted_lower {
                return Some(i);
            }
        }
    }
    None
}

/// 查找图片条目（大小写不敏感）
pub fn find_image_entry(archive: &mut ZipArchive<fs::File>, wanted: &str) -> Option<(String, Vec<u8>)> {
    // 1. 原路径查找
    if let Some(bytes) = read_zip_entry_bytes(archive, wanted) {
        return Some((wanted.to_string(), bytes));
    }
    // 2. 大小写不敏感查找
    if let Some(idx) = find_entry_case_insensitive(archive, wanted) {
        if let Some(bytes) = read_zip_entry_bytes_by_index(archive, idx) {
            let name = archive.by_index(idx).ok()?.name().to_string();
            return Some((name, bytes));
        }
    }
    None
}

/// 生成封面缩略图
/// 返回缩放后的 PNG 字节
pub fn generate_cover_thumbnail(bytes: &[u8]) -> Option<Vec<u8>> {
    let image = image::load_from_memory(bytes).ok()?;
    let (width, height) = image.dimensions();
    
    // 如果小于最大维度，直接返回原图
    if width <= COVER_THUMB_MAX_DIM && height <= COVER_THUMB_MAX_DIM {
        return Some(bytes.to_vec());
    }
    
    // 计算缩放比例，保持宽高比
    let scale = (COVER_THUMB_MAX_DIM as f32 / width.max(height) as f32).max(1.0);
    let new_width = (width as f32 * scale).round() as u32;
    let new_height = (height as f32 * scale).round() as u32;
    
    let resized = image.resize(new_width, new_height, image::imageops::FilterType::Triangle);
    
    // 转换为 RGBA 后保存为 PNG
    let rgba = resized.to_rgba8();
    let mut buffer = Vec::new();
    use image::EncodableLayout;
    buffer.extend_from_slice(rgba.as_bytes());
    
    Some(buffer)
}

/// 保存封面到文件（用于书架缓存）
pub fn save_cover(bytes: &[u8], save_path: &str) -> bool {
    if let Some(thumb) = generate_cover_thumbnail(bytes) {
        if let Ok(mut file) = std::fs::File::create(save_path) {
            use std::io::Write;
            file.write_all(&thumb).is_ok()
        } else {
            false
        }
    } else {
        // 解码失败，直接保存原始数据
        std::fs::write(save_path, bytes).is_ok()
    }
}
