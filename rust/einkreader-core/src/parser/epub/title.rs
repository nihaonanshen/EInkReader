//! 章节标题提取与解析

use once_cell::sync::Lazy;
use regex::Regex;

use super::toc::{find_ncx_title, NcxTitles};
// 预编译正则（标题相关）
// EasyPub 等工具生成的机器占位标题：chapter、Chapter 1、chapter_2、CHAPTER 12、chapter 1 - 0、chapter1-1 等
static REGEX_FAKE_CHAPTER: Lazy<Regex> =
    Lazy::new(|| Regex::new(r"(?i)^chapter[\s_\-]*\d+([\s_\-]+\d+)*$").unwrap());
static REGEX_LEADING_CHAP: Lazy<Regex> =
    Lazy::new(|| Regex::new(r"^(?i)(chapter|chap|ch|section|sec|part|lesson|unit|volume|vol|module)\s*").unwrap());
static REGEX_TRAILING_SEP: Lazy<Regex> = Lazy::new(|| Regex::new(r"[._\-–\s]+$").unwrap());
static REGEX_LEADING_SEP: Lazy<Regex> = Lazy::new(|| Regex::new(r"^[_\-–\s]+").unwrap());
static REGEX_TRAILING_SEP2: Lazy<Regex> = Lazy::new(|| Regex::new(r"[_\-–\s]+$").unwrap());
static REGEX_LEADING_ZERO: Lazy<Regex> = Lazy::new(|| Regex::new(r"^0+").unwrap());
static REGEX_H1: Lazy<Regex> = Lazy::new(|| Regex::new(r"(?is)<h1(?:[^>]*)?>(.*?)</h1\s*>").unwrap());
static REGEX_H2: Lazy<Regex> = Lazy::new(|| Regex::new(r"(?is)<h2(?:[^>]*)?>(.*?)</h2\s*>").unwrap());
static REGEX_H3: Lazy<Regex> = Lazy::new(|| Regex::new(r"(?is)<h3(?:[^>]*)?>(.*?)</h3\s*>").unwrap());
static REGEX_TITLE: Lazy<Regex> = Lazy::new(|| Regex::new(r"(?is)<title(?:[^>]*)?>(.*?)</title\s*>").unwrap());
static REGEX_STRIP_TAGS: Lazy<Regex> = Lazy::new(|| Regex::new(r"<[^>]*>").unwrap());

/// 机器生成的占位标题：空、"chapter"、"Chapter 1"、"chapter_2"、
/// "章节正文"（部分 EPUB 生成器对每个 XHTML 的固定占位标题）等
pub(super) fn is_placeholder_title(t: &str) -> bool {
    t.is_empty()
        || t.eq_ignore_ascii_case("chapter")
        || REGEX_FAKE_CHAPTER.is_match(t)
        || matches!(t.trim(), "章节正文" | "章节正文内容" | "正文" | "ChapterText")
}

/// 从原始 HTML 提取真实标题：优先 <title>，其次 <h1>/<h2>/<h3>
pub(super) fn extract_title_from_raw(html: &str) -> Option<String> {
    // 优先从 <title> 标签提取（与 Java fallback 行为一致）；占位符标题（如 "Chapter 1"）跳过
    if let Some(cap) = REGEX_TITLE.captures(html) {
        let candidate = REGEX_STRIP_TAGS.replace_all(&cap[1], "").trim().to_string();
        if !candidate.is_empty() && candidate.len() < 200 && !is_placeholder_title(&candidate) {
            return Some(candidate);
        }
    }
    // 其次从 <h1>/<h2>/<h3> 提取（<title> 为机器占位符时，这里往往有真实章节名）
    for regex in [&*REGEX_H1, &*REGEX_H2, &*REGEX_H3] {
        if let Some(cap) = regex.captures(html) {
            let candidate = REGEX_STRIP_TAGS.replace_all(&cap[1], "").trim().to_string();
            if !candidate.is_empty() && candidate.len() < 200 && !is_placeholder_title(&candidate) {
                return Some(candidate);
            }
        }
    }
    None
}

/// 通过 NCX 映射表解析章节标题（多级匹配策略，支持大小写不敏感）
pub(super) fn resolve_title(href: &str, ncx_titles: &NcxTitles, _index: usize) -> String {
    // 使用增强的大小写不敏感查找
    if let Some(t) = find_ncx_title(ncx_titles, href) {
        return t.clone();
    }
    String::new()
}

/// 从 href 文件名提取标题（如 "ch01.xhtml" → "第1章"）
pub(super) fn extract_title_from_href(href: &str, index: usize) -> String {
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    #[test]
    fn test_placeholder_title_detection() {
        assert!(is_placeholder_title(""));
        assert!(is_placeholder_title("chapter"));
        assert!(is_placeholder_title("Chapter"));
        assert!(is_placeholder_title("Chapter 1"));
        assert!(is_placeholder_title("chapter_2"));
        assert!(is_placeholder_title("CHAPTER 12"));
        // EasyPub 机器占位标题：chapter 1 - 0 / chapter1-1 / chapter 1 - 2
        assert!(is_placeholder_title("chapter 1 - 0"));
        assert!(is_placeholder_title("Chapter 1 - 0"));
        assert!(is_placeholder_title("chapter1-1"));
        assert!(is_placeholder_title("chapter 12 - 3"));
        // 带真实后缀的章节名不应误判
        assert!(!is_placeholder_title("第一章 初入江湖"));
        assert!(!is_placeholder_title("Chapter 1: 真正的标题"));
        assert!(!is_placeholder_title("chapter 1 初入江湖"));
        assert!(!is_placeholder_title("序言"));
        // 中文机器占位标题
        assert!(is_placeholder_title("章节正文"));
        assert!(is_placeholder_title(" 章节正文 "));
        assert!(!is_placeholder_title("章节正文：初入江湖"));
    }

    #[test]
    fn test_extract_title_skips_placeholder() {
        // <title> 是占位符时,应跳过并继续用 <h1> 的真实标题
        let html = "<html><head><title>Chapter 1</title></head><body><h1>第一章 初入江湖</h1><p>x</p></body></html>";
        assert_eq!(extract_title_from_raw(html).as_deref(), Some("第一章 初入江湖"));
        // <title> 与 <h1> 都是占位符 → None
        let html2 = "<html><head><title>Chapter 2</title></head><body><h1>Chapter 2</h1><p>x</p></body></html>";
        assert_eq!(extract_title_from_raw(html2), None);
    }

    #[test]
    fn test_extract_title_from_html() {
        // 优先从 <title> 提取，而不是 <h1>
        let html =
            "<html><head><title>Test Title</title></head><body><h1>Chapter One</h1><p>text</p></body></html>";
        let title = extract_title_from_raw(html);
        assert_eq!(title, Some("Test Title".to_string()));
    }

    #[test]
    fn test_extract_title_from_title_tag_only() {
        // 只有 <title> 没有 <h1> 的情况
        let html = "<html><head><title>第一章：开始</title></head><body><p>正文内容</p></body></html>";
        let title = extract_title_from_raw(html);
        assert_eq!(title, Some("第一章：开始".to_string()));
    }

    #[test]
    fn test_extract_title_fallback_to_h1() {
        // 没有 <title> 时回退到 <h1>
        let html = "<html><body><h1>章节标题</h1><p>正文</p></body></html>";
        let title = extract_title_from_raw(html);
        assert_eq!(title, Some("章节标题".to_string()));
    }

    #[test]
    fn test_extract_title_from_href() {
        assert_eq!(extract_title_from_href("ch01.xhtml", 0), "第1章");
        assert_eq!(extract_title_from_href("001.xhtml", 0), "第1章");
        assert_eq!(extract_title_from_href("chapter_1.xhtml", 0), "1");
    }

    #[test]
    fn test_resolve_title() {
        let mut map = NcxTitles::default();
        map.insert("ch01.xhtml".to_string(), "第一章 开始".to_string());
        assert_eq!(resolve_title("ch01.xhtml", &map, 0), "第一章 开始");
        assert_eq!(resolve_title("unknown.xhtml", &map, 0), "");
    }
}
