//! JNI 桥接层
//!
//! 所有 JNI 函数在此统一管理，转发到纯 Rust 实现。

use jni::objects::{JByteArray, JClass, JObject, JString};
use jni::sys::{jbyteArray, jint, jstring};
use jni::JNIEnv;
use serde_json;

use crate::encoding;
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

        let forced_opt = if forced.is_empty() {
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
