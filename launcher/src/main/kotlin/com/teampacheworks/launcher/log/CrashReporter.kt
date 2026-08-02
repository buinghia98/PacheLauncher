package com.teampacheworks.launcher.log

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.teampacheworks.launcher.LauncherHost
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Uncaught-exception handler and process-exit forensics (design spec §4).
 *
 * Intended to be installed from the host's `Application.onCreate()`, in **every** process the host
 * app runs (a launcher process and a separate game process, if the host uses one); pass the
 * process name in so a crash in one process never collides with a crash file from another.
 *
 * Everything here is never-throw: it runs on the way down from an already-fatal exception, and a
 * secondary failure would only replace the real stack trace with a worse one.
 */
object CrashReporter {

    private var installed = false
    private var appContext: Context? = null
    private var processTag: String = "app"

    /** `com.example.game:game` -> `game`; a process with no `:suffix` -> `launcher`. */
    fun processTagOf(processName: String?): String {
        val p = processName ?: return "app"
        val colon = p.indexOf(':')
        return if (colon >= 0 && colon < p.length - 1) p.substring(colon + 1) else "launcher"
    }

    fun install(context: Context, processName: String?) {
        if (installed) return
        installed = true
        appContext = context.applicationContext
        processTag = processTagOf(processName)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                writeCrashReport(thread, error)
            } catch (ignored: Throwable) {
            }
            // Always hand back to the platform handler: swallowing it would leave a
            // live-but-broken process instead of the ANR/crash dialog the player expects.
            previous?.uncaughtException(thread, error)
        }
    }

    private fun writeCrashReport(thread: Thread, error: Throwable) {
        // The report is a *debug* artefact. With logging off there is no ring to attach and
        // nothing the player asked us to write into their Downloads folder.
        if (!LauncherLog.enabled) return
        val ctx = appContext ?: return
        val name = "crash_${processTag}_${LogExport.timestamp()}.log"
        val sb = StringBuilder(8192)
        header(sb, "crash report")
        sb.append('\n').append("--- Crash on thread '").append(thread.name).append("' ---\n")
        appendThrowable(sb, error, 0)
        sb.append('\n').append("--- Recent log (oldest first) ---\n")
        for (line in LauncherLog.snapshot()) sb.append(line).append('\n')
        LogExport.write(ctx, name, LauncherLog.scrub(sb.toString()))
    }

    private fun appendThrowable(sb: StringBuilder, t: Throwable?, depth: Int) {
        var e = t
        var d = depth
        while (e != null && d < 8) {
            val indent = " ".repeat(d * 2)
            sb.append(indent).append(e.javaClass.name).append(": ").append(e.message).append('\n')
            for (f in e.stackTrace) sb.append(indent).append("  at ").append(f).append('\n')
            e = e.cause
            if (e != null) sb.append(indent).append("--- Caused by ---\n")
            d++
        }
    }

    private fun header(sb: StringBuilder, title: String) {
        val appLabel = try { LauncherHost.config.appLabel } catch (t: Throwable) { "app" }
        sb.append("=== ").append(appLabel).append(' ').append(title).append(" ===\n")
        sb.append("Time: ")
            .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
        sb.append("Process: ").append(processTag).append('\n')
        sb.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(", Android API ").append(Build.VERSION.SDK_INT).append('\n')
    }

    // ------------------------------------------------- historical process exits

    data class ExitNotice(val summary: String, val savedTo: String?)

    /**
     * Design spec §4: on the launcher's `onResume`, ask the platform how a game process died last
     * time. Only abnormal reasons are reported - a clean exit is the normal case and must stay
     * silent. Returns null when there is nothing to say.
     *
     * `lastSeen` is the newest timestamp already reported, so the same crash is not announced twice.
     */
    fun checkGameProcessExit(context: Context, gameProcessName: String, lastSeen: Long): ExitNotice? {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
            val list = am.getHistoricalProcessExitReasons(context.packageName, 0, 20)
            val hit = list.firstOrNull {
                it.processName == gameProcessName && it.timestamp > lastSeen && isAbnormal(it.reason)
            } ?: return null

            val sb = StringBuilder(4096)
            header(sb, "process exit report")
            sb.append('\n').append("--- Abnormal exit of ").append(gameProcessName).append(" ---\n")
            sb.append("Reason: ").append(reasonName(hit.reason)).append(" (").append(hit.reason).append(")\n")
            sb.append("Status: ").append(hit.status).append('\n')
            sb.append("Importance: ").append(hit.importance).append('\n')
            sb.append("Description: ").append(hit.description ?: "-").append('\n')
            sb.append("When: ")
                .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(hit.timestamp)))
                .append('\n')
            try {
                hit.traceInputStream?.use { ins ->
                    sb.append("\n--- Platform trace ---\n")
                    sb.append(String(ins.readBytes(), Charsets.UTF_8))
                }
            } catch (ignored: Throwable) {
            }

            val name = "exit_game_${LogExport.timestamp()}.log"
            val saved = LogExport.write(context, name, LauncherLog.scrub(sb.toString()))
            ExitNotice("The game exited abnormally last time (${reasonName(hit.reason)}).", saved)
        } catch (t: Throwable) {
            null
        }
    }

    /** Newest abnormal game-process exit timestamp, used to seed the "already reported" watermark. */
    fun latestExitTimestamp(context: Context, gameProcessName: String): Long = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        am?.getHistoricalProcessExitReasons(context.packageName, 0, 20)
            ?.filter { it.processName == gameProcessName }
            ?.maxOfOrNull { it.timestamp } ?: 0L
    } catch (t: Throwable) {
        0L
    }

    /**
     * 🔴 `REASON_SIGNALED` and `REASON_USER_REQUESTED` are deliberately **not** abnormal here.
     * A game process that ends itself with `Process.killProcess`/`System.exit` on a normal exit
     * path would otherwise trip the "game crashed last time" notice on every single normal exit.
     * Only reasons the platform can attribute to a real fault are reported.
     */
    fun isAbnormal(reason: Int): Boolean = when (reason) {
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> true
        else -> false
    }

    fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "app crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "init failure"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource use"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by signal"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user requested"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
        else -> "other"
    }
}
