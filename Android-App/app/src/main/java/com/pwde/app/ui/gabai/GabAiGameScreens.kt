package com.pwde.app.ui.gabai

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import com.pwde.app.ui.profile.RECENT_CALIBRATIONS
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.pwde.app.data.local.CalibrationProfile
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pwde.app.data.gabai.GabAiState
import com.pwde.app.data.model.ButtonTrigger
import com.pwde.app.data.model.FacialGesture
import com.pwde.app.data.model.Game
import com.pwde.app.data.model.MappedButton
import com.pwde.app.data.model.TriggerType
import com.pwde.app.data.prefs.InputMode
import com.pwde.app.play.GameInput
import com.pwde.app.sensors.face.JoystickDirection
import com.pwde.app.ui.components.ButtonStyle
import com.pwde.app.ui.components.ControlsList
import com.pwde.app.ui.components.DemoModeBanner
import com.pwde.app.ui.components.overlayDrawsPointer
import com.pwde.app.ui.components.GradientCard
import com.pwde.app.ui.components.InfoNote
import com.pwde.app.ui.components.OptionCard
import com.pwde.app.ui.components.OptionKind
import com.pwde.app.ui.components.PwdeButton
import com.pwde.app.ui.components.PwdeIconButton
import com.pwde.app.ui.components.PwdeTextField
import com.pwde.app.ui.components.SectionTitle
import com.pwde.app.ui.components.SegmentedToggle
import com.pwde.app.ui.components.StatusPill
import com.pwde.app.ui.components.VoiceCommandsEffect
import com.pwde.app.data.games.gameDisplayName
import com.pwde.app.ui.games.GameGrid
import com.pwde.app.ui.components.voiceCommand
import com.pwde.app.data.local.inputModeOrDefault
import com.pwde.app.ui.theme.PwdeShapes
import com.pwde.app.ui.theme.PwdeTheme
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.heightIn
// ---------------- Choose game / calibration ----------------

@Composable
internal fun ChooseGameStep(viewModel: GabAiViewModel, ui: GabAiUiState) {
    val commands = remember { Game.entries.map { voiceCommand(it.id, it.displayName) } }
    val gameProfiles by viewModel.gameProfiles.collectAsStateWithLifecycle()
    val customGames by viewModel.customGames.collectAsStateWithLifecycle()
    VoiceCommandsEffect(commands) { id -> Game.byId(id)?.let(viewModel::chooseGame) }
    GabAiStep(
        viewModel, ui,
        panelTitle = ui.panelTitle,
        title = "Game profile",
        says = "Which game are we setting up?",
        voiceHint = "Say a game's name",
    ) {
        GameGrid(Game.entries, hasProfile = { game -> gameProfiles.any { it.gameId == game.id } }, onGame = viewModel::chooseGame, customGames = customGames, onAddedGame = viewModel::chooseCustomGame, onAddGame = viewModel::addCustomGame)
    }
}

private val CONFIRM_COMMANDS = listOf(
    voiceCommand("use", "use this one", "next", "confirm"),
    voiceCommand("new", "make a new calibration", "new calibration", "make a new one"),
)

@Composable
internal fun ConfirmCalibrationStep(viewModel: GabAiViewModel, ui: GabAiUiState) {
    val profiles by viewModel.calibrationProfiles.collectAsStateWithLifecycle()
    val selected = ui.form.calibrationProfileId
    // Start open when the picked profile is an older one, so it stays visible.
    var showAll by rememberSaveable { mutableStateOf(profiles.sortedWith(compareByDescending<CalibrationProfile> { it.createdAt }.thenByDescending { it.id }).drop(RECENT_CALIBRATIONS).any { it.id == selected }) }
    VoiceCommandsEffect(CONFIRM_COMMANDS) { id -> if (id == "use") viewModel.confirmCalibration() else viewModel.calibrateForThisGame() }
    GabAiStep(
        viewModel, ui,
        panelTitle = ui.panelTitle,
        title = "Calibration for ${gameDisplayName(ui.form.gameId) ?: "this game"}",
        says = if (profiles.isEmpty()) "This game needs a calibration first. It takes a minute."
        else "Which calibration should this game use?",
        voiceHint = if (profiles.isEmpty()) "Say \"new calibration\"" else "Say \"use this one\" or \"new calibration\"",
        footer = if (profiles.isEmpty()) null else {
            { PwdeButton("Use this one", viewModel::confirmCalibration, icon = Icons.AutoMirrored.Outlined.ArrowForward, modifier = Modifier.fillMaxWidth()) }
        },
    ) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(PwdeTheme.spacing.itemGap)) {
            val newestFirst = remember(profiles) { profiles.sortedWith(compareByDescending<CalibrationProfile> { it.createdAt }.thenByDescending { it.id }) }
            val shown = if (showAll) newestFirst else newestFirst.take(RECENT_CALIBRATIONS)
            shown.forEach { profile ->
                OptionCard(
                    profile.name,
                    profile.inputModeOrDefault.let { if (it == InputMode.JOYSTICK) "Joystick" else "Cursor" },
                    profile.id == selected,
                    { viewModel.chooseCalibration(profile.id) },
                    icon = Icons.Outlined.Tune,
                    kind = OptionKind.RADIO,
                )
            }
        }
        if (profiles.size > RECENT_CALIBRATIONS) {
            PwdeButton(
                if (showAll) "Show less" else "Show ${profiles.size - RECENT_CALIBRATIONS} more",
                { showAll = !showAll },
                style = ButtonStyle.SECONDARY,
                icon = if (showAll) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        PwdeButton(
            "Make a new calibration",
            viewModel::calibrateForThisGame,
            style = if (profiles.isEmpty()) ButtonStyle.PRIMARY else ButtonStyle.SECONDARY,
            icon = Icons.Outlined.Tune,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------------- Screenshot ----------------

private val SCREENSHOT_COMMANDS = listOf(
    voiceCommand("pick", "choose screenshot", "choose a different one", "pick screenshot", "choose"),
    voiceCommand("blank", "use a blank screen", "blank screen", "no screenshot"),
    voiceCommand("next", "next", "continue"),
)

@Composable
internal fun ScreenshotStep(viewModel: GabAiViewModel, ui: GabAiUiState) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(viewModel::importScreenshot) }
    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val hasShot = ui.form.screenshotPath != null
    VoiceCommandsEffect(SCREENSHOT_COMMANDS) { id ->
        when (id) {
            "pick" -> pick()
            "blank" -> viewModel.useBlankScreen()
            "next" -> if (hasShot && !ui.detectingButtons) viewModel.screenshotDone()
        }
    }
    GabAiStep(
        viewModel, ui,
        panelTitle = ui.panelTitle,
        title = "Game screenshot",
        says = "Pick a mid-match screenshot with the game's buttons visible.",
        voiceHint = "Say \"choose screenshot\", \"blank screen\" or \"next\"",
        footer = {
            PwdeButton("Next", viewModel::screenshotDone, enabled = hasShot && !ui.detectingButtons, icon = Icons.AutoMirrored.Outlined.ArrowForward, modifier = Modifier.fillMaxWidth())
        },
    ) {
        val shot = ui.screenshot
        if (shot != null) {
            Box(Modifier.fillMaxWidth().clip(PwdeShapes.card).border(2.dp, PwdeTheme.colors.borderBrush, PwdeShapes.card)) {
                Image(
                    shot,
                    contentDescription = "Your game screenshot",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                )
                if (ui.detectingButtons) {
                    Column(
                        Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.6f))
                            .semantics { contentDescription = "Processing your screenshot" },
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(color = Color.White)
                        Text(
                            "Finding the buttons…",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }
        PwdeButton(if (shot == null) "Choose screenshot" else "Choose a different one", pick, icon = Icons.Outlined.Image, modifier = Modifier.fillMaxWidth())
        PwdeButton("Use a blank screen instead", viewModel::useBlankScreen, style = ButtonStyle.SECONDARY, modifier = Modifier.fillMaxWidth())
        // Kept: where the screenshot goes is a privacy promise, not decoration.
        InfoNote(if (viewModel.autoDetectsButtons) "Sent once to PWDe's server to find the buttons; kept on this phone." else "Stays on this phone.")
    }
}

// ---------------- Button mapping ----------------

/** Internal so the Voice screen can list it; ids unchanged. */
internal val MAPPING_COMMANDS = listOf(
    voiceCommand("place", "place", "add button", "place here", "add"),
    voiceCommand("delete", "delete", "remove"),
    voiceCommand("rename", "rename", "name it"),
    voiceCommand("up", "move up", "up"),
    voiceCommand("down", "move down", "down"),
    voiceCommand("left", "move left", "left"),
    voiceCommand("right", "move right", "right"),
    voiceCommand("next_button", "next button", "select next"),
    voiceCommand("done", "done", "finished", "next"),
)

private const val NUDGE = 0.02f

@Composable
internal fun ButtonMappingStep(viewModel: GabAiViewModel, ui: GabAiUiState) {
    val face by viewModel.faceState.collectAsStateWithLifecycle()
    val buttons = ui.form.buttons
    val selected = buttons.firstOrNull { it.id == ui.selectedButtonId }
    // The stage uses PWDe's one overlay for the pointer, so "point and say place" points with the
    // same cursor — which can press things — as the game. Only draw our own when the service is off.
    val overlayDrawn = overlayDrawsPointer()
    VoiceCommandsEffect(MAPPING_COMMANDS) { id ->
        when (id) {
            "place" -> viewModel.addButtonAtPointer()
            "delete" -> viewModel.deleteSelected()
            "rename" -> viewModel.captureLabelByVoice()
            "up" -> viewModel.nudgeSelected(0f, -NUDGE)
            "down" -> viewModel.nudgeSelected(0f, NUDGE)
            "left" -> viewModel.nudgeSelected(-NUDGE, 0f)
            "right" -> viewModel.nudgeSelected(NUDGE, 0f)
            "next_button" -> if (buttons.isNotEmpty()) {
                val i = buttons.indexOfFirst { it.id == ui.selectedButtonId }
                viewModel.selectButton(buttons[(i + 1).mod(buttons.size)].id)
            }
            "done" -> viewModel.buttonsDone()
        }
    }
    GabAiStageStep(
        viewModel, ui,
        panelTitle = ui.panelTitle,
        title = "Mark the buttons",
        says = "Tap each game button, or point and say \"place\". Then name it.",
        voiceHint = "Say \"place\", \"rename\", \"move left\" or \"next\"",
        footer = {
            SideButton(
                "Next: ${buttons.size} ${if (buttons.size == 1) "button" else "buttons"}",
                viewModel::buttonsDone,
                enabled = buttons.isNotEmpty(),
                icon = Icons.Outlined.CheckCircle,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        stage = {
            ButtonCanvas(
                screenshot = ui.screenshot,
                buttons = buttons,
                selectedId = ui.selectedButtonId,
                pointer = if (!overlayDrawn && face.hasFace) Offset(face.cursor.x, face.cursor.y) else null,
                fit = true,
                onTapEmpty = viewModel::addButton,
                onTapButton = { viewModel.selectButton(it) },
                onDrag = viewModel::moveButton,
            )
        },
    ) {
        DemoModeBanner(face)
        if (selected == null) {
            InfoNote(if (buttons.isEmpty()) "No buttons yet. Tap the game screen to add one." else "Tap a button to rename, move or delete it.")
        } else {
            SelectedButtonEditor(viewModel, selected, ui.capturingLabel)
        }
    }
}

@Composable
private fun SelectedButtonEditor(viewModel: GabAiViewModel, button: MappedButton, capturing: Boolean) {
    val colors = PwdeTheme.colors
    GradientCard(Modifier.fillMaxWidth()) {
        PwdeTextField("Button name", button.label, { viewModel.renameButton(button.id, it) })
        PwdeButton(
            if (capturing) "Listening — say the name…" else "Say its name",
            viewModel::captureLabelByVoice,
            style = ButtonStyle.SECONDARY,
            icon = Icons.Outlined.Mic,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NudgeButton(Icons.AutoMirrored.Outlined.ArrowBack, "Move left") { viewModel.nudgeSelected(-NUDGE, 0f) }
            NudgeButton(Icons.Outlined.ArrowUpward, "Move up") { viewModel.nudgeSelected(0f, -NUDGE) }
            NudgeButton(Icons.Outlined.ArrowDownward, "Move down") { viewModel.nudgeSelected(0f, NUDGE) }
            NudgeButton(Icons.AutoMirrored.Outlined.ArrowForward, "Move right") { viewModel.nudgeSelected(NUDGE, 0f) }
        }
        PwdeButton("Delete button", viewModel::deleteSelected, style = ButtonStyle.DESTRUCTIVE, icon = Icons.Outlined.Delete, modifier = Modifier.fillMaxWidth())
        Text("Tip: drag the circle to move it.", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
    }
}

/** Same 56dp size, shape and border as every other Controls button; the icon scales with it. */
@Composable
private fun NudgeButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    PwdeIconButton(icon, description, onClick)
}

/**
 * The game screenshot (or a blank game-shaped screen) with its buttons drawn on it. Every button
 * looks the same however it got there.
 */
@Composable
internal fun ButtonCanvas(
    screenshot: ImageBitmap?,
    buttons: List<MappedButton>,
    selectedId: Int?,
    pointer: Offset? = null,
    highlightId: Int? = null,
    showTriggers: Boolean = false,
    fit: Boolean = false,
    focusSelected: Boolean = false,
    onTapEmpty: ((Float, Float) -> Unit)? = null,
    onTapButton: ((Int) -> Unit)? = null,
    onDrag: ((Int, Float, Float) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = PwdeTheme.colors
    val aspect = screenshot?.let { it.width.toFloat() / it.height } ?: (16f / 9f)
    val latestButtons by rememberUpdatedState(buttons)
    val latestSelected by rememberUpdatedState(selectedId)
    val latestFocus by rememberUpdatedState(focusSelected)
    BoxWithConstraints(
        modifier
            .then(if (fit) Modifier else Modifier.fillMaxWidth())
            .aspectRatio(aspect)
            .then(if (fit) Modifier else Modifier.clip(PwdeShapes.card).border(2.dp, colors.borderBrush, PwdeShapes.card))
            .background(Color(0xFF1B2A1E))
            .semantics {
                contentDescription = if (showTriggers) {
                    buttons.joinToString(prefix = "Game screen. ") { "${it.label}: ${it.trigger?.describe() ?: "not mapped"}" }
                } else {
                    "Game screen with ${buttons.size} marked buttons"
                }
            }
            .then(
                if (onTapEmpty == null && onTapButton == null) Modifier
                else Modifier.pointerInput(Unit) {
                    detectTapGestures { tap ->
                        val x = tap.x / size.width
                        val y = tap.y / size.height
                        val hit = latestButtons.minByOrNull { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) }
                            ?.takeIf { kotlin.math.hypot((it.x - x) * size.width, (it.y - y) * size.height) < HIT_RADIUS_DP * density }
                        if (latestFocus && latestSelected != null && hit?.id != latestSelected) return@detectTapGestures
                        if (hit != null) onTapButton?.invoke(hit.id) else onTapEmpty?.invoke(x, y)
                    }
                },
            )
            .then(
                if (onDrag == null) Modifier
                else Modifier.pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        val id = latestSelected ?: return@detectDragGestures
                        change.consume()
                        onDrag(id, change.position.x / size.width, change.position.y / size.height)
                    }
                },
            ),
    ) {
        if (screenshot != null) {
            Image(screenshot, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        } else {
            Text(
                "Blank game screen",
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.4f),
            )
        }
        val diameter = 44.dp
        buttons.forEach { button ->
            val isSelected = button.id == selectedId || button.id == highlightId
            val unmapped = showTriggers && button.trigger == null
            val dimmed = focusSelected && selectedId != null && !isSelected
            val ring = if (isSelected) SELECTED_COLOR else if (unmapped) colors.warning else Color.White
            Box(
                Modifier
                    .offset(
                        x = maxWidth * button.x - diameter / 2,
                        y = maxHeight * button.y - diameter / 2,
                    )
                    .size(diameter)
                    .alpha(if (dimmed) 0.3f else 1f),
                contentAlignment = Alignment.TopCenter,
            ) {
                if (isSelected) {
                    Box(
                        Modifier
                            .requiredSize(diameter + 18.dp)
                            .align(Alignment.Center)
                            .border(3.dp, SELECTED_COLOR.copy(alpha = 0.6f), CircleShape),
                    )
                }
                Box(
                    Modifier
                        .size(diameter)
                        .clip(CircleShape)
                        .background(if (isSelected) SELECTED_COLOR.copy(alpha = 0.5f) else colors.secondary.copy(alpha = 0.4f))
                        .border(if (isSelected) 5.dp else 3.dp, ring, CircleShape),
                )
                Column(
                    modifier = Modifier
                        .offset(y = diameter)
                        .wrapContentWidth(unbounded = true)
                        .background(if (isSelected) SELECTED_COLOR else Color.Black.copy(alpha = 0.7f), PwdeShapes.pill)
                        .padding(horizontal = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        button.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) Color.Black else Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                    if (showTriggers) {
                        Text(
                            button.trigger?.shortLabel() ?: "Tap to map",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) Color.Black else if (unmapped) colors.warning else colors.primary,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        if (pointer != null) {
            val dot = 16.dp
            Box(
                Modifier
                    .offset(x = maxWidth * pointer.x - dot / 2, y = maxHeight * pointer.y - dot / 2)
                    .size(dot)
                    .clip(CircleShape)
                    .background(colors.primary)
                    .border(2.dp, Color.White, CircleShape),
            )
        }
    }
}

private const val HIT_RADIUS_DP = 32f
private val SELECTED_COLOR = Color(0xFFFFE600)

// ---------------- Trigger assignment ----------------

internal val TRIGGER_COMMANDS = listOf(
    voiceCommand("type:VOICE", "voice", "voice command"),
    voiceCommand("type:GESTURE", "gesture", "head gesture"),
    voiceCommand("previous_button", "previous button", "previous"),
    voiceCommand("next_button", "next button"),
    // Moves on to testing, like the sidebar's "Next", even with a button's chooser open.
    voiceCommand("next_step", "next", "done mapping"),
    // Closes the open chooser; with none open, also moves on.
    voiceCommand("done", "done"),
    voiceCommand("close", "close", "cancel"),
    voiceCommand("use_all_suggested", "suggested words", "use all suggested words", "auto map"),
) + JoystickDirection.entries.filter { it != JoystickDirection.CENTER }
    .map { voiceCommand("dir:${it.name}", "stick ${it.label.lowercase()}") }
private val TRIGGER_PANEL_COMMANDS = listOf(
    voiceCommand("show_controls", *GameInput.SHOW_CONTROLS_PHRASES.toTypedArray()),
    voiceCommand("hide_controls", *GameInput.HIDE_CONTROLS_PHRASES.toTypedArray()),
    voiceCommand("hide_panel", *PANEL_PHRASES_HIDE),
    voiceCommand("show_panel", *PANEL_PHRASES_SHOW),
)

@Composable
internal fun AssignTriggersStep(viewModel: GabAiViewModel, ui: GabAiUiState) {
    val buttons = ui.form.buttons
    val selected = buttons.firstOrNull { it.id == ui.selectedButtonId }
    val mapped = buttons.count { it.trigger != null }
    val gestures by viewModel.triggerGestures.collectAsStateWithLifecycle()
    var type by rememberSaveable(selected?.id) { mutableStateOf(selected?.trigger?.type ?: TriggerType.VOICE) }
    // Open the first unmapped button immediately; for a saved profile, start on its first button so
    // every existing mapping can be reviewed or changed without a voice command.
    LaunchedEffect(ui.state) {
        if (buttons.isNotEmpty() && ui.selectedButtonId == null) viewModel.nextButtonToAssign()
    }
    val commands = remember(buttons.map { it.id to it.label }, gestures) {
        TRIGGER_COMMANDS + TRIGGER_PANEL_COMMANDS +
            buttons.map { voiceCommand("button:${it.id}", it.label.lowercase()) } +
            gestures.map { voiceCommand("gesture:${it.name}", it.spokenName) }
    }
    VoiceCommandsEffect(commands) { id ->
        when {
            id.startsWith("button:") -> viewModel.openTriggerChooser(id.removePrefix("button:").toInt())
            id.startsWith("type:") -> if (selected != null) type = TriggerType.valueOf(id.removePrefix("type:"))
            id.startsWith("gesture:") -> selected?.let { viewModel.pickGesture(it.id, FacialGesture.valueOf(id.removePrefix("gesture:"))) }
            // "Stick up" on the movement joystick would turn it into a plain button press.
            id.startsWith("dir:") -> selected?.takeIf { it.trigger?.type != TriggerType.MOVEMENT }?.let {
                type = TriggerType.JOYSTICK
                viewModel.setTrigger(it.id, ButtonTrigger(TriggerType.JOYSTICK, id.removePrefix("dir:")))
            }
            id == "previous_button" -> viewModel.previousButtonToAssign() // Added
            id == "next_button" -> viewModel.nextButtonToAssign()
            id == "use_all_suggested" -> viewModel.mapAllSuggestedWords() // Added
            id == "close" -> viewModel.openTriggerChooser(null)
            id == "show_controls" -> viewModel.setControlsShown(true)
            id == "hide_controls" -> viewModel.setControlsShown(false)
            id == "hide_panel" -> viewModel.setSidebarOpen(false)
            id == "show_panel" -> viewModel.setSidebarOpen(true)
            id == "next_step" -> viewModel.triggersDone()
            id == "done" -> if (selected != null) viewModel.openTriggerChooser(null) else viewModel.triggersDone()
        }
    }

    GabAiStageStep(
        viewModel, ui,
        panelTitle = ui.panelTitle,
        title = "Choose how to press each button",
        says = if (selected != null) "How to press ${selected.label}?" else "Tap a button or say its name, then choose how to press it.",
        voiceHint = if (selected != null) "Say \"voice\", \"gesture\", \"joystick\", \"previous button\", \"next button\" or \"done\""
        else "Tap Map beside a button, tap it on the game screen, or say its name",
        footer = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PwdeTheme.spacing.itemGap),
            ) {
                if (ui.canUndoAutoMap) {
                    SideButton(
                        "Undo",
                        viewModel::undoMapAllSuggestedWords,
                        icon = Icons.AutoMirrored.Outlined.ArrowBack,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    SideButton(
                        "Use All Suggested Words",
                        viewModel::mapAllSuggestedWords,
                        icon = Icons.Outlined.AutoAwesome,
                        modifier = Modifier.weight(1.4f),
                    )
                }
                SideButton(
                    "Next",
                    viewModel::triggersDone,
                    icon = Icons.Outlined.CheckCircle,
                    modifier = Modifier.weight(1f),
                )
                    }
        },
        stage = {
            ButtonCanvas(
                screenshot = ui.screenshot,
                buttons = buttons,
                selectedId = ui.selectedButtonId,
                showTriggers = true,
                fit = true,
                focusSelected = true,
                onTapButton = viewModel::openTriggerChooser,
            )
        },
    ) {
        if (selected != null) {
            TriggerChooser(
                viewModel = viewModel,
                button = selected,
                buttons = buttons,
                gestures = gestures,
                type = type,
                onType = { type = it },
            )
        } else {
            StatusPill(
                if (mapped == buttons.size) "All mapped" else "$mapped/${buttons.size} mapped",
                color = if (mapped == buttons.size) PwdeTheme.colors.primary else PwdeTheme.colors.warning,
                icon = if (mapped == buttons.size) Icons.Outlined.CheckCircle else Icons.Outlined.TouchApp,
            )
        }
        SectionTitle("Map or change a button")
        Text(
            "Choose a row to assign its control.",
            style = MaterialTheme.typography.bodySmall,
            color = PwdeTheme.colors.textMuted,
        )
        buttons.forEach { button ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(button.label, style = MaterialTheme.typography.labelLarge, color = PwdeTheme.colors.text)
                    Text(
                        button.trigger?.describe() ?: "Not mapped",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (button.trigger == null) PwdeTheme.colors.warning else PwdeTheme.colors.textMuted,
                    )
                }
                SideButton(
                    if (button.trigger == null) "Map" else "Edit",
                    { viewModel.openTriggerChooser(button.id) },
                    style = if (button.id == ui.selectedButtonId) ButtonStyle.PRIMARY else ButtonStyle.SECONDARY,
                    modifier = Modifier.weight(0.65f),
                )
            }
        }
        if (selected == null) {
            if (ui.controlsShown) {
                ControlsList(buttons, onClose = { viewModel.setControlsShown(false) })
            } else {
                SideButton("Show controls", { viewModel.setControlsShown(true) }, style = ButtonStyle.SECONDARY, icon = Icons.AutoMirrored.Outlined.FormatListBulleted, modifier = Modifier.fillMaxWidth())
            }
            Text("The joystick moves; your head steers it.", style = MaterialTheme.typography.bodySmall, color = PwdeTheme.colors.textMuted)
        }
    }
}
@Composable
private fun TriggerChooser(
    viewModel: GabAiViewModel,
    button: MappedButton,
    buttons: List<MappedButton>,
    gestures: List<FacialGesture>,
    type: TriggerType,
    onType: (TriggerType) -> Unit,
) {
    val colors = PwdeTheme.colors
    val trigger = button.trigger
    val conflicts = trigger?.let { value -> buttons.filter { it.id != button.id && it.trigger == value }.map { it.label } }.orEmpty()
    val gestureOff = trigger?.gesture?.let { it !in gestures } == true

    // Joystick directions are hidden; the movement joystick stays, so a detected stick shows as one
    // (and a hand-placed stick, as in manual mapping, can still be made one).
    val availableTriggerTypes = TriggerType.entries.filter { it != TriggerType.JOYSTICK }
    GradientCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 2.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(), // no padding here — let's isolate the source first
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = button.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.text,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = { viewModel.openTriggerChooser(null) }) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close", tint = colors.primary)
                }
            }

            // Segmented Toggle - with correct parameter names
            SegmentedToggle(
                options = availableTriggerTypes,
                selected = type,
                label = { it.label },
                onSelect = onType,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            )

            // Content based on type
            when (type) {
                TriggerType.VOICE -> {
                    val phrase = if (trigger?.type == TriggerType.VOICE) trigger.value else ""
                    PwdeTextField(
                        label = "What will you say?",
                        value = phrase,
                        onValueChange = { viewModel.setTrigger(button.id, if (it.isBlank()) null else ButtonTrigger(TriggerType.VOICE, it)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (phrase.isEmpty()) {
                        SideButton(
                            "Use \"${button.label.lowercase()}\"",
                            { viewModel.setTrigger(button.id, ButtonTrigger(TriggerType.VOICE, button.label.lowercase())) },
                            style = ButtonStyle.SECONDARY,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                TriggerType.GESTURE -> {
                    val availableGestures = FacialGesture.selectable
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        availableGestures.forEach { gesture ->
                            OptionCard(
                                title = gesture.label,
                                description = null,
                                selected = trigger?.type == TriggerType.GESTURE && trigger.value == gesture.name,
                                onClick = { viewModel.pickGesture(button.id, gesture) },
                                kind = OptionKind.RADIO,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                TriggerType.MOVEMENT -> {
                    if (trigger?.type == TriggerType.MOVEMENT) {
                        Text(
                            text = "This is the movement joystick: PWDe holds it and your head steers it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textMuted
                        )
                    } else {
                        SideButton(
                            "Use as movement joystick",
                            { viewModel.setTrigger(button.id, ButtonTrigger.MOVEMENT) },
                            style = ButtonStyle.SECONDARY,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                else -> {
                    Text(
                        text = "This trigger type is not available.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted
                    )
                }
            }

            // Status & Warning Pills
            trigger?.let {
                StatusPill(
                    text = "Pressed by: ${it.describe()}",
                    icon = Icons.Outlined.CheckCircle
                )
            }

            if (gestureOff) {
                Text(
                    text = "Gesture is not enabled in this calibration.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.warning
                )
            }

            if (conflicts.isNotEmpty()) {
                StatusPill(
                    text = "Also used by ${conflicts.joinToString()}",
                    color = colors.warning,
                    icon = Icons.Outlined.WarningAmber
                )
            }

            // Navigation Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SideButton(
                    "Previous button",
                    viewModel::previousButtonToAssign,
                    style = ButtonStyle.SECONDARY,
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    modifier = Modifier.weight(1f)
                )
                SideButton(
                    "Next button",
                    viewModel::nextButtonToAssign,
                    style = ButtonStyle.SECONDARY,
                    icon = Icons.Outlined.SkipNext,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ---------------- Test controls ----------------

@Composable
internal fun TestControlsStep(viewModel: GabAiViewModel, ui: GabAiUiState) {
    val buttons = ui.form.buttons
    val hit = ui.testHit
    // The app-wide recognizer can hold the mic here too, so "next" is registered with it as well
    // as with the test's own listener (TEST_DONE_PHRASES).
    VoiceCommandsEffect(remember { listOf(voiceCommand("next", "next", "done testing", "finish testing")) }) { viewModel.testingDone() }

    GabAiStep(
        viewModel, ui,
        panelTitle = ui.panelTitle,
        title = "Test controls",
        says = "Try a gesture or say a command to test your controls.",
        voiceHint = "Say a command or tap Next",
        // In AssignTriggersStep footer:
        footer = {
            PwdeButton(
                "Next",
                viewModel::testingDone,
                icon = Icons.AutoMirrored.Outlined.ArrowForward,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    ) {
        ButtonCanvas(
            ui.screenshot,
            buttons,
            selectedId = null,
            highlightId = hit?.buttonId,
            modifier = Modifier.fillMaxWidth(0.7f).align(Alignment.CenterHorizontally),
        )
        hit?.let {
            StatusPill(it.text, icon = Icons.Outlined.CheckCircle)
        }
        SectionTitle("Mapped controls")
        // Two per row, trigger under the name, so a full HUD fits without scrolling.
        buttons.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { button ->
                    Column(Modifier.weight(1f)) {
                        Text(button.label, style = MaterialTheme.typography.bodyMedium, color = PwdeTheme.colors.text, maxLines = 1)
                        Text(button.trigger?.shortLabel() ?: "—", style = MaterialTheme.typography.bodySmall, color = PwdeTheme.colors.primary, maxLines = 1)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// ---------------- Name, save, done ----------------

@Composable
internal fun NameAndSaveStep(viewModel: GabAiViewModel, ui: GabAiUiState) {
    val gameName = gameDisplayName(ui.form.gameId)
    VoiceCommandsEffect(remember { listOf(voiceCommand("save", "save game profile", "save", "save profile")) }) { viewModel.saveGameProfile() }
    GabAiStep(
        viewModel, ui,
        panelTitle = ui.panelTitle,
        title = "Name and save",
        says = "Name this ${gameName ?: "game"} profile and save it.",
        voiceHint = "Say \"save as\" and a name, e.g. \"save as Fanny\"",
        footer = { PwdeButton("Save game profile", viewModel::saveGameProfile, icon = Icons.Outlined.Save, modifier = Modifier.fillMaxWidth()) },
    ) {
        PwdeTextField("Profile name (optional)", ui.form.profileName, viewModel::setProfileName)
        // The button list was just reviewed on Test controls, so it isn't repeated here.
        Text("${ui.form.buttons.size} buttons mapped", style = MaterialTheme.typography.bodyMedium, color = PwdeTheme.colors.textMuted)
    }
}

private val SAVED_PROFILE_COMMANDS = listOf(
    voiceCommand("play", "play", "play now"),
    voiceCommand("another", "create another", "another"),
    voiceCommand("dashboard", "go to dashboard", "dashboard", "done"),
)

@Composable
internal fun ProfileSavedStep(viewModel: GabAiViewModel, ui: GabAiUiState) {
    VoiceCommandsEffect(SAVED_PROFILE_COMMANDS) { id ->
        when (id) {
            "play" -> viewModel.playNow()
            "another" -> viewModel.createAnother()
            "dashboard" -> viewModel.goToDashboard()
        }
    }
    GabAiStep(
        viewModel, ui,
        panelTitle = ui.panelTitle,
        title = "Profile saved",
        says = "Saved \"${ui.form.profileName}\"!",
        voiceHint = "Say \"play now\", \"create another\" or \"dashboard\"",
    ) {
        // An added game isn't supported yet: its mapping is saved, but there's nothing to launch.
        if (Game.byId(ui.form.gameId) != null) {
            PwdeButton("Play now", viewModel::playNow, icon = Icons.Outlined.PlayArrow, modifier = Modifier.fillMaxWidth())
        }
        PwdeButton("Create another", { viewModel.createAnother() }, style = ButtonStyle.SECONDARY, icon = Icons.Outlined.Add, modifier = Modifier.fillMaxWidth())
        PwdeButton("Go to Dashboard", viewModel::goToDashboard, style = ButtonStyle.SECONDARY, icon = Icons.Outlined.Dashboard, modifier = Modifier.fillMaxWidth())
    }
}
