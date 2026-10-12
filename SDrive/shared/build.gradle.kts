plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sdrive.shared"
    compileSdk = 35
    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
        disable += setOf("UnsafeOptInUsageError", "ScopedStorage", "QueryAllPackagesPermission")
    }
}

val media3 = "1.5.1"

dependencies {
    api(platform("androidx.compose:compose-bom:2024.12.01"))
    api("androidx.compose.ui:ui")
    api("androidx.compose.foundation:foundation")
    api("androidx.activity:activity-compose:1.9.3")
    api("androidx.core:core-ktx:1.15.0")
    api("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    api("androidx.media3:media3-exoplayer:$media3")
    api("androidx.media3:media3-session:$media3")
    // FFmpeg audio decoders for what the watch/phone chips can't decode themselves
    // (AC-3, E-AC-3, DTS, TrueHD, Vorbis… common in MKV/AVI/TS movies). Built for media3 1.5.x.
    api("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")
    api("com.google.android.gms:play-services-wearable:19.0.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    // Installs Compose's precompiled baseline profiles with the app -> less first-run UI stutter
    api("androidx.profileinstaller:profileinstaller:1.4.1")
}
