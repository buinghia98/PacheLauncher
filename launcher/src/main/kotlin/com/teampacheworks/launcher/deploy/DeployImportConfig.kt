package com.teampacheworks.launcher.deploy

import android.content.Context
import java.io.File

/**
 * Host description of a whole-install "deploy folder" import.
 *
 * The asset importer ([com.teampacheworks.launcher.assets.AssetStorage]) moves one *content*
 * package. This moves the entire thing a staging script produced for a device:
 *
 * ```text
 * <packageFolderName>/            e.g. com.example.mygame
 *   README-DEPLOY.txt
 *   files/                        <- payloadFolderName; its children mirror destinationDirectory
 *     deploy-manifest.json        <- manifestName
 *     env
 *     mygame/ …
 *     mygame-mods/ …
 * ```
 *
 * The user copies that folder anywhere they can reach with the system file picker (Download, a USB
 * drive) and points the launcher at it. **The app does the writing**, which is the entire reason
 * this route exists: files an app creates in its own external data dir are owned by the app, while
 * files any other uid puts there are 0660/2770 `ext_data_rw` and the app -- not being in that group
 * -- cannot read them at all.
 *
 * @param gameId Must equal the manifest's `gameId`. A package for another game is refused.
 * @param packageFolderName The staging script's package folder. Accepted as a pick target, as is
 *   [payloadFolderName] itself and any parent that contains the package folder.
 * @param payloadFolderName The folder whose children map 1:1 onto [destinationDirectory].
 * @param manifestName The manifest, which lives INSIDE the payload folder so that picking either
 *   the package folder or the payload folder finds it.
 * @param destinationDirectory Where the payload lands. Normally `getExternalFilesDir(null)`.
 * @param preservedPaths Payload-relative paths the *player* owns, not the package. A manifest entry
 *   landing on an existing file at or under one of these is dropped (its source is still consumed,
 *   since this is a move) rather than overwriting what is already there; when nothing is there yet,
 *   the package's copy is installed as the seed. Directory prefixes and exact file paths both work.
 *   This is the same fence a re-staging script draws: replace the runtime, keep the settings.
 * @param readabilityProbePaths Payload-relative paths checked by [DeployStorage.diagnose] for the
 *   present-but-unreadable case -- a tree an earlier adb push left behind without the `chmod`s.
 *   Reporting "Not installed" for those sends the user hunting for files that are plainly there.
 */
data class DeployImportConfig(
    val gameId: String,
    val packageFolderName: String,
    val destinationDirectory: (Context) -> File?,
    val payloadFolderName: String = "files",
    val manifestName: String = "deploy-manifest.json",
    val preservedPaths: List<String> = emptyList(),
    val readabilityProbePaths: List<String> = emptyList()
) {
    /** Sentinel written for the whole operation; its presence means "half a tree is on disk". */
    val sentinelName: String get() = ".pache-importing-deploy"

    /** Receipt written after a clean import. Informational: no scan reads it. */
    val receiptName: String get() = ".pache-installed-deploy.json"

    /** True when [path] is at or under one of [preservedPaths]. */
    fun isPreserved(path: String): Boolean = preservedPaths.any { raw ->
        val fence = raw.replace('\\', '/').trim('/')
        fence.isNotEmpty() && (path == fence || path.startsWith("$fence/"))
    }
}
