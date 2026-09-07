package com.foldbook

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import com.eschao.android.widget.pageflip.PageFlip
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import java.util.concurrent.locks.ReentrantLock

/**
 * 종이책 3D 페이지 넘김 뷰. 드래그하는 손가락 위치를 그대로 따라 페이지가 말린다 (eschao PageFlip).
 * doublePage=true 면 가로 화면에서 좌우 두 페이지를 함께 보여준다 (세로 화면에서는 자동으로 한 장).
 */
class PageFlipView(
    context: Context,
    private val provider: PageImageProvider,
    startPage: Int,
    doublePage: Boolean,
    private val duration: Int = 900,
) : GLSurfaceView(context), GLSurfaceView.Renderer {

    /** 첫 페이지에서 뒤로 / 마지막 페이지에서 앞으로 넘기려 할 때 호출 (메인 스레드). */
    var onBoundary: ((forward: Boolean) -> Unit)? = null

    val pageCount get() = provider.count
    fun currentPageNumber(): Int = render.pageNo

    private val lock = ReentrantLock()
    private val pageFlip = PageFlip(context)
    private val handler: Handler
    private var render: PageRender

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

        render = PageRender.Single(pageFlip, handler, provider, startPage) { f -> onBoundary?.invoke(f) }

        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> fingerDown(x, y)
            MotionEvent.ACTION_MOVE -> fingerMove(x, y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> fingerUp(x, y)
        }
        return true
    }

    /** 손대지 않고 다음 페이지로 넘긴다 (폴더블 접힘 제스처 등에서 호출). 오른쪽→왼쪽 스와이프를 흉내낸다. */
    fun flipForward() {
        if (width == 0 || pageFlip.isAnimating) return
        val y = height / 2f
        // ponytail: 합성 제스처. eschao 가 move 이력이 짧다고 무시하면 중간 move 를 더 넣어 튜닝.
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
        if (pageFlip.canAnimate(x, y)) {
            fingerUp(x, y)
            return
        }
        if (pageFlip.onFingerMove(x, y)) {
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
        pageFlip.onFingerUp(x, y, duration)
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
                    render = PageRender.Double(pageFlip, handler, provider, n) { f -> onBoundary?.invoke(f) }
                } else if (!wantDouble && render !is PageRender.Single) {
                    val n = render.pageNo
                    render.release()
                    render = PageRender.Single(pageFlip, handler, provider, n) { f -> onBoundary?.invoke(f) }
                }
                render.onSurfaceChanged()
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
