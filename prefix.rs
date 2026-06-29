//! EPUB 文件解析器
//!
//! EPUB 本质是一个 ZIP 包，包含：
//!   - META-INF/container.xml  → 找到 OPF 文件路径
//!   - *.opf                  → 书籍元数据 + 文件清单(manifest) + 阅读顺序(spine)
//!   - *.ncx                  → 目录结构（章节标题映射）
//!   - *.xhtml / *.html       → 正文内容
//!
//! 本实现提供最小可用版本：提取 title/author、按 spine 读取 XHTML、清理 HTML 标签、NCX 标题匹配。

use quick_xml::events::Event;
use quick_xml::Reader;
use regex::Regex;
use std::collections::HashMap;
use std::fs;
use std::io::Read;
use std::path::Path;
use zip::ZipArchive;

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
lazy_static::lazy_static! {
    static ref REGEX_NL_3PLUS: Regex = Regex::new(r"\n{3,}").unwrap();
    static ref REGEX_SPACE_2PLUS: Regex = Regex::new(r"[ \t]{2,}").unwrap();
    static ref REGEX_NL_TRAIL_SPACE: Regex = Regex::new(r"\n[ \t]+").unwrap();
    static ref REGEX_TRAIL_SPACE_NL: Regex = Regex::new(r"[ \t]+\n").unwrap();
    static ref REGEX_FAKE_CHAPTER: Regex = Regex::new(r"(?i)^chapter[\s_\-]*\d+$").unwrap();
    static ref REGEX_LEADING_CHAP: Regex =
        Regex::new(r"^(?i)(chapter|chap|ch|section|sec|part|lesson|unit|volume|vol|module)\s*")
            .unwrap();
    static ref REGEX_TRAILING_SEP: Regex = Regex::new(r"[._\-–\s]+$").unwrap();
    static ref REGEX_LEADING_SEP: Regex = Regex::new(r"^[_\-–\s]+").unwrap();
    static ref REGEX_TRAILING_SEP2: Regex = Regex::new(r"[_\-–\s]+$").unwrap();
    static ref REGEX_LEADING_ZERO: Regex = Regex::new(r"^0+").unwrap();
    static ref REGEX_H1: Regex =
        Regex::new(r"(?is)<h1(?:[^>]*)?>(.*?)</h1\s*>").unwrap();
    static ref REGEX_H2: Regex =
        Regex::new(r"(?is)<h2(?:[^>]*)?>(.*?)</h2\s*>").unwrap();
    static ref REGEX_H3: Regex =
        Regex::new(r"(?is)<h3(?:[^>]*)?>(.*?)</h3\s*>").unwrap();
    static ref REGEX_STRIP_TAGS: Regex = Regex::new(r"<[^>]*>").unwrap();
}

/// 解析 EPUB 文件，返回 EpubParseResult
pub fn parse_epub(file_path: &str) -> Result<EpubParseResult, String> {
    let file = fs::File::open(file_path)
        .map_err(|e| format!("打开 EPUB 文件失败: {}", e))?;
    let mut archive = ZipArchive::new(file)
        .map_err(|e| format!("读取 ZIP 失败: {}", e))?;

    // 1. container.xml → OPF 路径
    let opf_path = parse_container(&mut archive)?;
    let opf_dir = opf_path
        .rsplit_once('/')
        .map(|(d, _)| format!("{}/", d))
        .unwrap_or_default();

    // 2. OPF → 元数据 + manifest + spine
    let (opf_result, _manifest, spine_hrefs) = parse_opf(&mut archive, &opf_path)?;

    // 3. NCX → 标题映射
    let ncx_titles = parse_ncx(&mut archive, &opf_dir, &opf_result);

    // 4. 逐章解析 spine
    let mut chapters: Vec<EpubChapter> = Vec::new();

    for (i, href) in spine_hrefs.iter().enumerate() {
        let entry_path = format!("{}{}", opf_dir, href);

        let raw_html = get_raw_html(&mut archive, &entry_path, href);
        let content = if raw_html.is_empty() {
            String::new()
        } else {
            clean_html(&raw_html)
        };

        // 限制每章最大 500KB
        let content = if content.len() > 500_000 {
            format!("{}\n\n……(篇幅受限)……", &content[..500_000])
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
        images: HashMap::new(),
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
                        for attr in e.attributes().flatten() {
                            let name = std::str::from_utf8(attr.key.as_ref()).unwrap_or("");
                            let val = std::str::from_utf8(&attr.value).unwrap_or("");
                            match name.to_lowercase().as_str() {
                                "id" => id = Some(val.to_string()),
                                "href" => href = Some(val.to_string()),
                                "media-type" => media_type = Some(val.to_string()),
                                _ => {}
                            }
                        }
                        if let (Some(id), Some(href)) = (id, href) {
                            manifest.insert(id, href.clone());
                            if let Some(mt) = media_type {
                                if mt.contains("dtbncx") || mt.contains("ncx") {
                                    if result.ncx_href.is_none() {
                                        result.ncx_href = Some(href);
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

