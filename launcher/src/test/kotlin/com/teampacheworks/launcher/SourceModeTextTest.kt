package com.teampacheworks.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceModeTextTest {
    @Test fun blankHintIsLabelAlone() {
        assertEquals("Keep the source folder", sourceModeText("Keep the source folder", ""))
        assertEquals("Keep the source folder", sourceModeText("Keep the source folder", "  "))
    }

    @Test fun hintGoesOnSecondLine() {
        assertEquals("Keep\nStays here.", sourceModeText("Keep", " Stays here. "))
    }
}
