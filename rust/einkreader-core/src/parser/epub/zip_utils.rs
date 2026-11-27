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

/// [review-#38] 按字节上限限流读取：entry.size() 是 ZIP 头自报值，
/// 恶意/损坏包可能虚报小值 → 分块读取并把总读取量钳制在 cap 内。
/// 读取达到 cap 时返回（内容可能不完整，由调用方判定超限）。
fn read_limited<R: std::io::Read>(r: &mut R, cap: usize) -> std::io::Result<Vec<u8>> {
    let mut buf: Vec<u8> = Vec::new();
    let mut chunk = [0u8; 65536];
    loop {
        if buf.len() >= cap {
            return Ok(buf);
        }
        let room = (cap - buf.len()).min(chunk.len());
        let n = r.read(&mut chunk[..room])?;
        if n == 0 {
            break;
        }
        buf.extend_from_slice(&chunk[..n]);
    }
    Ok(buf)
}

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
    // [review-#38] entry.size() 为头自报值，不信任：分块限流读取兜底
    let bytes = match read_limited(&mut entry, MAX_ENTRY_UNCOMPRESSED as usize + 1) {
        Ok(b) => b,
        Err(_) => return None,
    };
    if bytes.len() > MAX_ENTRY_UNCOMPRESSED as usize {
        return None;
    }
    let buf = String::from_utf8(bytes).ok()?;
    if buf.is_empty() { None } else { Some(buf) }
}

/// 二进制安全读取 ZIP 条目（用于图片等非 UTF-8 数据）
/// 返回原始字节；条目不存在或超限时返回 None
pub fn read_zip_entry_bytes(archive: &mut ZipArchive<fs::File>, path: &str) -> Option<Vec<u8>> {
    let mut entry = archive.by_name(path).ok()?;
    if entry.size() > MAX_ENTRY_UNCOMPRESSED {
        return None;
    }
    // [review-#38] entry.size() 为头自报值，不信任：分块限流读取兜底
    let buf = match read_limited(&mut entry, MAX_ENTRY_UNCOMPRESSED as usize + 1) {
        Ok(b) => b,
        Err(_) => return None,
    };
    if buf.len() > MAX_ENTRY_UNCOMPRESSED as usize {
        return None;
    }
    if buf.is_empty() { None } else { Some(buf) }
}

/// 通过索引二进制安全读取 ZIP 条目
pub fn read_zip_entry_bytes_by_index(archive: &mut ZipArchive<fs::File>, index: usize) -> Option<Vec<u8>> {
    let mut entry = archive.by_index(index).ok()?;
    if entry.size() > MAX_ENTRY_UNCOMPRESSED {
        return None;
    }
    // [review-#38] 分块限流读取兜底
    let buf = match read_limited(&mut entry, MAX_ENTRY_UNCOMPRESSED as usize + 1) {
        Ok(b) => b,
        Err(_) => return None,
    };
    if buf.len() > MAX_ENTRY_UNCOMPRESSED as usize {
        return None;
    }
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
        // [review-#38] 分块限流读取：entry.size() 为头自报值，不信任
        if let Ok(bytes) = read_limited(&mut entry, MAX_ENTRY_UNCOMPRESSED as usize + 1) {
            if bytes.len() <= MAX_ENTRY_UNCOMPRESSED as usize {
                if let Ok(raw) = String::from_utf8(bytes) {
                    if !raw.is_empty() {
                        return raw;
                    }
                }
            }
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
    // [review-#38] 分块限流读取兜底
    let bytes = match read_limited(&mut entry, MAX_ENTRY_UNCOMPRESSED as usize + 1) {
        Ok(b) => b,
        Err(_) => return None,
    };
    if bytes.len() > MAX_ENTRY_UNCOMPRESSED as usize {
        return None;
    }
    let buf = String::from_utf8(bytes).ok()?;
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
    // [P0-#5] 修复：原 .max(1.0) 把 scale 钳到 >= 1.0，超过 360 的大图永远不缩小
    // （本分支只在 width/height > COVER_THUMB_MAX_DIM 时到达，scale 自然在 (0,1)）
    let scale = COVER_THUMB_MAX_DIM as f32 / width.max(height) as f32;
    // 缩放后维度保持 >= 1px，防止极端比例下 round 为 0
    let new_width = ((width as f32 * scale).round()).max(1.0) as u32;
    let new_height = ((height as f32 * scale).round()).max(1.0) as u32;
    
    let resized = image.resize(new_width, new_height, image::imageops::FilterType::Triangle);
    
    // [P0-#5] 修复：原代码直接写出原始 RGBA 像素缓冲（不是 PNG），save_cover 调用方
    // 把它当图片文件解码必然失败。现真正编码为 PNG；编码失败回退原字节（宁有错图无空图）
    let rgba = resized.to_rgba8();
    let mut buffer: Vec<u8> = Vec::new();
    {
        use image::codecs::png::PngEncoder;
        use image::ImageEncoder;
        let encoder = PngEncoder::new(std::io::Cursor::new(&mut buffer));
        let write_result = encoder.write_image(
            rgba.as_raw(),
            rgba.width(),
            rgba.height(),
            image::ColorType::Rgba8.into(),
        );
        if write_result.is_err() {
            return Some(bytes.to_vec());
        }
    }
    
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

#[cfg(test)]
mod tests {
    use super::*;

    /// 构造一张指定尺寸的 PNG 输入
    fn make_png(w: u32, h: u32) -> Vec<u8> {
        let mut png: Vec<u8> = Vec::new();
        {
            use image::codecs::png::PngEncoder;
            use image::ImageEncoder;
            let px = image::Rgba([200u8, 100, 50, 255]);
            PngEncoder::new(std::io::Cursor::new(&mut png))
                .write_image(
                    image::RgbaImage::from_pixel(w, h, px).as_raw(),
                    w,
                    h,
                    image::ColorType::Rgba8.into(),
                )
                .expect("png encode");
        }
        png
    }

    // [P0-#5] 回归：超大图必须真正缩小并产出可解码的 PNG
    #[test]
    fn test_cover_thumbnail_downscales_and_is_png() {
        let png_in = make_png(1000, 800);
        let thumb = generate_cover_thumbnail(&png_in).expect("thumbnail");
        // PNG 魔数（原 bug 返回裸 RGBA，无魔数）
        assert!(thumb.len() >= 8 && &thumb[0..4] == b"\x89PNG");
        let decoded = image::load_from_memory(&thumb).expect("decodable");
        let (w, h) = decoded.dimensions();
        assert!(w <= COVER_THUMB_MAX_DIM && h <= COVER_THUMB_MAX_DIM);
        // 长边 1000 → 360，短边 800 → 288（保持比例）
        assert_eq!(w, 360);
        assert_eq!(h, 288);
    }

    // [P0-#5] 回归：小图原样透传（保持原有行为）
    #[test]
    fn test_cover_thumbnail_small_passthrough() {
        let png_in = make_png(100, 100);
        let thumb = generate_cover_thumbnail(&png_in).expect("thumbnail");
        assert_eq!(thumb, png_in);
    }
}
