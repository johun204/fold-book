package com.foldbook

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** 다운로드 진행 상태. UI 배너 + MainActivity 화면 켜짐 유지에 쓴다. */
data class DownloadState(
    val label: String,
    val done: Int,
    val total: Int,
    val finished: Boolean = false,
    val error: String? = null,
)

/**
 * 네트워크 드라이브 → 로컬(SAF) 파일·폴더 다운로드를 포그라운드 서비스로 수행한다.
 * 화면을 벗어나거나 잠겨도 계속되고, 진행률을 알림으로 표시한다.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var treeUri: Uri? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "다운로드", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            job?.cancel()
            return START_NOT_STICKY
        }
        val connId = intent?.getStringExtra(EX_CONN)
        val tree = intent?.getStringExtra(EX_TREE)?.let(Uri::parse)
        if (connId == null || tree == null) { stopSelf(); return START_NOT_STICKY }
        treeUri = tree
        val ids = intent.getStringArrayExtra(EX_IDS) ?: emptyArray()
        val names = intent.getStringArrayExtra(EX_NAMES) ?: emptyArray()
        val dirs = intent.getBooleanArrayExtra(EX_DIRS) ?: BooleanArray(ids.size)
        val label = intent.getStringExtra(EX_LABEL) ?: "다운로드"

        state.value = DownloadState(label, 0, 0)
        startForegroundCompat(notif(label, 0, 0, indeterminate = true))

        job = scope.launch {
            val conn = App.of(this@DownloadService).connections.get(connId)
            val dest = conn?.let { DocumentFile.fromTreeUri(applicationContext, tree) }
            if (conn == null || dest == null) {
                finish(DownloadState(label, 0, 0, finished = true, error = "저장소/저장 위치 오류")); return@launch
            }
            val backend = backendFor(conn, applicationContext)
            val roots = ids.indices.map { Entry(ids[it], names[it], dirs.getOrElse(it) { false }) }

            runCatching {
                val total = countFiles(backend, roots)
                state.value = DownloadState(label, 0, total)
                pushNotif(notif(label, 0, total, indeterminate = false))
                var done = 0
                for (e in roots) copyInto(applicationContext, dest, backend, e) {
                    done++
                    state.value = DownloadState(label, done, total)
                    if (done % 2 == 0 || done == total) pushNotif(notif(label, done, total, indeterminate = false))
                }
                done
            }.onSuccess { n ->
                finish(DownloadState(label, n, n, finished = true))
                finalNotif("다운로드 완료", "$label · ${n}개")
            }.onFailure { t ->
                if (t is kotlinx.coroutines.CancellationException) {
                    finish(DownloadState(label, state.value?.done ?: 0, state.value?.total ?: 0, finished = true, error = "취소됨"))
                } else {
                    finish(DownloadState(label, state.value?.done ?: 0, state.value?.total ?: 0, finished = true, error = t.message ?: "실패"))
                    finalNotif("다운로드 실패", t.message ?: "")
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun finish(s: DownloadState) {
        state.value = s
        treeUri?.let { u ->
            runCatching {
                contentResolver.releasePersistableUriPermission(
                    u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ---- 알림 -----------------------------------------------------------

    private fun startForegroundCompat(n: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun pushNotif(n: android.app.Notification) =
        runCatching { NotificationManagerCompat.from(this).notify(NOTIF_ID, n) }

    private fun finalNotif(title: String, text: String) = runCatching {
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title).setContentText(text)
            .setContentIntent(contentPi())
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(NOTIF_ID + 1, n)
    }

    private fun notif(label: String, done: Int, total: Int, indeterminate: Boolean): android.app.Notification {
        val cancelPi = PendingIntent.getService(
            this, 1,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("다운로드 중 · $label")
            .setContentText(if (total > 0) "$done / $total" else "목록 확인 중…")
            .setProgress(total.coerceAtLeast(0), done, indeterminate || total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPi())
            .addAction(0, "취소", cancelPi)
            .build()
    }

    private fun contentPi(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        /** 현재 다운로드 상태(없으면 null). UI 배너 + 화면 켜짐 유지가 관찰. */
        val state = MutableStateFlow<DownloadState?>(null)

        private const val CHANNEL = "download"
        private const val NOTIF_ID = 42
        private const val ACTION_CANCEL = "com.foldbook.DOWNLOAD_CANCEL"
        private const val EX_CONN = "conn"
        private const val EX_TREE = "tree"
        private const val EX_IDS = "ids"
        private const val EX_NAMES = "names"
        private const val EX_DIRS = "dirs"
        private const val EX_LABEL = "label"

        fun start(ctx: Context, connId: String, tree: Uri, roots: List<Entry>, label: String) {
            state.value = DownloadState(label, 0, 0)
            val i = Intent(ctx, DownloadService::class.java).apply {
                putExtra(EX_CONN, connId)
                putExtra(EX_TREE, tree.toString())
                putExtra(EX_IDS, roots.map { it.id }.toTypedArray())
                putExtra(EX_NAMES, roots.map { it.name }.toTypedArray())
                putExtra(EX_DIRS, roots.map { it.isDir }.toBooleanArray())
                putExtra(EX_LABEL, label)
            }
            ContextCompat.startForegroundService(ctx, i)
        }

        fun cancel(ctx: Context) {
            runCatching {
                ctx.startService(Intent(ctx, DownloadService::class.java).setAction(ACTION_CANCEL))
            }
        }

        fun dismiss() { state.value = null }
    }
}

private suspend fun countFiles(backend: StorageBackend, entries: List<Entry>): Int {
    var n = 0
    for (e in entries) n += if (e.isDir) countFiles(backend, backend.list(e.id)) else 1
    return n
}

private suspend fun copyInto(
    ctx: Context,
    dest: DocumentFile,
    backend: StorageBackend,
    entry: Entry,
    onFile: () -> Unit,
) {
    currentCoroutineContext().ensureActive()
    if (entry.isDir) {
        val sub = dest.findFile(entry.name)?.takeIf { it.isDirectory }
            ?: dest.createDirectory(entry.name)
            ?: return
        for (child in backend.list(entry.id)) copyInto(ctx, sub, backend, child, onFile)
    } else {
        val file = dest.findFile(entry.name)?.takeIf { it.isFile }
            ?: dest.createFile(mimeOf(entry.name), entry.name)
            ?: return
        ctx.contentResolver.openOutputStream(file.uri, "wt")?.use { out ->
            backend.open(entry.id).use { it.copyTo(out) }
        }
        onFile()
    }
}

private fun mimeOf(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "bmp" -> "image/bmp"
    "heic", "heif" -> "image/heic"
    "avif" -> "image/avif"
    else -> "image/jpeg"
}
