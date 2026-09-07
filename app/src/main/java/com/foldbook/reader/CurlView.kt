package com.foldbook.reader

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import androidx.core.animation.doOnEnd
import kotlin.math.hypot

/**
 * 실사 종이 페이지-컬 뷰. GLSurfaceView 없이 하드웨어 가속 Canvas.drawBitmapMesh 로만 그린다.
 * 일반 View 라서 홈 갔다 오기·폴드/펼침·회전에 재생성/컨텍스트 손실 없이 그대로 다시 그린다.
 *
 * page: 현재 화면의 '먼저 읽는' 페이지 번호(1-based). 단면=그 페이지. 양면=그 페이지와 다음 페이지의 스프레드.
 * RTL 이면 먼저 읽는 페이지가 오른쪽 슬롯이다. 이미지는 절대 좌우 반전하지 않는다.
 */
@SuppressLint("ClickableViewAccessibility")
class CurlView(
    context: Context,
    private val source: PageSource,
    startPage: Int,
    private val rtl: Boolean,
    doublePage: Boolean,
) : View(context) {

    var onPageSettled: ((pageNumber: Int) -> Unit)? = null
    var onBoundary: ((forward: Boolean) -> Unit)? = null
    var onTap: (() -> Unit)? = null

    val pageCount get() = source.count
    val currentPage get() = page

    private var doublePage = doublePage
    private var page = startPage.coerceIn(1, maxOf(1, source.count)).let { if (this.doublePage) alignOdd(it) else it }

    private val step get() = if (doublePage) 2 else 1

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var dragging = false

    private val verts = FloatArray((CurlEngine.COLS + 1) * 3 * 2)
    private val meshRows = 2

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dstRect = RectF()
    private val srcRect = Rect()

    /** 진행 중인 컬. */
    private class Curl(
        val forward: Boolean,
        val curlPage: Int,       // 말리는 비트맵의 페이지 번호
        val behindPage: Int,     // 그 뒤로 드러나는 페이지
        val otherLeafPage: Int,  // 양면에서 안 말리는 반쪽 (단면이면 0)
        val regionLeft: Float,
        val regionRight: Float,
        val bindLeft: Boolean,
        var t: Float = 0f,
    )

    private var curl: Curl? = null
    private var animator: ValueAnimator? = null

    init {
        setBackgroundColor(Color.BLACK)
        source.onReady = { invalidate() }
    }

    // ---- 크기/설정 -------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        applySize(w, h)
    }

    private fun applySize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        cancelCurl()
        val pw = if (doublePage) w / 2 else w
        source.setPageSize(pw, h)
        source.focus(page - 1)
        invalidate()
    }

    /** 폴드/펼침 등으로 양면 여부가 바뀌었을 때 (Activity 재생성 없이) 호출. */
    fun onConfigChanged(wantDouble: Boolean) {
        if (wantDouble == doublePage) return
        doublePage = wantDouble
        if (doublePage) page = alignOdd(page)
        if (width > 0 && height > 0) applySize(width, height)
    }

    fun onPause() { /* 일반 View — 특별히 해제할 GL 자원 없음 */ }
    fun onResume() { invalidate(); source.focus(page - 1) }

    // ---- 외부 제어 -----------------------------------------------------------

    fun goTo(pageNumber: Int) {
        cancelCurl()
        page = pageNumber.coerceIn(1, maxOf(1, pageCount)).let { if (doublePage) alignOdd(it) else it }
        source.focus(page - 1)
        onPageSettled?.invoke(page)
        invalidate()
    }

    /** 손대지 않고 다음 페이지로 (폴드 접힘 제스처 등). */
    fun flipForward() {
        if (curl != null || animator != null) return
        val c = beginCurl(forward = true) ?: run { onBoundary?.invoke(true); return }
        curl = c
        animateTo(1f, commit = true)
    }

    // ---- 터치 --------------------------------------------------------------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (animator != null) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; downT = SystemClock.uptimeMillis(); dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(e.x - downX, e.y - downY) > touchSlop) {
                    dragging = true
                    val forward = decideForward(dirRight = e.x >= downX)
                    curl = beginCurl(forward) ?: run {
                        dragging = false
                        onBoundary?.invoke(forward)
                        null
                    }
                }
                curl?.let { c ->
                    c.t = CurlEngine.tFromFinger(
                        e.x.coerceIn(0f, width.toFloat()), c.regionLeft, c.regionRight, c.bindLeft,
                    )
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val isTap = !dragging &&
                    SystemClock.uptimeMillis() - downT < 220 &&
                    hypot(e.x - downX, e.y - downY) <= touchSlop
                val c = curl
                if (c != null) {
                    if (c.t > 0.5f) animateTo(1f, commit = true) else animateTo(0f, commit = false)
                } else if (isTap) {
                    onTap?.invoke()
                }
            }
            MotionEvent.ACTION_CANCEL -> if (curl != null) animateTo(0f, commit = false)
        }
        return true
    }

    /** 드래그 방향 + (양면) 반쪽 위치로 forward/backward 결정. */
    private fun decideForward(dirRight: Boolean): Boolean {
        return if (!doublePage) {
            if (rtl) dirRight else !dirRight            // RTL: 오른쪽으로 = 다음
        } else {
            val rightLeaf = downX >= width / 2f
            // LTR: 오른쪽 leaf 넘김 = 다음.  RTL: 왼쪽 leaf 넘김 = 다음.
            if (rtl) !rightLeaf else rightLeaf
        }
    }

    /** 경계면 null. 아니면 컬 상태 구성. */
    private fun beginCurl(forward: Boolean): Curl? {
        if (forward && page + step > pageCount) return null
        if (!forward && page - step < 1) return null

        val w = width.toFloat()
        val bindLeft = forward == !rtl

        if (!doublePage) {
            val curlPage = if (forward) page else page - 1
            val behind = if (forward) page + 1 else page
            return Curl(forward, curlPage, behind, 0, 0f, w, bindLeft)
        }

        // 양면: 넘어가는 반쪽만 말린다.
        val rightLeaf = forward == !rtl
        val rl = if (rightLeaf) w / 2f else 0f
        val rr = if (rightLeaf) w else w / 2f
        val curlPage: Int
        val behind: Int
        val other: Int
        if (forward) {
            curlPage = page + 1          // 현재 스프레드에서 넘어가는 쪽
            behind = page + 3           // 다음 스프레드의 같은 쪽
            other = page                // 그대로 있는 반쪽
        } else {
            curlPage = page             // 현재 스프레드의 먼저 읽는 쪽이 되말린다
            behind = page - 2
            other = page + 1
        }
        return Curl(forward, curlPage, behind, other, rl, rr, bindLeft)
    }

    private fun animateTo(target: Float, commit: Boolean) {
        val c = curl ?: return
        animator?.cancel()
        animator = ValueAnimator.ofFloat(c.t, target).apply {
            duration = 280
            interpolator = DecelerateInterpolator()
            addUpdateListener { c.t = it.animatedValue as Float; invalidate() }
            doOnEnd {
                animator = null
                if (commit) {
                    page = if (c.forward) page + step else page - step
                    page = page.coerceIn(1, maxOf(1, pageCount))
                    if (doublePage) page = alignOdd(page)
                    curl = null
                    source.focus(page - 1)
                    onPageSettled?.invoke(page)
                } else {
                    curl = null
                }
                invalidate()
            }
            start()
        }
    }

    private fun cancelCurl() {
        animator?.cancel(); animator = null; curl = null
    }

    // ---- 그리기 ----------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val c = curl
        if (c == null) {
            drawSpread(canvas, page, w, h)
            return
        }
        // 목적지(드러나는) 레이어
        if (!doublePage) {
            drawPageInto(canvas, c.behindPage, 0f, 0f, w, h)
        } else {
            // 안 말리는 반쪽 + 말리는 반쪽 뒤에 드러나는 페이지
            val leftPage: Int
            val rightPage: Int
            val curlIsRight = c.regionLeft >= w / 2f
            if (curlIsRight) {
                leftPage = c.otherLeafPage; rightPage = c.behindPage
            } else {
                leftPage = c.behindPage; rightPage = c.otherLeafPage
            }
            drawPageInto(canvas, leftPage, 0f, 0f, w / 2f, h)
            drawPageInto(canvas, rightPage, w / 2f, 0f, w, h)
        }
        // 말리는 페이지 메시
        drawCurlMesh(canvas, c, h)
    }

    private fun drawSpread(canvas: Canvas, first: Int, w: Float, h: Float) {
        if (!doublePage) {
            drawPageInto(canvas, first, 0f, 0f, w, h)
        } else {
            val leftPage = if (rtl) first + 1 else first
            val rightPage = if (rtl) first else first + 1
            drawPageInto(canvas, leftPage, 0f, 0f, w / 2f, h)
            drawPageInto(canvas, rightPage, w / 2f, 0f, w, h)
        }
    }

    private fun drawPageInto(canvas: Canvas, pageNumber: Int, l: Float, t: Float, r: Float, b: Float) {
        val idx = pageNumber - 1
        val bmp = if (idx in 0 until source.count) source.bitmapOrGray(idx) else source.placeholder()
        if (bmp.isRecycled) return
        srcRect.set(0, 0, bmp.width, bmp.height)
        dstRect.set(l, t, r, b)
        canvas.drawBitmap(bmp, srcRect, dstRect, bmpPaint)
    }

    private fun drawCurlMesh(canvas: Canvas, c: Curl, h: Float) {
        val idx = c.curlPage - 1
        val bmp = if (idx in 0 until source.count) source.bitmapOrGray(idx) else source.placeholder()
        if (bmp.isRecycled) return

        val curlX = CurlEngine.build(c.t, c.regionLeft, c.regionRight, h, c.bindLeft, verts, meshRows)
        canvas.drawBitmapMesh(bmp, CurlEngine.COLS, meshRows, verts, 0, null, 0, bmpPaint)

        // 접힌 능선 그림자 (curlX 부근 얇은 어두운 그라디언트)
        val band = (c.regionRight - c.regionLeft) * 0.06f
        if (curlX in -band..(width + band)) {
            val x0 = (curlX - band).coerceIn(0f, width.toFloat())
            val x1 = (curlX + band).coerceIn(0f, width.toFloat())
            if (x1 - x0 > 1f) {
                shadePaint.shader = LinearGradient(
                    x0, 0f, x1, 0f,
                    intArrayOf(0x00000000, 0x55000000, 0x00000000),
                    floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
                )
                canvas.drawRect(x0, 0f, x1, h, shadePaint)
                shadePaint.shader = null
            }
        }
        // 말려 올라간 쪽을 살짝 어둡게
        val dim = (0x33 * c.t).toInt().coerceIn(0, 0x55)
        shadePaint.color = (dim shl 24)
        val bind = if (c.bindLeft) c.regionLeft else c.regionRight
        canvas.drawRect(
            minOf(bind, curlX), 0f, maxOf(bind, curlX), h, shadePaint,
        )
        shadePaint.color = Color.TRANSPARENT
    }

    private fun alignOdd(p: Int): Int = if (p % 2 == 0) (p - 1).coerceAtLeast(1) else p
}
