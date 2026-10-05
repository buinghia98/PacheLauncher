pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // RadialGamePad (:touchpad only) is published on JitPack and nowhere else. Scoped to that
        // one group so a typo in any other coordinate fails against Maven Central rather than
        // silently resolving a same-named artifact from a build service anyone can publish to.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.Swordfish90") }
        }
    }
}

rootProject.name = "PacheLauncher"

include(":launcher")
// The on-screen gamepad. A SEPARATE module because it links GPL-3.0 code -- see
// touchpad/build.gradle.kts. A host that has no use for a touch overlay simply omits this include.
include(":touchpad")
include(":sample")
