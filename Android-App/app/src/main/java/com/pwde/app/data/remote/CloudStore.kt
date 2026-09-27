package com.pwde.app.data.remote

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

/**
 * The cloud side of sync: documents under `users/{uid}/{collection}/{id}`, plus the `users/{uid}` doc
 * itself. An interface so the sync engine can be tested without Firebase.
 */
interface CloudStore {
    suspend fun list(uid: String, collection: String): List<CloudDoc>
    suspend fun get(uid: String, collection: String, id: String): CloudDoc?
    suspend fun put(uid: String, collection: String, id: String, data: Map<String, Any?>)
    fun newId(uid: String, collection: String): String
    suspend fun putUser(uid: String, data: Map<String, Any?>)

    companion object {
        /** Singleton documents (controls, settings, added games) live in this collection. */
        const val STATE_COLLECTION = "state"
        const val CONTROLS_DOC = "controls"
        const val SETTINGS_DOC = "settings"
        const val CUSTOM_GAMES_DOC = "customGames"
    }
}

/** [CloudStore] on Cloud Firestore. Security rules (firebase/firestore.rules) keep each user to their own tree. */
class FirestoreCloudStore(private val db: FirebaseFirestore) : CloudStore {
    private fun user(uid: String) = db.collection(USERS).document(uid)

    override suspend fun list(uid: String, collection: String): List<CloudDoc> =
        user(uid).collection(collection).get().await().documents.map { CloudDoc(it.id, it.data.orEmpty()) }

    override suspend fun get(uid: String, collection: String, id: String): CloudDoc? {
        val snapshot = user(uid).collection(collection).document(id).get().await()
        return snapshot.data?.let { CloudDoc(snapshot.id, it) }
    }

    override suspend fun put(uid: String, collection: String, id: String, data: Map<String, Any?>) {
        user(uid).collection(collection).document(id).set(data).await()
    }

    override fun newId(uid: String, collection: String): String = user(uid).collection(collection).document().id

    override suspend fun putUser(uid: String, data: Map<String, Any?>) {
        user(uid).set(data, SetOptions.merge()).await()
    }

    private companion object {
        const val USERS = "users"
    }
}
