package com.pwde.app.ui.voice

import com.pwde.app.data.local.inputModeOrDefault
import com.pwde.app.data.local.ProfileRepository
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pwde.app.data.local.ControlsRepository
import com.pwde.app.data.model.Game
import com.pwde.app.data.model.JoystickSource
import com.pwde.app.data.model.VoiceShortcut
import com.pwde.app.data.prefs.InputMode
import com.pwde.app.data.prefs.SettingsRepository
import com.pwde.app.play.LivePlay
import com.pwde.app.play.hasJoystickConfig
import com.pwde.app.sensors.voice.CommandScope
import com.pwde.app.sensors.voice.StandardCommands
import com.pwde.app.sensors.voice.VoiceCommand
import com.pwde.app.sensors.voice.VoiceCommandManager
import com.pwde.app.sensors.voice.VoiceState
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Navigation the app-wide voice commands ask for. The NavHost performs it. */
enum class VoiceNavigation { BACK, HOME, SETTINGS, GAMES, GABAI, PROFILE, READ_SCREEN }

/** What screens and the voice bar need from the voice system, without touching SpeechRecognizer. */
interface VoiceController {
    val state: StateFlow<VoiceState>

    /** Short feedback such as "Switched to joystick", shown in the mic overlay's pop-up. */
    val notice: StateFlow<String?>
    val hasMicPermission: Boolean

    fun setVoiceEnabled(enabled: Boolean)
    fun onMicPermissionResult()

    /** While registered, saying one of [commands] calls [onCommand]. */
    fun register(owner: Any, commands: List<VoiceCommand>, onCommand: (VoiceCommand) -> Unit)
    fun unregister(owner: Any)
}

/** Null in previews and tests; screens then show voice as off. */
val LocalVoiceController = staticCompositionLocalOf<VoiceController?> { null }

/**
 * Root-level voice ViewModel, created once by the NavHost. Keeps the recognizer running while the
 * app is on screen, routes screen commands to the screen that registered them, and handles the
 * standard global commands (back, home, settings…) and the user's spoken shortcuts.
 */
class VoiceViewModel(
    private val voiceCommandManager: VoiceCommandManager,
    private val controlsRepository: ControlsRepository,
    private val settingsRepository: SettingsRepository,
    private val profileRepository: ProfileRepository,
    private val livePlay: LivePlay? = null,
) : ViewModel(), VoiceController {
    override val state: StateFlow<VoiceState> = voiceCommandManager.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000), voiceCommandManager.state.value)

    private val _notice = MutableStateFlow<String?>(null)
    override val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _navigation = Channel<VoiceNavigation>(Channel.BUFFERED)
    val navigation: Flow<VoiceNavigation> = _navigation.receiveAsFlow()

    /** "play <game>": the NavHost starts the real game (it can ask for permissions; this can't). */
    private val _playRequests = Channel<Game>(Channel.BUFFERED)
    val playRequests: Flow<Game> = _playRequests.receiveAsFlow()

    private val handlers = LinkedHashMap<Any, Pair<List<VoiceCommand>, (VoiceCommand) -> Unit>>()

    override val hasMicPermission: Boolean get() = voiceCommandManager.hasMicPermission

    init {
        viewModelScope.launch {
            voiceCommandManager.results.collect { result ->
                val command = result.command ?: return@collect
                if (command.scope == CommandScope.SCREEN) dispatchToScreen(command) else handleGlobal(command)
            }
        }
    }

    override fun setVoiceEnabled(enabled: Boolean) {
        viewModelScope.launch { controlsRepository.setVoiceEnabled(enabled, persistToActiveProfile = true) }
        voiceCommandManager.refreshPermissions()
    }

    override fun onMicPermissionResult() = voiceCommandManager.refreshPermissions()

    override fun register(owner: Any, commands: List<VoiceCommand>, onCommand: (VoiceCommand) -> Unit) {
        handlers[owner] = commands to onCommand
        voiceCommandManager.setScreenCommands(owner, commands)
    }

    override fun unregister(owner: Any) {
        handlers.remove(owner)
        voiceCommandManager.clearScreenCommands(owner)
    }

    private fun dispatchToScreen(command: VoiceCommand) {
        handlers.values.lastOrNull { (commands, _) -> commands.any { it.id == command.id } }?.second?.invoke(command)
    }

    private suspend fun handleGlobal(command: VoiceCommand) {
        // Speaking is never restricted by the input mode. Joystick mode decides what the *head* drives
        // (the movement stick instead of the pointer), not which commands may be spoken — it used to
        // drop everything but "cursor mode" and "switch profile", which read as voice being broken.
        when (command.id) {
            StandardCommands.BACK.id, StandardCommands.CLOSE.id -> _navigation.send(VoiceNavigation.BACK)
            StandardCommands.HOME.id, StandardCommands.MENU.id -> _navigation.send(VoiceNavigation.HOME)
            StandardCommands.SETTINGS.id -> _navigation.send(VoiceNavigation.SETTINGS)
            StandardCommands.GAMES.id -> _navigation.send(VoiceNavigation.GAMES)
            StandardCommands.GABAI.id -> _navigation.send(VoiceNavigation.GABAI)
            StandardCommands.PROFILE.id -> _navigation.send(VoiceNavigation.PROFILE)
            StandardCommands.READ_SCREEN.id -> _navigation.send(VoiceNavigation.READ_SCREEN)
            else -> StandardCommands.gameToPlay(command)?.let { _playRequests.send(it) } ?: when (StandardCommands.shortcutOf(command)) {
                VoiceShortcut.CURSOR_MODE -> switchInput(InputMode.HEAD_FACE, "Switched to cursor mode")
                VoiceShortcut.JOYSTICK_MODE -> switchInput(InputMode.JOYSTICK, "Switched to joystick mode")
                VoiceShortcut.GYRO_MODE -> switchJoystickSource(JoystickSource.GYRO)
                VoiceShortcut.HEAD_TRACKING -> switchJoystickSource(JoystickSource.HEAD)
                VoiceShortcut.SWITCH_PROFILE -> switchCalibrationProfile()
                null -> showNotice("\"${command.label}\" doesn't do anything on this screen")
            }
        }
    }

    private suspend fun switchInput(mode: InputMode, message: String) {
        if (mode == InputMode.JOYSTICK && livePlay?.state?.value?.hasJoystickConfig() != true) {
            showNotice("Joystick mode is only available when an app with a joystick configuration is open")
            return
        }
        if (settingsRepository.settings.first().inputMode != mode) settingsRepository.setInputMode(mode)
        showNotice(message)
    }

    /** "gyro mode" / "head tracking" from anywhere in PWDe: choose what steers the joystick. */
    private suspend fun switchJoystickSource(source: JoystickSource) {
        if (livePlay?.state?.value?.hasJoystickConfig() != true) {
            showNotice("Joystick mode is only available when an app with a joystick configuration is open")
            return
        }
        settingsRepository.setJoystickSource(source)
        if (settingsRepository.settings.first().inputMode != InputMode.JOYSTICK) {
            settingsRepository.setInputMode(InputMode.JOYSTICK)
        }
        showNotice("${source.label} — ${source.description.lowercase()}")
    }

    private var lastSwitchedProfileId: Long? = null

    /** Steps to the next saved calibration profile and makes it the working controls. */
    private suspend fun switchCalibrationProfile() {
        val profiles = profileRepository.calibrationProfiles.first()
        if (profiles.isEmpty()) {
            showNotice("No saved calibration profiles yet — make one with GabAI")
            return
        }
        val index = profiles.indexOfFirst { it.id == lastSwitchedProfileId }
        val next = profiles[(index + 1).mod(profiles.size)]
        lastSwitchedProfileId = next.id
        controlsRepository.applyCalibration(next)
        settingsRepository.setInputMode(next.inputModeOrDefault)
        showNotice("Switched to \"${next.name}\"")
    }

    private var noticeJob: Job? = null

    private fun showNotice(message: String) {
        noticeJob?.cancel()
        _notice.value = message
        noticeJob = viewModelScope.launch {
            delay(NOTICE_MS)
            _notice.value = null
        }
    }

    private companion object {
        const val NOTICE_MS = 3_000L
    }
}
