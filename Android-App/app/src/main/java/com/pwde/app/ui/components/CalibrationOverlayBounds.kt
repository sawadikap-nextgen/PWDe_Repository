package com.pwde.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.pwde.app.data.prefs.CalibrationOverlay
import com.pwde.app.data.prefs.CalibrationOverlayState

/**
 * The app-wide [CalibrationOverlayState], provided once by the nav host. Null outside it (previews,
 * tests), where a calibration screen simply has no live overlay to talk to.
 */
val LocalCalibrationOverlay = staticCompositionLocalOf<CalibrationOverlayState?> { null }

/** What a calibration screen needs the live pointer overlay to do while it is open. */
enum class CalibrationOverlayMode {
    /** The screen is tuning the pointer: it stays inside the calibration box. */
    CONFINE_TO_BOX,

    /** The screen is tuning something else (a joystick): the pointer goes away entirely. */
    HIDE_POINTER,
}

/**
 * Publishes [mode] to the live pointer overlay for as long as this composable is on screen, and
 * clears it again on pause or dispose, so the pointer returns to normal the moment the user leaves.
 *
 * For [CalibrationOverlayMode.CONFINE_TO_BOX] put the returned modifier on the calibration box — the
 * same view the pointer is drawn over. Its position is re-read whenever it moves (scrolling included)
 * and reported in display pixels, which is the space the accessibility overlay draws in.
 */
@Composable
fun rememberCalibrationOverlay(mode: CalibrationOverlayMode): Modifier {
    val state = LocalCalibrationOverlay.current ?: return Modifier
    val view = LocalView.current
    // The last box this composable was laid out at, so it can be re-published on resume without
    // waiting for another layout pass (returning from Android Settings doesn't re-lay-out).
    val box = remember { mutableStateOf<CalibrationOverlay.Box?>(null) }
    fun publish() {
        val current = box.value
        state.set(if (current != null) CalibrationOverlay.Confine(current) else CalibrationOverlay.Hidden)
    }
    // Whether the box the modifier is on is attached anywhere. A detached or zero-sized layout must
    // not report bounds: either would confine the pointer to nowhere.
    val laidOut = remember { mutableStateOf(false) }
    // Own the state only while PWDe is actually on screen: clearing on pause keeps the pointer from
    // being confined or hidden while the user is away in a game with the overlay up.
    LifecycleResumeEffect(mode) {
        if (laidOut.value) publish()
        onPauseOrDispose { state.clear() }
    }
    DisposableEffect(Unit) { onDispose { state.clear() } }
    if (mode != CalibrationOverlayMode.CONFINE_TO_BOX) return Modifier
    return Modifier.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        if (!coordinates.isAttached || bounds.width <= 0f || bounds.height <= 0f) {
            laidOut.value = false
            return@onGloballyPositioned
        }
        // boundsInWindow is window-relative; the overlay draws in display coordinates, so add where
        // this window sits on the display (the same origin the overlay view reports when drawing).
        val origin = IntArray(2).also { view.getLocationOnScreen(it) }
        box.value = CalibrationOverlay.Box(
            left = origin[0] + bounds.left,
            top = origin[1] + bounds.top,
            right = origin[0] + bounds.right,
            bottom = origin[1] + bounds.bottom,
        )
        laidOut.value = true
        publish()
    }
}
