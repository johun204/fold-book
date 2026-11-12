package com.foldbook

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** 다운로드 진행 상태. UI 카드 + MainActivity 화면 켜짐 유지가 관찰한다. */
data class DownloadState(
    val label: String,
    val done: Int,
    val total: Int,
    val currentFile: String = "",
    val roots: List<String> = emptyList(),
    /** 이 이름의 파일이 이미 있어 사용자 선택 대기 중(덮어쓰기/건너뛰기). null 이면 대기 아님. */
    val conflict: String? = null,
    /** Wi-Fi 가 끊겨 일시중지 중 (다시 연결되면 자동으로 이어받는다). */
    val paused: Boolean = false,
    val finished: Boolean = false,
    val error: String? = null,
)

/**
 * 네트워크 드라이브 → 로컬(SAF) 파일·폴더 다운로드를 포그라운드 서비스로 수행한다.
 * 화면을 벗어나거나 잠겨도 계속되고, 진행률을 알림으로 표시한다.
 * 대상 경로에 같은 이름의 파일이 이미 있으면 앱에서 덮어쓰기/건너뛰기(모두 적용)를 물어본다.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var treeUri: Uri? = null

    private var pending: CompletableDeferred<Boolean>? = null
    private var overwriteAll: Boolean? = null   // null=미결정, true=모두 덮어쓰기, false=모두 건너뛰기
    @Volatile private var allowMetered = false  // 모바일 데이터로도 받기 (사용자가 허용)
    @Volatile private var timedOut = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "다운로드", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                pending?.cancel()
                job?.cancel()
                stopIfIdle()
                return START_NOT_STICKY
            }
            ACTION_ALLOW_METERED -> {
                allowMetered = true
                stopIfIdle()
                return START_NOT_STICKY
            }
            ACTION_RESOLVE -> {
                val ow = intent.getBooleanExtra(EX_OVERWRITE, false)
                if (intent.getBooleanExtra(EX_ALL, false)) overwriteAll = ow
                pending?.complete(ow)
                stopIfIdle()
                return START_NOT_STICKY
            }
        }
        // 한 번에 하나만. 진행 중에 또 시작하면 두 작업이 같은 상태를 덮어쓰고, 먼저 끝난 쪽이 서비스를 내린다.
        if (job?.isActive == true) return START_NOT_STICKY

        val connId = intent?.getStringExtra(EX_CONN)
        val tree = intent?.getStringExtra(EX_TREE)?.let(Uri::parse)
        if (connId == null || tree == null) { stopSelf(); return START_NOT_STICKY }
        treeUri = tree
        val ids = intent.getStringArrayExtra(EX_IDS) ?: emptyArray()
        val names = intent.getStringArrayExtra(EX_NAMES) ?: emptyArray()
        val dirs = intent.getBooleanArrayExtra(EX_DIRS) ?: BooleanArray(ids.size)
        val label = intent.getStringExtra(EX_LABEL) ?: "다운로드"
        allowMetered = intent.getBooleanExtra(EX_METERED, false)
        overwriteAll = null
        timedOut = false
        val rootNames = names.toList()

        state.value = DownloadState(label, 0, 0, roots = rootNames)
        startForegroundCompat(notif(label, 0, 0, indeterminate = true, conflict = null))

        job = scope.launch {
            val conn = App.of(this@DownloadService).connections.get(connId)
            val dest = conn?.let { DocumentFile.fromTreeUri(applicationContext, tree) }
            if (conn == null || dest == null) {
                finish(state.value!!.copy(finished = true, error = "저장소/저장 위치 오류")); return@launch
            }
            val backend = backendFor(conn, applicationContext)
            val roots = ids.indices.map { Entry(ids[it], names[it], dirs.getOrElse(it) { false }) }

            runCatching {
                val total = countFiles(backend, roots)
                state.value = state.value!!.copy(total = total)
                pushNotif(notif(label, 0, total, indeterminate = false, conflict = null))
                var done = 0
                val existing = childrenByName(dest)
                for (e in roots) copyInto(
                    applicationContext, dest, existing, backend, e,
                    ask = { name -> askConflict(name) },
                    awaitNetwork = { awaitNetwork() },
                    onLeaf = { name ->
                        done++
                        state.value = state.value?.copy(done = done, currentFile = name)
                        if (done % 2 == 0 || done == total) {
                            pushNotif(notif(label, done, total, indeterminate = false, conflict = null))
                        }
                    },
                )
                done
            }.onSuccess { n ->
                finish(state.value!!.copy(done = n, total = n, currentFile = "", finished = true))
                finalNotif("다운로드 완료", "$label · ${n}개")
            }.onFailure { t ->
                if (t is kotlinx.coroutines.CancellationException) {
                    finish((state.value ?: DownloadState(label, 0, 0)).copy(finished = true, error = if (timedOut) "시간 제한으로 중단됨" else "취소됨"))
                } else {
                    finish((state.value ?: DownloadState(label, 0, 0)).copy(finished = true, error = t.message ?: "실패"))
                    finalNotif("다운로드 실패", t.message ?: "")
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Wi-Fi 전용인데 Wi-Fi 가 아니면(또는 연결이 끊겼으면) 다시 연결될 때까지 멈춘다.
     * 사용자가 "모바일 데이터로 계속"을 고르면 [allowMetered] 가 켜져 바로 빠져나온다.
     */
    private suspend fun awaitNetwork() {
        if (allowMetered || isUnmetered()) return
        state.value = state.value?.copy(paused = true)
        pushState()
        while (!allowMetered && !isUnmetered()) {
            currentCoroutineContext().ensureActive()
            delay(1000)          // ponytail: 멈춰 있는 동안만 1초 폴링. 콜백 등록보다 단순하다.
        }
        state.value = state.value?.copy(paused = false)
        pushState()
    }

    private fun isUnmetered(): Boolean = isUnmetered(this)

    /** 받는 작업 없이 취소·선택 인텐트만 온 경우(프로세스가 죽었다 살아난 뒤 등) 서비스를 남겨두지 않는다. */
    private fun stopIfIdle() {
        if (job?.isActive != true) stopSelf()
    }

    /** Android 15+: dataSync 포그라운드 서비스는 하루 6시간까지. 넘기면 여기서 스스로 멈춰야 한다. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        timedOut = true
        pending?.cancel()
        job?.cancel()
        stopIfIdle()
    }

    private fun pushState() {
        state.value?.let {
            pushNotif(notif(it.label, it.done, it.total, indeterminate = it.total == 0, conflict = it.conflict))
        }
    }

    /** 이미 있는 파일 처리를 물어본다. overwriteAll 이 정해져 있으면 바로 그 값 사용. */
    private suspend fun askConflict(name: String): Boolean {
        overwriteAll?.let { return it }
        val d = CompletableDeferred<Boolean>()
        pending = d
        val s = state.value
        state.value = s?.copy(conflict = name)
        pushNotif(notif(s?.label ?: "", s?.done ?: 0, s?.total ?: 0, indeterminate = false, conflict = name))
        return try {
            d.await()
        } finally {
            pending = null
            state.value = state.value?.copy(conflict = null)
            state.value?.let { pushNotif(notif(it.label, it.done, it.total, indeterminate = false, conflict = null)) }
        }
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
            .setContentIntent(openAppPi())
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(NOTIF_ID + 1, n)
    }

    private fun notif(
        label: String, done: Int, total: Int, indeterminate: Boolean, conflict: String?,
    ): android.app.Notification {
        val cancelPi = PendingIntent.getService(
            this, 1,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val meteredPi = PendingIntent.getService(
            this, 2,
            Intent(this, DownloadService::class.java).setAction(ACTION_ALLOW_METERED),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppPi())
            .addAction(0, "취소", cancelPi)
        if (state.value?.paused == true) {
            b.setContentTitle("일시중지 · Wi-Fi 를 기다리는 중")
                .setContentText("$done / $total · Wi-Fi 에 다시 연결되면 이어서 받습니다")
                .setProgress(0, 0, false)
                .addAction(0, "모바일 데이터로 계속", meteredPi)
            return b.build()
        }
        if (conflict != null) {
            b.setContentTitle("이미 있는 파일: $conflict")
                .setContentText("앱을 열어 덮어쓰기 / 건너뛰기를 선택하세요")
                .setProgress(0, 0, false)
        } else {
            b.setContentTitle("다운로드 중 · $label")
                .setContentText(if (total > 0) "$done / $total" else "목록 확인 중…")
                .setProgress(total.coerceAtLeast(0), done, indeterminate || total == 0)
        }
        return b.build()
    }

    /** 알림 탭 → 앱을 열고 탐색 탭의 다운로드 목록으로. */
    private fun openAppPi(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SHOW_DOWNLOADS, true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        /** 현재 다운로드 상태(없으면 null). */
        val state = MutableStateFlow<DownloadState?>(null)

        private const val CHANNEL = "download"
        private const val NOTIF_ID = 42
        private const val ACTION_CANCEL = "com.foldbook.DOWNLOAD_CANCEL"
        private const val ACTION_RESOLVE = "com.foldbook.DOWNLOAD_RESOLVE"
        private const val ACTION_ALLOW_METERED = "com.foldbook.DOWNLOAD_ALLOW_METERED"
        private const val EX_CONN = "conn"
        private const val EX_TREE = "tree"
        private const val EX_IDS = "ids"
        private const val EX_NAMES = "names"
        private const val EX_DIRS = "dirs"
        private const val EX_LABEL = "label"
        private const val EX_OVERWRITE = "overwrite"
        private const val EX_ALL = "all"
        private const val EX_METERED = "metered"

        /** 지금 연결이 Wi-Fi(종량제 아님)인가. 연결이 아예 없으면 false. */
        fun isUnmetered(ctx: Context): Boolean {
            val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }

        /** 받는 중인가 (끝났거나 닫지 않은 결과만 남은 상태는 아님). */
        val isActive get() = state.value?.finished == false

        /** allowMetered=true 면 모바일 데이터에서도 멈추지 않는다. 이미 받는 중이면 무시. */
        fun start(
            ctx: Context, connId: String, tree: Uri, roots: List<Entry>, label: String,
            allowMetered: Boolean,
        ) {
            if (isActive) return
            state.value = DownloadState(label, 0, 0, roots = roots.map { it.name })
            val i = Intent(ctx, DownloadService::class.java).apply {
                putExtra(EX_CONN, connId)
                putExtra(EX_TREE, tree.toString())
                putExtra(EX_IDS, roots.map { it.id }.toTypedArray())
                putExtra(EX_NAMES, roots.map { it.name }.toTypedArray())
                putExtra(EX_DIRS, roots.map { it.isDir }.toBooleanArray())
                putExtra(EX_LABEL, label)
                putExtra(EX_METERED, allowMetered)
            }
            ContextCompat.startForegroundService(ctx, i)
        }

        fun cancel(ctx: Context) = runCatching {
            ctx.startService(Intent(ctx, DownloadService::class.java).setAction(ACTION_CANCEL))
        }

        /** 충돌 해결: overwrite=덮어쓰기 여부, all=이후 모두 같은 선택 적용. */
        fun resolve(ctx: Context, overwrite: Boolean, all: Boolean) = runCatching {
            ctx.startService(
                Intent(ctx, DownloadService::class.java).setAction(ACTION_RESOLVE)
                    .putExtra(EX_OVERWRITE, overwrite).putExtra(EX_ALL, all),
            )
        }

        /** 일시중지 상태에서 모바일 데이터로 이어받기. */
        fun allowMetered(ctx: Context) = runCatching {
            ctx.startService(Intent(ctx, DownloadService::class.java).setAction(ACTION_ALLOW_METERED))
        }

        fun dismiss() { state.value = null }
    }
}

private suspend fun countFiles(backend: StorageBackend, entries: List<Entry>): Int {
    var n = 0
    for (e in entries) n += if (e.isDir) countFiles(backend, backend.list(e.id)) else 1
    return n
}

/** 폴더 안 항목을 한 번만 나열해 이름으로 찾는다 (DocumentFile.findFile 은 부를 때마다 전체를 나열). */
private fun childrenByName(dir: DocumentFile): Map<String, DocumentFile> =
    dir.listFiles().mapNotNull { f -> f.name?.let { it to f } }.toMap()

/** 연결이 끊겨 생긴 오류인가 (다시 받아볼 만한가). smbj 는 대부분 런타임 예외로 던진다. */
private fun isTransient(e: Exception) =
    e is java.io.IOException || e is com.hierynomus.smbj.common.SMBRuntimeException

private suspend fun copyInto(
    ctx: Context,
    dest: DocumentFile,
    existingInDest: Map<String, DocumentFile>,
    backend: StorageBackend,
    entry: Entry,
    ask: suspend (name: String) -> Boolean,
    awaitNetwork: suspend () -> Unit,
    onLeaf: (name: String) -> Unit,
) {
    currentCoroutineContext().ensureActive()
    awaitNetwork()
    if (entry.isDir) {
        val found = existingInDest[entry.name]?.takeIf { it.isDirectory }
        val sub = found ?: dest.createDirectory(entry.name) ?: return
        val existing = if (found != null) childrenByName(sub) else emptyMap()
        for (child in backend.list(entry.id)) {
            copyInto(ctx, sub, existing, backend, child, ask, awaitNetwork, onLeaf)
        }
    } else {
        val existing = existingInDest[entry.name]?.takeIf { it.isFile }
        if (existing != null && !ask(entry.name)) {
            onLeaf(entry.name)   // 건너뛰기 — 진행 카운트에는 포함
            return
        }
        val file = existing ?: dest.createFile(mimeOf(entry.name), entry.name) ?: return
        // 받는 중에 연결이 끊기면 그 파일만 처음부터 다시 받는다("wt" 라 기존 내용은 잘린다).
        // 끝내 못 받았거나 취소되면 잘린 파일을 남기지 않는다.
        var attempt = 0
        while (true) {
            try {
                ctx.contentResolver.openOutputStream(file.uri, "wt")?.use { out ->
                    backend.open(entry.id).use { it.copyTo(out) }
                }
                currentCoroutineContext().ensureActive()
                break
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException || !isTransient(e) || ++attempt > 3) {
                    runCatching { file.delete() }
                    throw e
                }
                awaitNetwork()
            }
        }
        onLeaf(entry.name)
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
