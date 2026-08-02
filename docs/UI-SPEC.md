# UI-SPEC — the Dustaet visual standard

PacheLauncher ships one launcher UI. Every port that adopts the library gets the same layout,
typography, spacing and component vocabulary; the only things that vary per game are the theme's
seed colour and the text/behaviour supplied through `LauncherConfig`. Do not fork the layout XML
per game — if a port needs a structural change, the change belongs in this library so every other
port gets it too.

## Main screen

Structure (`launcher/src/main/res/layout/activity_launcher.xml`):

```
ScrollView -> FrameLayout -> LinearLayout root (vertical, width = 560dp FIXED, centered
                                                horizontal, padding 24dp on every side)
1. Header row (horizontal, centerVertical):
   ImageView 48x48dp (LauncherConfig.iconRes), marginEnd 14dp
   + column: gameTitle      26sp bold, colorPrimary
             gameSubtitle   13sp, textColorSecondary
2. PLAY: MaterialButton, filled (the only filled control on the screen), "PLAY", 20sp bold,
   MATCH_PARENT x 64dp fixed height, margins (0, 20dp, 0, 8dp).
3. LinearProgressIndicator (GONE by default) + status line (GONE) + error card (GONE) — kept in
   the layout for structural parity with hosts that add a first-run install step. A host that has
   nothing to install leaves these three untouched.
4. Card "Settings" (MaterialCardView, default corner radius, marginTop 12dp; inner LinearLayout
   padding 16/12/16/12dp):
   - Header "Settings", 16sp bold, colorPrimary, marginBottom 10dp
   - Aspect ratio label (14sp) + a plain framework `Spinner` (NOT an exposed dropdown —
     `android.R.layout.simple_spinner_item` / `simple_spinner_dropdown_item`), then an 11sp hint
     line
   - MaterialSwitch "Debug logging" + 11sp hint (Debug logging comes BEFORE Show FPS)
   - MaterialSwitch "Show FPS" + 11sp hint
   - MaterialButton OUTLINED "Export logs"
5. Card "Save & Cloud" (same geometry as the Settings card):
   - Header "Save & Cloud"
   - OUTLINED "Manage saves" (marginBottom 8dp)
   - OUTLINED "Cloud sync (GitHub Gist)" (marginBottom 8dp)
   - 11sp notice line
6. Footer: `LauncherConfig.footerText`, 11sp secondary, gravity center, margins (0, 20dp, 0, 4dp)

Every row inside the two cards that has an explanatory line under it uses **11sp** for that line —
this is the one typographic rule that is easy to get wrong when adding a new row.

PLAY is the one filled button on the screen; everything else that acts as a button is outlined.
There is no background art anywhere in the layout.

## Theme

- `Theme.Material3.DayNight.NoActionBar` as the base for `Theme.PacheLauncher`
  (`launcher/src/main/res/values/themes.xml`).
- `DynamicColors.applyToActivitiesIfAvailable(...)` is applied **only in the launcher process** —
  a host that runs its game in a separate process must not apply dynamic colours there, since the
  game surface is not part of this UI standard. See INTEGRATION.md for where this call goes.
- Every port overrides the theme's seed colour with one derived from its own key art (see
  `launcher/src/main/res/values/colors.xml` for the neutral fallback and where to point your own
  override). Nothing else in the theme is per-game.
- Typography: system default, explicit `sp` sizes (26/20/16/14/13/12/11), bold via `textStyle`.
  Do not introduce `TextAppearance` tokens into this layout — the whole point of the standard is
  that every port's launcher looks pixel-identical apart from colour and text.
- Hardcoded foreground colours are limited to two: `pl_error` (#C03030) and `pl_fps_overlay`
  (#00E63C). Nothing else should hardcode a colour outside the theme.
- Material Components version and the pill-button workaround: see README.md "Material / pill
  button" — this library targets Material 1.13, which does not default `MaterialButton` to a pill
  shape, so the PLAY button sets `app:cornerRadius="32dp"` explicitly. A consumer on Material 1.14+
  can drop that override.
- `LauncherActivity` (and every sub-screen) declares `android:screenOrientation="sensorLandscape"`
  and `android:configChanges="orientation|screenSize|screenLayout|keyboardHidden"` in the library's
  manifest. Do not add `windowLayoutInDisplayCutoutMode` to the theme.

## Strings and capitalization policy

- All strings ship in English (`launcher/src/main/res/values/strings.xml`) and are sentence case
  ("Manage saves", not "Manage Saves"). The only exceptions are "PLAY" and proper nouns.
- The double-back toast is fixed text: "Press Back again to exit to launcher" — see
  `com.teampacheworks.launcher.ui.DoubleBackToExitHandler`.
- Aspect ratio labels are fixed: "Force 16:9" / "Force 4:3" / "Full screen (no bars)". The
  *persisted* values behind those labels are `"16:9"` / `"4:3"` / `"full"` — see
  `LauncherContract.DEFAULT_ASPECT_OPTIONS`. A host may supply its own `aspectOptions` list instead
  (`LauncherConfig.aspectOptions`), but should keep the same label style if it does.
- The FPS overlay's placeholder text (before the first frame is measured) is `"-- FPS"`.
- Every string that mentions a product name, folder name, or count takes that value as a
  `%1$s`/`%1$d` argument filled in from `LauncherConfig` at runtime — no string in this library
  hardcodes a game's name. If you are adding a new string and find yourself tempted to hardcode a
  name into it, that is a sign the string belongs in the host app instead.
- Prose uses `--` in place of an em dash wherever the string lives in XML (matches the existing
  strings; keep new ones consistent).

## Per-game seed colour

Every port picks a single flat primary colour (and a darker variant) from its own key art and
overrides `Theme.PacheLauncher`'s color attributes with it — the sample app under `sample/` shows
the override pattern end to end. `launcher/src/main/res/values/colors.xml`'s `pl_default_primary` /
`pl_default_primary_dark` are a neutral fallback only; no host should ship with them unmodified.

## Sub-screen chrome

Both sub-screens (`SaveManagementActivity`, `CloudBackupActivity`) use a standalone
`MaterialToolbar` with a manual `setNavigationOnClickListener { finish() }`.
**`setSupportActionBar(toolbar)` is never called** — doing so swallows the toolbar's navigation
click in a way that is easy to miss in testing and only shows up as a broken back arrow on device.
This is a hard rule for this library, not a style preference; follow it in any new sub-screen.

## FPS overlay

`com.teampacheworks.launcher.ui.FpsOverlayLayout` offsets its counter onto the visible picture
area, never into a letterbox bar, using the same aspect math as
`com.teampacheworks.launcher.ui.AspectFit`. A host that adds its own FPS overlay directly (rather
than delegating aspect math to `AspectFit`) must reproduce that constraint.
