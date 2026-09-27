package com.pwde.app.play

import com.pwde.app.data.model.Game
import com.pwde.app.data.model.MappedButton
import com.pwde.app.data.model.NavigationMode
import com.pwde.app.data.model.TriggerType
import com.pwde.app.data.model.navigationModeFor
import com.pwde.app.sensors.face.FaceState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The live session over the real game, as the notification, overlay and accessibility service see it. */
data class LivePlayState(
    val active: Boolean = false,
    val game: Game? = null,
    val profileName: String? = null,
    val buttons: List<MappedButton> = emptyList(),
    val paused: Boolean = false,
    /** The user hid PWDe's floating UI; the pointer and taps still work. */
    val overlayHidden: Boolean = false,
    /** "show controls": every mapped button is labelled with what presses it, for a few seconds. */
    val controlsShown: Boolean = false,
    val face: FaceState = FaceState(),
    /** A drag is holding the screen at the pointer until "drop". */
    val dragging: Boolean = false,
    /** Latest feedback, e.g. "Pressed Skill 1" or why something was ignored. */
    val message: String? = null,
    /** The speech engine listening in game, e.g. "sherpa-onnx keyword spotter". */
    val voiceModel: String? = null,
    /** What that engine last heard, shown under the floating bubble. */
    val heard: Heard? = null,
    /**
     * An explicit "game mode" / "navigation mode" for this session, or null to follow the input
     * mode. Not saved: a new session starts from the input mode again.
     */
    val navigationOverride: NavigationMode? = null,
    /**
     * The movement stick is braked: it stays at its centre, whichever way the head or phone points,
     * until the user unlocks it. Not saved — a mid-match brake is not a preference.
     */
    val centerLocked: Boolean = false,
)

/**
 * One speech result: the words, and whether they matched a command. [seq] goes up with every result,
 * so saying the same phrase twice still reads as a new hit.
 */
data class Heard(val text: String, val matched: Boolean, val seq: Int)

/**
 * App-wide hub for the live session: [PlayService] writes it, the accessibility service reads
 * [state] and performs [actions] on the real screen. One instance, on AppContainer.
 */
class LivePlay {
    private val _state = MutableStateFlow(LivePlayState())
    val state: StateFlow<LivePlayState> = _state.asStateFlow()

    /**
     * The PWDe screens asking for the overlay right now, by name.
     *
     * A **reference count, not a flag**: a wizard step and the preview behind it may both want the
     * overlay and there must still be exactly one. Mutated with `update {}` so a read-modify-write
     * cannot drop a holder (that exact bug once left the camera closed in the eye-tracking work).
     *
     * The overlay itself is the accessibility service's, a system singleton: acquiring it never
     * creates anything, it only says who is using it.
     */
    private val _overlayHolders = MutableStateFlow<Set<String>>(emptySet())
    val overlayHolders: StateFlow<Set<String>> = _overlayHolders.asStateFlow()

    /** Ask for the one overlay on [name]'s behalf. Balanced by [releaseOverlay]. */
    fun acquireOverlay(name: String) = _overlayHolders.update { it + name }

    /** Release [name]'s claim. A name that never claimed is a no-op. */
    fun releaseOverlay(name: String) = _overlayHolders.update { it - name }

    private val _actions = MutableSharedFlow<GameCommand>(extraBufferCapacity = 16)

    /** Commands that act on the real screen: presses, select, touch & hold, scroll, drag and system actions. */
    val actions: SharedFlow<GameCommand> = _actions.asSharedFlow()

    private val _requests = MutableSharedFlow<GameCommand>(extraBufferCapacity = 16)

    /** Commands from outside the session, e.g. the floating bubble's pause or mode switch. */
    internal val requests: SharedFlow<GameCommand> = _requests.asSharedFlow()

    /** Ask the running session to do [command], as if the user had said it. */
    fun request(command: GameCommand) {
        _requests.tryEmit(command)
    }

    internal fun update(transform: (LivePlayState) -> LivePlayState) = _state.update(transform)

    /**
     * Publish a command for the accessibility service to carry out on the real screen. Used by a
     * running session *and* by PWDe's own screens, so the in-app preview drives the same overlay,
     * with the same coordinates and the same tap logic, as a session over the real game.
     */
    internal fun perform(command: GameCommand) {
        _actions.tryEmit(command)
    }

    internal fun end() {
        // Deliberately not clearing [overlayHolders]: a screen showing PWDe is still using the
        // overlay after the session over a game ends. Only the screen that claimed it may release it.
        _state.value = LivePlayState()
    }
}

/**
 * What the one overlay is driving. There is exactly **one** overlay — the accessibility service's
 * views — and this says what it does, instead of a second, private copy being built per screen.
 */
enum class OverlayMode {
    /** Nobody is using it: no views are shown at all. */
    OFF,

    /**
     * PWDe is on screen and one of its own flows wants the pointer, so the overlay drives PWDe.
     * Voice is the app-wide recognizer (`AndroidVoiceCommandManager`, Google), which is the only
     * thing listening while PWDe is in front.
     */
    IN_APP,

    /**
     * A live session runs over the real game: the overlay presses the game's buttons. Voice there is
     * the in-game engine (sherpa-onnx), which holds the mic for the whole session.
     */
    GAME,
}

/**
 * The overlay's behaviour, from what is actually running. [inApp] is true while at least one PWDe
 * screen holds a claim; [pwdeInForeground] is true while the user is actually in the PWDe app.
 *
 * A running session always wins: the overlay must never be half in-app while it is pressing the
 * game's buttons. Pure, so the rule is unit-tested rather than inferred from three flows in the UI.
 */
fun LivePlayState.overlayMode(inApp: Boolean, pwdeInForeground: Boolean): OverlayMode = when {
    active -> OverlayMode.GAME
    inApp && pwdeInForeground -> OverlayMode.IN_APP
    else -> OverlayMode.OFF
}

/** True if a game session is active and the currently opened app has a joystick configuration. */
fun LivePlayState.hasJoystickConfig(): Boolean {
    if (!active || game == null) return false
    return buttons.any { b ->
        b.trigger?.type == TriggerType.MOVEMENT || b.trigger?.type == TriggerType.JOYSTICK
    } || profileName != null
}

/**
 * Whether the phone's own navigation (back, home, recents, notifications, all apps) is allowed right
 * now. Saying "game mode" / "navigation mode" wins for the rest of the session; otherwise the input
 * mode decides, so joystick mode is game mode and cursor mode is navigation mode by default.
 */
fun LivePlayState.navigationMode(): NavigationMode = navigationModeFor(navigationOverride, face.outputMode)
