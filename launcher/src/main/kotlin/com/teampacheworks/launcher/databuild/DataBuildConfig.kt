package com.teampacheworks.launcher.databuild

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Host description of an **on-device data build**: turning a folder of the player's own PC game
 * files into the install tree the game actually runs from, without a PC-side script.
 *
 * ### How this differs from [com.teampacheworks.launcher.deploy.DeployImportConfig]
 * The deploy import moves a folder that a build script *already assembled*. This builds that folder
 * here, from raw inputs. Both exist for the same underlying reason -- a file another uid puts into
 * the app's external data dir is `0660/2770 ext_data_rw` and unreadable to the app -- and both solve
 * it the same way: **the app does the writing**. What changes is where the work happens.
 *
 * ### What this library does and does not know
 * It knows how to pick a folder, keep permission on it across process death, run a long blocking job
 * off the main thread with a partial wake lock, draw two progress bars, cancel, and refuse to hand a
 * half-built tree to a launch gate. It knows nothing about game content, mods or archives -- all of
 * that is [builder], which the host supplies. A library that understood "asset tier" or "mod" would
 * be this project's launcher with extra steps, not a framework.
 *
 * @param gameId Short stable id, written into the build receipt. Not shown to the player.
 * @param sourceFolderName The folder the player is told to copy onto the device and then pick, e.g.
 *   `"Hades II Game Files"`. Quoted in the on-screen instructions, so it must be the name they will
 *   literally see in the picker.
 * @param destinationDirectory Where the built tree lands. Normally `getExternalFilesDir(null)` --
 *   the same directory the deploy import writes to, because it is the same install.
 * @param builder The host's implementation. Both its methods block and are called off the main
 *   thread.
 * @param requiresNetwork Whether the build may need to download. Only used to decide whether the
 *   screen warns about Wi-Fi before it starts; the framework never checks connectivity itself,
 *   because only the builder knows whether *this* variant on *this* device has anything to fetch.
 * @param archiveMimeTypes Non-empty adds a second picker button (`pl_build_choose_archive`) that
 *   opens a single FILE of one of these types (e.g. `application/zip`) instead of a folder. The
 *   pick goes to [DataBuilder.inspectArchive] and the build gets it as [BuildRequest.archive]. Read
 *   it with [SafZip]. Empty (default) = folder only, exactly as before.
 * @param showOnMainScreen Adds a button (`pl_build_main_button`) directly under PLAY on the
 *   launcher's main screen that opens this screen. For hosts where building/importing the data is
 *   the first thing a player does, not a maintenance task under Manage assets.
 * @param launchReady Non-null makes this config a PLAY gate: while a build is half-done (sentinel)
 *   or this returns false, PLAY - and a direct launch - opens this screen instead of the game.
 *   Called on the main thread; keep it to a few `stat`s. Null (default) = no gate.
 * @param consumeSourceSwitch Replaces the two-row "keep it / use it up" choice with one switch
 *   (`pl_build_consume_switch`, default off) for folder sources, and hides it for archive sources.
 * @param postBuildWarning Optional one-line caveat about the data that was just built, e.g. "this
 *   game version is untested". Called on the worker thread after a successful build (sentinel
 *   already removed, receipt written); non-blank text is shown in the error colour under the
 *   completion summary and in the completion dialog. It never fails or undoes the build.
 */
data class DataBuildConfig(
    val gameId: String,
    val sourceFolderName: String,
    val destinationDirectory: (Context) -> File?,
    val builder: DataBuilder,
    val requiresNetwork: Boolean = false,
    val archiveMimeTypes: List<String> = emptyList(),
    val showOnMainScreen: Boolean = false,
    val launchReady: ((Context) -> Boolean)? = null,
    val consumeSourceSwitch: Boolean = false,
    val postBuildWarning: ((Context) -> String?)? = null
) {
    /**
     * Written for the whole operation and removed only on success. Its presence means a partly-built
     * tree is on disk, which no launch gate may accept -- the same contract as the deploy import's
     * sentinel, and deliberately a *different* file, so "an import stopped halfway" and "a build
     * stopped halfway" stay distinguishable in a bug report.
     */
    val sentinelName: String get() = ".pache-building-data"

    /** Written after a clean build. Informational: nothing gates on it. */
    val receiptName: String get() = ".pache-built-data.json"
}

/**
 * The host's build logic. Every method blocks; the framework calls them on a worker thread and
 * polls `cancelled` for it.
 *
 * Implementations must treat cancellation as an ordinary outcome rather than damage: throw
 * [DataBuildCancelled] at the next safe boundary and leave the tree in a state a second run can
 * finish. The framework leaves the sentinel in place for exactly that reason.
 */
interface DataBuilder {

    /**
     * Read the picked folder and report what can be built from it. Called once per pick, on a
     * worker thread, and expected to be cheap enough that the player is not left staring at a
     * spinner -- probe for marker files, do not walk 8,000 entries.
     */
    fun inspect(context: Context, source: SafTree, cancelled: () -> Boolean): Inspection

    /**
     * Like [inspect], for a single archive file picked through [DataBuildConfig.archiveMimeTypes].
     * Only called when that list is non-empty; the default refuses, so a builder that never opts in
     * has nothing to implement.
     */
    fun inspectArchive(context: Context, archive: Uri, cancelled: () -> Boolean): Inspection =
        Inspection(headline = "", problem = "Archives are not supported here.")

    /**
     * Do the work. Returns the lines shown in the completion dialog, most important first.
     *
     * @param progress may be called from any thread and at any rate; the framework throttles it
     *   before it reaches a View.
     */
    fun build(
        context: Context,
        request: BuildRequest,
        cancelled: () -> Boolean,
        progress: (BuildProgress) -> Unit
    ): List<String>
}

/**
 * What [DataBuilder.inspect] found.
 *
 * @param headline One line naming what is in the folder, e.g. `"Hades II v1.138464"`.
 * @param details Supporting lines, one fact each -- what was found, what was not, and what that
 *   costs. This is where a missing optional input is explained, and it is the only place the player
 *   can learn *why* a variant below is greyed out.
 * @param variants What may be built, in display order. The first enabled one is pre-selected. A
 *   single variant with a blank label is drawn as no choice at all (the heading and row are hidden).
 * @param options Extra choices the build takes, drawn under the variants. Use these for a decision
 *   that is INDEPENDENT of the variant -- which asset qualities to include, which language pack --
 *   rather than multiplying the variant list by every combination of them.
 * @param problem Non-null means nothing can be built from this folder at all; it is shown as an
 *   error and [variants] is ignored. Say which folder to pick instead -- "invalid selection" is the
 *   least actionable message a document picker can produce.
 */
data class Inspection(
    val headline: String,
    val details: List<String> = emptyList(),
    val variants: List<BuildVariant> = emptyList(),
    val options: List<BuildOption> = emptyList(),
    val problem: String? = null
)

/**
 * One extra decision the build takes, drawn as its own titled group.
 *
 * This exists so a host does not have to express an independent choice as more variants. "Game or
 * game+mods" times "High, Low or both" is six rows of variant that the player has to read as a
 * matrix; it is two questions, and they are easier as two questions.
 *
 * @param key Returned as the map key in [BuildRequest.selections]. The framework never reads it.
 * @param multiSelect Checkboxes when true, radio buttons when false. A multi-select group is not
 *   allowed to end up empty -- the Start button is disabled while one is -- because "build none of
 *   these" is a state no host asked to be given.
 * @param defaultSelected Choice ids on at first draw. A single-select group takes the first.
 */
data class BuildOption(
    val key: String,
    val title: String,
    val choices: List<BuildChoice>,
    val multiSelect: Boolean = true,
    val defaultSelected: List<String> = emptyList()
)

data class BuildChoice(val id: String, val label: String, val hint: String = "")

/**
 * One thing the player may choose to build, e.g. "Game only" or "Game and mods".
 *
 * @param id Passed back verbatim in [BuildRequest.variantId]. The framework never interprets it.
 * @param disabledNote Why this row cannot be picked. Required in practice whenever [enabled] is
 *   false: a greyed row with no reason is a bug report.
 */
data class BuildVariant(
    val id: String,
    val label: String,
    val hint: String = "",
    val enabled: Boolean = true,
    val disabledNote: String = ""
)

/**
 * @param consumeSource The player's answer to "keep or consume the source folder". True means the
 *   builder may delete files out of [source] as it finishes with them -- which is what lets a build
 *   need one copy of the data on the device instead of two. False means the folder must be left
 *   exactly as it was found, so a second build with different options needs no second transfer from
 *   the PC. The framework asks the question and forwards the answer; honouring it is the builder's
 *   job, and a builder with nothing to gain from consuming is free to ignore it.
 */
data class BuildRequest(
    /** The picked folder; null when the build was started from an [archive]. */
    val sourceTree: SafTree?,
    val variantId: String,
    val consumeSource: Boolean,
    /** Chosen ids per [BuildOption.key], in the order the option declared them. */
    val selections: Map<String, List<String>> = emptyMap(),
    /** The picked archive file ([DataBuildConfig.archiveMimeTypes]); null for a folder build. */
    val archive: Uri? = null
) {
    /** The picked folder. Folder builds only - an archive build has [archive] instead. */
    val source: SafTree
        get() = sourceTree ?: error("this build was started from an archive; read BuildRequest.archive")

    fun selected(key: String): List<String> = selections[key].orEmpty()
}

/**
 * One progress tick.
 *
 * Two scales, because a build is not one long copy: [phase] names the step ("Downloading mods",
 * "Copying game content") and moves rarely, while the counters move constantly *within* it. A
 * single bar over the whole build would spend forty minutes between 4% and 5%, which is
 * indistinguishable from a hang.
 *
 * @param label The item currently being handled -- a file path, a URL. Shown small and elided.
 * @param bytes/totalBytes Drive the bar when non-zero; otherwise [units]/[totalUnits] do, and if
 *   both are zero the bar goes indeterminate. A step that genuinely cannot know its size should
 *   leave both at zero rather than invent a denominator.
 */
data class BuildProgress(
    val phase: String,
    val label: String = "",
    val units: Long = 0,
    val totalUnits: Long = 0,
    val bytes: Long = 0,
    val totalBytes: Long = 0
)

/** Thrown by a builder when [DataBuilder]'s `cancelled` went true. Not an error; not logged as one. */
class DataBuildCancelled : RuntimeException("Build cancelled")
