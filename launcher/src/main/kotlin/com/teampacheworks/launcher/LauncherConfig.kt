package com.teampacheworks.launcher

import android.app.Activity
import android.content.Intent

/**
 * One aspect-ratio choice offered on the Settings card (ui-sync design spec, §Main screen).
 *
 * [value] is the value persisted to SharedPreferences and handed to the game activity as the
 * [LauncherContract.EXTRA_ASPECT] extra - keep it stable across app versions. [label] is the
 * user-facing string shown in the aspect dropdown.
 */
data class AspectOption(val value: String, val label: String)

/**
 * Everything a host app supplies to genericize the shared launcher UI/cloud/save framework for
 * its own game (see docs/INTEGRATION.md for the full adoption walkthrough).
 *
 * Nothing in this library reads a per-game string resource directly - every piece of game-specific
 * text and behaviour flows through one [LauncherConfig] instance, built once (typically in the
 * host's `Application.onCreate`) and read by [LauncherActivity], [SaveManagementActivity] and
 * [CloudBackupActivity] via [LauncherHost].
 *
 * @param gameTitle Big 26sp header title on the main screen (e.g. "My Game").
 * @param gameSubtitle 13sp header subtitle (e.g. "Android port · Team Pache Works").
 * @param appLabel Human-readable product name used in prose (crash report headers, cloud gist
 *   description/README, log ring header) - does NOT set `android:label`; that stays a static
 *   manifest attribute the host app owns (see docs/INTEGRATION.md).
 * @param footerText Small footer line under the Save & Cloud card (e.g.
 *   "Team Pache Works · personal build, not for distribution").
 * @param downloadsFolderName Sub-folder of the public Downloads directory logs/crash reports are
 *   exported to (`Downloads/<downloadsFolderName>`), e.g. "MyGame".
 * @param cloudAppId The gist manifest's `"app"` discriminator (design spec §3 invariant 6) - must
 *   be a short, stable, lowercase-by-convention id unique to this game, e.g. "mygame". Gist
 *   discovery treats any gist whose `01-manifest.json` carries this id as belonging to this game.
 * @param cloudProductName Human name used inside the cloud payload header / gist README / gist
 *   description text, e.g. "My Game". Usually equal to [gameTitle].
 * @param cloudFenceTag The word wrapped by the base64 fence markers, e.g. "MYGAME" produces
 *   `-----BEGIN MYGAME SAVE-----` / `-----END MYGAME SAVE-----` (design spec §3). Must be
 *   stable once players have cloud backups - changing it orphans old fenced payloads (they will
 *   simply fail decode, not corrupt anything).
 * @param cloudTokenMagic The [TokenStore] file's on-disk scheme header. Configurable so two ports
 *   sharing a device do not need to agree on a token format, but the default is a perfectly
 *   reasonable choice for a new port; only override this if you specifically need to.
 * @param savePatterns Glob patterns (e.g. `"*.fasta"`, `"save_??.dat"`) identifying which files in
 *   `filesDir` are part of a save bundle. `?` matches one character, `*` matches any run of
 *   characters; matching is case-insensitive. See [com.teampacheworks.launcher.save.SaveBundle].
 * @param saveExcludeNames Exact file names (case-insensitive) that are never bundled even if they
 *   match [savePatterns] - typically machine-local settings files that live alongside saves.
 * @param exportFilenamePrefix Prefix for the SAF export file name; the suggested name is
 *   `"<exportFilenamePrefix><yyyyMMdd-HHmmss>.zip"`, e.g. "mygame-save-".
 * @param aspectOptions The Settings card's aspect-ratio dropdown, in display order. The game
 *   activity is responsible for actually letterboxing to the chosen [AspectOption.value].
 * @param defaultAspectValue Must equal one of [aspectOptions]'s values.
 * @param gameActivityClass The host's game activity, started by the PLAY button.
 * @param buildGameIntentExtras Called after the three standard extras
 *   ([LauncherContract.EXTRA_ASPECT]/[LauncherContract.EXTRA_SHOW_FPS]/
 *   [LauncherContract.EXTRA_DEBUG_LOG]) are already set on the intent, so the host can add
 *   whatever else its game activity needs.
 * @param appVersionName Shown in the cloud client's User-Agent header and nowhere else that is
 *   user-facing; pass `BuildConfig.VERSION_NAME` from the host app.
 * @param prefsName SharedPreferences file name for launcher settings (aspect/fps/debug). Two ports
 *   installed on the same device never collide because each host supplies its own.
 * @param cloudPrefsName SharedPreferences file name for cloud bookkeeping (gist id, login,
 *   revision watermark). Exclude this file's name from the host's own backup rules XML - see
 *   docs/INTEGRATION.md.
 * @param iconRes The host app's own launcher icon resource id (e.g. `R.mipmap.ic_launcher`),
 *   shown at 48x48dp in the main screen's header. The library does not assume a resource name
 *   exists in the host app, so this is passed explicitly rather than hardcoded.
 * @param gameProcessSuffix If the host runs its game in a separate process (e.g. `":game"`, as a
 *   game-engine wrapper commonly does to isolate a crash from the launcher), set this to that
 *   suffix and [LauncherActivity] will check for - and report - an abnormal exit of that process
 *   on every resume (design spec §4). Set to null when the game runs in the launcher's own
 *   process; the check is then skipped entirely.
 */
data class LauncherConfig(
    val gameTitle: String,
    val gameSubtitle: String,
    val appLabel: String,
    val footerText: String = "Personal build, not for distribution",
    val downloadsFolderName: String,
    val cloudAppId: String,
    val cloudProductName: String = gameTitle,
    val cloudFenceTag: String = cloudAppId.uppercase(),
    val cloudTokenMagic: String = "PACHE-CLOUD-TOKEN-1\n",
    val savePatterns: List<String>,
    val saveExcludeNames: Set<String> = emptySet(),
    val exportFilenamePrefix: String = "${cloudAppId}-save-",
    val aspectOptions: List<AspectOption> = LauncherContract.DEFAULT_ASPECT_OPTIONS,
    val defaultAspectValue: String = LauncherContract.ASPECT_16_9,
    val gameActivityClass: Class<out Activity>,
    val buildGameIntentExtras: (Intent, aspect: String, fps: Boolean, debug: Boolean) -> Unit = { _, _, _, _ -> },
    val appVersionName: String = "dev",
    val prefsName: String = "$cloudAppId-launcher",
    val cloudPrefsName: String = "$cloudAppId-cloud",
    val iconRes: Int,
    val gameProcessSuffix: String? = ":game"
)

/**
 * Process-wide holder for the single [LauncherConfig] a host app builds once. Every library
 * Activity reads [config] instead of taking a constructor argument, because Android instantiates
 * activities itself.
 *
 * Call [install] as early as possible - typically the first line of the host's
 * `Application.onCreate()`, before any library Activity can be launched.
 */
object LauncherHost {
    @Volatile
    private var current: LauncherConfig? = null

    fun install(config: LauncherConfig) {
        current = config
    }

    val config: LauncherConfig
        get() = checkNotNull(current) {
            "LauncherHost.install(LauncherConfig(...)) was never called. Call it from your " +
                "Application.onCreate() before any com.teampacheworks.launcher Activity starts. " +
                "See docs/INTEGRATION.md."
        }
}
