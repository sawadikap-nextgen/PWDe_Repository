package com.pwde.app.play

import com.pwde.app.data.model.ButtonTrigger
import com.pwde.app.data.model.ControlConfig
import com.pwde.app.data.model.FaceOutputMode
import com.pwde.app.data.model.FacialGesture
import com.pwde.app.data.model.GestureAction
import com.pwde.app.data.model.MappedButton
import com.pwde.app.data.model.NavigationMode
import com.pwde.app.data.model.TriggerType
import com.pwde.app.data.model.defaultNavigationMode
import com.pwde.app.data.model.navigationModeFor
import com.pwde.app.sensors.face.JoystickDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameInputTest {
    private val gesture = FacialGesture.entries.first()
    private val skill = MappedButton(1, "Skill", 0.8f, 0.7f, ButtonTrigger(TriggerType.VOICE, "fire"))
    private val attack = MappedButton(2, "Attack", 0.9f, 0.8f, ButtonTrigger(TriggerType.GESTURE, gesture.name))
    private val recall = MappedButton(3, "Recall", 0.5f, 0.9f, ButtonTrigger(TriggerType.JOYSTICK, JoystickDirection.UP.name))
    private val buttons = listOf(skill, attack, recall)

    @Test
    fun voiceTriggersBecomeBindings() {
        val bindings = GameInput.bindings(buttons)
        assertTrue(bindings.containsAll(GameInput.STANDARD_BINDINGS))
        assertEquals(listOf("fire"), bindings.single { it.commandId == GameInput.buttonCommandId(1) }.phrases)
        assertNull(bindings.firstOrNull { it.commandId == GameInput.buttonCommandId(2) })
    }

    @Test
    fun voiceCommandsMapToGameCommands() {
        assertEquals(GameCommand.Press(skill), GameInput.fromVoice(GameInput.buttonCommandId(1), "fire", buttons))
        assertEquals(GameCommand.Exit, GameInput.fromVoice(GameInput.MENU, "pwde menu", buttons))
        assertEquals(GameCommand.Back, GameInput.fromVoice(GameInput.BACK, "back", buttons))
        assertEquals(GameCommand.HideOverlay, GameInput.fromVoice(GameInput.HIDE_OVERLAY, "hide overlay", buttons))
        assertEquals(GameCommand.HideOverlay, GameInput.fromVoice(GameInput.HIDE_OVERLAY, "close overlay", buttons))
        assertEquals(GameCommand.ShowOverlay, GameInput.fromVoice(GameInput.SHOW_OVERLAY, "open overlay", buttons))
        assertEquals(GameCommand.ShowControls, GameInput.fromVoice(GameInput.SHOW_CONTROLS, "show controls", buttons))
        assertEquals(GameCommand.HideControls, GameInput.fromVoice(GameInput.HIDE_CONTROLS, "hide controls", buttons))
        assertTrue(GameInput.worksWhilePaused(GameCommand.ShowControls))
        assertTrue(GameInput.fromVoice(null, "banana", buttons) is GameCommand.Ignored)
        assertNull(GameInput.fromVoice(null, null, buttons))
    }

    /**
     * The in-game spotter hears the game's own audio, so ending the session can't be a bare, common
     * word: the game saying "menu" used to stop the session and pull PWDe over the game.
     */
    @Test
    fun leavingTheGameNeedsAnExplicitPhrase() {
        val phrases = GameInput.STANDARD_BINDINGS.flatMap { it.phrases }
        assertFalse("menu" in phrases)
        assertFalse("main menu" in phrases)
        assertFalse("exit" in phrases)
        assertFalse("quit" in phrases)
        assertEquals(GameCommand.Exit, GameInput.fromVoice(GameInput.EXIT, "exit game", buttons))
        assertEquals(GameCommand.Exit, GameInput.fromVoice(GameInput.EXIT, "stop pwde", buttons))
    }

    @Test
    fun cursorModeVoiceCommands() {
        val scrollDown = GameInput.STANDARD_BINDINGS.single { "scroll down" in it.phrases }
        assertEquals(GameCommand.Scroll(ScrollDirection.DOWN), GameInput.fromVoice(scrollDown.commandId, "scroll down", buttons))
        assertEquals(GameCommand.StartDrag, GameInput.fromVoice(GameInput.DRAG, "drag", buttons))
        assertEquals(GameCommand.Recents, GameInput.fromVoice(GameInput.RECENTS, "recent apps", buttons))
        assertEquals(GameCommand.JoystickMode, GameInput.fromVoice(GameInput.JOYSTICK_MODE, "joystick mode", buttons))
        // "drop" and mode switches still work while paused, so a drag can always be let go.
        assertTrue(GameInput.worksWhilePaused(GameCommand.Drop))
        assertFalse(GameInput.worksWhilePaused(GameCommand.StartDrag))
    }

    /**
     * Joystick mode takes the pointer away (the head steers the game's movement stick), so only the
     * commands that act where the user is looking need cursor mode. Everything a user can assign to a
     * button — the button's own voice trigger above all — works in both modes.
     */
    @Test
    fun onlyPointerCommandsNeedCursorMode() {
        assertEquals(GameCommand.Press(skill), GameInput.fromVoice(GameInput.buttonCommandId(1), "fire", buttons))
        assertFalse(GameInput.needsPointer(GameCommand.Press(skill)))
        assertFalse(GameInput.needsPointer(GameCommand.CursorMode))
        assertFalse(GameInput.needsPointer(GameCommand.JoystickMode))
        assertFalse(GameInput.needsPointer(GameCommand.Drop))
        listOf(
            GameCommand.Back, GameCommand.Home, GameCommand.Recents, GameCommand.Notifications,
            GameCommand.AllApps, GameCommand.Pause, GameCommand.Resume, GameCommand.TogglePause,
            GameCommand.Exit, GameCommand.Recenter, GameCommand.HideOverlay, GameCommand.ShowControls,
        ).forEach { assertFalse("$it acts at the pointer", GameInput.needsPointer(it)) }
        assertTrue(GameInput.needsPointer(GameCommand.Select))
        assertTrue(GameInput.needsPointer(GameCommand.TouchHold))
        assertTrue(GameInput.needsPointer(GameCommand.StartDrag))
        assertTrue(GameInput.needsPointer(GameCommand.Scroll(ScrollDirection.DOWN)))
    }

    @Test
    fun aButtonMappedToAGestureWinsOverItsAction() {
        assertEquals(GameCommand.Press(attack), GameInput.fromGesture(gesture, buttons, ControlConfig()))
    }

    @Test
    fun joystickDirectionsPressTheirButton() {
        assertEquals(GameCommand.Press(recall), GameInput.fromJoystick(JoystickDirection.UP, buttons))
        assertNull(GameInput.fromJoystick(JoystickDirection.DOWN, buttons))
        assertNull(GameInput.fromJoystick(JoystickDirection.CENTER, buttons))
    }

    @Test
    fun onlyPwdeControlsWorkWhilePaused() {
        assertTrue(GameInput.worksWhilePaused(GameCommand.Resume))
        assertTrue(GameInput.worksWhilePaused(GameCommand.Exit))
        assertFalse(GameInput.worksWhilePaused(GameCommand.Press(skill)))
        assertFalse(GameInput.worksWhilePaused(GameCommand.Select))
        assertFalse(GameInput.worksWhilePaused(GameCommand.Back))
    }

    /** Cursor mode is navigation mode and joystick mode is game mode, until a phrase says otherwise. */
    @Test
    fun theInputModeDecidesTheNavigationMode() {
        assertEquals(NavigationMode.GAME, FaceOutputMode.JOYSTICK.defaultNavigationMode())
        assertEquals(NavigationMode.NAVIGATION, FaceOutputMode.CURSOR.defaultNavigationMode())
        assertEquals(NavigationMode.GAME, navigationModeFor(null, FaceOutputMode.JOYSTICK))
        assertEquals(NavigationMode.NAVIGATION, navigationModeFor(null, FaceOutputMode.CURSOR))
        // The spoken phrase always wins, whichever way the head is driving.
        assertEquals(NavigationMode.NAVIGATION, navigationModeFor(NavigationMode.NAVIGATION, FaceOutputMode.JOYSTICK))
        assertEquals(NavigationMode.GAME, navigationModeFor(NavigationMode.GAME, FaceOutputMode.CURSOR))
    }

    @Test
    fun theModeSwitchIsSpokenAndWorksWhilePaused() {
        assertEquals(GameCommand.GameMode, GameInput.fromVoice(GameInput.GAME_MODE, "game mode", buttons))
        assertEquals(GameCommand.NavigationMode, GameInput.fromVoice(GameInput.NAVIGATION_MODE, "navigation mode", buttons))
        assertTrue(GameInput.worksWhilePaused(GameCommand.GameMode))
        assertTrue(GameInput.worksWhilePaused(GameCommand.NavigationMode))
        // Neither gives up a pointer, so neither is a command joystick mode has to refuse.
        assertFalse(GameInput.needsPointer(GameCommand.GameMode))
        assertFalse(GameInput.needsPointer(GameCommand.NavigationMode))
    }

    @Test
    fun gameModeBlocksPausingButAllowsResuming() {
        assertTrue(GameCommand.Pause.isPauseBlockedInGameMode(NavigationMode.GAME, paused = false))
        assertTrue(GameCommand.TogglePause.isPauseBlockedInGameMode(NavigationMode.GAME, paused = false))
        assertFalse(GameCommand.TogglePause.isPauseBlockedInGameMode(NavigationMode.GAME, paused = true))
        assertFalse(GameCommand.Resume.isPauseBlockedInGameMode(NavigationMode.GAME, paused = true))
        assertFalse(GameCommand.TogglePause.isPauseBlockedInGameMode(NavigationMode.NAVIGATION, paused = false))
    }

    /**
     * Game mode is what keeps the phone's own navigation out of a match: joystick mode is game mode
     * by default, so a stray "back" can't pull the user out of a game. It is refused out loud, never
     * dropped silently — silence is indistinguishable from a broken microphone.
     */
    @Test
    fun gameModeRefusesPhoneNavigation() {
        listOf(GameCommand.Back, GameCommand.Home, GameCommand.Recents, GameCommand.Notifications, GameCommand.AllApps)
            .forEach { command ->
                assertTrue("$command steers the phone", GameInput.isNavigationCommand(command))
                assertNotNull("$command is refused in game mode", GameInput.navigationRefusal(command, NavigationMode.GAME))
                assertNull("$command works in navigation mode", GameInput.navigationRefusal(command, NavigationMode.NAVIGATION))
            }
    }

    @Test
    fun gameModeLeavesPlayingTheGameAlone() {
        listOf(
            GameCommand.Press(skill), GameCommand.Select, GameCommand.TouchHold, GameCommand.StartDrag,
            GameCommand.Drop, GameCommand.Pause, GameCommand.Resume, GameCommand.Recenter,
            GameCommand.Scroll(ScrollDirection.DOWN), GameCommand.ShowControls, GameCommand.Exit,
            GameCommand.CursorMode, GameCommand.JoystickMode, GameCommand.GameMode, GameCommand.NavigationMode,
        ).forEach { command ->
            assertFalse("$command is a game command", GameInput.isNavigationCommand(command))
            assertNull("$command runs in game mode", GameInput.navigationRefusal(command, NavigationMode.GAME))
        }
    }

    /** A gesture assigned to Back/Home/Notifications/All apps becomes the same command, so it is gated too. */
    @Test
    fun aNavigationGestureIsRefusedInGameMode() {
        val config = ControlConfig(gestureAssignments = mapOf(GestureAction.BACK to gesture))
        val command = GameInput.fromGesture(gesture, emptyList(), config)
        assertEquals(GameCommand.Back, command)
        assertNotNull(GameInput.navigationRefusal(command, NavigationMode.GAME))
        assertNull(GameInput.navigationRefusal(command, NavigationMode.NAVIGATION))
    }

    /**
     * Centering is NOT phone navigation. Game mode turns off the phone's own navigation and nothing
     * else, so neither "Center" nor "Lock center" may ever be caught by a mode gate — and a new
     * action without a command is a compile error, so this list only grows on purpose.
     */
    @Test
    fun centeringIsNeverTreatedAsPhoneNavigation() {
        listOf(GestureAction.RECENTER, GestureAction.LOCK_CENTER).forEach { action ->
            assertTrue("${action.label} centres", action.isCentering)
            val command = GameInput.commandFor(action)
            assertFalse("${action.label} is not phone navigation", GameInput.isNavigationCommand(command))
            assertNull("${action.label} is never refused", GameInput.navigationRefusal(command, NavigationMode.GAME))
        }
        assertFalse(GestureAction.SELECT.isCentering)
        assertFalse(GestureAction.BACK.isCentering)
    }

    /**
     * A gesture toggles the brake, so the same move turns it on and off. Speech names the state
     * outright instead: "lock center" must never *unlock* just because it was already locked.
     */
    @Test
    fun theLockGestureTogglesAndSpeechNamesTheState() {
        assertEquals(GameCommand.ToggleCenterLock, GameInput.commandFor(GestureAction.LOCK_CENTER))
        assertEquals(GameCommand.Recenter, GameInput.commandFor(GestureAction.RECENTER))
        assertEquals(GameCommand.ToggleCenterLock, GameInput.fromGesture(gesture, emptyList(), ControlConfig(gestureAssignments = mapOf(GestureAction.LOCK_CENTER to gesture))))
        assertEquals(GameCommand.Recenter, GameInput.fromGesture(gesture, emptyList(), ControlConfig(gestureAssignments = mapOf(GestureAction.RECENTER to gesture))))
        assertEquals(GameCommand.CenterLock(true), GameInput.fromVoice(GameInput.LOCK_CENTER, "lock center", buttons))
        assertEquals(GameCommand.CenterLock(false), GameInput.fromVoice(GameInput.UNLOCK_CENTER, "unlock center", buttons))
        assertFalse(GameInput.fromVoice(GameInput.LOCK_CENTER, "lock center", buttons) == GameInput.fromVoice(GameInput.UNLOCK_CENTER, "unlock center", buttons))
    }

    /** The brake holds the movement stick still, so it is only refused where there is no stick. */
    @Test
    fun theBrakeOnlyMakesSenseWithAMovementStick() {
        assertNull(GameInput.centerLockRefusal(FaceOutputMode.JOYSTICK))
        assertNotNull(GameInput.centerLockRefusal(FaceOutputMode.CURSOR))
        assertTrue(GameInput.worksWhilePaused(GameCommand.ToggleCenterLock))
        assertTrue(GameInput.worksWhilePaused(GameCommand.CenterLock(true)))
        assertFalse(GameInput.needsPointer(GameCommand.ToggleCenterLock))
        assertFalse(GameInput.needsPointer(GameCommand.CenterLock(true)))
    }

    @Test
    fun profileCommandsSwitchTheGameProfile() {
        assertEquals(GameCommand.SwitchProfile(null), GameInput.fromVoice(GameInput.NEXT_PROFILE, "next profile", emptyList()))
        val binding = GameInput.profileBindings(listOf(7L to "Fanny")).single()
        assertEquals(listOf("profile Fanny"), binding.phrases)
        assertEquals(GameCommand.SwitchProfile(7L), GameInput.fromVoice(binding.commandId, "profile fanny", emptyList()))
        assertTrue(GameInput.worksWhilePaused(GameCommand.SwitchProfile(null)))
        assertNotNull(GameInput.navigationRefusal(GameCommand.SwitchProfile(null), NavigationMode.GAME))
        assertNull(GameInput.navigationRefusal(GameCommand.SwitchProfile(null), NavigationMode.NAVIGATION))
        assertEquals(GameCommand.Exit, GameInput.fromVoice(GameInput.EXIT, "back to dashboard", emptyList()))
    }
}
