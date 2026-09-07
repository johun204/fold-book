package com.comicviewer

import android.content.Context
import androidx.preference.PreferenceManager

class Prefs(context: Context) {

    private val sp = PreferenceManager.getDefaultSharedPreferences(context)

    val direction: ReadingDirection
        get() = if (sp.getString(K_DIR, "rtl") == "ltr") ReadingDirection.LTR else ReadingDirection.RTL

    val spreadMode: SpreadMode
        get() = when (sp.getString(K_SPREAD, "auto")) {
            "single" -> SpreadMode.SINGLE
            "double" -> SpreadMode.DOUBLE
            else -> SpreadMode.AUTO
        }

    val foldFlip: Boolean get() = sp.getBoolean(K_FOLD, false)

    fun splitSpreads(wide: Boolean) = SpreadPolicy.splitSpreads(spreadMode, wide)
    fun doublePage(wide: Boolean) = SpreadPolicy.doublePage(spreadMode, wide)

    /** 마지막 SMB 접속 정보 (비밀번호 포함 — ponytail: 평문 저장, 필요 시 EncryptedSharedPreferences 로 교체). */
    var smb: Source.Smb
        get() = Source.Smb(
            host = sp.getString("smb_host", "") ?: "",
            share = sp.getString("smb_share", "") ?: "",
            path = sp.getString("smb_path", "") ?: "",
            user = sp.getString("smb_user", "") ?: "",
            pass = sp.getString("smb_pass", "") ?: "",
            domain = sp.getString("smb_domain", "") ?: "",
        )
        set(v) = sp.edit()
            .putString("smb_host", v.host).putString("smb_share", v.share)
            .putString("smb_path", v.path).putString("smb_user", v.user)
            .putString("smb_pass", v.pass).putString("smb_domain", v.domain)
            .apply()

    companion object {
        const val K_DIR = "reading_direction"
        const val K_SPREAD = "spread_mode"
        const val K_FOLD = "fold_flip"
    }
}
