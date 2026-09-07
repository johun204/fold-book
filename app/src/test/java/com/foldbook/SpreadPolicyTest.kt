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

    @Test fun `rtl splits spread into right then left`() {
        val pages = SpreadPolicy.expand(images, splitSpreads = true, dir = ReadingDirection.RTL, dims = dims)
        assertEquals(
            listOf(
                PageRef("a", "a.jpg", Half.WHOLE),
                PageRef("b", "b.jpg", Half.RIGHT),
                PageRef("b", "b.jpg", Half.LEFT),
            ),
            pages,
        )
    }

    @Test fun `ltr splits spread into left then right`() {
        val pages = SpreadPolicy.expand(images, splitSpreads = true, dir = ReadingDirection.LTR, dims = dims)
        assertEquals(listOf(Half.WHOLE, Half.LEFT, Half.RIGHT), pages.map { it.half })
    }

    @Test fun `no split keeps every image whole`() {
        val pages = SpreadPolicy.expand(images, splitSpreads = false, dir = ReadingDirection.RTL, dims = dims)
        assertEquals(listOf(Half.WHOLE, Half.WHOLE), pages.map { it.half })
    }

    @Test fun `unknown dims never split (remote)`() {
        val pages = SpreadPolicy.expand(images, splitSpreads = true, dir = ReadingDirection.RTL, dims = { null })
        assertEquals(listOf(Half.WHOLE, Half.WHOLE), pages.map { it.half })
    }

    @Test fun `mode truth table`() {
        assertEquals(true, SpreadPolicy.splitSpreads(SpreadMode.AUTO, wideScreen = false))
        assertEquals(false, SpreadPolicy.splitSpreads(SpreadMode.AUTO, wideScreen = true))
        assertEquals(true, SpreadPolicy.doublePage(SpreadMode.AUTO, wideScreen = true))
        assertEquals(true, SpreadPolicy.splitSpreads(SpreadMode.SINGLE, wideScreen = true))
        assertEquals(false, SpreadPolicy.doublePage(SpreadMode.SINGLE, wideScreen = true))
        assertEquals(false, SpreadPolicy.splitSpreads(SpreadMode.DOUBLE, wideScreen = false))
        assertEquals(true, SpreadPolicy.doublePage(SpreadMode.DOUBLE, wideScreen = false))
    }
}
