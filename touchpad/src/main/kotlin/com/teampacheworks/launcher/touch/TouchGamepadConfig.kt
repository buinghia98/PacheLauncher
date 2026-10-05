package com.teampacheworks.launcher.touch

import android.content.Context
import com.swordfish.radialgamepad.library.config.RadialGamePadTheme
import java.io.File

/**
 * Where a press goes once the overlay has decided one happened.
 *
 * This is the whole of the module's coupling to a game, and it is one interface with two methods on
 * purpose. Everything above it -- the controls, the geometry, the editor, the saved layout -- is
 * about a finger on a screen and knows nothing about engines; everything below it is the host's
 * business and may be a JNI call into a native SDL virtual joystick, a key event, a socket, or a
 * test double.
 *
 * INDICES ARE SDL GAME-CONTROLLER INDICES, and deliberately not an enum of this module's own.
 *
 * [TouchGamepadLayout.SdlButton] / [TouchGamepadLayout.SdlAxis] spell out the standard order --
 * A,B,X,Y,BACK,GUIDE,START,LS,RS,LB,RB,DPAD_UP..RIGHT for buttons and LEFTX,LEFTY,RIGHTX,RIGHTY,
 * TRIGGERLEFT,TRIGGERRIGHT for axes -- which is what `SDL_JoystickAttachVirtual` synthesises a
 * mapping for when a joystick is declared as SDL_JOYSTICK_TYPE_GAMECONTROLLER with 6 axes, 15
 * buttons and no hats. A host on that path passes the index straight through. A host on any other
 * path translates once, here, where the translation is visible.
 *
 * SIGN CONVENTION. Up is NEGATIVE on both axes -- RadialGamePad emits screen-convention Y
 * (`CrossDial`'s UP is `PointF(0f, -1f)`, `StickDial` derives its point from `atan2(dy, dx)` over
 * raw touch deltas) and SDL_CONTROLLER_AXIS_LEFTY is negative-up as well, so nothing is flipped on
 * the way through and an implementation of this interface must not flip it either.
 *
 * THREADING. Both methods are called on the main thread, from the Flow collector
 * [TouchGamepadOverlay] runs on `Dispatchers.Main.immediate`. An implementation that does real work
 * must hand it off itself.
 */
interface TouchGamepadSink {

    /** @param index 0..14, see [TouchGamepadLayout.SdlButton]. */
    fun button(index: Int, pressed: Boolean)

    /**
     * @param index 0..5, see [TouchGamepadLayout.SdlAxis].
     * @param value -32768..32767 for a stick; 0..32767 for the two triggers, which SDL ranges
     *   differently and which this overlay drives digitally (full deflection or nothing).
     */
    fun axis(index: Int, value: Int)
}

/**
 * Everything the on-screen gamepad's appearance and persistence is decided by.
 *
 * Built once by the host and handed to [TouchGamepadHost.install], in the same shape and for the
 * same reason as [com.teampacheworks.launcher.LauncherConfig]: Android instantiates
 * [TouchGamepadEditorActivity] itself, so it cannot be given a constructor argument.
 *
 * INSTALL IT IN EVERY PROCESS THAT DRAWS THE PAD. On a host that runs its game in a `:game` process
 * -- the usual arrangement -- that is two: the launcher process draws the editor and the game
 * process draws the overlay. `Application.onCreate` runs in both, which is why the install belongs
 * there and not in an Activity.
 *
 * @param controls The pad, in the order they are added to the container -- which is also their
 *   z-order. Defaults to [TouchGamepadLayout.XBOX_CONTROLS], the ten-control Xbox arrangement
 *   for wide screens; [TouchGamepadLayout.STACKED_CONTROLS] is the same ten stacked in three bands
 *   for near-square panels. A host wanting a different set builds its own list from
 *   [TouchGamepadLayout.singleButton] / [TouchGamepadLayout.menuButton] /
 *   [TouchGamepadLayout.stick] / [TouchGamepadLayout.cross] / [TouchGamepadLayout.faceButtons] and
 *   must read [TouchGamepadLayout.XBOX_CONTROLS]' note on z-order before choosing an order.
 * @param theme The pad's PALETTE, painted onto the Kenney Style C sprites by [TouchGamepadSprite]
 *   in RadialGamePad's own colour roles (body `normalColor`, pressed `pressedColor`, stick well
 *   `backgroundColor`, outline + label `textColor`). RadialGamePad itself draws nothing.
 * @param hiddenByDefault Keys of the controls the GAME does not show until the player switches
 *   them on in the editor ("Visible"). Empty by default: every control is visible. A hidden control
 *   is not created in game at all -- no view, no touch target, no events. Only the default: a
 *   saved layout's `<key>_visible` always wins.
 * @param layoutFileName Name of the small text file the editor writes and the game process reads.
 *   Include the game's name: two ports installed on one device share external files storage only if
 *   the host points them at the same directory, but a distinctive name costs nothing and makes the
 *   file identifiable in a `ls` over adb.
 * @param layoutDirectory Where that file lives. The default is external files storage rather than
 *   `filesDir` because `filesDir` is per-process-app-private in exactly the way that makes a
 *   two-process launcher awkward, and because this is already where a host's other cross-process
 *   files tend to be. Returning null disables saving (the pad still draws, at its defaults).
 * @param defaultOpacity View alpha used when the launcher's opacity option is absent, 0.15..1.
 * @param defaultSizeDp The size bracket used when the launcher's size option is absent. Every
 *   control's own [TouchGamepadLayout.Control.sizeFraction] multiplies this.
 * @param sizeRangeDp When non-null, the launcher's size option is CLAMPED into it (not rejected:
 *   a value saved under an older, larger ceiling comes back as the new maximum instead of silently
 *   snapping to the default). Set it to the largest size at which your default layout still has
 *   no overlapping press targets on your narrowest target panel. Null: no clamp.
 * @param scaleRange The editor's per-control size multiplier range, and the clamp the layout
 *   reader applies to it. Keep the top at 1 when [sizeRangeDp] is a hard ceiling, or a single
 *   control can be walked back past it.
 * @param legacyPrefsName An older build's in-game editor prefs file, cleared by
 *   [TouchGamepadSettings.clearLayout] so a reset cannot resurrect a layout from before the editor
 *   moved. Null on a new adoption, which is the common case.
 */
data class TouchGamepadConfig(
    val controls: List<TouchGamepadLayout.Control> = TouchGamepadLayout.XBOX_CONTROLS,
    val theme: RadialGamePadTheme = TouchGamepadLayout.DEFAULT_THEME,
    val layoutFileName: String = "touchpad-layout.txt",
    val layoutDirectory: (Context) -> File? = { it.getExternalFilesDir(null) },
    val defaultOpacity: Float = 0.75f,
    val defaultSizeDp: Float = 170f,
    val sizeRangeDp: ClosedFloatingPointRange<Float>? = null,
    val scaleRange: ClosedFloatingPointRange<Float> = DEFAULT_SCALE_RANGE,
    val hiddenByDefault: Set<String> = emptySet(),
    val legacyPrefsName: String? = null
) {
    init {
        require(controls.isNotEmpty()) { "TouchGamepadConfig has no controls" }
        require(controls.map { it.key }.toSet().size == controls.size) {
            "TouchGamepadConfig has two controls with the same key: " +
                controls.map { it.key }.groupBy { it }.filterValues { it.size > 1 }.keys
        }
        require(layoutFileName.isNotBlank()) { "TouchGamepadConfig layoutFileName is blank" }
        val keys = controls.map { it.key }.toSet()
        require(keys.containsAll(hiddenByDefault)) {
            "TouchGamepadConfig hiddenByDefault names unknown controls: ${hiddenByDefault - keys}"
        }
        require(scaleRange.start > 0f && scaleRange.start <= scaleRange.endInclusive) {
            "TouchGamepadConfig scaleRange is empty or not positive: $scaleRange"
        }
    }

    companion object {
        /** Half to 1.6x of the launcher's size bracket, per control. */
        val DEFAULT_SCALE_RANGE: ClosedFloatingPointRange<Float> = 0.5f..1.6f
    }
}

/**
 * Process-wide holder for the single [TouchGamepadConfig], mirroring
 * [com.teampacheworks.launcher.LauncherHost] exactly.
 */
object TouchGamepadHost {

    @Volatile
    private var current: TouchGamepadConfig? = null

    fun install(config: TouchGamepadConfig) {
        current = config
    }

    /**
     * Null when the host has not installed one. [TouchGamepadOverlay.attach] treats that as "this
     * host does not have a touch pad" and does nothing, so a process that never calls [install] --
     * a `:game` process on a device that only ever sees a real controller, say -- is not a crash.
     */
    val configOrNull: TouchGamepadConfig? get() = current

    val config: TouchGamepadConfig
        get() = checkNotNull(current) {
            "TouchGamepadHost.install(TouchGamepadConfig(...)) was never called. Call it from your " +
                "Application.onCreate() -- in EVERY process that draws the pad -- before the " +
                "overlay is attached or the layout editor is started. See docs/TOUCH-GAMEPAD.md."
        }
}
