package com.teampacheworks.launcher.cloud

import com.teampacheworks.launcher.LauncherHost

/**
 * A GitHub fine-grained personal access token that is **never a bare string** (design spec §3).
 *
 * 🔴 The whole point of this type is that the only way out of it is an `Authorization` header.
 * If you find yourself wanting a `val value: String`, stop: that property is the bug this class
 * exists to make impossible. `toString()` is masked, equality is reference identity (comparing
 * tokens is not something this app needs, and an `==` that touched the value would be one more
 * place it could be reasoned about).
 */
class GitHubToken private constructor(private val raw: String) {

    /** The single exit point. */
    fun applyTo(builder: okhttp3.Request.Builder): okhttp3.Request.Builder =
        builder.header("Authorization", "Bearer $raw")

    /** Second, named, in-module exit: [TokenStore] has to be able to persist the bytes. */
    internal fun copyUtf8(): ByteArray = raw.toByteArray(Charsets.UTF_8)

    override fun toString(): String = MASK
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)

    sealed class Parsed {
        class Ok(val token: GitHubToken) : Parsed()
        data class Invalid(val error: String) : Parsed()
    }

    companion object {
        const val MASK = "github_pat_***"

        val FINE_GRAINED = Regex("^github_pat_[A-Za-z0-9_]+$")
        val CLASSIC = Regex("^gh[pousr]_[A-Za-z0-9]+$")

        private fun appLabel(): String = try { LauncherHost.config.appLabel } catch (t: Throwable) { "This app" }

        /**
         * Shape validation is **local**: a malformed paste costs zero network requests.
         * The classic-token branch is checked first so its dedicated message wins.
         */
        fun parse(rawInput: String?): Parsed {
            val trimmed = rawInput?.trim().orEmpty()
            if (trimmed.isEmpty()) {
                return Parsed.Invalid("Paste the token you copied from GitHub.")
            }
            if (CLASSIC.matches(trimmed)) {
                return Parsed.Invalid(
                    "That is a classic personal access token. ${appLabel()} needs a fine-grained " +
                        "token — they start with github_pat_. Create one at " +
                        "github.com/settings/personal-access-tokens/new"
                )
            }
            if (!FINE_GRAINED.matches(trimmed)) {
                return Parsed.Invalid(
                    "That does not look like a fine-grained personal access token. Fine-grained " +
                        "tokens start with github_pat_. Classic tokens (ghp_) are not needed here."
                )
            }
            return Parsed.Ok(GitHubToken(trimmed))
        }

        /** A stored blob that is no longer token-shaped is corrupt, not an error. Never throws. */
        internal fun fromStoredUtf8(utf8: ByteArray?): GitHubToken? {
            if (utf8 == null || utf8.isEmpty()) return null
            val text = String(utf8, Charsets.UTF_8).trim()
            return if (FINE_GRAINED.matches(text)) GitHubToken(text) else null
        }
    }
}
