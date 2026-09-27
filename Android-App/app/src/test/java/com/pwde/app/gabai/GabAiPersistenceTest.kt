package com.pwde.app.gabai

import android.content.Context
import androidx.camera.core.SurfaceRequest
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pwde.app.data.gabai.Axis
import com.pwde.app.data.gabai.GabAiForm
import com.pwde.app.data.gabai.GabAiRepository
import com.pwde.app.data.gabai.GabAiSession
import com.pwde.app.data.gabai.GabAiState
import com.pwde.app.data.gabai.JoystickParameter
import com.pwde.app.data.local.ControlJson
import com.pwde.app.data.local.ControlsRepository
import com.pwde.app.data.local.ProfileRepository
import com.pwde.app.data.local.enabledGestures
import com.pwde.app.data.local.PwdeDatabase
import com.pwde.app.data.model.FacialGesture
import com.pwde.app.data.model.Game
import com.pwde.app.data.model.TriggerType
import com.pwde.app.data.model.ButtonTrigger
import com.pwde.app.data.prefs.InputMode
import com.pwde.app.data.model.GestureAction
import com.pwde.app.sensors.face.FaceState
import com.pwde.app.sensors.face.GestureReading
import com.pwde.app.sensors.face.FaceTrackingManager
import com.pwde.app.sensors.voice.VoiceCommand
import com.pwde.app.sensors.voice.VoiceCommandManager
import com.pwde.app.sensors.voice.VoiceResult
import com.pwde.app.sensors.voice.VoiceState
import com.pwde.app.ui.FakeSettingsRepository
import com.pwde.app.ui.MainDispatcherRule
import com.pwde.app.ui.gabai.GabAiNavigation
import com.pwde.app.ui.gabai.GabAiStart
import com.pwde.app.ui.gabai.GabAiViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.LooperMode

private class FakeFaceTracking : FaceTrackingManager {
    override val state = MutableStateFlow(FaceState())
    override val gestureEvents = MutableSharedFlow<FacialGesture>()
    override val surfaceRequest = MutableStateFlow<SurfaceRequest?>(null)
    override val hasCameraPermission = false
    override fun refreshPermissions() = Unit
    override fun recenterCursor() = Unit
    override suspend fun captureJoystickCenter(persistToActiveProfile: Boolean) = false
}

private class FakeVoice : VoiceCommandManager {
    override val state = MutableStateFlow(VoiceState())
    override val results = MutableSharedFlow<VoiceResult>()
    override val hasMicPermission = false
    override fun refreshPermissions() = Unit
    override fun submitText(text: String) = Unit
    override fun setScreenCommands(owner: Any, commands: List<VoiceCommand>) = Unit
    override fun clearScreenCommands(owner: Any) = Unit
    override fun setDictating(owner: Any, dictating: Boolean) = Unit
}

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class GabAiPersistenceTest {
    @get:Rule
    val mainRule = MainDispatcherRule()

    private lateinit var db: PwdeDatabase
    private lateinit var gabAi: GabAiRepository
    private lateinit var profiles: ProfileRepository
    private lateinit var controls: ControlsRepository
    private val settings = FakeSettingsRepository()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Inline executors make Room run each query synchronously, so the tests are deterministic.
        db = Room.inMemoryDatabaseBuilder(context, PwdeDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        gabAi = GabAiRepository(context, db.gabAiSessionDao())
        profiles = ProfileRepository(db.calibrationProfileDao(), db.gameProfileDao())
        controls = ControlsRepository(db.controlSettingsDao())
    }

    @After
    fun tearDown() = db.close()

    private val face = FakeFaceTracking()

    private fun newViewModel(start: GabAiStart = GabAiStart.Welcome) =
        GabAiViewModel(gabAi, profiles, controls, settings, FakeVoice(), face, start)

    private fun showFace(vararg active: FacialGesture) {
        face.state.value = FaceState(gesture = GestureReading(active = active.toSet()))
    }

    /** Lets anything still in flight (e.g. flows collecting on Room's invalidation thread) land. */
    private fun settle() = Thread.sleep(50)

    @Test
    fun repositoryKeepsOnlyTheUnfinishedSession() = runBlocking {
        val session = GabAiSession(gabAi.newSessionId(), GabAiState.CalibrateCursorAxis(Axis.LEFT), GabAiForm(calibrationName = "x"))
        gabAi.save(session)
        assertEquals(session, gabAi.unfinishedSession())
        gabAi.complete(session.id)
        assertNull(gabAi.unfinishedSession())
    }

    @Test
    fun forceCloseMidCalibrationResumesOnTheSameStep() {
        val first = newViewModel()
        first.startCalibration()
        settle()
        first.axisDone(Axis.UP)
        first.setCursor(first.ui.value.form.cursor.copy(speedDown = 9))
        settle()
        // "Force-close": the ViewModel is gone; a brand-new one reads the same database.
        val reopened = newViewModel()
        settle()
        val resumable = reopened.ui.value.resumable
        assertNotNull(resumable)
        reopened.resume()
        assertEquals(GabAiState.CalibrateCursorAxis(Axis.DOWN), reopened.ui.value.state)
        assertEquals(9, reopened.ui.value.form.cursor.speedDown)
        assertEquals(InputMode.HEAD_FACE, settings.settings.value.inputMode)
    }

    @Test
    fun fullGameProfileFlowSavesAReusableProfile() {
        val vm = newViewModel()
        vm.startCalibration()
        settle()
        Axis.entries.forEach(vm::axisDone)
        vm.joystickDone(JoystickParameter.SENSITIVITY)
        vm.joystickDone(JoystickParameter.DEAD_ZONE)
        vm.setCalibrationName("Tilt")
        vm.saveCalibration()
        settle()
        assertEquals(GabAiState.CalibrationSaved, vm.ui.value.state)
        val calibrationId = runBlocking { profiles.calibrationProfiles.first().single().id }

        vm.continueToGame()
        vm.chooseGame(Game.MOBILE_LEGENDS)
        vm.confirmCalibration()
        settle()
        vm.useBlankScreen()
        vm.addButton(0.8f, 0.8f)
        vm.addButton(0.2f, 0.7f)
        vm.buttonsDone()
        assertEquals(GabAiState.AssignTriggers, vm.ui.value.state)
        val (first, second) = vm.ui.value.form.buttons.map { it.id }
        vm.setTrigger(first, ButtonTrigger(TriggerType.VOICE, "attack"))
        // One button still unmapped: done opens its chooser instead of moving on.
        vm.triggersDone()
        assertEquals(GabAiState.AssignTriggers, vm.ui.value.state)
        assertEquals(second, vm.ui.value.selectedButtonId)
        vm.pickGesture(second, FacialGesture.SMILE)
        assertNull(vm.ui.value.selectedButtonId)
        vm.triggersDone()
        assertEquals(GabAiState.TestControls, vm.ui.value.state)
        vm.testingDone()
        assertEquals(GabAiState.NameAndSaveProfile, vm.ui.value.state)
        vm.setProfileName("Ranked")
        vm.saveGameProfile()
        settle()

        assertEquals(GabAiState.ProfileSaved, vm.ui.value.state)
        val saved = runBlocking { profiles.gameProfiles.first().single() }
        assertEquals("Ranked", saved.profileName)
        assertEquals(calibrationId, saved.calibrationProfileId)
        val buttons = ControlJson.decodeButtons(saved.buttonMappingsJson)
        assertEquals(listOf("attack", FacialGesture.SMILE.name), buttons.map { it.trigger?.value })
        // Saved means finished: nothing left to "continue".
        assertNull(runBlocking { gabAi.unfinishedSession() })
    }

    @Test
    fun onlyPerformedGesturesAreEnabledInTheSavedCalibration() {
        runBlocking { controls.setGesture(GestureAction.SELECT, FacialGesture.NOD) }
        val vm = newViewModel()
        vm.startCalibration()
        settle()
        Axis.entries.forEach(vm::axisDone)
        vm.joystickDone(JoystickParameter.SENSITIVITY)
        vm.joystickDone(JoystickParameter.DEAD_ZONE)
        val first = GabAiState.GESTURE_TEST[0]
        // Already held when the step opens: doesn't count until released and done again.
        showFace(first)
        vm.voiceDone()
        assertEquals(GabAiState.CalibrationGestureTest(0), vm.ui.value.state)
        settle()
        assertEquals(emptySet<FacialGesture>(), vm.ui.value.form.passedGestures)
        showFace()
        showFace(first)
        settle()
        assertEquals(setOf(first), vm.ui.value.form.passedGestures)
        // Moves on by itself after a short pause.
        mainRule.dispatcher.scheduler.advanceTimeBy(2_000)
        mainRule.dispatcher.scheduler.runCurrent()
        assertEquals(GabAiState.CalibrationGestureTest(1), vm.ui.value.state)

        // Skipping leaves the rest off.
        vm.nextGesture()
        assertEquals(GabAiState.CalibrationGestureTest(2), vm.ui.value.state)
        vm.skipRemainingGestures()
        assertEquals(GabAiState.CalibrationGestureReview, vm.ui.value.state)
        vm.retryMissedGestures()
        assertEquals(GabAiState.CalibrationGestureTest(1), vm.ui.value.state)
        vm.skipRemainingGestures()
        vm.saveCalibration()
        settle()

        assertEquals(GabAiState.CalibrationSaved, vm.ui.value.state)
        val saved = runBlocking { profiles.calibrationProfiles.first().single() }
        assertEquals(setOf(first), saved.enabledGestures)
        val working = runBlocking { controls.config.first() }
        assertEquals(setOf(first), working.enabledGestures)
        // Nod wasn't performed, so the action mapped to it was unmapped.
        assertNull(working.gestureAssignments[GestureAction.SELECT])
    }

    @Test
    fun editingAGameProfileStartsAtButtonMappingAndUpdatesInPlace() {
        val vm = newViewModel()
        vm.startGameProfile("clash_royale")
        settle()
        vm.useBlankScreen()
        vm.addButton(0.5f, 0.5f)
        vm.buttonsDone()
        vm.setTrigger(vm.ui.value.form.buttons.single().id, ButtonTrigger(TriggerType.GESTURE, FacialGesture.SMILE.name))
        vm.triggersDone()
        vm.testingDone()
        vm.saveGameProfile()
        settle()
        val id = runBlocking { profiles.gameProfiles.first().single().id }

        val editor = newViewModel(GabAiStart.EditGameProfile(id))
        settle()
        assertEquals(GabAiState.ButtonMapping(1), editor.ui.value.state)
        editor.renameButton(editor.ui.value.form.buttons.single().id, "Deploy")
        editor.buttonsDone()
        val editedButton = editor.ui.value.form.buttons.single()
        editor.openTriggerChooser(editedButton.id)
        editor.setTrigger(editedButton.id, ButtonTrigger(TriggerType.VOICE, "deploy"))
        editor.triggersDone()
        editor.testingDone()
        editor.saveGameProfile()
        settle()
        val all = runBlocking { profiles.gameProfiles.first() }
        assertEquals(1, all.size)
        val savedButton = ControlJson.decodeButtons(all.single().buttonMappingsJson).single()
        assertEquals("Deploy", savedButton.label)
        assertEquals(ButtonTrigger(TriggerType.VOICE, "deploy"), savedButton.trigger)
    }

    /** The next navigation request, or null if none arrives. */
    private fun GabAiViewModel.nextNavigation(): GabAiNavigation? =
        runBlocking { withTimeoutOrNull(200) { navigation.first() } }

    private fun savedGameProfileId(): Long {
        val vm = newViewModel()
        vm.startGameProfile("clash_royale")
        settle()
        vm.useBlankScreen()
        vm.addButton(0.5f, 0.5f)
        vm.buttonsDone()
        vm.setTrigger(vm.ui.value.form.buttons.single().id, ButtonTrigger(TriggerType.GESTURE, FacialGesture.SMILE.name))
        vm.triggersDone()
        vm.testingDone()
        vm.saveGameProfile()
        settle()
        return runBlocking { profiles.gameProfiles.first().single().id }
    }

    @Test
    fun backFromTheStepAnotherScreenOpenedLeavesGabAi() {
        val editor = newViewModel(GabAiStart.EditGameProfile(savedGameProfileId()))
        settle()
        assertEquals(GabAiState.ButtonMapping(1), editor.ui.value.state)
        editor.back()
        // Straight back to Game Detail / Profile, not GabAI's Welcome, which the user never saw.
        assertEquals(GabAiNavigation.Exit, editor.nextNavigation())
    }

    @Test
    fun backStepsThroughVisitedStepsBeforeLeaving() {
        val editor = newViewModel(GabAiStart.EditGameProfile(savedGameProfileId()))
        settle()
        editor.buttonsDone()
        editor.back()
        assertEquals(GabAiState.ButtonMapping::class, editor.ui.value.state::class)
        assertNull(editor.nextNavigation())
        editor.back()
        assertEquals(GabAiNavigation.Exit, editor.nextNavigation())
    }

    @Test
    fun backInAFlowStartedFromWelcomeReturnsToWelcomeFirst() {
        val vm = newViewModel()
        vm.startCalibration()
        settle()
        vm.back()
        assertNull(vm.ui.value.session)
        assertNull(vm.nextNavigation())
        vm.back()
        assertEquals(GabAiNavigation.Exit, vm.nextNavigation())
    }
}
