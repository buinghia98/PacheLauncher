package com.teampacheworks.launcher.log

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream

/**
 * Streams this process's own logcat into a session file in `Downloads/<folder>` (design spec §4).
 *
 * Reading logcat for **our own pid** needs no permission - `READ_LOGS` is only required to read
 * *other* apps' output. That is the whole point here: many game engines' native layers log outside
 * Java (a different tag, or `stderr`/`stdout` captured by the platform) and never cross into the
 * JVM, so [LauncherLog]'s ring buffer alone cannot see them. `logcat --pid=<mypid>` can, so a debug
 * session captures the underlying engine's output too, whatever it is.
 *
 * A host typically starts this only in its own game process, and only when Debug logging is on.
 */
class LogcatPump private constructor(
    private val out: OutputStream,
    private val process: java.lang.Process?
) {

    @Volatile
    private var stopped = false
    private var thread: Thread? = null

    private fun start() {
        val p = process ?: return
        val t = Thread({
            try {
                BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                    while (!stopped) {
                        val line = reader.readLine() ?: break
                        out.write(LauncherLog.scrub(line).toByteArray(Charsets.UTF_8))
                        out.write('\n'.code)
                        // Cheap and worth it: a crash that kills us must not lose the tail.
                        out.flush()
                    }
                }
            } catch (ignored: Throwable) {
            } finally {
                try { out.close() } catch (ignored: Throwable) {}
            }
        }, "pache-logcat-pump")
        t.isDaemon = true
        thread = t
        t.start()
    }

    fun stop() {
        stopped = true
        try { process?.destroy() } catch (ignored: Throwable) {}
        try { out.flush() } catch (ignored: Throwable) {}
    }

    companion object {
        /** Never throws; returns null when the pump could not be started. */
        fun start(context: Context, processTag: String): LogcatPump? = try {
            val name = "session_${processTag}_${LogExport.timestamp()}.log"
            val out = LogExport.openAppend(context, name)
            if (out == null) {
                null
            } else {
                val pid = android.os.Process.myPid()
                val proc = ProcessBuilder(
                    listOf("logcat", "--pid=$pid", "-v", "time")
                ).redirectErrorStream(true).start()
                LogcatPump(out, proc).also {
                    it.start()
                    LauncherLog.write("log", "logcat pump -> Downloads/${LogExport.FOLDER}/$name (pid=$pid)")
                }
            }
        } catch (t: Throwable) {
            null
        }
    }
}
