package com.teampacheworks.launcher

import android.app.Activity

/**
 * A representative [LauncherConfig] for unit tests: framework-scoped code (save bundling, cloud
 * payload/manifest, token parsing) reads [LauncherHost.config] for game-specific values, so tests
 * that exercise it need one installed. Values deliberately mirror a plausible real port (glob
 * pattern, exclude list, cloud app id) without depending on any specific host.
 */
fun installTestConfig(
    cloudAppId: String = "testgame",
    savePatterns: List<String> = listOf("*.fasta"),
    saveExcludeNames: Set<String> = setOf("device_config.txt", "devkey.txt")
) {
    LauncherHost.install(
        LauncherConfig(
            gameTitle = "Test Game",
            gameSubtitle = "Unit test fixture",
            appLabel = "Test Game",
            downloadsFolderName = "TestGame",
            cloudAppId = cloudAppId,
            savePatterns = savePatterns,
            saveExcludeNames = saveExcludeNames,
            gameActivityClass = Activity::class.java,
            iconRes = 0
        )
    )
}
