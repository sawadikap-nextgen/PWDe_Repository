package com.pwde.app.data.remote

import com.pwde.app.data.games.CustomGamesRepository
import com.pwde.app.data.local.ControlsRepository
import com.pwde.app.data.local.ProfileRepository
import com.pwde.app.data.local.ProfileRepository.Companion.CALIBRATION_COLLECTION
import com.pwde.app.data.local.ProfileRepository.Companion.GAME_COLLECTION
import com.pwde.app.data.prefs.SettingsRepository
import com.pwde.app.data.remote.CloudMappers.toCloud
import com.pwde.app.data.remote.CloudStore.Companion.CONTROLS_DOC
import com.pwde.app.data.remote.CloudStore.Companion.CUSTOM_GAMES_DOC
import com.pwde.app.data.remote.CloudStore.Companion.SETTINGS_DOC
import com.pwde.app.data.remote.CloudStore.Companion.STATE_COLLECTION
import kotlinx.coroutines.flow.first

/**
 * One two-way sync of everything PWDe keeps for the user: calibration profiles, game profiles, the
 * working controls, app settings and added games. Each record keeps whichever copy changed last;
 * nothing on this phone is deleted unless it was deleted in the cloud after its last change here.
 */
class CloudSyncEngine(
    private val store: CloudStore,
    private val profiles: ProfileRepository,
    private val controls: ControlsRepository,
    private val settings: SettingsRepository,
    private val customGames: CustomGamesRepository,
    private val ledger: SyncLedger,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun sync(uid: String, email: String? = null, displayName: String? = null) {
        val now = clock()
        val lastUid = ledger.lastUid
        if (lastUid != null && lastUid != uid) forgetPreviousAccount()
        ledger.lastUid = uid

        store.putUser(uid, mapOf("email" to email, "displayName" to displayName, "lastSyncedAt" to now))
        sendPendingDeletes(uid, now)
        syncCalibrations(uid, now)

        val calibrations = profiles.allCalibrationProfiles().filter { it.remoteId != null }
        val remoteIdByLocal = calibrations.associate { it.id to it.remoteId!! }
        val localIdByRemote = calibrations.associate { it.remoteId!! to it.id }

        syncGames(uid, now, remoteIdByLocal, localIdByRemote)
        syncControls(uid, remoteIdByLocal, localIdByRemote)
        syncSettings(uid)
        syncCustomGames(uid, now)
        ledger.lastSyncedAt = now
    }

    /**
     * Signed in to a different account than last time: the local profiles' remote ids point into the
     * old account, so they are uploaded to the new one as new records instead (nothing is lost).
     */
    private suspend fun forgetPreviousAccount() {
        profiles.allCalibrationProfiles().filter { it.remoteId != null }
            .forEach { profiles.writeSyncedCalibrationProfile(it.copy(remoteId = null, lastSyncedAt = null)) }
        profiles.allGameProfiles().filter { it.remoteId != null }
            .forEach { profiles.writeSyncedGameProfile(it.copy(remoteId = null, lastSyncedAt = null)) }
        ledger.clearAllDeletes()
    }

    /** Profiles deleted on this phone become tombstones, so other phones delete them too. */
    private suspend fun sendPendingDeletes(uid: String, now: Long) {
        for ((collection, remoteId) in ledger.pendingDeletes()) {
            store.put(uid, collection, remoteId, mapOf(CloudDoc.FIELD_DELETED to true, CloudDoc.FIELD_UPDATED_AT to now))
            ledger.clearDelete(collection, remoteId)
        }
    }

    private suspend fun syncCalibrations(uid: String, now: Long) {
        val steps = planMerge(
            profiles.allCalibrationProfiles(),
            store.list(uid, CALIBRATION_COLLECTION),
            remoteIdOf = { it.remoteId },
            updatedAtOf = { it.updatedAt },
        )
        for (step in steps) when (step) {
            is MergeStep.Push -> {
                val id = step.local.remoteId ?: store.newId(uid, CALIBRATION_COLLECTION)
                store.put(uid, CALIBRATION_COLLECTION, id, step.local.toCloud())
                profiles.writeSyncedCalibrationProfile(step.local.copy(remoteId = id, lastSyncedAt = now))
            }
            is MergeStep.Pull ->
                profiles.writeSyncedCalibrationProfile(CloudMappers.calibrationFromCloud(step.remote, step.local?.id ?: 0L, now))
            is MergeStep.DeleteLocal -> profiles.removeSyncedCalibrationProfile(step.local)
        }
    }

    private suspend fun syncGames(uid: String, now: Long, remoteIdByLocal: Map<Long, String>, localIdByRemote: Map<String, Long>) {
        val steps = planMerge(
            profiles.allGameProfiles(),
            store.list(uid, GAME_COLLECTION),
            remoteIdOf = { it.remoteId },
            updatedAtOf = { it.updatedAt },
        )
        for (step in steps) when (step) {
            is MergeStep.Push -> {
                val id = step.local.remoteId ?: store.newId(uid, GAME_COLLECTION)
                val calibrationRemoteId = step.local.calibrationProfileId?.let(remoteIdByLocal::get)
                store.put(uid, GAME_COLLECTION, id, step.local.toCloud(calibrationRemoteId))
                profiles.writeSyncedGameProfile(step.local.copy(remoteId = id, lastSyncedAt = now))
            }
            is MergeStep.Pull -> {
                val calibrationId = CloudMappers.calibrationRemoteIdOf(step.remote)?.let(localIdByRemote::get)
                profiles.writeSyncedGameProfile(CloudMappers.gameFromCloud(step.remote, step.local, calibrationId, now))
            }
            is MergeStep.DeleteLocal -> profiles.removeSyncedGameProfile(step.local)
        }
    }

    private suspend fun syncControls(uid: String, remoteIdByLocal: Map<Long, String>, localIdByRemote: Map<String, Long>) {
        val local = controls.syncedEntity()
        val remote = store.get(uid, STATE_COLLECTION, CONTROLS_DOC)
        when {
            remote != null && (local == null || remote.updatedAt > local.updatedAt) -> {
                val activeId = CloudMappers.activeCalibrationRemoteIdOf(remote)?.let(localIdByRemote::get)
                controls.writeSyncedEntity(CloudMappers.controlsFromCloud(remote, activeId))
            }
            local != null && (remote == null || local.updatedAt > remote.updatedAt) -> {
                val activeRemoteId = local.activeCalibrationProfileId?.let(remoteIdByLocal::get)
                store.put(uid, STATE_COLLECTION, CONTROLS_DOC, local.toCloud(activeRemoteId))
            }
        }
    }

    private suspend fun syncSettings(uid: String) {
        val local = settings.settings.first()
        val remote = store.get(uid, STATE_COLLECTION, SETTINGS_DOC)
        when {
            remote != null && remote.updatedAt > local.updatedAt ->
                settings.applySynced(CloudMappers.settingsFromCloud(remote, local))
            // updatedAt 0: never changed here, so there's nothing of the user's to upload yet.
            local.updatedAt > 0L && (remote == null || local.updatedAt > remote.updatedAt) ->
                store.put(uid, STATE_COLLECTION, SETTINGS_DOC, local.toCloud())
        }
    }

    /** Added games are only ever added, so both sides end up with the union. */
    private suspend fun syncCustomGames(uid: String, now: Long) {
        val remote = store.get(uid, STATE_COLLECTION, CUSTOM_GAMES_DOC)
        val remoteNames = remote?.let(CloudMappers::customGamesFromCloud).orEmpty()
        customGames.addAll(remoteNames)
        val merged = customGames.names.first()
        if (merged.isNotEmpty() && merged.toSet() != remoteNames.toSet()) {
            store.put(uid, STATE_COLLECTION, CUSTOM_GAMES_DOC, CloudMappers.customGamesToCloud(merged, now))
        }
    }
}
