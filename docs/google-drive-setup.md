# 구글 드라이브 연동 설정 가이드

이 앱의 드라이브 기능은 **API 키가 아니라 OAuth 2.0 클라이언트**가 필요하다.
(API 키는 공개 데이터 전용. 사용자의 드라이브 파일을 읽으려면 OAuth 로그인이 필수.)

앱 구조:
- `GoogleSignIn` 으로 로그인 → `GoogleAuthUtil.getToken()` 으로 액세스 토큰 획득
- 토큰으로 Drive v3 REST (`https://www.googleapis.com/drive/v3/files`) 직접 호출
- 범위(scope): `https://www.googleapis.com/auth/drive.readonly` (읽기 전용)
- OAuth 클라이언트 타입: **Android** — 패키지명 + 서명 인증서 SHA-1 로 검증하므로 앱에 넣는 client_secret 은 없다.

---

## 1. GCP 프로젝트 준비

1. https://console.cloud.google.com 접속 (드라이브를 쓸 구글 계정으로 로그인)
2. 상단 프로젝트 선택 → **새 프로젝트** → 이름 예: `comic-viewer` → 만들기
3. 만든 프로젝트가 선택된 상태인지 확인

## 2. Google Drive API 사용 설정

1. 좌측 메뉴 → **API 및 서비스 → 라이브러리**
2. `Google Drive API` 검색 → 선택 → **사용** 클릭
   - 이걸 안 하면 앱에서 `403 ... Google Drive API has not been used in project ...` 에러가 난다.

## 3. OAuth 동의 화면 구성

좌측 메뉴 → **API 및 서비스 → OAuth 동의 화면**

1. User Type: **외부(External)** 선택 → 만들기
2. 앱 정보:
   - 앱 이름: `만화책 뷰어` (아무거나)
   - 사용자 지원 이메일: 본인 이메일
   - 개발자 연락처 정보: 본인 이메일
   - 나머지(로고, 도메인)는 비워도 됨 → 저장 후 계속
3. **범위(Scopes)**: **범위 추가 또는 삭제** →
   - 필터에 `drive.readonly` 입력 → `.../auth/drive.readonly` (See and download all your Google Drive files) 체크 → 업데이트
   - 저장 후 계속
   - ⚠️ 이건 "제한된 범위(restricted/sensitive scope)" 라서, 앱을 정식 게시하려면 Google 검수가 필요하다.
     **테스트 모드로 두면 검수 없이** 최대 100명(테스트 사용자)까지 쓸 수 있다.
4. **테스트 사용자(Test users)**: **ADD USERS** → 본인 Gmail 주소 추가 → 저장
   - 여기 없는 계정으로 로그인하면 `403 access_denied` 가 난다.
5. 게시 상태는 **"테스트 중"** 그대로 둔다. (프로덕션으로 올리지 말 것 — 검수 요구됨)

## 4. 서명 인증서 SHA-1 지문 확인

OAuth 클라이언트(Android)는 `패키지명 + SHA-1` 조합으로 앱을 식별한다.
디버그 빌드와 릴리즈 빌드는 서명 키가 다르므로 **둘 다 등록**하는 게 편하다.

### 릴리즈 (이 저장소의 keystore)
```
keytool -list -v -keystore keystore/release.jks -alias comicviewer
# 비밀번호는 keystore.properties 참고
```
현재 릴리즈 키 SHA-1:
```
26:09:3C:8B:B4:DD:36:DD:9B:9C:D9:49:B1:96:66:3A:D1:15:4C:5B
```
(SHA-256: `E6:0E:BB:F8:3D:34:00:9A:6A:22:B5:6C:AA:92:CA:98:53:33:CE:82:9A:3E:7A:3F:29:D4:F4:C9:81:78:7E:BB`)

### 디버그 (Android Studio 기본 키)
```
keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey -storepass android -keypass android
```
출력의 `SHA1:` 줄 값을 사용.

## 5. OAuth 2.0 클라이언트 ID 생성

좌측 메뉴 → **API 및 서비스 → 사용자 인증 정보** → **사용자 인증 정보 만들기 → OAuth 클라이언트 ID**

1. 애플리케이션 유형: **Android**
2. 이름: `comic-viewer android` (아무거나)
3. 패키지 이름: `com.comicviewer`  ← 정확히 이 값
4. SHA-1 인증서 지문: 위 4번에서 얻은 값 붙여넣기
5. 만들기
6. 디버그 키로도 테스트하려면 **같은 방식으로 하나 더** 만들어 디버그 SHA-1 등록
   (Android OAuth 클라이언트는 프로젝트당 여러 개 가능)

> Android 유형 클라이언트는 client ID/secret 을 앱에 넣지 않는다.
> 구글 로그인 SDK 가 기기에서 패키지명+서명을 확인해 자동으로 매칭한다. **다운로드할 파일 없음.**

## 6. 앱에서 확인

1. 앱 실행 → **구글 드라이브** → **구글 로그인**
2. 계정 선택 → "Google에서 확인하지 않은 앱입니다" 화면이 나오면
   **고급 → '만화책 뷰어'(안전하지 않음)으로 이동** → 계속
3. `Google Drive의 모든 파일 보기 및 다운로드` 권한 요청 → 허용
4. 폴더 목록이 뜨면 성공. 폴더 진입 후 **이 폴더 열기**.

---

## 자주 나는 에러

| 증상 | 원인 / 해결 |
|---|---|
| 로그인 직후 `10: DEVELOPER_ERROR` 토스트 | SHA-1 또는 패키지명이 OAuth 클라이언트와 불일치. 4~5번 재확인 (특히 릴리즈/디버그 빌드 구분) |
| `403 access_denied` / 동의 화면에서 막힘 | 로그인한 계정이 **테스트 사용자**에 없음 (3-4) |
| `403 ... Google Drive API has not been used` | Drive API 미사용 설정 (2번) |
| `UserRecoverableAuthException` | 동의 화면 scope 에 `drive.readonly` 누락(3-3), 또는 최초 1회 동의 필요 → 다시 로그인 |
| 로그인은 되는데 폴더가 안 뜸 | scope 누락, 또는 테스트 모드 refresh token 만료(7일) → 재로그인 |
| `PERMISSION_DENIED` (특정 폴더) | 공유받은 폴더가 아님 / 휴지통 상태 |

## 참고: 테스트 모드 한계
- 테스트 사용자 최대 100명
- 발급된 refresh token 이 **7일 후 만료** → 주기적으로 재로그인 필요
- 정식 배포하려면 OAuth 앱 검수(민감/제한 범위 심사, 개인정보처리방침 URL·데모 영상 등) 필요.
  개인용이면 테스트 모드로 계속 쓰는 게 현실적.
