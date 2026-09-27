package com.pwde.app.sensors.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The engine a live session listens with, which **follows the user**:
 *
 * - over the real game it is the in-game engine (sherpa-onnx);
 * - the moment PWDe comes back to the front it **stands down**, because in the app the app-wide
 *   `AndroidVoiceCommandManager` (Android `SpeechRecognizer`, i.e. Google's service) is already the
 *   recognizer listening on every screen.
 *
 * This has to be a live switch, not a decision taken once when the session starts — which is what it
 * was, and why it did not work. A session over the real game outlives the user's visit to PWDe, so
 * coming back to the app left the spotter holding the microphone: the app-wide recognizer stayed
 * muted and PWDe kept listening with the *game's* engine. Verified on device —
 * `SherpaWakeWord: Spotted "pause"` fired while `com.pwde.app.MainActivity` had focus, with
 * `PlayService` still `isForeground=true`.
 *
 * [pwdeInForeground] is a plain flow rather than the Android tracker itself
 * (`AppContainer.pwdeVisibility`), so this class has no Android dependency and the switching is
 * unit-tested with fake engines.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdaptiveVoiceEngine(
    private val overGame: InGameVoiceEngine,
    /** What PWDe listens with while it is in front: the app-wide recognizer's label, for the caption. */
    private val inAppLabel: String,
    private val pwdeInForeground: Flow<Boolean>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : InGameVoiceEngine {
    private val commands = MutableStateFlow<List<VoiceCommandBinding>>(emptyList())

    /** True while the in-game engine is the one that should be recording. */
    private val usingGameEngine = MutableStateFlow(false)

    private var watching: Job? = null

    /** Nothing is recognised by this engine while PWDe is in front; the app-wide one has the mic. */
    override val results: Flow<InGameVoiceResult> =
        usingGameEngine.flatMapLatest { usingGame -> if (usingGame) overGame.results else emptyFlow() }

    override val state: StateFlow<InGameVoiceState> =
        combine(overGame.state, usingGameEngine) { state, usingGame ->
            state.copy(
                modelLabel = if (usingGame) overGame.modelLabel else inAppLabel,
                // Stood down: report silence rather than the previous engine's activity.
                running = state.running && usingGame,
                listening = state.listening && usingGame,
                level = if (usingGame) state.level else 0f,
            )
        }.stateIn(scope, SharingStarted.Eagerly, InGameVoiceState())

    override val modelLabel: String get() = if (usingGameEngine.value) overGame.modelLabel else inAppLabel

    override fun loadCommands(commands: List<VoiceCommandBinding>) {
        this.commands.value = commands
        // Remembered even while stood down, so the switch back to the game has the right phrases.
        if (usingGameEngine.value) overGame.loadCommands(commands)
    }

    override fun submitText(text: String) {
        if (usingGameEngine.value) overGame.submitText(text)
    }

    override fun start() {
        watching?.cancel()
        // collect (not first): the switch has to keep happening for the life of the session.
        watching = scope.launch { pwdeInForeground.collect { inFront -> setUsingGameEngine(!inFront) } }
    }

    override fun stop() {
        watching?.cancel()
        watching = null
        setUsingGameEngine(false)
    }

    private fun setUsingGameEngine(usingGame: Boolean) {
        if (usingGameEngine.value == usingGame) return
        usingGameEngine.value = usingGame
        if (usingGame) {
            overGame.loadCommands(commands.value)
            overGame.start()
        } else {
            overGame.stop()
        }
    }
}
