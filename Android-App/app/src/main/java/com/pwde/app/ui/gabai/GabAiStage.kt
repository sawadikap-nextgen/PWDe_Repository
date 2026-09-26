package com.pwde.app.ui.gabai

import androidx.compose.animation.AnimatedVisibility
import com.pwde.app.ui.theme.PwdeShapes
import com.pwde.app.ui.components.ButtonStyle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pwde.app.ui.components.StatusPill
import com.pwde.app.ui.components.VoiceMicOverlay
import com.pwde.app.ui.theme.PwdeTheme

/**
 * Full-screen frame for the steps that work on the game's screen (choosing triggers, testing):
 * [        ] fills the screen like the game does, and GabAI's conversation sits in a collapsible
 * sidebar on the right, with the same header, message and reply hint as every other step.
 */
@Composable
internal fun GabAiStageStep(
    viewModel: GabAiViewModel,
    ui: GabAiUiState,
    title: String,
    says: String,
    voiceHint: String,
    footer: @Composable () -> Unit,
    stage: @Composable BoxScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = PwdeTheme.colors
    val spacing = PwdeTheme.spacing
    val open = ui.sidebarOpen
    // The screenshot runs edge to edge like the game (and the Playing screen); only the sidebar keeps clear of insets.
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF1B2A1E))) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center, content = stage)
        // Beside the game in landscape; over most of it in portrait, where it's easy to tuck away.
        // Narrow, so as much of the game as possible stays in view on a phone.
        // Collapsible, so it can take a good share of the screen; the game is a tap (or "collapse") away.
        val width = if (maxWidth > maxHeight) (maxWidth * 0.42f).coerceIn(300.dp, 460.dp) else (maxWidth * 0.85f).coerceAtMost(400.dp)
        AnimatedVisibility(
            open,
            Modifier.align(Alignment.CenterStart),
            enter = slideInHorizontally { -it },
            exit = slideOutHorizontally { -it },
        ) {
            Column(
                Modifier
                    .width(width)
                    .fillMaxHeight()
                    .clip(SidebarShape)
                    .background(colors.background.copy(alpha = 0.96f))
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = viewModel::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.primary) }
                    Column(Modifier.weight(1f)) {
                        Text("GabAI", style = MaterialTheme.typography.titleMedium, color = colors.primary, modifier = Modifier.semantics { heading() })
                        Text(title, style = MaterialTheme.typography.labelSmall, color = colors.textMuted, maxLines = 1)
                    }
                    IconButton(onClick = { viewModel.setSidebarOpen(false) }) {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = "Hide GabAI panel", tint = colors.primary)
                    }
                }
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    GabAiSays(says, compact = true)
                    ui.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.warning) }
                    content()
                }
                Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    footer()
                    // Bottom-left corner: the mic, and what can be said back as tappable replies.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VoiceMicOverlay(hint = voiceHint, popupAtEnd = true)
                        SuggestedReplies(voiceHint, viewModel::reply, Modifier.weight(1f))
                    }
                }
            }
        }
        if (!open) {
            Row(
                Modifier
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp))
                    .background(colors.background.copy(alpha = 0.92f))
                    .clickable(role = Role.Button) { viewModel.setSidebarOpen(true) }
                    .padding(horizontal = 8.dp, vertical = 16.dp)
                    .semantics { contentDescription = "Show GabAI panel" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = colors.secondary)
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = colors.primary)
            }
            // The panel (and its mic) is tucked away: keep the mic in the bottom-left corner.
            VoiceMicOverlay(
                hint = voiceHint,
                popupAtEnd = true,
                modifier = Modifier.align(Alignment.BottomStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
            )
        }
    }
}

private val SidebarShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)

/** A quoted phrase; one followed by "+" needs more words after it ("assign" + words). */
private val QUOTED = Regex("\"([^\"]+)\"(\\s*\\+)?")

/**
 * The quoted phrases of [hint] as reply chips; tapping one acts as if it were said. Phrases that
 * need more words after them stay voice-only and aren't offered.
 */
@Composable
private fun SuggestedReplies(hint: String, onReply: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = PwdeTheme.colors
    val replies = remember(hint) { QUOTED.findAll(hint).filter { it.groupValues[2].isEmpty() }.map { it.groupValues[1] }.toList() }
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        replies.forEach { reply ->
            Text(
                reply,
                style = MaterialTheme.typography.labelMedium,
                color = colors.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .border(1.dp, colors.primary.copy(alpha = 0.6f), RoundedCornerShape(50))
                    .clickable(role = Role.Button) { onReply(reply) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** Spoken on the stage steps to tuck the sidebar away or bring it back. */
internal val PANEL_PHRASES_HIDE = arrayOf("collapse", "hide panel", "hide sidebar")
internal val PANEL_PHRASES_SHOW = arrayOf("expand", "show panel", "show sidebar", "gab ai")

/**
 * The sidebar's button: like [PwdeButton] (same styles) but one line, 40dp tall and small type, so
 * a phone in landscape still shows most of the sidebar at once.
 */
@Composable
internal fun SideButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.PRIMARY,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = PwdeTheme.colors
    val content = if (style == ButtonStyle.SECONDARY) colors.primary else colors.onAccent
    Row(
        modifier
            .heightIn(min = 40.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(PwdeShapes.button)
            .then(
                when (style) {
                    ButtonStyle.PRIMARY -> Modifier.background(colors.buttonBrush)
                    ButtonStyle.SECONDARY -> Modifier.border(1.5.dp, colors.primary, PwdeShapes.button)
                    ButtonStyle.DESTRUCTIVE -> Modifier.background(colors.danger)
                },
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A small tappable pill for picking one of several options (gestures) in the sidebar. */
@Composable
internal fun SideChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = PwdeTheme.colors
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) colors.onAccent else colors.primary,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .then(if (selected) Modifier.background(colors.primary) else Modifier.border(1.dp, colors.primary.copy(alpha = 0.6f), RoundedCornerShape(50)))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
