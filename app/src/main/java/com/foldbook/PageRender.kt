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
        provider.setSpread(this is Double)
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

    /**
     * 넘김 애니메이션이 진행 중이거나, 끝 처리(텍스처 교체·페이지 번호 확정)가 아직 안 끝났는가.
     * 스크롤러만 보면 '끝났지만 아직 정리 안 된' 순간을 놓쳐서 엉뚱한 페이지가 번쩍인다.
     */
    val busy get() = drawCommand == DRAW_ANIMATING_FRAME

    /**
     * 진행 중인 넘김을 그 자리에서 끝난 것으로 확정한다 (넘김 도중에 또 넘길 때).
     * 호출자가 lock 을 잡아야 한다. GL 호출은 하지 않는다(텍스처 id 정리는 다음 프레임에서).
     */
    fun settleNow(): Boolean {
        if (!busy) return false
        pageFlip.abortAnimating()
        return onEndedDrawing(DRAW_ANIMATING_FRAME)
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

        /** 왼쪽 가장자리를 기준으로 접히는 중인가 = 라이브러리상 '뒤로' 넘기는 중. */
        private val backward get() = pageFlip.isOriginAtLeft

        /** 지금 넘김이 끝나면 화면에 남을 페이지 (접히는 현재 페이지 아래에 깔리는 페이지). */
        private fun targetNo() = if (backward) pageNo - 1 else pageNo + 1

        /** 되돌린 뒤 미리 올려둔 '넘어갈 페이지' 텍스처를 버려야 하는가 (GL 스레드에서 처리). */
        private var dropPreview = false

        override fun onDrawFrame() {
            pageFlip.deleteUnusedTextures()
            val page = pageFlip.firstPage
            if (dropPreview) {
                dropPreview = false
                page.deleteAllTextures()
                firstReal = false
            }
            when (drawCommand) {
                DRAW_MOVING_FRAME, DRAW_ANIMATING_FRAME -> {
                    // 어느 방향이든 '현재 페이지(first)'가 접히고 그 아래 넘어갈 페이지(second)가 드러난다
                    if (!page.isFirstTextureSet) page.setFirstTexture(provider.bitmap(pageNo))
                    if (!page.isSecondTextureSet) page.setSecondTexture(provider.bitmap(targetNo()))
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
            // 이미 끝 처리를 했거나(연속 넘김으로 확정) 새 드래그가 시작됐으면, 뒤늦게 도착한
            // 프레임 통지는 버린다. 안 그러면 페이지가 두 번 넘어가거나 잠깐 되돌아가 번쩍인다.
            if (what != DRAW_ANIMATING_FRAME || !busy) return false
            if (pageFlip.animating()) {
                drawCommand = DRAW_ANIMATING_FRAME
                return true
            }
            if (pageFlip.flipState == PageFlipState.END_WITH_FORWARD) {
                pageFlip.firstPage.setFirstTextureWithSecond()
                pageNo = targetNo().coerceIn(1, maxOf(1, provider.count))
                drawCommand = DRAW_FULL_PAGE
                settled()
            } else {
                // 제자리로 돌아온 경우: 미리 올려둔 '넘어갈 페이지' 텍스처를 버려서
                // 다음에 반대 방향으로 끌 때 엉뚱한 페이지가 비치지 않게 한다.
                // (이 함수는 메인 스레드라 GL 컨텍스트가 없다 -> 실제 삭제는 다음 프레임에서)
                dropPreview = true
                drawCommand = DRAW_FULL_PAGE
            }
            return true
        }

        override fun canFlipForward(): Boolean = when {
            backward -> if (pageNo > 1) true else blockAtBoundary(false)
            pageNo < provider.count -> true
            else -> blockAtBoundary(true)
        }

        /** 라이브러리의 뒤로 넘김(넘어갈 페이지가 펼쳐지는 효과)은 쓰지 않는다. */
        override fun canFlipBackward(): Boolean = false
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
            // 이미 끝 처리를 했거나(연속 넘김으로 확정) 새 드래그가 시작됐으면, 뒤늦게 도착한
            // 프레임 통지는 버린다. 안 그러면 페이지가 두 번 넘어가거나 잠깐 되돌아가 번쩍인다.
            if (what != DRAW_ANIMATING_FRAME || !busy) return false
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
            // 왼쪽 페이지를 넘기는 건 라이브러리상 '뒤로' 이므로 경계도 뒤로 알려야 한다
            return if (ok) true else blockAtBoundary(!page.isLeftPage)
        }

        override fun canFlipBackward(): Boolean = false
    }
}
