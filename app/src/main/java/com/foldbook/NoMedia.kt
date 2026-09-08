package com.foldbook

import android.content.Context
import android.media.MediaScannerConnection
import java.io.File
import java.util.concurrent.Executors

/**
 * 로컬 폴더에 `.nomedia` 파일을 두거나 지워서 갤러리·미디어 앱이 그 폴더의 만화 이미지를 인식하지 않게 한다.
 * (SMB·드라이브에는 의미가 없어 로컬 전용)
 */
object NoMedia {

    private val io = Executors.newSingleThreadExecutor()

    fun exists(folderPath: String): Boolean =
        folderPath.isNotBlank() && runCatching { File(folderPath, ".nomedia").exists() }.getOrDefault(false)

    /** `.nomedia` 를 토글하고, 이후 상태(true = 숨김 설정됨)를 돌려준다. 무거운 재스캔은 백그라운드로. */
    fun toggle(ctx: Context, folderPath: String): Boolean {
        val f = File(folderPath, ".nomedia")
        runCatching { if (f.exists()) f.delete() else f.createNewFile() }
        val hidden = f.exists()
        val appCtx = ctx.applicationContext
        io.execute { rescan(appCtx, folderPath) }
        return hidden
    }

    /** 폴더 안 파일들을 다시 스캔시켜 갤러리 색인이 `.nomedia` 를 반영하도록 유도(최대 5000개). */
    private fun rescan(ctx: Context, folderPath: String) {
        runCatching {
            val paths = ArrayList<String>()
            paths.add(folderPath)
            File(folderPath).walkTopDown().maxDepth(6).forEach {
                if (it.isFile && paths.size < 5000) paths.add(it.absolutePath)
            }
            MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null, null)
        }
    }
}
