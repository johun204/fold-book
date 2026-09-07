package com.foldbook.reader

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 종이 한 장이 원통을 감듯 말리는 페이지-컬 기하. 프레임워크 의존이 없어 단위 테스트로 고정한다.
 *
 * 좌표계는 뷰 픽셀. 커링 영역은 세로 전체([0,ph]) + 가로 [regionLeft, regionRight] 이다.
 * - bindLeft=true  : 왼쪽 모서리가 제본(고정), 자유단은 오른쪽
 * - bindLeft=false : 오른쪽 모서리가 제본, 자유단은 왼쪽
 * t 는 0(완전히 평평, 자유단 제자리) ~ 1(완전히 말려 제본선 너머로 사라짐). 항상 [0,1] 로 클램프하므로
 * "절반에서 멈춤" 같은 상태가 없다 — 화면 끝에서 끝까지 연속.
 *
 * 세로 곡률은 넣지 않는다(페이지 넘김엔 x 변형만으로 충분하고 훨씬 안정적). 결과 vertsOut 은
 * Canvas.drawBitmapMesh 형식으로 (COLS+1)*(rows+1) 개의 (x,y) 쌍을 행 우선으로 채운다.
 */
object CurlEngine {

    /** 가로 분할 수. (COLS+1) 개의 세로선. */
    const val COLS = 24

    /** 자유단이 제본선 너머로 얼마나 더 이동하는지(영역 폭 배수). t=1 에서 페이지가 화면 밖으로. */
    private const val TRAVEL = 1.18f

    /** 원통 반지름(영역 폭 배수). t 가 커질수록 살짝 조여든다. */
    private const val RADIUS0 = 0.14f
    private const val RADIUS_TIGHTEN = 0.55f

    /**
     * 손가락 x 로부터 진행도 t. t=0 은 손가락이 자유단에 있을 때(평평), t=1 은 제본선까지 끌었을 때.
     * bindLeft=true → 자유단은 오른쪽이므로 손가락이 왼쪽으로 갈수록 t 증가.
     */
    fun tFromFinger(fingerX: Float, regionLeft: Float, regionRight: Float, bindLeft: Boolean): Float {
        val w = (regionRight - regionLeft).coerceAtLeast(1f)
        val raw = if (bindLeft) (regionRight - fingerX) / w else (fingerX - regionLeft) / w
        return raw.coerceIn(0f, 1f)
    }

    /**
     * 컬 정점을 [vertsOut] 에 채운다.
     * @param vertsOut 크기 >= (COLS+1)*(rows+1)*2
     * @param rows 세로 격자 칸 수(2면 충분, drawBitmapMesh 의 meshHeight)
     * @return 컬 라인의 x (뷰 픽셀) — 그림자 그리기에 사용
     */
    fun build(
        t: Float,
        regionLeft: Float,
        regionRight: Float,
        ph: Float,
        bindLeft: Boolean,
        vertsOut: FloatArray,
        rows: Int = 2,
    ): Float {
        val tt = t.coerceIn(0f, 1f)
        val regionW = (regionRight - regionLeft).coerceAtLeast(1f)
        val dir = if (bindLeft) 1f else -1f            // 자유단 → 제본선 방향
        val freeX = if (bindLeft) regionRight else regionLeft
        val curlX = freeX - dir * tt * regionW * TRAVEL
        val radius = (regionW * RADIUS0 * (1f - RADIUS_TIGHTEN * tt)).coerceAtLeast(regionW * 0.02f)
        val halfCirc = PI.toFloat() * radius

        var k = 0
        for (r in 0..rows) {
            val y = ph * r / rows
            for (c in 0..COLS) {
                val x0 = regionLeft + regionW * c / COLS
                val d = dir * (x0 - curlX)             // 컬 라인 기준 자유단 쪽 거리 (>0 이면 말리는 부분)
                val x = when {
                    d <= 0f -> x0                                        // 아직 평평
                    d < halfCirc -> curlX + dir * radius * sin(d / radius)  // 원통을 감는 부분
                    else -> curlX - dir * (d - halfCirc)                 // 뒤로 젖혀진 부분(페이지 뒷면)
                }
                vertsOut[k++] = x
                vertsOut[k++] = y
            }
        }
        return curlX
    }

    /** d<halfCirc 인 마지막 정점의 z 깊이 비율(0~1) — 필요시 음영에 사용. 현재는 참고용. */
    fun peekDepth(t: Float): Float {
        val tt = t.coerceIn(0f, 1f)
        return (1f - cos(PI.toFloat() * tt)) * 0.5f
    }
}
