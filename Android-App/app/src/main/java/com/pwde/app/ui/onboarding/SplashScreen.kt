package com.pwde.app.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pwde.app.R
import com.pwde.app.data.prefs.SettingsRepository
import com.pwde.app.data.remote.AuthRepository
import com.pwde.app.data.remote.AuthState
import com.pwde.app.ui.navigation.Routes
import com.pwde.app.ui.theme.PwdeShapes
import com.pwde.app.ui.theme.PwdeTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Decides where the app opens: signed-in → Dashboard; first run → Welcome; mid-onboarding resumes. */
class SplashViewModel(
    private val settingsRepository: SettingsRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val _destination = MutableStateFlow<String?>(null)
    val destination: StateFlow<String?> = _destination.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            delay(SPLASH_MS)
            _destination.value = when {
                authRepository.authState.value is AuthState.SignedIn -> Routes.DASHBOARD
                !settings.setupCompleted -> Routes.WELCOME
                !settings.voiceTutorialCompleted -> Routes.voiceTutorial()
                else -> Routes.DASHBOARD
            }
        }
    }

    companion object {
        const val SPLASH_MS = 1200L
    }
}

@Composable
fun SplashScreen(viewModel: SplashViewModel, onFinished: (String) -> Unit) {
    val destination by viewModel.destination.collectAsStateWithLifecycle()
    LaunchedEffect(destination) { destination?.let(onFinished) }

    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(SplashViewModel.SPLASH_MS.toInt())) }

    val colors = PwdeTheme.colors
    Box(Modifier.fillMaxSize().background(colors.background), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Image(
                painterResource(R.drawable.logo_wordmark),
                contentDescription = "PWDe",
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(100.dp),
                contentScale = ContentScale.Fit,
            )
            LinearProgressIndicator(
                progress = { progress.value },
                modifier = Modifier.fillMaxWidth(0.7f).height(10.dp).clip(PwdeShapes.pill),
                color = colors.primary,
                trackColor = colors.surfaceMuted,
                drawStopIndicator = {},
            )
            Text(
                "Getting your controls ready…",
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(
                "Everything works without an account. Your settings stay on this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
