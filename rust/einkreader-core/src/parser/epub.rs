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

fn parse_ncx(
    archive: &mut ZipArchive<fs::File>,
    opf_dir: &str,
    opf_result: &OpfResult,
) -> HashMap<String, String> {
    let mut nav_point_depth: usize = 0;
    let mut ncx_titles = HashMap::new();

    let ncx_href = match &opf_result.ncx_href {
        Some(h) => h.clone(),
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
            match found {
                Some(h) => h,
                None => return ncx_titles,
            }
        }
    };

    let ncx_path = if ncx_href.starts_with(opf_dir.trim_end_matches('/'))
        || ncx_href.starts_with('/')
    {
        ncx_href.clone()
    } else {
        format!("{}{}", opf_dir, ncx_href)
    };

    let mut content = String::new();
    {
        // 先尝试完整路径，再试原始 href（避免嵌套 match 导致借用冲突）
        let mut found = None;
        if let Ok(mut e) = archive.by_name(&ncx_path) {
            let mut buf = String::new();
            if e.read_to_string(&mut buf).is_ok() && !buf.is_empty() {
                found = Some(buf);
            }
        }
        if found.is_none() {
            if let Ok(mut e) = archive.by_name(&ncx_href) {
                let mut buf = String::new();
                if e.read_to_string(&mut buf).is_ok() && !buf.is_empty() {
                    found = Some(buf);
                }
            }
        }
        match found {
            Some(s) => content = s,
            None => return ncx_titles,
        }
    }

    let mut reader = Reader::from_str(&content);
    reader.config_mut().trim_text_start = true;
    reader.config_mut().trim_text_end = true;
    let mut buf = Vec::new();

    let mut current_src: Option<String> = None;
    let mut current_label: Option<String> = None;
    let mut in_navlabel = false;
    let mut in_text = false;

    loop {
        match reader.read_event_into(&mut buf) {
            Ok(Event::Start(ref e)) => {
                let tag = String::from_utf8_lossy(e.name().as_ref()).to_lowercase();
                match tag.as_str() {
                    "navpoint" => {
                        if nav_point_depth == 0 { nav_point_depth = 1; } else { nav_point_depth += 1; }
                    }
                    "content" => {
                        if nav_point_depth == 1 {
                            for attr in e.attributes().flatten() {
                                let name = std::str::from_utf8(attr.key.as_ref()).unwrap_or("");
                                if name.eq_ignore_ascii_case("src") {
                                    let src = std::str::from_utf8(&attr.value).unwrap_or("");
                                    current_src = Some(src.to_string());
                                }
                            }
                        }
                    }
                    "navlabel" => {
                        if nav_point_depth == 1 {
                            in_navlabel = true;
                            current_label = None;
                        }
                    }
                    "text" if in_navlabel => {
                        if nav_point_depth == 1 {
                            in_text = true;
                        }
                    }
                    _ => {}
                }
            }
            Ok(Event::Text(ref e)) => {
                if in_text {
                    current_label = Some(e.unescape().unwrap_or_default().to_string());
                }
            }
            Ok(Event::End(ref e)) => {
                let tag = String::from_utf8_lossy(e.name().as_ref()).to_lowercase();
                match tag.as_str() {
                    "navpoint" => {
                        if nav_point_depth > 0 { nav_point_depth -= 1; }
                        if let (Some(src), Some(label)) = (&current_src, &current_label) {
                            let href = if let Some(hash_idx) = src.find('#') {
                                src[..hash_idx].to_string()
                            } else {
                                src.clone()
                            };
                            ncx_titles.insert(href, label.trim().to_string());
                        }
                        current_src = None;
                        current_label = None;
                    }
                    "navlabel" => in_navlabel = false,
                    "text" => in_text = false,
                    _ => {}
                }
            }
            Ok(Event::Eof) => break,
            Err(_) => break,
            _ => {}
        }
        buf.clear();
    }

    ncx_titles
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
