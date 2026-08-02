package com.teampacheworks.launcher.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Reconstructs the joystick GUID that SDL would compute for an Android [InputDevice], so that
 * `gamecontrollerdb.txt` can be looked up (design spec §5).
 *
 * Verified against SDL2 `master` sources, not from memory:
 *
 * - `src/joystick/SDL_joystick.c :: SDL_CreateJoystickGUID(bus, vendor, product, version, …)` lays
 *   the 16 bytes out as six little-endian `Uint16`s: `bus, crc, vendor, 0, product, 0, version`,
 *   with `data[14]/data[15]` as driver signature/data.
 * - `src/joystick/android/SDL_sysjoystick.c :: Android_AddJoystick` calls it with
 *   `bus = SDL_HARDWARE_BUS_BLUETOOTH (0x05)`, `version = 0`, and then **overwrites** the last two
 *   words: `guid16[6] = button_mask`, `guid16[7] = axis_mask`.
 * - `android-project/.../SDLControllerManager.java :: getButtonMask/getAxisMask/RangeComparator`
 *   is where those two masks come from.
 *
 * So an Android entry reads `05 00 | 00 00 | <vendor LE> | 00 00 | <product LE> | 00 00 |
 * <buttonMask LE> | <axisMask LE>` - e.g. the 8BitDo M30 at `05000000c82d000006500000ffff3f00`.
 *
 * The `crc` word is left at zero: SDL zeroes it before matching against the database, and every
 * `platform:Android` row in the shipped db has `0000` there.
 *
 * When the device reports no vendor/product, SDL falls back to copying the device **descriptor**
 * (or, failing that, the name) into the 16 GUID bytes as raw ASCII - which is why so many Android
 * rows are readable hex strings like `61393962646434393836356631636132` ("a99bdd49865f1ca2").
 */
object SdlGuid {

    const val BUS_BLUETOOTH = 0x05

    /**
     * Every GUID worth trying for [device], most specific first. Callers walk the list and take the
     * first hit; masks are capability-derived and drift between SDL versions, so the exact form is
     * tried first and a mask-agnostic prefix match backs it up.
     */
    fun candidates(device: InputDevice): List<String> {
        val out = LinkedHashSet<String>()
        val vendor = device.vendorId
        val product = device.productId
        if (vendor != 0 && product != 0) {
            out += vidPid(vendor, product, buttonMask(device), axisMask(device))
            out += vidPid(vendor, product, 0, 0)
        }
        descriptorGuid(device.descriptor)?.let { out += it }
        descriptorGuid(device.name)?.let { out += it }
        return out.toList()
    }

    /** First 12 bytes (bus + crc + vendor + pad + product + pad) as 24 hex chars, or null. */
    fun vidPidPrefix(device: InputDevice): String? {
        val vendor = device.vendorId
        val product = device.productId
        if (vendor == 0 || product == 0) return null
        return vidPid(vendor, product, 0, 0).substring(0, 24)
    }

    fun vidPid(vendor: Int, product: Int, buttonMask: Int, axisMask: Int): String {
        val b = ByteArray(16)
        le16(b, 0, BUS_BLUETOOTH)
        le16(b, 2, 0)           // crc - zeroed, see the class comment
        le16(b, 4, vendor)
        le16(b, 6, 0)
        le16(b, 8, product)
        le16(b, 10, 0)
        le16(b, 12, buttonMask) // guid16[6], overwritten by Android_AddJoystick
        le16(b, 14, axisMask)   // guid16[7]
        return hex(b)
    }

    /** SDL's no-vendor fallback: the descriptor string's first 16 bytes, zero padded. */
    fun descriptorGuid(text: String?): String? {
        if (text.isNullOrEmpty()) return null
        val src = text.toByteArray(Charsets.US_ASCII)
        val b = ByteArray(16)
        for (i in 0 until minOf(16, src.size)) b[i] = src[i]
        return hex(b)
    }

    private fun le16(b: ByteArray, off: Int, value: Int) {
        b[off] = (value and 0xff).toByte()
        b[off + 1] = ((value ushr 8) and 0xff).toByte()
    }

    fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            sb.append(HEX[(x.toInt() shr 4) and 0xf]).append(HEX[x.toInt() and 0xf])
        }
        return sb.toString()
    }

    private const val HEX = "0123456789abcdef"

    // ------------------------------------------------------------------- masks

    /** Mirrors `SDLJoystickHandler_API19.getButtonMask`; only the low 16 bits reach the GUID. */
    fun buttonMask(device: InputDevice): Int {
        val keys = intArrayOf(
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_C, KeyEvent.KEYCODE_BUTTON_Z
        )
        val masks = intArrayOf(
            1 shl 0, 1 shl 1, 1 shl 2, 1 shl 3,
            1 shl 4,            // BACK  -> BACK
            1 shl 6,            // MENU  -> START
            1 shl 5,            // MODE  -> GUIDE
            1 shl 6,            // START -> START
            1 shl 7, 1 shl 8,   // THUMBL/THUMBR
            1 shl 9, 1 shl 10,  // L1/R1
            1 shl 11, 1 shl 12, 1 shl 13, 1 shl 14,
            1 shl 4,            // SELECT -> BACK
            1 shl 0,            // DPAD_CENTER -> A
            1 shl 15, 1 shl 16, 1 shl 17, 1 shl 18
        )
        return try {
            val has = device.hasKeys(*keys)
            var mask = 0
            for (i in keys.indices) if (has[i]) mask = mask or masks[i]
            // Android_AddJoystick folds a hat into the four DPAD bits before building the GUID.
            if (hatCount(device) > 0) mask = mask or 0x7800
            mask
        } catch (t: Throwable) {
            0
        }
    }

    /** Mirrors `SDLJoystickHandler_API19.getAxisMask` over the SDL-sorted joystick axes. */
    fun axisMask(device: InputDevice): Int {
        val ranges = sortedAxes(device)
        var mask = 0
        if (ranges.size >= 2) mask = mask or 0x0003
        if (ranges.size >= 4) mask = mask or 0x000c
        if (ranges.size >= 6) mask = mask or 0x0030
        // The "sorting order changed" indicator bit SDL uses to retire outdated db rows.
        var haveZ = false
        var havePastZBeforeRz = false
        for (a in ranges) {
            if (a == MotionEvent.AXIS_Z) haveZ = true
            else if (a > MotionEvent.AXIS_Z && a < MotionEvent.AXIS_RZ) havePastZBeforeRz = true
        }
        if (haveZ && havePastZBeforeRz) mask = mask or 0x8000
        return mask
    }

    /**
     * The joystick-class axes, minus the hat, in SDL's `RangeComparator` order. This ordering is
     * also what SDL's `aN` indices refer to, so the remap layer uses the same list.
     */
    fun sortedAxes(device: InputDevice): List<Int> = try {
        device.motionRanges
            .filter { it.source and InputDevice.SOURCE_CLASS_JOYSTICK != 0 }
            .map { it.axis }
            .filter { it != MotionEvent.AXIS_HAT_X && it != MotionEvent.AXIS_HAT_Y }
            .distinct()
            .sortedBy { sortKey(it) }
    } catch (t: Throwable) {
        emptyList()
    }

    private fun hatCount(device: InputDevice): Int = try {
        device.motionRanges.count {
            it.source and InputDevice.SOURCE_CLASS_JOYSTICK != 0 &&
                (it.axis == MotionEvent.AXIS_HAT_X || it.axis == MotionEvent.AXIS_HAT_Y)
        } / 2
    } catch (t: Throwable) {
        0
    }

    /**
     * SDL's `RangeComparator`, transcribed:
     *  - GAS and BRAKE swap places (some pads report them the wrong way round);
     *  - AXIS_Z is moved to sort immediately before AXIS_RZ, and everything between them shifts
     *    down one, so the usual Xbox-ish pairing (right stick on RX/RY, triggers on Z/RZ) sorts
     *    into SDL's expected order.
     */
    fun sortKey(axis: Int): Int {
        var a = when (axis) {
            MotionEvent.AXIS_GAS -> MotionEvent.AXIS_BRAKE
            MotionEvent.AXIS_BRAKE -> MotionEvent.AXIS_GAS
            else -> axis
        }
        if (a == MotionEvent.AXIS_Z) a = MotionEvent.AXIS_RZ - 1
        else if (a > MotionEvent.AXIS_Z && a < MotionEvent.AXIS_RZ) a -= 1
        return a
    }
}
