package com.clipnest.core.download

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 魔术字节嗅探（对应线上模块 39957 `sniffImageExt`）。
 * 返回值固定小写不带点：jpg/png/gif/webp/avif/heic/mp4/unknown。
 */
class MediaSnifferTest {

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    /** 拼一个最小 ISO-BMFF `ftyp` box：[size]["ftyp"][major][minor][compatible...]。 */
    private fun ftypBox(majorBrand: String, compatibleBrands: List<String> = emptyList()): ByteArray {
        val total = 16 + compatibleBrands.size * 4
        val out = ByteArray(total)
        out[0] = (total ushr 24).toByte()
        out[1] = (total ushr 16).toByte()
        out[2] = (total ushr 8).toByte()
        out[3] = total.toByte()
        ascii("ftyp").copyInto(out, 4)
        ascii(majorBrand).copyInto(out, 8)
        compatibleBrands.forEachIndexed { index, brand ->
            ascii(brand).copyInto(out, 16 + index * 4)
        }
        return out
    }

    @Test
    fun `常见图片格式`() {
        assertEquals("jpg", MediaSniffer.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10)))
        assertEquals(
            "png",
            MediaSniffer.sniff(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00)),
        )
        assertEquals("gif", MediaSniffer.sniff(ascii("GIF87a") + ByteArray(4)))
        assertEquals("gif", MediaSniffer.sniff(ascii("GIF89a") + ByteArray(4)))
        assertEquals("webp", MediaSniffer.sniff(ascii("RIFF") + bytes(0, 0, 0, 0) + ascii("WEBP")))
    }

    @Test
    fun `ISO-BMFF 家族`() {
        assertEquals("avif", MediaSniffer.sniff(ftypBox("avif")))
        assertEquals("avif", MediaSniffer.sniff(ftypBox("avis")))
        assertEquals("heic", MediaSniffer.sniff(ftypBox("heic")))
        assertEquals("heic", MediaSniffer.sniff(ftypBox("heix")))
        assertEquals("heic", MediaSniffer.sniff(ftypBox("mif1")))
        assertEquals("mp4", MediaSniffer.sniff(ftypBox("isom", listOf("mp42", "avc1"))))
        assertEquals("mp4", MediaSniffer.sniff(ftypBox("M4V ")))
        assertEquals("兼容 brand 里有 avif 仍按 avif 识别", "avif", MediaSniffer.sniff(ftypBox("isom", listOf("avif"))))
    }

    @Test
    fun `认不出的返回 unknown`() {
        assertEquals("unknown", MediaSniffer.sniff(ByteArray(0)))
        assertEquals("unknown", MediaSniffer.sniff(bytes(1, 2, 3)))
        assertEquals("unknown", MediaSniffer.sniff(ascii("hello world")))
        // BMP/TIFF/ICO 不在契约返回列表里，交给调用方回落 Content-Type
        assertEquals("unknown", MediaSniffer.sniff(ascii("BM") + ByteArray(30)))
        assertEquals("unknown", MediaSniffer.sniff(bytes(0x49, 0x49, 0x2A, 0x00, 0x08)))
    }

    @Test
    fun `ftyp box 声明长度超过实际数据也能安全处理`() {
        val box = ftypBox("heic").copyOf()
        // 把 box size 改成很大的值，嗅探仍应正常返回 heic 而不是越界
        box[0] = 0x7F
        box[1] = 0xFF.toByte()
        box[2] = 0xFF.toByte()
        box[3] = 0xFF.toByte()
        assertEquals("heic", MediaSniffer.sniff(box))
    }
}