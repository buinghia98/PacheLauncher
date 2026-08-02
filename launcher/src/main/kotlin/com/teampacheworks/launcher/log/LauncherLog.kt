package com.teampacheworks.launcher.log

import android.util.Log
import java.util.Locale

/**
 * In-memory diagnostic ring (design spec §4, originally ported from a sister project's DiagLog).
 *
 * Three contracts, all load-bearing:
 *
 * 1. **Never throws.** Every public entry point swallows everything. This is called from crash
 *    paths; a secondary exception here would replace the original crash's stack trace with a
 *    less useful one.
 * 2. **Zero cost when disabled.** The `(() -> String)` overloads are `inline`, so with logging off
 *    the message is never even built - no string concatenation, no boxing, just a static bool read.
 * 3. **Everything is scrubbed.** [scrub] runs on every line before it reaches the buffer, because
 *    [LogExport] copies this buffer into **public** Downloads storage on crash. A GitHub token that
 *    reaches this ring has effectively been published. The cloud layer already keeps the token out
 *    of everything *it* constructs; what it cannot protect against is a platform exception
 *    (SSL, socket, HTTP) whose message it never wrote. This is the only place that path closes.
 */
object LauncherLog {

    /**
     * The `android.util.Log` tag every line here is written under. Defaults to something neutral;
     * a host app may want to change it (e.g. to match its own logcat filtering habits) before
     * anything is logged - typically the first line of `Application.onCreate()`, next to
     * [com.teampacheworks.launcher.LauncherHost.install].
     */
    @Volatile
    @JvmStatic
    var tag: String = "PacheLauncher"

    const val RING_CAPACITY = 500

    @Volatile
    @JvmStatic
    var enabled: Boolean = false

    private val ring = ArrayDeque<String>(RING_CAPACITY)
    private val lock = Any()

    // ---------------------------------------------------------------- scrubbing

    private const val MASK = "***REDACTED***"

    /**
     * Three families: GitHub fine-grained PATs, the classic `gh?_` families, and any bare
     * `Bearer <blob>`. A player *will* paste a classic token by mistake; the rejected string must
     * not be logged either.
     */
    private val CREDENTIALS = Regex(
        "github_pat_[A-Za-z0-9_]{16,}" +
            "|gh[pousr]_[A-Za-z0-9]{16,}" +
            "|(?<=[Bb]earer )[A-Za-z0-9_\\-.~+/=]{16,}"
    )

    /** Cheap pre-filter: every alternative above needs either '_' or the "earer" of "Bearer ". */
    fun scrub(message: String): String {
        if (message.isEmpty()) return message
        if (!message.contains('_') && !message.contains("earer")) return message
        return CREDENTIALS.replace(message, MASK)
    }

    // ------------------------------------------------------------------ writing

    /** Zero-cost when disabled: [message] is never invoked. */
    inline fun d(tag: String, message: () -> String) {
        if (!enabled) return
        write(tag, message())
    }

    inline fun w(tag: String, message: () -> String) {
        if (!enabled) return
        write("$tag!", message())
    }

    /** Errors are always recorded - they are the reason the ring exists. */
    fun e(tag: String, message: String, t: Throwable? = null) {
        val full = if (t == null) message else "$message: $t"
        write("$tag!!", full)
        try {
            Log.e(this.tag, scrub(full))
        } catch (ignored: Throwable) {
        }
    }

    /** Appends one already-materialised line. Public because the inline overloads call it. */
    fun write(tag: String, message: String) {
        try {
            if (!enabled) return
            val line = format(elapsedMillis(), tag, scrub(message))
            synchronized(lock) {
                ring.addLast(line)
                while (ring.size > RING_CAPACITY) ring.removeFirst()
            }
            Log.d(this.tag, line)
        } catch (ignored: Throwable) {
            // never-throw contract
        }
    }

    /** `[+   12.345] [tag] message` - seconds since [start], right-padded to 9 columns. */
    fun format(millisSinceStart: Long, tag: String, message: String): String {
        val secs = String.format(Locale.US, "%.3f", millisSinceStart / 1000.0).padStart(9)
        return "[+$secs] [$tag] $message"
    }

    // -------------------------------------------------------------------- state

    private val startedAt = System.currentTimeMillis()

    private fun elapsedMillis(): Long = System.currentTimeMillis() - startedAt

    /** Oldest first. Snapshot copy - the caller may take its time formatting it. */
    fun snapshot(): List<String> = synchronized(lock) { ring.toList() }

    fun clear() = synchronized(lock) { ring.clear() }

    fun size(): Int = synchronized(lock) { ring.size }
}
