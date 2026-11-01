package com.geekify.android.data.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import com.geekify.android.R
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import timber.log.Timber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class AccountUser(
    val uid: String,
    val email: String?,
    val name: String,
    val photoUrl: String?,
    /** Signed up with email + password (as opposed to Google). */
    val hasPassword: Boolean = false,
    /** [photoUrl] is a picture the person picked on this phone. */
    val hasCustomPhoto: Boolean = false,
    /** Whether the user's email address has been verified. */
    val emailVerified: Boolean = true
)

enum class SyncState { IDLE, SYNCING, SAVED, OFFLINE, ERROR }

/** Mirrors useAuthStore.ts + friendlyAuthError. */
@Singleton
class AuthRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private val auth: FirebaseAuth = Firebase.auth
    private val TAG = "GeekifyAuth"

    private val _user = MutableStateFlow<AccountUser?>(null)
    val user: StateFlow<AccountUser?> = _user.asStateFlow()

    private val profilePrefs = context.getSharedPreferences("geekify_profile", Context.MODE_PRIVATE)

    companion object {
        private val secureRandom = SecureRandom()
        private val jwtJson = Json { ignoreUnknownKeys = true }

        /** Generates a cryptographically random 256-bit nonce encoded as hex. */
        fun generateRawNonce(): String {
            val bytes = ByteArray(32)
            secureRandom.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** Computes the lowercase SHA-256 hex digest of [input]. */
        fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        /**
         * Verifies that a Google ID token JWT carries the expected [hashedNonce] (or [rawNonce])
         * in its payload `nonce` claim to prevent token replay attacks.
         */
        fun verifyIdTokenNonce(idToken: String, hashedNonce: String, rawNonce: String): Boolean {
            val parts = idToken.split('.')
            if (parts.size < 2) return false
            val payloadJson = runCatching {
                val decoded = Base64.getUrlDecoder().decode(parts[1])
                String(decoded, Charsets.UTF_8)
            }.getOrNull() ?: return false
            val claimNonce = runCatching {
                jwtJson.parseToJsonElement(payloadJson).jsonObject["nonce"]?.jsonPrimitive?.contentOrNull
            }.getOrNull() ?: return false
            return claimNonce == hashedNonce || claimNonce == rawNonce
        }
    }

    init {
        // Existing authentication sessions are preserved across app restarts.
        auth.addAuthStateListener { publishUser() }
    }

    private fun avatarKey(uid: String) = "avatar_$uid"

    private fun localAvatar(uid: String): File? =
        profilePrefs.getString(avatarKey(uid), null)?.let(::File)?.takeIf { it.exists() }

    /** Rebuilds [user] from Firebase plus the on-device profile picture. Also called after profile edits. */
    private fun publishUser() {
        _user.value = auth.currentUser?.let { u ->
            val fallback = u.email?.substringBefore("@") ?: "Listener"
            val local = localAvatar(u.uid)
            val isPasswordAccount = u.providerData.any { it.providerId == "password" }
            AccountUser(
                uid = u.uid,
                email = u.email,
                name = u.displayName?.trim()?.ifBlank { fallback } ?: fallback,
                photoUrl = local?.let { android.net.Uri.fromFile(it).toString() } ?: u.photoUrl?.toString(),
                hasPassword = isPasswordAccount,
                hasCustomPhoto = local != null,
                emailVerified = !isPasswordAccount || u.isEmailVerified
            )
        }
    }

    suspend fun updateDisplayName(name: String) {
        val u = auth.currentUser ?: return
        u.updateProfile(com.google.firebase.auth.userProfileChangeRequest { displayName = name.trim() }).await()
        u.reload().await()
        publishUser()
    }

    /** Saves an already-downscaled JPEG as this account's profile picture (kept on this phone). */
    suspend fun setLocalAvatar(jpeg: ByteArray) = withContext(Dispatchers.IO) {
        val u = auth.currentUser ?: return@withContext
        val dir = File(context.filesDir, "avatars").apply { mkdirs() }
        // A new file name each time, so image caches never show the previous picture.
        val file = File(dir, "${u.uid}_${System.currentTimeMillis()}.jpg")
        file.writeBytes(jpeg)
        localAvatar(u.uid)?.delete()
        profilePrefs.edit().putString(avatarKey(u.uid), file.absolutePath).apply()
        publishUser()
    }

    suspend fun removeLocalAvatar() = withContext(Dispatchers.IO) {
        val u = auth.currentUser ?: return@withContext
        localAvatar(u.uid)?.delete()
        profilePrefs.edit().remove(avatarKey(u.uid)).apply()
        publishUser()
    }

    /** Proves it is really the account owner (password, or Google's account sheet) before a destructive action. */
    suspend fun reauthenticate(password: String?, activityContext: Context) {
        val u = auth.currentUser ?: throw IllegalStateException("You're not signed in.")
        val credential: AuthCredential = if (u.providerData.any { it.providerId == "password" }) {
            val email = u.email ?: throw IllegalStateException("This account has no email address.")
            if (password.isNullOrEmpty()) throw IllegalArgumentException("Enter your password to continue.")
            EmailAuthProvider.getCredential(email, password)
        } else {
            fetchGoogleCredential(activityContext)
        }
        u.reauthenticate(credential).await()
    }

    /** Permanently deletes the Firebase account and this phone's profile picture. */
    suspend fun deleteAccount() {
        val u = auth.currentUser ?: return
        val uid = u.uid
        u.delete().await()
        withContext(Dispatchers.IO) {
            localAvatar(uid)?.delete()
            profilePrefs.edit().remove(avatarKey(uid)).apply()
        }
        publishUser()
    }

    class EmailVerificationRequiredException(message: String) : Exception(message)

    suspend fun signInEmail(email: String, password: String) {
        val trimmedEmail = email.trim()
        val cred = auth.signInWithEmailAndPassword(trimmedEmail, password).await()
        val signedInUser = cred.user
        if (signedInUser != null &&
            signedInUser.providerData.any { it.providerId == "password" } &&
            !signedInUser.isEmailVerified
        ) {
            runCatching { signedInUser.sendEmailVerification().await() }
            auth.signOut()
            throw EmailVerificationRequiredException(
                "Please verify your email address ($trimmedEmail). We've sent a verification link to your inbox."
            )
        }
    }

    suspend fun signUpEmail(name: String, email: String, password: String) {
        val trimmedEmail = email.trim()
        val cred = auth.createUserWithEmailAndPassword(trimmedEmail, password).await()
        val createdUser = cred.user
        createdUser?.updateProfile(com.google.firebase.auth.userProfileChangeRequest { displayName = name.trim() })?.await()
        runCatching { createdUser?.sendEmailVerification()?.await() }
        if (createdUser != null && !createdUser.isEmailVerified) {
            auth.signOut()
            throw EmailVerificationRequiredException(
                "Account created! Check $trimmedEmail and click the verification link before signing in."
            )
        }
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
        auth.signInWithCredential(fetchGoogleCredential(activityContext)).await()
    }

    private suspend fun fetchGoogleCredential(activityContext: Context): AuthCredential {
        val rawNonce = generateRawNonce()
        val hashedNonce = sha256Hex(rawNonce)
        val option = GetSignInWithGoogleOption.Builder(context.getString(R.string.default_web_client_id))
            .setNonce(hashedNonce)
            .build()
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
            Timber.tag(TAG).e(e, "No Google credential available")
            throw GoogleSignInException(
                "Google sign-in isn't available. Make sure a Google account is added on this phone, " +
                    "and that this app's SHA-1 fingerprint is registered in Firebase.", e
            )
        } catch (e: GetCredentialProviderConfigurationException) {
            Timber.tag(TAG).e(e, "Credential provider not configured")
            throw GoogleSignInException("Google Play services needs to be updated to use Google sign-in.", e)
        } catch (e: GetCredentialException) {
            Timber.tag(TAG).e(e, "Google sign-in failed")
            throw GoogleSignInException("Google sign-in failed (${e.type.substringAfterLast('.')}). Please try again.", e)
        } catch (e: GoogleIdTokenParsingException) {
            Timber.tag(TAG).e(e, "Bad Google ID token")
            throw GoogleSignInException("Couldn't read the Google sign-in response. Please try again.", e)
        }
        if (!verifyIdTokenNonce(googleId.idToken, hashedNonce = hashedNonce, rawNonce = rawNonce)) {
            throw GoogleSignInException("Google sign-in security verification failed. Please try again.")
        }
        return GoogleAuthProvider.getCredential(googleId.idToken, null)
    }

    suspend fun resetPassword(email: String) {
        auth.sendPasswordResetEmail(email.trim()).await()
    }

    suspend fun signOut() = auth.signOut()

    fun friendlyError(e: Throwable): String {
        if (e is EmailVerificationRequiredException) return e.message ?: "Please verify your email address before signing in."
        if (e is GoogleSignInException) return e.message ?: "Google sign-in failed."
        if (e is IllegalArgumentException || e is IllegalStateException) return e.message ?: "Something went wrong. Please try again."
        val code = (e as? FirebaseAuthException)?.errorCode ?: ""
        return when (code) {
            "ERROR_INVALID_EMAIL" -> "That email address doesn't look right."
            "ERROR_USER_NOT_FOUND", "ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL",
            "ERROR_INVALID_LOGIN_CREDENTIALS" -> "Incorrect email or password."
            "ERROR_EMAIL_ALREADY_IN_USE" -> "An account with this email already exists. Try signing in instead."
            "ERROR_WEAK_PASSWORD" -> "Choose a stronger password (at least 6 characters)."
            "ERROR_TOO_MANY_REQUESTS" -> "Too many attempts. Wait a minute and try again."
            "ERROR_NETWORK_REQUEST_FAILED" -> "Network problem. Check your connection and try again."
            "ERROR_REQUIRES_RECENT_LOGIN" -> "For your security, sign in again and retry."
            "ERROR_OPERATION_NOT_ALLOWED" -> "This sign-in method isn't enabled in Firebase yet."
            else -> "Something went wrong. Please try again."
        }
    }
}
