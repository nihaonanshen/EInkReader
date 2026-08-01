//! 目录解析：EPUB2 NCX + EPUB3 nav.xhtml

use std::collections::HashMap;
use std::fs;
use quick_xml::events::Event;
use quick_xml::Reader;
use zip::ZipArchive;

use super::opf::OpfResult;
use super::zip_utils::read_zip_entry;

/// 解析目录：优先 EPUB2 NCX，缺失时用 EPUB3 nav.xhtml 兜底
/// 返回 href → 标题 的映射表
pub(super) fn parse_ncx(
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

    loop {
        match reader.read_event_into(&mut buf) {
            Ok(Event::Start(ref e)) => {
                let tag = String::from_utf8_lossy(e.name().as_ref()).to_lowercase();
                match tag.as_str() {
                    "navpoint" => {
                        stack.push((None, None));
                    }
                    "navlabel" => {
                        in_navlabel = true;
                    }
                    _ => {}
                }
            }
            Ok(Event::Empty(ref e)) => {
                let tag = String::from_utf8_lossy(e.name().as_ref()).to_lowercase();
                if tag == "content" {
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
            }
            Ok(Event::Text(ref e)) => {
                if in_navlabel {
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
                    "navpoint" => {
                        if let Some(top) = stack.pop() {
                            let src = top.0;
                            let label = top.1;

                            // ✅ 核心修复：无论哪一层都添加到目录映射表
                            // 使用 clone() 以保留源值用于后续父节点引用
                            if let (Some(src_val), Some(label_val)) = (&src, &label) {
                                commit_ncx_title(ncx_titles, Some(src_val.clone()), Some(label_val.clone()));
                            }

                            // 保留父节点引用（用于构建层次结构，可选功能）
                            if let Some(parent) = stack.last_mut() {
                                // 仅当父节点尚未设置 src/label 时才填充（避免覆盖）
                                if parent.0.is_none() {
                                    if let Some(s) = &src {
                                        parent.0 = Some(s.clone());
                                    }
                                }
                                if parent.1.is_none() {
                                    if let Some(l) = &label {
                                        parent.1 = Some(l.clone());
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

/// 提交 NCX 标题条目：去锚点、路径标准化（处理 ./ ../ \）、trim 空白
fn commit_ncx_title(
    ncx_titles: &mut HashMap<String, String>,
    src: Option<String>,
    label: Option<String>,
) {
    let (Some(s), Some(l)) = (src, label) else { return };
    let mut href = s;

    // 去掉 # 锚点
    if let Some(hash_idx) = href.find('#') {
        href = href[..hash_idx].to_string();
    }

    // ✅ 新增：标准化 Windows 反向斜杠为正向（兼容某些 EPUB 生成的路径）
    href = href.replace("\\", "/");

    // ✅ 规范化：递归去掉 ./ 和 ../ 前缀
    loop {
        let mut changed = false;
        if href.starts_with("./") {
            href = href[2..].to_string();
            changed = true;
        } else if href.starts_with("../") {
            // 查找最后一个 / 的位置，去掉上一级目录
            if let Some(last_slash) = href.rfind('/') {
                href = href[last_slash + 1..].to_string();
            } else {
                // 没有 /，直接去掉 ../
                href = href[3..].to_string();
            }
            changed = true;
        }
        if !changed { break; }
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
                        // ✅ 新增：支持多种 epub:type 命名空间和 TOC 标识值
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
                                    .unwrap_or_default()
                                    .to_lowercase();
                                // 同时支持 "toc"、"contents"、"index" 等 TOC 相关标识
                                if v.contains("toc") || v.contains("contents") || v.contains("index") {
                                    t = Some(v);
                                    break;
                                }
                            }
                        }
                        nav_type = t;
                        // ✅ 增强：只要包含 toc/contents/index 就认为是 TOC 导航
                        if nav_type.as_ref().map(|s| s.contains("toc")) == Some(true) {
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
                        // ✅ 增强：对应前面的判断逻辑
                        if nav_type.as_ref().map(|s| s.contains("toc")) == Some(true) {
                            in_toc_nav = 0;
                        }
                        nav_type = None;
                    }
                    "li" if in_toc_nav > 0 => {
                        if let Some(top) = li_stack.pop() {
                            // 获取 label（需要 clone，因为后面还会用到）
                            let label = current_text.trim().to_string();

                            // ✅ 核心修复：所有层级的 navpoint 都添加到目录映射表（不再只看 depth==1）
                            // 使用 clone() 确保值不会被移动后再次使用
                            if let Some(href_val) = &top.0 {
                                commit_ncx_title(ncx_titles, Some(href_val.clone()), Some(label.clone()));
                            } else if let Some(href_val) = &current_href {
                                commit_ncx_title(ncx_titles, Some(href_val.clone()), Some(label.clone()));
                            }

                            // 保留父节点引用（用于构建层次结构，可选功能）
                            if let Some(parent) = li_stack.last_mut() {
                                // 仅当父节点尚未设置时才填充（避免覆盖）
                                if parent.0.is_none() {
                                    // 拷贝 top.0 的值（如果需要的话）
                                    if let Some(val) = &top.0 {
                                        parent.0 = Some(val.clone());
                                    }
                                }
                                if parent.1.is_none() && !label.is_empty() {
                                    // label 是 String 类型，直接 clone 赋值
                                    parent.1 = Some(label.clone());
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
