package com.foldbook

import android.app.Application
import android.content.Context

class App : Application() {

    lateinit var connections: ConnectionStore
        private set
    lateinit var sessions: SessionStore
        private set

    override fun onCreate() {
        super.onCreate()
        connections = ConnectionStore(this)
        sessions = SessionStore(this)
        // 활성 세션에 없는 캐시 + 구버전 찌꺼기 정리
        SessionCache.sweep(this, sessions.activeIds())
    }

    companion object {
        fun of(context: Context): App = context.applicationContext as App
    }
}
