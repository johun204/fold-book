# 폴드책 (fold-book)

갤럭시 폴더블에서 쓰는 책 뷰어. 이미지 폴더를 종이책처럼 넘겨 본다.
로컬 저장소 · 윈도우 공유폴더(SMB) · 구글 드라이브를 지원. 패키지: `com.foldbook`

## 빌드
```
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot
gradlew :app:assembleDebug          # 또는 installDebug (연결된 기기)
gradlew :app:testDebugUnitTest
```
AGP 8.7.3 / Kotlin 2.1.0 / Gradle 8.14.3 / compileSdk 35 / minSdk 26.
(저메모리 환경에서는 `gradle.properties` 의 힙을 낮추고 `org.gradle.daemon=false` 로 빌드)

## UI 구조 (Jetpack Compose + Material 3)
하단 네비게이션 **홈 / 탐색 / 설정**.

- **홈** — 최근에 본 폴더 = "세션" 목록. 항목을 누르면 마지막 본 페이지부터 이어보기.
  세션은 그 폴더의 마지막 파일까지 다 보면 종료되고, 다음 폴더 세션이 자동으로 시작된다.
  길게 누르면 세션 삭제(캐시도 정리).
- **탐색** — 저장소 연결 목록. `기기 저장소` 는 기본 제공. `+` 로 SMB / 구글 드라이브 연결 추가.
  같은 종류 여러 개 가능(공유폴더 여러 경로, 드라이브 여러 계정).
  연결을 길게 누르거나 연결 진입 후 우상단 `⋮` → 삭제.
  폴더 탐색 화면은 **하위폴더 + 이미지**를 함께 표시:
  - 폴더 선택 → 그 폴더의 **첫 이미지**부터
  - 이미지 선택 → **그 이미지**부터 (예: 10장 중 5번째 누르면 5/10 부터)
- **설정** — 읽기 방향(좌↔우), 페이지 표시(자동/한 장/양면), 폴더블 접힘 토글.

## 리더
- 드래그 위치를 따라오는 3D 종이책 페이지 넘김 (`eschao/android-PageFlip`, `pageflip/` 로 벤더링).
- **창(window) 방식 로딩**: 폴더의 파일 목록만 먼저 읽고, 현재 페이지 앞뒤 몇 장만 백그라운드로
  미리 받아 디코드. 빠르게 넘겨서 아직 안 받은 페이지는 **회색**으로 보이다가 도착하면 교체.
- 받은 파일은 세션 캐시(`cacheDir/sessions/<id>/`)에 저장, **세션 종료 시 삭제**.
  앱 시작 시 활성 세션에 없는 캐시 + 구버전 찌꺼기 정리, 총 용량 상한(400MB) 초과 시 오래된 것부터 삭제.
- 좌우 양면 스캔본 자동 분할은 **로컬 저장소에서만** (원격은 파일 크기를 미리 알 수 없어 통짜 표시).
- 갤럭시 폴드: 살짝 접었다 펴면 다음 페이지 (`androidx.window`, 설정 토글).

## 구글 드라이브 준비 (1회)
[`docs/google-drive-setup.md`](docs/google-drive-setup.md) 참고. 패키지 `com.foldbook`,
릴리즈 서명 SHA-1 `3A:21:F0:1D:0F:80:01:04:1C:31:82:2E:87:25:55:65:43:C2:17:73`.

## 검증 상태
- `assembleDebug` / `assembleRelease` / 단위 테스트(자연 정렬 · SpreadPolicy) **통과**.
- 이 PC는 RAM 부족으로 에뮬레이터 구동 불가 → **실기기에서 `installDebug` 로 실제 동작 확인 필요.**
- 회전 시 리더는 재생성 없이 GL 표면만 갱신(양면/한 장은 방향 따라감, 스프레드 분할은 시작 시점 기준).

## 핵심 파일
| 파일 | 역할 |
|---|---|
| `ui/FoldBookApp.kt` | Scaffold + 하단 네비 + NavHost |
| `ui/HomeScreen / BrowseScreen / BrowseFolderScreen / SettingsScreen / AddConnection` | 화면들 |
| `Models.kt` / `Stores.kt` | Session · Connection 모델과 JSON 스토어(StateFlow) |
| `StorageBackend.kt` / `SmbBackend.kt` / `DriveBackend.kt` | 로컬/SMB/Drive 공통 접근 |
| `PageStream.kt` | 창 방식 비동기 로더 + 세션 캐시 |
| `PageFlipView.kt` / `PageRender.kt` | eschao PageFlip 연동 (회색→실제 텍스처 1회 교체) |
| `SessionCache.kt` | 세션 캐시 디렉터리 관리 + 찌꺼기 정리 |
| `NaturalOrder.kt` / `SpreadPolicy.kt` | 자연 정렬 · 스프레드 확장 (테스트 있음) |
| `pageflip/` | 벤더링한 eschao PageFlip (Apache-2.0) |
