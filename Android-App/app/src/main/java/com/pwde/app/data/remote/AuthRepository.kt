package com.pwde.app.data.remote

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.pwde.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

sealed interface AuthState {
    /** Default for everyone. Full functionality, data stays on this device. */
    data object Guest : AuthState

    data class SignedIn(val uid: String, val email: String?, val displayName: String?) : AuthState
}

sealed interface AuthResult {
    data object Success : AuthResult
    data class Error(val message: String) : AuthResult
}

/**
 * Optional account layer. Signing in only unlocks cloud sync; it never gates features and
 * never touches local data. When Firebase isn't configured, [isCloudAvailable] is false and
 * every call returns a friendly error instead of crashing.
 */
interface AuthRepository {
    val isCloudAvailable: Boolean
    val authState: StateFlow<AuthState>

    suspend fun signIn(email: String, password: String): AuthResult
    suspend fun createAccount(email: String, password: String): AuthResult
    suspend fun sendPasswordReset(email: String): AuthResult
    fun signOut()

    companion object {
        private const val TAG = "AuthRepository"

        /** Uses Firebase when a config is present (google-services resources or local.properties). */
        fun create(context: Context): AuthRepository {
            val app = firebaseApp(context)
            return if (app != null) FirebaseAuthRepository(FirebaseAuth.getInstance(app))
            else GuestOnlyAuthRepository()
        }

        /** The configured Firebase app (initialized on first call), or null when this build has none. */
        fun firebaseApp(context: Context): FirebaseApp? = runCatching { initFirebase(context) }
            .onFailure { Log.w(TAG, "Firebase init failed; running guest-only", it) }
            .getOrNull()

        private fun initFirebase(context: Context): FirebaseApp? {
            FirebaseApp.getApps(context).firstOrNull()?.let { return it }
            FirebaseApp.initializeApp(context)?.let { return it }
            if (BuildConfig.FIREBASE_API_KEY.isBlank() || BuildConfig.FIREBASE_APP_ID.isBlank()) return null
            val options = FirebaseOptions.Builder()
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID.ifBlank { null })
                .build()
            return FirebaseApp.initializeApp(context, options)
        }
    }
}

class GuestOnlyAuthRepository : AuthRepository {
    override val isCloudAvailable = false
    override val authState: StateFlow<AuthState> = MutableStateFlow(AuthState.Guest)

    override suspend fun signIn(email: String, password: String) = unavailable()
    override suspend fun createAccount(email: String, password: String) = unavailable()
    override suspend fun sendPasswordReset(email: String) = unavailable()
    override fun signOut() = Unit

    private fun unavailable() = AuthResult.Error(NOT_CONFIGURED_MESSAGE)

    companion object {
        const val NOT_CONFIGURED_MESSAGE =
            "Accounts aren't set up in this build. You can keep using PWDe as a guest — nothing is lost."
    }
}

class FirebaseAuthRepository(private val auth: FirebaseAuth) : AuthRepository {
    override val isCloudAvailable = true

    private val state = MutableStateFlow(auth.currentUser.toAuthState())
    override val authState: StateFlow<AuthState> = state.asStateFlow()

    init {
        auth.addAuthStateListener { state.value = it.currentUser.toAuthState() }
    }

    override suspend fun signIn(email: String, password: String) =
        auth.signInWithEmailAndPassword(email.trim(), password).awaitResult()

    override suspend fun createAccount(email: String, password: String) =
        auth.createUserWithEmailAndPassword(email.trim(), password).awaitResult()

    override suspend fun sendPasswordReset(email: String) =
        auth.sendPasswordResetEmail(email.trim()).awaitResult()

    override fun signOut() = auth.signOut()

    private fun com.google.firebase.auth.FirebaseUser?.toAuthState(): AuthState =
        this?.let { AuthState.SignedIn(it.uid, it.email, it.displayName) } ?: AuthState.Guest
}

private suspend fun <T> Task<T>.awaitResult(): AuthResult = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val result = if (task.isSuccessful) AuthResult.Success
        else AuthResult.Error(task.exception?.localizedMessage ?: "Something went wrong. Please try again.")
        if (cont.isActive) cont.resume(result)
    }
}
