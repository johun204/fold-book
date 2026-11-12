package com.foldbook

import android.os.Handler
import android.os.Message
import java.util.IdentityHashMap
import com.eschao.android.widget.pageflip.OnPageFlipListener
import com.eschao.android.widget.pageflip.Page
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
 * 넘기는 동안 미리 올린 텍스처가 실제 이미지였는지도 기억해 두었다가, 넘김이 끝나 그 텍스처가
 * 화면에 남을 때 같은 그림을 다시 올리지 않는다(화면 크기 텍스처 업로드는 프레임을 떨어뜨린다).
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

    /** 올려 둔 텍스처가 회색이라는 표시를 모두 되돌린다 (텍스처를 전부 다시 올려야 할 때). */
    protected abstract fun resetReal()

    init {
        pageFlip.setListener(this)
    }

    fun onSurfaceChanged() {
        val page = pageFlip.firstPage
        provider.setPageSize(page.width().toInt(), page.height().toInt())
        provider.setSpread(this is Double)
        resetReal()
    }

    /** 스크러버로 특정 페이지로 점프. GL 스레드에서 호출할 것. */
    fun jumpTo(pageNumber: Int) {
        pageNo = pageNumber.coerceIn(1, maxOf(1, provider.count))
        resetReal()
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

        /** 현재 페이지 / 미리 올려둔 넘어갈 페이지 텍스처가 실제 이미지인가 (회색 아님). */
        private var firstReal = false
        private var targetReal = false

        override fun resetReal() {
            firstReal = false
            targetReal = false
        }

        private fun setFirst() {
            val b = provider.bitmapOrNull(pageNo)
            pageFlip.firstPage.setFirstTexture(b ?: provider.bitmap(pageNo))
            firstReal = b != null
        }

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
                    if (!page.isFirstTextureSet) setFirst()
                    if (!page.isSecondTextureSet) {
                        val b = provider.bitmapOrNull(targetNo())
                        page.setSecondTexture(b ?: provider.bitmap(targetNo()))
                        targetReal = b != null
                    }
                    pageFlip.drawFlipFrame()
                }
                DRAW_FULL_PAGE -> {
                    if (!page.isFirstTextureSet) {
                        setFirst()
                    } else if (!firstReal && provider.isReal(pageNo)) {
                        page.deleteAllTextures()
                        setFirst()
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
                firstReal = targetReal      // 미리 올려둔 텍스처가 그대로 현재 페이지가 된다
                targetReal = false
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

        /**
         * 페이지(왼쪽/오른쪽 Page 객체)별로, 올려 둔 세 텍스처가 실제 이미지인가.
         * 어느 쪽이 '넘기는 페이지(first)'인지는 터치마다 바뀌므로 역할이 아니라 객체에 붙여 둔다.
         */
        private class Real { var first = false; var back = false; var second = false }
        private val real = IdentityHashMap<Page, Real>()
        private fun realOf(p: Page) = real.getOrPut(p) { Real() }

        override fun resetReal() = real.clear()

        /** 보이는 면(first 텍스처)을 올린다. 아직 회색이었고 실제 이미지가 도착했으면 바꿔 올린다. */
        private fun showFace(page: Page, n: Int) {
            val r = realOf(page)
            if (page.isFirstTextureSet && (r.first || !provider.isReal(n))) return
            if (page.isFirstTextureSet) {
                page.deleteAllTextures()       // 뒷면·다음 장 텍스처도 함께 지워진다
                r.back = false
                r.second = false
            }
            val b = provider.bitmapOrNull(n)
            page.setFirstTexture(b ?: provider.bitmap(n))
            r.first = b != null
        }

        override fun onDrawFrame() {
            pageFlip.deleteUnusedTextures()
            val first = pageFlip.firstPage
            val second = pageFlip.secondPage ?: return

            showFace(first, if (first.isLeftPage) pageNo else pageNo + 1)
            showFace(second, if (second.isLeftPage) pageNo else pageNo + 1)

            when (drawCommand) {
                DRAW_MOVING_FRAME, DRAW_ANIMATING_FRAME -> {
                    val r = realOf(first)
                    if (!first.isBackTextureSet) {
                        val n = if (first.isLeftPage) pageNo - 1 else pageNo + 2
                        val b = provider.bitmapOrNull(n)
                        first.setBackTexture(b ?: provider.bitmap(n))
                        r.back = b != null
                    }
                    if (!first.isSecondTextureSet) {
                        val n = if (first.isLeftPage) pageNo - 2 else pageNo + 3
                        val b = provider.bitmapOrNull(n)
                        first.setSecondTexture(b ?: provider.bitmap(n))
                        r.second = b != null
                    }
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
                    // 텍스처가 옮겨간 그대로 '실제 이미지인가' 표시도 옮긴다 (Page.swapTexturesWithPage 와 같은 순서)
                    val rs = realOf(second)
                    val rf = realOf(first)
                    rs.second = rs.first
                    rs.back = rf.first
                    rs.first = rf.back
                    rf.back = false
                    rf.first = rf.second
                    rf.second = false
                    // 반대쪽 페이지에 남은 뒷면·다음 장 텍스처는 되넘길 때 그대로 쓰인다.
                    // 회색인 채로 남았으면 버려서 그때 새로 올리게 한다.
                    if (!rs.back || !rs.second) dropFlipTextures(second)
                    pageNo += if (first.isLeftPage) -2 else 2
                }
                drawCommand = DRAW_FULL_PAGE
                settled()
                return true
            }
            // 제자리로 돌아온 경우: 회색으로 올려둔 뒷면·다음 장은 다음에 끌 때 다시 올린다.
            pageFlip.firstPage?.let { p ->
                val r = realOf(p)
                if (!r.back || !r.second) dropFlipTextures(p)
            }
            drawCommand = DRAW_FULL_PAGE
            return true
        }

        private fun dropFlipTextures(page: Page) {
            page.dropFlipTextures()
            realOf(page).let { it.back = false; it.second = false }
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
