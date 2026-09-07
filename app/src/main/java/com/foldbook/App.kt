package com.foldbook

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {

    lateinit var connections: ConnectionStore
        private set
    lateinit var sessions: SessionStore
        private set
    val prefs: Prefs by lazy { Prefs(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        connections = ConnectionStore(this)
        sessions = SessionStore(this)
        // 활성 세션에 없는 캐시 + 구버전 찌꺼기 정리
        SessionCache.sweep(this, sessions.activeIds())
        prewarmDrive()
    }

    /**
     * 홈에 구글 드라이브 세션/연결이 있으면 앱 시작 시 백그라운드에서 액세스 토큰을 미리 받아 둔다.
     * (첫 파일을 열 때 비로소 OAuth 토큰을 받느라 로딩이 오래 걸리던 문제 완화)
     */
    private fun prewarmDrive() {
        val emails = connections.flow.value
            .filter { it.type == ConnType.DRIVE }
            .map { it.accountEmail }
            .ifEmpty {
                if (sessions.flow.value.any { it.connType == ConnType.DRIVE }) listOf("") else emptyList()
            }
        if (emails.isEmpty()) return
        appScope.launch {
            emails.distinct().forEach { email ->
                runCatching { DriveAuth.token(this@App, email) }
            }
        }
    }

    companion object {
        fun of(context: Context): App = context.applicationContext as App
    }
}
