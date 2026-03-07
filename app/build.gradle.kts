plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.tvfileserver"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.tvfileserver"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Блок для отключения тестов (исправлено)
    testOptions {
        unitTests.all {
            it.enabled = false
        }
    }
}

// Конфигурация Kotlin компилятора с использованием нового DSL
kotlin {
    compilerOptions {
        // Устанавливаем целевую версию JVM с помощью типобезопасного API
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")

    // Для Android TV
    implementation("androidx.leanback:leanback:1.2.0")

    // NanoHTTPD
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Для QR-кодов - ВАЖНО!
    implementation("com.google.zxing:core:3.5.3")
}