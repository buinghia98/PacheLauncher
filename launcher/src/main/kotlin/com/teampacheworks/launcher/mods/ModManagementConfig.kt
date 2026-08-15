package com.teampacheworks.launcher.mods

import android.content.Context
import java.io.File

/**
 * One user-togglable mod on the Manage Mods screen.
 *
 * [guid] is the identifier the game's own mod loader knows the plugin by, and it is the exact text
 * written to the enabled-set file - keep it byte-identical to what the loader's order table holds.
 *
 * **The name, author, version, description and icon a row shows come from the MOD, not from here.**
 * They are read at render time out of the staged plugin tree (see [ModMetadata]), because a mod
 * already ships all five and a second copy in Kotlin goes stale the moment a pin moves. The fields
 * below are fallbacks for a mod that ships no metadata, and [label] additionally serves as the
 * display name whenever the host wants to override an awkward package name.
 *
 * @param label Display name. Falls back to the package's own name only when this is blank.
 * @param hint Description of last resort, used when the package ships none.
 * @param note A line shown IN ADDITION to the mod's own description, for the things a package
 *   cannot describe about itself - above all, how it interacts with another mod in this list. A
 *   mod's blurb is written for a world where it is the only mod installed; "with X also on, this
 *   behaves differently" is knowledge that lives in the host's pin record and nowhere else. Use it
 *   sparingly: it is emphasised precisely because it is rare.
 * @param requiresPaths Data OUTSIDE this mod's own plugin folder that it cannot run without, as
 *   absolute files. A package may legally ship a mod's code and not its data - the reference case is
 *   a several-gigabyte import of another game that the player may not own - so "the plugin is
 *   staged" is not the same question as "this mod can run". Every path listed must exist for the row
 *   to be offered. Empty (the default) means the plugin folder alone decides.
 * @param unavailableNote Why the row is greyed out when [requiresPaths] is not satisfied, written
 *   for the player. Falls back to a generic line.
 */
data class ModEntry(
    val guid: String,
    val label: String,
    val hint: String = "",
    val defaultOn: Boolean = false,
    val author: String? = null,
    val version: String? = null,
    val note: String? = null,
    val requiresPaths: (Context) -> List<File> = { emptyList() },
    val unavailableNote: String? = null
)

/**
 * Everything the shared launcher needs to render and persist a per-mod on/off list, supplied by the
 * host exactly like [com.teampacheworks.launcher.gpu.GpuDriverConfig]. Null on [LauncherConfig]
 * means the game has no mod support and neither the entry button nor the screen exists.
 *
 * **This library resolves no dependencies.** A mod set that ships with a game is fixed and pinned,
 * so [requires] is a literal graph the host writes out by hand; enabling a mod marks everything
 * transitively reachable from it, and disabling one drops any library no longer reachable from a
 * still-enabled mod. Five entries of data beat a resolver.
 *
 * @param mods The togglable rows, in display order.
 * @param baseLibraries The mod runtime itself - the plugins that load whenever mods are enabled at
 *   all, regardless of which mods are selected. They are NOT part of [requires] and never appear in
 *   the enabled-set file, because the game's loader never filters them; they are listed here purely
 *   so the summary line can account for every plugin the log will report. Named before [libraries]
 *   in that line, which is also their load order.
 * @param libraries Auto-managed support plugins that a MOD pulls in. Never individually togglable -
 *   showing them as rows invites a player to break a mod - but named in a one-line summary so the
 *   plugin count in the game's log is explicable.
 * @param libraryLabels Optional pretty names for [libraries] in that summary line; a guid with no
 *   entry is shown as-is.
 * @param requires guid -> the guids it needs. Both mods and libraries may appear as keys (a library
 *   that needs another library is normal); the closure is transitive and the graph must be acyclic.
 * @param modFiles guid -> that mod's staged files directory, from which the screen reads the mod's
 *   own name, author, version, description and icon ([ModMetadata]). Returning null - the default -
 *   simply means every row falls back to its [ModEntry] values, so a host with no on-disk mod tree
 *   needs no changes.
 * @param enabledFile Where the enabled-set file lives. The game process and the launcher process
 *   are different processes and do not share SharedPreferences reliably, but they do resolve the
 *   same external files directory, so a file is the supported channel for a SET of ids.
 * @param invalidateOnChange Caches the game derives FROM the enabled set - deleted (recursively)
 *   whenever the written set actually changes, never otherwise. A mod loader that pre-computes
 *   anything at all (a merged data bake, a path table, a compiled chunk cache) has to be told that
 *   the inputs moved; deleting is the one instruction that needs no cooperation from the game and
 *   cannot half-apply. Empty by default, in which case nothing is ever deleted.
 * @param prefsKeyPrefix SharedPreferences key prefix for the per-mod booleans (the launcher's own
 *   view of the same state; the file is what the game reads).
 * @param masterPrefsKey SharedPreferences key of the "Enable mods" master switch. Off means the
 *   game launches unmodded, whatever the list says, and the list greys out.
 * @param masterDefaultOn Master switch default for a fresh install.
 */
data class ModManagementConfig(
    val mods: List<ModEntry>,
    val baseLibraries: List<String> = emptyList(),
    val libraries: List<String> = emptyList(),
    val libraryLabels: Map<String, String> = emptyMap(),
    val requires: Map<String, List<String>> = emptyMap(),
    val enabledFile: (Context) -> File?,
    val modFiles: (Context, String) -> File? = { _, _ -> null },
    val invalidateOnChange: (Context) -> List<File> = { emptyList() },
    val prefsKeyPrefix: String = "mod_enabled_",
    val masterPrefsKey: String = "mods_enabled",
    val masterDefaultOn: Boolean = false
) {
    init {
        require(mods.isNotEmpty()) { "ModManagementConfig needs at least one mod row" }
        require(mods.map { it.guid }.toSet().size == mods.size) { "duplicate mod guid" }
    }

    fun prefsKeyFor(guid: String): String = prefsKeyPrefix + guid

    /**
     * Whether this mod's files are actually on the device.
     *
     * A deploy package is allowed to be partial - game content and four small mods, with the
     * multi-gigabyte optional block left out - so the mod list is a claim about what this build
     * *supports*, and the disk is the authority on what it can *run*. Derived on every call rather
     * than recorded at import time, for the same reason the optional-block path is: an import receipt goes
     * stale the moment anything else touches the tree, and the tree cannot lie about itself.
     */
    fun isAvailable(context: Context, entry: ModEntry): Boolean {
        val plugin = modFiles(context, entry.guid)
        if (plugin != null && !plugin.exists()) return false
        return entry.requiresPaths(context).all { it.exists() }
    }

    /** The subset of [mods] that can actually run here, in display order. */
    fun availableMods(context: Context): Set<String> =
        mods.filter { isAvailable(context, it) }.map { it.guid }.toSet()

    /**
     * [selected] plus everything transitively reachable from it through [requires]. Depth-limited
     * rather than cycle-detected on purpose: a cycle in a hand-written five-entry literal is a
     * typo, and stopping is the only sane response to one.
     */
    fun closure(selected: Set<String>): Set<String> {
        val out = LinkedHashSet<String>()
        val queue = ArrayDeque(selected)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 4096) {
            val guid = queue.removeFirst()
            if (!out.add(guid)) continue
            requires[guid]?.forEach { if (it !in out) queue.addLast(it) }
        }
        return out
    }
}
