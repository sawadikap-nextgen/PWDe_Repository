package com.pwde.app.data.gabai

import com.google.gson.Gson
import com.pwde.app.data.local.ControlJson
import com.pwde.app.data.model.CursorTuning
import com.pwde.app.data.model.FaceOutputMode
import com.pwde.app.data.model.FacialGesture
import com.pwde.app.data.model.JoystickTuning
import com.pwde.app.data.model.MappedButton
import com.pwde.app.data.model.VoiceActivationMode
import com.pwde.app.data.model.VoiceMatchMode

/** Cursor directions GabAI calibrates one at a time, in this order. */
enum class Axis(val label: String) {
    UP("Up"), DOWN("Down"), LEFT("Left"), RIGHT("Right"), DIAGONAL("Diagonal");

    fun next(): Axis? = entries.getOrNull(ordinal + 1)
    fun previous(): Axis? = entries.getOrNull(ordinal - 1)
}

enum class JoystickParameter(val label: String) {
    SENSITIVITY("Sensitivity"), DEAD_ZONE("Dead zone");

    fun next(): JoystickParameter? = entries.getOrNull(ordinal + 1)
    fun previous(): JoystickParameter? = entries.getOrNull(ordinal - 1)
}

/** GabAI is a scripted, resumable conversation. Every screen is exactly one of these states. */
sealed class GabAiState {
    object Welcome : GabAiState() // New Calibration / New Game Profile / Continue Existing

    // --- Calibration branch ---
    data class CalibrateCursorAxis(val axis: Axis) : GabAiState() // repeats per direction
    data class CalibrateJoystick(val parameter: JoystickParameter) : GabAiState()
    object CalibrationVoiceSetup : GabAiState() // voice on/off, matching mode, activation mode
    data class CalibrationGestureTest(val index: Int) : GabAiState() // perform GESTURE_TEST[index], or skip it
    object CalibrationGestureReview : GabAiState() // which gestures are on, retry missed ones, name and save
    object CalibrationSaved : GabAiState() // Done, or continue into the game-profile branch

    // --- Game-profile branch ---
    object ChooseGame : GabAiState()
    object ConfirmCalibrationProfile : GabAiState() // use existing or switch
    object UploadScreenshot : GabAiState()
    data class ButtonMapping(val buttonsPlaced: Int) : GabAiState()
    object AssignTriggers : GabAiState() // every button on one screen; pick one to choose voice or head
    object TestControls : GabAiState() // try the new mapping: presses light up on the screenshot
    object NameAndSaveProfile : GabAiState()
    object ProfileSaved : GabAiState() // Dashboard, or Create Another

    companion object {
        /** The gestures the calibration's gesture test asks for, in order. Raw blendshapes aren't tested. */
        val GESTURE_TEST: List<FacialGesture> = FacialGesture.curated
    }

    /** Short description for "Continue where you left off". */
    val summary: String
        get() = when (this) {
            Welcome -> "Start"
            is CalibrateCursorAxis -> "Calibration: cursor ${axis.label.lowercase()}"
            is CalibrateJoystick -> "Calibration: joystick ${parameter.label.lowercase()}"
            CalibrationVoiceSetup -> "Calibration: voice"
            is CalibrationGestureTest -> "Calibration: gesture ${index + 1} of ${GESTURE_TEST.size}"
            CalibrationGestureReview -> "Calibration: gesture results"
            CalibrationSaved -> "Calibration saved"
            ChooseGame -> "Game profile: choose a game"
            ConfirmCalibrationProfile -> "Game profile: pick a calibration"
            UploadScreenshot -> "Game profile: screenshot"
            is ButtonMapping -> "Game profile: placing buttons ($buttonsPlaced so far)"
            AssignTriggers -> "Game profile: choosing how to press each button"
            TestControls -> "Game profile: testing your controls"
            NameAndSaveProfile -> "Game profile: name and save"
            ProfileSaved -> "Game profile saved"
        }
}

/** Everything the user has entered in a GabAI session so far. */
data class GabAiForm(
    val calibrationMode: FaceOutputMode = FaceOutputMode.CURSOR,
    val cursor: CursorTuning = CursorTuning(),
    val joystick: JoystickTuning = JoystickTuning(),
    val voiceEnabled: Boolean = true,
    val matchMode: VoiceMatchMode = VoiceMatchMode.WORD_ANYWHERE,
    val activationMode: VoiceActivationMode = VoiceActivationMode.IMMEDIATE,
    /** Gestures performed in the gesture test so far; only these are enabled in the saved calibration. */
    val passedGestures: Set<FacialGesture> = emptySet(),
    val calibrationName: String = "",
    /** The calibration profile saved in this session, if any. */
    val savedCalibrationId: Long? = null,
    /** Set when a game profile needed a calibration first; saving it continues to the game. */
    val continueToGame: Boolean = false,
    val gameId: String? = null,
    val calibrationProfileId: Long? = null,
    val screenshotPath: String? = null,
    val buttons: List<MappedButton> = emptyList(),
    val profileName: String = "",
    /** Editing an existing game profile rather than creating one. */
    val editingGameProfileId: Long? = null,
    val savedGameProfileId: Long? = null,
    /** Manual mapping: no calibration steps and no button detection; the screenshot is only a backdrop. */
    val manual: Boolean = false,
)

/** State and form ⇄ the strings stored in Room. Anything unreadable falls back safely. */
object GabAiCodec {
    private val gson = Gson()

    fun encodeState(state: GabAiState): String = when (state) {
        is GabAiState.CalibrateCursorAxis -> "CalibrateCursorAxis:${state.axis.name}"
        is GabAiState.CalibrationGestureTest -> "CalibrationGestureTest:${state.index}"
        is GabAiState.ButtonMapping -> "ButtonMapping:${state.buttonsPlaced}"
        // Spelled out rather than taken from class names, which minification would rename.
        GabAiState.Welcome -> "Welcome"
        is GabAiState.CalibrateJoystick -> "CalibrateJoystick:${state.parameter.name}"
        GabAiState.CalibrationVoiceSetup -> "CalibrationVoiceSetup"
        GabAiState.CalibrationGestureReview -> "CalibrationGestureReview"
        GabAiState.CalibrationSaved -> "CalibrationSaved"
        GabAiState.ChooseGame -> "ChooseGame"
        GabAiState.ConfirmCalibrationProfile -> "ConfirmCalibrationProfile"
        GabAiState.UploadScreenshot -> "UploadScreenshot"
        GabAiState.AssignTriggers -> "AssignTriggers"
        GabAiState.TestControls -> "TestControls"
        GabAiState.NameAndSaveProfile -> "NameAndSaveProfile"
        GabAiState.ProfileSaved -> "ProfileSaved"
    }

    fun decodeState(value: String?): GabAiState {
        val parts = value.orEmpty().split(':')
        return when (parts[0]) {
            "Welcome" -> GabAiState.Welcome
            // Older sessions paused at the removed mode picker resume with cursor calibration.
            "ChooseCalibrationMode" -> GabAiState.CalibrateCursorAxis(Axis.UP)
            "CalibrateCursorAxis" -> Axis.entries.firstOrNull { it.name == parts.getOrNull(1) }
                ?.let { GabAiState.CalibrateCursorAxis(it) } ?: GabAiState.CalibrateCursorAxis(Axis.UP)
            "CalibrateJoystick" -> JoystickParameter.entries.firstOrNull { it.name == parts.getOrNull(1) }
                ?.let { GabAiState.CalibrateJoystick(it) } ?: GabAiState.CalibrateJoystick(JoystickParameter.SENSITIVITY)
            "CalibrationVoiceSetup" -> GabAiState.CalibrationVoiceSetup
            "CalibrationGestureTest" -> parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in GabAiState.GESTURE_TEST.indices }
                ?.let { GabAiState.CalibrationGestureTest(it) } ?: GabAiState.CalibrationGestureTest(0)
            "CalibrationGestureReview" -> GabAiState.CalibrationGestureReview
            "CalibrationSaved" -> GabAiState.CalibrationSaved
            "ChooseGame" -> GabAiState.ChooseGame
            "ConfirmCalibrationProfile" -> GabAiState.ConfirmCalibrationProfile
            "UploadScreenshot" -> GabAiState.UploadScreenshot
            "ButtonMapping" -> GabAiState.ButtonMapping(parts.getOrNull(1)?.toIntOrNull() ?: 0)
            "AssignTriggers" -> GabAiState.AssignTriggers
            "TestControls" -> GabAiState.TestControls
            // Sessions saved before assignment moved onto one screen resume on that screen.
            "TriggerAssignment" -> GabAiState.AssignTriggers
            "NameAndSaveProfile" -> GabAiState.NameAndSaveProfile
            "ProfileSaved" -> GabAiState.ProfileSaved
            else -> GabAiState.Welcome
        }
    }

    fun encodeForm(form: GabAiForm): String = gson.toJson(FormJson.from(form))

    fun decodeForm(json: String?): GabAiForm =
        json?.let { runCatching { gson.fromJson(it, FormJson::class.java) }.getOrNull() }?.toForm() ?: GabAiForm()

    /** Every field nullable, so JSON from an older version still loads with defaults. */
    private data class FormJson(
        val calibrationMode: String? = null,
        val speedUp: Int? = null, val speedDown: Int? = null, val speedLeft: Int? = null, val speedRight: Int? = null,
        val smoothing: Int? = null,
        val joystickSize: Int? = null, val joystickSensitivity: Int? = null, val joystickDeadZone: Int? = null,
        val joystickCenterPitch: Float? = null, val joystickCenterRoll: Float? = null,
        val voiceEnabled: Boolean? = null, val matchMode: String? = null, val activationMode: String? = null,
        val passedGestures: List<String>? = null,
        val calibrationName: String? = null, val savedCalibrationId: Long? = null, val continueToGame: Boolean? = null,
        val gameId: String? = null, val calibrationProfileId: Long? = null, val screenshotPath: String? = null,
        val buttonsJson: String? = null, val profileName: String? = null,
        val editingGameProfileId: Long? = null, val savedGameProfileId: Long? = null,
        val manual: Boolean? = null,
    ) {
        fun toForm(): GabAiForm {
            val d = GabAiForm()
            return GabAiForm(
                calibrationMode = FaceOutputMode.entries.firstOrNull { it.name == calibrationMode } ?: d.calibrationMode,
                cursor = CursorTuning(
                    speedUp ?: d.cursor.speedUp, speedDown ?: d.cursor.speedDown, speedLeft ?: d.cursor.speedLeft,
                    speedRight ?: d.cursor.speedRight, smoothing ?: d.cursor.smoothing,
                ),
                joystick = JoystickTuning(
                    joystickSize ?: d.joystick.size, joystickSensitivity ?: d.joystick.sensitivity,
                    joystickDeadZone ?: d.joystick.deadZone, joystickCenterPitch ?: 0f, joystickCenterRoll ?: 0f,
                ),
                voiceEnabled = voiceEnabled ?: d.voiceEnabled,
                matchMode = VoiceMatchMode.entries.firstOrNull { it.name == matchMode } ?: d.matchMode,
                activationMode = VoiceActivationMode.entries.firstOrNull { it.name == activationMode } ?: d.activationMode,
                passedGestures = passedGestures.orEmpty().mapNotNull { name -> FacialGesture.entries.firstOrNull { it.name == name } }.toSet(),
                calibrationName = calibrationName.orEmpty(),
                savedCalibrationId = savedCalibrationId,
                continueToGame = continueToGame ?: false,
                gameId = gameId,
                calibrationProfileId = calibrationProfileId,
                screenshotPath = screenshotPath,
                buttons = ControlJson.decodeButtons(buttonsJson),
                profileName = profileName.orEmpty(),
                editingGameProfileId = editingGameProfileId,
                savedGameProfileId = savedGameProfileId,
                manual = manual ?: false,
            )
        }

        companion object {
            fun from(f: GabAiForm) = FormJson(
                calibrationMode = f.calibrationMode.name,
                speedUp = f.cursor.speedUp, speedDown = f.cursor.speedDown, speedLeft = f.cursor.speedLeft,
                speedRight = f.cursor.speedRight, smoothing = f.cursor.smoothing,
                joystickSize = f.joystick.size, joystickSensitivity = f.joystick.sensitivity, joystickDeadZone = f.joystick.deadZone,
                joystickCenterPitch = f.joystick.centerPitch, joystickCenterRoll = f.joystick.centerRoll,
                voiceEnabled = f.voiceEnabled, matchMode = f.matchMode.name, activationMode = f.activationMode.name,
                passedGestures = f.passedGestures.map { it.name },
                calibrationName = f.calibrationName, savedCalibrationId = f.savedCalibrationId, continueToGame = f.continueToGame,
                gameId = f.gameId, calibrationProfileId = f.calibrationProfileId, screenshotPath = f.screenshotPath,
                buttonsJson = ControlJson.encodeButtons(f.buttons), profileName = f.profileName,
                editingGameProfileId = f.editingGameProfileId, savedGameProfileId = f.savedGameProfileId,
                manual = f.manual,
            )
        }
    }
}
