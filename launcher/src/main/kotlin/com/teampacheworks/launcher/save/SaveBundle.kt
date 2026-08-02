package com.teampacheworks.launcher.save

import com.teampacheworks.launcher.LauncherHost
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The one and only save-bundle format (design spec §2).
 *
 * A bundle is a **zip of every save file in the game's save directory that matches
 * [com.teampacheworks.launcher.LauncherConfig.savePatterns]**, minus anything named in
 * [com.teampacheworks.launcher.LauncherConfig.saveExcludeNames] (typically machine-local settings
 * files - volume/keybinds - that live alongside saves but are not playthrough progress).
 *
 * The zip is **deterministic**: entries are sorted by name and every timestamp is pinned to
 * 1980-01-01 00:00:00 local, which is exactly the DOS epoch floor, so the same set of save bytes
 * always produces the same zip bytes and therefore the same SHA-256. The cloud dedup gate
 * (§3 invariant 3) depends on that: it compares content identity, never mtime.
 *
 * Pure JVM on purpose - no `android.*` here - so the whole format is unit-testable on a PC.
 */
object SaveBundle {

    /** Written into filesDir right before an import overwrites anything (§2 commit pattern). */
    const val PRE_IMPORT_BACKUP = "pre-import.bak.zip"

    private const val ZIP_LOCAL_HEADER = 0x04034b50

    // ------------------------------------------------------------------ listing

    /** Every bundle-eligible save file in [dir], sorted by name. Never throws. */
    fun listSaveFiles(dir: File): List<File> = try {
        (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && isSaveFileName(it.name) }
            .sortedBy { it.name }
    } catch (t: Throwable) {
        emptyList()
    }

    /**
     * Matches [name] against [com.teampacheworks.launcher.LauncherConfig.savePatterns] (glob:
     * `*`/`?`, case-insensitive) and rejects anything in
     * [com.teampacheworks.launcher.LauncherConfig.saveExcludeNames].
     */
    fun isSaveFileName(name: String): Boolean {
        val config = LauncherHost.config
        val lower = name.lowercase(Locale.US)
        if (lower in config.saveExcludeNames) return false
        return config.savePatterns.any { globMatches(it.lowercase(Locale.US), lower) }
    }

    /** `*` matches any run of characters (including none); `?` matches exactly one. */
    fun globMatches(pattern: String, name: String): Boolean {
        val regex = buildString {
            append('^')
            for (c in pattern) {
                when (c) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(c.toString()))
                }
            }
            append('$')
        }
        return Regex(regex).matches(name)
    }

    // ------------------------------------------------------------------ writing

    /** Deterministic zip of [entries] (name -> bytes). */
    fun createBundle(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream(entries.values.sumOf { it.size } + 1024)
        ZipOutputStream(out).use { zip ->
            // Sorted: entry order is part of the byte identity.
            for (name in entries.keys.sorted()) {
                val body = entries.getValue(name)
                val e = ZipEntry(name)
                e.time = FIXED_TIME
                zip.putNextEntry(e)
                zip.write(body)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Deterministic bundle of every save file currently in [dir]. */
    fun createBundleFromDir(dir: File): ByteArray =
        createBundle(listSaveFiles(dir).associate { it.name to it.readBytes() })

    // ------------------------------------------------------------------ reading

    /** True if [bytes] begins with a zip local-file-header signature. */
    fun looksLikeZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            (bytes[0].toInt() and 0xff) or
            ((bytes[1].toInt() and 0xff) shl 8) or
            ((bytes[2].toInt() and 0xff) shl 16) or
            ((bytes[3].toInt() and 0xff) shl 24) == ZIP_LOCAL_HEADER

    /**
     * Reads every entry of a zip into memory. Returns null when [bytes] is not a readable zip.
     * Directory entries are skipped; names are flattened to their basename so a bundle produced
     * by some other tool cannot write outside the save directory (zip-slip).
     */
    fun readEntries(bytes: ByteArray): Map<String, ByteArray>? {
        if (!looksLikeZip(bytes)) return null
        return try {
            val result = LinkedHashMap<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (e.isDirectory) { zin.closeEntry(); continue }
                    val base = e.name.substringAfterLast('/').substringAfterLast('\\')
                    if (base.isEmpty()) { zin.closeEntry(); continue }
                    result[base] = zin.readBytes()
                    zin.closeEntry()
                }
            }
            if (result.isEmpty()) null else result
        } catch (t: Throwable) {
            null
        }
    }

    // --------------------------------------------------------------- validation

    /**
     * @param fatal      the bundle cannot be used at all
     * @param headerWarn every structural check passed but at least one entry does not start with
     *                   the required leading byte configured via [hasValidHeader]. Surfaced as an
     *                   override-able warning rather than a hard stop.
     */
    data class Validation(
        val ok: Boolean,
        val error: String? = null,
        val saveNames: List<String> = emptyList(),
        val headerWarn: List<String> = emptyList()
    ) {
        val fatal: Boolean get() = !ok
    }

    /** §2: zip readable + >= 1 matching save entry + each entry passes [hasValidHeader]. */
    fun validate(bytes: ByteArray): Validation {
        val entries = readEntries(bytes)
            ?: return Validation(false, "This file is not a readable save bundle (zip).")
        val saves = entries.keys.filter { isSaveFileName(it) }.sorted()
        if (saves.isEmpty()) {
            return Validation(false, "This bundle contains no save files matching the configured pattern.")
        }
        val warn = ArrayList<String>()
        for (name in saves) {
            val body = entries.getValue(name)
            if (body.isEmpty()) return Validation(false, "Save '$name' inside the bundle is empty.")
            if (!hasValidHeader(body)) warn += name
        }
        return Validation(true, null, saves, warn)
    }

    /**
     * Optional lightweight structural sanity check on one save file's bytes, run per-entry during
     * [validate]. The default accepts anything non-empty; a host whose save format has a magic
     * byte/header worth checking (e.g. a fixed leading byte or short signature) can override this
     * by setting [headerCheck].
     */
    fun hasValidHeader(body: ByteArray): Boolean = headerCheck(body)

    /** Defaults to "anything non-empty is fine"; override for a format-specific sanity check. */
    @Volatile
    var headerCheck: (ByteArray) -> Boolean = { it.isNotEmpty() }

    // -------------------------------------------------------------------- hash

    fun sha256Hex(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append(HEX[(b.toInt() shr 4) and 0xf]).append(HEX[b.toInt() and 0xf])
        return sb.toString()
    }

    fun hashesMatch(a: String?, b: String?): Boolean =
        !a.isNullOrEmpty() && !b.isNullOrEmpty() && a.trim().equals(b.trim(), ignoreCase = true)

    private const val HEX = "0123456789abcdef"

    /**
     * 1980-01-01 00:00:00 **local** - the DOS-time floor, so java.util.zip stores exactly
     * 0x00210000 whatever the device timezone is. Using a plain epoch constant here would make the
     * zip bytes (and therefore the SHA-256) timezone-dependent.
     */
    private val FIXED_TIME: Long = Calendar.getInstance().run {
        clear()
        set(1980, Calendar.JANUARY, 1, 0, 0, 0)
        timeInMillis
    }
}
