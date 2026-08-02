import java.util.Properties

// The Mina the Hollower host app for PacheLauncher (docs/INTEGRATION.md). This module owns the
// game's own applicationId, icon, theme seed colour, and packaging; it never depends on anything
// under H:\AI Projects\MinaTheHollower\android — see MinaGameProxyActivity.kt for why.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// --- release signing --------------------------------------------------------
// Shares the game's keystore (H:\AI Projects\MinaTheHollower\keystore, see the
// README there). Both the keystore and keystore.properties are gitignored, so
// this is loaded defensively: if nothing is found the release signingConfig is
// not created at all and the release build just falls back to the debug key.
val keystorePropsFile = listOf(
    file("keystore.properties"),
    rootProject.file("keystore.properties"),
    rootProject.file("../../keystore/keystore.properties"),
).firstOrNull { it.exists() }

val keystoreProps = Properties()
keystorePropsFile?.inputStream()?.use { stream -> keystoreProps.load(stream) }
val hasReleaseKeystore = keystorePropsFile != null &&
    (keystoreProps["storeFile"]?.toString()?.let { path: String -> file(path).exists() } == true)

if (keystorePropsFile == null) {
    logger.lifecycle("mina-launcher: no keystore.properties found -- release build will use the debug key.")
}

// --- default APK export dir -------------------------------------------------
// <MinaTheHollower>/dist, the same dir the game APK self-exports to.
val distDir = rootProject.file("../../dist")

android {
    namespace = "com.teampacheworks.minalauncher"
    compileSdk = 34

    defaultConfig {
        // Deliberately NOT com.teampacheworks.minathehollower - that applicationId belongs to the actual
        // game APK built under android/. This is a separate, standalone launcher app that starts
        // it via an explicit cross-package Intent (see MinaGameProxyActivity).
        applicationId = "com.teampacheworks.minalauncher"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
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

// Self-export: every `assemble<Variant>` finalizes by copying its APK into
// <MinaTheHollower>/dist as MinaLauncher-<variant>.apk (see distDir above).
android.applicationVariants.all {
    val variant = this
    val copyTask = tasks.register<Copy>(
        "copy${variant.name.replaceFirstChar { it.uppercase() }}ApkToDist"
    ) {
        description = "Copies the ${variant.name} APK into $distDir"
        from(variant.packageApplicationProvider.map { it.outputDirectory }) {
            include("*.apk")
            rename { "MinaLauncher-${variant.name}.apk" }
        }
        into(distDir)
    }
    variant.assembleProvider.configure { finalizedBy(copyTask) }
}

dependencies {
    implementation(project(":launcher"))
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
}
