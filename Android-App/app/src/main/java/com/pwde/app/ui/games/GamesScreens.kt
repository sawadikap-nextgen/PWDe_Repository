package com.pwde.app.ui.games

import com.pwde.app.data.local.GameProfile
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pwde.app.data.local.ProfileRepository
import com.pwde.app.data.model.Game
import com.pwde.app.ui.components.ButtonStyle
import com.pwde.app.ui.components.GradientCard
import com.pwde.app.ui.components.InfoNote
import com.pwde.app.ui.components.MainTab
import com.pwde.app.ui.components.PlaceholderNotice
import com.pwde.app.ui.components.PwdeBottomNav
import com.pwde.app.ui.components.PwdeButton
import com.pwde.app.ui.components.PwdeDialog
import com.pwde.app.ui.components.PwdeTextField
import com.pwde.app.ui.components.PwdeScreen
import com.pwde.app.ui.components.SectionTitle
import com.pwde.app.ui.components.StatusPill
import com.pwde.app.ui.components.VoiceCommandsEffect
import com.pwde.app.ui.components.voiceCommand
import com.pwde.app.ui.theme.PwdeShapes
import com.pwde.app.ui.theme.PwdeTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.pwde.app.data.games.CustomGamesRepository
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import com.pwde.app.R
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import com.pwde.app.ui.components.IconBadge
import androidx.compose.ui.unit.Dp
/**
 * Small square game artwork for list rows, matching IconBadge's size and shape.
 * Falls back to [fallbackIcon] (shown in an IconBadge) when no artwork is bundled.
 */
@Composable
internal fun GameThumbnail(
    game: Game,
    fallbackIcon: ImageVector,
    size: Dp = 40.dp,
) {
    val res = gameArtRes(game)
    if (res == null) {
        IconBadge(fallbackIcon)
        return
    }
    val shape = RoundedCornerShape(12.dp) // keep in sync with IconBadge
    Image(
        painter = painterResource(id = res),
        contentDescription = "${game.displayName} artwork",
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(size)
            .clip(shape)
            .border(1.dp, PwdeTheme.colors.borderBrush, shape),
    )
}
/** Which games already have a saved game profile (from Room), and the games the user added. */
class GamesViewModel(
    profileRepository: ProfileRepository,
    private val customGamesRepository: CustomGamesRepository = CustomGamesRepository.InMemory(),
) : ViewModel() {
    val gamesWithProfiles: StateFlow<Set<String>> = profileRepository.gameProfiles
        .map { profiles -> profiles.map { it.gameId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val customGames: StateFlow<List<String>> = customGamesRepository.names
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addGame(name: String) {
        viewModelScope.launch { customGamesRepository.add(name) }
    }
}

/** Say a game's name to open it; say a tab's name to switch tabs. */
internal fun gameCommands(current: MainTab) = Game.entries.map { voiceCommand("game:${it.id}", it.displayName) } +
        MainTab.entries.filter { it != current }.map { voiceCommand("tab:${it.name}", it.label) }

@Composable
private fun GameListVoice(current: MainTab, onGame: (Game) -> Unit, onTab: (MainTab) -> Unit) {
    val commands = remember(current) { gameCommands(current) }
    VoiceCommandsEffect(commands) { id ->
        when {
            id.startsWith("game:") -> Game.byId(id.removePrefix("game:"))?.let(onGame)
            id.startsWith("tab:") -> onTab(MainTab.valueOf(id.removePrefix("tab:")))
        }
    }
}

/** [compact] is the half-width grid cell: taller art, and the status pill under the name instead of beside it. */
@Composable
fun GameCard(game: Game, hasProfile: Boolean, compact: Boolean = false, onClick: () -> Unit) {
    val colors = PwdeTheme.colors
    val status: @Composable () -> Unit = {
        if (hasProfile) StatusPill("Profile ready", icon = Icons.Outlined.CheckCircle)
        else StatusPill("No profile yet", color = colors.textMuted)
    }
    GradientCard(if (compact) Modifier.fillMaxWidth().fillMaxHeight() else Modifier.fillMaxWidth(), onClick = onClick) {
        GameArt(game, aspectRatio = if (compact) GRID_ART_ASPECT else 2.4f)
        if (compact) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(game.displayName, style = MaterialTheme.typography.titleMedium, color = colors.text, maxLines = 2)
                Text(game.genre, style = MaterialTheme.typography.bodySmall, color = colors.textMuted, maxLines = 1)
                status()
            }
        } else {
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(game.displayName, style = MaterialTheme.typography.titleLarge, color = colors.text)
                    Text(game.genre, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                }
                status()
            }
        }
    }
}

/** At half width the full-width 2.4:1 banner is a thin strip; a squarer crop keeps the artwork readable. */
private const val GRID_ART_ASPECT = 1.5f

/** Rows of two for [GameGrid]; the last row holds one game when the count is odd. */
internal fun <T> gridRows(items: List<T>): List<List<T>> = items.chunked(2)

/** One cell of [GameGrid]: a supported game, or one the user added by name. */
internal sealed interface GridEntry {
    data class Supported(val game: Game) : GridEntry
    data class Added(val name: String) : GridEntry
    /** The "Add a game" card, always last. */
    data object AddGame : GridEntry
}

/** Supported games first, then the ones the user added, in the order they were added. */
internal fun gridEntries(games: List<Game>, customGames: List<String>, withAddCard: Boolean = false): List<GridEntry> =
    games.map { GridEntry.Supported(it) } + customGames.map { GridEntry.Added(it) } + listOfNotNull(GridEntry.AddGame.takeIf { withAddCard })

/** Games two per row. An odd one out keeps half width, with an empty slot beside it. */
@Composable
fun GameGrid(
    games: List<Game>,
    hasProfile: (Game) -> Boolean,
    onGame: (Game) -> Unit,
    customGames: List<String> = emptyList(),
    /** Tapping an added game: map its buttons by hand. */
    onAddedGame: (name: String) -> Unit = {},
    /** When set, an "Add a game" card ends the grid, same size as the game cards. */
    onAddGame: ((name: String) -> Unit)? = null,
) {
    val gap = PwdeTheme.spacing.itemGap
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
        gridRows(gridEntries(games, customGames, withAddCard = onAddGame != null)).forEach { row ->
            // Cards in a row share the tallest one's height, so a long name doesn't leave them ragged.
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { entry ->
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        when (entry) {
                            is GridEntry.Supported -> GameCard(entry.game, hasProfile(entry.game), compact = true) { onGame(entry.game) }
                            is GridEntry.Added -> AddedGameCard(entry.name) { onAddedGame(entry.name) }
                            GridEntry.AddGame -> onAddGame?.let { AddGameCard(it) }
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** The name to submit from the Add-a-game field, or null while it's blank (which keeps "Add" disabled). */
internal fun addGameName(input: String): String? = input.trim().takeIf { it.isNotBlank() }

/**
 * Entry point for adding a game: the card opens a name-entry dialog and hands the trimmed name to
 * [onAddGame], which saves it as an [AddedGameCard]. The dialog's state is local, like the Games
 * screen's other UI state.
 */
@Composable
fun AddGameCard(onAddGame: (name: String) -> Unit) {
    val colors = PwdeTheme.colors
    var open by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    val close = { open = false; name = "" }
    // Laid out like a compact GameCard (art, name, line, pill) so it sits in the grid as an equal.
    GradientCard(Modifier.fillMaxWidth().fillMaxHeight(), onClick = { open = true }) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(GRID_ART_ASPECT)
                .clip(PwdeShapes.button)
                .border(1.5.dp, colors.primary.copy(alpha = 0.6f), PwdeShapes.button),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, tint = colors.primary, modifier = Modifier.size(40.dp))
        }
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Add a game", style = MaterialTheme.typography.titleMedium, color = colors.text, maxLines = 2)
            Text("Any game, by name", style = MaterialTheme.typography.bodySmall, color = colors.textMuted, maxLines = 1)
            StatusPill("Map it yourself", color = colors.textMuted)
        }
    }
    if (open) {
        val submitted = addGameName(name)
        PwdeDialog(onDismiss = close, title = "Add a game") {
            PwdeTextField("Game name", name, { name = it }, Modifier.fillMaxWidth())
            PwdeButton(
                "Add",
                onClick = { submitted?.let { onAddGame(it); close() } },
                enabled = submitted != null,
                modifier = Modifier.fillMaxWidth(),
            )
            PwdeButton("Cancel", close, style = ButtonStyle.SECONDARY, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * A game the user added by name: same shape as a compact [GameCard], with plain artwork. It can't
 * be launched yet (see [CustomGamesRepository]), but tapping it maps its buttons by hand.
 */
@Composable
fun AddedGameCard(name: String, onClick: () -> Unit) {
    val colors = PwdeTheme.colors
    GradientCard(Modifier.fillMaxWidth().fillMaxHeight(), onClick = onClick) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(GRID_ART_ASPECT)
                .clip(PwdeShapes.button)
                .background(Brush.linearGradient(listOf(colors.secondary.copy(alpha = 0.6f), colors.primary.copy(alpha = 0.35f)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.SportsEsports, contentDescription = null, tint = colors.text, modifier = Modifier.size(40.dp))
        }
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = colors.text, maxLines = 2)
            Text("Tap to map buttons", style = MaterialTheme.typography.bodySmall, color = colors.textMuted, maxLines = 1)
            StatusPill("Not supported yet", color = colors.textMuted)
        }
    }
}

/** Returns the drawable resource for a game's artwork, or null if none is bundled. */
internal fun gameArtRes(game: Game): Int? = when (game) {
    Game.MOBILE_LEGENDS -> R.drawable.mobile_legends
    Game.CLASH_ROYALE -> R.drawable.clash_royale
    else -> null
}

/** Game artwork with a scrim so the title/status text stays readable. Falls back to a gradient + icon. */
@Composable
internal fun GameArt(game: Game, aspectRatio: Float = 2.4f) {
    val colors = PwdeTheme.colors
    val res = gameArtRes(game)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .clip(PwdeShapes.button)
            .background(
                Brush.linearGradient(
                    listOf(
                        colors.secondary.copy(alpha = 0.6f),
                        colors.primary.copy(alpha = 0.35f),
                    )
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (res != null) {
            Image(
                painter = painterResource(id = res),
                contentDescription = "${game.displayName} artwork",
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
            // Dark scrim so text/icons drawn on top remain legible.
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.55f),
                            )
                        )
                    )
            )
        } else {
            val icon: ImageVector =
                if (game == Game.CLASH_ROYALE) Icons.Outlined.Style
                else Icons.Outlined.SportsEsports
            Icon(
                icon,
                contentDescription = null,
                tint = colors.text,
                modifier = Modifier.size(56.dp),
            )
        }
    }
}
/** A game's saved profiles, newest first. */
class GameDetailViewModel(profileRepository: ProfileRepository, game: Game) : ViewModel() {
    val profiles: StateFlow<List<GameProfile>> = profileRepository.gameProfilesFor(game.id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/**
 * D3 Game detail: play the real game with a saved game profile (or without one), test a profile on
 * the simulated preview, edit one, or make one with GabAI.
 */
@Composable
fun GameDetailScreen(
    game: Game,
    viewModel: GameDetailViewModel,
    onBack: () -> Unit,
    onPlay: (profileId: Long?) -> Unit,
    onTestProfile: (profileId: Long) -> Unit,
    onEditProfile: (profileId: Long) -> Unit,
    onSetUpWithGabAi: () -> Unit,
) {
    val colors = PwdeTheme.colors
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val newest = profiles.firstOrNull()
    VoiceCommandsEffect(GAME_DETAIL_COMMANDS) { id -> if (id == "play") onPlay(newest?.id) else onSetUpWithGabAi() }
    PwdeScreen(
        title = game.displayName,
        subtitle = game.genre,
        onBack = onBack,
        voiceHint = "Say \"play\" or \"set up with GabAI\"",
        footer = {
            PwdeButton(
                if (newest != null) "Play with \"${newest.profileName}\"" else "Play ${game.displayName} with PWDe",
                { onPlay(newest?.id) },
                icon = Icons.Outlined.SportsEsports,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        GameArt(game)
        Text(game.description, style = MaterialTheme.typography.bodyLarge, color = colors.text)
        if (profiles.isEmpty()) {
            InfoNote("No game profile yet. GabAI will walk you through mapping this game's buttons to your head, face and voice.")
        } else {
            SectionTitle("Your profiles for this game")
            profiles.forEach { profile ->
                GradientCard(Modifier.fillMaxWidth()) {
                    Text(profile.profileName, style = MaterialTheme.typography.titleMedium, color = colors.text)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                        PwdeButton("Play", { onPlay(profile.id) }, icon = Icons.Outlined.SportsEsports, modifier = Modifier.weight(1f))
                        PwdeButton("Test", { onTestProfile(profile.id) }, style = ButtonStyle.SECONDARY, modifier = Modifier.weight(1f))
                        PwdeButton("Edit", { onEditProfile(profile.id) }, style = ButtonStyle.SECONDARY, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        PwdeButton(
            if (profiles.isEmpty()) "Set up with GabAI" else "New profile with GabAI",
            onSetUpWithGabAi,
            style = ButtonStyle.SECONDARY,
            icon = Icons.Outlined.AutoAwesome,
            modifier = Modifier.fillMaxWidth(),
        )
        InfoNote(
            "Play opens ${game.displayName} and keeps PWDe running on top of it, with a notification to pause or stop. " +
                    "Test tries a profile on its screenshot inside PWDe first.",
            icon = Icons.Outlined.Info,
        )
    }
}

private enum class GameFilter(val label: String, val matches: (Game, Set<String>) -> Boolean) {
    ALL("All games", { _, _ -> true }),
    STRATEGY("Strategy", { g, _ -> g.genre == "Strategy" }),
    MOBA("MOBA", { g, _ -> g.genre == "MOBA" }),
    READY("Profile ready", { g, ids -> g.id in ids }),
}

internal val GAME_DETAIL_COMMANDS = listOf(
    voiceCommand("play", "play", "launch game", "launch", "start"),
    voiceCommand("gabai", "set up with gabai", "new profile with gabai", "new profile", "gabai", "gab ai"),
)

/** D2 Games: narrow the game list by genre or setup status; tiles show their setup status. */
@Composable
fun GamesScreen(viewModel: GamesViewModel, onGame: (Game) -> Unit, onTab: (MainTab) -> Unit, onMapAddedGame: (name: String) -> Unit = {}) {
    val withProfiles by viewModel.gamesWithProfiles.collectAsStateWithLifecycle()
    val customGames by viewModel.customGames.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(GameFilter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    val results = Game.entries.filter { game ->
        filter.matches(game, withProfiles) &&
                (query.isBlank() || listOf(game.displayName, game.genre, game.description).any {
                    it.contains(query.trim(), ignoreCase = true)
                })
    }
    // Added games have no genre or profile, so only "All games" and the search reach them.
    val addedResults = if (filter != GameFilter.ALL) emptyList() else customGames.filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }
    val count = results.size + addedResults.size
    GameListVoice(MainTab.GAMES, onGame, onTab)
    val filterCommands = remember { GameFilter.entries.map { voiceCommand(it.name, it.label) } }
    VoiceCommandsEffect(filterCommands) { id -> filter = GameFilter.valueOf(id) }
    PwdeScreen(
        title = "Games",
        subtitle = "Pick a game to play or set up. Filter by type or setup status.",
        voiceHint = "Say a game's or filter's name",
        bottomBar = { PwdeBottomNav(MainTab.GAMES, onTab) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Search games") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Clear search")
                        }
                    }
                } else null,
                shape = PwdeShapes.field,
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = PwdeTheme.colors.text,
                    unfocusedTextColor = PwdeTheme.colors.text,
                    cursorColor = PwdeTheme.colors.primary,
                    focusedBorderColor = PwdeTheme.colors.primary,
                    unfocusedBorderColor = PwdeTheme.colors.secondary.copy(alpha = 0.6f),
                ),
            )
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Outlined.FilterList, contentDescription = null)
                    Text("Filter", modifier = Modifier.padding(start = 4.dp))
                    Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    GameFilter.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                filter = option
                                menuExpanded = false
                            },
                            trailingIcon = if (option == filter) {
                                { Icon(Icons.Outlined.CheckCircle, contentDescription = "Selected") }
                            } else null,
                        )
                    }
                }
            }
        }
        SectionTitle("$count ${if (count == 1) "game" else "games"}")
        if (count == 0) InfoNote("No games match this search and filter.")
        GameGrid(results, hasProfile = { it.id in withProfiles }, onGame = onGame, customGames = addedResults, onAddedGame = onMapAddedGame, onAddGame = viewModel::addGame)
    }
}