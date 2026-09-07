package com.foldbook

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * content:// / file:// URI 를 실제 파일 경로로 바꾼다. 모든 파일 접근 권한이 있어야 File API 로 읽을 수 있다.
 * ponytail: externalstorage / 미디어 provider 만 처리 (대부분 기기에서 충분).
 */
fun Context.resolveToFilePath(uri: Uri): String? {
    if (uri.scheme == "file") return uri.path

    if (uri.authority == "com.android.externalstorage.documents" && DocumentsContract.isDocumentUri(this, uri)) {
        val parts = DocumentsContract.getDocumentId(uri).split(":", limit = 2)
        val type = parts[0]
        val rel = parts.getOrElse(1) { "" }
        if (type.equals("primary", ignoreCase = true)) {
            return Environment.getExternalStorageDirectory().absolutePath + "/" + rel
        }
        if (File("/storage/$type").exists()) return "/storage/$type/$rel"
    }

    return runCatching {
        contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
