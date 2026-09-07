package com.foldbook

import android.graphics.Bitmap
import com.foldbook.reader.PageSource

/**
 * eschao 페이지 번호(1-based, "라이브러리 순번") → 실제 비트맵.
 *
 * rtl 이면 라이브러리 순번을 읽기 순번의 역순으로 매핑한다. 그러면 eschao 는 LTR 그대로 동작하면서도
 * '앞으로(forward) 넘기기'가 읽기상 이전 파일을, '뒤로(backward) 넘기기'가 읽기상 다음 파일을 가리키게 된다.
 * 넘김 효과 자체는 좌우 반전 없이 원본 그대로다.
 */
class PageImageProvider(val source: PageSource, private val rtl: Boolean = false) {

    val count get() = source.count

    /** 라이브러리 순번(1-based) → 읽기 인덱스(0-based). */
    private fun idx(libPage: Int): Int = if (rtl) count - libPage else libPage - 1

    fun setPageSize(w: Int, h: Int) = source.setPageSize(w, h)

    /** 항상 non-null. 아직 안 받은 페이지는 회색. */
    fun bitmap(libPage: Int): Bitmap {
        val i = idx(libPage)
        if (i !in 0 until count) return source.placeholder()
        return source.bitmap(i) ?: source.placeholder()
    }

    /** 해당 페이지의 실제 이미지가 준비됐는지 (회색 아님). */
    fun isReal(libPage: Int): Boolean {
        val i = idx(libPage)
        return i in 0 until count && source.bitmap(i) != null
    }

    /** 라이브러리 순번 기준 창(window) 포커스. */
    fun focus(libPage: Int) = source.focus(idx(libPage).coerceIn(0, maxOf(0, count - 1)))
}
