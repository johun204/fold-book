package com.foldbook

import android.content.Context
import java.io.File

/**
 * 세션별 원격 다운로드 캐시. `cacheDir/sessions/<sessionId>/` 아래에만 쓴다.
 * - 세션 종료(다음 폴더로 롤오버) 또는 목록에서 제거 시 해당 디렉터리 삭제
 * - 앱 시작 시 활성 세션에 없는 디렉터리 일괄 정리 (찌꺼기 방지)
 * - 총 용량이 상한을 넘으면 오래된 세션 디렉터리부터 삭제
 */
object SessionCache {

    private const val CAP_BYTES = 400L * 1024 * 1024

    private fun root(context: Context) = File(context.cacheDir, "sessions")

    fun dir(context: Context, sessionId: String): File =
        File(root(context), sessionId).apply { mkdirs() }

    fun clear(context: Context, sessionId: String) {
        File(root(context), sessionId).deleteRecursively()
    }

    /** 활성 세션 것만 남기고 나머지 삭제 + 구버전 캐시(`comic/`) 제거. */
    fun sweep(context: Context, activeIds: Set<String>) {
        File(context.cacheDir, "comic").deleteRecursively() // v0.1 잔재
        val r = root(context)
        r.listFiles()?.forEach { d ->
            if (d.isDirectory && d.name !in activeIds) d.deleteRecursively()
        }
        enforceCap(context, activeIds)
    }

    private fun enforceCap(context: Context, keepIds: Set<String>) {
        val dirs = root(context).listFiles()?.filter { it.isDirectory } ?: return
        var total = dirs.sumOf { it.walkBottomUp().filter { f -> f.isFile }.sumOf(File::length) }
        if (total <= CAP_BYTES) return
        dirs.sortedBy { it.lastModified() }.forEach { d ->
            if (total <= CAP_BYTES || d.name in keepIds) return@forEach
            total -= d.walkBottomUp().filter { it.isFile }.sumOf(File::length)
            d.deleteRecursively()
        }
    }
}
