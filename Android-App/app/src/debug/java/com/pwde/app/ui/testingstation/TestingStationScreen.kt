package com.pwde.app.ui.testingstation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pwde.app.data.model.FacialGesture
import com.pwde.app.data.prefs.ButtonOverlay
import com.pwde.app.data.prefs.ButtonOverlayPrefs
import com.pwde.app.sensors.face.FaceState
import com.pwde.app.sensors.face.FaceTrackingManager
import com.pwde.app.sensors.face.TrackingStatus
import com.pwde.app.sensors.voice.DEFAULT_WAKE_WORDS
import com.pwde.app.sensors.voice.SherpaInGameVoiceEngine
import com.pwde.app.sensors.voice.VoiceCommandManager
import com.pwde.app.sensors.voice.VoiceResult
import com.pwde.app.sensors.voice.VoiceState
import com.pwde.app.sensors.voice.WakeWordDetection
import com.pwde.app.sensors.voice.WakeWordEngine
import com.pwde.app.sensors.voice.WakeWordSensitivity
import com.pwde.app.sensors.voice.WakeWordSpotterTuning
import com.pwde.app.sensors.voice.WakeWordState
import com.pwde.app.sensors.voice.WakeWordTuning
import com.pwde.app.sensors.voice.WakeWordTuningStore
import com.pwde.app.sensors.voice.stepTuningValue
import com.pwde.app.ui.common.FaceTrackingViewModel
import com.pwde.app.ui.components.ButtonStyle
import com.pwde.app.ui.components.CameraFeed
import com.pwde.app.ui.components.SegmentedToggle
import com.pwde.app.ui.controls.GestureCatalog
import com.pwde.app.ui.components.CursorPad
import com.pwde.app.ui.components.DemoModeBanner
import com.pwde.app.ui.components.FooterActions
import com.pwde.app.ui.components.GestureMeter
import com.pwde.app.ui.components.GradientCard
import com.pwde.app.ui.components.IconBadge
import com.pwde.app.ui.components.JoystickView
import com.pwde.app.ui.components.LevelSlider
import com.pwde.app.ui.components.SwitchRow
import com.pwde.app.ui.components.PwdeDialog
import com.pwde.app.ui.components.PwdeButton
import com.pwde.app.ui.components.PwdeScreen
import com.pwde.app.ui.components.PwdeTextField
import com.pwde.app.ui.components.StatusPill
import com.pwde.app.ui.components.StepProgress
import com.pwde.app.ui.components.VoiceCommandsEffect
import com.pwde.app.ui.components.fmt
import com.pwde.app.ui.components.voiceCommand
import com.pwde.app.ui.theme.MinTouchTarget
import com.pwde.app.ui.theme.PwdeShapes
import com.pwde.app.ui.theme.PwdeTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Debug-only: live readouts from every input pipeline, for development validation. */
class TestingStationViewModel(
    faceTracking: FaceTrackingManager,
    voiceCommandManager: VoiceCommandManager,
    private val wakeWordEngine: WakeWordEngine,
    private val tuningStore: WakeWordTuningStore,
    private val buttonOverlayPrefs: ButtonOverlayPrefs,
) : FaceTrackingViewModel(faceTracking) {
    /** The mapped-button overlay PWDe draws over the real game. */
    val buttonOverlay: StateFlow<ButtonOverlay> = buttonOverlayPrefs.overlay

    fun setButtonOverlayShown(shown: Boolean) = buttonOverlayPrefs.setShown(shown)

    fun setButtonOverlayOpacity(opacity: Float) = buttonOverlayPrefs.setOpacity(opacity)

    val voice: StateFlow<VoiceState> = voiceCommandManager.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), voiceCommandManager.state.value)

    private val _lastResult = MutableStateFlow<VoiceResult?>(null)
    val lastResult: StateFlow<VoiceResult?> = _lastResult.asStateFlow()

    val wakeWord: StateFlow<WakeWordState> = wakeWordEngine.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), wakeWordEngine.state.value)

    /** Recent hits, newest first, so one can't be missed between glances at the screen. */
    private val _wakeWordLog = MutableStateFlow<List<WakeWordDetection>>(emptyList())
    val wakeWordLog: StateFlow<List<WakeWordDetection>> = _wakeWordLog.asStateFlow()

    /** Phrases the spotter listens for, as the user typed them. */
    private val _wakeWords = MutableStateFlow(DEFAULT_WAKE_WORDS)
    val wakeWords: StateFlow<List<String>> = _wakeWords.asStateFlow()

    /**
     * Per-phrase boost/threshold, shared with gameplay's button voice through [tuningStore]. A phrase
     * missing from this map starts on [WakeWordTuningStore.DEFAULT_PHRASE_TUNING]; one mapped to
     * [WakeWordTuning.INHERIT] uses [spotterTuning] instead. Editable by hand; see [setTuning].
     */
    val wakeTuning: StateFlow<Map<String, WakeWordTuning>> = tuningStore.phraseTuning

    /**
     * The spotter-wide numbers, i.e. what an untuned phrase uses. Shared with gameplay, which picks
     * edits up on its next restart; this panel's own spotter only takes them on
     * [applyWakeWordTuning], because applying one reloads ~6 MB of weights and nobody wants that per tap.
     */
    val spotterTuning: StateFlow<WakeWordSpotterTuning> = tuningStore.spotter

    /** What the user is currently typing into the phrase field. */
    private val _newWakeWord = MutableStateFlow("")
    val newWakeWord: StateFlow<String> = _newWakeWord.asStateFlow()

    init {
        viewModelScope.launch { voiceCommandManager.results.collect { _lastResult.value = it } }
        viewModelScope.launch {
            wakeWordEngine.detections.collect { detection ->
                _wakeWordLog.update { (listOf(detection) + it).take(MAX_WAKE_WORD_LOG) }
            }
        }
    }

    fun toggleWakeWordListening() {
        if (wakeWord.value.running) {
            wakeWordEngine.stop()
        } else {
            startWakeWord()
        }
    }

    fun onNewWakeWordChange(text: String) {
        _newWakeWord.value = text
    }

    /** Adds a typed phrase and restarts, because the spotter re-tokenizes the whole set. */
    fun addWakeWord() {
        val phrase = _newWakeWord.value.trim()
        if (phrase.isEmpty()) return
        _newWakeWord.value = ""
        if (phrase !in _wakeWords.value) {
            // A freshly added phrase starts on the store's default (the most eager preset).
            _wakeWords.update { it + phrase }
            restartIfListening()
        }
    }

    fun removeWakeWord(phrase: String) {
        // Its tuning stays in the store: gameplay may use the same phrase for a button.
        _wakeWords.update { it - phrase }
        restartIfListening()
    }

    /** A preset is a one-tap action, so it takes effect immediately. */
    fun setSensitivity(phrase: String, sensitivity: WakeWordSensitivity) {
        tuningStore.setPhraseTuning(phrase, sensitivity.tuning)
        restartIfListening()
    }

    /** Hand-edited per-phrase numbers. Applied by [applyWakeWordTuning]. */
    fun setTuning(phrase: String, tuning: WakeWordTuning) {
        tuningStore.setPhraseTuning(phrase, tuning)
    }

    /** Hand-edited spotter numbers. Applied by [applyWakeWordTuning]. */
    fun setSpotterTuning(tuning: WakeWordSpotterTuning) {
        tuningStore.setSpotter(tuning)
    }

    /** Pushes every hand-edited number into the engine by rebuilding the spotter. */
    fun applyWakeWordTuning() {
        restartIfListening()
    }

    /**
     * Every phrase back to the eager preset the app ships with, and the spotter to its defaults —
     * for gameplay's button voice too.
     */
    fun resetWakeWordTuning() {
        tuningStore.reset()
        restartIfListening()
    }

    private fun startWakeWord() {
        wakeWordEngine.start(_wakeWords.value, tuningStore.tuningFor(_wakeWords.value), tuningStore.spotter.value)
    }

    private fun restartIfListening() {
        if (wakeWord.value.running) startWakeWord()
    }

    /** Leaving the screen gives the mic back, so the spotter can't hold it into a game. */
    override fun onCleared() {
        wakeWordEngine.stop()
        super.onCleared()
    }

    private companion object {
        const val MAX_WAKE_WORD_LOG = 20
    }
}

/** F · Testing Station (debug builds only). Every panel is live; nothing here is simulated data. */
@Composable
fun TestingStationScreen(viewModel: TestingStationViewModel, onBack: () -> Unit) {
    val face by viewModel.faceState.collectAsStateWithLifecycle()
    val surface by viewModel.surfaceRequest.collectAsStateWithLifecycle()
    val voice by viewModel.voice.collectAsStateWithLifecycle()
    val lastResult by viewModel.lastResult.collectAsStateWithLifecycle()
    val wakeWord by viewModel.wakeWord.collectAsStateWithLifecycle()
    val wakeWordLog by viewModel.wakeWordLog.collectAsStateWithLifecycle()
    val selectedWakeWords by viewModel.wakeWords.collectAsStateWithLifecycle()
    val wakeTuning by viewModel.wakeTuning.collectAsStateWithLifecycle()
    val spotterTuning by viewModel.spotterTuning.collectAsStateWithLifecycle()
    val newWakeWord by viewModel.newWakeWord.collectAsStateWithLifecycle()
    val buttonOverlay by viewModel.buttonOverlay.collectAsStateWithLifecycle()
    val hitTime = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    var page by rememberSaveable { mutableStateOf(TestingPage.CAMERA_GESTURES) }
    val isLastPage = page.ordinal == TestingPage.entries.lastIndex
    fun next() {
        if (isLastPage) onBack() else page = TestingPage.entries[page.ordinal + 1]
    }
    fun previous() {
        if (page.ordinal == 0) onBack() else page = TestingPage.entries[page.ordinal - 1]
    }
    VoiceCommandsEffect(PAGE_COMMANDS) { id -> if (id == "next_page") next() else previous() }
    BackHandler { previous() }
    // Keeps each page's own saveable state (gesture position, open Tune rows) while it is off-screen.
    val pageState = rememberSaveableStateHolder()

    // Keyed so every page starts scrolled to the top.
    key(page) {
        PwdeScreen(
            title = "Testing Station",
            subtitle = "See exactly what PWDe picks up from you.",
            onBack = { previous() },
            voiceHint = page.voiceHint,
            footer = {
                FooterActions(
                    primaryText = if (isLastPage) "Done" else "Next",
                    onPrimary = { next() },
                    primaryIcon = if (isLastPage) Icons.Outlined.Check else Icons.AutoMirrored.Outlined.ArrowForward,
                    secondaryText = if (page.ordinal > 0) "Previous" else null,
                    onSecondary = { previous() },
                    secondaryIcon = Icons.AutoMirrored.Outlined.ArrowBack,
                )
            },
        ) {
            StepProgress(page.ordinal + 1, TestingPage.entries.size, page.label)
            pageState.SaveableStateProvider(page.name) {
                when (page) {
                    TestingPage.CAMERA_GESTURES -> {
                        DemoModeBanner(face)
                        CameraFeed(
                            faceState = face,
                            surfaceRequest = surface,
                            canRequestCamera = viewModel.canRequestCamera,
                            onCameraPermissionResult = viewModel::onCameraPermissionResult,
                            showLandmarks = true,
                        )
                        GesturesPanel(face)
                        FacePanel(face)
                    }
                    TestingPage.VOICE -> {
                        VoicePanel(voice, lastResult)
                        WakeWordPanel(
                            viewModel = viewModel,
                            wakeWord = wakeWord,
                            wakeWordLog = wakeWordLog,
                            selectedWakeWords = selectedWakeWords,
                            wakeTuning = wakeTuning,
                            spotterTuning = spotterTuning,
                            newWakeWord = newWakeWord,
                            hitTime = hitTime,
                        )
                    }
                    TestingPage.CURSOR_JOYSTICK -> {
                        CursorPanel(face)
                        JoystickPanel(face)
                        MappedButtonsPanel(buttonOverlay, viewModel)
                    }
                }
            }
        }
    }
}

/** The Testing Station's pages, in order. */
private enum class TestingPage(val label: String, val voiceHint: String) {
    CAMERA_GESTURES(
        "Camera, gestures & face",
        "Say \"swap gesture\", \"previous gesture\", \"mediapipe\" or \"next page\"",
    ),
    VOICE(
        "Voice",
        "Say anything — it shows up in the Voice panel. \"next page\" or \"previous page\" to move",
    ),
    CURSOR_JOYSTICK(
        "Cursor & joystick",
        "Move your head to drive the cursor and joystick. Say \"previous page\" to go back",
    ),
}

private val PAGE_COMMANDS = listOf(
    voiceCommand("next_page", "next page", "next"),
    voiceCommand("previous_page", "previous page", "back", "go back"),
)

@Composable
private fun MappedButtonsPanel(buttonOverlay: ButtonOverlay, viewModel: TestingStationViewModel) {
    Panel("Mapped buttons in game", Icons.Outlined.TouchApp) {
        SwitchRow(
            "Show mapped buttons",
            buttonOverlay.shown,
            viewModel::setButtonOverlayShown,
            description = "While playing, draws each button where PWDe taps it. A press flashes green when " +
                    "Android made the tap, amber when it went with the held joystick, red when it failed.",
        )
        val level = (buttonOverlay.opacity * 10).roundToInt().coerceIn(1, 10)
        LevelSlider(
            "Overlay opacity",
            level,
            { viewModel.setButtonOverlayOpacity(it / 10f) },
            valueLabel = "${level * 10}%",
            enabled = buttonOverlay.shown,
        )
    }
}

@Composable
private fun VoicePanel(voice: VoiceState, lastResult: VoiceResult?) {
    Panel("Voice", Icons.Outlined.Mic) {
        Reading("State", when {
            voice.usesTextFallback -> voice.availability.label
            !voice.enabled -> "Off"
            voice.listening -> "Listening (level ${fmt(voice.level)})"
            else -> "Starting"
        })
        Reading("Match / activation", "${voice.matchMode.label} · ${voice.activationMode.label}")
        Reading("Transcript", lastResult?.let { "\"${it.transcript}\" (${if (it.isFinal) "final" else "partial"}, ${it.source.name.lowercase()})" } ?: "—")
        Reading("Matched command", lastResult?.command?.let { "${it.label} [${it.scope.name.lowercase()}]" } ?: "—")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WakeWordPanel(
    viewModel: TestingStationViewModel,
    wakeWord: WakeWordState,
    wakeWordLog: List<WakeWordDetection>,
    selectedWakeWords: List<String>,
    wakeTuning: Map<String, WakeWordTuning>,
    spotterTuning: WakeWordSpotterTuning,
    newWakeWord: String,
    hitTime: SimpleDateFormat,
) {
    val colors = PwdeTheme.colors
    Panel("Wake word", Icons.Outlined.Hearing) {
        Reading("Engine", SherpaInGameVoiceEngine.MODEL_LABEL)
        Reading(
            "Status",
            when {
                wakeWord.running -> "Listening for ${wakeWord.phrases.size} phrase(s)"
                wakeWord.canListen -> "Stopped"
                else -> wakeWord.availability.label
            },
        )
        Reading("Detections", wakeWord.detections.toString())
        Reading("Mic level", if (wakeWord.running) fmt(wakeWord.level) else "—")
        Reading(
            "Model load",
            if (wakeWord.running) {
                val percent = (wakeWord.realTimeFactor * 100).toInt()
                "$percent% of real time" + if (percent >= 100) " — dropping audio" else ""
            } else {
                "—"
            },
        )
        Reading("Last hit", wakeWordLog.firstOrNull()?.let { "${hitTime.format(Date(it.atMs))} · \"${it.phrase}\"" } ?: "—")
        wakeWord.error?.let { Reading("Error", it) }
        if (wakeWord.unsupported.isNotEmpty()) {
            Reading("Can't spot", wakeWord.unsupported.joinToString(", ") { "\"$it\"" })
        }
        PwdeTextField(
            "Wake phrase",
            newWakeWord,
            viewModel::onNewWakeWordChange,
            helper = "Any English phrase, e.g. hey pwde",
        )
        PwdeButton(
            "Add phrase",
            viewModel::addWakeWord,
            enabled = newWakeWord.isNotBlank(),
            icon = Icons.Outlined.Add,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Listening for · tap a phrase to tune it", style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
        // The phrase whose tuning pop-up is open, if any.
        var tuningPhrase by rememberSaveable { mutableStateOf<String?>(null) }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            selectedWakeWords.forEach { phrase -> PhraseChip(phrase, onClick = { tuningPhrase = phrase }) }
        }
        tuningPhrase?.takeIf { it in selectedWakeWords }?.let { phrase ->
            // A phrase starts on a preset; the steppers expose the by-hand numbers behind it.
            val tuning = wakeTuning[WakeWordTuningStore.key(phrase)] ?: WakeWordTuningStore.DEFAULT_PHRASE_TUNING
            PwdeDialog(title = "\"$phrase\"", onDismiss = { tuningPhrase = null }) {
                Text("Sensitivity", style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
                SegmentedToggle(
                    WakeWordSensitivity.entries,
                    WakeWordSensitivity.presetFor(tuning),
                    { it.label },
                    { viewModel.setSensitivity(phrase, it) },
                )
                TuningSteppers(
                    boost = tuning.boost,
                    threshold = tuning.threshold,
                    inherited = spotterTuning,
                    onTuning = { viewModel.setTuning(phrase, it) },
                )
                Text(
                    "Hand-edited numbers take effect with Apply & restart.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
                PwdeButton(
                    "Stop listening for this phrase",
                    {
                        viewModel.removeWakeWord(phrase)
                        tuningPhrase = null
                    },
                    style = ButtonStyle.DESTRUCTIVE,
                    icon = Icons.Outlined.Close,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Text("Spotter defaults", style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
        TuningSteppers(
            boost = spotterTuning.score,
            threshold = spotterTuning.threshold,
            inherited = spotterTuning,
            onTuning = { edited ->
                viewModel.setSpotterTuning(
                    spotterTuning.copy(
                        score = edited.boost ?: spotterTuning.score,
                        threshold = edited.threshold ?: spotterTuning.threshold,
                    ),
                )
            },
        )
        ValueStepper(
            "Trailing blanks",
            spotterTuning.trailingBlanks.toString(),
            { direction ->
                viewModel.setSpotterTuning(
                    spotterTuning.copy(trailingBlanks = spotterTuning.trailingBlanks + direction),
                )
            },
            canDecrease = spotterTuning.trailingBlanks > WakeWordSpotterTuning.TRAILING_BLANKS_RANGE.start,
            canIncrease = spotterTuning.trailingBlanks < WakeWordSpotterTuning.TRAILING_BLANKS_RANGE.endInclusive,
            definition = TRAILING_BLANKS_DEFINITION,
        )
        ValueStepper(
            "Active paths",
            spotterTuning.activePaths.toString(),
            { direction ->
                viewModel.setSpotterTuning(
                    spotterTuning.copy(activePaths = spotterTuning.activePaths + direction),
                )
            },
            canDecrease = spotterTuning.activePaths > WakeWordSpotterTuning.ACTIVE_PATHS_RANGE.start,
            canIncrease = spotterTuning.activePaths < WakeWordSpotterTuning.ACTIVE_PATHS_RANGE.endInclusive,
            definition = ACTIVE_PATHS_DEFINITION,
        )
        SwitchRow(
            "Noise cancellation",
            spotterTuning.noiseCancellation,
            { viewModel.setSpotterTuning(spotterTuning.copy(noiseCancellation = it)) },
            description = "The phone's call-audio cleanup: cuts background noise and the game's own sound. Can cost accuracy on some voices.",
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PwdeButton("Apply & restart", viewModel::applyWakeWordTuning, modifier = Modifier.weight(1f))
            PwdeButton(
                "Reset",
                viewModel::resetWakeWordTuning,
                style = ButtonStyle.SECONDARY,
                modifier = Modifier.weight(1f),
            )
        }
        // Each fact opens on its own, so reading one doesn't unfold the rest.
        ExpandableSection("Easier to hit") {
            Definition("Higher boost keeps a keyword alive through beam search; lower threshold fires on weaker evidence. Both raise the false-alarm rate, so they are set per phrase — Normal / High / Max in one tap, or tap a phrase to step the numbers by hand.")
        }
        ExpandableSection("Tuning") {
            Definition("A phrase's own numbers are written into its keyword line; Spotter defaults are what an untuned phrase uses (score, threshold, trailing blanks and active paths are exactly sherpa-onnx's keywordsScore, keywordsThreshold, numTrailingBlanks and maxActivePaths). Values are clamped to what the model accepts, and both halves are pushed to the engine by Apply & restart.")
        }
        ExpandableSection("Gameplay") {
            Definition("Mapped buttons are pressed by voice with this same spotter, and it uses the tuning set here. Tune a button's exact voice trigger by adding it as a phrase. App navigation still uses Google speech.")
        }
        ExpandableSection("Heads up") {
            Definition("While this listens, PWDe's app-wide voice commands stand down.")
        }
        if (wakeWord.running) {
            PwdeButton(
                "Stop listening",
                viewModel::toggleWakeWordListening,
                style = ButtonStyle.SECONDARY,
                icon = Icons.Outlined.Hearing,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            PwdeButton(
                "Start listening",
                viewModel::toggleWakeWordListening,
                enabled = selectedWakeWords.isNotEmpty(),
                icon = Icons.Outlined.Hearing,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CursorPanel(face: FaceState) {
    Panel("Cursor", Icons.Outlined.Mouse) {
        Reading("Position", "x ${fmt(face.cursor.x)}, y ${fmt(face.cursor.y)}")
        Reading("Output mode", face.outputMode.label)
        CursorPad(face.cursor, active = face.hasFace)
    }
}

@Composable
private fun JoystickPanel(face: FaceState) {
    Panel("Joystick", Icons.Outlined.Gamepad) {
        Reading("X / Y", "${fmt(face.joystick.x)} / ${fmt(face.joystick.y)}")
        Reading("Direction", face.joystick.direction.label)
        JoystickView(face.joystick, Modifier.fillMaxWidth(0.6f).align(Alignment.CenterHorizontally), active = face.hasFace)
    }
}

private val GESTURE_SWAP_COMMANDS = listOf(
    voiceCommand("next_gesture", "next gesture", "swap gesture", "swap"),
    voiceCommand("previous_gesture", "previous gesture"),
    voiceCommand("tab:GESTURES", "gestures", "curated"),
    voiceCommand("tab:MEDIAPIPE", "mediapipe", "media pipe", "blendshapes"),
)

/**
 * One gesture at a time, with a swap button to step through every gesture PWDe knows. The
 * "Detected" line still covers all of them, so nothing firing off-screen is missed.
 */
@Composable
private fun GesturesPanel(face: FaceState) {
    val colors = PwdeTheme.colors
    var catalog by rememberSaveable { mutableStateOf(GestureCatalog.GESTURES) }
    // Separate position per list, so switching lists doesn't lose your place.
    var curatedIndex by rememberSaveable { mutableIntStateOf(0) }
    var rawIndex by rememberSaveable { mutableIntStateOf(0) }
    val gestures = catalog.gestures
    val index = if (catalog == GestureCatalog.GESTURES) curatedIndex else rawIndex
    fun swap(delta: Int) {
        val next = (index + delta).mod(gestures.size)
        if (catalog == GestureCatalog.GESTURES) curatedIndex = next else rawIndex = next
    }
    VoiceCommandsEffect(GESTURE_SWAP_COMMANDS) { id ->
        when {
            id.startsWith("tab:") -> catalog = GestureCatalog.valueOf(id.removePrefix("tab:"))
            else -> swap(if (id == "next_gesture") 1 else -1)
        }
    }
    val gesture = gestures[index]
    val measure = face.gesture.measures[gesture]
    val detected = gesture in face.gesture.active

    Panel("Gestures", Icons.Outlined.TouchApp) {
        SegmentedToggle(GestureCatalog.entries, catalog, { it.label }, { catalog = it })
        Text(
            "Detected: " + face.gesture.active.filter { it in gestures }.joinToString { it.label }.ifEmpty { "none" },
            style = MaterialTheme.typography.bodyMedium,
            color = if (face.gesture.active.isNotEmpty()) colors.primary else colors.textMuted,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(PwdeShapes.button)
                .background(if (detected) colors.primary.copy(alpha = 0.18f) else colors.surfaceMuted)
                .border(if (detected) 2.dp else 1.dp, if (detected) colors.primary else colors.secondary.copy(alpha = 0.5f), PwdeShapes.button)
                .padding(PwdeTheme.spacing.internal),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (catalog == GestureCatalog.MEDIAPIPE) "Blendshape ${index + 1} of ${gestures.size} · MediaPipe"
                else "Gesture ${index + 1} of ${gestures.size}",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textMuted,
            )
            Text(gesture.label, style = MaterialTheme.typography.headlineSmall, color = if (detected) colors.primary else colors.text)
            Text(gesture.description, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            GestureMeter(gesture, measure, active = detected)
            Text(
                when {
                    detected -> "DETECTED"
                    measure == null && face.isSimulated -> "Demo mode can only simulate head moves (tilt, nod, shake)."
                    measure == null -> "Waiting for a face…"
                    else -> "Score ${fmt(measure.score)} · fires at ${fmt(measure.threshold)} (your sensitivity)"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (detected) colors.primary else colors.text,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PwdeButton(
                "Previous",
                { swap(-1) },
                style = ButtonStyle.SECONDARY,
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                modifier = Modifier.weight(1f),
            )
            PwdeButton(
                "Swap gesture",
                { swap(1) },
                icon = Icons.Outlined.SwapHoriz,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun FacePanel(face: FaceState) {
    Panel("Face Tracking", Icons.Outlined.Face) {
        Reading(
            "Source",
            when {
                face.isGyro -> "Gyro (phone rotation sensor)"
                face.isSimulated -> "Simulated (motion sensors)"
                else -> "Camera + MediaPipe"
            },
        )
        Reading("Status", when (val s = face.status) {
            TrackingStatus.Idle -> "Idle"
            TrackingStatus.Starting -> "Starting"
            TrackingStatus.Live -> "Live · ${face.fps.toInt()} fps"
            TrackingStatus.NoFace -> "No face in view"
            is TrackingStatus.Unavailable -> "Unavailable: ${s.reason}"
        })
        val pose = face.pose
        Reading("Yaw / pitch / roll", if (pose == null) "—" else "${fmt(pose.yaw)}° / ${fmt(pose.pitch)}° / ${fmt(pose.roll)}°")
        Reading("Landmarks", face.landmarks?.let { "${it.size / 2} points" } ?: "—")
        Reading(
            "Confidence",
            when {
                face.confidence != null -> fmt(face.confidence)
                face.hasFace && !face.isSimulated -> "≥ 0.50 (model gate; per-point score not reported)"
                else -> "—"
            },
        )
    }
}

@Composable
private fun Panel(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    GradientCard(Modifier.fillMaxWidth()) {
        // Bottom padding separates the icon + title header from the panel's first reading or control.
        Row(
            Modifier.padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconBadge(icon)
            Text(title, style = MaterialTheme.typography.titleMedium, color = PwdeTheme.colors.text)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/** A tappable header that shows or hides [content]; starts collapsed. */
@Composable
private fun ExpandableSection(title: String, content: @Composable () -> Unit) {
    val colors = PwdeTheme.colors
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clip(PwdeShapes.button)
            .clickable(role = Role.Button) { open = !open }
            .semantics { stateDescription = if (open) "Expanded" else "Collapsed" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = colors.textMuted, modifier = Modifier.weight(1f))
        Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null, tint = colors.primary)
    }
    if (open) content()
}

/**
 * One wake phrase as a pill. Always in the selected style of [SegmentedToggle] (primary fill,
 * onAccent text), since every chip is a phrase being listened for. Tapping opens its tuning.
 */
@Composable
private fun PhraseChip(phrase: String, onClick: () -> Unit) {
    val colors = PwdeTheme.colors
    Row(
        Modifier
            .heightIn(min = MinTouchTarget)
            .clip(PwdeShapes.pill)
            .background(colors.primary)
            .clickable(role = Role.Button, onClickLabel = "Tune", onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(phrase, style = MaterialTheme.typography.labelLarge, color = colors.onAccent)
        Icon(Icons.Outlined.Tune, contentDescription = null, tint = colors.onAccent, modifier = Modifier.size(16.dp))
    }
}

/** The body text of an expanded definition. */
@Composable
private fun Definition(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = PwdeTheme.colors.text)
}

/** One size for every definition's info icon. */
private val InfoIconSize = 20.dp

private const val BOOST_DEFINITION =
    "How strongly the spotter favours this phrase while decoding (sherpa-onnx keywordsScore). Higher keeps a half-heard phrase alive, so it fires more easily — and more often by mistake."
private const val THRESHOLD_DEFINITION =
    "How sure the spotter must be before it fires, from 0 to 1 (keywordsThreshold). Lower fires on weaker evidence."
private const val TRAILING_BLANKS_DEFINITION =
    "How many blank frames must follow a phrase before it counts (numTrailingBlanks). More waits for the phrase to end, so it fires later but mixes up overlapping phrases less."
private const val ACTIVE_PATHS_DEFINITION =
    "How many candidate decodings the spotter keeps at once (maxActivePaths). More catches more ways of saying a phrase, at more CPU."

@Composable
private fun Reading(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodySmall, color = PwdeTheme.colors.textMuted, modifier = Modifier.weight(0.4f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = PwdeTheme.colors.text, modifier = Modifier.weight(0.6f))
    }
}

/** One tap of the boost stepper. */
private const val BOOST_STEP = 0.5f

/** One tap of the threshold stepper: 0.00 to 1.00 in twenty taps. */
private const val THRESHOLD_STEP = 0.05f

/**
 * Boost and threshold for one tuning, as steppers. Steppers are this screen's design language (see
 * `LevelStepper`), and they are also what makes these numbers safe to edit by hand: every tap is
 * already a valid value, so nothing has to be parsed or re-seeded while it is half-typed.
 *
 * A null field is the "inherit" state — the phrase then writes no numbers into its keyword line and
 * uses [inherited], which the label says out loud. The first tap turns the pair into explicit
 * numbers seeded from [inherited] and the other field's current value.
 */
@Composable
private fun TuningSteppers(
    boost: Float?,
    threshold: Float?,
    inherited: WakeWordSpotterTuning,
    onTuning: (WakeWordTuning) -> Unit,
) {
    val boostValue = boost ?: inherited.score
    val thresholdValue = threshold ?: inherited.threshold
    ValueStepper(
        label = "Boost",
        valueText = if (boost == null) "${fmt(boostValue)} · spotter" else fmt(boostValue),
        onStep = { direction ->
            onTuning(
                WakeWordTuning(
                    stepTuningValue(boostValue, BOOST_STEP, direction, WakeWordSpotterTuning.SCORE_RANGE),
                    thresholdValue,
                ),
            )
        },
        canDecrease = boostValue > WakeWordSpotterTuning.SCORE_RANGE.start,
        canIncrease = boostValue < WakeWordSpotterTuning.SCORE_RANGE.endInclusive,
        definition = BOOST_DEFINITION,
    )
    ValueStepper(
        label = "Threshold",
        valueText = if (threshold == null) "${fmt(thresholdValue)} · spotter" else fmt(thresholdValue),
        onStep = { direction ->
            onTuning(
                WakeWordTuning(
                    boostValue,
                    stepTuningValue(thresholdValue, THRESHOLD_STEP, direction, WakeWordSpotterTuning.THRESHOLD_RANGE),
                ),
            )
        },
        canDecrease = thresholdValue > WakeWordSpotterTuning.THRESHOLD_RANGE.start,
        canIncrease = thresholdValue < WakeWordSpotterTuning.THRESHOLD_RANGE.endInclusive,
        definition = THRESHOLD_DEFINITION,
    )
}

/**
 * −  value  +  row for one hand-editable number. With a [definition], tapping the label (marked
 * with an info icon) shows or hides it below the row, like [ExpandableSection]; it starts collapsed.
 */
@Composable
private fun ValueStepper(
    label: String,
    valueText: String,
    onStep: (Int) -> Unit,
    canDecrease: Boolean,
    canIncrease: Boolean,
    definition: String? = null,
) {
    val colors = PwdeTheme.colors
    var defined by rememberSaveable(label) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (definition == null) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.text, modifier = Modifier.weight(1f))
        } else {
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = MinTouchTarget)
                    .clip(PwdeShapes.button)
                    .clickable(role = Role.Button, onClickLabel = if (defined) "Hide definition" else "Show definition") { defined = !defined }
                    .semantics { stateDescription = if (defined) "Definition shown" else "Definition hidden" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // weight(fill = false) measures the icon first, so it is always its full size and a
                // long label ("Trailing blanks") wraps beside it instead of squeezing it out.
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.text,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    if (defined) Icons.Outlined.ExpandLess else Icons.Outlined.Info,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(InfoIconSize),
                )
            }
        }
        IconButton(onClick = { onStep(-1) }, enabled = canDecrease, modifier = Modifier.size(MinTouchTarget)) {
            Icon(Icons.Filled.Remove, contentDescription = "Lower $label", tint = colors.primary)
        }
        Text(
            valueText,
            style = MaterialTheme.typography.labelMedium,
            color = colors.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 96.dp),
        )
        IconButton(onClick = { onStep(1) }, enabled = canIncrease, modifier = Modifier.size(MinTouchTarget)) {
            Icon(Icons.Filled.Add, contentDescription = "Raise $label", tint = colors.primary)
        }
    }
    if (definition != null && defined) Definition(definition)
}
