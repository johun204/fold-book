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
 * 종이책 3D 페이지 넘김 뷰 (eschao PageFlip). 드래그하는 손가락을 따라 페이지가 말린다.
 * doublePage=true 면 가로 화면에서 좌우 두 페이지를 함께 보여준다.
 *
 * rtl=true(일본 만화) 는 **효과를 좌우로 뒤집지 않는다.** [PageImageProvider] 가 라이브러리 순번을
 * 읽기 순번의 역순으로 매핑하므로, 라이브러리는 LTR 그대로 동작하고 "왼쪽→오른쪽 드래그"(eschao 의
 * backward flip)가 읽기상 다음 파일을, "오른쪽→왼쪽 드래그"가 이전 파일을 불러온다.
 * 즉 넘김 애니메이션은 원본 그대로이고, 다음/이전 파일 계산만 반대가 된다.
 */
class PageFlipView(
    context: Context,
    private val provider: PageImageProvider,
    startPageReading: Int,
    doublePage: Boolean,
    private val rtl: Boolean = false,
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
                downT = SystemClock.uptimeMillis(); dragging = false
                fingerDown(x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && (kotlin.math.hypot(x - downX, y - downY) > touchSlop)) dragging = true
                if (dragging) fingerMove(x, y)
            }
            MotionEvent.ACTION_UP -> {
                val isTap = !dragging &&
                    SystemClock.uptimeMillis() - downT < 220 &&
                    kotlin.math.hypot(x - downX, y - downY) <= touchSlop
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

    /** 손대지 않고 읽기상 다음 페이지로 (폴더블 접힘 제스처). rtl 이면 왼→오, 아니면 오→왼 스와이프 흉내. */
    fun flipForward() {
        if (width == 0 || pageFlip.isAnimating) return
        val y = height / 2f
        if (rtl) {
            downX = width * 0.08f; downY = y
            fingerDown(width * 0.08f, y)
            fingerMove(width * 0.45f, y)
            fingerMove(width * 0.8f, y)
            fingerUp(width * 0.94f, y)
        } else {
            downX = width * 0.92f; downY = y
            fingerDown(width * 0.92f, y)
            fingerMove(width * 0.55f, y)
            fingerMove(width * 0.2f, y)
            fingerUp(width * 0.06f, y)
        }
    }

    private fun fingerDown(x: Float, y: Float) {
        if (!pageFlip.isAnimating && pageFlip.firstPage != null) pageFlip.onFingerDown(x, y)
    }

    private fun fingerMove(x: Float, y: Float) {
        if (pageFlip.isAnimating) return
        // 손가락을 뗄 때까지 자동으로 넘기지 않는다. 폭 제한 없이 화면 끝까지 드래그해도 곡선이 이어진다.
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

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            pageFlip.onSurfaceCreated()
        } catch (e: Exception) {
            Log.e("PageFlipView", "onSurfaceCreated failed", e)
        }
    }
}
