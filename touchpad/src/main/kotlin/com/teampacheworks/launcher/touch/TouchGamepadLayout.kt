package com.teampacheworks.launcher.touch

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.FrameLayout
import com.swordfish.radialgamepad.library.RadialGamePad
import com.swordfish.radialgamepad.library.config.ButtonConfig
import com.swordfish.radialgamepad.library.config.CrossConfig
import com.swordfish.radialgamepad.library.config.PrimaryDialConfig
import com.swordfish.radialgamepad.library.config.RadialGamePadConfig
import com.swordfish.radialgamepad.library.config.RadialGamePadTheme
import com.swordfish.radialgamepad.library.haptics.HapticConfig
import kotlin.math.roundToInt

/**
 * What the on-screen gamepad LOOKS like, with no opinion on where its events go.
 *
 * Two callers build this pad, and they must build the same one down to the pixel: the game process
 * draws it over the running game ([TouchGamepadOverlay]) and the launcher process draws it over a
 * black screen so the player can position it ([TouchGamepadEditorActivity]). An editor whose
 * preview is a near-miss of the real pad is worse than no editor, so the configuration, the theme,
 * the default placements and the geometry maths all live here exactly once and neither caller is
 * allowed its own copy.
 *
 * The split with [TouchGamepadOverlay] is: this file knows controls, sizes and positions; that file
 * knows where a press goes.
 *
 * ONE VIEW PER CONTROL
 *
 * Every control that a player can meaningfully want somewhere else is its own [RadialGamePad]: a
 * config with ONE primary dial and NO secondary dials, in a view box sized to that dial, positioned
 * absolutely in a [FrameLayout]. The alternative -- one pad carrying several secondary dials --
 * cannot work here, because RadialGamePad decides where a secondary dial sits from its socket
 * index, so inside such a cluster nothing can be moved at all and an editor can only shove whole
 * clusters around.
 *
 * The two exceptions are the two things that are one control in the hand as well as on screen: the
 * D-pad (a single `CrossDial`, four directions and their diagonals) and the ABXY diamond (a single
 * `PrimaryButtonsDial`, whose composite touch anchors are what let one thumb press A+B).
 *
 * Three things fall out of that, all of them improvements over a cluster row:
 *
 * * **Placement is over the WHOLE screen.** RadialGamePad clamps a cluster's offset to the free
 *   space inside its own share of whatever band it was given, which on an unusual aspect ratio
 *   leaves margins the editor refuses to place anything in. Positions here are the control's CENTRE
 *   as a FRACTION of the real screen, clamped only so the press target stays on screen, so the same
 *   saved layout lands in the same relative place on any aspect ratio.
 * * **The overlay swallows far less.** `RadialGamePad.onTouchEvent` returns true unconditionally,
 *   so a full-width cluster row consumes every touch inside it whether or not it hit a dial. Here
 *   only the control-sized press targets consume, and everything between them reaches the surface
 *   underneath.
 * * **A press can be highlighted in a colour that belongs to the overlay** ([DEFAULT_THEME]).
 */
object TouchGamepadLayout {

    // -------------------------------------------------------------------------------------------
    // SDL indices. See TouchGamepadSink's header for why these are SDL's numbers and not an enum of
    // this module's own.
    // -------------------------------------------------------------------------------------------

    object SdlButton {
        const val A = 0
        const val B = 1
        const val X = 2
        const val Y = 3
        const val BACK = 4
        const val GUIDE = 5
        const val START = 6
        const val LEFT_STICK = 7
        const val RIGHT_STICK = 8
        const val LEFT_SHOULDER = 9
        const val RIGHT_SHOULDER = 10
        const val DPAD_UP = 11
        const val DPAD_DOWN = 12
        const val DPAD_LEFT = 13
        const val DPAD_RIGHT = 14
    }

    object SdlAxis {
        const val LEFT_X = 0
        const val LEFT_Y = 1
        const val RIGHT_X = 2
        const val RIGHT_Y = 3
        const val TRIGGER_LEFT = 4
        const val TRIGGER_RIGHT = 5
    }

    /**
     * Composite-control ids, above the 0..14 button range so they can never collide with it.
     *
     * These are the ids [TouchGamepadOverlay] switches on to do something other than "press button
     * N": the two sticks and the d-pad because one control produces several SDL values, and the two
     * triggers because SDL makes them axes while a touch overlay draws them as buttons. A host
     * building its own control list reuses these ids to get that handling.
     */
    const val ID_LEFT_STICK = 100
    const val ID_RIGHT_STICK = 101
    const val ID_DPAD = 102
    const val ID_LT = 110
    const val ID_RT = 111

    // -------------------------------------------------------------------------------------------
    // Theme
    // -------------------------------------------------------------------------------------------

    /**
     * The default palette: deliberately dark and low-contrast, because this sits on top of a game
     * whose art is the point, and the launcher's opacity option moves the whole thing further back
     * when a player wants that.
     *
     * PRESSED IS A NEUTRAL WHITE-GREY, and that is the one colour choice here worth defending. The
     * reference port first used the game's own accent gold, and a pressed button then looked like
     * something the game had lit up rather than something the player was touching -- every prompt
     * and every menu highlight in that game was the same colour. A light grey belongs to no one but
     * the overlay, which is exactly what a press indicator should be. A host overriding
     * [TouchGamepadConfig.theme] to match its key art should leave `pressedColor` alone for this
     * reason.
     */
    val DEFAULT_THEME = RadialGamePadTheme(
        normalColor = Color.argb(170, 32, 28, 40),
        pressedColor = Color.argb(235, 214, 216, 224),
        textColor = Color.argb(225, 236, 226, 206),
        backgroundColor = Color.argb(80, 14, 11, 18),
        lightColor = Color.argb(130, 96, 86, 116),
        normalStrokeColor = Color.argb(150, 120, 108, 140),
        lightStrokeColor = Color.argb(110, 120, 108, 140),
        backgroundStrokeColor = Color.argb(90, 120, 108, 140)
    )

    /**
     * The two themes a pad is drawn with: one for real dials, one for single buttons.
     *
     * They differ in exactly one thing -- a single button has no backing disc. Its view box is 2.63x
     * the button it draws ([Kind.BUTTON]), so a background painted on that box would be a large
     * visible circle around a small button, advertising dead space that (with the design in [Pad])
     * is not even touch-sensitive.
     */
    class Themes(val dial: RadialGamePadTheme, val button: RadialGamePadTheme)

    fun themesFor(theme: RadialGamePadTheme) = Themes(
        dial = theme,
        button = theme.copy(
            backgroundColor = Color.TRANSPARENT,
            backgroundStrokeColor = Color.TRANSPARENT
        )
    )

    // -------------------------------------------------------------------------------------------
    // Controls
    // -------------------------------------------------------------------------------------------

    /**
     * How much bigger than the DRAWN button its press target may be, by default.
     *
     * The library pins the OTHER ratio and it cannot be tuned (see [Kind]), so the press target is a
     * separate, smaller VIEW instead -- see [Pad] -- and this is how much bigger than the button
     * that view is.
     *
     * WHY 1.2 AND NOT SOMETHING ROUNDER. In the reference port the untouched 2.63x meant a 61 dp
     * button answering a 161 dp circle, and with six menu buttons 0.110 of the width apart the
     * circles overlapped by 69.5 dp -- more than half of each. 1.5 was tried next: at 91.8 dp
     * against the 91.6 dp the defaults left between them, the targets touched EDGE TO EDGE, and a
     * player on a device narrower than the one measured still reported them fighting. 1.2 gives a
     * 73.4 dp target, an 18.2 dp gap on the same device, and needs a 667 dp wide screen to stay
     * clear against 835 dp at 1.5.
     *
     * The lesson worth carrying, more than the number: a threshold that lands within a dp of the
     * device you measured on is not solved, it is merely not yet visible.
     */
    const val BUTTON_HIT_RATIO = 1.2f

    /**
     * What a control is made of, and how big its view box has to be for a given drawn size.
     *
     * [boxFactor] is the ratio between the view box and the drawn control, and it is a property of
     * RadialGamePad's own arithmetic rather than a taste decision:
     *
     * * a Stick / Cross / PrimaryButtons dial is measured as `min(width, height) / 2` and drawn to
     *   fill the box, so the box IS the control (factor 1);
     * * a single button is a `PrimaryButtonsDial` with only a `center` action, and
     *   `PrimaryButtonsDial.computeButtonRadius` gives that centre button a radius of
     *   `box/2 * 0.95/2 * 0.8` -- 0.19 of the box, i.e. a drawn diameter of 0.38 of it. So a 60 dp
     *   button needs a ~158 dp box.
     *
     * THE ONE THING TO UNDERSTAND ABOUT THAT 0.38: it cannot be tuned. Scaling the box scales the
     * button with it, so "shrink the box to tighten the touch target" makes the button smaller and
     * changes nothing else. That is why the press target is a separate view instead -- see [Pad].
     *
     * [hitRatio] here is only the default for a kind; [Control.hitRatio] overrides it per control,
     * which is how it is actually used.
     */
    enum class Kind(val boxFactor: Float, val hitRatio: Float) {
        STICK(1f, 1f),
        CROSS(1f, 1f),
        FACE(1f, 1f),
        BUTTON(1f / 0.38f, BUTTON_HIT_RATIO)
    }

    /**
     * One independently placed and sized control.
     *
     * @param key The persistence key, written into the layout file ([TouchGamepadSettings.save]).
     *   STABLE: renaming one silently resets that control to its default for every player.
     * @param nameRes The control's user-visible name, shown over the editor's size slider.
     * @param defaultXFraction / [defaultYFraction] The control's CENTRE as a fraction of the screen,
     *   x from the left edge and y from the top. Fractions rather than dp so a layout tuned on one
     *   device lands in the same relative place on another.
     * @param sizeFraction The drawn size of this control as a multiple of the launcher's size
     *   bracket ([TouchGamepadSettings.Settings.baseSizeDp]). The editor's slider multiplies this
     *   further, per control.
     * @param hitRatio Press target as a multiple of the DRAWN control, overriding [Kind.hitRatio].
     *   Set it to 1 for a control whose neighbours are close, whose position is at a screen edge, or
     *   whose mis-press is expensive -- and do NOT feel obliged to keep the two halves of the pad
     *   symmetric about it. In [XBOX_CONTROLS] the right half is the crowded one and takes 1 while
     *   the left half keeps the default; that asymmetry is the point, not an oversight.
     * @param config Builds the RadialGamePad configuration, given the pad's two themes.
     */
    class Control(
        val key: String,
        val nameRes: Int,
        val kind: Kind,
        val defaultXFraction: Float,
        val defaultYFraction: Float,
        val sizeFraction: Float,
        val hitRatio: Float = kind.hitRatio,
        val config: (Themes) -> RadialGamePadConfig
    )

    /** A single button: one `PrimaryButtonsDial` with only a centre action. */
    fun singleButton(id: Int, label: String): (Themes) -> RadialGamePadConfig = { themes ->
        RadialGamePadConfig(
            sockets = 12,
            primaryDial = PrimaryDialConfig.PrimaryButtons(
                dials = emptyList(),
                center = ButtonConfig(id = id, label = label),
                // Must stay false with an empty `dials` list: PrimaryButtonsDial builds its
                // composite (two-buttons-at-once) anchors from `circleActions[0]`. It only does
                // that when multiple presses are allowed AND there is no centre action, so a
                // centre-only dial is safe either way -- this is belt and braces on a
                // library-internal invariant.
                allowMultiplePressesSingleFinger = false,
                theme = themes.button
            ),
            secondaryDials = emptyList(),
            haptic = HapticConfig.PRESS,
            theme = themes.button
        )
    }

    /** An analogue stick that can also be clicked in. */
    fun stick(id: Int, pressId: Int, description: String): (Themes) -> RadialGamePadConfig =
        { themes ->
            RadialGamePadConfig(
                sockets = 12,
                primaryDial = PrimaryDialConfig.Stick(
                    id = id, buttonPressId = pressId, contentDescription = description
                ),
                secondaryDials = emptyList(),
                haptic = HapticConfig.PRESS,
                theme = themes.dial
            )
        }

    /** The four-way cross with diagonals, decomposed into d-pad buttons by [TouchGamepadOverlay]. */
    fun cross(id: Int): (Themes) -> RadialGamePadConfig = { themes ->
        RadialGamePadConfig(
            sockets = 12,
            primaryDial = PrimaryDialConfig.Cross(
                CrossConfig(id = id, shape = CrossConfig.Shape.STANDARD)
            ),
            secondaryDials = emptyList(),
            haptic = HapticConfig.PRESS,
            theme = themes.dial
        )
    }

    /** The ABXY diamond as ONE control, so that one thumb can press two of its buttons at once. */
    fun faceButtons(): (Themes) -> RadialGamePadConfig = { themes ->
        RadialGamePadConfig(
            sockets = 12,
            // Counterclockwise from 3 o'clock, so this reads B(right), Y(top), X(left), A(bottom)
            // -- the Xbox diamond, not the Nintendo one.
            primaryDial = PrimaryDialConfig.PrimaryButtons(
                dials = listOf(
                    ButtonConfig(id = SdlButton.B, label = "B"),
                    ButtonConfig(id = SdlButton.Y, label = "Y"),
                    ButtonConfig(id = SdlButton.X, label = "X"),
                    ButtonConfig(id = SdlButton.A, label = "A")
                )
            ),
            secondaryDials = emptyList(),
            haptic = HapticConfig.PRESS,
            theme = themes.dial
        )
    }

    /**
     * The default ten-control Xbox pad, in the order they are added to the container -- WHICH IS
     * ALSO THEIR Z-ORDER, and that order is the single least obvious thing in this file.
     *
     * THE RULE IS "THE MORE DEAD SPACE A CONTROL'S BOX HAS, THE LOWER IT GOES", not "the more
     * expensive a mis-press is, the lower it goes". The reference port ordered it the second way for
     * four revisions and was wrong all four times.
     *
     * Why: a button's view box is 2.63x the button, so 86% of it draws nothing. In any design where
     * that box still receives touches -- which is every design short of the split in [Pad] -- a
     * button sitting ON TOP of a dial does not steal the dial's presses, it DELETES them, because
     * `RadialGamePad.onTouchEvent` returns true unconditionally and there is nothing to hand the
     * touch on to. Measured on an 800x360 dp phone with the shoulder buttons above the dials: RB's
     * box covered 42.2 dp of the ABXY diamond and LB's covered 42.2 dp of the left stick, all of it
     * dead. The player reported it as "Y is very hard to press" -- not as RB firing, because RB
     * never fired. That is the signature to recognise.
     *
     * A dial has boxFactor 1: its box IS its control, no dead ring, so it shadows nothing and
     * belongs on top. Cost-of-mis-press is the tie-breaker WITHIN a group, which is why BACK and
     * START are first of the six buttons and not merely first of the ten.
     *
     * The default positions put the sticks and the diamond low on each side, the D-pad below and
     * inboard of the left stick, the right stick below and inboard of the diamond (a real Xbox pad's
     * relationship), and the two shoulder/trigger/menu triples arcing above each side, mirrored
     * through the screen's vertical axis.
     */
    val XBOX_CONTROLS: List<Control> = listOf(
        // BACK and START first of all ten, so they sit under every other control -- the sticks, the
        // d-pad and the ABXY diamond included, not just the shoulders. Where two targets overlap the
        // topmost one takes the touch, and these two must always be the ones that lose it.
        //
        // They also take hitRatio = 1: the press target is the drawn button and nothing around it.
        // At 1.5 START's target still reached into the ABXY diamond on a phone. These are deliberate
        // presses rather than reflexes, so demanding an accurate one costs nothing a player notices
        // in a fight -- and opening the menu by accident can cost a run.
        Control(
            "back", R.string.pl_pad_back, Kind.BUTTON, 0.075f, 0.440f, 0.36f, hitRatio = 1f,
            config = singleButton(SdlButton.BACK, "BACK")
        ),
        Control(
            "start", R.string.pl_pad_start, Kind.BUTTON, 0.925f, 0.440f, 0.36f, hitRatio = 1f,
            config = singleButton(SdlButton.START, "START")
        ),
        // The shoulders and triggers, UNDER the dials -- see the header.
        //
        // The two sides do NOT get the same target, and that is deliberate. The right half of the
        // screen is the crowded one -- RT, RB and START share it with the ABXY diamond below them --
        // so RT and RB join BACK and START at hitRatio 1 and answer their own button only. LB and LT
        // keep the wider default: nothing sits near them but each other, so there is room to be
        // forgiving and no reason not to be.
        Control(
            "lb", R.string.pl_pad_lb, Kind.BUTTON, 0.185f, 0.400f, 0.36f,
            config = singleButton(SdlButton.LEFT_SHOULDER, "LB")
        ),
        Control(
            "lt", R.string.pl_pad_lt, Kind.BUTTON, 0.295f, 0.440f, 0.36f,
            config = singleButton(ID_LT, "LT")
        ),
        Control(
            "rt", R.string.pl_pad_rt, Kind.BUTTON, 0.705f, 0.440f, 0.36f, hitRatio = 1f,
            config = singleButton(ID_RT, "RT")
        ),
        Control(
            "rb", R.string.pl_pad_rb, Kind.BUTTON, 0.815f, 0.400f, 0.36f, hitRatio = 1f,
            config = singleButton(SdlButton.RIGHT_SHOULDER, "RB")
        ),
        Control(
            "lstick", R.string.pl_pad_left_stick, Kind.STICK, 0.130f, 0.720f, 1.00f,
            config = stick(ID_LEFT_STICK, SdlButton.LEFT_STICK, "Left stick")
        ),
        Control(
            "face", R.string.pl_pad_face, Kind.FACE, 0.870f, 0.720f, 1.00f,
            config = faceButtons()
        ),
        Control(
            "dpad", R.string.pl_pad_dpad, Kind.CROSS, 0.300f, 0.860f, 0.95f,
            config = cross(ID_DPAD)
        ),
        Control(
            "rstick", R.string.pl_pad_right_stick, Kind.STICK, 0.700f, 0.860f, 0.95f,
            config = stick(ID_RIGHT_STICK, SdlButton.RIGHT_STICK, "Right stick")
        )
    )

    /** The controls this host actually uses. */
    val controls: List<Control> get() = TouchGamepadHost.config.controls

    fun controlFor(key: String): Control? = controls.firstOrNull { it.key == key }

    // -------------------------------------------------------------------------------------------
    // Building and placing
    // -------------------------------------------------------------------------------------------

    /**
     * One control: the view that DRAWS it, and the view that RECEIVES its touches.
     *
     * They are two different views because they must be two different sizes. The library draws a
     * button at 0.38 of its view, so drawing a 61 dp button needs a 161 dp RadialGamePad -- and a
     * 161 dp view that answers touches over its whole area is what covered the ABXY diamond in four
     * earlier attempts at this.
     *
     * So [hit] is a plain FrameLayout sized to the PRESS TARGET, [view] is the RadialGamePad sized
     * to what the library needs, and [view] is centred inside [hit] with NEGATIVE margins so it
     * hangs out on all four sides and draws at full size. `clipChildren = false` is what lets it,
     * and it must be false on every ViewGroup from [hit] up to wherever drawing is allowed.
     *
     * Android hit-tests children by their bounds, so a touch outside [hit] is never offered to this
     * control at all -- it falls through to whatever is beneath, exactly as it should. Nothing is
     * swallowed, a MOVE that leaves the control still delivers its release (Android keeps the target
     * until UP), and multi-touch is the framework's own per-pointer dispatch rather than anything
     * written here.
     *
     * The one cost: the press target is the SQUARE bounds of [hit], not a circle inscribed in it, so
     * the four corners are ~27% more area than a circle would give. That is the price of swallowing
     * nothing, and it is the right way round.
     */
    class Pad(
        val control: Control,
        val view: RadialGamePad,
        val hit: FrameLayout
    )

    /**
     * The container and the pads inside it.
     *
     * The placement is re-run from [container]'s own layout listener as well as from [apply],
     * because the fractions mean nothing until the container has a width and a height -- and on a
     * device with a hinge, a resizable window or a rotation, the size it has is not the one the
     * display metrics reported.
     */
    class Pads(val container: FrameLayout, val pads: List<Pad>) {
        internal var opacity: Float = 1f
        internal var baseSizeDp: Float = 170f
        internal var placements: Map<String, TouchGamepadSettings.Placement> = emptyMap()

        fun pad(key: String): Pad? = pads.firstOrNull { it.control.key == key }
    }

    fun build(context: Context): Pads {
        val config = TouchGamepadHost.config
        val container = FrameLayout(context)
        // The pads draw outside their own bounds -- see Pad -- so nothing on the way down may clip
        // them.
        container.clipChildren = false
        val pads = config.controls.map { control ->
            // Zero default margins: the drawn dial is then exactly the view box, which is the whole
            // basis of Kind.boxFactor.
            val view = RadialGamePad(
                gamePadConfig = control.config(config.themes),
                defaultMarginsInDp = 0f,
                context = context
            )
            val hit = FrameLayout(context)
            hit.clipChildren = false
            hit.addView(
                view, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            container.addView(
                hit, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            Pad(control, view, hit)
        }
        val result = Pads(container, pads)
        container.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if (r - l != or_ - ol || b - t != ob - ot) place(context, result)
        }
        return result
    }

    /** Opacity, the launcher's size bracket and the editor's per-control placements onto live pads. */
    fun apply(
        context: Context,
        pads: Pads,
        settings: TouchGamepadSettings.Settings,
        placements: Map<String, TouchGamepadSettings.Placement> = settings.placements
    ) {
        pads.opacity = settings.opacity
        pads.baseSizeDp = settings.baseSizeDp
        pads.placements = placements
        place(context, pads)
    }

    /**
     * The one piece of geometry arithmetic in the whole feature.
     *
     * Each control's drawn size is `baseSizeDp * sizeFraction * scale`; its RadialGamePad view is
     * that times `kind.boxFactor`; its press target is that times `hitRatio`; and its centre is
     * `(xFraction, yFraction)` of the container, clamped so the PRESS TARGET stays inside the
     * container. Nothing here knows about aspect ratios, reserved bands or screen halves.
     *
     * Clamping the press target rather than the view box is not a detail. Clamp the box and a
     * control asked for 0.925 of the width is shoved back inside by half the dead ring -- 18 dp in
     * the reference port -- and lands deeper into its neighbour than the layout ever said.
     */
    private fun place(context: Context, pads: Pads) {
        val w = pads.container.width.takeIf { it > 0 }
            ?: context.resources.displayMetrics.widthPixels
        val h = pads.container.height.takeIf { it > 0 }
            ?: context.resources.displayMetrics.heightPixels

        for (pad in pads.pads) {
            val p = pads.placements[pad.control.key]
                ?: TouchGamepadSettings.defaultPlacement(pad.control)
            val drawnDp = pads.baseSizeDp * pad.control.sizeFraction * p.scale
            val boxPx = dpf(context, drawnDp * pad.control.kind.boxFactor)
                .roundToInt().coerceAtLeast(1)
            // The press target: the drawn control, times whatever forgiveness this control is
            // allowed. For a dial the two are the same number.
            val hitPx = dpf(context, drawnDp * pad.control.hitRatio).roundToInt().coerceAtLeast(1)

            pad.view.alpha = pads.opacity
            pad.view.primaryDialMaxSizeDp = drawnDp * pad.control.kind.boxFactor

            // The RadialGamePad, centred in the wrapper and hanging out of it.
            val vlp = pad.view.layoutParams as FrameLayout.LayoutParams
            vlp.width = boxPx
            vlp.height = boxPx
            vlp.leftMargin = (hitPx - boxPx) / 2
            vlp.topMargin = (hitPx - boxPx) / 2
            pad.view.layoutParams = vlp

            // The wrapper is what gets positioned and what the edge rule applies to. It is the press
            // target, so keeping it on screen keeps the part of the control a finger can use on
            // screen -- and for every control whose hitRatio is 1 that is exactly the drawn control.
            val hlp = pad.hit.layoutParams as FrameLayout.LayoutParams
            hlp.width = hitPx
            hlp.height = hitPx
            hlp.leftMargin = (p.xFraction * w - hitPx / 2f).roundToInt()
                .coerceIn(0, (w - hitPx).coerceAtLeast(0))
            hlp.topMargin = (p.yFraction * h - hitPx / 2f).roundToInt()
                .coerceIn(0, (h - hitPx).coerceAtLeast(0))
            pad.hit.layoutParams = hlp
        }
    }

    /**
     * The centre fraction a press target of [hitPx] may be dragged to without leaving the container,
     * as (min, max). The editor clamps with this so a control cannot be pushed off the edge and
     * lost -- and it must be the same quantity [place] clamps, or the editor would place controls
     * that the game then moves.
     */
    fun centreRange(hitPx: Int, extentPx: Int): ClosedFloatingPointRange<Float> {
        if (extentPx <= 0) return 0f..1f
        val half = (hitPx / 2f) / extentPx
        return if (half >= 0.5f) 0.5f..0.5f else half..(1f - half)
    }

    fun dpf(context: Context, v: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics
    )

    fun dp(context: Context, v: Float): Int = dpf(context, v).toInt()
}
