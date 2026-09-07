package com.comicviewer

import android.app.Activity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.launch

/**
 * 갤럭시 폴드류에서 "살짝 접었다 편다" 제스처를 다음 페이지 넘김으로 해석한다.
 * androidx.window 는 경첩 각도를 raw 로 주지 않으므로 상태 전이로 판정한다:
 *   FLAT -> HALF_OPENED -> FLAT 가 [gestureWindowMs] 안에 일어나면 onFlip().
 */
class FoldGestureDetector(
    private val activity: Activity,
    private val gestureWindowMs: Long = 2500,
    private val onFlip: () -> Unit,
) {
    private var bentAt: Long = 0L
    private var wasFlat = false

    fun start() {
        if (activity !is androidx.lifecycle.LifecycleOwner) return
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(activity)
                    .windowLayoutInfo(activity)
                    .collect { info ->
                        val fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                            ?: return@collect
                        when (fold.state) {
                            FoldingFeature.State.FLAT -> {
                                val now = System.currentTimeMillis()
                                if (bentAt != 0L && now - bentAt <= gestureWindowMs) {
                                    bentAt = 0L
                                    onFlip()
                                }
                                wasFlat = true
                            }
                            FoldingFeature.State.HALF_OPENED -> {
                                if (wasFlat) bentAt = System.currentTimeMillis()
                                wasFlat = false
                            }
                        }
                    }
            }
        }
    }
}
