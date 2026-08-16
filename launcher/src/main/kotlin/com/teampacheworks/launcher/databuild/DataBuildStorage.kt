package com.teampacheworks.launcher.databuild

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The bookkeeping around a build: the sentinel that makes a half-built tree un-launchable, and the
 * receipt that records what the last successful one produced.
 *
 * Both files are the framework's, not the builder's, which is what lets a launch gate ask one
 * question ("is a build in progress?") without knowing anything about the game.
 */
object DataBuildStorage {

    /** True while a partly-built tree is on disk. Launch gates must refuse this. */
    fun buildInProgress(context: Context, config: DataBuildConfig): Boolean {
        val root = config.destinationDirectory(context) ?: return false
        return File(root, config.sentinelName).exists()
    }

    fun begin(context: Context, config: DataBuildConfig, variantId: String) {
        val root = config.destinationDirectory(context) ?: error("storage unavailable")
        if (!root.exists() && !root.mkdirs()) error("could not create ${root.absolutePath}")
        File(root, config.sentinelName).writeText("building $variantId")
    }

    fun finish(context: Context, config: DataBuildConfig, variantId: String, summary: List<String>) {
        val root = config.destinationDirectory(context) ?: return
        File(root, config.receiptName).writeText(
            JSONObject().apply {
                put("schemaVersion", 1)
                put("gameId", config.gameId)
                put("variant", variantId)
                put("builtUtc", stamp())
                put("summary", JSONArray(summary))
            }.toString()
        )
        File(root, config.sentinelName).delete()
    }

    private fun stamp(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())
}
