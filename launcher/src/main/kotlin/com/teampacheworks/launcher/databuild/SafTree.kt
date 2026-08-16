package com.teampacheworks.launcher.databuild

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/** One entry in a picked folder tree. [size] is 0 for directories and for providers that omit it. */
class SafEntry(
    val documentId: String,
    val name: String,
    val uri: Uri,
    val size: Long,
    val isDirectory: Boolean
)

/**
 * A read/consume view of a folder the user picked, built for **tens of thousands of files**.
 *
 * `DocumentFile` is the obvious API and the wrong one at this scale: `listFiles()` is one query per
 * directory that then allocates an object per row, `length()` is *another* query per file, and
 * `findFile()` is a linear scan of a fresh listing every call. Walking a game's content tree through
 * it costs one round trip per file for the sizes alone -- measured in minutes before a single byte
 * moves. This class asks each directory once, for id + name + mime + size together, which is the
 * same information the provider was going to return anyway.
 *
 * Nothing here is game-specific; it is the plumbing every [DataBuilder] needs and none of them
 * should write twice.
 */
class SafTree(private val context: Context, val treeUri: Uri) {

    val rootId: String = DocumentsContract.getTreeDocumentId(treeUri)

    /** Immediate children of [documentId]. Order is the provider's; never assume it is sorted. */
    fun list(documentId: String): List<SafEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val out = ArrayList<SafEntry>()
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                // A name with a separator in it would let a crafted archive-like tree write outside
                // the install root once the caller joins it to a path. Drop it here, once.
                if (name.isEmpty() || name == "." || name == ".." || '/' in name || '\\' in name) continue
                val dir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                out += SafEntry(
                    documentId = id,
                    name = name,
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                    size = if (dir || c.isNull(3)) 0L else c.getLong(3),
                    isDirectory = dir
                )
            }
        }
        return out
    }

    /** `"Contents/Resources/Content"` relative to [from]. Null when any segment is absent. */
    fun find(relative: String, from: String = rootId): SafEntry? {
        var parent = from
        var found: SafEntry? = null
        for (segment in relative.split('/', '\\')) {
            if (segment.isEmpty()) continue
            // One listing per segment, then an exact match preferred over a case-insensitive one.
            // The case fallback exists because a folder copied through Windows and a phone can
            // arrive with a different case than the one the game shipped.
            val children = list(parent)
            found = children.firstOrNull { it.name == segment }
                ?: children.firstOrNull { it.name.equals(segment, ignoreCase = true) }
                ?: return null
            parent = found.documentId
        }
        return found
    }

    fun exists(relative: String, from: String = rootId): Boolean = find(relative, from) != null

    /**
     * Every file at or under [documentId], keyed by its path relative to it (forward-slashed).
     *
     * Directories are not in the result: a builder wants the files and makes its own destination
     * directories. [cancelled] is polled per directory, which on a slow provider is the difference
     * between a responsive cancel and one that arrives ten minutes later.
     */
    fun walk(
        documentId: String,
        prefix: String = "",
        out: LinkedHashMap<String, SafEntry> = LinkedHashMap(),
        cancelled: () -> Boolean = { false }
    ): LinkedHashMap<String, SafEntry> {
        if (cancelled()) throw DataBuildCancelled()
        for (child in list(documentId)) {
            val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            if (child.isDirectory) walk(child.documentId, path, out, cancelled)
            else out[path] = child
        }
        return out
    }

    fun openInput(entry: SafEntry): InputStream =
        context.contentResolver.openInputStream(entry.uri)
            ?: error("${entry.name} could not be opened")

    fun readText(entry: SafEntry, limitBytes: Int = 1 shl 22): String =
        openInput(entry).use { input ->
            val buffer = ByteArray(minOf(limitBytes, maxOf(1, entry.size.toInt().takeIf { it > 0 } ?: limitBytes)))
            var n = 0
            while (n < buffer.size) {
                val r = input.read(buffer, n, buffer.size - n)
                if (r < 0) break
                n += r
            }
            String(buffer, 0, n, Charsets.UTF_8)
        }

    /**
     * Copy one source file to [target], creating parents. Returns the bytes installed.
     *
     * With [consume] set this first tries a real filesystem rename, which turns a multi-gigabyte
     * build into metadata operations -- see [legacyPath] for when that is available. It falls back
     * to copy-then-unlink, in that order, so there is no instant at which the file exists nowhere.
     *
     * The copy goes to a temp name and is renamed into place only once the byte count matches, so an
     * interrupted build never leaves a short file that a later run would accept as installed.
     */
    fun install(entry: SafEntry, target: File, consume: Boolean): Long {
        target.parentFile?.mkdirs()
        if (consume && fastMove(entry, target)) return target.length()

        val temp = File(target.parentFile, ".${target.name}.pache-build.tmp")
        try {
            var written = 0L
            openInput(entry).use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(1 shl 18)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        written += n
                    }
                }
            }
            if (entry.size > 0 && written != entry.size) {
                error("${entry.name} copied $written bytes, expected ${entry.size}")
            }
            if (target.exists() && !target.delete()) error("could not replace ${target.name}")
            if (!temp.renameTo(target)) error("could not install ${target.name}")
            if (consume) deleteSource(entry)
            return written
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /** Best-effort unlink of a source document. A failure is not fatal to a build that already copied it. */
    fun deleteSource(entry: SafEntry): Boolean = try {
        DocumentsContract.deleteDocument(context.contentResolver, entry.uri)
    } catch (t: Throwable) {
        false
    }

    /**
     * A true rename, when the platform will give us one.
     *
     * `DocumentsContract.moveDocument` moves between two *documents*, and the destination here is a
     * plain [File] in the app's own external data directory that no provider hands out a tree uri
     * for. So the only rename available is the filesystem one, and it needs the source's real path
     * plus permission to unlink it. That holds for the external-storage provider (Download, a
     * removable volume) and for nothing else, so this tries once per file and costs a failed
     * `renameTo` when it does not apply.
     *
     * Returns false to mean "not available", never "failed halfway": [File.renameTo] is atomic, so a
     * false leaves the source exactly as it was.
     */
    private fun fastMove(entry: SafEntry, target: File): Boolean {
        val path = legacyPath(entry.uri) ?: return false
        val src = File(path)
        if (!src.isFile || !src.canRead()) return false
        return try {
            if (target.exists() && !target.delete()) false else src.renameTo(target)
        } catch (t: Throwable) {
            false
        }
    }

    /** `primary:Download/x/y` -> `/storage/emulated/0/Download/x/y`, external-storage provider only. */
    fun legacyPath(uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        return try {
            val id = DocumentsContract.getDocumentId(uri)
            val volume = id.substringBefore(':', "")
            val relative = id.substringAfter(':', "")
            if (relative.isEmpty()) return null
            when (volume) {
                "primary" -> "${android.os.Environment.getExternalStorageDirectory()}/$relative"
                else -> "/storage/$volume/$relative"
            }
        } catch (t: Throwable) {
            null
        }
    }
}
