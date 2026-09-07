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
     * 홈에 구글 드라이브 세션/연결이 있으면 앱 시작 시 백그라운드에서:
     *  1) 액세스 토큰을 미리 받아 두고 (첫 파일 열 때 OAuth 대기 제거)
     *  2) 가장 최근 진행 중 드라이브 세션의 폴더 목록을 한 번 조회해 HTTP 경로를 데운다.
     */
    private fun prewarmDrive() {
        val driveConns = connections.flow.value.filter { it.type == ConnType.DRIVE }
        val hasDriveSession = sessions.flow.value.any { it.connType == ConnType.DRIVE }
        if (driveConns.isEmpty() && !hasDriveSession) return

        val emails = driveConns.map { it.accountEmail }.ifEmpty { listOf("") }.distinct()
        appScope.launch {
            emails.forEach { email -> runCatching { DriveAuth.token(this@App, email) } }

            val recent = sessions.flow.value
                .filter { it.connType == ConnType.DRIVE }
                .maxByOrNull { it.updatedAt } ?: return@launch
            val conn = connections.get(recent.connectionId) ?: return@launch
            runCatching { backendFor(conn, this@App).list(recent.folderId) }
        }
    }

    companion object {
        fun of(context: Context): App = context.applicationContext as App
    }
}
