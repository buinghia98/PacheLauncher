package com.teampacheworks.launcher

import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.text.format.Formatter
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.teampacheworks.launcher.deploy.DeployStorage
import com.teampacheworks.launcher.log.LauncherLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Import-a-deploy-folder, with the determinate progress a multi-gigabyte move needs.
 *
 * The asset screen's `runBusy` - a raw Thread ending in a Toast - is right for a few hundred
 * megabytes and wrong for fourteen gigabytes: there is nothing to look at for an hour and no way to
 * stop. This screen exists for the numbers and the cancel button, and for nothing else; the actual
 * work is all in [DeployStorage].
 *
 * Cancelling is a first-class outcome, not an abort. The move leaves a source that is missing
 * exactly what the destination gained, which is precisely the state a second run resumes from, so
 * the cancel copy says "resume later" rather than warning about damage.
 */
class DeployImportActivity : AppCompatActivity() {

    private val config get() = requireNotNull(LauncherHost.config.deployImport)

    private lateinit var status: TextView
    private lateinit var counts: TextView
    private lateinit var current: TextView
    private lateinit var bar: LinearProgressIndicator
    private lateinit var choose: MaterialButton
    private lateinit var cancel: MaterialButton

    private val cancelled = AtomicBoolean(false)
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var backGuard: OnBackPressedCallback? = null

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) start(uri) else status.setText(R.string.pl_deploy_intro)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (LauncherHost.config.deployImport == null) { finish(); return }
        setContentView(R.layout.activity_deploy_import)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        status = findViewById(R.id.pl_deploy_status)
        counts = findViewById(R.id.pl_deploy_counts)
        current = findViewById(R.id.pl_deploy_current)
        bar = findViewById(R.id.pl_deploy_bar)
        choose = findViewById(R.id.pl_deploy_choose)
        cancel = findViewById(R.id.pl_deploy_cancel)

        choose.setOnClickListener { pickFolder.launch(null) }
        cancel.setOnClickListener { requestCancel() }

        // Leaving mid-move would orphan the worker with the screen gone, so Back asks first while
        // one is running. Any other time it is an ordinary Back.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = requestCancel()
        }.also { backGuard = it })

        if (DeployStorage.importInProgress(this, config)) {
            status.setText(R.string.pl_deploy_resume_available)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (worker != null) requestCancel() else finish()
        return true
    }

    private fun requestCancel() {
        if (worker == null) { finish(); return }
        AlertDialog.Builder(this)
            .setTitle(R.string.pl_deploy_cancel_title)
            .setMessage(R.string.pl_deploy_cancel_body)
            .setNegativeButton(R.string.pl_deploy_keep_going, null)
            .setPositiveButton(R.string.pl_deploy_cancel) { _, _ ->
                cancelled.set(true)
                cancel.isEnabled = false
                status.setText(R.string.pl_deploy_cancelling)
            }.show()
    }

    private fun start(uri: Uri) {
        if (worker != null) return
        cancelled.set(false)
        choose.visibility = View.GONE
        cancel.visibility = View.VISIBLE
        cancel.isEnabled = true
        counts.visibility = View.VISIBLE
        current.visibility = View.VISIBLE
        bar.visibility = View.VISIBLE
        bar.isIndeterminate = true
        status.setText(R.string.pl_deploy_verifying)
        backGuard?.isEnabled = true

        // A move that takes an hour must not be suspended halfway by the screen going off. The wake
        // lock is partial (the CPU, not the display) and is released on every exit path.
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pache:deploy-import")
            .also { it.acquire(6 * 60 * 60 * 1000L) }

        val started = System.currentTimeMillis()
        worker = Thread {
            try {
                val result = DeployStorage.import(this, config, uri, cancelled::get) { p ->
                    runOnUiThread { render(p) }
                }
                val seconds = (System.currentTimeMillis() - started) / 1000
                LauncherLog.write("deploy", "import finished in ${seconds}s: $result")
                runOnUiThread { done(result, seconds) }
            } catch (t: Throwable) {
                runOnUiThread { failed(t) }
            } finally {
                runOnUiThread { release() }
            }
        }.also { it.start() }
    }

    private fun render(p: DeployStorage.Progress) {
        if (bar.isIndeterminate && p.totalFiles > 0) {
            bar.isIndeterminate = false
            status.setText(R.string.pl_deploy_moving)
        }
        counts.text = getString(
            R.string.pl_deploy_counts,
            NUMBER.format(p.files), NUMBER.format(p.totalFiles),
            Formatter.formatShortFileSize(this, p.bytes),
            Formatter.formatShortFileSize(this, p.totalBytes)
        )
        current.text = p.label
        if (p.totalBytes > 0) {
            bar.setProgressCompat((1000.0 * p.bytes / p.totalBytes).toInt().coerceIn(0, 1000), true)
        }
    }

    private fun done(result: DeployStorage.Result, seconds: Long) {
        bar.visibility = View.GONE
        current.visibility = View.GONE
        val moved = Formatter.formatShortFileSize(this, result.movedBytes)
        val detail = buildString {
            append(getString(R.string.pl_deploy_done, NUMBER.format(result.movedFiles), moved,
                seconds / 60, seconds % 60))
            if (result.resumedFiles > 0) {
                append("\n"); append(getString(R.string.pl_deploy_done_resumed, NUMBER.format(result.resumedFiles)))
            }
            if (result.preservedFiles > 0) {
                append("\n"); append(getString(R.string.pl_deploy_done_preserved, NUMBER.format(result.preservedFiles)))
            }
            append("\n"); append(getString(R.string.pl_deploy_done_blocks, result.blocks.joinToString(", ")))
        }
        status.text = detail
        counts.visibility = View.GONE
        AlertDialog.Builder(this)
            .setTitle(R.string.pl_deploy_done_title)
            .setMessage(detail)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .show()
    }

    private fun failed(t: Throwable) {
        bar.visibility = View.GONE
        current.visibility = View.GONE
        counts.visibility = View.GONE
        choose.visibility = View.VISIBLE
        cancel.visibility = View.GONE
        if (t is DeployStorage.CancelledException) {
            status.setText(R.string.pl_deploy_cancelled)
            return
        }
        LauncherLog.e("deploy", "import failed", t)
        status.text = getString(R.string.pl_deploy_failed, t.message ?: t.javaClass.simpleName)
    }

    private fun release() {
        worker = null
        backGuard?.isEnabled = false
        choose.visibility = View.VISIBLE
        cancel.visibility = View.GONE
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        // The worker holds `this` as a Context; letting it outlive the activity would keep writing
        // into a destroyed screen. Signalling here makes it stop at the next file boundary, which
        // is a resumable state by construction.
        cancelled.set(true)
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private companion object {
        val NUMBER: java.text.NumberFormat = java.text.NumberFormat.getIntegerInstance()
    }
}
