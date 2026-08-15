package com.teampacheworks.launcher.mods

import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.content.res.Resources
import org.json.JSONObject
import java.io.File

/**
 * A mod's own description of itself, read from the staged plugin tree rather than hand-written in
 * the host's config.
 *
 * Mod packages carry name/author/version/description/icon already, and a hand-written line in
 * Kotlin is a second copy that goes stale the moment a pin moves. So the staging script normalises
 * whatever the package ships - Thunderstore's `manifest.json`, or the `thunderstore.toml` its
 * release workflow builds one from - into a single generated file beside the plugin's code:
 *
 * ```
 * <mod files>/<guid>/pache-modinfo.json   { guid, name, version, author, description, icon }
 * <mod files>/<guid>/icon.png
 * ```
 *
 * Reading it at render time (rather than baking it into the APK) is what keeps the screen correct
 * when a mod is re-staged at a newer pin without rebuilding the launcher.
 *
 * **Everything here is best-effort.** The files live on external storage, a mod may ship no
 * metadata at all, and a partly-staged tree is a normal intermediate state - so every field is
 * nullable and every failure degrades to the host's own [ModEntry] values instead of throwing. A
 * missing icon is a missing icon, not a broken screen.
 */
data class ModMetadata(
    val name: String? = null,
    val version: String? = null,
    val author: String? = null,
    val description: String? = null,
    val icon: Drawable? = null
) {
    companion object {
        /** Never throws: an unreadable or malformed tree yields an all-null instance. */
        fun read(dir: File?, res: Resources): ModMetadata {
            if (dir == null || !dir.isDirectory) return ModMetadata()
            return try {
                val json = File(dir, "pache-modinfo.json")
                    .takeIf { it.isFile && it.length() < 64 * 1024 }
                    ?.readText()
                    // The generator runs under Windows PowerShell, whose UTF-8 writer emits a BOM.
                    // JSONObject treats a leading U+FEFF as a syntax error, which would turn every
                    // row back into its fallback for a file that is otherwise perfectly good.
                    ?.removePrefix("﻿")
                    ?.let { JSONObject(it) }
                    ?: return ModMetadata(icon = icon(dir, res))
                ModMetadata(
                    name = json.str("name"),
                    version = json.str("version"),
                    author = json.str("author"),
                    description = json.str("description"),
                    icon = icon(dir, json.str("icon") ?: "icon.png", res)
                )
            } catch (_: Throwable) {
                ModMetadata()
            }
        }

        private fun JSONObject.str(key: String): String? =
            optString(key, "").trim().takeIf { it.isNotEmpty() && it != "null" }

        private fun icon(dir: File, res: Resources): Drawable? = icon(dir, "icon.png", res)

        /**
         * Decoded with a size cap and a sample factor: these are Thunderstore icons (256x256 by
         * convention) but nothing enforces that, and a mod that ships a 4K PNG must not be able to
         * OOM the launcher on a screen that shows it at 40dp.
         */
        private fun icon(dir: File, name: String, res: Resources): Drawable? = try {
            val f = File(dir, name)
            if (!f.isFile || f.length() > 4L * 1024 * 1024) null else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, bounds)
                val target = (48 * res.displayMetrics.density).toInt().coerceAtLeast(1)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= target) sample *= 2
                BitmapFactory.decodeFile(
                    f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }
                )?.let { BitmapDrawable(res, it) }
            }
        } catch (_: Throwable) {
            null
        }
    }
}
