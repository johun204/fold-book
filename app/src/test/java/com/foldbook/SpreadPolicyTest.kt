package com.foldbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        // RTL: 오른쪽 먼저 (읽기 순번).
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
        assertEquals(listOf(Half.WHOLE), pages.filter { it.entryId.isNotEmpty() }.map { it.half })
    }

    /**
     * 라이브러리는 (홀,짝) 순번을 한 페어로 그린다. RTL 은 라이브러리 순번이 읽기 순번의 역순이라
     * 전체 장수가 홀수면 페어가 한 칸 밀린다 = 스프레드의 두 반쪽이 서로 다른 페어로 찢어진다.
     */
    private fun pairOf(readingIndex: Int, count: Int, rtl: Boolean): Int {
        val lib = if (rtl) count - readingIndex else readingIndex + 1
        return (lib + 1) / 2
    }

    private fun assertSpreadInOnePair(pages: List<PageRef>, entryId: String, rtl: Boolean) {
        val idx = pages.indices.filter { pages[it].entryId == entryId }
        assertEquals(2, idx.size)
        assertEquals(
            "스프레드 두 반쪽이 같은 페어에 있어야 한다: ${pages.map { it.entryId + it.half }}",
            pairOf(idx[0], pages.size, rtl),
            pairOf(idx[1], pages.size, rtl),
        )
    }

    @Test fun `rtl keeps spread halves in one pair when a single follows`() {
        // 스프레드 뒤에 낱장 -> 장수가 홀수가 되어 RTL 페어가 밀리던 경우
        val pages = SpreadPolicy.expand(listOf(b, a), ReadingDirection.RTL, doubleMode = true, split = true, dims = dims)
        assertSpreadInOnePair(pages, "b", rtl = true)
    }

    @Test fun `rtl keeps spread halves in one pair when a single precedes`() {
        val pages = SpreadPolicy.expand(images, ReadingDirection.RTL, doubleMode = true, split = true, dims = dims)
        assertSpreadInOnePair(pages, "b", rtl = true)
    }

    @Test fun `ltr keeps spread halves in one pair either way`() {
        assertSpreadInOnePair(
            SpreadPolicy.expand(listOf(b, a), ReadingDirection.LTR, doubleMode = true, split = true, dims = dims),
            "b", rtl = false,
        )
        assertSpreadInOnePair(
            SpreadPolicy.expand(images, ReadingDirection.LTR, doubleMode = true, split = true, dims = dims),
            "b", rtl = false,
        )
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

    @Test fun `near-square image is not treated as a spread`() {
        val sq = Entry("s", "s.jpg", false)
        val pages = SpreadPolicy.expand(listOf(sq), ReadingDirection.LTR, doubleMode = false, split = true) { 1000 to 1000 }
        assertEquals(listOf(Half.WHOLE), pages.map { it.half })
    }

    @Test fun `resume position survives switching between single and double view`() {
        // 낱장 3 + 스프레드 + 낱장: 양면 모드에서는 스프레드 앞에 빈 페이지가 끼어 번호가 밀린다.
        val c = Entry("c", "c.jpg", false)
        val d = Entry("d", "d.jpg", false)
        val e = Entry("e", "e.jpg", false)
        val imgs = listOf(a, c, d, b, e)
        val single = SpreadPolicy.expand(imgs, ReadingDirection.RTL, doubleMode = false, split = true, dims = dims)
        val double = SpreadPolicy.expand(imgs, ReadingDirection.RTL, doubleMode = true, split = true, dims = dims)

        val n = pageNumberOf(single, "e", Half.WHOLE)!!
        val ref = pageRefAt(single, n)!!
        val m = pageNumberOf(double, ref.entryId, ref.half)!!
        assertEquals("e", double[m - 1].entryId)
        assertTrue(n != m)                               // 번호로 복원했다면 다른 그림이 나왔을 자리

        // 스프레드의 반쪽도 같은 반쪽으로 돌아온다
        val left = pageNumberOf(double, "b", Half.LEFT)!!
        assertEquals(Half.LEFT, single[pageNumberOf(single, "b", pageRefAt(double, left)!!.half)!! - 1].half)
    }

    @Test fun `blank page resolves to its neighbour image and unknown image to null`() {
        val pages = SpreadPolicy.expand(images, ReadingDirection.RTL, doubleMode = true, split = true, dims = dims)
        assertEquals("", pages[1].entryId)
        assertEquals("a", pageRefAt(pages, 2)!!.entryId)   // 빈 페이지 -> 같은 펼침면의 그림
        assertEquals(null, pageNumberOf(pages, "zzz", null))
        assertEquals(null, pageNumberOf(pages, null, null))
    }
}
