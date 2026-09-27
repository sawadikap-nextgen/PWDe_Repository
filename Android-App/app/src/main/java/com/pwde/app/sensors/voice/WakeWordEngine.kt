package com.pwde.app.sensors.voice

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/**
 * Always-listening wake word: the user says a phrase, the engine hears it. Deliberately knows
 * nothing about the engine behind it; [SherpaWakeWordEngine] implements it with sherpa-onnx's
 * keyword spotter. The Testing Station drives one directly, and [SherpaInGameVoiceEngine] wraps
 * another to press mapped buttons during gameplay.
 *
 * Contract every implementation keeps:
 * - A spotter records on its own behalf, so [start] must take the microphone through [MicArbiter]
 *   and [stop] must give it back — the app-wide recognizer stands down rather than fighting for
 *   the mic.
 * - A missing microphone permission, a native library that won't load, or a phrase the model can't
 *   represent is reported through [WakeWordState], never thrown: a wake word that can't run must
 *   not break the screen it's on.
 */
interface WakeWordEngine {
    val state: StateFlow<WakeWordState>

    /** Wake word hits. Each one already has its own counter in [WakeWordState.detections]. */
    val detections: SharedFlow<WakeWordDetection>

    /**
     * Takes the microphone and listens for [phrases] — plain text, e.g. `"hey pwde"`. An engine
     * that can't represent a phrase reports it in [WakeWordState] rather than failing the start.
     * An empty list listens for nothing.
     *
     * [tuning] is the per-phrase half of the spotter's settings — one boost/threshold pair per
     * phrase. A phrase missing from it inherits [spotter]. [spotter] is the spotter-wide half, i.e.
     * what those phrases inherit.
     *
     * Both are values the user can edit by hand, so an engine must accept anything and clamp what
     * the native spotter can't take rather than throwing.
     *
     * Safe to call when it can't listen; it says so in [state].
     */
    fun start(
        phrases: List<String>,
        tuning: Map<String, WakeWordTuning> = emptyMap(),
        spotter: WakeWordSpotterTuning = WakeWordSpotterTuning(),
    )

    /** Stops listening and frees the engine's native resources. */
    fun stop()
}

/**
 * The spotter-wide numbers, i.e. what a keyword line uses when it carries no `:boost #threshold`.
 * [WakeWordTuning] tunes one phrase; this tunes all of them.
 *
 * The defaults are what PWDe ships with — deliberately more sensitive than sherpa-onnx's own
 * 1.5 / 0.25 / 2 / 4 — and every field is editable by hand, so [clamped] is what keeps a typed
 * value inside the range the native spotter accepts.
 *
 * @param score keyword boost. Higher keeps a keyword alive through beam search.
 * @param threshold trigger threshold, 0-1. Lower fires on weaker acoustic evidence; 0 fires on
 *   noise alone.
 * @param trailingBlanks non-keyword frames tolerated after a partial match before giving up.
 * @param activePaths keyword hypotheses pursued at once. Raising it costs CPU.
 * @param noiseCancellation record through the phone's own voice-call processing (echo cancellation,
 *   noise suppression, gain control) instead of the raw mic. Echo cancellation is what strips the
 *   game's own sound out of the mic; how well it does that varies by phone.
 */
data class WakeWordSpotterTuning(
    val score: Float = 6.0f,
    // 0 accepts the weakest acoustic evidence: as eager as the spotter goes, at the cost of false alarms.
    val threshold: Float = 0.0f,
    val trailingBlanks: Int = 3,
    val activePaths: Int = 8,
    val noiseCancellation: Boolean = true,
) {
    /** Every field pulled back into the range the native spotter accepts. */
    fun clamped(): WakeWordSpotterTuning =
        copy(
            score = score.coerceIn(SCORE_RANGE),
            threshold = threshold.coerceIn(THRESHOLD_RANGE),
            trailingBlanks = trailingBlanks.coerceIn(TRAILING_BLANKS_RANGE),
            activePaths = activePaths.coerceIn(ACTIVE_PATHS_RANGE),
        )

    companion object {
        /** Generous upper bound: a huge boost is merely wasteful, not invalid. */
        val SCORE_RANGE = 0f..10f

        /** The native parser expects 0-1; above 1 nothing would ever fire. */
        val THRESHOLD_RANGE = 0f..1f

        val TRAILING_BLANKS_RANGE = 0..10

        /** At least one path is needed to match anything; more paths cost CPU. */
        val ACTIVE_PATHS_RANGE = 1..32
    }
}

/**
 * Moves [value] one [step] in [direction] and snaps the result back onto the step grid, so repeated
 * decimal steps can't drift (0.05 + 0.05 + 0.05 = 0.15000001) and stepping back lands exactly where
 * it started — which is what lets the preset toggle still recognise the pair afterwards.
 */
internal fun stepTuningValue(
    value: Float,
    step: Float,
    direction: Int,
    range: ClosedFloatingPointRange<Float>,
): Float = (((value / step).roundToInt() + direction) * step).coerceIn(range)

/** Why the wake word engine can or can't listen. */
enum class WakeWordAvailability(val label: String) {
    /** Engine and microphone are both in place. */
    READY("Ready"),
    NO_PERMISSION("Microphone permission is off"),
    /** The engine started but then failed — usually its native library won't load on this device. */
    FAILED("The engine failed to start"),
    /** No engine in this build, or this device can't run one. */
    UNAVAILABLE("Not available in this build"),
}

/** One wake word hit: the phrase that fired, and when. */
data class WakeWordDetection(val phrase: String, val atMs: Long)

data class WakeWordState(
    val availability: WakeWordAvailability = WakeWordAvailability.UNAVAILABLE,
    val running: Boolean = false,
    /** Hits since the last start. */
    val detections: Int = 0,
    /** RMS of the captured audio, 0-1. Speech is roughly 0.02-0.15; below ~0.01 the mic is too far away. */
    val level: Float = 0f,
    /**
     * How long decoding takes relative to the audio it consumes. At or above 1.0 the spotter cannot
     * keep up with real time, the input buffer overruns, and audio is silently dropped.
     */
    val realTimeFactor: Float = 0f,
    /** What to actually say, e.g. ["hey pwde", "play my game"]. Empty when there's no engine. */
    val phrases: List<String> = emptyList(),
    /** Phrases the loaded model can't represent, so the user isn't left guessing. */
    val unsupported: List<String> = emptyList(),
    val lastPhrase: String? = null,
    /** The engine's own error text, so a failed start is never silent. */
    val error: String? = null,
) {
    val canListen: Boolean get() = availability == WakeWordAvailability.READY
}
