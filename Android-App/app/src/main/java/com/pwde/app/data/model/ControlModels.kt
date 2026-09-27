package com.pwde.app.data.model

import com.pwde.app.data.prefs.InputMode

/**
 * Static catalog of facial gestures PWDe recognises through face tracking. Two kinds:
 * - curated gestures ([isRaw] false): the GameFace set plus a few more, some combining several
 *   blendshapes, and head-pose moves (tilt, nod, shake);
 * - raw MediaPipe Face Landmarker blendshapes ([isRaw] true, [blendshape] set), each one usable as
 *   a gesture on its own. Sides are as MediaPipe names them.
 * Stored by name, so order doesn't matter.
 */
enum class FacialGesture(
    val label: String,
    val description: String,
    val blendshape: String? = null,
) {
    SMILE("Smile", "A wide smile"),
    OPEN_MOUTH("Open mouth", "Open your mouth, then close it"),
    EYEBROW_RAISE("Eyebrow raise", "Raise both eyebrows"),
    TILT_LEFT("Tilt left", "Tilt your head to the left"),
    TILT_RIGHT("Tilt right", "Tilt your head to the right"),
    NOD("Nod", "A small nod down and back up"),

    // Mouth
    MOUTH_LEFT("Mouth left", "Push your lips to the left"),
    MOUTH_RIGHT("Mouth right", "Push your lips to the right"),
    PUCKER("Pucker", "Push your lips forward, like a kiss"),
    ROLL_LOWER_LIP("Roll lower lip", "Tuck your lower lip in"),

    // Eyebrows
    RAISE_LEFT_EYEBROW("Raise left eyebrow", "Lift only your left eyebrow"),
    RAISE_RIGHT_EYEBROW("Raise right eyebrow", "Lift only your right eyebrow"),

    // Eyes
    CLOSE_EYES("Close both eyes", "Close both eyes and hold for a moment"),

    // Head
    SHAKE("Shake head", "A small shake, left and right and back"),

    // MediaPipe Face Landmarker blendshapes, in the model's order. Ones that match a removed
    // gesture (cheek puff, frown, wink, jaw slides, brow down, eye gaze in every direction) are
    // left out so they can't be mapped under a raw name either.
    MP_NEUTRAL("_neutral", "Resting face — MediaPipe's baseline score", blendshape = "_neutral"),
    MP_BROW_INNER_UP("browInnerUp", "Raise the inner ends of both eyebrows", blendshape = "browInnerUp"),
    MP_BROW_OUTER_UP_LEFT("browOuterUpLeft", "Raise the outer end of your left eyebrow", blendshape = "browOuterUpLeft"),
    MP_BROW_OUTER_UP_RIGHT("browOuterUpRight", "Raise the outer end of your right eyebrow", blendshape = "browOuterUpRight"),
    MP_CHEEK_SQUINT_LEFT("cheekSquintLeft", "Raise your left cheek", blendshape = "cheekSquintLeft"),
    MP_CHEEK_SQUINT_RIGHT("cheekSquintRight", "Raise your right cheek", blendshape = "cheekSquintRight"),
    MP_EYE_SQUINT_LEFT("eyeSquintLeft", "Squint your left eye", blendshape = "eyeSquintLeft"),
    MP_EYE_SQUINT_RIGHT("eyeSquintRight", "Squint your right eye", blendshape = "eyeSquintRight"),
    MP_EYE_WIDE_LEFT("eyeWideLeft", "Open your left eye wide", blendshape = "eyeWideLeft"),
    MP_EYE_WIDE_RIGHT("eyeWideRight", "Open your right eye wide", blendshape = "eyeWideRight"),
    MP_JAW_FORWARD("jawForward", "Push your jaw forward", blendshape = "jawForward"),
    MP_JAW_OPEN("jawOpen", "Open your jaw", blendshape = "jawOpen"),
    MP_MOUTH_CLOSE("mouthClose", "Keep your lips together while your jaw opens", blendshape = "mouthClose"),
    MP_MOUTH_DIMPLE_LEFT("mouthDimpleLeft", "Pull the left corner of your mouth back", blendshape = "mouthDimpleLeft"),
    MP_MOUTH_DIMPLE_RIGHT("mouthDimpleRight", "Pull the right corner of your mouth back", blendshape = "mouthDimpleRight"),
    MP_MOUTH_FUNNEL("mouthFunnel", "Round your lips into an O", blendshape = "mouthFunnel"),
    MP_MOUTH_LEFT("mouthLeft", "Push your lips to the left", blendshape = "mouthLeft"),
    MP_MOUTH_LOWER_DOWN_LEFT("mouthLowerDownLeft", "Pull the left side of your lower lip down", blendshape = "mouthLowerDownLeft"),
    MP_MOUTH_LOWER_DOWN_RIGHT("mouthLowerDownRight", "Pull the right side of your lower lip down", blendshape = "mouthLowerDownRight"),
    MP_MOUTH_PRESS_LEFT("mouthPressLeft", "Press the left side of your lips together", blendshape = "mouthPressLeft"),
    MP_MOUTH_PRESS_RIGHT("mouthPressRight", "Press the right side of your lips together", blendshape = "mouthPressRight"),
    MP_MOUTH_PUCKER("mouthPucker", "Push your lips forward, like a kiss", blendshape = "mouthPucker"),
    MP_MOUTH_RIGHT("mouthRight", "Push your lips to the right", blendshape = "mouthRight"),
    MP_MOUTH_ROLL_LOWER("mouthRollLower", "Tuck your lower lip in", blendshape = "mouthRollLower"),
    MP_MOUTH_ROLL_UPPER("mouthRollUpper", "Tuck your upper lip in", blendshape = "mouthRollUpper"),
    MP_MOUTH_SHRUG_LOWER("mouthShrugLower", "Push your lower lip up", blendshape = "mouthShrugLower"),
    MP_MOUTH_SHRUG_UPPER("mouthShrugUpper", "Push your upper lip up", blendshape = "mouthShrugUpper"),
    MP_MOUTH_SMILE_LEFT("mouthSmileLeft", "Smile with the left side of your mouth", blendshape = "mouthSmileLeft"),
    MP_MOUTH_SMILE_RIGHT("mouthSmileRight", "Smile with the right side of your mouth", blendshape = "mouthSmileRight"),
    MP_MOUTH_STRETCH_LEFT("mouthStretchLeft", "Stretch the left corner of your mouth sideways", blendshape = "mouthStretchLeft"),
    MP_MOUTH_STRETCH_RIGHT("mouthStretchRight", "Stretch the right corner of your mouth sideways", blendshape = "mouthStretchRight"),
    MP_MOUTH_UPPER_UP_LEFT("mouthUpperUpLeft", "Raise the left side of your upper lip", blendshape = "mouthUpperUpLeft"),
    MP_MOUTH_UPPER_UP_RIGHT("mouthUpperUpRight", "Raise the right side of your upper lip", blendshape = "mouthUpperUpRight"),
    MP_NOSE_SNEER_LEFT("noseSneerLeft", "Wrinkle the left side of your nose", blendshape = "noseSneerLeft"),
    MP_NOSE_SNEER_RIGHT("noseSneerRight", "Wrinkle the right side of your nose", blendshape = "noseSneerRight"),
    ;

    /** A single raw MediaPipe blendshape rather than a curated gesture. */
    val isRaw: Boolean get() = blendshape != null

    /** How to say it: the label, or a raw blendshape's name split into words ("brow down left"). */
    val spokenName: String
        get() = blendshape?.let { name ->
            if (name == "_neutral") "neutral" else name.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").lowercase()
        } ?: label.lowercase()

    companion object {
        val curated: List<FacialGesture> = entries.filter { !it.isRaw }
        val raw: List<FacialGesture> = entries.filter { it.isRaw }

        /** Curated gestures offered in gesture pickers. */
        val selectable: List<FacialGesture> = curated
    }
}

/** Things a user can trigger with a gesture. */
enum class GestureAction(val label: String) {
    SELECT("Select"),
    HOME("Home"),
    BACK("Back"),
    NOTIFICATIONS("Notifications"),
    PAUSE_RESUME("Pause / resume"),
    RECENTER("Recenter"),
    TOUCH_HOLD("Touch & hold"),
    ALL_APPS("All apps"),
}

/**
 * The gesture actions a fresh install already has mapped, before the user changes anything.
 *
 * Both picks are the easiest curated gesture to make on purpose and to stop again, they read cleanly
 * from the front camera (`mouthSmile` averaged for Select, `jawOpen` for Recenter — see
 * `GestureClassifier`), and they are unmistakable in a live preview. So a new user can press the
 * thing the pointer is on and bring it back to the middle without mapping anything first. Setup says
 * so out loud, and every mapping stays editable in Controls → Gestures.
 */
val DEFAULT_GESTURE_ASSIGNMENTS: Map<GestureAction, FacialGesture> =
    mapOf(
        GestureAction.SELECT to FacialGesture.SMILE,
        GestureAction.RECENTER to FacialGesture.OPEN_MOUTH,
    )

enum class VoiceMatchMode(val label: String, val description: String) {
    EXACT("Match: exact phrase", "Only \"attack\" by itself"),
    WORD_ANYWHERE("Match: word anywhere", "\"go attack now\" also works"),
}

enum class VoiceActivationMode(val label: String, val description: String) {
    IMMEDIATE("Act: right away", "Faster, can't double-check"),
    AFTER_FINISH("Act: after I finish", "Slower, fewer mistakes"),
}

/** Spoken shortcuts the user can customise. */
enum class VoiceShortcut(val label: String, val defaultPhrase: String) {
    SWITCH_PROFILE("Switch profiles", "switch profile"),
    CURSOR_MODE("Cursor mode", "cursor mode"),
    JOYSTICK_MODE("Joystick mode", "joystick mode"),
    GYRO_MODE("Gyro joystick", "gyro mode"),
    HEAD_TRACKING("Head joystick", "head tracking"),
}

/** Levels are 1–10 everywhere, matching [com.pwde.app.ui.components.LevelStepper]. */
const val MIN_LEVEL = 1
const val MAX_LEVEL = 10
const val DEFAULT_LEVEL = 5

/** How the pointer follows head yaw/pitch. Speeds are per direction. */
data class CursorTuning(
    val speedUp: Int = DEFAULT_LEVEL,
    val speedDown: Int = DEFAULT_LEVEL,
    val speedLeft: Int = DEFAULT_LEVEL,
    val speedRight: Int = DEFAULT_LEVEL,
    val smoothing: Int = 7,
)

/** Head-tilt joystick. [centerPitch]/[centerRoll] are the user's neutral pose, in degrees. */
data class JoystickTuning(
    val size: Int = DEFAULT_LEVEL,
    val sensitivity: Int = DEFAULT_LEVEL,
    val deadZone: Int = 3,
    val centerPitch: Float = 0f,
    val centerRoll: Float = 0f,
)

/**
 * What steers the joystick while the output mode is [FaceOutputMode.JOYSTICK].
 *
 * Head tracking needs the front camera pointed at the user's face; gyro needs only the phone's own
 * motion sensors, so it keeps working with the camera off and the phone held anywhere it can be
 * tilted. Both produce the same three [com.pwde.app.sensors.face.HeadPose] angles, so Size,
 * Sensitivity, Dead zone and Smoothing mean the same thing either way.
 */
enum class JoystickSource(val label: String, val description: String) {
    HEAD("Head tracking", "Tilt your head to steer"),
    GYRO("Gyro tracking", "Tilt your phone to steer"),
}

/** What head movement drives: a free pointer or an 8-way joystick. */
enum class FaceOutputMode(val label: String) { CURSOR("Cursor"), JOYSTICK("Joystick") }

/** Joystick input drives the joystick; head & face and voice modes use the pointer. */
fun InputMode.faceOutputMode(): FaceOutputMode =
    if (this == InputMode.JOYSTICK) FaceOutputMode.JOYSTICK else FaceOutputMode.CURSOR

/**
 * What PWDe's phone-navigation words do in a session. Back, Home, Recents, Notifications and All
 * apps drive the *phone*, so in [GAME] they are refused: a stray "back" — or the in-game spotter
 * hearing the game's own audio — must never yank the user out of a match. [NAVIGATION] is for
 * driving the phone: browsing, settings, other apps.
 */
enum class NavigationMode(val label: String) {
    GAME("Game"),
    NAVIGATION("Navigation"),
}

/**
 * The default with no explicit switch: the joystick plays the game, so navigation is off; a cursor
 * means the user is pointing at the phone, so navigation is on. That is why joystick mode is game
 * mode and cursor mode is navigation mode out of the box.
 */
fun FaceOutputMode.defaultNavigationMode(): NavigationMode =
    if (this == FaceOutputMode.JOYSTICK) NavigationMode.GAME else NavigationMode.NAVIGATION

/**
 * An explicit "game mode" / "navigation mode" wins; otherwise the input mode decides. The single
 * rule, so the live session and the simulated preview can never drift apart.
 */
fun navigationModeFor(override: NavigationMode?, outputMode: FaceOutputMode): NavigationMode =
    override ?: outputMode.defaultNavigationMode()

/** The user's working controls configuration (not yet saved as a named profile). */
data class ControlConfig(
    val gestureAssignments: Map<GestureAction, FacialGesture> = DEFAULT_GESTURE_ASSIGNMENTS,
    val gestureSensitivity: Map<FacialGesture, Int> = emptyMap(),
    val enabledGestures: Set<FacialGesture>? = null,
    val voiceEnabled: Boolean = true,
    val voiceMatchMode: VoiceMatchMode = VoiceMatchMode.WORD_ANYWHERE,
    val voiceActivationMode: VoiceActivationMode = VoiceActivationMode.IMMEDIATE,
    val voiceShortcuts: Map<VoiceShortcut, String> = VoiceShortcut.entries.associateWith { it.defaultPhrase },
    val cursor: CursorTuning = CursorTuning(),
    val joystick: JoystickTuning = JoystickTuning(),
) {
    fun sensitivityOf(gesture: FacialGesture): Int = gestureSensitivity[gesture] ?: DEFAULT_LEVEL

    fun isGestureEnabled(gesture: FacialGesture): Boolean =
        enabledGestures == null || gesture.isRaw || gesture in enabledGestures

    /** The action mapped to [gesture], if any. */
    fun actionFor(gesture: FacialGesture): GestureAction? =
        gestureAssignments.entries.firstOrNull { it.value == gesture }?.key

    /** Actions other than [action] that already use [gesture]. */
    fun conflictsFor(action: GestureAction, gesture: FacialGesture): List<GestureAction> =
        gestureAssignments.filter { (other, g) -> other != action && g == gesture }.keys.toList()
}

fun FacialGesture.isEnabledBy(enabledSet: Set<FacialGesture>?): Boolean =
    enabledSet == null || this in enabledSet
