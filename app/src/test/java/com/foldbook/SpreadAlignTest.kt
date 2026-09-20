package com.foldbook

import org.junit.Assert.assertEquals
import org.junit.Test

/** 양면 모드에서 두 이미지가 가운데(책등)에 딱 붙는지 = 왼쪽 슬롯은 오른쪽 정렬, 오른쪽 슬롯은 왼쪽 정렬. */
class SpreadAlignTest {

    @Test fun `ltr pairs face each other at the spine`() {
        // 1,2 페이지가 한 쌍 → 1 은 왼쪽 슬롯(오른쪽 정렬), 2 는 오른쪽 슬롯(왼쪽 정렬)
        assertEquals(1f, spreadAlignX(index = 0, count = 10, rtl = false))
        assertEquals(0f, spreadAlignX(index = 1, count = 10, rtl = false))
        assertEquals(1f, spreadAlignX(index = 2, count = 10, rtl = false))
    }

    @Test fun `rtl pairs face each other at the spine`() {
        // rtl 은 라이브러리 순번이 역순 → 첫 읽기 페이지(index 0)가 오른쪽 슬롯
        assertEquals(0f, spreadAlignX(index = 0, count = 10, rtl = true))
        assertEquals(1f, spreadAlignX(index = 1, count = 10, rtl = true))
        assertEquals(0f, spreadAlignX(index = 2, count = 10, rtl = true))
    }
}
