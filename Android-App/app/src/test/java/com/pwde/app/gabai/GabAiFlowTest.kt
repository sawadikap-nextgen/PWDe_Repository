package com.pwde.app.gabai

import com.pwde.app.data.gabai.Axis
import com.pwde.app.data.gabai.GabAiCodec
import com.pwde.app.data.gabai.GabAiFlow
import com.pwde.app.data.gabai.GabAiForm
import com.pwde.app.data.gabai.GabAiState
import com.pwde.app.data.gabai.JoystickParameter
import com.pwde.app.data.model.ButtonTrigger
import com.pwde.app.data.model.CursorTuning
import com.pwde.app.data.model.FaceOutputMode
import com.pwde.app.data.model.FacialGesture
import com.pwde.app.data.model.JoystickTuning
import com.pwde.app.data.model.MappedButton
import com.pwde.app.data.model.TriggerType
import com.pwde.app.data.model.VoiceActivationMode
import com.pwde.app.data.model.VoiceMatchMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GabAiFlowTest {
    private val threeButtons = GabAiForm(
        gameId = "mobile_legends",
        buttons = listOf(MappedButton(1, "Attack", 0.8f, 0.8f), MappedButton(2, "Skill", 0.7f, 0.7f), MappedButton(3, "Recall", 0.1f, 0.9f)),
    )

    @Test
    fun calibrationVisitsCursorAxesThenJoystickThenVoice() {
        val visited = mutableListOf<GabAiState>()
        var state: GabAiState = GabAiFlow.newCalibration()
        while (state is GabAiState.CalibrateCursorAxis) {
            visited += state
            state = GabAiFlow.axisDone(state.axis)
        }
        assertEquals(Axis.entries.map { GabAiState.CalibrateCursorAxis(it) }, visited)
        assertEquals(GabAiState.CalibrateJoystick(JoystickParameter.SENSITIVITY), state)
        state = GabAiFlow.joystickDone(JoystickParameter.SENSITIVITY)
        assertEquals(GabAiState.CalibrateJoystick(JoystickParameter.DEAD_ZONE), state)
        state = GabAiFlow.joystickDone(JoystickParameter.DEAD_ZONE)
        assertEquals(GabAiState.CalibrationVoiceSetup, state)
        assertEquals(GabAiState.CalibrationSaved, GabAiFlow.calibrationSaved())
    }

    @Test
    fun gestureTestVisitsEveryGestureThenTheResults() {
        val form = GabAiForm()
        var state = GabAiFlow.voiceDone(form)
        val seen = mutableListOf<GabAiState>()
        while (state is GabAiState.CalibrationGestureTest) {
            seen += state
            state = GabAiFlow.gestureTested(state, form)
        }
        assertEquals(GabAiState.GESTURE_TEST.indices.map { GabAiState.CalibrationGestureTest(it) }, seen)
        assertEquals(GabAiState.CalibrationGestureReview, state)
        assertEquals(GabAiState.CalibrationGestureReview, GabAiFlow.gestureTestEnded())
    }

    @Test
    fun retryingVisitsOnlyTheMissedGestures() {
        val tests = GabAiState.GESTURE_TEST
        val missed = setOf(tests[1], tests[4])
        val form = GabAiForm(passedGestures = tests.toSet() - missed)
        val first = GabAiFlow.retryMissedGestures(form)
        assertEquals(GabAiState.CalibrationGestureTest(1), first)
        val second = GabAiFlow.gestureTested(first as GabAiState.CalibrationGestureTest, form)
        assertEquals(GabAiState.CalibrationGestureTest(4), second)
        assertEquals(GabAiState.CalibrationGestureReview, GabAiFlow.gestureTested(second as GabAiState.CalibrationGestureTest, form))
        // Every gesture done: nothing to retry.
        assertEquals(GabAiState.CalibrationGestureReview, GabAiFlow.voiceDone(GabAiForm(passedGestures = tests.toSet())))
    }

    @Test
    fun afterCalibrationContinueToGameSkipsGamePickIfAlreadyChosen() {
        assertEquals(GabAiState.ChooseGame, GabAiFlow.continueToGame(GabAiForm()))
        assertEquals(GabAiState.ConfirmCalibrationProfile, GabAiFlow.continueToGame(GabAiForm(gameId = "clash_royale")))
    }

    @Test
    fun gameProfileBranchAssignsAllButtonsOnOneScreenThenTestsThem() {
        assertEquals(GabAiState.ChooseGame, GabAiFlow.newGameProfile(GabAiForm()))
        assertEquals(GabAiState.ConfirmCalibrationProfile, GabAiFlow.gameChosen())
        assertEquals(GabAiState.UploadScreenshot, GabAiFlow.calibrationConfirmed())
        assertEquals(GabAiState.ButtonMapping(3), GabAiFlow.screenshotDone(threeButtons))

        assertEquals(GabAiState.AssignTriggers, GabAiFlow.buttonsDone(threeButtons))
        // Not every button has a trigger yet: stay on the trigger screen.
        assertEquals(GabAiState.AssignTriggers, GabAiFlow.triggersDone(threeButtons))
        val mapped = threeButtons.copy(buttons = threeButtons.buttons.map { it.copy(trigger = ButtonTrigger(TriggerType.VOICE, it.label)) })
        assertEquals(GabAiState.TestControls, GabAiFlow.triggersDone(mapped))
        assertEquals(GabAiState.NameAndSaveProfile, GabAiFlow.testingDone())
        assertEquals(GabAiState.ProfileSaved, GabAiFlow.profileSaved())
        assertEquals(GabAiState.ChooseGame, GabAiFlow.createAnother())
    }

    @Test
    fun cannotLeaveButtonMappingWithoutButtons() {
        assertEquals(GabAiState.ButtonMapping(0), GabAiFlow.buttonsDone(GabAiForm()))
    }

    /**
     * The screen is only held in the game's orientation while the user is working on the game's own
     * screen. Once mapping is done, naming and saving is a form: holding it landscape left the device
     * stuck sideways after the mapping was finished.
     */
    @Test
    fun orientationLockCoversTheGameScreenStepsOnly() {
        // Placing, assigning and testing are done on the game's screen.
        assertTrue(GabAiFlow.locksGameScreenOrientation(GabAiState.ButtonMapping(3)))
        assertTrue(GabAiFlow.locksGameScreenOrientation(GabAiState.AssignTriggers))
        assertFalse(GabAiFlow.locksGameScreenOrientation(GabAiState.TestControls))

        // Everything after that is a form or a choice, so the lock is released.
        assertFalse(GabAiFlow.locksGameScreenOrientation(GabAiState.NameAndSaveProfile))
        assertFalse(GabAiFlow.locksGameScreenOrientation(GabAiState.ProfileSaved))
        assertFalse(GabAiFlow.locksGameScreenOrientation(GabAiState.Welcome))
        assertFalse(GabAiFlow.locksGameScreenOrientation(GabAiState.ChooseGame))
        assertFalse(GabAiFlow.locksGameScreenOrientation(GabAiState.UploadScreenshot))
        assertFalse(GabAiFlow.locksGameScreenOrientation(GabAiState.CalibrationVoiceSetup))
    }

    @Test
    fun newGameProfileForAKnownGameSkipsTheGamePick() {
        assertEquals(GabAiState.ConfirmCalibrationProfile, GabAiFlow.newGameProfile(GabAiForm(gameId = "clash_royale")))
    }

    @Test
    fun backWalksEachBranchInReverse() {
        val cursor = GabAiForm(calibrationMode = FaceOutputMode.CURSOR)
        assertNull(GabAiFlow.back(GabAiState.Welcome, cursor))
        assertEquals(GabAiState.Welcome, GabAiFlow.back(GabAiState.CalibrateCursorAxis(Axis.UP), cursor))
        assertEquals(GabAiState.CalibrateCursorAxis(Axis.LEFT), GabAiFlow.back(GabAiState.CalibrateCursorAxis(Axis.RIGHT), cursor))
        assertEquals(
            GabAiState.CalibrateCursorAxis(Axis.DIAGONAL),
            GabAiFlow.back(GabAiState.CalibrateJoystick(JoystickParameter.SENSITIVITY), cursor),
        )
        assertEquals(
            GabAiState.CalibrateJoystick(JoystickParameter.SENSITIVITY),
            GabAiFlow.back(GabAiState.CalibrateJoystick(JoystickParameter.DEAD_ZONE), cursor),
        )
        assertEquals(
            GabAiState.CalibrateJoystick(JoystickParameter.DEAD_ZONE),
            GabAiFlow.back(GabAiState.CalibrationVoiceSetup, cursor),
        )
        assertEquals(GabAiState.CalibrationVoiceSetup, GabAiFlow.back(GabAiState.CalibrationGestureTest(0), cursor))
        assertEquals(GabAiState.CalibrationGestureTest(2), GabAiFlow.back(GabAiState.CalibrationGestureTest(3), cursor))
        assertEquals(
            GabAiState.CalibrationGestureTest(GabAiState.GESTURE_TEST.lastIndex),
            GabAiFlow.back(GabAiState.CalibrationGestureReview, cursor),
        )
        assertEquals(GabAiState.ChooseGame, GabAiFlow.back(GabAiState.ConfirmCalibrationProfile, cursor))
        assertEquals(GabAiState.ButtonMapping(3), GabAiFlow.back(GabAiState.AssignTriggers, threeButtons))
        assertEquals(GabAiState.AssignTriggers, GabAiFlow.back(GabAiState.TestControls, threeButtons))
        assertEquals(GabAiState.TestControls, GabAiFlow.back(GabAiState.NameAndSaveProfile, threeButtons))
    }

    @Test
    fun calibrationStartedForAGameBacksOutToThatGame() {
        val form = GabAiForm(continueToGame = true, gameId = "clash_royale")
        assertEquals(GabAiState.ConfirmCalibrationProfile, GabAiFlow.back(GabAiState.CalibrateCursorAxis(Axis.UP), form))
    }
}

class GabAiCodecTest {
    private val allStates: List<GabAiState> = listOf(
        GabAiState.Welcome,
        GabAiState.CalibrationVoiceSetup, GabAiState.CalibrationSaved, GabAiState.ChooseGame,
        GabAiState.ConfirmCalibrationProfile, GabAiState.UploadScreenshot, GabAiState.NameAndSaveProfile,
        GabAiState.ProfileSaved, GabAiState.ButtonMapping(4), GabAiState.AssignTriggers, GabAiState.TestControls,
        GabAiState.CalibrationGestureTest(3), GabAiState.CalibrationGestureReview,
    ) + Axis.entries.map { GabAiState.CalibrateCursorAxis(it) } + JoystickParameter.entries.map { GabAiState.CalibrateJoystick(it) }

    @Test
    fun everyStateRoundTrips() {
        allStates.forEach { assertEquals(it, GabAiCodec.decodeState(GabAiCodec.encodeState(it))) }
    }

    @Test
    fun unreadableStatesFallBackSafely() {
        assertEquals(GabAiState.Welcome, GabAiCodec.decodeState(null))
        assertEquals(GabAiState.Welcome, GabAiCodec.decodeState("SomethingNew"))
        assertEquals(GabAiState.CalibrateCursorAxis(Axis.UP), GabAiCodec.decodeState("CalibrateCursorAxis:SIDEWAYS"))
        assertEquals(GabAiState.CalibrateCursorAxis(Axis.UP), GabAiCodec.decodeState("ChooseCalibrationMode"))
        assertEquals(
            GabAiState.CalibrateJoystick(JoystickParameter.SENSITIVITY),
            GabAiCodec.decodeState("CalibrateJoystick"),
        )
        assertEquals(
            GabAiState.CalibrateJoystick(JoystickParameter.SENSITIVITY),
            GabAiCodec.decodeState("CalibrateJoystick:UNKNOWN"),
        )
        // Sessions saved mid one-button-at-a-time assignment resume on the all-buttons screen.
        assertEquals(GabAiState.AssignTriggers, GabAiCodec.decodeState("TriggerAssignment:1:3"))
        assertEquals(GabAiState.CalibrationGestureTest(0), GabAiCodec.decodeState("CalibrationGestureTest:999"))
    }

    @Test
    fun formRoundTripsIncludingButtonsAndTriggers() {
        val form = GabAiForm(
            calibrationMode = FaceOutputMode.JOYSTICK,
            cursor = CursorTuning(2, 3, 4, 5, 6),
            joystick = JoystickTuning(size = 8, sensitivity = 2, deadZone = 4, centerPitch = -6.5f, centerRoll = 3f),
            voiceEnabled = false,
            matchMode = VoiceMatchMode.EXACT,
            activationMode = VoiceActivationMode.AFTER_FINISH,
            passedGestures = setOf(FacialGesture.SMILE, FacialGesture.NOD),
            calibrationName = "Evening",
            savedCalibrationId = 7,
            continueToGame = true,
            gameId = "mobile_legends",
            calibrationProfileId = 7,
            screenshotPath = "/data/shot.img",
            buttons = listOf(
                MappedButton(1, "Attack", 0.8f, 0.75f, ButtonTrigger(TriggerType.VOICE, "attack")),
                MappedButton(2, "Skill 1", 0.6f, 0.8f, ButtonTrigger(TriggerType.GESTURE, "SMILE")),
                MappedButton(3, "Move", 0.2f, 0.7f, ButtonTrigger(TriggerType.JOYSTICK, "UP_LEFT")),
                MappedButton(4, "Unassigned", 0.5f, 0.5f),
            ),
            profileName = "Ranked",
            editingGameProfileId = 3,
            savedGameProfileId = 3,
        )
        assertEquals(form, GabAiCodec.decodeForm(GabAiCodec.encodeForm(form)))
    }

    @Test
    fun missingOrBrokenFormJsonGivesDefaults() {
        assertEquals(GabAiForm(), GabAiCodec.decodeForm(null))
        assertEquals(GabAiForm(), GabAiCodec.decodeForm("not json"))
        assertEquals(GabAiForm(gameId = "clash_royale"), GabAiCodec.decodeForm("""{"gameId":"clash_royale"}"""))
    }
}
