//! container.xml 解析：定位 OPF 文件路径

use std::fs;
use quick_xml::events::Event;
use quick_xml::Reader;
use std::io::Read;
use zip::ZipArchive;

/// 解析 META-INF/container.xml，返回 OPF 文件路径
pub(super) fn parse_container(archive: &mut ZipArchive<fs::File>) -> Result<String, String> {
    let mut entry = archive
        .by_name("META-INF/container.xml")
        .map_err(|_| "找不到 META-INF/container.xml".to_string())?;
    let mut content = String::new();
    entry
        .read_to_string(&mut content)
        .map_err(|e| format!("读取 container.xml 失败: {}", e))?;

    let mut reader = Reader::from_str(&content);
    reader.config_mut().trim_text_start = true;
    reader.config_mut().trim_text_end = true;
    let mut buf = Vec::new();

    loop {
        match reader.read_event_into(&mut buf) {
            Ok(Event::Start(ref e)) | Ok(Event::Empty(ref e)) => {
                if e.name().as_ref().eq_ignore_ascii_case(b"rootfile") {
                    // 尝试各种属性名匹配
                    for attr in e.attributes().flatten() {
                        let attr_name = std::str::from_utf8(attr.key.as_ref()).unwrap_or("");
                        if attr_name.ends_with("full-path")
                            || attr_name.eq_ignore_ascii_case("full-path")
                        {
                            let path = std::str::from_utf8(&attr.value)
                                .map_err(|_| "full-path 属性不是有效 UTF-8".to_string())?;
                            return Ok(path.trim().to_string());
                        }
                    }
                }
            }
            Ok(Event::Eof) => break,
            Err(e) => return Err(format!("解析 container.xml 失败: {}", e)),
            _ => {}
        }
        buf.clear();
    }

    Err("container.xml 中未找到 rootfile 元素".to_string())
}
