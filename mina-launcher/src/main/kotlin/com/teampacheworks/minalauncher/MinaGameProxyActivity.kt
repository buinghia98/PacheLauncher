package com.teampacheworks.minalauncher

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.teampacheworks.launcher.log.LauncherLog

/**
 * Stands in for docs/INTEGRATION.md §5's "game Activity", adapted for a two-APK setup instead of
 * that guide's single-APK assumption.
 *
 * PacheLauncher's [com.teampacheworks.launcher.LauncherConfig.gameActivityClass] wants a
 * compile-time `Class<Activity>` in *this* app, because the library has no opinion about the
 * game's engine, ABI set, or process model (README.md "Constraints") and is not allowed to take a
 * native dependency of its own. Mina the Hollower's real Activity -
 * `com.teampacheworks.mina.MainActivity`, an SDLActivity subclass with its own native `.so`
 * payload - is built as a completely separate Gradle project under `android/` with its own
 * applicationId, by a different pipeline than this launcher. There is no shared class to
 * reference at compile time and there should never be one (that would require this pure-Kotlin
 * launcher module to depend on an APK carrying native libraries).
 *
 * So this Activity is the bridge: PLAY starts it like any other game Activity, and all it does is
 * fire an explicit, cross-package [Intent] at the other app's launch Activity by package/class
 * name and immediately finish. If that APK isn't installed yet (e.g. still mid-build), it reports
 * that clearly instead of crashing.
 */
class MinaGameProxyActivity : AppCompatActivity() {

    companion object {
        private const val GAME_PACKAGE = "com.teampacheworks.mina"
        private const val GAME_ACTIVITY = "com.teampacheworks.mina.MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val target = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(GAME_PACKAGE, GAME_ACTIVITY)
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // The three standard extras a PacheLauncher game Activity expects
            // (docs/INTEGRATION.md §5) are copied through in case a future build of
            // com.teampacheworks.mina.MainActivity chooses to read them; today's SDLActivity
            // scaffold does not, and simply ignores unknown extras.
            putExtras(intent)
        }

        try {
            startActivity(target)
        } catch (e: ActivityNotFoundException) {
            LauncherLog.e("MinaGameProxy", "Mina the Hollower ($GAME_PACKAGE) is not installed on this device", e)
            Toast.makeText(
                this,
                "Mina the Hollower is not installed yet ($GAME_PACKAGE)",
                Toast.LENGTH_LONG
            ).show()
        } finally {
            finish()
        }
    }
}
