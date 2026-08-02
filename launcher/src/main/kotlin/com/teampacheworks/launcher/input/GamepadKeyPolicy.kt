package com.teampacheworks.launcher.input

import android.view.InputDevice
import android.view.KeyEvent

/**
 * Pure classification rules for a host game Activity's `dispatchKeyEvent`, aimed at a bug class
 * seen on real hardware: pressing gamepad A drawing a green focus-highlight border on the game
 * image, and B triggering a "press Back again to exit" flow it should not have.
 *
 * Root cause is Android's key FALLBACK mechanism: when nothing in the dispatch chain reports a
 * key event as consumed, the framework synthesizes a fallback key and re-dispatches it through the
 * same `dispatchKeyEvent` entry point - `KEYCODE_BUTTON_A` -> `KEYCODE_DPAD_CENTER` (a
 * focus/pressed ring on whatever view happens to be focused) and `KEYCODE_BUTTON_B` ->
 * `KEYCODE_BACK` (which can trip a double-back-to-exit handler). Many game engines consume gamepad
 * input on their own native/internal path but never report the Android key event itself as
 * handled, so without an explicit fix the framework happily invents these fallbacks on every press.
 *
 * A host's game Activity should check [isFallback] first (before any real-`KEYCODE_BACK` handling)
 * and swallow fallback events outright, then report every gamepad-sourced key as consumed via
 * [isGamepadSource] so the framework has no reason to synthesize a fallback in the first place.
 *
 * Split into its own object (rather than left inline in an Activity) purely so the classification
 * logic is unit-testable off-device: [isFallback]/[isGamepadSource] only look at plain int
 * flags/bitmasks and need no live Activity, View, or InputDevice instance.
 */
object GamepadKeyPolicy {

    /**
     * True for a key event the FRAMEWORK synthesized as a fallback of some other key (never true
     * for a key a human actually pressed). Must be checked and swallowed before anything else -
     * in particular before a real-`KEYCODE_BACK` check - so a synthesized BACK (from B) is never
     * mistaken for a real BACK press.
     */
    fun isFallback(flags: Int): Boolean = (flags and KeyEvent.FLAG_FALLBACK) != 0

    /**
     * True when the key event's source includes the gamepad or joystick input class. Used as a
     * proactive guard: reporting every such key as "consumed" (regardless of what the engine's own
     * dispatch chain returned) means the framework never has a reason to synthesize a fallback for
     * A/B (or anything else) in the first place - [isFallback] is the reactive backstop in case one
     * happens anyway.
     */
    fun isGamepadSource(source: Int): Boolean =
        (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
}
