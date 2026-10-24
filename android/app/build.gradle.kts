plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wolftower.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wolftower.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    // Стабильная подпись APK (нужна, чтобы ID устройства в лицензиях не менялся между сборками
    // и обновления ставились поверх). Включается, если заданы переменные окружения WT_*.
    val keystorePath: String? = System.getenv("WT_KEYSTORE_FILE")
    if (keystorePath != null && file(keystorePath).exists()) {
        val wtSigning = signingConfigs.create("wt") {
            storeFile = file(keystorePath)
            storePassword = System.getenv("WT_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("WT_KEY_ALIAS")
            keyPassword = System.getenv("WT_KEY_PASSWORD")
        }
        buildTypes {
            getByName("debug") { signingConfig = wtSigning }
            getByName("release") { signingConfig = wtSigning }
        }
    }

    lint { checkReleaseBuilds = false }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }

    // Нужно, чтобы libciadpi.so распаковывался на диск и его можно было запустить как процесс
    packaging { jniLibs { useLegacyPackaging = true } }

    // profiles/profiles.json общий для всех платформ и попадает в assets
    sourceSets["main"].assets.srcDir("../../profiles")

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":tun2socks"))
    val bom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(bom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
