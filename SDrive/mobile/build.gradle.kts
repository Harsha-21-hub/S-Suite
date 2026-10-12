plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sdrive.mobile"
    compileSdk = 35
    defaultConfig {
        // SAME applicationId on watch + phone: required for the Bluetooth (Wear Data Layer) link.
        applicationId = "com.sdrive.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // Only ARM code (watch + phone CPUs): keeps the FFmpeg decoder small.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }
    buildTypes {
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
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version", "DebugProbesKt.bin",
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/versions/9/module-info.class",
            "META-INF/BCKEY.DSA", "META-INF/BCKEY.SF",
        )
    }
}

val media3 = "1.5.1"

dependencies {
    implementation(project(":shared"))
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")

    // ADB terminal: pair/connect to the watch over Wireless debugging, run shell, install APKs
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
}
