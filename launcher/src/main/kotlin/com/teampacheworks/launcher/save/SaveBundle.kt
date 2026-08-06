package com.teampacheworks.launcher.save

import com.teampacheworks.launcher.LauncherHost
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
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

    /**
     * Every bundle-eligible save file in [dir], sorted by its path relative to [dir] (which is
     * also its bare name when [com.teampacheworks.launcher.LauncherConfig.recursiveSaves] is off -
     * the flat scan is unchanged bit-for-bit). Never throws.
     */
    fun listSaveFiles(dir: File): List<File> = try {
        if (LauncherHost.config.recursiveSaves) listSaveFilesRecursive(dir)
        else listSaveFilesFlat(dir)
    } catch (t: Throwable) {
        emptyList()
    }

    private fun listSaveFilesFlat(dir: File): List<File> =
        (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && isSaveFileName(it.name) }
            .sortedBy { it.name }

    /**
     * Walks the whole [dir] tree (design spec §5 gap). Skips symlinks (files and directories - a
     * symlinked directory is simply never descended into, matching `Files.walk`'s default of not
     * following links), any path component starting with `.` (dotfiles/dirs), `cloud.token`, and
     * the launcher/cloud SharedPreferences xml file names - none of those are ever save data even
     * if a loose [com.teampacheworks.launcher.LauncherConfig.savePatterns] glob would otherwise
     * match them.
     */
    private fun listSaveFilesRecursive(dir: File): List<File> {
        if (!dir.isDirectory) return emptyList()
        val root = dir.toPath()
        val result = ArrayList<File>()
        Files.walk(root).use { stream ->
            stream.forEach { path ->
                if (path == root) return@forEach
                if (Files.isSymbolicLink(path)) return@forEach
                if (!Files.isRegularFile(path)) return@forEach
                val rel = root.relativize(path)
                for (i in 0 until rel.nameCount) {
                    if (rel.getName(i).toString().startsWith(".")) return@forEach
                }
                val relPath = rel.toString().replace('\\', '/')
                val file = path.toFile()
                if (isHardSkip(file.name)) return@forEach
                if (isSaveFileName(relPath)) result.add(file)
            }
        }
        return result.sortedBy { relativePath(dir, it) }
    }

    /** Never treated as save data, whatever [com.teampacheworks.launcher.LauncherConfig.savePatterns] says. */
    private fun isHardSkip(basename: String): Boolean {
        val config = LauncherHost.config
        val lower = basename.lowercase(Locale.US)
        return lower == "cloud.token" ||
            lower == "${config.prefsName}.xml".lowercase(Locale.US) ||
            lower == "${config.cloudPrefsName}.xml".lowercase(Locale.US)
    }

    /** [file]'s path relative to [root], forward-slash normalized. */
    fun relativePath(root: File, file: File): String =
        root.toPath().relativize(file.toPath()).toString().replace('\\', '/')

    /**
     * Matches [name] - a bare file name, or (when
     * [com.teampacheworks.launcher.LauncherConfig.recursiveSaves] is on) a `/`-normalized path
     * relative to `filesDir` - against
     * [com.teampacheworks.launcher.LauncherConfig.savePatterns] (glob: `*`/`?`, case-insensitive)
     * and rejects anything in [com.teampacheworks.launcher.LauncherConfig.saveExcludeNames]. Both
     * the bare name and the full relative path are checked against patterns and exclusions, so a
     * flat name (no `/`) behaves exactly as it always has.
     */
    fun isSaveFileName(name: String): Boolean {
        val config = LauncherHost.config
        val normalized = name.replace('\\', '/').trimStart('/')
        val basename = normalized.substringAfterLast('/')
        val lowerBase = basename.lowercase(Locale.US)
        val lowerPath = normalized.lowercase(Locale.US)
        if (lowerBase in config.saveExcludeNames || lowerPath in config.saveExcludeNames) return false
        return config.savePatterns.any { pattern ->
            val p = pattern.lowercase(Locale.US)
            globMatches(p, lowerBase) || globMatches(p, lowerPath)
        }
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

    /**
     * Deterministic bundle of every save file currently in [dir]. Entry names are relative paths
     * (forward-slash) under [dir] when
     * [com.teampacheworks.launcher.LauncherConfig.recursiveSaves] is on, bare names otherwise -
     * identical to what [listSaveFiles] just walked, so the two never disagree.
     */
    fun createBundleFromDir(dir: File): ByteArray {
        val recursive = LauncherHost.config.recursiveSaves
        val entries = LinkedHashMap<String, ByteArray>()
        for (f in listSaveFiles(dir)) {
            val key = if (recursive) relativePath(dir, f) else f.name
            entries[key] = f.readBytes()
        }
        return createBundle(entries)
    }

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
     * Directory entries are skipped.
     *
     * When [com.teampacheworks.launcher.LauncherConfig.recursiveSaves] is off, names are flattened
     * to their basename exactly as before - existing flat-bundle behavior (and therefore its
     * SHA-256 identity) is unchanged bit-for-bit. When it is on, entries keep their path relative to
     * the save root, but every entry name is run through [sanitizeZipEntryName] first: any entry
     * whose name is absolute, escapes the root via a `..` segment, or has a dotfile/dir component is
     * dropped rather than trusted (zip-slip protection - a hostile or corrupt bundle can never write
     * outside `filesDir` on import).
     */
    fun readEntries(bytes: ByteArray): Map<String, ByteArray>? {
        if (!looksLikeZip(bytes)) return null
        val recursive = LauncherHost.config.recursiveSaves
        return try {
            val result = LinkedHashMap<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (e.isDirectory) { zin.closeEntry(); continue }
                    val key = if (recursive) sanitizeZipEntryName(e.name)
                    else e.name.substringAfterLast('/').substringAfterLast('\\')
                    if (key.isNullOrEmpty()) { zin.closeEntry(); continue }
                    result[key] = zin.readBytes()
                    zin.closeEntry()
                }
            }
            if (result.isEmpty()) null else result
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Normalizes a zip entry name to a safe, `/`-relative path, or null if it must be rejected:
     * absolute paths (leading `/`, or a Windows drive like `C:`), any `.`/`..` segment, or any
     * dotfile/dir segment (matching the same hidden-entry rule the recursive walk applies on
     * export). This is the only thing standing between an untrusted bundle and zip-slip.
     */
    fun sanitizeZipEntryName(rawName: String): String? {
        val normalized = rawName.replace('\\', '/')
        if (normalized.isEmpty()) return null
        if (normalized.startsWith('/')) return null
        if (normalized.length >= 2 && normalized[1] == ':') return null // C:\... style
        val parts = normalized.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        for (part in parts) {
            if (part == "." || part == "..") return null
            if (part.startsWith(".")) return null
        }
        return parts.joinToString("/")
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
