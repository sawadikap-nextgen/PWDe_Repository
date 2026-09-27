package com.pwde.app.ui.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pwde.app.data.model.FaceOutputMode
import com.pwde.app.data.model.FacialGesture
import com.pwde.app.data.model.GestureAction
import com.pwde.app.data.model.JoystickSource
import com.pwde.app.data.model.MAX_LEVEL
import com.pwde.app.data.model.MIN_LEVEL
import com.pwde.app.data.model.faceOutputMode
import com.pwde.app.data.prefs.InputMode
import com.pwde.app.sensors.face.GestureThresholds
import com.pwde.app.sensors.voice.VoiceCommand
import com.pwde.app.ui.components.ButtonStyle
import com.pwde.app.ui.components.CheckBadge
import com.pwde.app.ui.components.CameraFeed
import com.pwde.app.ui.components.CalibrationOverlayMode
import com.pwde.app.ui.components.CursorCalibrationOverlay
import com.pwde.app.ui.components.DemoModeBanner
import com.pwde.app.ui.components.GestureMeter
import com.pwde.app.ui.components.GradientCard
import com.pwde.app.ui.components.InfoNote
import com.pwde.app.ui.components.JoystickView
import com.pwde.app.ui.components.LevelSlider
import com.pwde.app.ui.components.NavCard
import com.pwde.app.ui.components.OptionCard
import com.pwde.app.ui.components.OptionKind
import com.pwde.app.ui.components.Pager
import com.pwde.app.ui.components.PlaceholderNotice
import com.pwde.app.ui.components.PwdeButton
import com.pwde.app.ui.components.PwdeScreen
import com.pwde.app.ui.components.SectionTitle
import com.pwde.app.ui.components.SegmentedToggle
import com.pwde.app.ui.components.StatusPill
import com.pwde.app.ui.components.VoiceCommandsEffect
import com.pwde.app.ui.components.fmt
import com.pwde.app.ui.components.levelWord
import com.pwde.app.ui.components.rememberCalibrationOverlay
import com.pwde.app.ui.components.voiceCommand
import com.pwde.app.ui.dashboard.icon
import com.pwde.app.ui.theme.PwdeShapes
import com.pwde.app.ui.theme.PwdeTheme
import com.pwde.app.ui.theme.iconSizeFor
import com.pwde.app.ui.theme.scaled

enum class ControlsDestination { INPUT, GESTURES, CURSOR, JOYSTICK, VOICE, CUSTOM_BUTTONS }

private val HUB_COMMANDS = listOf(
    voiceCommand(ControlsDestination.INPUT.name, "input", "input mode"),
    voiceCommand(ControlsDestination.GESTURES.name, "gestures", "gesture"),
    voiceCommand(ControlsDestination.CURSOR.name, "cursor speed", "cursor", "pointer"),
    voiceCommand(ControlsDestination.JOYSTICK.name, "joystick"),
    voiceCommand(ControlsDestination.VOICE.name, "voice"),
    voiceCommand(ControlsDestination.CUSTOM_BUTTONS.name, "custom buttons"),
)

/** E1 Controls hub: six compact cards. */
@Composable
fun ControlsHubScreen(onBack: () -> Unit, onOpen: (ControlsDestination) -> Unit) {
    VoiceCommandsEffect(HUB_COMMANDS) { id -> onOpen(ControlsDestination.valueOf(id)) }
    PwdeScreen(
        title = "Controls",
        subtitle = "Everything that controls your games.",
        onBack = onBack,
        voiceHint = "Say a card's name, like \"joystick\"",
    ) {
        NavCard("Input", "Head, joystick or voice", Icons.Outlined.Face, { onOpen(ControlsDestination.INPUT) })
        NavCard("Gestures", "Which face move does what", Icons.Outlined.TouchApp, { onOpen(ControlsDestination.GESTURES) })
        NavCard("Cursor speed", "How fast the pointer moves", Icons.Outlined.Mouse, { onOpen(ControlsDestination.CURSOR) })
        NavCard("Joystick", "Size, sensitivity, dead zone", Icons.Outlined.Gamepad, { onOpen(ControlsDestination.JOYSTICK) })
        NavCard("Voice", "Commands and matching", Icons.Outlined.RecordVoiceOver, { onOpen(ControlsDestination.VOICE) })
        NavCard("Custom buttons", "Map a game's buttons with GabAI", Icons.Outlined.Dashboard, { onOpen(ControlsDestination.CUSTOM_BUTTONS) })
    }
}

internal val INPUT_COMMANDS = listOf(
    voiceCommand(InputMode.HEAD_FACE.name, "head", "head and face", "face"),
    voiceCommand(InputMode.JOYSTICK.name, "joystick"),
    voiceCommand(InputMode.VOICE.name, "voice"),
    voiceCommand(SOURCE_GYRO, "gyro mode", "gyro tracking"),
    voiceCommand(SOURCE_HEAD, "head tracking", "head joystick"),
)

private const val SOURCE_GYRO = "source_gyro"
private const val SOURCE_HEAD = "source_head"

/**
 * Input mode picker. Saved right away; head tracking switches between pointer and joystick live, and
 * a joystick picks what steers it — the head or the phone's own tilt — without leaving the screen.
 */
@Composable
fun InputModeScreen(viewModel: InputModeViewModel, onBack: () -> Unit) {
    val selected by viewModel.inputMode.collectAsStateWithLifecycle()
    val source by viewModel.joystickSource.collectAsStateWithLifecycle()
    val steering = source ?: JoystickSource.HEAD
    VoiceCommandsEffect(INPUT_COMMANDS) { id ->
        when (id) {
            // Naming a joystick source means "give me a joystick steered this way", so it selects one.
            SOURCE_GYRO -> {
                viewModel.selectSource(JoystickSource.GYRO)
                viewModel.select(InputMode.JOYSTICK)
            }
            SOURCE_HEAD -> {
                viewModel.selectSource(JoystickSource.HEAD)
                viewModel.select(InputMode.JOYSTICK)
            }
            else -> viewModel.select(InputMode.valueOf(id))
        }
    }
    PwdeScreen(
        title = "Input",
        subtitle = "Your main way to control games. Saved automatically.",
        onBack = onBack,
        voiceHint = "Say \"head\", \"joystick\", \"voice\", \"gyro mode\" or \"head tracking\"",
    ) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(PwdeTheme.spacing.itemGap)) {
            InputMode.entries.forEach { mode ->
                OptionCard(
                    title = mode.label,
                    description = mode.description,
                    selected = mode == selected,
                    onClick = { viewModel.select(mode) },
                    icon = mode.icon(),
                    kind = OptionKind.RADIO,
                )
            }
        }
        selected?.let { mode ->
            if (mode == InputMode.JOYSTICK) {
                SectionTitle("Steered by")
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(PwdeTheme.spacing.itemGap)) {
                    JoystickSource.entries.forEach { option ->
                        OptionCard(
                            title = option.label,
                            description = option.description,
                            selected = option == steering,
                            onClick = { viewModel.selectSource(option) },
                            icon = option.icon(),
                            kind = OptionKind.RADIO,
                        )
                    }
                }
                StatusPill("Joystick steered by: ${steering.label}", icon = steering.icon())
            } else {
                val output = mode.faceOutputMode()
                StatusPill(
                    "Head movement drives: ${output.label}",
                    icon = if (output == FaceOutputMode.JOYSTICK) Icons.Outlined.Gamepad else Icons.Outlined.Mouse,
                )
            }
        }
        InfoNote(
            "Head & face and Voice move a pointer with your head. Joystick turns tilt into an 8-way joystick, " +
                    "steered either by your head or by tilting the phone itself — gyro tracking needs no camera at all. " +
                    "Switch any time by saying \"cursor mode\", \"joystick mode\", \"gyro mode\" or \"head tracking\". " +
                    "Cursor mode is navigation mode, so \"home\", \"back\", \"recent apps\" and \"notifications\" work. " +
                    "Joystick mode is game mode: those are off there, so a stray word can't pull you out of a match. " +
                    "Say \"game mode\" or \"navigation mode\" to change that for the session.",
        )
    }
}

/** The icon for a joystick source, shared by the picker, the pill and the joystick screen. */
internal fun JoystickSource.icon() =
    if (this == JoystickSource.GYRO) Icons.Outlined.ScreenRotation else Icons.Outlined.Face

private const val ACTIONS_PER_PAGE = 4

internal val GESTURES_COMMANDS = GestureAction.entries.map { voiceCommand(it.name, "change ${it.label}", it.label) } + listOf(
    voiceCommand("next_page", "next page"),
    voiceCommand("previous_page", "previous page", "previous"),
)

/** E2/E3 Gestures: 8 actions over two pages, conflicts flagged. */
@Composable
fun GesturesScreen(viewModel: GesturesViewModel, onBack: () -> Unit, onChoose: (GestureAction) -> Unit) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableIntStateOf(1) }
    val pages = GestureAction.entries.chunked(ACTIONS_PER_PAGE)
    VoiceCommandsEffect(GESTURES_COMMANDS) { id ->
        when (id) {
            "next_page" -> if (page < pages.size) page++
            "previous_page" -> if (page > 1) page--
            else -> onChoose(GestureAction.valueOf(id))
        }
    }
    PwdeScreen(
        title = "Gestures",
        subtitle = "Pick a face move for each action.",
        onBack = onBack,
        voiceHint = "Say \"change select\" or \"next page\"",
        footer = { Pager(page, pages.size, { page-- }, { page++ }) },
    ) {
        val current = config ?: return@PwdeScreen
        pages[page - 1].forEach { action ->
            val gesture = current.gestureAssignments[action]
            val conflict = gesture?.let { current.conflictsFor(action, it) }.orEmpty()
            GradientCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(action.label, style = MaterialTheme.typography.titleMedium, color = PwdeTheme.colors.text)
                            // "Configured" at a glance; the conflict pill below stays separate.
                            if (gesture != null) CheckBadge("Gesture assigned")
                        }
                        Text(
                            gesture?.let { "${it.label} · sensitivity ${levelWord(current.sensitivityOf(it)).lowercase()}" } ?: "Not set",
                            style = MaterialTheme.typography.bodySmall,
                            color = PwdeTheme.colors.textMuted,
                        )
                        if (conflict.isNotEmpty()) {
                            StatusPill(
                                "Same as ${conflict.joinToString { it.label }}",
                                color = PwdeTheme.colors.warning,
                                icon = Icons.Outlined.WarningAmber,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    PwdeButton(
                        if (gesture == null) "Add" else "Change",
                        { onChoose(action) },
                        modifier = Modifier.padding(start = 12.dp).widthIn(min = 120.dp),
                    )
                }
            }
        }
        InfoNote(
            "Gestures fire their actions in PWDe's play overlay. Notifications, All apps and Touch & hold need " +
                    "system access PWDe doesn't have, so they act inside the overlay only, not on the rest of your phone.",
        )
    }
}

/** Which part of the catalog is shown: curated gestures, or the raw MediaPipe blendshapes. */
enum class GestureCatalog(val label: String, val gestures: List<FacialGesture>) {
    GESTURES("Gestures (${FacialGesture.selectable.size})", FacialGesture.selectable),
    MEDIAPIPE("MediaPipe (${FacialGesture.raw.size})", FacialGesture.raw),
}

private fun gesturePickCommands(catalog: GestureCatalog) = catalog.gestures.map { gesture ->
    val extra = when (gesture) {
        FacialGesture.EYEBROW_RAISE -> listOf("eyebrows", "raise eyebrows")
        FacialGesture.OPEN_MOUTH -> listOf("mouth")
        FacialGesture.CLOSE_EYES -> listOf("close eyes", "blink")
        FacialGesture.SHAKE -> listOf("shake")
        else -> emptyList()
    }
    VoiceCommand(gesture.name, listOf(gesture.spokenName) + extra)
} + listOf(
    voiceCommand("clear", "clear"),
    voiceCommand("done", "done"),
    voiceCommand("tab:GESTURES", "gestures", "curated"),
    voiceCommand("tab:MEDIAPIPE", "mediapipe", "media pipe", "all blendshapes", "blendshapes"),
)

/** E4/E5 Choose a gesture from the catalog, by tap or by voice; tune its sensitivity and try it live. */
@Composable
fun ChooseGestureScreen(viewModel: ChooseGestureViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = PwdeTheme.colors
    // Open on the list that holds the current pick.
    var catalog by rememberSaveable(state.selected?.isRaw) {
        mutableStateOf(if (state.selected?.isRaw == true) GestureCatalog.MEDIAPIPE else GestureCatalog.GESTURES)
    }
    val commands = remember(catalog) { gesturePickCommands(catalog) }
    VoiceCommandsEffect(commands) { id ->
        when {
            id == "clear" -> viewModel.clear()
            id == "done" -> onBack()
            id.startsWith("tab:") -> catalog = GestureCatalog.valueOf(id.removePrefix("tab:"))
            else -> viewModel.select(FacialGesture.valueOf(id))
        }
    }
    PwdeScreen(
        title = "Gesture for \"${state.action.label}\"",
        subtitle = "Pick one. Moves already in use are marked.",
        onBack = onBack,
        voiceHint = "Say a gesture's name, like \"smile\"",
        footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PwdeButton("Clear", viewModel::clear, style = ButtonStyle.SECONDARY, enabled = state.selected != null, modifier = Modifier.weight(1f))
                PwdeButton("Done", onBack, modifier = Modifier.weight(1f))
            }
        },
    ) {
        SegmentedToggle(GestureCatalog.entries, catalog, { it.label }, { catalog = it })
        if (catalog == GestureCatalog.MEDIAPIPE) {
            InfoNote("Each MediaPipe face score on its own, named as MediaPipe names them. Say a name like \"brow inner up\".")
        }
        catalog.gestures.chunked(2).forEach { row ->
            Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(PwdeTheme.spacing.itemGap)) {
                row.forEach { gesture ->
                    GestureTile(
                        gesture = gesture,
                        selected = gesture == state.selected,
                        usedBy = state.usedBy[gesture].orEmpty(),
                        onClick = { viewModel.select(gesture) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (state.selected != null && state.usedBy[state.selected].orEmpty().isNotEmpty()) {
            Text(
                "Heads up: this move also triggers ${state.usedBy[state.selected].orEmpty().joinToString { it.label }}.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.warning,
            )
        }
        state.selected?.let { gesture ->
            TryGesture(viewModel, gesture, state.sensitivityOf(gesture), Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

/** Sensitivity for the chosen move plus a live meter from the camera, so the user can feel it out. */
@Composable
private fun TryGesture(viewModel: ChooseGestureViewModel, gesture: FacialGesture, level: Int, cameraModifier: Modifier) {
    val face by viewModel.faceState.collectAsStateWithLifecycle()
    val surface by viewModel.surfaceRequest.collectAsStateWithLifecycle()
    val colors = PwdeTheme.colors
    val detected = gesture in face.gesture.active
    SectionTitle("Try \"${gesture.label}\"")
    LevelSlider(
        label = "Sensitivity",
        level = level,
        onLevelChange = { viewModel.setSensitivity(gesture, it) },
        valueLabel = "${levelWord(level)} · fires at ${fmt(GestureThresholds.forGesture(gesture, level))}${gesture.unit()}",
    )
    GradientCard(Modifier.fillMaxWidth()) {
        GestureMeter(gesture, face.gesture.measures[gesture], active = detected)
        Text(
            when {
                detected -> "Detected!"
                face.isGyro -> "Face gestures need the camera, and a gyro joystick deliberately keeps it off."
                face.isSimulated && face.gesture.measures[gesture] == null -> "Demo mode can only simulate tilt, nod and shake."
                !face.hasFace -> "Face the camera to try it."
                else -> "Do the move — the bar passes the white tick when PWDe sees it."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (detected) colors.primary else colors.textMuted,
        )
    }
    DemoModeBanner(face)
    CameraFeed(
        faceState = face,
        surfaceRequest = surface,
        canRequestCamera = viewModel.canRequestCamera,
        onCameraPermissionResult = viewModel::onCameraPermissionResult,
        modifier = cameraModifier.fillMaxWidth(0.6f),
    )
}

private fun FacialGesture.unit() = when (this) {
    FacialGesture.TILT_LEFT, FacialGesture.TILT_RIGHT, FacialGesture.NOD, FacialGesture.SHAKE -> "°"
    else -> ""
}

@Composable
private fun GestureTile(
    gesture: FacialGesture,
    selected: Boolean,
    usedBy: List<GestureAction>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PwdeTheme.colors
    // Both states mean "this move already has a mapping"; only the colour differs.
    val markColor = when {
        selected -> colors.primary
        usedBy.isNotEmpty() -> colors.warning
        else -> null
    }
    BoxWithConstraints(
        modifier
            .heightIn(min = 120.dp)
            .clip(PwdeShapes.card)
            .background(colors.cardBrush)
            .border(if (selected) 3.dp else 1.dp, if (selected) colors.primary else colors.secondary.copy(alpha = 0.5f), PwdeShapes.card)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(12.dp),
    ) {
        // Tiles share a row by weight, so their width varies by screen: size the badge from it.
        val badgeSize = (maxWidth * 0.3f).coerceIn(40.dp, 64.dp).scaled()
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box {
                Box(
                    Modifier.size(badgeSize).clip(CircleShape).background(colors.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Face, contentDescription = null, tint = colors.primary, modifier = Modifier.size(iconSizeFor(badgeSize, 0.55f)))
                }
                // Visible even when the pill below wraps on narrow screens. The pill carries the words.
                if (markColor != null) {
                    CheckBadge(description = null, color = markColor, modifier = Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp))
                }
            }
            Text(gesture.label, style = MaterialTheme.typography.titleMedium, color = colors.text)
            Text(gesture.description, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            when {
                selected -> StatusPill("Selected", icon = Icons.Outlined.CheckCircle)
                usedBy.isNotEmpty() -> StatusPill("Used: ${usedBy.joinToString { it.label }}", color = colors.warning, icon = Icons.Outlined.CheckCircle)
            }
        }
    }
}

private enum class Detail(val label: String) { BASIC("Basic"), ADVANCED("Advanced") }

internal val CURSOR_COMMANDS = listOf(
    voiceCommand("faster", "faster", "speed up"),
    voiceCommand("slower", "slower", "slow down"),
    voiceCommand("advanced", "advanced"),
    voiceCommand("basic", "basic"),
    voiceCommand("recenter", "recenter", "center", "re center"),
)

/** E6–E8 Cursor speed: live camera + pointer; per-direction speeds saved to Room as you change them. */
@Composable
fun CursorSpeedScreen(viewModel: CursorSpeedViewModel, onBack: () -> Unit) {
    var detail by rememberSaveable { mutableStateOf(Detail.BASIC) }
    val tuning by viewModel.tuning.collectAsStateWithLifecycle()
    val face by viewModel.faceState.collectAsStateWithLifecycle()
    val surface by viewModel.surfaceRequest.collectAsStateWithLifecycle()
    VoiceCommandsEffect(CURSOR_COMMANDS) { id ->
        when (id) {
            "faster" -> viewModel.changeOverallSpeed(+1)
            "slower" -> viewModel.changeOverallSpeed(-1)
            "advanced" -> detail = Detail.ADVANCED
            "basic" -> detail = Detail.BASIC
            "recenter" -> viewModel.recenterCursor()
        }
    }
    PwdeScreen(
        title = "Cursor speed",
        subtitle = "How the pointer follows your head. Saved automatically.",
        onBack = onBack,
        voiceHint = "Say \"faster\", \"slower\", \"recenter\" or \"advanced\"",
    ) {
        DemoModeBanner(face)
        // The live pointer is held inside the calibration box while this screen is open.
        val confine = rememberCalibrationOverlay(CalibrationOverlayMode.CONFINE_TO_BOX)
        CameraFeed(
            faceState = face,
            surfaceRequest = surface,
            canRequestCamera = viewModel.canRequestCamera,
            onCameraPermissionResult = viewModel::onCameraPermissionResult,
            modifier = Modifier.align(Alignment.CenterHorizontally).fillMaxWidth().then(confine),
            feedAspectRatio = 16f / 10f,
            overlay = {
                CursorCalibrationOverlay(face.cursor.x, face.cursor.y, face.hasFace, Offset(0.5f, 0.5f))
            },
        )
        PwdeButton(
            "Recenter pointer",
            viewModel::recenterCursor,
            style = ButtonStyle.SECONDARY,
            icon = Icons.Outlined.CenterFocusStrong,
            modifier = Modifier.fillMaxWidth(),
        )
        SegmentedToggle(Detail.entries, detail, { it.label }, { detail = it })
        val t = tuning ?: return@PwdeScreen
        if (detail == Detail.BASIC) {
            LevelSlider("Speed", CursorSpeedViewModel.overallSpeed(t), viewModel::setOverallSpeed)
        } else {
            LevelSlider("Moving up", t.speedUp, { level -> viewModel.update { it.copy(speedUp = level) } })
            LevelSlider("Moving down", t.speedDown, { level -> viewModel.update { it.copy(speedDown = level) } })
            LevelSlider("Moving left", t.speedLeft, { level -> viewModel.update { it.copy(speedLeft = level) } })
            LevelSlider("Moving right", t.speedRight, { level -> viewModel.update { it.copy(speedRight = level) } })
        }
        LevelSlider("Smoothing", t.smoothing, { level -> viewModel.update { it.copy(smoothing = level) } })
        InfoNote("More smoothing steadies a shaky pointer but makes it a little slower to react.")
    }
}

internal val JOYSTICK_COMMANDS = listOf(
    voiceCommand("bigger", "bigger", "larger", "increase size"),
    voiceCommand("smaller", "smaller", "decrease size"),
    voiceCommand("more_sensitive", "more sensitive", "increase sensitivity"),
    voiceCommand("less_sensitive", "less sensitive", "decrease sensitivity"),
    voiceCommand("set_center", "set center", "center here", "set centre"),
    voiceCommand("advanced", "advanced"),
    voiceCommand("basic", "basic"),
)

/** E9/E10 Joystick: live head-tilt joystick with size, sensitivity, dead zone and center. */
@Composable
fun JoystickScreen(viewModel: JoystickViewModel, onBack: () -> Unit) {
    var detail by rememberSaveable { mutableStateOf(Detail.BASIC) }
    val tuning by viewModel.tuning.collectAsStateWithLifecycle()
    val smoothing by viewModel.smoothing.collectAsStateWithLifecycle()
    val face by viewModel.faceState.collectAsStateWithLifecycle()
    val surface by viewModel.surfaceRequest.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val source by viewModel.source.collectAsStateWithLifecycle()
    val steering = source ?: JoystickSource.HEAD
    val gyro = steering == JoystickSource.GYRO
    val colors = PwdeTheme.colors
    fun step(level: Int, delta: Int) = (level + delta).coerceIn(MIN_LEVEL, MAX_LEVEL)
    VoiceCommandsEffect(JOYSTICK_COMMANDS) { id ->
        when (id) {
            "bigger" -> viewModel.update { it.copy(size = step(it.size, +1)) }
            "smaller" -> viewModel.update { it.copy(size = step(it.size, -1)) }
            "more_sensitive" -> viewModel.update { it.copy(sensitivity = step(it.sensitivity, +1)) }
            "less_sensitive" -> viewModel.update { it.copy(sensitivity = step(it.sensitivity, -1)) }
            "set_center" -> viewModel.setCenterHere()
            "advanced" -> detail = Detail.ADVANCED
            "basic" -> detail = Detail.BASIC
        }
    }
    PwdeScreen(
        title = "Joystick",
        subtitle = if (gyro) "Tilt your phone to steer. Saved automatically." else "Tilt your head to steer. Saved automatically.",
        onBack = onBack,
        voiceHint = "Say \"increase size\", \"increase sensitivity\" or \"set center\"",
    ) {
        DemoModeBanner(face)
        // A joystick is steered by tilting, so the roaming pointer is just noise here: it is hidden
        // for as long as this screen is open, and comes back when the user leaves.
        rememberCalibrationOverlay(CalibrationOverlayMode.HIDE_POINTER)
        val sizeLevel = tuning?.size ?: 5
        val sizeFraction = 0.55f + 0.45f * (sizeLevel - MIN_LEVEL) / (MAX_LEVEL - MIN_LEVEL).toFloat()
        CameraFeed(
            faceState = face,
            surfaceRequest = surface,
            canRequestCamera = viewModel.canRequestCamera,
            onCameraPermissionResult = viewModel::onCameraPermissionResult,
            modifier = Modifier.align(Alignment.CenterHorizontally).fillMaxWidth(),
            feedAspectRatio = 16f / 10f,
            overlay = {
                JoystickView(
                    face.joystick,
                    Modifier.fillMaxWidth(sizeFraction * 0.5f).align(Alignment.Center),
                    active = face.hasFace,
                )
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(PwdeTheme.spacing.itemGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusPill(face.joystick.direction.label, icon = Icons.Outlined.Gamepad)
                    // Says out loud which thing is steering, so "gyro" and "head" are never
                    // confused when the joystick looks identical either way.
                    StatusPill(steering.label, icon = steering.icon())
                }
            },
        )
        PwdeButton("Set center here", viewModel::setCenterHere, icon = Icons.Outlined.CenterFocusStrong, modifier = Modifier.fillMaxWidth())
        message?.let { Text(it.text, style = MaterialTheme.typography.bodyMedium, color = if (it.isError) colors.warning else colors.primary) }
        SegmentedToggle(Detail.entries, detail, { it.label }, { detail = it })
        val t = tuning ?: return@PwdeScreen
        if (detail == Detail.BASIC) {
            LevelSlider("Size", t.size, { level -> viewModel.update { it.copy(size = level) } })
            LevelSlider("Sensitivity", t.sensitivity, { level -> viewModel.update { it.copy(sensitivity = level) } })
        } else {
            LevelSlider("Dead zone", t.deadZone, { level -> viewModel.update { it.copy(deadZone = level) } })
            InfoNote(
                "A bigger dead zone ignores small " + (if (gyro) "phone movements" else "head movements") +
                    ", so the joystick doesn't drift while you rest.",
            )
            val smoothingLevel = smoothing ?: return@PwdeScreen
            LevelSlider("Smoothing", smoothingLevel, viewModel::setSmoothing)
            InfoNote("More smoothing steadies a shaky stick but makes it a little slower to react. Shared with the pointer.")
            if (gyro) {
                InfoNote(
                    "Gyro tracking keeps no saved center: however you are holding the phone is straight ahead, " +
                        "and \"Set center here\" re-takes that without touching the head's saved center.",
                )
            } else {
                PwdeButton("Reset center to straight ahead", viewModel::resetCenter, style = ButtonStyle.SECONDARY, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
