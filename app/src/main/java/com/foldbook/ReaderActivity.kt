package com.foldbook

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** (connId|entryId) -> 이미지 크기. 프로세스 생존 동안 유지해 같은 폴더 재열람 시 재분석 생략. */
object DimsCache {
    private val m = ConcurrentHashMap<String, Pair<Int, Int>>()
    fun get(k: String): Pair<Int, Int>? = m[k]
    fun put(k: String, v: Pair<Int, Int>?) { if (v != null) m[k] = v }
}

class ReaderActivity : ComponentActivity() {

    private lateinit var app: App
    private lateinit var backend: StorageBackend
    private lateinit var conn: Connection
    private lateinit var stream: PageStream
    private lateinit var view: PageFlipView

    private var session: Session? = null
    private var folderId: String = ""
    private var rolling = false
    private var saveJob: Job? = null
    private var restoredPage = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = App.of(this)
        immersive()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(ProgressBar(this@ReaderActivity), FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        })

        // 폴드/회전 등으로 재생성되면 저장해둔 현재 페이지에서 이어감
        restoredPage = savedInstanceState?.getInt(SAVED_PAGE, 0) ?: 0

        val (connId, fId, fName, startEntry, sessionId) = parseIntent() ?: run {
            toast(getString(R.string.err_path)); finish(); return
        }
        val c = if (connId == "view") localViewConnection() else app.connections.get(connId)
        if (c == null) { toast(getString(R.string.err_path)); finish(); return }
        conn = c
        folderId = fId

        lifecycleScope.launch { setup(fId, fName, startEntry, sessionId) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::view.isInitialized) outState.putInt(SAVED_PAGE, view.currentPageNumber())
    }

    private data class Args(
        val connId: String, val folderId: String, val folderName: String,
        val startEntryId: String?, val sessionId: String?,
    )

    private fun parseIntent(): Args? {
        intent.getStringExtra(EXTRA_CONNECTION_ID)?.let { cid ->
            return Args(
                cid,
                intent.getStringExtra(EXTRA_FOLDER_ID) ?: return null,
                intent.getStringExtra(EXTRA_FOLDER_NAME) ?: "",
                intent.getStringExtra(EXTRA_START_ENTRY_ID),
                intent.getStringExtra(EXTRA_SESSION_ID),
            )
        }
        // 다른 앱에서 이미지 열기 (ACTION_VIEW)
        val uri = intent.data ?: return null
        val path = resolveToFilePath(uri) ?: return null
        val f = File(path)
        if (!f.exists()) return null
        val dir = f.parentFile ?: return null
        return Args("view", dir.absolutePath, dir.name, f.absolutePath, null)
    }

    private fun localViewConnection() =
        Connection(id = "local", type = ConnType.LOCAL, label = "기기 저장소")

    private suspend fun setup(fId: String, fName: String, startEntryId: String?, sessionId: String?) {
        backend = backendFor(conn, applicationContext)

        val images = try {
            backend.list(fId).filter { !it.isDir }
        } catch (e: Exception) {
            toast(getString(R.string.remote_failed, e.message ?: e.javaClass.simpleName)); finish(); return
        }
        if (images.isEmpty()) { toast(getString(R.string.err_empty)); finish(); return }

        val prefs = Prefs(this)
        val wide = isWideScreen()
        val autoPage = prefs.doublePage(wide)
        // eschao 는 가로일 때만 실제로 양면 렌더 -> 여백 삽입/좌우순서도 그 조건에 맞춘다
        val doubleMode = autoPage &&
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        val analyze = prefs.splitWideScans &&
            (conn.type == ConnType.LOCAL || prefs.analyzeRemote)
        val dimsMap = if (analyze) scanSizes(images) else emptyMap()
        val pages = SpreadPolicy.expand(images, prefs.direction, doubleMode, prefs.splitWideScans) { id -> dimsMap[id] }

        val startEntryIdx =
            if (startEntryId != null) images.indexOfFirst { it.id == startEntryId }.coerceAtLeast(0) else 0
        val startEntry = images[startEntryIdx].id
        val startPageIdx = pages.indexOfFirst { it.entryId == startEntry }.coerceAtLeast(0)

        var s = sessionId?.let { app.sessions.get(it) }
            ?: app.sessions.findOrCreate(conn, fId, fName)
        var startPage = when {
            restoredPage in 1..pages.size -> restoredPage
            startEntryId != null -> startPageIdx + 1
            s.pageIndex in pages.indices -> s.pageIndex + 1
            else -> 1
        }
        // 양면 모드에서는 페어의 왼쪽(홀수, 1-based)로 스냅해야 좌/우 짝이 안 어긋남
        if (doubleMode && startPage % 2 == 0) startPage = (startPage - 1).coerceAtLeast(1)
        // 최초 setup 이후 재생성 시엔 세션 위치로 이어가도록 시작 이미지 지정 제거
        intent.removeExtra(EXTRA_START_ENTRY_ID)

        s = s.copy(
            pageCount = pages.size,
            pageIndex = (startPage - 1).coerceIn(0, (pages.size - 1).coerceAtLeast(0)),
            folderName = fName.ifBlank { s.folderName },
        )
        app.sessions.upsert(s)
        session = s

        val cacheDir = SessionCache.dir(this, s.id)
        stream = PageStream(backend, pages, cacheDir, lifecycleScope, prefs.prefetchForward)
        val provider = PageImageProvider(stream)

        view = PageFlipView(this, provider, startPage, autoPage)
        view.onBoundary = { fwd -> onBoundary(fwd) }
        view.onPageSettled = { n -> saveProgress(n) }
        stream.focus(startPage - 1)
        setContentView(view)

        if (prefs.foldFlip) FoldGestureDetector(this) { view.flipForward() }.start()
    }

    /** 이미지 크기 병렬 분석 (원격은 헤더만). 캐시 히트는 건너뛴다. */
    private suspend fun scanSizes(images: List<Entry>): Map<String, Pair<Int, Int>?> {
        val result = HashMap<String, Pair<Int, Int>?>(images.size)
        val todo = ArrayList<Entry>()
        for (e in images) {
            val cached = DimsCache.get("${conn.id}|${e.id}")
            if (cached != null) result[e.id] = cached else todo.add(e)
        }
        if (todo.isEmpty()) return result
        val sem = Semaphore(if (conn.type == ConnType.LOCAL) 4 else 10)
        coroutineScope {
            todo.map { e ->
                async(Dispatchers.IO) {
                    val d = runCatching { sem.withPermit { backend.imageSize(e.id) } }.getOrNull()
                    DimsCache.put("${conn.id}|${e.id}", d)
                    e.id to d
                }
            }.awaitAll().forEach { (id, d) -> result[id] = d }
        }
        return result
    }

    private fun saveProgress(pageNumber: Int) {
        val s = session?.copy(pageIndex = (pageNumber - 1).coerceAtLeast(0)) ?: return
        session = s
        saveJob?.cancel()
        saveJob = lifecycleScope.launch {
            delay(400)
            app.sessions.upsert(s)
        }
    }

    private fun onBoundary(forward: Boolean) {
        if (!forward) {
            toast(getString(R.string.first_page)); return
        }
        if (rolling) return
        rolling = true
        lifecycleScope.launch {
            val cur = session
            if (cur == null) { finish(); return@launch }

            val next = try {
                val sibs = backend.siblingFolders(folderId)
                val idx = sibs.indexOfFirst { it.id == folderId }
                if (idx >= 0) sibs.getOrNull(idx + 1) else null
            } catch (e: Exception) {
                null
            }

            // 현재 세션 종료 + 캐시 정리
            app.sessions.remove(cur.id)
            if (::stream.isInitialized) stream.close()
            SessionCache.clear(this@ReaderActivity, cur.id)

            if (next == null) {
                toast(getString(R.string.last_folder)); finish(); return@launch
            }
            val ns = app.sessions.findOrCreate(conn, next.id, next.name)
            startActivity(
                Intent(this@ReaderActivity, ReaderActivity::class.java)
                    .putExtra(EXTRA_CONNECTION_ID, conn.id)
                    .putExtra(EXTRA_FOLDER_ID, next.id)
                    .putExtra(EXTRA_FOLDER_NAME, next.name)
                    .putExtra(EXTRA_SESSION_ID, ns.id)
            )
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::view.isInitialized) view.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (::view.isInitialized) view.onPause()
        session?.let { app.sessions.upsert(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!rolling && ::stream.isInitialized) stream.close()
    }

    private fun isWideScreen(): Boolean {
        val c = resources.configuration
        return c.orientation == Configuration.ORIENTATION_LANDSCAPE || c.smallestScreenWidthDp >= 600
    }

    private fun immersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_CONNECTION_ID = "conn"
        const val EXTRA_FOLDER_ID = "folder"
        const val EXTRA_FOLDER_NAME = "folderName"
        const val EXTRA_START_ENTRY_ID = "startEntry"
        const val EXTRA_SESSION_ID = "session"
        private const val SAVED_PAGE = "savedPage"

        fun start(
            ctx: android.content.Context, connId: String, folderId: String, folderName: String,
            startEntryId: String? = null, sessionId: String? = null,
        ) {
            val i = Intent(ctx, ReaderActivity::class.java)
                .putExtra(EXTRA_CONNECTION_ID, connId)
                .putExtra(EXTRA_FOLDER_ID, folderId)
                .putExtra(EXTRA_FOLDER_NAME, folderName)
            if (startEntryId != null) i.putExtra(EXTRA_START_ENTRY_ID, startEntryId)
            if (sessionId != null) i.putExtra(EXTRA_SESSION_ID, sessionId)
            ctx.startActivity(i)
        }
    }
}
