package com.teampacheworks.launcher

import com.teampacheworks.launcher.log.TombstoneDecoder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Hand-encoded Tombstone protobufs (field numbers from AOSP debuggerd/proto/tombstone.proto). */
class TombstoneDecoderTest {

    // ------------------------------------------------------------ tiny protobuf encoder

    private class Pb {
        val out = ByteArrayOutputStream()
        private fun varint(v: Long) {
            var x = v
            while (true) {
                if (x and 0x7fL.inv() == 0L) { out.write(x.toInt()); return }
                out.write(((x and 0x7f) or 0x80).toInt())
                x = x ushr 7
            }
        }
        fun u(n: Int, v: Long) = apply { varint((n.toLong() shl 3) or 0); varint(v) }
        fun b(n: Int, v: ByteArray) = apply { varint((n.toLong() shl 3) or 2); varint(v.size.toLong()); out.write(v) }
        fun s(n: Int, v: String) = b(n, v.toByteArray())
        fun m(n: Int, v: Pb) = b(n, v.bytes())
        fun f64(n: Int, v: Long) = apply {
            varint((n.toLong() shl 3) or 1)
            for (i in 0 until 8) out.write(((v ushr (8 * i)) and 0xff).toInt())
        }
        fun bytes(): ByteArray = out.toByteArray()
    }

    private fun frame(relPc: Long, pc: Long, fn: String, off: Long, file: String, bid: String) =
        Pb().u(1, relPc).u(2, pc).s(4, fn).u(5, off).s(6, file).s(8, bid)

    private fun synthetic(): ByteArray {
        val crash = Pb().u(1, 4242).s(2, "GameThread")
            .m(3, Pb().s(1, "x0").u(2, 0))
            .m(3, Pb().s(1, "pc").u(2, 0x7f12345678L))
            .m(4, frame(0x8a2c4, 0x7f12345678L, "syscall", 28,
                "/apex/com.android.runtime/lib64/bionic/libc.so", "59cafcd8c6514064584f920f18ea8760"))
            .m(4, Pb().u(1, 0x1234).u(2, 0x100001234L).s(6, "<anonymous:100000000>"))
        val other = Pb().u(1, 4243).s(2, "RenderThread")
            .m(4, frame(0x10, 0x7f00000010L, "__futex_wait", 4, "/system/lib64/libc.so", ""))
        return Pb()
            .u(1, 1) // ARM64
            .s(2, "vendor/device:14/X/Y:user/release-keys")
            .s(4, "2026-10-06 11:11:07")
            .u(5, 4242).u(6, 4242).u(7, 10234)
            .s(9, "com.example.game:game")
            .m(10, Pb().u(1, 11).s(2, "SIGSEGV").u(3, 0).s(4, "SI_USER")
                .u(5, 1).u(6, 10234).u(7, 4242))
            .m(15, Pb().s(1, "null pointer dereference"))
            .m(16, Pb().u(1, 4243).m(2, other))
            .m(16, Pb().u(1, 4242).m(2, crash))
            .m(17, Pb().u(1, 0x100000000L).u(2, 0x100400000L).u(4, 1).u(6, 1))
            .m(17, Pb().u(1, 0x7f12300000L).u(2, 0x7f12400000L).u(4, 1).u(6, 1)
                .s(7, "/apex/com.android.runtime/lib64/bionic/libc.so"))
            .m(17, Pb().u(1, 0x7f20000000L).u(2, 0x7f20001000L).u(4, 1).u(5, 1)) // not executable
            .m(18, Pb().s(1, "main").m(2, Pb().s(1, "10-06 11:11:07.000").u(2, 4242).u(3, 4242)
                .u(4, 4).s(5, "mina").s(6, "about to crash")))
            .f64(99, 0x1122334455667788L) // unknown fixed64 field must be skipped
            .bytes()
    }

    @Test
    fun decodesSyntheticTombstone() {
        val bytes = synthetic()
        assertTrue(TombstoneDecoder.looksLikeProtobuf(bytes))
        val text = TombstoneDecoder.render(bytes, mapOf(0x100000000L to "mach-o"))
        assertFalse(text, text.contains("decode failed"))
        assertTrue(text, text.contains("ABI: 'arm64'"))
        assertTrue(text, text.contains("pid: 4242, tid: 4242, name: GameThread  >>> com.example.game:game <<<"))
        assertTrue(text, text.contains("signal 11 (SIGSEGV), code 0 (SI_USER from pid 4242, uid 10234)"))
        assertTrue(text, text.contains("Cause: null pointer dereference"))
        assertTrue(text, text.contains("pc  0000007f12345678"))
        assertTrue(text, text.contains(
            "#00 pc 000000000008a2c4  /apex/com.android.runtime/lib64/bionic/libc.so (syscall+28) " +
                "(BuildId: 59cafcd8c6514064584f920f18ea8760)"))
        assertTrue(text, text.contains("#01 pc 0000000000001234  mach-o+0x1234  (<anonymous:100000000>)"))
        // crashing thread first, then the others
        assertTrue(text.indexOf("GameThread") < text.indexOf("RenderThread"))
        assertTrue(text, text.contains("(__futex_wait+4)"))
        assertTrue(text, text.contains("executable mappings, 2 of 3"))
        assertTrue(text, text.contains("[mach-o+0x0]"))
        assertTrue(text, text.contains("I mina: about to crash"))
    }

    @Test
    fun textTraceIsNotProtobuf() {
        val anr = "----- pid 123 at 2026-10-06 11:00:00 -----\nCmd line: com.example\n".toByteArray()
        assertFalse(TombstoneDecoder.looksLikeProtobuf(anr))
    }

    @Test
    fun truncatedInputFallsBack() {
        val bytes = synthetic()
        val text = TombstoneDecoder.render(bytes.copyOf(bytes.size / 2))
        assertTrue(text, text.contains("decode failed"))
        assertTrue(text, text.contains("Trace size:"))
    }

    @Test
    fun decodesRealDeviceTombstoneWhenPresent() {
        // Captured on a Retroid Pocket Nova (kill -SEGV of the game process); host-repo only.
        val f = File("../../../out/logs/crashreport_test/exit_game_20261006_233050.trace.pb")
        assumeTrue(f.isFile)
        val bytes = f.readBytes()
        assertTrue(TombstoneDecoder.looksLikeProtobuf(bytes))
        val text = TombstoneDecoder.render(bytes, mapOf(0x100000000L to "mach-o"))
        assertFalse(text.contains("decode failed"))
        assertTrue(text.contains("signal 11 (SIGSEGV)"))
        assertTrue(text.contains("libc.so (__epoll_pwait+"))
        assertTrue(text.contains("mach-o+0x"))
        assertFalse(text.contains('\u0000'))
    }

    @Test
    fun lossyRealReportDoesNotThrow() {
        // The pre-fix report whose tombstone went through String(bytes, UTF_8): undecodable by
        // construction, so only the never-throw fallback is checked.
        val f = File("../../../out/logs/mirror_crash/exit_game_20261006_111108.log.txt")
        assumeTrue(f.isFile)
        val all = f.readBytes()
        val marker = "--- Platform trace ---\n".toByteArray()
        val at = String(all, Charsets.ISO_8859_1).indexOf(String(marker, Charsets.ISO_8859_1))
        assumeTrue(at >= 0)
        val lossy = all.copyOfRange(at + marker.size, all.size)
        val text = TombstoneDecoder.render(lossy)
        assertTrue(text.isNotEmpty())
        TombstoneDecoder.looksLikeProtobuf(lossy)
        TombstoneDecoder.render(ByteArray(0))
        TombstoneDecoder.render(byteArrayOf(0x0a, 0x7f))
    }
}
