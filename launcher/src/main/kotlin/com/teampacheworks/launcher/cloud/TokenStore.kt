package com.teampacheworks.launcher.cloud

import com.teampacheworks.launcher.LauncherHost
import com.teampacheworks.launcher.log.LauncherLog
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Where the one stored secret lives at rest (design spec §3).
 *
 * 🔴 **NOT the Android Keystore.** A sister project's post-mortem: on one real handheld a Keystore
 * alias silently disappeared and took a real token with it, leaving the player signed out with no
 * way to tell why. The threat model here is "someone with shell access to a rooted handheld",
 * against which the Keystore buys little; losing the token to a vanished alias is the failure that
 * actually happened. So: a static AES key compiled into the app. This is **obfuscation, not
 * hardware protection**, and it is written down here honestly rather than dressed up as encryption.
 *
 * Two invariants, both load-bearing:
 *
 * 1. **Nothing here throws at the caller.** Absent, corrupt, foreign scheme, unwrap failure - all
 *    of them mean the same thing to the player ("you are signed out, paste a token again"), and all
 *    of them are handled by deleting the file and returning null.
 * 2. **The file is excluded from Auto Backup.** The host app's own `backup_rules.xml` /
 *    `data_extraction_rules.xml` must exclude `filesDir/cloud.token` and the
 *    [com.teampacheworks.launcher.LauncherConfig.cloudPrefsName] SharedPreferences file - see
 *    docs/INTEGRATION.md - so a device-to-device transfer never carries the token off this handheld.
 */
class TokenStore(private val file: File, private val magic: ByteArray) {

    val exists: Boolean
        get() = try { file.isFile } catch (t: Throwable) { false }

    /** Never throws. Null means "signed out", whatever the underlying reason was. */
    fun tryLoad(): GitHubToken? {
        var plaintext: ByteArray? = null
        try {
            if (!file.isFile) return null
            val rawBytes = file.readBytes()
            if (rawBytes.size <= magic.size || !startsWithMagic(rawBytes)) {
                LauncherLog.write("cloud", "token store: unrecognised header; discarding.")
                clear()
                return null
            }
            val blob = rawBytes.copyOfRange(magic.size, rawBytes.size)
            plaintext = unwrap(blob)
            val token = GitHubToken.fromStoredUtf8(plaintext)
            if (token == null) {
                LauncherLog.write("cloud", "token store: stored value is not token-shaped; discarding.")
                clear()
                return null
            }
            return token
        } catch (t: Throwable) {
            LauncherLog.write("cloud", "token store: load failed (${t.javaClass.simpleName}); treating as signed out.")
            clear()
            return null
        } finally {
            plaintext?.fill(0)
        }
    }

    /** @return true when the token reached disk. Never throws. */
    fun save(token: GitHubToken?): Boolean {
        if (token == null) { clear(); return true }
        var plaintext: ByteArray? = null
        return try {
            file.parentFile?.mkdirs()
            plaintext = token.copyUtf8()
            val payload = magic + wrap(plaintext)
            file.writeBytes(payload)
            true
        } catch (t: Throwable) {
            LauncherLog.write("cloud", "token store: save failed (${t.javaClass.simpleName}).")
            false
        } finally {
            plaintext?.fill(0)
        }
    }

    /** Never throws, never reports. */
    fun clear() {
        try { if (file.exists()) file.delete() } catch (ignored: Throwable) {}
    }

    private fun startsWithMagic(bytes: ByteArray): Boolean {
        for (i in magic.indices) if (bytes[i] != magic[i]) return false
        return true
    }

    // ---------------------------------------------------------- AES-GCM wrapping

    private fun wrap(plaintext: ByteArray): ByteArray {
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(STATIC_KEY, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return iv + c.doFinal(plaintext)
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        require(blob.size > IV_LEN) { "blob too short" }
        val iv = blob.copyOfRange(0, IV_LEN)
        val body = blob.copyOfRange(IV_LEN, blob.size)
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(STATIC_KEY, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return c.doFinal(body)
    }

    companion object {
        const val FILE_NAME = "cloud.token"

        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_LEN = 12
        private const val TAG_BITS = 128

        /**
         * Static, compiled-in, and therefore extractable by anyone who unpacks the APK. Deliberate -
         * see the class comment. Do not pretend otherwise in a UI string.
         */
        private val STATIC_KEY: ByteArray = byteArrayOf(
            0x50, 0x61, 0x63, 0x68, 0x65, 0x2d, 0x4c, 0x61,
            0x75, 0x6e, 0x63, 0x68, 0x65, 0x72, 0x2d, 0x63,
            0x6c, 0x6f, 0x75, 0x64, 0x2d, 0x74, 0x6f, 0x6b,
            0x65, 0x6e, 0x2d, 0x6f, 0x62, 0x66, 0x75, 0x73
        )

        /**
         * Built from `filesDir` and the installed
         * [com.teampacheworks.launcher.LauncherConfig.cloudTokenMagic].
         */
        fun default(filesDir: File): TokenStore =
            TokenStore(File(filesDir, FILE_NAME), LauncherHost.config.cloudTokenMagic.toByteArray(Charsets.US_ASCII))
    }
}
