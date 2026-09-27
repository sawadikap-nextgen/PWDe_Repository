package com.pwde.app.di

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.datastore.preferences.preferencesDataStore
import com.pwde.app.BuildConfig
import com.pwde.app.data.gabai.CloudHudDetector
import com.pwde.app.data.gabai.GabAiRepository
import com.pwde.app.data.gabai.HudDetector
import com.pwde.app.data.local.ControlsRepository
import com.pwde.app.data.local.ProfileRepository
import com.pwde.app.data.local.PwdeDatabase
import com.pwde.app.data.media.TutorialPlayer
import com.pwde.app.data.prefs.DataStoreSettingsRepository
import com.pwde.app.data.prefs.SettingsRepository
import com.pwde.app.data.remote.AuthRepository
import com.pwde.app.data.remote.NoOpSyncRepository
import com.pwde.app.data.remote.SyncRepository
import com.pwde.app.data.speech.SpeechOutput
import com.pwde.app.data.prefs.ButtonOverlayPrefs
import com.pwde.app.data.prefs.CalibrationOverlayState
import com.pwde.app.play.LivePlay
import com.pwde.app.sensors.face.FaceTrackingManager
import com.pwde.app.sensors.face.MediaPipeFaceTrackingManager
import com.pwde.app.sensors.voice.AdaptiveVoiceEngine
import com.pwde.app.sensors.voice.AndroidVoiceCommandManager
import com.pwde.app.sensors.voice.InGameVoiceEngine
import com.pwde.app.sensors.voice.MicArbiter
import com.pwde.app.sensors.voice.SherpaInGameVoiceEngine
import com.pwde.app.sensors.voice.SherpaSupport
import com.pwde.app.sensors.voice.SherpaWakeWordEngine
import com.pwde.app.sensors.voice.SpeechRecognizerInGameVoiceEngine
import com.pwde.app.sensors.voice.VoiceCommandManager
import com.pwde.app.sensors.voice.WakeWordEngine
import com.pwde.app.sensors.voice.WakeWordTuningStore

private val Context.settingsDataStore by preferencesDataStore(name = "user_settings")

/** Manual DI: app-wide singletons, created lazily. Lives on [com.pwde.app.PwdeApplication]. */
class AppContainer(private val context: Context) {
    private val database by lazy { PwdeDatabase.create(context) }

    val settingsRepository: SettingsRepository by lazy { DataStoreSettingsRepository(context.settingsDataStore) }
    val profileRepository by lazy {
        ProfileRepository(database.calibrationProfileDao(), database.gameProfileDao())
    }
    val controlsRepository by lazy { ControlsRepository(database.controlSettingsDao(), profileRepository = profileRepository) }
    val authRepository: AuthRepository by lazy { AuthRepository.create(context) }
    val syncRepository: SyncRepository by lazy { NoOpSyncRepository(authRepository) }
    val speechOutput by lazy { SpeechOutput(context) }

    /** Camera + MediaPipe head/face tracking (motion-sensor demo mode when the camera can't be used). */
    val faceTrackingManager: FaceTrackingManager by lazy {
        MediaPipeFaceTrackingManager(context, controlsRepository, settingsRepository)
    }

    /** Whether the user is in the PWDe app right now: the voice engine split and the overlay read it. */
    val pwdeVisibility by lazy { PwdeVisibility() }

    /** Makes sure gameplay's voice engine and the app-wide one never listen at the same time. */
    private val micArbiter by lazy { MicArbiter() }

    /** App-scoped voice commands (Android SpeechRecognizer, typed fallback). Navigation everywhere except gameplay. */
    val voiceCommandManager: VoiceCommandManager by lazy {
        AndroidVoiceCommandManager(context, controlsRepository, micArbiter)
    }

    /** sherpa-onnx keyword spotter tuning, edited in the Testing Station and used by gameplay. */
    val wakeWordTuningStore by lazy { WakeWordTuningStore() }

    /** The Testing Station's own sherpa-onnx spotter, for trying phrases and tuning. */
    val wakeWordEngine: WakeWordEngine by lazy {
        SherpaWakeWordEngine(
            context,
            takeMic = micArbiter::takeForWakeWord,
            releaseMic = micArbiter::releaseFromWakeWord,
            keywordsFileName = "keywords-testing.txt",
        )
    }

    /**
     * Gameplay-time voice recognition (mapped-button triggers + standard in-game commands), scoped
     * to the active game profile's commands. sherpa-onnx keyword spotting where it can run; the
     * platform recognizer on devices it can't (non-arm64, or a checkout without the model).
     * GameplayViewModel and everything above it depend only on the interface.
     */
    /**
     * What a live session listens with. It is [AdaptiveVoiceEngine], which is sherpa-onnx over the real
     * game and stands down while PWDe is in front so the app-wide recognizer (Google) is the one
     * listening. The engine is chosen by **where the user is**, live, not once when the session starts.
     */
    val inGameVoiceEngine: InGameVoiceEngine by lazy {
        AdaptiveVoiceEngine(
            overGame =
                if (SherpaSupport.isSupported(context)) {
                    SherpaInGameVoiceEngine(context, controlsRepository, micArbiter, wakeWordTuningStore)
                } else {
                    SpeechRecognizerInGameVoiceEngine(context, controlsRepository, micArbiter)
                },
            inAppLabel = voiceCommandManager.modelLabel.substringBefore(" ·"),
            pwdeInForeground = pwdeVisibility.inForeground,
        )
    }

    /**
     * Voice for the flows that press a game's buttons **on PWDe's own screens** — the in-app Play
     * preview and GabAI's controls test. These run with PWDe in front, where the app-wide navigation
     * voice (also Android `SpeechRecognizer`, i.e. Google's service) is listening anyway, so they use
     * the same recognizer and the user's match/activation modes.
     *
     * Only a live session over the real game gets [inGameVoiceEngine]'s sherpa-onnx spotter, which is
     * built for a phrase list heard over the game's own audio. Both take the mic through [MicArbiter],
     * so they still never listen at the same time.
     */
    val inAppVoiceEngine: InGameVoiceEngine by lazy {
        SpeechRecognizerInGameVoiceEngine(context, controlsRepository, micArbiter)
    }

    /** Resumable GabAI sessions and game screenshots. */
    val gabAiRepository by lazy { GabAiRepository(context, database.gabAiSessionDao()) }

    /** Auto-detects HUD buttons on GabAI screenshots; off unless pwde.detection.url is set. */
    val hudDetector: HudDetector by lazy {
        BuildConfig.DETECTION_URL.takeIf { it.isNotBlank() }?.let(::CloudHudDetector) ?: HudDetector.None
    }

    /** The live session over the real game, shared by PlayService, the accessibility service and the UI. */
    val livePlay by lazy { LivePlay() }

    /** Whether the live session draws the mapped buttons over the game (a debugging aid), and how strongly. */
    val buttonOverlayPrefs by lazy { ButtonOverlayPrefs(context) }

    /**
     * What the open calibration screen wants of the live pointer overlay, if anything: confined to
     * its box, or hidden entirely. Read synchronously by [com.pwde.app.accessibility.PwdeAccessibilityService].
     */
    val calibrationOverlay by lazy { CalibrationOverlayState() }

    fun newTutorialPlayer() = TutorialPlayer(context)

    /** True when TalkBack (or another touch-exploration screen reader) is running. */
    fun isSystemScreenReaderOn(): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        return manager?.isTouchExplorationEnabled == true
    }
}
