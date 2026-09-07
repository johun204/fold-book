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
     * - 좌우 양면 스캔본(가로가 긴 이미지)은 **항상** 두 장으로 나눈다. 화면·기기와 무관하게
     *   페이지 인덱스가 일정하고(이어보기 안정), 양면 모드에선 좌/우 절반이 두 슬롯을 채워 원래 스프레드처럼 보인다.
     * - 양면 모드: 두 절반을 시각적 좌→우 순서로, 그리고 스프레드가 항상 (홀,짝) 페어에 통째로 들어가도록
     *   필요하면 앞에 빈 페이지 하나를 끼운다. 단면(한 쪽씩) 볼 땐 읽기 방향만 따른다.
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
        for (e in images) {
            val d = if (split) dims(e.id) else null
            val spread = d != null && d.first > 0 && d.second > 0 &&
                d.first.toFloat() / d.second >= SPREAD_ASPECT
            if (spread) {
                if (doubleMode && out.size % 2 == 1) out += BLANK  // 왼쪽 절반을 페어의 왼쪽으로
                val halves = when {
                    doubleMode -> listOf(Half.LEFT, Half.RIGHT)
                    dir == ReadingDirection.RTL -> listOf(Half.RIGHT, Half.LEFT)
                    else -> listOf(Half.LEFT, Half.RIGHT)
                }
                halves.forEach { out += PageRef(e.id, e.name, it) }
            } else {
                out += PageRef(e.id, e.name, Half.WHOLE)
            }
        }
        return out
    }
}
