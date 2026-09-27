package com.pwde.app.data.local

import kotlinx.coroutines.flow.Flow

/**
 * Room-backed store for calibration and game profiles. The only path to those tables.
 *
 * [onRemoteDelete] hears about every deleted profile that was already in the cloud (collection name
 * and remote id), so cloud sync can delete it there too instead of pulling it back.
 */
class ProfileRepository(
    private val calibrationDao: CalibrationProfileDao,
    private val gameDao: GameProfileDao,
    private val onRemoteDelete: suspend (collection: String, remoteId: String) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val calibrationProfiles: Flow<List<CalibrationProfile>> = calibrationDao.observeAll()
    val gameProfiles: Flow<List<GameProfile>> = gameDao.observeAll()

    fun gameProfilesFor(gameId: String): Flow<List<GameProfile>> = gameDao.observeForGame(gameId)

    suspend fun getCalibrationProfile(id: Long): CalibrationProfile? = calibrationDao.getById(id)

    suspend fun getGameProfile(id: Long): GameProfile? = gameDao.getById(id)

    /** The profile to use when the user just says "play <game>": last played, else newest. */
    suspend fun lastPlayedGameProfile(gameId: String): GameProfile? = gameDao.lastPlayedFor(gameId)

    /** Doesn't touch updatedAt, so playing never reorders "newest first" lists. */
    suspend fun markGameProfilePlayed(id: Long) = gameDao.markPlayed(id, clock())

    suspend fun saveCalibrationProfile(profile: CalibrationProfile): Long {
        val now = clock()
        return if (profile.id == 0L) {
            calibrationDao.insert(profile.copy(createdAt = now, updatedAt = now))
        } else {
            calibrationDao.update(profile.copy(updatedAt = now))
            profile.id
        }
    }

    suspend fun saveGameProfile(profile: GameProfile): Long {
        val now = clock()
        return if (profile.id == 0L) {
            gameDao.insert(profile.copy(createdAt = now, updatedAt = now))
        } else {
            gameDao.update(profile.copy(updatedAt = now))
            profile.id
        }
    }

    suspend fun deleteCalibrationProfile(profile: CalibrationProfile) {
        calibrationDao.delete(profile)
        profile.remoteId?.let { onRemoteDelete(CALIBRATION_COLLECTION, it) }
    }

    suspend fun deleteGameProfile(profile: GameProfile) {
        gameDao.delete(profile)
        profile.remoteId?.let { onRemoteDelete(GAME_COLLECTION, it) }
    }

    // ---- Cloud sync only: writes rows exactly as given, keeping their timestamps. ----

    suspend fun allCalibrationProfiles(): List<CalibrationProfile> = calibrationDao.getAll()

    suspend fun allGameProfiles(): List<GameProfile> = gameDao.getAll()

    /** Inserts (id 0) or overwrites a calibration profile without touching updatedAt. */
    suspend fun writeSyncedCalibrationProfile(profile: CalibrationProfile): Long =
        if (profile.id == 0L) calibrationDao.insert(profile) else profile.id.also { calibrationDao.update(profile) }

    /** Inserts (id 0) or overwrites a game profile without touching updatedAt. */
    suspend fun writeSyncedGameProfile(profile: GameProfile): Long =
        if (profile.id == 0L) gameDao.insert(profile) else profile.id.also { gameDao.update(profile) }

    /** Removes a profile the cloud deleted; unlike the user-facing deletes, doesn't report it back. */
    suspend fun removeSyncedCalibrationProfile(profile: CalibrationProfile) = calibrationDao.delete(profile)

    suspend fun removeSyncedGameProfile(profile: GameProfile) = gameDao.delete(profile)

    companion object {
        /** Firestore collection names under `users/{uid}`, also used for pending cloud deletes. */
        const val CALIBRATION_COLLECTION = "calibrationProfiles"
        const val GAME_COLLECTION = "gameProfiles"
    }
}
