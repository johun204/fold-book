package com.comicviewer

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

class ReaderActivity : AppCompatActivity() {

    private lateinit var folder: ComicFolder
    private lateinit var view: PageFlipView
    private var source: Source? = null
    private var boundaryDialogShowing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        source = Source.fromJson(intent.getStringExtra(EXTRA_SOURCE))

        // 1) 홈에서 넘어온 로컬 경로, 또는 다른 앱의 "이미지 열기(ACTION_VIEW)"
        val path = intent.getStringExtra(EXTRA_PATH) ?: intent.data?.let { resolveToFilePath(it) }
        if (path != null) {
            open(File(path), savedInstanceState)
            return
        }

        // 2) 경로 없이 원격 출처만 왔다 -> 이 폴더를 캐시로 받아온 뒤 연다
        val src = source
        if (src != null) {
            syncThenOpen(src)
            return
        }

        Toast.makeText(this, R.string.err_path, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun syncThenOpen(src: Source) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.downloading)
            .setMessage(getString(R.string.listing))
            .setCancelable(false)
            .create()
        dialog.show()
        lifecycleScope.launch {
            try {
                val files = RemoteSync.sync(applicationContext, src) { done, total ->
                    runOnUiThread { dialog.setMessage("$done / $total") }
                }
                dialog.dismiss()
                val first = files.firstOrNull()
                if (first == null) {
                    toast(getString(R.string.err_empty)); finish()
                } else {
                    open(first, null)
                }
            } catch (e: Exception) {
                dialog.dismiss()
                AlertDialog.Builder(this@ReaderActivity)
                    .setMessage(getString(R.string.remote_failed, e.message ?: e.javaClass.simpleName))
                    .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
                    .setOnCancelListener { finish() }
                    .show()
            }
        }
    }

    private fun open(file: File, savedInstanceState: Bundle?) {
        if (!file.exists()) {
            Toast.makeText(this, R.string.err_path, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val dir = file.parentFile ?: run { finish(); return }
        folder = ComicFolder(dir)
        if (folder.pages.isEmpty()) {
            toast(getString(R.string.err_empty)); finish(); return
        }

        val prefs = Prefs(this)
        val wide = isWideScreen()
        val readerPages = SpreadPolicy.expand(
            files = folder.pages,
            splitSpreads = prefs.splitSpreads(wide),
            dir = prefs.direction,
            isSpread = ::imageIsSpread,
        )
        val provider = PageImageProvider(readerPages)

        val restored = savedInstanceState?.getInt(EXTRA_PAGE, 0) ?: 0
        val start = if (restored > 0) restored
        else readerPages.indexOfFirst { it.file.absolutePath == file.absolutePath }.coerceAtLeast(0) + 1

        view = PageFlipView(this, provider, start, doublePage = prefs.doublePage(wide))
        view.onBoundary = { forward -> onBoundary(forward) }
        setContentView(view)

        if (prefs.foldFlip) {
            FoldGestureDetector(this) { view.flipForward() }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::view.isInitialized) view.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (::view.isInitialized) view.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::view.isInitialized) outState.putInt(EXTRA_PAGE, view.currentPageNumber())
    }

    private fun onBoundary(forward: Boolean) {
        if (boundaryDialogShowing) return
        if (!forward) {
            toast(getString(R.string.first_page)); return
        }
        val src = source
        if (src == null) localNextFolder() else remoteNextFolder(src)
    }

    private fun localNextFolder() {
        val next = folder.sibling(+1)
        showNextFolderDialog(next?.name) {
            val firstPage = next?.let { ComicFolder(it).pages.firstOrNull() }
            if (firstPage == null) toast(getString(R.string.err_empty))
            else {
                startActivity(Intent(this, ReaderActivity::class.java).putExtra(EXTRA_PATH, firstPage.absolutePath))
                finish()
            }
        }
    }

    private fun remoteNextFolder(src: Source) {
        boundaryDialogShowing = true
        lifecycleScope.launch {
            val next = try {
                RemoteSync.nextFolder(applicationContext, src)
            } catch (e: Exception) {
                toast(e.message ?: "error"); boundaryDialogShowing = false; return@launch
            }
            if (next == null) {
                toast(getString(R.string.last_folder)); boundaryDialogShowing = false; return@launch
            }
            val name = when (next) {
                is Source.Drive -> next.folderName
                is Source.Smb -> next.path.trimEnd('/').substringAfterLast('/')
            }
            showNextFolderDialog(name) {
                startActivity(Intent(this@ReaderActivity, ReaderActivity::class.java)
                    .putExtra(EXTRA_SOURCE, next.toJson()))
                finish()
            }
        }
    }

    private fun showNextFolderDialog(nextName: String?, onMove: () -> Unit) {
        boundaryDialogShowing = true
        val b = AlertDialog.Builder(this)
            .setOnDismissListener { boundaryDialogShowing = false }
            .setNegativeButton(R.string.close, null)
        if (nextName != null) {
            b.setMessage(getString(R.string.ask_next_folder, nextName))
            b.setPositiveButton(R.string.move) { _, _ -> onMove() }
        } else {
            b.setMessage(getString(R.string.last_folder))
        }
        b.show()
    }

    private fun isWideScreen(): Boolean {
        val c = resources.configuration
        return c.orientation == Configuration.ORIENTATION_LANDSCAPE || c.smallestScreenWidthDp >= 600
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_SOURCE = "source"
        private const val EXTRA_PAGE = "page"
    }
}
