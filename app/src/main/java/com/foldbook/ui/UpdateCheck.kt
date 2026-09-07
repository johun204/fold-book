package com.foldbook.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateCheck {

    const val RELEASES_URL = "https://github.com/johun204/fold-book/releases/latest"
    private const val API = "https://api.github.com/repos/johun204/fold-book/releases/latest"

    data class Result(val latest: String, val updateAvailable: Boolean)

    /** 최신 릴리즈 태그를 가져와 현재 버전과 비교. 실패 시 null. */
    suspend fun check(current: String): Result? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(API).openConnection() as HttpURLConnection
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.connectTimeout = 8000
            c.readTimeout = 8000
            if (c.responseCode !in 200..299) return@runCatching null
            val tag = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                .optString("tag_name").ifBlank { return@runCatching null }
            Result(tag, isNewer(tag, current))
        }.getOrNull()
    }

    private fun parts(v: String) =
        v.trimStart('v', 'V').split('.', '-', ' ').mapNotNull { it.toIntOrNull() }

    fun isNewer(candidate: String, current: String): Boolean {
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
