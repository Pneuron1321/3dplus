plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.questclaude.most3"
    compileSdk = 34

    defaultConfig {
        // Мост 3 — отдельное приложение рядом с мостом 2 (com.questclaude.bridge2): своё имя пакета, свой порт 8767.
        // Показ и звук карты захвата без HDMI Link (22.10).
        applicationId = "com.questclaude.most3"
        minSdk = 29
        targetSdk = 34
        versionCode = 320
        versionName = "3.2.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    // Тот же ключ, что у моста 2 (сборка расшифровывает quest-bridge/app/debug.keystore.enc): обновления — поверх.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Без androidx: платформенные Activity и GLSurfaceView.
    // Звук карты — изохронные передачи usbfs (ioctl) через JNA: в Java их нет, NDK не нужен.
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    testImplementation("junit:junit:4.13.2")
}
