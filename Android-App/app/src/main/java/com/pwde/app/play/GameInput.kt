package com.pwde.app.play

import com.pwde.app.data.model.ControlConfig
import com.pwde.app.data.model.FaceOutputMode
import com.pwde.app.data.model.FacialGesture
import com.pwde.app.data.model.GestureAction
import com.pwde.app.data.model.MappedButton
import com.pwde.app.data.model.NavigationMode
import com.pwde.app.data.model.TriggerType
import com.pwde.app.sensors.face.JoystickDirection
import com.pwde.app.sensors.voice.VoiceCommandBinding

/** What one piece of in-game input (a voice command, gesture or joystick move) asks for. */
sealed interface GameCommand {
    data class Press(val button: MappedButton) : GameCommand
    object Select : GameCommand
    object TouchHold : GameCommand
    object Pause : GameCommand
    object Resume : GameCommand
    object TogglePause : GameCommand
    object Recenter : GameCommand
    /** Flip the center lock: hold the movement stick at the middle until this is asked for again. */
    object ToggleCenterLock : GameCommand
    /**
     * Explicitly lock ([locked] = true) or unlock the center. The shape for speech, which names the
     * state rather than toggling.
     *
     * **Currently has no phrase**: the centre lock is a gesture (`GestureAction.LOCK_CENTER`) only,
     * because the in-game spotter's keyword list cannot afford the phrases — see the note in
     * [STANDARD_BINDINGS]. Re-add a binding there and this becomes reachable again; the handling in the
     * live session, the preview and the accessibility service is already in place.
     */
    data class CenterLock(val locked: Boolean) : GameCommand
    object Back : GameCommand
    object Home : GameCommand
    object Notifications : GameCommand
    object AllApps : GameCommand
    object Recents : GameCommand
    /** Swipe the screen under the pointer so content moves the way [direction] reads, e.g. DOWN shows what's below. */
    data class Scroll(val direction: ScrollDirection) : GameCommand
    /** Press and hold at the pointer, then follow the head until [Drop]. */
    object StartDrag : GameCommand
    object Drop : GameCommand
    /** The drag gesture: [StartDrag] when nothing is held, [Drop] when a drag is. See [GameInput.resolveDrag]. */
    object ToggleDrag : GameCommand
    object CursorMode : GameCommand
    object JoystickMode : GameCommand
    /** Steer the joystick with the phone's own tilt instead of the head. */
    object GyroMode : GameCommand
    /** Steer the joystick with head tracking again. */
    object HeadTracking : GameCommand
    /**
     * Turn phone navigation off/on for this session, without changing what the head drives. Joystick
     * mode is game mode and cursor mode is navigation mode by default, so this is how a cursor-mode
     * user keeps "back"/"home" from pulling them out of a game.
     */
    object GameMode : GameCommand
    object NavigationMode : GameCommand
    /** Leave the game and go back to PWDe. */
    object Exit : GameCommand
    object HideOverlay : GameCommand
    object ShowOverlay : GameCommand
    /** List every mapped button with what presses it, over the game. */
    object ShowControls : GameCommand
    object HideControls : GameCommand

    /** Nothing to do; [reason] is shown to the user. */
    data class Ignored(val reason: String) : GameCommand
}

enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

fun GameCommand.isPauseBlockedInGameMode(mode: NavigationMode, paused: Boolean): Boolean =
    mode == NavigationMode.GAME && !paused && (this == GameCommand.Pause || this == GameCommand.TogglePause)

/**
 * Turns in-game input into [GameCommand]s. Shared by the simulated Playing screen and the live
 * session over the real game, so both follow the same rules. Pure, so it's unit-tested.
 */
object GameInput {
    const val BACK = "game_back"
    const val PAUSE = "game_pause"
    const val MENU = "game_menu"
    const val RESUME = "game_resume"
    const val EXIT = "game_exit"
    const val SELECT = "game_select"
    const val RECENTER = "game_recenter"

    /** Speech ids for the center lock. Kept for a future explicit phrase; no binding uses them yet. */
    const val LOCK_CENTER = "game_lock_center"
    const val UNLOCK_CENTER = "game_unlock_center"

    const val HIDE_OVERLAY = "game_hide_overlay"
    const val SHOW_OVERLAY = "game_show_overlay"
    const val SHOW_CONTROLS = "game_show_controls"
    const val HIDE_CONTROLS = "game_hide_controls"
    const val HOME = "game_home"
    const val RECENTS = "game_recents"
    const val NOTIFICATIONS = "game_notifications"
    const val TOUCH_HOLD = "game_touch_hold"
    const val DRAG = "game_drag"
    const val DROP = "game_drop"
    const val CURSOR_MODE = "game_cursor_mode"
    const val JOYSTICK_MODE = "game_joystick_mode"
    const val GYRO_MODE = "game_gyro_mode"
    const val HEAD_TRACKING = "game_head_tracking"
    const val GAME_MODE = "game_mode"
    const val NAVIGATION_MODE = "game_navigation_mode"
    private const val SCROLL = "game_scroll:"

    /**
     * The phone's own navigation: these drive the phone, not the game. Back / Home / Notifications /
     * All apps are also reachable as gesture actions, and every one of those becomes one of these
     * commands, so gating them here covers words and gestures alike.
     */
    private val NAVIGATION_COMMANDS: Set<GameCommand> = setOf(
        GameCommand.Back, GameCommand.Home, GameCommand.Recents, GameCommand.Notifications, GameCommand.AllApps,
    )

    fun buttonCommandId(buttonId: Int) = "button:$buttonId"

    /** Also used by GabAI's mapping and test steps, so the phrase works the same everywhere. */
    val SHOW_CONTROLS_PHRASES = listOf("show controls", "show buttons", "list controls")
    val HIDE_CONTROLS_PHRASES = listOf("hide controls", "hide buttons")

    /** Always available in game, on top of the profile's own voice commands. */
    val STANDARD_BINDINGS = listOf(
        VoiceCommandBinding(BACK, listOf("back", "go back")),
        VoiceCommandBinding(PAUSE, listOf("pause", "pause game")),
        // Leaving the game ends the whole session, and in-game voice is a keyword spotter that
        // hears the mic, game audio included. So the only way out is an explicit phrase — a bare
        // "menu" or "exit" is too easy for the game's own music and voice lines to trip.
        VoiceCommandBinding(MENU, listOf("pwde menu")),
        VoiceCommandBinding(RESUME, listOf("resume", "continue game", "unpause")),
        VoiceCommandBinding(EXIT, listOf("exit game", "quit game", "exit to pwde", "stop pwde")),
        VoiceCommandBinding(SELECT, listOf("select", "tap", "click")),
        VoiceCommandBinding(RECENTER, listOf("recenter", "center", "recenter joystick", "center joystick")),
        // The centre lock is a GESTURE (GestureAction.LOCK_CENTER -> ToggleCenterLock) and deliberately
        // has no phrase here. The in-game engine is sherpa-onnx keyword spotting over ONE flat list of
        // ~60 phrases sharing `maxActivePaths` search paths, and phrases are matched on sound: "lock
        // joystick"/"unlock joystick"/"lock center" competed directly with "joystick mode", "gyro
        // joystick" and "center joystick", which then stopped firing. Verified on device by reading
        // files/models/…/keywords-game.txt (60+ lines, every one `:6.0 #0.0`). Keep this list lean.
        VoiceCommandBinding(HIDE_OVERLAY, listOf("hide overlay", "hide panel", "close overlay")),
        VoiceCommandBinding(SHOW_OVERLAY, listOf("show overlay", "show panel", "open overlay")),
        VoiceCommandBinding(SHOW_CONTROLS, SHOW_CONTROLS_PHRASES),
        VoiceCommandBinding(HIDE_CONTROLS, HIDE_CONTROLS_PHRASES),
        VoiceCommandBinding(HOME, listOf("go home", "home screen")),
        VoiceCommandBinding(RECENTS, listOf("recent apps", "recents")),
        VoiceCommandBinding(NOTIFICATIONS, listOf("notifications", "open notifications")),
        VoiceCommandBinding(TOUCH_HOLD, listOf("long press", "touch and hold")),
        VoiceCommandBinding(DRAG, listOf("drag", "start drag")),
        VoiceCommandBinding(DROP, listOf("drop", "let go")),
        VoiceCommandBinding(CURSOR_MODE, listOf("cursor mode")),
        VoiceCommandBinding(JOYSTICK_MODE, listOf("joystick mode")),
        // What steers that joystick. Both work in joystick mode and put the user into it, so choosing
        // the source is one word rather than a setting to go and find.
        VoiceCommandBinding(GYRO_MODE, listOf("gyro mode", "gyro tracking", "gyro joystick")),
        VoiceCommandBinding(HEAD_TRACKING, listOf("head tracking", "head joystick")),
        VoiceCommandBinding(GAME_MODE, listOf("game mode")),
        VoiceCommandBinding(NAVIGATION_MODE, listOf("navigation mode")),
    ) + ScrollDirection.entries.map { VoiceCommandBinding(SCROLL + it.name, listOf("scroll ${it.name.lowercase()}")) }

    /** The standard commands plus each button's own voice trigger. */
    fun bindings(buttons: List<MappedButton>): List<VoiceCommandBinding> = STANDARD_BINDINGS + buttonBindings(buttons)

    /** Just each button's own voice trigger. */
    fun buttonBindings(buttons: List<MappedButton>): List<VoiceCommandBinding> = buttons.mapNotNull { b ->
        b.trigger?.takeIf { it.type == TriggerType.VOICE }?.let { VoiceCommandBinding(buttonCommandId(b.id), listOf(it.value)) }
    }

    fun fromVoice(commandId: String?, rawText: String?, buttons: List<MappedButton>): GameCommand? = when (commandId) {
        null -> rawText?.let { GameCommand.Ignored("Heard \"$it\" — not a command in this game") }
        BACK -> GameCommand.Back
        PAUSE -> GameCommand.Pause
        RESUME -> GameCommand.Resume
        MENU, EXIT -> GameCommand.Exit
        SELECT -> GameCommand.Select
        RECENTER -> GameCommand.Recenter
        LOCK_CENTER -> GameCommand.CenterLock(true)
        UNLOCK_CENTER -> GameCommand.CenterLock(false)
        HIDE_OVERLAY -> GameCommand.HideOverlay
        SHOW_OVERLAY -> GameCommand.ShowOverlay
        SHOW_CONTROLS -> GameCommand.ShowControls
        HIDE_CONTROLS -> GameCommand.HideControls
        HOME -> GameCommand.Home
        RECENTS -> GameCommand.Recents
        NOTIFICATIONS -> GameCommand.Notifications
        TOUCH_HOLD -> GameCommand.TouchHold
        DRAG -> GameCommand.StartDrag
        DROP -> GameCommand.Drop
        CURSOR_MODE -> GameCommand.CursorMode
        JOYSTICK_MODE -> GameCommand.JoystickMode
        GYRO_MODE -> GameCommand.GyroMode
        HEAD_TRACKING -> GameCommand.HeadTracking
        GAME_MODE -> GameCommand.GameMode
        NAVIGATION_MODE -> GameCommand.NavigationMode
        else -> ScrollDirection.entries.firstOrNull { commandId == SCROLL + it.name }?.let { GameCommand.Scroll(it) } ?: buttons.firstOrNull { buttonCommandId(it.id) == commandId }?.let { GameCommand.Press(it) }
    }

    /** A game button mapped to this gesture wins over the general gesture actions. */
    fun fromGesture(gesture: FacialGesture, buttons: List<MappedButton>, config: ControlConfig): GameCommand {
        buttonFor(buttons, TriggerType.GESTURE, gesture.name)?.let { return GameCommand.Press(it) }
        return config.actionFor(gesture)?.let(::commandFor)
            ?: GameCommand.Ignored("${gesture.label} — no action assigned")
    }

    /**
     * The one mapping from a [GestureAction] to the [GameCommand] it runs. No `else`, so a new action
     * asks to be given a command instead of silently doing nothing.
     */
    fun commandFor(action: GestureAction): GameCommand = when (action) {
        GestureAction.SELECT -> GameCommand.Select
        GestureAction.PAUSE_RESUME -> GameCommand.TogglePause
        GestureAction.RECENTER -> GameCommand.Recenter
        GestureAction.LOCK_CENTER -> GameCommand.ToggleCenterLock
        GestureAction.BACK -> GameCommand.Back
        GestureAction.HOME -> GameCommand.Home
        GestureAction.NOTIFICATIONS -> GameCommand.Notifications
        GestureAction.ALL_APPS -> GameCommand.AllApps
        GestureAction.TOUCH_HOLD -> GameCommand.TouchHold
        GestureAction.DRAG -> GameCommand.ToggleDrag
    }

    /**
     * The drag toggle as what it means right now, given whether a drag is [dragging]. Resolved before
     * any pause check, so the same gesture that picked something up can always put it down.
     */
    fun resolveDrag(command: GameCommand, dragging: Boolean): GameCommand = when {
        command != GameCommand.ToggleDrag -> command
        dragging -> GameCommand.Drop
        else -> GameCommand.StartDrag
    }

    /** The head joystick settled on [direction]; null when no button is mapped to it. */
    fun fromJoystick(direction: JoystickDirection, buttons: List<MappedButton>): GameCommand? =
        if (direction == JoystickDirection.CENTER) null
        else buttonFor(buttons, TriggerType.JOYSTICK, direction.name)?.let { GameCommand.Press(it) }

    /**
     * Commands that act **where the user is looking**. In joystick mode the head steers the game's
     * movement stick instead of a pointer, so there is no visible spot to act on and PWDe says so
     * rather than tapping somewhere the user cannot see. Everything else — every mapped button,
     * the system actions, the mode switches — works in both modes.
     */
    fun needsPointer(command: GameCommand): Boolean = when (command) {
        GameCommand.Select, GameCommand.TouchHold, GameCommand.StartDrag, GameCommand.ToggleDrag -> true
        is GameCommand.Scroll -> true
        else -> false
    }

    /** While paused only commands that control PWDe itself still work. */
    fun worksWhilePaused(command: GameCommand): Boolean = when (command) {
        GameCommand.Pause, GameCommand.Resume, GameCommand.TogglePause, GameCommand.Recenter, GameCommand.Exit,
        GameCommand.ToggleCenterLock, is GameCommand.CenterLock,
        GameCommand.HideOverlay, GameCommand.ShowOverlay, GameCommand.ShowControls, GameCommand.HideControls, GameCommand.CursorMode, GameCommand.JoystickMode,
        GameCommand.GyroMode, GameCommand.HeadTracking,
        GameCommand.GameMode, GameCommand.NavigationMode,
        GameCommand.Drop, is GameCommand.Ignored -> true
        else -> false
    }

    /** True for the commands that steer the phone rather than the game. */
    fun isNavigationCommand(command: GameCommand): Boolean = command in NAVIGATION_COMMANDS

    /**
     * Why the center lock is refused in [outputMode], or null when it may run. The lock brakes the
     * movement stick, so it only means anything where there is one: a cursor has nothing to hold still.
     * Centering is NOT phone navigation, so game mode never refuses it — only the mode without a stick.
     */
    fun centerLockRefusal(outputMode: FaceOutputMode): String? =
        if (outputMode == FaceOutputMode.JOYSTICK) null
        else "Locking the centre needs a joystick — say \"joystick mode\" for it"

    /**
     * Why [command] is refused in [mode], or null when it may run. Game mode keeps the phone's own
     * navigation out of a match, and the refusal is spoken rather than swallowed: silence is
     * indistinguishable from a broken microphone, the lesson this repo keeps relearning.
     */
    fun navigationRefusal(command: GameCommand, mode: NavigationMode): String? =
        if (mode == NavigationMode.NAVIGATION || !isNavigationCommand(command)) null
        else "\"${navigationLabel(command)}\" is off in game mode — say \"navigation mode\" to use it"

    private fun navigationLabel(command: GameCommand): String = when (command) {
        GameCommand.Back -> "Back"
        GameCommand.Home -> "Home"
        GameCommand.Recents -> "Recent apps"
        GameCommand.Notifications -> "Notifications"
        GameCommand.AllApps -> "All apps"
        else -> "That"
    }

    private fun buttonFor(buttons: List<MappedButton>, type: TriggerType, value: String): MappedButton? =
        buttons.firstOrNull { it.trigger?.type == type && it.trigger.value == value }
}
