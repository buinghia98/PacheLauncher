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
