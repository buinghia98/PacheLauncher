package com.teampacheworks.launcher.deploy

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.teampacheworks.launcher.log.LauncherLog
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Imports a whole staged deploy folder into the app's own external files directory, by **moving**.
 *
 * ### Why move and not copy
 * The package is the entire install -- on this project's reference game, ~14.5 GB. A copy needs the
 * set twice over on a device that has it once, which is exactly the device most people are on. So
 * the unit of work is one file at a time: copy it, verify it, put it in place, **then delete the
 * source**. Peak extra storage is the largest single file, not the set.
 *
 * ### Why a partially-moved source is the normal state, not damage
 * Because the source shrinks as the destination grows, cancelling mid-import (or losing the process)
 * leaves a set that is half here and half there. That is the state a resume reads, so the validation
 * pass deliberately does **not** ask "does the source match the manifest" the way
 * [com.teampacheworks.launcher.assets.AssetStorage] does. It asks, per entry:
 *
 * > is this file at the source with the right size, **or** already at the destination with the
 * > right size?
 *
 * Anything that answers yes is workable. Only an entry that is in neither place, or in one of them
 * at the wrong size, is a real fault -- and that is reported before a single destination byte moves.
 *
 * ### What is never overwritten
 * [DeployImportConfig.preservedPaths] is the same fence the staging scripts draw: the package owns
 * the runtime, the *player* owns the settings, the mod data and the saves. An entry landing on an
 * existing preserved file is dropped (and its source consumed, because this is still a move); an
 * entry landing where nothing exists yet installs normally, which is what seeds a fresh device.
 */
object DeployStorage {

    /** Live counters for the progress UI. [label] is the file currently being handled. */
    data class Progress(
        val files: Long,
        val totalFiles: Long,
        val bytes: Long,
        val totalBytes: Long,
        val label: String
    )

    data class Result(
        val movedFiles: Long,
        val movedBytes: Long,
        /** Already at the destination from an earlier run. */
        val resumedFiles: Long,
        /** Dropped because a preserved file was already there. */
        val preservedFiles: Long,
        val blocks: List<String>,
        val mods: List<String>,
        val tiers: List<String>,
        val fastMoves: Long
    )

    class CancelledException : RuntimeException("Import cancelled")

    /** Present-but-unreadable diagnosis, the failure an adb push without the `chmod`s leaves. */
    data class Diagnosis(val present: List<String>, val unreadable: List<String>) {
        val isUnreadable: Boolean get() = unreadable.isNotEmpty()
    }

    // ---------------------------------------------------------------- locating the payload

    /**
     * Resolves the payload folder from whatever the user actually picked.
     *
     * Three picks are all reasonable and all accepted: the package folder itself, the `files/`
     * payload inside it, or a parent directory (a USB drive root, `Download`) that contains the
     * package folder. Anything else gets a message naming the folder to look for, because "invalid
     * selection" on a document picker is the least actionable error there is.
     */
    fun locatePayload(context: Context, config: DeployImportConfig, treeUri: Uri): DocumentFile {
        val picked = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("That folder could not be opened")
        candidates(config, picked).forEach { candidate ->
            if (candidate.findFile(config.manifestName)?.isFile == true) return candidate
        }
        error(
            "No ${config.manifestName} in the folder you picked. Choose the folder named " +
                "${config.packageFolderName}, or the ${config.payloadFolderName} folder inside it."
        )
    }

    private fun candidates(config: DeployImportConfig, picked: DocumentFile): List<DocumentFile> =
        listOfNotNull(
            picked,                                                     // …/files
            picked.findFile(config.payloadFolderName),                  // …/<package>
            picked.findFile(config.packageFolderName)                   // …/Download
                ?.findFile(config.payloadFolderName)
        ).filter { it.isDirectory }

    fun readManifest(context: Context, config: DeployImportConfig, payload: DocumentFile): DeployManifest {
        val doc = payload.findFile(config.manifestName)
            ?: error("${config.manifestName} is missing")
        val text = context.contentResolver.openInputStream(doc.uri).use { input ->
            requireNotNull(input) { "${config.manifestName} could not be read" }
            input.bufferedReader().readText()
        }
        return DeployManifest.parse(JSONObject(text), config)
    }

    // ---------------------------------------------------------------- the import

    /**
     * Verifies then moves. Blocking; call it off the main thread.
     *
     * @param cancelled polled between files. A cancel is not a failure: it throws
     *   [CancelledException] and leaves a resumable half-moved set with the sentinel still in place.
     */
    fun import(
        context: Context,
        config: DeployImportConfig,
        treeUri: Uri,
        cancelled: () -> Boolean,
        onProgress: (Progress) -> Unit
    ): Result {
        val payload = locatePayload(context, config, treeUri)
        val manifest = readManifest(context, config, payload)
        val root = config.destinationDirectory(context)
            ?: error("This device's storage for the game is unavailable")
        if (!root.exists() && !root.mkdirs()) error("Could not create ${root.absolutePath}")

        // One recursive listing of the source. DocumentFile.findFile() is a query per call, so
        // walking 8,400 entries by path would be 8,400 round trips through the provider; the whole
        // tree comes back in a few hundred.
        onProgress(Progress(0, manifest.fileCount, 0, manifest.totalBytes, "Reading the folder…"))
        val source = LinkedHashMap<String, DocumentFile>()
        indexTree(payload, "", source, cancelled)

        val plan = validate(config, manifest, source, root)
        LauncherLog.write(
            "deploy",
            "import plan: ${plan.steps.size} step(s), ${plan.resumed} already installed, " +
                "${plan.preserved} preserved, ${manifest.fileCount} listed, " +
                "blocks=${manifest.blocks.map { it.id }} tiers=${manifest.tiers}"
        )

        val sentinel = File(root, config.sentinelName)
        sentinel.writeText("import in progress")

        var movedFiles = 0L
        var movedBytes = 0L
        var fastMoves = 0L
        // Counted over ALL steps, resumed and preserved included: the denominator is the manifest,
        // so a resumed import must start its bar where the previous one stopped, not at zero.
        var doneFiles = 0L
        var doneBytes = 0L
        var lastTick = 0L

        try {
            for (step in plan.steps) {
                if (cancelled()) throw CancelledException()
                val doc = source[step.entry.path]
                when {
                    // Preserved and already present: the player's copy wins, the package's is
                    // consumed. Doing nothing here would leave the source folder un-emptiable.
                    step.preservedHit -> doc?.delete()

                    doc == null -> Unit // resumed: verified at the destination during validate()

                    else -> {
                        val target = resolveInside(root, step.entry.path)
                        target.parentFile?.mkdirs()
                        if (fastMove(doc, target)) fastMoves++
                        else copyVerifyDelete(context, doc, target, step.entry)
                        movedFiles++
                        movedBytes += step.entry.size
                    }
                }
                doneFiles++
                doneBytes += step.entry.size
                val now = System.currentTimeMillis()
                if (now - lastTick >= 120 || doneFiles == manifest.fileCount) {
                    lastTick = now
                    onProgress(Progress(doneFiles, manifest.fileCount, doneBytes, manifest.totalBytes,
                        step.entry.path))
                }
            }

            // The source is now empty of everything the manifest named. Take the manifest itself
            // last, so an interrupted run can always still be resumed from it.
            payload.findFile(config.manifestName)?.delete()
            pruneEmpty(payload)

            File(root, config.receiptName).writeText(receipt(manifest).toString())
            sentinel.delete()
        } catch (t: Throwable) {
            // The sentinel deliberately survives every failure path, cancellation included: a
            // half-moved tree must never satisfy a launch gate. A completed retry removes it.
            throw t
        }

        return Result(
            movedFiles = movedFiles,
            movedBytes = movedBytes,
            resumedFiles = plan.resumed,
            preservedFiles = plan.preserved,
            blocks = manifest.blocks.map { it.id },
            mods = manifest.providedMods,
            tiers = manifest.tiers,
            fastMoves = fastMoves
        )
    }

    // ---------------------------------------------------------------- validation

    private class Step(val entry: DeployEntry, val preservedHit: Boolean)

    private class Plan(
        val steps: List<Step>,
        val resumed: Long,
        val preserved: Long
    )

    /**
     * Nothing is written until every listed entry is accounted for. Errors name the first few
     * offenders and how many more there are; a list of 8,400 paths is not a message.
     */
    private fun validate(
        config: DeployImportConfig,
        manifest: DeployManifest,
        source: Map<String, DocumentFile>,
        root: File
    ): Plan {
        val steps = ArrayList<Step>(manifest.files.size)
        val problems = ArrayList<String>()
        var resumed = 0L
        var preserved = 0L

        for (entry in manifest.files) {
            val target = resolveInside(root, entry.path)
            val doc = source[entry.path]
            // Deliberately NOT size-checked. Under a preserved path the destination is the
            // player's file and any size is legitimate -- `enabled-mods.txt` is rewritten on every
            // single launch, so demanding the manifest's size there would fail a resume for the
            // most ordinary reason there is.
            val preservedHit = config.isPreserved(entry.path) && target.isFile

            when {
                // Counted as "kept your file" ONLY when a packaged copy is actually being
                // discarded in its favour. On a resume, a preserved entry an earlier run already
                // moved has no source left, and calling that "skipped to keep your existing
                // settings" is a lie about the user's own data -- it is the same resume as any
                // other file. Measured on the first device run, where it mislabelled 269 of them.
                preservedHit && doc != null -> { preserved++; steps += Step(entry, true) }
                preservedHit -> { resumed++; steps += Step(entry, true) }

                doc != null && doc.length() == entry.size -> steps += Step(entry, false)

                doc != null -> problems += "${entry.path} is ${doc.length()} bytes, expected ${entry.size}"

                // Source gone: this entry was moved by an earlier run. That is the ordinary resume
                // case and must never read as corruption.
                target.isFile && target.length() == entry.size -> {
                    resumed++; steps += Step(entry, false)
                }

                target.isFile -> problems +=
                    "${entry.path} is already installed at ${target.length()} bytes, expected ${entry.size}"

                else -> problems += "${entry.path} is missing from the folder"
            }
            if (problems.size > 24) break
        }

        if (problems.isNotEmpty()) {
            val head = problems.take(4).joinToString("; ")
            val more = if (problems.size > 4) " (and ${problems.size - 4} more)" else ""
            error("The deploy folder does not match its manifest: $head$more")
        }
        return Plan(steps, resumed, preserved)
    }

    // ---------------------------------------------------------------- moving one file

    /**
     * A true rename, when the platform will give us one.
     *
     * `DocumentsContract.moveDocument` only moves between two SAF documents, and the destination
     * here is a plain [File] in the app's own external data directory that no provider will hand out
     * a tree uri for. So the only real rename available is the filesystem one, and it needs the
     * source's actual path plus permission to unlink it -- which an app without legacy storage
     * access does not have for `Download`. This tries anyway, once per file, at the cost of a
     * `renameTo` that fails fast; when it works a 14 GB import becomes metadata operations.
     *
     * Returns false to mean "not available", never to mean "failed halfway": [File.renameTo] is
     * atomic, so a false leaves the source exactly as it was and the copy path takes over.
     */
    private fun fastMove(doc: DocumentFile, target: File): Boolean {
        val path = legacyPath(doc.uri) ?: return false
        val src = File(path)
        if (!src.isFile || !src.canRead()) return false
        return try {
            if (target.exists() && !target.delete()) false else src.renameTo(target)
        } catch (t: Throwable) {
            false
        }
    }

    /** `primary:Download/x/y` -> `/storage/emulated/0/Download/x/y`, for the external provider only. */
    private fun legacyPath(uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        return try {
            val id = DocumentsContract.getDocumentId(uri)
            val volume = id.substringBefore(':', "")
            val relative = id.substringAfter(':', "")
            if (relative.isEmpty()) return null
            when (volume) {
                "primary" -> "${android.os.Environment.getExternalStorageDirectory()}/$relative"
                else -> "/storage/$volume/$relative"
            }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Copy into a temp name, verify, rename into place, then unlink the source -- in that order, so
     * there is no instant at which the file exists in neither place.
     */
    private fun copyVerifyDelete(
        context: Context,
        doc: DocumentFile,
        target: File,
        entry: DeployEntry
    ) {
        val temp = File(target.parentFile, ".${target.name}.pache-import.tmp")
        try {
            val digest = entry.sha256?.let { MessageDigest.getInstance("SHA-256") }
            var written = 0L
            context.contentResolver.openInputStream(doc.uri).use { input ->
                requireNotNull(input) { "${entry.path} could not be read from the folder" }
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(1 shl 18)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest?.update(buffer, 0, n)
                        written += n
                    }
                }
            }
            require(written == entry.size) {
                "${entry.path} copied $written bytes, expected ${entry.size}"
            }
            digest?.let {
                val actual = it.digest().joinToString("") { b -> "%02x".format(b) }
                require(actual.equals(entry.sha256, ignoreCase = true)) {
                    "${entry.path} failed its checksum"
                }
            }
            if (target.exists() && !target.delete()) error("Could not replace ${entry.path}")
            if (!temp.renameTo(target)) error("Could not install ${entry.path}")
            // Only now is it safe to lose the source.
            if (!doc.delete()) {
                LauncherLog.w("deploy") { "installed ${entry.path} but could not remove the source copy" }
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    // ---------------------------------------------------------------- source tree helpers

    private fun indexTree(
        folder: DocumentFile,
        prefix: String,
        out: MutableMap<String, DocumentFile>,
        cancelled: () -> Boolean
    ) {
        if (cancelled()) throw CancelledException()
        for (child in folder.listFiles()) {
            val name = child.name ?: continue
            if (name == "." || name == ".." || '/' in name) continue
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isDirectory) indexTree(child, path, out, cancelled)
            else if (child.isFile) out[path] = child
        }
    }

    /** Depth-first removal of directories the move emptied. Files that are not ours are left alone. */
    private fun pruneEmpty(folder: DocumentFile): Boolean {
        var empty = true
        for (child in folder.listFiles()) {
            if (child.isDirectory) { if (!pruneEmpty(child)) empty = false }
            else empty = false
        }
        return if (empty) folder.delete() else false
    }

    // ---------------------------------------------------------------- diagnosis + receipt

    /**
     * Distinguishes "not installed" from "installed and unreadable by this app".
     *
     * A directory another uid created in the app's external data dir is `2770 <thatuid>:ext_data_rw`.
     * The app is not in `ext_data_rw`, so it falls through to `other`, which has no bits -- `stat`
     * still succeeds (the parent is the app's own) but `listFiles()` comes back null. That pair,
     * exists-but-cannot-list, is the whole signature, and reporting it as "Not installed" sends the
     * user looking for files that are sitting right there.
     */
    fun diagnose(context: Context, config: DeployImportConfig): Diagnosis {
        val root = config.destinationDirectory(context) ?: return Diagnosis(emptyList(), emptyList())
        val present = ArrayList<String>()
        val unreadable = ArrayList<String>()
        for (relative in config.readabilityProbePaths) {
            val file = File(root, relative.replace('\\', '/').trim('/'))
            if (!file.exists()) continue
            present += relative
            val readable = if (file.isDirectory) file.listFiles() != null else file.canRead()
            if (!readable) unreadable += relative
        }
        return Diagnosis(present, unreadable)
    }

    /** True while a half-moved tree is on disk. Launch gates must refuse this. */
    fun importInProgress(context: Context, config: DeployImportConfig): Boolean {
        val root = config.destinationDirectory(context) ?: return false
        return File(root, config.sentinelName).exists()
    }

    private fun receipt(manifest: DeployManifest): JSONObject = JSONObject().apply {
        put("schemaVersion", 1)
        put("gameId", manifest.gameId)
        put("package", manifest.packageName)
        put("importedUtc", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date()))
        manifest.createdUtc?.let { put("packageCreatedUtc", it) }
        put("fileCount", manifest.fileCount)
        put("totalBytes", manifest.totalBytes)
        put("blocks", org.json.JSONArray(manifest.blocks.map { it.id }))
        put("tiers", org.json.JSONArray(manifest.tiers))
        put("mods", org.json.JSONArray(manifest.providedMods))
    }

    private fun resolveInside(root: File, relative: String): File {
        val result = File(root, relative).canonicalFile
        require(result.toPath().startsWith(root.canonicalFile.toPath())) {
            "Path escapes the install root: $relative"
        }
        return result
    }
}
