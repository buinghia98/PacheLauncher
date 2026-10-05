package com.teampacheworks.launcher.assets

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

object AssetStorage {
    /** Fast launch gate: checks required paths/sentinels only and never walks files for sizes. */
    fun isLaunchReady(context: Context, config: AssetManagementConfig, selectedTier: String): Boolean {
        val root = config.rootDirectory(context) ?: return false
        val tier = config.tier(selectedTier) ?: return false
        return !File(root, importingName(config.commonImportFolderName)).exists() &&
            !File(root, importingName(tier.importFolderName)).exists() &&
            requiredPresent(root, config.commonRequiredPaths) &&
            requiredPresent(root, tier.relativePaths)
    }

    fun scan(context: Context, config: AssetManagementConfig): AssetInstallState {
        val root = config.rootDirectory(context)
            ?: return AssetInstallState(emptyState(), emptyState(), emptyState())
        val tierPaths = (config.highTier.relativePaths + config.lowTier.relativePaths)
            .map { normalise(it) }
        val commonStats = stats(root) { relative ->
            !relative.substringAfterLast('/').startsWith(".pache-") &&
                tierPaths.none { relative == it || relative.startsWith("$it/") }
        }
        val commonImporting = File(root, importingName(config.commonImportFolderName)).exists()
        return AssetInstallState(
            AssetComponentState(!commonImporting && requiredPresent(root, config.commonRequiredPaths), commonStats.first, commonStats.second),
            component(root, config.highTier, config.highTier.importFolderName),
            component(root, config.lowTier, config.lowTier.importFolderName)
        )
    }

    fun deleteTier(context: Context, config: AssetManagementConfig, tier: AssetTierConfig) {
        val root = requireNotNull(config.rootDirectory(context)) { "Asset storage is unavailable" }
        val sentinel = File(root, importingName(tier.importFolderName))
        sentinel.writeText("delete in progress")
        try {
            tier.relativePaths.forEach { relative ->
                val target = resolveInside(root, relative)
                if (target.exists() && !target.deleteRecursively()) {
                    error("Could not delete ${target.name}")
                }
            }
            File(root, ".pache-installed-${tier.importFolderName}.json").delete()
            sentinel.delete()
        } catch (t: Throwable) {
            // Retain the sentinel so a partially deleted tier can never pass the launch gate.
            throw t
        }
    }

    /**
     * Install an asset PACKAGE -- the older, narrower route, kept as library API and reached by no
     * built-in screen any more.
     *
     * The Manage Assets screen used to carry a button for it. It stopped earning one once the same
     * screen offered a whole-install import and an on-device build: three buttons where two of them
     * did a superset of the third is a choice the player has no way to make correctly. A host whose
     * game really does ship per-tier asset packages can still call this itself.
     */
    fun importTree(context: Context, config: AssetManagementConfig, treeUri: Uri): Long {
        val source = requireNotNull(DocumentFile.fromTreeUri(context, treeUri)) {
            "The selected folder cannot be opened"
        }
        val root = requireNotNull(config.rootDirectory(context)) { "Asset storage is unavailable" }
        if (!root.exists() && !root.mkdirs()) error("Could not create ${root.absolutePath}")

        val components = listOf(config.commonImportFolderName, config.highTier.importFolderName,
            config.lowTier.importFolderName)
        var copied = 0L
        var found = false
        val prepared = mutableListOf<PreparedComponent>()
        val isAggregate = components.any { source.findFile(it)?.isDirectory == true }
        val bundle = source.findFile("bundle.json")
        if (isAggregate && bundle == null) error("The asset package is missing bundle.json")
        bundle?.let {
            val json = readJson(context, it)
            require(json.optInt("schemaVersion") == 1 && json.optString("gameId") == config.gameId) {
                "This asset package is for a different game or format"
            }
            require(json.optBoolean("complete", false)) { "This asset package is incomplete" }
        }
        for (name in components) {
            val component = source.findFile(name) ?: continue
            if (!component.isDirectory) continue
            found = true
            prepared += prepareComponent(context, config, component, name)
        }
        if (!found) {
            // Also accept choosing one component folder itself, which is convenient on Android's
            // document picker. Its name determines the component; content still maps to root.
            val ownName = source.name
            if (components.none { it == ownName }) {
                error("Select the asset package folder, or its Common/High/Low child")
            }
            prepared += prepareComponent(context, config, source, ownName!!)
        }
        // Validate every selected block before the first destination byte is touched.
        prepared.forEach { copied += installComponent(context, it, root) }
        return copied
    }

    private data class PreparedComponent(
        val block: String,
        val content: DocumentFile,
        val manifest: JSONObject
    )

    private fun prepareComponent(
        context: Context,
        config: AssetManagementConfig,
        component: DocumentFile,
        expectedBlock: String
    ): PreparedComponent {
        val manifest = component.findFile("manifest.json")
            ?: error("$expectedBlock is missing manifest.json")
        val json = readJson(context, manifest)
        require(json.optInt("schemaVersion") == 1 && json.optString("gameId") == config.gameId) {
            "$expectedBlock has an incompatible manifest"
        }
        require(json.optString("block") == expectedBlock && json.optString("contentPrefix") == "Content") {
            "$expectedBlock manifest does not match its folder"
        }
        val content = component.findFile("Content")
            ?.takeIf { it.isDirectory }
            ?: error("$expectedBlock is missing its Content folder")
        validateComponent(content, json)
        return PreparedComponent(expectedBlock, content, json)
    }

    private fun installComponent(context: Context, prepared: PreparedComponent, root: File): Long {
        val importing = File(root, importingName(prepared.block))
        importing.writeText("import in progress")
        return try {
            val copied = copyDirectory(context, prepared.content, root)
            require(copied == prepared.manifest.getLong("totalBytes")) {
                "${prepared.block} copied $copied bytes, expected ${prepared.manifest.getLong("totalBytes")}" 
            }
            File(root, ".pache-installed-${prepared.block}.json").writeText(prepared.manifest.toString())
            importing.delete()
            copied
        } catch (t: Throwable) {
            // Deliberately retain the sentinel: scanners must not call this partial component
            // installed. A successful retry removes it after every manifest byte is copied.
            throw t
        }
    }

    private fun validateComponent(content: DocumentFile, manifest: JSONObject) {
        val actual = linkedMapOf<String, Long>()
        collectDocuments(content, "Content", actual)
        val listed = linkedMapOf<String, Long>()
        val files = manifest.getJSONArray("files")
        for (i in 0 until files.length()) {
            val entry = files.getJSONObject(i)
            val path = normalise(entry.getString("path"))
            require(path.startsWith("Content/") && path.split('/').none { it == ".." || it.isBlank() }) {
                "Unsafe path in asset manifest"
            }
            listed[path] = entry.getLong("size")
        }
        require(files.length().toLong() == manifest.getLong("fileCount")) { "Manifest file count is inconsistent" }
        require(listed.values.sum() == manifest.getLong("totalBytes")) { "Manifest byte count is inconsistent" }
        require(actual == listed) { "Asset files do not match the manifest (missing, extra, or wrong size)" }
    }

    private fun collectDocuments(folder: DocumentFile, prefix: String, out: MutableMap<String, Long>) {
        folder.listFiles().forEach { child ->
            val name = safeName(child.name ?: error("An imported entry has no name"))
            val path = "$prefix/$name"
            if (child.isDirectory) collectDocuments(child, path, out)
            else if (child.isFile) out[normalise(path)] = child.length()
        }
    }

    private fun readJson(context: Context, file: DocumentFile): JSONObject {
        val text = context.contentResolver.openInputStream(file.uri).use { input ->
            requireNotNull(input) { "Could not read ${file.name}" }
            input.bufferedReader().readText()
        }
        return JSONObject(text)
    }

    private fun component(root: File, tier: AssetTierConfig, block: String): AssetComponentState {
        var bytes = 0L
        var files = 0L
        tier.relativePaths.forEach { path ->
            val s = stats(resolveInside(root, path)) { true }
            bytes += s.first
            files += s.second
        }
        return AssetComponentState(
            !File(root, importingName(block)).exists() && requiredPresent(root, tier.relativePaths),
            bytes,
            files
        )
    }

    private fun requiredPresent(root: File, paths: List<String>): Boolean = paths.all { relative ->
        val file = resolveInside(root, relative)
        file.isFile || (file.isDirectory && file.walkTopDown().any { it.isFile })
    }

    private fun stats(root: File, include: (String) -> Boolean): Pair<Long, Long> {
        if (!root.exists()) return 0L to 0L
        var bytes = 0L
        var files = 0L
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = normalise(file.relativeTo(root).path)
            if (include(relative)) {
                bytes += file.length()
                files++
            }
        }
        return bytes to files
    }

    private fun copyDirectory(context: Context, source: DocumentFile, destination: File): Long {
        var total = 0L
        source.listFiles().forEach { child ->
            val name = safeName(child.name ?: error("An imported entry has no name"))
            val target = resolveInside(destination, name)
            if (child.isDirectory) {
                if (!target.exists() && !target.mkdirs()) error("Could not create ${target.name}")
                total += copyDirectory(context, child, target)
            } else if (child.isFile) {
                target.parentFile?.mkdirs()
                val temp = File(target.parentFile, ".${target.name}.pache-import.tmp")
                try {
                    context.contentResolver.openInputStream(child.uri).use { input ->
                        requireNotNull(input) { "Could not read $name" }
                        FileOutputStream(temp).use { output -> total += input.copyTo(output) }
                    }
                    if (target.exists() && !target.delete()) error("Could not replace $name")
                    if (!temp.renameTo(target)) error("Could not install $name")
                } finally {
                    if (temp.exists()) temp.delete()
                }
            }
        }
        return total
    }

    private fun resolveInside(root: File, relative: String): File {
        val result = File(root, normalise(relative)).canonicalFile
        val canonicalRoot = root.canonicalFile
        require(result.toPath().startsWith(canonicalRoot.toPath())) { "Path escapes asset root" }
        return result
    }

    private fun safeName(name: String): String {
        require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name) {
            "Unsafe asset entry name"
        }
        return name
    }

    private fun normalise(path: String): String = path.replace('\\', '/').trim('/')
    private fun importingName(block: String) = ".pache-importing-$block"
    private fun emptyState() = AssetComponentState(false, 0L, 0L)
}
