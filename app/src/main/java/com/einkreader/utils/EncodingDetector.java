package com.einkreader.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;

/**
 * 文本编码自动检测
 *
 * 支持：UTF-8、GBK、GB2312、GB18030、Big5、UTF-16LE、UTF-16BE
 * 速度优化：统计预筛 → 小样本解码验证（只用 4KB 而不是全 64KB）
 */
public class EncodingDetector {

    private static final String[] ENCODINGS = {
        "UTF-8", "GBK", "GB18030", "Big5", "GB2312", "UTF-16LE", "UTF-16BE"
    };

    /** 解码验证只用前 4KB，足够区分编码 */
    private static final int SAMPLE_SIZE = 4096;

    /**
     * 检测文件编码
     * 策略：BOM → 统计预筛（快速） → 小样本解码验证（4KB）
     */
    public static String detect(File file) {
        if (file == null || !file.exists() || file.length() == 0) return "UTF-8";

        // 读文件头（最多 64KB，但解码验证只取前 4KB）
        int readSize = (int) Math.min(file.length(), 65536);
        byte[] header = new byte[readSize];

        InputStream is = null;
        try {
            is = new FileInputStream(file);
            int actualRead = is.read(header, 0, readSize);
            if (actualRead <= 0) return "UTF-8";
            return detect(header, actualRead);
        } catch (IOException e) {
            return "UTF-8";
        } finally {
            if (is != null) try { is.close(); } catch (IOException e) { }
        }
    }

    /**
     * 从字节数组中检测编码（用于 TxtParser 已读入全文件的场景，避免重复 I/O）
     */
    public static String detect(byte[] data, int dataLen) {
        if (data == null || dataLen <= 0) return "UTF-8";

        // 1. BOM 检测（100% 准确）
        String bomResult = detectByBom(data, dataLen);
        if (bomResult != null) return bomResult;

        // 2. 快速统计预筛：只用于快速确认 UTF-8，不会误判为 GBK
        if (isUtf8ByStats(data, dataLen)) return "UTF-8";

        // 3. 小样本解码验证（只处理前 4KB，速度比全量 64KB 快 16 倍）
        int sampleLen = Math.min(dataLen, SAMPLE_SIZE);
        return detectByDecoding(data, sampleLen);
    }

    /**
     * 通过 BOM（字节顺序标记）检测编码
     */
    private static String detectByBom(byte[] data, int len) {
        if (len >= 3 && data[0] == (byte)0xEF && data[1] == (byte)0xBB && data[2] == (byte)0xBF)
            return "UTF-8";
        if (len >= 4 && data[0] == (byte)0xFF && data[1] == (byte)0xFE
                && data[2] == 0x00 && data[3] == 0x00)
            return "UTF-32LE";
        if (len >= 4 && data[0] == (byte)0x00 && data[1] == (byte)0x00
                && data[2] == (byte)0xFE && data[3] == (byte)0xFF)
            return "UTF-32BE";
        if (len >= 2 && data[0] == (byte)0xFF && data[1] == (byte)0xFE)
            return "UTF-16LE";
        if (len >= 2 && data[0] == (byte)0xFE && data[1] == (byte)0xFF)
            return "UTF-16BE";
        return null;
    }

    /**
     * 快速统计预筛：只用于确认 UTF-8，不会返回 GBK
     * 扫描文件头中的 3 字节 UTF-8 序列（中文），数量足够多就果断返回 UTF-8
     * 中文之外的 UTF-8 编码文件（如英文），会继续走到解码验证
     */
    private static boolean isUtf8ByStats(byte[] data, int len) {
        int utf8Count = 0;
        int limit = Math.min(len, 32768); // 最多看 32KB 就够了

        // 只数 3 字节和 4 字节 UTF-8 序列（中文字符的标志）
        int i = 0;
        while (i < limit - 3) {
            int b = data[i] & 0xFF;
            if (b < 0x80) { i++; continue; }

            // 3 字节 UTF-8（这是中文最常见的编码形式）
            if (b >= 0xE0 && b <= 0xEF) {
                if ((data[i+1] & 0xC0) == 0x80 && (data[i+2] & 0xC0) == 0x80) {
                    utf8Count++;
                    i += 3;
                    continue;
                }
            }
            // 4 字节 UTF-8（表情符号等）
            else if (b >= 0xF0 && b <= 0xF7) {
                if ((data[i+1] & 0xC0) == 0x80 && (data[i+2] & 0xC0) == 0x80 && (data[i+3] & 0xC0) == 0x80) {
                    utf8Count++;
                    i += 4;
                    continue;
                }
            }
            i++;
        }

        // 有足够多的 3/4 字节 UTF-8 序列，肯定是 UTF-8
        return utf8Count > 10;
    }

    /**
     * 逐个编码尝试解码，选生成有效汉字最多的
     * ★ 只处理小样本（4KB），并在 UTF-8 得分足够时提前退出
     */
    private static String detectByDecoding(byte[] data, int len) {
        // UTF-8 最常用，先试
        int utf8Score = scoreEncoding(data, len, "UTF-8");
        if (utf8Score > 20) return "UTF-8"; // 有足够中文字符，确信是 UTF-8

        String best = "UTF-8";
        int bestScore = Math.max(utf8Score, 0);

        // 试其他编码
        String[] remaining = {"GBK", "GB18030", "Big5", "GB2312", "UTF-16LE", "UTF-16BE"};
        for (String enc : remaining) {
            int score = scoreEncoding(data, len, enc);
            if (score > bestScore) {
                bestScore = score;
                best = enc;
            }
        }

        return best;
    }

    /** 用指定编码解码并打分 */
    private static int scoreEncoding(byte[] data, int len, String encoding) {
        try {
            CharsetDecoder decoder = Charset.forName(encoding).newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
            String result = decoder.decode(ByteBuffer.wrap(data, 0, len)).toString();

            int score = 0;
            for (int j = 0; j < result.length(); j++) {
                char c = result.charAt(j);
                // 基本汉字
                if (c >= 0x4E00 && c <= 0x9FFF) score += 3;
                // 常用汉字标点
                else if (c == '\u3001' || c == '\u3002' || c == '\uFF0C'
                      || c == '\uFF1A' || c == '\uFF1B' || c == '\u201C'
                      || c == '\u201D' || c == '\u2018' || c == '\u2019')
                    score += 1;
            }
            return score;
        } catch (Exception e) {
            return -1; // 解码失败（非法字节），分数最低
        }
    }
}
