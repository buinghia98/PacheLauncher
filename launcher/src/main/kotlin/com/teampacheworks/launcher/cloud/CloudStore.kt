package com.teampacheworks.launcher.cloud

import com.teampacheworks.launcher.LauncherHost
import com.teampacheworks.launcher.log.LauncherLog
import com.teampacheworks.launcher.save.SaveBundle
import com.teampacheworks.launcher.save.SaveImporter
import java.io.File

/** One row of the "which gist is yours?" chooser. */
data class GistCandidate(
    val id: String,
    val login: String,
    val description: String,
    val saveCount: Int
)

data class UploadResult(
    val ok: Boolean,
    val error: String? = null,
    val failure: CloudFailure? = null,
    val slot: CloudSlot? = null,
    /** True when the bundle's SHA-256 already matched a slot: nothing was written, nothing bumped. */
    val alreadyUpToDate: Boolean = false
)

data class DownloadResult(
    val ok: Boolean,
    val error: String? = null,
    val failure: CloudFailure? = null,
    val written: Int = 0,
    val cancelled: Boolean = false
)

data class Listing(
    val manifest: CloudManifest,
    val warning: String? = null,
    val stale: Boolean = false
)

/**
 * The upload and download cores (design spec §3).
 *
 * 🔴 **Zero network without a token.** [isConfigured] false means every method here refuses before
 * touching the client. The absence of a token is not an error state, it is the default state.
 *
 * Everything is blocking; the Activity runs it on a background executor.
 */
class CloudStore(
    private val client: GistClient,
    val state: CloudState,
    private val filesDir: File,
    private val cacheDir: File
) {

    private var token: GitHubToken? = null

    var discoveredCandidates: List<GistCandidate> = emptyList()
        private set

    val isConfigured: Boolean get() = token != null

    val readStrategy: GistReadStrategy get() = client.readStrategy

    fun setToken(t: GitHubToken?) {
        token = t
    }

    private fun requireConfigured(): GitHubToken = token
        ?: throw IllegalStateException(
            "Cloud backup is not configured: no token. Check isConfigured before calling this."
        )

    // ---------------------------------------------------------------- discovery

    /**
     * §3 invariant 6. A gist is ours iff it holds `01-manifest.json` whose `app` matches
     * [com.teampacheworks.launcher.LauncherConfig.cloudAppId] - never by description, which the
     * player can rewrite and an unrelated gist could share.
     *
     * One candidate adopts itself; several do not. Picking here would be guessing which playthrough
     * is theirs, and guessing wrong writes the next backup into the wrong gist.
     */
    fun discoverGist(): String? {
        val t = requireConfigured()
        state.gistId?.let { id ->
            /* Older launcher builds could claim any gist that merely contained a file named
             * 01-manifest.json because GitHub's list endpoint normally omits file content. Do not
             * carry that bad adoption forward: verify the manifest discriminator before allowing
             * an existing id to win. Network failures preserve the id; a proven mismatch does not. */
            try {
                val content = client.getSnapshot(t, id).files[CloudManifest.FILE_NAME]?.content
                if (CloudManifest.isOurManifest(content)) return id
                LauncherLog.write(
                    "cloud",
                    "stored gist ${CloudException.maskGistId(id)} belongs to another app; forgetting it"
                )
                state.forgetGist()
            } catch (ex: CloudException) {
                if (ex.failure != CloudFailure.NotFound) return id
                state.forgetGist()
            }
        }

        val gists = try {
            client.listMyGists(t, DISCOVERY_MAX_PAGES)
        } catch (ex: CloudException) {
            // Offline / rate-limited / missing scope does not mean "no gist exists".
            LauncherLog.write("cloud", "discovery: list failed (${ex.failure}); leaving the gist unset")
            return null
        }

        val ours = gists.filter { g ->
            val listed = g.files[CloudManifest.FILE_NAME] ?: return@filter false
            /* GET /gists normally lists file metadata without content. Presence of the generic
             * manifest filename is not ownership: fetch the candidate and verify its `app` field.
             * This is what stops one port from adopting a different port's gist. */
            val content = if (!listed.truncated && listed.content != null) listed.content else try {
                client.getSnapshot(t, g.id).files[CloudManifest.FILE_NAME]?.content
            } catch (ex: CloudException) {
                LauncherLog.write(
                    "cloud",
                    "discovery: could not verify ${CloudException.maskGistId(g.id)} (${ex.failure})"
                )
                null
            }
            CloudManifest.isOurManifest(content)
        }.map { g ->
            GistCandidate(
                id = g.id,
                login = g.ownerLogin,
                description = g.description,
                saveCount = g.files.keys.count { CloudPayload.isSlotFileName(it) }
            )
        }.sortedByDescending { it.saveCount }

        discoveredCandidates = ours
        if (ours.isEmpty()) {
            LauncherLog.write("cloud", "discovery: no existing cloud gist on this account")
            return null
        }
        if (ours.size > 1) {
            LauncherLog.write("cloud", "discovery: ${ours.size} candidate gists; leaving the choice to the player")
            return null
        }
        adoptGist(ours[0].id, ours[0].login)
        LauncherLog.write(
            "cloud",
            "discovery: adopted ${CloudException.maskGistId(ours[0].id)} (${ours[0].saveCount} save file(s))"
        )
        return ours[0].id
    }

    fun adoptGist(gistId: String, login: String?) {
        state.gistId = gistId.trim()
        if (!login.isNullOrEmpty()) state.login = login
        client.invalidateReadCache()
    }

    // ------------------------------------------------------------------ listing

    fun list(): Listing {
        val t = requireConfigured()
        if (!state.hasGist) return Listing(CloudManifest.createEmpty()) // no request made

        val json = try {
            client.readTextFile(t, state.gistId!!, state.login, CloudManifest.FILE_NAME, null, null)
        } catch (ex: CloudException) {
            if (ex.failure == CloudFailure.NotFound) {
                return Listing(CloudManifest.createEmpty(), warning = MANIFEST_WARNING)
            }
            throw ex
        }
        return when (val p = CloudManifest.tryParse(json)) {
            is CloudManifest.Parse.Err -> {
                LauncherLog.write("cloud", "manifest parse failed: ${p.error}")
                Listing(CloudManifest.createEmpty(), warning = MANIFEST_WARNING)
            }
            is CloudManifest.Parse.Ok -> {
                val stale = isStale(p.manifest.revision, state.lastWrittenRevision)
                if (stale) {
                    LauncherLog.write(
                        "cloud",
                        "manifest revision ${p.manifest.revision} < last written " +
                            "${state.lastWrittenRevision}; stale edge copy"
                    )
                }
                Listing(p.manifest, stale = stale)
            }
        }
    }

    // ------------------------------------------------------------------- upload

    /**
     * §3 invariants 1 and 3: one PATCH carries slot + manifest, and a bundle whose SHA-256 already
     * matches **any** existing slot is "up to date" - no new slot, no revision bump, no request.
     */
    fun upload(manifest: CloudManifest?, note: String): UploadResult {
        val t = requireConfigured()
        val bundle = try {
            SaveBundle.createBundleFromDir(filesDir)
        } catch (e: Throwable) {
            return UploadResult(false, "Could not read the local saves: ${e.message}", CloudFailure.Local)
        }
        val v = SaveBundle.validate(bundle)
        if (!v.ok) return UploadResult(false, v.error ?: "There is nothing to upload.", CloudFailure.Local)

        val sha = SaveBundle.sha256Hex(bundle)
        val mf = manifest ?: CloudManifest.createEmpty()

        mf.findBySha(sha)?.let { existing ->
            LauncherLog.write("cloud", "upload skipped: sha256 already in ${existing.file}")
            return UploadResult(true, slot = existing, alreadyUpToDate = true)
        }

        val slotNumber = mf.nextFreeSlot(MAX_SLOTS)
            ?: return UploadResult(
                false,
                "All $MAX_SLOTS cloud slots are in use. Delete one before uploading again.",
                CloudFailure.Local
            )
        val fileName = CloudPayload.fileNameFor(slotNumber)
        val entry = CloudSlot(
            file = fileName,
            sha256 = sha,
            bytes = bundle.size.toLong(),
            note = note.trim(),
            uploadedAt = CloudManifest.nowIso()
        )
        mf.upsert(entry)

        val files = linkedMapOf<String, String?>(
            fileName to CloudPayload.encode(bundle, slotNumber, sha),
            CloudManifest.FILE_NAME to mf.toJson()
        )

        return try {
            if (!state.hasGist) {
                // 🔴 Last-chance re-discovery. An upload can be the first thing that reaches the
                // network on a device that was set up offline, and a duplicate gist is the one
                // outcome here that is genuinely hard to undo.
                discoverGist()
            }
            if (!state.hasGist) {
                files[CloudManifest.README_NAME] = readme()
                val snap = client.createGist(t, gistDescription(), files)
                // Persist the id BEFORE reporting success: an id lost here strands the gist.
                state.gistId = snap.id
                if (snap.ownerLogin.isNotEmpty()) state.login = snap.ownerLogin
            } else {
                client.patchGist(t, state.gistId!!, files)
            }
            state.lastWrittenRevision = mf.revision
            client.tokenExpiration?.let { state.tokenExpiration = it }
            LauncherLog.write("cloud", "upload ok slot=$slotNumber bytes=${bundle.size} rev=${mf.revision}")
            UploadResult(true, slot = entry)
        } catch (ex: CloudException) {
            LauncherLog.write("cloud", "upload failed: ${ex.toLogLine()}")
            UploadResult(false, ex.message, ex.failure)
        }
    }

    // ----------------------------------------------------------------- download

    /**
     * §3 invariant 4 - the order is not negotiable:
     * fetch → decode fence → verify sha256 vs manifest → temp file → validate zip on the temp →
     * confirm → commit with `pre-import.bak.zip` + rollback → cleanup in `finally`.
     *
     * [confirm] is called only once every check has passed, and it is handed the **downloaded**
     * bundle's contents, never the manifest's claims about them.
     */
    fun download(
        entry: CloudSlot,
        skipHashCheck: Boolean,
        confirm: (SaveBundle.Validation) -> Boolean
    ): DownloadResult {
        val t = requireConfigured()
        if (!state.hasGist) {
            return DownloadResult(false, "There is no cloud gist for this device yet.", CloudFailure.NotFound)
        }

        // 1. fetch
        val text = try {
            client.readTextFile(t, state.gistId!!, state.login, entry.file, null, null)
        } catch (ex: CloudException) {
            val msg = if (ex.failure == CloudFailure.NotFound) {
                "That cloud slot's file is not in your gist any more."
            } else {
                ex.message
            }
            return DownloadResult(false, msg, ex.failure)
        }

        // 2. decode - strict, the fence is required
        val bundle = when (val d = CloudPayload.decode(text)) {
            is CloudPayload.Decoded.Err -> {
                LauncherLog.write("cloud", "download refused at decode (${entry.file})")
                return DownloadResult(
                    false, d.message + "\n\nYour saves were not changed.", CloudFailure.Integrity
                )
            }
            is CloudPayload.Decoded.Ok -> d.bytes
        }

        // 3. integrity cross-check against the manifest
        if (!skipHashCheck && entry.sha256.isNotEmpty()) {
            val actual = SaveBundle.sha256Hex(bundle)
            if (!SaveBundle.hashesMatch(actual, entry.sha256)) {
                LauncherLog.write("cloud", "download refused at sha256 (${entry.file})")
                return DownloadResult(
                    false,
                    "This cloud slot does not match its recorded checksum — it was changed outside " +
                        "the launcher. Your saves were not changed.",
                    CloudFailure.Integrity
                )
            }
        }

        // 4+5. temp file and validate ON the temp file, then 6. confirm, 7. commit, 8. cleanup.
        //     SaveImporter owns exactly that order; there is no second save-writing path.
        val prepared = SaveImporter.prepare(bundle)
        if (prepared is SaveImporter.Prepared.Rejected) {
            return DownloadResult(
                false,
                prepared.reason + "\n\nYour saves were not changed.",
                CloudFailure.Integrity
            )
        }
        val ready = prepared as SaveImporter.Prepared.Ready
        if (!confirm(ready.validation)) return DownloadResult(false, null, cancelled = true)

        val commit = SaveImporter.commit(filesDir, cacheDir, ready)
        return if (commit.ok) {
            LauncherLog.write("cloud", "download ok ${entry.file} -> ${commit.written} file(s)")
            DownloadResult(true, written = commit.written)
        } else {
            DownloadResult(false, commit.error, CloudFailure.Local)
        }
    }

    // ------------------------------------------------------------------- delete

    fun deleteSlot(manifest: CloudManifest?, entry: CloudSlot): UploadResult {
        val t = requireConfigured()
        if (!state.hasGist) {
            return UploadResult(false, "There is no cloud gist for this device yet.", CloudFailure.NotFound)
        }
        val mf = manifest ?: CloudManifest.createEmpty()
        mf.remove(entry.file)
        val files = linkedMapOf<String, String?>(
            entry.file to null, // null value deletes the file
            CloudManifest.FILE_NAME to mf.toJson()
        )
        return try {
            client.patchGist(t, state.gistId!!, files)
            state.lastWrittenRevision = mf.revision
            LauncherLog.write("cloud", "slot deleted ${entry.file}")
            UploadResult(true)
        } catch (ex: CloudException) {
            LauncherLog.write("cloud", "slot delete failed: ${ex.toLogLine()}")
            UploadResult(false, ex.message, ex.failure)
        }
    }

    /** Disconnect-and-delete: removes the whole gist. There is no undo. */
    fun deleteGist(): UploadResult {
        val t = requireConfigured()
        val id = state.gistId ?: return UploadResult(true)
        return try {
            client.deleteGist(t, id)
            state.forgetGist()
            UploadResult(true)
        } catch (ex: CloudException) {
            UploadResult(false, ex.message, ex.failure)
        }
    }

    companion object {
        const val MAX_SLOTS = 20
        const val DISCOVERY_MAX_PAGES = 3

        /**
         * §3 invariant 2. Staleness is decided by the revision counter and **never** by a
         * timestamp: GitHub's edge can serve an older manifest with a perfectly plausible
         * `updatedAt`. A fetched revision below what this device last wrote is a cache artefact,
         * so it must not be shown as truth and must not be written back over.
         */
        fun isStale(fetchedRevision: Int, lastWrittenRevision: Int): Boolean =
            fetchedRevision < lastWrittenRevision

        const val MANIFEST_WARNING =
            "The index in your cloud gist is missing or unreadable. Slot details are unavailable, " +
                "but a download is still checked before it commits."

        private fun gistDescription(): String {
            val name = try { LauncherHost.config.cloudProductName } catch (t: Throwable) { "PacheLauncher" }
            return "$name cloud saves — created by the $name launcher — do not edit by hand"
        }

        private fun readme(): String {
            val name = try { LauncherHost.config.cloudProductName } catch (t: Throwable) { "PacheLauncher" }
            val fence = try { LauncherHost.config.cloudFenceTag } catch (t: Throwable) { "PACHE" }
            return """
                # $name cloud saves

                This gist is a backup of $name save files, created by the game's Android launcher.
                Each `save-NN.zip.b64.txt` file is one backup: a zip of the game's save files,
                base64-encoded so that it survives being stored as text. `01-manifest.json` lists what is
                in each slot.

                **Do not edit these files by hand.** The launcher checks a SHA-256 of each backup before
                it will restore one; a hand-edited file will be refused.

                To restore: open the launcher → Cloud Backup → Download on the slot you want.

                To use a file without the launcher's cloud screen: download it, then use
                Save Management → Import save bundle and pick the `.zip.b64.txt` file directly.
                The launcher decodes it for you.

                To decode by hand: strip everything outside the
                `-----BEGIN $fence SAVE-----` / `-----END $fence SAVE-----` markers, then
                `base64 -d <file> > save.zip`.

                This gist is *secret*, which means unlisted — **not private**. Anyone who has its URL can
                read it. Do not share the URL.
            """.trimIndent()
        }
    }
}
