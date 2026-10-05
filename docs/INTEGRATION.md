# INTEGRATION — adopting PacheLauncher in a new port

PacheLauncher is a pure Kotlin/Java Android library (`com.teampacheworks.launcher`) providing a
shared launcher UI, save bundling/import, GitHub Gist cloud backup, crash/debug logging, and a
handful of engine-agnostic input/UI helpers. It has no opinion about your game: the host app
provides a game Activity (any engine), declares it via `LauncherConfig`, and decides its own
process model, ABI set, and packaging entirely on its own. This library never links a native
library and never will — see README.md "Constraints" for why that is a hard rule, not an
oversight.

`sample/` in this repo is a minimal, runnable demonstration of everything below, wired against a
dummy game Activity. When in doubt about a step, read it there rather than guessing from prose.

## 1. Add the module

```kotlin
// settings.gradle.kts
include(":launcher")
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(project(":launcher"))
}
```

(If you're consuming this as a published artifact instead of a source module, swap the
`project(...)` line for a Maven coordinate — this repo does not currently publish one; see
README.md.)

`:touchpad` — the on-screen gamepad — is a **separate, optional** module with its own adoption
steps, because it links GPL-3.0 code that must not reach a host that has no use for it. It is the
real thing, not a stub: RadialGamePad as the invisible input layer, a Kenney Style C (CC0) sprite
skin on top, a launcher-side layout editor with per-control size and a **Visible** switch, and a
square D-pad hit area. Nothing in this document assumes it; see `TOUCH-GAMEPAD.md` if you want it.
A host needs `implementation project(':touchpad')`, a `TouchGamepadSink`, one
`TouchGamepadHost.install(...)` and one `TouchGamepadOverlay.attach(...)`.

## 2. Build a `LauncherConfig` and install it

Exactly one `LauncherConfig` exists per process lifetime. Build it and call
`LauncherHost.install(...)` as the very first line of your `Application.onCreate()` — every
library Activity reads `LauncherHost.config` and will crash with a clear message if nothing was
installed yet.

```kotlin
class MyGameApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LauncherLog.tag = "MyGame"
        CrashReporter.install(this, Application.getProcessName())

        LauncherHost.install(
            LauncherConfig(
                gameTitle = "My Game",
                gameSubtitle = "Android port · Team Pache Works",
                appLabel = "My Game",
                downloadsFolderName = "MyGame",
                cloudAppId = "mygame",
                savePatterns = listOf("save_*.dat", "profile.dat"),
                saveExcludeNames = setOf("settings.cfg"),
                gameActivityClass = MyGameActivity::class.java,
                buildGameIntentExtras = { intent, aspect, fps, debug ->
                    // add anything your game activity needs beyond the three standard extras
                },
                appVersionName = BuildConfig.VERSION_NAME,
                iconRes = R.mipmap.ic_launcher,
                gameProcessSuffix = ":game" // or null if the game runs in this same process
            )
        )

        // Launcher-process-only: never call this from the game process, since the game surface
        // is not part of the Dustaet UI standard this library ships.
        if (isLauncherProcess()) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }
    }
}
```

Every field is documented in `launcher/src/main/kotlin/com/teampacheworks/launcher/LauncherConfig.kt` —
read the KDoc there before guessing at a value; several fields (`cloudFenceTag`, `cloudTokenMagic`)
have stability requirements once real players have data riding on them.

## 2b. (Optional) extra Settings-card options

Anything your port needs as a "pick one of N" setting goes in `LauncherConfig.gameOptions` — the
library renders each one as a label + `Spinner` + hint row on the Video settings card (docs/UI-SPEC.md
"Host options"), persists the selection across launches, and hands the chosen `value` to your game
Activity. It is deliberately engine-neutral: the library never learns what the value means.

```kotlin
gameOptions = listOf(
    LauncherOption(
        key = "fps_limit",                       // stable: drives the pref key AND the extra name
        label = "FPS limit",
        hint = "Caps how fast the game runs. Applies the next time the game starts.",
        choices = (30..120 step 5).map { LauncherOptionChoice("$it", "$it FPS") } +
            LauncherOptionChoice("unlimited", "Unlimited"),
        defaultValue = "60"
    )
)
```

Read it in the game Activity with the contract helper (never by hand-writing the extra name):

```kotlin
val fpsLimit = intent.getStringExtra(LauncherContract.extraNameFor("fps_limit"))
```

`adb` can override one for a single launch without disturbing what the player picked:
`am start ... --ez autoplay true -e opt_fps_limit 30`.

A **boolean** knob is just a two-choice option — give it `Off`/`On` labels over stable values
(`"0"`/`"1"`, or whatever your engine reads) and let it render as a two-item `Spinner` like any
other row. There is intentionally no separate switch/toggle option kind: the Video settings card is a
uniform column of label→`Spinner`→hint rows (docs/UI-SPEC.md "Host options"), and a two-item
dropdown expresses a boolean without a second render path or a broken visual rhythm. An
"Async GPU submit" row over `Off`/`On` is exactly this pattern.

If the chosen `value` is already the string your engine consumes, forward it verbatim; only add a
transform when the displayed choice and the engine input genuinely differ (the FPS limiter turns
`"60"` into a microsecond budget, so it does; a present-mode option whose values are already
`fifo`/`mailbox`/`immediate` does not).

## 2c. (Optional) build the game data on the device

`deployImport` moves an install folder that a PC script already assembled. `dataBuild` **assembles
it here**, from a folder of the player's own PC game files, so the only PC step left is copying that
folder onto the device. Both exist for the same reason — a file another uid writes into the app's
external data dir is `0660/2770 ext_data_rw` and this app is not in that group — and both answer it
the same way: the app does the writing.

```kotlin
dataBuild = DataBuildConfig(
    gameId = "mygame",
    // Quoted in the on-screen instructions. It DESCRIBES the folder; it is not a name the
    // build requires. See below.
    sourceFolderName = "the folder with MyGame.app in it",
    destinationDirectory = { it.getExternalFilesDir(null) },
    builder = MyGameDataBuilder(),
    requiresNetwork = true
)
```

The library supplies the picker, the persisted permission, the worker thread, the partial wake lock,
two progress scales, cancellation, and a sentinel that keeps a half-built tree away from your launch
gate. It supplies **no** knowledge of game content. That is `DataBuilder`, which you implement:

- `inspect(context, source, cancelled)` reads the picked folder and returns an `Inspection`: a
  headline, some detail lines, and the `BuildVariant`s that can be built from it. A variant that
  cannot be built is returned `enabled = false` with a `disabledNote` saying why — a greyed row with
  no reason is a bug report.
- `build(context, request, cancelled, progress)` does the work and returns the lines shown when it
  finishes. `request.consumeSource` is the player's answer to keep-or-consume; honour it if your
  build has anything to gain from moving rather than copying, ignore it if not.

`SafTree` is the tool for both. It is a document-tree reader written for **tens of thousands of
files**: `DocumentFile.length()` is a separate provider query per file, so walking a real game's
content tree through it costs minutes before a byte moves. `SafTree.walk()` asks each directory once
for id, name, mime and size together, and `SafTree.install()` copies — or, with `consume`, renames —
one entry into a plain `File`.

**Identify the folder by what is in it, never by its name.** The picker gives no guarantee about the
name a folder ends up with after being copied to a phone, and refusing a correct folder over its
label is refusing the right answer. Probe for a marker the game genuinely needs, and accept the pick,
one level below it, and the obvious parent.

Your launch gate must refuse a half-built tree:

```kotlin
LauncherHost.config.dataBuild?.let {
    if (DataBuildStorage.buildInProgress(this, it)) return false
}
```

The button lands on the Manage Assets screen below "Import install folder": importing a folder
somebody already built is the shorter road whenever one exists.

## 3. Manifest

The library's own manifest already declares `LauncherActivity`, `SaveManagementActivity`,
`CloudBackupActivity` and `DataBuildActivity`, all `exported="false"`. Your host manifest supplies the launcher entry point
by overriding `LauncherActivity` through a merge:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <application android:label="@string/app_name" android:theme="@style/Theme.MyGame">
        <!-- The library declares this exported="false" with Theme.PacheLauncher; both are
             overridden here, so the manifest merger needs tools:replace to accept it. -->
        <activity
            android:name="com.teampacheworks.launcher.LauncherActivity"
            android:exported="true"
            android:theme="@style/Theme.MyGame"
            tools:replace="android:exported,android:theme">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity
            android:name=".MyGameActivity"
            android:exported="false"
            android:process=":game" />
    </application>
</manifest>
```

`android:theme="@style/Theme.MyGame"` should extend `Theme.PacheLauncher` and override
`colorPrimary`/`colorPrimaryDark` with colours derived from your own key art — see
docs/UI-SPEC.md "Per-game seed colour" and `sample/`'s theme override for the exact pattern.

## 4. Exclude cloud state from Auto Backup

The token file and cloud bookkeeping must never ride along in an Android Auto Backup transfer —
add both to your own `backup_rules.xml` / `data_extraction_rules.xml`:

```xml
<exclude domain="file" path="cloud.token" />
<exclude domain="sharedpref" path="${cloudPrefsName}.xml" />
```

Substitute your actual `LauncherConfig.cloudPrefsName` value for `${cloudPrefsName}`.

## 5. Wire the game Activity

Everything the launcher decided is handed to your game Activity as three Intent extras (never
SharedPreferences — two processes cannot rely on sharing those):

```kotlin
class MyGameActivity : Activity() {
    private lateinit var doubleBack: DoubleBackToExitHandler
    private var remap: ControllerRemap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val aspect = intent.getStringExtra(LauncherContract.EXTRA_ASPECT)
        val showFps = intent.getBooleanExtra(LauncherContract.EXTRA_SHOW_FPS, false)
        val debug = intent.getBooleanExtra(LauncherContract.EXTRA_DEBUG_LOG, false)
        LauncherLog.enabled = debug

        // Letterbox your surface to the requested aspect (optional helper; your engine may
        // already do this itself — see docs/UI-SPEC.md "FPS overlay" for why AspectFit and
        // FpsOverlayLayout share their math when you use both).
        AspectFit.apply(gameSurfaceView, containerW, containerH, AspectFit.ratioOf(aspect))

        if (debug) LogcatPump.start(this, "game")

        doubleBack = DoubleBackToExitHandler(
            onFirstPress = { Toast.makeText(this, R.string.pl_back_again_to_exit, Toast.LENGTH_SHORT).show() },
            onConfirmedExit = { finish() }
        )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Swallow synthesized fallback events first (see GamepadKeyPolicy's KDoc for why this
        // must run before any real-BACK handling).
        if (GamepadKeyPolicy.isFallback(event.flags)) return true

        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            doubleBack.onBackPressed()
            return true
        }

        val mapped = remap?.remapKey(event) ?: event
        val consumed = super.dispatchKeyEvent(mapped)
        return consumed || GamepadKeyPolicy.isGamepadSource(event.source)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val mapped = remap?.remapMotion(event) ?: event
        return super.onGenericMotionEvent(mapped)
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        val device = InputDevice.getDevice(deviceId) ?: return
        remap = ControllerRemap.forDevice(this, device, LauncherLog.enabled) ?: remap
    }
}
```

`assets/gamecontrollerdb.txt` is bundled by the library, so `ControllerRemap.forDevice(...)` and
`GameControllerDb.load(...)` work with no per-host asset. A device with no matching database entry
passes its input through untouched — most first-party pads already speak the standard Android
gamepad keycodes and need no remap at all.

## 6. Save files

Save wherever your engine already writes files inside `filesDir`; the launcher never dictates a
save format. `LauncherConfig.savePatterns` (glob, `*`/`?`, case-insensitive) tells `SaveBundle`
which files in `filesDir` are part of a bundle, and `saveExcludeNames` lists exact file names
(typically machine-local settings) that are excluded even when they match a pattern. If your save
format has a cheap structural sanity check worth running per file (a magic byte, a fixed header),
set `SaveBundle.headerCheck` — otherwise the default accepts anything non-empty.

Nothing else is required: `SaveManagementActivity` lists, exports, and imports using
`LauncherConfig` alone.

### 6a. `recursiveSaves` — when save data isn't directly in `filesDir`

Some ports don't write saves flat into `filesDir` — they nest them under profile/slot/backup
folders (e.g. `PROFILES/<guid>/StorySlot0/variable-storage.json`). By default `SaveBundle` only
scans `filesDir` itself (`recursiveSaves = false`, the historical behavior — **zero change** for
existing games), so a game whose saves live even one level deeper would bundle nothing.

Set `recursiveSaves = true` on `LauncherConfig` to opt in:

```kotlin
LauncherConfig(
    // ...
    savePatterns = listOf("*.json", "format-version.txt"),
    saveExcludeNames = setOf("device_config.txt"),
    recursiveSaves = true,
)
```

With it on:

- `SaveBundle` walks the whole `filesDir` tree instead of one level.
- `savePatterns`/`saveExcludeNames` are matched against **both** each file's bare name and its
  path relative to `filesDir` (forward-slash normalized) — a pattern like `"*.json"` still matches
  `PROFILES/<guid>/profile-metadata.json` by basename, and an exclude name can be either a bare
  name or a full relative path.
- Zip entries are stored under their relative path (`PROFILES/<guid>/StorySlot0/variable-storage.json`)
  instead of being flattened to a bare name — this matters as soon as two files share a name at
  different depths (e.g. `variable-storage.json` under `StorySlot0` and `StorySlot1`), which the
  old basename-only scheme could not represent without collisions.
- Entry ordering, the pinned 1980-01-01 zip timestamps, and the SHA-256 content-identity guarantee
  are unchanged — only the *key* each entry is stored/sorted under changes shape.
- On import, entries are restored to their relative path under `filesDir`, creating parent
  directories as needed; the existing temp → validate → pre-import-backup → replace → rollback
  commit flow (`SaveImporter`) is unchanged, and the pre-import backup itself is taken with the
  same recursive walk so nested trees are fully recoverable too.
- Zip-slip protection: any entry name that is absolute, escapes the root via a `..` segment, or has
  a dotfile/dir component is dropped on read rather than trusted — a hostile or corrupt bundle can
  never write outside `filesDir`.
- Always skipped regardless of `savePatterns`: `cloud.token`, the launcher/cloud prefs xml file
  names, any dotfile/dir (a path component starting with `.`), and symlinks.

Turning `recursiveSaves` on changes save discovery only for that game — it has no effect on any
other `LauncherConfig` instance, and leaving it at its `false` default keeps a flat-save game's
bundle bytes (and therefore its SHA-256 dedup identity) bit-for-bit identical to before this
option existed.

## 7. Cloud backup

No extra wiring needed beyond `LauncherConfig.cloudAppId`/`cloudProductName`/`cloudFenceTag` — see
docs/CLOUD-SPEC.md for what each of those controls and which must stay stable once players have
real backups. `CloudBackupActivity` is fully self-contained.

### 7a. Opting out: `cloudBackupEnabled = false`

A port that must not go online sets `LauncherConfig(cloudBackupEnabled = false)`: the Cloud sync
button disappears from the Manage game data card and the notice under it no longer mentions the
cloud. The cloud code stays in the library; drop the permission the library manifest declares from
your merged manifest as well:

```xml
<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
```

## 7b. Direct launch (`directLaunch = true`, opt-in)

Default `false` changes nothing. With `true` (requires `gameProcessSuffix`):

- **Launch confirmation.** Your GAME creates `LauncherContract.launchConfirmedFile(context)`
  (`<filesDir>/launcher/launch_ok`) once it has really started (e.g. after its first presented
  frames). It is a file because it is written from the game process.
- **Cold start.** A fresh `LauncherActivity` (no saved state, no `autoplay` extra) whose player has
  not switched on Debug > "Always start with launcher", with the marker present and no unreported
  unclean game exit, starts the game straight away with the persisted settings. The launcher UI is
  not inflated until the launcher is actually shown; its window stays black meanwhile.
- **Return.** The game is started for a result. Finish the game activity with `RESULT_OK` when the
  player asks for the launcher (e.g. double-Back). Otherwise the launcher reads the game process's
  `ApplicationExitInfo` (polling up to ~3 s, the record is written asynchronously):
  `CrashReporter.isUncleanGameExit` false (`EXIT_SELF` status 0, `USER_REQUESTED`, package
  management reasons) closes the task with `finishAndRemoveTask()`; true (crash, native crash, ANR,
  non-zero `exit()`, and a signal/low-memory/other kill while foreground or visible) shows the
  launcher with the crash notice and deletes the marker, so the next cold start shows the launcher
  until the game confirms a launch again. No record at all shows the launcher.
- With "Always start with launcher" on, the launcher behaves as it does without `directLaunch`
  (shown on cold start and after every game exit).

## 8. What stays per-game (does not belong in this library)

- The game Activity itself, its engine, its rendering surface, its ABI/process/packaging choices.
- The save file *format* (only the bundling/glob-matching around it is generic).
- The app icon, key art, and the theme's seed colour derived from it.
- Any engine-specific input handling beyond the generic key/motion remap this library provides.
- `android:label`, `android:icon`, and the launch `intent-filter` — these are static manifest
  attributes the host app owns; the library's manifest only ever supplies `exported="false"`
  defaults that a host overrides via manifest merging.
- **Every naming, attribution and legal line.** The publisher a port is *not* affiliated with, the
  rights notice, the redistribution terms: these name a specific company and a specific game, so
  they are host copy fed in through `LauncherConfig.footerText` (and `gameTitle` / `gameSubtitle` /
  `appLabel` for the rest of the identity). The library ships only the neutral default
  `"Personal build, not for distribution"`, and **no string in `launcher/`'s `strings.xml` may ever
  name a game, a studio, or a package id** — see UI-SPEC.md "Strings and capitalization policy".
  A host that needs a disclaimer writes its own, e.g.

  ```xml
  <!-- in the HOST app's strings.xml, not this library's -->
  <string name="my_footer">Unofficial port. Personal use only. Not affiliated with %1$s.</string>
  ```

  ```kotlin
  footerText = getString(R.string.my_footer, getString(R.string.my_publisher)),
  ```

## 9. Sanity-check before shipping

- `gradlew :launcher:test` — framework-scoped unit tests (save bundling, cloud payload/manifest,
  logging, input classification) run on the JVM with no device.
- `gradlew :sample:assembleDebug` — confirms the library actually assembles into a consuming app.
- Grep your own manifest and `backup_rules.xml` for the exclusions in step 4 — a token that rides
  along in an Auto Backup transfer is the one mistake this library cannot catch for you at
  compile time.
