package com.foldbook

import kotlinx.serialization.Serializable

@Serializable
enum class ReadingDirection { LTR, RTL }
enum class SpreadMode { AUTO, SINGLE, DOUBLE }

/**
 * 이미지를 페이지 칸에 어떻게 맞출지.
 * - [BOTH] 잘리는 곳 없이 전부 보이게 (남는 쪽에 검은 여백) — 기본값
 * - [WIDTH] 가로를 꽉 채움 (세로가 넘치면 위아래가 잘림)
 * - [HEIGHT] 세로를 꽉 채움 (가로가 넘치면 좌우가 잘림)
 */
enum class FitMode { BOTH, WIDTH, HEIGHT }
enum class Half { WHOLE, LEFT, RIGHT }

object SpreadPolicy {

    /**
     * 가로/세로 비가 이 값 이상이면 좌우 양면 스캔본으로 간주한다.
     * 양면 스캔은 보통 1.3~1.5. 정사각형에 가까운 한 장짜리 그림(표지·삽화)을 반으로 자르지 않도록 여유를 둔다.
     */
    const val SPREAD_ASPECT = 1.2f

    /** 단면 이미지를 두 장씩 묶어 양면으로 보여줄지. */
    fun doublePage(mode: SpreadMode, wideScreen: Boolean): Boolean = when (mode) {
        SpreadMode.SINGLE -> false
        SpreadMode.DOUBLE -> true
        SpreadMode.AUTO -> wideScreen
    }

    /** 빈 페이지(양면 정렬용 여백). entryId 가 비어 있으면 PageSource 가 어두운 여백으로 그린다. */
    val BLANK = PageRef("", "", Half.WHOLE)

    /**
     * 이미지 목록을 리더 페이지 목록으로 확장한다.
     * - 좌우 양면 스캔본(가로가 긴 이미지)은 화면·기기와 무관하게 **항상** 두 장으로 나눈다
     *   (양면 모드에선 두 절반이 두 슬롯을 채워 스프레드로 보임).
     * - 절반 순서는 읽기 방향을 따른다(RTL=오른쪽 먼저). 읽기 순번이므로 single·double 동일하고,
     *   화면 배치는 PageImageProvider 가 RTL 에서 라이브러리 순번을 역순으로 매핑해 맞춘다.
     * - 빈 페이지가 모드·방향에 따라 달라지므로 페이지 번호는 일정하지 않다. 이어보기 위치는
     *   번호가 아니라 이미지로 찾는다([pageRefAt] / [pageNumberOf]).
     * - 양면 모드에선 스프레드가 한 페어에 통째로 들어가도록 필요하면 앞에 빈 페이지를 끼운다.
     *   RTL 은 라이브러리 순번이 읽기 순번의 **역순**이라, 전체 장수가 홀수면 페어가 한 칸 밀려
     *   스프레드의 두 반쪽이 서로 다른 페어로 찢어진다. 그래서 끝에 빈 페이지를 하나 더해
     *   장수를 짝수로 맞춘다 (`spreadAlignX` 와 같은 '왼쪽 슬롯 = 라이브러리 홀수' 규칙).
     * - dims 가 null(원격 등 크기 미상)이면 스프레드 판정 불가 → 통짜.
     */
    fun expand(
        images: List<Entry>,
        dir: ReadingDirection,
        doubleMode: Boolean,
        split: Boolean,
        dims: (entryId: String) -> Pair<Int, Int>?,
    ): List<PageRef> {
        val out = ArrayList<PageRef>(images.size)
        val halves = if (dir == ReadingDirection.RTL) listOf(Half.RIGHT, Half.LEFT)
        else listOf(Half.LEFT, Half.RIGHT)
        for (e in images) {
            val d = if (split) dims(e.id) else null
            val spread = d != null && d.first > 0 && d.second > 0 &&
                d.first.toFloat() / d.second >= SPREAD_ASPECT
            if (spread) {
                if (doubleMode && out.size % 2 == 1) out += BLANK  // 스프레드를 한 페어에
                halves.forEach { out += PageRef(e.id, e.name, it) }
            } else {
                out += PageRef(e.id, e.name, Half.WHOLE)
            }
        }
        if (doubleMode && dir == ReadingDirection.RTL && out.size % 2 == 1) out += BLANK
        return out
    }
}

/** [pageNumber](1-based) 가 보여주는 이미지. 빈 페이지(양면 정렬용)면 같은 펼침면·이웃 페이지의 이미지. */
fun pageRefAt(pages: List<PageRef>, pageNumber: Int): PageRef? {
    val i = pageNumber - 1
    return listOf(i, i xor 1, i + 1, i - 1)
        .firstNotNullOfOrNull { k -> pages.getOrNull(k)?.takeIf { it.entryId.isNotEmpty() } }
}

/** 그 이미지(가능하면 같은 반쪽)를 보여주는 페이지 번호(1-based). 없으면 null. */
fun pageNumberOf(pages: List<PageRef>, entryId: String?, half: Half?): Int? {
    if (entryId.isNullOrEmpty()) return null
    val exact = if (half == null) -1 else pages.indexOfFirst { it.entryId == entryId && it.half == half }
    val i = if (exact >= 0) exact else pages.indexOfFirst { it.entryId == entryId }
    return if (i >= 0) i + 1 else null
}
