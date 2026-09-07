# 폴드책 (fold-book)

갤럭시 폴더블에서 쓰는 책 뷰어. 이미지 폴더를 종이책처럼 넘겨 본다.
로컬 저장소 · 윈도우 공유폴더(SMB) · 구글 드라이브의 폴더를 지원.
패키지: `com.foldbook`

## 빌드
- Android Studio 로 `comic-viewer/` 열기 → Gradle sync → 실행.
- CLI:
  ```
  set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot
  gradlew :app:assembleDebug
  gradlew :app:testDebugUnitTest
  ```
- SDK 위치는 `local.properties` (`sdk.dir=C:\Android\sdk`).
- AGP 8.7.3 / Kotlin 2.1.0 / Gradle 8.14.3 / compileSdk 35 / minSdk 26.

## 기능

### 로컬 저장소
- 모든 파일 접근 권한(`MANAGE_EXTERNAL_STORAGE`) 요청
- 이미지 1장 선택 → 그 폴더를 만화로 인식
- **자연 정렬**: `9.jpg < 10.jpg`, 선행 0 무시, 알파벳 섞여도 숫자 덩어리를 값으로 비교 (`NaturalOrder`, 테스트 있음)

### 3D 종이책 페이지 넘김
- 드래그 위치를 그대로 따라 페이지가 말림 (`eschao/android-PageFlip`, Apache-2.0).
- **JitPack 배포본이 없어서** 소스를 `pageflip/` 로컬 모듈로 벤더링했다 (코드 무수정).
- `PageFlipView` / `PageRender` 가 eschao Sample 을 Kotlin 이식하고 텍스처 출처만 실제 이미지로 교체.

### 화면 크기별 표시 (`SpreadPolicy`, 설정)
- 좌우 양면 스캔본(가로>세로) 자동 판정 → 세로 화면은 반씩, 가로/태블릿(sw≥600dp)은 통짜
- 단면 스캔본 → 가로/태블릿에선 두 장 묶어 양면
- `자동 / 항상 한 장 / 항상 양면`

### 읽기 방향
- 설정에서 좌→우 / 우→좌 (기본 우→좌)

### 폴더 끝 → 다음 폴더
- 마지막 페이지에서 앞으로 넘기면 "다음 폴더로 넘어갈까요?" → 형제 폴더를 자연 정렬로 탐색. 로컬/SMB/드라이브 모두.

### 폴더블 접힘 센서 (`FoldGestureDetector`, 설정 토글)
- `androidx.window` 로 경첩 상태 관찰. `FLAT → HALF_OPENED → FLAT` 를 2.5초 안에 감지하면 다음 페이지.
- (angle raw 값은 API 가 안 줘서 상태 전이로 판정)

### 윈도우 공유폴더 (SMB2/3, `smbj`)
- 홈 화면 "윈도우 공유폴더" → 호스트/공유이름/폴더경로/계정 입력
- 폴더 이미지를 앱 캐시로 내려받은 뒤 로컬과 동일한 뷰어로 재생
- 접속 정보는 저장됨 — **비밀번호 평문 SharedPreferences** (`ponytail:` 표시, 필요 시 EncryptedSharedPreferences)

### 구글 드라이브 (Drive v3 REST + Google 로그인)
- 홈 화면 "구글 드라이브" → 로그인 → 폴더 탐색 → "이 폴더 열기" → 캐시로 받아 재생
- **사전 준비(1회)는 [`docs/google-drive-setup.md`](docs/google-drive-setup.md) 참고.**
  요약: GCP 프로젝트 → Drive API 사용 설정 → OAuth 동의화면(외부/테스트, 본인 계정을 테스트 사용자로,
  scope `.../auth/drive.readonly`) → OAuth 클라이언트 ID(Android, 패키지 `com.foldbook` + 서명 SHA-1).
- 릴리즈 서명 SHA-1: `3A:21:F0:1D:0F:80:01:04:1C:31:82:2E:87:25:55:65:43:C2:17:73`

## 실행 / 검증 상태
- `gradlew :app:assembleDebug` + `:app:testDebugUnitTest` **통과** (자연 정렬·SpreadPolicy·폴더 탐색 단위 테스트 포함).
- **에뮬레이터 구동은 이 PC에서 실패** — 여유 RAM 부족(약 340MB, qemu 는 게스트 RAM mmap 에 ~1.5GB 필요) + 디스크 여유 부족.
  툴체인은 준비됨: android-35 이미지 설치, `medium_phone` AVD 생성, 누락된 `userdata.img` 를 직접 만들어 넣음.
- 여유가 생기면 `scripts/run-on-emulator.ps1` 로 부팅→빌드→설치→테스트 데이터 push→스크린샷까지 자동 실행.
  (다른 앱 닫아 RAM ~2GB, 디스크 ~10GB 확보 필요. AVD userdata 파티션이 6GB 고정.)
- 실기기: `gradlew :app:installDebug` 후 파일 관리자에서 이미지 "열기" 로 뷰어 진입.
- 폴더블 접힘 제스처는 현 CLI 프로필에 폴더블 AVD 가 없어 미검증 (코드/로직 리뷰만). Pixel Fold 실기기 필요.

## 테스트 데이터
`testdata/gen.py` 실행 시 `testdata/Comics/원피스/{제1권,제2권,제10권}/` 생성:
자연 정렬(9<10), 이름 섞임(cover), 좌우 양면 스캔본(05.jpg 가로 2000×1400), 폴더 순서(제2권<제10권) 확인용.

## 아직 안 한 것
- 회전 시 Activity 재생성으로 처리(페이지 위치 저장/복원). 무재생성 전환은 추후.
- 원격 캐시 용량 관리(오래된 폴더 자동 삭제) 없음.
- `flipForward()` 는 합성 스와이프. eschao 가 무시하면 move 스텝 튜닝 필요.

## 핵심 파일
| 파일 | 역할 |
|---|---|
| `NaturalOrder.kt` | 파일명 자연 정렬 (테스트) |
| `ComicFolder.kt` | 폴더 스캔 + 형제 폴더 (테스트) |
| `SpreadPolicy.kt` | 원본 이미지 → 화면 페이지 확장 규칙 (테스트) |
| `PageImageProvider.kt` | 페이지 번호 → 크기 맞춘 비트맵 |
| `PageFlipView.kt` / `PageRender.kt` | eschao PageFlip 연동 |
| `FoldGestureDetector.kt` | 폴더블 접힘 제스처 |
| `SmbRepo.kt` / `DriveRepo.kt` / `RemoteSync.kt` | 원격 폴더 → 로컬 캐시 동기화 |
| `SmbConnectActivity.kt` / `DriveBrowseActivity.kt` | 원격 접속·탐색 UI |
| `ReaderActivity.kt` | 뷰어 화면 + 원격 sync + 다음 폴더 이동 |
| `pageflip/` | 벤더링한 eschao PageFlip (Apache-2.0) |
