package com.teampacheworks.launcher.touch

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

/**
 * The on-screen gamepad's layout editor: the real controls, at the real size, over a black screen.
 *
 * Reached from a [com.teampacheworks.launcher.LauncherOptionScreen]'s action button -- that field
 * exists for exactly this shape of feature, one whose remaining settings are continuous and cannot
 * honestly be a dropdown.
 *
 * WHY IT IS HERE AND NOT OVER THE RUNNING GAME
 *
 * The tempting argument is that the only honest preview of a pad is the pad over the thing it will
 * cover. It loses to a plainer fact about the ports this library serves: they have no pause the
 * launcher can drive and no overlay mechanism, so editor chrome over the game is chrome over LIVE
 * gameplay -- a tap target sitting on top of a session that cannot be suspended. The layout is
 * decided before you play, so it is edited before you play.
 *
 * What is kept from that argument is the geometry: this screen builds its controls through
 * [TouchGamepadLayout], the same code and the same numbers the overlay uses -- the same configs, the
 * same theme, the same fraction-of-the-screen placement, and the same opacity/size the launcher's
 * rows currently say ([TouchGamepadSettings.resolveFromLauncherPrefs]). The window is fullscreen
 * with the system bars hidden for the same reason: the game's window is, and a preview measured
 * against a shorter screen would put every control in the wrong place.
 *
 * THE SHAPE OF THE SCREEN
 *
 * Every control is independent, so:
 *
 * * a control is dragged BY TOUCHING IT -- each control's own press target is the drag handle, so
 *   there is nothing to explain about which half of the screen drives what;
 * * touching one also SELECTS it, and there is exactly ONE size slider, labelled with the selected
 *   control's name. Ten sliders would be a wall, and a slider with no name on it would be a guess;
 * * resetting is three things and not one. Size and position are two edits made by two different
 *   gestures, and a single Reset that undid both for every control would make "put this one back
 *   where it was" cost the nine controls that were already right. The two narrow resets act on the
 *   SELECTED control alone; Reset all is the whole-pad one.
 *
 * It runs in the LAUNCHER process (no `android:process` on its manifest entry), which is why what it
 * saves goes to a file both processes read rather than to SharedPreferences -- see
 * [TouchGamepadSettings]' process note.
 */
class TouchGamepadEditorActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "pl/touchpad"
    }

    private lateinit var settings: TouchGamepadSettings.Settings
    private lateinit var pads: TouchGamepadLayout.Pads

    /** Live placements, keyed by [TouchGamepadLayout.Control.key]. Saved on the way out. */
    private val placements = HashMap<String, TouchGamepadSettings.Placement>()

    /** The most recently touched control -- what the one slider is about. Null until first touch. */
    private var selected: TouchGamepadLayout.Control? = null

    private lateinit var selectedLabel: TextView
    private lateinit var sizeSlider: Slider

    /** The two per-selection resets. Dead until a control is selected, like the slider beside them. */
    private lateinit var resetSizeButton: MaterialButton
    private lateinit var resetButton: MaterialButton

    /** Set while the slider is being written programmatically, so it does not echo back a change. */
    private var suppressSlider = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goFullscreen()

        settings = TouchGamepadSettings.resolveFromLauncherPrefs(this)
        placements.putAll(settings.placements)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        pads = TouchGamepadLayout.build(this)
        root.addView(
            pads.container,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        for (pad in pads.pads) pad.hit.setOnTouchListener(dragHandler(pad))

        root.addView(
            buildPanel(root),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = TouchGamepadLayout.dp(this@TouchGamepadEditorActivity, 12f) }
        )

        setContentView(root)
        applyGeometry()
        Log.i(
            TAG, "layout editor opened (opacity=${settings.opacity} " +
                "sizeDp=${settings.baseSizeDp} controls=${pads.pads.size})"
        )
    }

    /**
     * Immersive, bars hidden, drawing edge to edge. The game's own window is fullscreen; a preview
     * laid out against a window that still had a status bar would place every control a bar's height
     * too low, and the error would be invisible until the player was in a session.
     */
    private fun goFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun buildPanel(parent: ViewGroup): View {
        val panel = layoutInflater.inflate(R.layout.pl_pad_editor_panel, parent, false)
        selectedLabel = panel.findViewById(R.id.pl_pad_editor_selected)
        sizeSlider = panel.findViewById(R.id.pl_pad_editor_size)
        resetSizeButton = panel.findViewById(R.id.pl_pad_editor_reset_size)
        resetButton = panel.findViewById(R.id.pl_pad_editor_reset)

        sizeSlider.addOnChangeListener { _, value, _ ->
            if (suppressSlider) return@addOnChangeListener
            val control = selected ?: return@addOnChangeListener
            placements[control.key] = placement(control).copy(scale = value)
            applyGeometry()
        }

        // Three resets, at three scopes, and each one sits next to what it undoes: the icon by the
        // slider undoes the slider, the text button by Done undoes the drag, and Reset all -- the
        // only one that can throw away work you cannot see on screen -- is off on its own.
        resetSizeButton.setOnClickListener {
            val control = selected ?: return@setOnClickListener
            placements[control.key] =
                placement(control).copy(scale = TouchGamepadSettings.defaultPlacement(control).scale)
            showSelection(control)
            applyGeometry()
        }
        resetButton.setOnClickListener {
            val control = selected ?: return@setOnClickListener
            val default = TouchGamepadSettings.defaultPlacement(control)
            placements[control.key] = placement(control)
                .copy(xFraction = default.xFraction, yFraction = default.yFraction)
            applyGeometry()
        }
        panel.findViewById<MaterialButton>(R.id.pl_pad_editor_reset_all).setOnClickListener {
            placements.clear()
            placements.putAll(TouchGamepadSettings.defaultPlacements())
            TouchGamepadSettings.clearLayout(this)
            selected?.let { showSelection(it) }
            applyGeometry()
        }
        panel.findViewById<MaterialButton>(R.id.pl_pad_editor_done).setOnClickListener { finish() }
        return panel
    }

    private fun placement(control: TouchGamepadLayout.Control) =
        placements[control.key] ?: TouchGamepadSettings.defaultPlacement(control)

    /**
     * One control's drag handle: its own press target.
     *
     * Returning true from every event is what keeps [com.swordfish.radialgamepad.library.RadialGamePad]
     * from ever seeing a press here -- `View.dispatchTouchEvent` consults the listener first, so
     * `onTouchEvent` is never reached. This screen must not press a button; there is no game process
     * in the launcher to press one on.
     *
     * The delta is applied to the placement FRACTIONS rather than to the view's position, so what
     * the finger moves and what gets saved are the same number, and the clamp
     * ([TouchGamepadLayout.centreRange]) is the only edge rule. An editor that clamped differently
     * from the game would place controls the game then moves.
     *
     * The listener is on the PRESS TARGET (`pad.hit`), NOT on the RadialGamePad, because that is the
     * view the container positions and the one whose bounds decide what this screen can grab.
     * Dragging a button by its visible face is also what a player expects; a listener on the pad
     * would let them grab it anywhere in an invisible box 2.63x its size.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun dragHandler(pad: TouchGamepadLayout.Pad): View.OnTouchListener {
        var lastX = 0f
        var lastY = 0f
        return View.OnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = ev.rawX
                    lastY = ev.rawY
                    showSelection(pad.control)
                    applyGeometry()
                }
                MotionEvent.ACTION_MOVE -> {
                    val w = pads.container.width
                    val h = pads.container.height
                    if (w > 0 && h > 0) {
                        val current = placement(pad.control)
                        val xr = TouchGamepadLayout.centreRange(view.width, w)
                        val yr = TouchGamepadLayout.centreRange(view.height, h)
                        placements[pad.control.key] = current.copy(
                            xFraction = (current.xFraction + (ev.rawX - lastX) / w)
                                .coerceIn(xr.start, xr.endInclusive),
                            yFraction = (current.yFraction + (ev.rawY - lastY) / h)
                                .coerceIn(yr.start, yr.endInclusive)
                        )
                        lastX = ev.rawX
                        lastY = ev.rawY
                        applyGeometry()
                    }
                }
            }
            true
        }
    }

    /** Point the one slider at [control] and say so above it. */
    private fun showSelection(control: TouchGamepadLayout.Control) {
        selected = control
        val name = getString(control.nameRes)
        selectedLabel.text = getString(R.string.pl_pad_editor_size, name)
        suppressSlider = true
        sizeSlider.isEnabled = true
        resetSizeButton.isEnabled = true
        resetButton.isEnabled = true
        // Snapped to the slider's own 0.01 step: Slider.setValue rejects a value off the step grid
        // with an IllegalStateException, and a scale read back from a hand-edited layout file has no
        // reason to be on it.
        sizeSlider.value = ((placement(control).scale * 100f).roundToInt() / 100f)
            .coerceIn(TouchGamepadSettings.SCALE_MIN, TouchGamepadSettings.SCALE_MAX)
        suppressSlider = false
    }

    /**
     * Re-place every control, then lift the selected one to full opacity so it is obvious which one
     * the slider is about. A ring or a glow would need a custom drawable over a GPL library's view;
     * opacity is already a property this screen owns.
     */
    private fun applyGeometry() {
        TouchGamepadLayout.apply(this, pads, settings, placements)
        val key = selected?.key ?: return
        pads.pad(key)?.view?.alpha = 1f
    }

    /**
     * Saved on the way out, on every exit path -- Done, the system Back gesture and a swipe away
     * alike. There is no Apply button, for the same reason the launcher's mod screen has none: what
     * matters is what the NEXT launch reads, and a screen the system can close must not be able to
     * lose a change.
     */
    override fun onPause() {
        super.onPause()
        TouchGamepadSettings.save(this, placements)
    }
}
