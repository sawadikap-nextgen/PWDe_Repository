package com.pwde.app.ui.profile

import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.AutoAwesome
import com.pwde.app.data.prefs.SettingsRepository
import com.pwde.app.data.local.inputModeOrDefault
import com.pwde.app.data.local.ControlsRepository
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pwde.app.data.local.CalibrationProfile
import com.pwde.app.data.local.GameProfile
import com.pwde.app.data.local.ProfileRepository
import com.pwde.app.data.model.Game
import com.pwde.app.data.remote.AuthRepository
import com.pwde.app.data.remote.AuthState
import com.pwde.app.data.remote.SyncRepository
import com.pwde.app.data.remote.SyncStatus
import com.pwde.app.ui.components.ButtonStyle
import com.pwde.app.ui.components.GradientCard
import com.pwde.app.ui.components.IconBadge
import com.pwde.app.ui.components.InfoNote
import com.pwde.app.ui.components.MainTab
import com.pwde.app.ui.components.NavCard
import com.pwde.app.ui.components.PwdeBottomNav
import com.pwde.app.ui.components.PwdeButton
import com.pwde.app.ui.components.PwdeScreen
import com.pwde.app.ui.components.PwdeTextField
import com.pwde.app.ui.components.VoiceCommandsEffect
import com.pwde.app.ui.components.voiceCommand
import com.pwde.app.ui.components.SectionTitle
import com.pwde.app.ui.components.StatusPill
import com.pwde.app.ui.theme.PwdeTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.pwde.app.ui.games.GameThumbnail
import com.pwde.app.ui.games.GameArt

data class ProfileUiState(
    val auth: AuthState = AuthState.Guest,
    val cloudAvailable: Boolean = false,
    val syncStatus: SyncStatus = SyncStatus.LocalOnly,
    val calibrationProfiles: List<CalibrationProfile> = emptyList(),
    val gameProfiles: List<GameProfile> = emptyList(),
    val activeCalibrationProfileId: Long? = null,
)

class ProfileViewModel(
    private val authRepository: AuthRepository,
    syncRepository: SyncRepository,
    private val profileRepository: ProfileRepository,
    private val controlsRepository: ControlsRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** Makes a saved calibration the working controls (and its input mode the active one). */
    fun useCalibration(profile: CalibrationProfile) {
        viewModelScope.launch {
            controlsRepository.applyCalibration(profile)
            settingsRepository.setInputMode(profile.inputModeOrDefault)
            _notice.value = "Now using \"${profile.name}\""
        }
    }

    private val profileState = combine(
        authRepository.authState,
        syncRepository.status,
        profileRepository.calibrationProfiles,
        profileRepository.gameProfiles,
    ) { auth, sync, calibrations, games ->
        ProfileUiState(auth, authRepository.isCloudAvailable, sync, calibrations, games)
    }

    val state: StateFlow<ProfileUiState> = combine(profileState, controlsRepository.activeCalibrationProfileId) { profileState, activeId ->
        profileState.copy(activeCalibrationProfileId = activeId)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ProfileUiState(cloudAvailable = authRepository.isCloudAvailable),
    )

    /** Signing out never deletes local profiles. */
    fun signOut() = authRepository.signOut()

    fun rename(profile: SavedProfile, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            when (profile) {
                is SavedProfile.Calibration -> profileRepository.saveCalibrationProfile(profile.profile.copy(name = trimmed))
                is SavedProfile.Game -> profileRepository.saveGameProfile(profile.profile.copy(profileName = trimmed))
            }
        }
    }

    fun delete(profile: SavedProfile) {
        viewModelScope.launch {
            when (profile) {
                is SavedProfile.Calibration -> {
                    controlsRepository.clearActiveCalibrationProfile(profile.profile.id)
                    profileRepository.deleteCalibrationProfile(profile.profile)
                }
                is SavedProfile.Game -> profileRepository.deleteGameProfile(profile.profile)
            }
        }
    }
}

/** A saved profile of either kind, for the shared rename/delete UI. */
sealed interface SavedProfile {
    val name: String
    val detail: String

    data class Calibration(val profile: CalibrationProfile) : SavedProfile {
        override val name get() = profile.name
        override val detail get() = profile.inputMode.lowercase().replace('_', ' ')
    }

    data class Game(val profile: GameProfile) : SavedProfile {
        override val name get() = profile.profileName
        override val detail get() = profile.gameName
    }
}

private sealed interface ProfileDialog {
    data class Rename(val profile: SavedProfile) : ProfileDialog
    data class Delete(val profile: SavedProfile) : ProfileDialog
}

internal val PROFILE_COMMANDS = listOf(
    voiceCommand("gabai", "gabai", "gab ai", "new profile"),
    voiceCommand("sign_in", "sign in", "sync"),
    voiceCommand("appearance", "appearance"),
    voiceCommand("controls", "controls"),
    voiceCommand("redo_onboarding", "redo onboarding", "run setup again", "redo setup", "restart setup"),
) + Game.entries.map { voiceCommand("folder:${it.id}", it.displayName) } + MainTab.entries.filter { it != MainTab.PROFILE }.map { voiceCommand("tab:${it.name}", it.label) }

/** H1 Profile. Local profiles from Room, sign-in entry for guests, sync status for signed-in users. */
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    onSignIn: () -> Unit,
    onEditGameProfile: (Long) -> Unit,
    onPlayGameProfile: (gameId: String, profileId: Long) -> Unit,
    onTestGameProfile: (gameId: String, profileId: Long) -> Unit,
    onNewWithGabAi: () -> Unit,
    onEditCalibration: (Long) -> Unit,
    onEditAppearance: () -> Unit,
    onRedoOnboarding: () -> Unit,
    onControls: () -> Unit,
    onTab: (MainTab) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = PwdeTheme.colors
    val signedIn = state.auth as? AuthState.SignedIn
    val name = signedIn?.let { it.displayName ?: it.email } ?: "Guest"
    var dialog by remember { mutableStateOf<ProfileDialog?>(null) }
    var confirmRedo by rememberSaveable { mutableStateOf(false) }
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    // Game ids whose folder is expanded; closed by default so the list stays short.
    var openFolders by rememberSaveable { mutableStateOf(emptyList<String>()) }
    fun toggleFolder(gameId: String) {
        openFolders = if (gameId in openFolders) openFolders - gameId else openFolders + gameId
    }
    VoiceCommandsEffect(PROFILE_COMMANDS) { id ->
        when {
            id.startsWith("folder:") -> toggleFolder(id.removePrefix("folder:"))
            id.startsWith("tab:") -> onTab(MainTab.valueOf(id.removePrefix("tab:")))
            id == "sign_in" -> if (signedIn == null) onSignIn()
            id == "appearance" -> onEditAppearance()
            id == "controls" -> onControls()
            id == "redo_onboarding" -> confirmRedo = true
            id == "gabai" -> onNewWithGabAi()
        }
    }
    when (val d = dialog) {
        is ProfileDialog.Rename -> RenameDialog(d.profile, onDismiss = { dialog = null }) { newName ->
            viewModel.rename(d.profile, newName)
            dialog = null
        }
        is ProfileDialog.Delete -> DeleteDialog(d.profile, onDismiss = { dialog = null }) {
            viewModel.delete(d.profile)
            dialog = null
        }
        null -> Unit
    }

    PwdeScreen(
        title = "Profile",
        voiceHint = "Say \"sign in\" or a game's name to open its folder",
        bottomBar = { PwdeBottomNav(MainTab.PROFILE, onTab) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(colors.cardBrush).border(2.dp, colors.borderBrush, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(name.take(1).uppercase(), style = MaterialTheme.typography.headlineMedium, color = colors.text)
            }
            Column {
                Text(name, style = MaterialTheme.typography.titleLarge, color = colors.text)
                Text(
                    if (signedIn != null) "Signed in" else "Guest — everything is saved on this phone",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }

        SyncCard(state, onSignIn, viewModel::signOut)

        SectionTitle("Calibration profiles")
        if (state.calibrationProfiles.isEmpty()) {
            InfoNote("No calibration profiles yet. GabAI will help you make one.")
            PwdeButton(
                "Make one with GabAI", onNewWithGabAi, style = ButtonStyle.SECONDARY, icon = Icons.Outlined.AutoAwesome,
                modifier = Modifier.fillMaxWidth(), contentPadding = buttonPadding(),
            )
        } else {
            notice?.let { StatusPill(it, icon = Icons.Outlined.CheckCircle) }
            CalibrationProfileCarousel(
                profiles = state.calibrationProfiles,
                activeProfileId = state.activeCalibrationProfileId,
                onActivate = viewModel::useCalibration,
                onEdit = onEditCalibration,
            )
        }

        SectionTitle("Game profiles")
        if (state.gameProfiles.isEmpty()) {
            InfoNote("No game profiles yet. GabAI makes one for each game: you mark its buttons and pick how to press them.")
        } else {
            gameFolders(state.gameProfiles).forEach { folder ->
                val open = folder.gameId in openFolders
                GameFolderCard(folder, open, onToggle = { toggleFolder(folder.gameId) })
                if (open) folder.profiles.forEach {
                    ProfileRow(
                        profile = SavedProfile.Game(it),
                        icon = Icons.Outlined.SportsEsports,
                        onRename = { p -> dialog = ProfileDialog.Rename(p) },
                        onDelete = { p -> dialog = ProfileDialog.Delete(p) },
                        game = Game.byId(it.gameId),   // <-- new
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(PwdeTheme.spacing.itemGap)) {
                            PwdeButton(
                                "Play",
                                { onPlayGameProfile(it.gameId, it.id) },
                                icon = Icons.Outlined.SportsEsports,
                                modifier = Modifier.weight(1f),
                                contentPadding = pairedButtonPadding(),
                            )
                            PwdeButton(
                                "Test",
                                { onTestGameProfile(it.gameId, it.id) },
                                style = ButtonStyle.SECONDARY,
                                modifier = Modifier.weight(1f),
                                contentPadding = pairedButtonPadding(),
                            )
                        }
                        PwdeButton(
                            "Edit buttons & mappings",
                            { onEditGameProfile(it.id) },
                            style = ButtonStyle.SECONDARY,
                            icon = Icons.Outlined.AutoAwesome,
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = pairedButtonPadding(),
                        )
                    }
                }
            }
        }

        SectionTitle("Settings")
        NavCard("Appearance", "Colors, text size, layout", Icons.Outlined.Palette, onEditAppearance)
        NavCard("Controls", "Input, gestures, voice", Icons.Outlined.Tune, onControls)
        SectionTitle("Onboarding")
        NavCard(
            "Redo onboarding",
            "Walk through permissions and cursor setup again",
            Icons.Outlined.RestartAlt,
            { confirmRedo = true },
        )
        InfoNote(
            "Your profiles, games and gestures are kept — this only asks for permissions again and " +
                "re-tunes the pointer. Use Appearance for colours and text size only.",
        )
    }

    if (confirmRedo) {
        AlertDialog(
            onDismissRequest = { confirmRedo = false },
            icon = { Icon(Icons.Outlined.RestartAlt, contentDescription = null) },
            title = { Text("Redo onboarding?") },
            text = {
                Text(
                    "You'll be asked for permissions again and walked through calibrating the pointer. " +
                        "Your saved profiles, games and gesture mappings are not deleted.",
                )
            },
            confirmButton = { PwdeButton("Redo onboarding", { confirmRedo = false; onRedoOnboarding() }, contentPadding = buttonPadding()) },
            dismissButton = { PwdeButton("Cancel", { confirmRedo = false }, style = ButtonStyle.SECONDARY, contentPadding = buttonPadding()) },
        )
    }
}

@Composable
private fun CalibrationProfileCarousel(
    profiles: List<CalibrationProfile>,
    activeProfileId: Long?,
    onActivate: (CalibrationProfile) -> Unit,
    onEdit: (Long) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { profiles.size })
    Column(verticalArrangement = Arrangement.spacedBy(PwdeTheme.spacing.itemGap)) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 12.dp),
            pageSpacing = PwdeTheme.spacing.itemGap,
            modifier = Modifier.fillMaxWidth(),
        ) { page ->
            val profile = profiles[page]
            val active = profile.id == activeProfileId
            GradientCard(
                Modifier.fillMaxWidth(),
                contentPadding = PwdeTheme.spacing.screenMargin,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(PwdeTheme.spacing.itemGap)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(profile.name, style = MaterialTheme.typography.titleLarge, color = PwdeTheme.colors.text)
                            Text(profile.inputMode.lowercase().replace('_', ' '), style = MaterialTheme.typography.bodyMedium, color = PwdeTheme.colors.textMuted)
                        }
                        if (active) StatusPill("Active", icon = Icons.Outlined.CheckCircle)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(PwdeTheme.spacing.itemGap)) {
                        PwdeButton(
                            if (active) "Active" else "Activate",
                            { onActivate(profile) },
                            modifier = Modifier.weight(1f),
                            icon = Icons.Outlined.CheckCircle,
                            enabled = !active,
                        )
                        PwdeButton(
                            "Edit",
                            { onEdit(profile.id) },
                            modifier = Modifier.weight(1f),
                            style = ButtonStyle.SECONDARY,
                            icon = Icons.Outlined.Edit,
                        )
                    }
                }
            }
        }
        if (profiles.size > 1) {
            Text(
                "Swipe to browse · ${pagerState.currentPage + 1} of ${profiles.size}",
                style = MaterialTheme.typography.labelMedium,
                color = PwdeTheme.colors.textMuted,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

/** One game's profiles, like a folder. */
private data class GameFolder(val gameId: String, val name: String, val profiles: List<GameProfile>)

/** Supported games first, in catalog order; profiles for games no longer listed come last. */
private fun gameFolders(profiles: List<GameProfile>): List<GameFolder> {
    val order = Game.entries.map { it.id }
    return profiles.groupBy { it.gameId }
        .map { (gameId, list) -> GameFolder(gameId, Game.byId(gameId)?.displayName ?: list.first().gameName, list) }
        .sortedWith(compareBy({ order.indexOf(it.gameId).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.name }))
}

@Composable
private fun GameFolderCard(folder: GameFolder, open: Boolean, onToggle: () -> Unit) {
    val colors = PwdeTheme.colors
    val spacing = PwdeTheme.spacing
    val count = "${folder.profiles.size} ${if (folder.profiles.size == 1) "profile" else "profiles"}"
    GradientCard(
        Modifier.fillMaxWidth().semantics { stateDescription = if (open) "Open" else "Closed" },
        selected = open,
        onClick = onToggle,
        contentPadding = spacing.screenMargin,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.itemGap)) {
            val game = Game.byId(folder.gameId)
            if (game != null) {
                GameThumbnail(game, fallbackIcon = if (open) Icons.Outlined.FolderOpen else Icons.Outlined.Folder)
            } else {
                IconBadge(if (open) Icons.Outlined.FolderOpen else Icons.Outlined.Folder)
            }
            Column(Modifier.weight(1f)) {
                Text(folder.name, style = MaterialTheme.typography.titleMedium, color = colors.text)
                Text(count, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            }
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null, tint = colors.primary)
        }
    }
}

/**
 * One saved profile (calibration or game) with rename and delete. Card padding and the gaps between
 * header, primary action and rename/delete all come from the spacing tokens.
 */
@Composable
private fun ProfileRow(
    profile: SavedProfile,
    icon: ImageVector,
    onRename: (SavedProfile) -> Unit,
    onDelete: (SavedProfile) -> Unit,
    game: Game? = null,          // <-- new
    primary: @Composable () -> Unit,
) {
    val colors = PwdeTheme.colors
    val spacing = PwdeTheme.spacing
    GradientCard(Modifier.fillMaxWidth(), contentPadding = spacing.screenMargin) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.itemGap)) {

            // NEW: show the full banner for game profiles, icon badge otherwise
            if (game != null) {
                GameArt(game)
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.itemGap)) {
                if (game == null) {
                    IconBadge(icon)
                }
                Column(Modifier.weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium, color = colors.text)
                    Text(profile.detail, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                }
            }
            primary()
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.itemGap)) {
                PwdeButton(
                    "Rename", { onRename(profile) }, style = ButtonStyle.SECONDARY, icon = Icons.Outlined.Edit,
                    modifier = Modifier.weight(1f), contentPadding = pairedButtonPadding(),
                )
                PwdeButton(
                    "Delete", { onDelete(profile) }, style = ButtonStyle.SECONDARY, icon = Icons.Outlined.Delete,
                    modifier = Modifier.weight(1f), contentPadding = pairedButtonPadding(),
                )
            }
        }
    }
}

/** Profile-screen button padding: roomier than PwdeButton's default 16 × 12dp, from the spacing tokens. */
@Composable
private fun buttonPadding() = PaddingValues(horizontal = PwdeTheme.spacing.screenMargin, vertical = PwdeTheme.spacing.internal)

/** Two buttons side by side: same vertical padding, narrower sides so labels don't wrap on small phones. */
@Composable
private fun pairedButtonPadding() = PaddingValues(horizontal = PwdeTheme.spacing.internal, vertical = PwdeTheme.spacing.internal)

@Composable
private fun RenameDialog(profile: SavedProfile, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember(profile) { mutableStateOf(profile.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PwdeTheme.colors.surface,
        title = { Text("Rename profile", color = PwdeTheme.colors.text) },
        text = { PwdeTextField(label = "Name", value = name, onValueChange = { name = it }) },
        confirmButton = { PwdeButton("Save", { onConfirm(name) }, enabled = name.isNotBlank(), contentPadding = buttonPadding()) },
        dismissButton = { PwdeButton("Cancel", onDismiss, style = ButtonStyle.SECONDARY, contentPadding = buttonPadding()) },
    )
}

@Composable
private fun DeleteDialog(profile: SavedProfile, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PwdeTheme.colors.surface,
        title = { Text("Delete \"${profile.name}\"?", color = PwdeTheme.colors.text) },
        text = { Text("This removes it from this phone. It can't be undone.", color = PwdeTheme.colors.textMuted) },
        confirmButton = { PwdeButton("Delete", onConfirm, style = ButtonStyle.DESTRUCTIVE, contentPadding = buttonPadding()) },
        dismissButton = { PwdeButton("Keep it", onDismiss, style = ButtonStyle.SECONDARY, contentPadding = buttonPadding()) },
    )
}
@Composable
private fun SyncCard(state: ProfileUiState, onSignIn: () -> Unit, onSignOut: () -> Unit) {
    val colors = PwdeTheme.colors
    val spacing = PwdeTheme.spacing
    // Same card padding and item gaps as the profile cards; every child is spaced evenly.
    GradientCard(Modifier.fillMaxWidth(), contentPadding = spacing.screenMargin) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.internal)) {
            when (state.syncStatus) {
                SyncStatus.LocalOnly -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.itemGap)) {
                        IconBadge(Icons.Outlined.PhoneAndroid)
                        Column(Modifier.weight(1f)) {
                            Text("Saved on this phone", style = MaterialTheme.typography.titleMedium, color = colors.text)
                            Text(
                                "Sign in to sync your profiles across devices. Nothing here is lost when you do.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textMuted,
                            )
                        }
                    }
                    SyncActionButton("Sign in to sync across devices", Icons.AutoMirrored.Outlined.Login, onSignIn)
                    if (!state.cloudAvailable) {
                        StatusPill("Accounts aren't set up in this build", color = colors.textMuted, icon = Icons.Outlined.CloudOff)
                    }
                }
                SyncStatus.NotAvailable -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.itemGap)) {
                        IconBadge(Icons.Outlined.CloudSync)
                        Column(Modifier.weight(1f)) {
                            Text("Sync status", style = MaterialTheme.typography.titleMedium, color = colors.text)
                            StatusPill("Cloud sync not available yet", color = colors.warning, icon = Icons.Outlined.CloudOff)
                        }
                    }
                    Text(
                        "You're signed in. Your profiles are safe on this phone; syncing them to the cloud is coming in a later update.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                    SyncActionButton("Sign out", Icons.AutoMirrored.Outlined.Logout, onSignOut)
                }
            }
        }
    }
}

/**
 * The sync card's action ("Sign in to sync across devices" / "Sign out"): 24dp top and bottom, so
 * it renders ≥ 68dp tall with centered text, clearly larger than the 56dp minimum; 20dp sides keep
 * the long label on one line on most phones.
 */
@Composable
private fun SyncActionButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    PwdeButton(
        text,
        onClick,
        style = ButtonStyle.SECONDARY,
        icon = icon,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = PwdeTheme.spacing.screenMargin, vertical = PwdeTheme.spacing.section),
    )
}
