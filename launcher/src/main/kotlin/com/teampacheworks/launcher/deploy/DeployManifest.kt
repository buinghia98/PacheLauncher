package com.teampacheworks.launcher.deploy

import org.json.JSONObject

/** One file in a [DeployBlock]. [sha256] is present only when the staging step was told to hash. */
data class DeployEntry(val path: String, val size: Long, val sha256: String?)

/**
 * An independently verifiable part of a deploy folder.
 *
 * Blocks are what make a **partial** package legal. The staging script emits a block only for what
 * it actually staged, so a folder built without some optional import simply has no block for it
 *  -- and an absent optional block is not a defect to report, it is a smaller install. A block
 * that *is* listed must verify completely; there is no half-block.
 *
 * The hierarchy is deliberately shallow and one-directional:
 *
 * ```text
 * content-common   required   the guest binary and untiered game data
 * content-high     optional   1080p packages and movies   } at least one is needed to play,
 * content-low      optional   720p packages and movies    } which the launcher's own tier gate says
 * settings         optional   env
 * mods-core        optional   the mod runtime, plugins and their data
 * mods-extra       optional   a large optional import and the mods needing it, only with mods-core
 * ```
 *
 * **Nothing in the mod stack is mandatory.** A vanilla-only folder -- content plus `env` -- is a
 * legal package: it imports cleanly, the game plays unmodded, and Manage Mods shows its empty state
 * rather than an error.
 *
 * @param required Whether a package is meaningless without it. At least one required block must be
 *   present, which is what stops a mods-only folder from being imported as if it were an install.
 * @param tier When non-null, the quality tier this block carries, matching the value the host's
 *   asset config uses (`1080p` / `720p`). Reported after the import; nothing gates on it here.
 * @param mods Guids this block's payload makes runnable. Purely informational for the import; what
 *   the launcher actually offers is derived from the tree on disk afterwards, never from a receipt.
 */
data class DeployBlock(
    val id: String,
    val label: String,
    val required: Boolean,
    val tier: String?,
    val mods: List<String>,
    val files: List<DeployEntry>
) {
    val fileCount: Long get() = files.size.toLong()
    val totalBytes: Long get() = files.sumOf { it.size }
}

/**
 * `<payload>/deploy-manifest.json`, schema version 1.
 *
 * Deliberately the same shape as the asset bundle's `manifest.json` -- `schemaVersion`, `gameId`, `fileCount`, `totalBytes`,
 * `files[] { path, size, sha256? }` -- with the file list pushed one level down into [blocks] so a
 * package can carry an optional part. Paths are payload-relative and forward-slashed.
 */
class DeployManifest private constructor(
    val gameId: String,
    val packageName: String,
    val payload: String,
    val tier: String?,
    val createdUtc: String?,
    val blocks: List<DeployBlock>
) {
    val files: List<DeployEntry> = blocks.flatMap { it.files }
    val fileCount: Long get() = files.size.toLong()
    val totalBytes: Long get() = files.sumOf { it.size }

    /** Every block that carries at least one guid, for the post-import summary. */
    val providedMods: List<String> get() = blocks.flatMap { it.mods }.distinct()

    /** Quality tiers this package carries, in manifest order. Empty is legal but unplayable. */
    val tiers: List<String> get() = blocks.mapNotNull { it.tier }.distinct()

    companion object {
        fun parse(json: JSONObject, config: DeployImportConfig): DeployManifest {
            require(json.optInt("schemaVersion") == 1) {
                "This deploy folder uses an unsupported manifest version"
            }
            require(json.optString("gameId") == config.gameId) {
                "This deploy folder is for a different game"
            }
            require(json.optBoolean("complete", false)) {
                "This deploy folder is incomplete - the staging script did not finish"
            }
            val payload = json.optString("payload")
            require(payload == config.payloadFolderName) {
                "The manifest describes a '$payload' payload, not '${config.payloadFolderName}'"
            }

            val blockArray = json.optJSONArray("blocks")
                ?: error("The manifest lists no blocks")
            val blocks = ArrayList<DeployBlock>(blockArray.length())
            val seenPaths = HashSet<String>()
            val seenIds = HashSet<String>()
            for (i in 0 until blockArray.length()) {
                val b = blockArray.getJSONObject(i)
                val id = b.getString("id")
                require(seenIds.add(id)) { "The manifest lists block '$id' twice" }
                val fileArray = b.getJSONArray("files")
                val entries = ArrayList<DeployEntry>(fileArray.length())
                for (f in 0 until fileArray.length()) {
                    val e = fileArray.getJSONObject(f)
                    val path = safePath(e.getString("path"))
                    require(seenPaths.add(path)) { "'$path' is listed by more than one block" }
                    entries += DeployEntry(
                        path = path,
                        size = e.getLong("size"),
                        sha256 = e.optString("sha256").takeIf { it.length == 64 }
                    )
                }
                val block = DeployBlock(
                    id = id,
                    label = b.optString("label").ifBlank { id },
                    required = b.optBoolean("required", false),
                    tier = b.optString("tier").takeIf { it.isNotBlank() },
                    mods = (0 until (b.optJSONArray("mods")?.length() ?: 0)).map {
                        b.getJSONArray("mods").getString(it)
                    },
                    files = entries
                )
                // Per-block totals are checked here rather than only in aggregate, so a truncated
                // block is named in the error instead of surfacing as a wrong grand total.
                require(block.fileCount == b.getLong("fileCount")) {
                    "Block '$id' lists ${block.fileCount} files but claims ${b.getLong("fileCount")}"
                }
                require(block.totalBytes == b.getLong("totalBytes")) {
                    "Block '$id' lists ${block.totalBytes} bytes but claims ${b.getLong("totalBytes")}"
                }
                blocks += block
            }
            require(blocks.any { it.required }) {
                "This folder carries no game data - pick the folder named ${config.packageFolderName}"
            }
            val manifest = DeployManifest(
                gameId = json.getString("gameId"),
                packageName = json.optString("package", config.packageFolderName),
                payload = payload,
                tier = json.optString("tier").takeIf { it.isNotBlank() },
                createdUtc = json.optString("createdUtc").takeIf { it.isNotBlank() },
                blocks = blocks
            )
            require(manifest.fileCount == json.getLong("fileCount")) { "Manifest file count is inconsistent" }
            require(manifest.totalBytes == json.getLong("totalBytes")) { "Manifest byte count is inconsistent" }
            return manifest
        }

        /**
         * The same fence [com.teampacheworks.launcher.assets.AssetStorage] applies to its own
         * manifests: relative, forward-slashed, no `..`, no empty segment, no drive letter. A
         * manifest is data from outside the app and is treated as hostile.
         */
        private fun safePath(raw: String): String {
            val path = raw.replace('\\', '/').trim('/')
            require(path.isNotEmpty() && !path.startsWith("/") && ':' !in path) {
                "Unsafe path in the deploy manifest: $raw"
            }
            require(path.split('/').none { it.isBlank() || it == "." || it == ".." }) {
                "Unsafe path in the deploy manifest: $raw"
            }
            return path
        }
    }
}
