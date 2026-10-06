package com.teampacheworks.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.teampacheworks.launcher.assets.AssetManagementConfig
import com.teampacheworks.launcher.gpu.GpuDriverConfig
import com.teampacheworks.launcher.mods.ModManagementConfig
import java.io.File

/**
 * One aspect-ratio choice offered on the Video settings card (ui-sync design spec, §Main screen).
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
 * @param gameSubtitle 13sp header subtitle (e.g. "Android port · Team Pache Works"). Static text,
 *   used whenever [gameSubtitleProvider] is absent or declines to answer.
 * @param gameSubtitleProvider Optional LIVE subtitle, re-evaluated on every main-screen resume.
 *   Return null or blank to fall back to the static [gameSubtitle] - which is what a host does when
 *   whatever it wanted to report is not knowable on this device yet, so the header never shows a
 *   half-filled template. Keep it cheap: it runs on the main thread inside `onResume`. The reason it
 *   exists is that the interesting facts about a launch (which game build is installed, whether the
 *   next start is modded) are changed from screens inside this same launcher, so a subtitle computed
 *   once at config-build time would be stale the moment the player came back from one of them.
 * @param mainScreenNotice Optional LIVE warning line shown between the header and PLAY in the error
 *   colour (e.g. "untested game version"), re-evaluated on every main-screen resume like
 *   [gameSubtitleProvider]. Null/blank = no line. Informational only: it never blocks PLAY.
 * @param appLabel Human-readable product name used in prose (crash report headers, cloud gist
 *   description/README, log ring header) - does NOT set `android:label`; that stays a static
 *   manifest attribute the host app owns (see docs/INTEGRATION.md).
 * @param footerText Small footer line under the last card (e.g.
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
 *   [saveDirectory] are part of a save bundle. `?` matches one character, `*` matches any run of
 *   characters; matching is case-insensitive. See [com.teampacheworks.launcher.save.SaveBundle].
 * @param saveDirectory Resolves the directory scanned by Save Management and Cloud Backup. The
 *   default preserves the original behaviour (`Context.filesDir`); ports whose guest writes to an
 *   external or nested compatibility path can point the launcher at that exact directory.
 * @param saveExcludeNames Exact file names (case-insensitive) that are never bundled even if they
 *   match [savePatterns] - typically machine-local settings files that live alongside saves. Checked
 *   against both the bare file name and, when [recursiveSaves] is on, the full path relative to
 *   `filesDir` (forward-slash normalized).
 * @param recursiveSaves When false (default - **zero behavior change** for existing games), save
 *   discovery is a flat, single-level scan of `filesDir`, exactly as it always was. When true,
 *   [com.teampacheworks.launcher.save.SaveBundle] walks the entire `filesDir` tree; [savePatterns]/
 *   [saveExcludeNames] are matched against both each file's bare name and its path relative to
 *   `filesDir`, and zip entries are stored under that relative path (instead of flattened to a bare
 *   name) so same-named files at different depths - e.g. a per-profile `variable-storage.json` -
 *   don't collide. Turn this on when a game's save data isn't sitting directly in `filesDir` (see
 *   docs/INTEGRATION.md "recursiveSaves").
 * @param exportFilenamePrefix Prefix for the SAF export file name; the suggested name is
 *   `"<exportFilenamePrefix><yyyyMMdd-HHmmss>.zip"`, e.g. "mygame-save-".
 * @param aspectOptions The Video settings card's aspect-ratio dropdown, in display order. The game
 *   activity is responsible for actually letterboxing to the chosen [AspectOption.value].
 * @param defaultAspectValue Must equal one of [aspectOptions]'s values.
 * @param gameOptions Extra host-declared enumerated settings ("pick one of N") rendered on the
 *   Video settings card directly under the aspect row, in list order, each as a label + Spinner + hint
 *   triple identical in geometry to the aspect row (docs/UI-SPEC.md "Host options"). Each
 *   selection is persisted under [LauncherOption.prefsKey] and handed to the game activity as the
 *   String extra [LauncherOption.extraName]. The library never interprets the values - an FPS
 *   limiter, a texture-quality level and a scaler mode are all the same thing to it. Empty by
 *   default, in which case the Video settings card is byte-for-byte what it always was. An entry carrying
 *   a [LauncherOption.screenKey] is drawn on the matching [optionScreens] sub-screen instead of on
 *   the card, and is otherwise treated identically - persisted and forwarded exactly the same.
 * @param optionScreens Sub-screens hosting groups of [gameOptions] (see [LauncherOptionScreen]).
 *   Each one that has at least one option pointing at it adds a single navigation button to the
 *   Controls card. Empty by default, in which case there is no Controls card at all.
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
 * @param cloudBackupEnabled When false the Cloud sync (GitHub Gist) button is removed from the
 *   Manage game data card and the save notice stops mentioning the cloud. The cloud code stays in
 *   the library (a host opting out should also drop `android.permission.INTERNET` from its merged
 *   manifest with `tools:node="remove"`, see docs/INTEGRATION.md). Default true = unchanged.
 * @param directLaunch Opt-in "game first" start-up (docs/INTEGRATION.md "Direct launch"). Once the
 *   game has confirmed a successful launch by creating [LauncherContract.launchConfirmedFile], a
 *   cold start of the app skips the launcher UI and starts [gameActivityClass] with the persisted
 *   settings; the launcher stays underneath it in the task and only becomes visible when the game
 *   ends unexpectedly or the game activity finishes with `RESULT_OK` (the player asked for the
 *   launcher, e.g. double-Back). A clean game exit closes the whole task. The Debug card gains an
 *   "Always start with launcher" switch that restores the classic behaviour. Requires
 *   [gameProcessSuffix] (exits are classified from that process's ApplicationExitInfo). Default
 *   false = the launcher behaves exactly as it always did.
 * @param reportExtras Extra `key: value` lines for the header of every crash / exit report (e.g.
 *   build flavor, installed game-data version). Called on the way into a report, in whichever
 *   process writes it; must be cheap and must not depend on UI state. A throw is ignored.
 * @param nativeImageLabels Anonymous executable regions to name in decoded native-crash
 *   tombstones: base address -> label. A pc inside the contiguous mappings that start at a base is
 *   printed as `label+0x<offset>` (e.g. `0x100000000L to "mach-o"` for a guest image a loader maps
 *   there). Empty by default.
 */
data class LauncherConfig(
    val gameTitle: String,
    val gameSubtitle: String,
    val gameSubtitleProvider: ((Context) -> String?)? = null,
    val mainScreenNotice: ((Context) -> String?)? = null,
    val appLabel: String,
    val footerText: String = "Personal build, not for distribution",
    val downloadsFolderName: String,
    val cloudAppId: String,
    val cloudProductName: String = gameTitle,
    val cloudFenceTag: String = cloudAppId.uppercase(),
    val cloudTokenMagic: String = "PACHE-CLOUD-TOKEN-1\n",
    val savePatterns: List<String>,
    val saveDirectory: (Context) -> File? = { it.filesDir },
    val saveExcludeNames: Set<String> = emptySet(),
    val recursiveSaves: Boolean = false,
    val exportFilenamePrefix: String = "${cloudAppId}-save-",
    /** Hide the legacy aspect-ratio row for games that size themselves from the native surface. */
    val showAspectRatio: Boolean = true,
    val aspectOptions: List<AspectOption> = LauncherContract.DEFAULT_ASPECT_OPTIONS,
    val defaultAspectValue: String = LauncherContract.ASPECT_16_9,
    val gameOptions: List<LauncherOption> = emptyList(),
    val optionScreens: List<LauncherOptionScreen> = emptyList(),
    val assetManagement: AssetManagementConfig? = null,
    /**
     * Whole-install import from a staged deploy folder, by moving rather than copying. Non-null
     * adds the "Import install folder" button to the Manage Assets screen and the screen behind it;
     * null leaves the launcher exactly as it was. Independent of [assetManagement] - a host may have
     * either, both, or neither - though in practice the deploy folder is a superset of the asset
     * bundle and this is the route that needs no terminal.
     */
    val deployImport: com.teampacheworks.launcher.deploy.DeployImportConfig? = null,
    /**
     * On-device build of the install tree from the player's own PC game files. Non-null adds the
     * "Build data from PC game files" button to the Manage Assets screen and the screen behind it;
     * null leaves the launcher exactly as it was.
     *
     * The sibling of [deployImport], not a replacement for it: that one moves a folder a PC script
     * already assembled, this one assembles it here. A host may offer either, both, or neither.
     */
    val dataBuild: com.teampacheworks.launcher.databuild.DataBuildConfig? = null,
    val gpuDriverManagement: GpuDriverConfig? = null,
    /**
     * Per-mod on/off management. Non-null adds the "Manage mods" button and screen, and makes the
     * launcher hand the game activity [LauncherContract.EXTRA_MODS_ENABLED]; null is byte-for-byte
     * what the launcher always was.
     */
    val modManagement: ModManagementConfig? = null,
    val gameActivityClass: Class<out Activity>,
    val buildGameIntentExtras: (Intent, aspect: String, fps: Boolean, debug: Boolean) -> Unit = { _, _, _, _ -> },
    val appVersionName: String = "dev",
    val prefsName: String = "$cloudAppId-launcher",
    val cloudPrefsName: String = "$cloudAppId-cloud",
    val iconRes: Int,
    val gameProcessSuffix: String? = ":game",
    val cloudBackupEnabled: Boolean = true,
    val directLaunch: Boolean = false,
    val reportExtras: ((Context) -> List<Pair<String, String>>)? = null,
    val nativeImageLabels: Map<Long, String> = emptyMap()
) {
    fun resolveSaveDirectory(context: Context): File = saveDirectory(context) ?: context.filesDir
}

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
