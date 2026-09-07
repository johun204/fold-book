package com.foldbook

enum class ReadingDirection { LTR, RTL }
enum class SpreadMode { AUTO, SINGLE, DOUBLE }
enum class Half { WHOLE, LEFT, RIGHT }

object SpreadPolicy {

    /** 가로/세로 비가 이 값 이상이면 좌우 양면 스캔본으로 간주한다. */
    const val SPREAD_ASPECT = 1.0f

    /** 단면 이미지를 두 장씩 묶어 양면으로 보여줄지 (eschao 오토 페이지 모드). */
    fun doublePage(mode: SpreadMode, wideScreen: Boolean): Boolean = when (mode) {
        SpreadMode.SINGLE -> false
        SpreadMode.DOUBLE -> true
        SpreadMode.AUTO -> wideScreen
    }

    /** 빈 페이지(양면 정렬용 여백). entryId 가 비어 있으면 PageStream 이 어두운 여백으로 그린다. */
    val BLANK = PageRef("", "", Half.WHOLE)

    /**
     * 이미지 목록을 리더 페이지 목록으로 확장한다.
     * - 좌우 양면 스캔본(가로가 긴 이미지)은 화면·기기와 무관하게 **항상** 두 장으로 나눈다
     *   (페이지 인덱스가 일정 → 이어보기 안정, 양면 모드에선 두 절반이 두 슬롯을 채워 스프레드로 보임).
     * - 절반 순서는 읽기 방향을 따른다(RTL=오른쪽 먼저). 뷰가 RTL 에서 좌우 반전되므로 이 순서 그대로가 화면에 맞다.
     * - 양면 모드에선 스프레드가 (홀,짝) 페어에 통째로 들어가도록 필요하면 앞에 빈 페이지를 끼운다.
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
        return out
    }
}
