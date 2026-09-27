package com.pwde.app.sensors

import com.pwde.app.data.model.VoiceActivationMode
import com.pwde.app.data.model.VoiceMatchMode
import com.pwde.app.sensors.voice.BaseInGameVoiceEngine
import com.pwde.app.sensors.voice.InGameVoiceResult
import com.pwde.app.sensors.voice.MicArbiter
import com.pwde.app.sensors.voice.VoiceCommandBinding
import com.pwde.app.play.GameInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A bare engine to exercise the contract every implementation inherits from the base class. */
private class TestEngine : BaseInGameVoiceEngine() {
    var started = false
    override fun start() {
        started = true
    }
    override fun stop() {
        started = false
    }
    fun hear(vararg text: String, isFinal: Boolean = true, confidences: FloatArray? = null) =
        onTranscript(text.toList(), confidences, isFinal)
    fun configure(match: VoiceMatchMode, activation: VoiceActivationMode) {
        matchMode = match
        activationMode = activation
    }
}

class InGameVoiceEngineTest {
    private val gameCommands = GameInput.STANDARD_BINDINGS + VoiceCommandBinding("button:1", listOf("attack"))

    private fun collect(engine: TestEngine, block: () -> Unit): List<InGameVoiceResult> = mutableListOf<InGameVoiceResult>().also { out ->
        runTest(UnconfinedTestDispatcher()) {
            val job = engine.results.onEach { out += it }.launchIn(this)
            block()
            job.cancel()
        }
    }

    @Test
    fun onlyLoadedCommandsAreRecognized() {
        val engine = TestEngine().apply { loadCommands(gameCommands) }
        val results = collect(engine) {
            engine.hear("attack now")
            engine.hear("settings") // an app-wide command, not a game one
        }
        assertEquals("button:1", results[0].commandId)
        assertEquals(null, results[1].commandId)
        assertEquals("settings", results[1].rawText)
    }

    @Test
    fun backPauseAndMenuAreAlwaysThere() {
        val engine = TestEngine().apply { loadCommands(GameInput.STANDARD_BINDINGS) }
        val results = collect(engine) {
            engine.hear("go back")
            engine.hear("pause")
            engine.hear("pwde menu")
        }
        assertEquals(listOf(GameInput.BACK, GameInput.PAUSE, GameInput.MENU), results.map { it.commandId })
    }

    @Test
    fun reloadingReplacesTheCommandSet() {
        val engine = TestEngine().apply { loadCommands(gameCommands) }
        engine.loadCommands(GameInput.STANDARD_BINDINGS)
        val results = collect(engine) { engine.hear("attack") }
        assertEquals(null, results.single().commandId)
    }

    @Test
    fun typedFallbackMatchesLikeSpeech() {
        val engine = TestEngine().apply { loadCommands(gameCommands) }
        val results = collect(engine) {
            engine.submitText("Attack!")
            engine.submitText("dance")
        }
        assertEquals("button:1", results[0].commandId)
        assertEquals(InGameVoiceResult.CONFIDENCE_TYPED, results[0].confidence)
        assertEquals(null, results[1].commandId)
    }

    @Test
    fun immediateFiresOnPartialOnceAfterFinishWaits() {
        val engine = TestEngine().apply { loadCommands(gameCommands) }
        val immediate = collect(engine) {
            engine.hear("attack", isFinal = false)
            engine.hear("attack", isFinal = true)
        }
        assertEquals(listOf("button:1"), immediate.map { it.commandId })

        engine.configure(VoiceMatchMode.WORD_ANYWHERE, VoiceActivationMode.AFTER_FINISH)
        val afterFinish = collect(engine) {
            engine.hear("attack", isFinal = false)
            engine.hear("attack", isFinal = true)
        }
        assertEquals(listOf("button:1"), afterFinish.map { it.commandId })
    }

    @Test
    fun partialNonMatchesAreSilent() {
        val engine = TestEngine().apply { loadCommands(gameCommands) }
        assertTrue(collect(engine) { engine.hear("hmm", isFinal = false) }.isEmpty())
    }

    @Test
    fun confidenceComesFromTheMatchingHypothesis() {
        val engine = TestEngine().apply { loadCommands(gameCommands) }
        val results = collect(engine) { engine.hear("a tack", "attack", confidences = floatArrayOf(0.4f, 0.9f)) }
        assertEquals(0.9f, results.single().confidence, 0f)
        val unknown = collect(engine) { engine.hear("attack") }
        assertEquals(InGameVoiceResult.CONFIDENCE_UNKNOWN, unknown.single().confidence)
    }

    @Test
    fun micArbiterHandsTheMicToTheGameAndBack() = runTest {
        val arbiter = MicArbiter()
        val holder = MicArbiter.newHolder("test")
        assertFalse(arbiter.gameHasMic.first())
        arbiter.takeForGame(holder)
        assertTrue(arbiter.gameHasMic.first())
        arbiter.releaseFromGame(holder)
        assertFalse(arbiter.gameHasMic.first())
    }

    /**
     * PWDe has more than one engine in the game slot (the app recognizer for PWDe's own screens, the
     * sherpa spotter over the real game). With a single boolean the first release cleared the slot
     * while the other was still recording, letting the app-wide recognizer start on top of it — two
     * `AudioRecord`s then fight for the microphone and the loser hears silence, so the spotter goes
     * deaf and in-game commands stop firing.
     */
    @Test
    fun oneEngineReleasingLeavesTheMicWithTheOneStillHoldingIt() = runTest {
        val arbiter = MicArbiter()
        val inApp = MicArbiter.newHolder("platform")
        val overGame = MicArbiter.newHolder("sherpa")
        arbiter.takeForGame(inApp)
        arbiter.takeForGame(overGame)
        arbiter.releaseFromGame(inApp)
        assertTrue("the engine over the real game is still recording", arbiter.busy.first())
        arbiter.releaseFromGame(overGame)
        assertFalse(arbiter.busy.first())
    }

    /** Holder names are per instance, so two engines can never be handed the same one. */
    @Test
    fun everyEngineGetsItsOwnHolderName() {
        assertFalse(MicArbiter.newHolder("sherpa") == MicArbiter.newHolder("sherpa"))
    }

    /** Releasing a name that never took it must not free somebody else's claim. */
    @Test
    fun releasingAnUnknownHolderChangesNothing() = runTest {
        val arbiter = MicArbiter()
        arbiter.takeForGame(MicArbiter.newHolder("sherpa"))
        arbiter.releaseFromGame(MicArbiter.newHolder("platform"))
        assertTrue(arbiter.busy.first())
    }

    @Test
    fun micArbiterIsBusyWhileTheGameOrTheWakeWordHasTheMic() = runTest {
        val arbiter = MicArbiter()
        val holder = MicArbiter.newHolder("sherpa")
        assertFalse(arbiter.busy.first())
        arbiter.takeForWakeWord()
        assertTrue(arbiter.busy.first())
        arbiter.takeForGame(holder)
        arbiter.releaseFromWakeWord()
        assertTrue(arbiter.busy.first())
        arbiter.releaseFromGame(holder)
        assertFalse(arbiter.busy.first())
    }
}
