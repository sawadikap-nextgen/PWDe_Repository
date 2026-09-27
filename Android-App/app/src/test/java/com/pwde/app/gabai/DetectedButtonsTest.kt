package com.pwde.app.gabai

import com.pwde.app.data.gabai.DetectedButton
import com.pwde.app.data.gabai.detectedToButtons
import com.pwde.app.data.model.ButtonTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetectedButtonsTest {
    private fun detected(name: String, x: Float, y: Float) = DetectedButton(name, 0.9f, x, y)

    @Test
    fun theJoystickBecomesTheMovementJoystick() {
        val buttons = detectedToButtons(
            listOf(detected("skill_button", 0.8f, 0.7f), detected("joystick", 0.15f, 0.75f), detected("basic_attack", 0.9f, 0.85f)),
            firstId = 5,
        )
        val stick = buttons.single { it.label == "Joystick" }
        assertEquals(ButtonTrigger.MOVEMENT, stick.trigger)
        assertEquals(0.15f, stick.x, 0f)
        // Everything else still waits for the user to choose a trigger.
        assertEquals(listOf(null, null), buttons.filter { it !== stick }.map { it.trigger })
    }

    @Test
    fun buttonsAreGroupedByClassWithFreshIdsAndNumberedRepeats() {
        val buttons = detectedToButtons(
            listOf(detected("skill_button", 0.8f, 0.9f), detected("skill_button", 0.7f, 0.6f), detected("recall", 0.5f, 0.2f)),
            firstId = 3,
        )
        assertEquals(listOf("Recall", "Skill button 1", "Skill button 2"), buttons.map { it.label })
        assertEquals(listOf(3, 4, 5), buttons.map { it.id })
    }

    @Test
    fun mobileLegendsHudFollowsTheMappingOrder() {
        val buttons = detectedToButtons(
            listOf(
                detected("use_item", 0.82f, 0.4f), detected("buy_item", 0.86f, 0.2f), detected("skill_upgrade", 0.7f, 0.7f),
                detected("skill_button", 0.72f, 0.8f), detected("basic_attack", 0.9f, 0.85f), detected("spell", 0.62f, 0.9f),
                detected("regen", 0.55f, 0.9f), detected("recall", 0.48f, 0.9f), detected("joystick", 0.2f, 0.75f),
            ),
            firstId = 1,
        )
        assertEquals(
            listOf("Joystick", "Recall", "Regen", "Spell", "Basic attack", "Skill button", "Skill upgrade", "Buy item", "Use item"),
            buttons.map { it.label },
        )
    }

    @Test
    fun skillsAndUpgradesAreNumberedLeftToRightWhateverTheirHeight() {
        val buttons = detectedToButtons(
            listOf(
                detected("skill_upgrade", 0.86f, 0.45f), detected("skill_button", 0.88f, 0.55f),
                detected("skill_upgrade", 0.70f, 0.72f), detected("skill_button", 0.72f, 0.82f),
                detected("skill_upgrade", 0.78f, 0.58f), detected("skill_button", 0.80f, 0.68f),
                detected("basic_attack", 0.92f, 0.85f),
            ),
            firstId = 1,
        )
        assertEquals(
            listOf("auto", "first", "upgrade first", "second", "upgrade second", "third", "upgrade third"),
            buttons.map { it.label },
        )
        assertEquals(listOf(0.72f, 0.80f, 0.88f), buttons.filter { it.label.startsWith("Skill button") }.map { it.x })
        assertEquals(listOf(0.70f, 0.78f, 0.86f), buttons.filter { it.label.startsWith("Skill upgrade") }.map { it.x })
    }

    @Test
    fun onlyTheFirstJoystickIsTheMovementJoystick() {
        val buttons = detectedToButtons(listOf(detected("joystick", 0.1f, 0.7f), detected("joystick", 0.2f, 0.8f)), firstId = 1)
        assertEquals(ButtonTrigger.MOVEMENT, buttons[0].trigger)
        assertNull(buttons[1].trigger)
        assertEquals("Joystick", buttons[1].label)
    }
}
