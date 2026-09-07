package com.foldbook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 창(window) 방식 이미지 로더. 현재 페이지 주변 몇 장만 백그라운드로 미리 받아 디코드해 둔다.
 * - 아직 준비 안 된 페이지는 [placeholder] (회색) 를 주고, 준비되면 [onReady] 로 알린다.
 * - 원격은 [cacheDir] 로 내려받아 디코드, 로컬은 파일에서 바로 디코드.
 * - 세션 종료 시 [close] + 캐시 디렉터리 삭제로 찌꺼기 없이 정리.
 */
class PageStream(
    private val backend: StorageBackend,
    val pages: List<PageRef>,
    private val cacheDir: File,
    private val scope: CoroutineScope,
    prefetchForward: Int = 5,
) {
    private companion object {
        const val BACK = 2
    }

    private val fwd = prefetchForward.coerceIn(1, 12)
    private val keep = fwd + BACK + 6   // 창 밖 캐시 유지 폭

    @Volatile private var pageW = 1
    @Volatile private var pageH = 1
    @Volatile private var current = 0

    private var gray: Bitmap = makeGray(1, 1)
    private val decoded = HashMap<Int, Bitmap>()   // guarded by `decoded`
    private val inflight = HashSet<Int>()          // guarded by `decoded`

    var onReady: ((index: Int) -> Unit)? = null

    val count get() = pages.size

    fun setSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        synchronized(decoded) {
            pageW = w
            pageH = h
            gray.recycle()
            gray = makeGray(w, h)
            decoded.values.forEach { it.recycle() }
            decoded.clear()
        }
        ensureWindow()
    }

    fun placeholder(): Bitmap = gray

    fun bitmap(index: Int): Bitmap? {
        if (index in pages.indices && pages[index].entryId.isEmpty()) {
            synchronized(decoded) { decoded[index]?.takeIf { !it.isRecycled } }?.let { return it }
            val f = makeFiller(pageW, pageH)
            synchronized(decoded) { decoded[index] = f }
            return f
        }
        return synchronized(decoded) { decoded[index]?.takeIf { !it.isRecycled } }
    }

    fun focus(index: Int) {
        current = index
        ensureWindow()
    }

    private fun ensureWindow() {
        val toLoad = ArrayList<Int>()
        synchronized(decoded) {
            for (i in (current - BACK)..(current + fwd)) {
                if (i !in pages.indices) continue
                if (pages[i].entryId.isEmpty()) continue   // 빈 페이지는 로딩 대상 아님
                if (decoded.containsKey(i) || i in inflight) continue
                inflight.add(i)
                toLoad.add(i)
            }
            val it = decoded.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.key < current - keep || e.key > current + keep) {
                    e.value.recycle()
                    it.remove()
                }
            }
        }
        for (i in toLoad) {
            scope.launch(Dispatchers.IO) {
                val bmp = runCatching { load(i) }.getOrNull()
                synchronized(decoded) {
                    inflight.remove(i)
                    if (bmp != null) decoded[i] = bmp
                }
                if (bmp != null) withContext(Dispatchers.Main) { onReady?.invoke(i) }
            }
        }
    }

    private suspend fun load(index: Int): Bitmap {
        val p = pages[index]
        val src: File = backend.localPath(p.entryId)?.let(::File) ?: run {
            val f = File(cacheDir, "%05d_%s".format(index, sanitize(p.name)))
            if (!f.exists() || f.length() == 0L) {
                backend.open(p.entryId).use { input -> f.outputStream().use { input.copyTo(it) } }
            }
            f
        }
        return decode(src, p.half)
    }

    private fun decode(file: File, half: Half): Bitmap {
        val pw = pageW
        val ph = pageH
        val path = file.absolutePath
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return makeGray(pw, ph)

        val targetW = if (half == Half.WHOLE) pw else pw * 2
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sample(bounds.outWidth, bounds.outHeight, targetW, ph)
        }
        var bmp = BitmapFactory.decodeFile(path, opts) ?: return makeGray(pw, ph)

        if (half != Half.WHOLE) {
            val hw = bmp.width / 2
            val x = if (half == Half.LEFT) 0 else bmp.width - hw
            val cut = Bitmap.createBitmap(bmp, x, 0, hw, bmp.height)
            if (cut !== bmp) bmp.recycle()
            bmp = cut
        }

        val out = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.rgb(20, 20, 20))
            val s = minOf(pw.toFloat() / bmp.width, ph.toFloat() / bmp.height)
            val dw = bmp.width * s
            val dh = bmp.height * s
            val l = (pw - dw) / 2f
            val t = (ph - dh) / 2f
            drawBitmap(bmp, null, RectF(l, t, l + dw, t + dh), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        bmp.recycle()
        return out
    }

    private fun sample(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        var s = 1
        while (reqW > 0 && reqH > 0 && w / (s * 2) >= reqW && h / (s * 2) >= reqH) s *= 2
        return s
    }

    private fun makeGray(w: Int, h: Int) =
        Bitmap.createBitmap(maxOf(w, 1), maxOf(h, 1), Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.rgb(58, 58, 58)) }

    private fun makeFiller(w: Int, h: Int) =
        Bitmap.createBitmap(maxOf(w, 1), maxOf(h, 1), Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.rgb(18, 18, 18)) }

    private fun sanitize(n: String) = n.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)

    fun close() {
        synchronized(decoded) {
            decoded.values.forEach { it.recycle() }
            decoded.clear()
            gray.recycle()
        }
    }
}
