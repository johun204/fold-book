package com.foldbook

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
 * eschao Sample 의 렌더러 Kotlin 이식. 텍스처 출처는 PageImageProvider(창 방식 로더).
 * 아직 안 받은 페이지는 회색이 나오고, 실제 비트맵이 도착하면 그 페이지 한정으로 텍스처를 한 번 교체한다
 * (매 프레임 setFirstTexture 를 부르면 eschao 가 GL 텍스처를 계속 새로 만들어 누수됨 -> 1회로 제한).
 * pageNo 는 리더 페이지 번호(1-based). 더블 모드에서는 왼쪽 페이지 번호.
 */
abstract class PageRender(
    protected val pageFlip: PageFlip,
    protected val handler: Handler,
    protected val provider: PageImageProvider,
    var pageNo: Int,
    private val onBoundary: (forward: Boolean) -> Unit,
    private val onSettled: (pageNumber: Int) -> Unit,
) : OnPageFlipListener {

    protected var drawCommand = DRAW_FULL_PAGE
    protected var firstReal = false
    protected var secondReal = false

    init {
        pageFlip.setListener(this)
    }

    fun onSurfaceChanged() {
        val page = pageFlip.firstPage
        provider.setPageSize(page.width().toInt(), page.height().toInt())
        firstReal = false
        secondReal = false
    }

    /** 스크러버로 특정 페이지로 점프. GL 스레드에서 호출할 것. */
    fun jumpTo(pageNumber: Int) {
        pageNo = pageNumber.coerceIn(1, maxOf(1, provider.count))
        firstReal = false
        secondReal = false
        drawCommand = DRAW_FULL_PAGE
        pageFlip.firstPage?.deleteAllTextures()
        pageFlip.secondPage?.deleteAllTextures()
        settled()
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

    protected fun settled() {
        firstReal = false
        secondReal = false
        val n = pageNo
        handler.post { onSettled(n) }
    }

    protected fun blockAtBoundary(forward: Boolean): Boolean {
        handler.post { onBoundary(forward) }
        return false
    }

    open fun release() {
        pageFlip.setListener(null)
    }

    abstract fun onDrawFrame()
    abstract fun onEndedDrawing(what: Int): Boolean

    // --- Single -------------------------------------------------------------

    class Single(
        pf: PageFlip, h: Handler, p: PageImageProvider, pageNo: Int,
        ob: (Boolean) -> Unit, os: (Int) -> Unit,
    ) : PageRender(pf, h, p, pageNo, ob, os) {

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
                    if (!page.isFirstTextureSet) {
                        page.setFirstTexture(provider.bitmap(pageNo))
                        firstReal = provider.isReal(pageNo)
                    } else if (!firstReal && provider.isReal(pageNo)) {
                        page.deleteAllTextures()
                        page.setFirstTexture(provider.bitmap(pageNo))
                        firstReal = true
                    }
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
            when (pageFlip.flipState) {
                PageFlipState.END_WITH_FORWARD -> {
                    pageFlip.firstPage.setFirstTextureWithSecond()
                    pageNo++
                    drawCommand = DRAW_FULL_PAGE
                    settled()
                }
                PageFlipState.END_WITH_BACKWARD -> {
                    drawCommand = DRAW_FULL_PAGE
                    settled()
                }
                else -> drawCommand = DRAW_FULL_PAGE
            }
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

    // --- Double -----------------------------------------------------------

    class Double(
        pf: PageFlip, h: Handler, p: PageImageProvider, pageNo: Int,
        ob: (Boolean) -> Unit, os: (Int) -> Unit,
    ) : PageRender(pf, h, p, pageNo, ob, os) {

        override fun onDrawFrame() {
            pageFlip.deleteUnusedTextures()
            val first = pageFlip.firstPage
            val second = pageFlip.secondPage ?: return

            val nFirst = if (first.isLeftPage) pageNo else pageNo + 1
            val nSecond = if (second.isLeftPage) pageNo else pageNo + 1
            if (!first.isFirstTextureSet) {
                first.setFirstTexture(provider.bitmap(nFirst)); firstReal = provider.isReal(nFirst)
            } else if (!firstReal && provider.isReal(nFirst)) {
                first.deleteAllTextures()
                first.setFirstTexture(provider.bitmap(nFirst)); firstReal = true
            }
            if (!second.isFirstTextureSet) {
                second.setFirstTexture(provider.bitmap(nSecond)); secondReal = provider.isReal(nSecond)
            } else if (!secondReal && provider.isReal(nSecond)) {
                second.deleteAllTextures()
                second.setFirstTexture(provider.bitmap(nSecond)); secondReal = true
            }

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
                drawCommand = DRAW_FULL_PAGE
                settled()
                return true
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
