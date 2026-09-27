package com.pwde.app.ui.gameplay

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pwde.app.BuildConfig
import com.pwde.app.data.model.FaceOutputMode
import com.pwde.app.data.model.MappedButton
import com.pwde.app.data.model.NavigationMode
import com.pwde.app.data.model.TriggerType
import com.pwde.app.sensors.face.JoystickState
import com.pwde.app.sensors.voice.InGameVoiceState
import com.pwde.app.sensors.face.FaceState
import com.pwde.app.sensors.face.TrackingStatus
import com.pwde.app.ui.components.ButtonStyle
import com.pwde.app.ui.components.DemoModeBanner
import com.pwde.app.ui.components.overlayDrawsPointer
import com.pwde.app.ui.components.ControlsList
import com.pwde.app.ui.components.JoystickView
import com.pwde.app.ui.components.LockOrientation
import com.pwde.app.ui.components.PwdeButton
import com.pwde.app.ui.components.PwdeIconButton
import com.pwde.app.ui.components.StatusPill
import com.pwde.app.ui.components.rememberCameraPermissionRequest
import com.pwde.app.ui.theme.PwdeShapes
import com.pwde.app.ui.theme.PwdeTheme

/**
 * D4/D5 Playing view: the game profile's screenshot (or a simulated arena) under a live PWDe
 * overlay — in-game voice state, detected gesture, cursor or joystick, and which mapped button
 * each voice command, gesture or joystick move just pressed. Back (touch or voice), "exit", or an
 * Exit gesture returns to the menu. The eye button (or "hide overlay") hides the status UI
 * while keeping the mapped buttons and pointer. The real game isn't launched.
 */
@Composable
fun PlayingScreen(viewModel: GameplayViewModel, onExit: () -> Unit) {
    val face by viewModel.faceState.collectAsStateWithLifecycle()
    val voice by viewModel.voice.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val paused by viewModel.paused.collectAsStateWithLifecycle()
    val lastEvent by viewModel.lastEvent.collectAsStateWithLifecycle()
    val overlayHidden by viewModel.overlayHidden.collectAsStateWithLifecycle()
    val controlsShown by viewModel.controlsShown.collectAsStateWithLifecycle()
    val navigationMode by viewModel.navigationMode.collectAsStateWithLifecycle()
    val centerLocked by viewModel.centerLocked.collectAsStateWithLifecycle()
    // The preview uses PWDe's one overlay for the pointer, exactly as it is over the real game — the
    // app-wide claim is made by PwdeNavHost. It only falls back to drawing its own when the
    // accessibility service is off, so there is never a second cursor that cannot press anything.
    val overlayDrawn = overlayDrawsPointer()
    val requestCamera = rememberCameraPermissionRequest { viewModel.onCameraPermissionResult() }
    val colors = PwdeTheme.colors
    BackHandler(onBack = onExit)
    // Held the way the game is played, so the mapped buttons sit where they do in the game.
    LockOrientation(viewModel.game?.landscape ?: true)
    LaunchedEffect(viewModel) { viewModel.exitRequests.collect { onExit() } }
    // The in-game voice engine holds the mic only while this screen is visible.
    LifecycleStartEffect(viewModel) {
        viewModel.onScreenStarted()
        onStopOrDispose { viewModel.onScreenStopped() }
    }
    LaunchedEffect(face.joystick.direction) { viewModel.onJoystickDirection(face.joystick.direction) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF1B2A1E))) {
        val shot = ui.screenshot
        if (shot != null) {
            Image(shot, contentDescription = "${viewModel.game?.displayName ?: "Game"} screenshot", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            SimulatedBackground()
        }
        val active = face.hasFace && !paused
        val joystickMode = face.outputMode == FaceOutputMode.JOYSTICK
        // In joystick mode the game's movement joystick (if mapped) shows the head joystick where it really is.
        val hasMovementStick = ui.buttons.any { it.trigger?.type == TriggerType.MOVEMENT }
        ProfileButtons(ui.buttons, lastEvent, shot, stick = face.joystick.takeIf { joystickMode }, stickActive = active)
        val screenWidth = maxWidth
        if (!joystickMode && !overlayDrawn) {
            CursorLayer(face, active, lastEvent)
        } else if (joystickMode && shot == null) {
            // Only the plain simulated arena gets a character to walk around.
            SimulatedAvatar(face, active)
        }

        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Hiding keeps the mapped buttons, pointer and joystick; only the status UI goes.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (overlayHidden) {
                    Spacer(Modifier.weight(1f))
                } else {
                    StatusPill(
                        (if (face.isGyro) "GYRO" else "SIMULATED") +
                            " — ${viewModel.game?.displayName ?: "preview"}" + (ui.profile?.let { " · ${it.profileName}" } ?: ""),
                        color = colors.warning,
                        modifier = Modifier.weight(1f).background(colors.background.copy(alpha = 0.8f), PwdeShapes.pill),
                    )
                    GameVoiceIndicator(voice)
                }
                OverlayToggle(overlayHidden) { viewModel.setOverlayHidden(!overlayHidden) }
            }
            if (!overlayHidden) ui.calibrationName?.let {
                StatusPill("Calibration: $it", modifier = Modifier.background(colors.background.copy(alpha = 0.8f), PwdeShapes.pill))
            }
            if (!overlayHidden) DemoModeBanner(face, Modifier.background(colors.background.copy(alpha = 0.85f), PwdeShapes.button))
            if (!overlayHidden && !overlayDrawn) {
                // Honest about it: the pointer is drawn by the overlay, and without "Use PWDe" on there
                // is nothing to carry a press out. A silent, dead pointer is the bug this screen had.
                StatusPill(
                    "Turn on \"Use PWDe\" in Android Settings to press things on screen",
                    color = colors.warning,
                    modifier = Modifier.background(colors.background.copy(alpha = 0.85f), PwdeShapes.pill),
                )
            }
            if (!overlayHidden && face.isSimulated && viewModel.canRequestCamera) {
                PwdeButton("Turn on camera", requestCamera, style = ButtonStyle.SECONDARY, icon = Icons.Outlined.Videocam)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (joystickMode && !hasMovementStick) {
                    JoystickView(
                        face.joystick,
                        Modifier.align(Alignment.BottomStart).size(screenWidth * face.joystick.radius * 2f),
                        active = active,
                    )
                }
            }
            if (controlsShown) ControlsList(ui.buttons, onClose = { viewModel.setControlsShown(false) })
            if (!overlayHidden) {
                if (voice.usesTextFallback) GameCommandField(voice.availability.label, viewModel::submitText)
                OverlayPanel(face, navigationMode, voiceLine(voice), lastEvent, paused, centerLocked, viewModel::togglePause, onExit)
            }
        }
    }
}

/** Hides or shows the status UI so the screenshot underneath can be checked. */
@Composable
private fun OverlayToggle(hidden: Boolean, onClick: () -> Unit) {
    PwdeIconButton(
        if (hidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
        if (hidden) "Show overlay" else "Hide overlay",
        onClick,
        modifier = Modifier.background(PwdeTheme.colors.background.copy(alpha = 0.8f), PwdeShapes.button),
    )
}

private fun voiceLine(voice: InGameVoiceState): String = when {
    voice.lastText != null -> "Heard: \"${voice.lastText}\""
    else -> "Voice: say \"pause\", \"show controls\", \"close overlay\", \"open overlay\", \"exit\" or a button's command"
}

/** In-game voice status (the app-wide voice bar is paused while the game has the mic). */
@Composable
private fun GameVoiceIndicator(state: InGameVoiceState) {
    val colors = PwdeTheme.colors
    val (text, color) = when {
        state.usesTextFallback -> "Game voice: typing" to colors.warning
        state.listening -> "Game voice: listening…" to colors.primary
        state.running -> "Game voice: on" to colors.textMuted
        else -> "Game voice: off" to colors.textMuted
    }
    StatusPill(
        text,
        modifier = Modifier
            .background(colors.background.copy(alpha = 0.8f), PwdeShapes.pill)
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = color,
        icon = if (state.listening) Icons.Outlined.Mic else Icons.Outlined.MicOff,
    )
}

/** Typed game commands when the mic can't be used — the same commands the voice engine knows. */
@Composable
private fun GameCommandField(reason: String, onSend: (String) -> Unit) {
    val colors = PwdeTheme.colors
    var text by rememberSaveable { mutableStateOf("") }
    val send = {
        if (text.isNotBlank()) {
            onSend(text)
            text = ""
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(PwdeShapes.button)
            .background(colors.background.copy(alpha = 0.92f))
            .border(1.5.dp, colors.warning, PwdeShapes.button)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("$reason — type a game command instead", style = MaterialTheme.typography.labelMedium, color = colors.warning)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("e.g. \"pause\" or a button's command", color = colors.textMuted) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = colors.text, unfocusedTextColor = colors.text),
            )
            PwdeButton("Send", { send() })
        }
    }
}

/**
 * The game profile's mapped buttons; the one just pressed lights up. With [stick] (joystick mode),
 * the movement joystick is drawn as the head joystick at the reach PWDe drags it in the real game.
 */
@Composable
private fun ProfileButtons(
    buttons: List<MappedButton>,
    lastEvent: OverlayEvent?,
    screenshot: ImageBitmap?,
    stick: JoystickState?,
    stickActive: Boolean,
) {
    if (buttons.isEmpty()) return
    val colors = PwdeTheme.colors
    val flash = remember { Animatable(0f) }
    val pressedId = lastEvent?.takeIf { it.kind == OverlayEvent.Kind.BUTTON }?.buttonId
    LaunchedEffect(lastEvent?.id) {
        if (pressedId != null) {
            flash.snapTo(1f)
            flash.animateTo(0f, tween(600))
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenshotAspect = screenshot?.let { it.width.toFloat() / it.height } ?: (16f / 9f)
        val containerAspect = maxWidth / maxHeight
        val viewportWidth: Dp
        val viewportHeight: Dp
        val viewportLeft: Dp
        val viewportTop: Dp
        if (containerAspect > screenshotAspect) {
            viewportHeight = maxHeight
            viewportWidth = maxHeight * screenshotAspect
            viewportLeft = (maxWidth - viewportWidth) / 2
            viewportTop = 0.dp
        } else {
            viewportWidth = maxWidth
            viewportHeight = maxWidth / screenshotAspect
            viewportLeft = 0.dp
            viewportTop = (maxHeight - viewportHeight) / 2
        }
        if (BuildConfig.DEBUG) {
            val density = LocalDensity.current
            val viewportWidthPx = with(density) { viewportWidth.roundToPx() }
            val viewportHeightPx = with(density) { viewportHeight.roundToPx() }
            val viewportLeftPx = with(density) { viewportLeft.roundToPx() }
            val viewportTopPx = with(density) { viewportTop.roundToPx() }
            Text(
                "Mapping viewport ${screenshot?.width ?: 0}x${screenshot?.height ?: 0} -> " +
                    "${viewportWidthPx}x${viewportHeightPx} px @ " +
                    "(${viewportLeftPx}, ${viewportTopPx})",
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.7f), PwdeShapes.pill)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }
        val diameter = 48.dp
        Box(Modifier.offset(viewportLeft, viewportTop).size(viewportWidth, viewportHeight)) {
            buttons.forEach { button ->
                if (stick != null && button.trigger?.type == TriggerType.MOVEMENT) {
                    val size = minOf(viewportWidth, viewportHeight) * stick.radius * 2f + diameter
                    JoystickView(
                        stick,
                        Modifier
                            .offset(x = viewportWidth * button.x - size / 2, y = viewportHeight * button.y - size / 2)
                            .size(size),
                        active = stickActive,
                    )
                    return@forEach
                }
                val pressed = button.id == pressedId && flash.value > 0f
                Box(
                    Modifier
                        .offset(x = viewportWidth * button.x - diameter / 2, y = viewportHeight * button.y - diameter / 2)
                        .size(diameter),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(
                        Modifier
                            .size(diameter)
                            .clip(CircleShape)
                            .background(if (pressed) colors.primary.copy(alpha = 0.4f + 0.5f * flash.value) else colors.secondary.copy(alpha = 0.3f))
                            .border(3.dp, if (pressed) colors.primary else Color.White.copy(alpha = 0.8f), CircleShape)
                            .semantics { contentDescription = "${button.label}: ${button.trigger?.describe() ?: "no trigger"}" },
                    )
                    Text(
                        button.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 1,
                        modifier = Modifier
                            .offset(y = diameter)
                            .background(Color.Black.copy(alpha = 0.6f), PwdeShapes.pill)
                            .padding(horizontal = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun OverlayPanel(
    face: FaceState,
    navigationMode: NavigationMode,
    voiceText: String,
    lastEvent: OverlayEvent?,
    paused: Boolean,
    centerLocked: Boolean,
    onTogglePause: () -> Unit,
    onExit: () -> Unit,
) {
    val colors = PwdeTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(PwdeShapes.card)
            .background(colors.background.copy(alpha = 0.92f))
            .border(2.dp, colors.primary, PwdeShapes.card)
            .alpha(if (navigationMode == NavigationMode.GAME) 0.68f else 1f)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("PWDe", style = MaterialTheme.typography.titleLarge, color = colors.primary, modifier = Modifier.weight(1f))
            StatusPill(
                "${navigationMode.label} mode",
                color = if (navigationMode == NavigationMode.GAME) colors.textMuted else colors.primary,
            )
            StatusPill(
                when {
                    paused -> "Paused"
                    face.status == TrackingStatus.Live -> when {
                        face.isGyro -> "Gyro tracking"
                        face.isSimulated -> "Demo tracking"
                        else -> "Tracking"
                    }
                    face.status == TrackingStatus.NoFace -> "No face in view"
                    face.status is TrackingStatus.Unavailable -> "Tracking unavailable"
                    else -> "Starting…"
                },
                color = if (paused || face.status != TrackingStatus.Live) colors.warning else colors.primary,
                icon = Icons.Outlined.Face,
            )
        }
        if (centerLocked) {
            StatusPill(
                "Centre locked — repeat the gesture to steer",
                color = colors.warning,
                icon = Icons.Outlined.Lock,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
            Text("Gesture: ${face.gesture.primary?.label ?: "—"}", style = MaterialTheme.typography.bodyMedium, color = colors.text)
            Text(voiceText, style = MaterialTheme.typography.bodyMedium, color = colors.text)
            Text(
                "Last action: ${lastEvent?.text ?: "—"}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (lastEvent?.kind == OverlayEvent.Kind.IGNORED) colors.textMuted else colors.primary,
            )
            Text(
                if (face.outputMode == FaceOutputMode.JOYSTICK) "Joystick: ${face.joystick.direction.label}"
                else "Pointer: ${(face.cursor.x * 100).toInt()}%, ${(face.cursor.y * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PwdeButton(
                if (paused) "Resume" else "Pause",
                onTogglePause,
                icon = if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                modifier = Modifier.weight(1f),
                enabled = navigationMode != NavigationMode.GAME || paused,
            )
            PwdeButton(
                "Exit to PWDe",
                onExit,
                style = ButtonStyle.SECONDARY,
                icon = Icons.AutoMirrored.Outlined.ExitToApp,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Head pointer across the whole screen, with a ripple wherever "Select" fires. */
@Composable
private fun CursorLayer(face: FaceState, active: Boolean, lastEvent: OverlayEvent?) {
    val colors = PwdeTheme.colors
    val ripple = remember { Animatable(1f) }
    LaunchedEffect(lastEvent?.id) {
        if (lastEvent?.kind == OverlayEvent.Kind.SELECT) {
            ripple.snapTo(0f)
            ripple.animateTo(1f, tween(450))
        }
    }
    Canvas(
        Modifier.fillMaxSize().semantics {
            contentDescription = "Pointer at ${(face.cursor.x * 100).toInt()} percent across, ${(face.cursor.y * 100).toInt()} percent down"
        },
    ) {
        val center = Offset(face.cursor.x * size.width, face.cursor.y * size.height)
        if (ripple.value < 1f) {
            drawCircle(colors.primary.copy(alpha = 1f - ripple.value), radius = 24.dp.toPx() + 50.dp.toPx() * ripple.value, center = center, style = Stroke(4.dp.toPx()))
        }
        drawCircle(Color.Black.copy(alpha = 0.4f), radius = 16.dp.toPx(), center = center)
        drawCircle(if (active) colors.primary else colors.textMuted, radius = 12.dp.toPx(), center = center)
        drawCircle(Color.White, radius = 12.dp.toPx(), center = center, style = Stroke(2.dp.toPx()))
    }
}

/** A marker the joystick steers around the arena, so joystick input has something to move. */
@Composable
private fun SimulatedAvatar(face: FaceState, active: Boolean) {
    val colors = PwdeTheme.colors
    val density = LocalDensity.current
    var position by remember { mutableStateOf(Offset(0.5f, 0.6f)) }
    val joystick = face.joystick
    // Read by the frame loop without restarting it on every tracking frame.
    val stick by rememberUpdatedState(Offset(joystick.x, joystick.y))
    LaunchedEffect(active) {
        var last = 0L
        while (active) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = (now - last) / 1_000_000_000f
                    position = Offset(
                        (position.x + stick.x * AVATAR_SPEED * dt).coerceIn(0.05f, 0.95f),
                        (position.y + stick.y * AVATAR_SPEED * dt).coerceIn(0.1f, 0.9f),
                    )
                }
                last = now
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val size = 36.dp
        Box(
            Modifier
                .offset(
                    x = with(density) { (constraints.maxWidth * position.x).toDp() } - size / 2,
                    y = with(density) { (constraints.maxHeight * position.y).toDp() } - size / 2,
                )
                .size(size)
                .clip(CircleShape)
                .background(if (active) colors.secondary else colors.textMuted)
                .border(3.dp, Color.White, CircleShape)
                .semantics { contentDescription = "Your character, moving ${joystick.direction.label}" },
        )
    }
}

/** Screen fractions per second at full deflection. */
private const val AVATAR_SPEED = 0.45f

/** A plain drawn "arena" so the overlay has something to sit on. Clearly not a real game. */
@Composable
private fun SimulatedBackground() {
    val colors = PwdeTheme.colors
    Canvas(Modifier.fillMaxSize().semantics { contentDescription = "Simulated game background" }) {
        val step = 48.dp.toPx()
        var x = 0f
        while (x < size.width) {
            drawLine(Color.White.copy(alpha = 0.06f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
            x += step
        }
        var y = 0f
        while (y < size.height) {
            drawLine(Color.White.copy(alpha = 0.06f), Offset(0f, y), Offset(size.width, y), strokeWidth = 2f)
            y += step
        }
        drawRect(colors.secondary.copy(alpha = 0.18f), topLeft = Offset(0f, size.height * 0.48f), size = Size(size.width, size.height * 0.04f))
        drawCircle(colors.primary.copy(alpha = 0.25f), radius = size.minDimension * 0.08f, center = Offset(size.width * 0.25f, size.height * 0.8f))
        drawCircle(colors.primary.copy(alpha = 0.25f), radius = size.minDimension * 0.08f, center = Offset(size.width * 0.75f, size.height * 0.8f))
        drawCircle(colors.danger.copy(alpha = 0.25f), radius = size.minDimension * 0.08f, center = Offset(size.width * 0.25f, size.height * 0.2f))
        drawCircle(colors.danger.copy(alpha = 0.25f), radius = size.minDimension * 0.08f, center = Offset(size.width * 0.75f, size.height * 0.2f))
    }
}
