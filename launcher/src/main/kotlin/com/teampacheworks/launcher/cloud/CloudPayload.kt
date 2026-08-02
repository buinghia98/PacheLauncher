package com.teampacheworks.launcher.cloud

import com.teampacheworks.launcher.LauncherHost
import java.util.Base64
import java.util.Locale

/**
 * The text wrapper a save bundle travels in (design spec §3).
 *
 * WHY BASE64 AT ALL - do not "optimise" this away: the Gist API makes no documented promise that
 * file content comes back byte-for-byte. `content` is a JSON string field in a system built for
 * source code, where end-of-line normalisation is normal behaviour. A save bundle's corruption is
 * silent and its loss is a playthrough. Base64 removes the entire class of risk: the decoder ignores
 * whitespace, and the alphabet `A-Za-z0-9+/=` contains nothing a text pipeline touches.
 *
 * The header lines are for a human reading the gist in a browser. **The decoder never trusts them**
 * - it parses the fence and nothing else. The authoritative byte count and hash live in the manifest.
 *
 * The fence markers are `-----BEGIN <tag> SAVE-----` / `-----END <tag> SAVE-----`, where `<tag>` is
 * [com.teampacheworks.launcher.LauncherConfig.cloudFenceTag]. Keep that value stable once players
 * have cloud backups: changing it does not corrupt anything, but it does orphan payloads already
 * uploaded under the old tag (they simply fail to decode as "ours" any more).
 */
object CloudPayload {

    private val fenceTag: String get() = LauncherHost.config.cloudFenceTag
    private val productName: String get() = LauncherHost.config.cloudProductName

    val BEGIN_FENCE: String get() = "-----BEGIN $fenceTag SAVE-----"
    val END_FENCE: String get() = "-----END $fenceTag SAVE-----"

    /** Readable in the gist UI; harmless because decoding ignores whitespace. */
    private const val WRAP_COLUMNS = 76

    /** `save-01.zip.b64.txt` .. `save-NN.zip.b64.txt` - the file name *is* the slot's identity. */
    fun fileNameFor(slot: Int): String =
        "save-" + String.format(Locale.US, "%02d", slot) + ".zip.b64.txt"

    fun isSlotFileName(name: String): Boolean =
        name.startsWith("save-", ignoreCase = true) && name.endsWith(".zip.b64.txt", ignoreCase = true)

    fun slotOf(fileName: String): Int? =
        Regex("^save-(\\d{2})\\.zip\\.b64\\.txt$", RegexOption.IGNORE_CASE)
            .find(fileName)?.groupValues?.get(1)?.toIntOrNull()

    // ------------------------------------------------------------------ encode

    fun encode(bundle: ByteArray, slot: Int, sha256Hex: String? = null): String {
        val sha = sha256Hex ?: com.teampacheworks.launcher.save.SaveBundle.sha256Hex(bundle)
        val b64 = Base64.getEncoder().encodeToString(bundle)
        val sb = StringBuilder(b64.length + b64.length / WRAP_COLUMNS + 512)
        sb.append(productName).append(" cloud save · slot ")
            .append(String.format(Locale.US, "%02d", slot))
            .append(" · ").append(bundle.size).append(" bytes · sha256 ").append(sha).append('\n')
        sb.append("Base64 of save bundle zip. Decode: base64 -d <file> > save.zip\n")
        sb.append(BEGIN_FENCE).append('\n')
        var i = 0
        while (i < b64.length) {
            val end = minOf(i + WRAP_COLUMNS, b64.length)
            sb.append(b64, i, end).append('\n')
            i = end
        }
        sb.append(END_FENCE).append('\n')
        return sb.toString()
    }

    // ------------------------------------------------------------------ decode

    sealed class Decoded {
        data class Ok(val bytes: ByteArray) : Decoded()
        data class Err(val message: String) : Decoded()
    }

    /**
     * Strict: the fence is required. Used by the cloud download path - it never guesses.
     * The import path in Save Management tries [looksLikeFenced] first and falls back to
     * treating the file as a raw zip.
     */
    fun decode(text: String): Decoded {
        val body = text.removePrefix("﻿")
        val begin = indexOfFenceAtLineStart(body, BEGIN_FENCE, 0)
        if (begin < 0) {
            return Decoded.Err("This file is missing its \"$BEGIN_FENCE\" marker, so it is not a $productName cloud save.")
        }
        val bodyStart = endOfLine(body, begin)
        val end = indexOfFenceAtLineStart(body, END_FENCE, bodyStart)
        if (end < 0) {
            return Decoded.Err("This cloud file has an opening marker but no closing marker; it is truncated or was edited.")
        }
        val bytes = tryFromBase64(body.substring(bodyStart, end))
            ?: return Decoded.Err("The base64 inside this cloud file could not be decoded; it was changed outside the launcher.")
        return Decoded.Ok(bytes)
    }

    fun looksLikeFenced(text: String): Boolean =
        indexOfFenceAtLineStart(text.removePrefix("﻿"), BEGIN_FENCE, 0) >= 0

    /**
     * The fence must start a line - a fence-shaped substring quoted inside a sentence is skipped,
     * so a README that documents the format cannot be mistaken for a payload.
     */
    private fun indexOfFenceAtLineStart(text: String, fence: String, from: Int): Int {
        var i = from
        while (i <= text.length - fence.length) {
            val hit = text.indexOf(fence, i)
            if (hit < 0) return -1
            val lineStart = text.lastIndexOf('\n', hit - 1) + 1
            if ((lineStart until hit).all { text[it].isWhitespace() }) return hit
            i = hit + fence.length
        }
        return -1
    }

    private fun endOfLine(text: String, from: Int): Int {
        val nl = text.indexOf('\n', from)
        return if (nl < 0) text.length else nl + 1
    }

    /**
     * Strips **all** whitespace by hand rather than relying on the decoder's own tolerance: a
     * copy/paste round trip can introduce a non-breaking space, which `Base64.getMimeDecoder`
     * does not treat as ignorable.
     */
    private fun tryFromBase64(span: String): ByteArray? {
        val sb = StringBuilder(span.length)
        for (c in span) if (!c.isWhitespace()) sb.append(c)
        if (sb.isEmpty()) return null
        return try {
            val out = Base64.getMimeDecoder().decode(sb.toString())
            if (out.isEmpty()) null else out
        } catch (t: Throwable) {
            null
        }
    }
}
