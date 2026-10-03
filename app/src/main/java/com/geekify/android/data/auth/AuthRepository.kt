package com.geekify.android.data.auth

import android.content.Context
import com.geekify.android.R
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

data class AccountUser(val uid: String, val email: String?, val name: String, val photoUrl: String?)

enum class SyncState { IDLE, SYNCING, SAVED, ERROR }

/** Mirrors useAuthStore.ts + friendlyAuthError. */
@Singleton
class AuthRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private val auth: FirebaseAuth = Firebase.auth

    private val _user = MutableStateFlow<AccountUser?>(null)
    val user: StateFlow<AccountUser?> = _user.asStateFlow()

    init {
        auth.addAuthStateListener { fa ->
            _user.value = fa.currentUser?.let { u ->
                val fallback = u.email?.substringBefore("@") ?: "Listener"
                AccountUser(u.uid, u.email, u.displayName?.trim()?.ifBlank { fallback } ?: fallback, u.photoUrl?.toString())
            }
        }
    }

    suspend fun signInEmail(email: String, password: String) {
        auth.signInWithEmailAndPassword(email.trim(), password).await()
    }

    suspend fun signUpEmail(name: String, email: String, password: String) {
        val cred = auth.createUserWithEmailAndPassword(email.trim(), password).await()
        cred.user?.updateProfile(com.google.firebase.auth.userProfileChangeRequest { displayName = name.trim() })?.await()
    }

    suspend fun signInGoogle() {
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(context.getString(R.string.default_web_client_id))
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val result = CredentialManager.create(context).getCredential(context, request)
        val googleId = GoogleIdTokenCredential.createFrom(result.credential.data)
        val firebaseCred = GoogleAuthProvider.getCredential(googleId.idToken, null)
        auth.signInWithCredential(firebaseCred).await()
    }

    suspend fun resetPassword(email: String) {
        auth.sendPasswordResetEmail(email.trim()).await()
    }

    suspend fun signOut() = auth.signOut()

    fun friendlyError(e: Throwable): String {
        val code = (e as? FirebaseAuthException)?.errorCode ?: ""
        return when (code) {
            "ERROR_INVALID_EMAIL" -> "That email address doesn't look right."
            "ERROR_USER_NOT_FOUND", "ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL",
            "ERROR_INVALID_LOGIN_CREDENTIALS" -> "Incorrect email or password."
            "ERROR_EMAIL_ALREADY_IN_USE" -> "An account with this email already exists. Try signing in instead."
            "ERROR_WEAK_PASSWORD" -> "Choose a stronger password (at least 6 characters)."
            "ERROR_TOO_MANY_REQUESTS" -> "Too many attempts. Wait a minute and try again."
            "ERROR_NETWORK_REQUEST_FAILED" -> "Network problem. Check your connection and try again."
            "ERROR_OPERATION_NOT_ALLOWED" -> "This sign-in method isn't enabled in Firebase yet."
            else -> "Something went wrong. Please try again."
        }
    }
}
