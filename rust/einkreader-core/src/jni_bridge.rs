//! JNI 桥接层
//!
//! 所有 JNI 函数在此统一管理，转发到纯 Rust 实现。

use jni::objects::{JByteArray, JClass, JObject, JString};
use jni::sys::{jboolean, jbyteArray, jfloat, jint, jstring};
use jni::JNIEnv;
use serde_json;

use crate::encoding;
use crate::layout;
use crate::parser;

/// 编码检测
#[no_mangle]
pub extern "system" fn Java_com_einkreader_core_NativeBridge_nativeDetectEncoding(
    mut env: JNIEnv,
    _class: JClass,
    data: jbyteArray,
    len: jint,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
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

/// TXT 解析
#[no_mangle]
pub extern "system" fn Java_com_einkreader_core_NativeBridge_nativeParseTxt(
    mut env: JNIEnv,
    _class: JClass,
    file_path: JString,
    forced_encoding: JString,
) -> jstring {
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
            Ok(parse_result) => serde_json::to_string(&parse_result)
                .unwrap_or_else(|e| format!("{{\"error\":\"JSON序列化失败: {}\"}}", e)),
            Err(e) => format!("{{\"error\":\"{}\"}}", e),
        }
    }));

    match result {
        Ok(json) => env
            .new_string(&json)
            .expect("Failed to create string")
            .into_raw(),
        Err(_) => env
            .new_string("{\"error\":\"解析过程panic\"}")
            .expect("Failed to create string")
            .into_raw(),
    }
}

/// EPUB 解析
#[no_mangle]
pub extern "system" fn Java_com_einkreader_core_NativeBridge_nativeParseEpub(
    mut env: JNIEnv,
    _class: JClass,
    file_path: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let path: String = env
            .get_string(&file_path)
            .expect("Failed to get file_path string")
            .into();

        match parser::epub::parse_epub(&path) {
            Ok(parse_result) => serde_json::to_string(&parse_result)
                .unwrap_or_else(|e| format!("{{\"error\":\"JSON序列化失败: {}\"}}", e)),
            Err(e) => format!("{{\"error\":\"{}\"}}", e),
        }
    }));

    match result {
        Ok(json) => env
            .new_string(&json)
            .expect("Failed to create string")
            .into_raw(),
        Err(_) => env
            .new_string("{\"error\":\"解析过程panic\"}")
            .expect("Failed to create string")
            .into_raw(),
    }
}

/// 文本布局（JSON 版，兼容已有调用方）
#[no_mangle]
pub extern "system" fn Java_com_einkreader_core_NativeBridge_nativeLayoutText(
    mut env: JNIEnv,
    _class: JClass,
    text: JString,
    max_width_px: jfloat,
    max_height_px: jfloat,
    font_size_px: jfloat,
    line_spacing: jfloat,
    paragraph_spacing: jfloat,
    first_line_indent: jboolean,
) -> jstring {
    // 兼容模式：padding 传 0
    let json = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let text_str: String = env
            .get_string(&text)
            .expect("Failed to get text string")
            .into();

        let res = layout::layout_text(
            &text_str,
            max_width_px,
            max_height_px,
            font_size_px,
            line_spacing,
            paragraph_spacing,
            first_line_indent != 0,
            0.0, // padding_left
            0.0, // padding_top
        );

        serde_json::to_string(&res)
            .unwrap_or_else(|e| format!("{{\"error\":\"JSON序列化失败: {}\"}}", e))
    }));

    match json {
        Ok(j) => env.new_string(&j).expect("new_string").into_raw(),
        Err(_) => env.new_string("{\"error\":\"布局过程panic\"}").expect("new_string").into_raw(),
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