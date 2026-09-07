package com.foldbook

import org.junit.Assert.assertEquals
import org.junit.Test

class SpreadPolicyTest {

    private val a = Entry("a", "a.jpg", false)   // 단면 (800x1200)
    private val b = Entry("b", "b.jpg", false)   // 양면 (2000x1400)
    private val images = listOf(a, b)
    private val dims: (String) -> Pair<Int, Int>? = { id ->
        if (id == "b") 2000 to 1400 else 800 to 1200
    }

    @Test fun `single-page view rtl splits spread right then left`() {
        val pages = SpreadPolicy.expand(images, ReadingDirection.RTL, doubleMode = false, split = true, dims = dims)
        assertEquals(
            listOf(
                PageRef("a", "a.jpg", Half.WHOLE),
                PageRef("b", "b.jpg", Half.RIGHT),
                PageRef("b", "b.jpg", Half.LEFT),
            ),
            pages,
        )
    }

    @Test fun `single-page view ltr splits spread left then right`() {
        val pages = SpreadPolicy.expand(images, ReadingDirection.LTR, doubleMode = false, split = true, dims = dims)
        assertEquals(listOf(Half.WHOLE, Half.LEFT, Half.RIGHT), pages.map { it.half })
    }

    @Test fun `double mode splits spread following reading direction`() {
        // RTL: 오른쪽 먼저. 뷰가 좌우 반전(scaleX=-1)되므로 이 순서 그대로가 화면에 맞다.
        val pages = SpreadPolicy.expand(listOf(b), ReadingDirection.RTL, doubleMode = true, split = true, dims = dims)
        assertEquals(listOf(Half.RIGHT, Half.LEFT), pages.map { it.half })
        assertEquals(listOf("b", "b"), pages.map { it.entryId })
    }

    @Test fun `double mode inserts a blank so spread fills one pair`() {
        // a(단면) 뒤에 스프레드 -> 홀수 경계라 앞에 빈 페이지 삽입 -> 스프레드가 (홀,짝) 페어에 통째로
        val pages = SpreadPolicy.expand(images, ReadingDirection.RTL, doubleMode = true, split = true, dims = dims)
        assertEquals(listOf(Half.WHOLE, Half.WHOLE, Half.RIGHT, Half.LEFT), pages.map { it.half })
        assertEquals("", pages[1].entryId)              // 빈 페이지
    }

    @Test fun `single-page scan is never split`() {
        val pages = SpreadPolicy.expand(listOf(a), ReadingDirection.RTL, doubleMode = true, split = true, dims = dims)
        assertEquals(listOf(Half.WHOLE), pages.map { it.half })
    }

    @Test fun `unknown dims never split (remote)`() {
        val pages = SpreadPolicy.expand(images, ReadingDirection.RTL, doubleMode = false, split = true, dims = { null })
        assertEquals(listOf(Half.WHOLE, Half.WHOLE), pages.map { it.half })
    }

    @Test fun `split off keeps spreads whole`() {
        val pages = SpreadPolicy.expand(images, ReadingDirection.RTL, doubleMode = false, split = false, dims = dims)
        assertEquals(listOf(Half.WHOLE, Half.WHOLE), pages.map { it.half })
    }

    @Test fun `doublePage mode truth table`() {
        assertEquals(false, SpreadPolicy.doublePage(SpreadMode.AUTO, wideScreen = false))
        assertEquals(true, SpreadPolicy.doublePage(SpreadMode.AUTO, wideScreen = true))
        assertEquals(false, SpreadPolicy.doublePage(SpreadMode.SINGLE, wideScreen = true))
        assertEquals(true, SpreadPolicy.doublePage(SpreadMode.DOUBLE, wideScreen = false))
    }
}
