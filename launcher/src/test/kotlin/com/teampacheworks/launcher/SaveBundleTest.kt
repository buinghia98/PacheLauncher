package com.teampacheworks.launcher

import com.teampacheworks.launcher.cloud.CloudPayload
import com.teampacheworks.launcher.save.SaveBundle
import com.teampacheworks.launcher.save.SaveImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Design spec §2: the bundle format and the one save-writing path. */
class SaveBundleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        installTestConfig()
        // A representative structural check for a text-based save format: first non-blank byte
        // of the first line must be '>'.
        SaveBundle.headerCheck = { body ->
            var i = 0
            var ok = false
            while (i < body.size) {
                val c = body[i].toInt()
                if (c == '\r'.code || c == '\n'.code || c == ' '.code || c == '\t'.code) { i++; continue }
                ok = c == '>'.code
                break
            }
            ok
        }
    }

    private fun fasta(body: String) = ">save\n$body\n".toByteArray()

    // ------------------------------------------------------------- determinism

    @Test
    fun `bundle bytes are deterministic regardless of insertion order`() {
        val a = SaveBundle.createBundle(
            linkedMapOf("save_02.fasta" to fasta("B"), "save_01.fasta" to fasta("A"))
        )
        val b = SaveBundle.createBundle(
            linkedMapOf("save_01.fasta" to fasta("A"), "save_02.fasta" to fasta("B"))
        )
        assertTrue("entry order must not affect the bytes", a.contentEquals(b))
        assertEquals(SaveBundle.sha256Hex(a), SaveBundle.sha256Hex(b))
    }

    @Test
    fun `bundle bytes do not change over time`() {
        val one = SaveBundle.createBundle(mapOf("save_01.fasta" to fasta("A")))
        Thread.sleep(1100) // long enough that a real mtime would differ
        val two = SaveBundle.createBundle(mapOf("save_01.fasta" to fasta("A")))
        assertTrue("timestamps must be pinned, not taken from the clock", one.contentEquals(two))
    }

    @Test
    fun `different content gives a different hash`() {
        val a = SaveBundle.createBundle(mapOf("save_01.fasta" to fasta("A")))
        val b = SaveBundle.createBundle(mapOf("save_01.fasta" to fasta("B")))
        assertFalse(SaveBundle.sha256Hex(a) == SaveBundle.sha256Hex(b))
    }

    // ------------------------------------------------------------------ listing

    @Test
    fun `only matching-pattern files are bundled and excluded names are skipped`() {
        val dir = tmp.newFolder("files")
        File(dir, "save_01.fasta").writeBytes(fasta("A"))
        File(dir, "newgameplus01.fasta").writeBytes(fasta("B"))
        File(dir, "NGPlusLog.fasta").writeBytes(fasta("C"))
        File(dir, "device_config.txt").writeText("volume=5")
        File(dir, "devkey.txt").writeText("nope")
        File(dir, "cloud.token").writeBytes(byteArrayOf(1, 2, 3))

        val names = SaveBundle.listSaveFiles(dir).map { it.name }
        assertEquals(listOf("NGPlusLog.fasta", "newgameplus01.fasta", "save_01.fasta"), names)

        val entries = SaveBundle.readEntries(SaveBundle.createBundleFromDir(dir))!!
        assertEquals(3, entries.size)
        assertFalse(entries.containsKey("device_config.txt"))
        assertFalse(entries.containsKey("cloud.token"))
    }

    @Test
    fun `glob matching supports both wildcards`() {
        installTestConfig(savePatterns = listOf("save_??.dat"))
        assertTrue(SaveBundle.isSaveFileName("save_01.dat"))
        assertTrue(SaveBundle.isSaveFileName("SAVE_99.DAT")) // case-insensitive
        assertFalse(SaveBundle.isSaveFileName("save_001.dat"))
        assertFalse(SaveBundle.isSaveFileName("save_01.dat.bak"))
    }

    @Test
    fun `listing a missing directory is empty, not an exception`() {
        assertTrue(SaveBundle.listSaveFiles(File(tmp.root, "does-not-exist")).isEmpty())
    }

    // --------------------------------------------------------------- validation

    @Test
    fun `validation accepts a real bundle`() {
        val bundle = SaveBundle.createBundle(mapOf("save_01.fasta" to fasta("A")))
        val v = SaveBundle.validate(bundle)
        assertTrue(v.ok)
        assertEquals(listOf("save_01.fasta"), v.saveNames)
        assertTrue(v.headerWarn.isEmpty())
    }

    @Test
    fun `validation rejects non-zip, empty entries and pattern-less bundles`() {
        assertFalse(SaveBundle.validate("hello".toByteArray()).ok)
        assertFalse(SaveBundle.validate(SaveBundle.createBundle(mapOf("readme.txt" to "x".toByteArray()))).ok)
        assertFalse(SaveBundle.validate(SaveBundle.createBundle(mapOf("save_01.fasta" to ByteArray(0)))).ok)
    }

    @Test
    fun `a missing header warns but does not block`() {
        val v = SaveBundle.validate(
            SaveBundle.createBundle(mapOf("save_01.fasta" to "no header here".toByteArray()))
        )
        assertTrue("structural checks passed, so it must not be fatal", v.ok)
        assertEquals(listOf("save_01.fasta"), v.headerWarn)
    }

    @Test
    fun `header detection skips leading whitespace`() {
        assertTrue(SaveBundle.hasValidHeader("\r\n  >x".toByteArray()))
        assertFalse(SaveBundle.hasValidHeader("x>".toByteArray()))
        assertFalse(SaveBundle.hasValidHeader(ByteArray(0)))
    }

    @Test
    fun `default header check accepts any non-empty entry`() {
        SaveBundle.headerCheck = { it.isNotEmpty() }
        assertTrue(SaveBundle.hasValidHeader("anything".toByteArray()))
        assertFalse(SaveBundle.hasValidHeader(ByteArray(0)))
    }

    @Test
    fun `zip entry paths are flattened so a bundle cannot escape the save directory`() {
        val evil = SaveBundle.createBundle(mapOf("../../../etc/save_01.fasta" to fasta("A")))
        val entries = SaveBundle.readEntries(evil)!!
        assertEquals(setOf("save_01.fasta"), entries.keys)
    }

    // ------------------------------------------------------------ import detect

    @Test
    fun `import detects a raw zip`() {
        val bundle = SaveBundle.createBundle(mapOf("save_01.fasta" to fasta("A")))
        val p = SaveImporter.prepare(bundle)
        assertTrue(p is SaveImporter.Prepared.Ready)
        assertFalse((p as SaveImporter.Prepared.Ready).fromFence)
    }

    @Test
    fun `import detects a base64 fenced cloud payload`() {
        val bundle = SaveBundle.createBundle(mapOf("save_01.fasta" to fasta("A")))
        val text = CloudPayload.encode(bundle, 1)
        val p = SaveImporter.prepare(text.toByteArray())
        assertTrue(p is SaveImporter.Prepared.Ready)
        assertTrue((p as SaveImporter.Prepared.Ready).fromFence)
        assertTrue(bundle.contentEquals(p.bundle))
    }

    @Test
    fun `import rejects anything else`() {
        assertTrue(SaveImporter.prepare(ByteArray(0)) is SaveImporter.Prepared.Rejected)
        assertTrue(SaveImporter.prepare("random text".toByteArray()) is SaveImporter.Prepared.Rejected)
    }

    // ------------------------------------------------------------ commit + roll

    @Test
    fun `commit backs up, replaces and leaves no temp file`() {
        val files = tmp.newFolder("files")
        val cache = tmp.newFolder("cache")
        File(files, "save_01.fasta").writeBytes(fasta("OLD"))
        File(files, "save_02.fasta").writeBytes(fasta("KEEP"))

        val incoming = SaveBundle.createBundle(mapOf("save_01.fasta" to fasta("NEW")))
        val ready = SaveImporter.prepare(incoming) as SaveImporter.Prepared.Ready
        val result = SaveImporter.commit(files, cache, ready)

        assertTrue(result.error, result.ok)
        assertEquals(1, result.written)
        assertEquals(">save\nNEW\n", File(files, "save_01.fasta").readText())
        assertEquals("untouched files are left alone", ">save\nKEEP\n", File(files, "save_02.fasta").readText())

        val backup = File(files, SaveBundle.PRE_IMPORT_BACKUP)
        assertTrue("pre-import backup must exist", backup.isFile)
        val restored = SaveBundle.readEntries(backup.readBytes())!!
        assertEquals(">save\nOLD\n", String(restored["save_01.fasta"]!!))
        assertEquals(">save\nKEEP\n", String(restored["save_02.fasta"]!!))

        assertTrue("temp files must be cleaned up", cache.listFiles()!!.none { it.name.startsWith("import-") })
    }

    @Test
    fun `commit into an empty directory works`() {
        val files = tmp.newFolder("files")
        val cache = tmp.newFolder("cache")
        val incoming = SaveBundle.createBundle(mapOf("save_01.fasta" to fasta("FRESH")))
        val ready = SaveImporter.prepare(incoming) as SaveImporter.Prepared.Ready
        assertTrue(SaveImporter.commit(files, cache, ready).ok)
        assertEquals(">save\nFRESH\n", File(files, "save_01.fasta").readText())
    }

    @Test
    fun `rollback restores byte for byte when a write fails`() {
        val files = tmp.newFolder("files")
        val cache = tmp.newFolder("cache")
        File(files, "save_01.fasta").writeBytes(fasta("ORIGINAL"))

        // A directory where a save file should go makes writeBytes throw mid-replace.
        File(files, "save_02.fasta").mkdirs()

        val incoming = SaveBundle.createBundle(
            linkedMapOf("save_01.fasta" to fasta("NEW"), "save_02.fasta" to fasta("ALSO NEW"))
        )
        val ready = SaveImporter.prepare(incoming) as SaveImporter.Prepared.Ready
        val result = SaveImporter.commit(files, cache, ready)

        assertFalse(result.ok)
        assertNotNull(result.error)
        assertEquals(
            "the already-replaced file must be restored byte for byte",
            ">save\nORIGINAL\n",
            File(files, "save_01.fasta").readText()
        )
        assertTrue(cache.listFiles()!!.none { it.name.startsWith("import-") })
    }

    @Test
    fun `readEntries returns null for anything that is not a zip`() {
        assertNull(SaveBundle.readEntries("PK-but-not-really".toByteArray()))
        assertNull(SaveBundle.readEntries(ByteArray(0)))
        assertFalse(SaveBundle.looksLikeZip("abc".toByteArray()))
    }
}
