package com.teampacheworks.launcher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.teampacheworks.launcher.assets.AssetComponentState
import com.teampacheworks.launcher.assets.AssetInstallState
import com.teampacheworks.launcher.assets.AssetManagementConfig
import com.teampacheworks.launcher.assets.AssetStorage
import com.teampacheworks.launcher.assets.AssetTierConfig
import com.teampacheworks.launcher.databuild.DataBuildStorage
import com.teampacheworks.launcher.deploy.DeployStorage

class AssetManagementActivity : AppCompatActivity() {
    private lateinit var config: AssetManagementConfig
    private lateinit var cards: LinearLayout
    private lateinit var summary: TextView
    private lateinit var deployButton: MaterialButton
    private lateinit var warning: TextView
    private var state: AssetInstallState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = LauncherHost.config.assetManagement ?: run { finish(); return }
        setContentView(R.layout.activity_asset_management)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        cards = findViewById(R.id.pl_asset_cards)
        summary = findViewById(R.id.pl_assets_summary)
        deployButton = findViewById(R.id.pl_import_deploy_button)
        warning = findViewById(R.id.pl_assets_warning)
        findViewById<MaterialButton>(R.id.pl_build_data_button).let { button ->
            if (LauncherHost.config.dataBuild == null) return@let
            button.visibility = View.VISIBLE
            button.setOnClickListener { startActivity(Intent(this, DataBuildActivity::class.java)) }
        }
        LauncherHost.config.deployImport?.let {
            deployButton.visibility = View.VISIBLE
            deployButton.setOnClickListener {
                startActivity(Intent(this, DeployImportActivity::class.java))
            }
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // The deploy import runs in another activity and changes everything this screen shows.
        if (state != null) refresh()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun refresh() {
        setBusy(getString(R.string.pl_assets_scanning))
        Thread {
            val scanned = AssetStorage.scan(this, config)
            // Ask the separate question "is it there but locked?" alongside "is it there?", because
            // a tree another uid wrote is 2770 <thatuid>:ext_data_rw and this app is not in that
            // group -- it stats fine and lists as null, which the scan above can only report as
            // "Not installed". That sent a previous session hunting for files that were present and
            // perfect.
            val diagnosis = LauncherHost.config.deployImport?.let { DeployStorage.diagnose(this, it) }
            val halfDone = LauncherHost.config.deployImport?.let {
                DeployStorage.importInProgress(this, it)
            } ?: false
            val halfBuilt = LauncherHost.config.dataBuild?.let {
                DataBuildStorage.buildInProgress(this, it)
            } ?: false
            runOnUiThread {
                state = scanned; render(scanned); renderWarning(diagnosis, halfDone, halfBuilt)
            }
        }.start()
    }

    private fun renderWarning(
        diagnosis: DeployStorage.Diagnosis?,
        halfDone: Boolean,
        halfBuilt: Boolean
    ) {
        val text = when {
            diagnosis?.isUnreadable == true -> getString(R.string.pl_assets_unreadable)
            halfDone -> getString(R.string.pl_assets_import_half_done)
            halfBuilt -> getString(R.string.pl_assets_build_half_done)
            else -> null
        }
        warning.text = text ?: ""
        warning.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun render(s: AssetInstallState) {
        val allReady = s.common.installed && (s.high.installed || s.low.installed)
        summary.text = getString(if (allReady) R.string.pl_assets_ready else R.string.pl_assets_incomplete)
        cards.removeAllViews()
        addCard(getString(R.string.pl_assets_common), s.common, null)
        addCard(config.highTier.label, s.high, config.highTier)
        addCard(config.lowTier.label, s.low, config.lowTier)
    }

    private fun addCard(title: String, component: AssetComponentState, tier: AssetTierConfig?) {
        val dp = resources.displayMetrics.density
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
        }
        body.addView(TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        body.addView(TextView(this).apply {
            text = if (component.installed) {
                getString(R.string.pl_assets_installed_size, Formatter.formatFileSize(this@AssetManagementActivity, component.bytes), component.files)
            } else getString(R.string.pl_assets_not_installed)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        if (tier != null && component.installed) {
            body.addView(MaterialButton(this).apply {
                text = getString(R.string.pl_delete_asset_tier)
                setOnClickListener { confirmDelete(tier) }
            })
        }
        cards.addView(MaterialCardView(this).apply {
            addView(body)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * dp).toInt() }
        })
    }

    private fun confirmDelete(tier: AssetTierConfig) {
        val current = state ?: return
        val otherInstalled = if (tier == config.highTier) current.low.installed else current.high.installed
        if (!otherInstalled) {
            AlertDialog.Builder(this).setTitle(R.string.pl_assets_cannot_delete)
                .setMessage(R.string.pl_assets_keep_one_tier).setPositiveButton(android.R.string.ok, null).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.pl_assets_delete_title, tier.label))
            .setMessage(R.string.pl_assets_delete_body)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.pl_delete) { _, _ ->
                // If this is selected, switch to the known-installed other tier before removal.
                val prefs = getSharedPreferences(LauncherHost.config.prefsName, Context.MODE_PRIVATE)
                if (prefs.getString(config.prefsKey, config.defaultTierValue) == tier.value) {
                    val other = if (tier == config.highTier) config.lowTier else config.highTier
                    prefs.edit().putString(config.prefsKey, other.value).apply()
                }
                runBusy(getString(R.string.pl_assets_deleting)) {
                    AssetStorage.deleteTier(this, config, tier)
                    0L
                }
            }.show()
    }

    private fun runBusy(label: String, work: () -> Long) {
        setBusy(label)
        Thread {
            try {
                val bytes = work()
                runOnUiThread {
                    if (bytes > 0) Toast.makeText(this, getString(R.string.pl_assets_imported, Formatter.formatFileSize(this, bytes)), Toast.LENGTH_LONG).show()
                    refresh()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.pl_assets_operation_failed, t.message ?: t.javaClass.simpleName), Toast.LENGTH_LONG).show()
                    refresh()
                }
            }
        }.start()
    }

    private fun setBusy(label: String) {
        summary.text = label
    }
}
