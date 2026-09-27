package com.pwde.app.ui.voiceconfig

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pwde.app.data.local.ControlsRepository
import com.pwde.app.data.model.ControlConfig
import com.pwde.app.data.model.VoiceActivationMode
import com.pwde.app.data.model.VoiceMatchMode
import com.pwde.app.data.model.VoiceShortcut
import com.pwde.app.data.prefs.SettingsRepository
import com.pwde.app.data.prefs.TtsSpeed
import com.pwde.app.sensors.voice.CommandScope
import com.pwde.app.sensors.voice.StandardCommands
import com.pwde.app.sensors.voice.VoiceCommandManager
import com.pwde.app.sensors.voice.VoiceState
import com.pwde.app.ui.components.GradientCard
import com.pwde.app.ui.components.InfoNote
import com.pwde.app.ui.components.OptionCard
import com.pwde.app.ui.components.OptionKind
import com.pwde.app.ui.components.Pager
import com.pwde.app.ui.components.PwdeScreen
import com.pwde.app.ui.components.SegmentedToggle
import com.pwde.app.ui.components.PwdeTextField
import com.pwde.app.ui.components.SectionTitle
import com.pwde.app.ui.components.StatusPill
import com.pwde.app.ui.components.SwitchRow
import com.pwde.app.ui.components.VoiceCommandsEffect
import com.pwde.app.ui.components.rememberMicPermissionRequest
import com.pwde.app.ui.components.voiceCommand
import com.pwde.app.ui.theme.PwdeTheme
import com.pwde.app.ui.theme.iconSizeFor
import com.pwde.app.ui.theme.scaled
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Read-aloud, as the Voice screen shows it. */
data class ReadAloudState(
    val enabled: Boolean = false,
    val speed: TtsSpeed = TtsSpeed.NORMAL,
    val usesOtherScreenReader: Boolean = false,
)

class VoiceConfigViewModel(
    private val controlsRepository: ControlsRepository,
    private val voiceCommandManager: VoiceCommandManager,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val config: StateFlow<ControlConfig?> = controlsRepository.config
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val voice: StateFlow<VoiceState> = voiceCommandManager.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), voiceCommandManager.state.value)

    /** Recent mic levels, oldest first, for the scrolling waveform. */
    private val _levels = MutableStateFlow(List(WAVEFORM_BARS) { 0f })
    val levels: StateFlow<List<Float>> = _levels.asStateFlow()

    /** Local copy of phrases while typing, so the field never fights the database round-trip. */
    private val _phrases = MutableStateFlow<Map<VoiceShortcut, String>?>(null)
    val phrases: StateFlow<Map<VoiceShortcut, String>?> = _phrases.asStateFlow()

    val hasMicPermission: Boolean get() = voiceCommandManager.hasMicPermission

    init {
        viewModelScope.launch { _phrases.value = controlsRepository.config.first().voiceShortcuts }
        viewModelScope.launch {
            voice.collect { state -> _levels.update { (it + if (state.listening) state.level else 0f).takeLast(WAVEFORM_BARS) } }
        }
    }

    fun setVoiceEnabled(enabled: Boolean) {
        launch { controlsRepository.setVoiceEnabled(enabled, persistToActiveProfile = true) }
        voiceCommandManager.refreshPermissions()
    }

    /**
     * Read-aloud lives in settings rather than the controls, but this screen is where a user changes how
     * PWDe talks — and the first-run Voice tutorial, the only other place with the switch, is never shown
     * again.
     */
    val readAloud: StateFlow<ReadAloudState> = settingsRepository.settings
        .map { ReadAloudState(it.ttsEnabled, it.ttsSpeed, it.usesOtherScreenReader) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadAloudState())

    /** Two screen readers would talk over each other, so turning one on turns the other off. */
    fun setReadAloud(enabled: Boolean) = launch {
        val current = settingsRepository.settings.first()
        settingsRepository.setScreenReading(enabled, current.ttsSpeed, if (enabled) false else current.usesOtherScreenReader)
    }

    fun setReadAloudSpeed(speed: TtsSpeed) = launch {
        val current = settingsRepository.settings.first()
        settingsRepository.setScreenReading(current.ttsEnabled, speed, current.usesOtherScreenReader)
    }

    fun setMatchMode(mode: VoiceMatchMode) = launch { controlsRepository.setVoiceMatchMode(mode, persistToActiveProfile = true) }
    fun setActivationMode(mode: VoiceActivationMode) = launch { controlsRepository.setVoiceActivationMode(mode, persistToActiveProfile = true) }
    fun onMicPermissionResult() = voiceCommandManager.refreshPermissions()

    fun setPhrase(shortcut: VoiceShortcut, phrase: String) {
        _phrases.update { it.orEmpty() + (shortcut to phrase) }
        launch { controlsRepository.setVoiceShortcut(shortcut, phrase, persistToActiveProfile = true) }
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    companion object {
        const val WAVEFORM_BARS = 32
    }
}

private val VOICE_CONFIG_COMMANDS = listOf(
    voiceCommand("exact", "exact phrase", "exact"),
    voiceCommand("anywhere", "word anywhere", "anywhere"),
    voiceCommand("immediate", "right away"),
    voiceCommand("after", "after I finish", "after finish"),
    voiceCommand("next_page", "next", "next page", "commands"),
    voiceCommand("previous_page", "previous page", "previous"),
    voiceCommand("all_commands", "all commands", "all voice commands"),
)

private const val PAGE_COUNT = 3

/** E11/E12 Voice: live mic, on/off, matching and activation modes, and the full command list. */
@Composable
fun VoiceConfigScreen(viewModel: VoiceConfigViewModel, onBack: () -> Unit) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val voice by viewModel.voice.collectAsStateWithLifecycle()
    val levels by viewModel.levels.collectAsStateWithLifecycle()
    val phrases by viewModel.phrases.collectAsStateWithLifecycle()
    val readAloud by viewModel.readAloud.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableIntStateOf(1) }
    val requestMic = rememberMicPermissionRequest { granted ->
        viewModel.onMicPermissionResult()
        if (granted) viewModel.setVoiceEnabled(true)
    }
    VoiceCommandsEffect(VOICE_CONFIG_COMMANDS) { id ->
        when (id) {
            "exact" -> viewModel.setMatchMode(VoiceMatchMode.EXACT)
            "anywhere" -> viewModel.setMatchMode(VoiceMatchMode.WORD_ANYWHERE)
            "immediate" -> viewModel.setActivationMode(VoiceActivationMode.IMMEDIATE)
            "after" -> viewModel.setActivationMode(VoiceActivationMode.AFTER_FINISH)
            "next_page" -> page = (page + 1).coerceAtMost(PAGE_COUNT)
            "previous_page" -> page = (page - 1).coerceAtLeast(1)
            "all_commands" -> page = PAGE_COUNT
        }
    }
    val setVoice: (Boolean) -> Unit = { enabled ->
        if (enabled && !viewModel.hasMicPermission) requestMic() else viewModel.setVoiceEnabled(enabled)
    }
    PwdeScreen(
        title = "Voice",
        subtitle = "Say a button's name to press it.",
        onBack = onBack,
        voiceHint = "Say \"word anywhere\", \"right away\" or \"all commands\"",
        footer = { Pager(page, PAGE_COUNT, { page-- }, { page++ }) },
    ) {
        val current = config ?: return@PwdeScreen
        if (page == 1) {
            BigMicButton(on = current.voiceEnabled, listening = voice.listening, onToggle = setVoice)
            SwitchRow("Voice control", current.voiceEnabled, setVoice, icon = Icons.Outlined.Mic)
            MicLevel(voice, levels)
            SectionTitle("How words are matched")
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(PwdeTheme.spacing.itemGap)) {
                VoiceMatchMode.entries.forEach { mode ->
                    OptionCard(
                        mode.label, mode.description, mode == current.voiceMatchMode, { viewModel.setMatchMode(mode) },
                        icon = Icons.AutoMirrored.Outlined.FormatListBulleted, kind = OptionKind.RADIO,
                    )
                }
            }
            SectionTitle("When PWDe acts")
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(PwdeTheme.spacing.itemGap)) {
                VoiceActivationMode.entries.forEach { mode ->
                    OptionCard(
                        mode.label, mode.description, mode == current.voiceActivationMode, { viewModel.setActivationMode(mode) },
                        icon = Icons.Outlined.Timer, kind = OptionKind.RADIO,
                    )
                }
            }
            SectionTitle("Read aloud")
            SwitchRow(
                title = "Read the screen to me",
                description = "Reads the screen aloud",
                checked = readAloud.enabled,
                onCheckedChange = viewModel::setReadAloud,
                icon = Icons.AutoMirrored.Outlined.VolumeUp,
            )
            if (readAloud.usesOtherScreenReader) {
                StatusPill("Off — you told PWDe you use another screen reader", color = PwdeTheme.colors.warning)
            }
            if (readAloud.enabled) {
                SegmentedToggle(
                    options = TtsSpeed.entries,
                    selected = readAloud.speed,
                    label = { it.label },
                    onSelect = viewModel::setReadAloudSpeed,
                )
                InfoNote("Say \"read screen\" to hear it again. Leave off with TalkBack.")
            }
        } else if (page == 2) {
            SectionTitle("Your spoken shortcuts")
            VoiceShortcut.entries.forEach { shortcut ->
                PwdeTextField(
                    label = shortcut.label,
                    value = phrases?.get(shortcut).orEmpty(),
                    onValueChange = { viewModel.setPhrase(shortcut, it) },
                )
            }
            SectionTitle("Works on every screen")
            CommandList(StandardCommands.all.map { it.phrases.joinToString(" / ") })
            val screenCommands = voice.commands.filter { it.scope == CommandScope.SCREEN }
            if (screenCommands.isNotEmpty()) {
                SectionTitle("On this screen")
                CommandList(screenCommands.map { it.phrases.joinToString(" / ") })
            }
            InfoNote("Each screen also answers to the words on its buttons.")
        } else {
            SectionTitle("All voice commands")
            val shortcuts = StandardCommands.shortcuts(phrases.orEmpty())
            allVoiceCommandGroups(VOICE_CONFIG_COMMANDS, shortcuts).forEach { group ->
                SectionTitle(group.title)
                CommandList(group.commands.map { it.phrases.joinToString(" / ") })
            }
        }
    }
}

/**
 * Large dedicated mic toggle (88dp; the voice bar's mic is 60dp). Same on/off and mic-permission
 * flow as the "Voice control" switch below it, which stays as a secondary control.
 */
@Composable
private fun BigMicButton(on: Boolean, listening: Boolean, onToggle: (Boolean) -> Unit) {
    val colors = PwdeTheme.colors
    val size = 88.dp
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(if (on) colors.buttonBrush else SolidColor(colors.surfaceMuted))
                .border(if (listening) 4.dp else 2.dp, colors.primary, CircleShape)
                .toggleable(value = on, role = Role.Switch, onValueChange = onToggle)
                .semantics { contentDescription = "Voice control" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (on) Icons.Outlined.Mic else Icons.Outlined.MicOff,
                contentDescription = null,
                tint = if (on) colors.onAccent else colors.primary,
                modifier = Modifier.size(iconSizeFor(size, 0.45f).scaled().coerceAtMost(size * 0.65f)),
            )
        }
        Text(
            if (on) "Voice is on · tap to turn it off" else "Tap to turn voice on",
            style = MaterialTheme.typography.titleSmall,
            color = colors.text,
        )
    }
}

@Composable
private fun CommandList(lines: List<String>) {
    GradientCard(Modifier.fillMaxWidth()) {
        lines.forEach { Text("\"$it\"", style = MaterialTheme.typography.bodyMedium, color = PwdeTheme.colors.text) }
    }
}

/** Real mic level from the recognizer, plus what was last heard and matched. */
@Composable
private fun MicLevel(voice: VoiceState, levels: List<Float>) {
    val colors = PwdeTheme.colors
    GradientCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Mic level", style = MaterialTheme.typography.titleMedium, color = colors.text, modifier = Modifier.weight(1f))
            StatusPill(
                when {
                    voice.usesTextFallback -> "Unavailable"
                    !voice.enabled -> "Off"
                    voice.listening -> "Listening"
                    else -> "Starting"
                },
                color = if (voice.listening) colors.primary else colors.textMuted,
            )
        }
        Canvas(
            Modifier.fillMaxWidth().height(48.dp).semantics {
                contentDescription = if (voice.listening) "Microphone level, listening" else "Microphone not listening"
            },
        ) {
            val gap = 4.dp.toPx()
            val barWidth = (size.width - gap * (levels.size - 1)) / levels.size
            levels.forEachIndexed { i, level ->
                val h = size.height * (0.1f + 0.9f * level)
                drawRoundRect(
                    color = if (voice.listening) colors.primary else colors.textMuted.copy(alpha = 0.5f),
                    topLeft = Offset(i * (barWidth + gap), (size.height - h) / 2),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(barWidth / 2),
                )
            }
        }
        Text(
            when {
                voice.usesTextFallback -> "${voice.availability.label}. Every screen still works by touch."
                voice.lastTranscript != null -> "Heard: \"${voice.lastTranscript}\"" + (voice.lastCommand?.let { " · last command: ${it.label}" } ?: "")
                voice.enabled -> "Say something — what PWDe hears appears here."
                else -> "Voice control is off."
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.textMuted,
        )
        voice.lastCommand?.let { StatusPill("Matched: ${it.label}", icon = Icons.Outlined.CheckCircle) }
    }
}
