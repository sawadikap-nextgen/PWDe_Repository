package com.pwde.app.sensors.face

import com.pwde.app.data.model.CursorTuning
import com.pwde.app.data.model.DEFAULT_LEVEL
import com.pwde.app.data.model.JoystickSource
import com.pwde.app.data.model.JoystickTuning
import com.pwde.app.data.model.MAX_LEVEL
import com.pwde.app.data.model.MIN_LEVEL
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** Pointer position, normalized: (0,0) top-left, (1,1) bottom-right. */
data class CursorPosition(val x: Float, val y: Float) {
    companion object {
        val CENTER = CursorPosition(0.5f, 0.5f)
    }
}

enum class JoystickDirection(val label: String) {
    CENTER("Center"), UP("Up"), UP_RIGHT("Up-right"), RIGHT("Right"), DOWN_RIGHT("Down-right"),
    DOWN("Down"), DOWN_LEFT("Down-left"), LEFT("Left"), UP_LEFT("Up-left"),
}

/**
 * Joystick deflection in screen convention: x > 0 right, y > 0 down, both in [-1, 1].
 * [radius] is the on-screen joystick size as a fraction of the shorter screen side.
 */
data class JoystickState(
    val x: Float = 0f,
    val y: Float = 0f,
    val direction: JoystickDirection = JoystickDirection.CENTER,
    val radius: Float = JoystickMapper.radiusFor(DEFAULT_LEVEL),
    /** The dead zone as a share of the full-scale tilt, so the UI can draw it. See [JoystickMapper]. */
    val deadZone: Float = JoystickMapper.deadZoneFraction(JoystickTuning()),
)

private fun levelFraction(level: Int) = (level.coerceIn(MIN_LEVEL, MAX_LEVEL) - MIN_LEVEL).toFloat() / (MAX_LEVEL - MIN_LEVEL)

/** Smoothing level 1 barely smooths; level 10 is heavy smoothing. Shared by the pointer and the stick. */
internal fun headSmoothingAlpha(level: Int): Float = 0.9f - 0.8f * levelFraction(level)

/**
 * Time constant of the joystick's damping, in milliseconds, from the Smoothing level.
 *
 * Derived from the old per-frame curve at the rate that curve was tuned at, so head and face tracking
 * steadies exactly as it always did at every level. Expressing it as a time constant is what makes the
 * gyro behave: its sensor stream arrives at ~50 Hz, and a per-frame share filters that ~40% less in
 * wall-clock terms than the camera's ~30 fps at the same setting — the reason the gyro felt twitchier.
 */
internal fun joystickDampingTauMs(level: Int): Float {
    val alpha = headSmoothingAlpha(level).coerceIn(0.01f, 0.99f)
    return NOMINAL_CAMERA_FRAME_MS / -ln(1f - alpha)
}

/** The frame rate the old per-frame damping curve was tuned at. */
private const val NOMINAL_CAMERA_FRAME_MS = 33f

/** One step of an exponential low-pass with a time constant, so the result ignores the frame rate. */
internal fun lowPass(previous: Float, target: Float, tauMs: Float, dtMs: Float): Float {
    if (tauMs <= 0f) return target
    return previous + (1f - exp(-dtMs / tauMs)) * (target - previous)
}

/*
 * Relative head-pointer movement adapted from Google Project GameFace
 * (https://github.com/google/project-gameface, Apache License 2.0): the pointer moves by the change
 * in head angle times a per-direction speed, after exponential smoothing to calm tremor.
 */
class CursorMapper {
    private var position = CursorPosition.CENTER
    private var smoothYaw = Float.NaN
    private var smoothPitch = Float.NaN

    fun update(pose: HeadPose, tuning: CursorTuning): CursorPosition {
        val alpha = smoothingAlpha(tuning.smoothing)
        if (smoothYaw.isNaN()) {
            smoothYaw = pose.yaw
            smoothPitch = pose.pitch
            return position
        }
        val newYaw = smoothYaw + alpha * (pose.yaw - smoothYaw)
        val newPitch = smoothPitch + alpha * (pose.pitch - smoothPitch)
        val dYaw = newYaw - smoothYaw
        val dPitch = newPitch - smoothPitch
        smoothYaw = newYaw
        smoothPitch = newPitch

        val dx = if (abs(dYaw) < JITTER_DEGREES) 0f
        else dYaw * gain(if (dYaw > 0) tuning.speedRight else tuning.speedLeft)
        // Looking up (pitch rising) moves the pointer up, i.e. toward y = 0.
        val dy = if (abs(dPitch) < JITTER_DEGREES) 0f
        else -dPitch * gain(if (dPitch > 0) tuning.speedUp else tuning.speedDown)
        position = CursorPosition((position.x + dx).coerceIn(0f, 1f), (position.y + dy).coerceIn(0f, 1f))
        return position
    }

    fun recenter() {
        position = CursorPosition.CENTER
    }

    /** Forget the last head angle, e.g. after the face was lost, so the pointer doesn't jump. */
    fun resetTracking() {
        smoothYaw = Float.NaN
        smoothPitch = Float.NaN
    }

    companion object {
        /** Smoothed head movement below this per frame is treated as jitter. */
        const val JITTER_DEGREES = 0.02f

        /** Screen fraction per degree of head movement: level 1 is slow, level 10 is fast. */
        fun gain(level: Int): Float = 0.004f * 1.35f.pow(level.coerceIn(MIN_LEVEL, MAX_LEVEL) - 1)

        /** Smoothing level 1 barely smooths; level 10 is heavy smoothing. */
        fun smoothingAlpha(level: Int): Float = headSmoothingAlpha(level)
    }
}

/**
 * Head tilt joystick: roll steers left/right, pitch steers up/down, both from the user's center.
 *
 * The mapping follows ASPHI Nose-Drive (https://github.com/Fondazione-ASPHI/Nose-Drive,
 * `src/nosedrive/main.py`). Nose-Drive's whole mapping is: save a base pose once, then the input is
 * `(value - base) * sensitivity`, clamped to [-1, 1], per axis — and that clamped number *is* the
 * gamepad axis. It is absolute, not a step per frame, so the stick returns to the middle when the
 * head does, and sensitivity and the base stay independent knobs.
 *
 * Two departures, because this drives a real touch screen rather than a virtual gamepad:
 * - The dead zone is measured in **degrees**, not as a share of the full-scale tilt. Tying it to the
 *   full scale meant raising Sensibility silently shrank it (0.15 x 23° = 3.5° at level 5 but
 *   0.15 x 8° = 1.2° at level 10), so "ignores small head movements" quietly stopped being true.
 *   (Nose-Drive has no dead zone at all.)
 * - The dead zone has hysteresis. Without it a head resting near the boundary flips between CENTER
 *   and a direction every frame, and each flip makes the accessibility layer lift and re-press the
 *   finger — which the game sees as taps instead of a held stick.
 */
object JoystickMapper {
    /**
     * Head tilt for full deflection: 30° at level 1 down to 4° at level 10. Geometric, so a step
     * feels like a step, like [CursorMapper.gain]. The old linear 35°..8° curve needed 23° at the
     * default level, which is a strain rather than a steering gesture.
     */
    fun fullScaleDegrees(sensitivity: Int): Float =
        30f * SENSITIVITY_STEP.pow(sensitivity.coerceIn(MIN_LEVEL, MAX_LEVEL) - MIN_LEVEL)

    /**
     * Phone tilt for full deflection. Still more than a head needs — a hand holding a phone shakes it
     * by a degree or two where a head barely moves — but well under the reference's 40°, because
     * tilting a phone 40° is a chore rather than a steering movement.
     */
    fun gyroFullScaleDegrees(sensitivity: Int): Float =
        (GYRO_FULL_TILT_DEGREES * SENSITIVITY_STEP.pow(sensitivity.coerceIn(MIN_LEVEL, MAX_LEVEL) - DEFAULT_LEVEL))
            .coerceIn(GYRO_MIN_FULL_TILT_DEGREES, GYRO_MAX_FULL_TILT_DEGREES)

    /** Full-deflection tilt for whichever source is steering. */
    fun fullScaleDegrees(sensitivity: Int, source: JoystickSource): Float =
        if (source == JoystickSource.GYRO) gyroFullScaleDegrees(sensitivity) else fullScaleDegrees(sensitivity)

    /** Movement ignored around the base pose, in degrees, for the head. */
    fun deadZoneDegrees(level: Int): Float = 1f + 6f * levelFraction(level)

    /**
     * Movement ignored around the base pose, in degrees, for whichever source is steering.
     *
     * The gyro's is deliberately larger: a hand holding a phone wobbles more than a head does, and a
     * phone is a coarser thing to steer, so more of the small movement has to be ignored before the
     * stick is allowed to move at all. Its full-scale tilt is *smaller* at the same time, so the stick
     * still reaches the rim quickly once it does move — a bigger dead zone without a sluggish stick.
     */
    fun deadZoneDegrees(level: Int, source: JoystickSource): Float =
        deadZoneDegrees(level) * if (source == JoystickSource.GYRO) GYRO_DEAD_ZONE_FACTOR else 1f

    /**
     * The dead zone as a share of the full-scale tilt, for the source in use: the head and the phone
     * differ in both numbers, not only in the scale, so this cannot be computed from one of them.
     */
    fun deadZoneFraction(tuning: JoystickTuning, source: JoystickSource = JoystickSource.HEAD): Float {
        val scale = fullScaleDegrees(tuning.sensitivity, source)
        return (deadZoneDegrees(tuning.deadZone, source) / scale).coerceIn(0f, 1f)
    }

    /**
     * How far the game's stick is dragged at full deflection, as a share of the screen's short side.
     * Level 5 lands on the 0.12 the stepper used to hard-code, so this is the one source of truth for
     * the in-game drag, the button marker and the preview alike.
     */
    fun radiusFor(size: Int): Float = 0.06f + 0.12f * levelFraction(size)

    /** Snaps a vector (screen convention, y down) to one of 8 directions. */
    fun directionOf(x: Float, y: Float): JoystickDirection {
        if (x == 0f && y == 0f) return JoystickDirection.CENTER
        return when (((angleOf(x, y) + SECTOR_HALF_DEGREES) / 45f).toInt() % 8) {
            0 -> JoystickDirection.RIGHT
            1 -> JoystickDirection.UP_RIGHT
            2 -> JoystickDirection.UP
            3 -> JoystickDirection.UP_LEFT
            4 -> JoystickDirection.LEFT
            5 -> JoystickDirection.DOWN_LEFT
            6 -> JoystickDirection.DOWN
            else -> JoystickDirection.DOWN_RIGHT
        }
    }

    /**
     * [directionOf], but sticky: [previous] is kept until the vector is more than
     * [hysteresisDegrees] outside the 45° sector that [previous] owns.
     *
     * Without it a vector resting on a sector boundary flips between two octants on noise, and every
     * flip is a fresh press for anything mapped to a direction — and a flickering label for the user.
     */
    fun directionOf(x: Float, y: Float, previous: JoystickDirection, hysteresisDegrees: Float): JoystickDirection {
        val wanted = directionOf(x, y)
        if (wanted == previous || previous == JoystickDirection.CENTER) return wanted
        val outside = abs(((angleOf(x, y) - CENTER_DEGREES.getValue(previous) + 540f) % 360f) - 180f)
        return if (outside <= SECTOR_HALF_DEGREES + hysteresisDegrees) previous else wanted
    }

    /** Angle in degrees, measured counter-clockwise from "right", with y flipped so up is positive. */
    fun angleOf(x: Float, y: Float): Float = ((atan2(-y, x) * 180.0 / PI + 360.0) % 360.0).toFloat()

    /** The middle of each direction's sector, in [angleOf] degrees. */
    private val CENTER_DEGREES = mapOf(
        JoystickDirection.RIGHT to 0f,
        JoystickDirection.UP_RIGHT to 45f,
        JoystickDirection.UP to 90f,
        JoystickDirection.UP_LEFT to 135f,
        JoystickDirection.LEFT to 180f,
        JoystickDirection.DOWN_LEFT to 225f,
        JoystickDirection.DOWN to 270f,
        JoystickDirection.DOWN_RIGHT to 315f,
    )

    /** Each direction owns a 45° sector, so its boundary is half that from its center. */
    const val SECTOR_HALF_DEGREES = 22.5f

    /** Per sensitivity level: 30° down to 4° across the nine steps. */
    private const val SENSITIVITY_STEP = 0.8f

    /**
     * Phone tilt for full deflection at [DEFAULT_LEVEL]. Well under the reference's 40°: a phone is a
     * coarser thing to steer than a head, and 40° is a chore rather than a steering movement.
     */
    private const val GYRO_FULL_TILT_DEGREES = 16f

    /** Bounds on the gyro curve, so no level is unusably dead or unusably twitchy. */
    private const val GYRO_MIN_FULL_TILT_DEGREES = 6f
    private const val GYRO_MAX_FULL_TILT_DEGREES = 30f

    /** How much larger the gyro's dead zone is than the head's. See [deadZoneDegrees]. */
    private const val GYRO_DEAD_ZONE_FACTOR = 1.6f
}

/**
 * Live head or gyro joystick. Stateful, because it damps the deflection between frames, remembers
 * whether it is inside the dead zone, and keeps the direction it last reported.
 *
 * Every filter here is a **time constant**, not a per-frame share, because the two sources deliver
 * frames at different rates (camera ~30 fps, gyro ~50 Hz) and the same Smoothing setting has to
 * steady the stick by the same amount at either. That is why [update] takes `nowMs`.
 *
 * `scaleDegrees` is the tilt that means full deflection for the source in use, and `deadZoneDegrees`
 * the tilt ignored first: the head and the phone need very different amounts of both (see
 * [JoystickMapper.gyroFullScaleDegrees] and [JoystickMapper.deadZoneDegrees]).
 *
 * The engage and release thresholds deliberately use different evidence:
 * - **Engage** needs the *smoothed* tilt past the dead zone, so one noisy sample cannot press the
 *   game's joystick. Hand tremor on a phone is larger in degrees than head tremor, which is what
 *   made the gyro pump the stick.
 * - **Release** uses the *raw* tilt, so letting go lifts the finger immediately. Holding on would
 *   walk the character onward after the user had stopped.
 */
class JoystickTracker {
    private var x = 0f
    private var y = 0f

    /** The slow copy of the tilt the engage test runs on. Never reset, so re-engaging stays protected. */
    private var gateTilt = Float.NaN
    private var engaged = false
    private var fresh = true
    private var direction = JoystickDirection.CENTER
    private var lastMs = 0L

    fun update(pose: HeadPose, tuning: JoystickTuning, smoothing: Int, nowMs: Long, source: JoystickSource): JoystickState {
        val radius = JoystickMapper.radiusFor(tuning.size)
        // Both numbers are the source's own: the phone ignores more tilt first, then needs less of it.
        val scale = JoystickMapper.fullScaleDegrees(tuning.sensitivity, source)
        val zone = JoystickMapper.deadZoneDegrees(tuning.deadZone, source)
        val deadZone = (zone / scale).coerceIn(0f, 1f)
        val centered = JoystickState(0f, 0f, JoystickDirection.CENTER, radius, deadZone)
        val dRoll = pose.roll - tuning.centerRoll
        // Looking up (pitch rising) pushes the stick up, i.e. toward y = 0.
        val dPitch = -(pose.pitch - tuning.centerPitch)
        val tilt = sqrt(dRoll * dRoll + dPitch * dPitch)
        val dt = frameMs(nowMs)

        gateTilt = if (gateTilt.isNaN()) tilt else lowPass(gateTilt, tilt, GATE_TAU_MS, dt)
        val wasEngaged = engaged
        engaged = (if (wasEngaged) tilt else gateTilt) > (if (wasEngaged) zone * RELEASE_FRACTION else zone)
        if (!engaged) {
            x = 0f
            y = 0f
            fresh = true
            direction = JoystickDirection.CENTER
            return centered
        }

        // The reference implementation's shaping, and two details of it matter. The deflection is a
        // **magnitude** with the dead zone taken off that magnitude, so the direction is never
        // distorted by clamping each axis on its own; and the magnitude is shaped by an exponent
        // rather than ramped from zero, so just past the dead zone the stick jumps to a usable
        // deflection. That creeping-out-of-zero band was the "it sits in the middle and jitters"
        // report: right there the gain was highest and any tremor became visible movement.
        val normalizedX = dRoll / scale
        val normalizedY = dPitch / scale
        val magnitude = sqrt(normalizedX * normalizedX + normalizedY * normalizedY)
        // Inside the dead zone the stick is centred, full stop — even while the engage gate above is
        // still holding on through its hysteresis band. Without this the deflection collapsed to zero
        // but `direction` kept reporting the last octant (x and y decay towards zero without ever
        // reaching it), so the aim was dragged all the way back to the base and out again on the next
        // tilt. That is exactly what "the stick keeps recentring itself and moving" looks like.
        //
        // `fresh` is deliberately *not* set here: a genuine engage still snaps (the `!engaged` branch
        // above), but a brief dip back through the zone must not make the next tilt snap out again,
        // which would just be a different jump.
        if (magnitude <= deadZone) {
            x = 0f
            y = 0f
            direction = JoystickDirection.CENTER
            return centered
        }
        val reach = magnitude.coerceAtMost(1f).pow(RESPONSE_EXPONENT)
        val targetX = normalizedX / magnitude * reach
        val targetY = normalizedY / magnitude * reach

        if (fresh) {
            // Start where the head or the phone is, so engaging never sweeps in from the middle.
            x = targetX
            y = targetY
            fresh = false
        } else {
            val tau = joystickDampingTauMs(smoothing)
            x = lowPass(x, targetX, tau, dt)
            y = lowPass(y, targetY, tau, dt)
        }
        direction = JoystickMapper.directionOf(x, y, direction, DIRECTION_HYSTERESIS_DEGREES)
        return JoystickState(x, y, direction, radius, deadZone)
    }

    /** Forget everything, e.g. after the face was lost, so the stick doesn't sweep back in. */
    fun resetTracking() {
        engaged = false
        fresh = true
        x = 0f
        y = 0f
        gateTilt = Float.NaN
        direction = JoystickDirection.CENTER
        lastMs = 0L
    }

    /**
     * Time since the last reading. A timestamp that does not advance — the first call, or a caller
     * that has no clock — reads as one nominal frame, and a long gap reads as a single frame rather
     * than one giant filter step.
     */
    private fun frameMs(nowMs: Long): Float {
        val elapsed = if (lastMs == 0L || nowMs <= lastMs) NOMINAL_FRAME_MS else (nowMs - lastMs).coerceAtMost(MAX_FRAME_MS)
        lastMs = nowMs
        return elapsed.toFloat()
    }

    private companion object {
        /** The stick stays deflected until the tilt is this far back inside the dead zone. */
        const val RELEASE_FRACTION = 0.6f

        /**
         * Time constant of the engage gate: long enough that a single noisy sample cannot engage the
         * stick, short enough that a deliberate tilt engages within a frame or two.
         */
        const val GATE_TAU_MS = 40f

        /** A vector resting on an octant boundary keeps its direction until clearly past it. */
        const val DIRECTION_HYSTERESIS_DEGREES = 12f

        /**
         * How the deflection grows with the tilt, from the reference implementation: less than 1
         * boosts the mid range, so a comfortable tilt reaches real travel without a hair trigger near
         * the middle.
         */
        const val RESPONSE_EXPONENT = 0.85f

        /** Used when the timestamp does not advance, e.g. a caller that has no clock. */
        const val NOMINAL_FRAME_MS = 33L

        /** A gap this long is a pause, not a frame. */
        const val MAX_FRAME_MS = 200L
    }
}
