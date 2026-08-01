//! ZIP 条目读取工具：安全读取（含 Zip Bomb 防护）

use std::fs;
use std::io::Read;
use zip::ZipArchive;

/// 单个 ZIP 条目解压后大小上限 (64MB) — 防 Zip Bomb
const MAX_ENTRY_UNCOMPRESSED: u64 = 64 * 1024 * 1024;

/// 从 ZIP 读取指定 entry 的完整文本
pub(super) fn read_zip_entry(archive: &mut ZipArchive<fs::File>, path: &str) -> Option<String> {
    let mut entry = archive.by_name(path).ok()?;
    if entry.size() > MAX_ENTRY_UNCOMPRESSED {
        return None;
    }
    let mut buf = String::new();
    entry.read_to_string(&mut buf).ok()?;
    if buf.is_empty() { None } else { Some(buf) }
}

/// 获取指定章节的原始 HTML，尝试多种路径匹配方式
pub(super) fn get_raw_html(archive: &mut ZipArchive<fs::File>, entry_path: &str, href: &str) -> String {
    // 尝试多种路径方式，逐个尝试（避免链式 or_else 导致借用冲突）
    let raw = try_get_entry(archive, entry_path);
    if !raw.is_empty() {
        return raw;
    }
    // URL 解码
    if let Ok(decoded) = urlencoding::decode(entry_path) {
        let raw = try_get_entry(archive, &decoded);
        if !raw.is_empty() {
            return raw;
        }
    }
    // 去掉 ./
    if let Some(stripped) = entry_path.strip_prefix("./") {
        let raw = try_get_entry(archive, stripped);
        if !raw.is_empty() {
            return raw;
        }
    }
    // 不区分大小写匹配文件名
    let filename = href.rsplit_once('/').map(|(_, f)| f).unwrap_or(href);
    let matched = {
        let mut found = None;
        for i in 0..archive.len() {
            if let Ok(entry) = archive.by_index(i) {
                let name = entry.name().to_string();
                let entry_file = name
                    .rsplit_once('/')
                    .map(|(_, f)| f)
                    .unwrap_or(&name)
                    .to_string();
                if entry_file.eq_ignore_ascii_case(filename) {
                    found = Some(name);
                    break;
                }
            }
        }
        found
    };
    if let Some(name) = matched {
        try_get_entry(archive, &name)
    } else {
        String::new()
    }
}

/// 尝试读取 ZIP 条目，失败或超限时返回空字符串
pub(super) fn try_get_entry(archive: &mut ZipArchive<fs::File>, path: &str) -> String {
    if let Ok(mut entry) = archive.by_name(path) {
        // ✅ 单条目解压大小上限，防 Zip Bomb
        if entry.size() > MAX_ENTRY_UNCOMPRESSED {
            return String::new();
        }
        let mut raw = String::new();
        if entry.read_to_string(&mut raw).is_ok() && !raw.is_empty() {
            return raw;
        }
    }
    String::new()
}
