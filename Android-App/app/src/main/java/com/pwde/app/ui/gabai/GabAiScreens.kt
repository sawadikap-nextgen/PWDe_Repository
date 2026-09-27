package com.pwde.app.ui.gabai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.runtime.remember
import com.pwde.app.data.local.GameProfile
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pwde.app.data.gabai.Axis
import com.pwde.app.data.gabai.GabAiFlow
import com.pwde.app.data.gabai.GabAiState
import com.pwde.app.data.gabai.JoystickParameter
import com.pwde.app.data.model.DEFAULT_LEVEL
import com.pwde.app.data.model.FacialGesture
import com.pwde.app.data.model.MAX_LEVEL
import com.pwde.app.data.model.MIN_LEVEL
import com.pwde.app.data.model.VoiceActivationMode
import com.pwde.app.data.model.VoiceMatchMode
import com.pwde.app.sensors.face.GestureThresholds
import com.pwde.app.ui.components.ButtonStyle
import com.pwde.app.ui.components.CameraFeed
import com.pwde.app.ui.components.CalibrationOverlayMode
import com.pwde.app.ui.components.CursorCalibrationOverlay
import com.pwde.app.ui.components.DemoModeBanner
import com.pwde.app.ui.components.overlayDrawsPointer
import com.pwde.app.ui.components.GestureMeter
import com.pwde.app.ui.components.GradientCard
import com.pwde.app.ui.components.IconBadge
import com.pwde.app.ui.components.InfoNote
import com.pwde.app.ui.components.JoystickView
import com.pwde.app.ui.components.LevelSlider
import com.pwde.app.ui.components.LockOrientation
import com.pwde.app.data.model.Game
import com.pwde.app.ui.components.MainTab
import com.pwde.app.ui.components.NavCard
import com.pwde.app.ui.components.OptionCard
import com.pwde.app.ui.components.OptionKind
import com.pwde.app.ui.components.PwdeBottomNav
import com.pwde.app.ui.components.PwdeButton
import com.pwde.app.ui.components.PwdeScreen
import com.pwde.app.ui.components.PwdeTextField
import com.pwde.app.ui.components.SectionTitle
import com.pwde.app.ui.components.StatusPill
import com.pwde.app.ui.components.StepProgress
import com.pwde.app.ui.components.SwitchRow
import com.pwde.app.ui.components.VoiceCommandsEffect
import com.pwde.app.ui.components.fmt
import com.pwde.app.ui.components.levelWord
import com.pwde.app.ui.components.rememberCalibrationOverlay
import com.pwde.app.ui.components.voiceCommand
import com.pwde.app.ui.theme.PwdeShapes
import com.pwde.app.ui.theme.PwdeTheme

/**
 * G · GabAI. One route; the screen shown is whatever [GabAiState] the conversation is in, so
 * leaving and coming back (even after a force-close) lands on exactly the same step.
 */
@Composable
fun GabAiScreen(
    viewModel: GabAiViewModel,
    onExit: () -> Unit,
    onTab: (MainTab) -> Unit,
    onDashboard: () -> Unit,
    onPlay: (gameId: String, profileId: Long) -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    BackHandler(onBack = viewModel::back)
    LaunchedEffect(viewModel) {
        viewModel.navigation.collect {
            when (it) {
                GabAiNavigation.Exit -> onExit()
                GabAiNavigation.Dashboard -> onDashboard()
                is GabAiNavigation.Play -> onPlay(it.gameId, it.profileId)
            }
        }
    }
    if (!ui.loaded) return
    // From placing buttons to testing them, the work is on the game's screen, so hold the phone the
    // way the game is played. The lock is released once mapping is done: naming and saving is a form,
    // and staying in landscape there left the device stuck sideways after the mapping was finished.
    if (GabAiFlow.locksGameScreenOrientation(ui.state)) {
        LockOrientation(Game.byId(ui.form.gameId)?.landscape ?: true)
    }
    when (val state = ui.state) {
        GabAiState.Welcome -> WelcomeStep(viewModel, ui, onTab)
        is GabAiState.CalibrateCursorAxis -> CursorAxisStep(viewModel, ui, state.axis)
        is GabAiState.CalibrateJoystick -> JoystickStep(viewModel, ui, state.parameter)
        GabAiState.CalibrationVoiceSetup -> VoiceStep(viewModel, ui)
        is GabAiState.CalibrationGestureTest -> GestureTestStep(viewModel, ui, state)
        GabAiState.CalibrationGestureReview -> GestureReviewStep(viewModel, ui)
        GabAiState.CalibrationSaved -> CalibrationSavedStep(viewModel, ui)
        GabAiState.ChooseGame -> ChooseGameStep(viewModel, ui)
        GabAiState.ConfirmCalibrationProfile -> ConfirmCalibrationStep(viewModel, ui)
        GabAiState.UploadScreenshot -> ScreenshotStep(viewModel, ui)
        is GabAiState.ButtonMapping -> ButtonMappingStep(viewModel, ui)
        GabAiState.AssignTriggers -> AssignTriggersStep(viewModel, ui)
        GabAiState.TestControls -> TestControlsStep(viewModel, ui)
        GabAiState.NameAndSaveProfile -> NameAndSaveStep(viewModel, ui)
        GabAiState.ProfileSaved -> ProfileSavedStep(viewModel, ui)
    }
}

/** Shared frame for every GabAI step: GabAI's line on top, then the step's own content. */
@Composable
internal fun GabAiStep(
    viewModel: GabAiViewModel,
    ui: GabAiUiState,
    title: String,
    says: String,
    voiceHint: String,
    panelTitle: String,
    footer: (@Composable () -> Unit)? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    compactSays: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    PwdeScreen(
        // The flow on top (GabAI, or Manual Mapping), like a chat; the step or section is the subheader.
        title = panelTitle,
        subtitle = title,
        // Tab screens (with a bottom bar) have no back arrow; system back still works.
        onBack = if (bottomBar == null) viewModel::back else null,
        voiceHint = voiceHint,
        footer = {
            Column(verticalArrangement = Arrangement.spacedBy(PwdeTheme.spacing.itemGap)) {
                ReplyHint(voiceHint)
                footer?.invoke()
            }
        },
        bottomBar = bottomBar,
    ) {
        GabAiSays(says, compact = compactSays)
        ui.message?.let { StatusPill(it, color = PwdeTheme.colors.warning, modifier = Modifier.fillMaxWidth()) }
        content()
    }
}

/** Where a chat's reply box would be: what the user can say back to GabAI right now. */
@Composable
internal fun ReplyHint(hint: String) {
    val colors = PwdeTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(PwdeShapes.pill)
            .background(colors.surfaceMuted)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { contentDescription = "You can reply: $hint" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // One line: the mic icon already says "you can reply", and screen readers still hear it.
        Icon(Icons.Outlined.Mic, contentDescription = null, tint = colors.primary)
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = colors.text, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun GabAiSays(text: String, compact: Boolean = false) {
    val colors = PwdeTheme.colors
    GradientCard(Modifier.fillMaxWidth(), contentPadding = if (compact) 10.dp else PwdeTheme.spacing.internal) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
            IconBadge(Icons.Outlined.AutoAwesome, tint = colors.secondary, size = if (compact) 28.dp else 36.dp)
            Text(
                text,
                style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge,
                color = colors.text,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ---------------- Welcome ----------------

private val WELCOME_COMMANDS = listOf(
    voiceCommand("calibration", "new calibration profile", "new calibration", "calibration"),
    voiceCommand("game", "new game profile", "new game", "game profile"),
    voiceCommand("continue", "continue", "continue existing"),
) + MainTab.entries.filter { it != MainTab.GABAI }.map { voiceCommand("tab:${it.name}", it.label) }

@Composable
private fun WelcomeStep(viewModel: GabAiViewModel, ui: GabAiUiState, onTab: (MainTab) -> Unit) {
    val gameProfiles by viewModel.gameProfiles.collectAsStateWithLifecycle()
    VoiceCommandsEffect(WELCOME_COMMANDS) { id ->
        when {
            id.startsWith("tab:") -> onTab(MainTab.valueOf(id.removePrefix("tab:")))
            id == "calibration" -> viewModel.startCalibration()
            id == "game" -> viewModel.startGameProfile()
            id == "continue" -> viewModel.resume()
        }
    }
    GabAiStep(
        viewModel, ui,
        panelTitle = ui.panelTitle,
        title = "Welcome",
        says = "Hi, I'm GabAI! What would you like to set up?",
        voiceHint = "Say \"new calibration\", \"new game\" or \"continue\"",
        bottomBar = { PwdeBottomNav(MainTab.GABAI, onTab) },
    ) {
        NavCard("New Calibration Profile", "Tune cursor, joystick and voice to you", Icons.Outlined.Tune, viewModel::startCalibration)
        NavCard("New Game Profile", "Map a game's buttons to your moves", Icons.Outlined.SportsEsports, { viewModel.startGameProfile() })
        val resumable = ui.resumable
        if (resumable != null) {
            NavCard("Continue Existing", "Pick up where you left off: ${resumable.state.summary}", Icons.Outlined.History, viewModel::resume)
        } else {
            InfoNote("Nothing unfinished to continue.")
        }
        if (gameProfiles.isNotEmpty()) {
            var showAll by rememberSaveable { mutableStateOf(false) }
            val newestFirst = remember(gameProfiles) { gameProfiles.sortedWith(compareByDescending<GameProfile> { it.createdAt }.thenByDescending { it.id }) }
            SectionTitle(if (showAll) "Edit a saved game profile" else "Edit a recent game profile")
            (if (showAll) newestFirst else newestFirst.take(RECENT_GAME_PROFILES)).forEach { profile ->
                NavCard(profile.profileName, profile.gameName, Icons.Outlined.SportsEsports, { viewModel.editGameProfile(profile.id) })
            }
            if (gameProfiles.size > RECENT_GAME_PROFILES) {
                PwdeButton(
                    if (showAll) "Show recent only" else "Show all ${gameProfiles.size} profiles",
                    { showAll = !showAll },
                    style = ButtonStyle.SECONDARY,
                    icon = if (showAll) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** How many of the newest game profiles GabAI lists before "Show all". */
private const val RECENT_GAME_PROFILES = 3

// ---------------- Calibration branch ----------------

private val STEP_COMMANDS = listOf(
    voiceCommand("next", "next", "looks good", "done"),
    voiceCommand("faster", "faster", "more"),
    voiceCommand("slower", "slower", "less"),
    voiceCommand("recenter", "recenter", "center"),
)

private fun axisSays(axis: Axis) = when (axis) {
    Axis.UP -> "Look up to reach the top target. Adjust the speed to feel comfortable."
    Axis.DOWN -> "Now look down to reach the bottom target."
    Axis.LEFT -> "Turn your head left to reach the left target."
    Axis.RIGHT -> "And right, to the right target."
    Axis.DIAGONAL -> "Last one: reach a corner. Shaky? Add smoothing. Laggy? Reduce it."
}

@Composable
private fun CursorAxisStep(viewModel: GabAiViewModel, ui: GabAiUiState, axis: Axis) {
    val face by viewModel.faceState.collectAsStateWithLifecycle()
    val surface by viewModel.surfaceRequest.collectAsStateWithLifecycle()
    val cursor = ui.form.cursor
    val level = when (axis) {
        Axis.UP -> cursor.speedUp
        Axis.DOWN -> cursor.speedDown
        Axis.LEFT -> cursor.speedLeft
        Axis.RIGHT -> cursor.speedRight
        Axis.DIAGONAL -> cursor.smoothing
    }
    fun set(value: Int) = viewModel.setCursor(
        when (axis) {
            Axis.UP -> cursor.copy(speedUp = value)
            Axis.DOWN -> cursor.copy(speedDown = value)
            Axis.LEFT -> cursor.copy(speedLeft = value)
            Axis.RIGHT -> cursor.copy(speedRight = value)
            Axis.DIAGONAL -> cursor.copy(smoothing = value)
        },
    )
    VoiceCommandsEffect(STEP_COMMANDS) { id ->
        when (id) {
            "next" -> viewModel.axisDone(axis)
            "faster" -> set((level + 1).coerceAtMost(MAX_LEVEL))
            "slower" -> set((level - 1).coerceAtLeast(MIN_LEVEL))
            "recenter" -> viewModel.recenterCursor()
        }
    }
    // The overlay draws the pointer; the pad keeps the ring and drops its own dot, so the user sees
    // exactly one pointer. With "Use PWDe" off the overlay draws nothing and the pad shows the dot.
    val overlayDrawn = overlayDrawsPointer()
    GabAiStep(
        viewModel, ui,
        panelTitle = ui.panelTitle,
        title = "Cursor: ${axis.label}",
        says = axisSays(axis),
        voiceHint = "Say \"faster\", \"slower\", \"recenter\" or \"next\"",
        compactSays = true,
        footer = { PwdeButton("Next", { viewModel.axisDone(axis) }, icon = Icons.AutoMirrored.Outlined.ArrowForward, modifier = Modifier.fillMaxWidth()) },
    ) {
        StepProgress(axis.ordinal + 1, Axis.entries.size, "${axis.label} direction")
        DemoModeBanner(face)
        // The live pointer is held inside the calibration box for as long as this step is open.
        val confine = rememberCalibrationOverlay(CalibrationOverlayMode.CONFINE_TO_BOX)
        CameraFeed(
            faceState = face,
            surfaceRequest = surface,
            canRequestCamera = viewModel.canRequestCamera,
            onCameraPermissionResult = viewModel::onCameraPermissionResult,
            modifier = Modifier.align(Alignment.CenterHorizontally).fillMaxWidth().then(confine),
            feedAspectRatio = 16f / 10f,
            overlay = {
                CursorCalibrationOverlay(face.cursor.x, face.cursor.y, face.hasFace, targetFor(axis), showPointer = !overlayDrawn)
            },
        )
        PwdeButton("Recenter pointer", viewModel::recenterCursor, style = ButtonStyle.SECONDARY, icon = Icons.Outlined.CenterFocusStrong, modifier = Modifier.fillMaxWidth())
        LevelSlider(if (axis == Axis.DIAGONAL) "Smoothing" else "Speed moving ${axis.label.lowercase()}", level, ::set)
    }
}

/** Where the target sits for each direction. */
private fun targetFor(axis: Axis): Offset = when (axis) {
    Axis.UP -> Offset(0.5f, 0.1f)
    Axis.DOWN -> Offset(0.5f, 0.9f)
    Axis.LEFT -> Offset(0.1f, 0.5f)
    Axis.RIGHT -> Offset(0.9f, 0.5f)
    Axis.DIAGONAL -> Offset(0.9f, 0.1f)
}

private fun joystickStepCommands(parameter: JoystickParameter) = when (parameter) {
    JoystickParameter.SENSITIVITY -> listOf(
        voiceCommand("increase", "increase sensitivity", "more sensitive", "more"),
        voiceCommand("decrease", "decrease sensitivity", "less sensitive", "less"),
    )
    JoystickParameter.DEAD_ZONE -> listOf(
        voiceCommand("increase", "increase dead zone", "widen dead zone", "more"),
        voiceCommand("decrease", "decrease dead zone", "narrow dead zone", "less"),
    )
} + listOf(
    voiceCommand("next", "next", "looks good", "done"),
    voiceCommand("center", "set center", "center here", "recenter"),
)

private fun joystickSays(parameter: JoystickParameter) = when (parameter) {
    JoystickParameter.SENSITIVITY -> "Tilt your head. Adjust until the stick reaches the outer ring comfortably."
    JoystickParameter.DEAD_ZONE -> "Adjust until small head movements are ignored but steering still responds."
}

private fun joystickVoiceHint(parameter: JoystickParameter) = when (parameter) {
    JoystickParameter.SENSITIVITY -> "Say \"increase sensitivity\", \"decrease sensitivity\", \"recenter\" or \"next\""
    JoystickParameter.DEAD_ZONE -> "Say \"increase dead zone\", \"decrease dead zone\", \"recenter\" or \"next\""
}

@Composable
private fun JoystickStep(viewModel: GabAiViewModel, ui: GabAiUiState, parameter: JoystickParameter) {
    val face by viewModel.faceState.collectAsStateWithLifecycle()
    val surface by viewModel.surfaceRequest.collectAsStateWithLifecycle()
    val joystick = ui.form.joystick
    val level = when (parameter) {
        JoystickParameter.SENSITIVITY -> joystick.sensitivity
        JoystickParameter.DEAD_ZONE -> joystick.deadZone
    }
    fun set(value: Int) = viewModel.setJoystick(
        when (parameter) {
            JoystickParameter.SENSITIVITY -> joystick.copy(sensitivity = value)
            JoystickParameter.DEAD_ZONE -> joystick.copy(deadZone = value)
        },
    )
    VoiceCommandsEffect(joystickStepCommands(parameter)) { id ->
        when (id) {
            "next" -> viewModel.joystickDone(parameter)
            "increase" -> set((level + 1).coerceAtMost(MAX_LEVEL))
            "decrease" -> set((level - 1).coerceAtLeast(MIN_LEVEL))
            "center" -> viewModel.setJoystickCenterHere()
        }
    }
    GabAiStep(
        viewModel, ui,
        panelTitle = ui.panelTitle,
        title = "Joystick: ${parameter.label}",
        says = joystickSays(parameter),
        voiceHint = joystickVoiceHint(parameter),
        compactSays = true,
        footer = {
            PwdeButton(
                "Next",
                { viewModel.joystickDone(parameter) },
                icon = Icons.AutoMirrored.Outlined.ArrowForward,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        StepProgress(parameter.ordinal + 1, JoystickParameter.entries.size, parameter.label)
        DemoModeBanner(face)
        // A joystick is steered by tilting, so the roaming pointer is just noise here: it is hidden
        // for as long as this step is open, and comes back when the step is left.
        rememberCalibrationOverlay(CalibrationOverlayMode.HIDE_POINTER)
        CameraFeed(
            faceState = face,
            surfaceRequest = surface,
            canRequestCamera = viewModel.canRequestCamera,
            onCameraPermissionResult = viewModel::onCameraPermissionResult,
            modifier = Modifier.align(Alignment.CenterHorizontally).fillMaxWidth(),
            feedAspectRatio = 16f / 10f,
            overlay = {
                JoystickView(face.joystick, Modifier.width(136.dp).align(Alignment.Center), active = face.hasFace)
                StatusPill(
                    face.joystick.direction.label,
                    icon = Icons.Outlined.Gamepad,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                )
            },
        )
        PwdeButton(
            "Recenter joystick",
            viewModel::setJoystickCenterHere,
            style = ButtonStyle.SECONDARY,
            icon = Icons.Outlined.CenterFocusStrong,
            modifier = Modifier.fillMaxWidth(),
        )
        LevelSlider(parameter.label, level, ::set)
    }
}

private val VOICE_STEP_COMMANDS = listOf(
    voiceCommand("next", "next", "looks good", "done"),
    voiceCommand("exact", "exact phrase", "exact"),
    voiceCommand("anywhere", "word anywhere", "anywhere"),
    voiceCommand("immediate", "right away"),
    voiceCommand("after", "after I finish"),
)

@Composable
private fun VoiceStep(viewModel: GabAiViewModel, ui: GabAiUiState) {
    val form = ui.form
    VoiceCommandsEffect(VOICE_STEP_COMMANDS) { id ->
        when (id) {
            "next" -> viewModel.voiceDone()
            "exact" -> viewModel.setVoice(match = VoiceMatchMode.EXACT)
            "anywhere" -> viewModel.setVoice(match = VoiceMatchMode.WORD_ANYWHERE)
            "immediate" -> viewModel.setVoice(activation = VoiceActivationMode.IMMEDIATE)
            "after" -> viewModel.setVoice(activation = VoiceActivationMode.AFTER_FINISH)
        }
    }
    GabAiStep(
        viewModel, ui,
        panelTitle = ui.panelTitle,
        title = "Calibration: Voice",
        says = "Now, voice: how strictly I match your words, and when I act.",
        voiceHint = "Say \"word anywhere\", \"right away\" or \"next\"",
        footer = { PwdeButton("Next", viewModel::voiceDone, icon = Icons.AutoMirrored.Outlined.ArrowForward, modifier = Modifier.fillMaxWidth()) },
    ) {
        SwitchRow("Voice control", form.voiceEnabled, { viewModel.setVoice(enabled = it) }, icon = Icons.Outlined.Mic)
        SectionTitle("How words are matched")
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(PwdeTheme.spacing.itemGap)) {
            VoiceMatchMode.entries.forEach { mode ->
                OptionCard(
                    mode.label, mode.description, mode == form.matchMode, { viewModel.setVoice(match = mode) },
                    icon = Icons.AutoMirrored.Outlined.FormatListBulleted, kind = OptionKind.RADIO,
                )
            }
        }
        SectionTitle("When PWDe acts")
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(PwdeTheme.spacing.itemGap)) {
            VoiceActivationMode.entries.forEach { mode ->
                OptionCard(
                    mode.label, mode.description, mode == form.activationMode, { viewModel.setVoice(activation = mode) },
                    icon = Icons.Outlined.Timer, kind = OptionKind.RADIO,
                )
            }
        }
    }
}

private val GESTURE_TEST_COMMANDS = listOf(
    voiceCommand("next", "skip", "next", "can't do it"),
    voiceCommand("finish", "skip the rest", "finish gestures"),
) + listOf(
    voiceCommand("sensitivity_up", "increase sensitivity", "raise sensitivity", "more sensitive", "sensitivity up"),
    voiceCommand("sensitivity_down", "decrease sensitivity", "lower sensitivity", "less sensitive", "sensitivity down"),
) + (MIN_LEVEL..MAX_LEVEL).map { level ->
    val spokenLevel = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")[level - 1]
    voiceCommand(
        "sensitivity:$level",
        "sensitivity $level", "sensitivity $spokenLevel", "set sensitivity to $level", "set sensitivity to $spokenLevel",
        "level $level", "level $spokenLevel",
    )
}

/** One gesture at a time: doing it turns it on (and moves on by itself); skipping leaves it off. */
@Composable
private fun GestureTestStep(viewModel: GabAiViewModel, ui: GabAiUiState, test: GabAiState.CalibrationGestureTest) {
    val face by viewModel.faceState.collectAsStateWithLifecycle()
    val surface by viewModel.surfaceRequest.collectAsStateWithLifecycle()
    val sensitivity by viewModel.gestureSensitivity.collectAsStateWithLifecycle()
    val colors = PwdeTheme.colors
    val gesture = GabAiState.GESTURE_TEST[test.index]
    val commands = remember(gesture) { GESTURE_TEST_COMMANDS }
    val passed = gesture in ui.form.passedGestures
    val level = sensitivity[gesture] ?: DEFAULT_LEVEL
    val measure = face.gesture.measures[gesture]
    VoiceCommandsEffect(commands) { id ->
        when {
            id == "next" -> viewModel.nextGesture()
            id == "finish" -> viewModel.skipRemainingGestures()
            id == "sensitivity_up" -> viewModel.setGestureSensitivity(gesture, (level + 1).coerceAtMost(MAX_LEVEL))
            id == "sensitivity_down" -> viewModel.setGestureSensitivity(gesture, (level - 1).coerceAtLeast(MIN_LEVEL))
            id.startsWith("sensitivity:") -> viewModel.setGestureSensitivity(gesture, id.substringAfter(':').toInt())
        }
    }
    GabAiStep(
        viewModel, ui,
        panelTitle = ui.panelTitle,
        title = "Gesture: ${gesture.label}",
        says = if (passed) "Got it! ${gesture.label} is on."
        else "${gesture.description}. Can't do it? Skip it.",
        voiceHint = "Say \"skip\", \"more sensitive\", \"less sensitive\", or a sensitivity level from 1 to 10",
        footer = {
            PwdeButton(
                if (passed) "Next" else "Skip",
                viewModel::nextGesture,
                style = if (passed) ButtonStyle.PRIMARY else ButtonStyle.SECONDARY,
                icon = Icons.AutoMirrored.Outlined.ArrowForward,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        StepProgress(test.index + 1, GabAiState.GESTURE_TEST.size, gesture.label)
        DemoModeBanner(face)
        GradientCard(Modifier.fillMaxWidth()) {
            GestureMeter(gesture, measure, active = passed || gesture in face.gesture.active)
            Text(
                when {
                    passed -> "Detected! This gesture is on."
                    face.isGyro && measure == null ->
                        "Gyro joystick: skip head tilt, nod and shake."
                    face.isSimulated && measure == null -> "Demo mode can only simulate tilt, nod and shake — skip this one."
                    !face.hasFace -> "Face the camera to try it."
                    else -> "Do the move until the bar passes the tick. Too hard? Raise sensitivity."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (passed) colors.primary else colors.textMuted,
            )
        }
        CameraFeed(
            faceState = face,
            surfaceRequest = surface,
            canRequestCamera = viewModel.canRequestCamera,
            onCameraPermissionResult = viewModel::onCameraPermissionResult,
            modifier = Modifier.align(Alignment.CenterHorizontally).fillMaxWidth(0.45f),
        )
        LevelSlider(
            label = "Sensitivity",
            level = level,
            onLevelChange = { viewModel.setGestureSensitivity(gesture, it) },
            valueLabel = "${levelWord(level)} · fires at ${fmt(GestureThresholds.forGesture(gesture, level))}${gesture.unit()}",
        )
        PwdeButton("Skip the rest", viewModel::skipRemainingGestures, style = ButtonStyle.SECONDARY, icon = Icons.Outlined.SkipNext, modifier = Modifier.fillMaxWidth())
    }
}

private fun FacialGesture.unit() = when (this) {
    FacialGesture.TILT_LEFT, FacialGesture.TILT_RIGHT, FacialGesture.NOD, FacialGesture.SHAKE -> "°"
    else -> ""
}

private val GESTURE_REVIEW_COMMANDS = listOf(
    voiceCommand("save", "save calibration profile", "save", "save profile"),
    voiceCommand("retry", "try the missed ones again", "try again", "retry"),
)

@Composable
private fun GestureReviewStep(viewModel: GabAiViewModel, ui: GabAiUiState) {
    val colors = PwdeTheme.colors
    val form = ui.form
    val tests = GabAiState.GESTURE_TEST
    val on = tests.filter { it in form.passedGestures }
    val missed = tests.filterNot { it in form.passedGestures }
    VoiceCommandsEffect(GESTURE_REVIEW_COMMANDS) { id -> if (id == "save") viewModel.saveCalibration() else viewModel.retryMissedGestures() }
    GabAiStep(
        viewModel, ui,
        panelTitle = ui.panelTitle,
        title = "Calibration: Gestures",
        says = when {
            on.isEmpty() -> "No gestures are on — everything else still works. "
            missed.isEmpty() -> "Every gesture is on! "
            else -> "${on.size} of ${tests.size} gestures are on. "
        } + "Name it and save.",
        voiceHint = "Say \"save as\" + a name" + if (missed.isEmpty()) "" else ", or \"try again\"",
        footer = { PwdeButton("Save calibration profile", viewModel::saveCalibration, icon = Icons.Outlined.Save, modifier = Modifier.fillMaxWidth()) },
    ) {
        SectionTitle("On (${on.size})")
        Text(on.joinToString { it.label }.ifEmpty { "None" }, style = MaterialTheme.typography.bodyLarge, color = colors.text)
        if (missed.isNotEmpty()) {
            SectionTitle("Off (${missed.size})")
            Text(missed.joinToString { it.label }, style = MaterialTheme.typography.bodyLarge, color = colors.textMuted)
            PwdeButton("Try the missed ones again", viewModel::retryMissedGestures, style = ButtonStyle.SECONDARY, icon = Icons.Outlined.Replay, modifier = Modifier.fillMaxWidth())
        }
        PwdeTextField("Profile name (optional)", form.calibrationName, viewModel::setCalibrationName)
    }
}

private val SAVED_COMMANDS = listOf(
    voiceCommand("done", "done", "finish"),
    voiceCommand("game", "next", "set up a game with it", "set up a game", "game profile", "continue"),
)

@Composable
private fun CalibrationSavedStep(viewModel: GabAiViewModel, ui: GabAiUiState) {
    VoiceCommandsEffect(SAVED_COMMANDS) { id -> if (id == "done") viewModel.calibrationDone() else viewModel.continueToGame() }
    val nextGame = ui.form.continueToGame || ui.form.gameId != null
    GabAiStep(
        viewModel, ui,
        panelTitle = ui.panelTitle,
        title = "Calibration saved",
        says = "Saved \"${ui.form.calibrationName}\"! It's active now. " +
                if (nextGame) "Let's carry on with your game." else "Want to set up a game with it?",
        voiceHint = if (nextGame) "Say \"next\" or \"done\"" else "Say \"set up a game\" or \"done\"",
    ) {
        PwdeButton(
            if (nextGame) "Next" else "Set up a game with it",
            viewModel::continueToGame,
            icon = Icons.Outlined.SportsEsports,
            modifier = Modifier.fillMaxWidth(),
        )
        PwdeButton("Done", viewModel::calibrationDone, style = ButtonStyle.SECONDARY, icon = Icons.Outlined.CheckCircle, modifier = Modifier.fillMaxWidth())
    }
}
