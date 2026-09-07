package com.comicviewer

import org.json.JSONObject

/** 만화 폴더의 출처. 로컬은 그냥 파일 경로라 여기 없고, 원격만 표현한다. */
sealed class Source {

    data class Smb(
        val host: String,
        val share: String,
        val path: String,      // 공유 안에서의 폴더 경로 ("Comics/OnePiece/01"), 구분자는 /
        val user: String,
        val pass: String,
        val domain: String = "",
    ) : Source()

    data class Drive(
        val folderId: String,
        val folderName: String,
    ) : Source()

    fun toJson(): String = when (this) {
        is Smb -> JSONObject()
            .put("t", "smb").put("host", host).put("share", share).put("path", path)
            .put("user", user).put("pass", pass).put("domain", domain).toString()
        is Drive -> JSONObject()
            .put("t", "drive").put("id", folderId).put("name", folderName).toString()
    }

    companion object {
        fun fromJson(s: String?): Source? {
            if (s.isNullOrBlank()) return null
            val o = JSONObject(s)
            return when (o.optString("t")) {
                "smb" -> Smb(
                    o.optString("host"), o.optString("share"), o.optString("path"),
                    o.optString("user"), o.optString("pass"), o.optString("domain"),
                )
                "drive" -> Drive(o.optString("id"), o.optString("name"))
                else -> null
            }
        }
    }
}
