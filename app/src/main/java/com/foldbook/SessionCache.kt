package com.foldbook

import android.content.Context
import java.io.File

/**
 * 세션별 원격 다운로드 캐시. `cacheDir/sessions/<sessionId>/` 아래에만 쓴다.
 * - 세션 종료(다음 폴더로 롤오버) 또는 목록에서 제거 시 해당 디렉터리 삭제
 * - 앱 시작 시 활성 세션에 없는 디렉터리 일괄 정리 (찌꺼기 방지)
 * - 총 용량이 상한을 넘으면 오래 안 본 세션부터 받아둔 이미지를 지운다 (가장 최근 세션은 보호,
 *   `manifest.json` 은 남겨 이어보기 즉시 열기는 유지 — 이미지는 볼 때 다시 받는다)
 */
object SessionCache {

    private const val CAP_BYTES = 400L * 1024 * 1024

    private fun root(context: Context) = File(context.cacheDir, "sessions")

    fun dir(context: Context, sessionId: String): File =
        File(root(context), sessionId).apply { mkdirs() }

    fun clear(context: Context, sessionId: String) {
        File(root(context), sessionId).deleteRecursively()
    }

    /**
     * 활성 세션 것만 남기고 나머지 삭제 + 구버전 캐시(`comic/`) 제거. 백그라운드 스레드에서 부를 것.
     * [activeIds] 는 지울 때마다 다시 물어본다 (스윕 도중 새로 열린 세션의 캐시를 지우지 않도록).
     */
    fun sweep(context: Context, activeIds: () -> Set<String>, mostRecentId: String?) {
        File(context.cacheDir, "comic").deleteRecursively() // v0.1 잔재
        root(context).listFiles()?.forEach { d ->
            if (d.isDirectory && d.name !in activeIds()) d.deleteRecursively()
        }
        val dirs = root(context).listFiles()?.filter { it.isDirectory } ?: return
        enforceCap(dirs, mostRecentId, CAP_BYTES)
    }

    /** 세션 디렉터리 안에서 지워도 되는 파일(받아둔 이미지). 매니페스트는 남긴다. */
    private fun images(dir: File): List<File> =
        dir.listFiles()?.filter { it.isFile && it.name != "manifest.json" }.orEmpty()

    /** 오래 안 쓴 세션 디렉터리부터 이미지를 지워 [cap] 아래로 맞춘다. [protectId] 세션은 건드리지 않는다. */
    internal fun enforceCap(dirs: List<File>, protectId: String?, cap: Long) {
        var total = dirs.sumOf { d -> images(d).sumOf(File::length) }
        if (total <= cap) return
        for (d in dirs.sortedBy { it.lastModified() }) {
            if (total <= cap) break
            if (d.name == protectId) continue
            for (f in images(d)) {
                total -= f.length()
                f.delete()
            }
        }
    }
}
