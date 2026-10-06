package com.teampacheworks.launcher.log

/**
 * Renders the binary Tombstone protobuf that `ApplicationExitInfo.getTraceInputStream()` returns
 * for `REASON_CRASH_NATIVE` as text in the familiar logcat tombstone layout.
 *
 * Dependency-free on purpose (no protobuf runtime in this module): a minimal wire-format reader
 * plus the field numbers of AOSP `system/core/debuggerd/proto/tombstone.proto` (main, checked
 * 2026-10). Only the fields a developer reads first are decoded; everything else is skipped by
 * wire type, so newer tombstones with extra fields still decode.
 *
 * Never throws: any malformed input falls back to [fallbackSummary] (size, hex head, printable
 * strings), so a broken trace can never cost the rest of the exit report.
 */
object TombstoneDecoder {

    /**
     * True when [bytes] look like a protobuf message rather than text: the first byte is not
     * printable/whitespace ASCII and the whole buffer parses as top-level fields.
     */
    fun looksLikeProtobuf(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val b0 = bytes[0].toInt() and 0xff
        if (b0 == 0x09 || b0 == 0x0a || b0 == 0x0d || b0 in 0x20..0x7e) {
            // Could still be a tag (0x0a = field 1 length-delimited, 0x12 '"'...), so text wins
            // only when the head is mostly printable.
            if (printableRatio(bytes, 256) > 0.95) return false
        }
        return try {
            Msg.parse(bytes, 0, bytes.size)
            true
        } catch (t: Throwable) {
            false
        }
    }

    private fun printableRatio(bytes: ByteArray, max: Int): Double {
        val n = minOf(bytes.size, max)
        if (n == 0) return 1.0
        var ok = 0
        for (i in 0 until n) {
            val c = bytes[i].toInt() and 0xff
            if (c == 0x09 || c == 0x0a || c == 0x0d || c in 0x20..0x7e || c >= 0x80) ok++
        }
        return ok.toDouble() / n
    }

    /**
     * @param imageLabels anonymous executable regions to attribute by name: base address -> label.
     *   A pc inside the contiguous run of mappings that starts at a base is printed as
     *   `label+0x<pc - base>` (e.g. a Mach-O image mapped at 0x100000000 by a loader).
     */
    fun render(bytes: ByteArray, imageLabels: Map<Long, String> = emptyMap()): String = try {
        renderOrThrow(bytes, imageLabels)
    } catch (t: Throwable) {
        "(tombstone decode failed: ${t.javaClass.simpleName}: ${t.message})\n" + fallbackSummary(bytes)
    }

    /** Size, hex dump of the head, and the printable strings of an undecodable trace. */
    fun fallbackSummary(bytes: ByteArray): String = try {
        val sb = StringBuilder()
        sb.append("Trace size: ").append(bytes.size).append(" bytes\n")
        val n = minOf(bytes.size, 256)
        for (row in 0 until n step 16) {
            sb.append(String.format("  %04x: ", row))
            for (i in row until minOf(row + 16, n)) sb.append(String.format("%02x ", bytes[i].toInt() and 0xff))
            sb.append('\n')
        }
        sb.append("Printable strings (>= 6 chars, first 300):\n")
        var count = 0
        var start = -1
        for (i in 0..bytes.size) {
            val c = if (i < bytes.size) bytes[i].toInt() and 0xff else 0
            val printable = c in 0x20..0x7e
            if (printable && start < 0) start = i
            if (!printable && start >= 0) {
                if (i - start >= 6) {
                    sb.append("  ").append(String(bytes, start, i - start, Charsets.US_ASCII)).append('\n')
                    if (++count >= 300) break
                }
                start = -1
            }
        }
        sb.toString()
    } catch (t: Throwable) {
        "Trace size: ${bytes.size} bytes (summary failed: $t)\n"
    }

    // ------------------------------------------------------------------ rendering

    private val ARCH = arrayOf("arm", "arm64", "x86", "x86_64", "riscv64", "none")
    private const val SEP = "*** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***\n"
    private const val THREAD_SEP = "--- --- --- --- --- --- --- --- --- --- --- --- --- --- --- ---\n"
    private const val LOG_TAIL = 200
    private val PRIORITY = arrayOf("?", "?", "V", "D", "I", "W", "E", "F", "S")

    private class Mapping(
        val begin: Long, val end: Long, val offset: Long,
        val r: Boolean, val w: Boolean, val x: Boolean,
        val name: String, val buildId: String, val loadBias: Long
    )

    /** A labelled anonymous image: [begin, end) and its label. */
    private class Image(val begin: Long, val end: Long, val label: String)

    private fun renderOrThrow(bytes: ByteArray, imageLabels: Map<Long, String>): String {
        val t = Msg.parse(bytes, 0, bytes.size)
        val sb = StringBuilder(16384)
        val arch = t.u64(1).toInt()
        val is64 = arch == 1 || arch == 3 || arch == 4
        sb.append(SEP)
        sb.append("Build fingerprint: '").append(t.str(2)).append("'\n")
        if (t.has(3)) sb.append("Revision: '").append(t.str(3)).append("'\n")
        sb.append("ABI: '").append(ARCH.getOrElse(arch) { "arch$arch" }).append("'\n")
        sb.append("Timestamp: ").append(t.str(4)).append('\n')
        if (t.has(20)) sb.append("Process uptime: ").append(t.u64(20)).append("s\n")
        val pid = t.u64(5)
        val tid = t.u64(6)
        val cmd = t.strs(9).joinToString(" ")
        val threads = t.msgs(16).mapNotNull { entry ->
            // map<uint32, Thread>: key = 1, value = 2
            entry.msgs(2).firstOrNull()?.let { entry.u64(1) to it }
        }
        val crashThread = threads.firstOrNull { it.first == tid }?.second
        sb.append("pid: ").append(pid).append(", tid: ").append(tid)
            .append(", name: ").append(crashThread?.str(2) ?: "?")
            .append("  >>> ").append(cmd).append(" <<<\n")
        sb.append("uid: ").append(t.u64(7)).append('\n')
        if (t.has(8)) sb.append("selinux: ").append(t.str(8)).append('\n')

        t.msgs(10).firstOrNull()?.let { s ->
            sb.append("signal ").append(s.i32(1)).append(" (").append(s.str(2)).append("), code ")
                .append(s.i32(3)).append(" (").append(s.str(4))
            if (s.bool(5)) sb.append(" from pid ").append(s.i32(7)).append(", uid ").append(s.i32(6))
            sb.append("), fault addr ")
            if (s.bool(8)) sb.append(hex(s.u64(9), is64)) else sb.append("--------")
            sb.append('\n')
        }
        if (t.has(14)) sb.append("Abort message: '").append(t.str(14)).append("'\n")
        for (c in t.msgs(15)) sb.append("Cause: ").append(c.str(1)).append('\n')

        val maps = t.msgs(17).map { m ->
            Mapping(
                m.u64(1), m.u64(2), m.u64(3), m.bool(4), m.bool(5), m.bool(6),
                m.str(7), m.str(8), m.u64(9)
            )
        }.sortedBy { it.begin }
        val images = imageLabels.mapNotNull { (base, label) -> imageAt(maps, base, label) }

        val ordered = threads.sortedWith(compareBy({ it.first != tid }, { it.first }))
        for ((index, entry) in ordered.withIndex()) {
            val th = entry.second
            if (index > 0) {
                sb.append(THREAD_SEP)
                sb.append("pid: ").append(pid).append(", tid: ").append(th.u64(1))
                    .append(", name: ").append(th.str(2)).append("  >>> ").append(cmd).append(" <<<\n")
            }
            if (entry.first == tid) appendRegisters(sb, th, is64)
            for (note in th.strs(7)) sb.append("  NOTE: ").append(note).append('\n')
            val frames = th.msgs(4)
            sb.append('\n').append(frames.size).append(" total frames\nbacktrace:\n")
            for ((n, f) in frames.withIndex()) appendFrame(sb, n, f, is64, maps, images)
            sb.append('\n')
        }

        val exec = maps.filter { it.x }
        sb.append(SEP).append("memory map (executable mappings, ").append(exec.size)
            .append(" of ").append(maps.size).append("):\n")
        for (m in exec) {
            sb.append("    ").append(hex(m.begin, is64)).append('-').append(hex(m.end, is64)).append(' ')
                .append(if (m.r) 'r' else '-').append(if (m.w) 'w' else '-').append(if (m.x) 'x' else '-')
                .append("  ").append(java.lang.Long.toHexString(m.offset))
                .append("  ").append(java.lang.Long.toHexString(m.end - m.begin))
                .append("  ").append(m.name.ifEmpty { "<anonymous:${java.lang.Long.toHexString(m.begin)}>" })
            images.firstOrNull { m.begin >= it.begin && m.end <= it.end }?.let {
                sb.append("  [").append(it.label).append("+0x")
                    .append(java.lang.Long.toHexString(m.begin - it.begin)).append(']')
            }
            if (m.buildId.isNotEmpty()) sb.append(" (BuildId: ").append(m.buildId).append(')')
            if (m.loadBias != 0L) sb.append(" (load bias 0x").append(java.lang.Long.toHexString(m.loadBias)).append(')')
            sb.append('\n')
        }

        val fds = t.msgs(19)
        if (fds.isNotEmpty()) sb.append("\nopen files: ").append(fds.size).append('\n')

        for (buf in t.msgs(18)) {
            val logs = buf.msgs(2)
            sb.append(SEP).append("--------- tail end of log ").append(buf.str(1))
                .append(" (").append(minOf(logs.size, LOG_TAIL)).append(" of ").append(logs.size).append(")\n")
            for (l in logs.takeLast(LOG_TAIL)) {
                sb.append(l.str(1)).append(' ').append(l.u64(2)).append(' ').append(l.u64(3)).append(' ')
                    .append(PRIORITY.getOrElse(l.u64(4).toInt()) { "?" }).append(' ')
                    .append(l.str(5)).append(": ").append(l.str(6).trimEnd()).append('\n')
            }
        }
        return sb.toString()
    }

    /** The contiguous run of mappings that starts exactly at [base], or null if none does. */
    private fun imageAt(maps: List<Mapping>, base: Long, label: String): Image? {
        var i = maps.indexOfFirst { it.begin == base }
        if (i < 0) return null
        var end = maps[i].end
        while (i + 1 < maps.size && maps[i + 1].begin == end && isAnonymous(maps[i + 1].name)) {
            i++
            end = maps[i].end
        }
        return Image(base, end, label)
    }

    private fun isAnonymous(name: String) =
        name.isEmpty() || name.startsWith("[anon") || name.startsWith("<anonymous")

    private fun appendRegisters(sb: StringBuilder, th: Msg, is64: Boolean) {
        val regs = th.msgs(3)
        if (regs.isEmpty()) return
        for (row in regs.chunked(4)) {
            sb.append("   ")
            for (r in row) {
                sb.append(' ').append(r.str(1).padStart(4)).append("  ").append(hex(r.u64(2), is64))
            }
            sb.append('\n')
        }
    }

    private fun appendFrame(
        sb: StringBuilder, n: Int, f: Msg, is64: Boolean, maps: List<Mapping>, images: List<Image>
    ) {
        val pc = f.u64(2)
        val relPc = f.u64(1)
        sb.append("      #").append(n.toString().padStart(2, '0')).append(" pc ").append(hex(relPc, is64)).append("  ")
        val image = images.firstOrNull { java.lang.Long.compareUnsigned(pc, it.begin) >= 0 && java.lang.Long.compareUnsigned(pc, it.end) < 0 }
        var file = f.str(6)
        if (file.isEmpty()) {
            file = maps.firstOrNull { pc >= it.begin && pc < it.end }?.let {
                it.name.ifEmpty { "<anonymous:${java.lang.Long.toHexString(it.begin)}>" }
            } ?: "<unknown>"
        }
        if (image != null) {
            sb.append(image.label).append("+0x").append(java.lang.Long.toHexString(pc - image.begin))
                .append("  (").append(file).append(')')
        } else {
            sb.append(file)
            val mapOff = f.u64(7)
            if (mapOff != 0L) sb.append(" (offset 0x").append(java.lang.Long.toHexString(mapOff)).append(')')
        }
        val fn = f.str(4)
        if (fn.isNotEmpty()) {
            sb.append(" (").append(fn)
            val off = f.u64(5)
            if (off != 0L) sb.append('+').append(off)
            sb.append(')')
        }
        val bid = f.str(8)
        if (bid.isNotEmpty()) sb.append(" (BuildId: ").append(bid).append(')')
        sb.append('\n')
    }

    private fun hex(v: Long, is64: Boolean): String =
        if (is64) String.format("%016x", v) else String.format("%08x", v and 0xffffffffL)

    // ------------------------------------------------------------------ wire format

    /**
     * One parsed message: every field occurrence in order. Varint / fixed64 / fixed32 values are
     * kept as [Long]; length-delimited ones as a slice of the original buffer. Groups (wire types
     * 3/4, deprecated) are skipped.
     */
    class Msg private constructor(private val buf: ByteArray, private val fields: List<Field>) {

        class Field(val number: Int, val wire: Int, val value: Long, val off: Int, val len: Int)

        fun has(n: Int) = fields.any { it.number == n }

        /** Last occurrence wins, as in protobuf; 0 when absent. */
        fun u64(n: Int): Long = fields.lastOrNull { it.number == n && it.wire != 2 }?.value ?: 0L

        fun i32(n: Int): Int = u64(n).toInt()

        fun bool(n: Int): Boolean = u64(n) != 0L

        fun str(n: Int): String = fields.lastOrNull { it.number == n && it.wire == 2 }
            ?.let { text(it) } ?: ""

        fun strs(n: Int): List<String> = fields.filter { it.number == n && it.wire == 2 }
            .map { text(it) }

        /** UTF-8 with the trailing NULs some fields carry (e.g. the selinux label) removed. */
        private fun text(f: Field): String = String(buf, f.off, f.len, Charsets.UTF_8).trimEnd('\u0000')

        fun msgs(n: Int): List<Msg> = fields.filter { it.number == n && it.wire == 2 }
            .map { parse(buf, it.off, it.off + it.len) }

        companion object {
            /** @throws IllegalArgumentException on malformed input. */
            fun parse(buf: ByteArray, start: Int, end: Int): Msg {
                val out = ArrayList<Field>()
                val r = Reader(buf, start, end)
                while (r.pos < end) {
                    val tag = r.varint()
                    val number = (tag ushr 3).toInt()
                    val wire = (tag and 7).toInt()
                    require(number > 0) { "bad field number at ${r.pos}" }
                    when (wire) {
                        0 -> out.add(Field(number, 0, r.varint(), 0, 0))
                        1 -> out.add(Field(number, 1, r.fixed(8), 0, 0))
                        5 -> out.add(Field(number, 5, r.fixed(4), 0, 0))
                        2 -> {
                            val len = r.varint()
                            require(len >= 0 && len <= end - r.pos) { "bad length $len at ${r.pos}" }
                            out.add(Field(number, 2, 0, r.pos, len.toInt()))
                            r.pos += len.toInt()
                        }
                        3 -> r.skipGroup(number)
                        else -> throw IllegalArgumentException("bad wire type $wire at ${r.pos}")
                    }
                }
                return Msg(buf, out)
            }
        }
    }

    private class Reader(val buf: ByteArray, var pos: Int, val end: Int) {
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(pos < end) { "truncated varint" }
                val b = buf[pos++].toInt() and 0xff
                if (shift < 64) result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                require(shift < 70) { "varint too long" }
            }
        }

        fun fixed(size: Int): Long {
            require(end - pos >= size) { "truncated fixed$size" }
            var v = 0L
            for (i in 0 until size) v = v or ((buf[pos + i].toLong() and 0xff) shl (8 * i))
            pos += size
            return v
        }

        fun skipGroup(number: Int) {
            while (true) {
                val tag = varint()
                val wire = (tag and 7).toInt()
                when (wire) {
                    0 -> varint()
                    1 -> fixed(8)
                    5 -> fixed(4)
                    2 -> {
                        val len = varint()
                        require(len >= 0 && len <= end - pos) { "bad length in group" }
                        pos += len.toInt()
                    }
                    3 -> skipGroup((tag ushr 3).toInt())
                    4 -> {
                        require((tag ushr 3).toInt() == number) { "mismatched end group" }
                        return
                    }
                    else -> throw IllegalArgumentException("bad wire type $wire in group")
                }
            }
        }
    }
}
