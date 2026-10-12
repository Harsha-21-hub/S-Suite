plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sdrive.wear"
    compileSdk = 35
    defaultConfig {
        // SAME applicationId on watch + phone: required for the Bluetooth (Wear Data Layer) link.
        applicationId = "com.sdrive.app"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // Only ARM code (watch + phone CPUs): keeps the FFmpeg decoder small.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }
    buildTypes {
        // The watch CPU is slow: a "debuggable" build runs Compose several times slower (no AOT, no
        // baseline profile), which is what made the UI crawl. Run/Install from Android Studio now gives a
        // normal, fast app. (Only the Debug-with-debugger button won't attach; nobody needs it here.)
        getByName("debug") {
            isDebuggable = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so watch + phone builds share a signature (needed for the Bluetooth link).
            // Replace with your own keystore if you publish.
            signingConfig = signingConfigs.getByName("debug")
        }
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
        disable += setOf("UnsafeOptInUsageError", "ScopedStorage")
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version", "DebugProbesKt.bin")
    }
}

val media3 = "1.5.1"

dependencies {
    implementation(project(":shared"))

}
