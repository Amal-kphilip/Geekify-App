package com.geekify.android.data.auth

import android.content.Context
import com.geekify.android.R
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
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
    private val TAG = "GeekifyAuth"

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

    /** Thrown when the person closes the Google account picker; callers should stay silent. */
    class GoogleSignInCancelledException : Exception()

    /** A Google sign-in failure with a message that is safe to show to the person. */
    class GoogleSignInException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * [activityContext] must be an Activity: Credential Manager shows its account sheet through it,
     * and with the application context the sheet can never appear.
     */
    suspend fun signInGoogle(activityContext: Context) {
        val option = GetSignInWithGoogleOption.Builder(context.getString(R.string.default_web_client_id)).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val googleId = try {
            val result = CredentialManager.create(activityContext).getCredential(activityContext, request)
            val credential = result.credential
            if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                throw GoogleSignInException("Google returned an unexpected sign-in response.")
            }
            GoogleIdTokenCredential.createFrom(credential.data)
        } catch (e: GetCredentialCancellationException) {
            throw GoogleSignInCancelledException()
        } catch (e: NoCredentialException) {
            Log.e(TAG, "No Google credential available", e)
            throw GoogleSignInException(
                "Google sign-in isn't available. Make sure a Google account is added on this phone, " +
                    "and that this app's SHA-1 fingerprint is registered in Firebase.", e
            )
        } catch (e: GetCredentialProviderConfigurationException) {
            Log.e(TAG, "Credential provider not configured", e)
            throw GoogleSignInException("Google Play services needs to be updated to use Google sign-in.", e)
        } catch (e: GetCredentialException) {
            Log.e(TAG, "Google sign-in failed", e)
            throw GoogleSignInException("Google sign-in failed (${e.type.substringAfterLast('.')}). Please try again.", e)
        } catch (e: GoogleIdTokenParsingException) {
            Log.e(TAG, "Bad Google ID token", e)
            throw GoogleSignInException("Couldn't read the Google sign-in response. Please try again.", e)
        }
        val firebaseCred = GoogleAuthProvider.getCredential(googleId.idToken, null)
        auth.signInWithCredential(firebaseCred).await()
    }

    suspend fun resetPassword(email: String) {
        auth.sendPasswordResetEmail(email.trim()).await()
    }

    suspend fun signOut() = auth.signOut()

    fun friendlyError(e: Throwable): String {
        if (e is GoogleSignInException) return e.message ?: "Google sign-in failed."
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
