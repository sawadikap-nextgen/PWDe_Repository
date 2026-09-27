package com.pwde.app.sensors.voice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * The shared Android SpeechRecognizer plumbing: keeps listening utterance after utterance,
 * restarting after results or errors with backoff, and reports what it hears. Used by both the
 * app-wide [VoiceCommandManager] and the gameplay [SpeechRecognizerInGameVoiceEngine]. Must be
 * driven from the main thread ([scope] must use the main dispatcher).
 */
class ContinuousSpeechRecognizer(
    context: Context,
    private val scope: CoroutineScope,
    private val listener: Listener,
) {
    interface Listener {
        fun onListening(listening: Boolean)
        fun onLevel(level: Float)

        /** A new speech utterance started after any prior result or silence. */
        fun onUtteranceStarted() = Unit

        /** The speech recognizer detected silence after the current utterance. */
        fun onUtteranceEnded() = Unit

        /** Recognition hypotheses, best first, with confidence scores when the recognizer reports them. */
        fun onHeard(hypotheses: List<String>, confidences: FloatArray?, isFinal: Boolean)

        /** The current utterance ended without a final result (silence, error). */
        fun onUtteranceAborted()

        /** Listening stopped for good until [start] is called again. */
        fun onUnavailable(reason: MicAvailability)
    }

    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var wanted = false
    private var restartJob: Job? = null
    private var consecutiveErrors = 0

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun availability(): MicAvailability = when {
        !hasMicPermission(appContext) -> MicAvailability.NO_PERMISSION
        !SpeechRecognizer.isRecognitionAvailable(appContext) -> MicAvailability.NO_RECOGNIZER
        consecutiveErrors >= MAX_CONSECUTIVE_ERRORS -> MicAvailability.SERVICE_ERROR
        else -> MicAvailability.AVAILABLE
    }

    /** Forget past failures, e.g. after the user re-grants permission or turns voice back on. */
    fun resetErrors() {
        consecutiveErrors = 0
    }

    fun start() {
        if (wanted) return
        wanted = true
        _running.value = true
        listenOnce()
    }

    fun stop() {
        wanted = false
        _running.value = false
        restartJob?.cancel()
        recognizer?.let {
            it.cancel()
            it.destroy()
        }
        recognizer = null
        listener.onListening(false)
        listener.onLevel(0f)
    }

    private fun listenOnce() {
        if (!wanted) return
        val r = recognizer ?: runCatching { SpeechRecognizer.createSpeechRecognizer(appContext) }.getOrNull()
            ?.also {
                it.setRecognitionListener(callbacks)
                recognizer = it
            }
        if (r == null) {
            giveUp(MicAvailability.NO_RECOGNIZER)
            return
        }
        runCatching { r.startListening(intent()) }.onFailure {
            Log.w(TAG, "startListening failed", it)
            onError(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    private fun giveUp(reason: MicAvailability) {
        stop()
        listener.onUnavailable(reason)
    }

    private fun restartAfter(delayMs: Long) {
        restartJob?.cancel()
        restartJob = scope.launch {
            delay(delayMs)
            listenOnce()
        }
    }

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
    }

    private fun heard(bundle: Bundle?, isFinal: Boolean) {
        val hypotheses = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.filter { it.isNotBlank() }.orEmpty()
        if (hypotheses.isEmpty()) {
            if (isFinal) listener.onUtteranceAborted()
            return
        }
        listener.onHeard(hypotheses, bundle?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES), isFinal)
    }

    private fun onError(error: Int) {
        listener.onListening(false)
        listener.onLevel(0f)
        listener.onUtteranceAborted()
        if (!wanted) return
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                consecutiveErrors = 0
                restartAfter(RESTART_DELAY_MS)
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> giveUp(MicAvailability.NO_PERMISSION)
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                recognizer?.destroy()
                recognizer = null
                countFailureAndRetry()
            }
            else -> countFailureAndRetry()
        }
    }

    private fun countFailureAndRetry() {
        consecutiveErrors++
        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) giveUp(MicAvailability.SERVICE_ERROR)
        else restartAfter(min(MAX_BACKOFF_MS, RESTART_DELAY_MS shl consecutiveErrors))
    }

    private val callbacks = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = listener.onListening(true)
        override fun onBeginningOfSpeech() = listener.onUtteranceStarted()

        // Typical rmsdB runs from about -2 (silence) to 10 (loud speech).
        override fun onRmsChanged(rmsdB: Float) = listener.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() {
            listener.onLevel(0f)
            listener.onUtteranceEnded()
        }
        override fun onError(error: Int) = this@ContinuousSpeechRecognizer.onError(error)

        override fun onResults(results: Bundle?) {
            consecutiveErrors = 0
            heard(results, isFinal = true)
            listener.onListening(false)
            listener.onLevel(0f)
            if (wanted) restartAfter(RESTART_DELAY_MS)
        }

        override fun onPartialResults(partialResults: Bundle?) = heard(partialResults, isFinal = false)
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    companion object {
        private const val TAG = "SpeechRecognizer"
        private const val RESTART_DELAY_MS = 250L
        private const val MAX_BACKOFF_MS = 4_000L
        private const val MAX_CONSECUTIVE_ERRORS = 6

        fun hasMicPermission(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        /**
         * What [SpeechRecognizer.createSpeechRecognizer] actually talks to, e.g. "Android SpeechRecognizer ·
         * Speech Recognition & Synthesis from Google": the phone's default recognition service if it can be
         * read, otherwise the first installed one.
         */
        fun modelLabel(context: Context): String {
            val services = runCatching {
                context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            }.getOrDefault(emptyList())
            // Not every device lets apps read this setting; fall back to the first service found.
            val default = runCatching {
                Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
                    ?.let(ComponentName::unflattenFromString)
            }.getOrNull()
            val service = services.firstOrNull { it.serviceInfo.packageName == default?.packageName } ?: services.firstOrNull()
                ?: return "Android SpeechRecognizer · no recognition service installed"
            val name = service.loadLabel(context.packageManager).toString().ifBlank { service.serviceInfo.packageName }
            return "Android SpeechRecognizer · $name"
        }
    }
}

/**
 * Guarantees only one recognizer listens at a time: while a game-side [InGameVoiceEngine] or the
 * Testing Station's wake-word engine holds the microphone, the app-wide [VoiceCommandManager] stands
 * down.
 *
 * The game slot is a **reference count of named holders**, not a flag. PWDe has more than one engine
 * that can use it — the app recognizer for PWDe's own screens and the sherpa spotter for a session
 * over the real game — and with a single boolean the first `release` cleared the slot while the other
 * engine was still recording. The app-wide recognizer then started on top of it, and two `AudioRecord`s
 * fight over the microphone: the loser hears **silence**, so the spotter goes deaf and in-game voice
 * commands quietly stop firing. Mutated with `update {}` so a read-modify-write cannot drop a holder.
 */
class MicArbiter {
    private val gameHolders = MutableStateFlow<Set<String>>(emptySet())

    /** True while any game-side engine is recording. */
    val gameHasMic: Flow<Boolean> = gameHolders.map { it.isNotEmpty() }

    private val _wakeWordHasMic = MutableStateFlow(false)
    val wakeWordHasMic: StateFlow<Boolean> = _wakeWordHasMic.asStateFlow()

    /** True while anybody other than the app-wide recognizer is recording. */
    val busy: Flow<Boolean> = combine(gameHasMic, _wakeWordHasMic) { game, wakeWord -> game || wakeWord }

    /** [holder] names the engine, so two in this slot cannot release each other's claim. */
    fun takeForGame(holder: String) {
        gameHolders.update { it + holder }
    }

    fun releaseFromGame(holder: String) {
        gameHolders.update { it - holder }
    }

    fun takeForWakeWord() {
        _wakeWordHasMic.value = true
    }

    fun releaseFromWakeWord() {
        _wakeWordHasMic.value = false
    }

    companion object {
        private val counter = AtomicInteger()

        /**
         * A holder name unique to **one engine instance**.
         *
         * Two engines must never share a name. The slot is a set, so a shared name means one engine's
         * `release` frees the other's claim — the app-wide recognizer then starts on top of a recording
         * one, two `AudioRecord`s fight for the microphone and the loser hears silence, i.e. the spotter
         * goes deaf. Deriving the name per instance means no call site can get that wrong.
         */
        fun newHolder(owner: String): String = "$owner#${counter.incrementAndGet()}"
    }
}
