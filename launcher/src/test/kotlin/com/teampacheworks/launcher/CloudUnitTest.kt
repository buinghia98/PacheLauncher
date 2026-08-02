package com.teampacheworks.launcher

import com.teampacheworks.launcher.cloud.CloudException
import com.teampacheworks.launcher.cloud.CloudManifest
import com.teampacheworks.launcher.cloud.CloudPayload
import com.teampacheworks.launcher.cloud.CloudSlot
import com.teampacheworks.launcher.cloud.CloudStore
import com.teampacheworks.launcher.cloud.GitHubToken
import com.teampacheworks.launcher.save.SaveBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Design spec §3 invariants that can be pinned down without a device or a network. */
class CloudUnitTest {

    @Before
    fun setUp() {
        installTestConfig()
    }

    // ------------------------------------------------------------ token shapes

    @Test
    fun `fine grained token is accepted`() {
        val r = GitHubToken.parse("github_pat_11ABCDEFG0abcdefgHIJKLMNOP_qrstuvwxyz0123456789ABCDEfghij")
        assertTrue(r is GitHubToken.Parsed.Ok)
    }

    @Test
    fun `classic token gets its own message`() {
        val r = GitHubToken.parse("ghp_0123456789abcdefghijABCDEFGHIJKLMNop")
        assertTrue(r is GitHubToken.Parsed.Invalid)
        val msg = (r as GitHubToken.Parsed.Invalid).error
        assertTrue("classic branch must be named explicitly", msg.contains("classic", ignoreCase = true))
        assertTrue(msg.contains("fine-grained"))
    }

    @Test
    fun `every classic prefix family is recognised as classic`() {
        for (p in listOf("ghp_", "gho_", "ghu_", "ghs_", "ghr_")) {
            val r = GitHubToken.parse(p + "0123456789abcdefghijABCDEFGHIJKLMNop")
            val msg = (r as GitHubToken.Parsed.Invalid).error
            assertTrue("$p should be classified classic", msg.contains("classic", ignoreCase = true))
        }
    }

    @Test
    fun `garbage and empty are rejected without mentioning classic tokens`() {
        assertTrue(GitHubToken.parse(null) is GitHubToken.Parsed.Invalid)
        assertTrue(GitHubToken.parse("   ") is GitHubToken.Parsed.Invalid)
        val r = GitHubToken.parse("not-a-token") as GitHubToken.Parsed.Invalid
        assertFalse(r.error.contains("That is a classic"))
    }

    @Test
    fun `token whitespace is trimmed and inner spaces are not accepted`() {
        assertTrue(GitHubToken.parse("  github_pat_abc123  ") is GitHubToken.Parsed.Ok)
        assertTrue(GitHubToken.parse("github_pat_abc 123") is GitHubToken.Parsed.Invalid)
    }

    @Test
    fun `token never reveals its value`() {
        val t = (GitHubToken.parse("github_pat_secretvalue123") as GitHubToken.Parsed.Ok).token
        assertEquals(GitHubToken.MASK, t.toString())
        assertFalse(t.toString().contains("secretvalue"))
    }

    @Test
    fun `token applies itself as a bearer header and nowhere else`() {
        val t = (GitHubToken.parse("github_pat_headeronly99") as GitHubToken.Parsed.Ok).token
        val req = t.applyTo(okhttp3.Request.Builder().url("https://api.github.com/user")).build()
        assertEquals("Bearer github_pat_headeronly99", req.header("Authorization"))
        assertFalse("the token must never reach the URL", req.url.toString().contains("github_pat_"))
    }

    // ---------------------------------------------------------- message scrub

    @Test
    fun `exception messages are scrubbed of credentials`() {
        val ex = CloudException(
            com.teampacheworks.launcher.cloud.CloudFailure.Protocol,
            "boom github_pat_0123456789abcdefghijklmn and ghp_0123456789abcdefghijklmnop"
        )
        assertFalse(ex.message!!.contains("github_pat_0123"))
        assertFalse(ex.message!!.contains("ghp_0123"))
        assertTrue(ex.message!!.contains("***REDACTED***"))
    }

    @Test
    fun `gist ids are masked before they can reach a log line`() {
        assertEquals("aabbccd…", CloudException.maskGistId("aabbccddeeff00112233"))
        assertEquals("-", CloudException.maskGistId(null))
    }

    // -------------------------------------------------------------- payload

    @Test
    fun `payload round trips`() {
        val bundle = SaveBundle.createBundle(mapOf("save_01.fasta" to ">alpha\nAAAA\n".toByteArray()))
        val text = CloudPayload.encode(bundle, 3)
        val decoded = CloudPayload.decode(text)
        assertTrue(decoded is CloudPayload.Decoded.Ok)
        assertTrue(bundle.contentEquals((decoded as CloudPayload.Decoded.Ok).bytes))
    }

    @Test
    fun `fence markers use the configured tag`() {
        assertEquals("-----BEGIN TESTGAME SAVE-----", CloudPayload.BEGIN_FENCE)
        assertEquals("-----END TESTGAME SAVE-----", CloudPayload.END_FENCE)
    }

    @Test
    fun `payload base64 body is wrapped at 76 columns`() {
        val big = ByteArray(4096) { (it % 251).toByte() }
        val text = CloudPayload.encode(big, 1)
        val body = text.lines()
            .dropWhile { it != CloudPayload.BEGIN_FENCE }.drop(1)
            .takeWhile { it != CloudPayload.END_FENCE }
        assertTrue(body.size > 1)
        // Every line but the last is exactly 76 columns wide.
        for (l in body.dropLast(1)) assertEquals(76, l.length)
        assertTrue(body.last().length in 1..76)
    }

    @Test
    fun `decoder ignores the header lines entirely`() {
        val bundle = SaveBundle.createBundle(mapOf("save_01.fasta" to ">a\nGG\n".toByteArray()))
        val honest = CloudPayload.encode(bundle, 2)
        // Lie about the size and hash in the header; the fence is the only thing that counts.
        val lying = honest.lines().toMutableList()
        lying[0] = "Test Game cloud save · slot 99 · 999999 bytes · sha256 deadbeef"
        lying[1] = "totally different second line"
        val decoded = CloudPayload.decode(lying.joinToString("\n"))
        assertTrue(decoded is CloudPayload.Decoded.Ok)
        assertTrue(bundle.contentEquals((decoded as CloudPayload.Decoded.Ok).bytes))
    }

    @Test
    fun `decoder tolerates whitespace damage inside the fence`() {
        val bundle = SaveBundle.createBundle(mapOf("save_01.fasta" to ">a\nGGGGGGGGGGGGGGGG\n".toByteArray()))
        val text = CloudPayload.encode(bundle, 1)
            .replace("\n", "\r\n")            // CRLF normalisation by a text pipeline
            .replace(CloudPayload.END_FENCE, "  \t \n" + CloudPayload.END_FENCE)
        val decoded = CloudPayload.decode(text)
        assertTrue(decoded is CloudPayload.Decoded.Ok)
        assertTrue(bundle.contentEquals((decoded as CloudPayload.Decoded.Ok).bytes))
    }

    @Test
    fun `missing or truncated fence is refused, never guessed`() {
        assertTrue(CloudPayload.decode("just some text") is CloudPayload.Decoded.Err)
        val truncated = CloudPayload.BEGIN_FENCE + "\nQUJD\n"
        assertTrue(CloudPayload.decode(truncated) is CloudPayload.Decoded.Err)
    }

    @Test
    fun `a fence quoted mid sentence is not treated as a payload`() {
        val readme = "The file starts with a ${CloudPayload.BEGIN_FENCE} marker.\n"
        assertFalse(CloudPayload.looksLikeFenced(readme))
    }

    @Test
    fun `slot file names round trip`() {
        assertEquals("save-07.zip.b64.txt", CloudPayload.fileNameFor(7))
        assertEquals(7, CloudPayload.slotOf("save-07.zip.b64.txt"))
        assertTrue(CloudPayload.isSlotFileName("save-01.zip.b64.txt"))
        assertFalse(CloudPayload.isSlotFileName("01-manifest.json"))
        assertNull(CloudPayload.slotOf("00-README.md"))
    }

    // -------------------------------------------------------------- manifest

    @Test
    fun `manifest json round trips`() {
        val m = CloudManifest.createEmpty()
        m.upsert(CloudSlot("save-01.zip.b64.txt", "aa11", 1234, "before boss", "2026-08-02T10:00:00Z"))
        m.upsert(CloudSlot("save-02.zip.b64.txt", "bb22", 4321, "", "2026-08-02T11:00:00Z"))
        val json = m.toJson()

        val back = CloudManifest.tryParse(json)
        assertTrue(back is CloudManifest.Parse.Ok)
        val m2 = (back as CloudManifest.Parse.Ok).manifest
        assertEquals("testgame", m2.app)
        assertEquals(1, m2.version)
        assertEquals(m.revision, m2.revision)
        assertEquals(2, m2.slots.size)
        val s = m2.find("save-01.zip.b64.txt")!!
        assertEquals("aa11", s.sha256)
        assertEquals(1234L, s.bytes)
        assertEquals("before boss", s.note)
        assertEquals(1, s.slotNumber)
    }

    @Test
    fun `unknown fields survive a rewrite`() {
        val json = """
            {"app":"testgame","version":1,"revision":4,"futureThing":{"x":1},
             "slots":[{"file":"save-01.zip.b64.txt","sha256":"aa","bytes":1,
                       "note":"n","uploadedAt":"t","futureRowField":"keep me"}]}
        """.trimIndent()
        val m = (CloudManifest.tryParse(json) as CloudManifest.Parse.Ok).manifest
        m.upsert(CloudSlot("save-02.zip.b64.txt", "bb", 2))
        val out = m.toJson()
        assertTrue("top-level unknown key lost", out.contains("futureThing"))
        assertTrue("per-row unknown key lost", out.contains("keep me"))
    }

    @Test
    fun `revision increments on every mutation`() {
        val m = CloudManifest.createEmpty()
        assertEquals(0, m.revision)
        m.upsert(CloudSlot("save-01.zip.b64.txt", "aa", 1))
        assertEquals(1, m.revision)
        m.upsert(CloudSlot("save-02.zip.b64.txt", "bb", 2))
        assertEquals(2, m.revision)
        m.remove("save-01.zip.b64.txt")
        assertEquals(3, m.revision)
        assertEquals(1, m.slots.size)
    }

    @Test
    fun `broken manifest degrades instead of throwing`() {
        assertTrue(CloudManifest.tryParse(null) is CloudManifest.Parse.Err)
        assertTrue(CloudManifest.tryParse("") is CloudManifest.Parse.Err)
        assertTrue(CloudManifest.tryParse("[1,2,3]") is CloudManifest.Parse.Err)
        assertTrue(CloudManifest.tryParse("{ not json") is CloudManifest.Parse.Err)
        // A manifest with no slots array is empty, not broken.
        val ok = CloudManifest.tryParse("""{"app":"testgame","revision":2}""")
        assertTrue(ok is CloudManifest.Parse.Ok)
        assertEquals(0, (ok as CloudManifest.Parse.Ok).manifest.slots.size)
    }

    @Test
    fun `discovery only claims gists whose manifest names this app`() {
        assertTrue(CloudManifest.isOurManifest("""{"app":"testgame","revision":1,"slots":[]}"""))
        assertFalse(CloudManifest.isOurManifest("""{"app":"someothergame","revision":1,"slots":[]}"""))
        assertFalse(CloudManifest.isOurManifest("garbage"))
        // The explicit-appId overload works without any LauncherHost config installed.
        assertTrue(CloudManifest.isOurManifest("""{"app":"foo"}""", expectedAppId = "foo"))
    }

    @Test
    fun `next free slot fills gaps in order`() {
        val m = CloudManifest.createEmpty()
        m.upsert(CloudSlot("save-01.zip.b64.txt", "a", 1))
        m.upsert(CloudSlot("save-03.zip.b64.txt", "c", 1))
        assertEquals(2, m.nextFreeSlot(20))
        m.upsert(CloudSlot("save-02.zip.b64.txt", "b", 1))
        assertEquals(4, m.nextFreeSlot(20))
        // Full gist -> null, never an overwrite.
        val full = CloudManifest.createEmpty()
        for (i in 1..3) full.upsert(CloudSlot(CloudPayload.fileNameFor(i), "h$i", 1))
        assertNull(full.nextFreeSlot(3))
    }

    // ------------------------------------------------------------ dedup gate

    @Test
    fun `sha256 dedup gate matches on content identity`() {
        val a = SaveBundle.createBundle(mapOf("save_01.fasta" to ">x\nAB\n".toByteArray()))
        val b = SaveBundle.createBundle(mapOf("save_01.fasta" to ">x\nAB\n".toByteArray()))
        val other = SaveBundle.createBundle(mapOf("save_01.fasta" to ">x\nCD\n".toByteArray()))
        val shaA = SaveBundle.sha256Hex(a)

        // Same content -> same bytes -> same hash, regardless of when it was produced.
        assertTrue(a.contentEquals(b))
        assertEquals(shaA, SaveBundle.sha256Hex(b))

        val m = CloudManifest.createEmpty()
        m.upsert(CloudSlot("save-01.zip.b64.txt", shaA, a.size.toLong()))
        val revisionBefore = m.revision

        assertNotNull("re-uploading identical content must hit the gate", m.findBySha(shaA))
        assertNull("different content must not hit the gate", m.findBySha(SaveBundle.sha256Hex(other)))
        assertEquals("a gate hit must not bump the revision", revisionBefore, m.revision)
    }

    @Test
    fun `sha comparison is case insensitive and whitespace tolerant`() {
        assertTrue(SaveBundle.hashesMatch("AABB", " aabb "))
        assertFalse(SaveBundle.hashesMatch("aabb", "aabc"))
        assertFalse(SaveBundle.hashesMatch(null, "aabb"))
        assertFalse(SaveBundle.hashesMatch("", ""))
    }

    // ------------------------------------------------------- revision staleness

    @Test
    fun `revision staleness rule`() {
        // A manifest older than what this device last wrote is a stale edge copy.
        assertTrue(CloudStore.isStale(fetchedRevision = 4, lastWrittenRevision = 5))
        // Equal is current, not stale.
        assertFalse(CloudStore.isStale(fetchedRevision = 5, lastWrittenRevision = 5))
        // Newer means another device wrote after us - current, and definitely not stale.
        assertFalse(CloudStore.isStale(fetchedRevision = 9, lastWrittenRevision = 5))
        // Fresh device that has never written anything trusts whatever it fetches.
        assertFalse(CloudStore.isStale(fetchedRevision = 0, lastWrittenRevision = 0))
    }
}
