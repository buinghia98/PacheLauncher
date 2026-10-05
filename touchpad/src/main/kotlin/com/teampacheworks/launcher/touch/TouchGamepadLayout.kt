package com.teampacheworks.launcher.touch

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.swordfish.radialgamepad.library.RadialGamePad
import com.swordfish.radialgamepad.library.config.ButtonConfig
import com.swordfish.radialgamepad.library.config.CrossConfig
import com.swordfish.radialgamepad.library.config.PrimaryDialConfig
import com.swordfish.radialgamepad.library.config.RadialGamePadConfig
import com.swordfish.radialgamepad.library.config.RadialGamePadTheme
import com.swordfish.radialgamepad.library.haptics.HapticConfig
import com.teampacheworks.launcher.touch.TouchGamepadSprite.Body
import com.teampacheworks.launcher.touch.TouchGamepadSprite.FaceButton
import com.teampacheworks.launcher.touch.TouchGamepadSprite.Label
import com.teampacheworks.launcher.touch.TouchGamepadSprite.Skin
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What the on-screen gamepad LOOKS like and where it sits, with no opinion on where its events go.
 *
 * Two callers build this pad, and they must build the same one down to the pixel: the game process
 * draws it over the running game ([TouchGamepadOverlay]) and the launcher process draws it over a
 * black screen so the player can position it ([TouchGamepadEditorActivity]). So the controls, the
 * skin, the default placements and the geometry maths all live here exactly once.
 *
 * TWO LAYERS PER CONTROL
 *
 * * INPUT: one [RadialGamePad] per control -- touch tracking, stick maths, the cross's diagonals,
 *   multi-press on the ABXY diamond, haptics, and the event Flow the sink is fed from. It paints
 *   nothing ([INPUT_ONLY_THEME]) and sits at alpha 0.
 * * LOOK: a [TouchGamepadSprite] under it -- Kenney "Mobile Controls" Style C (CC0) -- sized to the
 *   DRAWN control and mirroring pressed state from the same events.
 *
 * Both are derived from one [TouchGamepadSprite.Skin] per control ([inputConfig]), so the two layers
 * cannot disagree about which id is where.
 *
 * ONE VIEW PER CONTROL. Every control is its own RadialGamePad with ONE primary dial in a view box
 * sized to that dial, positioned absolutely in a [FrameLayout]. A cluster pad with secondary dials
 * cannot work here: RadialGamePad places a secondary dial by socket index, so nothing inside a
 * cluster could be moved. The two exceptions are the D-pad (one `CrossDial`) and the ABXY diamond
 * (one `PrimaryButtonsDial`, whose composite anchors let one thumb press A+B).
 *
 * Placement is the control's CENTRE as a FRACTION of the whole screen, clamped only so the press
 * target stays on screen -- see [place] and docs/TOUCH-GAMEPAD-SPEC.md.
 */
object TouchGamepadLayout {

    // -------------------------------------------------------------------------------------------
    // SDL indices. See TouchGamepadSink's header for why these are SDL's numbers.
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
     * Composite-control ids, above the 0..14 button range so they can never collide with it: the
     * sticks and the d-pad produce several SDL values, the triggers are SDL axes drawn as buttons.
     * A host building its own control list reuses these ids to get [TouchGamepadOverlay]'s handling.
     */
    const val ID_LEFT_STICK = 100
    const val ID_RIGHT_STICK = 101
    const val ID_DPAD = 102
    const val ID_LT = 110
    const val ID_RT = 111

    /**
     * D-pad axis threshold: past it a direction is down. One constant for the sink dispatch and the
     * sprite's lit arms, so what is shown pressed is what the game was told.
     */
    const val DPAD_THRESHOLD = .5f

    /**
     * The fraction of the normal opacity a HIDDEN control is drawn at -- which only ever happens in
     * the editor, because the game never builds a hidden control (see [visibleControls]).
     */
    const val HIDDEN_EDITOR_ALPHA = .3f

    /** Where an outside-the-circle d-pad touch is put back: safely inside the Cross's bound. */
    const val CROSS_SQUARE_CLAMP = .9f

    // -------------------------------------------------------------------------------------------
    // Theme
    // -------------------------------------------------------------------------------------------

    /**
     * The default PALETTE, painted by [TouchGamepadSprite] in the roles RadialGamePad itself gave
     * these colours: body fill `normalColor`, pressed fill `pressedColor`, stick well
     * `backgroundColor`, outline + label `textColor`. Deliberately dark and low-contrast, because
     * this sits on top of a game whose art is the point.
     *
     * PRESSED IS A NEUTRAL WHITE-GREY. A press highlight in the game's own accent colour reads as
     * the game lighting something up rather than as the player touching something. A host
     * overriding [TouchGamepadConfig.theme] to match its key art should leave `pressedColor` alone.
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
     * RadialGamePad as a pure INPUT layer: every colour transparent, so it paints nothing while its
     * touch handling, stick maths, cross diagonals, multi-press and haptics stay exactly as they are.
     *
     * Verified against the 2.0.0 bytecode: fills come from [RadialGamePadTheme] colours only,
     * `FillStrokePaint.buildStrokePaint` returns no paint at all for colour 0, labels use
     * `textColor`, and the cross / button icon drawables are tinted with `textColor` -- so with all
     * of them 0 nothing visible is left. The view is additionally kept at alpha 0 in [place] so the
     * framework skips its draw pass; alpha does not affect touch dispatch (visibility would).
     */
    val INPUT_ONLY_THEME = RadialGamePadTheme(
        normalColor = Color.TRANSPARENT,
        pressedColor = Color.TRANSPARENT,
        simulatedColor = Color.TRANSPARENT,
        textColor = Color.TRANSPARENT,
        backgroundColor = Color.TRANSPARENT,
        lightColor = Color.TRANSPARENT,
        normalStrokeColor = Color.TRANSPARENT,
        lightStrokeColor = Color.TRANSPARENT,
        backgroundStrokeColor = Color.TRANSPARENT
    )

    // -------------------------------------------------------------------------------------------
    // Controls
    // -------------------------------------------------------------------------------------------

    /**
     * How much bigger than the DRAWN button its press target may be, by default. 1.2 rather than
     * 1.5: at 1.5 six menu buttons 0.110 of the width apart touched edge to edge on the device
     * measured, which is not solved but merely not yet visible. See TOUCH-GAMEPAD-SPEC.md §4.1.
     */
    const val BUTTON_HIT_RATIO = 1.2f

    /**
     * What a control is made of, and how big its RadialGamePad view box has to be for a given drawn
     * size ([boxFactor] -- RadialGamePad's own arithmetic, re-verified against 2.0.0):
     *
     * * a Stick / Cross / multi-button PrimaryButtons dial is measured as `min(w, h) / 2` and drawn
     *   to fill the box, so the box IS the control (factor 1);
     * * a single button is a `PrimaryButtonsDial` with only `center`, whose radius works out to
     *   `box/2 * 0.95 * 0.5 * 0.8 = 0.19 * box` -- a drawn diameter of exactly 0.38 of the box.
     *   Nothing tunes that ratio, which is why the press target is a separate view ([Pad]).
     *
     * [hitRatio] is only the default for a kind; [Control.hitRatio] overrides it per control.
     */
    enum class Kind(val boxFactor: Float, val hitRatio: Float) {
        STICK(1f, 1f),
        CROSS(1f, 1f),
        FACE(1f, 1f),
        BUTTON(1f / 0.38f, BUTTON_HIT_RATIO)
    }

    fun kindOf(skin: Skin): Kind = when (skin) {
        is Skin.Single -> Kind.BUTTON
        is Skin.Face -> Kind.FACE
        is Skin.Cross -> Kind.CROSS
        is Skin.Stick -> Kind.STICK
    }

    /**
     * One independently placed and sized control.
     *
     * @param key The persistence key, written into the layout file ([TouchGamepadSettings.save]).
     *   STABLE: renaming one silently resets that control to its default for every player.
     * @param nameRes The control's user-visible name, shown over the editor's size slider.
     * @param defaultXFraction / [defaultYFraction] The control's CENTRE as a fraction of the screen.
     * @param sizeFraction The drawn size as a multiple of the launcher's size bracket
     *   ([TouchGamepadSettings.Settings.baseSizeDp]). The editor's slider multiplies this further.
     * @param skin What is drawn AND which ids are emitted -- see [inputConfig].
     * @param hitRatio Press target as a multiple of the DRAWN control, overriding [Kind.hitRatio].
     *   Set it to 1 for a control whose neighbours are close, that sits at an edge, or whose
     *   mis-press is expensive.
     */
    class Control(
        val key: String,
        val nameRes: Int,
        val defaultXFraction: Float,
        val defaultYFraction: Float,
        val sizeFraction: Float,
        val skin: Skin,
        val hitRatio: Float = kindOf(skin).hitRatio
    ) {
        val kind: Kind get() = kindOf(skin)
    }

    /** A round shoulder/trigger-style button with a text label; [fitAs] sizes a group alike. */
    fun singleButton(id: Int, label: String, fitAs: String = "LB"): Skin =
        Skin.Single(id, Body.CIRCLE, Label.Text(label, fitAs))

    /** A pill-shaped menu button (SELECT / START), told apart from the shoulders at a glance. */
    fun menuButton(id: Int, label: String): Skin =
        Skin.Single(id, Body.WIDE, Label.Text(label, fitAs = "SELECT"))

    /** An analogue stick that can also be clicked in. */
    fun stick(id: Int, pressId: Int): Skin = Skin.Stick(id, pressId)

    /** The four-way cross with diagonals, decomposed into d-pad buttons by [TouchGamepadOverlay]. */
    fun cross(id: Int = ID_DPAD): Skin = Skin.Cross(id)

    /**
     * ABXY in RadialGamePad dial order -- index i sits i * 90 degrees counter-clockwise from east,
     * so B right, Y top, X left, A bottom: the Xbox diamond, not the Nintendo one.
     */
    val XBOX_FACE_BUTTONS: List<FaceButton> = listOf(
        FaceButton(SdlButton.B, "B", R.drawable.pl_kenney_icon_button_b),
        FaceButton(SdlButton.Y, "Y", R.drawable.pl_kenney_icon_button_y),
        FaceButton(SdlButton.X, "X", R.drawable.pl_kenney_icon_button_x),
        FaceButton(SdlButton.A, "A", R.drawable.pl_kenney_icon_button_a)
    )

    /** The ABXY diamond as ONE control, so that one thumb can press two of its buttons at once. */
    fun faceButtons(buttons: List<FaceButton> = XBOX_FACE_BUTTONS): Skin = Skin.Face(buttons)

    /**
     * The RadialGamePad configuration for [skin] -- the invisible input half of a control.
     */
    fun inputConfig(skin: Skin): RadialGamePadConfig {
        val dial = when (skin) {
            // Multi-press must stay false with an empty `dials` list: PrimaryButtonsDial builds its
            // composite anchors from `circleActions[0]`. A centre-only dial is safe either way --
            // this is belt and braces on a library-internal invariant.
            is Skin.Single -> PrimaryDialConfig.PrimaryButtons(
                dials = emptyList(),
                center = ButtonConfig(id = skin.id, label = labelOf(skin)),
                allowMultiplePressesSingleFinger = false,
                theme = INPUT_ONLY_THEME
            )
            is Skin.Face -> PrimaryDialConfig.PrimaryButtons(
                dials = skin.buttons.map { ButtonConfig(id = it.id, label = it.label) },
                theme = INPUT_ONLY_THEME
            )
            is Skin.Cross -> PrimaryDialConfig.Cross(
                CrossConfig(id = skin.id, shape = CrossConfig.Shape.STANDARD, theme = INPUT_ONLY_THEME)
            )
            is Skin.Stick -> PrimaryDialConfig.Stick(
                id = skin.id, buttonPressId = skin.pressId,
                contentDescription = if (skin.id == ID_RIGHT_STICK) "Right stick" else "Left stick"
            )
        }
        return RadialGamePadConfig(
            sockets = 12,
            primaryDial = dial,
            secondaryDials = emptyList(),
            haptic = HapticConfig.PRESS,
            theme = INPUT_ONLY_THEME
        )
    }

    private fun labelOf(skin: Skin.Single): String = when (val l = skin.label) {
        is Label.Text -> l.text
        is Label.Icon -> ""
    }

    /**
     * The default ten-control Xbox pad for WIDE screens (phones, 16:9 handhelds), in the order they
     * are added to the container -- WHICH IS ALSO THEIR Z-ORDER.
     *
     * THE RULE IS "THE MORE DEAD SPACE A CONTROL'S BOX HAS, THE LOWER IT GOES", not "the more
     * expensive a mis-press is, the lower it goes". A button's view box is 2.63x the button, so 86%
     * of it draws nothing; a dial's box IS the dial. Since the split in [Pad] the dead ring receives
     * no touches, but the order is cheap insurance if that is ever regressed. Cost-of-mis-press is
     * the tie-breaker WITHIN a group, which is why BACK and START are first of all ten.
     *
     * Sticks and the diamond sit low on each side, the D-pad below and inboard of the left stick,
     * the right stick below and inboard of the diamond (a real Xbox pad's relationship), and the
     * shoulder/trigger/menu triples arc above each side. Tuned on an 800 x 360 dp phone; on a
     * near-square panel, where width is the scarce axis, use [STACKED_CONTROLS] instead.
     */
    val XBOX_CONTROLS: List<Control> = listOf(
        // BACK/START: deliberate presses, so the press target is the drawn pill and nothing more.
        Control("back", R.string.pl_pad_back, .075f, .440f, .36f,
            menuButton(SdlButton.BACK, "SELECT"), hitRatio = 1f),
        Control("start", R.string.pl_pad_start, .925f, .440f, .36f,
            menuButton(SdlButton.START, "START"), hitRatio = 1f),
        // The right half is the crowded one (RT, RB and START share it with ABXY), so RT and RB
        // answer their own button only; LB and LT keep the wider default.
        Control("lb", R.string.pl_pad_lb, .185f, .400f, .36f,
            singleButton(SdlButton.LEFT_SHOULDER, "LB")),
        Control("lt", R.string.pl_pad_lt, .295f, .440f, .36f, singleButton(ID_LT, "LT")),
        Control("rt", R.string.pl_pad_rt, .705f, .440f, .36f, singleButton(ID_RT, "RT"),
            hitRatio = 1f),
        Control("rb", R.string.pl_pad_rb, .815f, .400f, .36f,
            singleButton(SdlButton.RIGHT_SHOULDER, "RB"), hitRatio = 1f),
        // Dials on top: box == control, no dead ring, so they shadow nothing.
        Control("lstick", R.string.pl_pad_left_stick, .130f, .720f, 1.00f,
            stick(ID_LEFT_STICK, SdlButton.LEFT_STICK)),
        Control("face", R.string.pl_pad_face, .870f, .720f, 1.00f, faceButtons()),
        Control("dpad", R.string.pl_pad_dpad, .300f, .860f, .95f, cross(ID_DPAD)),
        Control("rstick", R.string.pl_pad_right_stick, .700f, .860f, .95f,
            stick(ID_RIGHT_STICK, SdlButton.RIGHT_STICK))
    )

    /**
     * The three horizontal bands [STACKED_CONTROLS] is built from, as fractions of screen HEIGHT.
     *
     * On a near-square panel (the reference port's RP Mini: 1240x1080 px, 537.7 x 468.3 dp) the
     * four dials alone want `170 + 161.5` dp of width per side -- 663 dp against 537.7 -- so no
     * horizontal arrangement can separate the left stick from the d-pad. A SQUARE press target only
     * needs clearance on ONE axis, so these defaults stack instead: one big dial per side per band.
     *
     * Budget at a 190 dp size: `0.36x190 + 0.95x190 + 1.00x190 = 438.9` dp of control against
     * 468.3 dp of height -- 29.4 dp of slack for three gaps and two margins. That is what fixes the
     * values, and why a host using this list should cap its size option at about
     * `height_dp / 2.46` (190 dp on a 1080 px-tall ~370 dpi panel); 200 closes the stick/d-pad gap
     * to 0.2 dp, which is not a clearance.
     */
    const val ROW_BUTTONS = .0816f
    const val ROW_REACH = .3644f
    const val ROW_THUMBS = .7813f

    /**
     * The left stick's row: the reach band lowered by 0.0072 H. The left column stacks a 1.00 size
     * (stick) above a 0.95 (d-pad), so a stick at [ROW_REACH] would clear LB/LT by 3.2 dp and the
     * d-pad by 10.0 at the 190 dp ceiling; centring it in that gap gives 6.6 dp on both sides.
     */
    const val ROW_REACH_LSTICK = .3716f

    /**
     * The same ten controls for NEAR-SQUARE panels (4:3 .. 5:4 handhelds), stacked in three bands
     * (see [ROW_BUTTONS]). The two controls a thumb rests on -- the d-pad and ABXY -- take the bottom
     * band, mirrored left/right; the two sticks sit directly above them; the six shoulder/menu
     * buttons take the top band, 0.138 of the width apart. Every button answers its own face only
     * (hitRatio 1): at 1.2 neighbouring targets overlap by 14.3 dp on a 537.7 dp-wide panel.
     *
     * Same keys, ids and z-order rule as [XBOX_CONTROLS], so a saved layout carries across.
     */
    val STACKED_CONTROLS: List<Control> = listOf(
        Control("back", R.string.pl_pad_back, .070f, ROW_BUTTONS, .36f,
            menuButton(SdlButton.BACK, "SELECT"), hitRatio = 1f),
        Control("start", R.string.pl_pad_start, .930f, ROW_BUTTONS, .36f,
            menuButton(SdlButton.START, "START"), hitRatio = 1f),
        Control("lb", R.string.pl_pad_lb, .208f, ROW_BUTTONS, .36f,
            singleButton(SdlButton.LEFT_SHOULDER, "LB"), hitRatio = 1f),
        Control("lt", R.string.pl_pad_lt, .346f, ROW_BUTTONS, .36f,
            singleButton(ID_LT, "LT"), hitRatio = 1f),
        Control("rt", R.string.pl_pad_rt, .654f, ROW_BUTTONS, .36f,
            singleButton(ID_RT, "RT"), hitRatio = 1f),
        Control("rb", R.string.pl_pad_rb, .792f, ROW_BUTTONS, .36f,
            singleButton(SdlButton.RIGHT_SHOULDER, "RB"), hitRatio = 1f),
        Control("lstick", R.string.pl_pad_left_stick, .175f, ROW_REACH_LSTICK, 1f,
            stick(ID_LEFT_STICK, SdlButton.LEFT_STICK)),
        Control("face", R.string.pl_pad_face, .815f, ROW_THUMBS, 1f, faceButtons()),
        Control("dpad", R.string.pl_pad_dpad, .185f, ROW_THUMBS, .95f, cross(ID_DPAD)),
        Control("rstick", R.string.pl_pad_right_stick, .825f, ROW_REACH, .95f,
            stick(ID_RIGHT_STICK, SdlButton.RIGHT_STICK))
    )

    /** The controls this host actually uses. */
    val controls: List<Control> get() = TouchGamepadHost.config.controls

    fun controlFor(key: String): Control? = controls.firstOrNull { it.key == key }

    /** The controls the GAME builds: the ones whose placement says visible. */
    fun visibleControls(placements: Map<String, TouchGamepadSettings.Placement>): List<Control> =
        controls.filter { (placements[it.key] ?: TouchGamepadSettings.defaultPlacement(it)).visible }

    // -------------------------------------------------------------------------------------------
    // Building and placing
    // -------------------------------------------------------------------------------------------

    /**
     * One control: the view that receives its input ([view], invisible), the view that draws it
     * ([sprite]) and the view that bounds its touches ([hit]).
     *
     * The library draws a single button at 0.38 of its view, so a 61 dp button needs a 161 dp
     * RadialGamePad -- and `RadialGamePad.onTouchEvent` returns true unconditionally, so a 161 dp
     * view that answered touches would DELETE a neighbour's presses. So [hit] is sized to the PRESS
     * TARGET and [view] is centred inside it with NEGATIVE margins, hanging out on all four sides.
     * `clipChildren = false` on [hit] and on the container is what allows that.
     *
     * Android hit-tests children by their bounds, so a touch outside [hit] is never offered to this
     * control -- it falls through to whatever is beneath. The cost is that the target is a square.
     */
    class Pad(
        val control: Control,
        val view: RadialGamePad,
        val hit: HitBox,
        val sprite: TouchGamepadSprite
    )

    /**
     * The press target of one control: a bare FrameLayout, plus two behaviours.
     *
     * [interceptTouches] is for the editor, and it is not optional there: touch dispatch offers a
     * ViewGroup's children the event BEFORE the group's own OnTouchListener, and
     * `RadialGamePad.onTouchEvent` returns true for everything -- so a drag listener on an ordinary
     * wrapper would never be called. Intercepting lets the editor grab the control instead.
     *
     * The d-pad's SQUARE target ([clampToCircle]) is the other.
     */
    class HitBox(context: Context) : FrameLayout(context) {
        var interceptTouches = false

        /** Told when a finger lands on / leaves this target in game (the stick nub's held look). */
        internal var sprite: TouchGamepadSprite? = null

        /**
         * Set for the d-pad only: the RadialGamePad whose CIRCULAR touch bound should answer the
         * whole SQUARE target.
         */
        internal var squareToCircle: View? = null

        private var props = emptyArray<MotionEvent.PointerProperties>()
        private var coords = emptyArray<MotionEvent.PointerCoords>()

        init {
            clipChildren = false
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = interceptTouches

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (!interceptTouches) when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> sprite?.onTouchActive(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> sprite?.onTouchActive(false)
            }
            val target = squareToCircle
            if (interceptTouches || target == null) return super.dispatchTouchEvent(ev)
            val clamped = clampToCircle(ev, target)
            return try {
                super.dispatchTouchEvent(clamped)
            } finally {
                clamped.recycle()
            }
        }

        /**
         * The d-pad's square press target, answered by RadialGamePad's round Cross.
         *
         * The Cross only claims a pointer inside its circular touch bound (centre of the view,
         * radius = half the dial), so the corners of the drawn d-pad -- inside this target -- would
         * press nothing. Every pointer farther out than [CROSS_SQUARE_CLAMP] of that radius is pulled
         * straight back along its own angle onto that circle. The ANGLE is all the Cross reads
         * (sector -> 4/8-way, so a top-right corner is UP+RIGHT); inside the circle nothing moves,
         * so the centre dead zone and every press that worked before are unchanged. Only this view's
         * own pointers arrive here (split dispatch), so multi-touch elsewhere is untouched.
         */
        private fun clampToCircle(ev: MotionEvent, target: View): MotionEvent {
            val n = ev.pointerCount
            if (props.size < n) {
                props = Array(n) { MotionEvent.PointerProperties() }
                coords = Array(n) { MotionEvent.PointerCoords() }
            }
            val cx = target.left + target.width / 2f
            val cy = target.top + target.height / 2f
            val r = minOf(target.width, target.height) / 2f * CROSS_SQUARE_CLAMP
            for (i in 0 until n) {
                ev.getPointerProperties(i, props[i])
                ev.getPointerCoords(i, coords[i])
                val dx = coords[i].x - cx
                val dy = coords[i].y - cy
                val d = sqrt(dx * dx + dy * dy)
                if (d > r) {
                    coords[i].x = cx + dx * r / d
                    coords[i].y = cy + dy * r / d
                }
            }
            return MotionEvent.obtain(
                ev.downTime, ev.eventTime, ev.action, n, props, coords, ev.metaState,
                ev.buttonState, ev.xPrecision, ev.yPrecision, ev.deviceId, ev.edgeFlags,
                ev.source, ev.flags
            )
        }
    }

    /**
     * The container and the pads inside it. The placement is re-run from [container]'s own layout
     * listener as well as from [apply], because fractions mean nothing until it has a size.
     */
    class Pads(val container: FrameLayout, val pads: List<Pad>) {
        internal var opacity: Float = 1f
        internal var baseSizeDp: Float = 170f
        internal var placements: Map<String, TouchGamepadSettings.Placement> = emptyMap()

        fun pad(key: String): Pad? = pads.firstOrNull { it.control.key == key }
    }

    /**
     * Build [controls] -- every control for the editor, [visibleControls] for the game. A control
     * left out here has no view at all: it cannot draw, cannot be hit, cannot steal a neighbour's
     * press and never emits an event.
     */
    fun build(context: Context, controls: List<Control> = this.controls): Pads {
        val theme = TouchGamepadHost.config.theme
        val container = FrameLayout(context)
        // The pads draw outside their own bounds -- see Pad -- so nothing on the way down may clip.
        container.clipChildren = false
        val pads = controls.map { control ->
            // Zero default margins: the dial is then exactly the view box (Kind.boxFactor).
            val view = RadialGamePad(
                gamePadConfig = inputConfig(control.skin),
                defaultMarginsInDp = 0f,
                context = context
            )
            val sprite = TouchGamepadSprite(context, control.skin, theme)
            val hit = HitBox(context).also { it.sprite = sprite }
            if (control.kind == Kind.CROSS) hit.squareToCircle = view
            // Sprite first, input on top: dispatch offers the touch to the topmost child. The sprite
            // is never clickable, so it could not take it anyway.
            hit.addView(sprite, wrapContent())
            hit.addView(view, wrapContent())
            container.addView(hit, wrapContent())
            Pad(control, view, hit, sprite)
        }
        val result = Pads(container, pads)
        container.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if (r - l != or_ - ol || b - t != ob - ot) place(context, result)
        }
        return result
    }

    private fun wrapContent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

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
     * that times `kind.boxFactor`; its sprite is the drawn size; its press target is that times
     * `hitRatio`; and its centre is `(xFraction, yFraction)` of the container, clamped so the PRESS
     * TARGET stays inside the container. Clamping the 2.63x view box instead would shove an edge
     * button inwards by half its dead ring.
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
            val boxDp = drawnDp * pad.control.kind.boxFactor
            val boxPx = dpf(context, boxDp).roundToInt().coerceAtLeast(1)
            val hitPx = dpf(context, drawnDp * pad.control.hitRatio).roundToInt().coerceAtLeast(1)
            val drawnPx = dpf(context, drawnDp).roundToInt().coerceAtLeast(1)

            // The input layer paints nothing (INPUT_ONLY_THEME); alpha 0 also skips its draw pass.
            // The opacity option applies to the sprite, which is what the player sees.
            pad.view.alpha = 0f
            pad.sprite.alpha =
                if (p.visible) pads.opacity else pads.opacity * HIDDEN_EDITOR_ALPHA

            // The sprite: exactly the DRAWN control, centred on the press target.
            val slp = pad.sprite.layoutParams as FrameLayout.LayoutParams
            slp.width = drawnPx
            slp.height = drawnPx
            slp.leftMargin = (hitPx - drawnPx) / 2
            slp.topMargin = (hitPx - drawnPx) / 2
            pad.sprite.layoutParams = slp

            // A cap, not a size: with zero margins `usable` IS boxPx, so this pins the dial to the
            // box. Setting it LOWER would shrink the dial without shrinking the view and silently
            // invalidate the 0.38 ratio.
            pad.view.primaryDialMaxSizeDp = boxDp

            // The RadialGamePad, centred in the wrapper and hanging out of it.
            val vlp = pad.view.layoutParams as FrameLayout.LayoutParams
            vlp.width = boxPx
            vlp.height = boxPx
            vlp.leftMargin = (hitPx - boxPx) / 2
            vlp.topMargin = (hitPx - boxPx) / 2
            pad.view.layoutParams = vlp

            // The wrapper is what gets positioned, and what the edge rule applies to.
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
     * as (min, max) -- the same quantity [place] clamps, or the editor would place controls the game
     * then moves.
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
