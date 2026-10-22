package com.teampacheworks.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherOptionTest {
    private val choices = listOf(
        LauncherOptionChoice("disabled", "Off"),
        LauncherOptionChoice("enabled", "On")
    )

    @Test fun togglePreservesOpaqueValuesAndFallsBackAfterInvalidSavedValue() {
        val option = LauncherOption("compat", "Compatibility", "Next launch", choices,
            defaultValue = "disabled", control = LauncherOptionControl.TOGGLE)
        assertEquals(0, option.indexOf(null))
        assertEquals(0, option.indexOf("obsolete"))
        assertEquals(1, option.indexOf("enabled"))
        assertEquals("opt_compat", option.prefsKey)
        assertEquals(LauncherContract.extraNameFor("compat"), option.extraName)
        assertEquals(LauncherOptionControl.DROPDOWN, option.copy(control = LauncherOptionControl.DROPDOWN).control)
    }

    @Test(expected = IllegalArgumentException::class)
    fun toggleRejectsAmbiguousValues() {
        LauncherOption("bad", "Bad", "", listOf(choices[0], choices[0]),
            control = LauncherOptionControl.TOGGLE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun toggleRejectsMoreThanTwoStates() {
        LauncherOption("bad", "Bad", "", choices + LauncherOptionChoice("auto", "Auto"),
            control = LauncherOptionControl.TOGGLE)
    }

    @Test fun sliderStepsAreTheChoicesInOrderAndFallBackToTheDefault() {
        val scale = listOf("25", "50", "75", "100").map { LauncherOptionChoice(it, "$it%") }
        val option = LauncherOption("scale", "Scale", "", scale,
            defaultValue = "75", control = LauncherOptionControl.SLIDER)
        assertEquals(0, option.indexOf("25"))
        assertEquals(3, option.indexOf("100"))
        assertEquals(2, option.indexOf(null))
        assertEquals(2, option.indexOf("60"))
        assertEquals("opt_scale", option.prefsKey)
        assertEquals(LauncherContract.extraNameFor("scale"), option.extraName)
    }

    @Test(expected = IllegalArgumentException::class)
    fun sliderRejectsASingleChoice() {
        LauncherOption("bad", "Bad", "", listOf(choices[0]),
            control = LauncherOptionControl.SLIDER)
    }
}
