package com.teampacheworks.launcher

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.View
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
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
import com.teampacheworks.launcher.assets.AssetInstallState
import com.teampacheworks.launcher.assets.AssetStorage
import com.teampacheworks.launcher.gpu.GpuDriverStorage
import com.teampacheworks.launcher.mods.ModEnabledStore

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

    /**
     * Current selection per [LauncherConfig.gameOptions] entry, parallel to that list. Held as
     * indices for the same reason [aspectIndex] is: a Spinner speaks positions, and the value
     * behind a position is whatever the config says it is.
     */
    private var optionIndices = IntArray(0)
    private var assetSpinner: Spinner? = null
    private var assetState: AssetInstallState? = null
    private var assetValue: String? = null
    private var suppressAssetSelection = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = LauncherHost.config
        setContentView(R.layout.activity_launcher)

        prefs = getSharedPreferences(config.prefsName, Context.MODE_PRIVATE)

        findViewById<ImageView>(R.id.pl_icon).setImageResource(config.iconRes)
        findViewById<TextView>(R.id.pl_title).text = config.gameTitle
        // Subtitle and footer are host copy, and a host that supplies none gets no line rather than
        // an empty one -- the same rule every hint below follows (OptionRows "blank text is no
        // element").
        refreshSubtitle()
        OptionRows.goneIfBlank(findViewById(R.id.pl_footer), config.footerText)

        aspectSpinner = findViewById(R.id.pl_aspect_spinner)
        if (!config.showAspectRatio) {
            findViewById<View>(R.id.pl_aspect_label).visibility = View.GONE
            aspectSpinner.visibility = View.GONE
            findViewById<View>(R.id.pl_aspect_hint).visibility = View.GONE
        } else {
            OptionRows.goneIfBlank(
                findViewById(R.id.pl_aspect_hint), getText(R.string.pl_aspect_hint), aspectSpinner
            )
        }

        // The four hint/notice lines that live in the layout XML rather than in a built row. Each
        // hands its bottom margin to the control above it when the host empties the string, so the
        // card stays a card instead of growing a gap where the line was.
        // Where the logs land is host-configured, so the line naming that folder is formatted here
        // rather than being a static resource that would have to name one port's folder.
        OptionRows.goneIfBlank(
            findViewById(R.id.pl_debug_logging_hint),
            getString(R.string.pl_debug_logging_hint, config.downloadsFolderName),
            findViewById(R.id.pl_debug_switch)
        )
        OptionRows.goneIfBlank(
            findViewById(R.id.pl_show_fps_hint), getText(R.string.pl_show_fps_hint),
            findViewById(R.id.pl_fps_switch)
        )
        // The assets hint describes the Manage assets BUTTON, so it follows that button's fate: a
        // host with no asset management gets neither, rather than a paragraph about a control that
        // is not on the card.
        if (config.assetManagement == null) {
            findViewById<View>(R.id.pl_assets_hint).visibility = View.GONE
        } else {
            OptionRows.goneIfBlank(
                findViewById(R.id.pl_assets_hint), getText(R.string.pl_manage_assets_hint)
            )
        }
        OptionRows.goneIfBlank(
            findViewById(R.id.pl_save_and_cloud_notice), getText(R.string.pl_save_and_cloud_notice)
        )
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
        // After the two switches are restored: a Spinner's first onItemSelected is dispatched on
        // the next layout pass, but persist() writes every control at once, so building the
        // option rows before the switches hold their persisted values is a needless way to make
        // that ordering load-bearing.
        val optionsContainer = findViewById<ViewGroup>(R.id.pl_options_container)
        buildOptionRows(optionsContainer)
        buildAssetQualityRow(optionsContainer)
        fpsSwitch.setOnCheckedChangeListener { _, _ -> persist() }
        debugSwitch.setOnCheckedChangeListener { _, _ -> persist() }

        play.setOnClickListener { launchGame(currentAspect(), fpsSwitch.isChecked, debugSwitch.isChecked) }
        cloud.setOnClickListener { startActivity(Intent(this, CloudBackupActivity::class.java)) }
        findViewById<MaterialButton>(R.id.pl_save_management_button).setOnClickListener {
            startActivity(Intent(this, SaveManagementActivity::class.java))
        }
        exportLogsButton.setOnClickListener { onExportLogsClicked() }
        // The Manage game data card itself is unconditional now (Manage saves and Cloud sync are
        // always there); only the two host-configured buttons on it come and go.
        config.assetManagement?.let {
            findViewById<MaterialButton>(R.id.pl_manage_assets_button).apply {
                visibility = View.VISIBLE
                setOnClickListener {
                    startActivity(Intent(this@LauncherActivity, AssetManagementActivity::class.java))
                }
            }
        }
        config.gpuDriverManagement?.let {
            findViewById<MaterialButton>(R.id.pl_manage_gpu_drivers_button).apply {
                visibility = View.VISIBLE
                setOnClickListener {
                    startActivity(Intent(this@LauncherActivity, GpuDriverManagementActivity::class.java))
                }
            }
        }
        config.modManagement?.let {
            findViewById<MaterialButton>(R.id.pl_manage_mods_button).apply {
                visibility = View.VISIBLE
                setOnClickListener {
                    startActivity(Intent(this@LauncherActivity, ModManagementActivity::class.java))
                }
            }
        }
        buildOptionScreenButtons(findViewById(R.id.pl_option_screens_container))

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
            // Host options join the same shortcut vocabulary: `-e opt_<key> <value>` overrides one
            // for this launch only (nothing is persisted), so a smoke test can sweep an option
            // without touching what the player picked.
            val options = config.gameOptions.mapIndexed { index, option ->
                val override = i.getStringExtra(option.prefsKey)
                    ?.takeIf { v -> option.choices.any { it.value == v } }
                override ?: option.choices[optionIndices[index]].value
            }
            Log.i(LauncherLog.tag, "autoplay requested aspect=$aspect fps=$fps debug=$debug " +
                "options=$options")
            launchGame(aspect, fps, debug, options)
        }
    }

    /**
     * Materialises [LauncherConfig.gameOptions] into the Video settings card. Built in code rather than
     * in the layout XML because the row count is per-host; the geometry, sizes and control types
     * are hardcoded to the aspect row's so a host cannot drift from docs/UI-SPEC.md by declaring
     * an option.
     */
    private fun buildOptionRows(container: ViewGroup) {
        optionIndices = IntArray(config.gameOptions.size)
        config.gameOptions.forEachIndexed { index, option ->
            // Read for EVERY option, drawn only for the ones that stayed on the card: the launch
            // handoff below is over the whole list, so an option living on a sub-screen still needs
            // its current value in hand.
            optionIndices[index] = option.indexOf(prefs.getString(option.prefsKey, option.defaultValue))
            if (screenFor(option) != null) return@forEachIndexed

            OptionRows.add(this, container, option, optionIndices[index]) { position ->
                optionIndices[index] = position
                persist()
            }
        }
    }

    /** The sub-screen [option] was moved onto, or null while it is a Settings-card row. */
    private fun screenFor(option: LauncherOption): LauncherOptionScreen? =
        option.screenKey?.let { key -> config.optionScreens.firstOrNull { it.key == key } }

    /**
     * One outlined navigation button per [LauncherConfig.optionScreens] entry that actually has
     * options pointing at it, in the same shape as the Manage mods / driver buttons on the other
     * cards. A declared screen with no options is silently skipped rather than opening an empty
     * page - and a host where that leaves NO buttons at all gets no Controls card, because a
     * header over an empty column is worse than an absent section.
     */
    private fun buildOptionScreenButtons(container: ViewGroup) {
        for (screen in config.optionScreens) {
            if (config.gameOptions.none { it.screenKey == screen.key }) continue
            val button = layoutInflater.inflate(R.layout.pl_option_screen_button, container, false)
                as MaterialButton
            button.text = screen.title
            button.setOnClickListener {
                startActivity(
                    Intent(this, OptionScreenActivity::class.java)
                        .putExtra(OptionScreenActivity.EXTRA_SCREEN_KEY, screen.key)
                )
            }
            container.addView(button)
        }
        findViewById<View>(R.id.pl_controls_card).visibility =
            if (container.childCount > 0) View.VISIBLE else View.GONE
    }

    /**
     * Re-reads the options that live on a sub-screen. Those are persisted by
     * [OptionScreenActivity] directly, so without this the stale in-memory index would be written
     * back over the player's change the next time anything on this screen calls [persist].
     */
    private fun refreshScreenOptionIndices() {
        config.gameOptions.forEachIndexed { index, option ->
            if (screenFor(option) == null) return@forEachIndexed
            if (index < optionIndices.size) {
                optionIndices[index] = option.indexOf(prefs.getString(option.prefsKey, option.defaultValue))
            }
        }
    }

    private fun buildAssetQualityRow(container: ViewGroup) {
        val assets = config.assetManagement ?: return
        val dp = resources.displayMetrics.density
        val assetHint = getString(R.string.pl_asset_quality_hint)
        assetValue = prefs.getString(assets.prefsKey, assets.defaultTierValue)
            ?.takeIf { assets.tier(it) != null } ?: assets.defaultTierValue
        container.addView(TextView(this).apply {
            text = getString(R.string.pl_asset_quality)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })
        assetSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@LauncherActivity, android.R.layout.simple_spinner_item,
                listOf(assets.highTier.label, assets.lowTier.label)
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(if (assetValue == assets.highTier.value) 0 else 1)
            isEnabled = false // enabled after the background install scan completes
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                    if (suppressAssetSelection) return
                    val candidate = if (position == 0) assets.highTier.value else assets.lowTier.value
                    val scanned = assetState ?: return
                    if (!scanned.tierInstalled(candidate, assets)) {
                        Toast.makeText(this@LauncherActivity, R.string.pl_asset_tier_missing, Toast.LENGTH_LONG).show()
                        showAssetSelection(assetValue ?: assets.defaultTierValue)
                        return
                    }
                    assetValue = candidate
                    persist()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = ((if (assetHint.isBlank()) 12 else 8) * dp).toInt() }
        }.also { container.addView(it) }
        if (assetHint.isBlank()) return
        container.addView(TextView(this).apply {
            text = assetHint
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            secondaryTextColor()?.let { setTextColor(it) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12 * dp).toInt() }
        })
    }

    private fun refreshAssetState() {
        val assets = config.assetManagement ?: return
        assetSpinner?.isEnabled = false
        Thread {
            val scanned = AssetStorage.scan(this, assets)
            runOnUiThread {
                assetState = scanned
                val current = assetValue ?: assets.defaultTierValue
                val valid = when {
                    scanned.tierInstalled(current, assets) -> current
                    scanned.high.installed -> assets.highTier.value
                    scanned.low.installed -> assets.lowTier.value
                    else -> current
                }
                if (valid != current) {
                    assetValue = valid
                    prefs.edit().putString(assets.prefsKey, valid).apply()
                    showAssetSelection(valid)
                }
                assetSpinner?.isEnabled = scanned.high.installed || scanned.low.installed
            }
        }.start()
    }

    private fun showAssetSelection(value: String) {
        val assets = config.assetManagement ?: return
        suppressAssetSelection = true
        assetSpinner?.setSelection(if (value == assets.highTier.value) 0 else 1)
        assetSpinner?.post { suppressAssetSelection = false }
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
        refreshAssetState()
        refreshScreenOptionIndices()
        refreshSubtitle()
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

    /**
     * Draws the header subtitle: [LauncherConfig.gameSubtitleProvider] when the host has one and it
     * answers, the static [LauncherConfig.gameSubtitle] otherwise.
     *
     * Run from `onCreate` AND `onResume` because everything a live subtitle can be about - which
     * game build is installed, whether the next launch is modded - is changed from Manage Assets and
     * Manage Mods, i.e. from screens the player returns to this one FROM. A provider that threw
     * would take the whole main screen down for a decorative line, so it is contained here and the
     * static text stands in.
     */
    private fun refreshSubtitle() {
        val live = try {
            config.gameSubtitleProvider?.invoke(this)
        } catch (t: Throwable) {
            Log.w(LauncherLog.tag, "gameSubtitleProvider failed - falling back to the static subtitle", t)
            null
        }
        val text = if (live.isNullOrBlank()) config.gameSubtitle else live
        OptionRows.goneIfBlank(findViewById(R.id.pl_subtitle), text)
    }

    private fun currentAspect(): String = config.aspectOptions[aspectIndex].value

    /** Selected value per [LauncherConfig.gameOptions] entry, parallel to that list. */
    private fun currentOptionValues(): List<String> =
        config.gameOptions.mapIndexed { i, option -> option.choices[optionIndices[i]].value }

    /**
     * `?android:attr/textColorSecondary` as the layout XML's hint lines resolve it. Read through
     * `obtainStyledAttributes` rather than `Theme.resolveAttribute` + `getColor` because the
     * attribute is a ColorStateList in every Material theme, and asking for it as a plain colour
     * int throws.
     */
    private fun secondaryTextColor(): android.content.res.ColorStateList? {
        val ta = obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary))
        val csl = ta.getColorStateList(0)
        ta.recycle()
        return csl
    }

    private fun persist() {
        val editor = prefs.edit()
            .putString(KEY_ASPECT, currentAspect())
            .putBoolean(KEY_FPS, fpsSwitch.isChecked)
            .putBoolean(KEY_DEBUG, debugSwitch.isChecked)
        config.gameOptions.forEachIndexed { i, option ->
            editor.putString(option.prefsKey, option.choices[optionIndices[i]].value)
        }
        config.assetManagement?.let { assets -> assetValue?.let { editor.putString(assets.prefsKey, it) } }
        editor.apply()
        LauncherLog.enabled = debugSwitch.isChecked
    }

    private fun launchGame(
        aspect: String,
        fps: Boolean,
        debug: Boolean,
        options: List<String> = currentOptionValues()
    ) {
        // An interrupted whole-install move leaves a tree that can look complete to a path check
        // while a file is still missing from the middle of it, so the sentinel outranks every other
        // gate and is tested first.
        config.deployImport?.let { deploy ->
            if (com.teampacheworks.launcher.deploy.DeployStorage.importInProgress(this, deploy)) {
                Toast.makeText(this, R.string.pl_assets_import_half_done, Toast.LENGTH_LONG).show()
                startActivity(Intent(this, DeployImportActivity::class.java))
                return
            }
        }
        config.assetManagement?.let { assets ->
            val selected = assetValue ?: assets.defaultTierValue
            if (!AssetStorage.isLaunchReady(this, assets, selected)) {
                Toast.makeText(this, R.string.pl_assets_launch_blocked, Toast.LENGTH_LONG).show()
                startActivity(Intent(this, AssetManagementActivity::class.java))
                return
            }
        }
        Log.i(LauncherLog.tag, "starting game activity aspect=$aspect fps=$fps debug=$debug " +
            "options=${config.gameOptions.map { it.key }.zip(options)}")
        val intent = Intent(this, config.gameActivityClass)
            .putExtra(LauncherContract.EXTRA_ASPECT, aspect)
            .putExtra(LauncherContract.EXTRA_SHOW_FPS, fps)
            .putExtra(LauncherContract.EXTRA_DEBUG_LOG, debug)
        config.gameOptions.forEachIndexed { i, option ->
            intent.putExtra(option.extraName, options[i])
        }
        config.assetManagement?.let { assets ->
            intent.putExtra(assets.extraName, assetValue ?: assets.defaultTierValue)
        }
        config.gpuDriverManagement?.let { gpu ->
            val selected = prefs.getString(gpu.prefsKey, GpuDriverStorage.SYSTEM)
            val driver = GpuDriverStorage.resolve(this, gpu, selected)
            if (driver == null) {
                intent.putExtra(LauncherContract.EXTRA_GPU_DRIVER, GpuDriverStorage.SYSTEM)
            } else {
                intent.putExtra(LauncherContract.EXTRA_GPU_DRIVER, if (driver.imported) "custom" else "bundled")
                intent.putExtra(LauncherContract.EXTRA_GPU_DRIVER_DIR, driver.directory.absolutePath)
                intent.putExtra(LauncherContract.EXTRA_GPU_DRIVER_LIB, driver.libraryName)
            }
        }
        // The mod SET is a file, not an extra, and it is rewritten here rather than only on toggle:
        // a re-stage of the game's mod tree, a restored backup or a hand-edit can all leave the
        // file disagreeing with what this launcher last persisted, and the launcher's prefs are the
        // authority. One small text write per launch.
        config.modManagement?.let { mods ->
            ModEnabledStore.write(this, prefs, mods)
            intent.putExtra(
                LauncherContract.EXTRA_MODS_ENABLED,
                // The disk-aware form: an install carrying no runnable mod arms nothing, so a
                // vanilla-only package boots vanilla even with the switch left on from before.
                if (ModEnabledStore.masterEnabled(this, prefs, mods)) "1" else "0"
            )
        }
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
