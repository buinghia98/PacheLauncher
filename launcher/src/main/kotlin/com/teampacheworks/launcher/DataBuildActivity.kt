package com.teampacheworks.launcher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.text.format.Formatter
import android.view.View
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.teampacheworks.launcher.databuild.BuildOption
import com.teampacheworks.launcher.databuild.BuildProgress
import com.teampacheworks.launcher.databuild.BuildRequest
import com.teampacheworks.launcher.databuild.BuildVariant
import com.teampacheworks.launcher.databuild.DataBuildCancelled
import com.teampacheworks.launcher.databuild.DataBuildStorage
import com.teampacheworks.launcher.databuild.Inspection
import com.teampacheworks.launcher.databuild.SafTree
import com.teampacheworks.launcher.log.LauncherLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Build the game's data folder on the device, from the player's own PC game files.
 *
 * Three states, one screen: **choose** a folder, **decide** what to build from what was found in it,
 * then **watch** it happen. They are three states rather than three screens because the middle one
 * is a direct answer to the first and the player has to be able to see both at once -- "Zagreus'
 * Journey is greyed out" only means anything next to "no Hades I install in that folder".
 *
 * The work is entirely [com.teampacheworks.launcher.databuild.DataBuilder]'s. This class owns the
 * thread, the wake lock, the cancel, and the rule that a half-built tree stays marked as one.
 */
class DataBuildActivity : AppCompatActivity() {

    private val config get() = requireNotNull(LauncherHost.config.dataBuild)

    private lateinit var status: TextView
    private lateinit var planCard: MaterialCardView
    private lateinit var headline: TextView
    private lateinit var details: TextView
    private lateinit var variantGroup: RadioGroup
    private lateinit var optionHost: LinearLayout
    private lateinit var sourceGroup: RadioGroup
    private lateinit var phase: TextView
    private lateinit var counts: TextView
    private lateinit var bar: LinearProgressIndicator
    private lateinit var current: TextView
    private lateinit var startButton: MaterialButton
    private lateinit var chooseButton: MaterialButton
    private lateinit var cancelButton: MaterialButton
    private lateinit var hint: TextView
    private lateinit var chooseArchiveButton: MaterialButton
    private lateinit var variantTitle: TextView
    private lateinit var sourceTitle: TextView
    private lateinit var consumeSwitch: MaterialSwitch

    private val cancelled = AtomicBoolean(false)
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var backGuard: OnBackPressedCallback? = null

    private var tree: SafTree? = null
    /** The picked archive ([DataBuildConfig.archiveMimeTypes]); exclusive with [tree]. */
    private var archive: Uri? = null
    private var variants: List<BuildVariant> = emptyList()
    private var options: List<BuildOption> = emptyList()
    /** Live selection per option key, in declaration order. Read straight into [BuildRequest]. */
    private val selections = LinkedHashMap<String, MutableList<String>>()
    /** The drawn rows per option key, so a single-select group can clear its siblings. */
    private val rows = LinkedHashMap<String, MutableList<Pair<String, CompoundButton>>>()

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) inspect(uri)
    }

    private val pickArchive = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) inspectArchive(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (LauncherHost.config.dataBuild == null) { finish(); return }
        setContentView(R.layout.activity_data_build)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        status = findViewById(R.id.pl_build_status)
        planCard = findViewById(R.id.pl_build_plan_card)
        headline = findViewById(R.id.pl_build_headline)
        details = findViewById(R.id.pl_build_details)
        variantGroup = findViewById(R.id.pl_build_variants)
        optionHost = findViewById(R.id.pl_build_options)
        sourceGroup = findViewById(R.id.pl_build_source_modes)
        phase = findViewById(R.id.pl_build_phase)
        counts = findViewById(R.id.pl_build_counts)
        bar = findViewById(R.id.pl_build_bar)
        current = findViewById(R.id.pl_build_current)
        startButton = findViewById(R.id.pl_build_start)
        chooseButton = findViewById(R.id.pl_build_choose)
        cancelButton = findViewById(R.id.pl_build_cancel)
        hint = findViewById(R.id.pl_build_hint)
        chooseArchiveButton = findViewById(R.id.pl_build_choose_archive)
        variantTitle = findViewById(R.id.pl_build_variant_title)
        sourceTitle = findViewById(R.id.pl_build_source_title)
        consumeSwitch = findViewById(R.id.pl_build_consume_switch)

        status.text = getString(R.string.pl_build_intro, config.sourceFolderName)
        hint.text = getString(
            if (config.requiresNetwork) R.string.pl_build_hint_network else R.string.pl_build_hint
        )
        chooseButton.setOnClickListener { pickFolder.launch(null) }
        if (config.archiveMimeTypes.isNotEmpty()) {
            chooseArchiveButton.visibility = View.VISIBLE
            chooseArchiveButton.setOnClickListener {
                pickArchive.launch(config.archiveMimeTypes.toTypedArray())
            }
        }
        cancelButton.setOnClickListener { requestCancel() }
        startButton.setOnClickListener { start() }

        addSourceModes()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = requestCancel()
        }.also { backGuard = it })

        if (DataBuildStorage.buildInProgress(this, config)) {
            status.setText(R.string.pl_build_resume_available)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (worker != null) requestCancel() else finish()
        return true
    }

    // ------------------------------------------------------------------ choose and inspect

    private fun inspect(uri: Uri) {
        // Without this the grant dies with the activity, and a build that outlives a configuration
        // change loses the folder it is reading from mid-way.
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (t: Throwable) {
            LauncherLog.w("databuild") { "no persistable permission on the picked folder: $t" }
        }
        planCard.visibility = View.GONE
        startButton.visibility = View.GONE
        setChoosersEnabled(false)
        status.setText(R.string.pl_build_inspecting)
        val picked = SafTree(this, uri)
        Thread {
            val outcome = runCatching { config.builder.inspect(this, picked, { false }) }
            runOnUiThread {
                setChoosersEnabled(true)
                outcome.onSuccess { render(picked, null, it) }.onFailure {
                    LauncherLog.e("databuild", "inspection failed", it)
                    status.text = getString(
                        R.string.pl_build_inspect_failed, it.message ?: it.javaClass.simpleName
                    )
                }
            }
        }.start()
    }

    /** Same as [inspect], for one archive file. Read-only, so no persistable grant is needed. */
    private fun inspectArchive(uri: Uri) {
        planCard.visibility = View.GONE
        startButton.visibility = View.GONE
        setChoosersEnabled(false)
        status.setText(R.string.pl_build_inspecting)
        Thread {
            val outcome = runCatching { config.builder.inspectArchive(this, uri, { false }) }
            runOnUiThread {
                setChoosersEnabled(true)
                outcome.onSuccess { render(null, uri, it) }.onFailure {
                    LauncherLog.e("databuild", "archive inspection failed", it)
                    status.text = getString(
                        R.string.pl_build_inspect_failed, it.message ?: it.javaClass.simpleName
                    )
                }
            }
        }.start()
    }

    private fun setChoosersEnabled(enabled: Boolean) {
        chooseButton.isEnabled = enabled
        chooseArchiveButton.isEnabled = enabled
    }

    private fun render(picked: SafTree?, pickedArchive: Uri?, inspection: Inspection) {
        if (inspection.problem != null) {
            tree = null
            archive = null
            renderOptions(emptyList())
            planCard.visibility = View.GONE
            startButton.visibility = View.GONE
            status.text = inspection.problem
            return
        }
        tree = picked
        archive = pickedArchive
        variants = inspection.variants
        headline.text = inspection.headline
        details.text = inspection.details.joinToString("\n")
        details.visibility = if (inspection.details.isEmpty()) View.GONE else View.VISIBLE

        variantGroup.removeAllViews()
        inspection.variants.forEachIndexed { index, variant ->
            variantGroup.addView(RadioButton(this).apply {
                id = View.generateViewId()
                tag = index
                isEnabled = variant.enabled
                text = buildString {
                    append(variant.label)
                    val note = if (variant.enabled) variant.hint else variant.disabledNote
                    if (note.isNotBlank()) { append('\n'); append(note) }
                }
                textSize = 14f
            })
        }
        // Pre-select the first row that can actually be built, so the common case is one tap.
        val firstEnabled = (0 until variantGroup.childCount)
            .firstOrNull { variantGroup.getChildAt(it).isEnabled }
        if (firstEnabled != null) variantGroup.check(variantGroup.getChildAt(firstEnabled).id)
        // One unnamed variant is not a choice: draw nothing for it.
        val noChoice = inspection.variants.size == 1 && inspection.variants[0].label.isBlank()
        variantTitle.visibility = if (noChoice) View.GONE else View.VISIBLE
        variantGroup.visibility = if (noChoice) View.GONE else View.VISIBLE

        // Keep/consume only means something for a folder; an archive is never consumed.
        if (config.consumeSourceSwitch) {
            sourceTitle.visibility = View.GONE
            sourceGroup.visibility = View.GONE
            consumeSwitch.visibility = if (picked != null) View.VISIBLE else View.GONE
            if (picked == null) consumeSwitch.isChecked = false
        }

        renderOptions(inspection.options)

        planCard.visibility = View.VISIBLE
        startButton.visibility = if (firstEnabled != null) View.VISIBLE else View.GONE
        status.text = if (firstEnabled != null) {
            getString(R.string.pl_build_ready)
        } else {
            getString(R.string.pl_build_nothing_buildable)
        }
    }

    /**
     * Draw each host-declared option group: a title, then checkboxes or radio buttons.
     *
     * Built in code rather than inflated, because how many groups there are and what is in them is
     * config. The selection map is kept alongside the views instead of being read back off them at
     * Start, so the empty-multi-select guard can run on every tick.
     */
    private fun renderOptions(declared: List<BuildOption>) {
        options = declared
        selections.clear()
        rows.clear()
        optionHost.removeAllViews()
        val dp = resources.displayMetrics.density

        for (option in declared) {
            val chosen = option.choices
                .filter { it.id in option.defaultSelected }
                .map { it.id }
                .toMutableList()
            if (!option.multiSelect && chosen.isEmpty()) {
                option.choices.firstOrNull()?.let { chosen += it.id }
            }
            selections[option.key] = chosen

            optionHost.addView(TextView(this).apply {
                text = option.title
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, (16 * dp).toInt(), 0, 0)
            })
            for (choice in option.choices) {
                val row: CompoundButton =
                    if (option.multiSelect) CheckBox(this) else RadioButton(this)
                row.text = buildString {
                    append(choice.label)
                    if (choice.hint.isNotBlank()) { append('\n'); append(choice.hint) }
                }
                row.textSize = 14f
                row.isChecked = choice.id in chosen
                rows.getOrPut(option.key) { ArrayList() } += choice.id to row
                row.setOnCheckedChangeListener { _, isChecked ->
                    val list = selections[option.key] ?: return@setOnCheckedChangeListener
                    if (option.multiSelect) {
                        if (isChecked) { if (choice.id !in list) list += choice.id }
                        else list -= choice.id
                    } else if (isChecked) {
                        // Radio semantics by hand: these are separate views rather than one
                        // RadioGroup, because a RadioGroup would not also serve the multi-select
                        // case and two render paths for one concept is how they drift.
                        list.clear(); list += choice.id
                        rows[option.key].orEmpty().forEach { (id, view) ->
                            if (id != choice.id && view.isChecked) view.isChecked = false
                        }
                    }
                    updateStartEnabled()
                }
                optionHost.addView(row)
            }
        }
        updateStartEnabled()
    }

    /** A multi-select group with nothing ticked would build nothing; refuse to start on it. */
    private fun updateStartEnabled() {
        val ok = options.none { it.multiSelect && selections[it.key].isNullOrEmpty() }
        startButton.isEnabled = ok
    }

    /**
     * Keep or consume. Asked here rather than decided by the framework because the answer is about
     * the *device*, not the game: consuming halves the free space a build needs and costs a second
     * transfer from the PC if the player later wants a different variant.
     */
    private fun addSourceModes() {
        if (config.consumeSourceSwitch) {
            sourceTitle.visibility = View.GONE
            sourceGroup.visibility = View.GONE
            consumeSwitch.isChecked = false
            return
        }
        sourceGroup.removeAllViews()
        listOf(
            R.string.pl_build_source_keep to R.string.pl_build_source_keep_hint,
            R.string.pl_build_source_consume to R.string.pl_build_source_consume_hint
        ).forEachIndexed { index, (label, note) ->
            sourceGroup.addView(RadioButton(this).apply {
                id = View.generateViewId()
                tag = index
                text = "${getString(label)}\n${getString(note, config.sourceFolderName)}"
                textSize = 14f
            })
        }
        sourceGroup.check(sourceGroup.getChildAt(0).id)
    }

    private fun selectedIndex(group: RadioGroup): Int =
        group.findViewById<View>(group.checkedRadioButtonId)?.tag as? Int ?: 0

    // ------------------------------------------------------------------ building

    private fun start() {
        val picked = tree
        val pickedArchive = archive
        if (picked == null && pickedArchive == null) return
        if (worker != null) return
        val variant = variants.getOrNull(selectedIndex(variantGroup)) ?: return
        val consume = picked != null && if (config.consumeSourceSwitch) {
            consumeSwitch.isChecked
        } else {
            selectedIndex(sourceGroup) == 1
        }

        cancelled.set(false)
        planCard.visibility = View.GONE
        startButton.visibility = View.GONE
        chooseButton.visibility = View.GONE
        chooseArchiveButton.visibility = View.GONE
        cancelButton.visibility = View.VISIBLE
        cancelButton.isEnabled = true
        phase.visibility = View.VISIBLE
        counts.visibility = View.VISIBLE
        current.visibility = View.VISIBLE
        bar.visibility = View.VISIBLE
        bar.isIndeterminate = true
        phase.setText(R.string.pl_build_starting)
        status.setText(R.string.pl_build_running)
        backGuard?.isEnabled = true

        // A build takes tens of minutes. Partial lock: the CPU keeps going, the screen is free to
        // go off, and it is released on every exit path including cancellation.
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pache:data-build")
            .also { it.acquire(8 * 60 * 60 * 1000L) }

        val started = System.currentTimeMillis()
        var lastTick = 0L
        worker = Thread {
            try {
                DataBuildStorage.begin(this, config, variant.id)
                val summary = config.builder.build(
                    this,
                    BuildRequest(
                        picked, variant.id, consume, selections.mapValues { it.value.toList() },
                        pickedArchive
                    ),
                    cancelled::get
                ) { p ->
                    val now = System.currentTimeMillis()
                    if (now - lastTick >= 120) { lastTick = now; runOnUiThread { render(p) } }
                }
                DataBuildStorage.finish(this, config, variant.id, summary)
                val seconds = (System.currentTimeMillis() - started) / 1000
                LauncherLog.write("databuild", "build '${variant.id}' finished in ${seconds}s: $summary")
                runOnUiThread { done(summary, seconds) }
            } catch (t: Throwable) {
                runOnUiThread { failed(t) }
            } finally {
                runOnUiThread { release() }
            }
        }.also { it.start() }
    }

    private fun render(p: BuildProgress) {
        phase.text = p.phase
        val known = p.totalBytes > 0 || p.totalUnits > 0
        if (bar.isIndeterminate && known) bar.isIndeterminate = false
        if (!bar.isIndeterminate && !known) bar.isIndeterminate = true
        counts.text = when {
            p.totalBytes > 0 -> getString(
                R.string.pl_build_counts,
                NUMBER.format(p.units), NUMBER.format(p.totalUnits),
                Formatter.formatShortFileSize(this, p.bytes),
                Formatter.formatShortFileSize(this, p.totalBytes)
            )
            p.totalUnits > 0 -> getString(
                R.string.pl_build_counts_files, NUMBER.format(p.units), NUMBER.format(p.totalUnits)
            )
            else -> ""
        }
        counts.visibility = if (counts.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        current.text = p.label
        val fraction = when {
            p.totalBytes > 0 -> p.bytes.toDouble() / p.totalBytes
            p.totalUnits > 0 -> p.units.toDouble() / p.totalUnits
            else -> 0.0
        }
        if (known) bar.setProgressCompat((1000 * fraction).toInt().coerceIn(0, 1000), true)
    }

    private fun done(summary: List<String>, seconds: Long) {
        bar.visibility = View.GONE
        current.visibility = View.GONE
        counts.visibility = View.GONE
        phase.visibility = View.GONE
        val text = buildString {
            append(getString(R.string.pl_build_done, seconds / 60, seconds % 60))
            summary.forEach { append("\n"); append(it) }
        }
        status.text = text
        AlertDialog.Builder(this)
            .setTitle(R.string.pl_build_done_title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .show()
    }

    private fun failed(t: Throwable) {
        bar.visibility = View.GONE
        current.visibility = View.GONE
        counts.visibility = View.GONE
        phase.visibility = View.GONE
        if (t is DataBuildCancelled) {
            status.setText(R.string.pl_build_cancelled)
            return
        }
        LauncherLog.e("databuild", "build failed", t)
        status.text = getString(R.string.pl_build_failed, t.message ?: t.javaClass.simpleName)
    }

    private fun requestCancel() {
        if (worker == null) { finish(); return }
        AlertDialog.Builder(this)
            .setTitle(R.string.pl_build_cancel_title)
            .setMessage(R.string.pl_build_cancel_body)
            .setNegativeButton(R.string.pl_build_keep_going, null)
            .setPositiveButton(R.string.pl_build_cancel) { _, _ ->
                cancelled.set(true)
                cancelButton.isEnabled = false
                status.setText(R.string.pl_build_cancelling)
            }.show()
    }

    private fun release() {
        worker = null
        backGuard?.isEnabled = false
        chooseButton.visibility = View.VISIBLE
        if (config.archiveMimeTypes.isNotEmpty()) chooseArchiveButton.visibility = View.VISIBLE
        cancelButton.visibility = View.GONE
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        // The worker holds `this` as a Context. Signalling here stops it at the next safe boundary,
        // which is a resumable state by construction -- the sentinel survives to say so.
        cancelled.set(true)
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private companion object {
        val NUMBER: java.text.NumberFormat = java.text.NumberFormat.getIntegerInstance()
    }
}
