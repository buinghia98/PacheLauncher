package com.teampacheworks.launcher.log

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.teampacheworks.launcher.LauncherHost
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes diagnostic text to `Downloads/<`[LauncherConfig.downloadsFolderName]`>` (design spec §4).
 *
 * MediaStore.Downloads rather than SAF: a crash cannot prompt the player for a destination, so the
 * write has to be silent and automatic. On API 29+ an app needs no permission for a Downloads entry
 * it creates itself.
 *
 * 🔴 Untestable on a PC and only partly predictable on device, and host-dependent: an app whose
 * `targetSdk` is below 29 is a *legacy-storage* app on a modern device, and legacy apps
 * historically needed `WRITE_EXTERNAL_STORAGE` for MediaStore writes - a permission that is inert
 * on API 33+. The code is therefore deliberately dumb and defensive: try MediaStore, then the
 * public Downloads directory, then the app's own external files dir, and give up quietly. Every
 * branch is never-throw; the return value says where (if anywhere) the bytes landed.
 */
object LogExport {

    /** `Downloads/<folder>`, folder = [LauncherConfig.downloadsFolderName]. */
    val FOLDER: String get() = try { LauncherHost.config.downloadsFolderName } catch (t: Throwable) { "PacheLauncher" }

    private val RELATIVE_PATH: String get() = "Download/$FOLDER"

    fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /**
     * @return a human-readable description of where the file went, or null if nothing was written.
     */
    fun write(context: Context, fileName: String, content: String): String? =
        writeBytes(context, fileName, content.toByteArray(Charsets.UTF_8), "text/plain")

    /**
     * Same fallback ladder as [write], for raw bytes (e.g. a binary tombstone next to a crash
     * report). [mimeType] matters only for the MediaStore branch.
     *
     * @return a human-readable description of where the file went, or null if nothing was written.
     */
    fun writeBytes(context: Context, fileName: String, content: ByteArray, mimeType: String): String? {
        viaMediaStore(context, fileName, content, mimeType)?.let { return it }
        viaPublicDownloads(fileName, content)?.let { return it }
        viaAppExternal(context, fileName, content)?.let { return it }
        try {
            Log.w(LauncherLog.tag, "LogExport: could not write $fileName anywhere")
        } catch (ignored: Throwable) {
        }
        return null
    }

    private fun viaMediaStore(context: Context, fileName: String, content: ByteArray, mimeType: String): String? = try {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            null
        } else {
            resolver.openOutputStream(uri, "wt").use { out ->
                if (out == null) null else {
                    out.write(content)
                    out.flush()
                    "Downloads/$FOLDER/$fileName"
                }
            }
        }
    } catch (t: Throwable) {
        null
    }

    private fun viaPublicDownloads(fileName: String, content: ByteArray): String? = try {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, fileName)
        f.writeBytes(content)
        f.absolutePath
    } catch (t: Throwable) {
        null
    }

    private fun viaAppExternal(context: Context, fileName: String, content: ByteArray): String? = try {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, fileName)
        f.writeBytes(content)
        f.absolutePath
    } catch (t: Throwable) {
        null
    }

    /**
     * Result of a manual "Export logs" button press (design spec §1/§2, ui-sync). Distinguishes
     * "nothing to export" from "tried and failed" so the caller can show the three-way toast the
     * spec calls for: "Exported n log file(s)...", "No logs to export yet.", "Export failed: ...".
     */
    sealed class SessionExportResult {
        data class Exported(val count: Int, val where: String) : SessionExportResult()
        object Empty : SessionExportResult()
        data class Failed(val reason: String) : SessionExportResult()
    }

    /**
     * Dumps whatever [LauncherLog]'s in-memory ring currently holds to `Downloads/<folder>`,
     * independent of whether debug logging is enabled - unlike [CrashReporter]'s automatic
     * crash/exit reports, a player pressing this button wants whatever is in the buffer right now.
     * There is exactly one ring, so at most one file is ever written.
     */
    fun exportSessionLog(context: Context): SessionExportResult {
        val lines = LauncherLog.snapshot()
        if (lines.isEmpty()) return SessionExportResult.Empty
        return try {
            val name = "session_launcher_${timestamp()}.log"
            val content = formatSessionLog(lines)
            val saved = write(context, name, LauncherLog.scrub(content))
            if (saved != null) {
                SessionExportResult.Exported(1, saved)
            } else {
                SessionExportResult.Failed("could not write to any storage location")
            }
        } catch (t: Throwable) {
            SessionExportResult.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    /** Pure text formatting, split out so it can be unit-tested without an Android Context. */
    fun formatSessionLog(lines: List<String>): String {
        val appLabel = try { LauncherHost.config.appLabel } catch (t: Throwable) { "app" }
        val sb = StringBuilder(4096)
        sb.append("=== ").append(appLabel).append(" session log ===\n")
        sb.append("Time: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
        for (line in lines) sb.append(line).append('\n')
        return sb.toString()
    }

    /** Opens a stream for the logcat pump; same fallback ladder, but incremental. */
    fun openAppend(context: Context, fileName: String): java.io.OutputStream? {
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                val out = resolver.openOutputStream(uri, "wt")
                if (out != null) return out
            }
        } catch (ignored: Throwable) {
        }
        return try {
            @Suppress("DEPRECATION")
            val pub = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
            if (!pub.exists()) pub.mkdirs()
            java.io.FileOutputStream(File(pub, fileName))
        } catch (ignored: Throwable) {
            try {
                val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
                if (!dir.exists()) dir.mkdirs()
                java.io.FileOutputStream(File(dir, fileName))
            } catch (ignored2: Throwable) {
                null
            }
        }
    }
}
