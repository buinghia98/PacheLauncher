plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

/*
 * WHY THIS IS A SEPARATE MODULE AND NOT PART OF :launcher
 *
 * RadialGamePad is GPL-3.0. Every source file in Swordfish90's library carries the GPL header and
 * its repository LICENSE is GPL v3, so linking it makes the linking APK a derived work under the
 * GPL's terms ON DISTRIBUTION. :launcher must stay something a host can adopt without inheriting
 * that, so the dependency lives here and a host opts into it by adding one more `include`.
 *
 * The other half of the same argument: a host that ships to handhelds with real sticks does not
 * want an on-screen pad's code, its coroutines dependency or its two extra activities in the APK at
 * all. Neither cost should be the price of using the launcher.
 *
 * The :launcher rule this module DOES keep is the important one: no native (.so) dependency may
 * ever be added here either. RadialGamePad is pure Kotlin canvas drawing, and the one thing this
 * module cannot do by itself -- deliver a press to a game engine -- is deliberately left to the
 * host as [TouchGamepadSink]. ABI choices stay entirely on the host's game side.
 */
android {
    namespace = "com.teampacheworks.launcher.touch"
    compileSdk = 34

    defaultConfig {
        // Matches :launcher. Nothing in this module needs an API above 21 by itself; the floor is
        // inherited so a host cannot end up with two different minSdk claims from one library.
        minSdk = 30

        consumerProguardFiles("consumer-rules.pro")
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

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // `api`, not `implementation`: a host writes LauncherOptionScreen(actionActivityClass =
    // TouchGamepadEditorActivity::class.java) and implements TouchGamepadSink, so it is already
    // compiling against both libraries and must not have to declare :launcher twice.
    api(project(":launcher"))

    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")

    // The on-screen pad itself. GPL-3.0 -- see the note above. The same library Lemuroid uses.
    implementation("com.github.Swordfish90:radialgamepad:2.0.0")
    // RadialGamePad exposes its events as a kotlinx Flow and depends only on coroutines-core;
    // Dispatchers.Main lives in the android artifact, and TouchGamepadOverlay collects on it
    // because every RadialGamePad property it then touches is a View property.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
