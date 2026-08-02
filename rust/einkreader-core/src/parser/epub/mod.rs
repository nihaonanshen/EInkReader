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
use zip_utils::{get_raw_html, read_zip_entry_bytes};

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

    // 4. 逐章解析 spine（收集图片路径）
    let mut chapters: Vec<EpubChapter> = Vec::new();

    for (i, href) in spine_hrefs.iter().enumerate() {
        // ✅ ZIP Slip 防护
        if sanitize_zip_path(href).is_none() {
            eprintln!("跳过不安全的路径: {}", href);
            continue;
        }

        let entry_path = format!("{}{}", opf_dir, href);
        // 章节 XHTML 所在目录（用于解析相对图片路径，如 "OEBPS/Text/"）
        let base_dir = entry_path
            .rsplit_once('/')
            .map(|(d, _)| format!("{}/", d))
            .unwrap_or_default();

        let raw_html = get_raw_html(&mut archive, &entry_path, href);
        let parsed = if raw_html.is_empty() {
            XhtmlContent { text: String::new(), image_paths: Vec::new(), paragraph_types: Vec::new() }
        } else {
            parse_xhtml(&raw_html, &base_dir)
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

    // 5. 提取图片字节（遍历章节引用的图片路径，而非 manifest——保证 key 与
    //    [[IMAGE:path]] 标记一致；JPEG 等二进制数据用字节读取，不能 read_to_string）
    // 上限：单图 1MB，总图 8MB（与 Java fallback 一致）
    const MAX_IMAGE_BYTES: u64 = 1 * 1024 * 1024;
    const MAX_TOTAL_IMAGE_BYTES: u64 = 8 * 1024 * 1024;
    let mut images: HashMap<String, String> = HashMap::new();
    let mut all_image_paths: Vec<String> = Vec::new();
    // 封面图单独入 map（key 固定为 "__cover__"，供书架显示）
    const COVER_KEY: &str = "__cover__";
    if let Some(cover_href) = &opf_result.cover_href {
        // cover 路径相对 OPF 目录
        let cover_path = if cover_href.starts_with(opf_dir.trim_end_matches('/'))
            || cover_href.starts_with('/')
        {
            cover_href.clone()
        } else {
            format!("{}{}", opf_dir, cover_href)
        };
        if let Some(bytes) = read_zip_entry_bytes(&mut archive, &cover_path) {
            if !bytes.is_empty() && (bytes.len() as u64) <= MAX_IMAGE_BYTES {
                images.insert(COVER_KEY.to_string(), STANDARD.encode(&bytes));
            }
        }
    }
    for ch in &chapters {
        for p in &ch.image_paths {
            if !all_image_paths.contains(p) {
                all_image_paths.push(p.clone());
            }
        }
    }
    let mut total_image_bytes: u64 = 0;
    for path in &all_image_paths {
        if path.starts_with("http://") || path.starts_with("https://") || path.starts_with("data:") {
            continue; // 外链图片无法从 ZIP 读取
        }
        if let Some(bytes) = read_zip_entry_bytes(&mut archive, path) {
            let len = bytes.len() as u64;
            if len > MAX_IMAGE_BYTES {
                eprintln!("跳过超大图片: {} ({} bytes)", path, len);
                continue;
            }
            if total_image_bytes >= MAX_TOTAL_IMAGE_BYTES {
                eprintln!("图片总字节已达上限，跳过: {}", path);
                break;
            }
            images.insert(path.clone(), STANDARD.encode(&bytes));
            total_image_bytes += len;
        }
    }
    let _ = &manifest; // manifest 仍由 parse_opf 返回（保留签名），图片提取不再依赖它

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

    // 章节 XHTML 所在目录（用于解析相对图片路径，如 "OEBPS/Text/"）
    let base_dir = sanitized
        .rsplit_once('/')
        .map(|(d, _)| format!("{}/", d))
        .unwrap_or_default();

    // 解析 XHTML（复用已有的 parse_xhtml，提取 text）
    let parsed = parse_xhtml(&html, &base_dir);
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
    use std::io::Write;

    #[test]
    fn test_sanitize_zip_path() {
        assert_eq!(sanitize_zip_path("Text/ch1.xhtml").as_deref(), Some("Text/ch1.xhtml"));
        assert_eq!(sanitize_zip_path("../escape.xhtml"), None);
        assert_eq!(sanitize_zip_path("a\\b.xhtml"), None);
        assert_eq!(sanitize_zip_path(""), None);
        assert_eq!(sanitize_zip_path("/abs/path.xhtml"), None);
        assert!(sanitize_zip_path("中文/目录.xhtml").is_some());
    }

    /// 构造一个最小 EPUB（含一张 JPEG 图片），验证图片字节提取与 IMAGE 标记
    #[test]
    fn test_parse_epub_extracts_image_bytes_and_markers() {
        // 构造一个极小的合法 JPEG 头（FF D8 FF E0 ... FF D9），非 UTF-8 二进制
        let jpeg: Vec<u8> = vec![
            0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
            0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0xFF, 0xD9,
        ];
        let opf = r#"<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>图片测试</dc:title>
  </metadata>
  <manifest>
    <item id="c1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="img" href="Images/pic.jpeg" media-type="image/jpeg" properties="cover-image"/>
  </manifest>
  <spine><itemref idref="c1"/></spine>
</package>"#;
        let ch1 = r#"<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>第一章</title></head>
<body><p>正文<img src="../Images/pic.jpeg"/>结束</p></body></html>"#;

        let dir = tempfile::tempdir().unwrap();
        let epub_path = dir.path().join("test_images.epub");
        {
            let file = std::fs::File::create(&epub_path).unwrap();
            let mut zw = zip::ZipWriter::new(file);
            let opts: zip::write::FileOptions<'_, ()> =
                zip::write::FileOptions::default().compression_method(zip::CompressionMethod::Stored);
            zw.start_file("mimetype", opts).unwrap();
            zw.write_all(b"application/epub+zip").unwrap();
            zw.start_file("META-INF/container.xml", opts).unwrap();
            zw.write_all(br#"<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"#).unwrap();
            zw.start_file("content.opf", opts).unwrap();
            zw.write_all(opf.as_bytes()).unwrap();
            zw.start_file("Text/ch1.xhtml", opts).unwrap();
            zw.write_all(ch1.as_bytes()).unwrap();
            zw.start_file("Images/pic.jpeg", opts).unwrap();
            zw.write_all(&jpeg).unwrap();
            zw.finish().unwrap();
        }

        let result = parse_epub(epub_path.to_str().unwrap()).expect("解析 EPUB 应成功");
        // 图片字节被提取（key = ZIP 绝对路径）
        // 章节引用的图片 + 封面（__cover__）各一条
        assert_eq!(result.images.len(), 2, "应有章节图 + __cover__ 封面，got {:?}", result.images.keys());
        // 封面：manifest properties="cover-image" → images["__cover__"]
        let cover = result.images.get("__cover__").expect("应有 __cover__ 封面");
        let cover_decoded = base64::engine::general_purpose::STANDARD
            .decode(cover)
            .expect("封面 base64 应可解码");
        assert_eq!(cover_decoded, jpeg, "封面字节应与原图一致");
        let (key, b64) = result.images
            .iter()
            .find(|(k, _)| k.as_str() == "Images/pic.jpeg")
            .expect("应有 Images/pic.jpeg");
        assert_eq!(key, "Images/pic.jpeg");
        // base64 解码回原字节
        let decoded = base64::engine::general_purpose::STANDARD
            .decode(b64)
            .expect("base64 应可解码");
        assert_eq!(decoded, jpeg, "图片字节应完整往返");

        // 章节标记与 image_paths
        assert_eq!(result.chapters.len(), 1);
        let ch = &result.chapters[0];
        assert_eq!(ch.image_paths, vec!["Images/pic.jpeg"]);
        // 懒加载内容包含标记
        let content = load_chapter_content(epub_path.to_str().unwrap(), "Text/ch1.xhtml")
            .expect("懒加载应成功");
        assert!(
            content.contains("[[IMAGE:Images/pic.jpeg]]"),
            "内容应包含 IMAGE 标记, got: {:?}",
            content
        );
        assert!(content.contains("正文"), "正文文本应保留");
    }
}
