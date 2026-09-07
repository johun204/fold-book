package com.foldbook

enum class ReadingDirection { LTR, RTL }
enum class SpreadMode { AUTO, SINGLE, DOUBLE }
enum class Half { WHOLE, LEFT, RIGHT }

object SpreadPolicy {

    /** 가로/세로 비가 이 값 이상이면 좌우 양면 스캔본으로 간주한다. */
    const val SPREAD_ASPECT = 1.0f

    /** 좌우 스캔본을 반으로 쪼개서 한 쪽씩 보여줄지. */
    fun splitSpreads(mode: SpreadMode, wideScreen: Boolean): Boolean = when (mode) {
        SpreadMode.SINGLE -> true
        SpreadMode.DOUBLE -> false
        SpreadMode.AUTO -> !wideScreen
    }

    /** 단면 이미지를 두 장씩 묶어 양면으로 보여줄지 (eschao 오토 페이지 모드). */
    fun doublePage(mode: SpreadMode, wideScreen: Boolean): Boolean = when (mode) {
        SpreadMode.SINGLE -> false
        SpreadMode.DOUBLE -> true
        SpreadMode.AUTO -> wideScreen
    }

    /**
     * 이미지 목록을 리더 페이지 목록으로 확장한다.
     * dims(id) 가 크기를 주고 splitSpreads=true 이며 가로가 길면 두 장(읽기 방향 순서)으로 나눈다.
     * dims 가 null 이면(원격 등 크기 미상) 통짜로 둔다.
     */
    fun expand(
        images: List<Entry>,
        splitSpreads: Boolean,
        dir: ReadingDirection,
        dims: (entryId: String) -> Pair<Int, Int>?,
    ): List<PageRef> {
        val out = ArrayList<PageRef>(images.size)
        for (e in images) {
            val d = if (splitSpreads) dims(e.id) else null
            val spread = d != null && d.first > 0 && d.second > 0 &&
                d.first.toFloat() / d.second >= SPREAD_ASPECT
            if (spread) {
                val halves = if (dir == ReadingDirection.RTL) listOf(Half.RIGHT, Half.LEFT)
                else listOf(Half.LEFT, Half.RIGHT)
                halves.forEach { out += PageRef(e.id, e.name, it) }
            } else {
                out += PageRef(e.id, e.name, Half.WHOLE)
            }
        }
        return out
    }
}
