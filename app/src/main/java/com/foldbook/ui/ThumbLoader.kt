package com.foldbook.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import com.foldbook.StorageBackend
import java.io.File

/** 폴더 탐색용 썸네일. 화면에 보이는 항목만 지연 로딩, 메모리 LRU 캐시. */
object ThumbLoader {

    private const val MAX = 64
    private const val TARGET_PX = 160

    private val cache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean {
            val drop = size > MAX
            if (drop) eldest.value.recycle()
            return drop
        }
    }
    private val sem = Semaphore(3)

    fun cached(key: String): Bitmap? = synchronized(cache) { cache[key]?.takeIf { !it.isRecycled } }

    suspend fun load(key: String, backend: StorageBackend, entryId: String): Bitmap? {
        cached(key)?.let { return it }
        val bmp = try {
            sem.withPermit {
                withContext(Dispatchers.IO) {
                    val bytes = backend.localPath(entryId)?.let { File(it).readBytes() }
                        ?: backend.openThumbnail(entryId).use { it.readBytes() }
                    decode(bytes)
                }
            }
        } catch (e: CancellationException) {
            throw e          // 스크롤로 화면을 벗어난 것 — 실패로 기억하지 않는다
        } catch (e: Exception) {
            null
        }
        // 실패(null)는 캐시하지 않는다. 다시 보일 때 재시도.
        if (bmp != null) synchronized(cache) { cache[key] = bmp }
        return bmp
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1
        while (o.outWidth / (s * 2) >= TARGET_PX && o.outHeight / (s * 2) >= TARGET_PX) s *= 2
        return BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = s },
        )
    }
}
