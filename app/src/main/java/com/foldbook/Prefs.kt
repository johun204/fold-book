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

    /** 파일 다운로드를 Wi-Fi 에서만 할지. 끄면 모바일 데이터로도 바로 받는다. */
    var wifiOnlyDownload: Boolean
        get() = sp.getBoolean(K_WIFI_ONLY, true)
        set(v) = sp.edit().putBoolean(K_WIFI_ONLY, v).apply()

    /** 새로 여는 책에 적용할 스캔 보정 기본 강도 (0=끔, 1~5). */
    var enhanceLevel: Int
        get() = sp.getInt(K_ENHANCE, 0).coerceIn(0, ENHANCE_MAX)
        set(v) = sp.edit().putInt(K_ENHANCE, v.coerceIn(0, ENHANCE_MAX)).apply()

    /** 이미지를 페이지에 맞추는 방식. 기본은 잘림 없이 전부 보이기. */
    var fitMode: FitMode
        get() = when (sp.getString(K_FIT, "both")) {
            "width" -> FitMode.WIDTH
            "height" -> FitMode.HEIGHT
            else -> FitMode.BOTH
        }
        set(v) = sp.edit().putString(K_FIT, v.name.lowercase()).apply()

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

    companion object {
        /** 스캔 보정 최대 강도. */
        const val ENHANCE_MAX = 5

        /** 강도 설명 (0 = 끔). */
        fun enhanceLabel(level: Int) = when (level.coerceIn(0, ENHANCE_MAX)) {
            0 -> "끔"
            1 -> "아주 약하게"
            2 -> "약하게"
            3 -> "보통"
            4 -> "강하게"
            else -> "아주 강하게"
        }

        private const val K_DIR = "reading_direction"
        private const val K_SPREAD = "spread_mode"
        private const val K_FIT = "fit_mode"
        private const val K_ENHANCE = "enhance_level"
        private const val K_WIFI_ONLY = "wifi_only_download"
        private const val K_PREFETCH = "prefetch_forward"
        private const val K_SPLIT = "split_wide_scans"
        private const val K_ANALYZE_REMOTE = "analyze_remote"
        private const val K_REMOTE_THUMBS = "remote_thumbnails"
        private const val K_TAPZONE_WIDE = "tap_zone_wide"
        private const val K_TAPZONE_NARROW = "tap_zone_narrow"
    }
}
