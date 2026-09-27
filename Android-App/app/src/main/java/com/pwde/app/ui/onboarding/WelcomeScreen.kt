package com.pwde.app.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pwde.app.R
import com.pwde.app.ui.components.ButtonStyle
import com.pwde.app.ui.components.PwdeButton
import com.pwde.app.ui.components.PwdeScreen
import com.pwde.app.ui.components.VoiceCommandsEffect
import com.pwde.app.ui.components.voiceCommand
import com.pwde.app.ui.theme.PwdeTheme

private val WELCOME_COMMANDS = listOf(
    voiceCommand("guest", "get started as guest", "get started", "continue as guest", "start setup"),
    voiceCommand("account", "i have an account", "sign in", "log in"),
)

/** A2 Welcome. Guest is the primary path; an account is optional and only adds cloud sync. */
@Composable
fun WelcomeScreen(onContinueAsGuest: () -> Unit, onHaveAccount: () -> Unit) {
    VoiceCommandsEffect(WELCOME_COMMANDS) { if (it == "guest") onContinueAsGuest() else onHaveAccount() }
    val colors = PwdeTheme.colors
    PwdeScreen(
        title = null,
        voiceHint = "Say \"get started\" or \"I have an account\"",
        footer = { PwdeButton("Get started as guest", onContinueAsGuest, modifier = Modifier.fillMaxWidth()) },
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(
                painterResource(R.drawable.logo_full),
                contentDescription = "PWDe logo",
                modifier = Modifier.size(240.dp),
            )
            Text(
                "Your game. Your rules. Your way.",
                style = MaterialTheme.typography.headlineMedium,
                color = colors.text,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "Play mobile games with your head, face or voice.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textMuted,
                textAlign = TextAlign.Center,
            )
            PwdeButton(
                "I have an account",
                onHaveAccount,
                style = ButtonStyle.SECONDARY,
                icon = Icons.Outlined.Person,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
