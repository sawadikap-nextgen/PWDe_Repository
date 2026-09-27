package com.pwde.app.sensors.voice

import com.pwde.app.data.model.Game
import com.pwde.app.data.model.VoiceActivationMode
import com.pwde.app.data.model.VoiceMatchMode
import com.pwde.app.data.model.VoiceShortcut

enum class CommandScope {
    /** Works on every screen (back, home, …). */
    GLOBAL,

    /** Registered by the screen currently shown; wins over a global command with the same words. */
    SCREEN,
}

/** Something the user can say. Any of [phrases] triggers it. */
data class VoiceCommand(
    val id: String,
    val phrases: List<String>,
    val scope: CommandScope = CommandScope.SCREEN,
) {
    val label: String get() = phrases.first()

    constructor(id: String, vararg phrases: String, scope: CommandScope = CommandScope.SCREEN) :
        this(id, phrases.toList(), scope)
}

/** The standard commands available everywhere, plus the user's customisable shortcuts. */
object StandardCommands {
    val BACK = VoiceCommand("back", "back", "go back", scope = CommandScope.GLOBAL)
    val HOME = VoiceCommand("home", "home", "go home", scope = CommandScope.GLOBAL)
    val NEXT = VoiceCommand("next", "next", "next page", "continue", scope = CommandScope.GLOBAL)
    val SKIP = VoiceCommand("skip", "skip", scope = CommandScope.GLOBAL)
    val SETTINGS = VoiceCommand("settings", "settings", "open settings", scope = CommandScope.GLOBAL)
    val MENU = VoiceCommand("menu", "menu", "main menu", scope = CommandScope.GLOBAL)
    val CLOSE = VoiceCommand("close", "close", scope = CommandScope.GLOBAL)

    /**
     * Reads the current screen out loud (see `ScreenReaderViewModel`). App-wide on purpose: the
     * in-game spotter's keyword list is a shared budget, and this is not an in-game command.
     */
    val READ_SCREEN = VoiceCommand("read_screen", "read screen", "read this screen", "read the screen", scope = CommandScope.GLOBAL)

    val GAMES = VoiceCommand("open_games", "open games", "games", "game library", scope = CommandScope.GLOBAL)
    val GABAI = VoiceCommand(
        "open_gabai", "gabai", "gab ai", "gabay", "gabby", "gabby ai", "open gabai", "open gab ai",
        "open gabay", "open gabby", "talk to gabai", "talk to gab ai", "talk to gabby ai", "talk to gabay",
        scope = CommandScope.GLOBAL,
    )
    val PROFILE = VoiceCommand("open_profile", "open profile", "profile", "my profile", scope = CommandScope.GLOBAL)

    val all = listOf(BACK, HOME, NEXT, SKIP, SETTINGS, MENU, CLOSE, READ_SCREEN, GAMES, GABAI, PROFILE)

    /** "play <game>" from anywhere in PWDe launches that game with its last-played profile. */
    val playGames = Game.entries.map { VoiceCommand("play:${it.id}", listOf("play ${it.displayName}"), CommandScope.GLOBAL) }

    fun gameToPlay(command: VoiceCommand): Game? =
        command.id.takeIf { it.startsWith("play:") }?.let { Game.byId(it.removePrefix("play:")) }

    fun shortcutId(shortcut: VoiceShortcut) = "shortcut:${shortcut.name}"

    fun shortcutOf(command: VoiceCommand): VoiceShortcut? =
        VoiceShortcut.entries.firstOrNull { shortcutId(it) == command.id }

    fun shortcuts(phrases: Map<VoiceShortcut, String>): List<VoiceCommand> =
        VoiceShortcut.entries.mapNotNull { shortcut ->
            val phrase = phrases[shortcut]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            VoiceCommand(shortcutId(shortcut), listOf(phrase), CommandScope.GLOBAL)
        }
}

/**
 * Spoken assignment, e.g. naming a button or setting its voice trigger: "assign <words>" or
 * "use <words>" assigns the words; "retry" redoes the last assignment. Anything else isn't an
 * assignment, so stray speech never renames a button.
 */
object Dictation {
    val PREFIXES = listOf("assign", "use", "assigned", "a sign" , "name it", "call it")
    val RETRY = listOf("retry", "reassign", "try again")

    /** "save as <name>": name what's being saved and save it in one go. Bare "save" saves as is. */
    const val SAVE_AS = "save as"

    /** "save profile", "save game profile"…: the button's own words, not a name. */
    private val SAVE_BUTTON_WORDS = setOf("profile", "game profile", "calibration profile", "calibration", "it")

    sealed interface Parsed {
        data class Assign(val words: String) : Parsed
        object Retry : Parsed
        /** [name] is null for a bare "save". */
        data class SaveAs(val name: String?) : Parsed
    }

    fun parse(text: String): Parsed? {
        val heard = CommandMatcher.normalize(text)
        if (heard in RETRY) return Parsed.Retry
        if (heard == "save" || heard.startsWith("save ")) {
            val rest = heard.removePrefix("save").trim().removePrefix("as").trim()
            return Parsed.SaveAs(rest.takeUnless { it.isEmpty() || it in SAVE_BUTTON_WORDS })
        }
        val prefix = PREFIXES.firstOrNull { heard.startsWith("$it ") } ?: return null
        return heard.removePrefix(prefix).trim().takeIf { it.isNotEmpty() }?.let(Parsed::Assign)
    }

    /**
     * True as soon as an utterance starts like an assignment, even as a partial transcript, so
     * the words being assigned ("use move left") can't fire commands on the way.
     */
    fun isAssignment(text: String): Boolean {
        val heard = CommandMatcher.normalize(text)
        // A bare "save" is held too: it may still grow into "save as <name>", and saving on the
        // partial would save before the name arrives. Its final transcript saves via [parse].
        // Any "save…" is held until it's finished: partials arrive as "save", "save a", "save as fa…",
        // and one of those matching the plain Save command saved before the name was heard.
        if (heard == "save" || heard.startsWith("save ")) return true
        return PREFIXES.any { heard == it || heard.startsWith("$it ") } || heard.substringBefore(' ') in PREFIXES
    }
}

/** Matches heard text against commands. Pure, so every rule here is unit-tested. */
object CommandMatcher {
    fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s']"), " ")
            .replace("'", "")
            .trim()
            .replace(Regex("\\s+"), " ")

    /**
     * Screen commands are tried before global ones, unless a global phrase is strictly longer
     * ("play mobile legends" beats a screen's "mobile legends"); within a group the longest
     * matching phrase wins, so "next page" beats "next". Each speech hypothesis is tried in order.
     */
    fun match(hypotheses: List<String>, commands: List<VoiceCommand>, mode: VoiceMatchMode): VoiceCommand? {
        val screen = commands.filter { it.scope == CommandScope.SCREEN }
        val global = commands.filter { it.scope == CommandScope.GLOBAL }
        for (hypothesis in hypotheses) {
            val heard = normalize(hypothesis)
            if (heard.isEmpty()) continue
            val onScreen = bestMatch(heard, screen, mode)
            val everywhere = bestMatch(heard, global, mode)
            val best = when {
                onScreen == null -> everywhere
                everywhere != null && everywhere.second > onScreen.second -> everywhere
                else -> onScreen
            }
            best?.let { return it.first }
        }
        // Nothing matched a whole phrase: accept a shortened one ("missed ones" for "try the missed
        // ones again"), but only when exactly one command on this screen could mean it.
        for (hypothesis in hypotheses) {
            shortenedMatch(normalize(hypothesis), screen)?.let { return it }
        }
        return null
    }

    /**
     * The one screen command whose phrase contains [heard] as a run of whole words, or null when
     * none or several do. Filler words alone ("the", "one") never count, so noise can't press anything.
     */
    private fun shortenedMatch(heard: String, commands: List<VoiceCommand>): VoiceCommand? {
        val words = heard.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty() || words.all { it in FILLER_WORDS }) return null
        val candidates = commands.filter { command ->
            command.phrases.any { " ${normalize(it)} ".contains(" $heard ") }
        }
        return candidates.singleOrNull()
    }

    /** Words too common to pick a command on their own. */
    private val FILLER_WORDS = setOf(
        "a", "an", "the", "to", "it", "is", "of", "on", "in", "up", "my", "i", "me", "this", "that", "one", "and",
        "or", "with", "for", "as", "at", "by", "use", "go", "do", "have", "please", "again",
    )

    fun match(text: String, commands: List<VoiceCommand>, mode: VoiceMatchMode): VoiceCommand? =
        match(listOf(text), commands, mode)

    /** The best command and the length of the phrase that matched. */
    private fun bestMatch(heard: String, commands: List<VoiceCommand>, mode: VoiceMatchMode): Pair<VoiceCommand, Int>? {
        var best: VoiceCommand? = null
        var bestLength = -1
        for (command in commands) {
            for (phrase in command.phrases) {
                val p = normalize(phrase)
                if (p.isEmpty()) continue
                val hit = when (mode) {
                    VoiceMatchMode.EXACT -> heard == p
                    VoiceMatchMode.WORD_ANYWHERE -> " $heard ".contains(" $p ")
                }
                if (hit && p.length > bestLength) {
                    best = command
                    bestLength = p.length
                }
            }
        }
        return best?.let { it to bestLength }
    }
}

/**
 * Decides when a match actually fires, per utterance.
 * - IMMEDIATE: on the first (partial) transcript that matches; the same command won't fire again
 *   when the final transcript arrives.
 * - AFTER_FINISH: only on the final transcript.
 */
class VoiceActivationGate {
    private val firedThisUtterance = mutableSetOf<String>()

    fun offer(match: VoiceCommand?, isFinal: Boolean, mode: VoiceActivationMode): VoiceCommand? {
        val fire = when {
            match == null -> null
            mode == VoiceActivationMode.AFTER_FINISH -> match.takeIf { isFinal }
            match.id in firedThisUtterance -> null
            else -> match
        }
        if (fire != null) firedThisUtterance += fire.id
        if (isFinal) firedThisUtterance.clear()
        return fire
    }

    fun reset() = firedThisUtterance.clear()
}
