package com.foldbook.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.foldbook.FitMode
import com.foldbook.Half
import com.foldbook.PageRef
import com.foldbook.StorageBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * 창(window) 방식 이미지 로더. 현재 페이지 주변 몇 장만 백그라운드로 미리 받아 디코드해 둔다.
 * - 아직 준비 안 된 페이지는 [placeholder] (회색) 를 주고, 준비되면 [onReady] 로 알린다.
 * - 원격은 [cacheDir] 로 내려받아 디코드(파일명은 entryId 해시 → 목록 순서가 바뀌어도 재사용).
 * - 현재 페이지를 최우선으로 1장 디코드한 뒤에야 앞뒤 프리페치를 시작한다.
 * - 이미지가 페이지보다 좁아 여백이 생길 때, [setAlign] 으로 좌우 어디에 붙일지 정한다
 *   (양면 모드에서 가운데(책등)에 검은 여백이 생기지 않도록).
 * - [enhanceLevel] 이 0 보다 크면 흐린 흑백 스캔본을 회색조 + 톤커브로 또렷하게 만든다.
 * - [fit] 으로 이미지를 페이지에 맞추는 방식을 정한다(전부 보이기 / 가로 맞춤 / 세로 맞춤).
 * - RTL 이어도 텍스처는 반전하지 않는다(넘김 기하가 CurlEngine 에서 처리).
 */
class PageSource(
    private val backend: StorageBackend,
    val pages: List<PageRef>,
    private val cacheDir: File,
    private val scope: CoroutineScope,
    prefetchForward: Int = 5,
    private val enhanceLevel: Int = 0,
    private val fit: FitMode = FitMode.BOTH,
) {
    private companion object {
        const val BACK = 2
    }

    private val fwd = prefetchForward.coerceIn(1, 12)
    private val keep = fwd + BACK + 6

    @Volatile private var pageW = 1
    @Volatile private var pageH = 1
    @Volatile private var current = 0

    /** 페이지 안에서 이미지를 가로로 어디에 붙일지 (0=왼쪽 끝, 0.5=가운데, 1=오른쪽 끝). */
    @Volatile private var alignX: (Int) -> Float = { 0.5f }

    private var gray: Bitmap = makeGray(1, 1)
    private val decoded = HashMap<Int, Bitmap>()   // guarded by `decoded`
    private val inflight = HashSet<Int>()          // guarded by `decoded`

    var onReady: ((index: Int) -> Unit)? = null

    val count get() = pages.size

    fun setPageSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        synchronized(decoded) {
            if (w == pageW && h == pageH) return
            pageW = w
            pageH = h
            gray.recycle()
            gray = makeGray(w, h)
            decoded.values.forEach { it.recycle() }
            decoded.clear()
        }
        ensureWindow()
    }

    /** 이미지 가로 정렬 규칙 변경 (양면/단면 전환). 바뀌면 디코드된 페이지를 다시 만든다. */
    fun setAlign(f: (Int) -> Float) {
        synchronized(decoded) {
            alignX = f
            decoded.values.forEach { it.recycle() }
            decoded.clear()
        }
        ensureWindow()
    }

    fun placeholder(): Bitmap = gray

    /** 준비된 실제 비트맵 또는 null. entryId 가 빈 페이지(정렬용 여백)는 어두운 여백을 준다. */
    fun bitmap(index: Int): Bitmap? {
        if (index in pages.indices && pages[index].entryId.isEmpty()) {
            synchronized(decoded) { decoded[index]?.takeIf { !it.isRecycled } }?.let { return it }
            val f = makeFiller(pageW, pageH)
            synchronized(decoded) { decoded[index] = f }
            return f
        }
        return synchronized(decoded) { decoded[index]?.takeIf { !it.isRecycled } }
    }

    /** 항상 non-null. 아직 안 받았으면 회색. */
    fun bitmapOrGray(index: Int): Bitmap = bitmap(index) ?: gray

    fun isReal(index: Int): Boolean =
        index in pages.indices && synchronized(decoded) { decoded[index]?.takeIf { !it.isRecycled } != null }

    fun focus(index: Int) {
        current = index
        ensureWindow()
    }

    /** 현재 페이지를 최우선으로 받고, 끝난 뒤에야 앞뒤 프리페치를 시작한다. */
    private fun ensureWindow() {
        val cur = current
        val loadCurNow = synchronized(decoded) {
            if (cur in pages.indices && pages[cur].entryId.isNotEmpty() &&
                !decoded.containsKey(cur) && cur !in inflight
            ) {
                inflight.add(cur); true
            } else false
        }
        if (!loadCurNow) {
            prefetchAround(cur)
            return
        }
        scope.launch(Dispatchers.IO) {
            val bmp = runCatching { load(cur) }.getOrNull()
            synchronized(decoded) {
                inflight.remove(cur)
                if (bmp != null) decoded[cur] = bmp
            }
            if (bmp != null) withContext(Dispatchers.Main) { onReady?.invoke(cur) }
            prefetchAround(current)
        }
    }

    private fun prefetchAround(center: Int) {
        val toLoad = ArrayList<Int>()
        synchronized(decoded) {
            for (i in (center - BACK)..(center + fwd)) {
                if (i !in pages.indices || i == center) continue
                if (pages[i].entryId.isEmpty()) continue
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
        toLoad.sortBy { abs(it - center) }
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
            val f = File(cacheDir, "img_%08x_%s".format(p.entryId.hashCode(), sanitize(p.name)))
            if (!f.exists() || f.length() == 0L) {
                backend.open(p.entryId).use { input -> f.outputStream().use { input.copyTo(it) } }
            }
            f
        }
        return decode(src, p.half, index)
    }

    private fun decode(file: File, half: Half, index: Int): Bitmap {
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

        val lut = if (enhanceLevel > 0) toneCurve(bmp, enhanceLevel) else null
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        val out = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.rgb(20, 20, 20))
            val sw = pw.toFloat() / bmp.width
            val sh = ph.toFloat() / bmp.height
            val s = when (fit) {
                FitMode.WIDTH -> sw
                FitMode.HEIGHT -> sh
                FitMode.BOTH -> minOf(sw, sh)
            }
            val dw = bmp.width * s
            val dh = bmp.height * s
            // 남을 때만 책등 쪽에 붙이고, 넘쳐서 잘릴 때는 가운데를 보여준다.
            val l = if (dw <= pw) (pw - dw) * alignX(index).coerceIn(0f, 1f) else (pw - dw) / 2f
            val t = (ph - dh) / 2f
            drawBitmap(bmp, null, RectF(l, t, l + dw, t + dh), paint)
        }
        bmp.recycle()
        if (lut != null) applyLut(out, lut)
        return out
    }

    /**
     * 스캔본 보정 LUT(밝기 0~255 → 보정 밝기). 두 단계다.
     *  1. 실제로 쓰인 밝기 구간(하위 1% ~ 상위 99%)을 0~255 로 편다 — 잿빛 배경이 하얘진다.
     *  2. 강도만큼 S 커브(smoothstep)를 섞는다 — 흐린 선은 더 진해지고 종이 얼룩은 날아간다.
     *
     * 밝기 분포는 가로줄 48 개만 훑어서 잰다. 색은 회색조로 뺀다([applyLut]).
     */
    private fun toneCurve(bmp: Bitmap, level: Int): IntArray? {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return null
        val rows = minOf(h, 48)
        val hist = IntArray(256)
        val buf = IntArray(w)
        for (r in 0 until rows) {
            val y = (r * h) / rows
            bmp.getPixels(buf, 0, w, 0, y, w, 1)
            for (c in buf) hist[luma(c)]++
        }
        val total = rows * w
        var lo = percentile(hist, (total * 0.01f).toInt())
        var hi = percentile(hist, (total * 0.99f).toInt())
        if (hi - lo < 16) { lo = 0; hi = 255 }       // 거의 단색 = 펴지 않고 회색조만
        val span = (hi - lo).toFloat()
        val s = (level.coerceIn(1, 5)) / 5f          // S 커브를 섞는 비율
        return IntArray(256) { v ->
            val t = ((v - lo) / span).coerceIn(0f, 1f)
            val curved = t + s * (t * t * (3 - 2 * t) - t)
            (curved * 255f).toInt().coerceIn(0, 255)
        }
    }

    /** 회색조로 바꾸면서 [lut] 을 적용한다. 가로줄 한 줄씩 처리해 여분 메모리를 쓰지 않는다. */
    private fun applyLut(bmp: Bitmap, lut: IntArray) {
        val w = bmp.width
        val row = IntArray(w)
        for (y in 0 until bmp.height) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (i in 0 until w) {
                val g = lut[luma(row[i])]
                row[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
            bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
    }

    private fun luma(c: Int) =
        ((c shr 16 and 0xFF) * 77 + (c shr 8 and 0xFF) * 151 + (c and 0xFF) * 28) shr 8

    /** 누적 개수가 [n] 을 넘는 첫 밝기 값. */
    private fun percentile(hist: IntArray, n: Int): Int {
        var acc = 0
        for (v in hist.indices) {
            acc += hist[v]
            if (acc >= n) return v
        }
        return 255
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

    private fun sanitize(n: String) = n.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)

    fun close() {
        synchronized(decoded) {
            decoded.values.forEach { it.recycle() }
            decoded.clear()
            gray.recycle()
        }
    }
}
