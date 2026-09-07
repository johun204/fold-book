// eschao/android-PageFlip (Apache-2.0) 를 로컬 모듈로 벤더링. JitPack 배포본이 존재하지 않아서 소스째로 포함.
// 원본: https://github.com/eschao/android-PageFlip  — 코드 수정 없음, 빌드 스크립트만 현행화.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.eschao.android.widget.pageflip"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = false
    }
}
