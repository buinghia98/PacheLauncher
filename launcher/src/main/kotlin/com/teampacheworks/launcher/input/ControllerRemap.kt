package com.teampacheworks.launcher.input

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.teampacheworks.launcher.log.LauncherLog

/**
 * Rewrites a non-standard gamepad's events into the standard X360-style layout many game engines
 * expect, before they reach the host's own input handling (design spec §5).
 *
 * How the pieces line up:
 *  - `gamecontrollerdb.txt` says, for one GUID, which **SDL** element sits on which **SDL** input:
 *    `a:b0` means "SDL's A button is SDL button index 0".
 *  - SDL's button index for an Android key event comes from
 *    `SDL_sysjoystick.c :: keycode_to_SDL` ([SDL_BUTTON_OF]); its `aN` index is the N-th entry of
 *    [SdlGuid.sortedAxes].
 *  - [X360_KEY]/[X360_AXIS] are the ordinary Android X360-style keycodes/axes most engines already
 *    understand as "a standard gamepad".
 *
 * So: android input → SDL index → db element → android X360 output.
 *
 * **Unknown device → pass through unchanged.** So does any binding form this layer does not model
 * (half axes `-a1`, inverted `a2~`, hats `h0.1`, button↔axis crossovers): a device that is already
 * standard must not be "fixed".
 */
class ControllerRemap private constructor(
    val deviceId: Int,
    val deviceName: String,
    val entryName: String,
    /** android keycode -> android keycode; only entries that actually differ. */
    private val keyMap: Map<Int, Int>,
    /** android axis -> android axis; only entries that actually differ. */
    private val axisMap: Map<Int, Int>
) {

    val isIdentity: Boolean get() = keyMap.isEmpty() && axisMap.isEmpty()

    /** Null when nothing needs changing; otherwise a fresh event the caller must recycle. */
    fun remapKey(event: KeyEvent): KeyEvent? {
        val target = keyMap[event.keyCode] ?: return null
        return KeyEvent(
            event.downTime, event.eventTime, event.action, target, event.repeatCount,
            event.metaState, event.deviceId, event.scanCode, event.flags, event.source
        )
    }

    /** Null when nothing needs changing; otherwise a fresh event the caller must recycle. */
    fun remapMotion(event: MotionEvent): MotionEvent? {
        if (axisMap.isEmpty()) return null
        return try {
            val props = MotionEvent.PointerProperties()
            event.getPointerProperties(0, props)
            val src = MotionEvent.PointerCoords()
            event.getPointerCoords(0, src)
            val dst = MotionEvent.PointerCoords()
            dst.copyFrom(src)
            for ((from, to) in axisMap) dst.setAxisValue(to, src.getAxisValue(from))
            MotionEvent.obtain(
                event.downTime, event.eventTime, event.action, 1,
                arrayOf(props), arrayOf(dst),
                event.metaState, event.buttonState, event.xPrecision, event.yPrecision,
                event.deviceId, event.edgeFlags, event.source, event.flags
            )
        } catch (t: Throwable) {
            null
        }
    }

    companion object {

        /** `SDL_sysjoystick.c :: keycode_to_SDL`, transcribed. */
        val SDL_BUTTON_OF: Map<Int, Int> = buildMap {
            put(KeyEvent.KEYCODE_BUTTON_A, 0)
            put(KeyEvent.KEYCODE_BUTTON_B, 1)
            put(KeyEvent.KEYCODE_BUTTON_X, 2)
            put(KeyEvent.KEYCODE_BUTTON_Y, 3)
            put(KeyEvent.KEYCODE_BUTTON_L1, 9)
            put(KeyEvent.KEYCODE_BUTTON_R1, 10)
            put(KeyEvent.KEYCODE_BUTTON_THUMBL, 7)
            put(KeyEvent.KEYCODE_BUTTON_THUMBR, 8)
            put(KeyEvent.KEYCODE_MENU, 6)
            put(KeyEvent.KEYCODE_BUTTON_START, 6)
            put(KeyEvent.KEYCODE_BUTTON_SELECT, 4)
            put(KeyEvent.KEYCODE_BUTTON_MODE, 5)
            put(KeyEvent.KEYCODE_BUTTON_L2, 15)
            put(KeyEvent.KEYCODE_BUTTON_R2, 16)
            put(KeyEvent.KEYCODE_BUTTON_C, 17)
            put(KeyEvent.KEYCODE_BUTTON_Z, 18)
            put(KeyEvent.KEYCODE_DPAD_UP, 11)
            put(KeyEvent.KEYCODE_DPAD_DOWN, 12)
            put(KeyEvent.KEYCODE_DPAD_LEFT, 13)
            put(KeyEvent.KEYCODE_DPAD_RIGHT, 14)
            put(KeyEvent.KEYCODE_DPAD_CENTER, 0)
            // KEYCODE_BUTTON_1..16 -> 20..35
            for (i in 0..15) put(KeyEvent.KEYCODE_BUTTON_1 + i, 20 + i)
            // KEYCODE_BACK is deliberately absent: a host's double-back-to-exit handling should
            // consume it before this layer ever sees it (see the sample app for the pattern).
        }

        /** The standard X360-style layout most game engines already understand as "a gamepad". */
        val X360_KEY: Map<String, Int> = mapOf(
            "a" to KeyEvent.KEYCODE_BUTTON_A,
            "b" to KeyEvent.KEYCODE_BUTTON_B,
            "x" to KeyEvent.KEYCODE_BUTTON_X,
            "y" to KeyEvent.KEYCODE_BUTTON_Y,
            "back" to KeyEvent.KEYCODE_BUTTON_SELECT,
            "guide" to KeyEvent.KEYCODE_BUTTON_MODE,
            "start" to KeyEvent.KEYCODE_BUTTON_START,
            "leftstick" to KeyEvent.KEYCODE_BUTTON_THUMBL,
            "rightstick" to KeyEvent.KEYCODE_BUTTON_THUMBR,
            "leftshoulder" to KeyEvent.KEYCODE_BUTTON_L1,
            "rightshoulder" to KeyEvent.KEYCODE_BUTTON_R1,
            "lefttrigger" to KeyEvent.KEYCODE_BUTTON_L2,
            "righttrigger" to KeyEvent.KEYCODE_BUTTON_R2,
            "dpup" to KeyEvent.KEYCODE_DPAD_UP,
            "dpdown" to KeyEvent.KEYCODE_DPAD_DOWN,
            "dpleft" to KeyEvent.KEYCODE_DPAD_LEFT,
            "dpright" to KeyEvent.KEYCODE_DPAD_RIGHT
        )

        val X360_AXIS: Map<String, Int> = mapOf(
            "leftx" to MotionEvent.AXIS_X,
            "lefty" to MotionEvent.AXIS_Y,
            "rightx" to MotionEvent.AXIS_Z,
            "righty" to MotionEvent.AXIS_RZ,
            "lefttrigger" to MotionEvent.AXIS_LTRIGGER,
            "righttrigger" to MotionEvent.AXIS_RTRIGGER
        )

        /** Null when the device is unknown to the db, or when the mapping is already standard. */
        fun forDevice(context: Context, device: InputDevice, debug: Boolean): ControllerRemap? {
            val entries = GameControllerDb.load(context)
            if (entries.isEmpty()) return null
            val hit = GameControllerDb.find(entries, device)
            if (debug) {
                LauncherLog.write(
                    "input",
                    "device '${device.name}' vid=${device.vendorId} pid=${device.productId} " +
                        "guid=${SdlGuid.candidates(device).firstOrNull() ?: "-"} " +
                        "mapping=${if (hit == null) "NOT FOUND" else "'${hit.first.name}' via ${hit.second}"}"
                )
            }
            val entry = hit?.first ?: return null

            val axes = SdlGuid.sortedAxes(device)
            val keyMap = HashMap<Int, Int>()
            val axisMap = HashMap<Int, Int>()

            // SDL button index -> the android keycode that produces it on THIS device.
            val androidKeyOfSdlButton = HashMap<Int, Int>()
            for ((keycode, sdl) in SDL_BUTTON_OF) {
                androidKeyOfSdlButton.putIfAbsent(sdl, keycode)
            }

            for ((element, binding) in entry.bindings) {
                when {
                    binding.length >= 2 && binding[0] == 'b' && binding.drop(1).all { it.isDigit() } -> {
                        val sdlButton = binding.drop(1).toIntOrNull() ?: continue
                        val target = X360_KEY[element] ?: continue
                        val source = androidKeyOfSdlButton[sdlButton] ?: continue
                        if (source != target) keyMap[source] = target
                    }
                    binding.length >= 2 && binding[0] == 'a' && binding.drop(1).all { it.isDigit() } -> {
                        val idx = binding.drop(1).toIntOrNull() ?: continue
                        val target = X360_AXIS[element] ?: continue
                        val source = axes.getOrNull(idx) ?: continue
                        if (source != target) axisMap[source] = target
                    }
                    // Everything else - '-a1', 'a2~', 'h0.4', '+a0' - is left alone on purpose.
                    else -> Unit
                }
            }

            val remap = ControllerRemap(
                device.id, device.name.orEmpty(), entry.name, keyMap, axisMap
            )
            if (remap.isIdentity) {
                if (debug) LauncherLog.write("input", "'${device.name}' already matches the X360 layout; no remap")
                return null
            }
            if (debug) {
                LauncherLog.write(
                    "input",
                    "remap for '${device.name}': ${keyMap.size} key(s), ${axisMap.size} axis/axes " +
                        "-> keys=$keyMap axes=$axisMap"
                )
            }
            return remap
        }
    }
}
