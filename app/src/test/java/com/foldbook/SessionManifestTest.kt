package com.foldbook

import com.foldbook.reader.SessionManifestStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SessionManifestTest {

    private fun tempDir() = Files.createTempDirectory("manifest-test").toFile()

    private val imgs = listOf(
        Entry("id-a", "001.jpg", isDir = false),
        Entry("id-b", "002.jpg", isDir = false),
        Entry("id-c", "003.jpg", isDir = false),
    )

    @Test fun `round trips entries and dims`() {
        val dir = tempDir()
        SessionManifestStore.save(
            dir, connectionId = "drive1", folderId = "folder-x",
            direction = ReadingDirection.RTL,
            images = imgs,
            dims = mapOf("id-a" to (2000 to 1400), "id-b" to null, "id-c" to (800 to 1200)),
        )

        val m = SessionManifestStore.load(dir)!!
        assertEquals("drive1", m.connectionId)
        assertEquals("folder-x", m.folderId)
        assertEquals(ReadingDirection.RTL, m.direction)
        assertEquals(listOf("id-a", "id-b", "id-c"), m.entryIds())
        assertEquals("002.jpg", m.entries[1].name)
        assertEquals(2000 to 1400, m.dimOf("id-a"))
        assertEquals(800 to 1200, m.dimOf("id-c"))
        assertNull(m.dimOf("id-b"))                  // null dims 는 저장 안 함
        assertEquals(3, m.asEntries().size)
        assertTrue(m.asEntries().none { it.isDir })
    }

    @Test fun `missing manifest returns null`() {
        assertNull(SessionManifestStore.load(tempDir()))
    }

    @Test fun `entry list change is detectable`() {
        val dir = tempDir()
        SessionManifestStore.save(dir, "c", "f", ReadingDirection.LTR, imgs, emptyMap())
        val known = SessionManifestStore.load(dir)!!.entryIds()
        val changed = listOf("id-a", "id-b")        // 한 장 삭제됨
        assertTrue(known != changed)
    }
}
