//! JNI 桥接层
//!
//! 所有 JNI 函数在此统一管理，转发到纯 Rust 实现。

use jni::objects::{JByteArray, JClass, JIntArray, JObject, JString};
use jni::sys::{jboolean, jbyteArray, jfloat, jint, jstring};
use jni::JNIEnv;

use crate::encoding;
use crate::layout;
use crate::parser;

/// [P0-#1] 错误字节数组：1=状态字节（错误）+ UTF-8 错误信息。
/// 旧协议无状态字节，合法 payload 首字节恰好为 1 时会被 Java 端误判为错误
fn err_bytes(msg: &str) -> Vec<u8> {
    let mut bytes = vec![1u8];
    bytes.extend_from_slice(msg.as_bytes());
    bytes
}

/// [P0-#12] i32 溢出保护的 jbyteArray 构建：
/// 原 `bytes.len() as i32` 在超 2GB 时截断为负数 → JNI 崩溃；
/// 现失败时统一返回空数组（Java 端将空结果视为失败，回退 Java 解析器）
fn build_byte_array(env: &JNIEnv, bytes: &[u8]) -> jbyteArray {
    let len = match i32::try_from(bytes.len()) {
        Ok(l) => l,
        Err(_) => {
            return env
                .new_byte_array(0)
                .expect("Failed to create empty byte array")
                .into_raw();
        }
    };
    let arr = match env.new_byte_array(len) {
        Ok(a) => a,
        Err(_) => {
            return env
                .new_byte_array(0)
                .expect("Failed to create empty byte array")
                .into_raw();
        }
    };
    let i8: Vec<i8> = bytes.iter().map(|&b| b as i8).collect();
    if env.set_byte_array_region(&arr, 0, &i8).is_err() {
        return env
            .new_byte_array(0)
            .expect("Failed to create empty byte array")
            .into_raw();
    }
    arr.into_raw()
}

/// 编码检测
// `data` 是原始 JNI 指针，调用方保证非空有效；标记 `unsafe` 符合 clippy `not_unsafe_ptr_arg_deref`
#[no_mangle]
pub unsafe extern "system" fn Java_com_einkreader_core_NativeBridge_nativeDetectEncoding(
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
            // [P0-#1] 统一协议：0=状态字节（成功）+ bincode payload；序列化失败也走错误路径
            Ok(parse_result) => match bincode::serialize(&parse_result) {
                Ok(payload) => {
                    let mut bytes = vec![0u8];
                    bytes.extend_from_slice(&payload);
                    bytes
                }
                Err(e) => err_bytes(&format!("TXT bincode 序列化失败: {}", e)),
            },
            Err(e) => err_bytes(e.as_str()),
        }
    }));

    match result {
        Ok(bytes) => build_byte_array(&env, &bytes),
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
            // [P0-#1] 统一协议：0=状态字节（成功）+ bincode payload；序列化失败也走错误路径
            Ok(parse_result) => match bincode::serialize(&parse_result) {
                Ok(payload) => {
                    let mut bytes = vec![0u8];
                    bytes.extend_from_slice(&payload);
                    bytes
                }
                Err(e) => err_bytes(&format!("EPUB bincode 序列化失败: {}", e)),
            },
            Err(e) => err_bytes(e.as_str()),
        }
    }));

    match result {
        Ok(bytes) => build_byte_array(&env, &bytes),
        Err(_) => env
            .new_byte_array(0)
            .expect("Failed to create empty byte array")
            .into_raw(),
    }
}

/// 加载指定章节内容（懒加载按需读取）
/// [P1-#3] JSON 字符串转义：Rust 错误消息可能含引号/反斜杠/控制字符，
/// 直接拼进 {"error":"..."} 会产出非法 JSON；先转义再拼
fn json_escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            other if (other as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", other as u32)),
            other => out.push(other),
        }
    }
    out
}

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
            Err(e) => format!("{{\"error\":\"{}\"}}", json_escape(&e)),
        }
    }));

    match result {
        Ok(json) => env
            .new_string(&json)
            .expect("Failed to create string")
            .into_raw(),
        Err(_) => env
            .new_string("{\"error\":\"加载过程 panic\"}")
            .expect("Failed to create string")
            .into_raw(),
    }
}

/// 批量文本布局（bincode 版）— 多个段落，相同参数
/// 输入：texts 为 bincode 序列化的 Vec<String>，params 为 bincode 序列化的 LayoutParams
/// 输出：bincode 序列化的 Vec<LayoutResult>
// `texts_bin`/`params_bin` 是原始 JNI 指针，调用方保证非空有效；标记 `unsafe` 符合 clippy `not_unsafe_ptr_arg_deref`
#[no_mangle]
pub unsafe extern "system" fn Java_com_einkreader_core_NativeBridge_nativeLayoutTextsBatchBinary(
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
        Ok(binary) => build_byte_array(&env, &binary),
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
    para_types: JObject,
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
        // [P1-2] paraTypes 可空（TXT / 无样式信息时 Java 传 null）→ 空 = 全正文
        let para_vec: Vec<i32> = if para_types.is_null() {
            Vec::new()
        } else {
            // jni 0.21 无 get_int_array_elements；用 region 拷贝（单次内存传输，Nook 友好）
            let arr = <&JIntArray>::from(&para_types);
            let n = env
                .get_array_length(arr)
                .expect("Failed to get para_types length") as usize;
            let mut buf = vec![0i32; n];
            if n > 0 {
                env.get_int_array_region(arr, 0, &mut buf)
                    .expect("Failed to read para_types elements");
            }
            buf
        };

        layout::layout_text_binary(
            &text_str,
            para_vec,
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
        Ok(binary) => build_byte_array(&env, &binary),
        Err(_) => env
            .new_byte_array(0)
            .expect("Failed to create empty byte array")
            .into_raw(),
    }
}
