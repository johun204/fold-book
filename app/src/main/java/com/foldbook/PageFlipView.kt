package com.foldbook

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.eschao.android.widget.pageflip.PageFlip
import java.util.concurrent.locks.ReentrantLock
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 종이책 3D 페이지 넘김 뷰 (eschao PageFlip). 드래그하는 손가락을 따라 페이지가 말린다.
 * doublePage=true 면 가로 화면에서 좌우 두 페이지를 함께 보여준다.
 *
 * 어느 쪽으로 끌든 **현재 페이지가 접히면서 넘어갈 페이지가 드러난다**(라이브러리를 고쳐 왼쪽
 * 가장자리 기준 접힘도 앞으로 넘김으로 처리). 드래그는 손을 뗄 때까지 손가락을 따라가며,
 * 페이지 폭의 절반을 넘겼으면 넘어가고 아니면 제자리로 돌아간다.
 *
 * rtl=true(일본 만화) 는 **효과를 좌우로 뒤집지 않는다.** [PageImageProvider] 가 라이브러리 순번을
 * 읽기 순번의 역순으로 매핑하므로, 라이브러리는 LTR 그대로 동작하고 "왼쪽→오른쪽 드래그"가
 * 읽기상 다음 파일을, "오른쪽→왼쪽 드래그"가 이전 파일을 불러온다.
 * 즉 넘김 애니메이션은 원본 그대로이고, 다음/이전 파일 계산만 반대가 된다.
 */
class PageFlipView(
    context: Context,
    private val provider: PageImageProvider,
    startPageReading: Int,
    doublePage: Boolean,
    private val rtl: Boolean = false,
    /** 단순 터치로 페이지를 넘기는 좌/우 가장자리 영역 비율. 0 이면 터치 넘김 끔(가운데 탭 = 오버레이). */
    var tapZone: Float = 0f,
    private val duration: Int = 900,
) : GLSurfaceView(context), GLSurfaceView.Renderer {

    /** 읽기상 첫 페이지에서 뒤로 / 마지막 페이지에서 앞으로 넘기려 할 때 (메인 스레드). forward=읽기 기준. */
    var onBoundary: ((forward: Boolean) -> Unit)? = null

    /** 페이지가 자리잡을 때마다 현재 '읽기' 페이지 번호(1-based) 통지 (메인 스레드). */
    var onPageSettled: ((pageNumber: Int) -> Unit)? = null

    /** 드래그가 아닌 단순 탭 (오버레이 표시/숨김용, 메인 스레드). */
    var onTap: (() -> Unit)? = null

    val pageCount get() = provider.count
    fun currentPageNumber(): Int = toReading(render.pageNo)

    private val lock = ReentrantLock()
    private val pageFlip = PageFlip(context)
    private val handler: Handler
    private var render: PageRender

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var dragging = false
    private var deferredDown = false   // 넘김 진행 중에 눌러서 잡기를 미뤄둔 상태
    private var synthAnim: ValueAnimator? = null   // 탭·접힘 제스처의 합성 드래그

    /** 읽기 순번 ↔ 라이브러리 순번 (rtl 이면 역순). 둘 다 1-based. */
    private fun toLib(readingPage: Int): Int =
        if (rtl) (provider.count + 1 - readingPage).coerceIn(1, maxOf(1, provider.count)) else readingPage
    private fun toReading(libPage: Int): Int =
        if (rtl) (provider.count + 1 - libPage).coerceIn(1, maxOf(1, provider.count)) else libPage

    init {
        pageFlip.setSemiPerimeterRatio(0.8f)
            .setShadowWidthOfFoldEdges(5f, 60f, 0.3f)
            .setShadowWidthOfFoldBase(5f, 80f, 0.4f)
            .setPixelsOfMesh(10)
            .enableClickToFlip(false)          // 탭 넘김은 tapZone 으로 직접 처리
            .setMaskAlphaOfFold(235)           // 단면 모드 접힘 뒷면을 어둡게 → 뒷면에 다음 페이지 내용이 비치지 않도록
        pageFlip.enableAutoPage(doublePage)    // boolean 반환이라 체인 끝에서 분리 호출

        setEGLContextClientVersion(2)

        handler = Handler(Looper.getMainLooper()) { msg ->
            if (msg.what == MSG_ENDED_DRAWING_FRAME) {
                lock.lock()
                try {
                    if (render.onEndedDrawing(msg.arg1)) requestRender()
                } finally {
                    lock.unlock()
                }
            }
            true
        }

        var libStart = toLib(startPageReading)
        if (doublePage && libStart % 2 == 0) libStart = (libStart - 1).coerceAtLeast(1)
        render = makeRender(PageRender.Single::class.java, libStart)

        provider.source.onReady = { queueEvent { requestRender() } }

        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    private fun makeRender(cls: Class<out PageRender>, libPageNo: Int): PageRender {
        val ob: (Boolean) -> Unit = { f -> onBoundary?.invoke(if (rtl) !f else f) }
        val os: (Int) -> Unit = { n -> provider.focus(n); onPageSettled?.invoke(toReading(n)) }
        return if (cls == PageRender.Double::class.java)
            PageRender.Double(pageFlip, handler, provider, libPageNo, ob, os)
        else
            PageRender.Single(pageFlip, handler, provider, libPageNo, ob, os)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y
                downT = SystemClock.uptimeMillis()
                dragging = false
                synthAnim?.cancel()          // 탭 넘김 중이었으면 그 자리에서 손 뗀 것으로 마무리
                // 넘김이 진행 중이면 아직 잡지 않는다. 손가락이 실제로 움직이거나(드래그)
                // 가장자리 탭으로 확정될 때 진행 중인 넘김을 확정하고 새 넘김을 시작한다.
                // (그냥 화면을 건드린 것만으로 애니메이션이 끊기지 않게)
                deferredDown = renderBusy()
                if (!deferredDown) fingerDown(x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && (kotlin.math.hypot(x - downX, y - downY) > touchSlop)) {
                    dragging = true
                    if (deferredDown) {
                        deferredDown = false
                        settleFlip()
                        fingerDown(downX, downY)
                    }
                }
                if (dragging) fingerMove(x, y)
            }
            MotionEvent.ACTION_UP -> {
                val isTap = !dragging &&
                    SystemClock.uptimeMillis() - downT < 220 &&
                    kotlin.math.hypot(x - downX, y - downY) <= touchSlop
                if (!deferredDown) fingerUp(x, y)   // 잡지 않았으면 놓을 것도 없다
                deferredDown = false
                if (isTap) onEdgeTapOrOverlay(x)
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!deferredDown) fingerUp(x, y)
                deferredDown = false
            }
        }
        return true
    }

    /** 탭이 좌/우 가장자리 영역이면 페이지 넘김, 가운데면 오버레이 토글. */
    private fun onEdgeTapOrOverlay(x: Float) {
        val z = tapZone
        when {
            z > 0f && x < width * z -> if (rtl) flipForward() else flipBackward()
            z > 0f && x > width * (1f - z) -> if (rtl) flipBackward() else flipForward()
            else -> onTap?.invoke()
        }
    }

    /** 스크러버에서 특정 '읽기' 페이지로 점프. */
    fun goTo(readingPage: Int) {
        queueEvent {
            lock.lock()
            try {
                render.jumpTo(toLib(readingPage))
                provider.focus(render.pageNo)
            } finally {
                lock.unlock()
            }
            requestRender()
        }
    }

    /** 손대지 않고 읽기상 다음/이전 페이지로 (폴더블 접힘 제스처·가장자리 탭). */
    fun flipForward() = synthFlip(fromRightEdge = !rtl)
    fun flipBackward() = synthFlip(fromRightEdge = rtl)

    /**
     * fromRightEdge=true → 오른쪽에서 왼쪽으로 접기, false → 왼쪽에서 오른쪽으로 접기.
     *
     * 손가락으로 끄는 것처럼 [SYNTH_DRAG_MS] 동안 천천히 끌어서 페이지 절반을 살짝 넘긴 뒤 놓는다.
     * (예전엔 곧바로 끝까지 끌어버려 넘김 효과가 거의 안 보였다)
     */
    private fun synthFlip(fromRightEdge: Boolean) {
        if (width == 0) return
        synthAnim?.cancel()
        settleFlip()
        if (pageFlip.isAnimating) return

        val y = height / 2f
        val w = width.toFloat()
        val from = if (fromRightEdge) w * 0.92f else w * 0.08f
        val to = if (fromRightEdge) w * 0.40f else w * 0.60f   // 되돌림 임계(페이지 절반)를 넘긴 지점
        downX = from; downY = y
        fingerDown(from, y)
        synthAnim = ValueAnimator.ofFloat(from, to).apply {
            duration = SYNTH_DRAG_MS
            addUpdateListener { fingerMove(it.animatedValue as Float, y) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    synthAnim = null
                    fingerUp(to, y)          // 나머지 절반은 라이브러리 스크롤러가 duration 동안
                }
            })
            start()
        }
    }

    /** 진행 중인 넘김이 있으면 그 자리에서 끝난 것으로 확정한다 (넘김 도중 또 넘기기). */
    private fun settleFlip() {
        lock.lock()
        try {
            if (render.settleNow()) requestRender()
        } finally {
            lock.unlock()
        }
    }

    private fun renderBusy(): Boolean {
        lock.lock()
        try {
            return render.busy
        } finally {
            lock.unlock()
        }
    }

    private fun fingerDown(x: Float, y: Float) {
        if (!pageFlip.isAnimating && pageFlip.firstPage != null) pageFlip.onFingerDown(x, y)
    }

    private fun fingerMove(x: Float, y: Float) {
        if (pageFlip.isAnimating) return
        val cx = x.coerceIn(0f, width.toFloat())
        val cy = y.coerceIn(0f, height.toFloat())
        if (pageFlip.onFingerMove(cx, cy)) {
            lock.lock()
            try {
                if (render.onFingerMove()) requestRender()
            } finally {
                lock.unlock()
            }
        }
    }

    private fun fingerUp(x: Float, y: Float) {
        if (pageFlip.isAnimating) return
        pageFlip.onFingerUp(x.coerceIn(0f, width.toFloat()), y.coerceIn(0f, height.toFloat()), duration)
        lock.lock()
        try {
            if (render.onFingerUp()) requestRender()
        } finally {
            lock.unlock()
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        lock.lock()
        try {
            render.onDrawFrame()
        } catch (e: Exception) {
            Log.e("PageFlipView", "onDrawFrame failed", e)
        } finally {
            lock.unlock()
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        try {
            pageFlip.onSurfaceChanged(width, height)
            applySurface()
        } catch (e: Exception) {
            Log.e("PageFlipView", "onSurfaceChanged failed", e)
        }
    }

    private fun applySurface() {
        lock.lock()
        try {
            val wantDouble = pageFlip.secondPage != null
            if (wantDouble && render !is PageRender.Double) {
                val n = render.pageNo
                render.release()
                render = makeRender(PageRender.Double::class.java, if (n % 2 == 0) (n - 1).coerceAtLeast(1) else n)
            } else if (!wantDouble && render !is PageRender.Single) {
                val n = render.pageNo
                render.release()
                render = makeRender(PageRender.Single::class.java, n)
            }
            render.onSurfaceChanged()
            provider.focus(render.pageNo)
        } finally {
            lock.unlock()
        }
    }

    /** 폴드/펼침 등으로 화면이 바뀌었을 때 (Activity 재생성 없이) 양면 여부를 다시 판단한다. */
    fun onConfigChanged(wantAutoPage: Boolean) {
        queueEvent {
            try {
                lock.lock()
                try {
                    pageFlip.enableAutoPage(wantAutoPage)
                    if (width > 0 && height > 0) pageFlip.onSurfaceChanged(width, height)
                } finally {
                    lock.unlock()
                }
                applySurface()
            } catch (e: Exception) {
                Log.e("PageFlipView", "onConfigChanged failed", e)
            }
            requestRender()
        }
    }

    private companion object {
        /** 탭·접힘 제스처로 넘길 때 합성 드래그에 쓰는 시간(ms). 길수록 넘어가는 게 잘 보인다. */
        const val SYNTH_DRAG_MS = 420L
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            pageFlip.onSurfaceCreated()
        } catch (e: Exception) {
            Log.e("PageFlipView", "onSurfaceCreated failed", e)
        }
    }
}
