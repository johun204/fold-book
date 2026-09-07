package com.foldbook

import android.graphics.Bitmap

/** eschao 페이지 번호(1-based) -> 실제 비트맵 또는 회색 플레이스홀더. */
class PageImageProvider(val stream: PageStream) {

    val count get() = stream.count

    fun setPageSize(w: Int, h: Int) = stream.setSize(w, h)

    /** 항상 non-null. 아직 안 받은 페이지는 회색. */
    fun bitmap(pageNumber: Int): Bitmap {
        val i = pageNumber - 1
        if (i !in 0 until count) return stream.placeholder()
        return stream.bitmap(i) ?: stream.placeholder()
    }

    /** 해당 페이지의 실제 이미지가 준비됐는지 (회색 아님). */
    fun isReal(pageNumber: Int): Boolean {
        val i = pageNumber - 1
        return i in 0 until count && stream.bitmap(i) != null
    }
}
