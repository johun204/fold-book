package com.comicviewer

import java.io.File

private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic", "heif")

fun File.isImage(): Boolean = isFile && extension.lowercase() in IMAGE_EXT

/** 한 폴더 안의 이미지들을 자연 정렬 순서로 담고, 형제 폴더 탐색을 제공한다. */
class ComicFolder(val dir: File) {

    val pages: List<File> = (dir.listFiles { f -> f.isImage() } ?: emptyArray())
        .sortedWith { x, y -> NaturalOrder.compare(x.name, y.name) }

    /** 형제 폴더 중 자연 정렬상 offset 만큼 떨어진 폴더. 없으면 null. (offset: +1 다음, -1 이전) */
    fun sibling(offset: Int): File? {
        val parent = dir.parentFile ?: return null
        val sibs = (parent.listFiles { f -> f.isDirectory } ?: return null)
            .sortedWith { x, y -> NaturalOrder.compare(x.name, y.name) }
        val idx = sibs.indexOfFirst { it.absolutePath == dir.absolutePath }
        if (idx < 0) return null
        return sibs.getOrNull(idx + offset)
    }
}
