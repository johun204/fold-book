package com.foldbook

import com.foldbook.reader.CurlEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurlEngineTest {

    private val cols = CurlEngine.COLS
    private val rows = 2
    private fun verts() = FloatArray((cols + 1) * (rows + 1) * 2)

    @Test fun `t=0 leaves the page flat (identity grid)`() {
        val v = verts()
        CurlEngine.build(0f, 0f, 100f, 200f, bindLeft = true, vertsOut = v, rows = rows)
        // 첫 행의 각 열 x 는 원래 격자점과 같아야 한다
        for (c in 0..cols) {
            val x = v[c * 2]
            assertEquals(100f * c / cols, x, 0.01f)
        }
    }

    @Test fun `t=1 pushes the free edge off screen`() {
        val v = verts()
        CurlEngine.build(1f, 0f, 100f, 200f, bindLeft = true, vertsOut = v, rows = rows)
        val freeEdgeX = v[cols * 2]          // 첫 행 마지막 열 = 자유단(오른쪽)
        assertTrue("free edge should be curled far left, was $freeEdgeX", freeEdgeX < 0f)
    }

    @Test fun `tFromFinger clamps and follows drag direction`() {
        // bindLeft: 자유단은 오른쪽. 손가락이 오른쪽 끝 → t=0, 왼쪽 끝 → t=1
        assertEquals(0f, CurlEngine.tFromFinger(100f, 0f, 100f, bindLeft = true), 0.001f)
        assertEquals(1f, CurlEngine.tFromFinger(0f, 0f, 100f, bindLeft = true), 0.001f)
        assertEquals(0.5f, CurlEngine.tFromFinger(50f, 0f, 100f, bindLeft = true), 0.001f)
        // 영역 밖은 클램프
        assertEquals(1f, CurlEngine.tFromFinger(-40f, 0f, 100f, bindLeft = true), 0.001f)
        assertEquals(0f, CurlEngine.tFromFinger(140f, 0f, 100f, bindLeft = true), 0.001f)
    }

    @Test fun `bindRight is the mirror of bindLeft`() {
        // bindRight: 자유단은 왼쪽. 손가락 왼쪽 끝 → t=0, 오른쪽 끝 → t=1
        assertEquals(0f, CurlEngine.tFromFinger(0f, 0f, 100f, bindLeft = false), 0.001f)
        assertEquals(1f, CurlEngine.tFromFinger(100f, 0f, 100f, bindLeft = false), 0.001f)

        val vL = verts(); val vR = verts()
        CurlEngine.build(0.5f, 0f, 100f, 200f, bindLeft = true, vertsOut = vL, rows = rows)
        CurlEngine.build(0.5f, 0f, 100f, 200f, bindLeft = false, vertsOut = vR, rows = rows)
        // 같은 t 에서 bindRight 의 열 x 는 bindLeft 의 좌우 반전과 대칭이어야 한다
        for (c in 0..cols) {
            val xL = vL[c * 2]
            val xR = vR[(cols - c) * 2]
            assertEquals("col $c", 100f - xL, xR, 0.5f)
        }
    }

    @Test fun `curl line sweeps from free edge toward bind as t grows`() {
        val v = verts()
        val x0 = CurlEngine.build(0.2f, 0f, 100f, 200f, bindLeft = true, vertsOut = v, rows = rows)
        val x1 = CurlEngine.build(0.8f, 0f, 100f, 200f, bindLeft = true, vertsOut = v, rows = rows)
        assertTrue("curl line should move left as t grows ($x0 -> $x1)", x1 < x0)
    }
}
