package com.foldbook

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
 * 종이책 3D 페이지 넘김 뷰. 드래그하는 손가락 위치를 따라 페이지가 말린다 (eschao PageFlip).
 * doublePage=true 면 가로 화면에서 좌우 두 페이지를 함께 보여준다 (세로에서는 자동으로 한 장).
 * rtl=true(일본 만화) 면 넘김 방향만 뒤집어 '왼쪽→오른쪽' 드래그로 다음 페이지(다음 이미지)가 넘어가게 한다.
 * (이미지 내용은 그대로. 스프레드 스캔본은 SpreadPolicy 에서 오른쪽 절반부터 순서를 잡는다.)
 * ponytail: 좌표만 반전 → 컬 애니메이션은 LTR 기준(반대쪽에서 말림). 완전한 RTL 컬은 PageFlip 엔진 포크 필요.
 */
class PageFlipView(
    context: Context,
    private val provider: PageImageProvider,
    startPage: Int,
    doublePage: Boolean,
    private val rtl: Boolean = false,
    private val duration: Int = 900,
) : GLSurfaceView(context), GLSurfaceView.Renderer {

    /** 첫 페이지에서 뒤로 / 마지막 페이지에서 앞으로 넘기려 할 때 (메인 스레드). */
    var onBoundary: ((forward: Boolean) -> Unit)? = null

    /** 페이지가 넘어가 자리잡을 때마다 현재 페이지 번호(1-based) 통지 (메인 스레드). */
    var onPageSettled: ((pageNumber: Int) -> Unit)? = null

    /** 드래그가 아닌 단순 탭 (오버레이 표시/숨김용, 메인 스레드). */
    var onTap: (() -> Unit)? = null

    val pageCount get() = provider.count
    fun currentPageNumber(): Int = render.pageNo

    private val lock = ReentrantLock()
    private val pageFlip = PageFlip(context)
    private val handler: Handler
    private var render: PageRender

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private var downT = 0L
    private var dragging = false

    /** RTL 이면 넘김 엔진에 넣는 x 를 좌우로 뒤집어, 왼→오 드래그가 '다음 페이지'가 되게 한다. */
    private fun ex(x: Float) = if (rtl) width - x else x

    init {
        pageFlip.setSemiPerimeterRatio(0.8f)
            .setShadowWidthOfFoldEdges(5f, 60f, 0.3f)
            .setShadowWidthOfFoldBase(5f, 80f, 0.4f)
            .setPixelsOfMesh(10)
            .enableAutoPage(doublePage)

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

        render = makeRender(PageRender.Single::class.java, startPage)

        // 실제 이미지가 백그라운드에서 도착하면 다시 그려 회색->실제 교체
        provider.stream.onReady = { queueEvent { requestRender() } }

        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    private fun makeRender(cls: Class<out PageRender>, pageNo: Int): PageRender {
        val ob: (Boolean) -> Unit = { f -> onBoundary?.invoke(f) }
        val os: (Int) -> Unit = { n -> provider.stream.focus(n - 1); onPageSettled?.invoke(n) }
        return if (cls == PageRender.Double::class.java)
            PageRender.Double(pageFlip, handler, provider, pageNo, ob, os)
        else
            PageRender.Single(pageFlip, handler, provider, pageNo, ob, os)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = ex(e.x)   // 넘김 엔진 좌표 (RTL 이면 좌우 반전)
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; downRawX = e.x; downRawY = e.y
                downT = SystemClock.uptimeMillis(); dragging = false
                fingerDown(x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && (kotlin.math.hypot(e.x - downRawX, e.y - downRawY) > touchSlop)) dragging = true
                if (dragging) fingerMove(x, y)
            }
            MotionEvent.ACTION_UP -> {
                val isTap = !dragging &&
                    SystemClock.uptimeMillis() - downT < 220 &&
                    kotlin.math.hypot(e.x - downRawX, e.y - downRawY) <= touchSlop
                pageFlip.onFingerUp(
                    x.coerceIn(0f, width.toFloat()), y.coerceIn(0f, height.toFloat()), duration,
                )
                lock.lock()
                try { if (render.onFingerUp()) requestRender() } finally { lock.unlock() }
                if (isTap) onTap?.invoke()
            }
            MotionEvent.ACTION_CANCEL -> fingerUp(x, y)
        }
        return true
    }

    /** 스크러버에서 특정 페이지로 점프. */
    fun goTo(pageNumber: Int) {
        queueEvent {
            lock.lock()
            try {
                render.jumpTo(pageNumber)
                provider.stream.focus(render.pageNo - 1)
            } finally {
                lock.unlock()
            }
            requestRender()
        }
    }

    /** 손대지 않고 다음 페이지로 넘긴다 (폴더블 접힘 제스처 등). 오른쪽→왼쪽 스와이프 흉내. */
    fun flipForward() {
        if (width == 0 || pageFlip.isAnimating) return
        val y = height / 2f
        downX = width * 0.92f
        downY = y
        fingerDown(width * 0.92f, y)
        fingerMove(width * 0.55f, y)
        fingerMove(width * 0.2f, y)
        fingerUp(width * 0.06f, y)
    }

    private fun fingerDown(x: Float, y: Float) {
        if (!pageFlip.isAnimating && pageFlip.firstPage != null) pageFlip.onFingerDown(x, y)
    }

    private fun fingerMove(x: Float, y: Float) {
        if (pageFlip.isAnimating) return
        // 손가락을 뗄 때(fingerUp)까지는 자동으로 넘기지 않는다.
        // 드래그 이동량을 폭의 72% 로 제한 → eschao 가 반대편 절반을 넘어가도 곡선을 계속 갱신(멈춤 방지).
        val maxX = width * 0.72f
        val maxY = height * 0.72f
        val cx = x.coerceIn(downX - maxX, downX + maxX).coerceIn(0f, width.toFloat())
        val cy = y.coerceIn(downY - maxY, downY + maxY).coerceIn(0f, height.toFloat())
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
        // 뗀 위치를 기준으로 eschao 가 완료할지 되돌릴지 결정한다.
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
        } finally {
            lock.unlock()
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        try {
            pageFlip.onSurfaceChanged(width, height)
            lock.lock()
            try {
                val wantDouble = pageFlip.secondPage != null
                if (wantDouble && render !is PageRender.Double) {
                    val n = render.pageNo
                    render.release()
                    render = makeRender(PageRender.Double::class.java, n)
                } else if (!wantDouble && render !is PageRender.Single) {
                    val n = render.pageNo
                    render.release()
                    render = makeRender(PageRender.Single::class.java, n)
                }
                render.onSurfaceChanged()
                provider.stream.focus(render.pageNo - 1)
            } finally {
                lock.unlock()
            }
        } catch (e: Exception) {
            Log.e("PageFlipView", "onSurfaceChanged failed", e)
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            pageFlip.onSurfaceCreated()
        } catch (e: Exception) {
            Log.e("PageFlipView", "onSurfaceCreated failed", e)
        }
    }
}
