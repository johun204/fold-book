package com.foldbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SessionCacheTest {

    private fun session(root: File, name: String, modified: Long, vararg sizes: Int): File {
        val d = File(root, name).apply { mkdirs() }
        File(d, "manifest.json").writeText("{}")
        sizes.forEachIndexed { i, n -> File(d, "img_$i").writeBytes(ByteArray(n)) }
        d.setLastModified(modified)
        return d
    }

    private fun imageCount(d: File) = d.listFiles()!!.count { it.name != "manifest.json" }

    @Test fun `over cap evicts oldest sessions first but keeps manifests and the protected one`() {
        val root = Files.createTempDirectory("cache-test").toFile()
        val old = session(root, "old", 1_000_000L, 400, 400)
        val mid = session(root, "mid", 2_000_000L, 400)
        val cur = session(root, "cur", 3_000_000L, 400, 400)

        SessionCache.enforceCap(listOf(cur, mid, old), protectId = "cur", cap = 1300)

        assertEquals(0, imageCount(old))             // 가장 오래된 것부터 비운다
        assertEquals(1, imageCount(mid))             // 상한 아래로 내려오면 멈춘다
        assertEquals(2, imageCount(cur))
        assertTrue(File(old, "manifest.json").exists())
    }

    @Test fun `protected session survives even when it alone exceeds the cap`() {
        val root = Files.createTempDirectory("cache-test").toFile()
        val old = session(root, "old", 1_000_000L, 100)
        val cur = session(root, "cur", 3_000_000L, 5000)

        SessionCache.enforceCap(listOf(old, cur), protectId = "cur", cap = 1000)

        assertEquals(0, imageCount(old))
        assertEquals(1, imageCount(cur))
    }

    @Test fun `under cap nothing is deleted`() {
        val root = Files.createTempDirectory("cache-test").toFile()
        val a = session(root, "a", 1_000_000L, 100)
        SessionCache.enforceCap(listOf(a), protectId = null, cap = 1000)
        assertEquals(1, imageCount(a))
    }
}
