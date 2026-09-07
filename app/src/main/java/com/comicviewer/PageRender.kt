package com.comicviewer

import android.os.Handler
import android.os.Message
import com.eschao.android.widget.pageflip.OnPageFlipListener
import com.eschao.android.widget.pageflip.PageFlip
import com.eschao.android.widget.pageflip.PageFlipState

const val MSG_ENDED_DRAWING_FRAME = 1

private const val DRAW_MOVING_FRAME = 0
private const val DRAW_ANIMATING_FRAME = 1
private const val DRAW_FULL_PAGE = 2

/**
 * eschao Sample 의 PageRender / SinglePageRender / DoublePagesRender 를 Kotlin 으로 옮기고,
 * 텍스처 출처만 PageImageProvider(실제 만화 이미지)로 교체했다. (원본 Apache-2.0)
 * pageNo 는 화면 페이지 번호(1-based). 더블 모드에서는 왼쪽 페이지 번호.
 */
abstract class PageRender(
    protected val pageFlip: PageFlip,
    protected val handler: Handler,
    protected val provider: PageImageProvider,
    var pageNo: Int,
    private val onBoundary: (forward: Boolean) -> Unit,
) : OnPageFlipListener {

    protected var drawCommand = DRAW_FULL_PAGE

    init {
        pageFlip.setListener(this)
    }

    fun onSurfaceChanged() {
        val page = pageFlip.firstPage
        provider.setPageSize(page.width().toInt(), page.height().toInt())
    }

    fun onFingerMove(): Boolean {
        drawCommand = DRAW_MOVING_FRAME
        return true
    }

    fun onFingerUp(): Boolean {
        if (pageFlip.animating()) {
            drawCommand = DRAW_ANIMATING_FRAME
            return true
        }
        return false
    }

    protected fun notifyEnded() {
        handler.sendMessage(Message.obtain().apply {
            what = MSG_ENDED_DRAWING_FRAME
            arg1 = drawCommand
        })
    }

    /** 경계(첫/마지막 페이지)에서 넘김 시도를 막고, 메인 스레드로 콜백을 던진다. */
    protected fun blockAtBoundary(forward: Boolean): Boolean {
        handler.post { onBoundary(forward) }
        return false
    }

    open fun release() {
        pageFlip.setListener(null)
    }

    abstract fun onDrawFrame()

    /** 애니메이션 프레임 종료 처리. 다시 그려야 하면 true. */
    abstract fun onEndedDrawing(what: Int): Boolean

    // --- Single ---------------------------------------------------------------

    class Single(
        pf: PageFlip, h: Handler, p: PageImageProvider, pageNo: Int, ob: (Boolean) -> Unit,
    ) : PageRender(pf, h, p, pageNo, ob) {

        override fun onDrawFrame() {
            pageFlip.deleteUnusedTextures()
            val page = pageFlip.firstPage
            when (drawCommand) {
                DRAW_MOVING_FRAME, DRAW_ANIMATING_FRAME -> {
                    if (pageFlip.flipState == PageFlipState.FORWARD_FLIP) {
                        if (!page.isSecondTextureSet) page.setSecondTexture(provider.bitmap(pageNo + 1))
                    } else if (!page.isFirstTextureSet) {
                        pageNo--
                        page.setFirstTexture(provider.bitmap(pageNo))
                    }
                    pageFlip.drawFlipFrame()
                }
                DRAW_FULL_PAGE -> {
                    if (!page.isFirstTextureSet) page.setFirstTexture(provider.bitmap(pageNo))
                    pageFlip.drawPageFrame()
                }
            }
            notifyEnded()
        }

        override fun onEndedDrawing(what: Int): Boolean {
            if (what != DRAW_ANIMATING_FRAME) return false
            if (pageFlip.animating()) {
                drawCommand = DRAW_ANIMATING_FRAME
                return true
            }
            if (pageFlip.flipState == PageFlipState.END_WITH_FORWARD) {
                pageFlip.firstPage.setFirstTextureWithSecond()
                pageNo++
            }
            drawCommand = DRAW_FULL_PAGE
            return true
        }

        override fun canFlipForward(): Boolean =
            if (pageNo < provider.count) true else blockAtBoundary(true)

        override fun canFlipBackward(): Boolean {
            if (pageNo > 1) {
                pageFlip.firstPage.setSecondTextureWithFirst()
                return true
            }
            return blockAtBoundary(false)
        }
    }

    // --- Double -------------------------------------------------------------

    class Double(
        pf: PageFlip, h: Handler, p: PageImageProvider, pageNo: Int, ob: (Boolean) -> Unit,
    ) : PageRender(pf, h, p, pageNo, ob) {

        override fun onDrawFrame() {
            pageFlip.deleteUnusedTextures()
            val first = pageFlip.firstPage
            val second = pageFlip.secondPage ?: return

            if (!first.isFirstTextureSet)
                first.setFirstTexture(provider.bitmap(if (first.isLeftPage) pageNo else pageNo + 1))
            if (!second.isFirstTextureSet)
                second.setFirstTexture(provider.bitmap(if (second.isLeftPage) pageNo else pageNo + 1))

            when (drawCommand) {
                DRAW_MOVING_FRAME, DRAW_ANIMATING_FRAME -> {
                    if (!first.isBackTextureSet)
                        first.setBackTexture(provider.bitmap(if (first.isLeftPage) pageNo - 1 else pageNo + 2))
                    if (!first.isSecondTextureSet)
                        first.setSecondTexture(provider.bitmap(if (first.isLeftPage) pageNo - 2 else pageNo + 3))
                    pageFlip.drawFlipFrame()
                }
                DRAW_FULL_PAGE -> pageFlip.drawPageFrame()
            }
            notifyEnded()
        }

        override fun onEndedDrawing(what: Int): Boolean {
            if (what != DRAW_ANIMATING_FRAME) return false
            if (pageFlip.animating()) {
                drawCommand = DRAW_ANIMATING_FRAME
                return true
            }
            if (pageFlip.flipState == PageFlipState.END_WITH_FORWARD) {
                val first = pageFlip.firstPage
                val second = pageFlip.secondPage
                if (second != null) {
                    second.swapTexturesWithPage(first)
                    pageNo += if (first.isLeftPage) -2 else 2
                }
            }
            drawCommand = DRAW_FULL_PAGE
            return true
        }

        override fun canFlipForward(): Boolean {
            val page = pageFlip.firstPage
            val ok = if (page.isLeftPage) pageNo > 1 else pageNo + 2 <= provider.count
            return if (ok) true else blockAtBoundary(true)
        }

        override fun canFlipBackward(): Boolean = false
    }
}
