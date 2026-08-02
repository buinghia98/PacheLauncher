package com.teampacheworks.launcher

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.teampacheworks.launcher.log.CrashReporter
import com.teampacheworks.launcher.log.LauncherLog
import com.teampacheworks.launcher.log.LogExport

/**
 * The shared launcher main screen (docs/UI-SPEC.md "Main screen").
 *
 * Everything a game process needs is persisted in SharedPreferences here (the launcher's own
 * process) and handed to [LauncherConfig.gameActivityClass] as **Intent extras** - two processes
 * do not share SharedPreferences reliably, so extras are the only supported channel when a host
 * runs its game in a separate process. Layout/typography here follow the Dustaet design standard
 * 1:1; only the theme's seed colour and [LauncherConfig]'s text/behaviour are per-host.
 *
 * Requires [LauncherHost.install] to have been called (typically from `Application.onCreate`)
 * before this Activity starts.
 */
class LauncherActivity : AppCompatActivity() {

    private lateinit var config: LauncherConfig
    private lateinit var prefs: SharedPreferences

    private lateinit var aspectSpinner: Spinner
    private lateinit var fpsSwitch: MaterialSwitch
    private lateinit var debugSwitch: MaterialSwitch
    private lateinit var exportLogsButton: MaterialButton

    /** Current aspect as an index into [LauncherConfig.aspectOptions]. */
    private var aspectIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = LauncherHost.config
        setContentView(R.layout.activity_launcher)

        prefs = getSharedPreferences(config.prefsName, Context.MODE_PRIVATE)

        findViewById<ImageView>(R.id.pl_icon).setImageResource(config.iconRes)
        findViewById<TextView>(R.id.pl_title).text = config.gameTitle
        findViewById<TextView>(R.id.pl_subtitle).text = config.gameSubtitle
        findViewById<TextView>(R.id.pl_footer).text = config.footerText

        aspectSpinner = findViewById(R.id.pl_aspect_spinner)
        fpsSwitch = findViewById(R.id.pl_fps_switch)
        debugSwitch = findViewById(R.id.pl_debug_switch)
        exportLogsButton = findViewById(R.id.pl_export_logs_button)
        val play = findViewById<MaterialButton>(R.id.pl_play_button)
        val cloud = findViewById<MaterialButton>(R.id.pl_cloud_backup_button)

        val aspectValues = config.aspectOptions.map { it.value }
        aspectIndex = aspectValues.indexOf(prefs.getString(KEY_ASPECT, config.defaultAspectValue))
            .let { if (it < 0) 0 else it }
        // Plain framework Spinner (docs/UI-SPEC.md), not an exposed dropdown.
        aspectSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            config.aspectOptions.map { it.label }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        aspectSpinner.setSelection(aspectIndex)
        aspectSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                aspectIndex = position
                persist()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        fpsSwitch.isChecked = prefs.getBoolean(KEY_FPS, false)
        debugSwitch.isChecked = prefs.getBoolean(KEY_DEBUG, false)
        fpsSwitch.setOnCheckedChangeListener { _, _ -> persist() }
        debugSwitch.setOnCheckedChangeListener { _, _ -> persist() }

        play.setOnClickListener { launchGame(currentAspect(), fpsSwitch.isChecked, debugSwitch.isChecked) }
        cloud.setOnClickListener { startActivity(Intent(this, CloudBackupActivity::class.java)) }
        findViewById<MaterialButton>(R.id.pl_save_management_button).setOnClickListener {
            startActivity(Intent(this, SaveManagementActivity::class.java))
        }
        exportLogsButton.setOnClickListener { onExportLogsClicked() }

        // Smoke-test / shortcut hook: the game activity is typically not exported, so
        // `adb shell am start` cannot target it directly. Launching the launcher with
        //   --ez autoplay true [-e aspect 16:9] [--ez fps true] [--ez debug true]
        // starts the game straight away with those settings.
        val i = intent
        if (i != null && i.getBooleanExtra(EXTRA_AUTOPLAY, false)) {
            val aspect = i.getStringExtra(EXTRA_ASPECT_SHORT)?.takeIf { it in aspectValues }
                ?: currentAspect()
            val fps = i.getBooleanExtra(EXTRA_FPS_SHORT, fpsSwitch.isChecked)
            val debug = i.getBooleanExtra(EXTRA_DEBUG_SHORT, debugSwitch.isChecked)
            Log.i(LauncherLog.tag, "autoplay requested aspect=$aspect fps=$fps debug=$debug")
            launchGame(aspect, fps, debug)
        }
    }

    /**
     * Dumps whatever is currently in [LauncherLog]'s in-memory ring to
     * `Downloads/<downloadsFolderName>`, regardless of whether debug logging is on - a player who
     * just flipped it on to reproduce a problem should not have to wait for a crash to grab a log.
     */
    private fun onExportLogsClicked() {
        exportLogsButton.isEnabled = false
        try {
            when (val result = LogExport.exportSessionLog(this)) {
                is LogExport.SessionExportResult.Exported ->
                    Toast.makeText(
                        this, getString(R.string.pl_export_logs_ok, result.count, config.downloadsFolderName),
                        Toast.LENGTH_LONG
                    ).show()
                LogExport.SessionExportResult.Empty ->
                    Toast.makeText(this, R.string.pl_export_logs_none, Toast.LENGTH_LONG).show()
                is LogExport.SessionExportResult.Failed ->
                    Toast.makeText(this, getString(R.string.pl_export_logs_failed, result.reason), Toast.LENGTH_LONG).show()
            }
        } finally {
            exportLogsButton.isEnabled = true
        }
    }

    /**
     * Design spec §4: if the host runs its game in a separate process
     * ([LauncherConfig.gameProcessSuffix] non-null), ask the platform how that process died last
     * time. A watermark in SharedPreferences keeps the same crash from being announced twice.
     */
    override fun onResume() {
        super.onResume()
        LauncherLog.enabled = prefs.getBoolean(KEY_DEBUG, false)
        val suffix = config.gameProcessSuffix ?: return
        val gameProcess = "$packageName$suffix"
        val lastSeen = prefs.getLong(KEY_LAST_EXIT_SEEN, 0L)
        val notice = CrashReporter.checkGameProcessExit(this, gameProcess, lastSeen)
        val newest = CrashReporter.latestExitTimestamp(this, gameProcess)
        if (newest > lastSeen) prefs.edit().putLong(KEY_LAST_EXIT_SEEN, newest).apply()
        if (notice != null) {
            val text = if (notice.savedTo != null) {
                getString(R.string.pl_crash_notice, config.downloadsFolderName)
            } else {
                getString(R.string.pl_crash_notice_nolog)
            }
            Log.w(LauncherLog.tag, "abnormal game process exit -> ${notice.summary} (log: ${notice.savedTo})")
            Snackbar.make(findViewById(android.R.id.content), text, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun currentAspect(): String = config.aspectOptions[aspectIndex].value

    private fun persist() {
        prefs.edit()
            .putString(KEY_ASPECT, currentAspect())
            .putBoolean(KEY_FPS, fpsSwitch.isChecked)
            .putBoolean(KEY_DEBUG, debugSwitch.isChecked)
            .apply()
        LauncherLog.enabled = debugSwitch.isChecked
    }

    private fun launchGame(aspect: String, fps: Boolean, debug: Boolean) {
        Log.i(LauncherLog.tag, "starting game activity aspect=$aspect fps=$fps debug=$debug")
        val intent = Intent(this, config.gameActivityClass)
            .putExtra(LauncherContract.EXTRA_ASPECT, aspect)
            .putExtra(LauncherContract.EXTRA_SHOW_FPS, fps)
            .putExtra(LauncherContract.EXTRA_DEBUG_LOG, debug)
        config.buildGameIntentExtras(intent, aspect, fps, debug)
        startActivity(intent)
    }

    companion object {
        const val KEY_ASPECT = "aspect"
        const val KEY_FPS = "show_fps"
        const val KEY_DEBUG = "debug_logging"

        /** Watermark so an abnormal game-process exit is announced exactly once. */
        const val KEY_LAST_EXIT_SEEN = "last_exit_seen"

        private const val EXTRA_AUTOPLAY = "autoplay"
        private const val EXTRA_ASPECT_SHORT = "aspect"
        private const val EXTRA_FPS_SHORT = "fps"
        private const val EXTRA_DEBUG_SHORT = "debug"
    }
}
