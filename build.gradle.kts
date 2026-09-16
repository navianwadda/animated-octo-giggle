// app/build.gradle.kts — V1 (Bundled)
// Drop this into your app module. Merge with your existing build.gradle.kts.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-parcelize")
    id("com.chaquo.python") version "15.0.0"   // <-- Chaquopy
}

android {
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yourapp.ydl"
        minSdk = 26           // Chaquopy requires API 21+; 26 recommended
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // ── Chaquopy configuration ────────────────────────────────────────
        ndk {
            // Only ship ARM64 and x86_64 to keep APK size reasonable.
            // ARM64 covers ~95% of Android devices in 2024+.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        python {
            // Python version to bundle
            version = "3.11"

            // pip packages bundled at build time
            pip {
                install("yt-dlp")
                // Do NOT install ffmpeg here — we use ffmpeg-kit instead
            }

            // ydl_bridge.py lives at src/main/python/ydl_bridge.py
            // Chaquopy picks it up automatically — no extra config needed
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    // Bundle splits: users download only their ABI from Play Store
    bundle {
        abi { enableSplit = true }
        density { enableSplit = true }
        language { enableSplit = true }
    }
}

dependencies {
    // ── Chaquopy (Python runtime) ──────────────────────────────────────────
    // No extra dependency needed — the plugin handles it

    // ── ffmpeg-kit (on-device video merge) ────────────────────────────────
    // "full" includes all codecs. Use "min" (~15MB smaller) if you only need
    // h264/aac which covers 99% of YouTube downloads.
    implementation("com.arthenica:ffmpeg-kit-full:6.0-2")

    // ── Jetpack Compose ───────────────────────────────────────────────────
    val composeBom = platform("androidx.compose:compose-bom:2024.04.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // ── Image loading (thumbnails) ────────────────────────────────────────
    implementation("io.coil-kt:coil-compose:2.6.0")

    // ── Coroutines ────────────────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
