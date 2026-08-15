package com.teampacheworks.launcher.gpu

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipInputStream

object GpuDriverStorage {
    const val SYSTEM = "system"
    private const val BUNDLED_ID = "bundled"
    private const val META_FILE = ".driver.properties"
    private const val MAX_SINGLE_FILE = 160L * 1024L * 1024L
    private val LIBRARY_NAME = Regex("libvulkan_[A-Za-z0-9_.-]+\\.so")

    fun list(context: Context, config: GpuDriverConfig): List<GpuDriver> {
        val root = config.storageDirectory(context)
        return root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".tmp-") }
            ?.mapNotNull(::readInstalled)
            ?.sortedWith(compareBy<GpuDriver> { it.imported }.thenBy { it.label.lowercase() })
            ?: emptyList()
    }

    fun resolve(context: Context, config: GpuDriverConfig, selection: String?): GpuDriver? =
        list(context, config).firstOrNull { it.selectionValue == selection }

    @Synchronized
    fun ensureBundled(context: Context, config: GpuDriverConfig): Result<GpuDriver?> = runCatching {
        val asset = config.bundledAssetPath ?: return@runCatching null
        val directory = File(config.storageDirectory(context), BUNDLED_ID)
        readInstalled(directory)?.let { return@runCatching it }
        if (directory.exists()) require(directory.deleteRecursively()) { "Cannot replace invalid bundled driver" }
        context.assets.open(asset).use {
            installStream(context, config, it, imported = false, fixedId = BUNDLED_ID,
                fallbackLabel = config.bundledLabel)
        }
    }

    fun importZip(context: Context, config: GpuDriverConfig, uri: Uri): Result<GpuDriver> = runCatching {
        val stream = requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot open selected ZIP" }
        stream.use {
            installStream(context, config, it, imported = true, fixedId = UUID.randomUUID().toString(),
                fallbackLabel = "Imported driver")
        }
    }

    fun delete(context: Context, config: GpuDriverConfig, driver: GpuDriver): Boolean {
        if (!driver.imported) return false
        val root = config.storageDirectory(context).canonicalFile
        val target = driver.libraryFile.canonicalFile.parentFile
        val install = generateSequence(target) { it.parentFile }.firstOrNull { it.parentFile == root }
            ?: return false
        if (!install.name.matches(Regex("[0-9a-fA-F-]{36}"))) return false
        return install.deleteRecursively()
    }

    private fun installStream(
        context: Context,
        config: GpuDriverConfig,
        source: InputStream,
        imported: Boolean,
        fixedId: String,
        fallbackLabel: String
    ): GpuDriver {
        val root = config.storageDirectory(context).also {
            require(it.mkdirs() || it.isDirectory) { "Cannot create driver storage" }
        }
        val temp = File(root, ".tmp-${UUID.randomUUID()}")
        require(temp.mkdir()) { "Cannot create temporary driver directory" }
        try {
            var count = 0
            var total = 0L
            var meta: JSONObject? = null
            val extracted = ArrayList<File>()
            ZipInputStream(BufferedInputStream(source)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    count++
                    require(count <= config.maxFiles) { "ZIP contains too many entries (max ${config.maxFiles})" }
                    require(!entry.name.startsWith('/') && !entry.name.startsWith('\\') &&
                        !Regex("^[A-Za-z]:").containsMatchIn(entry.name)) { "Absolute ZIP path: ${entry.name}" }
                    val normalized = entry.name.replace('\\', '/')
                    require(normalized.isNotBlank() && !normalized.split('/').contains("..")) {
                        "Unsafe ZIP path: ${entry.name}"
                    }
                    val out = File(temp, normalized).canonicalFile
                    require(out.path.startsWith(temp.canonicalPath + File.separator)) {
                        "ZIP entry escapes destination: ${entry.name}"
                    }
                    if (entry.isDirectory) {
                        require(out.mkdirs() || out.isDirectory)
                    } else {
                        val parent = requireNotNull(out.parentFile)
                        require(parent.mkdirs() || parent.isDirectory) {
                            "Cannot create ZIP destination directory"
                        }
                        var fileBytes = 0L
                        FileOutputStream(out).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                fileBytes += read
                                total += read
                                require(fileBytes <= MAX_SINGLE_FILE) { "ZIP entry is too large: ${entry.name}" }
                                require(total <= config.maxUncompressedBytes) {
                                    "ZIP expands beyond ${config.maxUncompressedBytes / 1024 / 1024} MiB"
                                }
                                output.write(buffer, 0, read)
                            }
                            output.fd.sync()
                        }
                        extracted += out
                        if (out.name == "meta.json") meta = JSONObject(out.readText(Charsets.UTF_8))
                    }
                    zip.closeEntry()
                }
            }
            require(count > 0) { "ZIP is empty" }
            val declared = meta?.optString("libraryName")?.takeIf { it.isNotBlank() }
                ?: "libvulkan_freedreno.so"
            require(LIBRARY_NAME.matches(declared) && !declared.contains('/')) {
                "Invalid Vulkan libraryName in meta.json"
            }
            val driverLibraries = extracted.filter { LIBRARY_NAME.matches(it.name) }
            require(driverLibraries.size == 1) {
                "ZIP must contain exactly one libvulkan_*.so"
            }
            val candidates = driverLibraries.filter { it.name == declared }
            require(candidates.size == 1) { "meta.json libraryName does not match the Vulkan library" }
            validateArm64Elf(candidates.single())

            val props = Properties().apply {
                setProperty("id", fixedId)
                setProperty("label", (meta?.optString("name")?.takeIf { it.isNotBlank() } ?: fallbackLabel).take(100))
                // Android canonicalizes /data/user/0/... to /data/data/....
                // Both operands must use the same canonical root or relativeTo
                // emits ../../ paths pointing back at the soon-renamed temp dir.
                setProperty("library", candidates.single()
                    .relativeTo(temp.canonicalFile).invariantSeparatorsPath)
                setProperty("imported", imported.toString())
                meta?.optString("driverVersion")?.takeIf { it.isNotBlank() }?.let { setProperty("version", it.take(100)) }
                meta?.optString("vendor")?.takeIf { it.isNotBlank() }?.let { setProperty("vendor", it.take(100)) }
                meta?.optString("description")?.takeIf { it.isNotBlank() }?.let { setProperty("description", it.take(500)) }
            }
            FileOutputStream(File(temp, META_FILE)).use { props.store(it, "Validated GPU driver") }
            val target = File(root, fixedId)
            require(!target.exists()) { "A driver with this id already exists" }
            require(temp.renameTo(target)) { "Could not atomically install driver" }
            return requireNotNull(readInstalled(target)) { "Installed driver failed verification" }
        } catch (t: Throwable) {
            temp.deleteRecursively()
            throw t
        }
    }

    private fun readInstalled(directory: File): GpuDriver? = runCatching {
        val props = Properties().apply { FileInputStream(File(directory, META_FILE)).use { load(it) } }
        val relative = requireNotNull(props.getProperty("library"))
        val library = File(directory, relative).canonicalFile
        require(library.path.startsWith(directory.canonicalPath + File.separator) && library.isFile)
        validateArm64Elf(library)
        GpuDriver(requireNotNull(props.getProperty("id")), requireNotNull(props.getProperty("label")),
            // Non-null by the require() above: the library was proven to sit UNDER `directory`.
            requireNotNull(library.parentFile), library.name,
            props.getProperty("version"), props.getProperty("vendor"),
            props.getProperty("description"), props.getProperty("imported").toBoolean())
    }.getOrNull()

    private fun validateArm64Elf(file: File) {
        val header = ByteArray(20)
        FileInputStream(file).use { require(it.read(header) == header.size) { "Vulkan library is truncated" } }
        require(header[0] == 0x7f.toByte() && header[1] == 69.toByte() && header[2] == 76.toByte() && header[3] == 70.toByte()) { "Vulkan library is not ELF" }
        require(header[4] == 2.toByte()) { "Vulkan library must be 64-bit ELF" }
        require(header[5] == 1.toByte()) { "Vulkan library must be little-endian ELF" }
        val machine = (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
        require(machine == 183) { "Vulkan library is not ARM64 (ELF machine $machine)" }
    }
}
