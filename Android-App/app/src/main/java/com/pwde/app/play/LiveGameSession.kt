package com.pwde.app.play

import android.util.Log
import com.pwde.app.data.local.ControlJson
import com.pwde.app.data.local.ControlsRepository
import com.pwde.app.data.local.ProfileRepository
import com.pwde.app.data.local.inputModeOrDefault
import com.pwde.app.data.model.ControlConfig
import com.pwde.app.data.model.FaceOutputMode
import com.pwde.app.data.model.FacialGesture
import com.pwde.app.data.model.Game
import com.pwde.app.data.model.JoystickSource
import com.pwde.app.data.model.MappedButton
import com.pwde.app.data.model.NavigationMode
import com.pwde.app.data.prefs.SettingsRepository
import com.pwde.app.sensors.face.FaceTrackingManager
import com.pwde.app.sensors.face.JoystickDirection
import com.pwde.app.sensors.voice.InGameVoiceEngine
import com.pwde.app.data.prefs.InputMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * One live session over the real game: keeps face tracking and the in-game voice engine running
 * while PWDe is in the background, turns their input into [GameCommand]s through [GameInput], and
 * publishes everything on [LivePlay]. Screen actions are performed by the accessibility service.
 */
class LiveGameSession(
    private val livePlay: LivePlay,
    private val faceTracking: FaceTrackingManager,
    private val voiceEngine: InGameVoiceEngine,
    private val profileRepository: ProfileRepository,
    private val controlsRepository: ControlsRepository,
    private val settingsRepository: SettingsRepository,
    /** The user asked to leave the game ("exit", or an Exit gesture). */
    private val onExit: () -> Unit,
    /** "profile <name>" named another game's profile: open that game and play it with that profile. */
    private val onPlayOtherGame: (Game, Long) -> Unit,
) {
    private var config = ControlConfig()
    private var lastDirection = JoystickDirection.CENTER

    /** Runs until cancelled. */
    /** The session's own scope, for work that outlives one command (saving the input mode). */
    private var scope: CoroutineScope? = null

    /** Hides the "show controls" labels again; restarted by every "show controls". */
    private var controlsTimeout: Job? = null

    /** The game profile in use; changed by "next profile" / "profile <name>". */
    private var profileId: Long? = null
    private var game: Game? = null

    suspend fun run(game: Game, profileId: Long?) {
        this.game = game
        this.profileId = profileId
        val profile = profileId?.let { profileRepository.getGameProfile(it) }
        if (profile != null) applyProfileCalibration(profile, profileRepository, controlsRepository, settingsRepository)
        val buttons = profile?.let { ControlJson.decodeButtons(it.buttonMappingsJson) }.orEmpty()
        livePlay.update {
            LivePlayState(
                active = true,
                game = game,
                profileName = profile?.profileName,
                buttons = buttons,
                voiceModel = voiceEngine.modelLabel.substringBefore(" ·"),
            )
        }
        loadVoiceCommands(buttons)
        voiceEngine.start()
        try {
            coroutineScope {
                scope = this
                launch { controlsRepository.config.collect { config = it } }
                // Collecting the state is what keeps the camera running in the background.
                launch { faceTracking.state.collect { face -> livePlay.update { it.copy(face = face) } } }
                // The engine follows the user between the app and the game, so what has the microphone is
                // republished as it changes — otherwise the overlay caption names the engine chosen when
                // the session started, and a switch is invisible even when it happens.
                launch {
                    voiceEngine.state.collect { voice ->
                        voice.modelLabel?.let { label -> livePlay.update { it.copy(voiceModel = label) } }
                    }
                }
                launch {
                    faceTracking.state.map { it.joystick.direction }.distinctUntilChanged().collect(::onJoystickDirection)
                }
                launch { faceTracking.gestureEvents.collect(::onGesture) }
                launch {
                    voiceEngine.results.collect {
                        showHeard(it.rawText, matched = it.commandId != null)
                        onVoice(it.commandId, it.rawText)
                    }
                }
                launch { livePlay.requests.collect(::runUnlessPaused) }
            }
        } finally {
            scope = null
            voiceEngine.stop()
            livePlay.end()
        }
    }

    fun togglePause() = execute(GameCommand.TogglePause)

    fun recenter() = execute(GameCommand.Recenter)

    private fun onJoystickDirection(direction: JoystickDirection) {
        if (direction == lastDirection) return
        lastDirection = direction
        if (livePlay.state.value.paused) return
        GameInput.fromJoystick(direction, livePlay.state.value.buttons)?.let(::execute)
    }

    private fun onVoice(commandId: String?, rawText: String?) {
        val command = GameInput.fromVoice(commandId, rawText, livePlay.state.value.buttons)
        Log.i(TAG, "Voice \"$rawText\" ($commandId) -> $command")
        if (command == null) return

        // "Switch profile" is a spoken shortcut rather than a mapped button, so it matches the words.
        val text = rawText?.lowercase().orEmpty()
        if (command is GameCommand.Ignored && (text.contains("switch profile") || text.contains("change profile"))) {
            scope?.launch { switchCalibrationProfile() }
            return
        }

        // Phone navigation is off in game mode, and joystick mode IS game mode by default: a stray
        // "back", or the game's own audio being heard by the spotter, must never pull the user out of
        // a match. Said out loud rather than dropped — silence reads as a broken microphone.
        GameInput.navigationRefusal(command, livePlay.state.value.navigationMode())?.let { reason ->
            Log.i(TAG, "Dropped $command: $reason")
            return message(reason)
        }

        // Every command, including every button the user assigned, works in both modes. Joystick mode
        // only takes away the pointer: the head steers the game's movement stick instead, so a command
        // that acts where the user is looking has nowhere to act. Say so rather than dropping it silently.
        if (livePlay.state.value.face.outputMode == FaceOutputMode.JOYSTICK && GameInput.needsPointer(command)) {
            Log.i(TAG, "Dropped $command in joystick mode: it acts at the pointer")
            return message("In joystick mode your head steers the movement stick — say \"cursor mode\" for that")
        }
        runUnlessPaused(command)
    }

    private fun showHeard(text: String?, matched: Boolean) {
        if (text.isNullOrBlank()) return
        livePlay.update { it.copy(heard = Heard(text, matched, (it.heard?.seq ?: 0) + 1)) }
    }

    private fun onGesture(gesture: FacialGesture) {
        val command = GameInput.fromGesture(gesture, livePlay.state.value.buttons, config)
        GameInput.navigationRefusal(command, livePlay.state.value.navigationMode())?.let { return message(it) }
        runUnlessPaused(command)
    }

    private fun runUnlessPaused(command: GameCommand) {
        if (livePlay.state.value.paused && !GameInput.worksWhilePaused(command)) {
            Log.i(TAG, "Dropped $command: paused")
            return message("Paused — say \"resume\" first")
        }
        execute(command)
    }

    private fun execute(command: GameCommand) {
        if (command.isPauseBlockedInGameMode(livePlay.state.value.navigationMode(), livePlay.state.value.paused)) {
            return message("Pause is disabled in game mode")
        }
        when (command) {
            GameCommand.Pause -> setPaused(true)
            GameCommand.Resume -> setPaused(false)
            GameCommand.TogglePause -> setPaused(!livePlay.state.value.paused)
            GameCommand.Recenter -> recenterForMode()
            GameCommand.ToggleCenterLock -> setCenterLock(!livePlay.state.value.centerLocked)
            is GameCommand.CenterLock -> setCenterLock(command.locked)
            GameCommand.Exit -> returnToPwde()
            GameCommand.HideOverlay -> livePlay.update { it.copy(overlayHidden = true) }
            GameCommand.ShowOverlay -> livePlay.update { it.copy(overlayHidden = false) }
            GameCommand.ShowControls -> showControls()
            GameCommand.HideControls -> hideControls()
            is GameCommand.Ignored -> message(command.reason)
            // Presses, select, touch & hold and system actions happen on the real screen.
            is GameCommand.Press -> {
                livePlay.perform(command)
                message("Pressed ${command.button.label}")
            }
            GameCommand.CursorMode -> switchMode(InputMode.HEAD_FACE, "Cursor mode")
            GameCommand.JoystickMode -> {
                if (!livePlay.state.value.hasJoystickConfig()) {
                    message("Joystick mode requires a joystick configuration for this app")
                } else {
                    switchMode(InputMode.JOYSTICK, "Joystick mode")
                }
            }
            GameCommand.GyroMode -> switchJoystickSource(JoystickSource.GYRO)
            GameCommand.HeadTracking -> switchJoystickSource(JoystickSource.HEAD)
            GameCommand.GameMode -> setNavigationMode(NavigationMode.GAME)
            GameCommand.NavigationMode -> setNavigationMode(NavigationMode.NAVIGATION)
            is GameCommand.SwitchProfile -> scope?.launch { switchGameProfile(command.profileId) }
            GameCommand.StartDrag -> {
                livePlay.update { it.copy(dragging = true) }
                livePlay.perform(command)
                message("Dragging — say \"drop\" to let go")
            }
            GameCommand.Drop -> {
                livePlay.update { it.copy(dragging = false) }
                livePlay.perform(command)
            }
            GameCommand.Select, GameCommand.TouchHold, GameCommand.Back, GameCommand.Home,
            GameCommand.Notifications, GameCommand.AllApps, GameCommand.Recents, is GameCommand.Scroll -> livePlay.perform(command)
        }
    }

    /** PWDe's own screens are driven with the pointer, so it always comes back in cursor mode. */
    private fun returnToPwde() {
        val s = scope ?: return onExit()
        s.launch {
            settingsRepository.setInputMode(InputMode.HEAD_FACE)
            onExit()
        }
    }

    /** Cursor mode: the pointer back to the middle. Joystick mode: where the head is now becomes the stick's neutral. */
    private fun recenterForMode() {
        if (livePlay.state.value.face.outputMode != FaceOutputMode.JOYSTICK) {
            faceTracking.recenterCursor()
            return message("Recentered")
        }
        scope?.launch {
            message(if (faceTracking.captureJoystickCenter()) "Joystick recentered" else "Can't see your face — look at the camera and try again")
        }
    }

    /**
     * The brake. While it is on, `steerStick` takes its existing release path, so PWDe lifts the
     * game's movement finger and the character simply stops — it stays stopped however the head or
     * phone moves. The same gesture turns it off again; speech names the state outright.
     *
     * Unlocking re-takes the neutral: the head very likely drifted while the stick was held still, so
     * without this the first unlocked frame would jerk the character off in that direction.
     */
    private fun setCenterLock(locked: Boolean) {
        GameInput.centerLockRefusal(livePlay.state.value.face.outputMode)?.let { return message(it) }
        if (livePlay.state.value.centerLocked == locked) {
            return message(if (locked) "The centre is already locked" else "The centre is already unlocked")
        }
        livePlay.update { it.copy(centerLocked = locked) }
        message(
            if (locked) "Centre locked — it stays centred until you repeat the gesture"
            else "Centre unlocked — steering again",
        )
        if (!locked) scope?.launch { faceTracking.captureJoystickCenter() }
    }

    private fun showControls() {
        val buttons = livePlay.state.value.buttons
        if (buttons.isEmpty()) return message("This profile has no mapped buttons")
        livePlay.update { it.copy(controlsShown = true) }
        message(buttons.joinToString(" · ") { "${it.label}: ${it.trigger?.shortLabel() ?: "—"}" })
        controlsTimeout?.cancel()
        controlsTimeout = scope?.launch {
            delay(CONTROLS_SHOWN_MS)
            livePlay.update { it.copy(controlsShown = false) }
        }
    }

    private fun hideControls() {
        controlsTimeout?.cancel()
        livePlay.update { it.copy(controlsShown = false) }
    }

    private fun switchMode(mode: InputMode, label: String) {
        scope?.launch { settingsRepository.setInputMode(mode) }
        // The brake belongs to the mode it was set in: a cursor has no stick to hold still.
        livePlay.update { it.copy(centerLocked = false) }
        message(label)
    }

    /**
     * What steers the joystick. Saying either one also puts the user into joystick mode, because the
     * source only means anything there — "gyro mode" is asking for a joystick they steer by tilting
     * the phone, not a setting they then have to go and enable.
     */
    private fun switchJoystickSource(source: JoystickSource) {
        if (!livePlay.state.value.hasJoystickConfig()) {
            return message("Joystick mode requires a joystick configuration for this app")
        }
        scope?.launch {
            settingsRepository.setJoystickSource(source)
            settingsRepository.setInputMode(InputMode.JOYSTICK)
        }
        message("${source.label} — ${source.description.lowercase()}")
    }

    /**
     * "game mode" / "navigation mode": overrides the input mode's own default for the rest of the
     * session, without touching what the head drives. Deliberately not saved, so a new session starts
     * from the input mode again — joystick mode is game mode and cursor mode is navigation mode
     * unless the user says otherwise.
     */
    private fun setNavigationMode(mode: NavigationMode) {
        livePlay.update { it.copy(navigationOverride = mode) }
        message(
            if (mode == NavigationMode.GAME) {
                "Game mode — \"back\", \"home\", \"recent apps\" and \"notifications\" are off. Say \"navigation mode\" for them."
            } else {
                "Navigation mode — \"back\", \"home\", \"recent apps\" and \"notifications\" work again."
            },
        )
    }

    /**
     * The standard commands, this profile's buttons, and a "profile <name>" for every other profile —
     * other games' too, which open that game. Only games PWDe can launch are offered.
     */
    private suspend fun loadVoiceCommands(buttons: List<MappedButton>) {
        val others = profileRepository.allGameProfiles()
            .filter { it.id != profileId && Game.byId(it.gameId) != null }
            .map { it.id to it.profileName }
        voiceEngine.loadCommands(GameInput.bindings(buttons) + GameInput.profileBindings(others))
    }

    /**
     * Swaps the live buttons, their triggers and the profile's calibration in place, so the user can
     * change hero mid-session without opening PWDe. [targetId] null means the next profile in turn.
     */
    private suspend fun switchGameProfile(targetId: Long?) {
        val gameId = game?.id ?: return
        if (targetId != null) {
            val target = profileRepository.getGameProfile(targetId) ?: return message("That profile no longer exists")
            if (target.gameId != gameId) {
                val other = Game.byId(target.gameId) ?: return message("PWDe can't open ${target.gameName}")
                message("Opening ${other.displayName} with \"${target.profileName}\"")
                // Starts a fresh session for that game, which ends this one.
                return onPlayOtherGame(other, target.id)
            }
        }
        val profiles = profileRepository.gameProfilesFor(gameId).first()
        val next = if (targetId != null) {
            profiles.firstOrNull { it.id == targetId }
        } else {
            val index = profiles.indexOfFirst { it.id == profileId }
            profiles.getOrNull((index + 1) % profiles.size.coerceAtLeast(1))
        }
        if (next == null || profiles.size < 2 && next.id == profileId) {
            return message("No other profile for this game — make one in GabAI")
        }
        if (next.id == profileId) return message("Already using \"${next.profileName}\"")
        profileId = next.id
        applyProfileCalibration(next, profileRepository, controlsRepository, settingsRepository)
        val buttons = ControlJson.decodeButtons(next.buttonMappingsJson)
        hideControls()
        livePlay.update { it.copy(profileName = next.profileName, buttons = buttons, centerLocked = false) }
        loadVoiceCommands(buttons)
        profileRepository.markGameProfilePlayed(next.id)
        message("Switched to \"${next.profileName}\" · ${buttons.size} button${if (buttons.size == 1) "" else "s"}")
    }

    private suspend fun switchCalibrationProfile() {
        val profiles = profileRepository.calibrationProfiles.first()
        if (profiles.isEmpty()) {
            return message("No saved calibration profiles yet")
        }
        val currentInputMode = settingsRepository.settings.first().inputMode
        val index = profiles.indexOfFirst { it.inputModeOrDefault == currentInputMode }
        val next = profiles[(if (index >= 0) index + 1 else 0) % profiles.size]
        controlsRepository.applyCalibration(next)
        settingsRepository.setInputMode(next.inputModeOrDefault)
        message("Switched to \"${next.name}\"")
    }

    private fun setPaused(paused: Boolean) {
        livePlay.update { it.copy(paused = paused) }
        message(if (paused) "Paused" else "Resumed")
    }

    private fun message(text: String) = livePlay.update { it.copy(message = text) }

    private companion object {
        const val TAG = "PwdeLiveSession"
        const val CONTROLS_SHOWN_MS = 10_000L
    }
}
