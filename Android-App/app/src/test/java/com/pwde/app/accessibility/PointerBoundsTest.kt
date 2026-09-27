package com.pwde.app.accessibility

import com.pwde.app.data.prefs.CalibrationOverlay
import org.junit.Assert.assertEquals
import org.junit.Test

class PointerBoundsTest {
    private val box = CalibrationOverlay.Box(left = 100f, top = 200f, right = 900f, bottom = 700f)
    private val radius = 12f

    @Test
    fun aPointerInsideTheBoxIsLeftAlone() {
        assertEquals(500f to 400f, PointerBounds.confine(500f, 400f, box, radius))
    }

    @Test
    fun aPointerOutsideIsPulledToTheBoxEdge_minusTheDotRadius() {
        // Far to the top-left of the box: clipped to the box's own corner, inset by the dot radius so
        // no part of the dot is drawn outside it.
        assertEquals(112f to 212f, PointerBounds.confine(0f, 0f, box, radius))
        assertEquals(888f to 688f, PointerBounds.confine(5000f, 5000f, box, radius))
    }

    @Test
    fun eachAxisIsClampedIndependently() {
        // Inside horizontally, outside vertically.
        assertEquals(500f to 688f, PointerBounds.confine(500f, 5000f, box, radius))
        assertEquals(112f to 400f, PointerBounds.confine(-50f, 400f, box, radius))
    }

    @Test
    fun aBoxNarrowerThanTheDotFallsBackToItsCentre() {
        val tiny = CalibrationOverlay.Box(left = 10f, top = 20f, right = 26f, bottom = 36f)
        // 16px box, 24px dot: inverting the range would be wrong, so the centre is used.
        assertEquals(18f to 28f, PointerBounds.confine(0f, 0f, tiny, radius))
        assertEquals(18f to 28f, PointerBounds.confine(999f, 999f, tiny, radius))
    }

    @Test
    fun edgeTouchesLandExactlyOnTheInsetEdge() {
        assertEquals(112f to 400f, PointerBounds.confine(box.left, 400f, box, radius))
        assertEquals(888f to 400f, PointerBounds.confine(box.right, 400f, box, radius))
        assertEquals(500f to 212f, PointerBounds.confine(500f, box.top, box, radius))
        assertEquals(500f to 688f, PointerBounds.confine(500f, box.bottom, box, radius))
    }
}
