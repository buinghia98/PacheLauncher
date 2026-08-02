// The Mina the Hollower host app for PacheLauncher (docs/INTEGRATION.md). This module owns the
// game's own applicationId, icon, theme seed colour, and packaging; it never depends on anything
// under H:\AI Projects\MinaTheHollower\android — see MinaGameProxyActivity.kt for why.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.teampacheworks.minalauncher"
    compileSdk = 34

    defaultConfig {
        // Deliberately NOT com.teampacheworks.mina - that applicationId belongs to the actual
        // game APK built under android/. This is a separate, standalone launcher app that starts
        // it via an explicit cross-package Intent (see MinaGameProxyActivity).
        applicationId = "com.teampacheworks.minalauncher"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":launcher"))
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
}
