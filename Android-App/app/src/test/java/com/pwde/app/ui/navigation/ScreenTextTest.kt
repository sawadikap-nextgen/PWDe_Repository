package com.pwde.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What PWDe says for a screen. The rules that matter are the reading order, the de-duplication and the
 * length limit — all silent failures if they are wrong: the user only hears too much, too little, or the
 * same thing twice.
 */
class ScreenTextTest {
    @Test
    fun entriesAreReadInOrderAsSentences() {
        assertEquals(
            "Controls. Voice. Gestures.",
            ScreenText.describe(listOf(ScreenEntry("Controls"), ScreenEntry("Voice"), ScreenEntry("Gestures"))),
        )
    }

    @Test
    fun nothingToReadIsEmptyRatherThanAPeriod() {
        assertEquals("", ScreenText.describe(emptyList()))
        assertEquals("", ScreenText.describe(listOf(ScreenEntry(""), ScreenEntry("   "))))
    }

    /** Compose exposes a control's label on the control and again on its icon. */
    @Test
    fun aLabelRepeatedByTheFrameworkIsSaidOnce() {
        assertEquals(
            "Read on-screen text aloud.",
            ScreenText.describe(listOf(ScreenEntry("Read on-screen text aloud"), ScreenEntry("read on-screen text aloud"))),
        )
    }

    @Test
    fun aMultiLineLabelIsReadAsOnePhrase() {
        assertEquals("Big text.", ScreenText.describe(listOf(ScreenEntry("Big\n  text"))))
    }

    /** A toggle must say what it is set to, or the user cannot know without tapping it. */
    @Test
    fun aToggleIsReadWithItsState() {
        assertEquals(
            "Read on-screen text aloud, on. Speed, off.",
            ScreenText.describe(listOf(ScreenEntry("Read on-screen text aloud", checked = true), ScreenEntry("Speed", checked = false))),
        )
    }

    @Test
    fun aLongScreenIsCutOnAWordBoundary() {
        val long = List(40) { ScreenEntry("Button number $it") }
        val spoken = ScreenText.describe(long, maxChars = 60)

        assertTrue("stays inside the limit: ${spoken.length}", spoken.length <= 61)
        assertTrue("no half a word: $spoken", !spoken.contains("Butto."))
        assertTrue("ends a sentence: $spoken", spoken.endsWith("."))
    }

    /** Cutting must not leave a dangling separator. */
    @Test
    fun cuttingNeverLeavesADanglingSeparator() {
        val spoken = ScreenText.describe(listOf(ScreenEntry("a")), maxChars = 1)
        assertTrue("got \"$spoken\"", !spoken.startsWith(".") && !spoken.startsWith(" "))
    }

    /**
     * The marker that answers "has the screen the user just left gone?". Navigation keeps the outgoing
     * destination composed for its whole transition, and reading during that would read BOTH screens —
     * this is what stops that without falling back on a guessed delay.
     */
    @Test
    fun theOutgoingScreenIsStillThereWhileItsWordsAreInTheTree() {
        val leaving = listOf(ScreenEntry("Controls"), ScreenEntry("Voice"))
        assertTrue(overlapsOutgoing(leaving, listOf(ScreenEntry("Controls"), ScreenEntry("Cursor speed"))))
        assertFalse(overlapsOutgoing(leaving, listOf(ScreenEntry("Dashboard"), ScreenEntry("Start playing"))))
    }

    /** The first screen of a session has nothing to compare against, and must not hold the read up. */
    @Test
    fun anEmptyMarkerNeverBlocksTheRead() {
        assertFalse(overlapsOutgoing(emptyList(), listOf(ScreenEntry("Dashboard"))))
    }
}
