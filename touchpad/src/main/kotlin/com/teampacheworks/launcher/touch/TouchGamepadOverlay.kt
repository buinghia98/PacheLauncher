package com.teampacheworks.launcher.touch

import android.app.Activity
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.swordfish.radialgamepad.library.event.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The on-screen gamepad itself: the [TouchGamepadLayout] controls drawn over whatever surface the
 * game renders to, wired to a [TouchGamepadSink].
 *
 * This is the only file in the module that turns a touch into an input, and it is deliberately thin.
 * Everything it does beyond forwarding is one of three things a naive forwarder gets wrong: the two
 * triggers are axes and not buttons, the d-pad is four buttons and not an axis pair, and a stick can
 * emit NaN.
 *
 * NO CHROME IS DRAWN OVER THE GAME. There is no gear, no handle and no editor here. The layout is
 * edited in the launcher ([TouchGamepadEditorActivity]) before the game starts, because a port of
 * this shape typically has no pause it can drive and no overlay mechanism to hang UI off -- every
 * frame a gear is on screen is a frame of live gameplay with a tap target sitting over it.
 *
 * LICENSING. RadialGamePad is **GPL-3.0**, not Apache-2.0 -- every source file carries the GPL
 * header and the repository's LICENSE is GPL v3. Linking it makes the APK a derived work under the
 * GPL's terms *on distribution*. That is why this is a separate Gradle module (see
 * touchpad/build.gradle.kts) rather than part of `:launcher`, and it is a constraint a host must
 * settle before shipping the APK to anyone.
 */
object TouchGamepadOverlay {

    private const val TAG = "pl/touchpad"

    private const val AXIS_MAX = 32767

    private var scope: CoroutineScope? = null
    private var root: FrameLayout? = null
    private var pads: TouchGamepadLayout.Pads? = null
    private var sink: TouchGamepadSink? = null
    private var visible = true

    // -------------------------------------------------------------------------------------------

    /**
     * Add the overlay to [activity]'s view tree.
     *
     * Call it AFTER `super.onCreate` (the game's content view has to exist first) and after whatever
     * the host does to make a virtual joystick exist on the other end of [sink].
     *
     * Does nothing when the resolved settings say the overlay is off, or when the host never
     * installed a [TouchGamepadConfig], so the caller does not have to branch on either.
     */
    @JvmStatic
    fun attach(activity: Activity, resolved: TouchGamepadSettings.Settings, sink: TouchGamepadSink) {
        if (TouchGamepadHost.configOrNull == null) {
            Log.i(TAG, "no TouchGamepadConfig installed -- nothing added to the view tree")
            return
        }
        if (!resolved.enabled) {
            Log.i(TAG, "overlay disabled for this launch -- nothing added to the view tree")
            return
        }
        if (root != null) {
            Log.w(TAG, "attach() called twice -- ignoring the second")
            return
        }

        val built = TouchGamepadLayout.build(activity)
        pads = built
        root = built.container
        this.sink = sink

        // The overlay must not swallow touches it has no control in. RadialGamePad's onTouchEvent
        // returns true unconditionally, so anything inside a pad's VIEW rectangle would be consumed
        // whether or not it hit a dial -- but what is placed here is the PRESS TARGET wrapper (see
        // TouchGamepadLayout.Pad), sized to the control, and this container is a plain FrameLayout
        // with nothing clickable on it. Every touch that misses all the targets falls through to
        // the game's surface underneath.
        activity.addContentView(
            built.container,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        TouchGamepadLayout.apply(activity, built, resolved)

        val sc = CoroutineScope(Dispatchers.Main.immediate)
        scope = sc
        for (pad in built.pads) {
            sc.launch { pad.view.events().collect { dispatch(it) } }
        }

        Log.i(TAG, "overlay attached (${built.pads.size} controls)")
    }

    @JvmStatic
    fun detach() {
        scope?.cancel()
        scope = null
        (root?.parent as? ViewGroup)?.removeView(root)
        root = null
        pads = null
        sink = null
        visible = true
    }

    // -------------------------------------------------------------------------------------------
    // Event -> sink
    // -------------------------------------------------------------------------------------------

    private fun dispatch(e: Event) {
        if (!visible) return
        val out = sink ?: return
        when (e) {
            is Event.Button -> {
                val down = e.action == KeyEvent.ACTION_DOWN
                when (e.id) {
                    // Drawn as buttons, but SDL_CONTROLLER_AXIS_TRIGGER* are axes with a 0..32767
                    // range (not -32768..32767). A digital on-screen trigger is therefore full
                    // deflection or nothing, which is how a menu or a dash-cancel reads them anyway.
                    TouchGamepadLayout.ID_LT ->
                        out.axis(TouchGamepadLayout.SdlAxis.TRIGGER_LEFT, if (down) AXIS_MAX else 0)
                    TouchGamepadLayout.ID_RT ->
                        out.axis(TouchGamepadLayout.SdlAxis.TRIGGER_RIGHT, if (down) AXIS_MAX else 0)
                    else -> out.button(e.id, down)
                }
            }
            is Event.Direction -> when (e.id) {
                TouchGamepadLayout.ID_LEFT_STICK -> {
                    out.axis(TouchGamepadLayout.SdlAxis.LEFT_X, scaled(e.xAxis))
                    out.axis(TouchGamepadLayout.SdlAxis.LEFT_Y, scaled(e.yAxis))
                }
                TouchGamepadLayout.ID_RIGHT_STICK -> {
                    out.axis(TouchGamepadLayout.SdlAxis.RIGHT_X, scaled(e.xAxis))
                    out.axis(TouchGamepadLayout.SdlAxis.RIGHT_Y, scaled(e.yAxis))
                }
                // The d-pad is four BUTTONS on an SDL game controller, never an axis pair, so the
                // cross's direction vector is decomposed here. Diagonals set two buttons, which is
                // exactly what a real pad's hat produces after SDL's mapping.
                TouchGamepadLayout.ID_DPAD -> {
                    val b = TouchGamepadLayout.SdlButton
                    out.button(b.DPAD_LEFT, e.xAxis < -0.5f)
                    out.button(b.DPAD_RIGHT, e.xAxis > 0.5f)
                    out.button(b.DPAD_UP, e.yAxis < -0.5f)
                    out.button(b.DPAD_DOWN, e.yAxis > 0.5f)
                }
            }
            // Gestures are not configured on any control here; nothing to do.
            else -> Unit
        }
    }

    /*
     * NaN IS A VALUE RadialGamePad ACTUALLY EMITS, and roundToInt() throws on it:
     *
     *   java.lang.IllegalArgumentException: Cannot round NaN value.
     *
     * -- on the UI thread, which takes the whole game process with it and the player's session with
     * that. In the reference port it arrived from the left stick mid-session on a device running at
     * 64 fps with nothing else wrong.
     *
     * A direction event is a normalised vector, and normalising the zero vector is 0/0. Whatever the
     * exact path inside the pad library, a stick can report a touch whose displacement rounds to
     * nothing, and NaN is what comes out.
     *
     * coerceIn() does NOT filter it: every comparison against NaN is false, so `this < min` and
     * `this > max` both fail and the value is returned unchanged. That is the whole trap -- the
     * clamp reads like it makes the range safe and it only makes the RANGE safe.
     *
     * Zero is the right answer rather than "hold the last value": the event means the stick has no
     * direction, and centring it is what a real pad reports there.
     *
     * The d-pad branch above needs no such guard, and for the same reason in reverse: its four
     * comparisons against NaN are all false, so every direction comes back released, which is
     * already what a centred stick should do.
     */
    private fun scaled(v: Float): Int =
        if (v.isNaN()) 0 else (v.coerceIn(-1f, 1f) * AXIS_MAX).roundToInt()

    /**
     * Release everything. Called when the overlay is hidden, so a finger that was holding a
     * direction when the view went away does not leave the game walking into a wall forever --
     * RadialGamePad emits no release event for a control that is removed under it.
     */
    private fun releaseAll() {
        val out = sink ?: return
        for (b in 0..14) out.button(b, false)
        for (a in 0..5) out.axis(a, 0)
    }

    /**
     * Runtime show/hide.
     *
     * Whatever the host attached on the other end of the sink STAYS attached either way -- a guest
     * that enumerated its joysticks once at startup would never pick one up again -- so hiding is a
     * view-tree and event-suppression change only, and re-showing needs no relaunch.
     */
    @JvmStatic
    fun setVisible(show: Boolean) {
        visible = show
        pads?.container?.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) releaseAll()
        Log.i(TAG, "overlay ${if (show) "shown" else "hidden"}")
    }
}
