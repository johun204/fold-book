package com.foldbook

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foldbook.ui.FoldBookApp
import com.foldbook.ui.FoldBookTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 파일 관리자 등에서 이미지 "열기" 로 들어온 경우 바로 리더로
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            startActivity(Intent(this, ReaderActivity::class.java).setData(intent.data))
            finish()
            return
        }

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
                FoldBookApp()
            }
        }
    }
}
