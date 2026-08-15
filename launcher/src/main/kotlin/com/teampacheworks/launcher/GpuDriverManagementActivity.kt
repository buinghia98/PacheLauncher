package com.teampacheworks.launcher

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.teampacheworks.launcher.gpu.GpuDriver
import com.teampacheworks.launcher.gpu.GpuDriverStorage

class GpuDriverManagementActivity : AppCompatActivity() {
    private val config get() = requireNotNull(LauncherHost.config.gpuDriverManagement)
    private data class Entry(val label: String, val value: String, val driver: GpuDriver? = null)

    private var entries: List<Entry> = emptyList()
    private lateinit var spinner: Spinner
    private lateinit var details: TextView
    private lateinit var makeDefault: MaterialButton
    private lateinit var delete: MaterialButton
    private var busy = false

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        setBusy(true)
        Thread {
            val result = GpuDriverStorage.importZip(this, config, uri)
            runOnUiThread {
                setBusy(false)
                result.onSuccess {
                    Toast.makeText(this, getString(R.string.pl_gpu_driver_imported, it.label), Toast.LENGTH_LONG).show()
                    refresh(it.selectionValue)
                }.onFailure { showError(it.message ?: it.javaClass.simpleName) }
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (LauncherHost.config.gpuDriverManagement == null) { finish(); return }
        setContentView(R.layout.activity_gpu_driver_management)
        spinner = findViewById(R.id.pl_gpu_driver_manage_spinner)
        details = findViewById(R.id.pl_gpu_driver_details)
        makeDefault = findViewById(R.id.pl_gpu_driver_make_default)
        delete = findViewById(R.id.pl_gpu_driver_delete)
        findViewById<MaterialButton>(R.id.pl_gpu_driver_import).setOnClickListener {
            picker.launch(arrayOf("application/zip", "application/octet-stream"))
        }
        makeDefault.setOnClickListener {
            val entry = entries.getOrNull(spinner.selectedItemPosition) ?: return@setOnClickListener
            getSharedPreferences(LauncherHost.config.prefsName, Context.MODE_PRIVATE)
                .edit().putString(config.prefsKey, entry.value).apply()
            Toast.makeText(this, getString(R.string.pl_gpu_driver_default_set, entry.label), Toast.LENGTH_SHORT).show()
            showEntry(spinner.selectedItemPosition)
        }
        delete.setOnClickListener {
            val driver = entries.getOrNull(spinner.selectedItemPosition)?.driver ?: return@setOnClickListener
            if (GpuDriverStorage.delete(this, config, driver)) {
                val prefs = getSharedPreferences(LauncherHost.config.prefsName, Context.MODE_PRIVATE)
                if (prefs.getString(config.prefsKey, GpuDriverStorage.SYSTEM) == driver.selectionValue) {
                    prefs.edit().putString(config.prefsKey, GpuDriverStorage.SYSTEM).apply()
                }
                Toast.makeText(this, R.string.pl_gpu_driver_deleted, Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
        findViewById<MaterialButton>(R.id.pl_gpu_driver_back).setOnClickListener { finish() }
        loadDrivers()
    }

    private fun loadDrivers(select: String? = null) {
        setBusy(true)
        Thread {
            val bundledResult = GpuDriverStorage.ensureBundled(this, config)
            runOnUiThread {
                setBusy(false)
                bundledResult.exceptionOrNull()?.let {
                    showError(it.message ?: it.javaClass.simpleName)
                }
                refresh(select)
            }
        }.start()
    }

    private fun refresh(select: String? = null) {
        entries = listOf(Entry(getString(R.string.pl_gpu_driver_system), GpuDriverStorage.SYSTEM)) +
            GpuDriverStorage.list(this, config).map { Entry(it.label, it.selectionValue, it) }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            entries.map { it.label }).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = showEntry(position)
            override fun onNothingSelected(parent: AdapterView<*>?) { details.text = "" }
        }
        val prefs = getSharedPreferences(LauncherHost.config.prefsName, Context.MODE_PRIVATE)
        val currentDefault = prefs.getString(config.prefsKey, GpuDriverStorage.SYSTEM) ?: GpuDriverStorage.SYSTEM
        val requested = select ?: currentDefault
        val index = entries.indexOfFirst { it.value == requested }.coerceAtLeast(0)
        if (entries.none { it.value == currentDefault }) {
            prefs.edit().putString(config.prefsKey, GpuDriverStorage.SYSTEM).apply()
        }
        spinner.setSelection(index)
        showEntry(index)
    }

    private fun showEntry(position: Int) {
        val entry = entries.getOrNull(position) ?: return
        val d = entry.driver
        details.text = if (d == null) {
            getString(R.string.pl_gpu_driver_system_details)
        } else {
            listOfNotNull(d.label, d.vendor, d.version, d.description,
                "${d.libraryName}\n${d.directory.absolutePath}").joinToString("\n")
        }
        val prefs = getSharedPreferences(LauncherHost.config.prefsName, Context.MODE_PRIVATE)
        val selected = prefs.getString(config.prefsKey, GpuDriverStorage.SYSTEM) == entry.value
        makeDefault.text = getString(if (selected) R.string.pl_gpu_driver_default else R.string.pl_gpu_driver_make_default)
        makeDefault.isEnabled = !busy && !selected
        delete.visibility = if (d?.imported == true) View.VISIBLE else View.GONE
        delete.isEnabled = !busy && d?.imported == true
    }

    private fun setBusy(busy: Boolean) {
        this.busy = busy
        findViewById<MaterialButton>(R.id.pl_gpu_driver_import).isEnabled = !busy
        spinner.isEnabled = !busy
        val entry = entries.getOrNull(spinner.selectedItemPosition)
        val currentDefault = getSharedPreferences(LauncherHost.config.prefsName, Context.MODE_PRIVATE)
            .getString(config.prefsKey, GpuDriverStorage.SYSTEM)
        makeDefault.isEnabled = !busy && entry != null && entry.value != currentDefault
        delete.isEnabled = !busy && entry?.driver?.imported == true
    }

    private fun showError(message: String) {
        details.text = getString(R.string.pl_gpu_driver_error, message)
        Toast.makeText(this, getString(R.string.pl_gpu_driver_error, message), Toast.LENGTH_LONG).show()
    }
}
