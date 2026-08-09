//! 目录解析：EPUB2 NCX + EPUB3 nav.xhtml
//!
//! 返回树形目录结构（TocItem 链表），同时构建扁平 href→title 映射用于章节标题匹配。
//!
//! 改进：
//! - 大小写不敏感的 NCX 标题映射（Ferrous 风格）
//! - URL 解码支持
//! - 路径规范化

use std::collections::HashMap;
use std::fs;
use quick_xml::events::Event;
use quick_xml::Reader;
use zip::ZipArchive;

use super::opf::OpfResult;
use super::title::is_placeholder_title;
use super::zip_utils::read_zip_entry;
use crate::types::TocItem;

/// 标准化路径：统一为正斜杠、去除多余分隔符
fn normalize_path(path: &str) -> String {
    let normalized = path.replace('\\', "/");
    let mut parts: Vec<&str> = Vec::new();
    for seg in normalized.split('/') {
        if seg.is_empty() || seg == "." {
            continue;
        } else if seg == ".." {
            parts.pop();
        } else {
            parts.push(seg);
        }
    }
    parts.join("/")
}

/// 章节标题映射：href → 标题。
///
/// 同时维护「小写文件名 → 标题」索引，避免长书（数千章）逐章线性遍历整个目录表
/// 导致 O(n²) 匹配。包含匹配（路径前缀不一致的兜底）仅在所有精确索引未命中时执行。
#[derive(Default)]
pub(super) struct NcxTitles {
    /// href → title（含原路径 + 小写变体）
    by_href: HashMap<String, String>,
    /// 文件名（小写）→ title
    by_filename: HashMap<String, String>,
}

impl NcxTitles {
    pub(super) fn is_empty(&self) -> bool {
        self.by_href.is_empty()
    }

    /// 插入条目：原路径 + 小写变体 + 小写文件名索引
    pub(super) fn insert(&mut self, href: String, title: String) {
        if href.is_empty() {
            return;
        }
        let lower = href.to_lowercase();
        self.by_href.insert(href.clone(), title.clone());
        if lower != href && !self.by_href.contains_key(&lower) {
            self.by_href.insert(lower, title.clone());
        }
        if let Some(fname) = href.rsplit_once('/').map(|(_, f)| f) {
            let fl = fname.to_lowercase();
            if !self.by_filename.contains_key(&fl) {
                self.by_filename.insert(fl, title);
            }
        }
    }
}

/// 大小写不敏感地查找 NCX 标题映射（O(1) 索引优先，包含匹配兜底）
pub(super) fn find_ncx_title<'a>(ncx_titles: &'a NcxTitles, href: &str) -> Option<&'a String> {
    // 1. 原样匹配
    if let Some(t) = ncx_titles.by_href.get(href) {
        return Some(t);
    }

    // 2. URL 解码后匹配
    if let Ok(decoded) = urlencoding::decode(href) {
        let decoded_str = decoded.as_ref();
        if let Some(t) = ncx_titles.by_href.get(decoded_str) {
            return Some(t);
        }
    }

    // 3. 大小写不敏感匹配
    let href_lower = href.to_lowercase();
    if let Some(t) = ncx_titles.by_href.get(&href_lower) {
        return Some(t);
    }

    // 4. 只匹配文件名（小写）——O(1) 索引
    let filename = href.rsplit_once('/').map(|(_, f)| f).unwrap_or(href);
    if let Some(t) = ncx_titles.by_filename.get(&filename.to_lowercase()) {
        return Some(t);
    }

    // 5. 包含匹配（解决路径前缀不一致问题）——仅精确索引未命中时的最后兜底
    for (key, val) in &ncx_titles.by_href {
        if key.contains(filename) || href.contains(key) {
            return Some(val);
        }
    }

    None
}

/// 解析目录：优先 EPUB2 NCX，缺失时用 EPUB3 nav.xhtml 兜底，再兜底扫描内嵌 HTML 目录页。
/// 返回 (目录树, href→title 扁平映射, 目录来源文件列表[相对 OPF 目录]).
///
/// `toc_sources` 用于 mod.rs 把目录页（如 toc.html / nav.xhtml）从正文章节中剔除，
/// 避免目录页本身被当作阅读章节显示。
pub(super) fn parse_ncx(
    archive: &mut ZipArchive<fs::File>,
    opf_dir: &str,
    opf_result: &OpfResult,
) -> (Vec<TocItem>, NcxTitles, Vec<String>) {
    let mut ncx_titles = NcxTitles::default();
    let mut toc_items: Vec<TocItem> = Vec::new();
    // 记录：成功识别出目录内容的来源文件（相对 OPF 目录）
    let mut toc_sources: Vec<String> = Vec::new();
    let mut found_source: Option<String> = None;

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
            parse_ncx_xml(&content, &mut ncx_titles, &mut toc_items);
        } else if let Some(content) = read_zip_entry(archive, &href) {
            parse_ncx_xml(&content, &mut ncx_titles, &mut toc_items);
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
                parse_epub3_nav(&content, &mut ncx_titles, &mut toc_items);
                if !ncx_titles.is_empty() {
                    found_source = Some(candidate.clone());
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
                        // EasyPub 等生成的目录页常命名为 toc / index / contents / TableOfContents
                        if name_lower.contains("nav")
                            || name_lower.contains("toc")
                            || name_lower.contains("index")
                            || name_lower.contains("content")
                            || name_lower.contains("contents")
                            || name_lower.contains("目录")
                        {
                            nav_candidates.push(name);
                        }
                    }
                }
            }
            for candidate in nav_candidates {
                if let Some(content) = read_zip_entry(archive, &candidate) {
                    // 1) EPUB3 <nav epub:type="toc"> 结构
                    parse_epub3_nav(&content, &mut ncx_titles, &mut toc_items);
                    if !ncx_titles.is_empty() {
                        found_source = Some(candidate.clone());
                        break;
                    }
                    // 2) EasyPub 内嵌 HTML 目录页：<div class="toc"><dt class="tocl2"><a href="...">标题</a>
                    parse_html_toc(&content, &candidate, opf_dir, &mut ncx_titles, &mut toc_items);
                    if !ncx_titles.is_empty() {
                        found_source = Some(candidate.clone());
                        break;
                    }
                }
            }
        }
        // 3. 内容特征兜底：不靠文件名，扫描所有 html/xhtml 内容
        //    只要某页含 class="toc" 之类的目录容器就尝试解析（如目录页叫 0001.html）
        if ncx_titles.is_empty() {
            // 先收集所有 html/xhtml 文件名，避免 by_index 借用冲突
            let mut html_files: Vec<String> = Vec::new();
            for i in 0..archive.len() {
                if let Ok(entry) = archive.by_index(i) {
                    let name = entry.name().to_string();
                    let name_lower = name.to_lowercase();
                    if name_lower.ends_with(".xhtml") || name_lower.ends_with(".html") {
                        html_files.push(name);
                    }
                }
            }
            for name in html_files {
                if let Some(content) = read_zip_entry(archive, &name) {
                    if looks_like_toc_page(&content) {
                        parse_html_toc(&content, &name, opf_dir, &mut ncx_titles, &mut toc_items);
                        if !ncx_titles.is_empty() {
                            found_source = Some(name);
                            break;
                        }
                    }
                }
            }
        }
    }

    // 记录目录来源：转为相对 OPF 目录的路径（供章节剔除比较）
    if let Some(src) = found_source {
        let full = normalize_path(&src);
        let opf_prefix = opf_dir.trim_end_matches('/');
        let rel = if opf_prefix.is_empty() {
            full
        } else if full == opf_prefix {
            String::new()
        } else if let Some(rest) = full.strip_prefix(&format!("{}/", opf_prefix)) {
            rest.to_string()
        } else {
            full
        };
        if !rel.is_empty() {
            toc_sources.push(rel);
        }
    }

    (toc_items, ncx_titles, toc_sources)
}

/// 内容特征快速预筛：页面是否像目录页（含 class="...toc..." 容器）
fn looks_like_toc_page(content: &str) -> bool {
    // 只检查前 64KB，避免大文件全量小写转换
    let head = &content[..content.len().min(64 * 1024)];
    let lower = head.to_lowercase();
    lower.contains("class") && (lower.contains("toc") || lower.contains("目录"))
}

/// 从 NCX XML 文本中解析目录，支持多级嵌套 navpoint（真正构建树形结构）
fn parse_ncx_xml(content: &str, ncx_titles: &mut NcxTitles, toc_items: &mut Vec<TocItem>) {
    let mut reader = Reader::from_str(content);
    reader.config_mut().trim_text_start = true;
    reader.config_mut().trim_text_end = true;
    let mut buf = Vec::new();

    // 节点栈：navpoint 嵌套层级即目录树层级。每个 navpoint push 一个待填充骨架，
    // End 时 pop 并挂到父节点 children（或 roots）
    let mut stack: Vec<TocItem> = Vec::new();
    let mut in_navlabel = false;

    loop {
        match reader.read_event_into(&mut buf) {
            Ok(Event::Start(ref e)) => {
                let tag = String::from_utf8_lossy(e.name().as_ref()).to_lowercase();
                match tag.as_str() {
                    "navpoint" => {
                        stack.push(TocItem { title: String::new(), href: String::new(), children: Vec::new() });
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
                                if top.href.is_empty() {
                                    top.href = src;
                                }
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
                                if top.title.is_empty() {
                                    top.title = s;
                                } else {
                                    // 多个文本段拼接（罕见）
                                    top.title.push_str(s.as_str());
                                }
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
                        if let Some(node) = stack.pop() {
                            attach_toc_node(ncx_titles, toc_items, &mut stack, node);
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

/// 把单个目录节点挂到树：标题非空非占位才入库并挂接；
/// 占位标题（如 "Chapter 1"）作为父容器时丢弃自身、将其 children 上提到父级。
fn attach_toc_node(
    ncx_titles: &mut NcxTitles,
    roots: &mut Vec<TocItem>,
    stack: &mut Vec<TocItem>,
    mut node: TocItem,
) {
    let trimmed = node.title.trim().to_string();
    let href = normalize_ncx_href(&node.href);
    let is_valid = !trimmed.is_empty() && !href.is_empty() && !is_placeholder_title(&trimmed);

    if is_valid {
        node.title = trimmed.clone();
        node.href = href.clone();
        ncx_titles.insert(href, trimmed);
        if let Some(parent) = stack.last_mut() {
            parent.children.push(node);
        } else {
            roots.push(node);
        }
    } else if !node.children.is_empty() {
        // 占位/无效父节点：把 children 上提到父级（或 roots）
        if let Some(parent) = stack.last_mut() {
            parent.children.append(&mut node.children);
        } else {
            roots.append(&mut node.children);
        }
    }
    // 完全无效且无子节点：直接丢弃
}

/// 提交 NCX 标题条目：去锚点、路径标准化、跳过占位符标题。
/// 仅测试使用（生产路径由 attach_toc_node 直接构建树并入库）。
#[cfg(test)]
fn commit_ncx_title(
    ncx_titles: &mut NcxTitles,
    toc_items: &mut Vec<TocItem>,
    src: Option<String>,
    label: Option<String>,
) {
    let (Some(s), Some(l)) = (src, label) else { return };
    let href = normalize_ncx_href(&s);
    let trimmed = l.trim().to_string();
    // 跳过占位符标题（如 "Chapter 1"）
    if !href.is_empty() && !trimmed.is_empty() && !is_placeholder_title(&trimmed) {
        ncx_titles.insert(href.clone(), trimmed.clone());
        toc_items.push(TocItem {
            title: trimmed.clone(),
            href: href.clone(),
            children: Vec::new(),
        });
    }
}

/// 规范化目录 href：去锚点、反斜杠转正斜杠、统一用 normalize_path 解析 ./ 与 ../
fn normalize_ncx_href(src: &str) -> String {
    let mut href = src.to_string();
    // 去掉 # 锚点
    if let Some(hash_idx) = href.find('#') {
        href = href[..hash_idx].to_string();
    }
    href = normalize_path(&href.replace('\\', "/"));
    href
}

/// EPUB3 nav.xhtml 兜底解析：提取 <nav epub:type="toc"> <ol><li><a href="...">title</a>
/// li 嵌套层级即目录树层级（构建真正的多级树）
fn parse_epub3_nav(content: &str, ncx_titles: &mut NcxTitles, toc_items: &mut Vec<TocItem>) {
    let mut reader = Reader::from_str(content);
    reader.config_mut().trim_text_start = true;
    reader.config_mut().trim_text_end = true;
    let mut buf = Vec::new();

    // li 节点栈：存储每层 li 的待填充节点
    let mut li_stack: Vec<TocItem> = Vec::new();
    let mut current_text = String::new();
    let mut in_toc_nav: i32 = 0;
    let mut nav_type: Option<String> = None;

    loop {
        match reader.read_event_into(&mut buf) {
            Ok(Event::Start(ref e)) => {
                let tag = String::from_utf8_lossy(e.name().as_ref()).to_lowercase();
                match tag.as_str() {
                    "nav" => {
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
                                if v.contains("toc") || v.contains("contents") || v.contains("index") {
                                    t = Some(v);
                                    break;
                                }
                            }
                        }
                        nav_type = t;
                        if nav_type.as_ref().map(|s| s.contains("toc")) == Some(true) {
                            in_toc_nav = 1;
                        }
                    }
                    "li" if in_toc_nav > 0 => {
                        li_stack.push(TocItem { title: String::new(), href: String::new(), children: Vec::new() });
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
                                if let Some(top) = li_stack.last_mut() {
                                    if top.href.is_empty() {
                                        top.href = v;
                                    }
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
                        if nav_type.as_ref().map(|s| s.contains("toc")) == Some(true) {
                            in_toc_nav = 0;
                        }
                        nav_type = None;
                    }
                    "li" if in_toc_nav > 0 => {
                        let label = current_text.trim().to_string();
                        if let Some(mut node) = li_stack.pop() {
                            if node.title.is_empty() {
                                node.title = label.clone();
                            }
                            attach_toc_node(ncx_titles, toc_items, &mut li_stack, node);
                        }
                    }
                    "a" if in_toc_nav > 0 => {
                        if let Some(top) = li_stack.last_mut() {
                            if top.title.is_empty() {
                                top.title = current_text.trim().to_string();
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

/// EasyPub 等生成的内嵌 HTML 目录页兜底解析：
/// `<div class="toc"><dl><dt class="tocl2"><a href="chapter0.html">序</a></dt>...`
///
/// 识别 class 含 "toc" 的容器（如 toc / tocl2 / titletoc）内的 `<a href="...">标题</a>`，
/// 将链接解析为相对 OPF 目录的路径（与 NCX 条目一致），并按嵌套深度构建多级目录树。
fn parse_html_toc(
    content: &str,
    toc_page_path: &str,
    opf_dir: &str,
    ncx_titles: &mut NcxTitles,
    toc_items: &mut Vec<TocItem>,
) {
    let mut reader = Reader::from_str(content);
    reader.config_mut().trim_text_start = true;
    reader.config_mut().trim_text_end = true;
    let mut buf = Vec::new();

    // 元素栈：记录每个元素是否 class 含 "toc"（用于 End 事件回溯深度）
    let mut stack: Vec<bool> = Vec::new();
    let mut toc_depth: i32 = 0;
    let mut current_href: Option<String> = None;
    let mut current_text = String::new();
    // 收集 (嵌套深度, href, label)，深度用于构建树
    let mut pending: Vec<(i32, String, String)> = Vec::new();

    // 目录页所在 ZIP 目录（用于解析相对链接）
    let page_dir = toc_page_path
        .rsplit_once('/')
        .map(|(d, _)| format!("{}/", d))
        .unwrap_or_default();

    loop {
        match reader.read_event_into(&mut buf) {
            Ok(Event::Start(ref e)) => {
                let tag = String::from_utf8_lossy(e.name().as_ref()).to_lowercase();
                // 检查 class 属性是否含 toc 词元（toc / tocl2 / titletoc 等），
                // 词元需等于 "toc" 或以 "toc" 开头，避免 tocs / catalog 等误判
                let class_has_toc = e.attributes().flatten().any(|attr| {
                    let name = std::str::from_utf8(attr.key.as_ref()).unwrap_or("").to_lowercase();
                    if name == "class" {
                        let v = std::str::from_utf8(&attr.value).unwrap_or("").to_lowercase();
                        v.split(|c: char| c.is_whitespace())
                            .any(|c| c == "toc" || c.starts_with("toc"))
                    } else {
                        false
                    }
                });
                if class_has_toc {
                    toc_depth += 1;
                }
                stack.push(class_has_toc);

                if tag == "a" && toc_depth > 0 {
                    for attr in e.attributes().flatten() {
                        let name = std::str::from_utf8(attr.key.as_ref()).unwrap_or("").to_lowercase();
                        if name == "href" {
                            current_href =
                                Some(std::str::from_utf8(&attr.value).unwrap_or("").to_string());
                            break;
                        }
                    }
                    current_text.clear();
                }
            }
            Ok(Event::Text(ref e)) => {
                if toc_depth > 0 {
                    if let Ok(t) = e.unescape() {
                        current_text.push_str(t.as_ref());
                    }
                }
            }
            Ok(Event::End(ref e)) => {
                let tag = String::from_utf8_lossy(e.name().as_ref()).to_lowercase();
                if tag == "a" && toc_depth > 0 {
                    if let Some(href) = current_href.take() {
                        let label = current_text.trim().to_string();
                        if !label.is_empty() {
                            pending.push((toc_depth, href, label));
                        }
                    }
                }
                // 回溯：本元素若是 toc 容器则深度 -1
                if let Some(is_toc) = stack.pop() {
                    if is_toc && toc_depth > 0 {
                        toc_depth -= 1;
                    }
                }
            }
            Ok(Event::Eof) => break,
            Err(_) => break,
            _ => {}
        }
        buf.clear();
    }

    // 按深度构建树：pending 中 (depth, href, label)，同级 → 兄弟，更深 → 子节点。
    // 标准算法：节点栈保存当前分支；遇到新节点先弹出所有深度 >= 当前深度的栈顶，
    // 弹出的节点挂到新的栈顶（或 roots）。
    let mut node_stack: Vec<(i32, TocItem)> = Vec::new();
    let mut roots: Vec<TocItem> = Vec::new();

    for (depth, href, label) in pending {
        // 去锚点
        let clean = href.split('#').next().unwrap_or("").to_string();
        if clean.is_empty() {
            continue;
        }
        // 拼接目录页目录并规范化（处理 ./ ../ 等）
        let full = normalize_path(&format!("{}{}", page_dir, clean));
        // 去掉 OPF 目录前缀（按路径段匹配，避免 "OEBPS2/..." 这类目录名被误剥前缀）
        let opf_prefix = opf_dir.trim_end_matches('/');
        let rel = if opf_prefix.is_empty() {
            full
        } else if full == opf_prefix {
            String::new()
        } else if let Some(rest) = full.strip_prefix(&format!("{}/", opf_prefix)) {
            rest.to_string()
        } else {
            full
        };
        if rel.is_empty() {
            continue;
        }
        let trimmed = label.trim().to_string();
        if trimmed.is_empty() || is_placeholder_title(&trimmed) {
            continue;
        }

        // 弹出深度 >= 当前深度的栈顶，挂到新的栈顶（或 roots）
        while let Some(&(d, _)) = node_stack.last() {
            if d >= depth {
                let (_, child) = node_stack.pop().unwrap();
                if let Some(parent) = node_stack.last_mut() {
                    parent.1.children.push(child);
                } else {
                    roots.push(child);
                }
            } else {
                break;
            }
        }
        node_stack.push((depth, TocItem { title: trimmed.clone(), href: rel.clone(), children: Vec::new() }));
        ncx_titles.insert(rel, trimmed);
    }
    // 清空剩余栈
    while let Some((_, child)) = node_stack.pop() {
        if let Some(parent) = node_stack.last_mut() {
            parent.1.children.push(child);
        } else {
            roots.push(child);
        }
    }
    toc_items.extend(roots);
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn test_normalize_path() {
        assert_eq!(normalize_path("./foo/../bar"), "bar");
        assert_eq!(normalize_path("foo\\bar\\baz"), "foo/bar/baz");
        assert_eq!(normalize_path("./a/./b/./c"), "a/b/c");
    }

    #[test]
    fn test_find_ncx_title_case_insensitive() {
        let mut map = NcxTitles::default();
        map.insert("Text/Chapter1.xhtml".to_string(), "第一章".to_string());
        
        // 原样匹配
        assert_eq!(find_ncx_title(&map, "Text/Chapter1.xhtml"), Some(&"第一章".to_string()));
        // 大小写不敏感
        assert_eq!(find_ncx_title(&map, "text/chapter1.xhtml"), Some(&"第一章".to_string()));
        assert_eq!(find_ncx_title(&map, "TEXT/CHAPTER1.XHTML"), Some(&"第一章".to_string()));
        // 只匹配文件名
        assert_eq!(find_ncx_title(&map, "Chapter1.xhtml"), Some(&"第一章".to_string()));
        // 找不到
        assert_eq!(find_ncx_title(&map, "nonexistent.xhtml"), None);
    }

    #[test]
    fn test_commit_ncx_title_case_insensitive() {
        let mut map = NcxTitles::default();
        let mut items = Vec::new();
        commit_ncx_title(&mut map, &mut items, Some("Text/Chapter1.xhtml".to_string()), Some("第一章".to_string()));

        // 原路径
        assert_eq!(map.by_href.get("Text/Chapter1.xhtml"), Some(&"第一章".to_string()));
        // 大小写不敏感版本
        assert_eq!(map.by_href.get("text/chapter1.xhtml"), Some(&"第一章".to_string()));
        // 文件名索引
        assert_eq!(map.by_filename.get("chapter1.xhtml"), Some(&"第一章".to_string()));
        // 应该添加到目录树
        assert_eq!(items.len(), 1);
        assert_eq!(items[0].title, "第一章");
    }

    #[test]
    fn test_commit_ncx_title_skips_placeholder() {
        let mut map = NcxTitles::default();
        let mut items = Vec::new();
        commit_ncx_title(&mut map, &mut items, Some("Text/Chapter1.xhtml".to_string()), Some("Chapter 1".to_string()));

        // 应该被跳过，不添加到目录树
        assert_eq!(items.len(), 0);
    }

    #[test]
    fn test_parse_ncx_xml_multilevel_tree() {
        // NCX 多级 navpoint → 真正的树形结构
        let ncx = r#"<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <navMap>
    <navPoint id="np1" playOrder="1">
      <navLabel><text>第一卷</text></navLabel>
      <content src="Text/vol1.xhtml"/>
      <navPoint id="np1a" playOrder="2">
        <navLabel><text>第一章 开始</text></navLabel>
        <content src="Text/ch01.xhtml"/>
      </navPoint>
      <navPoint id="np1b" playOrder="3">
        <navLabel><text>第二章 继续</text></navLabel>
        <content src="Text/ch02.xhtml"/>
      </navPoint>
    </navPoint>
    <navPoint id="np2" playOrder="4">
      <navLabel><text>第二卷</text></navLabel>
      <content src="Text/vol2.xhtml"/>
      <navPoint id="np2a" playOrder="5">
        <navLabel><text>第三章</text></navLabel>
        <content src="Text/ch03.xhtml"/>
      </navPoint>
    </navPoint>
  </navMap>
</ncx>"#;
        let mut map = NcxTitles::default();
        let mut items = Vec::new();
        parse_ncx_xml(ncx, &mut map, &mut items);

        assert_eq!(items.len(), 2, "应有 2 个顶级节点: {:?}", items.iter().map(|t| &t.title).collect::<Vec<_>>());
        assert_eq!(items[0].title, "第一卷");
        assert_eq!(items[0].children.len(), 2, "第一卷应有 2 个子章节");
        assert_eq!(items[0].children[0].title, "第一章 开始");
        assert_eq!(items[0].children[1].title, "第二章 继续");
        assert_eq!(items[1].title, "第二卷");
        assert_eq!(items[1].children.len(), 1);
        assert_eq!(items[1].children[0].title, "第三章");
        // 索引也应存在
        assert_eq!(map.by_href.get("Text/ch01.xhtml").map(|s| s.as_str()), Some("第一章 开始"));
    }

    #[test]
    fn test_parse_epub3_nav_multilevel_tree() {
        // EPUB3 nav 嵌套 ol/li → 树形
        let nav = r#"<html><body><nav epub:type="toc">
<ol>
  <li><a href="vol1.xhtml">第一卷</a>
    <ol>
      <li><a href="ch01.xhtml">第一章 开始</a></li>
      <li><a href="ch02.xhtml">第二章 继续</a></li>
    </ol>
  </li>
  <li><a href="vol2.xhtml">第二卷</a>
    <ol>
      <li><a href="ch03.xhtml">第三章</a></li>
    </ol>
  </li>
</ol>
</nav></body></html>"#;
        let mut map = NcxTitles::default();
        let mut items = Vec::new();
        parse_epub3_nav(nav, &mut map, &mut items);

        assert_eq!(items.len(), 2, "应有 2 个顶级节点: {:?}", items.iter().map(|t| &t.title).collect::<Vec<_>>());
        assert_eq!(items[0].title, "第一卷");
        assert_eq!(items[0].children.len(), 2);
        assert_eq!(items[0].children[1].title, "第二章 继续");
        assert_eq!(items[1].children.len(), 1);
    }

    #[test]
    fn test_parse_html_toc_easypub() {
        // EasyPub 生成的 HTML 目录页结构
        let html = r#"<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh-CN">
<head>
  <title>Table Of Contents</title>
</head>
<body>
  <h2 class="titletoc">目录</h2>
  <div class="toc">
    <dl>
      <dt class="tocl2"><a href="chapter0.html">序</a></dt>
      <dt class="tocl2"><a href="chapter1.html">第1章 愤怒！窒息！</a></dt>
      <dt class="tocl2"><a href="chapter2.html">第2章 夺宝！无耻！</a></dt>
    </dl>
  </div>
</body>
</html>"#;
        let mut map = NcxTitles::default();
        let mut items = Vec::new();
        // 目录页在 OEBPS/ 下，opf_dir 也是 OEBPS/ → href 保持相对 OPF 目录形式
        parse_html_toc(html, "OEBPS/toc.html", "OEBPS/", &mut map, &mut items);

        assert_eq!(items.len(), 3, "应提取 3 个目录项: {:?}", items);
        assert_eq!(items[0].title, "序");
        assert_eq!(items[0].href, "chapter0.html");
        assert_eq!(items[1].title, "第1章 愤怒！窒息！");
        assert_eq!(items[1].href, "chapter1.html");
        assert_eq!(items[2].title, "第2章 夺宝！无耻！");
        assert_eq!(items[2].href, "chapter2.html");
        // ncx_titles 映射也应存在
        assert_eq!(map.by_href.get("chapter1.html").map(|s| s.as_str()), Some("第1章 愤怒！窒息！"));
    }

    #[test]
    fn test_parse_html_toc_subdir_href() {
        // 目录页在 OEBPS/Text/ 下，链接相对该目录；OPF 在 OEBPS/ → href 应去掉 OEBPS/ 前缀
        let html = r#"<html><body><div class="toc"><dl>
          <dt class="tocl2"><a href="ch01.html">第一章 开始</a></dt>
          <dt class="tocl2"><a href="ch02.html">第二章 继续</a></dt>
        </dl></div></body></html>"#;
        let mut map = NcxTitles::default();
        let mut items = Vec::new();
        parse_html_toc(html, "OEBPS/Text/toc.html", "OEBPS/", &mut map, &mut items);

        assert_eq!(items.len(), 2);
        assert_eq!(items[0].href, "Text/ch01.html");
        assert_eq!(items[1].href, "Text/ch02.html");
    }

    #[test]
    fn test_parse_html_toc_skips_placeholder_label() {
        // 链接文本是机器占位标题时应跳过（如 "Chapter 1"）
        let html = r#"<html><body><div class="toc"><dl>
          <dt class="tocl2"><a href="ch1.html">Chapter 1</a></dt>
          <dt class="tocl2"><a href="ch2.html">第二章 真实标题</a></dt>
        </dl></div></body></html>"#;
        let mut map = NcxTitles::default();
        let mut items = Vec::new();
        parse_html_toc(html, "toc.html", "", &mut map, &mut items);

        assert_eq!(items.len(), 1, "占位标题应被跳过: {:?}", items);
        assert_eq!(items[0].title, "第二章 真实标题");
    }

    #[test]
    fn test_parse_html_toc_nested_toc_container() {
        // 嵌套 toc 容器（内层 tocl2 包裹链接），栈深度回溯应正确
        let html = r#"<html><body><div class="toc"><div class="toclist"><dl>
          <dt class="tocl2"><a href="ch01.html">第一章 开始</a></dt>
          <dt class="tocl2"><a href="ch02.html">第二章 继续</a></dt>
        </dl></div></div><p>正文不在目录内</p></body></html>"#;
        let mut map = NcxTitles::default();
        let mut items = Vec::new();
        parse_html_toc(html, "toc.html", "", &mut map, &mut items);

        assert_eq!(items.len(), 2, "嵌套容器内应提取 2 项: {:?}", items);
        assert_eq!(items[0].href, "ch01.html");
        assert_eq!(items[1].href, "ch02.html");
    }

    #[test]
    fn test_parse_html_toc_opf_prefix_segment_match() {
        // opf_dir 是 OEBPS/，但目录页链接指向 OEBPS2/ 下的文件，不应误剥前缀
        let html = r#"<html><body><div class="toc"><dl>
          <dt class="tocl2"><a href="../OEBPS2/ch01.html">第一章 开始</a></dt>
        </dl></div></body></html>"#;
        let mut map = NcxTitles::default();
        let mut items = Vec::new();
        // 目录页在 OEBPS/ 根下，链接 ../OEBPS2/ch01.html → 解析后不在 OEBPS/ 下
        parse_html_toc(html, "OEBPS/toc.html", "OEBPS/", &mut map, &mut items);

        assert_eq!(items.len(), 1);
        assert_eq!(items[0].href, "OEBPS2/ch01.html", "不应误剥 OEBPS2 前缀");
    }

    #[test]
    fn test_parse_html_toc_multilevel_parent() {
        // 多层 ../ 路径规范化
        let html = r#"<html><body><div class="toc"><dl>
          <dt class="tocl2"><a href="../../Text/ch01.html">第一章 开始</a></dt>
        </dl></div></body></html>"#;
        let mut map = NcxTitles::default();
        let mut items = Vec::new();
        // 目录页在 OEBPS/Text/toc.html，OPF 在 OEBPS/
        parse_html_toc(html, "OEBPS/Text/toc.html", "OEBPS/", &mut map, &mut items);

        assert_eq!(items.len(), 1);
        assert_eq!(items[0].href, "Text/ch01.html", "多层 ../ 应规范化为相对 OPF 路径");
    }
}
