package com.pwde.app.data

import com.pwde.app.data.prefs.CalibrationOverlay
import com.pwde.app.data.prefs.CalibrationOverlayState
import org.junit.Assert.assertEquals
import org.junit.Test

class CalibrationOverlayStateTest {
    @Test
    fun startsCleared_soThePointerBehavesNormally() {
        assertEquals(CalibrationOverlay.None, CalibrationOverlayState().overlay.value)
    }

    @Test
    fun holdsWhatTheCalibrationScreenPublished() {
        val state = CalibrationOverlayState()
        val box = CalibrationOverlay.Box(0f, 0f, 100f, 50f)

        state.set(CalibrationOverlay.Confine(box))
        assertEquals(CalibrationOverlay.Confine(box), state.overlay.value)

        state.set(CalibrationOverlay.Hidden)
        assertEquals(CalibrationOverlay.Hidden, state.overlay.value)

        // Clearing is what a screen does when it goes away: the pointer returns to normal.
        state.clear()
        assertEquals(CalibrationOverlay.None, state.overlay.value)
    }

    @Test
    fun boxReportsItsOwnSize() {
        val box = CalibrationOverlay.Box(left = 10f, top = 20f, right = 110f, bottom = 70f)
        assertEquals(100f, box.width, 0f)
        assertEquals(50f, box.height, 0f)
    }
}
