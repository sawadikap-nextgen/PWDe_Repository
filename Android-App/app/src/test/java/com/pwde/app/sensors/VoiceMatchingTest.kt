package com.pwde.app.sensors

import com.pwde.app.data.model.VoiceActivationMode
import com.pwde.app.data.model.VoiceMatchMode
import com.pwde.app.data.model.VoiceShortcut
import com.pwde.app.play.GameInput
import com.pwde.app.sensors.voice.CommandMatcher
import com.pwde.app.sensors.voice.CommandScope
import com.pwde.app.sensors.voice.Dictation
import com.pwde.app.sensors.voice.StandardCommands
import com.pwde.app.sensors.voice.VoiceActivationGate
import com.pwde.app.sensors.voice.VoiceCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandMatcherTest {
    private val attack = VoiceCommand("attack", "attack")
    private val nextPage = VoiceCommand("next_page", "next page")
    private val commands = StandardCommands.all + attack + nextPage

    @Test
    fun exactNeedsTheWholeUtterance() {
        assertEquals(attack, CommandMatcher.match("Attack!", commands, VoiceMatchMode.EXACT))
        assertNull(CommandMatcher.match("go attack now", commands, VoiceMatchMode.EXACT))
    }

    @Test
    fun aShortenedLabelWorksWhenOnlyOneCommandFits() {
        val retry = VoiceCommand("retry", "try the missed ones again")
        val pick = VoiceCommand("pick", "choose a different one")
        val blank = VoiceCommand("blank", "use a blank screen")
        val screen = listOf(retry, pick, blank)
        assertEquals(retry, CommandMatcher.match("missed ones", screen, VoiceMatchMode.EXACT))
        assertEquals(pick, CommandMatcher.match("different one", screen, VoiceMatchMode.EXACT))
        assertEquals(blank, CommandMatcher.match("blank screen", screen, VoiceMatchMode.EXACT))
        // Filler alone, words out of order, or a fit for two commands press nothing.
        assertNull(CommandMatcher.match("one", screen, VoiceMatchMode.EXACT))
        assertNull(CommandMatcher.match("screen blank", screen, VoiceMatchMode.EXACT))
        assertNull(CommandMatcher.match("move", listOf(VoiceCommand("l", "move left"), VoiceCommand("r", "move right")), VoiceMatchMode.EXACT))
    }

    @Test
    fun anywhereFindsTheWordInASentence() {
        assertEquals(attack, CommandMatcher.match("go attack now", commands, VoiceMatchMode.WORD_ANYWHERE))
    }

    @Test
    fun anywhereMatchesWholeWordsOnly() {
        assertNull(CommandMatcher.match("counterattacking", listOf(attack), VoiceMatchMode.WORD_ANYWHERE))
    }

    @Test
    fun screenCommandsBeatGlobalOnes() {
        // "next page" (screen) and "next page" (global NEXT phrase) both match; the screen wins.
        assertEquals(nextPage, CommandMatcher.match("next page please", commands, VoiceMatchMode.WORD_ANYWHERE))
    }

    @Test
    fun aLongerGlobalPhraseBeatsAShorterScreenOne() {
        val openGame = VoiceCommand("game:mobile_legends", "mobile legends", scope = CommandScope.SCREEN)
        val play = StandardCommands.playGames.first { it.id == "play:mobile_legends" }
        assertEquals(play, CommandMatcher.match("play mobile legends", listOf(openGame) + StandardCommands.playGames, VoiceMatchMode.WORD_ANYWHERE))
        assertEquals(openGame, CommandMatcher.match("mobile legends", listOf(openGame) + StandardCommands.playGames, VoiceMatchMode.WORD_ANYWHERE))
    }

    @Test
    fun longestPhraseWins() {
        val back = VoiceCommand("go_back_screen", "go back", scope = CommandScope.SCREEN)
        val go = VoiceCommand("go", "go", scope = CommandScope.SCREEN)
        assertEquals(back, CommandMatcher.match("go back", listOf(go, back), VoiceMatchMode.WORD_ANYWHERE))
    }

    @Test
    fun globalsStillWorkWithoutScreenCommands() {
        assertEquals(StandardCommands.BACK, CommandMatcher.match("Go back.", StandardCommands.all, VoiceMatchMode.EXACT))
        assertEquals(StandardCommands.HOME, CommandMatcher.match("take me home", StandardCommands.all, VoiceMatchMode.WORD_ANYWHERE))
    }

    @Test
    fun gabaiNameVariantsOpenTheAssistant() {
        listOf("Gab AI", "Gabay", "open GabAI", "talk to Gabby AI").forEach { phrase ->
            assertEquals(StandardCommands.GABAI, CommandMatcher.match(phrase, StandardCommands.all, VoiceMatchMode.EXACT))
        }
    }

    /**
     * "read screen" is app-wide on purpose: the in-game spotter's keyword list is a shared budget (see
     * `InGameKeywordMapTest`), so an app command must never be added to `GameInput.STANDARD_BINDINGS`.
     */
    @Test
    fun readScreenIsAGlobalCommandThatStaysOutOfTheInGameList() {
        assertEquals(StandardCommands.READ_SCREEN, CommandMatcher.match("read screen", StandardCommands.all, VoiceMatchMode.EXACT))
        assertEquals(StandardCommands.READ_SCREEN, CommandMatcher.match("read this screen please", StandardCommands.all, VoiceMatchMode.WORD_ANYWHERE))
        assertEquals(CommandScope.GLOBAL, StandardCommands.READ_SCREEN.scope)
        assertTrue("must not reach the spotter", GameInput.STANDARD_BINDINGS.none { "read screen" in it.phrases })
    }

    @Test
    fun laterHypothesesAreTriedWhenTheFirstDoesNotMatch() {
        val result = CommandMatcher.match(listOf("a tack", "attack"), commands, VoiceMatchMode.EXACT)
        assertEquals(attack, result)
    }

    @Test
    fun normalizesCaseAndPunctuation() {
        assertEquals("dont stop me now", CommandMatcher.normalize("  Don't  STOP, me now! "))
    }

    @Test
    fun userShortcutsBecomeGlobalCommands() {
        val shortcuts = StandardCommands.shortcuts(mapOf(VoiceShortcut.JOYSTICK_MODE to "stick please", VoiceShortcut.CURSOR_MODE to ""))
        assertEquals(1, shortcuts.size)
        val matched = CommandMatcher.match("stick please", shortcuts, VoiceMatchMode.EXACT)!!
        assertEquals(VoiceShortcut.JOYSTICK_MODE, StandardCommands.shortcutOf(matched))
    }
}

class VoiceActivationGateTest {
    private val attack = VoiceCommand("attack", "attack")

    @Test
    fun immediateFiresOnThePartialAndNotAgainOnTheFinal() {
        val gate = VoiceActivationGate()
        assertEquals(attack, gate.offer(attack, isFinal = false, mode = VoiceActivationMode.IMMEDIATE))
        assertNull(gate.offer(attack, isFinal = false, mode = VoiceActivationMode.IMMEDIATE))
        assertNull(gate.offer(attack, isFinal = true, mode = VoiceActivationMode.IMMEDIATE))
        // A new utterance can fire it again.
        assertEquals(attack, gate.offer(attack, isFinal = false, mode = VoiceActivationMode.IMMEDIATE))
    }

    @Test
    fun immediateStillFiresIfOnlyTheFinalMatches() {
        val gate = VoiceActivationGate()
        assertNull(gate.offer(null, isFinal = false, mode = VoiceActivationMode.IMMEDIATE))
        assertEquals(attack, gate.offer(attack, isFinal = true, mode = VoiceActivationMode.IMMEDIATE))
    }

    @Test
    fun afterFinishWaitsForTheFinal() {
        val gate = VoiceActivationGate()
        assertNull(gate.offer(attack, isFinal = false, mode = VoiceActivationMode.AFTER_FINISH))
        assertEquals(attack, gate.offer(attack, isFinal = true, mode = VoiceActivationMode.AFTER_FINISH))
    }

    @Test
    fun afterFinishIgnoresAPartialMatchTheFinalDropped() {
        val gate = VoiceActivationGate()
        gate.offer(attack, isFinal = false, mode = VoiceActivationMode.AFTER_FINISH)
        assertNull(gate.offer(null, isFinal = true, mode = VoiceActivationMode.AFTER_FINISH))
    }
}

class DictationTest {
    @Test
    fun saveAsNamesAndSaves() {
        assertEquals(Dictation.Parsed.SaveAs("fanny"), Dictation.parse("Save as Fanny"))
        assertEquals(Dictation.Parsed.SaveAs(null), Dictation.parse("save"))
        // Held while spoken, so a partial "save" can't save before the name arrives.
        assertTrue(Dictation.isAssignment("save"))
        assertTrue(Dictation.isAssignment("save as fan"))
        assertTrue(Dictation.isAssignment("save a"))
        assertEquals(Dictation.Parsed.SaveAs(null), Dictation.parse("save game profile"))
        assertEquals(Dictation.Parsed.SaveAs("fanny 4 skill"), Dictation.parse("save fanny 4 skill"))
    }

    @Test
    fun assignAndUseAssignTheRestOfTheUtterance() {
        assertEquals(Dictation.Parsed.Assign("skill one"), Dictation.parse("Assign skill one"))
        assertEquals(Dictation.Parsed.Assign("move left"), Dictation.parse("use move left!"))
    }

    @Test
    fun retryWordsRedo() {
        assertEquals(Dictation.Parsed.Retry, Dictation.parse("Retry"))
        assertEquals(Dictation.Parsed.Retry, Dictation.parse("reassign"))
        assertEquals(Dictation.Parsed.Retry, Dictation.parse("try again"))
    }

    @Test
    fun otherSpeechIsNotAnAssignment() {
        assertNull(Dictation.parse("skill one"))
        assertNull(Dictation.parse("assign"))
        assertNull(Dictation.parse("user interface"))
        assertNull(Dictation.parse("reassign skill"))
    }

    @Test
    fun partialTranscriptsAreRecognisedEarly() {
        assertEquals(true, Dictation.isAssignment("use"))
        assertEquals(true, Dictation.isAssignment("assign move"))
        assertEquals(false, Dictation.isAssignment("move left"))
        assertEquals(false, Dictation.isAssignment("user"))
    }
}
