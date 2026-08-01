//! JNI 桥接层
//!
//! 所有 JNI 函数在此统一管理，转发到纯 Rust 实现。

use jni::objects::{JByteArray, JClass, JObject, JString};
use jni::sys::{jboolean, jbyteArray, jfloat, jint, jstring};
use jni::JNIEnv;

use crate::encoding;
use crate::layout;
use crate::parser;

/// 编码检测
#[no_mangle]
pub extern "system" fn Java_com_einkreader_core_NativeBridge_nativeDetectEncoding(
    env: JNIEnv,
    _class: JClass,
    data: jbyteArray,
    len: jint,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        // ✅ 防御：jint 为有符号，负数转 usize 会产生约 4GB 分配 panic
        if len < 0 {
            return String::from("UTF-8");
        }
        let data_len = len as usize;
        let mut buf = vec![0u8; data_len];
        let obj = unsafe { JObject::from_raw(data) };
        let byte_array = JByteArray::from(obj);
        let buf_i8: &mut [i8] = unsafe {
            std::slice::from_raw_parts_mut(buf.as_mut_ptr() as *mut i8, data_len)
        };
        env.get_byte_array_region(byte_array, 0, buf_i8)
            .expect("Failed to read byte array");
        let enc_result = encoding::detect(&buf);
        enc_result.encoding
    }));

    match result {
        Ok(enc) => env
            .new_string(&enc)
            .expect("Failed to create string")
            .into_raw(),
        Err(_) => env
            .new_string("UTF-8")
            .expect("Failed to create string")
            .into_raw(),
    }
}

/// TXT 解析（二进制版 - bincode 序列化）
#[no_mangle]
pub extern "system" fn Java_com_einkreader_core_NativeBridge_nativeParseTxtBinary(
    mut env: JNIEnv,
    _class: JClass,
    file_path: JString,
    forced_encoding: JString,
) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let path: String = env
            .get_string(&file_path)
            .expect("Failed to get file_path string")
            .into();
        let forced: String = env
            .get_string(&forced_encoding)
            .expect("Failed to get forced_encoding string")
            .into();
        let forced_opt: Option<&str> = if forced.trim().is_empty() {
            None
        } else {
            Some(forced.as_str())
        };

        match parser::txt::parse_txt(&path, forced_opt) {
            Ok(parse_result) => {
                // 使用 bincode 二进制序列化（比 JSON 快 3-5 倍，体积小 40-60%）
                bincode::serialize(&parse_result).unwrap_or_default()
            }
            Err(e) => {
                // 错误码 1 + 错误信息
                let mut bytes = vec![1u8];
                bytes.extend_from_slice(e.as_bytes());
                bytes
            }
        }
    }));

    match result {
        Ok(bytes) => {
            let arr = env
                .new_byte_array(bytes.len() as i32)
                .expect("Failed to create byte array");
            let bytes_i8: Vec<i8> = bytes.iter().map(|&b| b as i8).collect();
            env.set_byte_array_region(&arr, 0, &bytes_i8)
                .expect("Failed to set byte array region");
            arr.into_raw()
        }
        Err(_) => env
            .new_byte_array(0)
            .expect("Failed to create empty byte array")
            .into_raw(),
    }
}

/// EPUB 解析（二进制版 - bincode 序列化）
#[no_mangle]
pub extern "system" fn Java_com_einkreader_core_NativeBridge_nativeParseEpubBinary(
    mut env: JNIEnv,
    _class: JClass,
    file_path: JString,
) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let path: String = env
            .get_string(&file_path)
            .expect("Failed to get file_path string")
            .into();

        match parser::epub::parse_epub(&path) {
            Ok(parse_result) => {
                // 使用 bincode 二进制序列化（比 JSON 快 3-5 倍，体积小 40-60%）
                bincode::serialize(&parse_result).unwrap_or_default()
            }
            Err(e) => {
                let mut bytes = vec![1u8];
                bytes.extend_from_slice(e.as_bytes());
                bytes
            }
        }
    }));

    match result {
        Ok(bytes) => {
            let arr = env
                .new_byte_array(bytes.len() as i32)
                .expect("Failed to create byte array");
            let bytes_i8: Vec<i8> = bytes.iter().map(|&b| b as i8).collect();
            env.set_byte_array_region(&arr, 0, &bytes_i8)
                .expect("Failed to set byte array region");
            arr.into_raw()
        }
        Err(_) => env
            .new_byte_array(0)
            .expect("Failed to create empty byte array")
            .into_raw(),
    }
}

/// 加载指定章节内容（懒加载按需读取）
#[no_mangle]
pub extern "system" fn Java_com_einkreader_core_NativeBridge_nativeLoadEpubChapterContent(
    mut env: JNIEnv,
    _class: JClass,
    file_path: JString,
    chapter_xhtml_path: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let path: String = env.get_string(&file_path).expect("Failed to get file_path").into();
        let chapter_path: String = env.get_string(&chapter_xhtml_path).expect("Failed to get chapter path").into();

        match parser::epub::load_chapter_content(&path, &chapter_path) {
            Ok(content) => content,
            Err(e) => format!("{{\"error\":\"{}\"}}", e),
        }
    }));

    match result {
        Ok(json) => env
            .new_string(&json)
            .expect("Failed to create string")
            .into_raw(),
        Err(_) => env
            .new_string("{\"error:\"加载过程panic\"}")
            .expect("Failed to create string")
            .into_raw(),
    }
}

/// 批量文本布局（bincode 版）— 多个段落，相同参数
/// 输入：texts 为 bincode 序列化的 Vec<String>，params 为 bincode 序列化的 LayoutParams
/// 输出：bincode 序列化的 Vec<LayoutResult>
#[no_mangle]
pub extern "system" fn Java_com_einkreader_core_NativeBridge_nativeLayoutTextsBatchBinary(
    env: JNIEnv,
    _class: JClass,
    texts_bin: jbyteArray,
    params_bin: jbyteArray,
) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let read_bytes = |arr: jbyteArray| -> Vec<u8> {
            let obj = unsafe { JObject::from_raw(arr) };
            let byte_array = JByteArray::from(obj);
            let len = env.get_array_length(&byte_array).unwrap_or(0) as usize;
            let mut buf = vec![0u8; len];
            let buf_i8: &mut [i8] = unsafe {
                std::slice::from_raw_parts_mut(buf.as_mut_ptr() as *mut i8, len)
            };
            env.get_byte_array_region(&byte_array, 0, buf_i8).ok();
            buf
        };

        let texts = read_bytes(texts_bin);
        let params = read_bytes(params_bin);
        layout::batch_layout_texts_binary(&texts, &params)
    }));

    match result {
        Ok(binary) => {
            let arr = env
                .new_byte_array(binary.len() as i32)
                .expect("Failed to create byte array");
            let binary_i8: Vec<i8> = binary.iter().map(|&b| b as i8).collect();
            env.set_byte_array_region(&arr, 0, &binary_i8)
                .expect("Failed to set byte array region");
            arr.into_raw()
        }
        Err(_) => env
            .new_byte_array(0)
            .expect("Failed to create empty byte array")
            .into_raw(),
    }
}

/// 文本布局（二进制版，主要入口）
#[no_mangle]
pub extern "system" fn Java_com_einkreader_core_NativeBridge_nativeLayoutTextBinary(
    mut env: JNIEnv,
    _class: JClass,
    text: JString,
    max_width_px: jfloat,
    max_height_px: jfloat,
    font_size_px: jfloat,
    line_spacing: jfloat,
    paragraph_spacing: jfloat,
    first_line_indent: jboolean,
    padding_left: jfloat,
    padding_top: jfloat,
) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let text_str: String = env
            .get_string(&text)
            .expect("Failed to get text string")
            .into();

        layout::layout_text_binary(
            &text_str,
            max_width_px,
            max_height_px,
            font_size_px,
            line_spacing,
            paragraph_spacing,
            first_line_indent != 0,
            padding_left,
            padding_top,
        )
    }));

    match result {
        Ok(binary) => {
                    let arr = env
                        .new_byte_array(binary.len() as i32)
                        .expect("Failed to create byte array");
                    let binary_i8: Vec<i8> = binary.iter().map(|&b| b as i8).collect();
                    env.set_byte_array_region(&arr, 0, &binary_i8)
                        .expect("Failed to set byte array region");
                    arr.into_raw()
                            }
                            Err(_) => env
            .new_byte_array(0)
            .expect("Failed to create empty byte array")
            .into_raw(),
    }
}
