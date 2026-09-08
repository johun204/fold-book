package com.foldbook

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foldbook.ui.FoldBookApp
import com.foldbook.ui.FoldBookTheme

class MainActivity : ComponentActivity() {

    // 알림 탭 등으로 특정 탭을 열어야 할 때(null = 없음). Compose 가 관찰해 이동 후 소비한다.
    private var pendingRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 파일 관리자 등에서 이미지 "열기" 로 들어온 경우 바로 리더로
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            startActivity(Intent(this, ReaderActivity::class.java).setData(intent.data))
            finish()
            return
        }
        pendingRoute = routeOf(intent)

        enableEdgeToEdge()
        setContent {
            // 다운로드가 진행 중이면 앱이 떠 있는 동안 화면이 꺼지지 않게 한다.
            val dl by DownloadService.state.collectAsStateWithLifecycle()
            LaunchedEffect(dl?.finished, dl == null) {
                if (dl != null && dl?.finished == false) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            FoldBookTheme {
                FoldBookApp(pendingRoute = pendingRoute, onRouteConsumed = { pendingRoute = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeOf(intent)?.let { pendingRoute = it }
    }

    private fun routeOf(i: Intent?): String? =
        if (i?.getBooleanExtra(EXTRA_SHOW_DOWNLOADS, false) == true) "browse" else null

    companion object {
        const val EXTRA_SHOW_DOWNLOADS = "showDownloads"
    }
}
