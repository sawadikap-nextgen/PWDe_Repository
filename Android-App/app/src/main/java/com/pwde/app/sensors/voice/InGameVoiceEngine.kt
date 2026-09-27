package com.pwde.app.sensors.voice

import android.content.Context
import com.pwde.app.data.local.ControlsRepository
import com.pwde.app.data.model.VoiceActivationMode
import com.pwde.app.data.model.VoiceMatchMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Gameplay-time voice recognition. Swappable: the team is still choosing a dedicated low-latency
 * engine, so gameplay depends only on this interface and one binding in AppContainer picks the
 * implementation.
 *
 * Contract every implementation must keep (most of it comes free with [BaseInGameVoiceEngine]):
 * - Only the commands passed to [loadCommands] can be recognized — nothing app-wide.
 * - [start] takes the microphone for the game: the app-wide voice system stands down (via
 *   [MicArbiter]) so there is never more than one listener; [stop] gives it back.
 * - If the microphone is unavailable, the engine produces no speech results (a no-op, never an
 *   error) and reports it through [state]; [submitText] is then the input, matched exactly the
 *   way speech would be, so the overlay can offer the same typed command entry as the rest of PWDe.
 */
interface InGameVoiceEngine {
    /** (Re)builds the recognizable command set for the active GameProfile, e.g. from its button→voice-command mappings plus standard back/pause/menu commands. Call whenever gameplay starts or the active profile changes. */
    fun loadCommands(commands: List<VoiceCommandBinding>)

    fun start()
    fun stop()

    /** Recognized command id + confidence, or an "unrecognized" result. */
    val results: Flow<InGameVoiceResult>

    /** Listening state and microphone availability, for the overlay. */
    val state: StateFlow<InGameVoiceState>

    /** Typed fallback: matched against the loaded commands exactly like speech. */
    fun submitText(text: String)

    /** The speech model this engine presses buttons with, for display. */
    val modelLabel: String get() = "Unknown"
}

data class VoiceCommandBinding(val commandId: String, val phrases: List<String>)

/** [commandId] is null for "heard something, but it isn't one of the loaded commands". */
data class InGameVoiceResult(val commandId: String?, val confidence: Float, val rawText: String?) {
    companion object {
        /** The engine didn't report a confidence score for this result. */
        const val CONFIDENCE_UNKNOWN = -1f

        /** Typed commands are exact by definition. */
        const val CONFIDENCE_TYPED = 1f
    }
}

data class InGameVoiceState(
    val running: Boolean = false,
    val listening: Boolean = false,
    val availability: MicAvailability = MicAvailability.AVAILABLE,
    val level: Float = 0f,
    val lastText: String? = null,
    val lastCommandId: String? = null,
    /**
     * The engine that really has the microphone **right now**, short form, for the overlay caption.
     *
     * Only an engine that changes which recognizer is listening mid-session sets this (see
     * [AdaptiveVoiceEngine]); every other engine leaves it null and its own [InGameVoiceEngine.modelLabel]
     * is the truth. Without it the caption would keep naming the engine chosen when the session started,
     * which is exactly how "it doesn't switch" looks from the outside.
     */
    val modelLabel: String? = null,
) {
    val usesTextFallback: Boolean get() = availability != MicAvailability.AVAILABLE
}

/**
 * Shared behaviour for any engine: command loading, matching with the user's match/activation
 * modes, the typed fallback, and results. An implementation only has to feed it transcripts via
 * [onTranscript] (or ready-made intents via [emit]) and report availability via [updateState].
 */
abstract class BaseInGameVoiceEngine : InGameVoiceEngine {
    private val _results = MutableSharedFlow<InGameVoiceResult>(extraBufferCapacity = 16)
    override val results: Flow<InGameVoiceResult> = _results.asSharedFlow()

    private val _state = MutableStateFlow(InGameVoiceState())
    override val state: StateFlow<InGameVoiceState> = _state.asStateFlow()

    @Volatile
    private var commands: List<VoiceCommand> = emptyList()
    private val gate = VoiceActivationGate()

    /** The user's matching preferences; implementations keep these up to date. */
    @Volatile
    protected var matchMode: VoiceMatchMode = VoiceMatchMode.WORD_ANYWHERE

    @Volatile
    protected var activationMode: VoiceActivationMode = VoiceActivationMode.IMMEDIATE

    override fun loadCommands(commands: List<VoiceCommandBinding>) {
        this.commands = commands.map { VoiceCommand(it.commandId, it.phrases, CommandScope.SCREEN) }
        gate.reset()
    }

    override fun submitText(text: String) {
        if (text.isBlank()) return
        val command = CommandMatcher.match(text, commands, matchMode)
        emit(InGameVoiceResult(command?.id, if (command != null) InGameVoiceResult.CONFIDENCE_TYPED else 0f, text.trim()))
    }

    /** Feed recognizer output. Partial non-matches are ignored; a final non-match is "unrecognized". */
    protected fun onTranscript(hypotheses: List<String>, confidences: FloatArray?, isFinal: Boolean) {
        if (hypotheses.isEmpty()) return
        val matchIndex = hypotheses.indexOfFirst { CommandMatcher.match(it, commands, matchMode) != null }
        val match = if (matchIndex >= 0) CommandMatcher.match(hypotheses[matchIndex], commands, matchMode) else null
        val fired = gate.offer(match, isFinal, activationMode)
        val confidence = confidences?.getOrNull(maxOf(matchIndex, 0))?.takeIf { it >= 0f } ?: InGameVoiceResult.CONFIDENCE_UNKNOWN
        when {
            fired != null -> emit(InGameVoiceResult(fired.id, confidence, hypotheses[maxOf(matchIndex, 0)]))
            isFinal && match == null -> emit(InGameVoiceResult(null, confidence, hypotheses.first()))
        }
        if (!isFinal) _state.update { it.copy(lastText = hypotheses.first()) }
    }

    protected fun onUtteranceAborted() = gate.reset()

    protected fun emit(result: InGameVoiceResult) {
        _state.update { it.copy(lastText = result.rawText ?: it.lastText, lastCommandId = result.commandId ?: it.lastCommandId) }
        _results.tryEmit(result)
    }

    protected fun updateState(transform: (InGameVoiceState) -> InGameVoiceState) = _state.update(transform)
}

/**
 * Placeholder in-game engine: the same Android SpeechRecognizer plumbing as the app-wide
 * [VoiceCommandManager], scoped to only the commands loaded for the current game. Genuinely
 * functional — just the baseline recognizer rather than a dedicated low-latency one.
 */
class SpeechRecognizerInGameVoiceEngine(
    context: Context,
    private val controlsRepository: ControlsRepository,
    private val micArbiter: MicArbiter,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : BaseInGameVoiceEngine() {
    /** Unique to this instance: see [MicArbiter.newHolder]. */
    private val micHolder = MicArbiter.newHolder("platform")
    private val appContext = context.applicationContext
    private var settingsJob: Job? = null

    override val modelLabel: String by lazy { ContinuousSpeechRecognizer.modelLabel(appContext) }

    private val recognizer = ContinuousSpeechRecognizer(appContext, scope, object : ContinuousSpeechRecognizer.Listener {
        override fun onListening(listening: Boolean) = updateState { it.copy(listening = listening) }
        override fun onLevel(level: Float) = updateState { it.copy(level = level) }
        override fun onUtteranceAborted() = this@SpeechRecognizerInGameVoiceEngine.onUtteranceAborted()
        override fun onUnavailable(reason: MicAvailability) = updateState { it.copy(availability = reason, listening = false) }
        override fun onRecovered() = updateState { it.copy(availability = MicAvailability.AVAILABLE) }
        override fun onHeard(hypotheses: List<String>, confidences: FloatArray?, isFinal: Boolean) =
            onTranscript(hypotheses, confidences, isFinal)
    })

    override fun start() {
        scope.launch {
            micArbiter.takeForGame(micHolder)
            recognizer.resetErrors()
            settingsJob?.cancel()
            settingsJob = launch {
                // Let the app-wide recognizer release the mic first, so two never overlap.
                delay(HANDOVER_MS)
                controlsRepository.config.collect { config ->
                    matchMode = config.voiceMatchMode
                    activationMode = config.voiceActivationMode
                    val availability = recognizer.availability()
                    updateState { it.copy(running = true, availability = availability) }
                    // Voice switched off by the user, or no mic: stay silent; typed commands still work.
                    if (config.voiceEnabled && availability == MicAvailability.AVAILABLE) recognizer.start() else recognizer.stop()
                }
            }
        }
    }

    override fun stop() {
        scope.launch {
            settingsJob?.cancel()
            settingsJob = null
            recognizer.stop()
            onUtteranceAborted()
            updateState { it.copy(running = false, listening = false, level = 0f) }
            micArbiter.releaseFromGame(micHolder)
        }
    }

    private companion object {
        const val HANDOVER_MS = 300L
    }
}
