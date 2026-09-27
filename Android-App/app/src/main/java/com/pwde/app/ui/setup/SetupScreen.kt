package com.pwde.app.ui.setup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Accessible
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pwde.app.accessibility.PwdeAccessibilityService
import com.pwde.app.data.gabai.Axis
import com.pwde.app.data.model.CursorTuning
import com.pwde.app.data.model.MAX_LEVEL
import com.pwde.app.data.model.MIN_LEVEL
import com.pwde.app.data.prefs.AccessibilityNeed
import com.pwde.app.data.prefs.ColorSchemeOption
import com.pwde.app.data.prefs.LayoutMode
import com.pwde.app.data.prefs.TextSizeOption
import com.pwde.app.ui.components.ButtonStyle
import com.pwde.app.ui.components.CameraFeed
import com.pwde.app.ui.components.CalibrationOverlayMode
import com.pwde.app.ui.components.DemoModeBanner
import com.pwde.app.ui.components.FooterActions
import com.pwde.app.ui.components.GradientCard
import com.pwde.app.ui.components.InfoNote
import com.pwde.app.ui.components.LevelSlider
import com.pwde.app.ui.components.OptionCard
import com.pwde.app.ui.components.OptionKind
import com.pwde.app.ui.components.PwdeButton
import com.pwde.app.ui.components.PwdeScreen
import com.pwde.app.ui.components.SectionTitle
import com.pwde.app.ui.components.StatusPill
import com.pwde.app.ui.components.StepProgress
import com.pwde.app.ui.components.VoiceCommandsEffect
import com.pwde.app.ui.components.rememberCalibrationOverlay
import com.pwde.app.ui.components.voiceCommand
import com.pwde.app.ui.theme.MinTouchTarget
import com.pwde.app.ui.theme.PwdeShapes
import com.pwde.app.ui.theme.PwdeTheme
import com.pwde.app.ui.theme.colorsFor
import kotlin.math.hypot
import kotlin.math.roundToInt

private val SETUP_COMMANDS = listOf(
    voiceCommand("continue", "continue", "next", "finish"),
    voiceCommand("skip", "skip"),
    voiceCommand("bigger", "bigger", "larger"),
    voiceCommand("smaller", "smaller"),
) + AccessibilityNeed.entries.map { voiceCommand("need:${it.name}", it.label) }

private val PERMISSION_COMMANDS = listOf(
    voiceCommand("allow", "allow"),
    voiceCommand("accessibility", "allow accessibility service", "accessibility service", "accessibility"),
    voiceCommand("app_settings", "open app settings"),
)

/** Just the axis-tuning phrases; "next"/"continue" is handled once, by [SETUP_COMMANDS]. */
private val CURSOR_CALIBRATION_COMMANDS = listOf(
    voiceCommand("faster", "faster", "more"),
    voiceCommand("slower", "slower", "less"),
    voiceCommand("recenter", "recenter", "center"),
)

@Composable
fun SetupScreen(viewModel: SetupViewModel, onExit: () -> Unit, onFinished: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val onCursorStep = state.step == SetupStep.CURSOR_CALIBRATION
    LaunchedEffect(state.finished) { if (state.finished) onFinished() }
    VoiceCommandsEffect(SETUP_COMMANDS) { id ->
        when {
            id == "continue" -> if (onCursorStep) viewModel.axisDone() else viewModel.continueStep()
            id == "skip" -> viewModel.skipStep()
            id == "bigger" -> TextSizeOption.entries.getOrNull(state.textSize.ordinal + 1)?.let(viewModel::setTextSize)
            id == "smaller" -> TextSizeOption.entries.getOrNull(state.textSize.ordinal - 1)?.let(viewModel::setTextSize)
            id.startsWith("need:") -> viewModel.toggleNeed(AccessibilityNeed.valueOf(id.removePrefix("need:")))
        }
    }
    BackHandler { if (!viewModel.back()) onExit() }
    if (!state.loaded) return

    // The permissions step asks Android itself as soon as it opens, and keeps the app on that step
    // until camera and microphone are allowed, so its launchers live up here where they outlive the
    // step's own state. Two single-permission requests, not one combined dialog: the camera has to
    // come first on its own so the step after this one boots a head-tracked pointer rather than gyro.
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.setPermissions(cameraGranted = granted, micGranted = viewModel.state.value.micGranted)
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.setPermissions(cameraGranted = viewModel.state.value.cameraGranted, micGranted = granted)
    }
    val requestCamera = remember(cameraLauncher) { { cameraLauncher.launch(Manifest.permission.CAMERA) } }
    val requestMic = remember(micLauncher) { { micLauncher.launch(Manifest.permission.RECORD_AUDIO) } }

    PwdeTheme(colorScheme = state.colorScheme, textSize = state.textSize, layoutMode = state.layoutMode) {
        val (title, subtitle, hint) = when (state.step) {
            SetupStep.APPEARANCE -> Triple(
                "How it looks",
                "Choose colours, text size and layout. The screen updates as you go.",
                "Say \"bigger\" or \"smaller\"",
            )
            SetupStep.NEEDS -> Triple(
                "What do you need help with?",
                "Pick all that apply. You can change this any time.",
                "Say an option's name to tick it",
            )
            SetupStep.PERMISSIONS -> Triple(
                "Allow camera and microphone",
                "PWDe asks Android for the camera first, then the microphone, as soon as this screen opens. Both are needed for head and voice control.",
                "Say \"allow\" to ask again",
            )
            SetupStep.CURSOR_CALIBRATION -> Triple(
                "Calibrate your cursor",
                "GabAI walks you through moving the pointer in each direction so it matches how you move your head.",
                "Say \"faster\", \"slower\", \"recenter\" or \"next\"",
            )
        }
        val onLastAxis = state.axis == Axis.entries.last()
        PwdeScreen(
            title = title,
            subtitle = subtitle,
            onBack = { if (!viewModel.back()) onExit() },
            voiceHint = hint,
            footer = {
                FooterActions(
                    primaryText = when {
                        onCursorStep && !onLastAxis -> "Next"
                        state.isLastStep -> "Finish"
                        else -> "Continue"
                    },
                    onPrimary = if (onCursorStep) viewModel::axisDone else viewModel::continueStep,
                    primaryIcon = if (state.isLastStep && (!onCursorStep || onLastAxis)) Icons.Outlined.Check else null,
                    secondaryText = if (state.permissionBlocked) null else "Skip",
                    onSecondary = if (state.permissionBlocked) null else viewModel::skipStep,
                    primaryEnabled = !state.permissionBlocked,
                )
            },
        ) {
            if (state.steps.size > 1) {
                StepProgress(step = state.stepIndex + 1, total = state.steps.size, label = state.step.label)
            }
            when (state.step) {
                SetupStep.PERMISSIONS -> PermissionsStep(
                    onGranted = viewModel::setPermissions,
                    requestCamera = requestCamera,
                    requestMic = requestMic,
                )
                SetupStep.APPEARANCE -> AppearanceStep(state, viewModel)
                SetupStep.CURSOR_CALIBRATION -> CursorCalibrationStep(state, viewModel)
                SetupStep.NEEDS -> NeedsStep(state.needs, viewModel::toggleNeed)
            }
        }
    }
}

@Composable
private fun NeedsStep(selected: Set<AccessibilityNeed>, onToggle: (AccessibilityNeed) -> Unit) {
    AccessibilityNeed.entries.forEach { need ->
        OptionCard(
            title = need.label,
            description = need.description,
            selected = need in selected,
            onClick = { onToggle(need) },
            icon = need.icon(),
        )
    }
}

private fun AccessibilityNeed.icon(): ImageVector = when (this) {
    AccessibilityNeed.MOVEMENT -> Icons.AutoMirrored.Outlined.Accessible
    AccessibilityNeed.SEEING -> Icons.Outlined.Visibility
    AccessibilityNeed.HEARING -> Icons.Outlined.Hearing
    AccessibilityNeed.SPEAKING -> Icons.Outlined.RecordVoiceOver
    AccessibilityNeed.OTHER -> Icons.Outlined.MoreHoriz
}

@Composable
private fun AppearanceStep(state: SetupUiState, viewModel: SetupViewModel) {
    SectionTitle("Color scheme")
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ColorSchemeOption.entries.forEach { option ->
            ColorSchemeTile(option, option == state.colorScheme, { viewModel.setColorScheme(option) }, Modifier.weight(1f))
        }
    }

    TextSizeSlider(state.textSize, viewModel::setTextSize)
    GradientCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Aa", style = MaterialTheme.typography.headlineMedium, color = PwdeTheme.colors.text)
            Column {
                Text("Live preview", style = MaterialTheme.typography.titleMedium, color = PwdeTheme.colors.text)
                Text("This is how text will look.", style = MaterialTheme.typography.bodyMedium, color = PwdeTheme.colors.textMuted)
            }
        }
    }

    SectionTitle("Layout")
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(PwdeTheme.spacing.itemGap)) {
        LayoutMode.entries.forEach { mode ->
            OptionCard(
                title = mode.label,
                description = mode.description,
                selected = mode == state.layoutMode,
                onClick = { viewModel.setLayoutMode(mode) },
                kind = OptionKind.RADIO,
            )
        }
    }
}

/** Snaps to each [TextSizeOption]; the draft theme around the screen rescales as it moves. */
@Composable
private fun TextSizeSlider(selected: TextSizeOption, onSelect: (TextSizeOption) -> Unit) {
    val colors = PwdeTheme.colors
    val sizes = TextSizeOption.entries
    val valueLabel = "${selected.label} · ${selected.percentLabel}"
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Text size", style = MaterialTheme.typography.titleMedium, color = colors.text, modifier = Modifier.weight(1f))
            Text(valueLabel, style = MaterialTheme.typography.labelMedium, color = colors.primary)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("A", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            Slider(
                value = selected.ordinal.toFloat(),
                onValueChange = { onSelect(sizes[it.roundToInt().coerceIn(0, sizes.lastIndex)]) },
                valueRange = 0f..sizes.lastIndex.toFloat(),
                steps = sizes.size - 2,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = MinTouchTarget)
                    .semantics {
                        contentDescription = "Text size"
                        stateDescription = valueLabel
                    },
                colors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary),
            )
            Text("A", style = MaterialTheme.typography.titleLarge, color = colors.text)
        }
    }
}

@Composable
private fun ColorSchemeTile(option: ColorSchemeOption, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val swatch = colorsFor(option)
    val colors = PwdeTheme.colors
    Column(
        modifier
            .heightIn(min = 88.dp)
            .clip(PwdeShapes.button)
            .background(swatch.background)
            .border(if (selected) 3.dp else 1.dp, if (selected) colors.primary else colors.textMuted, PwdeShapes.button)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(16.dp).clip(CircleShape).background(swatch.primary))
            Box(Modifier.size(16.dp).clip(CircleShape).background(swatch.secondary))
        }
        Text(option.label, style = MaterialTheme.typography.labelSmall, color = swatch.text, maxLines = 2)
        if (selected) Icon(Icons.Outlined.Check, contentDescription = null, tint = swatch.primary, modifier = Modifier.size(16.dp))
    }
}

/**
 * B5 · Permissions — the single place setup asks for everything Android has to grant: the camera,
 * the microphone and the accessibility service ("Use PWDe"), which together are what turns PWDe on.
 *
 * The camera is asked for **first** and on its own, so the cursor is already head-tracked and never
 * falls back to gyro when the calibration step boots it; the microphone follows. Camera and
 * microphone are required — the step reports them with [onGranted] and setup refuses to continue
 * while either is off — while the accessibility service is asked for on the same screen but stays
 * optional, since Android only lets the user flip "Use PWDe" in its own Settings.
 */
@Composable
private fun PermissionsStep(
    onGranted: (camera: Boolean, mic: Boolean) -> Unit,
    requestCamera: () -> Unit,
    requestMic: () -> Unit,
) {
    val context = LocalContext.current
    var camera by remember { mutableStateOf(context.isGranted(Manifest.permission.CAMERA)) }
    var mic by remember { mutableStateOf(context.isGranted(Manifest.permission.RECORD_AUDIO)) }
    var accessibility by remember { mutableStateOf(PwdeAccessibilityService.isEnabled(context)) }
    /** Android has already shown its dialog for this visit, so asking again may be silently refused. */
    var asked by rememberSaveable { mutableStateOf(false) }
    // Ask as soon as the step opens. The camera goes first, alone: the calibration step that
    // follows boots the pointer, and it needs a camera to find a face with rather than tilt.
    LaunchedEffect(Unit) {
        when {
            !camera -> {
                asked = true
                requestCamera()
            }
            !mic -> {
                asked = true
                requestMic()
            }
        }
    }
    // Once the camera has been dealt with, the microphone is the next thing to ask for.
    LaunchedEffect(camera) {
        if (camera && !mic) {
            asked = true
            requestMic()
        }
    }
    LifecycleResumeEffect(Unit) {
        camera = context.isGranted(Manifest.permission.CAMERA)
        mic = context.isGranted(Manifest.permission.RECORD_AUDIO)
        accessibility = PwdeAccessibilityService.isEnabled(context)
        onGranted(camera, mic)
        onPauseOrDispose { }
    }
    // Every change in either switch re-reports, so the gate (and the footer) stay in step.
    LaunchedEffect(camera, mic) { onGranted(camera, mic) }
    val openAccessibilitySettings = { context.startActivity(PwdeAccessibilityService.settingsIntent()) }
    val openAppSettings = {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
    VoiceCommandsEffect(PERMISSION_COMMANDS) { id ->
        when (id) {
            "allow" -> when {
                !camera -> requestCamera()
                !mic -> requestMic()
                !accessibility -> openAccessibilitySettings()
                else -> Unit
            }
            "accessibility" -> if (!accessibility) openAccessibilitySettings()
            "app_settings" -> openAppSettings()
        }
    }

    OptionCard(
        title = "Camera",
        description = if (camera) "Allowed — the pointer follows your head" else "Asked for first. The pointer follows your head, not the phone's tilt.",
        selected = camera,
        onClick = { if (!camera) requestCamera() },
        icon = Icons.Outlined.PhotoCamera,
    )
    OptionCard(
        title = "Microphone",
        description = if (mic) "Allowed" else "Hears voice commands like \"next\" or \"pause\".",
        selected = mic,
        onClick = { if (!mic) requestMic() },
        icon = Icons.Outlined.Mic,
    )
    OptionCard(
        title = "Accessibility service",
        description = if (accessibility) "Allowed — PWDe is on" else "Also called \"Use PWDe\". Lets PWDe press buttons in games",
        selected = accessibility,
        onClick = { if (!accessibility) openAccessibilitySettings() },
        icon = Icons.AutoMirrored.Outlined.Accessible,
    )
    InfoNote("Your face never leaves this phone. Nothing is recorded or uploaded.", icon = Icons.Outlined.Shield)
    if (camera && mic) {
        StatusPill("Camera and microphone allowed", color = PwdeTheme.colors.success, icon = Icons.Outlined.Check)
    } else {
        InfoNote("PWDe can't follow your head or hear commands without both. Allow camera and microphone to continue.")
        if (asked) {
            PwdeButton(
                "Open app settings",
                openAppSettings,
                style = ButtonStyle.SECONDARY,
                icon = Icons.Outlined.Settings,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    if (!accessibility) {
        InfoNote(
            "Turn the accessibility service on too, or PWDe's controls can't reach your games. " +
                "It opens Android Settings — come back and this screen updates by itself.",
            icon = Icons.Outlined.Settings,
        )
    }
}

private fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun axisSays(axis: Axis) = when (axis) {
    Axis.UP -> "Look up to move the pointer onto the top target. Change the speed until it feels comfortable."
    Axis.DOWN -> "Now look down to reach the bottom target."
    Axis.LEFT -> "Turn your head left to reach the left target."
    Axis.RIGHT -> "And right, to the right target."
    Axis.DIAGONAL -> "Last one: move to a corner target. If the pointer shakes, add smoothing; if it lags, take some away."
}

/**
 * B7 · Cursor calibration. The same axis-by-axis walkthrough GabAI uses when building a new
 * calibration profile, folded into Setup so a new user's pointer is tuned before they ever open
 * GabAI. Each adjustment is written straight to the working controls, exactly as GabAI's does.
 */
@Composable
private fun CursorCalibrationStep(state: SetupUiState, viewModel: SetupViewModel) {
    val axis = state.axis
    val face by viewModel.faceState.collectAsStateWithLifecycle()
    val surface by viewModel.surfaceRequest.collectAsStateWithLifecycle()
    val cursor = state.cursor
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
    VoiceCommandsEffect(CURSOR_CALIBRATION_COMMANDS) { id ->
        when (id) {
            "faster" -> set((level + 1).coerceAtMost(MAX_LEVEL))
            "slower" -> set((level - 1).coerceAtLeast(MIN_LEVEL))
            "recenter" -> viewModel.recenterCursor()
        }
    }
    // Boot the camera for this step. Collecting [SetupViewModel.faceState] below is what turns
    // tracking on, and refreshPreview() restarts it in case the session began before the camera was
    // allowed (the accessibility service subscribes from the moment "Use PWDe" is switched on).
    LifecycleResumeEffect(Unit) {
        viewModel.refreshPreview()
        onPauseOrDispose { }
    }
    InfoNote(axisSays(axis))
    DemoModeBanner(face)
    // While this step is open the live pointer is held inside the calibration box, and released
    // again the moment the step is left.
    val confine = rememberCalibrationOverlay(CalibrationOverlayMode.CONFINE_TO_BOX)
    CameraFeed(
        faceState = face,
        surfaceRequest = surface,
        canRequestCamera = viewModel.canRequestCamera,
        onCameraPermissionResult = viewModel::onCameraPermissionResult,
        modifier = Modifier.fillMaxWidth().then(confine),
        feedAspectRatio = 16f / 10f,
        overlay = { CursorCalibrationOverlay(face.cursor.x, face.cursor.y, face.hasFace, axis) },
    )
    PwdeButton(
        "Recenter pointer",
        viewModel::recenterCursor,
        style = ButtonStyle.SECONDARY,
        icon = Icons.Outlined.CenterFocusStrong,
        modifier = Modifier.fillMaxWidth(),
    )
    LevelSlider(if (axis == Axis.DIAGONAL) "Smoothing" else "Speed moving ${axis.label.lowercase()}", level, ::set)
    InfoNote(
        "Smiling is already set up as your Select gesture and opening your mouth as Recenter, so " +
            "you can press what the pointer is on and bring it back to the middle. Change either " +
            "any time in Controls, then Gestures.",
        icon = Icons.Outlined.CheckCircle,
    )
}

/** Where the target sits for each direction. */
private fun targetFor(axis: Axis): Offset = when (axis) {
    Axis.UP -> Offset(0.5f, 0.1f)
    Axis.DOWN -> Offset(0.5f, 0.9f)
    Axis.LEFT -> Offset(0.1f, 0.5f)
    Axis.RIGHT -> Offset(0.9f, 0.5f)
    Axis.DIAGONAL -> Offset(0.9f, 0.1f)
}

@Composable
private fun BoxScope.CursorCalibrationOverlay(x: Float, y: Float, active: Boolean, axis: Axis) {
    val colors = PwdeTheme.colors
    val target = targetFor(axis)
    val onTarget = hypot(x - target.x, y - target.y) < 0.1f
    Canvas(
        Modifier
            .fillMaxSize()
            .semantics {
                contentDescription = if (onTarget) "Pointer is on the target" else "Pointer at ${(x * 100).toInt()}% across, ${(y * 100).toInt()}% down"
            },
    ) {
        val t = Offset(target.x * size.width, target.y * size.height)
        drawCircle(colors.primary.copy(alpha = if (onTarget) 0.5f else 0.2f), radius = 26.dp.toPx(), center = t)
        drawCircle(colors.primary, radius = 26.dp.toPx(), center = t, style = Stroke(3.dp.toPx()))
        drawCircle(if (active) colors.secondary else colors.textMuted, radius = 12.dp.toPx(), center = Offset(x * size.width, y * size.height))
    }
    if (onTarget) {
        StatusPill(
            "On target!",
            icon = Icons.Outlined.CheckCircle,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
        )
    }
}