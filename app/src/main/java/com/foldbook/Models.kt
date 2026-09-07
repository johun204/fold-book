package com.foldbook

import kotlinx.serialization.Serializable

/** 저장소 종류. */
enum class ConnType { LOCAL, SMB, DRIVE }

/** 사용자가 등록한 저장소 연결. 같은 타입을 여러 개 만들 수 있다(원격폴더 여러 경로, 드라이브 여러 계정). */
@Serializable
data class Connection(
    val id: String,
    val type: ConnType,
    val label: String,
    // SMB
    val host: String = "",
    val share: String = "",
    val basePath: String = "",          // 공유 안에서 탐색 시작 경로 ("" = 공유 루트)
    val user: String = "",
    val pass: String = "",
    val domain: String = "",
    // Drive
    val accountEmail: String = "",
    val rootFolderId: String = "root",
) {
    val subtitle: String
        get() = when (type) {
            ConnType.LOCAL -> "기기 저장소"
            ConnType.SMB -> "smb://$host/$share" + (if (basePath.isNotBlank()) "/$basePath" else "")
            ConnType.DRIVE -> accountEmail
        }
}

/** 최근에 본 폴더 = 세션. 마지막 파일까지 다 보면 finished, 다음 폴더 세션이 새로 생성된다. */
@Serializable
data class Session(
    val id: String,
    val connectionId: String,
    val connectionLabel: String,
    val connType: ConnType,
    val folderId: String,               // 백엔드별 폴더 식별자 (로컬=절대경로, SMB=공유 내 경로, Drive=folderId)
    val folderName: String,
    val pageIndex: Int = 0,             // 마지막으로 본 리더 페이지(0-based)
    val pageCount: Int = 0,             // 전체 리더 페이지 수(0 = 미확정)
    val updatedAt: Long = 0L,
    val finished: Boolean = false,      // 폴더 끝까지 다 봄 → 홈의 '완료' 그룹
    val directionOverride: ReadingDirection? = null,  // 이 책(폴더)만의 읽기 방향. null = 설정의 기본값
)

/** 폴더 탐색 항목. */
data class Entry(
    val id: String,
    val name: String,
    val isDir: Boolean,
    val size: Long = 0L,
)

fun String.isImageName(): Boolean =
    substringAfterLast('.', "").lowercase() in
        setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic", "heif")

/** 한 리더 페이지가 가리키는 원본 이미지와, 좌우 스캔본일 때 어느 절반인지. */
data class PageRef(val entryId: String, val name: String, val half: Half)
