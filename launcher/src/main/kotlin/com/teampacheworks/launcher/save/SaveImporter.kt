package com.teampacheworks.launcher.save

import com.teampacheworks.launcher.cloud.CloudPayload
import java.io.File

/**
 * The **one** save-writing path (design spec §2 commit pattern).
 *
 * Order is not negotiable:
 *
 *   1. temp file in `cacheDir` - never in `filesDir`, which must never hold a half-checked save
 *   2. **validate on the temp file**
 *   3. `pre-import.bak.zip` of everything currently in `filesDir`
 *   4. replace
 *   5. byte-for-byte rollback if any part of (4) fails
 *   6. temp deleted in `finally` on every branch - success, refusal and exception alike
 *
 * Step 2 is the survival invariant: a truncated download or a hand-mangled zip is stopped **before**
 * a single local save is touched.
 *
 * Pure `java.io` so the whole thing is unit-testable on a PC.
 */
object SaveImporter {

    sealed class Prepared {
        /** Passed every structural check; nothing has been written yet. */
        class Ready(
            val bundle: ByteArray,
            val validation: SaveBundle.Validation,
            val fromFence: Boolean
        ) : Prepared()

        data class Rejected(val reason: String) : Prepared()
    }

    data class CommitResult(val ok: Boolean, val written: Int = 0, val error: String? = null)

    // ------------------------------------------------------------------ prepare

    /**
     * Content-based detection - the file name and the SAF provider's mime guess are never trusted.
     * Tries (1) a raw zip, then (2) a base64-fenced cloud payload wrapping a zip.
     */
    fun prepare(raw: ByteArray): Prepared {
        if (raw.isEmpty()) return Prepared.Rejected("The file is empty.")

        if (SaveBundle.looksLikeZip(raw)) {
            val v = SaveBundle.validate(raw)
            return if (v.ok) Prepared.Ready(raw, v, fromFence = false)
            else Prepared.Rejected(v.error ?: "This zip is not a valid save bundle.")
        }

        val text = String(raw, Charsets.UTF_8)
        if (CloudPayload.looksLikeFenced(text)) {
            return when (val d = CloudPayload.decode(text)) {
                is CloudPayload.Decoded.Err -> Prepared.Rejected(d.message)
                is CloudPayload.Decoded.Ok -> {
                    val v = SaveBundle.validate(d.bytes)
                    if (v.ok) Prepared.Ready(d.bytes, v, fromFence = true)
                    else Prepared.Rejected(v.error ?: "The decoded payload is not a save bundle.")
                }
            }
        }

        return Prepared.Rejected(
            "That file is neither a save bundle (zip) nor a cloud payload " +
                "(a text file with a \"${CloudPayload.BEGIN_FENCE}\" line)."
        )
    }

    // ------------------------------------------------------------------- commit

    fun commit(filesDir: File, cacheDir: File, ready: Prepared.Ready): CommitResult {
        var temp: File? = null
        // Everything needed to put filesDir back exactly as it was.
        val originals = LinkedHashMap<String, ByteArray?>()
        var replaced = false
        try {
            // 1. temp file
            if (!cacheDir.exists()) cacheDir.mkdirs()
            temp = File(cacheDir, "import-${System.currentTimeMillis()}.tmp.zip")
            temp.writeBytes(ready.bundle)

            // 2. validate ON THE TEMP FILE - the bytes we will actually read back, not the ones
            //    that happened to be in memory a moment ago.
            val onDisk = temp.readBytes()
            val v = SaveBundle.validate(onDisk)
            if (!v.ok) return CommitResult(false, error = v.error ?: "The bundle failed validation.")
            val entries = SaveBundle.readEntries(onDisk)
                ?: return CommitResult(false, error = "The bundle could not be re-read from disk.")
            val saves = entries.filterKeys { SaveBundle.isSaveFileName(it) }
            if (saves.isEmpty()) return CommitResult(false, error = "The bundle holds no save files.")

            // 3. pre-import backup of EVERYTHING currently there (not just what we overwrite)
            val backup = SaveBundle.createBundleFromDir(filesDir)
            File(filesDir, SaveBundle.PRE_IMPORT_BACKUP).writeBytes(backup)

            // 4. replace
            for ((name, body) in saves) {
                val target = File(filesDir, name)
                originals[name] = if (target.isFile) target.readBytes() else null
                replaced = true
                target.writeBytes(body)
            }
            return CommitResult(true, written = saves.size)
        } catch (t: Throwable) {
            // 5. rollback, byte for byte
            if (replaced) {
                for ((name, body) in originals) {
                    try {
                        val target = File(filesDir, name)
                        if (body == null) target.delete() else target.writeBytes(body)
                    } catch (ignored: Throwable) {
                        // Nothing better to try; the pre-import backup zip is the last resort.
                    }
                }
            }
            return CommitResult(false, error = t.message ?: t.javaClass.simpleName)
        } finally {
            // 6. always
            try { temp?.delete() } catch (ignored: Throwable) {}
        }
    }
}
