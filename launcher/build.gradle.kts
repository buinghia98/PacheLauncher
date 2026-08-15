plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.teampacheworks.launcher"
    compileSdk = 34

    defaultConfig {
        // CrashReporter's process-exit forensics (ActivityManager.getHistoricalProcessExitReasons)
        // is an API 30 call; lowering minSdk requires guarding that call with a
        // Build.VERSION.SDK_INT check first (see README.md).
        minSdk = 30

        consumerProguardFiles("consumer-rules.pro")
    }

    // IMPORTANT: this module must stay pure Kotlin/Java. No native (.so) dependency may ever be
    // added here: ABI choices (which native libraries ship, which architectures are supported)
    // belong entirely to the host app's game side, and a library that pulled in a native
    // dependency of its own would constrain every consumer's ABI set whether they wanted that or
    // not. See README.md "Constraints".
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

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests {
            // LauncherLog touches android.util.Log directly, and the ring-buffer tests only care
            // about the buffer itself, so stubbed framework calls must return instead of throw.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // Material 1.13.0, not 1.14+: see README.md "Material / pill button". Consumers that only need
    // Material 1.14's default-pill MaterialButton and don't mind the AGP bump can upgrade here.
    implementation("com.google.android.material:material:1.13.0")

    // Cloud backup. BOTH are pure JVM/Kotlin: no .so may ever enter this module.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation("junit:junit:4.13.2")
}
