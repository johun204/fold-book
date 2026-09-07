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
 * rtl=true(일본 만화) 면 뷰 전체를 좌우로 뒤집는다(scaleX=-1):
 *  - 프레임워크가 터치 좌표도 같이 뒤집어 주므로 '왼쪽→오른쪽' 드래그 = 다음 페이지, 컬도 손가락을 따라간다.
 *  - 뒤집힌 화면에서 글자가 정방향으로 보이도록 PageStream(mirror=true) 이 텍스처를 미리 좌우 반전한다.
 *    (원본 파일은 건드리지 않음 — 그리는 순간에만 반전)
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
    private var downT = 0L
    private var dragging = false

    init {
        if (rtl) scaleX = -1f
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
        val x = e.x   // rtl 이면 프레임워크가 scaleX=-1 의 역변환을 적용한 좌표가 들어온다
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
        // 폭 제한 없이 화면 끝까지 드래그해도 곡선이 이어진다 (PageFlip.java 가 과도 드래그를 가장자리에 고정).
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
        } catch (e: Exception) {
            // 폴드/펼침으로 표면이 재구성되는 순간의 일시적 상태(텍스처·페이지 미준비 등)로 죽지 않도록.
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

    /** 표면 크기가 바뀐 뒤 렌더러(단면/양면)를 맞추고 페이지 크기를 다시 잡는다. lock 안에서 실행. */
    private fun applySurface() {
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
    }

    /**
     * 폴드/펼침 등으로 화면 크기·형태가 바뀌었을 때 (Activity 재생성 없이) 양면 여부를 다시 판단한다.
     * 표면 크기 자체는 GLSurfaceView 가 onSurfaceChanged 를 다시 불러 반영한다.
     */
    fun onConfigChanged(wantAutoPage: Boolean) {
        queueEvent {
            try {
                lock.lock()
                try {
                    pageFlip.enableAutoPage(wantAutoPage)
                    if (width > 0 && height > 0) {
                        pageFlip.onSurfaceChanged(width, height)
                    }
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
