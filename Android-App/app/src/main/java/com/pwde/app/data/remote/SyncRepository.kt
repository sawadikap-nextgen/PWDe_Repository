package com.pwde.app.data.remote

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

sealed interface SyncStatus {
    /** Guest: everything lives on this device. */
    data object LocalOnly : SyncStatus

    /** Signed in, but this build can't reach Firestore (no project id). Local data is untouched. */
    data object NotAvailable : SyncStatus

    data object Syncing : SyncStatus

    /** Up to date as of [at] (null: signed in, not synced yet). */
    data class Synced(val at: Long?) : SyncStatus

    /** The last sync failed; local data is untouched and the next one retries. */
    data class Error(val message: String) : SyncStatus
}

/** Cloud sync of profiles, controls and settings, keyed by Firebase UID. Only meaningful when signed in. */
interface SyncRepository {
    val status: Flow<SyncStatus>
    suspend fun syncNow(): SyncStatus
}

/** For builds without Firestore: reports the state, never syncs. */
class NoOpSyncRepository(private val authRepository: AuthRepository) : SyncRepository {
    override val status: Flow<SyncStatus> = authRepository.authState.map { it.toStatus() }

    override suspend fun syncNow(): SyncStatus = authRepository.authState.value.toStatus()

    private fun AuthState.toStatus(): SyncStatus = when (this) {
        AuthState.Guest -> SyncStatus.LocalOnly
        is AuthState.SignedIn -> SyncStatus.NotAvailable
    }
}

/**
 * Syncs with [engine] whenever the user signs in, and [LOCAL_CHANGE_DELAY_MS] after local data
 * ([localChanges]) stops changing while signed in. A sync's own writes trigger one more pass, which
 * finds nothing to do.
 */
@OptIn(FlowPreview::class)
class FirestoreSyncRepository(
    private val authRepository: AuthRepository,
    private val engine: CloudSyncEngine,
    private val ledger: SyncLedger,
    localChanges: Flow<Unit>,
    scope: CoroutineScope,
) : SyncRepository {
    private val mutex = Mutex()

    /** The signed-in user's last result; null until the first sync of this run. */
    private val progress = MutableStateFlow<SyncStatus?>(null)

    override val status: Flow<SyncStatus> = combine(authRepository.authState, progress) { auth, progress ->
        if (auth is AuthState.Guest) SyncStatus.LocalOnly else progress ?: SyncStatus.Synced(ledger.lastSyncedAt)
    }

    init {
        scope.launch {
            authRepository.authState
                .map { (it as? AuthState.SignedIn)?.uid }
                .distinctUntilChanged()
                .collect { uid ->
                    progress.value = null
                    if (uid != null) syncNow()
                }
        }
        scope.launch {
            localChanges.debounce(LOCAL_CHANGE_DELAY_MS).collect {
                if (authRepository.authState.value is AuthState.SignedIn) syncNow()
            }
        }
    }

    override suspend fun syncNow(): SyncStatus {
        val user = authRepository.authState.value as? AuthState.SignedIn ?: return SyncStatus.LocalOnly
        return mutex.withLock {
            progress.value = SyncStatus.Syncing
            val result = try {
                withTimeout(TIMEOUT_MS) { engine.sync(user.uid, user.email, user.displayName) }
                SyncStatus.Synced(ledger.lastSyncedAt)
            } catch (e: TimeoutCancellationException) {
                SyncStatus.Error("Couldn't reach the cloud. Check your connection; we'll try again.")
            } catch (e: CancellationException) {
                progress.value = null
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Sync failed", e)
                SyncStatus.Error("Sync didn't finish. Your profiles are safe on this phone; we'll try again.")
            }
            progress.value = result
            result
        }
    }

    private companion object {
        const val TAG = "SyncRepository"
        const val LOCAL_CHANGE_DELAY_MS = 3_000L
        const val TIMEOUT_MS = 30_000L
    }
}
