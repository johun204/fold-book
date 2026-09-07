package com.comicviewer

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class SpreadPolicyTest {

    private val a = File("a.jpg") // 단면
    private val b = File("b.jpg") // 양면 스캔본
    private val files = listOf(a, b)
    private val isSpread: (File) -> Boolean = { it == b }

    @Test fun `rtl splits spread into right then left`() {
        val pages = SpreadPolicy.expand(files, splitSpreads = true, dir = ReadingDirection.RTL, isSpread = isSpread)
        assertEquals(
            listOf(
                ReaderPage(a, Half.WHOLE),
                ReaderPage(b, Half.RIGHT),
                ReaderPage(b, Half.LEFT),
            ),
            pages,
        )
    }

    @Test fun `ltr splits spread into left then right`() {
        val pages = SpreadPolicy.expand(files, splitSpreads = true, dir = ReadingDirection.LTR, isSpread = isSpread)
        assertEquals(listOf(Half.WHOLE, Half.LEFT, Half.RIGHT), pages.map { it.half })
    }

    @Test fun `no split keeps every image whole`() {
        val pages = SpreadPolicy.expand(files, splitSpreads = false, dir = ReadingDirection.RTL, isSpread = isSpread)
        assertEquals(listOf(Half.WHOLE, Half.WHOLE), pages.map { it.half })
    }

    @Test fun `mode truth table`() {
        // AUTO: 좁은 화면이면 쪼개고, 넓은 화면이면 양면
        assertEquals(true, SpreadPolicy.splitSpreads(SpreadMode.AUTO, wideScreen = false))
        assertEquals(false, SpreadPolicy.splitSpreads(SpreadMode.AUTO, wideScreen = true))
        assertEquals(true, SpreadPolicy.doublePage(SpreadMode.AUTO, wideScreen = true))
        // SINGLE: 항상 한 장
        assertEquals(true, SpreadPolicy.splitSpreads(SpreadMode.SINGLE, wideScreen = true))
        assertEquals(false, SpreadPolicy.doublePage(SpreadMode.SINGLE, wideScreen = true))
        // DOUBLE: 항상 양면
        assertEquals(false, SpreadPolicy.splitSpreads(SpreadMode.DOUBLE, wideScreen = false))
        assertEquals(true, SpreadPolicy.doublePage(SpreadMode.DOUBLE, wideScreen = false))
    }
}
