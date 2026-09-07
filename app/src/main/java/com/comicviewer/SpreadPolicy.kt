package com.comicviewer

import java.io.File

enum class ReadingDirection { LTR, RTL }
enum class SpreadMode { AUTO, SINGLE, DOUBLE }
enum class Half { WHOLE, LEFT, RIGHT }

/** 한 화면 페이지가 참조하는 원본 이미지와, 좌우 스캔본일 때 어느 절반인지. */
data class ReaderPage(val file: File, val half: Half)

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
     * 원본 이미지 목록을 화면 페이지 목록으로 확장한다.
     * splitSpreads 가 true 이고 해당 이미지가 좌우 스캔본이면 두 장(읽기 방향 순서)으로 나눈다.
     */
    fun expand(
        files: List<File>,
        splitSpreads: Boolean,
        dir: ReadingDirection,
        isSpread: (File) -> Boolean,
    ): List<ReaderPage> {
        val out = ArrayList<ReaderPage>(files.size)
        for (f in files) {
            if (splitSpreads && isSpread(f)) {
                val halves =
                    if (dir == ReadingDirection.RTL) listOf(Half.RIGHT, Half.LEFT)
                    else listOf(Half.LEFT, Half.RIGHT)
                halves.forEach { out += ReaderPage(f, it) }
            } else {
                out += ReaderPage(f, Half.WHOLE)
            }
        }
        return out
    }
}
