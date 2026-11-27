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
/// [P1-#35/36] 移除 content 字段：Java 侧按 LineMetric（text+坐标）逐行绘制，
/// 每页全文只是额外内存（整章文本逐页重复）+ 额外 bincode 字节，无消费者
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PageData {
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
    /// 段落样式类型（与 Java Chapter.PARA_* 一致：0 正文 1 H1 2 H2 3 H3 4 引文 5 图片标记段 [P1-#26]）。
    /// Java 绘制端据此套用字号倍数/伪粗体；x 坐标已按样式居中/缩进计算。
    pub para_type: i32,
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

/// 空布局结果（参数校验失败 / 提前返回；Kotlin 端将空结果视为 Rust 布局失败）
fn empty_layout_result() -> LayoutResult {
    LayoutResult {
        pages: Vec::new(),
        total_lines: 0,
        total_pages: 0,
        elapsed_ns: 0,
    }
}

/// 执行文本布局（完整分页 + 精确坐标 + 基准计时）
///
/// `para_types`：段落样式数组，与 `text.split('\n')` 的段落一一对应
/// （0 正文 1 H1 2 H2 3 H3 4 引文，缺省为正文）。TXT 书传空切片即可。
#[inline(always)]
pub fn layout_text(
    text: &str,
    para_types: &[i32],
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

    // [P0-#9] 参数健全性检查：NaN/Inf 或非正几何直接返回空布局（调用方回退 Java 布局）。
    // 原检查只有 <= 0：NaN 与任何值比较恒为 false，会流入热循环使 break 条件恒不成立、
    // 行高变 NaN → 单行无限累积或坐标溢出
    if !max_width_px.is_finite() || !max_height_px.is_finite() || !font_size_px.is_finite()
        || !line_spacing.is_finite()
        || !paragraph_spacing.is_finite()
        || !padding_left.is_finite()
        || !padding_top.is_finite()
        || max_width_px <= 0.0
        || max_height_px <= 0.0
        || font_size_px <= 0.0
        || text.is_empty()
    {
        return empty_layout_result();
    }

    let content_width = max_width_px - padding_left;
    // [P0-#9] content_width 非正（padding_left >= max_width_px）→ 几何非法，返回空布局
    if content_width <= 0.0 {
        return empty_layout_result();
    }
    // 行高按段落样式逐行计算（FlattenedLine.line_h），段距 = 字体大小 × 段距倍数（独立于行距）
    let para_extra = font_size_px * paragraph_spacing.max(0.0);
    // ========== 第一步：按 \n 拆分为段落 ==========
    let paragraphs: Vec<&str> = text.split('\n').collect();

    // ========== 第二步：逐段换行，生成带坐标的行列表 ==========
    struct FlattenedLine {
        text: String,
        x: f32,
        width: f32,
        is_paragraph_end: bool,
        is_first_in_paragraph: bool,
        /// 段落样式类型（透传到 LineMetric）
        para_type: i32,
        /// 该行实际行高（H1 等段落样式有独立行高）
        line_h: f32,
    }

    let mut flat_lines: Vec<FlattenedLine> = Vec::with_capacity(paragraphs.len() * 2);
    let mut line_height_count = 0usize;

    for (pi, para) in paragraphs.iter().enumerate() {
        if para.is_empty() {
            if let Some(last) = flat_lines.last_mut() {
                last.is_paragraph_end = true;
            }
            continue;
        }

        // [P1-2/P2-6] 段落样式（与 Java 路径 ReaderView:622-643 对齐）：
        // H1 字号×1.8/行距1.2/段距×1.5/居中粗体；H2 ×1.5/段距×1.2/粗体；
        // H3 ×1.2/粗体；引文 ×0.9/左右缩进 2em；[P1-#26] 图片标记段(5) 正文样式
        // [P1-#10] para_types 长度不匹配 / 缺失 → unwrap_or 回退 0（正文），不会越界 panic
        let ptype = para_types.get(pi).copied().unwrap_or(0);
        let (font_mult, ls_ovr, side_indent, centered): (f32, Option<f32>, f32, bool) = match ptype {
            1 => (1.8_f32, Some(1.2_f32), 0.0_f32, true),
            2 => (1.5_f32, None, 0.0_f32, false),
            3 => (1.2_f32, None, 0.0_f32, false),
            4 => (0.9_f32, None, font_size_px * 2.0_f32, false),
            5 => (1.0_f32, None, 0.0_f32, false),
            _ => (1.0_f32, None, 0.0_f32, false),
        };
        let p_font = font_size_px * font_mult;
        let p_line_h = match ls_ovr {
            Some(m) => p_font * m,
            None => p_font * line_spacing,
        };
        // 首行缩进与 Java 对齐：仅正文段落缩进 1em（原 1.6em 且全段落）
        let p_indent = if first_line_indent && ptype == 0 { p_font } else { 0.0 };
        // [P0-#9] avail_w 下限保护：大引文缩进（2em×2）可能使 content_width 非正 →
        // 换行条件恒真、x 坐标为负。钳制最小 1px
        let avail_w = (content_width - side_indent * 2.0_f32).max(1.0);
        let x_base = padding_left + side_indent;
        // [P2-2] 行字节容量 ≈ 每行字符数 × 3（UTF-8 CJK 3 字节上限），
        // 替代原「每行都预分配整段字节数」的过度分配（单核 Nook 热循环分配削减）
        let line_cap = if p_font > 0.0 {
            ((avail_w.max(1.0) / p_font) * 3.0_f32 + 8.0_f32) as usize
        } else {
            para.len()
        };

        let mut line_builder = String::with_capacity(line_cap.min(para.len()));
        let mut line_width = 0.0_f32;
        let mut is_first_char = true;
        let mut is_first_line_in_para = true;

        for ch in para.chars() {
            let cw = p_font * char_width_em(ch);
            let current_indent = if is_first_char { p_indent } else { 0.0 };

            if line_width + cw > avail_w - current_indent && !line_builder.is_empty() {
                let line_x = if centered {
                    x_base + (avail_w - line_width) / 2.0_f32
                } else {
                    x_base + current_indent
                };
                // Save current line and reset builder using mem::replace (avoids clone).
                // [review-#34] 每次换行仍需为下一行重新分配：行字符串是 owned 并 push 进
                // flat_lines，无法复用单一 buffer；容量按 line_cap（≈每行字节数）钳制，
                // 已是最小分配，接受现状不做进一步优化
                let line_text = std::mem::replace(&mut line_builder, String::with_capacity(line_cap.min(para.len())));
                flat_lines.push(FlattenedLine {
                    text: line_text,
                    x: line_x,
                    width: line_width,
                    is_paragraph_end: false,
                    is_first_in_paragraph: is_first_line_in_para,
                    para_type: ptype,
                    line_h: p_line_h,
                });
                line_height_count += 1;
                // line_builder is already empty after replace, no need to clear
                line_width = 0.0;
                is_first_char = true;
                is_first_line_in_para = false;
            }

            if is_first_char {
                line_width += p_indent;
            }
            line_builder.push(ch);
            line_width += cw;
            is_first_char = false;
        }

        // 刷出段落最后一行
        if !line_builder.is_empty() {
            let line_x = if centered {
                x_base + (avail_w - line_width) / 2.0_f32
            } else if is_first_line_in_para {
                x_base + p_indent
            } else {
                x_base
            };
            flat_lines.push(FlattenedLine {
                text: std::mem::take(&mut line_builder),
                x: line_x,
                width: line_width,
                is_paragraph_end: true,
                is_first_in_paragraph: is_first_line_in_para,
                para_type: ptype,
                line_h: p_line_h,
            });
            line_height_count += 1;
        }
        // [P1-#11] 移除原 else-if 死分支（!flat_lines.is_empty() && para.len() != 0）：
        // 到达此处时 para 非空（空 para 已在循环头部 continue）→ 上方 chars() 循环
        // 必向 line_builder 推入字符 → line_builder.is_empty() 恒假，该分支不可达
    }

    // ========== 第三步：按高度分页，构建 LineMetric ==========
    let mut pages: Vec<PageData> = Vec::new();
    // [P1-#35/36] page_builder 移除（content 字段已删）
    let mut page_metrics: Vec<LineMetric> = Vec::new();
    let mut page_line_count = 0usize;
    let mut page_height = 0.0_f32;
    let mut line_index = 0usize;

    while line_index < flat_lines.len() {
        let line = &flat_lines[line_index];
        // [P2-6] 行高随段落样式（H1 独立行距）；段距同按类型
        let line_extra = match line.para_type {
            1 => font_size_px * 1.5_f32,
            2 => font_size_px * 1.2_f32,
            _ => para_extra,
        };
        let effective_height = if line.is_paragraph_end {
            line.line_h + line_extra
        } else {
            line.line_h
        };

        // 检查是否超出页面（至少一行）
        if page_height + effective_height > max_height_px && page_line_count > 0 {
            // [P2-2] mem::take 零拷贝移交（原 clone 后 clear：每页边界深拷贝全部行指标）
            pages.push(PageData {
                line_count: page_line_count,
                lines: std::mem::take(&mut page_metrics),
            });
            page_height = 0.0;
            page_line_count = 0;
            // 不回退 line_index，继续处理当前行
        } else {
            page_metrics.push(LineMetric {
                text: line.text.clone(),
                x: line.x,
                y: padding_top + page_height,
                width: line.width,
                height: effective_height,
                is_paragraph_end: line.is_paragraph_end,
                is_first_in_paragraph: line.is_first_in_paragraph,
                para_type: line.para_type,
            });
            page_line_count += 1;
            page_height += effective_height;
            line_index += 1;
        }
    }

    // 最后一页
    if page_line_count > 0 {
        pages.push(PageData {
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
    para_types: Vec<i32>,
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
        text, &para_types, max_width_px, max_height_px,
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
            text, &[], params.max_width_px, params.max_height_px,
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
        let result = layout_text("Hello World", &[], 200.0, 400.0, 16.0, 1.5, 1.8, false, 10.0, 10.0);
        assert!(!result.pages.is_empty());
        assert!(result.total_lines > 0);
    }

    #[test]
    fn test_chinese_text() {
        let text = "这是一段中文测试文本，用于验证 Rust 布局引擎的分行和分页逻辑。";
        let result = layout_text(text, &[], 200.0, 400.0, 16.0, 1.5, 1.8, false, 10.0, 10.0);
        assert!(!result.pages.is_empty());
        assert!(result.total_lines > 0);
        assert!(result.elapsed_ns > 0);
    }

    #[test]
    fn test_empty_text() {
        let result = layout_text("", &[], 200.0, 400.0, 16.0, 1.5, 1.8, false, 10.0, 10.0);
        assert_eq!(result.total_lines, 0);
        assert_eq!(result.total_pages, 0);
    }

    #[test]
    fn test_line_metric_has_coordinates() {
        let text = "测试坐标";
        let result = layout_text(text, &[], 200.0, 400.0, 16.0, 1.5, 1.8, false, 10.0, 10.0);
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
        let binary = layout_text_binary(text, Vec::new(), 200.0, 400.0, 16.0, 1.5, 1.8, false, 10.0, 10.0);
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
        let result = layout_text(&text, &[], 500.0, max_height, font_size, line_spacing, 1.8, false, 10.0, 10.0);
        assert!(result.total_pages >= 2, "should have at least 2 pages, got {}", result.total_pages);
    }

    #[test]
    fn test_large_text_performance() {
        let text = "测试".repeat(5000);
        let result = layout_text(&text, &[], 300.0, 800.0, 16.0, 1.5, 1.8, false, 10.0, 10.0);
        assert!(result.total_pages > 0);
        // 5000 中文字符的处理时间通常 < 50ms
        assert!(result.elapsed_ns < 50_000_000, "took {}ns", result.elapsed_ns);
    }

    #[test]
    fn test_y_monotonic() {
        let text = "第一行\n第二行\n第三行\n第四行\n第五行";
        let result = layout_text(text, &[], 500.0, 400.0, 16.0, 1.5, 1.8, false, 10.0, 10.0);
        if !result.pages.is_empty() {
            let lines = &result.pages[0].lines;
            for i in 1..lines.len() {
                assert!(lines[i].y > lines[i-1].y, "y should be monotonic increasing");
            }
        }
    }

    // [P0-#9] 回归：NaN/Inf 参数必须被拒绝（原检查对 NaN 无效）
    #[test]
    fn test_nan_or_inf_params_rejected() {
        let r = layout_text("测试", &[], f32::NAN, 400.0, 16.0, 1.5, 1.8, false, 10.0, 10.0);
        assert_eq!(r.total_pages, 0);
        let r = layout_text("测试", &[], 200.0, 400.0, f32::INFINITY, 1.5, 1.8, false, 10.0, 10.0);
        assert_eq!(r.total_pages, 0);
        let r = layout_text("测试", &[], 200.0, f32::NEG_INFINITY, 16.0, 1.5, 1.8, false, 10.0, 10.0);
        assert_eq!(r.total_pages, 0);
    }

    // [P0-#9] 回归：padding_left >= max_width → content_width 非正 → 空布局
    #[test]
    fn test_padding_eats_full_width_rejected() {
        let r = layout_text("测试\n第二段", &[], 100.0, 400.0, 16.0, 1.5, 1.8, false, 100.0, 10.0);
        assert_eq!(r.total_pages, 0);
        assert_eq!(r.total_lines, 0);
    }

    // [P1-#10] 回归：para_types 长度不匹配 / 溢出 → unwrap_or 回退 0，不 panic，
    // 且样式缺失不影响总行数
    #[test]
    fn test_para_types_mismatch_safe() {
        let text = "第一行文字\n第二行文字\n第三行文字";
        let r_short = layout_text(text, &[1, 2], 300.0, 400.0, 16.0, 1.5, 1.8, false, 10.0, 10.0);
        assert!(r_short.total_pages > 0, "布局不应为空");
        let r_long = layout_text(text, &[1, 2, 3, 4, 5], 300.0, 400.0, 16.0, 1.5, 1.8, false, 10.0, 10.0);
        assert_eq!(r_short.total_lines, r_long.total_lines, "para_types 不匹配不应改变总行数");
    }

    // [P1-#26] PARA_IMAGE(5) 图片标记段回退正文样式（行高/总行数与正文一致）
    #[test]
    fn test_para_image_style_falls_to_body() {
        let text = "普通段落文本\n图片标记段";
        let r_body = layout_text(text, &[], 300.0, 400.0, 16.0, 1.5, 1.8, false, 10.0, 10.0);
        let r_img = layout_text(text, &[0, 5], 300.0, 400.0, 16.0, 1.5, 1.8, false, 10.0, 10.0);
        assert_eq!(r_body.total_lines, r_img.total_lines, "图片标记段按正文行高，总行数不变");
    }
}