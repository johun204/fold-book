package com.foldbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageHeaderTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun png() {
        // 8B sig + IHDR len + "IHDR" + width(800=0x0320) + height(1200=0x04B0) + pad
        val b = bytes(
            0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x03, 0x20, 0x00, 0x00, 0x04, 0xB0,
            0, 0, 0, 0,
        )
        assertEquals(800 to 1200, ImageHeader.size(b))
    }

    @Test fun gif() {
        val b = bytes(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61,
            0x20, 0x03, 0xB0, 0x04, // 800, 1200 little-endian
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        )
        assertEquals(800 to 1200, ImageHeader.size(b))
    }

    @Test fun garbage() {
        assertNull(ImageHeader.size(bytes(1, 2, 3, 4, 5, 6, 7, 8)))
    }

    @Test fun spread_ratio_via_png() {
        // 2000x1400 -> ratio 1.43 -> 스프레드로 판정될 크기
        val b = bytes(
            0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x07, 0xD0, 0x00, 0x00, 0x05, 0x78,
            0, 0, 0, 0,
        )
        val (w, h) = ImageHeader.size(b)!!
        assertEquals(2000, w); assertEquals(1400, h)
    }
}
