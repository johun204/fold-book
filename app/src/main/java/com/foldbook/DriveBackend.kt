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

    private suspend fun token() = DriveAuth.token(appContext, c.accountEmail)
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun httpGet(url: String, token: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) error("Drive API $code: ${body.take(300)}")
        return body
    }

    override suspend fun list(folderId: String): List<Entry> = withContext(Dispatchers.IO) {
        val t = token()
        val q = "'$folderId' in parents and trashed = false and " +
            "(mimeType = '$FOLDER_MIME' or mimeType contains 'image/')"
        val body = httpGet("$API?q=${enc(q)}&fields=files(id,name,mimeType)&pageSize=1000&orderBy=folder,name", t)
        val arr = JSONObject(body).optJSONArray("files") ?: return@withContext emptyList()
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Entry(o.getString("id"), o.getString("name"), o.getString("mimeType") == FOLDER_MIME)
        }.sortedWith(dirsThenNatural)
    }

    override suspend fun open(entryId: String): InputStream = withContext(Dispatchers.IO) {
        val conn = URL("$API/$entryId?alt=media").openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer ${token()}")
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        if (conn.responseCode !in 200..299) error("Drive download ${conn.responseCode}")
        ByteArrayInputStream(conn.inputStream.use { it.readBytes() })
    }

    override fun localPath(entryId: String): String? = null

    override suspend fun root(): Entry = Entry(c.rootFolderId.ifBlank { "root" }, c.label, true)

    override suspend fun parent(folderId: String): String? = withContext(Dispatchers.IO) {
        if (folderId == "root" || folderId.isBlank()) {
            null
        } else {
            val arr = JSONObject(httpGet("$API/$folderId?fields=parents", token())).optJSONArray("parents")
            if (arr != null && arr.length() > 0) arr.getString(0) else "root"
        }
    }

    override suspend fun siblingFolders(folderId: String): List<Entry> {
        val p = parent(folderId) ?: return emptyList()
        return list(p).filter { it.isDir }
    }
}
