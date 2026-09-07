package com.foldbook

import android.content.Context

/** 읽기 설정. 단순 SharedPreferences. */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("foldbook", Context.MODE_PRIVATE)

    var direction: ReadingDirection
        get() = if (sp.getString(K_DIR, "rtl") == "ltr") ReadingDirection.LTR else ReadingDirection.RTL
        set(v) = sp.edit().putString(K_DIR, if (v == ReadingDirection.LTR) "ltr" else "rtl").apply()

    var spreadMode: SpreadMode
        get() = when (sp.getString(K_SPREAD, "auto")) {
            "single" -> SpreadMode.SINGLE
            "double" -> SpreadMode.DOUBLE
            else -> SpreadMode.AUTO
        }
        set(v) = sp.edit().putString(K_SPREAD, v.name.lowercase()).apply()

    var foldFlip: Boolean
        get() = sp.getBoolean(K_FOLD, false)
        set(v) = sp.edit().putBoolean(K_FOLD, v).apply()

    fun splitSpreads(wide: Boolean) = SpreadPolicy.splitSpreads(spreadMode, wide)
    fun doublePage(wide: Boolean) = SpreadPolicy.doublePage(spreadMode, wide)

    private companion object {
        const val K_DIR = "reading_direction"
        const val K_SPREAD = "spread_mode"
        const val K_FOLD = "fold_flip"
    }
}
