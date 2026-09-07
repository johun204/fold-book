package com.foldbook

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** 로컬/SMB/Drive 공통 저장소 접근. folderId 는 백엔드별 식별자. */
interface StorageBackend {
    /** 폴더 안 하위폴더 + 이미지 (폴더 먼저, 각각 자연정렬). */
    suspend fun list(folderId: String): List<Entry>

    /** 이미지 원본 바이트. 호출측이 close 한다. */
    suspend fun open(entryId: String): InputStream

    /** 로컬 파일이면 경로, 원격이면 null. */
    fun localPath(entryId: String): String?

    /** 탐색 시작 폴더. */
    suspend fun root(): Entry

    /** folderId 의 상위 폴더 id (없으면 null). */
    suspend fun parent(folderId: String): String?

    /** folderId 부모 아래 하위 폴더들 (자연정렬) — '다음 폴더' 계산용. */
    suspend fun siblingFolders(folderId: String): List<Entry>
}

internal val dirsThenNatural: Comparator<Entry> =
    Comparator { a, b ->
        if (a.isDir != b.isDir) return@Comparator if (a.isDir) -1 else 1
        NaturalOrder.compare(a.name, b.name)
    }

fun backendFor(conn: Connection, appContext: Context): StorageBackend = when (conn.type) {
    ConnType.LOCAL -> LocalBackend()
    ConnType.SMB -> SmbBackend(conn)
    ConnType.DRIVE -> DriveBackend(conn, appContext.applicationContext)
}

class LocalBackend : StorageBackend {

    override suspend fun list(folderId: String): List<Entry> = withContext(Dispatchers.IO) {
        val kids = File(folderId).listFiles()?.toList().orEmpty()
        kids.mapNotNull { f ->
            when {
                f.isDirectory -> Entry(f.absolutePath, f.name, true)
                f.isFile && f.name.isImageName() -> Entry(f.absolutePath, f.name, false, f.length())
                else -> null
            }
        }.sortedWith(dirsThenNatural)
    }

    override suspend fun open(entryId: String): InputStream = File(entryId).inputStream()

    override fun localPath(entryId: String): String = entryId

    override suspend fun root(): Entry =
        Environment.getExternalStorageDirectory().let { Entry(it.absolutePath, "내장 저장소", true) }

    override suspend fun parent(folderId: String): String? = File(folderId).parentFile?.absolutePath

    override suspend fun siblingFolders(folderId: String): List<Entry> = withContext(Dispatchers.IO) {
        val p = File(folderId).parentFile ?: return@withContext emptyList()
        (p.listFiles { f -> f.isDirectory }?.toList().orEmpty())
            .map { Entry(it.absolutePath, it.name, true) }
            .sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
    }
}
