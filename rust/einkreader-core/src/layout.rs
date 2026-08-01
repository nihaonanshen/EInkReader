//! 文本布局引擎
//!
//! 模拟 Android Paint.measureText() 的宽度估算，无需实际字体度量。
//! 按 CJK / ASCII / 全角标点分类赋予不同 em 宽度，返回分页结果。
//!
//! 输出：bincode 二进制（高性能），包含精确坐标。

use serde::{Deserialize, Serialize};
use std::time::Instant;

/// 布局结果
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LayoutResult {
    /// 分页列表
    pub pages: Vec<PageData>,
    /// 总行数
    pub total_lines: usize,
    /// 总页数
    pub total_pages: usize,
    /// 耗时（纳秒），用于基准测试
    pub elapsed_ns: u64,
}

/// 单页数据
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PageData {
    /// 页面内容，行与行之间用 \n 分隔（兼容旧版）
    pub content: String,
    /// 该页包含的行数
    pub line_count: usize,
    /// 精确行指标（包含坐标，Java 直接使用）
    pub lines: Vec<LineMetric>,
}

/// 单行精确指标（坐标由 Rust 计算，Java 直接绘制）
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LineMetric {
    pub text: String,
    pub x: f32,
    pub y: f32,
    pub width: f32,
    pub height: f32,
    pub is_paragraph_end: bool,
    pub is_first_in_paragraph: bool,
}

/// 估算字符宽度（单位：em，相对于 font_size）
/// CJK / 全角 → 1.0，ASCII / 半角 → 0.5，零宽 → 0.0
#[inline]
fn char_width_em(c: char) -> f32 {
    match c {
        '\u{4E00}'..='\u{9FFF}' | '\u{3400}'..='\u{4DBF}'
        | '\u{20000}'..='\u{2A6DF}' | '\u{2A700}'..='\u{2B73F}'
        | '\u{2B740}'..='\u{2B81F}' | '\u{2B820}'..='\u{2CEAF}'
        | '\u{2CEB0}'..='\u{2EBEF}' | '\u{F900}'..='\u{FAFF}' => 1.0,
        '\u{FF01}'..='\u{FF60}' | '\u{3000}'..='\u{303F}' | '\u{FE30}'..='\u{FE4F}' => 1.0,
        '\u{200B}'..='\u{200F}' | '\u{FEFF}' | '\u{2060}' => 0.0,
        _ => 0.5,
    }
}

/// 执行文本布局（完整分页 + 精确坐标 + 基准计时）
#[inline(always)]
pub fn layout_text(
    text: &str,
    max_width_px: f32,
    max_height_px: f32,
    font_size_px: f32,
    line_spacing: f32,
    paragraph_spacing: f32,
    first_line_indent: bool,
    padding_left: f32,
    padding_top: f32,
) -> LayoutResult {
    let start = Instant::now();

    if max_width_px <= 0.0 || max_height_px <= 0.0 || font_size_px <= 0.0 || text.is_empty() {
        return LayoutResult {
            pages: Vec::new(),
            total_lines: 0,
            total_pages: 0,
            elapsed_ns: 0,
        };
    }

    let content_width = max_width_px - padding_left;
    let line_height = font_size_px * line_spacing;
    let para_extra = font_size_px * (paragraph_spacing - line_spacing).max(0.0);
    let indent_px = if first_line_indent {
        font_size_px * 1.6
    } else {
        0.0
    };

    // ========== 第一步：按 \n 拆分为段落 ==========
    let paragraphs: Vec<&str> = text.split('\n').collect();

    // ========== 第二步：逐段换行，生成带坐标的行列表 ==========
    struct FlattenedLine {
        text: String,
        x: f32,
        width: f32,
        is_paragraph_end: bool,
        is_first_in_paragraph: bool,
    }

    let mut flat_lines: Vec<FlattenedLine> = Vec::with_capacity(paragraphs.len() * 2);
    let mut line_height_count = 0usize;

    for para in paragraphs {
        if para.is_empty() {
            if let Some(last) = flat_lines.last_mut() {
                last.is_paragraph_end = true;
            }
            continue;
        }

        let mut line_builder = String::with_capacity(para.len());
        let mut line_width = 0.0_f32;
        let mut is_first_char = true;
        let mut is_first_line_in_para = true;

        for ch in para.chars() {
            let cw = font_size_px * char_width_em(ch);
            let current_indent = if is_first_char && first_line_indent {
                indent_px
            } else {
                0.0
            };

            if line_width + cw > content_width - current_indent && !line_builder.is_empty() {
                // Save current line and reset builder using mem::replace (avoids clone)
                let line_text = std::mem::replace(&mut line_builder, String::with_capacity(para.len()));
                flat_lines.push(FlattenedLine {
                    text: line_text,
                    x: padding_left + current_indent,
                    width: line_width,
                    is_paragraph_end: false,
                    is_first_in_paragraph: is_first_line_in_para,
                });
                line_height_count += 1;
                // line_builder is already empty after replace, no need to clear
                line_width = 0.0;
                is_first_char = true;
                is_first_line_in_para = false;
            }

            if is_first_char && first_line_indent {
                line_width += indent_px;
            }
            line_builder.push(ch);
            line_width += cw;
            is_first_char = false;
        }

        // 刷出段落最后一行
        if !line_builder.is_empty() {
            flat_lines.push(FlattenedLine {
                text: line_builder.clone(),
                x: padding_left + if is_first_line_in_para && first_line_indent { indent_px } else { 0.0 },
                width: line_width,
                is_paragraph_end: true,
                is_first_in_paragraph: is_first_line_in_para,
            });
            line_height_count += 1;
        } else if !flat_lines.is_empty() && para.len() != 0 {
            flat_lines.push(FlattenedLine {
                text: String::new(),
                x: padding_left,
                width: 0.0,
                is_paragraph_end: true,
                is_first_in_paragraph: is_first_line_in_para,
            });
            line_height_count += 1;
        }
    }

    // ========== 第三步：按高度分页，构建 LineMetric ==========
    let mut pages: Vec<PageData> = Vec::new();
    let mut page_builder = String::new();
    let mut page_metrics: Vec<LineMetric> = Vec::new();
    let mut page_line_count = 0usize;
    let mut page_height = 0.0_f32;
    let mut line_index = 0usize;

    while line_index < flat_lines.len() {
        let line = &flat_lines[line_index];
        let line_h = line_height;
        let effective_height = if line.is_paragraph_end {
            line_h + para_extra
        } else {
            line_h
        };

        // 检查是否超出页面（至少一行）
        if page_height + effective_height > max_height_px && page_line_count > 0 {
            pages.push(PageData {
                content: page_builder.clone(),
                line_count: page_line_count,
                lines: page_metrics.clone(),
            });
            page_builder.clear();
            page_metrics.clear();
            page_height = 0.0;
            page_line_count = 0;
            // 不回退 line_index，继续处理当前行
        } else {
            if page_line_count > 0 {
                page_builder.push('\n');
            }
            page_builder.push_str(&line.text);
            page_metrics.push(LineMetric {
                text: line.text.clone(),
                x: line.x,
                y: padding_top + page_height,
                width: line.width,
                height: effective_height,
                is_paragraph_end: line.is_paragraph_end,
                is_first_in_paragraph: line.is_first_in_paragraph,
            });
            page_line_count += 1;
            page_height += effective_height;
            line_index += 1;
        }
    }

    // 最后一页
    if page_line_count > 0 {
        pages.push(PageData {
            content: page_builder,
            line_count: page_line_count,
            lines: page_metrics,
        });
    }

    let elapsed = start.elapsed();
    LayoutResult {
        total_lines: line_height_count,
        total_pages: pages.len(),
        pages,
        elapsed_ns: elapsed.as_nanos() as u64,
    }
}

/// 将布局结果序列化为 bincode 二进制
#[inline(always)]
pub fn layout_text_binary(
    text: &str,
    max_width_px: f32,
    max_height_px: f32,
    font_size_px: f32,
    line_spacing: f32,
    paragraph_spacing: f32,
    first_line_indent: bool,
    padding_left: f32,
    padding_top: f32,
) -> Vec<u8> {
    let result = layout_text(
        text, max_width_px, max_height_px,
        font_size_px, line_spacing, paragraph_spacing,
        first_line_indent, padding_left, padding_top,
    );
    bincode::serialize(&result).unwrap_or_default()
}

/// 批量文本布局：对多个文本段应用相同参数，一次性返回所有布局结果
/// texts: 要排版的文本切片（按顺序）
/// params: 布局参数结构体
/// Result: Vec<LayoutResult>, 每个文本对应一个结果
#[allow(dead_code)] // 将通过 JNI 调用
pub fn batch_layout_texts(
    texts: &[&str],
    params: LayoutParams,
) -> Vec<LayoutResult> {
    texts.iter().map(|&text| {
        layout_text(
            text, params.max_width_px, params.max_height_px,
            params.font_size_px, params.line_spacing, params.paragraph_spacing,
            params.first_line_indent, params.padding_left, params.padding_top,
        )
    }).collect()
}

/// 批量文本布局（bincode 序列化版）：
/// 输入 texts 与 params 均为 bincode 序列化，输出 bincode 序列化的 Vec<LayoutResult>
#[allow(dead_code)] // 将通过 JNI 调用
pub fn batch_layout_texts_binary(texts_bin: &[u8], params_bin: &[u8]) -> Vec<u8> {
    let texts: Vec<String> = match bincode::deserialize(texts_bin) {
        Ok(v) => v,
        Err(_) => vec![],
    };
    if texts.is_empty() {
        return bincode::serialize(&Vec::<LayoutResult>::new()).unwrap_or_default();
    }
    let params: LayoutParams = match bincode::deserialize(params_bin) {
        Ok(v) => v,
        Err(_) => LayoutParams {
            max_width_px: 300.0,
            max_height_px: 400.0,
            font_size_px: 16.0,
            line_spacing: 1.5,
            paragraph_spacing: 1.8,
            first_line_indent: false,
            padding_left: 10.0,
            padding_top: 10.0,
        },
    };
    let text_refs: Vec<&str> = texts.iter().map(|s| s.as_str()).collect();
    let results = batch_layout_texts(&text_refs, params);
    bincode::serialize(&results).unwrap_or_default()
}

/// 布局参数容器
#[derive(Clone, Copy, Serialize, Deserialize)]
pub struct LayoutParams {
    pub max_width_px: f32,
    pub max_height_px: f32,
    pub font_size_px: f32,
    pub line_spacing: f32,
    pub paragraph_spacing: f32,
    pub first_line_indent: bool,
    pub padding_left: f32,
    pub padding_top: f32,
}

// ==================== 单元测试 ====================

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_simple_ascii_text() {
        let result = layout_text("Hello World", 200.0, 400.0, 16.0, 1.5, 1.8, false, 10.0, 10.0);
        assert!(!result.pages.is_empty());
        assert!(result.total_lines > 0);
    }

    #[test]
    fn test_chinese_text() {
        let text = "这是一段中文测试文本，用于验证 Rust 布局引擎的分行和分页逻辑。";
        let result = layout_text(text, 200.0, 400.0, 16.0, 1.5, 1.8, false, 10.0, 10.0);
        assert!(!result.pages.is_empty());
        assert!(result.total_lines > 0);
        assert!(result.elapsed_ns > 0);
    }

    #[test]
    fn test_empty_text() {
        let result = layout_text("", 200.0, 400.0, 16.0, 1.5, 1.8, false, 10.0, 10.0);
        assert_eq!(result.total_lines, 0);
        assert_eq!(result.total_pages, 0);
    }

    #[test]
    fn test_line_metric_has_coordinates() {
        let text = "测试坐标";
        let result = layout_text(text, 200.0, 400.0, 16.0, 1.5, 1.8, false, 10.0, 10.0);
        if !result.pages.is_empty() && !result.pages[0].lines.is_empty() {
            let lm = &result.pages[0].lines[0];
            assert!(lm.x >= 0.0, "x should be >= 0");
            assert!(lm.y >= 0.0, "y should be >= 0");
            assert!(!lm.text.is_empty(), "text should not be empty");
        }
    }

    #[test]
    fn test_binary_roundtrip() {
        let text = "二进制序列化测试";
        let binary = layout_text_binary(text, 200.0, 400.0, 16.0, 1.5, 1.8, false, 10.0, 10.0);
        assert!(!binary.is_empty(), "binary should not be empty");
        let deserialized: LayoutResult = bincode::deserialize(&binary).unwrap();
        assert_eq!(deserialized.total_pages, deserialized.pages.len());
        assert!(deserialized.elapsed_ns > 0);
    }

    #[test]
    fn test_page_break() {
        let line_count = 20usize;
        let text = "测试行\n".repeat(line_count);
        let font_size = 16.0;
        let line_spacing = 1.5;
        let max_height = font_size * line_spacing * 10.0;
        let result = layout_text(&text, 500.0, max_height, font_size, line_spacing, 1.8, false, 10.0, 10.0);
        assert!(result.total_pages >= 2, "should have at least 2 pages, got {}", result.total_pages);
    }

    #[test]
    fn test_large_text_performance() {
        let text = "测试".repeat(5000);
        let result = layout_text(&text, 300.0, 800.0, 16.0, 1.5, 1.8, false, 10.0, 10.0);
        assert!(result.total_pages > 0);
        assert!(result.elapsed_ns < 10_000_000, "took {}ns", result.elapsed_ns);
    }

    #[test]
    fn test_y_monotonic() {
        let text = "第一行\n第二行\n第三行\n第四行\n第五行";
        let result = layout_text(text, 500.0, 400.0, 16.0, 1.5, 1.8, false, 10.0, 10.0);
        if !result.pages.is_empty() {
            let lines = &result.pages[0].lines;
            for i in 1..lines.len() {
                assert!(lines[i].y > lines[i-1].y, "y should be monotonic increasing");
            }
        }
    }
}