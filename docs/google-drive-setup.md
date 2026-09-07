# 구글 드라이브 연동 설정 가이드 (폴드책 / fold-book)

이 앱의 드라이브 기능은 **API 키가 아니라 OAuth 2.0 클라이언트**가 필요하다.
(API 키는 공개 데이터 전용. 사용자의 드라이브 파일을 읽으려면 OAuth 로그인이 필수다.)

앱 동작 방식:
- `GoogleSignIn` 으로 로그인 → `GoogleAuthUtil.getToken()` 으로 액세스 토큰 획득
- 토큰으로 Drive v3 REST(`https://www.googleapis.com/drive/v3/files`) 직접 호출
- 권한 범위(scope): `https://www.googleapis.com/auth/drive.readonly` (읽기 전용)
- OAuth 클라이언트 타입: **Android** — `패키지명 + 서명 인증서 SHA-1` 로 검증하므로 앱에 넣는 client_secret 은 없다. **다운로드할 JSON 파일도 없다.**

| 항목 | 값 |
|---|---|
| 패키지명 (applicationId) | `com.foldbook` |
| 릴리즈 서명 SHA-1 | `3A:21:F0:1D:0F:80:01:04:1C:31:82:2E:87:25:55:65:43:C2:17:73` |
| 릴리즈 서명 SHA-256 | `48:D6:38:EB:2E:05:A6:77:1B:E5:F9:DE:74:F1:DB:4E:88:EC:8D:F7:DD:30:06:18:D3:50:D9:87:C7:19:96:45` |
| 요청 scope | `.../auth/drive.readonly` (민감 범위) |

---

## 1. GCP 프로젝트 준비
1. https://console.cloud.google.com 접속 (드라이브를 쓸 구글 계정으로 로그인)
2. 상단 프로젝트 선택 → **새 프로젝트** → 이름 예: `foldbook` → 만들기
3. 방금 만든 프로젝트가 선택돼 있는지 확인

## 2. Google Drive API 사용 설정
1. 좌측 메뉴 → **API 및 서비스 → 라이브러리**
2. `Google Drive API` 검색 → 선택 → **사용** 클릭
   - 누락 시 앱에서 `403 ... Google Drive API has not been used in project ...` 에러 발생

## 3. OAuth 동의 화면 구성
좌측 메뉴 → **API 및 서비스 → OAuth 동의 화면**

1. User Type: **외부(External)** → 만들기
2. 앱 정보
   - 앱 이름: `폴드책` (자유)
   - 사용자 지원 이메일 / 개발자 연락처 이메일: 본인 이메일
   - 로고·도메인은 비워도 됨 → 저장 후 계속
3. **범위(Scopes)** → **범위 추가 또는 삭제**
   - 필터에 `drive.readonly` 입력 → `.../auth/drive.readonly` 체크 → 업데이트 → 저장 후 계속
   - ⚠️ 민감/제한 범위라서 **정식 게시하려면 Google 검수 필요**. 테스트 모드면 검수 없이 최대 100명 사용 가능.
4. **테스트 사용자(Test users)** → **ADD USERS** → 본인 Gmail 추가 → 저장
   - 여기 없는 계정으로 로그인 시 `403 access_denied`
5. 게시 상태는 **"테스트 중" 그대로** 둔다 (프로덕션으로 올리면 검수 요구됨)

## 4. 서명 인증서 SHA-1 확인
OAuth Android 클라이언트는 `패키지명 + SHA-1` 로 앱을 식별한다. 디버그·릴리즈 서명이 다르므로 **둘 다 등록**하면 편하다.

### 릴리즈 (이 저장소의 keystore)
현재 릴리즈 키:
```
SHA-1  : 3A:21:F0:1D:0F:80:01:04:1C:31:82:2E:87:25:55:65:43:C2:17:73
SHA-256: 48:D6:38:EB:2E:05:A6:77:1B:E5:F9:DE:74:F1:DB:4E:88:EC:8D:F7:DD:30:06:18:D3:50:D9:87:C7:19:96:45
```
직접 확인하려면:
```
keytool -list -v -keystore keystore/release.jks -alias foldbook
# 비밀번호는 keystore.properties 참고 (git 에는 없음 — 로컬 파일)
```

### 디버그 (Android Studio 기본 키, 개발 빌드용)
```
keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey -storepass android -keypass android
```
출력의 `SHA1:` 값을 사용.

## 5. OAuth 2.0 클라이언트 ID 생성
좌측 메뉴 → **API 및 서비스 → 사용자 인증 정보** → **사용자 인증 정보 만들기 → OAuth 클라이언트 ID**

1. 애플리케이션 유형: **Android**
2. 이름: `foldbook-android-release` (자유)
3. 패키지 이름: `com.foldbook`  ← 정확히 이 값
4. SHA-1 인증서 지문: 위 릴리즈 SHA-1 붙여넣기
5. 만들기
6. 개발 빌드로도 테스트하려면 같은 방식으로 하나 더 만들어 **디버그 SHA-1** 등록
   (Android OAuth 클라이언트는 프로젝트당 여러 개 가능)

> Android 유형 클라이언트는 client ID/secret 을 앱에 넣지 않는다. 구글 로그인 SDK 가 기기에서
> 패키지명+서명을 확인해 자동으로 매칭한다.

## 6. 앱에서 확인
1. 앱 실행 → **구글 드라이브** → **구글 로그인**
2. 계정 선택 → "Google에서 확인하지 않은 앱입니다" 화면이 나오면 **고급 → '폴드책'(안전하지 않음)으로 이동** → 계속
3. `Google Drive의 모든 파일 보기 및 다운로드` 권한 요청 → 허용
4. 폴더 목록이 뜨면 성공. 폴더 진입 후 **이 폴더 열기**.

---

## 자주 나는 에러
| 증상 | 원인 / 해결 |
|---|---|
| 로그인 직후 `10: DEVELOPER_ERROR` | SHA-1 또는 패키지명 불일치. 릴리즈/디버그 빌드 구분 확인 (4~5) |
| `403 access_denied` / 동의 화면에서 막힘 | 로그인 계정이 **테스트 사용자**에 없음 (3-4) |
| `403 ... Google Drive API has not been used` | Drive API 미사용 설정 (2) |
| `UserRecoverableAuthException` | 동의 화면 scope 에 `drive.readonly` 누락(3-3) 또는 최초 동의 필요 → 재로그인 |
| 로그인은 되는데 폴더가 안 뜸 | scope 누락, 또는 테스트 모드 refresh token 7일 만료 → 재로그인 |
| `PERMISSION_DENIED` (특정 폴더) | 공유받지 않은 폴더 / 휴지통 상태 |

## 테스트 모드 한계
- 테스트 사용자 최대 100명
- refresh token 이 **7일 후 만료** → 주기적 재로그인 필요
- 정식 배포하려면 OAuth 앱 검수(민감 범위 심사, 개인정보처리방침 URL, 데모 영상 등) 필요.
  개인용이면 테스트 모드 유지가 현실적.
