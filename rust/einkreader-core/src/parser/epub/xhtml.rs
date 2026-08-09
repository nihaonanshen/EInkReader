//! XHTML 正文内容解析与 HTML 清理

use once_cell::sync::Lazy;
use regex::Regex;

/// 块级 HTML 标签 —— 遇到这些就换行
const BLOCK_TAGS: &[&str] = &[
    "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "li", "section",
    "article", "table", "tr", "hr", "address", "dd", "dt", "header", "footer", "nav",
    "aside", "ol", "ul",
];

/// 需要跳过内容的标签
const SKIP_TAGS: &[&str] = &["style", "script", "head"];

// 预编译正则（内容清理）
static REGEX_NL_3PLUS: Lazy<Regex> = Lazy::new(|| Regex::new(r"\n{3,}").unwrap());
static REGEX_SPACE_2PLUS: Lazy<Regex> = Lazy::new(|| Regex::new(r"[ \t]{2,}").unwrap());
static REGEX_NL_TRAIL_SPACE: Lazy<Regex> = Lazy::new(|| Regex::new(r"\n[ \t]+").unwrap());
static REGEX_TRAIL_SPACE_NL: Lazy<Regex> = Lazy::new(|| Regex::new(r"[ \t]+\n").unwrap());

/// 段落类型常量（与 Chapter.kt 的 PARA_* 常量保持一致）
const PARA_NORMAL: i32 = 0;
const PARA_H1: i32 = 1;
const PARA_H2: i32 = 2;
const PARA_H3: i32 = 3;
const PARA_BLOCKQUOTE: i32 = 4;
#[allow(dead_code)]
const PARA_IMAGE: i32 = 5;

/// XHTML 解析结果
pub(super) struct XhtmlContent {
    pub(super) text: String,
    pub(super) image_paths: Vec<String>,
    pub(super) paragraph_types: Vec<i32>,
}

/// 解析 XHTML 内容，返回结构化数据
/// base_dir: 章节 XHTML 文件在 ZIP 中的目录（如 "OEBPS/Text/"），用于解析相对图片路径
pub(super) fn parse_xhtml(html: &str, base_dir: &str) -> XhtmlContent {
    if html.is_empty() {
        return XhtmlContent { text: String::new(), image_paths: Vec::new(), paragraph_types: Vec::new() };
    }
    let mut text = String::with_capacity(html.len());
    let mut image_paths = Vec::new();
    let mut paragraph_types = Vec::new();
    let bytes = html.as_bytes();
    let len = bytes.len();
    let mut i = 0;
    let mut cur_type: i32 = PARA_NORMAL;
    let mut pending_type: i32 = PARA_NORMAL;
    let mut para_has_content = false;

    while i < len {
        let c = bytes[i] as char;
        if c == '<' {
            // HTML comment
            if i + 3 < len && bytes[i+1] as char == '!' && bytes[i+2] as char == '-' && bytes[i+3] as char == '-' {
                i += 4;
                let mut cm = 0usize;
                while i < len && cm < 10000 {
                    if i+2 < len && bytes[i] as char == '-' && bytes[i+1] as char == '-' && bytes[i+2] as char == '>' { i += 3; break; }
                    i += 1; cm += 1;
                }
                continue;
            }
            // CDATA
            if i + 8 < len && bytes[i+1] as char == '!' && bytes[i+2] as char == '[' && bytes[i+3] as char == 'C'
                && bytes[i+4] as char == 'D' && bytes[i+5] as char == 'A' && bytes[i+6] as char == 'T'
                && bytes[i+7] as char == 'A' && bytes[i+8] as char == '[' {
                i += 9;
                while i + 2 < len {
                    if bytes[i] as char == ']' && bytes[i+1] as char == ']' && bytes[i+2] as char == '>' { i += 3; break; }
                    i += 1;
                }
                continue;
            }
            i += 1;
            let tag_start = i;
            while i < len && bytes[i] as char != '>' && bytes[i] as char != ' ' && bytes[i] as char != '\t' && bytes[i] as char != '\n' && bytes[i] as char != '/' { i += 1; }
            let tag_name = std::str::from_utf8(&bytes[tag_start..i]).unwrap_or_default();
            let tag_lower = tag_name.to_lowercase();
            let is_closing = tag_lower.starts_with('/');
            let bare_tag: &str = if is_closing { &tag_lower[1..] } else { &tag_lower };

            if !is_closing {
                match bare_tag {
                    "h1" => cur_type = PARA_H1,
                    "h2" => cur_type = PARA_H2,
                    "h3" => cur_type = PARA_H3,
                    "blockquote" => cur_type = PARA_BLOCKQUOTE,
                    "img" => {
                        if let Some(src) = extract_img_src(&html[i..]) {
                            // 解析为 ZIP 内绝对路径，并在文本流中插入 [[IMAGE:path]] 标记
                            // （ReaderView 据此查找图片字节并渲染）
                            let resolved = resolve_img_path(base_dir, &src);
                            image_paths.push(resolved.clone());
                            text.push_str("[[IMAGE:");
                            text.push_str(&resolved);
                            text.push_str("]]");
                            para_has_content = true;
                        }
                    }
                    _ => {}
                }
            } else {
                match bare_tag {
                    "h1" | "h2" | "h3" | "blockquote" => {
                        pending_type = cur_type;
                        cur_type = PARA_NORMAL;
                    }
                    _ => {}
                }
            }

            // skip style/script/head content
            if SKIP_TAGS.contains(&bare_tag) && !is_closing {
                let close_tag = format!("</{}", bare_tag);
                while i < len {
                    if bytes[i] as char == '<' {
                        let mut mt = true;
                        for (k, cb) in close_tag.bytes().enumerate() {
                            if i + k >= len || (bytes[i + k] as char) != (cb as char) { mt = false; break; }
                        }
                        if mt {
                            i += close_tag.len();
                            while i < len && bytes[i] as char != '>' { i += 1; }
                            if i < len { i += 1; }
                            break;
                        }
                    }
                    i += 1;
                }
                continue;
            }

            if BLOCK_TAGS.contains(&bare_tag) || bare_tag == "br" { text.push('\n'); }

            while i < len && bytes[i] as char != '>' { i += 1; }
            if i < len { i += 1; }
            continue;
        }

        if c == '&' {
            let semi = html[i..].find(';');
            if let Some(so) = semi {
                if so <= 12 {
                    if let Some(d) = decode_entity(&html[i+1..i+so]) {
                        text.push_str(&d); para_has_content = true; i += so + 1; continue;
                    }
                }
            }
            text.push(c); para_has_content = true; i += 1; continue;
        }

        if c == '\n' && para_has_content {
            paragraph_types.push(pending_type);
            pending_type = PARA_NORMAL;
            para_has_content = false;
        }
        if c != '\r' {
            if c < '\u{80}' {
                text.push(c);
            } else {
                // 多字节 UTF-8 字符：手动解码当前字符（O(1)）。
                // ⚠️ 不能用 from_utf8(&bytes[i..])——它会校验整个剩余字符串，中文文本 O(n²) 爆炸
                if let Some(ch) = decode_utf8_at(bytes, i) {
                    if ch != '\u{feff}' { // 跳过 UTF-8 BOM
                        text.push(ch);
                    }
                    i += ch.len_utf8() - 1; // 循环末尾还会 +1，合计推进 ch.len_utf8()
                }
            }
        }
        if c != '\n' && c != '\r' && c != '\t' && c != ' ' { para_has_content = true; }
        i += 1;
    }
    if para_has_content { paragraph_types.push(pending_type); }

    let mut result = text;
    result = REGEX_NL_3PLUS.replace_all(&result, "\n\n").to_string();
    result = REGEX_SPACE_2PLUS.replace_all(&result, " ").to_string();
    result = REGEX_NL_TRAIL_SPACE.replace_all(&result, "\n").to_string();
    result = REGEX_TRAIL_SPACE_NL.replace_all(&result, "\n").to_string();
    let result = result.trim().to_string();
    XhtmlContent { text: result, image_paths, paragraph_types }
}

/// 将 img src 解析为 ZIP 内绝对路径
///
/// base_dir: 章节 XHTML 所在目录（如 "OEBPS/Text/"）
/// src: <img src> 原始值（可能为相对路径、./、../、绝对路径或 http(s) URL）
/// 返回归一化路径（反斜杠转正斜杠、去掉 ./、解析 ../、URL 解码）。
/// 外链（http/https/data）与以 / 开头的路径原样返回（无法从 ZIP 读取）。
fn resolve_img_path(base_dir: &str, src: &str) -> String {
    use super::zip_utils::normalize_path;
    
    let src = src.trim();
    if src.is_empty() {
        return String::new();
    }
    // 外链 / 绝对路径：原样返回
    if src.starts_with("http://")
        || src.starts_with("https://")
        || src.starts_with("data:")
        || src.starts_with('/')
    {
        return src.to_string();
    }
    // URL 解码（支持 %20 等编码）
    let src = urlencoding::decode(src).unwrap_or_else(|_| std::borrow::Cow::Borrowed(src)).into_owned();
    // 路径规范化
    normalize_path(&format!("{}{}", base_dir, src))
}

/// 从字节流 pos 位置解码一个 UTF-8 字符（O(1)，只处理当前字符）
/// 返回 None 表示非法 UTF-8 序列
fn decode_utf8_at(bytes: &[u8], pos: usize) -> Option<char> {
    let b0 = *bytes.get(pos)?;
    if b0 < 0x80 {
        return Some(b0 as char);
    }
    let (len, cp) = if b0 >= 0xF0 {
        (4, (b0 & 0x07) as u32)
    } else if b0 >= 0xE0 {
        (3, (b0 & 0x0F) as u32)
    } else if b0 >= 0xC0 {
        (2, (b0 & 0x1F) as u32)
    } else {
        return None; // 非法首字节
    };
    if pos + len > bytes.len() {
        return None;
    }
    let mut cp = cp;
    for k in 1..len {
        let b = bytes[pos + k];
        if b & 0xC0 != 0x80 {
            return None; // 非法续字节
        }
        cp = (cp << 6) | (b & 0x3F) as u32;
    }
    char::from_u32(cp)
}

/// 从 img 标签中提取 src 属性（支持 SVG <image> 标签）
///
/// 优先取真正的 `src` 属性。注意：微信读书源的 EPUB 中 `<img>` 常写成
/// `<img data-src="https://..." src="data-url-image.jpeg"/>` —— `data-src` 是原始外链，
/// `src` 才是 ZIP 内可用的本地图。必须精确匹配 `src=`（不能把 `data-src` 的子串误当 src），
/// 且 `src` 存在时优先于 `data-src` 返回。
fn extract_img_src(html_after_tag: &str) -> Option<String> {
    // 1) 精确匹配 src= 属性（排除 data-src / lazy-src 等带前缀的变体）
    let mut candidate_src: Option<String> = None;
    let mut candidate_data_src: Option<String> = None;

    let bytes = html_after_tag.as_bytes();
    let len = bytes.len();
    let mut i = 0;
    // data-src 分支需要访问 bytes[i+8]，循环条件必须保证 i+8 安全
    while i + 8 < len {
        // 检查是否为独立属性名 src=（前一个字符必须是空白/引号/标签开始，即不在 data- 等前缀后）
        if (bytes[i] as char == 's' || bytes[i] as char == 'S')
            && (bytes[i+1] as char == 'r' || bytes[i+1] as char == 'R')
            && (bytes[i+2] as char == 'c' || bytes[i+2] as char == 'C')
            && bytes[i+3] as char == '='
        {
            // 向前看：src 前面必须是属性边界（空白、>、引号），不能是 '-'（如 data-src）
            let prev = if i > 0 { bytes[i-1] as char } else { ' ' };
            if prev == '-' || prev == ':' || prev.is_ascii_alphanumeric() || prev == '_' {
                i += 1;
                continue;
            }
            i += 4;
            let q = bytes[i] as char;
            if q != '"' && q != '\'' { continue; }
            let start = i + 1;
            let mut end = start;
            while end < len && (bytes[end] as char) != q { end += 1; }
            if end > start && end < len {
                let src = std::str::from_utf8(&bytes[start..end]).unwrap_or_default().to_string();
                let cleaned = if src.starts_with("./") { src[2..].to_string() } else { src };
                // HTML 语义：真正的 src 优先于 data-src，直接返回
                return Some(cleaned);
            }
            return None;
        }
        // data-src 变体（微信读书源）：仅当没有真正的 src 时才作为兜底
        if (bytes[i] as char == 'd' || bytes[i] as char == 'D')
            && (bytes[i+1] as char == 'a' || bytes[i+1] as char == 'A')
            && (bytes[i+2] as char == 't' || bytes[i+2] as char == 'T')
            && (bytes[i+3] as char == 'a' || bytes[i+3] as char == 'A')
            && bytes[i+4] as char == '-'
            && (bytes[i+5] as char == 's' || bytes[i+5] as char == 'S')
            && (bytes[i+6] as char == 'r' || bytes[i+6] as char == 'R')
            && (bytes[i+7] as char == 'c' || bytes[i+7] as char == 'C')
            && bytes[i+8] as char == '='
        {
            i += 9;
            let q = bytes[i] as char;
            if q != '"' && q != '\'' { continue; }
            let start = i + 1;
            let mut end = start;
            while end < len && (bytes[end] as char) != q { end += 1; }
            if end > start && end < len {
                let src = std::str::from_utf8(&bytes[start..end]).unwrap_or_default().to_string();
                if candidate_data_src.is_none() {
                    candidate_data_src = Some(if src.starts_with("./") { src[2..].to_string() } else { src });
                }
            }
        }
        // 也支持 xlink:href 格式（SVG）：xlink:href = 11 字符
        if (bytes[i] as char == 'x' || bytes[i] as char == 'X')
            && bytes.get(i+1..).unwrap_or(&[]).windows(10).any(|w| {
                w.iter().zip("link:href".as_bytes()).all(|(a, b)| a.eq_ignore_ascii_case(b))
            }) {
            i += 11;
            let Some(q) = bytes.get(i) else { i -= 1; continue; };
            if *q != b'"' && *q != b'\'' { continue; }
            let start = i + 1;
            let mut end = start;
            while end < len && bytes[end] != *q { end += 1; }
            if end > start && end < len {
                return Some(std::str::from_utf8(&bytes[start..end]).unwrap_or_default().to_string());
            }
            // 找不到闭合引号：跳过，继续扫描其他属性
            continue;
        }
        i += 1;
    }
    // 没有 src 时用 data-src 兜底
    candidate_src.or(candidate_data_src)
}

/// 检查是否是支持的图片格式
pub fn is_image_path(path: &str) -> bool {
    use super::zip_utils::is_image_path as _is_image_path;
    _is_image_path(path)
}

/// 清理 HTML 为纯文本（保留换行结构）—— 保留给测试与兼容场景
#[allow(dead_code)]
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
    fn test_parse_xhtml_chinese_not_mojibake() {
        // 回归：parse_xhtml 曾把 UTF-8 字节逐个 as char，导致中文双重编码乱码
        let html = "<html><body><h1>第一章 初入江湖</h1><p>他推开门走了进去。</p></body></html>";
        let parsed = parse_xhtml(html, "");
        assert!(parsed.text.contains("第一章 初入江湖"), "got: {:?}", parsed.text);
        assert!(parsed.text.contains("他推开门走了进去"), "got: {:?}", parsed.text);
        // 每个字符都应是合法 Unicode 字符，不能是逐字节 Latin-1 形态
        assert!(!parsed.text.contains('\u{e7}'), "mojibake detected: {:?}", parsed.text);

        // 带 BOM 的 XHTML（EPUB 常见）：BOM 应被剥离
        let html2 = "\u{feff}<?xml version=\"1.0\"?><html><body><p>中文内容</p></body></html>";
        let parsed2 = parse_xhtml(html2, "");
        assert!(!parsed2.text.starts_with('\u{feff}'), "BOM kept: {:?}", parsed2.text);
        assert!(parsed2.text.contains("中文内容"), "got: {:?}", parsed2.text);
    }

    #[test]
    fn test_parse_xhtml_image_marker_and_resolve() {
        // 图片应解析为 ZIP 绝对路径并插入 [[IMAGE:path]] 标记
        let html = "<html><body><p>文字<img src=\"../Images/00005.jpeg\"/>结尾</p></body></html>";
        let parsed = parse_xhtml(html, "OEBPS/Text/");
        assert_eq!(parsed.image_paths, vec!["OEBPS/Images/00005.jpeg"]);
        assert!(parsed.text.contains("[[IMAGE:OEBPS/Images/00005.jpeg]]"), "got: {:?}", parsed.text);
        assert!(parsed.text.contains("文字"), "got: {:?}", parsed.text);
        assert!(parsed.text.contains("结尾"), "got: {:?}", parsed.text);

        // ./ 前缀与反斜杠
        let html2 = "<p><img src=\"./fig1.png\"/></p>";
        let parsed2 = parse_xhtml(html2, "Text/");
        assert_eq!(parsed2.image_paths, vec!["Text/fig1.png"]);

        // 外链保持原样
        let html3 = "<p><img src=\"https://example.com/a.jpg\"/></p>";
        let parsed3 = parse_xhtml(html3, "Text/");
        assert_eq!(parsed3.image_paths, vec!["https://example.com/a.jpg"]);
        assert!(parsed3.text.contains("[[IMAGE:https://example.com/a.jpg]]"));
    }

    #[test]
    fn test_extract_img_src_prefers_src_over_data_src() {
        // 微信读书源：<img data-src="https://外链" src="本地图"/> —— 必须优先取 src
        let tag = r#"<img alt="" class="qqreader-fullimg" data-src="https://res.weread.qq.com/wrepub/CB_1_a1.jpg" src="data-url-image.jpeg"/>"#;
        assert_eq!(extract_img_src(tag).as_deref(), Some("data-url-image.jpeg"));

        // 只有 data-src 没有 src 时兜底用 data-src
        let tag2 = r#"<img data-src="https://example.com/x.jpg"/>"#;
        assert_eq!(extract_img_src(tag2).as_deref(), Some("https://example.com/x.jpg"));

        // 常规 src 不受影响
        let tag3 = r#"<img src="../Images/00005.jpeg"/>"#;
        assert_eq!(extract_img_src(tag3).as_deref(), Some("../Images/00005.jpeg"));
    }

    #[test]
    fn test_extract_img_src_svg_xlink() {
        // SVG <image xlink:href="..."> 分支
        let tag = r#"<image xlink:href="Images/fig1.svg"/>"#;
        assert_eq!(extract_img_src(tag).as_deref(), Some("Images/fig1.svg"));
    }

    #[test]
    fn test_extract_img_src_short_input_no_panic() {
        // 短输入/边界不应 panic
        assert_eq!(extract_img_src(""), None);
        assert_eq!(extract_img_src("<img>"), None);
        assert_eq!(extract_img_src("<img src"), None);
        assert_eq!(extract_img_src(r#"<img data-src="x"/>"#), Some("x".to_string()));
    }

    #[test]
    fn test_parse_xhtml_data_src_image() {
        // 完整解析：微信读书风格 img 提取本地 src
        let html = r#"<html><body><p><img alt="" class="qqreader-fullimg" data-src="https://res.weread.qq.com/wrepub/CB_1_a1.jpg" src="data-url-image.jpeg"/></p></body></html>"#;
        let parsed = parse_xhtml(html, "");
        assert_eq!(parsed.image_paths, vec!["data-url-image.jpeg"]);
        assert!(parsed.text.contains("[[IMAGE:data-url-image.jpeg]]"), "got: {:?}", parsed.text);
    }

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
