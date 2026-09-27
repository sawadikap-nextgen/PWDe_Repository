package com.pwde.app.sensors

import com.pwde.app.data.model.CursorTuning
import com.pwde.app.data.model.DEFAULT_LEVEL
import com.pwde.app.data.model.JoystickSource
import com.pwde.app.data.model.JoystickTuning
import com.pwde.app.sensors.face.CursorMapper
import com.pwde.app.sensors.face.CursorPosition
import com.pwde.app.sensors.face.HeadPose
import com.pwde.app.sensors.face.JoystickDirection
import com.pwde.app.sensors.face.JoystickMapper
import com.pwde.app.sensors.face.JoystickState
import com.pwde.app.sensors.face.JoystickTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorMapperTest {
    /** No smoothing lag in these tests: level 1 ≈ follow almost immediately. */
    private val fast = CursorTuning(smoothing = 1)

    private fun CursorMapper.turn(fromYaw: Float, toYaw: Float, pitch: Float = 0f, tuning: CursorTuning = fast, steps: Int = 20): CursorPosition {
        var p = CursorPosition.CENTER
        for (i in 0..steps) p = update(HeadPose(fromYaw + (toYaw - fromYaw) * i / steps, pitch, 0f), tuning)
        repeat(10) { p = update(HeadPose(toYaw, pitch, 0f), tuning) }
        return p
    }

    @Test
    fun turningRightMovesRight() {
        val p = CursorMapper().turn(0f, 10f)
        assertTrue(p.x > 0.5f)
        assertEquals(0.5f, p.y, 0.001f)
    }

    @Test
    fun lookingUpMovesUp() {
        val mapper = CursorMapper()
        var p = CursorPosition.CENTER
        for (i in 0..20) p = mapper.update(HeadPose(0f, i * 0.5f, 0f), fast)
        assertTrue(p.y < 0.5f)
    }

    @Test
    fun perDirectionSpeedsAreIndependent() {
        val slowLeft = fast.copy(speedLeft = 1, speedRight = 10)
        val right = CursorMapper().turn(0f, 5f, tuning = slowLeft).x - 0.5f
        val left = 0.5f - CursorMapper().turn(0f, -5f, tuning = slowLeft).x
        assertTrue("right $right should outpace left $left", right > left * 5)
    }

    @Test
    fun staysOnScreen() {
        val p = CursorMapper().turn(0f, 200f, tuning = fast.copy(speedRight = 10))
        assertEquals(1f, p.x, 0f)
    }

    @Test
    fun recenterReturnsToMiddle() {
        val mapper = CursorMapper()
        mapper.turn(0f, 10f)
        mapper.recenter()
        val p = mapper.update(HeadPose(10f, 0f, 0f), fast)
        assertEquals(0.5f, p.x, 0.01f)
    }

    @Test
    fun gainGrowsWithLevel() {
        assertTrue(CursorMapper.gain(10) > CursorMapper.gain(5))
        assertTrue(CursorMapper.gain(5) > CursorMapper.gain(1))
    }
}

class JoystickMapperTest {
    private val tuning = JoystickTuning(size = 5, sensitivity = 5, deadZone = 1)

    /**
     * A tracker with a clock that steps one frame per reading, so the time-constant filters are
     * really exercised. `frameMs` is how fast the source delivers frames: the camera's ~30 fps, or
     * the gyro's ~50 Hz.
     */
    private class Rig(
        val tuning: JoystickTuning,
        val smoothing: Int = 1,
        val frameMs: Long = 33L,
        /** Which source steers: the head by default, the phone on request. */
        val source: JoystickSource = JoystickSource.HEAD,
    ) {
        private val tracker = JoystickTracker()
        private var now = 0L
        var last = JoystickState()
            private set

        fun feed(pose: HeadPose): JoystickState {
            now += frameMs
            return tracker.update(pose, tuning, smoothing, now, source).also { last = it }
        }
    }

    /** One reading from a freshly engaged stick, with no smoothing lag. */
    private fun stick(pose: HeadPose, tuning: JoystickTuning = this.tuning) =
        JoystickTracker().update(
            pose,
            tuning,
            smoothing = 1,
            nowMs = 0L,
            source = JoystickSource.HEAD,
        )

    @Test
    fun insideDeadZoneIsCentered() {
        val state = stick(HeadPose(0f, 0.5f, 0.5f), tuning.copy(deadZone = 10))
        assertEquals(JoystickDirection.CENTER, state.direction)
        assertEquals(0f, state.x, 0f)
        assertEquals(0f, state.y, 0f)
    }

    @Test
    fun rollSteersSidewaysAndPitchSteersVertically() {
        assertEquals(JoystickDirection.RIGHT, stick(HeadPose(0f, 0f, 25f)).direction)
        assertEquals(JoystickDirection.LEFT, stick(HeadPose(0f, 0f, -25f)).direction)
        assertEquals(JoystickDirection.UP, stick(HeadPose(0f, 25f, 0f)).direction)
        assertEquals(JoystickDirection.DOWN, stick(HeadPose(0f, -25f, 0f)).direction)
        assertEquals(JoystickDirection.UP_RIGHT, stick(HeadPose(0f, 20f, 20f)).direction)
    }

    @Test
    fun centerOffsetsTheNeutralPose() {
        val centered = tuning.copy(centerPitch = -10f, centerRoll = 5f)
        assertEquals(JoystickDirection.CENTER, stick(HeadPose(0f, -10f, 5f), centered).direction)
    }

    @Test
    fun deflectionIsClampedToTheRim() {
        assertEquals(1f, stick(HeadPose(0f, 0f, 90f)).x, 0.001f)
    }

    @Test
    fun higherSensitivityNeedsLessTilt() {
        val low = stick(HeadPose(0f, 0f, 10f), tuning.copy(sensitivity = 1)).x
        val high = stick(HeadPose(0f, 0f, 10f), tuning.copy(sensitivity = 10)).x
        assertTrue(high > low)
    }

    /**
     * The old full-scale curve needed a 23° head tilt at the default level, which is a strain rather
     * than a steering gesture — the "barely responds, needs a huge tilt" report.
     */
    @Test
    fun theDefaultSensitivityReachesTheRimWithoutStraining() {
        assertEquals(1f, stick(HeadPose(0f, 0f, 14f)).x, 0.001f)
    }

    /**
     * The dead zone used to be a share of the full scale, so raising Sensibility silently shrank it
     * and the same tilt drifted at one level but not the other.
     */
    @Test
    fun deadZoneDoesNotMoveWhenSensitivityChanges() {
        val quiet = tuning.copy(sensitivity = 1, deadZone = 5)
        assertEquals(JoystickDirection.CENTER, stick(HeadPose(0f, 0f, 3f), quiet).direction)
        assertEquals(JoystickDirection.CENTER, stick(HeadPose(0f, 0f, 3f), quiet.copy(sensitivity = 10)).direction)
    }

    /**
     * A head resting on the dead-zone edge used to flip CENTER/direction every frame, and each flip
     * makes the accessibility layer lift and re-press the held finger — the game sees taps instead
     * of a held stick, i.e. "twitchy".
     */
    /**
     * A head resting on the dead-zone edge used to flip CENTER/direction every frame, and each flip
     * makes the accessibility layer lift and re-press the held finger — the game sees taps instead
     * of a held stick, i.e. "twitchy". The hysteresis still keeps the *push* alive; what it must not
     * do is keep reporting a direction once the deflection itself has collapsed, which is what made
     * the aim get dragged back to the base (see [insideTheDeadZoneTheStickReadsAsCentred]).
     */
    @Test
    fun leavingAndReturningToTheDeadZoneUseDifferentThresholds() {
        val hysteresis = tuning.copy(deadZone = 4)   // 3° to leave, 1.8° to come back
        val rig = Rig(hysteresis)
        assertEquals(JoystickDirection.CENTER, rig.feed(HeadPose(0f, 0f, 0f)).direction)
        assertEquals(JoystickDirection.RIGHT, rig.feed(HeadPose(0f, 0f, 6f)).direction)
        // Coming back inside the zone there is no deflection left to report, even though the push is
        // still held by the gate, so the finger is not lifted the moment the tilt dips.
        assertEquals(JoystickDirection.CENTER, rig.feed(HeadPose(0f, 0f, 2f)).direction)
        // ...and tilting out again resumes from the middle rather than snapping back out of the stale
        // deflection. 0.543 is what a *fresh* engage at 6° snaps to, so resuming must undershoot it.
        val resumed = rig.feed(HeadPose(0f, 0f, 6f))
        assertEquals(JoystickDirection.RIGHT, resumed.direction)
        assertTrue("resumed from ${resumed.x}", resumed.x < 0.543f)
        // Below the release threshold the push really is dropped, and the next engage snaps again.
        assertEquals(JoystickDirection.CENTER, rig.feed(HeadPose(0f, 0f, 1f)).direction)
    }

    /**
     * Hand tremor on a phone is larger in degrees than head tremor, and the engage test used to run
     * off a single sample: engaging presses the game's joystick and releasing lifts the finger, so a
     * tilt jittering across the dead-zone edge was felt as the stick being dropped and grabbed again.
     */
    @Test
    fun jitterAcrossTheDeadZoneEdgeDoesNotEngageTheStick() {
        val jitter = tuning.copy(deadZone = 4)   // 3° threshold
        val rig = Rig(jitter, frameMs = 20L)
        // Alternating either side of the threshold, the way a hand holding a phone does.
        repeat(30) { i -> rig.feed(HeadPose(0f, 0f, if (i % 2 == 0) 3.5f else 0.5f)) }
        assertEquals(JoystickDirection.CENTER, rig.last.direction)
        // A tilt that is actually held does engage, and soon.
        repeat(3) { rig.feed(HeadPose(0f, 0f, 8f)) }
        assertEquals(JoystickDirection.RIGHT, rig.last.direction)
    }

    /** Letting go must lift the finger at once: holding on would walk the character onward. */
    @Test
    fun releasingLiftsTheStickOnTheRawReading() {
        val rig = Rig(tuning, smoothing = HEAVY_SMOOTHING)
        repeat(5) { rig.feed(HeadPose(0f, 0f, 15f)) }
        assertEquals(JoystickDirection.RIGHT, rig.last.direction)
        // One reading back inside the dead zone and the stick is already centered, however much
        // damping the Smoothing level asks for.
        val released = rig.feed(HeadPose(0f, 0f, 0f))
        assertEquals(JoystickDirection.CENTER, released.direction)
        assertEquals(0f, released.x, 0f)
        assertEquals(0f, released.y, 0f)
    }

    /**
     * The damping used to be a per-frame share tuned for the camera's 30 fps, so the gyro's ~50 Hz
     * stream was filtered ~40% less in wall-clock terms at the same setting — the "the gyro is
     * twitchier" report. The same elapsed time must now give the same result at either rate.
     */
    @Test
    fun theSameSmoothingSteadiesTheStickAtAnyFrameRate() {
        val camera = Rig(tuning, smoothing = HEAVY_SMOOTHING, frameMs = 33L)
        val gyro = Rig(tuning, smoothing = HEAVY_SMOOTHING, frameMs = 20L)
        // Settle on a small tilt first, at either rate, over the same span of time.
        repeat(6) { camera.feed(HeadPose(0f, 0f, 6f)) }
        repeat(10) { gyro.feed(HeadPose(0f, 0f, 6f)) }
        // Then step the tilt up, again over the same span of time: 198 ms is 6 camera frames or
        // 10 gyro samples, and the stick should have travelled the same distance.
        repeat(6) { camera.feed(HeadPose(0f, 0f, 14f)) }
        repeat(10) { gyro.feed(HeadPose(0f, 0f, 14f)) }
        assertEquals(camera.last.x, gyro.last.x, 0.02f)
    }

    /**
     * A vector resting on an octant boundary used to flip between two directions on noise, and every
     * flip is a fresh press for anything mapped to a direction — as well as a flickering label.
     */
    @Test
    fun aDirectionDoesNotFlipOnNoiseAtAnOctantBoundary() {
        // 26.6° is just past the 22.5° boundary where RIGHT gives way to UP_RIGHT.
        assertEquals(JoystickDirection.RIGHT, JoystickMapper.directionOf(1f, -0.5f, JoystickDirection.RIGHT, 12f))
        assertEquals(JoystickDirection.UP_RIGHT, JoystickMapper.directionOf(1f, -0.5f, JoystickDirection.RIGHT, 0f))
        // Clearly past it, the direction does change.
        assertEquals(JoystickDirection.UP_RIGHT, JoystickMapper.directionOf(1f, -1.4f, JoystickDirection.RIGHT, 12f))
        // A stick that has no direction yet has nothing to be sticky about.
        assertEquals(JoystickDirection.UP_RIGHT, JoystickMapper.directionOf(1f, -0.5f, JoystickDirection.CENTER, 12f))
    }

    @Test
    fun smoothingDampsASpikeInsteadOfPassingItStraightThrough() {
        val rig = Rig(tuning, smoothing = HEAVY_SMOOTHING)
        repeat(5) { rig.feed(HeadPose(0f, 0f, 8f)) }
        val spiked = rig.feed(HeadPose(0f, 0f, 18f)).x
        assertTrue(spiked < stick(HeadPose(0f, 0f, 18f)).x)
    }

    @Test
    fun eightWaySnapping() {
        assertEquals(JoystickDirection.RIGHT, JoystickMapper.directionOf(1f, 0.1f))
        assertEquals(JoystickDirection.DOWN_RIGHT, JoystickMapper.directionOf(1f, 1f))
        assertEquals(JoystickDirection.DOWN, JoystickMapper.directionOf(0f, 1f))
        assertEquals(JoystickDirection.DOWN_LEFT, JoystickMapper.directionOf(-1f, 1f))
        assertEquals(JoystickDirection.LEFT, JoystickMapper.directionOf(-1f, 0f))
        assertEquals(JoystickDirection.UP_LEFT, JoystickMapper.directionOf(-1f, -1f))
        assertEquals(JoystickDirection.UP, JoystickMapper.directionOf(0f, -1f))
        assertEquals(JoystickDirection.UP_RIGHT, JoystickMapper.directionOf(1f, -1f))
    }

    /** Level 5 keeps the travel the accessibility stepper used to hard-code, so nothing moves in game. */
    @Test
    fun sizeSetsTheSticksTravel() {
        assertTrue(JoystickMapper.radiusFor(10) > JoystickMapper.radiusFor(1))
        assertEquals(0.12f, JoystickMapper.radiusFor(5), 0.02f)
    }

    /** The dead-zone ring the UI draws is the degree threshold as a share of the full scale. */
    @Test
    fun theDrawnDeadZoneFollowsTheDegreeThreshold() {
        assertTrue(JoystickMapper.deadZoneFraction(tuning.copy(deadZone = 10)) > JoystickMapper.deadZoneFraction(tuning))
    }

    /**
     * A hand holding a phone shakes it by a degree or two, so the phone needs more tilt for full
     * deflection than a head does — but it is also given a **larger dead zone**, so the two sources
     * differ in both numbers rather than only in the scale. Tuned up from the reference's 40° because
     * 40° is a chore to steer with.
     */
    @Test
    fun theGyroIsMoreSensitiveButIgnoresMoreSmallMovementFirst() {
        assertEquals(16f, JoystickMapper.gyroFullScaleDegrees(DEFAULT_LEVEL), 0.001f)
        assertTrue(JoystickMapper.gyroFullScaleDegrees(DEFAULT_LEVEL) > JoystickMapper.fullScaleDegrees(DEFAULT_LEVEL))
        assertTrue(
            JoystickMapper.deadZoneDegrees(tuning.deadZone, JoystickSource.GYRO) >
                JoystickMapper.deadZoneDegrees(tuning.deadZone, JoystickSource.HEAD),
        )
        // Higher sensitivity still needs less tilt, and no level is unusably dead or twitchy.
        assertTrue(JoystickMapper.gyroFullScaleDegrees(10) < JoystickMapper.gyroFullScaleDegrees(DEFAULT_LEVEL))
        assertTrue(JoystickMapper.gyroFullScaleDegrees(1) <= 30f)
    }

    /** The phone still needs more tilt than a head for the same deflection, just less than before. */
    @Test
    fun theSameTiltStillMovesTheStickLessOnThePhone() {
        val tilt = 8f
        val head = Rig(tuning).feed(HeadPose(0f, 0f, tilt)).x
        val gyro = Rig(tuning, source = JoystickSource.GYRO).feed(HeadPose(0f, 0f, tilt)).x
        assertTrue("head $head vs gyro $gyro", gyro < head)
    }

    /**
     * The stick must read as centred the moment the deflection collapses, even while the engage gate
     * still holds on through its hysteresis band. Reporting the last octant instead made the aim get
     * dragged back to the base and out again — which looks exactly like the stick recentring itself
     * over and over, on either source.
     */
    @Test
    fun insideTheDeadZoneTheStickReadsAsCentred() {
        val hysteresis = tuning.copy(deadZone = 4)   // 3° to leave, 1.8° to come back
        val rig = Rig(hysteresis)
        assertEquals(JoystickDirection.RIGHT, rig.feed(HeadPose(0f, 0f, 6f)).direction)
        // 2° is inside the hysteresis band: still held, but with no deflection to report.
        val held = rig.feed(HeadPose(0f, 0f, 2f))
        assertEquals(JoystickDirection.CENTER, held.direction)
        assertEquals(0f, held.x, 0f)
        assertEquals(0f, held.y, 0f)
    }

    private companion object {
        /** Level 10 — heavy damping, so the lag is unmistakable. */
        const val HEAVY_SMOOTHING = 10
    }
}
