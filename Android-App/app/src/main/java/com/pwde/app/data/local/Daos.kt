package com.pwde.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CalibrationProfileDao {
    @Query("SELECT * FROM calibration_profiles ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<CalibrationProfile>>

    @Query("SELECT * FROM calibration_profiles")
    suspend fun getAll(): List<CalibrationProfile>

    @Query("SELECT * FROM calibration_profiles WHERE id = :id")
    suspend fun getById(id: Long): CalibrationProfile?

    @Insert
    suspend fun insert(profile: CalibrationProfile): Long

    @Update
    suspend fun update(profile: CalibrationProfile)

    @Delete
    suspend fun delete(profile: CalibrationProfile)
}

@Dao
interface GameProfileDao {
    @Query("SELECT * FROM game_profiles ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<GameProfile>>

    @Query("SELECT * FROM game_profiles")
    suspend fun getAll(): List<GameProfile>

    @Query("SELECT * FROM game_profiles WHERE gameId = :gameId ORDER BY updatedAt DESC")
    fun observeForGame(gameId: String): Flow<List<GameProfile>>

    @Query("SELECT * FROM game_profiles WHERE id = :id")
    suspend fun getById(id: Long): GameProfile?

    /** The profile played most recently for [gameId], else the newest one. */
    @Query(
        "SELECT * FROM game_profiles WHERE gameId = :gameId " +
            "ORDER BY lastPlayedAt IS NULL, lastPlayedAt DESC, createdAt DESC LIMIT 1",
    )
    suspend fun lastPlayedFor(gameId: String): GameProfile?

    @Query("UPDATE game_profiles SET lastPlayedAt = :playedAt WHERE id = :id")
    suspend fun markPlayed(id: Long, playedAt: Long)

    @Insert
    suspend fun insert(profile: GameProfile): Long

    @Update
    suspend fun update(profile: GameProfile)

    @Delete
    suspend fun delete(profile: GameProfile)
}

@Dao
interface ControlSettingsDao {
    @Query("SELECT * FROM control_settings WHERE id = ${ControlSettingsEntity.SINGLETON_ID}")
    fun observe(): Flow<ControlSettingsEntity?>

    @Query("SELECT * FROM control_settings WHERE id = ${ControlSettingsEntity.SINGLETON_ID}")
    suspend fun get(): ControlSettingsEntity?

    @Upsert
    suspend fun upsert(entity: ControlSettingsEntity)
}

@Dao
interface GabAiSessionDao {
    /** The most recent session the user hasn't finished. */
    @Query("SELECT * FROM gabai_sessions WHERE completed = 0 ORDER BY updatedAt DESC LIMIT 1")
    fun observeUnfinished(): Flow<GabAiSessionEntity?>

    @Query("SELECT * FROM gabai_sessions WHERE completed = 0 ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getUnfinished(): GabAiSessionEntity?

    @Query("SELECT * FROM gabai_sessions WHERE sessionId = :sessionId")
    suspend fun get(sessionId: String): GabAiSessionEntity?

    @Upsert
    suspend fun upsert(session: GabAiSessionEntity)

    @Query("UPDATE gabai_sessions SET completed = 1, updatedAt = :now WHERE sessionId = :sessionId")
    suspend fun markCompleted(sessionId: String, now: Long)

    @Query("DELETE FROM gabai_sessions WHERE completed = 1")
    suspend fun deleteCompleted()
}
