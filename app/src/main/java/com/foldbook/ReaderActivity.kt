package com.foldbook

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.foldbook.reader.PageSource
import com.foldbook.reader.SessionManifestStore
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
import kotlinx.coroutines.withContext
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
    private var source: PageSource? = null
    private var images: List<Entry> = emptyList()   // 폴더의 원본 이미지 목록 (재구성용)
    private var doubleMode = false                  // 지금 리더가 만들어진 기준
    private var flip: PageFlipView? = null

    private var session: Session? = null
    private var folderId: String = ""
    private var rolling = false
    private var saveJob: Job? = null
    private var restoredPage = 0

    private sealed interface BoundaryPrompt {
        data class PrevVolume(val folder: Entry) : BoundaryPrompt
        data class NextVolume(val folder: Entry) : BoundaryPrompt
        data object NoPrev : BoundaryPrompt
    }

    // Compose 상태
    private var ready by mutableStateOf(false)
    private var pageNum by mutableIntStateOf(1)
    private var totalPages by mutableIntStateOf(0)
    private var title by mutableStateOf("")
    private var rtl by mutableStateOf(false)
    private var curDirection by mutableStateOf(ReadingDirection.RTL)
    private var enhanceLevel by mutableIntStateOf(0)
    private var viewEpoch by mutableIntStateOf(0)
    private var boundaryPrompt by mutableStateOf<BoundaryPrompt?>(null)

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
                    viewEpoch = viewEpoch,
                    enhanceLevel = enhanceLevel,
                    onChangeEnhance = { lv ->
                        // 읽기 방향과 같이 이 책(폴더)에만 저장하고, 현재 페이지에서 다시 불러온다.
                        session?.let {
                            val ns = it.copy(enhanceLevel = lv)
                            app.sessions.upsert(ns)
                            session = ns
                        }
                        recreate()
                    },
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
                when (val bp = boundaryPrompt) {
                    is BoundaryPrompt.NextVolume -> AlertDialog(
                        onDismissRequest = { boundaryPrompt = null },
                        confirmButton = {
                            TextButton(onClick = {
                                boundaryPrompt = null
                                goNextVolume(bp.folder)
                            }) { Text("다음 권 보기") }
                        },
                        dismissButton = {
                            TextButton(onClick = { boundaryPrompt = null }) { Text("취소") }
                        },
                        title = { Text("마지막 페이지입니다") },
                        text = { Text("‘${bp.folder.name}’ 으로 이동할까요?") },
                    )
                    is BoundaryPrompt.PrevVolume -> AlertDialog(
                        onDismissRequest = { boundaryPrompt = null },
                        confirmButton = {
                            TextButton(onClick = {
                                boundaryPrompt = null
                                openFolder(bp.folder, atEnd = true)
                            }) { Text("이전 권 보기") }
                        },
                        dismissButton = {
                            TextButton(onClick = { boundaryPrompt = null }) { Text("취소") }
                        },
                        title = { Text("첫 페이지입니다") },
                        text = { Text("이전 권으로 이동할까요?") },
                    )
                    BoundaryPrompt.NoPrev -> AlertDialog(
                        onDismissRequest = { boundaryPrompt = null },
                        confirmButton = {
                            TextButton(onClick = { boundaryPrompt = null }) { Text("확인") }
                        },
                        title = { Text("첫 번째 권") },
                        text = { Text("이 만화의 첫 번째 권이라 이전으로 이동할 수 없습니다.") },
                    )
                    null -> {}
                }
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

    /** 폴드/펼침·회전으로 화면이 바뀌어도 Activity 를 재생성하지 않고(manifest configChanges) 그 자리에서 대응. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        immersive()
        val wide = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE ||
            newConfig.smallestScreenWidthDp >= 600
        val prefs = Prefs(this)
        val want = wantDouble(prefs, wide, newConfig)
        // 한쪽 ↔ 양쪽 보기가 바뀌면 페이지 구성(스프레드 짝맞춤용 빈 페이지)이 달라지므로
        // 표면만 바꾸는 게 아니라 페이지 목록부터 다시 만든다.
        if (ready && want != doubleMode) rebuildForSpread(want)
        else flip?.onConfigChanged(want)
        flip?.tapZone = if (wide) prefs.tapZoneWide else prefs.tapZoneNarrow
    }

    private fun wantDouble(prefs: Prefs, wide: Boolean, cfg: Configuration) =
        prefs.doublePage(wide) && cfg.orientation == Configuration.ORIENTATION_LANDSCAPE

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

        var s = sessionId?.let { app.sessions.get(it) }
            ?: app.sessions.findOrCreate(conn, fId, fName)
        if (s.finished) s = s.copy(finished = false, pageIndex = 0)   // 완료 세션 다시 열면 처음부터
        session = s

        val prefs = Prefs(this)
        val dir = s.directionOverride ?: prefs.direction
        val wide = isWideScreen()
        val doubleMode = prefs.doublePage(wide) &&
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val cacheDir = SessionCache.dir(this, s.id)

        // --- 빠른 경로: 저장된 매니페스트로 네트워크 없이 즉시 열기 ---
        val manifest = if (startEntryId == null) SessionManifestStore.load(cacheDir) else null
        if (manifest != null && manifest.connectionId == conn.id && manifest.folderId == fId &&
            manifest.entries.isNotEmpty()
        ) {
            val imgs = manifest.asEntries()
            imgs.forEach { DimsCache.put("${conn.id}|${it.id}", manifest.dimOf(it.id)) }
            images = imgs
            val pages = SpreadPolicy.expand(imgs, dir, doubleMode, prefs.splitWideScans) { manifest.dimOf(it) }
            buildReader(pages, dir, doubleMode, s, fName, startEntryId = null, startAtEnd = false)
            refreshInBackground(fId, dir, doubleMode, prefs, cacheDir, manifest.entryIds())
            return
        }

        // --- 느린 경로: 폴더 조회 + 스캔본 분석 + 매니페스트 기록 ---
        val imgs = try {
            backend.list(fId).filter { !it.isDir }
        } catch (e: Exception) {
            toast(getString(R.string.remote_failed, e.message ?: e.javaClass.simpleName)); finish(); return
        }
        if (imgs.isEmpty()) { toast(getString(R.string.err_empty)); finish(); return }
        images = imgs

        val analyze = prefs.splitWideScans && (conn.type == ConnType.LOCAL || prefs.analyzeRemote)
        val dimsMap = if (analyze) scanSizes(imgs) else emptyMap()
        SessionManifestStore.save(cacheDir, conn.id, fId, dir, imgs, dimsMap)

        val pages = SpreadPolicy.expand(imgs, dir, doubleMode, prefs.splitWideScans) { id -> dimsMap[id] }
        buildReader(pages, dir, doubleMode, s, fName, startEntryId, intent.getBooleanExtra(EXTRA_START_AT_END, false))
    }

    /** 매니페스트로 연 뒤, 실제 목록과 대조해 달라졌으면 매니페스트만 갱신(다음 열람에 반영). */
    private fun refreshInBackground(
        fId: String, dir: ReadingDirection, doubleMode: Boolean, prefs: Prefs,
        cacheDir: File, knownIds: List<String>,
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val fresh = runCatching { backend.list(fId).filter { !it.isDir } }.getOrNull() ?: return@launch
            if (fresh.isEmpty() || fresh.map { it.id } == knownIds) return@launch
            val analyze = prefs.splitWideScans && (conn.type == ConnType.LOCAL || prefs.analyzeRemote)
            val dimsMap = if (analyze) scanSizes(fresh) else emptyMap()
            SessionManifestStore.save(cacheDir, conn.id, fId, dir, fresh, dimsMap)
            withContext(Dispatchers.Main) {
                toast("폴더 내용이 바뀌었어요 — 다시 열면 반영됩니다")
            }
        }
    }

    private fun buildReader(
        pages: List<PageRef>, dir: ReadingDirection, doubleMode: Boolean,
        sessionIn: Session, fName: String, startEntryId: String?, startAtEnd: Boolean,
    ) {
        if (pages.isEmpty()) { toast(getString(R.string.err_empty)); finish(); return }
        var s = sessionIn
        val readingRtl = dir == ReadingDirection.RTL

        val startPageIdx = startEntryId
            ?.let { e -> pages.indexOfFirst { it.entryId == e } }
            ?.takeIf { it >= 0 } ?: 0

        var startPage = when {
            restoredPage in 1..pages.size -> restoredPage
            startEntryId != null -> startPageIdx + 1
            s.pageIndex in pages.indices && s.pageIndex > 0 -> s.pageIndex + 1
            startAtEnd -> pages.size
            s.pageIndex in pages.indices -> s.pageIndex + 1
            else -> 1
        }
        if (doubleMode && startPage % 2 == 0) startPage = (startPage - 1).coerceAtLeast(1)
        intent.removeExtra(EXTRA_START_ENTRY_ID)
        intent.removeExtra(EXTRA_START_AT_END)

        s = s.copy(
            pageCount = pages.size,
            pageIndex = (startPage - 1).coerceIn(0, (pages.size - 1).coerceAtLeast(0)),
            folderName = fName.ifBlank { s.folderName },
            finished = false,
        )
        app.sessions.upsert(s)
        session = s

        val prefs = Prefs(this)
        val cacheDir = SessionCache.dir(this, s.id)
        val tapZone = if (isWideScreen()) prefs.tapZoneWide else prefs.tapZoneNarrow
        val level = s.enhanceLevel ?: prefs.enhanceLevel
        val src = PageSource(
            backend, pages, cacheDir, lifecycleScope,
            prefs.prefetchForward, level, prefs.fitMode,
        )
        val provider = PageImageProvider(src, rtl = readingRtl)
        val view = PageFlipView(this, provider, startPage, doubleMode, rtl = readingRtl, tapZone = tapZone)
        view.onBoundary = { fwd -> onBoundary(fwd) }
        view.onPageSettled = { n -> pageNum = n; saveProgress(n) }

        source = src
        flip = view
        this.doubleMode = doubleMode
        viewEpoch++            // Compose 가 예전 GL 뷰를 버리고 새로 붙이도록
        rtl = readingRtl
        curDirection = dir
        enhanceLevel = level
        title = fName.ifBlank { s.folderName }
        totalPages = pages.size
        pageNum = startPage
        ready = true

    }

    /**
     * 폴드/회전으로 한쪽 ↔ 양쪽 보기가 바뀌면 페이지 구성이 달라진다(양면 모드는 스프레드가
     * 한 페어에 들어가도록 빈 페이지를 끼운다). 보던 이미지를 그대로 유지한 채 다시 만든다.
     */
    private fun rebuildForSpread(newDouble: Boolean) {
        val s = session ?: return
        val imgs = images
        val src = source ?: return
        if (imgs.isEmpty()) return

        val prefs = Prefs(this)
        val dir = s.directionOverride ?: prefs.direction
        val keep = src.pages.getOrNull(pageNum - 1)?.entryId?.takeIf { it.isNotEmpty() }
        val pages = SpreadPolicy.expand(imgs, dir, newDouble, prefs.splitWideScans) { id ->
            DimsCache.get("${conn.id}|$id")
        }

        ready = false
        flip = null
        source = null
        restoredPage = 0
        buildReader(pages, dir, newDouble, s, s.folderName, startEntryId = keep, startAtEnd = false)
        // 예전 GL 뷰가 떨어져 나간 뒤에 닫는다 (먼저 닫으면 GL 스레드가 재활용된 비트맵을 쓴다)
        window.decorView.post { src.close() }
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
        if (rolling) return
        rolling = true
        lifecycleScope.launch {
            try {
                if (session == null) { finish(); return@launch }
                val target = runCatching { adjacentFolder(forward) }.getOrNull()

                if (!forward) {
                    boundaryPrompt = if (target != null) BoundaryPrompt.PrevVolume(target)
                    else BoundaryPrompt.NoPrev
                    return@launch
                }

                if (target == null) {
                    toast(getString(R.string.last_folder))
                    return@launch
                }
                boundaryPrompt = BoundaryPrompt.NextVolume(target)
            } finally {
                rolling = false
            }
        }
    }

    /** 확인 후 다음 권으로: 현재 세션을 '완료' 로 옮기고 캐시를 정리한 뒤 새 폴더를 연다. */
    private fun goNextVolume(folder: Entry) {
        val cur = session ?: return
        // session 필드까지 '완료' 로 바꿔야 한다. 안 그러면 뒤이어 도는 지연 저장·onPause 가
        // 아직 finished=false 인 옛 값을 다시 써서 이전 권이 홈의 '이어보기' 에 되살아난다.
        saveJob?.cancel()
        val done = cur.copy(finished = true, pageIndex = (cur.pageCount - 1).coerceAtLeast(0))
        session = done
        app.sessions.upsert(done)
        source?.close()
        SessionCache.clear(this, cur.id)
        rolling = true          // onDestroy 에서 source 를 다시 닫지 않도록
        openFolder(folder, atEnd = false)
    }

    /**
     * 같은 바로 위 부모 폴더 안에서 현재 폴더의 앞/뒤로, 이미지가 실제로 들어 있는 첫 폴더.
     * (예: 원피스/1권 ↔ 원피스/2권 O,  원피스 → 나루토 X — siblingFolders 가 부모 한정)
     */
    private suspend fun adjacentFolder(forward: Boolean): Entry? {
        val sibs = backend.siblingFolders(folderId)
        val idx = sibs.indexOfFirst { it.id == folderId }
        if (idx < 0) return null
        val order = if (forward) (idx + 1)..sibs.lastIndex else (idx - 1) downTo 0
        for (i in order) {
            val f = sibs[i]
            if (runCatching { backend.list(f.id).any { !it.isDir } }.getOrDefault(false)) return f
        }
        return null
    }

    private fun openFolder(folder: Entry, atEnd: Boolean) {
        val ns = app.sessions.findOrCreate(conn, folder.id, folder.name)
        val i = Intent(this, ReaderActivity::class.java)
            .putExtra(EXTRA_CONNECTION_ID, conn.id)
            .putExtra(EXTRA_FOLDER_ID, folder.id)
            .putExtra(EXTRA_FOLDER_NAME, folder.name)
            .putExtra(EXTRA_SESSION_ID, ns.id)
        if (atEnd) i.putExtra(EXTRA_START_AT_END, true)
        startActivity(i)
        finish()
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
        if (!rolling) source?.close()
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
        const val EXTRA_START_AT_END = "startAtEnd"
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
