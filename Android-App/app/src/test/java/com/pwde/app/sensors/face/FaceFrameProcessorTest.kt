package com.pwde.app.sensors.face

import com.pwde.app.data.model.ControlConfig
import com.pwde.app.data.model.FaceOutputMode
import com.pwde.app.data.model.FacialGesture
import com.pwde.app.data.model.GestureAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tilt and nod are the same head movement the joystick steers with, so in joystick mode they are not
 * read as actions — with one deliberate exception: a tilt bound to a **centering** action must still
 * fire, because centering is exactly what a user needs *while* steering, and it is never phone
 * navigation for a mode gate to turn off.
 *
 * Pure: no camera, no Android types (see [FaceState]), so this runs on the JVM.
 */
class FaceFrameProcessorTest {
    private val processor = FaceFrameProcessor()

    private fun steering(started: Set<FacialGesture>) = FaceState(
        outputMode = FaceOutputMode.JOYSTICK,
        gesture = GestureReading(started = started),
    )

    private fun controls(action: GestureAction, gesture: FacialGesture) =
        ControlConfig(gestureAssignments = mapOf(action to gesture))

    @Test
    fun aTiltBoundToRecenterStillFiresWhileSteering() {
        assertEquals(
            setOf(FacialGesture.TILT_LEFT),
            processor.actionableStarts(
                steering(setOf(FacialGesture.TILT_LEFT)),
                controls(GestureAction.RECENTER, FacialGesture.TILT_LEFT),
            ),
        )
    }

    @Test
    fun aNodBoundToTheLockStillFiresWhileSteering() {
        assertEquals(
            setOf(FacialGesture.NOD),
            processor.actionableStarts(
                steering(setOf(FacialGesture.NOD)),
                controls(GestureAction.LOCK_CENTER, FacialGesture.NOD),
            ),
        )
    }

    @Test
    fun aTiltBoundToAnyOtherActionIsStillSteering() {
        assertTrue(
            processor.actionableStarts(
                steering(setOf(FacialGesture.TILT_RIGHT)),
                controls(GestureAction.SELECT, FacialGesture.TILT_RIGHT),
            ).isEmpty(),
        )
    }

    @Test
    fun anUnboundTiltIsStillSteering() {
        assertTrue(processor.actionableStarts(steering(setOf(FacialGesture.TILT_LEFT)), ControlConfig()).isEmpty())
    }

    /** Only the moves that double as steering are filtered: a blendshape is unaffected in joystick mode. */
    @Test
    fun aBlendshapeIsUnaffectedByTheSteeringFilter() {
        assertEquals(
            setOf(FacialGesture.SMILE),
            processor.actionableStarts(
                steering(setOf(FacialGesture.SMILE)),
                controls(GestureAction.SELECT, FacialGesture.SMILE),
            ),
        )
    }

    @Test
    fun cursorModeKeepsEveryGesture() {
        val cursor = FaceState(
            outputMode = FaceOutputMode.CURSOR,
            gesture = GestureReading(started = setOf(FacialGesture.TILT_LEFT)),
        )
        assertEquals(
            setOf(FacialGesture.TILT_LEFT),
            processor.actionableStarts(cursor, controls(GestureAction.SELECT, FacialGesture.TILT_LEFT)),
        )
    }

    /** A centering exemption must not resurrect a gesture the calibration switched off. */
    @Test
    fun aDisabledGestureStillNeverFires() {
        val controls = ControlConfig(
            gestureAssignments = mapOf(GestureAction.RECENTER to FacialGesture.TILT_LEFT),
            enabledGestures = emptySet(),
        )
        assertTrue(processor.actionableStarts(steering(setOf(FacialGesture.TILT_LEFT)), controls).isEmpty())
    }
}
