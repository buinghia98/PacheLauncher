package com.teampacheworks.launcher

/**
 * Stable Intent extra names and default aspect values, shared between [LauncherActivity] and
 * whatever game Activity a host wires up in [LauncherConfig.gameActivityClass].
 *
 * The game Activity reads these three extras (via `intent.getStringExtra`/`getBooleanExtra`) and
 * decides for itself what to do with them - this library does not letterbox or overlay anything
 * inside the game's own window; see docs/INTEGRATION.md for the pattern
 * ([com.teampacheworks.launcher.ui.AspectFit] and [com.teampacheworks.launcher.ui.FpsOverlayLayout]
 * are provided as small, engine-agnostic helpers for that, not as a requirement).
 */
object LauncherContract {
    const val EXTRA_ASPECT = "com.teampacheworks.launcher.ASPECT"
    const val EXTRA_SHOW_FPS = "com.teampacheworks.launcher.SHOW_FPS"
    const val EXTRA_DEBUG_LOG = "com.teampacheworks.launcher.DEBUG_LOG"
    const val EXTRA_GPU_DRIVER = "com.teampacheworks.launcher.GPU_DRIVER"
    const val EXTRA_GPU_DRIVER_DIR = "com.teampacheworks.launcher.GPU_DRIVER_DIR"
    const val EXTRA_GPU_DRIVER_LIB = "com.teampacheworks.launcher.GPU_DRIVER_LIB"

    /**
     * The Manage Mods master switch, as the String `"0"`/`"1"` (not a boolean) so a host can
     * forward it verbatim to an engine env var without a transform. Present only when the host
     * supplies a [LauncherConfig.modManagement].
     *
     * Note what this extra is NOT: the mod *set*. That travels as a file
     * ([com.teampacheworks.launcher.mods.ModManagementConfig.enabledFile]), because an Intent extra
     * is a poor channel for a list the game reads from a different process and a developer wants to
     * `cat` over adb.
     */
    const val EXTRA_MODS_ENABLED = "com.teampacheworks.launcher.MODS_ENABLED"

    /**
     * Namespace for the extras carrying [LauncherConfig.gameOptions] selections; see
     * [extraNameFor]. Kept distinct from the three standard extras so a host can add, rename or
     * drop an option without ever colliding with them.
     */
    const val EXTRA_OPTION_PREFIX = "com.teampacheworks.launcher.OPTION."

    /**
     * The String extra name a [LauncherOption]'s selected value arrives under in the game
     * activity's Intent, e.g. `extraNameFor("fps_limit")`. The value is the chosen
     * [LauncherOptionChoice.value], never the label.
     */
    fun extraNameFor(optionKey: String): String = EXTRA_OPTION_PREFIX + optionKey

    const val ASPECT_16_9 = "16:9"
    const val ASPECT_4_3 = "4:3"
    const val ASPECT_FULL = "full"

    /** The three options the Dustaet design standard ships by default (ui-sync design spec §1). */
    val DEFAULT_ASPECT_OPTIONS: List<AspectOption> = listOf(
        AspectOption(ASPECT_16_9, "Force 16:9"),
        AspectOption(ASPECT_4_3, "Force 4:3"),
        AspectOption(ASPECT_FULL, "Full screen (no bars)")
    )
}
