//! TXT 文件解析器
//!
//! 功能（与 Java TxtParser.java 行为一致）：
//! 1. 自动检测编码
//! 2. 按中文章节标题自动分章
//! 3. 英文 Chapter 格式支持
//! 4. 无标题时按字数分割

use once_cell::sync::Lazy;
use regex::Regex;
use std::fs;
use std::path::Path;

use crate::encoding;
use crate::types::{Chapter, TxtParseResult};

/// 无标题时默认分割字数
const DEFAULT_CHAPTER_SIZE: usize = 3000;

/// 懒编译的正则表达式
struct ChapterPatterns {
    /// 组合正则（含全部 8 种模式），消除 O(n×m) 多次匹配
    combined: Regex,
    /// 严格模式组合正则（full + loose + english + special）
    combined_strict: Regex,
}

impl ChapterPatterns {
    fn new() -> Self {
        // 全部 8 种模式已合并为单一组合正则，消除 O(n×m) 多次匹配。
        // 分支不带锚点，构造时统一外包 \A(?:...)\z 实现全串匹配，
        // 与 Kotlin 版 Matcher.matches() 语义一致（原实现用 is_match 只做子串匹配，会误判 "Chapter 12 是一个..." 这类正文行）。
        let all_patterns: [&str; 8] = [
            r"[\s\u{3000}]*[【\-―※（(\[]*第[零一二三四五六七八九十百千万亿0-9]{1,8}[章节回卷集篇部折][）)〕\]}]?[\s\u{3000}]*(?:[】\-―※]*[\s\u{3000}]*(\S.*))?[\s\u{3000}]*",
            r"\s*第[零一二三四五六七八九十百千万亿0-9]{1,8}[章节回卷集篇部折]",
            r"(?i)(chapter|chap|ch|section|sec|part|lesson|unit|volume|vol|module|lecture)[.\-:\s]+([0-9]+|[a-z]+(?:\s[a-z]+){0,3})(?:[.\-:\s]+[A-Za-z].*)?[\s\u{3000}]*",
            r"[\s\u{3000}]*(?:楔子|序章|序言|引子|前言|前奏|序幕|开篇|开场|写在前面|题记)[\s\u{3000}]*(?:[\S\u{3000}].*)?[\s\u{3000}]*|[\s\u{3000}]*(?:后记|尾声|终章|结局|结语|番外|外传|特别篇|附录|附注|致谢)[\s\u{3000}]*(?:[\S\u{3000}].*)?[\s\u{3000}]*",
            r"(?i)(volume|vol)\s*\.?\s*[0-9]+(?:[.:\s]+.*)?",
            r"[\s\u{3000}]*(?:[零一二三四五六七八九十百千万亿]{1,8}[、．.\s\u{3000}][\u{4e00}-\u{9fff}]{2,30}|[0-9]{1,3}[、．.][\u{4e00}-\u{9fff}]{8,30})[\s\u{3000}]*",
            r"[\s\u{3000}]*[\u{2500}-\u{257F}\u{25c6}\u{25c7}\u{25ce}\u{25b2}\u{25b3}\u{25bd}\u{25bc}\u{25cb}\u{25cf}\u{25a1}\u{25a4}\u{2606}\u{2605}\u{203b}\u{203c}\u{2049}\u{2a2f}\u{2217}\u{2261}\u{005f}\u{002a}\u{0023}\-\s\u{3000}]{0,15}第[零一二三四五六七八九十百千万亿0-9]{1,8}[章节回卷集篇部折].*",
            r"[\s\u{3000}\u{2500}-\u{257F}\u{25c6}\u{25c7}\u{25ce}\u{25b2}\u{25b3}\u{25bd}\u{25bc}\u{25cb}\u{25cf}\u{25a1}\u{25a4}\u{2606}\u{2605}\u{203b}\u{203c}\u{2049}\u{2a2f}\u{2217}\u{2261}\u{005f}\u{002a}\u{0023}\-]{0,10}第[零一二三四五六七八九十百千万亿0-9]{1,8}[章节回卷集篇部折].*",
        ];

        let strict_patterns = [
            all_patterns[0],
            all_patterns[1],
            r"(?i)(chapter|chap|ch|section|sec|part|lesson|unit|volume|vol|module|lecture)[.\-:\s]*[0-9零一二三四五六七八九十百千]+(?:[.\-:\s]+[A-Za-z].*)?[\s\u{3000}]*",
            all_patterns[4],
        ];

        Self {
            combined: Regex::new(&format!(r"\A(?:{})\z", all_patterns.join("|"))).unwrap(),
            combined_strict: Regex::new(&format!(r"\A(?:{})\z", strict_patterns.join("|"))).unwrap(),
        }
    }

    /// 快速预过滤：行首/行内可能构成章节标题的线索才进入正则匹配。
    /// 必须覆盖全部 8 种模式的起点形态（含 "第"、英文前缀、卷、数字/中文数字编号、特殊关键词），
    /// 否则 "VOL.1"、"一、初入江湖" 这类标题会被提前挡掉（与 Kotlin 版无预过滤的行为一致）。
    fn likely_chapter_line(trimmed: &str) -> bool {
        let lower = trimmed.to_lowercase();
        let first = trimmed.chars().next();
        let has_chinese_prefix = trimmed.contains('第');
        let has_english_prefix = lower.contains("chapter")
            || lower.contains("chap")
            || lower.contains("ch");
        let has_volume_prefix = lower.starts_with("vol");
        let has_cn_num_prefix = first.map_or(false, |c| "零一二三四五六七八九十百千万亿".contains(c));
        let has_digit_prefix = first.map_or(false, |c| c.is_ascii_digit());
        let has_special_keyword = ["楔子", "序章", "序言", "引子", "前言", "前奏", "序幕",
                                   "开篇", "开场", "写在前面", "题记", "后记", "尾声", "终章",
                                   "结局", "结语", "番外", "外传", "特别篇", "附录", "附注", "致谢"]
            .iter()
            .any(|&s| trimmed.contains(s));
        has_chinese_prefix
            || has_english_prefix
            || has_volume_prefix
            || has_cn_num_prefix
            || has_digit_prefix
            || has_special_keyword
    }

    fn is_chapter_title(&self, line: &str) -> bool {
        if line.is_empty() {
            return false;
        }
        let trimmed = line.trim();
        // 太长的行不可能是标题（按字符数而非字节数，与 Kotlin 的 length 语义一致）
        if trimmed.chars().count() > 80 {
            return false;
        }
        if !Self::likely_chapter_line(trimmed) {
            return false;
        }
        // 使用单一组合正则，消除 O(n×m) 多次匹配
        self.combined.is_match(trimmed)
    }

    /// 严格模式：只匹配"第X章"完整/宽松版、英文 Chapter 版、特殊关键词类标题。
    /// 排除 volume/numeric/decorated/anywhere 等易误判的宽松规则。
    fn is_strict_chapter_title(&self, line: &str) -> bool {
        if line.is_empty() {
            return false;
        }
        let trimmed = line.trim();
        if trimmed.chars().count() > 80 {
            return false;
        }
        if !Self::likely_chapter_line(trimmed) {
            return false;
        }
        // 使用单一组合正则
        self.combined_strict.is_match(trimmed)
    }

    fn extract_title(&self, line: &str) -> String {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            return trimmed.to_string();
        }
        if trimmed.chars().count() > 80 { return String::new(); }
        if !Self::likely_chapter_line(trimmed) {
            return String::new();
        }
        if self.combined.is_match(trimmed) {
            trimmed.to_string()
        } else if self.combined_strict.is_match(trimmed) {
            // 宽松模式未命中时回退严格模式（覆盖 "Chapter 三" 等严格支持、宽松不支持的格式）
            trimmed.to_string()
        } else {
            String::new()
        }
    }
}

/// 判断章节密度是否可疑（相邻章节间隔 < 3 行占比过高）
fn has_suspicious_density(chapter_breaks: &[(usize, usize)]) -> bool {
    if chapter_breaks.len() < 10 {
        return false;
    }
    let mut short_gap = 0usize;
    let mut total_gap = 0usize;
    for w in chapter_breaks.windows(2) {
        let gap = w[1].0.saturating_sub(w[0].0);
        if gap == 0 {
            continue;
        }
        total_gap += 1;
        if gap < 3 {
            short_gap += 1;
        }
    }
    // 超过 30% 的相邻章节间隔 < 3 行，疑似误判
    total_gap > 0 && short_gap * 100 > total_gap * 30
}

/// 清理标题中的装饰字符
fn clean_title(title: &str) -> String {
    static RE_LEAD: Lazy<Regex> = Lazy::new(|| Regex::new(r"^[【\[\-―※（(]+").unwrap());
    static RE_TRAIL: Lazy<Regex> = Lazy::new(|| Regex::new(r"[】\]\-―※）)]+$").unwrap());
    static RE_END: Lazy<Regex> = Lazy::new(|| Regex::new(r"[（(][完上中下续终]?[）)]$").unwrap());
    static RE_SPACES: Lazy<Regex> = Lazy::new(|| Regex::new(r"[\s\u{3000}]+").unwrap());
    let mut cleaned = title.trim().to_string();
    cleaned = RE_LEAD.replace_all(&cleaned, "").to_string();
    cleaned = RE_TRAIL.replace_all(&cleaned, "").to_string();
    cleaned = RE_END.replace_all(&cleaned, "").to_string();
    cleaned = RE_SPACES.replace_all(&cleaned, " ").to_string();
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

/// 最大文件尺寸 (150MB) — 防止 OOM，支持大型学术/教材类 EPUB
const MAX_FILE_SIZE: u64 = 150 * 1024 * 1024;

/// 解析 TXT 文件
pub fn parse_txt(file_path: &str, forced_encoding: Option<&str>) -> Result<TxtParseResult, String> {
    // ✅ 安全检查：验证文件大小
    let metadata = fs::metadata(file_path)
        .map_err(|e| format!("读取文件元数据失败: {}", e))?;
    if metadata.len() > MAX_FILE_SIZE {
        return Err(format!(
            "文件过大: {} bytes (最大允许 {} MB)",
            metadata.len(),
            MAX_FILE_SIZE / 1024 / 1024
        ));
    }
    
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
    let (full_text, encoding) = try_decode(bytes, &encoding, file_path)?;

    // 行偏移构建
    let line_offsets = build_line_offsets(&full_text);
    let line_count = line_offsets.len().saturating_sub(1);

    let patterns = ChapterPatterns::new();

    // 检测章节标题
    let mut chapter_breaks: Vec<(usize, usize)> = Vec::new(); // (start_line, end_line)
    let mut chapter_titles: Vec<Option<String>> = Vec::new();

    let mut detected_count = 0usize;
    for li in 0..line_count {
        let line_text = extract_line(&full_text, &line_offsets, li);
        if patterns.is_chapter_title(&line_text) {
            detected_count += 1;
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

    // ★ 后处理：如果检测到的章节数量异常多，或相邻章节间隔过少，
    // 疑似正文里 "第一章" 字样造成大量误判。保留宽松结果，用严格模式重扫；
    // 仅当严格模式标题数显著减少（确认误判）时才采用严格结果，
    // 避免 200+ 章的长篇小说（网文常见）被误切后丢失 "VOL.1" 等宽松格式标题。
    if detected_count > 200 || has_suspicious_density(&chapter_breaks) {
        let loose_breaks = chapter_breaks.clone();
        let loose_titles = chapter_titles.clone();
        chapter_breaks.clear();
        chapter_titles.clear();
        for li in 0..line_count {
            let line_text = extract_line(&full_text, &line_offsets, li);
            if patterns.is_strict_chapter_title(&line_text) {
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
        let strict_count = chapter_breaks.len();
        if strict_count * 10 > loose_breaks.len() * 9 {
            // 严格模式没有显著减少 → 大概率是真实长书，保留宽松结果
            chapter_breaks.clear();
            chapter_titles.clear();
            chapter_breaks.extend(loose_breaks);
            chapter_titles.extend(loose_titles);
        }
    }

    if !chapter_breaks.is_empty() {
        if let Some(last) = chapter_breaks.last_mut() {
            last.1 = line_count;
        }
    }

    // 后处理：合并多行标题。仅当下一行是"短副标题"形态才合并（不含句读/引号/括号标点、
    // 不以句末标点结尾、长度 <= 12），防止 "第X章" 独占行 + 正文紧跟时把正文第一行吞进标题。
    // 注意：带 $ 锚点实现全串匹配，与 Kotlin 的 LOOSE_CHAPTER_PATTERN.matches() 语义一致，
    // 否则 "第二章 初入江湖" 这类已带副标题的标题也会进入合并分支。
    let loose_regex = regex::Regex::new(r"^\s*第[零一二三四五六七八九十百千万亿0-9]{1,8}[章节回卷集篇部折]$").unwrap();
    for i in 0..chapter_titles.len() {
        if let Some(ref title) = chapter_titles[i] {
            if title.chars().count() < 12 && loose_regex.is_match(title) {
                let brk = chapter_breaks[i];
                let first_content_line = brk.0;
                if first_content_line < line_count {
                    let next_line = extract_line(&full_text, &line_offsets, first_content_line);
                    let trimmed_next = next_line.trim();
                    let has_punct = trimmed_next
                        .chars()
                        .any(|c| "。，！？；、：…“”‘’\"'「」『』()（）".contains(c));
                    if !trimmed_next.is_empty()
                        && trimmed_next.chars().count() <= 12
                        && !patterns.is_chapter_title(&trimmed_next)
                        && !trimmed_next.starts_with("[[IMAGE:")
                        && !has_punct
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

/// 尝试用指定编码解码，失败时尝试回退编码。
/// 返回 (解码文本, 实际使用的编码)——回退成功后实际编码可能不同于初值，
/// 调用方必须用返回值而非检测初值，否则下游（进度/续读缓存键等）会用错编码。
fn try_decode(bytes: &[u8], encoding: &str, _file_path: &str) -> Result<(String, String), String> {
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
        if total > 200 && chinese == 0 {
            let ascii_count = text.chars().take(10000).filter(|c| c.is_ascii()).count();
            // 无中文又不是纯 ASCII（< 90%），大概率解码错了
            if ascii_count * 100 < total.max(1) * 90 {
                return try_fallback_decode(bytes, encoding);
            }
        }
        return Ok((text.to_string(), encoding.to_string()));
    }

    // 当前编码失败，尝试回退
    try_fallback_decode(bytes, encoding)
}

fn try_fallback_decode(bytes: &[u8], skip_encoding: &str) -> Result<(String, String), String> {
    for enc_name in encoding::FALLBACK_ENCODINGS {
        if *enc_name == skip_encoding {
            continue;
        }
        if let Some(enc) = encoding_rs::Encoding::for_label(enc_name.as_bytes()) {
            let (text, _, _) = enc.decode(bytes);
            if !text.is_empty() {
                // 报告实际使用的编码
                return Ok((text.to_string(), enc_name.to_string()));
            }
        }
    }
    // 所有编码都失败，UTF-8 兜底
    let (text, _, _) = encoding_rs::UTF_8.decode(bytes);
    Ok((text.to_string(), "UTF-8".to_string()))
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

/// 按字数分割章节（后备方案）。
/// 注意：按**字符数**而非字节数统计（中文 3 字节/字符，用 .len() 会早切一半以上），
/// 与 Kotlin 版 TxtParser 的 `current.length >= charsPerChapter`（UTF-16 单元）语义对齐。
/// 用累加计数器统计字符数，避免每次 chars().count() 从头遍历导致 O(n²)。
fn split_by_size(lines: &[String], chars_per_chapter: usize) -> Vec<Chapter> {
    let mut chapters = Vec::new();
    let mut current = String::new();
    let mut current_chars = 0usize;
    let mut chapter_num = 1usize;
    let mut line_start = 0usize;

    for (i, line) in lines.iter().enumerate() {
        current.push_str(line);
        current.push('\n');
        current_chars += line.chars().count() + 1; // +1 为 '\n'
        if current_chars >= chars_per_chapter {
            chapters.push(Chapter {
                title: format!("第{}段", chapter_num),
                content: current.clone(),
                line_start: Some(line_start),
                line_end: Some(i + 1),
                index: Some(chapter_num - 1),
            });
            current = String::new();
            current_chars = 0;
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
    fn test_split_by_size_chinese_char_count() {
        // 中文 3 字节/字符：按字符数 300 切分，若误用字节数会切成 ~100 字符/段
        // 用 12 行、每行 100 字符 → 共 1200 字符
        let lines: Vec<String> = (0..12).map(|_| "中".repeat(100)).collect();
        let chapters = split_by_size(&lines, 300);
        // 1200 字符 / 300 = 4 段（字符计数正确时）；字节误用时 ~12 段
        assert_eq!(chapters.len(), 4, "应按字符数切分得到 4 段，实际: {}", chapters.len());
    }

    #[test]
    fn test_try_decode_fallback_reports_actual_encoding() {
        // GBK 编码的中文文本，但 forced/检测初值给 UTF-8 → 应回退到 GBK 并报告实际编码
        let gbk_bytes = "你好世界，测试文本。".encode_gbk();
        let (text, used_enc) = try_decode(&gbk_bytes, "UTF-8", "dummy.txt").expect("decode ok");
        assert_eq!(used_enc, "GBK", "回退后应报告实际编码 GBK，得到: {}", used_enc);
        assert_eq!(text, "你好世界，测试文本。");
    }

    #[test]
    fn test_try_decode_no_fallback_reports_input_encoding() {
        // 正确编码时保持初值
        let utf8_bytes = "你好世界".as_bytes();
        let (text, used_enc) = try_decode(utf8_bytes, "UTF-8", "dummy.txt").expect("decode ok");
        assert_eq!(used_enc, "UTF-8");
        assert_eq!(text, "你好世界");
    }

    /// 测试辅助：把 str 按 GBK 编码（避免测试依赖 encoding_rs 暴露）
    trait GbkEncode {
        fn encode_gbk(&self) -> Vec<u8>;
    }
    impl GbkEncode for str {
        fn encode_gbk(&self) -> Vec<u8> {
            use encoding_rs::GBK;
            let (bytes, _, _) = GBK.encode(self);
            bytes.into_owned()
        }
    }

    #[test]
    fn test_clean_title() {
        assert_eq!(clean_title("【第一章】"), "第一章");
        assert_eq!(clean_title("第一章　初入江湖"), "第一章 初入江湖");
    }

    #[test]
    fn test_no_false_positives() {
        let patterns = ChapterPatterns::new();
        // 正文里包含"第一章"字样的普通行，不应被误判
        assert!(!patterns.is_chapter_title(
            "这本书的主题是第二章至第五章，主线情节令人回味无穷"
        ));
        assert!(!patterns.is_chapter_title("这是一个普通的正文段落"));
        assert!(!patterns.is_chapter_title("1. 首先，我们需要准备好所有材料"));
        assert!(!patterns.is_chapter_title("3. 然后，将混合物搅拌均匀"));
    }

    #[test]
    fn test_special_with_subtitle() {
        let patterns = ChapterPatterns::new();
        assert!(patterns.is_chapter_title("楔子 暗夜降临"));
        assert!(patterns.is_chapter_title("番外 午夜"));
        assert!(patterns.is_chapter_title("后记 写在最后"));
        assert!(patterns.is_chapter_title("附录 参考书目"));
    }

    #[test]
    fn test_suspicious_density() {
        // 间隔很短（1 行）的连续章节，应被识别为"密度可疑"
        let breaks: Vec<(usize, usize)> = (0..20).map(|i| (i * 2, i * 2 + 1)).collect();
        assert!(has_suspicious_density(&breaks));

        // 间隔较大（100 行）的，不应被识别为可疑
        let breaks2: Vec<(usize, usize)> = (0..20).map(|i| (i * 100, i * 100 + 1)).collect();
        assert!(!has_suspicious_density(&breaks2));
    }

    #[test]
    fn test_fullmatch_semantics() {
        // is_match 必须等价于 Kotlin 的 matches()（全串匹配），不能只匹配前缀
        let patterns = ChapterPatterns::new();
        // 中文后缀不满足 ENG 模式的 [A-Za-z] 后缀要求，应拒绝
        assert!(!patterns.is_chapter_title("Chapter 12 是一个很好的章节，让人回味"));
        // 正文行（"第X章"仅出现在句中）不得误判
        assert!(!patterns.is_chapter_title("他说到了第三章的内容"));
        assert!(!patterns.is_chapter_title("这一章讲的是第一章内容"));
        // 真实标题仍应匹配
        assert!(patterns.is_chapter_title("Chapter 12: The Beginning"));
        assert!(patterns.is_chapter_title("第一章 初入江湖"));
        // 英文长句后缀与 Kotlin 的 matches() 语义一致：整行都是英文后缀时 ENG 模式整行匹配
        assert!(patterns.is_chapter_title("Chapter 12: The Beginning is a long sentence here"));
    }

    #[test]
    fn test_num_pattern_no_false_positive() {
        // 正文数字列表不应被误判为章节
        let patterns = ChapterPatterns::new();
        assert!(!patterns.is_chapter_title("1.他们来了"));
        assert!(!patterns.is_chapter_title("12 他们是英雄"));
        assert!(!patterns.is_chapter_title("3.然后搅拌均匀"));
        // 真正的数字编号标题仍应匹配
        assert!(patterns.is_chapter_title("一、初入江湖"));
        assert!(patterns.is_chapter_title("12、刀光剑影生死一线之间"));
        assert!(patterns.is_chapter_title("一百零八、群雄逐鹿"));
        // VOLUME 标题不应被预过滤挡掉
        assert!(patterns.is_chapter_title("VOL.1"));
        assert!(patterns.is_chapter_title("Vol.1 天玄大陆"));
    }

    #[test]
    fn test_strict_extract_title() {
        // 严格模式支持、宽松模式不支持的格式，提取标题时应回退严格模式
        let patterns = ChapterPatterns::new();
        assert_eq!(patterns.extract_title("Chapter 三"), "Chapter 三");
        assert_eq!(patterns.extract_title("Chapter 十二"), "Chapter 十二");
        assert!(patterns.is_strict_chapter_title("Chapter 三"));
    }

    #[test]
    fn test_long_title_chars_not_bytes() {
        // 长度上限按字符数而非字节数（30 个汉字 = 90 字节，应仍可识别）
        let patterns = ChapterPatterns::new();
        let long = format!("第{}章{}", "一", "初".repeat(30));
        assert!(patterns.is_chapter_title(&long));
    }

    #[test]
    fn test_end_to_end_merge_behavior() {
        // 端到端：验证多行标题合并不吞正文、副标题正常合并
        let text = "\
第一章
他推开门走了进去。

第二章 初入江湖
江湖传言，风云再起。

第三章
暗夜降临
";
        let result = parse_txt_bytes(text.as_bytes(), "test.txt", None).unwrap();
        let chapters = &result.chapters;
        assert_eq!(chapters.len(), 3);
        // 第一章：独占一行 + 正文紧跟，正文首行不得被吞进标题
        assert_eq!(chapters[0].title, "第一章");
        assert!(chapters[0].content.contains("他推开门走了进去。"));
        // 第二章：标题行内联副标题
        assert_eq!(chapters[1].title, "第二章 初入江湖");
        // 第三章：下一行是短无标点副标题 → 合并
        assert_eq!(chapters[2].title, "第三章 暗夜降临");
    }

    #[test]
    fn test_merge_not_applied_to_title_with_subtitle() {
        // 已带副标题的标题（"第二章 初入江湖"）不得再进入合并分支，
        // 否则下一行短正文会被误拼进标题（回归：loose_regex 曾缺 $ 锚点）
        let text = "\
第二章 初入江湖
暗夜
正文开始。
";
        let result = parse_txt_bytes(text.as_bytes(), "test.txt", None).unwrap();
        assert_eq!(result.chapters.len(), 1);
        assert_eq!(result.chapters[0].title, "第二章 初入江湖");
        assert!(result.chapters[0].content.contains("正文开始。"));
    }

    #[test]
    fn test_end_to_end_strict_keep_loose() {
        // 端到端：200+ 章的长书（含 VOLUME 卷标题）不应被误切到严格模式丢标题
        let mut text = String::new();
        for i in 1..=220 {
            text.push_str(&format!("第{}章 标题{}\n内容若干行\n\n", i, i));
        }
        text.push_str("VOL.2 新的征程\n这里是第二卷的正文内容\n");
        let result = parse_txt_bytes(text.as_bytes(), "long.txt", None).unwrap();
        // 宽松模式结果被保留：221 个标题（220 章 + 1 卷）
        assert_eq!(result.chapters.len(), 221);
        assert!(result.chapters.iter().any(|c| c.title == "VOL.2 新的征程"));
    }
}
