package com.teampacheworks.launcher.cloud

import android.content.Context
import android.content.SharedPreferences
import com.teampacheworks.launcher.LauncherHost

/**
 * The small amount of cloud bookkeeping that outlives a session (design spec §3).
 *
 * Lives in its own SharedPreferences file, named by
 * [com.teampacheworks.launcher.LauncherConfig.cloudPrefsName], so the host's own backup rules XML
 * can name it: none of this should ride along to another device in an Auto Backup. See
 * docs/INTEGRATION.md for the exact `<exclude>` entries a host app needs to add.
 *
 * 🔴 [lastWrittenRevision] is the staleness oracle. Timestamps are not: GitHub's edge can serve an
 * older copy of the manifest with a perfectly plausible `updatedAt`.
 */
class CloudState(private val prefs: SharedPreferences) {

    var gistId: String?
        get() = prefs.getString(K_GIST, null)?.takeIf { it.isNotBlank() }
        set(v) = prefs.edit().putString(K_GIST, v?.trim()).apply()

    var login: String
        get() = prefs.getString(K_LOGIN, "") ?: ""
        set(v) = prefs.edit().putString(K_LOGIN, v).apply()

    var lastWrittenRevision: Int
        get() = prefs.getInt(K_REVISION, 0)
        set(v) = prefs.edit().putInt(K_REVISION, v).apply()

    /** Raw header value from `GitHub-Authentication-Token-Expiration`; display only. */
    var tokenExpiration: String
        get() = prefs.getString(K_EXPIRY, "") ?: ""
        set(v) = prefs.edit().putString(K_EXPIRY, v).apply()

    val hasGist: Boolean get() = !gistId.isNullOrEmpty()

    fun forgetGist() {
        prefs.edit().remove(K_GIST).remove(K_REVISION).remove(K_LOGIN).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val K_GIST = "gist_id"
        private const val K_LOGIN = "login"
        private const val K_REVISION = "last_written_revision"
        private const val K_EXPIRY = "token_expiration"

        fun of(context: Context): CloudState =
            CloudState(context.getSharedPreferences(LauncherHost.config.cloudPrefsName, Context.MODE_PRIVATE))
    }
}
