//! TXT 文件解析器
//!
//! 功能（与 Java TxtParser.java 行为一致）：
//! 1. 自动检测编码
//! 2. 按中文章节标题自动分章
//! 3. 英文 Chapter 格式支持
//! 4. 无标题时按字数分割

use regex::Regex;
use std::fs;
use std::path::Path;

use crate::encoding;
use crate::types::{Chapter, TxtParseResult};

/// 无标题时默认分割字数
const DEFAULT_CHAPTER_SIZE: usize = 3000;

/// 懒编译的正则表达式
struct ChapterPatterns {
    /// 完整版：第X章/回/节 + 可选标题
    full: Regex,
    /// 宽松匹配：只要开头是"第X章"就算
    loose: Regex,
    /// 英文 Chapter 1 / Chapter One 格式
    english: Regex,
    /// Volume 格式
    volume: Regex,
    /// 特殊章节：楔子、序章、后记等
    special: Regex,
    /// 纯数字章节（要求有明确分隔符）
    numeric: Regex,
    /// 装饰性标题
    decorated: Regex,
    /// 任何位置的第X章（行首10字符内）
    anywhere: Regex,
}

impl ChapterPatterns {
    fn new() -> Self {
        Self {
            full: Regex::new(
                r"^[\s\u{3000}]*[【\-―※（(\[{]*第[零一二三四五六七八九十百千万亿\d]{1,8}[章节回卷集篇部折][）)〕\]}]?[\s\u{3000}]*(?:[】\-―※]*[\s\u{3000}]*(\S.*))?[\s\u{3000}]*$"
            ).unwrap(),
            loose: Regex::new(
                r"^\s*第[零一二三四五六七八九十百千万亿\d]{1,8}[章节回卷集篇部折]"
            ).unwrap(),
            english: Regex::new(
                r"^(?i)(chapter|chap|ch|section|sec|part|lesson|unit|volume|vol|module|lecture)[.\-:\s]*[\d一二三四五六七八九十百千]+(?:[.\-:\s]+.*)?$"
            ).unwrap(),
            volume: Regex::new(
                r"^(?i)(volume|vol)\s*\.?\s*[\d]+(?:[.:\s]+.*)?$"
            ).unwrap(),
            special: Regex::new(
                r"^[\s\u{3000}]*(?:楔子|序章|序言|引子|前言|前奏|序幕|开篇|开场|写在前面|题记)[\s\u{3000}]*$|^[\s\u{3000}]*(?:后记|尾声|终章|结局|尾声|结语|番外|外传|特别篇|附录|附注)[\s\u{3000}]*$"
            ).unwrap(),
            numeric: Regex::new(
                r"^[\s\u{3000}]*(?:[零一二三四五六七八九十百千万亿]+|[\d]+)[、．.\s　](?:[\u{4e00}-\u{9fff}]{1,30})?[\s\u{3000}]*$"
            ).unwrap(),
            decorated: Regex::new(
                r"^[\s\u{3000}]*[\u{2500}-\u{257F}◆◇◎▲△▽▼○●□■☆★※＊*#_\-　]{0,15}第[零一二三四五六七八九十百千万亿\d]{1,8}[章节回卷集篇部折].*$"
            ).unwrap(),
            anywhere: Regex::new(
                r"^[\s\u{3000}\u{2500}-\u{257F}◆◇◎▲△▽▼○●□■☆★※＊*#_\-]{0,10}第[零一二三四五六七八九十百千万亿\d]{1,8}[章节回卷集篇部折]"
            ).unwrap(),
        }
    }

    fn is_chapter_title(&self, line: &str) -> bool {
        if line.is_empty() {
            return false;
        }
        let trimmed = line.trim();
        // 太长的行不可能是标题
        if trimmed.len() > 80 {
            return false;
        }
        self.full.is_match(line)
            || self.loose.is_match(line)
            || self.english.is_match(line)
            || self.special.is_match(line)
            || self.volume.is_match(line)
            || self.numeric.is_match(line)
            || self.decorated.is_match(line)
            || (trimmed.len() < 50 && self.anywhere.is_match(line))
    }

    fn extract_title(&self, line: &str) -> String {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            return trimmed.to_string();
        }
        if trimmed.len() > 80 {
            return String::new();
        }
        if self.full.is_match(trimmed)
            || self.loose.is_match(trimmed)
            || self.english.is_match(trimmed)
            || self.special.is_match(trimmed)
            || self.volume.is_match(trimmed)
            || self.numeric.is_match(trimmed)
            || self.decorated.is_match(trimmed)
            || (trimmed.len() < 50 && self.anywhere.is_match(trimmed))
        {
            return trimmed.to_string();
        }
        String::new()
    }
}

/// 清理标题中的装饰字符
fn clean_title(title: &str) -> String {
    let mut cleaned = title.trim().to_string();
    cleaned = regex::Regex::new(r"^[【\[\-―※（(]+")
        .unwrap()
        .replace_all(&cleaned, "")
        .to_string();
    cleaned = regex::Regex::new(r"[】\]\-―※）)]+$")
        .unwrap()
        .replace_all(&cleaned, "")
        .to_string();
    cleaned = regex::Regex::new(r"[（(][完上中下续终]?[）)]$")
        .unwrap()
        .replace_all(&cleaned, "")
        .to_string();
    cleaned = regex::Regex::new(r"[\s\u{3000}]+")
        .unwrap()
        .replace_all(&cleaned, " ")
        .to_string();
    cleaned.trim().to_string()
}

/// 从文件名提取书名
fn extract_title_from_filename(path: &str) -> String {
    let path = Path::new(path);
    let name = path
        .file_stem()
        .and_then(|s| s.to_str())
        .unwrap_or("未知书籍")
        .to_string();
    name
}

/// 解析 TXT 文件
pub fn parse_txt(file_path: &str, forced_encoding: Option<&str>) -> Result<TxtParseResult, String> {
    let bytes = fs::read(file_path).map_err(|e| format!("读取文件失败: {}", e))?;
    parse_txt_bytes(&bytes, file_path, forced_encoding)
}

/// 从字节数组解析 TXT（支持 content:// URI 场景）
pub fn parse_txt_bytes(
    bytes: &[u8],
    file_path: &str,
    forced_encoding: Option<&str>,
) -> Result<TxtParseResult, String> {
    let book_title = extract_title_from_filename(file_path);

    // 检测编码
    let encoding = if let Some(enc) = forced_encoding {
        if !enc.is_empty() {
            enc.to_string()
        } else {
            encoding::detect(bytes).encoding
        }
    } else {
        encoding::detect(bytes).encoding
    };

    // 尝试解码
    let full_text = try_decode(bytes, &encoding, file_path)?;

    // 行偏移构建
    let line_offsets = build_line_offsets(&full_text);
    let line_count = line_offsets.len().saturating_sub(1);

    let patterns = ChapterPatterns::new();

    // 检测章节标题
    let mut chapter_breaks: Vec<(usize, usize)> = Vec::new(); // (start_line, end_line)
    let mut chapter_titles: Vec<Option<String>> = Vec::new();

    for li in 0..line_count {
        let line_text = extract_line(&full_text, &line_offsets, li);
        if patterns.is_chapter_title(&line_text) {
            if chapter_breaks.is_empty() && li > 0 {
                chapter_breaks.push((0, li));
                chapter_titles.push(None);
            } else if !chapter_breaks.is_empty() {
                let prev_end = &mut chapter_breaks.last_mut().unwrap().1;
                *prev_end = li;
            }
            chapter_breaks.push((li + 1, usize::MAX));
            chapter_titles.push(Some(clean_title(&patterns.extract_title(&line_text))));
        }
    }

    if !chapter_breaks.is_empty() {
        if let Some(last) = chapter_breaks.last_mut() {
            last.1 = line_count;
        }
    }

    // 后处理：合并多行标题
    let loose_pattern = ChapterPatterns::new().loose;
    for i in 0..chapter_titles.len() {
        if let Some(ref title) = chapter_titles[i] {
            if title.len() < 12 && loose_pattern.is_match(title) {
                let brk = chapter_breaks[i];
                let first_content_line = brk.0;
                if first_content_line < line_count {
                    let next_line = extract_line(&full_text, &line_offsets, first_content_line);
                    let trimmed_next = next_line.trim();
                    if !trimmed_next.is_empty()
                        && trimmed_next.len() < 80
                        && !patterns.is_chapter_title(&trimmed_next)
                        && !trimmed_next.starts_with("[[IMAGE:")
                    {
                        chapter_titles[i] = Some(format!("{} {}", title, trimmed_next));
                        chapter_breaks[i].0 = first_content_line + 1;
                    }
                }
            }
        }
    }

    let mut chapters: Vec<Chapter> = Vec::new();

    // 构建章节列表
    if !chapter_breaks.is_empty() {
        for (i, &(start_line, end_line)) in chapter_breaks.iter().enumerate() {
            let end = if end_line == usize::MAX {
                line_count
            } else {
                end_line
            };
            let content = extract_lines(&full_text, &line_offsets, start_line, end);
            let title = match &chapter_titles[i] {
                Some(t) if !t.is_empty() => t.clone(),
                _ => {
                    if i == 0 {
                        "引子".to_string()
                    } else {
                        format!("第{}章", i + 1)
                    }
                }
            };
            chapters.push(Chapter {
                title,
                content,
                line_start: Some(start_line),
                line_end: Some(end),
                index: Some(i),
            });
        }
    }

    // 没找到章节标题时，按固定字数分割
    if chapters.is_empty() {
        let all_lines: Vec<String> = (0..line_count)
            .map(|li| extract_line(&full_text, &line_offsets, li))
            .collect();
        chapters = split_by_size(&all_lines, DEFAULT_CHAPTER_SIZE);
    }

    Ok(TxtParseResult {
        book_title,
        encoding,
        chapters,
    })
}

/// 尝试用指定编码解码，失败时尝试回退编码
fn try_decode(bytes: &[u8], encoding: &str, file_path: &str) -> Result<String, String> {
    // 先用检测到的编码尝试
    let enc = encoding_rs::Encoding::for_label(encoding.as_bytes()).unwrap_or(encoding_rs::UTF_8);
    let (text, _, had_errors) = enc.decode(bytes);

    if !had_errors && !text.is_empty() {
        // 验证中文字符比例
        let total = text.chars().take(10000).count();
        let chinese = text
            .chars()
            .take(10000)
            .filter(|c| matches!(c, '\u{4E00}'..='\u{9FFF}'))
            .count();
        let replacements = text
            .chars()
            .take(10000)
            .filter(|&c| c == '\u{FFFD}') // replacement character
            .count();

        if total > 200 && replacements * 100 / total.max(1) > 10 {
            // 替换字符过多，尝试下一个编码
            return try_fallback_decode(bytes, encoding);
        }
        if total > 500 && chinese == 0 && replacements == 0 {
            // 无中文也无替换——可能是英文或其他，接受
        }
        return Ok(text.to_string());
    }

    // 当前编码失败，尝试回退
    try_fallback_decode(bytes, encoding)
}

fn try_fallback_decode(bytes: &[u8], skip_encoding: &str) -> Result<String, String> {
    for enc_name in encoding::FALLBACK_ENCODINGS {
        if *enc_name == skip_encoding {
            continue;
        }
        if let Some(enc) = encoding_rs::Encoding::for_label(enc_name.as_bytes()) {
            let (text, _, _) = enc.decode(bytes);
            if !text.is_empty() {
                return Ok(text.to_string());
            }
        }
    }
    // 所有编码都失败，UTF-8 兜底
    let (text, _, _) = encoding_rs::UTF_8.decode(bytes);
    Ok(text.to_string())
}

/// 构建行偏移数组
fn build_line_offsets(text: &str) -> Vec<usize> {
    let mut offsets = vec![0usize];
    for (i, c) in text.char_indices() {
        if c == '\n' {
            offsets.push(i + 1);
        }
    }
    offsets.push(text.len());
    offsets
}

/// 提取指定行的文本（不含换行符）
fn extract_line(text: &str, offsets: &[usize], line_idx: usize) -> String {
    let start = offsets[line_idx];
    let mut end = offsets[line_idx + 1];
    // 去掉末尾的 \n 或 \r\n
    if end > start && text.as_bytes()[end - 1] == b'\n' {
        end -= 1;
    }
    if end > start && text.as_bytes()[end - 1] == b'\r' {
        end -= 1;
    }
    text[start..end].to_string()
}

/// 提取从 start_line 到 end_line（不含）的文本内容
fn extract_lines(text: &str, offsets: &[usize], start_line: usize, end_line: usize) -> String {
    let start = offsets[start_line];
    let end = if end_line < offsets.len() {
        offsets[end_line]
    } else {
        text.len()
    };
    text[start..end].to_string()
}

/// 按字数分割章节（后备方案）
fn split_by_size(lines: &[String], chars_per_chapter: usize) -> Vec<Chapter> {
    let mut chapters = Vec::new();
    let mut current = String::new();
    let mut chapter_num = 1usize;
    let mut line_start = 0usize;

    for (i, line) in lines.iter().enumerate() {
        current.push_str(line);
        current.push('\n');
        if current.len() >= chars_per_chapter {
            chapters.push(Chapter {
                title: format!("第{}段", chapter_num),
                content: current.clone(),
                line_start: Some(line_start),
                line_end: Some(i + 1),
                index: Some(chapter_num - 1),
            });
            current = String::new();
            line_start = i + 1;
            chapter_num += 1;
        }
    }

    if !current.is_empty() {
        chapters.push(Chapter {
            title: format!("第{}段", chapter_num),
            content: current,
            line_start: Some(line_start),
            line_end: Some(lines.len()),
            index: Some(chapter_num - 1),
        });
    }

    chapters
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_chapter_patterns() {
        let patterns = ChapterPatterns::new();
        assert!(patterns.is_chapter_title("第一章 初入江湖"));
        assert!(patterns.is_chapter_title("第一回"));
        assert!(patterns.is_chapter_title("Chapter 1"));
        assert!(patterns.is_chapter_title("Chapter One"));
        assert!(patterns.is_chapter_title("楔子"));
        assert!(patterns.is_chapter_title("后记"));
        assert!(patterns.is_chapter_title("第一百零八章"));
        assert!(!patterns.is_chapter_title("这是一个普通的正文段落"));
        assert!(!patterns.is_chapter_title(""));
    }

    #[test]
    fn test_build_line_offsets() {
        let text = "line1\nline2\nline3";
        let offsets = build_line_offsets(text);
        assert_eq!(offsets.len(), 4); // 3 lines + 1 sentinel
        assert_eq!(extract_line(text, &offsets, 0), "line1");
        assert_eq!(extract_line(text, &offsets, 1), "line2");
        assert_eq!(extract_line(text, &offsets, 2), "line3");
    }

    #[test]
    fn test_split_by_size() {
        let lines = vec![
            "a".repeat(1000),
            "b".repeat(1000),
            "c".repeat(1000),
            "d".repeat(1000),
        ];
        let chapters = split_by_size(&lines, 1500);
        assert!(chapters.len() >= 2);
    }

    #[test]
    fn test_clean_title() {
        assert_eq!(clean_title("【第一章】"), "第一章");
        assert_eq!(clean_title("第一章　初入江湖"), "第一章 初入江湖");
    }
}
