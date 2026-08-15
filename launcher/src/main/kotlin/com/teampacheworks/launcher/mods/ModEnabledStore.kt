package com.teampacheworks.launcher.mods

import android.content.Context
import android.content.SharedPreferences
import com.teampacheworks.launcher.log.LauncherLog
import java.io.File

/**
 * Reads and writes the enabled-mod set.
 *
 * Two representations, deliberately:
 *
 *  * **SharedPreferences** - one boolean per mod row, the launcher's own view. Cheap to read while
 *    drawing the screen, and it survives the file being deleted by a re-stage.
 *  * **[ModManagementConfig.enabledFile]** - one guid per line, the game's view. Written on every
 *    change, before the game can be started. This is a *derived* artefact: it is the dependency
 *    closure of the checked rows, not the checked rows themselves.
 *
 * The file is plain text so it can be `cat`-ed and `echo`-ed over adb, which is what makes
 * bisecting a bad combination a one-line operation instead of a UI session.
 */
object ModEnabledStore {

    private const val HEADER =
        "# Enabled mods, one plugin guid per line. Written by the launcher's Manage Mods screen.\n" +
        "# The game's mod loader filters its plugin order against this list; a guid it does not\n" +
        "# know is logged and ignored, and a missing or unreadable file means base plugins only.\n" +
        "# Support libraries are included automatically - do not hand-remove one a mod needs.\n"

    /**
     * The mods the player has switched on (the rows, not the closure) - the launcher's own view,
     * including rows whose files are not on this device. Use [runnableMods] for anything the GAME
     * will read.
     */
    fun selectedMods(prefs: SharedPreferences, config: ModManagementConfig): Set<String> =
        config.mods.filter { prefs.getBoolean(config.prefsKeyFor(it.guid), it.defaultOn) }
            .map { it.guid }
            .toSet()

    /**
     * [selectedMods] minus anything whose files this install does not carry.
     *
     * A deploy package may legally omit an optional block, which leaves a mod listed by the build
     * but absent from the disk. Its stored preference is deliberately left alone - switching to a
     * full package later must bring the player's choice back, not silently reset it - so the filter
     * lives here, at the boundary where the set stops being a UI state and becomes an instruction
     * to the game's loader.
     */
    fun runnableMods(
        context: Context,
        prefs: SharedPreferences,
        config: ModManagementConfig
    ): Set<String> {
        val available = config.availableMods(context)
        return selectedMods(prefs, config).filter { it in available }.toSet()
    }

    /**
     * The master switch. Reads false when this install carries no runnable mod at all, whatever the
     * stored preference says: a vanilla-only package is a supported package, and arming a mod
     * runtime that has nothing to load only produces a confusing log.
     */
    fun masterEnabled(
        context: Context,
        prefs: SharedPreferences,
        config: ModManagementConfig
    ): Boolean = masterEnabled(prefs, config) && config.availableMods(context).isNotEmpty()

    /** The stored preference alone, for drawing the switch itself. */
    fun masterEnabled(prefs: SharedPreferences, config: ModManagementConfig): Boolean =
        prefs.getBoolean(config.masterPrefsKey, config.masterDefaultOn)

    /**
     * Everything that must load: the selected mods plus every library transitively required by one
     * of them. A library reachable from nothing is simply absent - no refcounts are kept because
     * reachability answers the same question without any state to get out of sync.
     */
    fun resolvedSet(
        context: Context,
        prefs: SharedPreferences,
        config: ModManagementConfig
    ): Set<String> = config.closure(runnableMods(context, prefs, config))

    /**
     * Every support plugin that will load, in load order: the always-on base runtime first, then
     * the libraries the current selection pulls in through [ModManagementConfig.requires].
     *
     * The base ones are unconditional whenever mods are on at all - the game's loader never filters
     * them - so they are counted rather than hidden. A summary that omitted them would disagree
     * with the plugin count in the game's own log, which is the number this line exists to explain.
     */
    fun activeLibraries(
        context: Context,
        prefs: SharedPreferences,
        config: ModManagementConfig
    ): List<String> {
        if (!masterEnabled(context, prefs, config)) return emptyList()
        val resolved = resolvedSet(context, prefs, config)
        return config.baseLibraries + config.libraries.filter { it in resolved }
    }

    /**
     * Writes the enabled-set file. The master switch off writes an EMPTY set rather than deleting
     * the file, so the game boots with base plugins only even if its own master flag is being
     * overridden by a developer env file - the two switches fail into the same safe state instead
     * of disagreeing.
     *
     * Written whole, via a temp file and a rename, so the game can never read a half-written list.
     */
    fun write(context: Context, prefs: SharedPreferences, config: ModManagementConfig): Boolean {
        val target = config.enabledFile(context) ?: run {
            LauncherLog.w("mods") { "no enabled-mods file location - external storage unavailable?" }
            return false
        }
        val guids = if (masterEnabled(context, prefs, config)) {
            resolvedSet(context, prefs, config)
        } else emptySet()
        val body = HEADER + guids.joinToString("") { it + "\n" }
        if (readGuids(target) != guids) invalidateCaches(context, config)
        return try {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(target)) {
                // Some FUSE-backed external volumes refuse rename-over; a direct write is still
                // better than no write, and the file is small enough that the window is tiny.
                target.writeText(body)
                tmp.delete()
            }
            LauncherLog.write("mods", "enabled-mods.txt <- ${guids.size} guid(s): $guids")
            true
        } catch (t: Throwable) {
            LauncherLog.e("mods", "failed to write ${target.absolutePath}", t)
            false
        }
    }

    /** The guids already on disk, in the same normalised form [write] produces. */
    private fun readGuids(file: File): Set<String>? {
        if (!file.isFile) return null
        return try {
            file.readLines()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Deletes [ModManagementConfig.invalidateOnChange] - and only on a real change, so an ordinary
     * launch (which rewrites the same set) never pays for a rebuild.
     *
     * This exists because a mod loader that caches anything derived from the set will otherwise
     * carry a disabled mod's work forward: on this project's first device run, switching a mod off
     * left every one of its baked file redirects live, so the game still read the mod's content
     * with the mod itself unloaded. A cache keyed on inputs that can change out from under it has
     * to be discarded by whoever changes them.
     */
    private fun invalidateCaches(context: Context, config: ModManagementConfig) {
        for (target in config.invalidateOnChange(context)) {
            if (!target.exists()) continue
            val ok = target.deleteRecursively()
            LauncherLog.write("mods", "enabled set changed -> ${if (ok) "cleared" else "FAILED to clear"} ${target.absolutePath}")
        }
    }
}
