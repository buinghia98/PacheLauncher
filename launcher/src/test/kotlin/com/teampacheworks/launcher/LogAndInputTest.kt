package com.teampacheworks.launcher

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.teampacheworks.launcher.input.ControllerRemap
import com.teampacheworks.launcher.input.GameControllerDb
import com.teampacheworks.launcher.input.GamepadKeyPolicy
import com.teampacheworks.launcher.input.SdlGuid
import com.teampacheworks.launcher.log.CrashReporter
import com.teampacheworks.launcher.log.LauncherLog
import com.teampacheworks.launcher.log.LogExport
import com.teampacheworks.launcher.ui.FpsOverlayLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Ring buffer / crash-tag logic (design spec §4), SDL GUID + db parsing (design spec §5). */
class LogAndInputTest {

    @Before
    fun setUp() {
        installTestConfig()
        LauncherLog.clear()
        LauncherLog.enabled = true
    }

    @After
    fun tearDown() {
        LauncherLog.enabled = false
        LauncherLog.clear()
    }

    // ------------------------------------------------------------ ring buffer

    @Test
    fun `ring buffer keeps the newest 500 lines`() {
        for (i in 1..600) LauncherLog.write("t", "line $i")
        val snap = LauncherLog.snapshot()
        assertEquals(LauncherLog.RING_CAPACITY, snap.size)
        assertTrue("oldest lines must be dropped", snap.first().endsWith("line 101"))
        assertTrue("newest line must survive", snap.last().endsWith("line 600"))
    }

    @Test
    fun `nothing is recorded while logging is disabled`() {
        LauncherLog.enabled = false
        LauncherLog.write("t", "should not appear")
        LauncherLog.d("t") { "nor should this" }
        assertEquals(0, LauncherLog.size())
    }

    @Test
    fun `the lazy overload never builds its message when disabled`() {
        LauncherLog.enabled = false
        var built = false
        LauncherLog.d("t") { built = true; "expensive" }
        assertFalse("zero-cost contract broken: the lambda ran", built)
    }

    @Test
    fun `line format is stable`() {
        assertEquals("[+    1.500] [game] hello", LauncherLog.format(1500L, "game", "hello"))
        assertEquals("[+    0.000] [x] y", LauncherLog.format(0L, "x", "y"))
        assertEquals("[+12345.678] [x] y", LauncherLog.format(12345678L, "x", "y"))
    }

    @Test
    fun `credentials are scrubbed before they reach the buffer`() {
        LauncherLog.write("cloud", "auth github_pat_0123456789abcdefghijklmnop failed")
        LauncherLog.write("cloud", "header Bearer 0123456789abcdefghijklmnop rejected")
        LauncherLog.write("cloud", "classic ghp_0123456789abcdefghijklmnop rejected")
        val all = LauncherLog.snapshot().joinToString("\n")
        assertFalse(all.contains("github_pat_0123"))
        assertFalse(all.contains("ghp_0123"))
        assertFalse(all.contains("Bearer 0123"))
        assertEquals(3, LauncherLog.snapshot().size)
    }

    @Test
    fun `scrubbing leaves ordinary text alone`() {
        val plain = "aspect '16:9' -> game view 1240x698"
        assertEquals(plain, LauncherLog.scrub(plain))
        assertEquals("", LauncherLog.scrub(""))
    }

    @Test
    fun `logging never throws`() {
        LauncherLog.write("t", " ￿ weird 😀")
        LauncherLog.e("t", "boom", RuntimeException("cause"))
        assertTrue(LauncherLog.size() > 0)
    }

    @Test
    fun `process tag is derived from the process name`() {
        assertEquals("game", CrashReporter.processTagOf("com.example.game:game"))
        assertEquals("launcher", CrashReporter.processTagOf("com.example.game"))
        assertEquals("app", CrashReporter.processTagOf(null))
    }

    // ------------------------------------------------------- manual "Export logs" (ui-sync §1/§2)

    @Test
    fun `session log formatting includes every ring line in order`() {
        val lines = listOf("[+    0.100] [a] one", "[+    0.200] [b] two")
        val text = LogExport.formatSessionLog(lines)
        assertTrue(text.contains("Test Game session log"))
        assertTrue(text.indexOf("one") < text.indexOf("two"))
        for (line in lines) assertTrue(text.contains(line))
    }

    // --------------------------------------------------------------- SDL GUID

    /**
     * Real rows from the shipped `gamecontrollerdb.txt`. If [SdlGuid.vidPid]'s byte layout is wrong
     * every lookup misses silently, so these are pinned against the actual database contents.
     */
    @Test
    fun `sdl android guid layout matches real database rows`() {
        // 8BitDo M30: vendor 0x2dc8, product 0x5006, buttonMask 0xffff, axisMask 0x003f
        assertEquals(
            "05000000c82d000006500000ffff3f00",
            SdlGuid.vidPid(0x2dc8, 0x5006, 0xffff, 0x003f)
        )
        // Nintendo Switch Pro Controller: vendor 0x057e, product 0x2009, masks ffff/000f
        assertEquals(
            "050000007e05000009200000ffff0f00",
            SdlGuid.vidPid(0x057e, 0x2009, 0xffff, 0x000f)
        )
        // PS3 Controller: vendor 0x054c, product 0x0268. Note the mask words are little-endian
        // too, so the bytes "df ff" in the row are buttonMask 0xffdf (GUIDE bit clear).
        assertEquals(
            "050000004c05000068020000dfff3f00",
            SdlGuid.vidPid(0x054c, 0x0268, 0xffdf, 0x003f)
        )
        // Bus is always 0x05 and the crc word is always zero for Android rows.
        assertTrue(SdlGuid.vidPid(1, 2, 3, 4).startsWith("05000000"))
    }

    @Test
    fun `descriptor fallback guid is the descriptor as ascii`() {
        // Real row: "a99bdd49865f1ca2" -> 61393962646434393836356631636132
        assertEquals("61393962646434393836356631636132", SdlGuid.descriptorGuid("a99bdd49865f1ca2"))
        // Short names are zero padded to 16 bytes.
        assertEquals("41420000000000000000000000000000", SdlGuid.descriptorGuid("AB"))
        // Longer than 16 bytes is truncated: "8BitDo Arcade Stick" -> "8BitDo Arcade St"
        assertEquals("38426974446f20417263616465205374", SdlGuid.descriptorGuid("8BitDo Arcade Stick"))
        assertNull(SdlGuid.descriptorGuid(null))
        assertNull(SdlGuid.descriptorGuid(""))
    }

    @Test
    fun `axis sort key reproduces SDL's RangeComparator`() {
        // GAS and BRAKE swap; Z moves to just before RZ; things between Z and RZ shift down one.
        assertEquals(MotionEvent.AXIS_RZ - 1, SdlGuid.sortKey(MotionEvent.AXIS_Z))
        assertEquals(MotionEvent.AXIS_X, SdlGuid.sortKey(MotionEvent.AXIS_X))
        assertEquals(MotionEvent.AXIS_Y, SdlGuid.sortKey(MotionEvent.AXIS_Y))
        // A standard Xbox-ish pad sorts X, Y, RX, RY, Z, RZ.
        val ordered = listOf(
            MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z,
            MotionEvent.AXIS_RX, MotionEvent.AXIS_RY, MotionEvent.AXIS_RZ
        ).sortedBy { SdlGuid.sortKey(it) }
        assertEquals(
            listOf(
                MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
                MotionEvent.AXIS_RX, MotionEvent.AXIS_RY,
                MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ
            ),
            ordered
        )
    }

    // ----------------------------------------------------------------- db parsing

    @Test
    fun `database rows parse into bindings`() {
        val lines = sequenceOf(
            "# a comment",
            "",
            "05000000c82d000006500000ffff3f00,8BitDo M30,a:b0,b:b1,x:b2,y:b3,leftx:a0,lefty:a1," +
                "righttrigger:a5,dpup:b11,platform:Android,",
            "short,row",
            "notahexguid,Name,a:b0,platform:Android,"
        )
        val entries = GameControllerDb.parse(lines)
        assertEquals(1, entries.size)
        val e = entries[0]
        assertEquals("8BitDo M30", e.name)
        assertEquals("b0", e.bindings["a"])
        assertEquals("a5", e.bindings["righttrigger"])
        assertEquals("b11", e.bindings["dpup"])
        assertNull("platform must not become a binding", e.bindings["platform"])
    }

    @Test
    fun `the shipped mapping tables are consistent`() {
        // Every element the key table maps must be something the db actually names.
        for (k in ControllerRemap.X360_KEY.keys) {
            assertTrue(
                k,
                k in setOf(
                    "a", "b", "x", "y", "back", "guide", "start", "leftstick", "rightstick",
                    "leftshoulder", "rightshoulder", "lefttrigger", "righttrigger",
                    "dpup", "dpdown", "dpleft", "dpright"
                )
            )
        }
        // SDL button indices per SDL_sysjoystick.c :: keycode_to_SDL.
        assertEquals(0, ControllerRemap.SDL_BUTTON_OF[KeyEvent.KEYCODE_BUTTON_A])
        assertEquals(4, ControllerRemap.SDL_BUTTON_OF[KeyEvent.KEYCODE_BUTTON_SELECT])
        assertEquals(6, ControllerRemap.SDL_BUTTON_OF[KeyEvent.KEYCODE_BUTTON_START])
        assertEquals(9, ControllerRemap.SDL_BUTTON_OF[KeyEvent.KEYCODE_BUTTON_L1])
        assertEquals(14, ControllerRemap.SDL_BUTTON_OF[KeyEvent.KEYCODE_DPAD_RIGHT])
        assertEquals(20, ControllerRemap.SDL_BUTTON_OF[KeyEvent.KEYCODE_BUTTON_1])
        assertEquals(35, ControllerRemap.SDL_BUTTON_OF[KeyEvent.KEYCODE_BUTTON_16])
        // 🔴 BACK must never be remappable: the host game Activity owns it for double-back-to-exit.
        assertNull(ControllerRemap.SDL_BUTTON_OF[KeyEvent.KEYCODE_BACK])
        assertFalse(ControllerRemap.X360_KEY.containsValue(KeyEvent.KEYCODE_BACK))
        // Right stick lands on Z/RZ, the standard X360-layout axis mapping most engines expect.
        assertEquals(MotionEvent.AXIS_Z, ControllerRemap.X360_AXIS["rightx"])
        assertEquals(MotionEvent.AXIS_RZ, ControllerRemap.X360_AXIS["righty"])
        assertNotNull(ControllerRemap.X360_AXIS["leftx"])
    }

    // ----------------------------------------------------- gamepad fallback bug fix (device report)

    @Test
    fun `fallback flag is recognised regardless of other flags set`() {
        assertTrue(GamepadKeyPolicy.isFallback(KeyEvent.FLAG_FALLBACK))
        assertTrue(GamepadKeyPolicy.isFallback(KeyEvent.FLAG_FALLBACK or KeyEvent.FLAG_CANCELED))
        assertFalse(GamepadKeyPolicy.isFallback(0))
        assertFalse(GamepadKeyPolicy.isFallback(KeyEvent.FLAG_CANCELED))
    }

    @Test
    fun `gamepad and joystick sources are recognised, others are not`() {
        assertTrue(GamepadKeyPolicy.isGamepadSource(InputDevice.SOURCE_GAMEPAD))
        assertTrue(GamepadKeyPolicy.isGamepadSource(InputDevice.SOURCE_JOYSTICK))
        // A real device's source is usually a bitwise-OR of several classes.
        assertTrue(
            GamepadKeyPolicy.isGamepadSource(InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_KEYBOARD)
        )
        assertFalse(GamepadKeyPolicy.isGamepadSource(InputDevice.SOURCE_KEYBOARD))
        assertFalse(GamepadKeyPolicy.isGamepadSource(InputDevice.SOURCE_TOUCHSCREEN))
    }

    @Test
    fun `fps overlay is offset onto the picture, never into the letterbox bar`() {
        // A representative device window: 1240x1080.
        // 16:9 -> 1240x698, so a 191px bar top and bottom and nothing at the sides.
        val (right169, top169) = FpsOverlayLayout.margins(1240, 1080, 1240, 698, base = 16)
        assertEquals(16, right169)
        assertEquals(16 + 191, top169)

        // 4:3 -> 1240x930, a 75px bar.
        val (right43, top43) = FpsOverlayLayout.margins(1240, 1080, 1240, 930, base = 16)
        assertEquals(16, right43)
        assertEquals(16 + 75, top43)

        // Full screen: the surface fills the window, so only the plain inset remains.
        val (rightFull, topFull) = FpsOverlayLayout.margins(1240, 1080, 1240, 1080, base = 16)
        assertEquals(16, rightFull)
        assertEquals(16, topFull)

        // A surface reported larger than its parent (mid-layout) must not push the counter
        // off the opposite edge with a negative margin.
        val (rightOver, topOver) = FpsOverlayLayout.margins(1240, 1080, 2000, 2000, base = 16)
        assertEquals(16, rightOver)
        assertEquals(16, topOver)
    }
}
