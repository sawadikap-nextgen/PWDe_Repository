package com.pwde.app.data.remote

import com.pwde.app.data.local.CalibrationProfile
import com.pwde.app.data.local.ControlSettingsEntity
import com.pwde.app.data.local.GameProfile
import com.pwde.app.data.model.JoystickSource
import com.pwde.app.data.prefs.AccessibilityNeed
import com.pwde.app.data.prefs.ColorSchemeOption
import com.pwde.app.data.prefs.InputMode
import com.pwde.app.data.prefs.LayoutMode
import com.pwde.app.data.prefs.TextSizeOption
import com.pwde.app.data.prefs.TtsSpeed
import com.pwde.app.data.prefs.UserSettings

/**
 * Firestore shapes of what PWDe syncs. Local row ids never leave the phone: records point at each other
 * by remote id (a game profile's calibration, the controls' active calibration).
 */
object CloudMappers {

    // ---- Calibration profiles: users/{uid}/calibrationProfiles/{remoteId} ----

    fun CalibrationProfile.toCloud(): Map<String, Any?> = mapOf(
        "name" to name,
        "inputMode" to inputMode,
        "cursorSpeedUp" to cursorSpeedUp,
        "cursorSpeedDown" to cursorSpeedDown,
        "cursorSpeedLeft" to cursorSpeedLeft,
        "cursorSpeedRight" to cursorSpeedRight,
        "cursorSmoothing" to cursorSmoothing,
        "joystickSensitivity" to joystickSensitivity,
        "joystickDeadZone" to joystickDeadZone,
        "joystickRadius" to joystickRadius,
        "joystickCenterPitch" to joystickCenterPitch.toDouble(),
        "joystickCenterRoll" to joystickCenterRoll.toDouble(),
        "gestureAssignmentsJson" to gestureAssignmentsJson,
        "gestureSensitivityJson" to gestureSensitivityJson,
        "enabledGesturesJson" to enabledGesturesJson,
        "voiceEnabled" to voiceEnabled,
        "voiceMatchMode" to voiceMatchMode,
        "voiceActivationMode" to voiceActivationMode,
        "createdAt" to createdAt,
        CloudDoc.FIELD_UPDATED_AT to updatedAt,
        CloudDoc.FIELD_DELETED to false,
    )

    /** [doc] as a calibration profile; [localId] is the row it replaces (0 = a new row). */
    fun calibrationFromCloud(doc: CloudDoc, localId: Long, syncedAt: Long): CalibrationProfile {
        val d = doc.data
        val defaults = CalibrationProfile(name = "", inputMode = "", voiceMatchMode = "", voiceActivationMode = "", createdAt = 0, updatedAt = 0)
        return CalibrationProfile(
            id = localId,
            name = d.str("name") ?: "Profile",
            inputMode = d.str("inputMode") ?: InputMode.HEAD_FACE.name,
            cursorSpeedUp = d.int("cursorSpeedUp", defaults.cursorSpeedUp),
            cursorSpeedDown = d.int("cursorSpeedDown", defaults.cursorSpeedDown),
            cursorSpeedLeft = d.int("cursorSpeedLeft", defaults.cursorSpeedLeft),
            cursorSpeedRight = d.int("cursorSpeedRight", defaults.cursorSpeedRight),
            cursorSmoothing = d.int("cursorSmoothing", defaults.cursorSmoothing),
            joystickSensitivity = d.int("joystickSensitivity", defaults.joystickSensitivity),
            joystickDeadZone = d.int("joystickDeadZone", defaults.joystickDeadZone),
            joystickRadius = d.int("joystickRadius", defaults.joystickRadius),
            joystickCenterPitch = d.float("joystickCenterPitch", 0f),
            joystickCenterRoll = d.float("joystickCenterRoll", 0f),
            gestureAssignmentsJson = d.str("gestureAssignmentsJson") ?: "{}",
            gestureSensitivityJson = d.str("gestureSensitivityJson") ?: "{}",
            enabledGesturesJson = d.str("enabledGesturesJson"),
            voiceEnabled = d.bool("voiceEnabled", true),
            voiceMatchMode = d.str("voiceMatchMode") ?: "",
            voiceActivationMode = d.str("voiceActivationMode") ?: "",
            createdAt = d.long("createdAt") ?: doc.updatedAt,
            updatedAt = doc.updatedAt,
            remoteId = doc.id,
            lastSyncedAt = syncedAt,
        )
    }

    // ---- Game profiles: users/{uid}/gameProfiles/{remoteId} ----

    /** The thumbnail is a file on this phone, so it isn't synced. */
    fun GameProfile.toCloud(calibrationRemoteId: String?): Map<String, Any?> = mapOf(
        "gameId" to gameId,
        "gameName" to gameName,
        "profileName" to profileName,
        "calibrationRemoteId" to calibrationRemoteId,
        "buttonMappingsJson" to buttonMappingsJson,
        "lastPlayedAt" to lastPlayedAt,
        "createdAt" to createdAt,
        CloudDoc.FIELD_UPDATED_AT to updatedAt,
        CloudDoc.FIELD_DELETED to false,
    )

    fun calibrationRemoteIdOf(doc: CloudDoc): String? = doc.data.str("calibrationRemoteId")

    /** [doc] as a game profile. [local] is the row it replaces (null = new), whose thumbnail is kept. */
    fun gameFromCloud(doc: CloudDoc, local: GameProfile?, calibrationProfileId: Long?, syncedAt: Long): GameProfile {
        val d = doc.data
        return GameProfile(
            id = local?.id ?: 0,
            gameId = d.str("gameId") ?: local?.gameId.orEmpty(),
            gameName = d.str("gameName") ?: local?.gameName.orEmpty(),
            profileName = d.str("profileName") ?: local?.profileName ?: "Profile",
            calibrationProfileId = calibrationProfileId,
            buttonMappingsJson = d.str("buttonMappingsJson") ?: "[]",
            thumbnailPath = local?.thumbnailPath,
            createdAt = d.long("createdAt") ?: doc.updatedAt,
            updatedAt = doc.updatedAt,
            remoteId = doc.id,
            lastSyncedAt = syncedAt,
            lastPlayedAt = maxOfNullable(d.long("lastPlayedAt"), local?.lastPlayedAt),
        )
    }

    // ---- Working controls: users/{uid}/state/controls ----

    fun ControlSettingsEntity.toCloud(activeCalibrationRemoteId: String?): Map<String, Any?> = mapOf(
        "gestureAssignmentsJson" to gestureAssignmentsJson,
        "gestureSensitivityJson" to gestureSensitivityJson,
        "enabledGesturesJson" to enabledGesturesJson,
        "voiceEnabled" to voiceEnabled,
        "voiceMatchMode" to voiceMatchMode,
        "voiceActivationMode" to voiceActivationMode,
        "voiceShortcutsJson" to voiceShortcutsJson,
        "cursorSpeedUp" to cursorSpeedUp,
        "cursorSpeedDown" to cursorSpeedDown,
        "cursorSpeedLeft" to cursorSpeedLeft,
        "cursorSpeedRight" to cursorSpeedRight,
        "cursorSmoothing" to cursorSmoothing,
        "joystickSize" to joystickSize,
        "joystickSensitivity" to joystickSensitivity,
        "joystickDeadZone" to joystickDeadZone,
        "joystickCenterPitch" to joystickCenterPitch.toDouble(),
        "joystickCenterRoll" to joystickCenterRoll.toDouble(),
        "activeCalibrationRemoteId" to activeCalibrationRemoteId,
        CloudDoc.FIELD_UPDATED_AT to updatedAt,
    )

    fun activeCalibrationRemoteIdOf(doc: CloudDoc): String? = doc.data.str("activeCalibrationRemoteId")

    fun controlsFromCloud(doc: CloudDoc, activeCalibrationProfileId: Long?): ControlSettingsEntity {
        val d = doc.data
        return ControlSettingsEntity(
            gestureAssignmentsJson = d.str("gestureAssignmentsJson") ?: "{}",
            voiceEnabled = d.bool("voiceEnabled", true),
            voiceMatchMode = d.str("voiceMatchMode") ?: "",
            voiceActivationMode = d.str("voiceActivationMode") ?: "",
            voiceShortcutsJson = d.str("voiceShortcutsJson") ?: "{}",
            updatedAt = doc.updatedAt,
            gestureSensitivityJson = d.str("gestureSensitivityJson") ?: "{}",
            cursorSpeedUp = d.int("cursorSpeedUp", 5),
            cursorSpeedDown = d.int("cursorSpeedDown", 5),
            cursorSpeedLeft = d.int("cursorSpeedLeft", 5),
            cursorSpeedRight = d.int("cursorSpeedRight", 5),
            cursorSmoothing = d.int("cursorSmoothing", 7),
            joystickSize = d.int("joystickSize", 5),
            joystickSensitivity = d.int("joystickSensitivity", 5),
            joystickDeadZone = d.int("joystickDeadZone", 3),
            joystickCenterPitch = d.float("joystickCenterPitch", 0f),
            joystickCenterRoll = d.float("joystickCenterRoll", 0f),
            enabledGesturesJson = d.str("enabledGesturesJson"),
            activeCalibrationProfileId = activeCalibrationProfileId,
        )
    }

    // ---- App settings: users/{uid}/state/settings ----

    /** Leaves out what describes this phone rather than the user: the master switch and other screen readers. */
    fun UserSettings.toCloud(): Map<String, Any?> = mapOf(
        "accessibilityNeeds" to accessibilityNeeds.map { it.name },
        "colorScheme" to colorScheme.name,
        "textSize" to textSize.name,
        "layoutMode" to layoutMode.name,
        "inputMode" to inputMode.name,
        "joystickSource" to joystickSource.name,
        "ttsEnabled" to ttsEnabled,
        "ttsSpeed" to ttsSpeed.name,
        "setupCompleted" to setupCompleted,
        "voiceTutorialCompleted" to voiceTutorialCompleted,
        CloudDoc.FIELD_UPDATED_AT to updatedAt,
    )

    /** [doc] applied over [current], which supplies the device-only settings and any unknown values. */
    fun settingsFromCloud(doc: CloudDoc, current: UserSettings): UserSettings {
        val d = doc.data
        return current.copy(
            accessibilityNeeds = d.strings("accessibilityNeeds").mapNotNull { enumOrNull<AccessibilityNeed>(it) }.toSet(),
            colorScheme = enumOrNull<ColorSchemeOption>(d.str("colorScheme")) ?: current.colorScheme,
            textSize = enumOrNull<TextSizeOption>(d.str("textSize")) ?: current.textSize,
            layoutMode = enumOrNull<LayoutMode>(d.str("layoutMode")) ?: current.layoutMode,
            inputMode = enumOrNull<InputMode>(d.str("inputMode")) ?: current.inputMode,
            joystickSource = enumOrNull<JoystickSource>(d.str("joystickSource")) ?: current.joystickSource,
            ttsEnabled = d.bool("ttsEnabled", current.ttsEnabled),
            ttsSpeed = enumOrNull<TtsSpeed>(d.str("ttsSpeed")) ?: current.ttsSpeed,
            setupCompleted = d.bool("setupCompleted", current.setupCompleted),
            voiceTutorialCompleted = d.bool("voiceTutorialCompleted", current.voiceTutorialCompleted),
            updatedAt = doc.updatedAt,
        )
    }

    // ---- Added games: users/{uid}/state/customGames ----

    fun customGamesToCloud(names: List<String>, now: Long): Map<String, Any?> =
        mapOf("names" to names, CloudDoc.FIELD_UPDATED_AT to now)

    fun customGamesFromCloud(doc: CloudDoc): List<String> = doc.data.strings("names")

    private fun maxOfNullable(a: Long?, b: Long?): Long? = if (a == null) b else if (b == null) a else maxOf(a, b)

    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }
}
