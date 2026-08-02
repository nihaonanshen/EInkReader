//! OPF 文件解析：书籍元数据 + manifest + spine

use std::collections::HashMap;
use std::fs;
use std::io::Read;
use quick_xml::events::Event;
use quick_xml::Reader;
use zip::ZipArchive;

/// OPF 解析结果
#[derive(Default)]
pub(super) struct OpfResult {
    pub(super) title: String,
    pub(super) author: String,
    pub(super) encoding: String,
    pub(super) ncx_href: Option<String>,
    pub(super) nav_xhtml_href: Option<String>,
    /// 封面图 href（manifest properties="cover-image" 或文件名含 cover）
    pub(super) cover_href: Option<String>,
}

/// 解析 OPF 文件，返回 (元数据, manifest, spine 中的章节 href 列表)
pub(super) fn parse_opf(
    archive: &mut ZipArchive<fs::File>,
    opf_path: &str,
) -> Result<(OpfResult, HashMap<String, String>, Vec<String>), String> {
    let mut entry = archive
        .by_name(opf_path)
        .map_err(|_| format!("找不到 OPF 文件: {}", opf_path))?;
    let mut content = String::new();
    entry
        .read_to_string(&mut content)
        .map_err(|e| format!("读取 OPF 失败: {}", e))?;

    let mut result = OpfResult::default();
    let mut manifest: HashMap<String, String> = HashMap::new();
    let mut spine_ids: Vec<String> = Vec::new();

    let mut reader = Reader::from_str(&content);
    reader.config_mut().trim_text_start = true;
    reader.config_mut().trim_text_end = true;
    let mut buf = Vec::new();

    let mut in_metadata = false;
    let mut in_manifest = false;
    let mut in_spine = false;
    let mut current_tag = String::new();

    loop {
        match reader.read_event_into(&mut buf) {
            Ok(Event::Start(ref e)) | Ok(Event::Empty(ref e)) => {
                let tag_name = String::from_utf8_lossy(e.name().as_ref()).to_lowercase();
                current_tag = tag_name.clone();

                match tag_name.as_str() {
                    "metadata" => in_metadata = true,
                    "manifest" => in_manifest = true,
                    "spine" => in_spine = true,
                    "itemref" if in_spine => {
                        for attr in e.attributes().flatten() {
                            let name = std::str::from_utf8(attr.key.as_ref()).unwrap_or("");
                            if name.eq_ignore_ascii_case("idref") {
                                let idref =
                                    std::str::from_utf8(&attr.value).unwrap_or("").to_string();
                                spine_ids.push(idref);
                            }
                        }
                    }
                    "item" if in_manifest => {
                        let mut id = None;
                        let mut href = None;
                        let mut media_type = None;
                        let mut properties: Option<String> = None;
                        for attr in e.attributes().flatten() {
                            let name = std::str::from_utf8(attr.key.as_ref()).unwrap_or("");
                            let val = std::str::from_utf8(&attr.value).unwrap_or("");
                            match name.to_lowercase().as_str() {
                                "id" => id = Some(val.to_string()),
                                "href" => href = Some(val.to_string()),
                                "media-type" => media_type = Some(val.to_string()),
                                "properties" => properties = Some(val.to_string()),
                                _ => {}
                            }
                        }
                        if let (Some(id), Some(href)) = (id, href) {
                            manifest.insert(id, href.clone());
                            if let Some(mt) = &media_type {
                                if mt.contains("dtbncx") || mt.contains("ncx") {
                                    if result.ncx_href.is_none() {
                                        result.ncx_href = Some(href.clone());
                                    }
                                }
                            }
                            if let Some(pr) = properties {
                                if pr.eq_ignore_ascii_case("nav") {
                                    if result.nav_xhtml_href.is_none() {
                                        result.nav_xhtml_href = Some(href.clone());
                                    }
                                }
                                // ✅ 封面：manifest properties="cover-image"
                                if pr.to_lowercase().contains("cover") {
                                    if result.cover_href.is_none() {
                                        result.cover_href = Some(href.clone());
                                    }
                                }
                            }
                            // ✅ 兜底：media-type 为图片且文件名含 cover/封面（如 cover.jpeg）
                            if result.cover_href.is_none() {
                                if let Some(mt) = &media_type {
                                    let href_lower = href.to_lowercase();
                                    if mt.starts_with("image/")
                                        && (href_lower.contains("cover") || href_lower.contains("封面"))
                                    {
                                        result.cover_href = Some(href.clone());
                                    }
                                }
                            }
                        }
                    }
                    _ => {}
                }
            }
            Ok(Event::End(ref e)) => {
                let tag_name = String::from_utf8_lossy(e.name().as_ref()).to_lowercase();
                match tag_name.as_str() {
                    "metadata" => in_metadata = false,
                    "manifest" => in_manifest = false,
                    "spine" => in_spine = false,
                    _ => {}
                }
                current_tag.clear();
            }
            Ok(Event::Text(ref e)) => {
                let text = e.unescape().unwrap_or_default().trim().to_string();
                if !text.is_empty() && in_metadata {
                    match current_tag.as_str() {
                        "title" => result.title = text,
                        "creator" => result.author = text,
                        _ => {}
                    }
                }
            }
            Ok(Event::Eof) => break,
            Err(e) => return Err(format!("解析 OPF 失败: {}", e)),
            _ => {}
        }
        buf.clear();
    }

    let spine_hrefs: Vec<String> = spine_ids
        .iter()
        .filter_map(|id| manifest.get(id))
        .cloned()
        .collect();

    Ok((result, manifest, spine_hrefs))
}
