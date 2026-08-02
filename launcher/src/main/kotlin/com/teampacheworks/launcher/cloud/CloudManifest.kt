package com.teampacheworks.launcher.cloud

import com.teampacheworks.launcher.LauncherHost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One backup slot's row. `file` is the slot's identity - there is no side channel. */
data class CloudSlot(
    val file: String,
    val sha256: String,
    val bytes: Long,
    val note: String = "",
    /** ISO-8601 UTC. Display only - staleness is decided by [CloudManifest.revision], never by time. */
    val uploadedAt: String = ""
) {
    val slotNumber: Int? get() = CloudPayload.slotOf(file)
}

/**
 * `01-manifest.json` (design spec §3).
 *
 * 🔴 **Correctness never depends on this file.** The manifest is display data plus one cross-check
 * (the SHA-256). What decides whether a downloaded bundle may overwrite local saves is
 * [com.teampacheworks.launcher.save.SaveBundle.validate] run on the decoded bytes in a temp file.
 * A missing, stale or hand-mangled manifest degrades the UI and nothing more.
 *
 * 🔴 **Unknown fields survive a rewrite.** The manifest is held as a live [JsonObject] and mutated
 * in place: known fields go through the typed view, everything else is carried verbatim. A future
 * build that adds a field must be able to round-trip through this one without losing it.
 */
class CloudManifest private constructor(private var root: JsonObject) {

    val app: String get() = root[K_APP]?.jsonPrimitive?.contentOrNull ?: ""
    val version: Int get() = root[K_VERSION]?.jsonPrimitive?.intOrNull ?: 1

    /** Monotonic, +1 per successful write. A fetched manifest whose revision is *lower* than the
     *  one we last wrote is a stale edge copy and must never be shown as truth. */
    val revision: Int get() = root[K_REVISION]?.jsonPrimitive?.intOrNull ?: 0

    val updatedAt: String get() = root[K_UPDATED]?.jsonPrimitive?.contentOrNull ?: ""

    val slots: List<CloudSlot>
        get() = try {
            (root[K_SLOTS] as? JsonArray)?.mapNotNull { readSlot(it as? JsonObject ?: return@mapNotNull null) }
                ?: emptyList()
        } catch (t: Throwable) {
            emptyList()
        }

    fun find(file: String): CloudSlot? = slots.firstOrNull { it.file.equals(file, ignoreCase = true) }

    /** Content identity, not mtime: the §3 dedup gate. */
    fun findBySha(sha256: String): CloudSlot? =
        slots.firstOrNull { it.sha256.equals(sha256, ignoreCase = true) }

    /** Lowest free slot number in 1..[maxSlots], or null when the gist is full. */
    fun nextFreeSlot(maxSlots: Int): Int? {
        val used = slots.mapNotNull { it.slotNumber }.toSet()
        return (1..maxSlots).firstOrNull { it !in used }
    }

    // ---------------------------------------------------------------- mutation

    fun upsert(slot: CloudSlot) {
        val existing = (root[K_SLOTS] as? JsonArray)?.toMutableList() ?: mutableListOf()
        val idx = existing.indexOfFirst {
            (it as? JsonObject)?.get(K_FILE)?.jsonPrimitive?.contentOrNull
                ?.equals(slot.file, ignoreCase = true) == true
        }
        // Merge onto the existing node so unknown per-row fields survive.
        val base = if (idx >= 0) (existing[idx] as JsonObject) else JsonObject(emptyMap())
        val merged = JsonObject(
            base.toMutableMap().apply {
                put(K_FILE, JsonPrimitive(slot.file))
                put(K_SHA, JsonPrimitive(slot.sha256))
                put(K_BYTES, JsonPrimitive(slot.bytes))
                put(K_NOTE, JsonPrimitive(slot.note))
                put(K_UPLOADED, JsonPrimitive(slot.uploadedAt))
            }
        )
        if (idx >= 0) existing[idx] = merged else existing.add(merged)
        replaceSlots(existing)
        touch()
    }

    fun remove(file: String) {
        val existing = (root[K_SLOTS] as? JsonArray)?.filterNot {
            (it as? JsonObject)?.get(K_FILE)?.jsonPrimitive?.contentOrNull
                ?.equals(file, ignoreCase = true) == true
        } ?: emptyList()
        replaceSlots(existing)
        touch()
    }

    private fun replaceSlots(rows: List<kotlinx.serialization.json.JsonElement>) {
        root = JsonObject(root.toMutableMap().apply { put(K_SLOTS, JsonArray(rows)) })
    }

    private fun touch() {
        root = JsonObject(
            root.toMutableMap().apply {
                put(K_APP, JsonPrimitive(appId()))
                put(K_VERSION, JsonPrimitive(1))
                put(K_REVISION, JsonPrimitive(revision + 1))
                put(K_UPDATED, JsonPrimitive(nowIso()))
            }
        )
    }

    fun toJson(): String = PRETTY.encodeToString(JsonObject.serializer(), root)

    // ----------------------------------------------------------------- parsing

    sealed class Parse {
        class Ok(val manifest: CloudManifest) : Parse()
        data class Err(val error: String) : Parse()
    }

    companion object {
        const val FILE_NAME = "01-manifest.json"
        const val README_NAME = "00-README.md"

        private const val K_APP = "app"
        private const val K_VERSION = "version"
        private const val K_REVISION = "revision"
        private const val K_UPDATED = "updatedAt"
        private const val K_SLOTS = "slots"
        private const val K_FILE = "file"
        private const val K_SHA = "sha256"
        private const val K_BYTES = "bytes"
        private const val K_NOTE = "note"
        private const val K_UPLOADED = "uploadedAt"

        private val PRETTY = Json { prettyPrint = true; encodeDefaults = true }
        private val LENIENT = Json { ignoreUnknownKeys = true; isLenient = true }

        /** [com.teampacheworks.launcher.LauncherConfig.cloudAppId] of the installed config. */
        private fun appId(): String = try { LauncherHost.config.cloudAppId } catch (t: Throwable) { "app" }

        fun createEmpty(): CloudManifest = CloudManifest(
            buildJsonObject {
                put(K_APP, JsonPrimitive(appId()))
                put(K_VERSION, JsonPrimitive(1))
                put(K_REVISION, JsonPrimitive(0))
                put(K_UPDATED, JsonPrimitive(nowIso()))
                put(K_SLOTS, buildJsonArray { })
            }
        )

        fun tryParse(json: String?): Parse {
            if (json.isNullOrBlank()) return Parse.Err("The manifest file is empty.")
            return try {
                val node = LENIENT.parseToJsonElement(json)
                val obj = node as? JsonObject ?: return Parse.Err("The manifest is not a JSON object.")
                // A manifest with no slots array is empty, not broken.
                val fixed = if (obj[K_SLOTS] is JsonArray) obj
                else JsonObject(obj.toMutableMap().apply { put(K_SLOTS, JsonArray(emptyList())) })
                Parse.Ok(CloudManifest(fixed))
            } catch (t: Throwable) {
                Parse.Err("The manifest is not valid JSON: ${t.message}")
            }
        }

        /**
         * Discovery (§3 invariant 6): a gist is ours iff it holds this manifest with the
         * installed config's `cloudAppId`. [expectedAppId] can be supplied explicitly (e.g. from a
         * unit test, where no [LauncherHost] config is installed).
         */
        fun isOurManifest(json: String?, expectedAppId: String = appId()): Boolean = when (val p = tryParse(json)) {
            is Parse.Err -> false
            is Parse.Ok -> p.manifest.app.equals(expectedAppId, ignoreCase = true)
        }

        private fun readSlot(o: JsonObject): CloudSlot? {
            val file = o[K_FILE]?.jsonPrimitive?.contentOrNull ?: return null
            return CloudSlot(
                file = file,
                sha256 = o[K_SHA]?.jsonPrimitive?.contentOrNull ?: "",
                bytes = o[K_BYTES]?.jsonPrimitive?.longOrNull ?: 0L,
                note = o[K_NOTE]?.jsonPrimitive?.contentOrNull ?: "",
                uploadedAt = o[K_UPLOADED]?.jsonPrimitive?.contentOrNull ?: ""
            )
        }

        fun nowIso(): String {
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            f.timeZone = TimeZone.getTimeZone("UTC")
            return f.format(Date())
        }
    }
}
