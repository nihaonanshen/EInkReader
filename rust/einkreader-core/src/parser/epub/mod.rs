//! EPUB 文件解析器
//!
//! EPUB 本质是一个 ZIP 包，包含：
//!   - META-INF/container.xml  → 找到 OPF 文件路径
//!   - *.opf                  → 书籍元数据 + 文件清单(manifest) + 阅读顺序(spine)
//!   - *.ncx                  → 目录结构（章节标题映射）
//!   - *.xhtml / *.html       → 正文内容
//!
//! 本实现提供最小可用版本：提取 title/author、按 spine 读取 XHTML、清理 HTML 标签、NCX 标题匹配。
//!
//! 模块划分：
//!   - container.rs  — container.xml 解析
//!   - opf.rs        — OPF 元数据 / manifest / spine
//!   - toc.rs        — NCX + EPUB3 nav 目录解析
//!   - title.rs      — 章节标题提取
//!   - xhtml.rs      — 正文内容解析与 HTML 清理
//!   - zip_utils.rs  — ZIP 条目安全读取

mod container;
mod opf;
mod title;
mod toc;
mod xhtml;
mod zip_utils;

use std::collections::HashMap;
use std::fs;
use std::io::Read;
use std::path::Path;
use base64::{engine::general_purpose::STANDARD, Engine};

use crate::types::{EpubChapter, EpubParseResult};

use container::parse_container;
use opf::parse_opf;
use title::{extract_title_from_href, extract_title_from_raw, is_placeholder_title, resolve_title};
use toc::parse_ncx;
use xhtml::{parse_xhtml, XhtmlContent};
use zip_utils::{get_raw_html, try_get_entry};

/// 最大文件尺寸 (150MB) — 防止 OOM，支持大型学术/教材类 EPUB
const MAX_FILE_SIZE: u64 = 150 * 1024 * 1024;

/// 最大条目数量 — 防止 Zip Bomb
const MAX_EPUB_ENTRIES: usize = 10_000;

/// 验证路径是否安全（不尝试逃离 ZIP 容器）
fn sanitize_zip_path(path: &str) -> Option<String> {
    // 阻止路径遍历攻击（.. 及 Windows 风格反斜杠路径）
    if path.contains("..") || path.contains('\\') {
        return None;
    }
    // 拒绝空路径、绝对路径与含 nul 字节的路径
    if path.is_empty() || path.starts_with('/') || path.contains('\0') {
        return None;
    }
    // 允许任意字符（含中文等非 ASCII 文件名），仅拦截路径逃逸
    Some(path.to_string())
}

/// 解析 EPUB 文件，返回 EpubParseResult
pub fn parse_epub(file_path: &str) -> Result<EpubParseResult, String> {
    let file = fs::File::open(file_path)
        .map_err(|e| format!("打开 EPUB 文件失败: {}", e))?;

    // ✅ 安全检查：验证 EPUB 文件大小
    let metadata = file.metadata()
        .map_err(|e| format!("读取文件元数据失败: {}", e))?;
    if metadata.len() > MAX_FILE_SIZE {
        return Err(format!(
            "EPUB 文件过大: {} bytes (最大允许 {} MB)",
            metadata.len(),
            MAX_FILE_SIZE / 1024 / 1024
        ));
    }

    let mut archive = zip::ZipArchive::new(file)
        .map_err(|e| format!("读取 ZIP 失败: {}", e))?;

    // ✅ 安全检查：限制 ZIP 条目数量
    if archive.len() > MAX_EPUB_ENTRIES {
        return Err(format!(
            "EPUB 包含过多条目: {} (最大允许 {})",
            archive.len(), MAX_EPUB_ENTRIES
        ));
    }

    // 1. container.xml → OPF 路径
    let opf_path = parse_container(&mut archive)?;

    // ✅ ZIP Slip 防护
    if sanitize_zip_path(&opf_path).is_none() {
        return Err("无效的 OPF 路径: 包含路径遍历序列".to_string());
    }

    let opf_dir = opf_path
        .rsplit_once('/')
        .map(|(d, _)| format!("{}/", d))
        .unwrap_or_default();

    // 2. OPF → 元数据 + manifest + spine
    let (opf_result, manifest, spine_hrefs) = parse_opf(&mut archive, &opf_path)?;

    // 3. NCX → 标题映射
    let ncx_titles = parse_ncx(&mut archive, &opf_dir, &opf_result);

    // 4. 提取图片（从 manifest 中的 image/* 类型）
    let mut images: HashMap<String, String> = HashMap::new();
    for (_id, href) in &manifest {
        // 需要从 manifest 获取 media-type，但我们当前没有存储 media-type
        // 这里简单通过文件扩展名判断
        if href.ends_with(".jpg")
            || href.ends_with(".jpeg")
            || href.ends_with(".png")
            || href.ends_with(".gif")
            || href.ends_with(".webp")
            || href.ends_with(".svg")
            || href.ends_with(".bmp")
        {
            let full_path = format!("{}{}", opf_dir, href);
            let raw = try_get_entry(&mut archive, &full_path);
            if !raw.is_empty() {
                images.insert(href.clone(), STANDARD.encode(raw.as_bytes()));
            } else {
                // 尝试不加 opf_dir
                let raw = try_get_entry(&mut archive, href);
                if !raw.is_empty() {
                    images.insert(href.clone(), STANDARD.encode(raw.as_bytes()));
                }
            }
        }
    }

    // 5. 逐章解析 spine
    let mut chapters: Vec<EpubChapter> = Vec::new();

    for (i, href) in spine_hrefs.iter().enumerate() {
        // ✅ ZIP Slip 防护
        if sanitize_zip_path(href).is_none() {
            eprintln!("跳过不安全的路径: {}", href);
            continue;
        }

        let entry_path = format!("{}{}", opf_dir, href);

        let raw_html = get_raw_html(&mut archive, &entry_path, href);
        let parsed = if raw_html.is_empty() {
            XhtmlContent { text: String::new(), image_paths: Vec::new(), paragraph_types: Vec::new() }
        } else {
            parse_xhtml(&raw_html)
        };

        // 标题
        let mut title = resolve_title(href, &ncx_titles, i);

        // 从 HTML <h1>/<h2>/<title> 提取标题（NCX 标题为占位符时优先用 HTML 里的真实标题）
        let raw_title = extract_title_from_raw(&raw_html);
        if is_placeholder_title(&title) {
            if let Some(ref rt) = raw_title {
                if rt.len() < 200 && !is_placeholder_title(rt) {
                    title = rt.clone();
                }
            }
        }

        // 占位标题（如 NCX 与 HTML 都是 "Chapter 1"）降级为 "第N章"，而不是原样显示或跳过整章
        if is_placeholder_title(&title) {
            title = extract_title_from_href(href, i);
        }
        if is_placeholder_title(&title) {
            title = format!("第{}章", i + 1);
        }

        chapters.push(EpubChapter {
            title,
            content: None, // 懒加载：初始未填充
            image_paths: parsed.image_paths,
            paragraph_types: parsed.paragraph_types,
            xhtml_path: Some(entry_path), // 保存路径以便后续按需加载
        });
    }

    Ok(EpubParseResult {
        title: if opf_result.title.is_empty() {
            Path::new(file_path)
                .file_stem()
                .and_then(|s| s.to_str())
                .unwrap_or("未知书籍")
                .to_string()
        } else {
            opf_result.title
        },
        author: opf_result.author,
        encoding: if opf_result.encoding.is_empty() {
            "UTF-8".to_string()
        } else {
            opf_result.encoding
        },
        chapters,
        images,
    })
}

/// 从 EPUB 中指定章节加载内容（用于懒加载）
/// file_path: EPUB 文件路径
/// chapter_xhtml_path: ZIP 内的 XHTML 文件路径（如 "chapter01.xhtml"）
pub fn load_chapter_content(file_path: &str, chapter_xhtml_path: &str) -> Result<String, String> {
    let file = std::fs::File::open(file_path).map_err(|e| format!("打开 EPUB 文件失败: {}", e))?;
    let mut archive = zip::ZipArchive::new(file).map_err(|e| format!("创建 ZipArchive 失败: {}", e))?;

    // 安全清理路径
    let sanitized = sanitize_zip_path(chapter_xhtml_path)
        .ok_or(format!("无效的章节路径: {}", chapter_xhtml_path))?;

    // 获取条目
    let mut entry = archive.by_name(&sanitized)
        .map_err(|_| format!("找不到章节文件: {}", chapter_xhtml_path))?;

    let mut html = String::new();
    entry.read_to_string(&mut html)
        .map_err(|e| format!("读取章节内容失败: {}&{}", e, chapter_xhtml_path))?;

    if html.is_empty() {
        return Ok(String::new());
    }

    // 解析 XHTML（复用已有的 parse_xhtml，提取 text）
    let parsed = parse_xhtml(&html);
    // 限制内容大小（500KB）
    const MAX_LOADED_CHAPTER: usize = 500_000;
    let content = if parsed.text.len() > MAX_LOADED_CHAPTER {
        format!("{}\n\n……(篇幅受限)……", &parsed.text[..MAX_LOADED_CHAPTER])
    } else {
        parsed.text
    };

    Ok(content)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_sanitize_zip_path() {
        assert_eq!(sanitize_zip_path("Text/ch1.xhtml").as_deref(), Some("Text/ch1.xhtml"));
        assert_eq!(sanitize_zip_path("../escape.xhtml"), None);
        assert_eq!(sanitize_zip_path("a\\b.xhtml"), None);
        assert_eq!(sanitize_zip_path(""), None);
        assert_eq!(sanitize_zip_path("/abs/path.xhtml"), None);
        assert!(sanitize_zip_path("中文/目录.xhtml").is_some());
    }
}
