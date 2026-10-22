package com.teampacheworks.pachelauncher.sample

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.teampacheworks.launcher.LauncherConfig
import com.teampacheworks.launcher.LauncherHost
import com.teampacheworks.launcher.LauncherOption
import com.teampacheworks.launcher.LauncherOptionChoice
import com.teampacheworks.launcher.LauncherOptionControl
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
                // One host option of each control kind the library draws (docs/UI-SPEC.md "Host
                // options"). The sample game reads none of them; they are here so the rows can be
                // seen, and so this module keeps compiling against the LauncherOption API.
                gameOptions = listOf(
                    LauncherOption(
                        key = "scale",
                        label = "Render scale",
                        hint = "",
                        choices = listOf("50", "75", "100").map { LauncherOptionChoice(it, "$it%") },
                        defaultValue = "100",
                        control = LauncherOptionControl.SLIDER
                    )
                ),
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
