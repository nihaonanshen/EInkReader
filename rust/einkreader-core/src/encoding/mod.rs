//! 文本编码自动检测
//!
//! 策略（与 Java EncodingDetector 一致）：
//! 1. BOM 检测（100% 准确）
//! 2. 快速 UTF-8 统计预筛
//! 3. 小样本解码验证（4KB）

use crate::types::EncodingResult;
use encoding_rs::*;

/// 解码验证只用前 4KB，足够区分编码
const SAMPLE_SIZE: usize = 4096;

/// 检测编码（与 Java EncodingDetector.detect() 行为一致）
pub fn detect(data: &[u8]) -> EncodingResult {
    if data.is_empty() {
        return EncodingResult {
            encoding: "UTF-8".into(),
            confidence: 1.0,
        };
    }

    // 1. BOM 检测
    if let Some(bom_enc) = detect_by_bom(data) {
        return EncodingResult {
            encoding: bom_enc.into(),
            confidence: 1.0,
        };
    }

    // 2. 快速 UTF-8 统计预筛
    if is_utf8_by_stats(data) {
        return EncodingResult {
            encoding: "UTF-8".into(),
            confidence: 0.95,
        };
    }

    // 3. 小样本解码验证（只处理前 4KB）
    let sample_len = data.len().min(SAMPLE_SIZE);
    detect_by_decoding(&data[..sample_len])
}

/// 通过 BOM 检测编码
fn detect_by_bom(data: &[u8]) -> Option<&'static str> {
    if data.len() >= 3 && data[0] == 0xEF && data[1] == 0xBB && data[2] == 0xBF {
        return Some("UTF-8");
    }
    if data.len() >= 4 && data[0] == 0xFF && data[1] == 0xFE && data[2] == 0x00 && data[3] == 0x00 {
        return Some("UTF-32LE");
    }
    if data.len() >= 4 && data[0] == 0x00 && data[1] == 0x00 && data[2] == 0xFE && data[3] == 0xFF {
        return Some("UTF-32BE");
    }
    if data.len() >= 2 && data[0] == 0xFF && data[1] == 0xFE {
        return Some("UTF-16LE");
    }
    if data.len() >= 2 && data[0] == 0xFE && data[1] == 0xFF {
        return Some("UTF-16BE");
    }
    None
}

/// 快速统计预筛：只用于确认 UTF-8
/// 扫描数据中的 3 字节和 4 字节 UTF-8 序列，足够多就返回 true
fn is_utf8_by_stats(data: &[u8]) -> bool {
    let limit = data.len().min(32768);
    if limit < 3 {
        return false;
    }

    let mut utf8_count = 0;
    let mut i = 0;

    while i < limit - 3 {
        let b = data[i] as u8;
        if b < 0x80 {
            i += 1;
            continue;
        }

        // 3 字节 UTF-8（中文最常见的编码形式）
        if b >= 0xE0 && b <= 0xEF {
            if (data[i + 1] & 0xC0) == 0x80 && (data[i + 2] & 0xC0) == 0x80 {
                utf8_count += 1;
                i += 3;
                continue;
            }
        }
        // 4 字节 UTF-8
        else if b >= 0xF0 && b <= 0xF7 {
            if (data[i + 1] & 0xC0) == 0x80
                && (data[i + 2] & 0xC0) == 0x80
                && (data[i + 3] & 0xC0) == 0x80
            {
                utf8_count += 1;
                i += 4;
                continue;
            }
        }
        i += 1;
    }

    utf8_count > 10
}

/// 逐个编码尝试解码，选生成有效汉字最多的
fn detect_by_decoding(sample: &[u8]) -> EncodingResult {
    // UTF-8 最常用，先试
    let utf8_score = score_encoding(sample, &UTF_8);
    if utf8_score > 20 {
        return EncodingResult {
            encoding: "UTF-8".into(),
            confidence: 0.9,
        };
    }

    let candidates: [(&str, &encoding_rs::Encoding); 6] = [
        ("GBK", GBK),
        ("GB18030", GB18030),
        ("Big5", BIG5),
        ("GB2312", GBK), // GB2312 用 GBK 编码器近似
        ("UTF-16LE", UTF_16LE),
        ("UTF-16BE", UTF_16BE),
    ];

    let mut best = "UTF-8";
    let mut best_score = utf8_score.max(0);

    for (name, enc) in &candidates {
        let score = score_encoding(sample, enc);
        if score > best_score {
            best_score = score;
            best = name;
        }
    }

    EncodingResult {
        encoding: best.to_string(),
        confidence: if best_score > 0 {
            (best_score as f32 / 100.0).min(0.95)
        } else {
            0.3
        },
    }
}

/// 用指定编码解码并打分
fn score_encoding(data: &[u8], encoding: &'static encoding_rs::Encoding) -> i32 {
    let (result, _, had_errors) = encoding.decode(data);
    if had_errors {
        // 有解码错误，分数打折
        let score = score_text(&result);
        if score > 0 {
            score / 2
        } else {
            -1
        }
    } else {
        score_text(&result)
    }
}

/// 对解码文本评分：汉字 +3，中文标点 +1
fn score_text(text: &str) -> i32 {
    let mut score = 0;
    for c in text.chars() {
        if c >= '\u{4E00}' && c <= '\u{9FFF}' {
            score += 3;
        } else if matches!(
            c,
            '\u{3001}'
                | '\u{3002}'
                | '\u{FF0C}'
                | '\u{FF1A}'
                | '\u{FF1B}'
                | '\u{201C}'
                | '\u{201D}'
                | '\u{2018}'
                | '\u{2019}'
        ) {
            score += 1;
        }
    }
    score
}

/// 编码回退列表，与 Java FALLBACK_ENCODINGS 一致
pub const FALLBACK_ENCODINGS: &[&str] = &["GBK", "GB18030", "UTF-8", "Big5", "GB2312"];

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_bom_utf8() {
        let data = b"\xEF\xBB\xBFHello, World!";
        assert_eq!(detect_by_bom(data), Some("UTF-8"));
    }

    #[test]
    fn test_bom_utf16le() {
        let data = b"\xFF\xFE\x00\x00";
        assert_eq!(detect_by_bom(data), Some("UTF-32LE"));
    }

    #[test]
    fn test_empty() {
        let result = detect(b"");
        assert_eq!(result.encoding, "UTF-8");
    }

    #[test]
    fn test_utf8_chinese() {
        // "你好世界" in UTF-8
        let data = "你好世界".as_bytes();
        let result = detect(data);
        assert_eq!(result.encoding, "UTF-8");
    }

    #[test]
    fn test_utf8_stats_positive() {
        // Create data with many 3-byte UTF-8 sequences
        let mut data = Vec::new();
        for _ in 0..20 {
            data.extend_from_slice("中".as_bytes()); // 3-byte UTF-8
        }
        assert!(is_utf8_by_stats(&data));
    }

    #[test]
    fn test_utf8_stats_negative() {
        let data = b"Hello, World! This is plain ASCII text without any multibyte sequences.";
        assert!(!is_utf8_by_stats(data));
    }

    #[test]
    fn test_score_encoding() {
        // UTF-8 "你好" should score well with UTF_8
        let text = "你好世界测试文本";
        let score = score_encoding(text.as_bytes(), UTF_8);
        assert!(score > 0);
    }
}
