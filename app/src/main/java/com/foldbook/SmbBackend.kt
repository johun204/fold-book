package com.foldbook

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.EnumSet

/** 윈도우 공유폴더(SMB2/3). folderId = 공유 안에서의 경로 ("" = 공유 루트, "/" 구분). */
class SmbBackend(private val c: Connection) : StorageBackend {

    private fun smb(p: String) = p.trim('/').replace('/', '\\')
    private fun join(dir: String, name: String) = (dir.trim('/').let { if (it.isEmpty()) name else "$it/$name" })

    private inline fun <T> withShare(block: (DiskShare) -> T): T {
        SMBClient().connect(c.host).use { conn ->
            val ac =
                if (c.user.isBlank()) AuthenticationContext.anonymous()
                else AuthenticationContext(c.user, c.pass.toCharArray(), c.domain.ifBlank { null })
            val session = conn.authenticate(ac)
            (session.connectShare(c.share) as DiskShare).use { share -> return block(share) }
        }
    }

    override suspend fun list(folderId: String): List<Entry> = withContext(Dispatchers.IO) {
        withShare { share ->
            share.list(smb(folderId))
                .filter { it.fileName != "." && it.fileName != ".." }
                .mapNotNull { fi ->
                    val isDir = (fi.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                    when {
                        isDir -> Entry(join(folderId, fi.fileName), fi.fileName, true)
                        fi.fileName.isImageName() -> Entry(join(folderId, fi.fileName), fi.fileName, false, fi.endOfFile)
                        else -> null
                    }
                }
                .sortedWith(dirsThenNatural)
        }
    }

    override suspend fun open(entryId: String): InputStream = withContext(Dispatchers.IO) {
        withShare { share ->
            share.openFile(
                smb(entryId), EnumSet.of(AccessMask.GENERIC_READ), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
            ).use { f -> ByteArrayInputStream(f.inputStream.use { it.readBytes() }) }
        }
    }

    override fun localPath(entryId: String): String? = null

    override suspend fun root(): Entry = Entry(c.basePath.trim('/'), c.label, true)

    override suspend fun parent(folderId: String): String? {
        val t = folderId.trim('/')
        return if (t.isEmpty()) null else t.substringBeforeLast('/', "")
    }

    override suspend fun siblingFolders(folderId: String): List<Entry> {
        val p = parent(folderId) ?: return emptyList()
        return list(p).filter { it.isDir }
    }

    override suspend fun imageSize(entryId: String): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        runCatching {
            withShare { share ->
                share.openFile(
                    smb(entryId), EnumSet.of(AccessMask.GENERIC_READ), null,
                    SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
                ).use { f ->
                    val buf = ByteArray(65536)
                    var n = 0
                    f.inputStream.use { ins ->
                        while (n < buf.size) {
                            val r = ins.read(buf, n, buf.size - n)
                            if (r < 0) break
                            n += r
                        }
                    }
                    sizeFromPrefix(if (n == buf.size) buf else buf.copyOf(n))
                }
            }
        }.getOrNull()
    }
}
