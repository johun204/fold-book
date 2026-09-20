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

    private var spread: Boolean? = null

    /**
     * 양면 모드에서는 이미지가 페이지보다 좁아도 가운데(책등) 쪽에 딱 붙이고 여백은 바깥쪽에 둔다.
     * 왼쪽 슬롯 = 라이브러리 홀수 페이지, 오른쪽 슬롯 = 짝수 페이지(PageRender.Double 과 같은 규칙).
     */
    fun setSpread(double: Boolean) {
        if (spread == double) return
        spread = double
        source.setAlign { index ->
            if (!double) 0.5f else spreadAlignX(index, count, rtl)
        }
    }

    /** 항상 non-null. 아직 안 받은 페이지는 회색. */
    fun bitmap(libPage: Int): Bitmap {
        val i = idx(libPage)
        if (i !in 0 until count) return source.placeholder()
        return source.bitmap(i) ?: source.placeholder()
    }

    /**
     * 접힌 종이 **뒷면** 텍스처용 비트맵 (좌우 반전).
     *
     * eschao 는 뒷면을 '종이 좌표' 그대로 찍는다. 종이가 책등을 축으로 넘어가면 화면에서는
     * 좌우가 뒤집혀 보이고, 넘김이 끝나 평평해진 순간의 그림이 정지 화면과 좌우가 반대가 된다.
     * 가운데(책등) 정렬을 쓰기 전에는 여백이 좌우 대칭이라 티가 안 났지만, 여백을 바깥쪽으로
     * 몰고 나서는 **넘김 중에는 검은 여백이 가운데**에 있다가 끝나는 순간 바깥으로 튀었다.
     * 미리 좌우를 뒤집어 건네면 넘김이 끝난 모습이 정지 화면과 정확히 같아진다.
     *
     * 호출자는 GL 업로드가 끝나면 recycle 해야 한다.
     */
    fun backBitmap(libPage: Int): Bitmap = source.mirrored(bitmap(libPage))

    /** 해당 페이지의 실제 이미지가 준비됐는지 (회색 아님). */
    fun isReal(libPage: Int): Boolean {
        val i = idx(libPage)
        return i in 0 until count && source.bitmap(i) != null
    }

    /** 라이브러리 순번 기준 창(window) 포커스. */
    fun focus(libPage: Int) = source.focus(idx(libPage).coerceIn(0, maxOf(0, count - 1)))
}

/**
 * 양면 모드에서 읽기 인덱스(0-based) [index] 이미지의 가로 정렬. 0=왼쪽 끝, 1=오른쪽 끝.
 *
 * 라이브러리 순번이 홀수면 왼쪽 슬롯([PageRender.Double] 과 같은 규칙)이므로 오른쪽(책등)에 붙이고,
 * 짝수면 오른쪽 슬롯이므로 왼쪽(책등)에 붙인다. 남는 여백은 화면 바깥쪽으로 간다.
 */
fun spreadAlignX(index: Int, count: Int, rtl: Boolean): Float {
    val libPage = if (rtl) count - index else index + 1
    return if (libPage % 2 == 1) 1f else 0f
}
