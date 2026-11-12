package com.foldbook

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 공유폴더 연결을 호출마다 새로 맺지 않고(TCP 연결 + 인증이 파일 하나 받는 것보다 오래 걸린다)
 * 열어 둔 채 재사용한다. 한동안 안 쓰면 닫는다.
 */
private object SmbPool {

    class Holder(val key: String, val client: SMBClient, val share: DiskShare) {
        var users = 0
        var lastUsed = System.nanoTime()
    }

    private const val IDLE_CLOSE_SEC = 60L
    private val holders = HashMap<String, Holder>()     // guarded by this

    init {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "smb-idle").apply { isDaemon = true } }
            .scheduleWithFixedDelay(::closeIdle, IDLE_CLOSE_SEC, IDLE_CLOSE_SEC / 2, TimeUnit.SECONDS)
    }

    private fun keyOf(c: Connection) = listOf(c.host, c.share, c.domain, c.user, c.pass).joinToString("\u0000")

    @Synchronized
    fun acquire(c: Connection): Holder {
        val key = keyOf(c)
        val h = holders[key]?.takeIf { it.share.isConnected } ?: run {
            holders.remove(key)?.let(::close)
            connect(key, c).also { holders[key] = it }
        }
        h.users++
        return h
    }

    @Synchronized
    fun release(h: Holder) {
        h.users--
        h.lastUsed = System.nanoTime()
    }

    /** 연결이 끊긴 것으로 보이는 홀더를 버린다. 다음 acquire 가 새로 맺는다. */
    @Synchronized
    fun drop(h: Holder) {
        if (holders[h.key] === h) holders.remove(h.key)
        close(h)
    }

    private fun connect(key: String, c: Connection): Holder {
        val client = SMBClient()
        try {
            val ac =
                if (c.user.isBlank()) AuthenticationContext.anonymous()
                else AuthenticationContext(c.user, c.pass.toCharArray(), c.domain.ifBlank { null })
            val share = client.connect(c.host).authenticate(ac).connectShare(c.share) as DiskShare
            return Holder(key, client, share)
        } catch (e: Exception) {
            runCatching { client.close() }
            throw e
        }
    }

    private fun close(h: Holder) {
        runCatching { h.share.close() }
        runCatching { h.client.close() }      // 연결과 세션까지 닫는다
    }

    @Synchronized
    private fun closeIdle() {
        val limit = System.nanoTime() - TimeUnit.SECONDS.toNanos(IDLE_CLOSE_SEC)
        val it = holders.values.iterator()
        while (it.hasNext()) {
            val h = it.next()
            if (h.users == 0 && h.lastUsed < limit) {
                it.remove()
                close(h)
            }
        }
    }
}

/** 윈도우 공유폴더(SMB2/3). folderId = 공유 안에서의 경로 ("" = 공유 루트, "/" 구분). */
class SmbBackend(private val c: Connection) : StorageBackend {

    private fun smb(p: String) = p.trim('/').replace('/', '\\')
    private fun join(dir: String, name: String) = (dir.trim('/').let { if (it.isEmpty()) name else "$it/$name" })

    /**
     * 열어 둔 연결([SmbPool])을 재사용한다. 연결이 죽어 있었으면 한 번 새로 붙어 다시 시도한다
     * (서버가 상태 코드로 거절한 것 — 없는 파일, 권한 없음 — 은 연결 문제가 아니므로 그대로 던진다).
     */
    private inline fun <T> withShare(block: (DiskShare) -> T): T {
        val first = SmbPool.acquire(c)
        try {
            return block(first.share)
        } catch (e: Exception) {
            if (e is SMBApiException || !(e is IOException || e is SMBRuntimeException)) throw e
            SmbPool.drop(first)
        } finally {
            SmbPool.release(first)
        }
        val second = SmbPool.acquire(c)
        try {
            return block(second.share)
        } finally {
            SmbPool.release(second)
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
