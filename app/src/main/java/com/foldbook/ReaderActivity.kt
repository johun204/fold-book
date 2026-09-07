package com.foldbook

import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
    private val dimsCache = HashMap<String, Pair<Int, Int>?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = App.of(this)
        immersive()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val (connId, fId, fName, startEntry, sessionId) = parseIntent() ?: run {
            toast(getString(R.string.err_path)); finish(); return
        }
        val c = if (connId == "view") localViewConnection() else app.connections.get(connId)
        if (c == null) { toast(getString(R.string.err_path)); finish(); return }
        conn = c
        folderId = fId

        lifecycleScope.launch { setup(fId, fName, startEntry, sessionId) }
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
        val dimsMap = HashMap<String, Pair<Int, Int>?>()
        if (conn.type == ConnType.LOCAL) for (e in images) dimsMap[e.id] = localDims(e.id)
        val pages = SpreadPolicy.expand(images, prefs.splitSpreads(wide), prefs.direction) { id -> dimsMap[id] }

        val startEntryIdx =
            if (startEntryId != null) images.indexOfFirst { it.id == startEntryId }.coerceAtLeast(0) else 0
        val startEntry = images[startEntryIdx].id
        val startPageIdx = pages.indexOfFirst { it.entryId == startEntry }.coerceAtLeast(0)

        var s = sessionId?.let { app.sessions.get(it) }
            ?: app.sessions.findOrCreate(conn, fId, fName)
        val startPage = when {
            startEntryId != null -> startPageIdx + 1
            s.pageIndex in pages.indices -> s.pageIndex + 1
            else -> 1
        }
        s = s.copy(pageCount = pages.size, folderName = fName.ifBlank { s.folderName })
        app.sessions.upsert(s)
        session = s

        val cacheDir = SessionCache.dir(this, s.id)
        stream = PageStream(backend, pages, cacheDir, lifecycleScope)
        val provider = PageImageProvider(stream)

        view = PageFlipView(this, provider, startPage, prefs.doublePage(wide))
        view.onBoundary = { fwd -> onBoundary(fwd) }
        view.onPageSettled = { n -> saveProgress(n) }
        stream.focus(startPage - 1)
        setContentView(view)

        if (prefs.foldFlip) FoldGestureDetector(this) { view.flipForward() }.start()
    }

    private suspend fun localDims(id: String): Pair<Int, Int>? {
        dimsCache[id]?.let { return it }
        if (dimsCache.containsKey(id)) return null
        val d = withContext(Dispatchers.IO) {
            runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(id, o)
                if (o.outWidth > 0) o.outWidth to o.outHeight else null
            }.getOrNull()
        }
        dimsCache[id] = d
        return d
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
