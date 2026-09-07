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

    /** 좌우 양면 스캔본(가로가 긴 이미지)을 반으로 나눠서 볼지. 끄면 통짜로 축소 표시. */
    var splitWideScans: Boolean
        get() = sp.getBoolean(K_SPLIT, true)
        set(v) = sp.edit().putBoolean(K_SPLIT, v).apply()

    /** 원격(SMB/드라이브)에서도 스캔본 크기를 미리 분석해 분할할지. 끄면 원격은 항상 통짜. */
    var analyzeRemote: Boolean
        get() = sp.getBoolean(K_ANALYZE_REMOTE, true)
        set(v) = sp.edit().putBoolean(K_ANALYZE_REMOTE, v).apply()

    /** 원격 폴더 탐색 시 이미지 썸네일 표시 (파일을 받아와야 해서 데이터를 씀). 로컬은 항상 표시. */
    var remoteThumbnails: Boolean
        get() = sp.getBoolean(K_REMOTE_THUMBS, true)
        set(v) = sp.edit().putBoolean(K_REMOTE_THUMBS, v).apply()

    /** 현재 페이지 기준 앞으로 몇 장까지 미리 받아둘지 (1~12). */
    var prefetchForward: Int
        get() = sp.getInt(K_PREFETCH, 5).coerceIn(1, 12)
        set(v) = sp.edit().putInt(K_PREFETCH, v.coerceIn(1, 12)).apply()

    /** 단순 터치로 페이지를 넘기는 좌/우 가장자리 영역 비율(0=끔, 0.05~0.5). 폴드 펼침/접힘 각각 저장. */
    var tapZoneWide: Float
        get() = sp.getFloat(K_TAPZONE_WIDE, 0.2f).coerceIn(0f, 0.5f)
        set(v) = sp.edit().putFloat(K_TAPZONE_WIDE, v.coerceIn(0f, 0.5f)).apply()

    var tapZoneNarrow: Float
        get() = sp.getFloat(K_TAPZONE_NARROW, 0.3f).coerceIn(0f, 0.5f)
        set(v) = sp.edit().putFloat(K_TAPZONE_NARROW, v.coerceIn(0f, 0.5f)).apply()

    fun doublePage(wide: Boolean) = SpreadPolicy.doublePage(spreadMode, wide)

    private companion object {
        const val K_DIR = "reading_direction"
        const val K_SPREAD = "spread_mode"
        const val K_FOLD = "fold_flip"
        const val K_PREFETCH = "prefetch_forward"
        const val K_SPLIT = "split_wide_scans"
        const val K_ANALYZE_REMOTE = "analyze_remote"
        const val K_REMOTE_THUMBS = "remote_thumbnails"
        const val K_TAPZONE_WIDE = "tap_zone_wide"
        const val K_TAPZONE_NARROW = "tap_zone_narrow"
    }
}
