plugins {
    id("com.android.library")
}

// Собирает hev-socks5-tunnel (MIT) через ndk-build в libhev-socks5-tunnel.so
// и содержит JNI-класс hev.htproxy.TProxyService (имя фиксировано в нативной библиотеке).
android {
    namespace = "com.wolftower.tun2socks"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        externalNativeBuild {
            ndkBuild {
                // APP_PLATFORM в Application.mk библиотеки (android-29) выше нашего minSdk
                arguments("APP_PLATFORM=android-26", "REV_ID=50d7b41")
            }
        }
    }

    externalNativeBuild {
        ndkBuild { path = file("../../third_party/hev-socks5-tunnel/Android.mk") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
