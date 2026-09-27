package com.pwde.app.ui.gameplay

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.viewModelScope
import com.pwde.app.data.gabai.GabAiRepository
import com.pwde.app.data.local.ControlJson
import com.pwde.app.data.local.ControlsRepository
import com.pwde.app.data.local.GameProfile
import com.pwde.app.data.local.ProfileRepository
import com.pwde.app.data.model.ControlConfig
import com.pwde.app.data.model.FaceOutputMode
import com.pwde.app.data.model.FacialGesture
import com.pwde.app.data.model.Game
import com.pwde.app.data.model.MappedButton
import com.pwde.app.data.model.NavigationMode
import com.pwde.app.data.model.navigationModeFor
import com.pwde.app.data.prefs.SettingsRepository
import com.pwde.app.play.GameCommand
import com.pwde.app.play.GameInput
import com.pwde.app.play.isPauseBlockedInGameMode
import com.pwde.app.play.LivePlay
import com.pwde.app.play.applyProfileCalibration
import com.pwde.app.sensors.face.FaceTrackingManager
import com.pwde.app.sensors.face.JoystickDirection
import com.pwde.app.sensors.voice.InGameVoiceEngine
import com.pwde.app.sensors.voice.InGameVoiceState
import com.pwde.app.ui.common.FaceTrackingViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Something the overlay did, shown briefly. [id] increases so repeated actions still animate. */
data class OverlayEvent(val id: Long, val text: String, val kind: Kind, val buttonId: Int? = null) {
    enum class Kind { ACTION, SELECT, BUTTON, IGNORED }
}

data class GameplayUiState(
    val profile: GameProfile? = null,
    val calibrationName: String? = null,
    val buttons: List<MappedButton> = emptyList(),
    val screenshot: ImageBitmap? = null,
)

/**
 * The play overlay. Face input comes from [FaceTrackingManager]; voice comes only from
 * [InGameVoiceEngine] — this class never names a concrete engine or the platform recognizer,
 * so swapping in a dedicated engine touches only the AppContainer binding.
 */
class GameplayViewModel(
    faceTracking: FaceTrackingManager,
    private val voiceEngine: InGameVoiceEngine,
    controlsRepository: ControlsRepository,
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository,
    private val gabAiRepository: GabAiRepository,
    val game: Game?,
    private val profileId: Long?,
    private val livePlay: LivePlay? = null,
) : FaceTrackingViewModel(faceTracking) {
    val voice: StateFlow<InGameVoiceState> = voiceEngine.state

    private val config: StateFlow<ControlConfig> = controlsRepository.config
        .stateIn(viewModelScope, SharingStarted.Eagerly, ControlConfig())

    private val _ui = MutableStateFlow(GameplayUiState())
    val ui: StateFlow<GameplayUiState> = _ui.asStateFlow()

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    /** Status pills and the info panel are hidden so the game (or screenshot) shows through. */
    private val _overlayHidden = MutableStateFlow(false)
    val overlayHidden: StateFlow<Boolean> = _overlayHidden.asStateFlow()

    /** "show controls": the list of buttons and what presses each, until "hide controls". */
    private val _controlsShown = MutableStateFlow(false)
    val controlsShown: StateFlow<Boolean> = _controlsShown.asStateFlow()

    /** The center brake for this preview, mirroring the live session's. */
    private val _centerLocked = MutableStateFlow(false)
    val centerLocked: StateFlow<Boolean> = _centerLocked.asStateFlow()

    fun setControlsShown(shown: Boolean) {
        _controlsShown.value = shown
    }

    private val _lastEvent = MutableStateFlow<OverlayEvent?>(null)
    val lastEvent: StateFlow<OverlayEvent?> = _lastEvent.asStateFlow()

    /**
     * An explicit "game mode" / "navigation mode" for this preview. The live session keeps this on
     * its own state instead (it is shared with the accessibility service); the same rule decides both.
     */
    private val _navigationOverride = MutableStateFlow<NavigationMode?>(null)

    /** Game mode / navigation mode, for the panel — never let the current mode be a mystery. */
    val navigationMode: StateFlow<NavigationMode> =
        combine(_navigationOverride, faceState) { override, face -> navigationModeFor(override, face.outputMode) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, NavigationMode.NAVIGATION)

    private val _exit = Channel<Unit>(Channel.CONFLATED)
    val exitRequests: Flow<Unit> = _exit.receiveAsFlow()

    private var eventId = 0L
    private var lastDirection = JoystickDirection.CENTER

    init {
        voiceEngine.loadCommands(GameInput.STANDARD_BINDINGS)
        viewModelScope.launch {
            val profile = profileId?.let { profileRepository.getGameProfile(it) }
                ?: game?.let { profileRepository.gameProfilesFor(it.id).firstOrNull()?.firstOrNull() }
            if (profile != null) loadProfile(profile, controlsRepository)
        }
        viewModelScope.launch { voiceEngine.results.collect { onVoice(it.commandId, it.rawText) } }
        viewModelScope.launch { faceTracking.gestureEvents.collect(::onGesture) }
    }

    private suspend fun loadProfile(profile: GameProfile, controlsRepository: ControlsRepository) {
        val buttons = ControlJson.decodeButtons(profile.buttonMappingsJson)
        val calibration = applyProfileCalibration(profile, profileRepository, controlsRepository, settingsRepository)
        _ui.update { it.copy(profile = profile, calibrationName = calibration?.name, buttons = buttons) }
        // Only this game's commands can be recognized while playing.
        voiceEngine.loadCommands(GameInput.bindings(buttons))
        val bitmap = gabAiRepository.loadScreenshot(profile.thumbnailPath)?.asImageBitmap()
        _ui.update { it.copy(screenshot = bitmap) }
    }

    /**
     * Screen visible: the game's voice engine takes the mic — once a live session over the real
     * game has finished stopping, since it shares the engine.
     */
    fun onScreenStarted() {
        voiceStart?.cancel()
        voiceStart = viewModelScope.launch {
            livePlay?.state?.first { !it.active }
            voiceEngine.start()
        }
    }

    private var voiceStart: Job? = null

    /** Screen hidden: give the mic back to the rest of PWDe. */
    fun onScreenStopped() {
        voiceStart?.cancel()
        voiceEngine.stop()
    }

    fun submitText(text: String) = voiceEngine.submitText(text)

    fun togglePause() {
        if (GameCommand.TogglePause.isPauseBlockedInGameMode(currentNavigationMode(), _paused.value)) {
            return post("Pause is disabled in game mode", OverlayEvent.Kind.IGNORED)
        }
        _paused.value = !_paused.value
        post(if (_paused.value) "Paused — gestures and voice won't play" else "Resumed", OverlayEvent.Kind.ACTION)
    }

    fun setOverlayHidden(hidden: Boolean) {
        if (_overlayHidden.value == hidden) return
        _overlayHidden.value = hidden
        post(if (hidden) "Overlay hidden — say \"show overlay\" to bring it back" else "Overlay shown", OverlayEvent.Kind.ACTION)
    }

    fun select(carryOut: Boolean = true) {
        if (_paused.value) return post("Paused — say \"resume\" first", OverlayEvent.Kind.IGNORED)
        // Carried out through the shared overlay, at the pointer's real position on the screen.
        if (carryOut) livePlay?.perform(GameCommand.Select)
        post("Select", OverlayEvent.Kind.SELECT)
    }

    fun exit() {
        _exit.trySend(Unit)
    }

    /** Called by the screen when the head joystick settles on a new direction. */
    fun onJoystickDirection(direction: JoystickDirection) {
        if (direction == lastDirection) return
        lastDirection = direction
        if (_paused.value) return
        GameInput.fromJoystick(direction, _ui.value.buttons)?.let(::execute)
    }

    private fun onVoice(commandId: String?, rawText: String?) {
        val command = GameInput.fromVoice(commandId, rawText, _ui.value.buttons) ?: return
        GameInput.navigationRefusal(command, currentNavigationMode())?.let { return post(it, OverlayEvent.Kind.IGNORED) }
        // Voice "back" always leaves the preview, even while paused.
        if (command == GameCommand.Back) return exit()
        if (_paused.value && !GameInput.worksWhilePaused(command)) {
            return post("Paused — say \"resume\" first", OverlayEvent.Kind.IGNORED)
        }
        execute(command, carryOut = true)
    }

    /**
     * A gesture is **already carried out by the overlay**: `PwdeAccessibilityService.handleIdleGesture`
     * runs for every PWDe screen while no session is over the real game, which is exactly the case in
     * the preview. So this only reports it — carrying it out here as well would press twice.
     */
    private fun onGesture(gesture: FacialGesture) {
        val command = GameInput.fromGesture(gesture, _ui.value.buttons, config.value)
        GameInput.navigationRefusal(command, currentNavigationMode())?.let { return post(it, OverlayEvent.Kind.IGNORED) }
        if (_paused.value && !GameInput.worksWhilePaused(command)) {
            return post("${gesture.label} ignored while paused", OverlayEvent.Kind.IGNORED)
        }
        execute(command, carryOut = false)
    }

    /** The mode right now: the phrase wins, otherwise the input mode decides. */
    private fun currentNavigationMode(): NavigationMode = navigationModeFor(_navigationOverride.value, faceState.value.outputMode)

    /**
     * [carryOut] is false when something else has already carried the command out — see [onGesture].
     * Everything that acts on the screen goes through [LivePlay.perform], which is the same path a live
     * session uses, so the overlay presses where the button really is, at the pointer's real position,
     * with the same tap logic. That is the point of sharing the one overlay: the preview can press,
     * instead of only saying it would.
     */
    private fun execute(command: GameCommand, carryOut: Boolean = true) {
        when (command) {
            is GameCommand.Press -> {
                if (carryOut) livePlay?.perform(command)
                post("Pressed ${command.button.label}", OverlayEvent.Kind.BUTTON, command.button.id)
            }
            GameCommand.Select -> select(carryOut)
            GameCommand.Pause -> if (!_paused.value) togglePause()
            GameCommand.Resume -> if (_paused.value) togglePause()
            GameCommand.TogglePause -> togglePause()
            GameCommand.Recenter -> recenter()
            GameCommand.ToggleCenterLock -> setCenterLock(!_centerLocked.value)
            is GameCommand.CenterLock -> setCenterLock(command.locked)
            GameCommand.Back, GameCommand.Home, GameCommand.Exit -> exit()
            GameCommand.HideOverlay -> setOverlayHidden(true)
            GameCommand.ShowOverlay -> setOverlayHidden(false)
            GameCommand.ShowControls -> setControlsShown(true)
            GameCommand.HideControls -> setControlsShown(false)
            GameCommand.Notifications -> {
                if (carryOut) livePlay?.perform(command)
                post("Notifications", OverlayEvent.Kind.ACTION)
            }
            GameCommand.AllApps -> {
                if (carryOut) livePlay?.perform(command)
                post("All apps", OverlayEvent.Kind.ACTION)
            }
            GameCommand.TouchHold -> {
                if (carryOut) livePlay?.perform(command)
                post("Touch & hold", OverlayEvent.Kind.ACTION)
            }
            GameCommand.Recents -> {
                if (carryOut) livePlay?.perform(command)
                post("Recent apps", OverlayEvent.Kind.ACTION)
            }
            is GameCommand.Scroll -> {
                if (carryOut) livePlay?.perform(command)
                post("Scroll ${command.direction.name.lowercase()}", OverlayEvent.Kind.ACTION)
            }
            GameCommand.StartDrag -> {
                if (carryOut) livePlay?.perform(command)
                post("Drag at the pointer", OverlayEvent.Kind.ACTION)
            }
            GameCommand.Drop -> {
                if (carryOut) livePlay?.perform(command)
                post("Drop", OverlayEvent.Kind.ACTION)
            }
            // Only a gesture produces this, and the overlay already resolved and carried it out.
            GameCommand.ToggleDrag -> post("Drag / drop", OverlayEvent.Kind.ACTION)
            // These change settings a live session owns, so the preview deliberately leaves them.
            GameCommand.CursorMode -> post("Cursor mode (in the real game only)", OverlayEvent.Kind.ACTION)
            GameCommand.JoystickMode -> post("Joystick mode (in the real game only)", OverlayEvent.Kind.ACTION)
            GameCommand.GyroMode -> post("Gyro joystick (in the real game only)", OverlayEvent.Kind.ACTION)
            GameCommand.HeadTracking -> post("Head joystick (in the real game only)", OverlayEvent.Kind.ACTION)
            GameCommand.GameMode -> setNavigationMode(NavigationMode.GAME)
            GameCommand.NavigationMode -> setNavigationMode(NavigationMode.NAVIGATION)
            is GameCommand.Ignored -> post(command.reason, OverlayEvent.Kind.IGNORED)
        }
    }

    /** The same switch as the live session's, so the preview can be used to check the rules. */
    private fun setNavigationMode(mode: NavigationMode) {
        _navigationOverride.value = mode
        post(
            if (mode == NavigationMode.GAME) {
                "Game mode — \"back\", \"home\", \"recent apps\" and \"notifications\" are off. Say \"navigation mode\" for them."
            } else {
                "Navigation mode — \"back\", \"home\", \"recent apps\" and \"notifications\" work again."
            },
            OverlayEvent.Kind.ACTION,
        )
    }

    /** Cursor mode: the pointer back to the middle. Joystick mode: where the head is now becomes the stick's neutral. */
    private fun recenter() {
        if (faceState.value.outputMode != FaceOutputMode.JOYSTICK) {
            recenterCursor()
            return post("Recentered", OverlayEvent.Kind.ACTION)
        }
        viewModelScope.launch {
            val saved = faceTracking.captureJoystickCenter()
            post(if (saved) "Joystick recentered" else "Can't see your face — look at the camera and try again", OverlayEvent.Kind.ACTION)
        }
    }

    /**
     * The same brake as the live session's, so the preview can be used to check the rules: speech
     * names the state, a gesture toggles it, and it is refused only where there is no stick to hold.
     */
    private fun setCenterLock(locked: Boolean) {
        GameInput.centerLockRefusal(faceState.value.outputMode)?.let { return post(it, OverlayEvent.Kind.IGNORED) }
        if (_centerLocked.value == locked) {
            return post(if (locked) "The centre is already locked" else "The centre is already unlocked", OverlayEvent.Kind.ACTION)
        }
        _centerLocked.value = locked
        if (locked) {
            post("Centre locked — it stays centred until you repeat the gesture", OverlayEvent.Kind.ACTION)
        } else {
            viewModelScope.launch { faceTracking.captureJoystickCenter() }
            post("Centre unlocked — steering again", OverlayEvent.Kind.ACTION)
        }
    }

    private fun post(text: String, kind: OverlayEvent.Kind, buttonId: Int? = null) {
        _lastEvent.value = OverlayEvent(++eventId, text, kind, buttonId)
    }

    override fun onCleared() {
        voiceEngine.stop()
    }
}
