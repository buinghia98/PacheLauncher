# PacheLauncher

A reusable Android launcher framework: one shared launcher UI, save-file bundling/import, GitHub
Gist cloud backup, crash/debug logging, and a handful of engine-agnostic input/UI helpers — so
every Team Pache Works Android game port gets the same launcher, save management, and cloud sync
screens without re-implementing them per game. The library has no opinion about your game engine,
your ABI set, or your process model; see "Constraints" below.

## Modules

- **`launcher/`** — the Android library (`com.teampacheworks.launcher`). UI scaffold (main screen,
  Save Management, Cloud Backup), theme template, save bundling + import, the full GitHub Gists
  cloud stack, logging (ring buffer, crash reporter, log export, logcat pump), a generic gamepad
  fallback-key policy, an SDL-GUID-based controller remap layer with a bundled
  `gamecontrollerdb.txt`, a double-back-to-exit helper, and a generic aspect-fit/letterbox helper.
  Everything per-game flows through one `LauncherConfig` value object — see
  `launcher/src/main/kotlin/com/teampacheworks/launcher/LauncherConfig.kt`.
- **`sample/`** — a minimal app demonstrating the adoption steps end to end, against a dummy game
  Activity that stands in for a real engine.
- **`docs/`** — `UI-SPEC.md` (the Dustaet visual standard this library ships), `CLOUD-SPEC.md`
  (every Gist sync invariant), `INTEGRATION.md` (how a new port adopts the library, and what stays
  per-game).

## Adopting this in a new port

Read `docs/INTEGRATION.md` first — it walks through adding the module, building a `LauncherConfig`,
the manifest merge for `LauncherActivity`, Auto Backup exclusions for the cloud token, and wiring a
game Activity to the aspect/FPS/debug extras and the input helpers. `sample/` is the same steps as
a working app.

## Constraints

- **Pure Kotlin/Java. No native (`.so`) dependency may ever be added to `launcher/`.** ABI choices —
  which native libraries ship, which architectures are supported, 32-bit vs. 64-bit — belong
  entirely to the host app's game side. A library with a native dependency of its own would
  constrain every consumer's ABI set whether they wanted that or not, so this module is guarded to
  stay pure JVM: see the comment in `launcher/build.gradle.kts`.
- **`minSdk = 30`**, driven by `CrashReporter`'s use of
  `ActivityManager.getHistoricalProcessExitReasons` (an API 30 call). Lowering it requires guarding
  that one call site with a `Build.VERSION.SDK_INT` check.
- **Engine-neutral by design.** Nothing in `launcher/` or `docs/` references a specific game engine,
  runner, or donor asset. The input/UI helpers (`AspectFit`, `FpsOverlayLayout`,
  `DoubleBackToExitHandler`, `GamepadKeyPolicy`, `ControllerRemap`) are written against plain
  `Activity`/`View`/`Context` and standard Android input APIs, not against any one engine's surface
  view or GL classes.

## Material / pill button

This library targets **Material Components 1.13.0**, which does not default `MaterialButton` to a
pill shape. The PLAY button on the main screen therefore sets `app:cornerRadius="32dp"` explicitly
in `launcher/src/main/res/layout/activity_launcher.xml` to produce the pill look the Dustaet
standard calls for. A consumer on **Material 1.14+** (which does default to a pill shape) can bump
the dependency in `launcher/build.gradle.kts` and drop that explicit `cornerRadius` — check on
Maven Central whether 1.14 has shipped before doing so; at the time this pin was chosen it had not.
Bumping Material may also require a newer AGP; test the whole UI-SPEC.md layout again after either
change.

## Building and testing

```
gradlew :launcher:test          # framework-scoped unit tests, JVM only, no device needed
gradlew :sample:assembleDebug   # confirms the library assembles into a consuming app
```

## Provenance

Extracted from the Fallen Leaf Android port's launcher, generalized to be engine- and game-neutral.
No engine-specific detail from that port (or any other) belongs in `launcher/` or `docs/` — if
you're about to add one, it belongs in a consuming app instead.

## License / ownership

Internal Team Pache Works framework, shared across every Android port the team ships.
