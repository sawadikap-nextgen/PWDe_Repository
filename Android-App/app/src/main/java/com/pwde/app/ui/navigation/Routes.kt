package com.pwde.app.ui.navigation

import android.net.Uri

/** Every destination in the app. Order mirrors the Figma flow (A Account → H Profile). */
object Routes {
    // A · Account
    const val SPLASH = "splash"
    const val WELCOME = "welcome"
    const val SIGN_IN = "sign_in"
    const val FORGOT_PASSWORD = "forgot_password"
    const val CREATE_ACCOUNT = "create_account"

    // B · Setup + voice tutorial
    const val SETUP = "setup?appearanceOnly={appearanceOnly}"
    fun setup(appearanceOnly: Boolean = false) = "setup?appearanceOnly=$appearanceOnly"
    const val VOICE_TUTORIAL = "voice_tutorial?replay={replay}"

    /**
     * [replay] is set when the user re-runs onboarding from Profile: the tutorial then returns them
     * where they came from instead of treating a finished tutorial as "welcome, you're set up" and
     * dropping them on the Dashboard.
     */
    fun voiceTutorial(replay: Boolean = false) = "voice_tutorial?replay=$replay"

    // D · Play
    const val DASHBOARD = "dashboard"
    const val GAMES = "games"
    const val GAME_DETAIL = "games/{gameId}"
    fun gameDetail(gameId: String) = "games/$gameId"
    const val PLAYING = "playing/{gameId}?profile={profile}"

    /** [profileId] picks a game profile; without one, the game's most recent profile (if any) is used. */
    fun playing(gameId: String, profileId: Long? = null) = "playing/$gameId?profile=${profileId ?: -1}"

    // E · Controls
    const val CONTROLS = "controls"
    const val CONTROLS_INPUT = "controls/input"
    const val CONTROLS_GESTURES = "controls/gestures"
    const val CHOOSE_GESTURE = "controls/gestures/{action}"
    fun chooseGesture(action: String) = "controls/gestures/$action"
    const val CONTROLS_CURSOR = "controls/cursor"
    const val CONTROLS_JOYSTICK = "controls/joystick"
    const val VOICE_CONFIG = "voice_config"

    // F · Testing, tutorial
    const val TESTING_STATION = "testing_station"
    const val WATCH_TUTORIAL = "watch_tutorial"

    // G · GabAI
    const val GABAI = "gabai?start={start}&game={game}&edit={edit}"
    const val GABAI_START_WELCOME = "welcome"
    const val GABAI_START_GAME = "game"
    const val GABAI_START_MANUAL = "manual"

    /** GabAI's Welcome, or straight into a new game profile (optionally for [gameId]), or editing one. */
    fun gabai(newGameProfile: Boolean = false, gameId: String? = null, editProfileId: Long? = null, manual: Boolean = false) =
        "gabai?start=${if (manual) GABAI_START_MANUAL else if (newGameProfile) GABAI_START_GAME else GABAI_START_WELCOME}&game=${Uri.encode(gameId.orEmpty())}&edit=${editProfileId ?: -1}"

    // H · Profile
    const val PROFILE = "profile"
    const val CALIBRATION_EDITOR = "profile/calibration/{profileId}"
    fun calibrationEditor(profileId: Long) = "profile/calibration/$profileId"

    /** Spoken screen names for the read-aloud option. */
    fun spokenTitle(route: String?): String? = when (route) {
        SPLASH -> null
        WELCOME -> "Welcome to PWDe"
        SIGN_IN -> "Sign in"
        FORGOT_PASSWORD -> "Reset password"
        CREATE_ACCOUNT -> "Create your account"
        SETUP -> "Setup"
        VOICE_TUTORIAL -> "Voice tutorial"
        DASHBOARD -> "Play"
        GAMES -> "Games"
        GAME_DETAIL -> "Game details"
        PLAYING -> "Playing"
        CONTROLS -> "Controls"
        CONTROLS_INPUT -> "Input mode"
        CONTROLS_GESTURES -> "Gestures"
        CHOOSE_GESTURE -> "Choose a gesture"
        CONTROLS_CURSOR -> "Cursor speed"
        CONTROLS_JOYSTICK -> "Joystick"
        VOICE_CONFIG -> "Voice"
        TESTING_STATION -> "Testing station"
        WATCH_TUTORIAL -> "Tutorial video"
        GABAI -> "GabAI setup"
        PROFILE -> "Profile"
        CALIBRATION_EDITOR -> "Edit calibration"
        else -> null
    }
}
