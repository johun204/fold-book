package com.foldbook

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

const val DRIVE_SCOPE_URL = "https://www.googleapis.com/auth/drive.readonly"

/** Drive REST 가 2xx 가 아닌 상태로 응답함. */
class DriveHttpException(val code: Int, message: String) : RuntimeException(message)

object DriveAuth {
    /** 특정 계정(email)의 액세스 토큰. email 이 비면 마지막 로그인 계정. */
    suspend fun token(context: Context, email: String): String = withContext(Dispatchers.IO) {
        val acct: Account = if (email.isBlank())
            GoogleSignIn.getLastSignedInAccount(context)?.account ?: error("구글 로그인이 필요합니다")
        else Account(email, "com.google")
        GoogleAuthUtil.getToken(context, acct, "oauth2:$DRIVE_SCOPE_URL")
    }
}

/** 구글 드라이브 (Drive v3 REST). folderId = Drive 파일 id, 루트는 "root". */
class DriveBackend(private val c: Connection, private val appContext: Context) : StorageBackend {

    private companion object {
        const val API = "https://www.googleapis.com/drive/v3/files"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /**
     * 액세스 토큰으로 [block] 을 실행한다. 401(캐시된 토큰이 만료·폐기됨)이면 그 토큰을 버리고
     * 새로 받아 한 번 더 시도한다.
     */
    private suspend fun <T> authed(block: (token: String) -> T): T {
        val t = DriveAuth.token(appContext, c.accountEmail)
        try {
            return block(t)
        } catch (e: DriveHttpException) {
            if (e.code != 401) throw e
            runCatching { GoogleAuthUtil.clearToken(appContext, t) }
        }
        return block(DriveAuth.token(appContext, c.accountEmail))
    }

    private fun connect(url: String, token: String, readTimeout: Int, range: String? = null): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer $token")
        if (range != null) conn.setRequestProperty("Range", range)
        conn.connectTimeout = 15000
        conn.readTimeout = readTimeout
        val code = conn.responseCode
        if (code !in 200..299) {
            val body = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw DriveHttpException(code, "Drive API $code: ${body.take(300)}")
        }
        return conn
    }

    private fun httpGet(url: String, token: String): String =
        connect(url, token, 30000).inputStream.bufferedReader().use { it.readText() }

    private fun download(url: String, token: String, readTimeout: Int, range: String? = null): ByteArray =
        connect(url, token, readTimeout, range).inputStream.use { it.readBytes() }

    override suspend fun list(folderId: String): List<Entry> = withContext(Dispatchers.IO) {
        val q = "'$folderId' in parents and trashed = false and " +
            "(mimeType = '$FOLDER_MIME' or mimeType contains 'image/')"
        val base = "$API?q=${enc(q)}&fields=nextPageToken,files(id,name,mimeType)&pageSize=1000&orderBy=folder,name"
        val out = ArrayList<Entry>()
        var pageToken: String? = null
        do {      // 한 번에 최대 1000개 — 더 있으면 nextPageToken 으로 이어 받는다
            val url = if (pageToken == null) base else "$base&pageToken=${enc(pageToken)}"
            val json = JSONObject(authed { httpGet(url, it) })
            val arr = json.optJSONArray("files")
            for (i in 0 until (arr?.length() ?: 0)) {
                val o = arr!!.getJSONObject(i)
                out += Entry(o.getString("id"), o.getString("name"), o.getString("mimeType") == FOLDER_MIME)
            }
            pageToken = json.optString("nextPageToken").ifEmpty { null }
        } while (pageToken != null)
        out.sortedWith(dirsThenNatural)
    }

    override suspend fun open(entryId: String): InputStream = withContext(Dispatchers.IO) {
        ByteArrayInputStream(authed { download("$API/$entryId?alt=media", it, 60000) })
    }

    /** 드라이브가 만들어 둔 작은 미리보기를 쓴다 (원본 전체를 받지 않는다). 없으면 원본. */
    override suspend fun openThumbnail(entryId: String): InputStream = withContext(Dispatchers.IO) {
        val thumb = runCatching {
            authed { t ->
                val link = JSONObject(httpGet("$API/$entryId?fields=thumbnailLink", t)).optString("thumbnailLink")
                if (link.isEmpty()) null else download(link, t, 20000)
            }
        }.getOrNull()
        if (thumb != null) ByteArrayInputStream(thumb) else open(entryId)
    }

    override fun localPath(entryId: String): String? = null

    override suspend fun root(): Entry = Entry(c.rootFolderId.ifBlank { "root" }, c.label, true)

    override suspend fun parent(folderId: String): String? = withContext(Dispatchers.IO) {
        if (folderId == "root" || folderId.isBlank()) {
            null
        } else {
            val arr = JSONObject(authed { httpGet("$API/$folderId?fields=parents", it) }).optJSONArray("parents")
            if (arr != null && arr.length() > 0) arr.getString(0) else "root"
        }
    }

    override suspend fun siblingFolders(folderId: String): List<Entry> {
        val p = parent(folderId) ?: return emptyList()
        return list(p).filter { it.isDir }
    }

    override suspend fun imageSize(entryId: String): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        runCatching {
            sizeFromPrefix(authed { download("$API/$entryId?alt=media", it, 20000, range = "bytes=0-65535") })
        }.getOrNull()
    }
}
