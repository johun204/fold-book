package com.foldbook

/**
 * 이미지 파일 앞부분 바이트만으로 (width, height) 를 뽑는다. 원격에서 전체를 안 받고
 * 스캔본 여부(가로/세로 비)만 판정하기 위함. JPEG / PNG / GIF / WEBP 지원, 실패 시 null.
 */
object ImageHeader {

    fun size(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 24) return null
        return when {
            // PNG: 89 50 4E 47 ... IHDR width(16..20) height(20..24)
            b[0].u == 0x89 && b[1].u == 0x50 && b[2].u == 0x4E && b[3].u == 0x47 ->
                be32(b, 16) to be32(b, 20)
            // GIF: "GIF8" then LE width(6..8) height(8..10)
            b[0].u == 0x47 && b[1].u == 0x49 && b[2].u == 0x46 && b[3].u == 0x38 ->
                le16(b, 6) to le16(b, 8)
            // WEBP: "RIFF"...."WEBP"
            b[0].u == 0x52 && b[1].u == 0x49 && b[2].u == 0x46 && b[3].u == 0x46 &&
                b[8].u == 0x57 && b[9].u == 0x45 && b[10].u == 0x42 && b[11].u == 0x50 -> webp(b)
            // JPEG: FF D8
            b[0].u == 0xFF && b[1].u == 0xD8 -> jpeg(b)
            else -> null
        }?.takeIf { it.first > 0 && it.second > 0 }
    }

    private val Byte.u get() = toInt() and 0xFF
    private fun be16(b: ByteArray, i: Int) = (b[i].u shl 8) or b[i + 1].u
    private fun be32(b: ByteArray, i: Int) =
        (b[i].u shl 24) or (b[i + 1].u shl 16) or (b[i + 2].u shl 8) or b[i + 3].u
    private fun le16(b: ByteArray, i: Int) = b[i].u or (b[i + 1].u shl 8)
    private fun le24(b: ByteArray, i: Int) = b[i].u or (b[i + 1].u shl 8) or (b[i + 2].u shl 16)

    private fun jpeg(b: ByteArray): Pair<Int, Int>? {
        var i = 2
        while (i + 9 < b.size) {
            if (b[i].u != 0xFF) { i++; continue }
            var marker = b[i + 1].u
            i += 2
            while (marker == 0xFF && i < b.size) { marker = b[i].u; i++ }
            // SOF0..SOF15 (제외: C4 DC C8)
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                if (i + 7 >= b.size) return null
                val h = be16(b, i + 3)
                val w = be16(b, i + 5)
                return w to h
            }
            if (i + 1 >= b.size) return null
            val len = be16(b, i)
            if (len < 2) return null
            i += len
        }
        return null
    }

    private fun webp(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 30) return null
        // b[12..15] = "VP8 " / "VP8L" / "VP8X"
        if (b[12].u != 0x56 || b[13].u != 0x50 || b[14].u != 0x38) return null
        return when (b[15].u) {
            0x20 -> (le16(b, 26) and 0x3FFF) to (le16(b, 28) and 0x3FFF)          // "VP8 " (lossy)
            0x4C -> {                                                              // "VP8L" (lossless)
                val bits = le32le(b, 21)
                (1 + (bits and 0x3FFF)) to (1 + ((bits shr 14) and 0x3FFF))
            }
            0x58 -> (1 + le24(b, 24)) to (1 + le24(b, 27))                         // "VP8X" (extended)
            else -> null
        }
    }

    private fun le32le(b: ByteArray, i: Int) =
        b[i].u or (b[i + 1].u shl 8) or (b[i + 2].u shl 16) or (b[i + 3].u shl 24)
}
