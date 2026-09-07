package com.foldbook

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.foldbook.ui.FoldBookTheme
import com.foldbook.ui.ReaderScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    private var flip: PageFlipView? = null

    private var session: Session? = null
    private var folderId: String = ""
    private var rolling = false
    private var saveJob: Job? = null
    private var restoredPage = 0

    // Compose 상태
    private var ready by mutableStateOf(false)
    private var pageNum by mutableIntStateOf(1)
    private var totalPages by mutableIntStateOf(0)
    private var title by mutableStateOf("")
    private var rtl by mutableStateOf(false)
    private var curDirection by mutableStateOf(ReadingDirection.RTL)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = App.of(this)
        immersive()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        restoredPage = savedInstanceState?.getInt(SAVED_PAGE, 0) ?: 0

        setContent {
            FoldBookTheme {
                ReaderScreen(
                    ready = ready,
                    page = pageNum,
                    total = totalPages,
                    title = title,
                    rtl = rtl,
                    flipViewProvider = { flip },
                    onBack = { finish() },
                    onJump = { n -> flip?.goTo(n) },
                    spreadMode = Prefs(this).spreadMode,
                    direction = curDirection,
                    onChangeSpread = { m -> Prefs(this).spreadMode = m; recreate() },
                    onChangeDirection = { d ->
                        // 이 책(폴더)만의 방향으로 저장 — 설정 탭의 기본값은 건드리지 않는다.
                        session?.let {
                            val ns = it.copy(directionOverride = d)
                            app.sessions.upsert(ns)
                            session = ns
                        }
                        recreate()
                    },
                )
            }
        }

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
        flip?.let { outState.putInt(SAVED_PAGE, it.currentPageNumber()) }
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

        var s = sessionId?.let { app.sessions.get(it) }
            ?: app.sessions.findOrCreate(conn, fId, fName)
        if (s.finished) s = s.copy(finished = false, pageIndex = 0)   // 완료 세션 다시 열면 처음부터

        val prefs = Prefs(this)
        val dir = s.directionOverride ?: prefs.direction   // 이 책만의 방향이 있으면 우선
        val readingRtl = dir == ReadingDirection.RTL
        val wide = isWideScreen()
        val autoPage = prefs.doublePage(wide)
        val doubleMode = autoPage &&
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        val analyze = prefs.splitWideScans &&
            (conn.type == ConnType.LOCAL || prefs.analyzeRemote)
        val dimsMap = if (analyze) scanSizes(images) else emptyMap()
        val pages = SpreadPolicy.expand(images, dir, doubleMode, prefs.splitWideScans) { id -> dimsMap[id] }

        val startEntryIdx =
            if (startEntryId != null) images.indexOfFirst { it.id == startEntryId }.coerceAtLeast(0) else 0
        val startEntry = images[startEntryIdx].id
        val startPageIdx = pages.indexOfFirst { it.entryId == startEntry }.coerceAtLeast(0)

        var startPage = when {
            restoredPage in 1..pages.size -> restoredPage
            startEntryId != null -> startPageIdx + 1
            s.pageIndex in pages.indices -> s.pageIndex + 1
            else -> 1
        }
        if (doubleMode && startPage % 2 == 0) startPage = (startPage - 1).coerceAtLeast(1)
        intent.removeExtra(EXTRA_START_ENTRY_ID)

        s = s.copy(
            pageCount = pages.size,
            pageIndex = (startPage - 1).coerceIn(0, (pages.size - 1).coerceAtLeast(0)),
            folderName = fName.ifBlank { s.folderName },
            finished = false,
        )
        app.sessions.upsert(s)
        session = s

        val cacheDir = SessionCache.dir(this, s.id)
        stream = PageStream(backend, pages, cacheDir, lifecycleScope, prefs.prefetchForward)
        val provider = PageImageProvider(stream)

        val v = PageFlipView(this, provider, startPage, autoPage, rtl = readingRtl)
        v.onBoundary = { fwd -> onBoundary(fwd) }
        v.onPageSettled = { n -> pageNum = n; saveProgress(n) }
        stream.focus(startPage - 1)
        flip = v

        rtl = readingRtl
        curDirection = dir
        title = fName.ifBlank { s.folderName }
        totalPages = pages.size
        pageNum = startPage
        ready = true

        if (prefs.foldFlip) FoldGestureDetector(this) { v.flipForward() }.start()
    }

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

            // 다음 '권'은 같은 바로 위 부모 폴더 안의, 이미지가 실제로 들어 있는 다음 폴더만 인정한다.
            // (예: 원피스/1권 → 원피스/2권 O,  원피스 → 나루토 X)
            val next = try {
                val sibs = backend.siblingFolders(folderId)
                val idx = sibs.indexOfFirst { it.id == folderId }
                if (idx < 0) null
                else sibs.drop(idx + 1).firstOrNull { sib ->
                    runCatching { backend.list(sib.id).any { !it.isDir } }.getOrDefault(false)
                }
            } catch (e: Exception) {
                null
            }

            if (next == null) {
                // 마지막 권 — 알림만 보이고 폴더 이동/종료는 하지 않는다.
                toast(getString(R.string.last_folder))
                rolling = false
                return@launch
            }

            // 현재 세션 = 완료 처리 (홈의 '완료' 그룹으로), 받아둔 미리불러오기 캐시는 정리
            app.sessions.upsert(cur.copy(finished = true, pageIndex = (cur.pageCount - 1).coerceAtLeast(0)))
            if (::stream.isInitialized) stream.close()
            SessionCache.clear(this@ReaderActivity, cur.id)

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
        flip?.onResume()
    }

    override fun onPause() {
        super.onPause()
        flip?.onPause()
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
