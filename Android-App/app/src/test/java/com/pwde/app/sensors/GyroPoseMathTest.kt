package com.pwde.app.sensors

import com.pwde.app.sensors.face.GyroPoseMath
import com.pwde.app.sensors.face.GyroPoseMath.ROTATION_0
import com.pwde.app.sensors.face.GyroPoseMath.ROTATION_270
import com.pwde.app.sensors.face.GyroPoseMath.ROTATION_90
import com.pwde.app.sensors.face.HeadPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The gyro joystick's tilt, as the user holds the phone for Mobile Legends: in landscape, screen
 * facing them, leaning back about 45°. Gravity readings are built in the *sensor's* frame (portrait-
 * native), the way the phone reports them, so these also pin the landscape axis conversion.
 */
class GyroPoseMathTest {
    /**
     * The sensor's "up" reading for a phone in landscape with its top on the left (ROTATION_90),
     * leaning back [lean] degrees from upright, with its right edge lowered by [rightDown] degrees.
     */
    private fun landscapeUp(lean: Float, rightDown: Float = 0f): FloatArray {
        val l = Math.toRadians(lean.toDouble())
        val r = Math.toRadians(rightDown.toDouble())
        // In screen axes: up leans left as the right edge drops, and toward the viewer as the screen tips back.
        val sx = -sin(r)
        val sy = cos(r) * cos(l)
        val sz = cos(r) * sin(l)
        // Screen → sensor for ROTATION_90: screen x is the phone's -y, screen y its +x.
        return floatArrayOf(sy.toFloat(), (-sx).toFloat(), sz.toFloat()).map { it * 9.81f }.toFloatArray()
    }

    private fun pose(neutral: FloatArray, current: FloatArray, rotation: Int = ROTATION_90): HeadPose {
        val n = GyroPoseMath.tilt(GyroPoseMath.toScreen(neutral, rotation))!!
        val c = GyroPoseMath.tilt(GyroPoseMath.toScreen(current, rotation))!!
        return GyroPoseMath.pose(n, c)
    }

    private val held = landscapeUp(lean = 45f)

    @Test
    fun holdingStillIsNoMovement() {
        val still = pose(held, held)
        assertEquals(0f, still.pitch, TOLERANCE)
        assertEquals(0f, still.roll, TOLERANCE)
    }

    @Test
    fun loweringTheRightEdgeSteersRight() {
        val p = pose(held, landscapeUp(lean = 45f, rightDown = 15f))
        assertEquals(15f, p.roll, TOLERANCE)
        // Only sideways: tipping to one side must not also push the stick up or down.
        assertEquals(0f, p.pitch, TOLERANCE)
        assertEquals(-15f, pose(held, landscapeUp(lean = 45f, rightDown = -15f)).roll, TOLERANCE)
    }

    @Test
    fun tippingTheTopAwaySteersUp() {
        val away = pose(held, landscapeUp(lean = 60f))
        assertEquals(15f, away.pitch, TOLERANCE)
        assertEquals(0f, away.roll, TOLERANCE)
        assertEquals(-15f, pose(held, landscapeUp(lean = 30f)).pitch, TOLERANCE)
    }

    /** The other landscape (top on the right) steers the same way on screen. */
    @Test
    fun theOtherLandscapeSteersTheSameWay() {
        // Rotating the phone 180° in its own plane negates the sensor's x and y.
        fun flipped(v: FloatArray) = floatArrayOf(-v[0], -v[1], v[2])
        val p = pose(flipped(held), flipped(landscapeUp(lean = 45f, rightDown = 15f)), ROTATION_270)
        assertEquals(15f, p.roll, TOLERANCE)
        assertEquals(15f, pose(flipped(held), flipped(landscapeUp(lean = 60f)), ROTATION_270).pitch, TOLERANCE)
    }

    @Test
    fun portraitStillWorks() {
        // Upright in portrait, "up" is the sensor's +y; lowering the right edge leans it toward -x.
        val upright = floatArrayOf(0f, 9.81f, 0f)
        val right = floatArrayOf(-9.81f * sin(Math.toRadians(10.0)).toFloat(), 9.81f * cos(Math.toRadians(10.0)).toFloat(), 0f)
        assertEquals(10f, pose(upright, right, ROTATION_0).roll, TOLERANCE)
    }

    @Test
    fun noGravityIsNoReading() {
        assertNull(GyroPoseMath.tilt(floatArrayOf(0f, 0f, 0.1f)))
    }

    private companion object {
        const val TOLERANCE = 0.2f
    }
}
