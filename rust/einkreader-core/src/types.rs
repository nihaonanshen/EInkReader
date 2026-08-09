/// 编码检测结果
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct EncodingResult {
    pub encoding: String,
    pub confidence: f32,
}

/// 章节数据
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct Chapter {
    pub title: String,
    pub content: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub line_start: Option<usize>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub line_end: Option<usize>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub index: Option<usize>,
}

/// TXT 解析结果
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct TxtParseResult {
    pub book_title: String,
    pub encoding: String,
    pub chapters: Vec<Chapter>,
}

/// EPUB 目录项（树形结构）
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct TocItem {
    pub title: String,
    pub href: String,
    pub children: Vec<TocItem>,
}

/// EPUB 解析结果（Phase 3 使用）
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct EpubParseResult {
    pub title: String,
    pub author: String,
    pub encoding: String,
    pub chapters: Vec<EpubChapter>,
    /// 图片数据：path -> base64 encoded bytes
    pub images: std::collections::HashMap<String, String>,
    /// 目录树（来自 NCX 或 nav.xhtml）
    #[serde(default)]
    pub toc_items: Vec<TocItem>,
}

/// EPUB 章节（含图片列表，内容按需加载）
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct EpubChapter {
    pub title: String,
    /// 内容：None 表示尚未加载（懒加载状态），已加载后填入
    pub content: Option<String>,
    pub image_paths: Vec<String>,
    pub paragraph_types: Vec<i32>,
    /// ZIP 内 XHTML 文件路径，用于按需加载（需序列化到客户端）
    pub xhtml_path: Option<String>,
}