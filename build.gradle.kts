// Versions pinned: Gradle 8.9, AGP 8.5.2, Kotlin 1.9.24. See README.md for the upgrade path
// (Material 1.14 / newer AGP) and README.md "Provenance" for where these pins came from.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("com.android.library") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
}
