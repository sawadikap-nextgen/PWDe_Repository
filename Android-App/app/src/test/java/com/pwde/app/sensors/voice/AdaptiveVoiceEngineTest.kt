package com.pwde.app.sensors.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine a live session listens with has to follow the user: sherpa-onnx over the real game, and
 * stood down while PWDe is in front so the app-wide Google recognizer is the one listening.
 *
 * The bug this pins: the choice used to be made once, when the session started. A session outlives a
 * visit to PWDe, so coming back to the app left the spotter holding the microphone — proved on device
 * by `SherpaWakeWord: Spotted "pause"` firing while `MainActivity` had focus.
 *
 * The engine is driven on an unconfined dispatcher so a focus change takes effect on the spot: the
 * switching itself is what is under test, not the coroutine scheduling.
 */
class AdaptiveVoiceEngineTest {
    private class FakeEngine(override val modelLabel: String) : InGameVoiceEngine {
        var started = 0
        var stopped = 0
        var commands: List<VoiceCommandBinding> = emptyList()

        private val _state = MutableStateFlow(InGameVoiceState())
        override val state: StateFlow<InGameVoiceState> = _state.asStateFlow()
        override val results: Flow<InGameVoiceResult> = emptyFlow()

        override fun loadCommands(commands: List<VoiceCommandBinding>) {
            this.commands = commands
        }

        override fun start() {
            started++
        }

        override fun stop() {
            stopped++
        }

        override fun submitText(text: String) = Unit
    }

    /** Unconfined: the collector reacts the instant the focus flow changes, so no scheduler dance. */
    private fun TestScope.engineOver(overGame: FakeEngine, inFront: MutableStateFlow<Boolean>) =
        AdaptiveVoiceEngine(overGame, IN_APP_LABEL, inFront, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

    @Test
    fun itStandsDownWhilePwdeIsInFrontAndTakesOverOverTheGame() = runTest {
        val overGame = FakeEngine("sherpa")
        val inFront = MutableStateFlow(true)
        val engine = engineOver(overGame, inFront)

        engine.start()
        // Nothing for the spotter to do inside the app: the app-wide recognizer has the mic.
        assertEquals(0, overGame.started)
        assertEquals(IN_APP_LABEL, engine.modelLabel)

        inFront.value = false // the user leaves for the game
        assertEquals(1, overGame.started)
        assertEquals("sherpa", engine.modelLabel)

        inFront.value = true // …and comes back to PWDe
        assertEquals(1, overGame.stopped)
        assertEquals(IN_APP_LABEL, engine.modelLabel)
    }

    /** Phrases loaded while the app is in front must still reach the spotter when the user leaves. */
    @Test
    fun commandsLoadedInTheAppReachTheSpotterOnTheWayOut() = runTest {
        val overGame = FakeEngine("sherpa")
        val inFront = MutableStateFlow(true)
        val engine = engineOver(overGame, inFront)

        engine.start()
        engine.loadCommands(listOf(VoiceCommandBinding("game_pause", listOf("pause"))))
        assertTrue("nothing is being listened for yet", overGame.commands.isEmpty())

        inFront.value = false
        assertEquals(listOf("pause"), overGame.commands.single().phrases)
    }

    /** A stopped session must not leave the spotter on the microphone. */
    @Test
    fun stoppingLeavesTheSpotterReleased() = runTest {
        val overGame = FakeEngine("sherpa")
        val inFront = MutableStateFlow(false)
        val engine = engineOver(overGame, inFront)

        engine.start()
        assertEquals(1, overGame.started)

        engine.stop()
        assertEquals(1, overGame.stopped)
    }

    /** Stopping while PWDe is in front must not start the spotter on the way out. */
    @Test
    fun stoppingWhileInTheAppNeverStartsTheSpotter() = runTest {
        val overGame = FakeEngine("sherpa")
        val inFront = MutableStateFlow(true)
        val engine = engineOver(overGame, inFront)

        engine.start()
        engine.stop()
        inFront.value = false
        assertEquals(0, overGame.started)
    }

    /** The caption follows whichever recognizer really has the microphone. */
    @Test
    fun theReportedLabelFollowsTheSwitch() = runTest {
        val overGame = FakeEngine("sherpa")
        val inFront = MutableStateFlow(false)
        val engine = engineOver(overGame, inFront)

        engine.start()
        assertEquals("sherpa", engine.state.value.modelLabel)

        inFront.value = true
        assertEquals(IN_APP_LABEL, engine.state.value.modelLabel)
        assertEquals(IN_APP_LABEL, engine.modelLabel)
    }

    private companion object {
        const val IN_APP_LABEL = "Android SpeechRecognizer"
    }
}
