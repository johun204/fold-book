# 만화책 뷰어 - 에뮬레이터에서 빌드·설치·스모크 테스트
# 요구: 여유 RAM 약 2GB, 여유 디스크 약 10GB (AVD userdata 파티션이 6GB 고정)
# 사용: powershell -ExecutionPolicy Bypass -File scripts\run-on-emulator.ps1

$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
$env:ANDROID_HOME = 'C:\Android\sdk'
$sdk = $env:ANDROID_HOME
$adb = "$sdk\platform-tools\adb.exe"
$emu = "$sdk\emulator\emulator.exe"
$sysimg = "$sdk\system-images\android-35\google_apis\x86_64"
$proj = Split-Path $PSScriptRoot -Parent

# 1) AVD 없으면 생성
& "$sdk\cmdline-tools\latest\bin\android.exe" emulator list 2>$null | Out-Null
if (-not (Test-Path "$env:USERPROFILE\.android\avd\medium_phone.ini")) {
    & "$sdk\cmdline-tools\latest\bin\android.exe" emulator create medium_phone
    # 이 CLI 가 만든 config 는 android-36 playstore 를 가리키므로 android-35 google_apis 로 교정
    $cfg = "$env:USERPROFILE\.android\avd\medium_phone.avd\config.ini"
    (Get-Content $cfg) `
        -replace 'image.sysdir.1=.*', 'image.sysdir.1=system-images/android-35/google_apis/x86_64/' `
        -replace 'target=.*', 'target=android-35' `
        -replace 'tag.id=.*', 'tag.id=google_apis' `
        -replace 'tag.ids=.*', 'tag.ids=google_apis' `
        -replace 'PlayStore.enabled=.*', 'PlayStore.enabled=false' | Set-Content $cfg
}

# 2) 이 system image 는 userdata.img 가 빠져있음 -> 직접 생성 (에뮬레이터 번들 e2fsck 와 호환되는 옵션)
if (-not (Test-Path "$sysimg\userdata.img")) {
    & "$sdk\emulator\qemu-img.exe" create -f raw "$sysimg\userdata.img" 2G
    & "$sdk\platform-tools\mke2fs.exe" -t ext4 -O '^64bit,^metadata_csum,^orphan_file,^metadata_csum_seed' -b 4096 -I 256 -q -F "$sysimg\userdata.img"
}

# 3) 부팅
Start-Process -NoNewWindow $emu -ArgumentList '-avd','medium_phone','-no-boot-anim','-no-snapshot','-wipe-data','-gpu','swiftshader_indirect'
Write-Host "부팅 대기..."
& $adb wait-for-device
do { Start-Sleep 3; $b = (& $adb shell getprop sys.boot_completed) -replace '\s' } until ($b -eq '1')
& $adb shell settings put global window_animation_scale 0
& $adb shell settings put global transition_animation_scale 0
& $adb shell settings put global animator_duration_scale 0

# 4) 빌드 + 설치
& "$proj\gradlew.bat" -p $proj :app:assembleDebug
& $adb install -r -g "$proj\app\build\outputs\apk\debug\app-debug.apk"

# 5) 테스트 데이터 푸시 + 모든 파일 접근 권한
& $adb shell appops set com.comicviewer MANAGE_EXTERNAL_STORAGE allow
if (Test-Path "$proj\testdata\Comics") { & $adb push "$proj\testdata\Comics" /sdcard/ }

# 6) 첫 페이지 열기 (ACTION_VIEW 로 ReaderActivity 직행)
& $adb shell am start -a android.intent.action.VIEW -t image/jpeg -d 'file:///sdcard/Comics/%EC%9B%90%ED%94%BC%EC%8A%A4/%EC%A0%9C1%EA%B6%8C/1.jpg' -n com.comicviewer/.ReaderActivity
Start-Sleep 4
& $adb exec-out screencap -p > "$proj\scripts\shot-reader.png"
Write-Host "스크린샷: scripts\shot-reader.png"

# 7) 넘김 제스처
& $adb shell input swipe 900 1200 120 1200 350
Start-Sleep 2
& $adb exec-out screencap -p > "$proj\scripts\shot-flip.png"
Write-Host "완료. 홈: adb shell am start -n com.comicviewer/.MainActivity"
