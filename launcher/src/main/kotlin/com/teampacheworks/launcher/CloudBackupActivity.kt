package com.teampacheworks.launcher

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.teampacheworks.launcher.cloud.CloudException
import com.teampacheworks.launcher.cloud.CloudFailure
import com.teampacheworks.launcher.cloud.CloudManifest
import com.teampacheworks.launcher.cloud.CloudSlot
import com.teampacheworks.launcher.cloud.CloudState
import com.teampacheworks.launcher.cloud.CloudStore
import com.teampacheworks.launcher.cloud.GistClient
import com.teampacheworks.launcher.cloud.GitHubToken
import com.teampacheworks.launcher.cloud.TokenStore
import com.teampacheworks.launcher.log.LauncherLog
import com.teampacheworks.launcher.save.SaveBundle
import java.util.concurrent.Executors

/**
 * GitHub Gist cloud backup (design spec §3).
 *
 * 🔴 **Inert without a token.** Opening this screen with no `cloud.token` on disk makes ZERO network
 * requests: [store] is not even constructed until a token has been loaded, and every network call
 * lives behind `store != null`. The absence of a token is the default state, not an error.
 *
 * 🔴 The token is never logged, never put into an exception message, never written to a status line,
 * and never kept in a field. It goes from the masked input straight into [GitHubToken] and the input
 * is cleared before any async work begins.
 *
 * The MaterialToolbar is standalone; `setSupportActionBar` is never called.
 */
class CloudBackupActivity : AppCompatActivity() {

    private lateinit var config: LauncherConfig
    private lateinit var tokenStore: TokenStore
    private lateinit var state: CloudState

    private var client: GistClient? = null
    private var store: CloudStore? = null
    private var manifest: CloudManifest? = null

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "pl-cloud-io") }
    private val ui = Handler(Looper.getMainLooper())
    private var busy = false

    private lateinit var tokenPanel: View
    private lateinit var signedInPanel: View
    private lateinit var busyBar: LinearProgressIndicator
    private lateinit var statusView: TextView
    private lateinit var tokenInput: TextInputEditText
    private lateinit var accountLine: TextView
    private lateinit var gistLine: TextView
    private lateinit var slotList: LinearLayout
    private lateinit var slotEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = LauncherHost.config
        setContentView(R.layout.activity_cloud_backup)

        findViewById<MaterialToolbar>(R.id.pl_cloud_toolbar).setNavigationOnClickListener { finish() }

        tokenPanel = findViewById(R.id.pl_token_panel)
        signedInPanel = findViewById(R.id.pl_signed_in_panel)
        busyBar = findViewById(R.id.pl_cloud_busy)
        statusView = findViewById(R.id.pl_cloud_status)
        tokenInput = findViewById(R.id.pl_token_input)
        accountLine = findViewById(R.id.pl_account_line)
        gistLine = findViewById(R.id.pl_gist_line)
        slotList = findViewById(R.id.pl_slot_list)
        slotEmpty = findViewById(R.id.pl_slot_empty)

        findViewById<TextView>(R.id.pl_cloud_intro_body).text =
            getString(R.string.pl_cloud_intro_body, config.appLabel)

        tokenStore = TokenStore.default(filesDir)
        state = CloudState.of(this)

        findViewById<MaterialButton>(R.id.pl_open_new_token).setOnClickListener { openUrl(URL_NEW_TOKEN) }
        findViewById<MaterialButton>(R.id.pl_open_token_list).setOnClickListener { openUrl(URL_TOKEN_LIST) }
        findViewById<MaterialButton>(R.id.pl_paste_token).setOnClickListener { pasteToken() }
        findViewById<MaterialButton>(R.id.pl_connect_button).setOnClickListener { connect() }
        findViewById<MaterialButton>(R.id.pl_refresh_button).setOnClickListener {
            client?.invalidateReadCache(); reload()
        }
        findViewById<MaterialButton>(R.id.pl_open_gist_button).setOnClickListener { openUrl(gistUrl()) }
        findViewById<MaterialButton>(R.id.pl_upload_button).setOnClickListener { promptUpload() }
        findViewById<MaterialButton>(R.id.pl_disconnect_button).setOnClickListener { disconnect(false) }
        findViewById<MaterialButton>(R.id.pl_disconnect_delete_button).setOnClickListener { disconnect(true) }

        ensureStore()
        render()
        if (store != null) reload()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    /**
     * 🔴 The file-existence check is the structural guarantee: with no token file, no [GistClient]
     * is ever constructed, so there is nothing that *could* make a request.
     */
    private fun ensureStore() {
        if (store != null) return
        if (!tokenStore.exists) return
        val token = tokenStore.tryLoad() ?: return
        val c = GistClient(config.appVersionName, config.appLabel)
        client = c
        store = CloudStore(c, state, filesDir, cacheDir).also { it.setToken(token) }
    }

    // ------------------------------------------------------------------ render

    private fun render() {
        val signedIn = store != null
        tokenPanel.visibility = if (signedIn) View.GONE else View.VISIBLE
        signedInPanel.visibility = if (signedIn) View.VISIBLE else View.GONE
        if (!signedIn) return

        accountLine.text = getString(
            R.string.pl_cloud_connected_as,
            state.login.ifEmpty { getString(R.string.pl_cloud_your_account) }
        )
        gistLine.text = if (state.hasGist) {
            getString(R.string.pl_cloud_gist_line, CloudException.maskGistId(state.gistId))
        } else {
            getString(R.string.pl_cloud_no_gist_yet)
        }
        renderSlots()
    }

    private fun renderSlots() {
        slotList.removeAllViews()
        val slots = manifest?.slots.orEmpty()
        slotEmpty.visibility = if (slots.isEmpty()) View.VISIBLE else View.GONE
        for (s in slots.sortedBy { it.file }) slotList.addView(slotCard(s))
    }

    private fun slotCard(slot: CloudSlot): View {
        val card = MaterialCardView(this).apply {
            radius = 16f * resources.displayMetrics.density
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (8f * resources.displayMetrics.density).toInt()
            layoutParams = lp
        }
        val pad = (16f * resources.displayMetrics.density).toInt()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        body.addView(TextView(this).apply {
            text = if (slot.note.isEmpty()) slot.file else "${slot.file} — ${slot.note}"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
        })
        body.addView(TextView(this).apply {
            text = getString(
                R.string.pl_cloud_slot_meta,
                slot.bytes,
                slot.uploadedAt.ifEmpty { "?" }
            )
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        row.addView(MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = getString(R.string.pl_cloud_download)
            setOnClickListener { confirmDownload(slot, skipHashCheck = false) }
        })
        row.addView(MaterialButton(
            // `?attr/borderlessButtonStyle` is not present on every Material version - apply the
            // style resource directly via a ContextThemeWrapper instead of a theme attr lookup,
            // which works the same on every Material version that ships this style constant.
            ContextThemeWrapper(this, com.google.android.material.R.style.Widget_Material3_Button_TextButton),
            null
        ).apply {
            text = getString(R.string.pl_cloud_delete)
            setOnClickListener { confirmDeleteSlot(slot) }
        })
        body.addView(row)
        card.addView(body)
        return card
    }

    // ------------------------------------------------------------------ connect

    private fun pasteToken() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip
            val text = if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
            } else {
                ""
            }
            if (text.isEmpty()) {
                setStatus(getString(R.string.pl_cloud_clipboard_empty))
            } else {
                // Goes straight into the masked field and nowhere else. Never logged.
                tokenInput.setText(text)
                setStatus(null)
            }
        } catch (t: Throwable) {
            LauncherLog.write("cloud", "clipboard read failed (${t.javaClass.simpleName})")
            setStatus(getString(R.string.pl_cloud_clipboard_empty))
        }
    }

    private fun connect() {
        val raw = tokenInput.text?.toString().orEmpty()
        tokenInput.setText("") // cleared BEFORE any async work
        when (val parsed = GitHubToken.parse(raw)) {
            is GitHubToken.Parsed.Invalid -> {
                // Shape is validated locally: a malformed paste costs zero network requests.
                setStatus(parsed.error)
            }
            is GitHubToken.Parsed.Ok -> {
                val token = parsed.token
                val c = GistClient(config.appVersionName, config.appLabel)
                runBusy({
                    val login = c.getUserLogin(token)
                    val saved = tokenStore.save(token)
                    state.login = login
                    c.tokenExpiration?.let { state.tokenExpiration = it }
                    val s = CloudStore(c, state, filesDir, cacheDir).also { it.setToken(token) }
                    // Adopt an existing gist right away so a second device never creates a duplicate.
                    try { s.discoverGist() } catch (ex: CloudException) {
                        LauncherLog.write("cloud", "connect: discovery failed (${ex.failure})")
                    }
                    Triple(login, saved, s)
                }, { (login, saved, s) ->
                    client = c
                    store = s
                    var msg = getString(R.string.pl_cloud_connected_ok, login.ifEmpty { "GitHub" })
                    if (!saved) msg += "\n\n" + getString(R.string.pl_cloud_token_not_saved)
                    setStatus(msg)
                    render()
                    reload()
                })
            }
        }
    }

    private fun disconnect(alsoDeleteCloud: Boolean) {
        if (!alsoDeleteCloud) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pl_cloud_disconnect)
                .setMessage(R.string.pl_cloud_disconnect_body)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.pl_cloud_disconnect) { _, _ -> doDisconnect(false) }
                .show()
            return
        }
        // Two-step confirmation: this is the only irreversible action on the screen.
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pl_cloud_disconnect_delete)
            .setMessage(R.string.pl_cloud_disconnect_delete_body1)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.pl_cloud_continue) { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.pl_cloud_are_you_sure)
                    .setMessage(R.string.pl_cloud_disconnect_delete_body2)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.pl_cloud_delete_everything) { _, _ -> doDisconnect(true) }
                    .show()
            }
            .show()
    }

    private fun doDisconnect(alsoDeleteCloud: Boolean) {
        val s = store
        if (alsoDeleteCloud && s != null) {
            runBusy({ s.deleteGist() }, { result ->
                if (!result.ok) {
                    setStatus(getString(R.string.pl_cloud_delete_failed, result.error.orEmpty()))
                    return@runBusy
                }
                finishDisconnect(true)
            })
        } else {
            finishDisconnect(false)
        }
    }

    private fun finishDisconnect(clearedCloud: Boolean) {
        tokenStore.clear()
        store = null
        client = null
        manifest = null
        if (clearedCloud) state.clearAll()
        LauncherLog.write("cloud", "disconnected (cloud data deleted=$clearedCloud)")
        setStatus(getString(if (clearedCloud) R.string.pl_cloud_deleted_ok else R.string.pl_cloud_disconnected_ok))
        render()
    }

    // ------------------------------------------------------------------- listing

    private fun reload() {
        val s = store ?: return
        runBusy({ s.list() }, { listing ->
            manifest = listing.manifest
            renderSlots()
            val bits = ArrayList<String>()
            listing.warning?.let { bits += it }
            if (listing.stale) bits += getString(R.string.pl_cloud_stale)
            setStatus(bits.joinToString("\n\n").ifEmpty { null })
        })
    }

    // -------------------------------------------------------------------- upload

    private fun promptUpload() {
        val s = store ?: return
        val saves = SaveBundle.listSaveFiles(filesDir)
        if (saves.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pl_cloud_nothing_to_upload)
                .setMessage(R.string.pl_cloud_nothing_to_upload_body)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val input = TextInputEditText(this).apply {
            hint = getString(R.string.pl_cloud_note_hint)
            val p = (16f * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pl_cloud_upload)
            .setMessage(getString(R.string.pl_cloud_upload_body, saves.size))
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.pl_cloud_upload_go) { _, _ ->
                val note = input.text?.toString().orEmpty()
                val mf = manifest
                runBusy({ s.upload(mf, note) }, { result ->
                    when {
                        result.ok && result.alreadyUpToDate ->
                            setStatus(getString(R.string.pl_cloud_up_to_date, result.slot?.file.orEmpty()))
                        result.ok -> {
                            setStatus(getString(R.string.pl_cloud_upload_ok, result.slot?.file.orEmpty()))
                            client?.invalidateReadCache()
                            reload()
                        }
                        else -> showFailure(getString(R.string.pl_cloud_upload_failed), result.error, result.failure)
                    }
                })
            }
            .show()
    }

    // ------------------------------------------------------------------ download

    private fun confirmDownload(slot: CloudSlot, skipHashCheck: Boolean) {
        val s = store ?: return
        val existing = SaveBundle.listSaveFiles(filesDir).size
        runBusy({
            // The confirm callback runs on the io thread; the dialog has to be posted to the UI
            // thread and waited on. A confirmation that could not be shown must read as "no":
            // cancelling costs a retry, guessing "yes" costs a playthrough.
            s.download(slot, skipHashCheck) { validation ->
                // Buffered by one so an answer can never be dropped by arriving before take().
                val gate = java.util.concurrent.ArrayBlockingQueue<Boolean>(1)
                ui.post {
                    val body = StringBuilder()
                        .append(getString(R.string.pl_cloud_download_body, validation.saveNames.size, existing))
                        .append("\n\n").append(validation.saveNames.joinToString("\n"))
                        .append("\n\n").append(getString(R.string.pl_import_confirm_backup, SaveBundle.PRE_IMPORT_BACKUP))
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.pl_cloud_download_title)
                        .setMessage(body.toString())
                        .setNegativeButton(android.R.string.cancel) { _, _ -> gate.offer(false) }
                        .setPositiveButton(R.string.pl_import_replace) { _, _ -> gate.offer(true) }
                        .setOnCancelListener { gate.offer(false) }
                        .show()
                }
                try { gate.take() } catch (t: Throwable) { false }
            }
        }, { result ->
            when {
                result.cancelled -> setStatus(getString(R.string.pl_import_cancelled))
                result.ok -> {
                    setStatus(getString(R.string.pl_cloud_download_ok, result.written, SaveBundle.PRE_IMPORT_BACKUP))
                }
                // The checksum refusal is the only one with an escape hatch: the bundle is still
                // validated as a readable save bundle either way, only the "was this changed
                // outside the launcher" check is skipped.
                result.failure == CloudFailure.Integrity &&
                    !skipHashCheck &&
                    result.error?.contains("checksum", ignoreCase = true) == true -> {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.pl_cloud_download_refused)
                        .setMessage(result.error + "\n\n" + getString(R.string.pl_cloud_validate_only_body))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.pl_cloud_validate_only) { _, _ ->
                            confirmDownload(slot, skipHashCheck = true)
                        }
                        .show()
                }
                else -> showFailure(getString(R.string.pl_cloud_download_failed), result.error, result.failure)
            }
        })
    }

    // -------------------------------------------------------------------- delete

    private fun confirmDeleteSlot(slot: CloudSlot) {
        val s = store ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pl_cloud_delete_slot_title)
            .setMessage(getString(R.string.pl_cloud_delete_slot_body, slot.file))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.pl_cloud_delete) { _, _ ->
                val mf = manifest
                runBusy({ s.deleteSlot(mf, slot) }, { result ->
                    if (result.ok) {
                        setStatus(getString(R.string.pl_cloud_slot_deleted, slot.file))
                        client?.invalidateReadCache()
                        reload()
                    } else {
                        showFailure(getString(R.string.pl_cloud_delete_failed_title), result.error, result.failure)
                    }
                })
            }
            .show()
    }

    // ------------------------------------------------------------------ plumbing

    /** One network operation at a time; a queued tap while a transfer runs is ignored. */
    private fun <T> runBusy(work: () -> T, then: (T) -> Unit) {
        if (busy) return
        setBusy(true)
        io.execute {
            val result = try {
                Result.success(work())
            } catch (t: Throwable) {
                Result.failure(t)
            }
            ui.post {
                setBusy(false)
                result.fold(
                    onSuccess = { then(it) },
                    onFailure = { err ->
                        if (err is CloudException) {
                            LauncherLog.write("cloud", "action failed: ${err.toLogLine()}")
                            if (err.failure == CloudFailure.Unauthorized) handleAuthFailure()
                            showFailure(getString(R.string.pl_cloud_action_failed), err.message, err.failure)
                        } else {
                            LauncherLog.write("cloud", "action failed: ${err.javaClass.simpleName}")
                            showFailure(getString(R.string.pl_cloud_action_failed), err.message, null)
                        }
                    }
                )
            }
        }
    }

    /**
     * §3: a 401 never renders as an empty slot list. The token is cleared and the screen flips back
     * to the onboarding panel, which *replaces* the list rather than sitting above it.
     */
    private fun handleAuthFailure() {
        LauncherLog.write("cloud", "401 -> token cleared, screen back in connect state")
        tokenStore.clear()
        store = null
        client = null
        manifest = null
        render()
    }

    private fun showFailure(title: String, message: String?, failure: CloudFailure?) {
        val extra = when (failure) {
            CloudFailure.MissingGistPermission ->
                "\n\n" + getString(R.string.pl_cloud_fix_permission)
            CloudFailure.Offline -> "\n\n" + getString(R.string.pl_cloud_offline_hint)
            CloudFailure.Unauthorized -> "\n\n" + getString(R.string.pl_cloud_unauthorized_hint)
            else -> ""
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage((message ?: getString(R.string.pl_cloud_action_failed)) + extra)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        setStatus(message)
    }

    private fun setBusy(b: Boolean) {
        busy = b
        busyBar.visibility = if (b) View.VISIBLE else View.GONE
    }

    private fun setStatus(text: String?) {
        if (text.isNullOrEmpty()) {
            statusView.visibility = View.GONE
        } else {
            statusView.text = text
            statusView.visibility = View.VISIBLE
        }
    }

    private fun gistUrl(): String {
        val id = state.gistId ?: return URL_GIST_LIST
        val login = state.login
        return if (login.isEmpty()) "$URL_GIST_LIST$id" else "$URL_GIST_LIST$login/$id"
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            LauncherLog.write("cloud", "could not open a browser (${t.javaClass.simpleName})")
            setStatus(getString(R.string.pl_cloud_no_browser, url))
        }
    }

    companion object {
        const val URL_NEW_TOKEN = "https://github.com/settings/personal-access-tokens/new"
        const val URL_TOKEN_LIST = "https://github.com/settings/tokens?type=beta"
        const val URL_GIST_LIST = "https://gist.github.com/"
    }
}
