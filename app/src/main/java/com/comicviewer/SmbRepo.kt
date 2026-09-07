package com.comicviewer

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.io.File
import java.util.EnumSet

/** 윈도우 공유폴더(SMB2/3) 접근. 모든 호출은 블로킹이므로 Dispatchers.IO 에서 부를 것. */
object SmbRepo {

    private inline fun <T> withShare(s: Source.Smb, block: (DiskShare) -> T): T {
        SMBClient().connect(s.host).use { conn ->
            val ac =
                if (s.user.isBlank()) AuthenticationContext.anonymous()
                else AuthenticationContext(s.user, s.pass.toCharArray(), s.domain.ifBlank { null })
            val session = conn.authenticate(ac)
            (session.connectShare(s.share) as DiskShare).use { share ->
                return block(share)
            }
        }
    }

    private fun smbPath(p: String) = p.trim('/').replace('/', '\\')

    private fun FileIdBothDirectoryInformation.isDir() =
        (fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L

    /** 폴더 안 이미지들을 로컬 캐시로 내려받는다. 이미 받은 파일은 건너뛴다. */
    fun syncFolder(s: Source.Smb, destDir: File, progress: (done: Int, total: Int) -> Unit): List<File> =
        withShare(s) { share ->
            val dir = smbPath(s.path)
            val names = share.list(dir)
                .filter { !it.isDir() && it.fileName.isImageName() }
                .map { it.fileName }
                .sortedWith { a, b -> NaturalOrder.compare(a, b) }

            destDir.mkdirs()
            val out = ArrayList<File>(names.size)
            names.forEachIndexed { i, name ->
                val dest = File(destDir, name)
                if (!dest.exists() || dest.length() == 0L) {
                    share.openFile(
                        "$dir\\$name", EnumSet.of(AccessMask.GENERIC_READ), null,
                        SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
                    ).use { f ->
                        f.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
                    }
                }
                out += dest
                progress(i + 1, names.size)
            }
            out
        }

    /** path 의 부모 폴더 안 하위 폴더명들 (자연 정렬). */
    fun listSiblingFolders(s: Source.Smb): List<String> {
        val parent = s.path.trim('/').substringBeforeLast('/', "")
        return withShare(s) { share ->
            share.list(smbPath(parent))
                .filter { it.isDir() && it.fileName != "." && it.fileName != ".." }
                .map { it.fileName }
                .sortedWith { a, b -> NaturalOrder.compare(a, b) }
        }
    }
}

fun String.isImageName(): Boolean =
    substringAfterLast('.', "").lowercase() in
        setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic", "heif")
