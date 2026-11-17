package com.clipnest.core.download

/**
 * 媒体魔术字节嗅探（对应线上模块 39957 的 `sniffImageExt`）。
 * 只读前若干字节判断真实格式，不信任 Content-Type。
 *
 * 返回值固定为小写、不带点的扩展名：
 * `jpg` / `png` / `gif` / `webp` / `avif` / `heic` / `mp4` / `unknown`
 *
 * - ISO-BMFF（`ftyp` box）里 `avif/avis` → avif；`heic/heix/hevc/hevx/mif1/msf1` → heic；
 *   其余 ftyp 家族（isom/mp42/avc1/M4V…）→ mp4。
 * - 认不出返回 `unknown`，由调用方回落 Content-Type / URL 后缀。
 */
object MediaSniffer {

    fun sniff(head: ByteArray): String {
        if (head.isEmpty()) return "unknown"

        // PNG: \x89PNG\r\n\x1a\n
        if (head.size >= 8 &&
            head[0] == 0x89.toByte() && head[1] == 0x50.toByte() && head[2] == 0x4E.toByte() &&
            head[3] == 0x47.toByte() && head[4] == 0x0D.toByte() && head[5] == 0x0A.toByte() &&
            head[6] == 0x1A.toByte() && head[7] == 0x0A.toByte()
        ) {
            return "png"
        }

        // JPEG: FF D8 FF
        if (head.size >= 3 &&
            head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()
        ) {
            return "jpg"
        }

        // GIF: GIF87a / GIF89a
        if (head.size >= 6) {
            val gif = ascii(head, 0, 6)
            if (gif == "GIF87a" || gif == "GIF89a") return "gif"
        }

        // WebP: RIFF....WEBP
        if (head.size >= 12 && ascii(head, 0, 4) == "RIFF" && ascii(head, 8, 4) == "WEBP") {
            return "webp"
        }

        // ISO-BMFF: [4 字节大端 box size]"ftyp" brands...
        if (head.size >= 12 && ascii(head, 4, 4) == "ftyp") {
            val boxSize = uint32BigEndian(head, 0)
            val end = minOf(boxSize, head.size.toLong()).toInt()
            val brands = ArrayList<String>(4)
            brands += ascii(head, 8, 4)
            var offset = 16
            while (offset + 4 <= end) {
                brands += ascii(head, offset, 4)
                offset += 4
            }
            if (brands.any { it == "avif" || it == "avis" }) return "avif"
            if (brands.any { it in HEIC_BRANDS }) return "heic"
            return "mp4"
        }

        return "unknown"
    }

    private val HEIC_BRANDS = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1")

    /** 从 [offset] 开始取 [length] 个字节按 ASCII 解释；越界时返回空串。 */
    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String {
        if (offset < 0 || length <= 0 || offset + length > bytes.size) return ""
        val chars = CharArray(length)
        for (index in 0 until length) {
            chars[index] = (bytes[offset + index].toInt() and 0xFF).toChar()
        }
        return String(chars)
    }

    /** 读 4 字节大端无符号整数（box size）。 */
    private fun uint32BigEndian(bytes: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 4 > bytes.size) return 0L
        return ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
    }
}