package com.foldbook

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** 원격(SMB/Drive) 폴더를 로컬 캐시로 내려받아, 기존 File 기반 뷰어 파이프라인이 그대로 쓰이게 한다. */
object RemoteSync {

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    fun cacheDir(ctx: Context, s: Source): File =
        File(ctx.cacheDir, "comic/" + sha1(s.toJson()))

    /** 폴더를 캐시로 동기화하고, 그 안 이미지들을 자연 정렬한 목록을 돌려준다. */
    suspend fun sync(
        ctx: Context, s: Source,
        progress: (done: Int, total: Int) -> Unit,
    ): List<File> = withContext(Dispatchers.IO) {
        val dir = cacheDir(ctx, s)
        val files = when (s) {
            is Source.Smb -> SmbRepo.syncFolder(s, dir, progress)
            is Source.Drive -> DriveRepo.syncFolder(DriveAuth.token(ctx), s.folderId, dir, progress)
        }
        files.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
    }

    /** 자연 정렬상 다음 형제 폴더의 Source. 없으면 null. */
    suspend fun nextFolder(ctx: Context, s: Source): Source? = withContext(Dispatchers.IO) {
        when (s) {
            is Source.Smb -> {
                val cur = s.path.trim('/')
                val parent = cur.substringBeforeLast('/', "")
                val name = cur.substringAfterLast('/')
                val sibs = SmbRepo.listSiblingFolders(s)
                val idx = sibs.indexOf(name)
                val next = if (idx >= 0) sibs.getOrNull(idx + 1) else null
                next?.let { s.copy(path = if (parent.isEmpty()) it else "$parent/$it") }
            }
            is Source.Drive -> {
                val token = DriveAuth.token(ctx)
                val parent = DriveRepo.parentOf(token, s.folderId) ?: return@withContext null
                val sibs = DriveRepo.listFolders(token, parent)
                val idx = sibs.indexOfFirst { it.first == s.folderId }
                val next = if (idx >= 0) sibs.getOrNull(idx + 1) else null
                next?.let { Source.Drive(it.first, it.second) }
            }
        }
    }
}
