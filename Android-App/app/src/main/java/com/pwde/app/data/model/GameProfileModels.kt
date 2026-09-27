package com.pwde.app.data.model

/** How a mapped on-screen game button gets pressed. */
enum class TriggerType(val label: String) {
    VOICE("Voice"),
    GESTURE("Gestures"),
    JOYSTICK("Joystick action"),

    /** This button is the game's movement joystick: in joystick mode PWDe holds it and drags it with the head. */
    MOVEMENT("Joystick"),
}

/**
 * One trigger. [value] depends on [type]: the spoken phrase (VOICE), a [FacialGesture] name
 * (GESTURE), a joystick direction name such as "UP_LEFT" (JOYSTICK), or [MOVEMENT_STICK] (MOVEMENT).
 */
data class ButtonTrigger(val type: TriggerType, val value: String) {
    val gesture: FacialGesture? get() = if (type == TriggerType.GESTURE) FacialGesture.entries.firstOrNull { it.name == value } else null

    fun describe(): String = when (type) {
        TriggerType.VOICE -> "Say \"$value\""
        TriggerType.GESTURE -> gesture?.label ?: value
        TriggerType.JOYSTICK -> "Joystick ${value.lowercase().replace('_', '-')}"
        TriggerType.MOVEMENT -> "Held and moved by the head joystick"
    }

    /** Short enough to sit under a button marker: the phrase in quotes, the gesture's name, or the stick. */
    fun shortLabel(): String = when (type) {
        TriggerType.VOICE -> "\"$value\""
        TriggerType.GESTURE -> gesture?.label ?: value
        TriggerType.JOYSTICK -> "Stick ${value.lowercase().replace('_', '-')}"
        TriggerType.MOVEMENT -> "Head joystick"
    }

    companion object {
        const val MOVEMENT_STICK = "STICK"

        /** Marks a button as the game's movement joystick. */
        val MOVEMENT = ButtonTrigger(TriggerType.MOVEMENT, MOVEMENT_STICK)
    }
}

/**
 * A game button placed on the game's screenshot. Position is the button's center, normalized to
 * the screenshot (0–1 on both axes), so it survives any screen size.
 */
data class MappedButton(
    val id: Int,
    val label: String,
    val x: Float,
    val y: Float,
    val trigger: ButtonTrigger? = null,
)
