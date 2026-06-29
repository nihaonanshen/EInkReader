fn parse_ncx(
    archive: &mut ZipArchive<fs::File>,
    opf_dir: &str,
    opf_result: &OpfResult,
) -> HashMap<String, String> {
    let mut nav_point_depth: usize = 0;
    let mut ncx_titles = HashMap::new();

    let ncx_href = match &opf_result.ncx_href {
        Some(h) => h.clone(),
        None => {
            let mut found = None;
            for i in 0..archive.len() {
                if let Ok(entry) = archive.by_index(i) {
                    let name = entry.name().to_lowercase();
                    if name.ends_with(".ncx") {
                        found = Some(entry.name().to_string());
                        break;
                    }
                }
            }
            match found {
                Some(h) => h,
                None => return ncx_titles,
            }
        }
    };

    let ncx_path = if ncx_href.starts_with(opf_dir.trim_end_matches('/'))
        || ncx_href.starts_with('/')
    {
        ncx_href.clone()
    } else {
        format!("{}{}", opf_dir, ncx_href)
    };

    let mut content = String::new();
    {
        // 先尝试完整路径，再试原始 href（避免嵌套 match 导致借用冲突）
        let mut found = None;
        if let Ok(mut e) = archive.by_name(&ncx_path) {
            let mut buf = String::new();
            if e.read_to_string(&mut buf).is_ok() && !buf.is_empty() {
                found = Some(buf);
            }
        }
        if found.is_none() {
            if let Ok(mut e) = archive.by_name(&ncx_href) {
                let mut buf = String::new();
                if e.read_to_string(&mut buf).is_ok() && !buf.is_empty() {
                    found = Some(buf);
                }
            }
        }
        match found {
            Some(s) => content = s,
            None => return ncx_titles,
        }
    }

    let mut reader = Reader::from_str(&content);
    reader.config_mut().trim_text_start = true;
    reader.config_mut().trim_text_end = true;
    let mut buf = Vec::new();

    let mut current_src: Option<String> = None;
    let mut current_label: Option<String> = None;
    let mut in_navlabel = false;
    let mut in_text = false;

    loop {
        match reader.read_event_into(&mut buf) {
            Ok(Event::Start(ref e)) => {
                let tag = String::from_utf8_lossy(e.name().as_ref()).to_lowercase();
                match tag.as_str() {
                    "navpoint" => {
                        if nav_point_depth == 0 { nav_point_depth = 1; } else { nav_point_depth += 1; }
                    }
                    "content" => {
                        if nav_point_depth == 1 {
                            for attr in e.attributes().flatten() {
                                let name = std::str::from_utf8(attr.key.as_ref()).unwrap_or("");
                                if name.eq_ignore_ascii_case("src") {
                                    let src = std::str::from_utf8(&attr.value).unwrap_or("");
                                    current_src = Some(src.to_string());
                                }
                            }
                        }
                    }
                    "navlabel" => {
                        if nav_point_depth == 1 {
                            in_navlabel = true;
                            current_label = None;
                        }
                    }
                    "text" if in_navlabel => {
                        if nav_point_depth == 1 {
                            in_text = true;
                        }
                    }
                    _ => {}
                }
            }
            Ok(Event::Text(ref e => {
                if in_text {
                    current_label = Some(e.unescape().unwrap_or_default().to_string());
                }
            }
            Ok(Event::End(ref e)) => {
                let tag = String::from_utf8_lossy(e.name().as_ref()).to_lowercase();
                match tag.as_str() {
                    "navpoint" => {
                        if nav_point_depth > 0 { nav_point_depth -= 1; }
                        if let (Some(src), Some(label)) = (&current_src, &current_label) {
                            let href = if let Some(hash_idx) = src.find('#') {
                                src[..hash_idx].to_string()
                            } else {
                                src.clone()
                            };
                            ncx_titles.insert(href, label.trim().to_string());
                        }
                        current_src = None;
                        current_label = None;
                    }
                    "navlabel" => in_navlabel = false,
                    "text" => in_text = false,
                    _ => {}
                }
            }
            Ok(Event::Eof) => break,
            Err(_) => break,
            _ => {}
        }
        buf.clear();
    }

    ncx_titles
}
