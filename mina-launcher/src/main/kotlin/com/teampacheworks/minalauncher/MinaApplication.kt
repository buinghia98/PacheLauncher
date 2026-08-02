package com.teampacheworks.minalauncher

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.teampacheworks.launcher.LauncherConfig
import com.teampacheworks.launcher.LauncherHost
import com.teampacheworks.launcher.log.CrashReporter
import com.teampacheworks.launcher.log.LauncherLog

/**
 * Installs this app's [LauncherConfig] (docs/INTEGRATION.md step 2). Everything here runs in a
 * single process - unlike the sample's ":game" split, [MinaGameProxyActivity] never renders
 * anything of its own; it only fires an Intent at the separately-installed
 * `com.teampacheworks.minathehollower` APK and finishes, so there is no second process to isolate or to
 * check for an abnormal exit ([LauncherConfig.gameProcessSuffix] = null).
 */
class MinaApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        LauncherLog.tag = "MinaLauncher"
        CrashReporter.install(this, getProcessName())

        LauncherHost.install(
            LauncherConfig(
                gameTitle = "Mina the Hollower",
                gameSubtitle = "Android port · Team Pache Works",
                appLabel = "Mina the Hollower",
                footerText = "Team Pache Works · personal build, not for distribution",
                downloadsFolderName = "MinaTheHollower",
                cloudAppId = "minathehollower",
                savePatterns = listOf("*.sav", "*.dat", "save_*", "profile*"),
                saveExcludeNames = setOf("settings.cfg", "config.cfg"),
                gameActivityClass = MinaGameProxyActivity::class.java,
                appVersionName = BuildConfig.VERSION_NAME,
                iconRes = R.mipmap.ic_launcher,
                // The real game runs as a wholly separate installed app/process
                // (com.teampacheworks.minathehollower), not a ":game" child process of this one - nothing
                // for LauncherActivity's per-resume exit-reason check to look at.
                gameProcessSuffix = null
            )
        )

        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
