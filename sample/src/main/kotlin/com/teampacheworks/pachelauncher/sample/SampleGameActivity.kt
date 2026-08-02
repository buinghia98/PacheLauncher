package com.teampacheworks.pachelauncher.sample

import android.app.Activity
import android.graphics.Color
import android.hardware.input.InputManager
import android.os.Bundle
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.teampacheworks.launcher.LauncherContract
import com.teampacheworks.launcher.input.ControllerRemap
import com.teampacheworks.launcher.input.GamepadKeyPolicy
import com.teampacheworks.launcher.log.LauncherLog
import com.teampacheworks.launcher.log.LogcatPump
import com.teampacheworks.launcher.ui.AspectFit
import com.teampacheworks.launcher.ui.DoubleBackToExitHandler
import com.teampacheworks.launcher.ui.FpsOverlayLayout

/**
 * A stand-in for a real game engine's Activity (docs/INTEGRATION.md §5). There is no actual game
 * here - just a coloured box representing where an engine's rendering surface would go - but every
 * piece of wiring below (aspect letterboxing, the FPS overlay's offset, double-back-to-exit,
 * fallback-key swallowing, gamepad remap) is the real library code a real port would call.
 */
class SampleGameActivity : Activity(), InputManager.InputDeviceListener {

    private lateinit var root: FrameLayout
    private lateinit var gameSurface: View
    private lateinit var fpsLabel: TextView
    private lateinit var doubleBack: DoubleBackToExitHandler

    private var remap: ControllerRemap? = null
    private var showFps = false
    private var aspectRatio: Float? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val aspect = intent.getStringExtra(LauncherContract.EXTRA_ASPECT)
        showFps = intent.getBooleanExtra(LauncherContract.EXTRA_SHOW_FPS, false)
        val debug = intent.getBooleanExtra(LauncherContract.EXTRA_DEBUG_LOG, false)
        LauncherLog.enabled = debug
        aspectRatio = AspectFit.ratioOf(aspect)

        if (debug) LogcatPump.start(this, "game")

        root = FrameLayout(this)
        gameSurface = View(this).apply { setBackgroundColor(Color.rgb(0x28, 0x4A, 0x7C)) }
        root.addView(gameSurface, FrameLayout.LayoutParams(0, 0))

        fpsLabel = TextView(this).apply {
            setTextColor(Color.parseColor("#00E63C"))
            text = "-- FPS"
            visibility = if (showFps) View.VISIBLE else View.GONE
        }
        root.addView(
            fpsLabel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            )
        )
        setContentView(root)

        root.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = r - l
            val h = b - t
            if (w > 0 && h > 0) layoutForSize(w, h)
        }

        doubleBack = DoubleBackToExitHandler(
            onFirstPress = {
                Toast.makeText(this, com.teampacheworks.launcher.R.string.pl_back_again_to_exit, Toast.LENGTH_SHORT).show()
            },
            onConfirmedExit = { finish() }
        )

        getSystemService(InputManager::class.java)?.registerInputDeviceListener(this, null)
    }

    override fun onDestroy() {
        getSystemService(InputManager::class.java)?.unregisterInputDeviceListener(this)
        super.onDestroy()
    }

    private fun layoutForSize(containerW: Int, containerH: Int) {
        val (w, h) = AspectFit.apply(gameSurface, containerW, containerH, aspectRatio)
        if (showFps) {
            val (marginRight, marginTop) = FpsOverlayLayout.margins(containerW, containerH, w, h, dp(8))
            (fpsLabel.layoutParams as FrameLayout.LayoutParams).apply {
                rightMargin = marginRight
                topMargin = marginTop
            }
            fpsLabel.requestLayout()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------- input

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Swallow synthesized fallback events first - see GamepadKeyPolicy's KDoc for why this
        // must run before any real-BACK handling.
        if (GamepadKeyPolicy.isFallback(event.flags)) return true

        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            doubleBack.onBackPressed()
            return true
        }

        val mapped = remap?.remapKey(event) ?: event
        val consumed = super.dispatchKeyEvent(mapped)
        return consumed || GamepadKeyPolicy.isGamepadSource(event.source)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val mapped = remap?.remapMotion(event) ?: event
        return super.onGenericMotionEvent(mapped)
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        val device = InputDevice.getDevice(deviceId) ?: return
        ControllerRemap.forDevice(this, device, LauncherLog.enabled)?.let { remap = it }
    }

    override fun onInputDeviceRemoved(deviceId: Int) {}

    override fun onInputDeviceChanged(deviceId: Int) {}
}
