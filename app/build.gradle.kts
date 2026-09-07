import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// keystore.properties 가 있으면 release 서명에 사용 (없으면 release 는 미서명으로 빌드)
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.foldbook"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.foldbook"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 3D 종이책 페이지 넘김 — eschao/android-PageFlip 을 로컬 모듈로 벤더링 (pageflip/ 참고).
    implementation(project(":pageflip"))

    // 폴더블 접힘 센서
    implementation("androidx.window:window:1.3.0")

    // 윈도우 공유폴더 (SMB2/3)
    implementation("com.hierynomus:smbj:0.13.0")
    implementation("org.slf4j:slf4j-nop:2.0.9")

    // 구글 드라이브 (로그인/토큰만 사용, Drive 호출은 REST 직접)
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    testImplementation("junit:junit:4.13.2")
}
