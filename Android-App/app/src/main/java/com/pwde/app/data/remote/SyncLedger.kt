package com.pwde.app.data.remote

import android.content.Context

/**
 * What sync remembers between runs on this phone: cloud deletes still to send, which account the
 * local profiles were last synced with, and when.
 */
interface SyncLedger {
    var lastUid: String?
    var lastSyncedAt: Long?

    fun recordDelete(collection: String, remoteId: String)
    fun pendingDeletes(): List<Pair<String, String>>
    fun clearDelete(collection: String, remoteId: String)
    fun clearAllDeletes()

    class InMemory : SyncLedger {
        override var lastUid: String? = null
        override var lastSyncedAt: Long? = null
        private val deletes = LinkedHashSet<Pair<String, String>>()

        override fun recordDelete(collection: String, remoteId: String) {
            synchronized(deletes) { deletes += collection to remoteId }
        }
        override fun pendingDeletes() = synchronized(deletes) { deletes.toList() }
        override fun clearDelete(collection: String, remoteId: String) {
            synchronized(deletes) { deletes -= collection to remoteId }
        }
        override fun clearAllDeletes() = synchronized(deletes) { deletes.clear() }
    }
}

class SharedPrefsSyncLedger(context: Context) : SyncLedger {
    private val prefs = context.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE)

    override var lastUid: String?
        get() = prefs.getString(KEY_UID, null)
        set(value) = prefs.edit().putString(KEY_UID, value).apply()

    override var lastSyncedAt: Long?
        get() = prefs.getLong(KEY_SYNCED_AT, 0L).takeIf { it > 0L }
        set(value) = prefs.edit().putLong(KEY_SYNCED_AT, value ?: 0L).apply()

    @Synchronized
    override fun recordDelete(collection: String, remoteId: String) = editDeletes { it += "$collection/$remoteId" }

    @Synchronized
    override fun pendingDeletes(): List<Pair<String, String>> = deletes().mapNotNull { entry ->
        entry.split('/', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
    }

    @Synchronized
    override fun clearDelete(collection: String, remoteId: String) = editDeletes { it -= "$collection/$remoteId" }

    @Synchronized
    override fun clearAllDeletes() = editDeletes { it.clear() }

    private fun deletes(): Set<String> = prefs.getStringSet(KEY_DELETES, emptySet()).orEmpty()

    private fun editDeletes(change: (MutableSet<String>) -> Unit) {
        // Copy: the set getStringSet returns must not be modified.
        val updated = deletes().toMutableSet().also(change)
        prefs.edit().putStringSet(KEY_DELETES, updated).apply()
    }

    private companion object {
        const val KEY_UID = "last_uid"
        const val KEY_SYNCED_AT = "last_synced_at"
        const val KEY_DELETES = "pending_deletes"
    }
}
