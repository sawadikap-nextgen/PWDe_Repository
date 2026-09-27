package com.pwde.app.data.games

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pwde.app.data.model.Game
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Games the user added by name. They show up as game cards but aren't playable: [Game] stays the
 * fixed enum of supported games. Making an added game playable is a separate, larger change.
 */
interface CustomGamesRepository {
    /** Added names, oldest first. */
    val names: Flow<List<String>>

    /** Adds [name] (trimmed). Returns false, adding nothing, if it's blank or already a game. */
    suspend fun add(name: String): Boolean

    /** Keeps added games in memory only; the default where nothing is persisted (tests, previews). */
    class InMemory : CustomGamesRepository {
        private val list = MutableStateFlow<List<String>>(emptyList())
        override val names: Flow<List<String>> = list
        override suspend fun add(name: String): Boolean {
            val added = withAddedGame(list.value, name) ?: return false
            list.update { added }
            return true
        }
    }
}

/** [current] plus [name], or null when [name] is blank or matches a supported or added game (ignoring case). */
internal fun withAddedGame(current: List<String>, name: String): List<String>? {
    val trimmed = name.trim()
    if (trimmed.isBlank()) return null
    val taken = Game.entries.map { it.displayName } + current
    if (taken.any { it.equals(trimmed, ignoreCase = true) }) return null
    return current + trimmed
}

/**
 * Game ids for added games, so they flow through button mapping and saved profiles like supported
 * ones. `Game.byId` returns null for them: anything that needs a real, launchable game checks that.
 */
object CustomGameId {
    private const val PREFIX = "custom:"
    fun of(name: String): String = PREFIX + name
    /** The added game's name, or null if [id] isn't an added game's. */
    fun nameOf(id: String?): String? = id?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)
}

/** What to call the game [id] names: a supported game's display name, or an added game's own name. */
fun gameDisplayName(id: String?): String? = Game.byId(id)?.displayName ?: CustomGameId.nameOf(id)

class DataStoreCustomGamesRepository(private val dataStore: DataStore<Preferences>) : CustomGamesRepository {
    private val gson = Gson()
    private val listType = object : TypeToken<List<String>>() {}.type

    override val names: Flow<List<String>> = dataStore.data.map { decode(it[KEY]) }.distinctUntilChanged()

    override suspend fun add(name: String): Boolean {
        var added = false
        dataStore.edit { prefs ->
            withAddedGame(decode(prefs[KEY]), name)?.let {
                prefs[KEY] = gson.toJson(it)
                added = true
            }
        }
        return added
    }

    private fun decode(json: String?): List<String> =
        json?.let { runCatching { gson.fromJson<List<String>>(it, listType) }.getOrNull() }.orEmpty()

    private companion object {
        val KEY = stringPreferencesKey("custom_game_names")
    }
}
