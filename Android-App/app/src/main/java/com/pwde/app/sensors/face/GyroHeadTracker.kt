package com.pwde.app.sensors.face

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.exp

/**
 * The gyro joystick's input: tilting the phone steers Mobile Legends' movement stick, with the
 * camera left closed. See [GyroPoseMath] for which tilt means which direction.
 *
 * Reads the fused gravity sensor (gyroscope + accelerometer, already smooth and free of hand
 * jolts), falling back to the raw accelerometer, low-passed, on phones without one.
 *
 * The neutral is how the phone is held when this starts: the average of the first few readings, so
 * a jolt while picking it up doesn't become the centre. [rebaseline] ("set center here") takes it
 * again. Gravity doesn't drift, so the neutral is never moved behind the user's back.
 */
class GyroHeadTracker(context: Context) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

    /** Set by [rebaseline] and consumed by the next sample, on the sensor's own thread. */
    private val rebaselineRequested = AtomicBoolean(false)

    val isAvailable: Boolean get() = sensor != null

    /** The next readings become the new neutral: "wherever the phone is now is straight ahead". */
    fun rebaseline() {
        rebaselineRequested.set(true)
    }

    private fun displayRotation(): Int =
        displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: GyroPoseMath.ROTATION_0

    /** Emits (pose, timestampMs), relative to the neutral; [HeadPose.NEUTRAL] while it is being taken. */
    fun poses(): Flow<Pair<HeadPose, Long>> = callbackFlow {
        val manager = sensorManager
        val source = sensor
        if (manager == null || source == null) {
            close()
            return@callbackFlow
        }
        val rawAccelerometer = source.type == Sensor.TYPE_ACCELEROMETER
        val up = FloatArray(3)
        var haveUp = false
        var lastSampleMs = 0L

        var neutral: GyroPoseMath.Tilt? = null
        var neutralRotation = -1
        var sideSum = 0f
        var forwardSum = 0f
        var neutralSamples = 0

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val now = SystemClock.uptimeMillis()
                if (rawAccelerometer && haveUp) {
                    // The accelerometer also feels every shake; keep only the slow part, which is gravity.
                    val keep = exp(-(now - lastSampleMs).coerceAtLeast(1) / ACCELEROMETER_TAU_MS)
                    for (i in 0..2) up[i] = up[i] * keep + event.values[i] * (1f - keep)
                } else {
                    event.values.copyInto(up, endIndex = 3)
                }
                haveUp = true
                lastSampleMs = now

                val rotation = displayRotation()
                val tilt = GyroPoseMath.tilt(GyroPoseMath.toScreen(up, rotation)) ?: return
                // Turning the phone round for the game changes what "right" means: start again.
                if (rebaselineRequested.getAndSet(false) || rotation != neutralRotation) {
                    neutral = null
                    neutralRotation = rotation
                    sideSum = 0f
                    forwardSum = 0f
                    neutralSamples = 0
                }
                val anchor = neutral
                if (anchor == null) {
                    sideSum += tilt.side
                    forwardSum += tilt.forward
                    neutralSamples++
                    if (neutralSamples >= NEUTRAL_SAMPLES) {
                        neutral = GyroPoseMath.Tilt(sideSum / neutralSamples, forwardSum / neutralSamples)
                        Log.i(TAG, "Neutral taken: $neutral at rotation $rotation")
                    }
                    trySend(HeadPose.NEUTRAL to now)
                    return
                }
                trySend(GyroPoseMath.pose(anchor, tilt) to now)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager.registerListener(listener, source, SensorManager.SENSOR_DELAY_GAME)
        awaitClose { manager.unregisterListener(listener) }
    }

    private companion object {
        private const val TAG = "GyroHeadTracker"

        /** Readings averaged into the neutral: about a fifth of a second at SENSOR_DELAY_GAME. */
        const val NEUTRAL_SAMPLES = 10

        /** The raw accelerometer's low-pass time constant, in ms. */
        const val ACCELEROMETER_TAU_MS = 80f
    }
}
