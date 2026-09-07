package com.comicviewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.io.File

/** 종횡비만 보고 좌우 양면 스캔본인지 판정한다 (픽셀 디코딩 없음). */
fun imageIsSpread(file: File): Boolean {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, o)
    return o.outWidth > 0 && o.outHeight > 0 &&
        o.outWidth.toFloat() / o.outHeight >= SpreadPolicy.SPREAD_ASPECT
}

/**
 * 화면 페이지 번호(eschao 는 1-based) -> 페이지 크기에 맞춘 ARGB_8888 비트맵.
 * 좌우 스캔본은 절반만 잘라 쓰고, 비율을 유지한 채 검은 여백으로 레터박스한다.
 * 호출은 모두 GL 스레드에서 순차적으로 일어나므로 캐시에 락이 필요 없다.
 */
class PageImageProvider(private val pages: List<ReaderPage>) {

    private var pageW = 1
    private var pageH = 1

    val count get() = pages.size

    fun setPageSize(w: Int, h: Int) {
        if (w > 0) pageW = w
        if (h > 0) pageH = h
        cache.values.forEach { it.recycle() }
        cache.clear()
    }

    // 넘김 한 번에 first/second/back 텍스처가 동시에 필요하므로 4장 정도 유지.
    private val cache = object : LinkedHashMap<Int, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>): Boolean {
            if (size > 4) {
                eldest.value.recycle()
                return true
            }
            return false
        }
    }

    fun bitmap(pageNumber: Int): Bitmap {
        cache[pageNumber]?.let { if (!it.isRecycled) return it }
        val idx = pageNumber - 1
        val bmp = if (idx in pages.indices) render(pages[idx]) else blank()
        cache[pageNumber] = bmp
        return bmp
    }

    private fun blank(): Bitmap =
        Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }

    private fun render(p: ReaderPage): Bitmap {
        val path = p.file.absolutePath
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return blank()

        val targetW = if (p.half == Half.WHOLE) pageW else pageW * 2
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetW, pageH)
        }
        var src = BitmapFactory.decodeFile(path, opts) ?: return blank()

        if (p.half != Half.WHOLE) {
            val hw = src.width / 2
            val x = if (p.half == Half.LEFT) 0 else src.width - hw
            val half = Bitmap.createBitmap(src, x, 0, hw, src.height)
            if (half !== src) src.recycle()
            src = half
        }

        val out = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.BLACK)
            val scale = minOf(pageW.toFloat() / src.width, pageH.toFloat() / src.height)
            val dw = src.width * scale
            val dh = src.height * scale
            val l = (pageW - dw) / 2f
            val t = (pageH - dh) / 2f
            drawBitmap(src, null, RectF(l, t, l + dw, t + dh), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        src.recycle()
        return out
    }

    private fun sampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        var s = 1
        while (reqW > 0 && reqH > 0 && w / (s * 2) >= reqW && h / (s * 2) >= reqH) s *= 2
        return s
    }
}
