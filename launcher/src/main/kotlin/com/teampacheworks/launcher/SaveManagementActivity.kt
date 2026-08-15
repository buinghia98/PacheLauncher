package com.teampacheworks.launcher

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.teampacheworks.launcher.log.LauncherLog
import com.teampacheworks.launcher.save.SaveBundle
import com.teampacheworks.launcher.save.SaveImporter
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.io.File
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Save Management (design spec §2).
 *
 * Saves are written by whatever process the host's game runs in, but `filesDir` is ordinary app
 * storage shared by every process of the app, so the launcher sees exactly the same files. No IPC
 * is needed - the list is simply rebuilt in [onResume], which also covers "played, exited, came
 * back here".
 *
 * The toolbar is standalone; `setSupportActionBar` is never called (see the layout comment).
 */
class SaveManagementActivity : AppCompatActivity() {

    private lateinit var config: LauncherConfig
    private lateinit var saveDir: File

    private lateinit var listGroup: LinearLayout
    private lateinit var summary: TextView
    private lateinit var status: TextView
    private lateinit var busy: LinearProgressIndicator
    private lateinit var exportButton: MaterialButton
    private lateinit var importButton: MaterialButton

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "pl-save-io") }
    private val ui = Handler(Looper.getMainLooper())

    private val exportPicker =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) runExport(uri) else setStatus(getString(R.string.pl_export_cancelled))
        }

    // */* on purpose: the picker must also accept the base64 .txt a player downloaded from their
    // gist by hand. Content decides what the file is, never its name or the provider's mime guess.
    private val importPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) runImport(uri) else setStatus(getString(R.string.pl_import_cancelled))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = LauncherHost.config
        saveDir = config.resolveSaveDirectory(this)
        setContentView(R.layout.activity_save_management)

        findViewById<MaterialToolbar>(R.id.pl_save_toolbar)
            .setNavigationOnClickListener { finish() }

        listGroup = findViewById(R.id.pl_save_list)
        summary = findViewById(R.id.pl_save_summary)
        status = findViewById(R.id.pl_save_status)
        busy = findViewById(R.id.pl_save_busy)
        exportButton = findViewById(R.id.pl_export_button)
        importButton = findViewById(R.id.pl_import_button)

        exportButton.setOnClickListener {
            exportPicker.launch(suggestedExportName())
        }
        importButton.setOnClickListener {
            importPicker.launch(arrayOf("*/*"))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ listing

    private fun refresh() {
        val files = SaveBundle.listSaveFiles(saveDir)
        listGroup.removeAllViews()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        for (f in files) {
            listGroup.addView(row(f.name, "${f.length()} B  ·  ${fmt.format(Date(f.lastModified()))}"))
        }
        if (files.isEmpty()) {
            summary.text = getString(R.string.pl_no_saves_yet)
            exportButton.isEnabled = false
        } else {
            summary.text = getString(R.string.pl_saves_count, files.size)
            exportButton.isEnabled = true
        }
        LauncherLog.d("save") { "listed ${files.size} save file(s) in $saveDir" }
    }

    private fun row(label: String, value: String): View {
        val lp = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val l = TextView(this).apply {
            text = label
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        }
        val v = TextView(this).apply {
            text = value
            gravity = Gravity.END
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        }
        lp.addView(l, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        lp.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return lp
    }

    // ------------------------------------------------------------------- export

    private fun suggestedExportName(): String =
        config.exportFilenamePrefix + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".zip"

    private fun runExport(uri: Uri) {
        setBusy(true)
        io.execute {
            val result = try {
                val bundle = SaveBundle.createBundleFromDir(saveDir)
                val v = SaveBundle.validate(bundle)
                if (!v.ok) {
                    Result.failure(IllegalStateException(v.error))
                } else {
                    // "wt" - truncating write. With the plain overload some SAF providers do not
                    // truncate, so overwriting a larger existing document leaves trailing garbage.
                    contentResolver.openOutputStream(uri, "wt").use { out ->
                        requireNotNull(out) { "The picked location could not be opened for writing." }
                        out.write(bundle)
                        out.flush()
                    }
                    Result.success(bundle.size to v)
                }
            } catch (t: Throwable) {
                Result.failure(t)
            }
            ui.post {
                setBusy(false)
                result.fold(
                    onSuccess = { (bytes, v) ->
                        LauncherLog.write("save", "exported ${v.saveNames.size} file(s), $bytes bytes")
                        var msg = getString(R.string.pl_export_ok, v.saveNames.size, bytes)
                        if (v.headerWarn.isNotEmpty()) {
                            msg += "\n" + getString(R.string.pl_header_check_warn, v.headerWarn.joinToString(", "))
                        }
                        setStatus(msg)
                    },
                    onFailure = {
                        LauncherLog.e("save", "export failed", it)
                        setStatus(getString(R.string.pl_export_failed, it.message ?: it.javaClass.simpleName))
                    }
                )
            }
        }
    }

    // ------------------------------------------------------------------- import

    private fun runImport(uri: Uri) {
        setBusy(true)
        io.execute {
            val prepared = try {
                val raw = readAll(uri)
                SaveImporter.prepare(raw)
            } catch (t: Throwable) {
                SaveImporter.Prepared.Rejected(t.message ?: t.javaClass.simpleName)
            }
            ui.post {
                setBusy(false)
                when (prepared) {
                    is SaveImporter.Prepared.Rejected -> {
                        LauncherLog.write("save", "import rejected: ${prepared.reason}")
                        MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.pl_import_rejected)
                            .setMessage(
                                prepared.reason + "\n\n" + getString(R.string.pl_saves_untouched)
                            )
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                        setStatus(getString(R.string.pl_import_rejected_short))
                    }
                    is SaveImporter.Prepared.Ready -> confirmImport(prepared)
                }
            }
        }
    }

    private fun confirmImport(ready: SaveImporter.Prepared.Ready) {
        val existing = SaveBundle.listSaveFiles(saveDir).size
        val body = StringBuilder()
        body.append(getString(R.string.pl_import_confirm_body, ready.validation.saveNames.size, existing))
        body.append("\n\n").append(ready.validation.saveNames.joinToString("\n"))
        body.append("\n\n").append(getString(R.string.pl_import_confirm_backup, SaveBundle.PRE_IMPORT_BACKUP))
        if (ready.validation.headerWarn.isNotEmpty()) {
            body.append("\n\n")
                .append(getString(R.string.pl_header_check_warn, ready.validation.headerWarn.joinToString(", ")))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pl_import_confirm_title)
            .setMessage(body.toString())
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                setStatus(getString(R.string.pl_import_cancelled))
            }
            .setPositiveButton(R.string.pl_import_replace) { _, _ -> commitImport(ready) }
            .show()
    }

    private fun commitImport(ready: SaveImporter.Prepared.Ready) {
        setBusy(true)
        io.execute {
            val result = SaveImporter.commit(saveDir, cacheDir, ready)
            ui.post {
                setBusy(false)
                refresh()
                if (result.ok) {
                    LauncherLog.write("save", "import committed: ${result.written} file(s)")
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.pl_import_done)
                        .setMessage(getString(R.string.pl_import_ok, result.written, SaveBundle.PRE_IMPORT_BACKUP))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    setStatus(getString(R.string.pl_import_ok_short, result.written))
                } else {
                    LauncherLog.e("save", "import failed: ${result.error}")
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.pl_import_failed)
                        .setMessage(result.error + "\n\n" + getString(R.string.pl_saves_restored))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    setStatus(getString(R.string.pl_import_failed_short))
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun readAll(uri: Uri): ByteArray {
        contentResolver.openInputStream(uri).use { ins ->
            requireNotNull(ins) { "The picked file could not be opened." }
            val out = ByteArrayOutputStream()
            ins.copyTo(out)
            return out.toByteArray()
        }
    }

    private fun setBusy(b: Boolean) {
        busy.visibility = if (b) View.VISIBLE else View.GONE
        exportButton.isEnabled = !b && SaveBundle.listSaveFiles(saveDir).isNotEmpty()
        importButton.isEnabled = !b
    }

    private fun setStatus(text: String?) {
        if (text.isNullOrEmpty()) {
            status.visibility = View.GONE
        } else {
            status.text = text
            status.visibility = View.VISIBLE
        }
    }
}
