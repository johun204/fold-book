package com.comicviewer

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalOrderTest {

    private fun sorted(vararg names: String) =
        names.toList().sortedWith { a, b -> NaturalOrder.compare(a, b) }

    @Test fun `numbers sort by value not lexically`() {
        assertEquals(
            listOf("2.jpg", "9.jpg", "10.jpg", "11.jpg", "100.jpg"),
            sorted("10.jpg", "100.jpg", "2.jpg", "11.jpg", "9.jpg"),
        )
    }

    @Test fun `leading zeros are ignored for ordering`() {
        assertEquals(
            listOf("008.png", "9.png", "10.png"),
            sorted("10.png", "008.png", "9.png"),
        )
    }

    @Test fun `letters mixed with numbers`() {
        assertEquals(
            listOf("ch1_p9.jpg", "ch1_p10.jpg", "ch2_p1.jpg"),
            sorted("ch2_p1.jpg", "ch1_p10.jpg", "ch1_p9.jpg"),
        )
    }

    @Test fun `case insensitive`() {
        assertEquals(
            listOf("Page1.jpg", "page2.jpg", "PAGE10.jpg"),
            sorted("PAGE10.jpg", "page2.jpg", "Page1.jpg"),
        )
    }

    @Test fun `same base different extension is stable-ish`() {
        assertEquals(listOf("1.jpg", "1.png"), sorted("1.png", "1.jpg"))
    }
}
