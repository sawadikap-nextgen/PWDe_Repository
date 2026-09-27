package com.pwde.app.ui.voicetutorial

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pwde.app.data.prefs.TtsSpeed
import com.pwde.app.data.speech.SpeechStatus
import com.pwde.app.ui.components.ButtonStyle
import com.pwde.app.ui.components.FooterActions
import com.pwde.app.ui.components.VoiceCommandsEffect
import com.pwde.app.ui.components.voiceCommand
import com.pwde.app.ui.components.GradientCard
import com.pwde.app.ui.components.IconBadge
import com.pwde.app.ui.components.InfoNote
import com.pwde.app.ui.components.Panel
import com.pwde.app.ui.components.PwdeButton
import com.pwde.app.ui.components.PwdeScreen
import com.pwde.app.ui.components.SectionTitle
import com.pwde.app.ui.components.SegmentedToggle
import com.pwde.app.ui.components.StatusPill
import com.pwde.app.ui.components.StepProgress
import com.pwde.app.ui.components.SwitchRow
import com.pwde.app.ui.theme.PwdeTheme

private val stepLabels = listOf("Say the button name", "Everything is voice controlled", "Read the screen aloud")

private val TUTORIAL_COMMANDS = listOf(
    voiceCommand("next", "next", "continue", "finish"),
    voiceCommand("skip", "skip"),
)

/** C · Voice tutorial: three skippable steps, controllable by voice ("next", "skip"). */
@Composable
fun VoiceTutorialScreen(viewModel: VoiceTutorialViewModel, onExit: () -> Unit, onFinished: () -> Unit) {
    VoiceCommandsEffect(TUTORIAL_COMMANDS) { id -> if (id == "next") viewModel.next() else viewModel.skip() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.finished) { if (state.finished) onFinished() }
    BackHandler { if (!viewModel.back()) onExit() }

    val (title, subtitle) = when (state.step) {
        0 -> "Practice Voice " to "Every button has a name. Say it to press it."
        1 -> "Everything is voice controlled" to "Menus, settings and games — all by voice."
        else -> "Read the screen aloud" to "PWDe can read what's on screen to you."
    }
    PwdeScreen(
        title = title,
        subtitle = subtitle,
        onBack = { if (!viewModel.back()) onExit() },
        voiceHint = if (state.isLastStep) "Say \"read aloud on\" or \"finish\"" else "Say \"next\" or \"skip\"",
        footer = {
            FooterActions(
                primaryText = if (state.isLastStep) "Finish" else "Next",
                onPrimary = viewModel::next,
                primaryIcon = if (state.isLastStep) Icons.Outlined.Check else null,
                secondaryText = "Skip",
                onSecondary = viewModel::skip,
            )
        },
    ) {
        StepProgress(state.step + 1, VoiceTutorialUiState.STEP_COUNT, stepLabels[state.step])
        when (state.step) {
            0 -> SayTheNameStep()
            1 -> EverythingVoiceStep()
            else -> ReadAloudStep(state, viewModel)
        }
    }
}

@Composable
private fun SayTheNameStep() {
    val colors = PwdeTheme.colors
    SectionTitle("How it works")
    GradientCard(Modifier.fillMaxWidth()) {
        Text("A button on screen:", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
        PwdeButton(
            "Start playing",
            onClick = {},
            icon = Icons.Outlined.SportsEsports,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                .clearAndSetSemantics { contentDescription = "Example button named Start playing" },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.RecordVoiceOver, contentDescription = null, tint = colors.primary)
            Text("You say: \"start playing\"", style = MaterialTheme.typography.titleSmall, color = colors.text)
        }
    }
    SectionTitle("You don't need to be exact")
    Text(
        "Slow, slurred or partial words still work.",
        style = MaterialTheme.typography.bodyMedium,
        color = colors.textMuted,
    )
    MatchExample("\"start play\"", matches = true)
    MatchExample("\"staaart playing\"", matches = true)
    MatchExample("\"open settings\"", matches = false, note = "a different button")
}

@Composable
private fun MatchExample(phrase: String, matches: Boolean, note: String? = null) {
    val colors = PwdeTheme.colors
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(phrase, style = MaterialTheme.typography.bodyLarge, color = colors.text, modifier = Modifier.weight(1f))
            StatusPill(
                if (matches) "Presses Start playing" else "No match${note?.let { " — $it" } ?: ""}",
                color = if (matches) colors.success else colors.textMuted,
                icon = if (matches) Icons.Outlined.Check else Icons.Outlined.Close,
            )
        }
    }
}

@Composable
private fun EverythingVoiceStep() {
    SectionTitle("Voice works in")
    AreaRow(Icons.Outlined.Settings, "Menus & settings", "Say any card or button name")
    AreaRow(Icons.Outlined.OpenWith, "Moving things", "Say \"move joystick up\" to reposition controls")
    AreaRow(Icons.Outlined.SportsEsports, "While you play", "Say \"pause\", \"recenter\" or a custom button's name")
    InfoNote("Voice is on: try saying \"next\".")
}

@Composable
private fun AreaRow(icon: ImageVector, title: String, body: String) {
    GradientCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(icon)
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, color = PwdeTheme.colors.text)
                Text(body, style = MaterialTheme.typography.bodySmall, color = PwdeTheme.colors.textMuted)
            }
        }
    }
}

@Composable
private fun ReadAloudStep(state: VoiceTutorialUiState, viewModel: VoiceTutorialViewModel) {
    val colors = PwdeTheme.colors
    SwitchRow(
        title = "Read on-screen text aloud",
        description = "Reads the screen aloud",
        checked = state.ttsEnabled,
        onCheckedChange = viewModel::setTtsEnabled,
        icon = Icons.AutoMirrored.Outlined.VolumeUp,
    )
    SectionTitle("Speed")
    SegmentedToggle(
        options = TtsSpeed.entries,
        selected = state.ttsSpeed,
        label = { it.label },
        onSelect = viewModel::setSpeed,
    )
    SectionTitle("Preview")
    GradientCard(Modifier.fillMaxWidth()) {
        Text("\"${VoiceTutorialViewModel.PREVIEW_TEXT}\"", style = MaterialTheme.typography.bodyMedium, color = colors.text)
        PwdeButton(
            "Play preview",
            viewModel::preview,
            style = ButtonStyle.SECONDARY,
            icon = Icons.AutoMirrored.Outlined.VolumeUp,
            enabled = state.speechStatus != SpeechStatus.UNAVAILABLE,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        if (state.speechStatus == SpeechStatus.UNAVAILABLE) {
            Text(
                "No text-to-speech engine found on this phone. Install one in Android Settings → Accessibility.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.warning,
            )
        }
    }
    SwitchRow(
        title = "I use another screen reader",
        description = if (state.systemScreenReaderOn) "TalkBack is on — PWDe won't talk over it" else "e.g. TalkBack. PWDe stays quiet.",
        checked = state.usesOtherScreenReader,
        onCheckedChange = viewModel::setUsesOtherScreenReader,
        icon = Icons.Outlined.Accessibility,
    )
}
