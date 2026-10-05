package com.teampacheworks.launcher.touch

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import com.teampacheworks.launcher.LauncherContract
import com.teampacheworks.launcher.LauncherHost
import java.io.File

/**
 * Everything the on-screen gamepad's appearance is decided by, and the two places it comes from.
 *
 * THE SPLIT, AND WHY IT IS NOT ARBITRARY
 *
 * PacheLauncher's per-game option mechanism ([com.teampacheworks.launcher.LauncherOption]) is
 * deliberately one shape only: a labelled Spinner over a fixed list of choices. docs/UI-SPEC.md
 * forbids forking the card's layout, and the framework is shared with other ports, so no feature
 * gets to add a slider row to it. That is a real constraint, not a temporary one, and it draws the
 * line cleanly:
 *
 *   * The launcher's own screens own what a *dropdown* expresses well and what has to be known
 *     before the game starts -- the [Mode] (is the pad attached at boot at all), plus coarse
 *     opacity / size presets. These arrive as Intent extras on every launch and are authoritative
 *     for that launch.
 *   * The layout editor ([TouchGamepadEditorActivity], reached from a
 *     [com.teampacheworks.launcher.LauncherOptionScreen]'s action button) owns what a dropdown
 *     cannot express at all: where each CONTROL sits and how far its size is trimmed. Those are
 *     continuous and two-dimensional, so they are dragged, and they are applied on top of the
 *     launcher's values.
 *
 * So there is exactly one writer per field and no reconciliation problem. [Placement.scale] is the
 * one place they meet: the launcher picks the size bracket, the editor nudges within it, per
 * control.
 *
 * There is no handedness setting, and there should not be one. It would swap the two halves of the
 * pad left-for-right, which only makes sense while the halves are rigid clusters; with every
 * control independently placeable, a left-handed layout is ten drags in the editor and a saved
 * file, not a boolean that reflects half the pad and inherits none of the tuning.
 *
 * PROCESS NOTE -- WHY THE LAYOUT IS A FILE AND NOT SharedPreferences
 *
 * The editor runs in the LAUNCHER process while the overlay is built in the game process, and
 * Android's multi-process SharedPreferences mode has been deprecated-and-unreliable since API 23.
 * So the layout is a small text file ([layoutFile]), written whole and read whole: two processes,
 * one file, no mode flag anyone has to trust. It is also, deliberately, a thing a developer can
 * `cat` over adb.
 */
object TouchGamepadSettings {

    private const val TAG = "pl/touchpad"

    /**
     * The layout file both processes agree on, under [TouchGamepadConfig.layoutDirectory]. Null when
     * the host's directory resolver declines (no external storage mounted, typically), in which case
     * the pad still draws -- at its defaults -- and nothing is saved.
     */
    fun layoutFile(context: Context): File? {
        val config = TouchGamepadHost.config
        return config.layoutDirectory(context)?.let { File(it, config.layoutFileName) }
    }

    // -------------------------------------------------------------------------------------------
    // Launcher option keys and values.
    //
    // A host declares its three LauncherOption rows with these keys, and this file reads the extras
    // back with them. Both sides must spell them identically or the option silently reverts to its
    // default, which is why they are constants here rather than strings in two places.
    // -------------------------------------------------------------------------------------------

    const val OPTION_MODE = "touchpad_mode"
    const val OPTION_OPACITY = "touchpad_opacity"
    const val OPTION_SIZE = "touchpad_size"

    const val MODE_OFF = "off"
    const val MODE_ON = "on"
    const val MODE_AUTO = "auto"

    /**
     * The resolved On/Off decision, which is a THREE-state question collapsed to a boolean exactly
     * once, in [resolve].
     *
     * `auto` is worth having as the default because a port typically ships to two audiences at
     * once: handhelds with a built-in pad, where an overlay is pure obstruction, and tablets, where
     * it is the only way to play. Asking either group to flip a switch they cannot see the need for
     * is worse than reading the device ([hasPhysicalGamepad]).
     */
    enum class Mode { OFF, ON, AUTO }

    /**
     * Where one control sits, how big it is, and whether the GAME shows it at all.
     *
     * @param xFraction / @param yFraction The control's CENTRE, as a fraction of the screen's width
     *   and height. Fractions, not dp, and of the WHOLE screen, not of a reserved band: the same
     *   saved layout has to land in the same relative place on a 16:9 handheld and a 3:2 tablet.
     *   [TouchGamepadLayout] clamps them so the control's press target stays on screen and does
     *   nothing else.
     * @param scale Editor-side multiplier ON TOP of the launcher's size bracket, per control. Kept
     *   separate rather than folded into one number so that changing the launcher's size row still
     *   moves a pad the player has hand-tuned, instead of being silently overridden by it.
     * @param visible The editor's "Visible" switch. False: the game does not create this control
     *   at all (no view, no touch target, no events); the editor still shows it, de-emphasised
     *   ([TouchGamepadLayout.HIDDEN_EDITOR_ALPHA]), so it can be found and switched back on.
     */
    data class Placement(
        val xFraction: Float,
        val yFraction: Float,
        val scale: Float = 1.0f,
        val visible: Boolean = true
    )

    /** The control's default: its declared centre, scale 1, visible unless the host hides it. */
    fun defaultPlacement(control: TouchGamepadLayout.Control) = Placement(
        control.defaultXFraction, control.defaultYFraction, 1.0f,
        visible = control.key !in TouchGamepadHost.config.hiddenByDefault
    )

    fun defaultPlacements(): Map<String, Placement> =
        TouchGamepadLayout.controls.associate { it.key to defaultPlacement(it) }

    data class Settings(
        val enabled: Boolean,
        val mode: Mode,
        /** View alpha, 0.15..1.0. */
        val opacity: Float,
        /** Launcher size bracket, the base every control's own size fraction multiplies. */
        val baseSizeDp: Float,
        /** One entry per [TouchGamepadConfig.controls] key. */
        val placements: Map<String, Placement>
    )

    /**
     * Resolve the launcher's choices for this launch, merged with the editor's saved geometry.
     *
     * Every extra is optional on purpose, and an absent one falls back to the host's declared
     * default. That is what keeps a bare `am start` at the game activity -- the standing bring-up
     * workflow on any port of this shape -- behaving like a normal launch instead of silently
     * losing the overlay.
     */
    @JvmStatic
    fun resolve(context: Context, intent: Intent?): Settings {
        val config = TouchGamepadHost.config
        val mode = modeOf(intent?.getStringExtra(LauncherContract.extraNameFor(OPTION_MODE)))
        val opacity = opacityOf(
            intent?.getStringExtra(LauncherContract.extraNameFor(OPTION_OPACITY)),
            config.defaultOpacity
        )
        val sizeDp = sizeOf(
            intent?.getStringExtra(LauncherContract.extraNameFor(OPTION_SIZE)), config
        )

        val enabled = when (mode) {
            Mode.OFF -> false
            Mode.ON -> true
            Mode.AUTO -> !hasPhysicalGamepad(context)
        }

        val s = Settings(
            enabled = enabled,
            mode = mode,
            opacity = opacity,
            baseSizeDp = sizeDp,
            placements = readLayout(context)
        )
        Log.i(
            TAG, "settings: mode=$mode -> enabled=$enabled opacity=$opacity " +
                "sizeDp=$sizeDp placements=${s.placements}"
        )
        return s
    }

    /**
     * The same resolution as [resolve], but reading the launcher's OWN SharedPreferences instead of
     * a launch Intent -- for [TouchGamepadEditorActivity], which runs in the launcher process where
     * those prefs are local and authoritative, and which is started with a bare Intent.
     *
     * [Settings.enabled] is forced true: the editor draws the pad whether or not this launch would
     * have shown it, because "Off" is a decision about the game and not about the editor.
     */
    fun resolveFromLauncherPrefs(context: Context): Settings {
        val config = TouchGamepadHost.config
        val prefs = context.getSharedPreferences(
            LauncherHost.config.prefsName, Context.MODE_PRIVATE
        )
        // "opt_" + key is LauncherOption.prefsKey; spelled out rather than reached through the host's
        // option list so the editor cannot be broken by a reordering of those rows.
        fun opt(key: String): String? = prefs.getString("opt_$key", null)

        return Settings(
            enabled = true,
            mode = modeOf(opt(OPTION_MODE)),
            opacity = opacityOf(opt(OPTION_OPACITY), config.defaultOpacity),
            baseSizeDp = sizeOf(opt(OPTION_SIZE), config),
            placements = readLayout(context)
        )
    }

    private fun modeOf(value: String?): Mode = when (value) {
        MODE_OFF -> Mode.OFF
        MODE_ON -> Mode.ON
        else -> Mode.AUTO
    }

    /** The option carries dp; clamped into [TouchGamepadConfig.sizeRangeDp] when the host set one. */
    private fun sizeOf(value: String?, config: TouchGamepadConfig): Float {
        val dp = value?.toFloatOrNull() ?: config.defaultSizeDp
        val range = config.sizeRangeDp ?: return dp
        return dp.coerceIn(range.start, range.endInclusive)
    }

    /** The option carries whole percent, because a Spinner's labels read better that way. */
    private fun opacityOf(value: String?, fallback: Float): Float =
        value?.toFloatOrNull()?.let { it / 100f }?.coerceIn(0.15f, 1f) ?: fallback

    /**
     * The saved placement per control, defaults filling in for anything the file does not name.
     *
     * Reading key by key rather than requiring a complete file is what makes adding a control to a
     * host's list a non-event: the new one takes its default and the nine already tuned are
     * untouched. It is also why a file written by an older, differently-shaped editor simply reads
     * as "no placements saved" instead of scattering a player's pad -- a wrong translation of stale
     * geometry is much harder to undo than a reset.
     */
    fun readLayout(context: Context): Map<String, Placement> {
        val values = HashMap<String, Float>()
        val file = layoutFile(context)
        if (file != null && file.isFile) {
            runCatching {
                for (line in file.readLines()) {
                    val t = line.trim()
                    if (t.isEmpty() || t.startsWith("#")) continue
                    val eq = t.indexOf('=')
                    if (eq <= 0) continue
                    t.substring(eq + 1).trim().toFloatOrNull()
                        ?.let { values[t.substring(0, eq).trim()] = it }
                }
            }.onFailure { Log.w(TAG, "layout file unreadable (${it.message}) -- using defaults") }
        }

        val scales = TouchGamepadHost.config.scaleRange
        return TouchGamepadLayout.controls.associate { control ->
            val d = defaultPlacement(control)
            control.key to Placement(
                xFraction = (values["${control.key}_x"] ?: d.xFraction).coerceIn(0f, 1f),
                yFraction = (values["${control.key}_y"] ?: d.yFraction).coerceIn(0f, 1f),
                scale = (values["${control.key}_scale"] ?: d.scale)
                    .coerceIn(scales.start, scales.endInclusive),
                // 0 = hidden, anything else = shown; absent (a file from before the switch existed)
                // = the host's default for that control.
                visible = values["${control.key}_visible"]?.let { it != 0f } ?: d.visible
            )
        }
    }

    /**
     * Writes the layout both processes read. Written whole, and via a temp file + rename, because
     * the reader is a different process that may start at any moment -- a half-written line would
     * otherwise be a silently mangled control rather than a failed read.
     */
    fun save(context: Context, placements: Map<String, Placement>): Boolean {
        val file = layoutFile(context) ?: run {
            Log.w(TAG, "no layout directory -- layout not saved")
            return false
        }
        val text = buildString {
            append("# On-screen gamepad layout. Written by the launcher's editor, read by the\n")
            append("# game process at launch. x/y are the control's centre as a fraction of the\n")
            append("# screen; scale multiplies the launcher's size bracket; visible is 1 shown /\n")
            append("# 0 hidden in game. A missing key takes the control's default.\n")
            for (control in TouchGamepadLayout.controls) {
                val p = placements[control.key] ?: defaultPlacement(control)
                append("${control.key}_x=${p.xFraction}\n")
                append("${control.key}_y=${p.yFraction}\n")
                append("${control.key}_scale=${p.scale}\n")
                append("${control.key}_visible=${if (p.visible) 1 else 0}\n")
            }
        }
        return runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
            Log.i(TAG, "layout saved to ${file.absolutePath}: $placements")
            true
        }.getOrElse {
            Log.w(TAG, "layout save failed: ${it.message}")
            false
        }
    }

    fun clearLayout(context: Context) {
        layoutFile(context)?.delete()
        // The legacy store too, or a reset would resurrect a layout from before the editor moved.
        TouchGamepadHost.config.legacyPrefsName?.let {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().apply()
        }
        Log.i(TAG, "layout reset to defaults")
    }

    /**
     * True when something that can actually play the game is already attached.
     *
     * The test is deliberately stricter than `SOURCE_GAMEPAD`: Android reports a great many things
     * as gamepad-sourced that cannot drive a character, and a false positive here is expensive
     * because it hides the ONLY input method a tablet has. So a device counts only when it is a
     * gamepad or joystick, is not virtual (`isVirtual` is what `adb shell input`'s injector and
     * several vendor overlays present as), and reports the two axes a left stick must have.
     *
     * FEATURE_GAMEPAD is checked first as a cheap negative, but it is NOT conclusive on its own --
     * some tablets omit the feature while a pad is plugged in -- so a negative falls through to the
     * device scan rather than returning early.
     */
    fun hasPhysicalGamepad(context: Context): Boolean {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_GAMEPAD)) {
            Log.i(TAG, "PackageManager reports no FEATURE_GAMEPAD")
        }
        for (id in InputDevice.getDeviceIds()) {
            val dev = InputDevice.getDevice(id) ?: continue
            if (dev.isVirtual) continue
            val sources = dev.sources
            val isPad = (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            if (!isPad) continue
            val hasStick =
                dev.getMotionRange(MotionEvent.AXIS_X, InputDevice.SOURCE_JOYSTICK) != null &&
                    dev.getMotionRange(MotionEvent.AXIS_Y, InputDevice.SOURCE_JOYSTICK) != null
            if (hasStick) {
                Log.i(
                    TAG, "physical gamepad present: '${dev.name}' (id=$id) -- " +
                        "Auto will leave the overlay off"
                )
                return true
            }
        }
        Log.i(TAG, "no physical gamepad found -- Auto will show the overlay")
        return false
    }
}
