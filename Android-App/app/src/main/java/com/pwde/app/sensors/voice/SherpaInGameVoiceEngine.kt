package com.pwde.app.sensors.voice

import android.content.Context
import android.util.Log
import com.pwde.app.data.local.ControlsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The phrases a sherpa-onnx spotter listens for during gameplay, each mapped to the command it
 * fires. Phrases are normalized the way [CommandMatcher] normalizes speech, which is also a spelling
 * the tokenizer accepts. When two bindings share a phrase the first one wins, as the standard
 * commands come first in [com.pwde.app.play.GameInput.bindings].
 */
internal fun inGameKeywordMap(bindings: List<VoiceCommandBinding>): Map<String, String> {
    val map = LinkedHashMap<String, String>()
    for (binding in bindings) {
        for (phrase in binding.phrases) {
            val normalized = CommandMatcher.normalize(phrase)
            if (normalized.isNotEmpty() && normalized !in map) map[normalized] = binding.commandId
        }
    }
    return map
}

/**
 * In-game voice on sherpa-onnx keyword spotting: the same GigaSpeech model the Testing Station's
 * Wake word panel runs, listening for exactly the loaded commands (the profile's button triggers plus
 * the standard in-game ones). The app-wide [VoiceCommandManager] keeps using the platform recognizer.
 *
 * A keyword spotter fires on the phrase itself, so the user's match and activation modes don't
 * apply here: every hit is an immediate, exact command. Per-phrase and spotter-wide tuning come from
 * [tuningStore], which the Testing Station edits.
 */
class SherpaInGameVoiceEngine(
    context: Context,
    private val controlsRepository: ControlsRepository,
    private val micArbiter: MicArbiter,
    private val tuningStore: WakeWordTuningStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : BaseInGameVoiceEngine() {
    /** Unique to this instance: see [MicArbiter.newHolder]. */
    private val micHolder = MicArbiter.newHolder("sherpa")

    // The engine holds the game's mic claim for the whole session itself, so restarting the spotter
    // (a new profile, new tuning) never leaves a gap for the app-wide recognizer to grab the mic.
    private val spotter = SherpaWakeWordEngine(context, takeMic = {}, releaseMic = {}, keywordsFileName = KEYWORDS_FILE)

    /** Normalized phrase -> command id, for the commands loaded right now. */
    private val keywords = MutableStateFlow<Map<String, String>>(emptyMap())

    private var listenJob: Job? = null

    override val modelLabel: String = MODEL_LABEL

    /** Phrases already reported as unspottable, so each is logged once per load. */
    private val reportedUnsupported = mutableSetOf<String>()

    init {
        scope.launch {
            spotter.detections.collect { detection ->
                val commandId = keywords.value[detection.phrase]
                if (commandId == null) {
                    Log.w(TAG, "\"${detection.phrase}\" isn't a loaded command (loaded: ${keywords.value.keys})")
                    return@collect
                }
                Log.i(TAG, "\"${detection.phrase}\" -> $commandId")
                emit(InGameVoiceResult(commandId, InGameVoiceResult.CONFIDENCE_UNKNOWN, detection.phrase))
            }
        }
        scope.launch {
            spotter.state.collect { wake ->
                updateState {
                    it.copy(listening = wake.running, level = wake.level, availability = wake.micAvailability())
                }
                val fresh = wake.unsupported.filter { reportedUnsupported.add(it) }
                if (fresh.isNotEmpty()) {
                    Log.w(TAG, "The keyword model can't spell: ${fresh.joinToString()}")
                    updateState { it.copy(lastText = "Can't spot " + fresh.joinToString { p -> "\"$p\"" }) }
                }
                wake.error?.let { Log.w(TAG, "Spotter error: $it") }
            }
        }
    }

    override fun loadCommands(commands: List<VoiceCommandBinding>) {
        super.loadCommands(commands)
        scope.launch {
            reportedUnsupported.clear()
            // A running session picks this up and restarts the spotter on the new phrase set.
            keywords.value = inGameKeywordMap(commands)
        }
    }

    override fun start() {
        scope.launch {
            micArbiter.takeForGame(micHolder)
            listenJob?.cancel()
            listenJob = launch {
                updateState { it.copy(running = true) }
                // Let the app-wide recognizer release the mic first, so two never overlap.
                delay(HANDOVER_MS)
                combine(
                    controlsRepository.config.map { it.voiceEnabled }.distinctUntilChanged(),
                    keywords,
                    tuningStore.phraseTuning,
                    tuningStore.spotter,
                ) { enabled, phrases, _, spotterTuning -> Triple(enabled, phrases.keys.toList(), spotterTuning) }
                    .distinctUntilChanged()
                    .collect { (enabled, phrases, spotterTuning) ->
                        // Voice switched off by the user, or nothing to listen for: stay silent; typed commands still work.
                        if (enabled && phrases.isNotEmpty()) {
                            spotter.start(phrases, tuningStore.tuningFor(phrases), spotterTuning)
                        } else {
                            spotter.stop()
                        }
                    }
            }
        }
    }

    override fun stop() {
        scope.launch {
            listenJob?.cancel()
            listenJob = null
            spotter.stop()
            onUtteranceAborted()
            updateState { it.copy(running = false, listening = false, level = 0f) }
            micArbiter.releaseFromGame(micHolder)
        }
    }

    private fun WakeWordState.micAvailability(): MicAvailability = when (availability) {
        WakeWordAvailability.READY -> MicAvailability.AVAILABLE
        WakeWordAvailability.NO_PERMISSION -> MicAvailability.NO_PERMISSION
        // The spotter failed to load or open the mic: the overlay offers typed commands instead.
        WakeWordAvailability.FAILED -> MicAvailability.SERVICE_ERROR
        WakeWordAvailability.UNAVAILABLE -> MicAvailability.NO_RECOGNIZER
    }

    companion object {
        /** The on-device keyword spotter and model every sherpa spotter in PWDe loads. */
        const val MODEL_LABEL = "sherpa-onnx keyword spotter · GigaSpeech 3.3M (English, on-device)"

        private const val TAG = "SherpaInGameVoice"
        private const val KEYWORDS_FILE = "keywords-game.txt"
        private const val HANDOVER_MS = 300L
    }
}
