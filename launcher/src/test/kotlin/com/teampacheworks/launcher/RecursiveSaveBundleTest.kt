package com.teampacheworks.launcher

import com.teampacheworks.launcher.save.SaveBundle
import com.teampacheworks.launcher.save.SaveImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [com.teampacheworks.launcher.LauncherConfig.recursiveSaves] (INTEGRATION_PLAN.md §5): a game
 * whose save data is nested under `filesDir` - UNBEATABLE's `PROFILES/<guid>/StorySlot0/...` being
 * the motivating case - opts in and gets a recursive walk with relative-path zip entries instead of
 * the flat, basename-only scan.
 */
class RecursiveSaveBundleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun installRecursive(
        savePatterns: List<String> = listOf("*.json", "format-version.txt"),
        saveExcludeNames: Set<String> = emptySet()
    ) {
        installTestConfig(
            savePatterns = savePatterns,
            saveExcludeNames = saveExcludeNames,
            recursiveSaves = true
        )
        SaveBundle.headerCheck = { body ->
            body.isNotEmpty() && (body[0] == '{'.code.toByte() || Character.isDigit(body[0].toInt().toChar()))
        }
    }

    private fun writeNestedTree(root: File) {
        File(root, "SYSTEM").mkdirs()
        File(root, "SYSTEM/system-options.json").writeText("{\"vol\":5}")

        val profileDir = File(root, "PROFILES/GUID-1")
        File(profileDir, "StorySlot0").mkdirs()
        File(profileDir, "StorySlot1").mkdirs()
        File(profileDir, "format-version.txt").writeText("3")
        File(profileDir, "profile-metadata.json").writeText("{\"name\":\"P1\"}")
        File(profileDir, "StorySlot0/variable-storage.json").writeText("{\"slot\":0}")
        File(profileDir, "StorySlot1/variable-storage.json").writeText("{\"slot\":1}")

        // Hard-skip / hidden entries that must never end up in the bundle.
        File(root, "cloud.token").writeBytes(byteArrayOf(1, 2, 3))
        File(root, ".hidden").mkdirs()
        File(root, ".hidden/should-not-appear.json").writeText("{}")
    }

    // -------------------------------------------------------------- export

    @Test
    fun `nested tree export includes deep files keyed by relative path`() {
        installRecursive()
        val root = tmp.newFolder("files")
        writeNestedTree(root)

        val names = SaveBundle.listSaveFiles(root).map { SaveBundle.relativePath(root, it) }
        assertEquals(
            listOf(
                "PROFILES/GUID-1/StorySlot0/variable-storage.json",
                "PROFILES/GUID-1/StorySlot1/variable-storage.json",
                "PROFILES/GUID-1/format-version.txt",
                "PROFILES/GUID-1/profile-metadata.json",
                "SYSTEM/system-options.json"
            ),
            names
        )

        val bundle = SaveBundle.createBundleFromDir(root)
        val entries = SaveBundle.readEntries(bundle)!!
        assertEquals(names.toSet(), entries.keys)
        assertFalse(entries.keys.any { it.contains("cloud.token") })
        assertFalse(entries.keys.any { it.contains(".hidden") })
        assertEquals("{\"slot\":0}", String(entries.getValue("PROFILES/GUID-1/StorySlot0/variable-storage.json")))
        assertEquals("{\"slot\":1}", String(entries.getValue("PROFILES/GUID-1/StorySlot1/variable-storage.json")))
    }

    @Test
    fun `saveExcludeNames matches basename and relative path in recursive mode`() {
        installRecursive(saveExcludeNames = setOf("profile-metadata.json"))
        val root = tmp.newFolder("files")
        writeNestedTree(root)

        val names = SaveBundle.listSaveFiles(root).map { SaveBundle.relativePath(root, it) }
        assertFalse(names.contains("PROFILES/GUID-1/profile-metadata.json"))
    }

    @Test
    fun `nested export is deterministic`() {
        installRecursive()
        val rootA = tmp.newFolder("filesA")
        val rootB = tmp.newFolder("filesB")
        writeNestedTree(rootA)
        writeNestedTree(rootB)

        val a = SaveBundle.createBundleFromDir(rootA)
        Thread.sleep(1100) // long enough that a real mtime would differ
        val b = SaveBundle.createBundleFromDir(rootB)

        assertTrue("same tree content must give byte-identical zips", a.contentEquals(b))
        assertEquals(SaveBundle.sha256Hex(a), SaveBundle.sha256Hex(b))
    }

    // -------------------------------------------------------------- import

    @Test
    fun `import round trip restores the tree byte-exact`() {
        installRecursive()
        val files = tmp.newFolder("files")
        val cache = tmp.newFolder("cache")
        writeNestedTree(files)

        val bundle = SaveBundle.createBundleFromDir(files)
        val originalEntries = SaveBundle.readEntries(bundle)!!

        // Simulate a fresh install: wipe filesDir's save tree, then re-import.
        File(files, "PROFILES").deleteRecursively()
        File(files, "SYSTEM").deleteRecursively()

        val ready = SaveImporter.prepare(bundle) as SaveImporter.Prepared.Ready
        val result = SaveImporter.commit(files, cache, ready)
        assertTrue(result.error, result.ok)
        assertEquals(originalEntries.size, result.written)

        val restoredBundle = SaveBundle.createBundleFromDir(files)
        // filter out the pre-import backup entry that createBundleFromDir would never include
        // anyway (it doesn't match savePatterns), so this is a straight content comparison.
        val restoredEntries = SaveBundle.readEntries(restoredBundle)!!
        assertEquals(originalEntries.keys, restoredEntries.keys)
        for ((name, bytes) in originalEntries) {
            assertTrue("entry '$name' must round-trip byte for byte", bytes.contentEquals(restoredEntries.getValue(name)))
        }
    }

    @Test
    fun `rollback on mid-import failure restores the original tree`() {
        installRecursive()
        val files = tmp.newFolder("files")
        val cache = tmp.newFolder("cache")
        writeNestedTree(files)
        val before = SaveBundle.readEntries(SaveBundle.createBundleFromDir(files))!!

        // Make one deep target path unwritable: put a directory where a save file needs to go.
        File(files, "SYSTEM/system-options.json").delete()
        File(files, "SYSTEM/system-options.json").mkdirs()

        val incoming = SaveBundle.createBundle(
            linkedMapOf(
                "PROFILES/GUID-1/format-version.txt" to "9".toByteArray(),
                "SYSTEM/system-options.json" to "{\"vol\":1}".toByteArray()
            )
        )
        val ready = SaveImporter.prepare(incoming) as SaveImporter.Prepared.Ready
        val result = SaveImporter.commit(files, cache, ready)
        assertFalse(result.ok)

        val after = SaveBundle.readEntries(SaveBundle.createBundleFromDir(files))!!
        assertEquals(
            "the already-replaced deep entry must be rolled back",
            String(before.getValue("PROFILES/GUID-1/format-version.txt")),
            String(after.getValue("PROFILES/GUID-1/format-version.txt"))
        )
        assertTrue(cache.listFiles()!!.none { it.name.startsWith("import-") })
    }

    @Test
    fun `zip-slip entries are dropped on import in recursive mode`() {
        installRecursive()
        val evil = SaveBundle.createBundle(
            mapOf(
                "../../outside.json" to "{}".toByteArray(),
                "/etc/passwd.json" to "{}".toByteArray(),
                "PROFILES/GUID-1/format-version.txt" to "3".toByteArray()
            )
        )
        val entries = SaveBundle.readEntries(evil)!!
        assertEquals(setOf("PROFILES/GUID-1/format-version.txt"), entries.keys)
    }

    // -------------------------------------------------------------- headerCheck

    @Test
    fun `headerCheck runs per matching entry across the whole tree`() {
        installRecursive()
        val root = tmp.newFolder("files")
        writeNestedTree(root)
        // format-version.txt is a bare digit, everything else is '{'-led JSON - both must pass.
        val v = SaveBundle.validate(SaveBundle.createBundleFromDir(root))
        assertTrue(v.ok)
        assertTrue("no entry should warn: digits and '{' both satisfy headerCheck", v.headerWarn.isEmpty())
    }

    // ---------------------------------------------------------- regression

    @Test
    fun `default recursiveSaves=false leaves flat-dir behavior and hashes unchanged`() {
        installTestConfig() // recursiveSaves defaults to false
        val dir = tmp.newFolder("files")
        File(dir, "save_01.fasta").writeBytes(">save\nA\n".toByteArray())
        File(dir, "save_02.fasta").writeBytes(">save\nB\n".toByteArray())

        val names = SaveBundle.listSaveFiles(dir).map { it.name }
        assertEquals(listOf("save_01.fasta", "save_02.fasta"), names)

        val bundle = SaveBundle.createBundleFromDir(dir)
        // This is the exact flat-bundle identity from SaveBundleTest / the pre-existing format:
        // sorted flat basenames, entries built via SaveBundle.createBundle with the same bytes.
        val expected = SaveBundle.createBundle(
            mapOf(
                "save_01.fasta" to ">save\nA\n".toByteArray(),
                "save_02.fasta" to ">save\nB\n".toByteArray()
            )
        )
        assertTrue("recursiveSaves=false must not change flat-bundle bytes/identity", bundle.contentEquals(expected))
        assertEquals(SaveBundle.sha256Hex(expected), SaveBundle.sha256Hex(bundle))
    }

    @Test
    fun `default recursiveSaves=false ignores nested files entirely`() {
        installTestConfig(savePatterns = listOf("*.json"))
        val dir = tmp.newFolder("files")
        File(dir, "top.json").writeText("{}")
        File(dir, "nested").mkdirs()
        File(dir, "nested/deep.json").writeText("{}")

        val names = SaveBundle.listSaveFiles(dir).map { it.name }
        assertEquals(listOf("top.json"), names)
    }

    @Test
    fun `sanitizeZipEntryName rejects traversal and absolute paths, accepts clean relative paths`() {
        assertNull(SaveBundle.sanitizeZipEntryName("../evil.json"))
        assertNull(SaveBundle.sanitizeZipEntryName("/etc/passwd"))
        assertNull(SaveBundle.sanitizeZipEntryName("C:\\Windows\\evil.json"))
        assertNull(SaveBundle.sanitizeZipEntryName("a/../../b.json"))
        assertNull(SaveBundle.sanitizeZipEntryName(".git/config"))
        assertEquals("PROFILES/GUID-1/x.json", SaveBundle.sanitizeZipEntryName("PROFILES/GUID-1/x.json"))
        assertEquals("PROFILES/GUID-1/x.json", SaveBundle.sanitizeZipEntryName("PROFILES\\GUID-1\\x.json"))
    }
}
