package com.comicviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class ComicFolderTest {

    @Test fun `pages are natural-ordered and non-images excluded`() {
        val root = Files.createTempDirectory("comic").toFile()
        val vol = root.resolve("Vol 2").apply { mkdirs() }
        listOf("10.jpg", "9.jpg", "1.jpg", "notes.txt", "2.png").forEach {
            vol.resolve(it).writeText("x")
        }

        val pages = ComicFolder(vol).pages.map { it.name }
        assertEquals(listOf("1.jpg", "2.png", "9.jpg", "10.jpg"), pages)
    }

    @Test fun `sibling folder navigation follows natural order`() {
        val root = Files.createTempDirectory("comic").toFile()
        listOf("Vol 1", "Vol 2", "Vol 10").forEach {
            root.resolve(it).apply { mkdirs() }.resolve("1.jpg").writeText("x")
        }

        val vol2 = ComicFolder(root.resolve("Vol 2"))
        assertEquals("Vol 10", vol2.sibling(+1)?.name)
        assertEquals("Vol 1", vol2.sibling(-1)?.name)

        val vol10 = ComicFolder(root.resolve("Vol 10"))
        assertNull(vol10.sibling(+1))
    }
}
