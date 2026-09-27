package com.pwde.app.ui.setup

import androidx.lifecycle.viewModelScope
import com.pwde.app.data.gabai.Axis
import com.pwde.app.data.local.ControlsRepository
import com.pwde.app.data.model.CursorTuning
import com.pwde.app.data.prefs.AccessibilityNeed
import com.pwde.app.data.prefs.ColorSchemeOption
import com.pwde.app.data.prefs.LayoutMode
import com.pwde.app.data.prefs.SettingsRepository
import com.pwde.app.data.prefs.TextSizeOption
import com.pwde.app.sensors.face.FaceTrackingManager
import com.pwde.app.ui.common.FaceTrackingViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Setup, in order. Everything Android has to grant lives on the single permissions step, so the
 * user is asked for camera, microphone and the accessibility service in one place before anything
 * else happens — in particular before the cursor boots, which is why it comes first.
 */
enum class SetupStep(val label: String) {

    PERMISSIONS("Permissions"),
    CURSOR_CALIBRATION("Cursor calibration"),
    NEEDS("What you need"),
    APPEARANCE("How it looks"),

}

data class SetupUiState(
    val loaded: Boolean = false,
    val steps: List<SetupStep> = SetupStep.entries,
    val stepIndex: Int = 0,
    val needs: Set<AccessibilityNeed> = emptySet(),
    val colorScheme: ColorSchemeOption = ColorSchemeOption.DEFAULT,
    val textSize: TextSizeOption = TextSizeOption.MEDIUM,
    val layoutMode: LayoutMode = LayoutMode.STANDARD,
    /** Which direction the cursor calibration step (B7) is currently on. */
    val axis: Axis = Axis.entries.first(),
    val cursor: CursorTuning = CursorTuning(),
    /**
     * What Android has granted, reported by the permissions step so it can hold the user there.
     * Camera and microphone are the one pair setup cannot proceed without; the accessibility
     * service is asked for on the same screen but stays optional.
     */
    val cameraGranted: Boolean = false,
    val micGranted: Boolean = false,
    val finished: Boolean = false,
) {
    val step: SetupStep get() = steps[stepIndex]
    val isLastStep: Boolean get() = stepIndex == steps.lastIndex

    /**
     * True while the permissions step (B5) still misses the camera or the microphone: Continue and
     * Skip are both refused, so the only ways on are allowing them or leaving setup.
     */
    val permissionBlocked: Boolean get() = permissionsGateBlocks(this)
}

/**
 * The permissions step is a hard gate on the camera and the microphone — the only things PWDe
 * truly cannot work without (head tracking and voice commands). It comes first so the cursor is
 * already head-tracked, rather than falling back to gyro, when calibration boots it.
 */
private fun permissionsGateBlocks(state: SetupUiState): Boolean =
    state.step == SetupStep.PERMISSIONS && !(state.cameraGranted && state.micGranted)

/**
 * Step edits are held as a draft (so the Setup screen can preview them live) and written to
 * [SettingsRepository] only when the user taps Continue on that step. The **permissions** step
 * (B5) is the exception and the one hard gate: Android grants the camera and microphone itself,
 * so it saves nothing, and the step cannot be left — by Continue or by Skip — until both are on.
 * The cursor calibration step (B7) is GabAI's own axis-by-axis walkthrough, reused here; like
 * GabAI it writes each adjustment straight to [ControlsRepository] as it's made, so there is
 * nothing to commit on Continue either.
 *
 * @param appearanceOnly opened from Profile to change just the look; finishing returns there.
 */
class SetupViewModel(
    private val settingsRepository: SettingsRepository,
    private val controlsRepository: ControlsRepository,
    private val appearanceOnly: Boolean,
    faceTracking: FaceTrackingManager,
) : FaceTrackingViewModel(faceTracking) {
    private val _state = MutableStateFlow(
        SetupUiState(steps = if (appearanceOnly) listOf(SetupStep.APPEARANCE) else SetupStep.entries),
    )
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = settingsRepository.settings.first()
            val cursor = controlsRepository.config.first().cursor
            _state.update {
                it.copy(
                    loaded = true,
                    needs = saved.accessibilityNeeds,
                    colorScheme = saved.colorScheme,
                    textSize = saved.textSize,
                    layoutMode = saved.layoutMode,
                    cursor = cursor,
                )
            }
        }
    }

    fun toggleNeed(need: AccessibilityNeed) = _state.update {
        it.copy(needs = if (need in it.needs) it.needs - need else it.needs + need)
    }

    fun setColorScheme(option: ColorSchemeOption) = _state.update { it.copy(colorScheme = option) }

    fun setTextSize(option: TextSizeOption) = _state.update { it.copy(textSize = option) }

    fun setLayoutMode(mode: LayoutMode) = _state.update { it.copy(layoutMode = mode) }

    /**
     * The permissions step reported what Android granted (camera, microphone). Recorded in state so
     * [continueStep] and [skipStep] can refuse to leave the step while either is missing.
     */
    fun setPermissions(cameraGranted: Boolean, micGranted: Boolean) = _state.update {
        it.copy(cameraGranted = cameraGranted, micGranted = micGranted)
    }

    /**
     * Keeps the pointer preview alive for the cursor calibration step. Two things have to happen for
     * the camera to actually boot there:
     *  - something must collect [FaceTrackingViewModel.faceState] (tracking only runs while a screen
     *    is observing it; the step's own CameraFeed does that);
     *  - a tracking session that started before the camera was allowed has to be restarted. The
     *    accessibility service collects the same state from the moment "Use PWDe" is switched on, so
     *    with the old ordering the session had already fallen back to the simulated demo by the time
     *    the user allowed the camera, and nothing bumped [FaceTrackingManager.refreshPermissions].
     */
    fun refreshPreview() = faceTracking.refreshPermissions()

    /** Applies one axis's speed/smoothing, live, exactly as GabAI's own calibration does. */
    fun setCursor(tuning: CursorTuning) {
        _state.update { it.copy(cursor = tuning) }
        viewModelScope.launch { controlsRepository.setCursorTuning(tuning) }
    }

    /** Moves to the next direction; on the last one, finishes the step like Continue would. */
    fun axisDone() {
        val next = _state.value.axis.next()
        if (next != null) {
            _state.update { it.copy(axis = next) }
        } else {
            continueStep()
        }
    }

    /** Saves this step, then moves on. Refused on the permissions step until camera and mic are on. */
    fun continueStep() {
        val s = _state.value
        if (s.permissionBlocked) return
        viewModelScope.launch {
            when (s.step) {
                SetupStep.PERMISSIONS,
                SetupStep.CURSOR_CALIBRATION,
                SetupStep.APPEARANCE -> settingsRepository.setAppearance(s.colorScheme, s.textSize, s.layoutMode)
                SetupStep.NEEDS -> settingsRepository.setAccessibilityNeeds(s.needs)
            }
            advance()
        }
    }

    /**
     * Moves on without saving; this step's draft is reset to what is saved. Refused on the
     * permissions step: camera and microphone are the one thing setup cannot do without.
     */
    fun skipStep() {
        if (_state.value.permissionBlocked) return
        viewModelScope.launch {
            val saved = settingsRepository.settings.first()
            _state.update {
                when (it.step) {
                    SetupStep.PERMISSIONS,
                    SetupStep.CURSOR_CALIBRATION,
                    SetupStep.NEEDS -> it.copy(needs = saved.accessibilityNeeds)
                    SetupStep.APPEARANCE -> it.copy(
                        colorScheme = saved.colorScheme,
                        textSize = saved.textSize,
                        layoutMode = saved.layoutMode,
                    )

                }
            }
            advance()
        }
    }

    /** @return false when already on the first step (caller should leave the screen). */
    fun back(): Boolean {
        val s = _state.value
        if (s.step == SetupStep.CURSOR_CALIBRATION) {
            val previousAxis = s.axis.previous()
            if (previousAxis != null) {
                _state.update { it.copy(axis = previousAxis) }
                return true
            }
        }
        if (s.stepIndex == 0) return false
        _state.update { it.copy(stepIndex = it.stepIndex - 1, axis = Axis.entries.first()) }
        return true
    }

    private suspend fun advance() {
        val s = _state.value
        if (s.isLastStep) {
            if (!appearanceOnly) settingsRepository.setSetupCompleted(true)
            _state.update { it.copy(finished = true) }
        } else {
            _state.update { it.copy(stepIndex = it.stepIndex + 1, axis = Axis.entries.first()) }
        }
    }
}