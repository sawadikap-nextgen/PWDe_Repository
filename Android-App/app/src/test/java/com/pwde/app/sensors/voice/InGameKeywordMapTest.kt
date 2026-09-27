package com.pwde.app.sensors.voice

import com.pwde.app.play.GameInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InGameKeywordMapTest {
    @Test
    fun everyPhraseMapsToItsCommand() {
        val map = inGameKeywordMap(listOf(VoiceCommandBinding("button:1", listOf("attack", "hit"))))
        assertEquals(mapOf("attack" to "button:1", "hit" to "button:1"), map)
    }

    @Test
    fun phrasesAreNormalizedTheWaySpeechIs() {
        val map = inGameKeywordMap(listOf(VoiceCommandBinding("button:1", listOf("  Don't  STOP! "))))
        assertEquals(mapOf("dont stop" to "button:1"), map)
    }

    @Test
    fun theFirstBindingKeepsASharedPhrase() {
        val map = inGameKeywordMap(
            listOf(
                VoiceCommandBinding(GameInput.PAUSE, listOf("pause")),
                VoiceCommandBinding("button:1", listOf("Pause", "skill")),
            ),
        )
        assertEquals(GameInput.PAUSE, map["pause"])
        assertEquals("button:1", map["skill"])
    }

    @Test
    fun blankPhrasesAreSkipped() {
        assertTrue(inGameKeywordMap(listOf(VoiceCommandBinding("button:1", listOf(" ", "!?")))).isEmpty())
    }

    @Test
    fun standardCommandsAndButtonTriggersComeThrough() {
        val map = inGameKeywordMap(GameInput.STANDARD_BINDINGS + VoiceCommandBinding("button:7", listOf("ultimate")))
        assertEquals(GameInput.BACK, map["go back"])
        assertEquals(GameInput.RECENTER, map["center joystick"])
        assertEquals("button:7", map["ultimate"])
    }

    /**
     * The in-game spotter is ONE flat keyword list sharing `maxActivePaths` search paths, so phrases are
     * a shared resource: every extra one crowds the rest. Matched on *sound*, a new phrase containing an
     * existing word steals that word's paths outright.
     *
     * That is what "lock joystick", "unlock joystick" and "lock center" did to "joystick mode",
     * "gyro joystick" and "center joystick" — they stopped firing on device. Read the real list with
     * `adb shell run-as com.pwde.app cat files/models/…/keywords-game.txt`; every line carries
     * `:6.0 #0.0`, so nothing here is a phrase the spotter politely ignores.
     *
     * This pins the budget so it cannot grow by accident again. If a change needs to go over it, raise
     * the spotter's `activePaths` (Testing Station → Tune → Spotter defaults) and measure first.
     */
    @Test
    fun theInGamePhraseListStaysInsideItsBudget() {
        val phrases = inGameKeywordMap(GameInput.STANDARD_BINDINGS).keys
        assertTrue(
            "the spotter shares its paths across every phrase; ${phrases.size} is too many: $phrases",
            phrases.size <= MAX_IN_GAME_PHRASES,
        )
        val joystick = phrases.filter { "joystick" in it }
        assertTrue("too many phrases compete for \"joystick\": $joystick", joystick.size <= MAX_JOYSTICK_PHRASES)
        // The commands that were crowded out. They must always be in the list.
        listOf("joystick mode", "head tracking", "gyro mode", "cursor mode", "navigation mode").forEach {
            assertTrue("\"$it\" must stay in the in-game list", it in phrases)
        }
    }

    private companion object {
        /** The list has been ~55 phrases; a handful of headroom, then a deliberate decision is due. */
        const val MAX_IN_GAME_PHRASES = 58

        /** "joystick" is the worst shared token: five phrasings can co-exist, no more. */
        const val MAX_JOYSTICK_PHRASES = 5
    }
}
