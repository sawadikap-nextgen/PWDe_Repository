package com.pwde.app.ui.gabai

import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.viewModelScope
import com.pwde.app.data.gabai.Axis
import com.pwde.app.data.gabai.GabAiFlow
import com.pwde.app.data.gabai.GabAiForm
import com.pwde.app.data.gabai.GabAiRepository
import com.pwde.app.data.gabai.GabAiSession
import com.pwde.app.data.gabai.GabAiState
import com.pwde.app.data.gabai.JoystickParameter
import com.pwde.app.data.gabai.HudDetector
import com.pwde.app.data.gabai.detectedToButtons
import com.pwde.app.data.local.CalibrationProfile
import com.pwde.app.data.local.ControlJson
import com.pwde.app.data.local.ControlsRepository
import com.pwde.app.data.local.GameProfile
import com.pwde.app.data.local.ProfileRepository
import com.pwde.app.data.local.toCalibrationProfile
import com.pwde.app.data.model.ButtonTrigger
import com.pwde.app.data.model.CursorTuning
import com.pwde.app.data.model.FaceOutputMode
import com.pwde.app.data.model.FacialGesture
import com.pwde.app.data.model.Game
import com.pwde.app.data.model.isEnabledBy
import com.pwde.app.data.model.JoystickTuning
import com.pwde.app.data.model.MappedButton
import com.pwde.app.data.model.TriggerType
import com.pwde.app.data.model.VoiceActivationMode
import com.pwde.app.data.model.VoiceMatchMode
import com.pwde.app.data.prefs.InputMode
import com.pwde.app.data.prefs.SettingsRepository
import com.pwde.app.play.GameInput
import com.pwde.app.play.LivePlay
import com.pwde.app.sensors.face.FaceTrackingManager
import com.pwde.app.sensors.voice.Dictation
import com.pwde.app.sensors.voice.InGameVoiceEngine
import com.pwde.app.sensors.voice.InGameVoiceResult
import com.pwde.app.sensors.voice.InGameVoiceState
import com.pwde.app.sensors.voice.VoiceCommandBinding
import com.pwde.app.sensors.voice.VoiceCommandManager
import com.pwde.app.ui.common.FaceTrackingViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How GabAI was opened. */
sealed interface GabAiStart {
    data object Welcome : GabAiStart
    data class NewGameProfile(val gameId: String?) : GabAiStart
    data class EditGameProfile(val profileId: Long) : GabAiStart
}

sealed interface GabAiNavigation {
    data object Exit : GabAiNavigation
    data object Dashboard : GabAiNavigation
    data class Play(val gameId: String, val profileId: Long) : GabAiNavigation
}

data class GabAiUiState(
    val loaded: Boolean = false,
    /** The conversation in progress; null on the Welcome screen before anything starts. */
    val session: GabAiSession? = null,
    /** An unfinished session the user can resume from Welcome. */
    val resumable: GabAiSession? = null,
    val screenshot: ImageBitmap? = null,
    val selectedButtonId: Int? = null,
    /** Prompting the user to say "assign <name>" for the selected button. */
    val capturingLabel: Boolean = false,
    /** The screenshot is being sent to the backend to find its buttons. */
    val detectingButtons: Boolean = false,
    val message: String? = null,
    /** "show controls": the list of buttons and what presses each. */
    val controlsShown: Boolean = false,
    /** The stage steps' GabAI sidebar is open (it can be tucked away to see the whole game). */
    val sidebarOpen: Boolean = true,
    /** The test step's latest result: a button pressed, or something heard that isn't one. */
    val testHit: TestHit? = null,
    val canUndoAutoMap: Boolean = false,
) {
    val state: GabAiState get() = session?.state ?: GabAiState.Welcome
    val form: GabAiForm get() = session?.form ?: GabAiForm()
}

/** One input on the test step. [buttonId] is null when it pressed nothing; [seq] makes a repeat flash again. */
data class TestHit(val buttonId: Int?, val text: String, val seq: Long)

/**
 * Orchestrates GabAI on top of what already exists: the pure [GabAiFlow] decides where to go,
 * [GabAiRepository] persists every step (so a force-close resumes exactly here), calibration
 * steps write straight to the live controls so tracking previews them, and saving goes through
 * [ProfileRepository].
 */
class GabAiViewModel(
    private val gabAiRepository: GabAiRepository,
    private val profileRepository: ProfileRepository,
    private val controlsRepository: ControlsRepository,
    private val settingsRepository: SettingsRepository,
    private val voiceCommandManager: VoiceCommandManager,
    faceTracking: FaceTrackingManager,
    start: GabAiStart,
    private val hudDetector: HudDetector = HudDetector.None,
    /** The speech model the test step presses mapped buttons with (see `InGameVoiceEngine.modelLabel`). */
    val buttonSpeechModel: String = "Unknown",
    /**
     * The engine the test step listens with. In-app it is the app-wide recognizer (`AppContainer`'s
     * `inAppVoiceEngine`): GabAI runs with PWDe on screen, where the user is already speaking to that
     * one, so the test hears exactly what a spoken button phrase would do. Only a live session over
     * the real game uses the sherpa-onnx spotter.
     */
    private val inGameVoice: InGameVoiceEngine? = null,
    /** The test waits for a live session over the real game to end: it takes the mic for itself. */
    private val livePlay: LivePlay? = null,
) : FaceTrackingViewModel(faceTracking) {
    /** The speech model behind GabAI's own voice commands and "assign/use" dictation. */
    val navigationSpeechModel: String get() = voiceCommandManager.modelLabel

    private val _ui = MutableStateFlow(GabAiUiState())
    val ui: StateFlow<GabAiUiState> = _ui.asStateFlow()

    val calibrationProfiles: StateFlow<List<CalibrationProfile>> = profileRepository.calibrationProfiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val gameProfiles: StateFlow<List<GameProfile>> = profileRepository.gameProfiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Gesture sensitivities from the working controls, so the gesture test can tune them live. */
    val gestureSensitivity: StateFlow<Map<FacialGesture, Int>> = controlsRepository.config
        .map { it.gestureSensitivity }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Gestures a button can use: all selectable gestures. */
    val triggerGestures: StateFlow<List<FacialGesture>> = MutableStateFlow(FacialGesture.selectable)

    private val _navigation = Channel<GabAiNavigation>(Channel.BUFFERED)
    val navigation: Flow<GabAiNavigation> = _navigation.receiveAsFlow()

    /** Screenshots are sent to the detection backend to pre-place buttons. */
    val autoDetectsButtons: Boolean get() = hudDetector.isAvailable

    /**
     * The step GabAI was opened on when another screen started it mid-flow (new or edited game
     * profile). Backing out of that step leaves GabAI for that screen instead of showing Welcome,
     * which the user never came through. Null when GabAI was opened on Welcome.
     */
    private var entryStep: GabAiState? = null
    private val openedMidFlow = start !is GabAiStart.Welcome

    /** The in-game voice engine's state while the test step listens (typed fallback when the mic is unavailable). */
    val testVoice: StateFlow<InGameVoiceState> = inGameVoice?.state ?: MutableStateFlow(InGameVoiceState())

    /** The test step holds the mic only while it's on screen. */
    private val testScreenVisible = MutableStateFlow(false)
    private var testSeq = 0L

    private var loadedScreenshotPath: String? = null
    private var nextButtonId = 1

    /** Session writes run one at a time, in order, so a late save can never undo a completion. */
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        viewModelScope.launch { for (write in writes) write() }
        viewModelScope.launch {
            val resumable = gabAiRepository.unfinishedSession()
            _ui.update { it.copy(loaded = true, resumable = resumable) }
            when (start) {
                GabAiStart.Welcome -> Unit
                is GabAiStart.NewGameProfile -> startGameProfile(start.gameId)
                is GabAiStart.EditGameProfile -> editGameProfile(start.profileId)
            }
        }
        viewModelScope.launch {
            // "assign <words>" / "use <words>" names the selected button or sets the voice trigger; "retry" undoes it.
            voiceCommandManager.results.collect { result ->
                if (!result.isFinal) return@collect
                when (val parsed = Dictation.parse(result.transcript)) {
                    is Dictation.Parsed.Assign -> assignByVoice(parsed.words)
                    Dictation.Parsed.Retry -> retryAssignment()
                    null -> Unit
                }
            }
        }
        viewModelScope.launch {
            // Watch the camera only while a gesture test step is showing.
            _ui.map { it.state as? GabAiState.CalibrationGestureTest }.distinctUntilChanged().collectLatest { test ->
                if (test != null) awaitGesture(test)
            }
        }
        viewModelScope.launch {
            combine(_ui.map { it.state == GabAiState.TestControls }.distinctUntilChanged(), testScreenVisible) { testing, visible -> testing && visible }
                .distinctUntilChanged()
                .collectLatest { if (it) runControlsTest() }
        }
        viewModelScope.launch {
            _ui.map { dictationTarget(it) != null }.distinctUntilChanged().collect {
                voiceCommandManager.setDictating(this@GabAiViewModel, it)
            }
        }
    }

    override fun onCleared() {
        voiceCommandManager.setDictating(this, false)
        testScreenVisible.value = false
        super.onCleared()
    }

    // ---- Welcome ----

    fun startCalibration() = viewModelScope.launch {
        val current = controlsRepository.config.first()
        val inputMode = settingsRepository.settings.first().inputMode
        val form = GabAiForm(
            calibrationMode = if (inputMode == InputMode.JOYSTICK) FaceOutputMode.JOYSTICK else FaceOutputMode.CURSOR,
            cursor = current.cursor,
            joystick = current.joystick,
            voiceEnabled = current.voiceEnabled,
            matchMode = current.voiceMatchMode,
            activationMode = current.voiceActivationMode,
        )
        begin(GabAiFlow.newCalibration(), form)
    }

    fun startGameProfile(gameId: String? = null) = viewModelScope.launch {
        val form = GabAiForm(gameId = gameId, calibrationProfileId = profileRepository.calibrationProfiles.first().firstOrNull()?.id)
        begin(GabAiFlow.newGameProfile(form), form)
    }

    fun resume() {
        val session = _ui.value.resumable ?: return
        _ui.update { it.copy(session = session, resumable = null) }
        onSessionLoaded(session.form)
    }

    fun editGameProfile(profileId: Long) = viewModelScope.launch {
        val profile = profileRepository.getGameProfile(profileId) ?: return@launch
        val form = GabAiForm(
            gameId = profile.gameId,
            calibrationProfileId = profile.calibrationProfileId,
            screenshotPath = profile.thumbnailPath,
            buttons = ControlJson.decodeButtons(profile.buttonMappingsJson),
            profileName = profile.profileName,
            editingGameProfileId = profile.id,
        )
        begin(GabAiState.ButtonMapping(form.buttons.size), form)
    }

    /** Starting something new replaces any unfinished session. */
    private fun begin(state: GabAiState, form: GabAiForm) {
        _ui.value.resumable?.let { old -> write { gabAiRepository.complete(old.id) } }
        val session = GabAiSession(gabAiRepository.newSessionId(), state, form)
        if (openedMidFlow && entryStep == null) entryStep = state
        _ui.update { it.copy(session = session, resumable = null, selectedButtonId = null, message = null) }
        onSessionLoaded(form)
        write { gabAiRepository.save(session) }
    }

    private fun onSessionLoaded(form: GabAiForm) {
        nextButtonId = (form.buttons.maxOfOrNull { it.id } ?: 0) + 1
        loadScreenshot(form.screenshotPath)
    }

    fun back() {
        val ui = _ui.value
        val session = ui.session
        if (session == null) {
            _navigation.trySend(GabAiNavigation.Exit)
            return
        }
        val atEntry = entryStep?.let { it::class == session.state::class } == true
        val previous = GabAiFlow.back(session.state, session.form)
        if (openedMidFlow && (atEntry || previous == null || previous == GabAiState.Welcome)) {
            // The session stays saved, so GabAI's Welcome offers to continue it next time.
            _navigation.trySend(GabAiNavigation.Exit)
        } else if (previous == null || previous == GabAiState.Welcome) {
            // The session stays saved, so Welcome offers to continue it.
            _ui.update { it.copy(session = null, resumable = if (session.state == GabAiState.ProfileSaved) null else session) }
        } else {
            go(previous)
        }
    }

    // ---- Calibration branch ----

    fun setCursor(tuning: CursorTuning) {
        updateForm { it.copy(cursor = tuning) }
        viewModelScope.launch { controlsRepository.setCursorTuning(tuning) }
    }

    fun axisDone(axis: Axis) = go(GabAiFlow.axisDone(axis))

    fun setJoystick(tuning: JoystickTuning) {
        updateForm { it.copy(joystick = tuning) }
        viewModelScope.launch { controlsRepository.setJoystickTuning(tuning) }
    }

    fun setJoystickCenterHere() {
        val pose = faceState.value.pose
        if (pose == null) {
            message("No head found — face the camera, then try again.")
            return
        }
        updateForm { it.copy(joystick = it.joystick.copy(centerPitch = pose.pitch, centerRoll = pose.roll)) }
        viewModelScope.launch { controlsRepository.setJoystickCenter(pose.pitch, pose.roll) }
        message("Center saved.")
    }

    fun joystickDone(parameter: JoystickParameter) = go(GabAiFlow.joystickDone(parameter))

    fun setVoice(enabled: Boolean? = null, match: VoiceMatchMode? = null, activation: VoiceActivationMode? = null) {
        updateForm {
            it.copy(
                voiceEnabled = enabled ?: it.voiceEnabled,
                matchMode = match ?: it.matchMode,
                activationMode = activation ?: it.activationMode,
            )
        }
        viewModelScope.launch {
            enabled?.let { controlsRepository.setVoiceEnabled(it) }
            match?.let { controlsRepository.setVoiceMatchMode(it) }
            activation?.let { controlsRepository.setVoiceActivationMode(it) }
            if (enabled == true) voiceCommandManager.refreshPermissions()
        }
    }

    fun voiceDone() = go(GabAiFlow.voiceDone(_ui.value.form))

    /**
     * Passes the gesture once the user performs it, then moves on. The gesture must start while
     * this step is showing, so a move still held from the previous step doesn't count.
     */
    private suspend fun awaitGesture(test: GabAiState.CalibrationGestureTest) {
        val gesture = GabAiState.GESTURE_TEST[test.index]
        if (gesture in _ui.value.form.passedGestures) return
        var released = false
        faceTracking.state.first { face ->
            val active = gesture in face.gesture.active
            if (!active) released = true
            active && released
        }
        updateForm { it.copy(passedGestures = it.passedGestures + gesture) }
        // Long enough to see "Got it!" before the next gesture.
        delay(GESTURE_PASSED_PAUSE_MS)
        val session = _ui.value.session ?: return
        if (session.state == test) go(GabAiFlow.gestureTested(test, session.form))
    }

    /** Next gesture; one not performed yet stays off. */
    fun nextGesture() {
        val session = _ui.value.session ?: return
        val test = session.state as? GabAiState.CalibrationGestureTest ?: return
        go(GabAiFlow.gestureTested(test, session.form))
    }

    fun skipRemainingGestures() = go(GabAiFlow.gestureTestEnded())

    fun retryMissedGestures() = go(GabAiFlow.retryMissedGestures(_ui.value.form))

    fun setGestureSensitivity(gesture: FacialGesture, level: Int) {
        viewModelScope.launch { controlsRepository.setGestureSensitivity(gesture, level) }
    }

    fun setCalibrationName(name: String) = updateForm { it.copy(calibrationName = name) }

    fun saveCalibration() = viewModelScope.launch {
        val form = _ui.value.form
        val name = form.calibrationName.trim().ifEmpty { defaultCalibrationName(form) }
        val current = controlsRepository.config.first()
        val config = current.copy(
            cursor = form.cursor,
            joystick = form.joystick,
            voiceEnabled = form.voiceEnabled,
            voiceMatchMode = form.matchMode,
            voiceActivationMode = form.activationMode,
            enabledGestures = form.passedGestures,
        ).let { config ->
            // An action mapped to a gesture the user couldn't perform would never fire; unmap it.
            config.copy(gestureAssignments = config.gestureAssignments.filterValues(config::isGestureEnabled))
        }
        // It's the active setup from now on, gestures included.
        controlsRepository.replace(config)
        val inputMode = if (form.calibrationMode == FaceOutputMode.JOYSTICK) InputMode.JOYSTICK else InputMode.HEAD_FACE
        val id = profileRepository.saveCalibrationProfile(config.toCalibrationProfile(name, inputMode, id = form.savedCalibrationId ?: 0))
        profileRepository.getCalibrationProfile(id)?.let { controlsRepository.applyCalibration(it) }
        updateForm { it.copy(calibrationName = name, savedCalibrationId = id, calibrationProfileId = id) }
        go(GabAiFlow.calibrationSaved())
    }

    fun calibrationDone() = finish(GabAiNavigation.Dashboard)

    fun continueToGame() = go(GabAiFlow.continueToGame(_ui.value.form))

    // ---- Game-profile branch ----

    fun chooseGame(game: Game) {
        updateForm { it.copy(gameId = game.id) }
        go(GabAiFlow.gameChosen())
    }

    fun chooseCalibration(id: Long) = updateForm { it.copy(calibrationProfileId = id) }

    fun confirmCalibration() {
        val form = _ui.value.form
        val chosen = form.calibrationProfileId
        if (chosen == null || calibrationProfiles.value.none { it.id == chosen }) {
            message("Pick a calibration profile, or make a new one.")
            return
        }
        viewModelScope.launch {
            calibrationProfiles.value.firstOrNull { it.id == chosen }?.let { profile ->
                // Button mapping previews with the calibration this game will use.
                controlsRepository.applyCalibration(profile)
            }
        }
        go(GabAiFlow.calibrationConfirmed())
    }

    /** No calibration yet (or the user wants a new one): calibrate, then come back here. */
    fun calibrateForThisGame() {
        val session = _ui.value.session ?: return
        viewModelScope.launch {
            val current = controlsRepository.config.first()
            val updated = session.form.copy(
                continueToGame = true,
                passedGestures = emptySet(),
                cursor = current.cursor,
                joystick = current.joystick,
                voiceEnabled = current.voiceEnabled,
                matchMode = current.voiceMatchMode,
                activationMode = current.voiceActivationMode,
            )
            persist(session.copy(state = GabAiFlow.newCalibration(), form = updated))
        }
    }

    fun importScreenshot(uri: Uri) = viewModelScope.launch {
        val path = gabAiRepository.importScreenshot(uri)
        if (path == null) {
            message("That file couldn't be opened as an image. Try another screenshot.")
        } else {
            updateForm { it.copy(screenshotPath = path) }
            loadScreenshot(path)
            detectButtons(path)
        }
    }

    /** Pre-places the buttons the backend model finds. Never overwrites buttons the user already placed. */
    private suspend fun detectButtons(path: String) {
        val gameId = _ui.value.form.gameId ?: return
        if (!hudDetector.isAvailable || _ui.value.form.buttons.isNotEmpty()) return
        _ui.update { it.copy(detectingButtons = true) }
        val found = runCatching { hudDetector.detect(path, gameId) }
        _ui.update { it.copy(detectingButtons = false) }
        // The user may have picked another screenshot, or started placing buttons, while this ran.
        if (_ui.value.form.screenshotPath != path || _ui.value.form.buttons.isNotEmpty()) return
        found.onSuccess { detected ->
            if (detected.isEmpty()) {
                message("No buttons found on this screenshot — you can place them yourself next.")
                return
            }
            val buttons = detectedToButtons(detected, nextButtonId)
            nextButtonId += buttons.size
            editButtons { buttons }
            val stick = if (buttons.any { it.trigger == ButtonTrigger.MOVEMENT }) ", including the movement joystick" else ""
            message("Found ${buttons.size} button${if (buttons.size == 1) "" else "s"}$stick. Check them on the next step.")
        }.onFailure {
            message("Couldn't reach button detection — you can place buttons yourself next.")
        }
    }

    fun useBlankScreen() {
        updateForm { it.copy(screenshotPath = null) }
        loadScreenshot(null)
        screenshotDone()
    }

    fun screenshotDone() = go(GabAiFlow.screenshotDone(_ui.value.form))

    fun addButton(x: Float, y: Float) {
        val button = MappedButton(nextButtonId++, "Button ${_ui.value.form.buttons.size + 1}", x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        editButtons { it + button }
        _ui.update { it.copy(selectedButtonId = button.id) }
    }

    /** Hands-free placement: drop a button wherever the head pointer is. */
    fun addButtonAtPointer() {
        val face = faceState.value
        if (!face.hasFace) {
            message("No head found — face the camera, then say \"place\" again.")
            return
        }
        addButton(face.cursor.x, face.cursor.y)
    }

    fun selectButton(id: Int?) = _ui.update { it.copy(selectedButtonId = id, capturingLabel = false) }

    fun moveButton(id: Int, x: Float, y: Float) =
        editButtons { list -> list.map { if (it.id == id) it.copy(x = x.coerceIn(0f, 1f), y = y.coerceIn(0f, 1f)) else it } }

    fun nudgeSelected(dx: Float, dy: Float) {
        val id = _ui.value.selectedButtonId ?: return
        val button = _ui.value.form.buttons.firstOrNull { it.id == id } ?: return
        moveButton(id, button.x + dx, button.y + dy)
    }

    fun renameButton(id: Int, label: String) =
        editButtons { list -> list.map { if (it.id == id) it.copy(label = label) else it } }

    fun deleteSelected() {
        val id = _ui.value.selectedButtonId ?: return
        editButtons { list -> list.filterNot { it.id == id } }
        _ui.update { it.copy(selectedButtonId = null, capturingLabel = false) }
    }

    fun captureLabelByVoice() {
        if (_ui.value.selectedButtonId == null) {
            message("Select a button first.")
            return
        }
        _ui.update { it.copy(capturingLabel = true) }
        message("Say \"assign\" and the name, like \"assign skill one\".")
    }

    // ---- Spoken assignment ----

    /** What "assign <words>" would change right now, if anything. */
    private sealed interface DictationTarget {
        data class Label(val buttonId: Int) : DictationTarget
        data class Trigger(val buttonId: Int) : DictationTarget
        data object CalibrationName : DictationTarget
        data object ProfileName : DictationTarget
    }

    /** What the last spoken assignment replaced, so "retry" can put it back. */
    private sealed interface Assignment {
        data class Label(val buttonId: Int, val previous: String) : Assignment
        data class Trigger(val buttonId: Int, val previous: ButtonTrigger?) : Assignment
        data class Name(val target: DictationTarget, val previous: String) : Assignment
    }

    private var lastAssignment: Assignment? = null

    private fun dictationTarget(ui: GabAiUiState): DictationTarget? = when (val state = ui.state) {
        is GabAiState.ButtonMapping -> ui.selectedButtonId?.let { DictationTarget.Label(it) }
        GabAiState.AssignTriggers -> ui.selectedButtonId?.let { DictationTarget.Trigger(it) }
        GabAiState.CalibrationGestureReview -> DictationTarget.CalibrationName
        GabAiState.NameAndSaveProfile -> DictationTarget.ProfileName
        else -> null
    }

    private fun assignByVoice(words: String) {
        val buttons = _ui.value.form.buttons
        when (val target = dictationTarget(_ui.value) ?: return) {
            is DictationTarget.Label -> {
                val button = buttons.firstOrNull { it.id == target.buttonId } ?: return
                lastAssignment = Assignment.Label(button.id, button.label)
                val label = words.replaceFirstChar { it.uppercase() }
                renameButton(button.id, label)
                _ui.update { it.copy(capturingLabel = false) }
                message("Named it \"$label\".")
            }
            is DictationTarget.Trigger -> {
                val button = buttons.firstOrNull { it.id == target.buttonId } ?: return
                if (button.trigger?.type == TriggerType.MOVEMENT) return
                lastAssignment = Assignment.Trigger(button.id, button.trigger)
                setTrigger(button.id, ButtonTrigger(TriggerType.VOICE, words))
                message("\"$words\" presses ${button.label}.")
            }
            DictationTarget.CalibrationName, DictationTarget.ProfileName -> {
                val name = words.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
                val calibration = target == DictationTarget.CalibrationName
                lastAssignment = Assignment.Name(target, if (calibration) _ui.value.form.calibrationName else _ui.value.form.profileName)
                if (calibration) setCalibrationName(name) else setProfileName(name)
                message("Named it \"$name\". Say \"save\", or \"retry\" to name it again.")
            }
        }
    }

    private fun retryAssignment() {
        val target = dictationTarget(_ui.value) ?: return
        when (val last = lastAssignment) {
            is Assignment.Label -> if (target == DictationTarget.Label(last.buttonId)) renameButton(last.buttonId, last.previous)
            is Assignment.Trigger -> if (target == DictationTarget.Trigger(last.buttonId)) setTrigger(last.buttonId, last.previous)
            is Assignment.Name -> if (target == last.target) {
                if (target == DictationTarget.CalibrationName) setCalibrationName(last.previous) else setProfileName(last.previous)
            }
            null -> Unit
        }
        lastAssignment = null
        if (target is DictationTarget.Label) {
            _ui.update { it.copy(capturingLabel = true) }
            message("Listening again — say \"assign\" and the name.")
        } else if (target !is DictationTarget.Trigger) {
            message("Listening again — say \"name it\" and the name.")
        } else {
            message("Listening again — say \"assign\" and what you'll say to press it.")
        }
    }

    fun buttonsDone() {
        if (_ui.value.form.buttons.isEmpty()) {
            message("Place at least one button first.")
            return
        }
        _ui.update { it.copy(selectedButtonId = null, capturingLabel = false, controlsShown = false) }
        go(GabAiFlow.buttonsDone(_ui.value.form))
    }

    fun setTrigger(buttonId: Int, trigger: ButtonTrigger?) =
        editButtons { list -> list.map { if (it.id == buttonId) it.copy(trigger = trigger) else it } }

    // ---- Trigger assignment: every button on one screen, a small chooser over the one picked ----

    /** Opens the voice-or-head chooser over [buttonId]; null closes it. */
    fun openTriggerChooser(buttonId: Int?) {
        lastAssignment = null
        // The chooser lives in the sidebar, so picking a button brings it back.
        _ui.update { it.copy(selectedButtonId = buttonId, capturingLabel = false, sidebarOpen = it.sidebarOpen || buttonId != null) }
    }

    /** A tapped suggested reply, handled exactly as if it had been said. */
    fun reply(phrase: String) {
        if (_ui.value.state == GabAiState.TestControls && inGameVoice != null) inGameVoice.submitText(phrase)
        else voiceCommandManager.submitText(phrase)
    }

    fun setSidebarOpen(open: Boolean) = _ui.update { it.copy(sidebarOpen = open) }

    /** A gesture is one pick, so it closes the chooser; a voice phrase keeps it open for "retry". */
    fun pickGesture(buttonId: Int, gesture: FacialGesture) {
        val button = _ui.value.form.buttons.firstOrNull { it.id == buttonId } ?: return
        setTrigger(buttonId, ButtonTrigger(TriggerType.GESTURE, gesture.name))
        openTriggerChooser(null)
        message("${gesture.label} presses ${button.label}.")
    }

    /** Opens the chooser on the next button still without a trigger (or simply the next one). */
    fun nextButtonToAssign() {
        val buttons = _ui.value.form.buttons
        if (buttons.isEmpty()) return
        val from = buttons.indexOfFirst { it.id == _ui.value.selectedButtonId }
        val order = buttons.indices.map { (from + 1 + it).mod(buttons.size) }
        val next = order.firstOrNull { buttons[it].trigger == null } ?: order.first()
        openTriggerChooser(buttons[next].id)
    }

    fun setControlsShown(shown: Boolean) = _ui.update { it.copy(controlsShown = shown) }

    /** Every button needs a trigger; then straight on to testing them. */
    fun triggersDone() {
        val unmapped = _ui.value.form.buttons.filter { it.trigger == null }
        if (unmapped.isNotEmpty()) {
            openTriggerChooser(unmapped.first().id)
            message(
                if (unmapped.size == 1) "${unmapped.first().label} still needs a trigger."
                else "${unmapped.size} left to map — starting with ${unmapped.first().label}.",
            )
            return
        }
        _ui.update { it.copy(selectedButtonId = null, controlsShown = false, testHit = null) }
        go(GabAiFlow.triggersDone(_ui.value.form))
    }

    // ---- Testing the new controls ----

    fun setTestScreenVisible(visible: Boolean) {
        testScreenVisible.value = visible
    }

    fun submitTestText(text: String) {
        inGameVoice?.submitText(text)
    }

    fun testingDone() {
        _ui.update { it.copy(controlsShown = false, testHit = null) }
        go(GabAiFlow.testingDone())
    }

    /** Back to the trigger screen to change something the test showed up. */
    fun changeMapping() {
        _ui.update { it.copy(controlsShown = false, testHit = null) }
        go(GabAiFlow.back(GabAiState.TestControls, _ui.value.form) ?: return)
    }

    /**
     * Listens the way gameplay does — gameplay's voice engine with only these buttons' phrases,
     * plus gesture events — and lights up whichever button the input would press.
     */
    private suspend fun runControlsTest() = coroutineScope {
        val buttons = _ui.value.form.buttons
        launch {
            faceTracking.gestureEvents.collect { gesture ->
                val button = buttons.firstOrNull { it.trigger?.gesture == gesture }
                testHit(button?.id, if (button != null) "${gesture.label} pressed ${button.label}" else "${gesture.label} isn't mapped to a button")
            }
        }
        val engine = inGameVoice ?: return@coroutineScope
        livePlay?.state?.first { !it.active }
        engine.loadCommands(GameInput.buttonBindings(buttons) + TEST_BINDINGS)
        engine.start()
        try {
            engine.results.collect { onTestVoice(it, buttons) }
        } finally {
            engine.stop()
        }
    }

    private fun onTestVoice(result: InGameVoiceResult, buttons: List<MappedButton>) {
        when (result.commandId) {
            TEST_SHOW_CONTROLS -> setControlsShown(true)
            TEST_HIDE_CONTROLS -> setControlsShown(false)
            TEST_DONE -> testingDone()
            TEST_CHANGE -> changeMapping()
            null -> result.rawText?.takeIf { it.isNotBlank() }?.let { testHit(null, "Heard \"$it\" — not one of your buttons") }
            else -> buttons.firstOrNull { GameInput.buttonCommandId(it.id) == result.commandId }?.let { button ->
                testHit(button.id, "\"${button.trigger?.value}\" pressed ${button.label}")
            }
        }
    }

    private fun testHit(buttonId: Int?, text: String) = _ui.update { it.copy(testHit = TestHit(buttonId, text, ++testSeq)) }

    fun setProfileName(name: String) = updateForm { it.copy(profileName = name) }

    fun saveGameProfile() = viewModelScope.launch {
        val form = _ui.value.form
        val game = Game.byId(form.gameId) ?: return@launch
        val existing = form.editingGameProfileId?.let { profileRepository.getGameProfile(it) }
        val name = form.profileName.trim().ifEmpty { defaultProfileName(game) }
        val profile = GameProfile(
            id = form.editingGameProfileId ?: 0,
            gameId = game.id,
            gameName = game.displayName,
            profileName = name,
            // A calibration deleted meanwhile would break the foreign key; save without the link instead.
            calibrationProfileId = form.calibrationProfileId?.takeIf { profileRepository.getCalibrationProfile(it) != null },
            buttonMappingsJson = ControlJson.encodeButtons(form.buttons),
            thumbnailPath = form.screenshotPath,
            createdAt = existing?.createdAt ?: 0,
            updatedAt = 0,
            remoteId = existing?.remoteId,
            lastSyncedAt = existing?.lastSyncedAt,
        )
        val id = profileRepository.saveGameProfile(profile)
        updateForm { it.copy(profileName = name, savedGameProfileId = id) }
        go(GabAiFlow.profileSaved())
        _ui.value.session?.let { done -> write { gabAiRepository.complete(done.id) } }
    }

    fun goToDashboard() = finish(GabAiNavigation.Dashboard)

    fun playNow() {
        val form = _ui.value.form
        val gameId = form.gameId ?: return
        val profileId = form.savedGameProfileId ?: return
        finish(GabAiNavigation.Play(gameId, profileId))
    }

    /** Another game profile, keeping the same calibration profile. */
    fun createAnother() = viewModelScope.launch {
        val calibration = _ui.value.form.calibrationProfileId
        val form = GabAiForm(calibrationProfileId = calibration)
        begin(GabAiFlow.createAnother(), form)
    }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    // ---- plumbing ----

    private fun finish(navigation: GabAiNavigation) {
        _ui.value.session?.let { done -> write { gabAiRepository.complete(done.id) } }
        _ui.update { it.copy(session = null, resumable = null) }
        _navigation.trySend(navigation)
    }

    private fun go(state: GabAiState) {
        val session = _ui.value.session ?: return
        persist(session.copy(state = state))
    }

    private fun updateForm(transform: (GabAiForm) -> GabAiForm) {
        val session = _ui.value.session ?: return
        persist(session.copy(form = transform(session.form)))
    }

    /** Button edits also keep ButtonMapping's count in step. */
    private fun editButtons(transform: (List<MappedButton>) -> List<MappedButton>) {
        val session = _ui.value.session ?: return
        val buttons = transform(session.form.buttons)
        val state = if (session.state is GabAiState.ButtonMapping) GabAiState.ButtonMapping(buttons.size) else session.state
        persist(session.copy(state = state, form = session.form.copy(buttons = buttons)))
    }

    private fun persist(session: GabAiSession) {
        _ui.update { it.copy(session = session, message = null) }
        write { gabAiRepository.save(session) }
    }

    private fun write(block: suspend () -> Unit) {
        writes.trySend(block)
    }

    private fun message(text: String) = _ui.update { it.copy(message = text) }

    private fun loadScreenshot(path: String?) {
        if (path == loadedScreenshotPath && (path == null || _ui.value.screenshot != null)) return
        loadedScreenshotPath = path
        viewModelScope.launch {
            val bitmap = gabAiRepository.loadScreenshot(path)?.asImageBitmap()
            if (loadedScreenshotPath == path) _ui.update { it.copy(screenshot = bitmap) }
        }
    }

    private companion object {
        const val GESTURE_PASSED_PAUSE_MS = 1_200L
        const val TEST_SHOW_CONTROLS = "test_show_controls"
        const val TEST_HIDE_CONTROLS = "test_hide_controls"
        const val TEST_DONE = "test_done"
        const val TEST_CHANGE = "test_change"

        /** What the test step understands besides the buttons' own phrases (it has the mic, like a game). */
        val TEST_BINDINGS = listOf(
            VoiceCommandBinding(TEST_SHOW_CONTROLS, GameInput.SHOW_CONTROLS_PHRASES),
            VoiceCommandBinding(TEST_HIDE_CONTROLS, GameInput.HIDE_CONTROLS_PHRASES),
            VoiceCommandBinding(TEST_DONE, TEST_DONE_PHRASES),
            VoiceCommandBinding(TEST_CHANGE, TEST_CHANGE_PHRASES),
        )
    }

    private fun defaultCalibrationName(form: GabAiForm): String =
        "My ${form.calibrationMode.label.lowercase()} setup ${calibrationProfiles.value.size + 1}"

    private fun defaultProfileName(game: Game): String = "${game.displayName} profile ${gameProfiles.value.count { it.gameId == game.id } + 1}"
    private var previousButtonsForUndo: List<MappedButton>? = null

    // 1. Function to go to the previous button
    fun previousButtonToAssign() {
        val buttons = _ui.value.form.buttons
        if (buttons.isEmpty()) return

        val from = buttons.indexOfFirst { it.id == _ui.value.selectedButtonId }
        val previousIndex = (from - 1 + buttons.size) % buttons.size

        openTriggerChooser(buttons[previousIndex].id)
    }

    // 2. Function to map all suggested words
    fun mapAllSuggestedWords() {
        val currentButtons = _ui.value.form.buttons

        // Save current state for undo
        previousButtonsForUndo = currentButtons

        // Apply the auto-mapping
        currentButtons.forEach { button ->
            setTrigger(button.id, ButtonTrigger(TriggerType.VOICE, button.label.lowercase()))
        }

        // Update UI state and close chooser
        _ui.update { it.copy(canUndoAutoMap = true) }
        openTriggerChooser(null)
        message("All buttons mapped to suggested words.")
    }

    // 3. Function to undo the auto-mapping
    fun undoMapAllSuggestedWords() {
        val previous = previousButtonsForUndo ?: return

        // Restore the previous button states
        editButtons { previous }

        // Clear the undo state
        previousButtonsForUndo = null
        _ui.update { it.copy(canUndoAutoMap = false) }
        message("Auto-mapping undone.")
    }
}

/** Said on the test step, where the in-game engine has the mic; the screen shows them too. */
internal val TEST_DONE_PHRASES = listOf("done testing", "finish testing", "looks good")
internal val TEST_CHANGE_PHRASES = listOf("change mapping", "edit mapping")
