package com.pwde.app.data.prefs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What a calibration screen is asking the live cursor overlay to do, while that screen is open.
 *
 * The overlay lives in [com.pwde.app.accessibility.PwdeAccessibilityService] and draws the head
 * pointer across the whole display, so it also draws on top of PWDe's own calibration screens. That
 * is wrong in two ways:
 *  - a pointer roaming the full screen during cursor calibration contradicts the calibration box the
 *    user is aiming at, and the small pointer drawn inside the box is what they are watching;
 *  - a joystick is steered by tilting, so a pointer wandering around while it is being tuned is
 *    just noise.
 *
 * So a screen that is holding a calibration publishes what it wants here, and the service obeys:
 * [Confine]s the pointer to the box it reports, [Hidden] removes it, and `null` (nothing published)
 * leaves the overlay exactly as it normally is.
 */
sealed interface CalibrationOverlay {

    /** No calibration screen is open: the pointer behaves normally, anywhere on the display. */
    data object None : CalibrationOverlay

    /** Move and hide the pointer entirely, e.g. while a joystick is being tuned. */
    data object Hidden : CalibrationOverlay

    /**
     * Keep the pointer inside [box], the calibration box in **raw display pixels** (the same space
     * the overlay's own window uses, since it covers the whole display). Edge touches are inside.
     */
    data class Confine(val box: Box) : CalibrationOverlay

    /** A rectangle in display pixels. [left]/[top] are relative to the display's top-left corner. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = (right - left).coerceAtLeast(0f)
        val height: Float get() = (bottom - top).coerceAtLeast(0f)
    }
}

/**
 * One instance on [com.pwde.app.di.AppContainer], shared the way [ButtonOverlayPrefs] is: Compose
 * screens publish into it, and the accessibility service reads it synchronously.
 */
class CalibrationOverlayState {
    private val _overlay = MutableStateFlow<CalibrationOverlay>(CalibrationOverlay.None)
    val overlay: StateFlow<CalibrationOverlay> = _overlay.asStateFlow()

    fun set(overlay: CalibrationOverlay) = _overlay.update { overlay }

    fun clear() = set(CalibrationOverlay.None)
}
