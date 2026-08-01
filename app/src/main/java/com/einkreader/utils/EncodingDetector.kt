package com.einkreader.utils

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object EncodingDetector {
    private const val SAMPLE_SIZE = 8192

    @JvmStatic
    fun detect(file: File?): String {
        if (file == null || !file.exists() || file.length() == 0L) return "UTF-8"
        val readSize = Math.min(file.length(), SAMPLE_SIZE.toLong()).toInt()
        val header = ByteArray(readSize)
        return try {
            FileInputStream(file).use { fis ->
                val actualRead = fis.read(header, 0, readSize)
                if (actualRead <= 0) return@use "UTF-8"
                // 先检查 BOM（与 ByteArray 版本一致）
                detectByBom(header, actualRead) ?: detectByDecoding(header, actualRead)
            }
        } catch (e: IOException) {
            android.util.Log.w("EncDetector", "probeEncoding failed", e)
            "UTF-8"
        }
    }

    @JvmStatic
    fun detect(data: ByteArray?, dataLen: Int): String {
        if (data == null || dataLen <= 0) return "UTF-8"
        detectByBom(data, dataLen)?.let { return it }
        if (isUtf8ByStats(data, dataLen)) return "UTF-8"
        val sampleLen = Math.min(dataLen, SAMPLE_SIZE)
        return detectByDecoding(data, sampleLen)
    }

    private fun detectByBom(data: ByteArray, len: Int): String? {
        if (len >= 3 && data[0].toInt() and 0xFF == 0xEF && data[1].toInt() and 0xFF == 0xBB && data[2].toInt() and 0xFF == 0xBF) return "UTF-8"
        if (len >= 4 && data[0].toInt() and 0xFF == 0xFF && data[1].toInt() and 0xFF == 0xFE && data[2].toInt() and 0xFF == 0 && data[3].toInt() and 0xFF == 0) return "UTF-32LE"
        if (len >= 4 && data[0].toInt() and 0xFF == 0 && data[1].toInt() and 0xFF == 0 && data[2].toInt() and 0xFF == 0xFE && data[3].toInt() and 0xFF == 0xFF) return "UTF-32BE"
        if (len >= 2 && data[0].toInt() and 0xFF == 0xFF && data[1].toInt() and 0xFF == 0xFE) return "UTF-16LE"
        if (len >= 2 && data[0].toInt() and 0xFF == 0xFE && data[1].toInt() and 0xFF == 0xFF) return "UTF-16BE"
        return null
    }

    private fun isUtf8ByStats(data: ByteArray, len: Int): Boolean {
        var utf8Count = 0
        val limit = Math.min(len, 32768)
        var i = 0
        while (i < limit - 3) {
            val b = data[i].toInt() and 0xFF
            if (b < 0x80) { i++; continue }
            if (b in 0xE0..0xEF) {
                if ((data[i+1].toInt() and 0xC0) == 0x80 && (data[i+2].toInt() and 0xC0) == 0x80) {
                    utf8Count++
                    i += 3
                    continue
                }
            } else if (b in 0xF0..0xF7) {
                if ((data[i+1].toInt() and 0xC0) == 0x80 && (data[i+2].toInt() and 0xC0) == 0x80 && (data[i+3].toInt() and 0xC0) == 0x80) {
                    utf8Count++
                    i += 4
                    continue
                }
            }
            i++
        }
        return utf8Count > 10
    }

    private fun detectByDecoding(data: ByteArray, len: Int): String {
        var utf8Score = scoreEncoding(data, len, "UTF-8")
        if (utf8Score > 20) return "UTF-8"
        var best = "UTF-8"
        var bestScore = maxOf(utf8Score, 0)
        for (enc in listOf("GBK", "GB18030", "Big5", "GB2312", "UTF-16LE", "UTF-16BE")) {
            val score = scoreEncoding(data, len, enc)
            if (score > bestScore) { bestScore = score; best = enc }
        }
        return best
    }

    private fun scoreEncoding(data: ByteArray, len: Int, encoding: String): Int {
        return try {
            val decoder = Charset.forName(encoding).newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val result = decoder.decode(ByteBuffer.wrap(data, 0, len)).toString()
            var score = 0
            for (ch in result) {
                when {
                    ch in '\u4E00'..'\u9FFF' -> score += 3
                    ch in setOf('\u3001', '\u3002', '\uFF0C', '\uFF1A', '\uFF1B', '\u201C', '\u201D', '\u2018', '\u2019') -> score++
                }
            }
            score
        } catch (e: Exception) {
            android.util.Log.w("EncDetector", "scoreEncoding failed", e)
            -1
        }
    }
}
