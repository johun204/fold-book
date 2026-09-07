package com.comicviewer

import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.readonly"

object DriveAuth {
    /** 마지막으로 로그인한 계정의 OAuth 액세스 토큰. 로그인 안 돼 있으면 예외. */
    suspend fun token(ctx: Context): String = withContext(Dispatchers.IO) {
        val acct = GoogleSignIn.getLastSignedInAccount(ctx)?.account
            ?: error("구글 로그인이 필요합니다")
        GoogleAuthUtil.getToken(ctx, acct, "oauth2:$DRIVE_SCOPE")
    }
}

/** Drive v3 REST. 블로킹 → Dispatchers.IO 에서 호출. (id, name) 쌍을 돌려준다. */
object DriveRepo {

    private const val API = "https://www.googleapis.com/drive/v3/files"
    private const val FOLDER_MIME = "application/vnd.google-apps.folder"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String, token: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Bearer $token")
        c.connectTimeout = 15000
        c.readTimeout = 30000
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) error("Drive API $code: ${body.take(300)}")
        return body
    }

    private fun files(json: String): List<Pair<String, String>> {
        val arr = JSONObject(json).optJSONArray("files") ?: return emptyList()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            o.getString("id") to o.getString("name")
        }
    }

    private fun query(token: String, q: String): List<Pair<String, String>> =
        files(get("$API?q=${enc(q)}&fields=files(id,name)&pageSize=1000&orderBy=name", token))

    fun listFolders(token: String, parentId: String): List<Pair<String, String>> =
        query(token, "'$parentId' in parents and mimeType = '$FOLDER_MIME' and trashed = false")
            .sortedWith { a, b -> NaturalOrder.compare(a.second, b.second) }

    fun listImages(token: String, folderId: String): List<Pair<String, String>> =
        query(token, "'$folderId' in parents and mimeType contains 'image/' and trashed = false")
            .sortedWith { a, b -> NaturalOrder.compare(a.second, b.second) }

    fun parentOf(token: String, folderId: String): String? {
        val arr = JSONObject(get("$API/$folderId?fields=parents", token)).optJSONArray("parents")
        return if (arr != null && arr.length() > 0) arr.getString(0) else null
    }

    fun download(token: String, fileId: String, dest: File) {
        val c = URL("$API/$fileId?alt=media").openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Bearer $token")
        c.connectTimeout = 15000
        c.readTimeout = 60000
        if (c.responseCode !in 200..299) error("Drive download ${c.responseCode}")
        c.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
    }

    fun syncFolder(
        token: String, folderId: String, destDir: File,
        progress: (done: Int, total: Int) -> Unit,
    ): List<File> {
        val images = listImages(token, folderId)
        destDir.mkdirs()
        val out = ArrayList<File>(images.size)
        images.forEachIndexed { i, (id, name) ->
            val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val dest = File(destDir, safe)
            if (!dest.exists() || dest.length() == 0L) download(token, id, dest)
            out += dest
            progress(i + 1, images.size)
        }
        return out
    }
}
