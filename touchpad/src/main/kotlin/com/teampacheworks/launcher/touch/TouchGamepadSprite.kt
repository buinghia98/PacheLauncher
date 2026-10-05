package com.teampacheworks.launcher.touch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.KeyEvent
import android.view.View
import androidx.annotation.DrawableRes
import com.swordfish.radialgamepad.library.config.RadialGamePadTheme
import com.swordfish.radialgamepad.library.event.Event
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The VISIBLE half of one on-screen control: Kenney "Mobile Controls" Style C bodies with Icons /
 * text labels on top (CC0, www.kenney.nl -- see `touchpad/KENNEY-LICENSE.txt`).
 *
 * RadialGamePad is the whole INPUT layer -- touch tracking, stick maths, the cross's diagonals,
 * multi-press, haptics and the event stream the [TouchGamepadSink] is fed from -- but it draws
 * nothing (see [TouchGamepadLayout.INPUT_ONLY_THEME]). This view sits under it in the same
 * [TouchGamepadLayout.HitBox], is sized to the DRAWN control by `place()`, never takes a touch, and
 * mirrors the library's pressed state from the very events that are dispatched to the sink
 * ([onEvent]).
 *
 * The palette is [TouchGamepadConfig.theme], with the roles the library itself gave those colours:
 * body fill `normalColor`, pressed fill `pressedColor`, stick well `backgroundColor`, outline and
 * label `textColor`. Pressed looks like the library's own pressed state -- the body fill switches
 * to the light `pressedColor` and the outline/label are muted into it ([PRESSED_LINE_COLOR]).
 * Nothing glows and nothing changes size.
 *
 * Memory: every drawable is a VectorDrawable built once per control; a VectorDrawable caches one
 * bitmap the size of its bounds, which is why the d-pad arms are cropped to their own boxes (see
 * `touchpad/tools/kenney_controls_to_vector.py`). [onDraw] allocates nothing.
 */
@SuppressLint("ViewConstructor")
class TouchGamepadSprite(
    context: Context,
    private val skin: Skin,
    theme: RadialGamePadTheme
) : View(context) {

    /**
     * What a control looks like AND which ids it emits: [TouchGamepadLayout] derives the
     * RadialGamePad input configuration from the same object ([TouchGamepadLayout.inputConfig]), so
     * the visible half and the input half cannot disagree about which button is where.
     */
    sealed class Skin {
        /** One button: a Style C [body] with a [label] on top. */
        class Single(val id: Int, val body: Body, val label: Label) : Skin()
        /** A diamond of buttons, in RadialGamePad's dial order -- index i sits at `i * 2PI / n`. */
        class Face(val buttons: List<FaceButton>) : Skin()
        /** The d-pad: `dpad_separate`, one arm per direction. */
        class Cross(val id: Int) : Skin()
        /** A stick: pad + nub; [pressId] is the stick click (L3/R3). */
        class Stick(val id: Int, val pressId: Int) : Skin()
    }

    /** [label] is what RadialGamePad's accessibility node announces; [icon] is what is drawn. */
    class FaceButton(val id: Int, val label: String, @DrawableRes val icon: Int)

    /**
     * A Style C button body. [heightRatio] is the sprite's height / width; a body narrower than the
     * square press target is centred vertically in it -- the target itself is not changed.
     */
    enum class Body(@DrawableRes val line: Int, @DrawableRes val fill: Int, val heightRatio: Float) {
        CIRCLE(R.drawable.pl_kenney_button_circle, R.drawable.pl_kenney_button_circle_fill, 1f),
        WIDE(R.drawable.pl_kenney_button_circle_wide, R.drawable.pl_kenney_button_circle_wide_fill, .5f)
    }

    sealed class Label {
        class Icon(@DrawableRes val res: Int) : Label()
        /** [fitAs] is the widest text of the group, so siblings (LB/LT/RT/RB) get one text size. */
        class Text(val text: String, val fitAs: String = text) : Label()
    }

    private enum class Role { FILL, FILL_BACK, LINE }

    /** One drawable at a fixed fraction of the view; [slot] is its bit in [pressedMask]. */
    private class Layer(
        val drawable: Drawable, val role: Role, val slot: Int,
        val l: Float, val t: Float, val r: Float, val b: Float, val moves: Boolean = false
    )

    private val fillNormal = PorterDuffColorFilter(theme.normalColor, PorterDuff.Mode.SRC_IN)
    private val fillBack = PorterDuffColorFilter(theme.backgroundColor, PorterDuff.Mode.SRC_IN)
    private val fillPressed = PorterDuffColorFilter(theme.pressedColor, PorterDuff.Mode.SRC_IN)
    private val lineNormal = PorterDuffColorFilter(theme.textColor, PorterDuff.Mode.SRC_IN)
    private val linePressed = PorterDuffColorFilter(PRESSED_LINE_COLOR, PorterDuff.Mode.SRC_IN)

    private val layers = ArrayList<Layer>()
    private var pressedMask = 0
    private var nubX = 0f
    private var nubY = 0f

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        when (skin) {
            is Skin.Single -> {
                val h = skin.body.heightRatio
                val top = (1f - h) / 2f
                body(skin.body, 0, 0f, top, 1f, top + h)
                when (val label = skin.label) {
                    is Label.Icon ->
                        centred(vector(label.res), Role.LINE, 0, ICON_SCALE, 0f, top, 1f, top + h)
                    is Label.Text -> {
                        // Text box: the inner width of the body, and a cap height that reads at 61 dp
                        // without touching the outline (Style C's stroke is 6/64 of the body).
                        val textH = if (skin.body == Body.CIRCLE) TEXT_CAP_CIRCLE else TEXT_CAP_WIDE
                        layers += Layer(
                            TextLabel(label.text, label.fitAs), Role.LINE, 0,
                            .5f - TEXT_WIDTH / 2, .5f - textH / 2, .5f + TEXT_WIDTH / 2, .5f + textH / 2
                        )
                    }
                }
            }
            is Skin.Face -> {
                // PrimaryButtonsDial.measure()/computeButtonRadius() in 2.0.0, as fractions of the
                // box: R = 0.95 / 2; raw r = R sin(PI/n) / (1 + sin(PI/n)); centre distance R - r;
                // drawn radius 0.8 r. For n = 4: distance 0.2783, diameter 0.3148 of the dial.
                val n = skin.buttons.size.coerceAtLeast(2)
                val outer = .95f / 2f
                val s = sin(PI / n).toFloat()
                val raw = outer * s / (1f + s)
                val dist = outer - raw
                val radius = raw * .8f
                skin.buttons.forEachIndexed { i, button ->
                    val a = i * 2.0 * PI / skin.buttons.size
                    val cx = .5f + dist * cos(a).toFloat()
                    val cy = .5f - dist * sin(a).toFloat()
                    val l = cx - radius; val t = cy - radius; val r = cx + radius; val b = cy + radius
                    body(Body.CIRCLE, i, l, t, r, b)
                    centred(vector(button.icon), Role.LINE, i, ICON_SCALE, l, t, r, b)
                }
            }
            is Skin.Cross -> ARMS.forEachIndexed { slot, arm ->
                layers += Layer(vector(arm.fill), Role.FILL, slot, arm.l, arm.t, arm.r, arm.b)
                layers += Layer(vector(arm.line), Role.LINE, slot, arm.l, arm.t, arm.r, arm.b)
            }
            is Skin.Stick -> {
                layers += Layer(
                    vector(R.drawable.pl_kenney_joystick_circle_pad_a_fill), Role.FILL_BACK,
                    SLOT_STICK_CLICK, 0f, 0f, 1f, 1f
                )
                layers += Layer(
                    vector(R.drawable.pl_kenney_joystick_circle_pad_a), Role.LINE,
                    SLOT_STICK_CLICK, 0f, 0f, 1f, 1f
                )
                // StickDial draws its nub at half the dial's diameter, as does the Kenney pair.
                val lo = .5f - NUB_DIAMETER / 2; val hi = .5f + NUB_DIAMETER / 2
                layers += Layer(
                    vector(R.drawable.pl_kenney_joystick_circle_nub_a_fill), Role.FILL,
                    SLOT_STICK_TOUCH, lo, lo, hi, hi, moves = true
                )
                layers += Layer(
                    vector(R.drawable.pl_kenney_joystick_circle_nub_a), Role.LINE,
                    SLOT_STICK_TOUCH, lo, lo, hi, hi, moves = true
                )
            }
        }
        refreshFilters()
    }

    private fun vector(@DrawableRes res: Int): Drawable =
        requireNotNull(context.getDrawable(res)) { "missing drawable $res" }.mutate()

    private fun body(body: Body, slot: Int, l: Float, t: Float, r: Float, b: Float) {
        layers += Layer(vector(body.fill), Role.FILL, slot, l, t, r, b)
        layers += Layer(vector(body.line), Role.LINE, slot, l, t, r, b)
    }

    private fun centred(
        d: Drawable, role: Role, slot: Int, scale: Float, l: Float, t: Float, r: Float, b: Float
    ) {
        val cx = (l + r) / 2; val cy = (t + b) / 2
        val hw = (r - l) * scale / 2; val hh = (b - t) * scale / 2
        layers += Layer(d, role, slot, cx - hw, cy - hh, cx + hw, cy + hh)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        for (layer in layers) {
            layer.drawable.setBounds(
                (layer.l * w).roundToInt(), (layer.t * h).roundToInt(),
                (layer.r * w).roundToInt(), (layer.b * h).roundToInt()
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        val dx = nubX * NUB_TRAVEL * width
        val dy = nubY * NUB_TRAVEL * height
        for (layer in layers) {
            if (layer.moves && (dx != 0f || dy != 0f)) {
                val save = canvas.save()
                canvas.translate(dx, dy)
                layer.drawable.draw(canvas)
                canvas.restoreToCount(save)
            } else {
                layer.drawable.draw(canvas)
            }
        }
    }

    /**
     * Mirror one RadialGamePad event. Called with every event BEFORE it is dispatched to the sink,
     * so what the player sees pressed is exactly what the game was told.
     */
    fun onEvent(event: Event) {
        when (skin) {
            is Skin.Single ->
                if (event is Event.Button && event.id == skin.id) setSlot(0, event.isDown())
            is Skin.Face -> if (event is Event.Button) {
                val i = skin.buttons.indexOfFirst { it.id == event.id }
                if (i >= 0) setSlot(i, event.isDown())
            }
            is Skin.Cross -> if (event is Event.Direction && event.id == skin.id) {
                // The same threshold the overlay dispatches with: a diagonal lights two arms.
                val t = TouchGamepadLayout.DPAD_THRESHOLD
                var mask = 0
                if (event.yAxis < -t) mask = mask or (1 shl SLOT_UP)
                if (event.xAxis > t) mask = mask or (1 shl SLOT_RIGHT)
                if (event.yAxis > t) mask = mask or (1 shl SLOT_DOWN)
                if (event.xAxis < -t) mask = mask or (1 shl SLOT_LEFT)
                setMask(mask)
            }
            is Skin.Stick -> when {
                event is Event.Direction && event.id == skin.id -> {
                    var x = event.xAxis; var y = event.yAxis
                    // NaN is a value a stick emits (see TouchGamepadOverlay.scaled): centre it.
                    if (x.isNaN() || y.isNaN()) { x = 0f; y = 0f }
                    val len = sqrt(x * x + y * y)
                    if (len > 1f) { x /= len; y /= len }
                    if (x != nubX || y != nubY) { nubX = x; nubY = y; invalidate() }
                }
                event is Event.Button && event.id == skin.pressId ->
                    setSlot(SLOT_STICK_CLICK, event.isDown())
            }
        }
    }

    /**
     * A finger went down on / came off this control's press target (from the HitBox). Only the
     * stick uses it: StickDial paints its nub pressed for as long as it is held, and no event says
     * so (a touch dead in the centre emits nothing).
     */
    fun onTouchActive(active: Boolean) {
        if (skin !is Skin.Stick) return
        setSlot(SLOT_STICK_TOUCH, active)
        if (!active && (nubX != 0f || nubY != 0f)) { nubX = 0f; nubY = 0f; invalidate() }
    }

    /** Back to idle -- the overlay was paused, hidden, or the sink was told everything is released. */
    fun reset() {
        nubX = 0f; nubY = 0f
        setMask(0)
        invalidate()
    }

    private fun Event.Button.isDown() = action == KeyEvent.ACTION_DOWN

    private fun setSlot(slot: Int, down: Boolean) =
        setMask(if (down) pressedMask or (1 shl slot) else pressedMask and (1 shl slot).inv())

    private fun setMask(mask: Int) {
        if (mask == pressedMask) return
        pressedMask = mask
        refreshFilters()
        invalidate()
    }

    private fun refreshFilters() {
        for (layer in layers) {
            val pressed = pressedMask and (1 shl layer.slot) != 0
            layer.drawable.colorFilter = when (layer.role) {
                Role.FILL -> if (pressed) fillPressed else fillNormal
                Role.FILL_BACK -> if (pressed) fillPressed else fillBack
                Role.LINE -> if (pressed) linePressed else lineNormal
            }
        }
    }

    /** A text label in a heavy geometric sans, sized to fit its bounds; tinted like the icons. */
    private class TextLabel(private val text: String, private val fitAs: String) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = LABEL_TYPEFACE
        }
        private val probe = Rect()
        private var baseline = 0f

        override fun onBoundsChange(bounds: Rect) {
            if (bounds.isEmpty) return
            // Size from [fitAs]: the cap height fills the box unless the width binds first.
            paint.textSize = PROBE_SIZE
            paint.getTextBounds(fitAs, 0, fitAs.length, probe)
            val w = paint.measureText(fitAs)
            paint.textSize =
                PROBE_SIZE * min(bounds.width() / w, bounds.height() / probe.height().toFloat())
            paint.getTextBounds(text, 0, text.length, probe)
            baseline = bounds.exactCenterY() - probe.exactCenterY()
        }

        override fun draw(canvas: Canvas) {
            canvas.drawText(text, bounds.exactCenterX(), baseline, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT

        private companion object { const val PROBE_SIZE = 100f }
    }

    private class Arm(
        @DrawableRes val line: Int, @DrawableRes val fill: Int,
        val l: Float, val t: Float, val r: Float, val b: Float
    )

    companion object {
        /** Outline / label on a pressed control: muted, so it sinks into the light pressed fill. */
        val PRESSED_LINE_COLOR = Color.argb(225, 132, 130, 142)

        /** Roboto at weight 900: the closest system face to Kenney's heavy, round-cornered glyphs. */
        private val LABEL_TYPEFACE: Typeface = Typeface.create(Typeface.SANS_SERIF, 900, false)

        /** Kenney's button icons fill 44/48 of their canvas; half the body leaves a clear ring. */
        private const val ICON_SCALE = .5f
        private const val TEXT_WIDTH = .62f
        private const val TEXT_CAP_CIRCLE = .30f
        private const val TEXT_CAP_WIDE = .17f

        private const val NUB_DIAMETER = .5f
        /** Nub travel at full deflection, as a fraction of the dial: keeps it inside the pad ring. */
        private const val NUB_TRAVEL = .2f

        private const val SLOT_UP = 0
        private const val SLOT_RIGHT = 1
        private const val SLOT_DOWN = 2
        private const val SLOT_LEFT = 3
        private const val SLOT_STICK_TOUCH = 0
        private const val SLOT_STICK_CLICK = 1

        /**
         * The d-pad arms, in SLOT order. The windows are the crop boxes the converter prints and
         * writes into each XML header: (39, 0)-(89, 59) of the 128-unit canvas, rotated.
         */
        private val ARMS = listOf(
            Arm(
                R.drawable.pl_kenney_dpad_separate_north, R.drawable.pl_kenney_dpad_separate_north_fill,
                39 / 128f, 0f, 89 / 128f, 59 / 128f
            ),
            Arm(
                R.drawable.pl_kenney_dpad_separate_east, R.drawable.pl_kenney_dpad_separate_east_fill,
                69 / 128f, 39 / 128f, 1f, 89 / 128f
            ),
            Arm(
                R.drawable.pl_kenney_dpad_separate_south, R.drawable.pl_kenney_dpad_separate_south_fill,
                39 / 128f, 69 / 128f, 89 / 128f, 1f
            ),
            Arm(
                R.drawable.pl_kenney_dpad_separate_west, R.drawable.pl_kenney_dpad_separate_west_fill,
                0f, 39 / 128f, 59 / 128f, 89 / 128f
            )
        )
    }
}
