package com.pwde.app.ui.tutorial

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.pwde.app.data.media.TutorialPlayer
import com.pwde.app.ui.components.ButtonStyle
import com.pwde.app.ui.components.InfoNote
import com.pwde.app.ui.components.PwdeButton
import com.pwde.app.ui.components.PwdeScreen
import com.pwde.app.ui.theme.MinTouchTarget
import com.pwde.app.ui.theme.PwdeShapes
import com.pwde.app.ui.theme.PwdeTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class TutorialUiState(val isPlaying: Boolean = false, val positionMs: Long = 0, val durationMs: Long = 0) {
    val progress: Float get() = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
}

class WatchTutorialViewModel(private val tutorialPlayer: TutorialPlayer) : ViewModel() {
    private val _state = MutableStateFlow(TutorialUiState())
    val state: StateFlow<TutorialUiState> = _state.asStateFlow()

    val player: Player get() = tutorialPlayer.player

    init {
        viewModelScope.launch {
            while (isActive) {
                _state.value = TutorialUiState(tutorialPlayer.isPlaying, tutorialPlayer.positionMs, tutorialPlayer.durationMs)
                delay(250)
            }
        }
    }

    fun togglePlay() = if (tutorialPlayer.isPlaying) tutorialPlayer.pause() else tutorialPlayer.play()
    fun pause() = tutorialPlayer.pause()
    fun replay10() = tutorialPlayer.seekTo(tutorialPlayer.positionMs - 10_000)
    fun seekToFraction(fraction: Float) = tutorialPlayer.seekTo((tutorialPlayer.durationMs * fraction).toLong())

    override fun onCleared() = tutorialPlayer.release()
}

/** H5 Tutorial video: play/pause, progress bar, rewind. Placeholder video until the real one exists. */
@Composable
fun WatchTutorialScreen(viewModel: WatchTutorialViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = PwdeTheme.colors

    // Pause when the app goes to the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) viewModel.pause() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    PwdeScreen(
        title = "Watch Tutorial",
        subtitle = null,
        onBack = onBack,
        voiceHint = "Say \"play\", \"pause\" or \"back 10\"",
        footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PwdeButton("Back 10s", viewModel::replay10, style = ButtonStyle.SECONDARY, icon = Icons.Outlined.Replay10, modifier = Modifier.weight(1f))
                PwdeButton(
                    if (state.isPlaying) "Pause" else "Play",
                    viewModel::togglePlay,
                    icon = if (state.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    modifier = Modifier.weight(1.4f),
                )
            }
        },
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(PwdeShapes.card).background(colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        useController = false
                        player = viewModel.player
                    }
                },
                modifier = Modifier.fillMaxSize().semantics { contentDescription = "Tutorial video" },
                onRelease = { it.player = null },
            )
        }
        Slider(
            value = state.progress,
            onValueChange = viewModel::seekToFraction,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .semantics {
                    contentDescription = "Video progress"
                    stateDescription = "${formatTime(state.positionMs)} of ${formatTime(state.durationMs)}"
                },
            colors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary, inactiveTrackColor = colors.surfaceMuted),
        )
        Row {
            Text(formatTime(state.positionMs), style = MaterialTheme.typography.labelMedium, color = colors.text, modifier = Modifier.weight(1f))
            Text(formatTime(state.durationMs), style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
        }
        InfoNote("Placeholder video — the full tutorial is coming.")
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
