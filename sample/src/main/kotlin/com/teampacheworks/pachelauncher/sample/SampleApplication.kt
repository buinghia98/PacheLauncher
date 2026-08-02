package com.teampacheworks.pachelauncher.sample

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.teampacheworks.launcher.LauncherConfig
import com.teampacheworks.launcher.LauncherHost
import com.teampacheworks.launcher.log.CrashReporter
import com.teampacheworks.launcher.log.LauncherLog

/**
 * The one place a host app installs its [LauncherConfig] (docs/INTEGRATION.md step 2). Every
 * library Activity reads [LauncherHost.config], so this must run before any of them can start -
 * `Application.onCreate` is called in every process this app runs, including the ":game" process
 * declared for [SampleGameActivity] in AndroidManifest.xml, so this class runs there too.
 */
class SampleApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        LauncherLog.tag = "PacheLauncherSample"
        CrashReporter.install(this, currentProcessName())

        LauncherHost.install(
            LauncherConfig(
                gameTitle = "Sample Game",
                gameSubtitle = "Android port · Team Pache Works",
                appLabel = "Sample Game",
                footerText = "Team Pache Works · sample build, not a real game",
                downloadsFolderName = "PacheLauncherSample",
                cloudAppId = "pachelauncher-sample",
                savePatterns = listOf("save_*.dat"),
                saveExcludeNames = setOf("settings.cfg"),
                gameActivityClass = SampleGameActivity::class.java,
                appVersionName = BuildConfig.VERSION_NAME,
                iconRes = R.mipmap.ic_launcher,
                gameProcessSuffix = ":game"
            )
        )

        // Dynamic Color only in the launcher process (docs/UI-SPEC.md "Theme") - the ":game"
        // process runs SampleGameActivity, which is not part of the Dustaet UI standard this
        // library ships and must not pick up a Material You palette meant for it.
        if (!currentProcessName().endsWith(":game")) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }
    }

    // getProcessName() is API 28+; this module's minSdk is 30 (set by :launcher), so no guard needed.
    private fun currentProcessName(): String = getProcessName()
}
