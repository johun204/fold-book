package com.foldbook

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
            FoldBookTheme {
                FoldBookApp()
            }
        }
    }
}
