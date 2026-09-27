package com.pwde.app.sensors.face

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Phone tilt → a [HeadPose] the joystick already understands, for steering Mobile Legends' movement
 * stick in landscape. Size, Sensitivity, Dead zone and Smoothing keep their meaning downstream.
 *
 * The tilt is read from **gravity**, not from an integrated rotation: "which way is up" never
 * drifts, has no heading to lose, and ignores turning on the spot entirely. It behaves like a marble
 * on a tray held in front of you:
 *
 * - **Lower the right edge** (turn it like a steering wheel, or tip that edge away) → stick right.
 * - **Tip the top edge away** from you → stick up; tip it toward you → stick down.
 *
 * Everything is measured in the **screen's** axes (x right, y up, z out of the screen toward the
 * user), converted from the sensor's portrait-native axes with the display rotation, so it holds
 * whichever way round the phone is turned for the game.
 */
object GyroPoseMath {
    /** `android.view.Surface.ROTATION_*`, kept here so the math stays free of Android classes. */
    const val ROTATION_0 = 0
    const val ROTATION_90 = 1
    const val ROTATION_180 = 2
    const val ROTATION_270 = 3

    /** How the phone is tilted, in degrees, measured in the screen's axes. */
    data class Tilt(
        /** Sideways lean: positive when the screen's right edge is raised. */
        val side: Float,
        /** The screen's angle from lying flat: 90 upright, 0 flat facing up. */
        val forward: Float,
    )

    /**
     * A sensor-frame vector in the screen's axes. `Display.getRotation` is the rotation of the drawn
     * image, opposite to the phone's: ROTATION_90 is the phone turned 90° counter-clockwise (its top
     * on the left), so the phone's +y points left and its +x points up the screen.
     */
    fun toScreen(v: FloatArray, displayRotation: Int): FloatArray = when (displayRotation) {
        ROTATION_90 -> floatArrayOf(-v[1], v[0], v[2])
        ROTATION_180 -> floatArrayOf(-v[0], -v[1], v[2])
        ROTATION_270 -> floatArrayOf(v[1], -v[0], v[2])
        else -> floatArrayOf(v[0], v[1], v[2])
    }

    /**
     * The tilt of a phone whose "up" is [up] — a gravity reading, which Android reports pointing
     * away from the ground — in screen axes. Null when the reading is too small to trust.
     */
    fun tilt(up: FloatArray): Tilt? {
        val norm = sqrt(up[0] * up[0] + up[1] * up[1] + up[2] * up[2])
        if (norm < MIN_GRAVITY) return null
        val x = up[0] / norm
        val y = up[1] / norm
        val z = up[2] / norm
        return Tilt(
            side = Math.toDegrees(asin(x.coerceIn(-1f, 1f).toDouble())).toFloat(),
            forward = Math.toDegrees(atan2(y.toDouble(), z.toDouble())).toFloat(),
        )
    }

    /**
     * The movement from [neutral] to [current]: [HeadPose.roll] steers right when positive and
     * [HeadPose.pitch] steers up when positive, exactly as the head's pose does.
     */
    fun pose(neutral: Tilt, current: Tilt): HeadPose = HeadPose(
        yaw = 0f,
        // The screen's right edge dropping raises "up" toward its left, i.e. side falls.
        pitch = wrap(neutral.forward - current.forward),
        roll = neutral.side - current.side,
    )

    /** An angle difference in (-180, 180], so passing through upright never flips the stick. */
    private fun wrap(degrees: Float): Float {
        var d = degrees % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    /** m/s²: below this the phone is in free fall (or the sensor is broken), with no "up" to read. */
    private const val MIN_GRAVITY = 1f
}
