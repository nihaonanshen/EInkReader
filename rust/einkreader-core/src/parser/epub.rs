//! EPUB 文件解析器
//!
//! EPUB 本质是一个 ZIP 包，包含：
//!   - META-INF/container.xml  → 找到 OPF 文件路径
//!   - *.opf                  → 书籍元数据 + 文件清单(manifest) + 阅读顺序(spine)
//!   - *.ncx                  → 目录结构（章节标题映射）
//!   - *.xhtml / *.html       → 正文内容
//!
//! 本实现提供最小可用版本：提取 title/author、按 spine 读取 XHTML、清理 HTML 标签、NCX 标题匹配。

use once_cell::sync::Lazy;
use quick_xml::events::Event;
use quick_xml::Reader;
use regex::Regex;
use std::collections::HashMap;
use std::fs;
use std::io::Read;
use std::path::Path;
use zip::ZipArchive;
use base64::{engine::general_purpose::STANDARD, Engine};

use crate::types::{EpubChapter, EpubParseResult};

/// 块级 HTML 标签 —— 遇到这些就换行
const BLOCK_TAGS: &[&str] = &[
    "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "li", "section",
    "article", "table", "tr", "hr", "address", "dd", "dt", "header", "footer", "nav",
    "aside", "ol", "ul",
];

/// 需要跳过内容的标签
const SKIP_TAGS: &[&str] = &["style", "script", "head"];

// 预编译正则
static REGEX_NL_3PLUS: Lazy<Regex> = Lazy::new(|| Regex::new(r"\n{3,}").unwrap());
static REGEX_SPACE_2PLUS: Lazy<Regex> = Lazy::new(|| Regex::new(r"[ \t]{2,}").unwrap());
static REGEX_NL_TRAIL_SPACE: Lazy<Regex> = Lazy::new(|| Regex::new(r"\n[ \t]+").unwrap());
static REGEX_TRAIL_SPACE_NL: Lazy<Regex> = Lazy::new(|| Regex::new(r"[ \t]+\n").unwrap());
static REGEX_FAKE_CHAPTER: Lazy<Regex> = Lazy::new(|| Regex::new(r"(?i)^chapter[\s_\-]*\d+$").unwrap());
static REGEX_LEADING_CHAP: Lazy<Regex> =
    Lazy::new(|| Regex::new(r"^(?i)(chapter|chap|ch|section|sec|part|lesson|unit|volume|vol|module)\s*").unwrap());
static REGEX_TRAILING_SEP: Lazy<Regex> = Lazy::new(|| Regex::new(r"[._\-–\s]+$").unwrap());
static REGEX_LEADING_SEP: Lazy<Regex> = Lazy::new(|| Regex::new(r"^[_\-–\s]+").unwrap());
static REGEX_TRAILING_SEP2: Lazy<Regex> = Lazy::new(|| Regex::new(r"[_\-–\s]+$").unwrap());
static REGEX_LEADING_ZERO: Lazy<Regex> = Lazy::new(|| Regex::new(r"^0+").unwrap());
static REGEX_H1: Lazy<Regex> = Lazy::new(|| Regex::new(r"(?is)<h1(?:[^>]*)?>(.*?)</h1\s*>").unwrap());
static REGEX_H2: Lazy<Regex> = Lazy::new(|| Regex::new(r"(?is)<h2(?:[^>]*)?>(.*?)</h2\s*>").unwrap());
static REGEX_H3: Lazy<Regex> = Lazy::new(|| Regex::new(r"(?is)<h3(?:[^>]*)?>(.*?)</h3\s*>").unwrap());
static REGEX_STRIP_TAGS: Lazy<Regex> = Lazy::new(|| Regex::new(r"<[^>]*>").unwrap());

/// 最大文件尺寸 (150MB) — 防止 OOM，支持大型学术/教材类 EPUB
const MAX_FILE_SIZE: u64 = 150 * 1024 * 1024;

/// 最大条目数量 — 防止 Zip Bomb
const MAX_EPUB_ENTRIES: usize = 10_000;

/// 最大章节内容大小 (500KB)
const MAX_CHAPTER_CONTENT: usize = 500_000;

/// 验证路径是否安全（不尝试逃离 ZIP 容器）
fn sanitize_zip_path(path: &str) -> Option<String> {
    // 阻止路径遍历攻击
    if path.contains("..") {
        return None;
    }
    // 只允许字母、数字、下划线、连字符、点、斜杠
    if path.chars().any(|c| !c.is_ascii_alphanumeric() && c != '.' && c != '_' && c != '-' && c != '/') {
        return None;
    }
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
    
    let mut archive = ZipArchive::new(file)
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
        let content = if raw_html.is_empty() {
            String::new()
        } else {
            clean_html(&raw_html)
        };

        // 限制每章最大内容大小 (MAX_CHAPTER_CONTENT)
        let content = if content.len() > MAX_CHAPTER_CONTENT {
            format!("{}\n\n……(篇幅受限)……", &content[..MAX_CHAPTER_CONTENT])
        } else {
            content
        };

        // 标题
        let mut title = resolve_title(href, &ncx_titles, i);

        // 从 HTML <h1>/<h2> 提取标题
        let raw_title = extract_title_from_raw(&raw_html);
        if let Some(ref rt) = raw_title {
            if !rt.is_empty() && *rt != title && rt.len() < 200 {
                let is_fallback = title.is_empty() || REGEX_FAKE_CHAPTER.is_match(&title);
                if is_fallback {
                    title = rt.clone();
                }
            }
        }

        if REGEX_FAKE_CHAPTER.is_match(&title) {
            continue;
        }

        if let Some(last) = chapters.last() {
            if last.content == content {
                continue;
            }
        }

        if title.is_empty() {
            title = extract_title_from_href(href, i);
        }

        chapters.push(EpubChapter {
            title,
            content,
            image_paths: Vec::new(),
            paragraph_types: Vec::new(),
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

// ==================== Container.xml 解析 ====================

fn parse_container(archive: &mut ZipArchive<fs::File>) -> Result<String, String> {
    let mut entry = archive
        .by_name("META-INF/container.xml")
        .map_err(|_| "找不到 META-INF/container.xml".to_string())?;
    let mut content = String::new();
    entry
        .read_to_string(&mut content)
        .map_err(|e| format!("读取 container.xml 失败: {}", e))?;

    let mut reader = Reader::from_str(&content);
    reader.config_mut().trim_text_start = true;
    reader.config_mut().trim_text_end = true;
    let mut buf = Vec::new();

    loop {
        match reader.read_event_into(&mut buf) {
            Ok(Event::Start(ref e)) | Ok(Event::Empty(ref e)) => {
                if e.name().as_ref().eq_ignore_ascii_case(b"rootfile") {
                    // 尝试各种属性名匹配
                    for attr in e.attributes().flatten() {
                        let attr_name = std::str::from_utf8(attr.key.as_ref()).unwrap_or("");
                        if attr_name.ends_with("full-path")
                            || attr_name.eq_ignore_ascii_case("full-path")
                        {
                            let path = std::str::from_utf8(&attr.value)
                                .map_err(|_| "full-path 属性不是有效 UTF-8".to_string())?;
                            return Ok(path.trim().to_string());
                        }
                    }
                }
            }
            Ok(Event::Eof) => break,
            Err(e) => return Err(format!("解析 container.xml 失败: {}", e)),
            _ => {}
        }
        buf.clear();
    }

    Err("container.xml 中未找到 rootfile 元素".to_string())
}

// ==================== OPF 解析 ====================

#[derive(Default)]
struct OpfResult {
    title: String,
    author: String,
    encoding: String,
    ncx_href: Option<String>,
    nav_xhtml_href: Option<String>,
}

fn parse_opf(
    archive: &mut ZipArchive<fs::File>,
    opf_path: &str,
) -> Result<(OpfResult, HashMap<String, String>, Vec<String>), String> {
    let mut entry = archive
        .by_name(opf_path)
        .map_err(|_| format!("找不到 OPF 文件: {}", opf_path))?;
    let mut content = String::new();
    entry
        .read_to_string(&mut content)
        .map_err(|e| format!("读取 OPF 失败: {}", e))?;

    let mut result = OpfResult::default();
    let mut manifest: HashMap<String, String> = HashMap::new();
    let mut spine_ids: Vec<String> = Vec::new();

    let mut reader = Reader::from_str(&content);
    reader.config_mut().trim_text_start = true;
    reader.config_mut().trim_text_end = true;
    let mut buf = Vec::new();

    let mut in_metadata = false;
    let mut in_manifest = false;
    let mut in_spine = false;
    let mut current_tag = String::new();

    loop {
        match reader.read_event_into(&mut buf) {
            Ok(Event::Start(ref e)) => {
                let tag_name = String::from_utf8_lossy(e.name().as_ref()).to_lowercase();
                current_tag = tag_name.clone();

                match tag_name.as_str() {
                    "metadata" => in_metadata = true,
                    "manifest" => in_manifest = true,
                    "spine" => in_spine = true,
                    "item" if in_manifest => {
                        let mut id = None;
                        let mut href = None;
                        let mut media_type = None;
                        let mut properties: Option<String> = None;
                        for attr in e.attributes().flatten() {
                            let name = std::str::from_utf8(attr.key.as_ref()).unwrap_or("");
                            let val = std::str::from_utf8(&attr.value).unwrap_or("");
                            match name.to_lowercase().as_str() {
                                "id" => id = Some(val.to_string()),
                                "href" => href = Some(val.to_string()),
                                "media-type" => media_type = Some(val.to_string()),
                                "properties" => properties = Some(val.to_string()),
                                _ => {}
                            }
                        }
                        if let (Some(id), Some(href)) = (id, href) {
                            manifest.insert(id, href.clone());
                            if let Some(mt) = media_type {
                                if mt.contains("dtbncx") || mt.contains("ncx") {
                                    if result.ncx_href.is_none() {
                                        result.ncx_href = Some(href.clone());
                                    }
                                }
                            }
                            if let Some(pr) = properties {
                                if pr.eq_ignore_ascii_case("nav") {
                                    if result.nav_xhtml_href.is_none() {
                                        result.nav_xhtml_href = Some(href.clone());
                                    }
                                }
                            }
                        }
                    }
                    "itemref" if in_spine => {
                        for attr in e.attributes().flatten() {
                            let name = std::str::from_utf8(attr.key.as_ref()).unwrap_or("");
                            if name.eq_ignore_ascii_case("idref") {
                                let idref =
                                    std::str::from_utf8(&attr.value).unwrap_or("").to_string();
                                spine_ids.push(idref);
                            }
                        }
                    }
                    _ => {}
                }
            }
            Ok(Event::End(ref e)) => {
                let tag_name = String::from_utf8_lossy(e.name().as_ref()).to_lowercase();
                match tag_name.as_str() {
                    "metadata" => in_metadata = false,
                    "manifest" => in_manifest = false,
                    "spine" => in_spine = false,
                    _ => {}
                }
                current_tag.clear();
            }
            Ok(Event::Text(ref e)) => {
                let text = e.unescape().unwrap_or_default().trim().to_string();
                if !text.is_empty() && in_metadata {
                    match current_tag.as_str() {
                        "title" => result.title = text,
                        "creator" => result.author = text,
                        _ => {}
                    }
                }
            }
            Ok(Event::Eof) => break,
            Err(e) => return Err(format!("解析 OPF 失败: {}", e)),
            _ => {}
        }
        buf.clear();
    }

    let spine_hrefs: Vec<String> = spine_ids
        .iter()
        .filter_map(|id| manifest.get(id))
        .cloned()
        .collect();

    Ok((result, manifest, spine_hrefs))
}

// ==================== NCX 解析 ====================

fn parse_ncx(
    archive: &mut ZipArchive<fs::File>,
    opf_dir: &str,
    opf_result: &OpfResult,
) -> HashMap<String, String> {
    let mut ncx_titles = HashMap::new();

    // 1. 尝试 EPUB2 NCX
    let ncx_href = match &opf_result.ncx_href {
        Some(h) => Some(h.clone()),
        None => {
            let mut found = None;
            for i in 0..archive.len() {
                if let Ok(entry) = archive.by_index(i) {
                    let name = entry.name().to_lowercase();
                    if name.ends_with(".ncx") {
                        found = Some(entry.name().to_string());
                        break;
                    }
                }
            }
            found
        }
    };

    if let Some(href) = ncx_href {
        let path = if href.starts_with(opf_dir.trim_end_matches('/'))
            || href.starts_with('/')
        {
            href.clone()
        } else {
            format!("{}{}", opf_dir, href)
        };

        if let Some(content) = read_zip_entry(archive, &path) {
            parse_ncx_xml(&content, &mut ncx_titles);
        } else if let Some(content) = read_zip_entry(archive, &href) {
            parse_ncx_xml(&content, &mut ncx_titles);
        }
    }

    // 2. EPUB3 兜底：从 nav.xhtml 的 <nav epub:type="toc"> 提取
    if ncx_titles.is_empty() {
        let mut candidates: Vec<String> = Vec::new();
        // 优先使用 manifest 中声明的 nav.xhtml（加上 opf_dir 前缀）
        if let Some(nav_href) = &opf_result.nav_xhtml_href {
            let full = if nav_href.starts_with(opf_dir.trim_end_matches('/'))
                || nav_href.starts_with('/')
            {
                nav_href.clone()
            } else {
                format!("{}{}", opf_dir, nav_href)
            };
            candidates.push(full);
        }
        candidates.push(format!("{}nav.xhtml", opf_dir));
        candidates.push("nav.xhtml".to_string());

        for candidate in &candidates {
            if let Some(content) = read_zip_entry(archive, candidate) {
                parse_epub3_nav(&content, &mut ncx_titles);
                if !ncx_titles.is_empty() {
                    break;
                }
            }
        }
        // 最后兜底：扫描 ZIP 找任意 nav/toc xhtml
        if ncx_titles.is_empty() {
            let mut nav_candidates: Vec<String> = Vec::new();
            for i in 0..archive.len() {
                if let Ok(entry) = archive.by_index(i) {
                    let name = entry.name().to_string();
                    let name_lower = name.to_lowercase();
                    if name_lower.ends_with(".xhtml") || name_lower.ends_with(".html") {
                        if name_lower.contains("nav") || name_lower.contains("toc") {
                            nav_candidates.push(name);
                        }
                    }
                }
            }
            for candidate in nav_candidates {
                if let Some(content) = read_zip_entry(archive, &candidate) {
                    parse_epub3_nav(&content, &mut ncx_titles);
                    if !ncx_titles.is_empty() {
                        break;
                    }
                }
            }
        }
    }

    ncx_titles
}

/// 从 NCX XML 文本中解析目录，支持多级嵌套 navpoint
fn parse_ncx_xml(content: &str, ncx_titles: &mut HashMap<String, String>) {
    let mut reader = Reader::from_str(content);
    reader.config_mut().trim_text_start = true;
    reader.config_mut().trim_text_end = true;
    let mut buf = Vec::new();

    // 栈跟踪每一层 navpoint 的 (src, label)
    let mut stack: Vec<(Option<String>, Option<String>)> = Vec::new();
    let mut in_navlabel = false;
    let mut in_text = false;

    loop {
        match reader.read_event_into(&mut buf) {
            Ok(Event::Start(ref e)) => {
                let tag = String::from_utf8_lossy(e.name().as_ref()).to_lowercase();
                match tag.as_str() {
                    "navpoint" => {
                        stack.push((None, None));
                    }
                    "content" => {
                        for attr in e.attributes().flatten() {
                            let name = std::str::from_utf8(attr.key.as_ref()).unwrap_or("");
                            if name.eq_ignore_ascii_case("src") {
                                let src = std::str::from_utf8(&attr.value)
                                    .unwrap_or("")
                                    .to_string();
                                    if let Some(top) = stack.last_mut() {
                                    top.0 = Some(src);
                                }
                            }
                        }
                    }
                    "navlabel" => {
                        in_navlabel = true;
                    }
                    "text" if in_navlabel => {
                        in_text = true;
                    }
                    _ => {}
                }
            }
            Ok(Event::Text(ref e)) => {
                if in_text {
                    if let Ok(t) = e.unescape() {
                        let s = t.trim().to_string();
                        if !s.is_empty() {
                            if let Some(top) = stack.last_mut() {
                                top.1 = Some(s);
                            }
                        }
                    }
                }
            }
            Ok(Event::End(ref e)) => {
                let tag = String::from_utf8_lossy(e.name().as_ref()).to_lowercase();
                match tag.as_str() {
                    "navlabel" => in_navlabel = false,
                    "text" => in_text = false,
                    "navpoint" => {
                        if let Some(top) = stack.pop() {
                            let depth = stack.len() + 1;
                            let src = top.0;
                            let label = top.1;
                            if depth == 1 {
                                commit_ncx_title(ncx_titles, src, label);
                            } else {
                                // 嵌套层：把 src/label 合并回父层（若父层为空）
                                if let Some(parent) = stack.last_mut() {
                                    if parent.0.is_none() {
                                        parent.0 = src;
                                    }
                                    if parent.1.is_none() {
                                        parent.1 = label;
                                    }
                                }
                            }
                        }
                    }
                    _ => {}
                }
            }
            Ok(Event::Eof) => break,
            Err(_) => break,
            _ => {}
        }
        buf.clear();
    }
}

/// 提交 NCX 标题条目：去锚点、去 ./ 前缀、trim 空白
fn commit_ncx_title(
    ncx_titles: &mut HashMap<String, String>,
    src: Option<String>,
    label: Option<String>,
) {
    let (Some(s), Some(l)) = (src, label) else { return };
    let mut href = s;
    if let Some(hash_idx) = href.find('#') {
        href = href[..hash_idx].to_string();
    }
    if let Some(stripped) = href.strip_prefix("./") {
        href = stripped.to_string();
    }
    let trimmed = l.trim().to_string();
    if !href.is_empty() && !trimmed.is_empty() && !ncx_titles.contains_key(&href) {
        ncx_titles.insert(href, trimmed);
    }
}

/// EPUB3 nav.xhtml 兜底解析：提取 <nav epub:type="toc"> <ol><li><a href="...">title</a>
fn parse_epub3_nav(content: &str, ncx_titles: &mut HashMap<String, String>) {
    let mut reader = Reader::from_str(content);
    reader.config_mut().trim_text_start = true;
    reader.config_mut().trim_text_end = true;
    let mut buf = Vec::new();

    // li 栈：存储每层 li 的 (href, label)
    let mut li_stack: Vec<(Option<String>, Option<String>)> = Vec::new();
    let mut current_href: Option<String> = None;
    let mut current_text = String::new();
    let mut in_toc_nav: i32 = 0;
    let mut nav_type: Option<String> = None;

    loop {
        match reader.read_event_into(&mut buf) {
            Ok(Event::Start(ref e)) => {
                let tag = String::from_utf8_lossy(e.name().as_ref()).to_lowercase();
                match tag.as_str() {
                    "nav" => {
                        // 支持多种 epub:type 命名空间
                        let mut t: Option<String> = None;
                        for attr in e.attributes().flatten() {
                            let name = std::str::from_utf8(attr.key.as_ref())
                                .unwrap_or("")
                                .to_lowercase();
                            if name == "type"
                                || name.ends_with("type")
                                || name == "epub:type"
                            {
                                let v = std::str::from_utf8(&attr.value)
                                    .unwrap_or("")
                                    .to_lowercase();
                                t = Some(v);
                                break;
                            }
                        }
                        nav_type = t;
                        if nav_type.as_deref() == Some("toc") {
                            in_toc_nav = 1;
                        }
                    }
                    "li" if in_toc_nav > 0 => {
                        li_stack.push((None, None));
                        current_href = None;
                        current_text.clear();
                    }
                    "a" if in_toc_nav > 0 => {
                        for attr in e.attributes().flatten() {
                            let name = std::str::from_utf8(attr.key.as_ref())
                                .unwrap_or("")
                                .to_lowercase();
                            if name == "href" {
                                let v = std::str::from_utf8(&attr.value)
                                    .unwrap_or("")
                                    .to_string();
                                current_href = Some(v.clone());
                                if let Some(top) = li_stack.last_mut() {
                                    top.0 = Some(v);
                                }
                                break;
                            }
                        }
                        current_text.clear();
                    }
                    _ => {}
                }
            }
            Ok(Event::Text(ref e)) => {
                if in_toc_nav > 0 {
                    if let Ok(t) = e.unescape() {
                        current_text.push_str(t.as_ref());
                    }
                }
            }
            Ok(Event::End(ref e)) => {
                let tag = String::from_utf8_lossy(e.name().as_ref()).to_lowercase();
                match tag.as_str() {
                    "nav" => {
                        if nav_type.as_deref() == Some("toc") {
                            in_toc_nav = 0;
                        }
                        nav_type = None;
                    }
                    "li" if in_toc_nav > 0 => {
                        if let Some(top) = li_stack.pop() {
                            let depth = li_stack.len() + 1;
                            let src = if top.0.is_some() { top.0 } else { current_href.clone() };
                            let label = current_text.trim().to_string();
                            if depth == 1 {
                                commit_ncx_title(ncx_titles, src, Some(label));
                            } else if let Some(parent) = li_stack.last_mut() {
                                if parent.0.is_none() {
                                    parent.0 = src;
                                }
                                if parent.1.is_none() && !label.is_empty() {
                                    parent.1 = Some(label);
                                }
                            }
                        }
                    }
                    "a" if in_toc_nav > 0 => {
                        if let Some(top) = li_stack.last_mut() {
                            top.1 = Some(current_text.trim().to_string());
                        }
                    }
                    _ => {}
                }
            }
            Ok(Event::Eof) => break,
            Err(_) => break,
            _ => {}
        }
        buf.clear();
    }
}

/// 从 ZIP 读取指定 entry 的完整文本
fn read_zip_entry(archive: &mut ZipArchive<fs::File>, path: &str) -> Option<String> {
    let mut entry = archive.by_name(path).ok()?;
    let mut buf = String::new();
    entry.read_to_string(&mut buf).ok()?;
    if buf.is_empty() { None } else { Some(buf) }
}

// ==================== HTML 内容获取 ====================

fn get_raw_html(archive: &mut ZipArchive<fs::File>, entry_path: &str, href: &str) -> String {
    // 尝试多种路径方式，逐个尝试（避免链式 or_else 导致借用冲突）
    let raw = try_get_entry(archive, entry_path);
    if !raw.is_empty() {
        return raw;
    }
    // URL 解码
    if let Ok(decoded) = urlencoding::decode(entry_path) {
        let raw = try_get_entry(archive, &decoded);
        if !raw.is_empty() {
            return raw;
        }
    }
    // 去掉 ./
    if let Some(stripped) = entry_path.strip_prefix("./") {
        let raw = try_get_entry(archive, stripped);
        if !raw.is_empty() {
            return raw;
        }
    }
    // 不区分大小写匹配文件名
    let filename = href.rsplit_once('/').map(|(_, f)| f).unwrap_or(href);
    let matched = {
        let mut found = None;
        for i in 0..archive.len() {
            if let Ok(entry) = archive.by_index(i) {
                let name = entry.name().to_string();
                let entry_file = name
                    .rsplit_once('/')
                    .map(|(_, f)| f)
                    .unwrap_or(&name)
                    .to_string();
                if entry_file.eq_ignore_ascii_case(filename) {
                    found = Some(name);
                    break;
                }
            }
        }
        found
    };
    if let Some(name) = matched {
        try_get_entry(archive, &name)
    } else {
        String::new()
    }
}

fn try_get_entry(archive: &mut ZipArchive<fs::File>, path: &str) -> String {
    if let Ok(mut entry) = archive.by_name(path) {
        let mut raw = String::new();
        if entry.read_to_string(&mut raw).is_ok() && !raw.is_empty() {
            return raw;
        }
    }
    String::new()
}

// ==================== 标题提取 ====================

fn extract_title_from_raw(html: &str) -> Option<String> {
    for regex in [&*REGEX_H1, &*REGEX_H2, &*REGEX_H3] {
        if let Some(cap) = regex.captures(html) {
            let candidate = REGEX_STRIP_TAGS.replace_all(&cap[1], "").trim().to_string();
            if !candidate.is_empty() && candidate.len() < 200 {
                return Some(candidate);
            }
        }
    }
    None
}

fn resolve_title(href: &str, ncx_titles: &HashMap<String, String>, _index: usize) -> String {
    // 1. 原样匹配
    if let Some(t) = ncx_titles.get(href) {
        return t.clone();
    }

    // 2. URL 解码
    if let Ok(decoded) = urlencoding::decode(href) {
        if let Some(t) = ncx_titles.get(decoded.as_ref()) {
            return t.clone();
        }
    }

    // 3. 去掉 ../ 或 ./
    let stripped = href
        .strip_prefix("../")
        .or_else(|| href.strip_prefix("./"))
        .unwrap_or(href);
    if stripped != href {
        if let Some(t) = ncx_titles.get(stripped) {
            return t.clone();
        }
        if let Ok(decoded) = urlencoding::decode(stripped) {
            if let Some(t) = ncx_titles.get(decoded.as_ref()) {
                return t.clone();
            }
        }
    }

    // 4. 只匹配文件名
    let filename = href.rsplit_once('/').map(|(_, f)| f).unwrap_or(href);
    if let Some(t) = ncx_titles.get(filename) {
        return t.clone();
    }
    if let Ok(decoded) = urlencoding::decode(filename) {
        if let Some(t) = ncx_titles.get(decoded.as_ref()) {
            return t.clone();
        }
    }

    // 5. 不区分大小写
    let filename_lower = filename.to_lowercase();
    for (key, val) in ncx_titles {
        let key_file = key.rsplit_once('/').map(|(_, f)| f).unwrap_or(key);
        if key_file.eq_ignore_ascii_case(&filename_lower) {
            return val.clone();
        }
    }

    String::new()
}

fn extract_title_from_href(href: &str, index: usize) -> String {
    let name = href
        .rsplit_once('/')
        .map(|(_, f)| f)
        .unwrap_or(href);
    let name = name
        .rsplit_once('.')
        .map(|(n, _)| n)
        .unwrap_or(&name);

    // 纯数字
    if let Ok(num) = name.parse::<usize>() {
        return format!("第{}章", num);
    }

    let lower = name.to_lowercase();
    let cleaned = REGEX_LEADING_CHAP.replace_all(&lower, "").to_string();
    let cleaned = REGEX_TRAILING_SEP.replace_all(&cleaned, "").to_string();
    let mut name = if !cleaned.is_empty() && cleaned != lower {
        if let Ok(num) = cleaned.parse::<usize>() {
            return format!("第{}章", num);
        }
        // 继续后续清理（与 Java 行为一致）
        cleaned
    } else {
        name.to_string()
    };

    name = REGEX_LEADING_SEP.replace_all(&name, "").to_string();
    name = REGEX_TRAILING_SEP2.replace_all(&name, "").to_string();
    name = REGEX_LEADING_ZERO.replace_all(&name, "").to_string();

    if !name.is_empty() {
        let lower = name.to_lowercase();
        match lower.as_str() {
            "prologue" | "foreword" | "preface" | "introduction" => return "序言".to_string(),
            "epilogue" | "afterword" | "postscript" => return "后记".to_string(),
            "appendix" | "reference" | "glossary" => return "附录".to_string(),
            _ => return name,
        }
    }

    format!("第{}章", index + 1)
}

// ==================== HTML 清理 ====================

fn clean_html(html: &str) -> String {
    if html.is_empty() {
        return String::new();
    }

    let mut out = String::with_capacity(html.len());
    let bytes = html.as_bytes();
    let len = bytes.len();
    let mut i = 0;

    while i < len {
        let c = bytes[i] as char;

        if c == '<' {
            // HTML 注释 <!-- ... -->
            if i + 3 < len
                && bytes[i + 1] as char == '!'
                && bytes[i + 2] as char == '-'
                && bytes[i + 3] as char == '-'
            {
                i += 4;
                let mut comment_max = 0usize;
                while i < len && comment_max < 10000 {
                    if i + 2 < len
                        && bytes[i] as char == '-'
                        && bytes[i + 1] as char == '-'
                        && bytes[i + 2] as char == '>'
                    {
                        i += 3;
                        break;
                    }
                    i += 1;
                    comment_max += 1;
                }
                continue;
            }

            // CDATA
            if i + 8 < len
                && bytes[i + 1] as char == '!'
                && bytes[i + 2] as char == '['
                && bytes[i + 3] as char == 'C'
                && bytes[i + 4] as char == 'D'
                && bytes[i + 5] as char == 'A'
                && bytes[i + 6] as char == 'T'
                && bytes[i + 7] as char == 'A'
                && bytes[i + 8] as char == '['
            {
                i += 9;
                while i + 2 < len {
                    if bytes[i] as char == ']'
                        && bytes[i + 1] as char == ']'
                        && bytes[i + 2] as char == '>'
                    {
                        i += 3;
                        break;
                    }
                    i += 1;
                }
                continue;
            }

            // 解析标签名
            i += 1;
            let tag_start = i;
            while i < len
                && bytes[i] as char != '>'
                && bytes[i] as char != ' '
                && bytes[i] as char != '\t'
                && bytes[i] as char != '\n'
                && bytes[i] as char != '/'
            {
                i += 1;
            }
            let tag_name_bytes = &bytes[tag_start..i];
            let tag_name = std::str::from_utf8(tag_name_bytes).unwrap_or("");

            let tag_lower = tag_name.to_lowercase();

            // 跳过 style/script/head
            if SKIP_TAGS.contains(&tag_lower.as_str()) {
                let close_tag = format!("</{}", tag_lower);
                while i < len {
                    if bytes[i] as char == '<' {
                        let mut match_tag = true;
                        for (k, cb) in close_tag.bytes().enumerate() {
                            if i + k >= len || (bytes[i + k] as char) != (cb as char) {
                                match_tag = false;
                                break;
                            }
                        }
                        if match_tag {
                            i += close_tag.len();
                            while i < len && bytes[i] as char != '>' {
                                i += 1;
                            }
                            if i < len {
                                i += 1;
                            }
                            break;
                        }
                    }
                    i += 1;
                }
                continue;
            }

            // 块级标签换行
            if BLOCK_TAGS.contains(&tag_lower.as_str()) {
                out.push('\n');
            } else if tag_lower == "br" {
                out.push('\n');
            }

            // 跳到 >
            while i < len && bytes[i] as char != '>' {
                i += 1;
            }
            if i < len {
                i += 1;
            }
            continue;
        }

        // HTML 实体解码
        if c == '&' {
            let semi = html[i..].find(';');
            if let Some(semi_offset) = semi {
                if semi_offset <= 12 {
                    let entity = &html[i + 1..i + semi_offset];
                    if let Some(decoded) = decode_entity(entity) {
                        out.push_str(&decoded);
                        i += semi_offset + 1;
                        continue;
                    }
                }
            }
            out.push(c);
            i += 1;
            continue;
        }

        // 普通字符
        if c != '\r' {
            out.push(c);
        }
        i += 1;
    }

    // 最终清理
    let mut result = REGEX_NL_3PLUS.replace_all(&out, "\n\n").to_string();
    result = REGEX_SPACE_2PLUS.replace_all(&result, " ").to_string();
    result = REGEX_NL_TRAIL_SPACE.replace_all(&result, "\n").to_string();
    result = REGEX_TRAIL_SPACE_NL.replace_all(&result, "\n").to_string();
    result.trim().to_string()
}

/// 解码 HTML 实体
fn decode_entity(entity: &str) -> Option<String> {
    if entity.is_empty() {
        return None;
    }

    if entity.starts_with('#') {
        let codepoint = if entity.as_bytes().get(1) == Some(&b'x')
            || entity.as_bytes().get(1) == Some(&b'X')
        {
            u32::from_str_radix(&entity[2..], 16).ok()
        } else {
            entity[1..].parse::<u32>().ok()
        };
        if let Some(cp) = codepoint {
            if cp > 0 && cp <= char::MAX as u32 {
                return char::from_u32(cp).map(|c| c.to_string());
            }
        }
        return None;
    }

    match entity {
        "amp" => Some("&".to_string()),
        "lt" => Some("<".to_string()),
        "gt" => Some(">".to_string()),
        "quot" => Some("\"".to_string()),
        "apos" => Some("'".to_string()),
        "nbsp" => Some(" ".to_string()),
        "mdash" => Some("\u{2014}".to_string()),
        "ndash" => Some("\u{2013}".to_string()),
        "hellip" => Some("\u{2026}".to_string()),
        "ldquo" => Some("\u{201C}".to_string()),
        "rdquo" => Some("\u{201D}".to_string()),
        "lsquo" => Some("\u{2018}".to_string()),
        "rsquo" => Some("\u{2019}".to_string()),
        "laquo" => Some("\u{00AB}".to_string()),
        "raquo" => Some("\u{00BB}".to_string()),
        "copy" => Some("\u{00A9}".to_string()),
        "reg" => Some("\u{00AE}".to_string()),
        "trade" => Some("\u{2122}".to_string()),
        "emsp" => Some("\u{2003}".to_string()),
        "ensp" => Some("\u{2002}".to_string()),
        "thinsp" => Some("\u{2009}".to_string()),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_decode_entity() {
        assert_eq!(decode_entity("amp"), Some("&".to_string()));
        assert_eq!(decode_entity("lt"), Some("<".to_string()));
        assert_eq!(decode_entity("nbsp"), Some(" ".to_string()));
        assert_eq!(decode_entity("mdash"), Some("\u{2014}".to_string()));
        assert_eq!(decode_entity("copy"), Some("\u{00A9}".to_string()));
        assert_eq!(decode_entity("#65"), Some("A".to_string()));
        assert_eq!(decode_entity("#x41"), Some("A".to_string()));
    }

    #[test]
    fn test_clean_html_simple() {
        let html = "<p>Hello <b>World</b></p>";
        let cleaned = clean_html(html);
        assert_eq!(cleaned, "Hello World");
    }

    #[test]
    fn test_clean_html_with_br() {
        let html = "Line1<br>Line2<br/>Line3";
        let cleaned = clean_html(html);
        assert!(cleaned.contains("Line1"));
        assert!(cleaned.contains("Line2"));
        assert!(cleaned.contains("Line3"));
    }

    #[test]
    fn test_clean_html_entities() {
        let html = "<p>Tom &amp; Jerry &lt;3</p>";
        let cleaned = clean_html(html);
        assert_eq!(cleaned, "Tom & Jerry <3");
    }

    #[test]
    fn test_clean_html_comment() {
        let html = "<p>Hello<!-- comment -->World</p>";
        let cleaned = clean_html(html);
        assert_eq!(cleaned, "HelloWorld");
    }

    #[test]
    fn test_extract_title_from_html() {
        let html =
            "<html><head><title>Test</title></head><body><h1>Chapter One</h1><p>text</p></body></html>";
        let title = extract_title_from_raw(html);
        assert_eq!(title, Some("Chapter One".to_string()));
    }

    #[test]
    fn test_extract_title_from_href() {
        assert_eq!(extract_title_from_href("ch01.xhtml", 0), "第1章");
        assert_eq!(extract_title_from_href("001.xhtml", 0), "第1章");
        assert_eq!(extract_title_from_href("chapter_1.xhtml", 0), "1");
    }

    #[test]
    fn test_resolve_title() {
        let mut map = HashMap::new();
        map.insert("ch01.xhtml".to_string(), "第一章 开始".to_string());
        assert_eq!(resolve_title("ch01.xhtml", &map, 0), "第一章 开始");
        assert_eq!(resolve_title("unknown.xhtml", &map, 0), "");
    }

    #[test]
    fn test_clean_html_skip_style_script() {
        let html =
            "<html><style>.a{}</style><p>Text</p><script>alert(1)</script><p>More</p></html>";
        let cleaned = clean_html(html);
        assert!(!cleaned.contains("alert"));
        assert!(cleaned.contains("Text"));
        assert!(cleaned.contains("More"));
    }

    #[test]
    fn test_clean_html_cdata() {
        let html = "<p>Text<![CDATA[some data]]></p>";
        let cleaned = clean_html(html);
        assert_eq!(cleaned, "Text");
    }

    #[test]
    fn test_clean_html_block_tags() {
        let html = "<div>Line1</div><div>Line2</div>";
        let cleaned = clean_html(html);
        assert!(cleaned.contains('\n'));
    }
}
