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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * - RTL 이어도 텍스처는 반전하지 않는다(순번만 [com.foldbook.PageImageProvider] 가 역순으로 매핑).
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

        /** 프리페치 창 바깥으로 이만큼 더 벗어난 페이지까지만 디코드된 채로 둔다. */
        const val KEEP_MARGIN = 2

        /** 불러오기 실패(네트워크 등) 시 다시 시도하는 횟수와 간격. */
        const val RETRIES = 2
        const val RETRY_DELAY_MS = 1500L

        /**
         * 보정 강도(1~5) → S 커브를 몇 번 적용할지. 소수는 마지막 한 번을 그만큼만 섞는다.
         * 1 번 적용이 예전 버전의 최대치였다 → 지금은 2 단계에 해당하고, 5 단계는 훨씬 세다.
         */
        val CURVE_STRENGTH = floatArrayOf(0f, 0.45f, 1f, 2f, 3.2f, 5f)
    }

    private val fwd = prefetchForward.coerceIn(1, 12)

    @Volatile private var pageW = 1
    @Volatile private var pageH = 1
    @Volatile private var current = 0

    /** 페이지 안에서 이미지를 가로로 어디에 붙일지 (0=왼쪽 끝, 0.5=가운데, 1=오른쪽 끝). */
    @Volatile private var alignX: (Int) -> Float = { 0.5f }

    @Volatile private var gray: Bitmap = makeGray(1, 1)
    private val decoded = HashMap<Int, Bitmap>()   // guarded by `decoded`
    private val inflight = HashSet<Int>()          // guarded by `decoded`

    /**
     * 페이지 크기·정렬이 바뀔 때마다 올린다. 그 전에 시작한 디코드는 옛 크기/정렬로 만든 것이라
     * 끝났을 때 세대가 다르면 버린다 (guarded by `decoded`).
     */
    private var generation = 0
    private var closed = false                     // guarded by `decoded`

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
            invalidateLocked()
        }
        ensureWindow()
    }

    /** 이미지 가로 정렬 규칙 변경 (양면/단면 전환). 바뀌면 디코드된 페이지를 다시 만든다. */
    fun setAlign(f: (Int) -> Float) {
        synchronized(decoded) {
            alignX = f
            invalidateLocked()
        }
        ensureWindow()
    }

    /** 디코드된 것을 모두 버리고, 진행 중인 디코드도 결과를 쓰지 않게 한다. `decoded` 락을 잡고 부를 것. */
    private fun invalidateLocked() {
        generation++
        inflight.clear()
        decoded.values.forEach { it.recycle() }
        decoded.clear()
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
        val gen = synchronized(decoded) {
            if (!closed && cur in pages.indices && pages[cur].entryId.isNotEmpty() &&
                !decoded.containsKey(cur) && cur !in inflight
            ) {
                inflight.add(cur); generation
            } else -1
        }
        if (gen < 0) {
            prefetchAround(cur)
            return
        }
        scope.launch(Dispatchers.IO) {
            loadInto(cur, gen)
            prefetchAround(current)
        }
    }

    private fun prefetchAround(center: Int) {
        val toLoad = ArrayList<Int>()
        val gen: Int
        synchronized(decoded) {
            if (closed) return
            gen = generation
            for (i in (center - BACK)..(center + fwd)) {
                if (i !in pages.indices || i == center) continue
                if (pages[i].entryId.isEmpty()) continue
                if (decoded.containsKey(i) || i in inflight) continue
                inflight.add(i)
                toLoad.add(i)
            }
            // 화면 크기 비트맵은 장당 수~십수 MB 라, 프리페치 창을 조금만 벗어나도 바로 버린다.
            val lo = current - BACK - KEEP_MARGIN
            val hi = current + fwd + KEEP_MARGIN
            val it = decoded.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.key < lo || e.key > hi) {
                    e.value.recycle()
                    it.remove()
                }
            }
        }
        toLoad.sortBy { abs(it - center) }
        for (i in toLoad) {
            scope.launch(Dispatchers.IO) { loadInto(i, gen) }
        }
    }

    /**
     * [index] 를 불러와 `decoded` 에 넣는다. 실패하면 몇 번 다시 시도한다.
     * 도중에 크기/정렬이 바뀌었거나([gen] 불일치) 닫혔으면 결과를 버린다 — 새 세대가 다시 요청한다.
     */
    private suspend fun loadInto(index: Int, gen: Int) {
        var attempt = 0
        while (true) {
            val bmp = try {
                load(index)
            } catch (e: CancellationException) {
                synchronized(decoded) { if (gen == generation) inflight.remove(index) }
                throw e
            } catch (e: Exception) {
                null
            }
            val stale = synchronized(decoded) { closed || gen != generation }
            if (stale) {
                bmp?.recycle()
                return
            }
            if (bmp != null) {
                val stored = synchronized(decoded) {
                    if (closed || gen != generation) false
                    else { inflight.remove(index); decoded[index] = bmp; true }
                }
                if (stored) withContext(Dispatchers.Main) { onReady?.invoke(index) } else bmp.recycle()
                return
            }
            if (attempt++ >= RETRIES) {
                synchronized(decoded) { if (gen == generation) inflight.remove(index) }
                return
            }
            delay(RETRY_DELAY_MS)
        }
    }

    /** 디코드된 비트맵, 또는 파일이 깨져 디코드할 수 없으면 null. 네트워크 오류는 예외. */
    private suspend fun load(index: Int): Bitmap? {
        val p = pages[index]
        val local = backend.localPath(p.entryId)
        if (local != null) return decode(File(local), p.half, index)

        val f = File(cacheDir, "img_%08x_%s".format(p.entryId.hashCode(), sanitize(p.name)))
        if (!f.exists() || f.length() == 0L) {
            // 임시 파일에 받고 다 받은 뒤에 이름을 바꾼다. 도중에 끊기면 반쪽 파일이
            // 캐시에 남아 계속 깨진 그림으로 디코드되는 일이 없도록.
            val tmp = File(cacheDir, f.name + ".${Thread.currentThread().id}.tmp")
            try {
                backend.open(p.entryId).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                if (!tmp.renameTo(f)) throw java.io.IOException("cache rename failed")
            } finally {
                tmp.delete()
            }
        }
        return decode(f, p.half, index) ?: run { f.delete(); null }   // 깨진 캐시는 다음에 다시 받는다
    }

    private fun decode(file: File, half: Half, index: Int): Bitmap? {
        val pw = pageW
        val ph = pageH
        val path = file.absolutePath
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null

        val targetW = if (half == Half.WHOLE) pw else pw * 2
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sample(bounds.outWidth, bounds.outHeight, targetW, ph)
        }
        var bmp = BitmapFactory.decodeFile(path, opts) ?: return null

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
     *  2. 강도만큼 S 커브(smoothstep)를 **거듭** 적용한다 — 흐린 선은 더 진해지고 종이 얼룩은
     *     날아간다. 거듭할수록 중간 밝기가 흑/백으로 갈라져, 강도 5 는 거의 흑백 2 값에 가깝다.
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
        val strength = CURVE_STRENGTH[level.coerceIn(1, CURVE_STRENGTH.size - 1)]
        return IntArray(256) { v ->
            var t = ((v - lo) / span).coerceIn(0f, 1f)
            var left = strength
            while (left > 0f) {
                val w = minOf(1f, left)             // 남은 강도만큼만 섞는다(소수점 단계)
                t += w * (t * t * (3 - 2 * t) - t)
                left -= w
            }
            (t * 255f).toInt().coerceIn(0, 255)
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
            if (closed) return
            closed = true
            invalidateLocked()
            gray.recycle()
        }
    }
}
