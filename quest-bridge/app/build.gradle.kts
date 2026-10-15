plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.questclaude.bridge"
    compileSdk = 34

    defaultConfig {
        // Вторая версия — отдельное приложение рядом с 1.0 (com.questclaude.bridge):
        // своё имя пакета, свой порт (Hub.PORT), своя Ubuntu «plus2» в Termux.
        applicationId = "com.questclaude.bridge2"
        minSdk = 29
        // 34: правила Android 14 для MediaProjection соблюдены (служба переднего плана,
        // колбэк до createVirtualDisplay, одноразовый токен). Служба нужна с targetSdk 29.
        targetSdk = 34
        versionCode = 250
        versionName = "2.5.0"
        // Quest 3 — arm64; остальные ABI Vosk и JNA только раздували бы APK.
        ndk { abiFilters += "arm64-v8a" }
    }

    // Один ключ на все сборки: GitHub каждый раз создаёт новый debug-ключ, и обновление
    // не ставилось бы поверх (INSTALL_FAILED_UPDATE_INCOMPATIBLE). Ключ отладочный,
    // пароли стандартные «android»; репо приватный. С 0.3.0 обновления — поверх.
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
    // Без androidx: платформенный Activity и Service.
    // Уши: Vosk — распознавание речи в шлеме, без интернета. Малые модели ru и en
    // кладёт в assets сборка на GitHub (ears/modeli.sh).
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    // Гости эфира (2.5.0): Opus — чистая Java-копия libopus (BSD), без нативных библиотек; проверяется на столе.
    implementation("io.github.jaredmdobson:concentus:1.0.2")
    testImplementation("junit:junit:4.13.2")
}
