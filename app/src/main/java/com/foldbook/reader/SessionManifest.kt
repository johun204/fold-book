package com.foldbook.reader

import com.foldbook.Entry
import com.foldbook.ReadingDirection
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 세션별 이미지 목록 스냅샷. 세션 캐시 디렉터리에 저장해 두면, 앱을 껐다 켠 뒤 이어보기를
 * 열 때 폴더를 다시 조회(원격이면 네트워크)하거나 스캔본 헤더를 다시 분석하지 않고 즉시 연다.
 * 진입 후 백그라운드에서 실제 목록과 대조해 달라졌을 때만 갱신한다.
 */
@Serializable
data class SessionManifest(
    val version: Int = 1,
    val connectionId: String,
    val folderId: String,
    val direction: ReadingDirection,
    val entries: List<ManifestEntry>,
    /** entryId -> [width, height]. 없으면 크기 미상(통짜로 취급). */
    val dims: Map<String, List<Int>> = emptyMap(),
    val savedAt: Long = 0L,
) {
    fun asEntries(): List<Entry> = entries.map { Entry(it.id, it.name, isDir = false) }
    fun dimOf(entryId: String): Pair<Int, Int>? = dims[entryId]?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
    fun entryIds(): List<String> = entries.map { it.id }
}

@Serializable
data class ManifestEntry(val id: String, val name: String)

object SessionManifestStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun file(sessionDir: File) = File(sessionDir, "manifest.json")

    fun load(sessionDir: File): SessionManifest? = runCatching {
        val f = file(sessionDir)
        if (!f.exists() || f.length() == 0L) null
        else json.decodeFromString<SessionManifest>(f.readText())
    }.getOrNull()

    fun save(
        sessionDir: File,
        connectionId: String,
        folderId: String,
        direction: ReadingDirection,
        images: List<Entry>,
        dims: Map<String, Pair<Int, Int>?>,
    ) {
        runCatching {
            val m = SessionManifest(
                connectionId = connectionId,
                folderId = folderId,
                direction = direction,
                entries = images.map { ManifestEntry(it.id, it.name) },
                dims = dims.mapNotNull { (k, v) -> v?.let { k to listOf(it.first, it.second) } }.toMap(),
                savedAt = System.currentTimeMillis(),
            )
            sessionDir.mkdirs()
            file(sessionDir).writeText(json.encodeToString(m))
        }
    }
}
