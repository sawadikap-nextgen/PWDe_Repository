package com.pwde.app.sensors.voice

import android.content.Context
import com.pwde.app.data.local.ControlsRepository
import com.pwde.app.data.model.ControlConfig
import com.pwde.app.data.model.VoiceActivationMode
import com.pwde.app.data.model.VoiceMatchMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MicAvailability(val label: String) {
    AVAILABLE("Microphone ready"),
    NO_PERMISSION("Microphone permission is off"),
    NO_RECOGNIZER("No speech recognition service on this phone"),
    SERVICE_ERROR("Speech recognition keeps failing"),
}

enum class VoiceInputSource { MIC, TEXT }

/** One transcript (partial or final). [command] is set only when it fired. */
data class VoiceResult(
    val transcript: String,
    val isFinal: Boolean,
    val command: VoiceCommand?,
    val source: VoiceInputSource,
)

data class VoiceState(
    /** The user's on/off setting. */
    val enabled: Boolean = false,
    val availability: MicAvailability = MicAvailability.AVAILABLE,
    val listening: Boolean = false,
    /** Mic input level, 0–1, for the waveform. */
    val level: Float = 0f,
    val lastTranscript: String? = null,
    val lastCommand: VoiceCommand? = null,
    val matchMode: VoiceMatchMode = VoiceMatchMode.WORD_ANYWHERE,
    val activationMode: VoiceActivationMode = VoiceActivationMode.IMMEDIATE,
    /** Everything that can be said right now: screen commands first, then global ones. */
    val commands: List<VoiceCommand> = emptyList(),
    /** Another subsystem (gameplay, or the Testing Station's wake-word engine) has the microphone. */
    val pausedForOtherInput: Boolean = false,
) {
    /** The mic can't be used (no permission, or no recognizer); app screens are then touch-only. */
    val usesTextFallback: Boolean get() = availability != MicAvailability.AVAILABLE
}

/**
 * App-scoped voice commands (no system-wide listening). Listens only while voice is on and
 * someone observes [state] — i.e. while PWDe is on screen — and never while a game holds the mic.
 */
interface VoiceCommandManager {
    val state: StateFlow<VoiceState>
    val results: SharedFlow<VoiceResult>
    val hasMicPermission: Boolean

    /** The speech model behind app-wide voice, for display, e.g. "Android SpeechRecognizer · Google". */
    val modelLabel: String get() = "Android SpeechRecognizer"

    fun refreshPermissions()

    /**
     * Text in place of speech: same matching, same command catalog. Kept as an API (tests, future
     * input methods) but no app screen calls it: the typed-command box was removed with the voice bar.
     */
    fun submitText(text: String)

    /** Commands for whatever [owner] (a screen) is showing. Replaces that owner's previous list. */
    fun setScreenCommands(owner: Any, commands: List<VoiceCommand>)

    fun clearScreenCommands(owner: Any)

    /**
     * While any owner is dictating, utterances starting with "assign" or "use" ([Dictation]) never
     * fire commands; the owner reads them from [results] instead.
     */
    fun setDictating(owner: Any, dictating: Boolean)
}

class AndroidVoiceCommandManager(
    context: Context,
    private val controlsRepository: ControlsRepository,
    private val micArbiter: MicArbiter,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : VoiceCommandManager {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(VoiceState())
    private val _results = MutableSharedFlow<VoiceResult>(extraBufferCapacity = 16)
    private val permissionTick = MutableStateFlow(0)
    private val screenCommands = MutableStateFlow<Map<Any, List<VoiceCommand>>>(emptyMap())
    private val dictationOwners = MutableStateFlow<Set<Any>>(emptySet())
    private val gate = VoiceActivationGate()
    private var historyClearJob: Job? = null

    /**
     * Google keeps one recognition session open across short pauses, and every partial carries the
     * whole session's transcript. Words before this index were already acted on (a command fired)
     * or went stale (silence), so they are never shown or matched again.
     */
    private var consumedWords = 0
    private var latestWordCount = 0
    private var config = ControlConfig()

    private val recognizer = ContinuousSpeechRecognizer(appContext, scope, object : ContinuousSpeechRecognizer.Listener {
        override fun onListening(listening: Boolean) = _state.update { it.copy(listening = listening) }
        override fun onLevel(level: Float) = _state.update { it.copy(level = level) }
        override fun onUtteranceStarted() {
            consumedWords = 0
            latestWordCount = 0
            clearRecognitionHistory()
        }
        override fun onUtteranceEnded() = scheduleHistoryClear()
        override fun onUtteranceAborted() {
            gate.reset()
            consumedWords = 0
            latestWordCount = 0
            scheduleHistoryClear()
        }
        override fun onUnavailable(reason: MicAvailability) = _state.update { it.copy(availability = reason) }
        override fun onRecovered() = _state.update { it.copy(availability = MicAvailability.AVAILABLE) }

        override fun onHeard(hypotheses: List<String>, confidences: FloatArray?, isFinal: Boolean) {
            val words = hypotheses.map(::words)
            latestWordCount = words.first().size
            val fresh = words.map { it.drop(consumedWords) }
            if (fresh.first().isEmpty()) {
                if (isFinal) consumedWords = 0
                return
            }
            val segment = fresh.first().joinToString(" ")
            val dictating = isDictation(segment)
            // Outside dictation, only the tail can still become a command: leading words that matched
            // nothing are dropped so they don't pile up in the bubble.
            val window = if (dictating) fresh else fresh.map { it.takeLast(matchWindowWords()) }
            val shown = window.first().joinToString(" ")
            val match = if (dictating) null else CommandMatcher.match(window.map { it.joinToString(" ") }, allCommands(), config.voiceMatchMode)
            val fired = gate.offer(match, isFinal, config.voiceActivationMode)
            publish(if (dictating) segment else shown, isFinal, fired, VoiceInputSource.MIC)
            when {
                isFinal -> consumedWords = 0
                fired != null -> {
                    // The command's words are spent; what's said next starts a fresh segment.
                    consumedWords = latestWordCount
                    gate.reset()
                }
                else -> scheduleHistoryClear()
            }
        }
    })

    override val state: StateFlow<VoiceState> = _state.asStateFlow()
    override val results: SharedFlow<VoiceResult> = _results.asSharedFlow()

    override val hasMicPermission: Boolean get() = ContinuousSpeechRecognizer.hasMicPermission(appContext)

    override val modelLabel: String by lazy { ContinuousSpeechRecognizer.modelLabel(appContext) }

    init {
        scope.launch {
            _state.subscriptionCount.map { it > 0 }.distinctUntilChanged().collectLatest { observed ->
                if (!observed) {
                    stopListening()
                    return@collectLatest
                }
                try {
                    combine(
                        controlsRepository.config,
                        permissionTick,
                        screenCommands,
                        micArbiter.busy,
                    ) { config, _, screens, micBusy -> ListenInputs(config, screens, micBusy) }
                        .collect { (latest, screens, micBusy) ->
                            config = latest
                            val availability = recognizer.availability()
                            _state.update {
                                it.copy(
                                    enabled = latest.voiceEnabled,
                                    availability = availability,
                                    matchMode = latest.voiceMatchMode,
                                    activationMode = latest.voiceActivationMode,
                                    commands = screens.values.flatten() + globalCommands(latest),
                                    pausedForOtherInput = micBusy,
                                )
                            }
                            // In-app navigation stays available before the separate accessibility service is enabled.
                            val listen = latest.voiceEnabled && availability == MicAvailability.AVAILABLE && !micBusy
                            if (listen) recognizer.start() else stopListening()
                        }
                } finally {
                    stopListening()
                }
            }
        }
    }

    override fun refreshPermissions() {
        recognizer.resetErrors()
        permissionTick.value++
    }

    override fun submitText(text: String) {
        if (text.isBlank()) return
        scope.launch {
            clearRecognitionHistory()
            val command = if (isDictation(text)) null else CommandMatcher.match(text, allCommands(), config.voiceMatchMode)
            publish(text.trim(), isFinal = true, command = command, source = VoiceInputSource.TEXT)
        }
    }

    override fun setScreenCommands(owner: Any, commands: List<VoiceCommand>) {
        screenCommands.update { it + (owner to commands) }
    }

    override fun clearScreenCommands(owner: Any) {
        screenCommands.update { it - owner }
    }

    override fun setDictating(owner: Any, dictating: Boolean) {
        dictationOwners.update { if (dictating) it + owner else it - owner }
    }

    private fun isDictation(text: String) = dictationOwners.value.isNotEmpty() && Dictation.isAssignment(text)

    private fun globalCommands(config: ControlConfig) =
        StandardCommands.all + StandardCommands.playGames + StandardCommands.shortcuts(config.voiceShortcuts)

    private fun allCommands() = screenCommands.value.values.flatten() + globalCommands(config)

    private fun stopListening() {
        recognizer.stop()
        clearRecognitionHistory()
    }

    private fun publish(transcript: String, isFinal: Boolean, command: VoiceCommand?, source: VoiceInputSource) {
        _state.update { it.copy(lastTranscript = transcript, lastCommand = command ?: it.lastCommand) }
        _results.tryEmit(VoiceResult(transcript, isFinal, command, source))
        if (isFinal || source == VoiceInputSource.TEXT) scheduleHistoryClear()
    }

    private fun clearRecognitionHistory() {
        historyClearJob?.cancel()
        historyClearJob = null
        gate.reset()
        _state.update { it.copy(lastTranscript = null, lastCommand = null) }
    }

    private fun scheduleHistoryClear() {
        historyClearJob?.cancel()
        historyClearJob = scope.launch {
            delay(RECOGNITION_HISTORY_MS)
            // Silence: whatever was heard so far in this recognizer session is stale.
            consumedWords = latestWordCount
            gate.reset()
            _state.update { it.copy(lastTranscript = null, lastCommand = null) }
            historyClearJob = null
        }
    }

    private fun words(text: String) = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }

    /** Longest phrase in words, plus slack for filler like "please" or a misheard word. */
    private fun matchWindowWords() =
        (allCommands().flatMap { it.phrases }.maxOfOrNull { words(it).size } ?: 1) + MATCH_WINDOW_SLACK_WORDS

    private data class ListenInputs(
        val config: ControlConfig,
        val screens: Map<Any, List<VoiceCommand>>,
        val micBusy: Boolean,
    )

    private companion object {
        const val RECOGNITION_HISTORY_MS = 3_000L
        const val MATCH_WINDOW_SLACK_WORDS = 2
        val WHITESPACE = Regex("\\s+")
    }
}
