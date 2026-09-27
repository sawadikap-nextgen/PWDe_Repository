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

        /**
         * Listening is not working: [MicAvailability.NO_PERMISSION] and [MicAvailability.NO_RECOGNIZER]
         * stop it until [start] is called again; [MicAvailability.SERVICE_ERROR] keeps retrying slowly
         * and ends with [onRecovered].
         */
        fun onUnavailable(reason: MicAvailability)

        /** A session started again after [onUnavailable] reported [MicAvailability.SERVICE_ERROR]. */
        fun onRecovered() = Unit
    }

    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var wanted = false
    private var restartJob: Job? = null
    private var watchdogJob: Job? = null
    private var consecutiveErrors = 0
    private var failing = false

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /**
     * Only what retrying cannot fix. A recognition service that keeps failing is not in here: the
     * callers stop listening on anything but AVAILABLE, and a stopped recognizer never finds out the
     * service came back — voice stayed dead until the user turned it off and on again.
     */
    fun availability(): MicAvailability = when {
        !hasMicPermission(appContext) -> MicAvailability.NO_PERMISSION
        !SpeechRecognizer.isRecognitionAvailable(appContext) -> MicAvailability.NO_RECOGNIZER
        else -> MicAvailability.AVAILABLE
    }

    /** Forget past failures, e.g. after the user re-grants permission or turns voice back on. */
    fun resetErrors() {
        consecutiveErrors = 0
        failing = false
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
        watchdogJob?.cancel()
        discardRecognizer()
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
        armWatchdog()
        runCatching { r.startListening(intent()) }.onFailure {
            Log.w(TAG, "startListening failed", it)
            onError(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    /**
     * The recognition service sometimes simply goes quiet: no result, no error, nothing — after it
     * was updated or killed in the background, or after the mic was taken from it. Nothing then ever
     * restarts the session, so voice is dead until the user toggles it. Any callback re-arms this;
     * [SESSION_STALL_MS] without one throws the recognizer away and starts a fresh one.
     */
    private fun armWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(SESSION_STALL_MS)
            if (!wanted) return@launch
            Log.w(TAG, "No word from the recognition service in ${SESSION_STALL_MS}ms — starting a new recognizer")
            discardRecognizer()
            listener.onListening(false)
            listener.onLevel(0f)
            listener.onUtteranceAborted()
            restartAfter(RESTART_DELAY_MS)
        }
    }

    private fun discardRecognizer() {
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    /** The service answered, so it is alive: forget past failures and say so if they were reported. */
    private fun onAlive() {
        consecutiveErrors = 0
        if (failing) {
            failing = false
            Log.i(TAG, "The recognition service is working again")
            listener.onRecovered()
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
        watchdogJob?.cancel()
        listener.onListening(false)
        listener.onLevel(0f)
        listener.onUtteranceAborted()
        if (!wanted) return
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                onAlive()
                restartAfter(RESTART_DELAY_MS)
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> giveUp(MicAvailability.NO_PERMISSION)
            else -> {
                Log.w(TAG, "Recognition error $error — retrying with a new recognizer")
                // Server disconnected, busy, client, audio, network…: the instance is often dead
                // after these (its binding to the service is gone), so reusing it just fails again.
                discardRecognizer()
                countFailureAndRetry()
            }
        }
    }

    /**
     * Back off, then keep trying. Past [MAX_CONSECUTIVE_ERRORS] the failure is reported, but listening
     * does not stop: Google's service comes back on its own (an update, a network drop, the mic freed
     * by another app) and the next working session clears it via [onAlive].
     */
    private fun countFailureAndRetry() {
        consecutiveErrors++
        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS && !failing) {
            failing = true
            listener.onUnavailable(MicAvailability.SERVICE_ERROR)
        }
        restartAfter(if (failing) FAILING_RETRY_MS else min(MAX_BACKOFF_MS, RESTART_DELAY_MS shl consecutiveErrors))
    }

    private val callbacks = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            // Not onAlive(): a service that gets ready and then fails every session is still failing.
            armWatchdog()
            listener.onListening(true)
        }

        override fun onBeginningOfSpeech() {
            armWatchdog()
            listener.onUtteranceStarted()
        }

        // Typical rmsdB runs from about -2 (silence) to 10 (loud speech).
        override fun onRmsChanged(rmsdB: Float) = listener.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() {
            armWatchdog()
            listener.onLevel(0f)
            listener.onUtteranceEnded()
        }
        override fun onError(error: Int) = this@ContinuousSpeechRecognizer.onError(error)

        override fun onResults(results: Bundle?) {
            watchdogJob?.cancel()
            onAlive()
            heard(results, isFinal = true)
            listener.onListening(false)
            listener.onLevel(0f)
            if (wanted) restartAfter(RESTART_DELAY_MS)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            armWatchdog()
            heard(partialResults, isFinal = false)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    companion object {
        private const val TAG = "SpeechRecognizer"
        private const val RESTART_DELAY_MS = 250L
        private const val MAX_BACKOFF_MS = 4_000L
        private const val MAX_CONSECUTIVE_ERRORS = 6

        /** Retry spacing once the service has been reported as failing. */
        private const val FAILING_RETRY_MS = 10_000L

        /**
         * Longest a session may go without any callback but the level meter. Google ends a silent
         * session itself with ERROR_SPEECH_TIMEOUT well before this, and every partial re-arms it.
         */
        private const val SESSION_STALL_MS = 15_000L

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
