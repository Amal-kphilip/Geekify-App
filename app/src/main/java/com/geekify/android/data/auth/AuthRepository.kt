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
import com.google.firebase.FirebaseApp
import com.google.firebase.ktx.Firebase
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlin.coroutines.resume
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

/** Non-secret account metadata shown in the account switcher. Passwords and tokens are never stored. */
data class KnownAccount(
    val uid: String,
    val email: String?,
    val name: String,
    val photoUrl: String?,
    val hasPassword: Boolean,
    val lastUsedAt: Long,
    /** True when Firebase already has a persistent session for this account on this device. */
    val hasCachedSession: Boolean = false
)

enum class SyncState { IDLE, SYNCING, SAVED, OFFLINE, ERROR }

/** Mirrors useAuthStore.ts + friendlyAuthError. */
@Singleton
class AuthRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private val defaultApp: FirebaseApp = FirebaseApp.getInstance()
    private val defaultAuth: FirebaseAuth = FirebaseAuth.getInstance(defaultApp)
    @Volatile private var activeApp: FirebaseApp = defaultApp
    @Volatile private var auth: FirebaseAuth = defaultAuth
    private val sessionAppLock = Any()
    private val sessionRestoreMutex = Mutex()
    private val sessionRestoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _sessionReady = MutableStateFlow(false)
    val sessionReady: StateFlow<Boolean> = _sessionReady.asStateFlow()
    @Volatile private var preservePreferredUidWhenEmpty = false
    private val activeAuthListener = FirebaseAuth.AuthStateListener { source ->
        if (source === auth && _sessionReady.value) publishUser()
    }
    private val TAG = "GeekifyAuth"

    private val _user = MutableStateFlow<AccountUser?>(null)
    val user: StateFlow<AccountUser?> = _user.asStateFlow()

    private val profilePrefs = context.getSharedPreferences("geekify_profile", Context.MODE_PRIVATE)
    private val accountPrefs = context.getSharedPreferences("geekify_known_accounts", Context.MODE_PRIVATE)
    private val validationAppCounter = AtomicLong()
    private val _knownAccounts = MutableStateFlow(readKnownAccounts())
    val knownAccounts: StateFlow<List<KnownAccount>> = _knownAccounts.asStateFlow()

    /** The selected account UID is exposed only after all persisted Firebase sessions are restored. */
    fun currentUid(): String? = if (_sessionReady.value) auth.currentUser?.uid else null

    suspend fun awaitSessionReady() {
        _sessionReady.first { it }
    }

    companion object {
        private const val KEY_KNOWN_ACCOUNTS = "known_accounts_v1"
        private const val KEY_ACTIVE_ACCOUNT_UID = "active_account_uid_v2"
        private const val MAX_KNOWN_ACCOUNTS = 8
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
        // Start on the default Firebase app but do not publish its user yet. A previous release may
        // have persisted the selected account in a named FirebaseApp. Firebase restores each app's
        // auth state asynchronously; choosing an app before its initial AuthStateListener callback
        // is the source of intermittent "sign in again" prompts after app restart.
        activeApp = defaultApp
        auth = defaultAuth
        auth.addAuthStateListener(activeAuthListener)
        sessionRestoreScope.launch {
            runCatching { restorePersistedSessions() }
                .onFailure { error ->
                    Timber.tag(TAG).e(error, "Could not restore saved account sessions")
                    // Do not leave the UI permanently blocked. The default session is the safest
                    // fallback, and the user can still authenticate normally.
                    _sessionReady.value = true
                    publishUser()
                }
        }
    }

    /**
     * Waits for Firebase's initial auth-state callback before selecting the remembered account.
     * Then warms every saved named FirebaseApp so the account switcher never mistakes a session
     * that is still restoring for an account that needs credentials again.
     */
    suspend fun restorePersistedSessions() = sessionRestoreMutex.withLock {
        if (_sessionReady.value) return@withLock
        withContext(Dispatchers.IO) {
            awaitInitialAuthState(defaultAuth)
            val preferredUid = accountPrefs.getString(KEY_ACTIVE_ACCOUNT_UID, null)
                ?.takeIf(String::isNotBlank)

            val selected = if (preferredUid == null || defaultAuth.currentUser?.uid == preferredUid) {
                defaultApp to defaultAuth
            } else {
                val preferredApp = accountApp(preferredUid)
                val preferredAuth = FirebaseAuth.getInstance(preferredApp)
                // Keep the preferred app selected even if its first callback is delayed. The active
                // listener is attached below and will publish the user as soon as Firebase restores it.
                runCatching { awaitInitialAuthState(preferredAuth) }
                preferredApp to preferredAuth
            }

            bindActiveSession(selected.first, selected.second, publish = false)
            preservePreferredUidWhenEmpty =
                preferredUid != null && selected.second.currentUser?.uid != preferredUid

            val restoredAccounts = _knownAccounts.value.map { account ->
                val accountAuth = runCatching {
                    if (defaultAuth.currentUser?.uid == account.uid) {
                        defaultAuth
                    } else {
                        val app = accountApp(account.uid)
                        FirebaseAuth.getInstance(app).also { awaitInitialAuthState(it) }
                    }
                }.getOrNull()
                account.copy(hasCachedSession = accountAuth?.currentUser?.uid == account.uid)
            }.sortedByDescending { it.lastUsedAt }
            _knownAccounts.value = restoredAccounts
            persistKnownAccounts(restoredAccounts)
            _sessionReady.value = true
            withContext(Dispatchers.Main.immediate) { publishUser() }
        }
    }

    private suspend fun awaitInitialAuthState(targetAuth: FirebaseAuth) {
        withTimeout(15_000L) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val listener = object : FirebaseAuth.AuthStateListener {
                    override fun onAuthStateChanged(firebaseAuth: FirebaseAuth) {
                        firebaseAuth.removeAuthStateListener(this)
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
                targetAuth.addAuthStateListener(listener)
                continuation.invokeOnCancellation { targetAuth.removeAuthStateListener(listener) }
            }
        }
    }

    private suspend fun awaitRestoredAccountAuth(targetAuth: FirebaseAuth) {
        try {
            awaitInitialAuthState(targetAuth)
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            // A late Firebase callback must not be mistaken for a revoked session, or the UI will
            // unnecessarily ask the user for a password / Google picker again.
            throw IllegalStateException(
                "The saved account is still being restored. Please try switching again in a moment.",
                timeout
            )
        }
    }

    private fun avatarKey(uid: String) = "avatar_$uid"

    private fun localAvatar(uid: String): File? =
        profilePrefs.getString(avatarKey(uid), null)?.let(::File)?.takeIf { it.exists() }

    /** Rebuilds [user] from Firebase plus the on-device profile picture. Also called after profile edits. */
    private fun publishUser() {
        val current = auth.currentUser?.let(::mapAccountUser)
        _user.value = current
        if (current != null) {
            preservePreferredUidWhenEmpty = false
            accountPrefs.edit().putString(KEY_ACTIVE_ACCOUNT_UID, current.uid).apply()
            current.takeIf { it.emailVerified }?.let(::rememberAccount)
        } else if (!preservePreferredUidWhenEmpty) {
            val previousUid = accountPrefs.getString(KEY_ACTIVE_ACCOUNT_UID, null)
            if (!previousUid.isNullOrBlank()) {
                val updated = _knownAccounts.value.map { account ->
                    if (account.uid == previousUid) account.copy(hasCachedSession = false) else account
                }
                _knownAccounts.value = updated
                persistKnownAccounts(updated)
            }
            accountPrefs.edit().remove(KEY_ACTIVE_ACCOUNT_UID).apply()
        }
    }

    private fun mapAccountUser(u: com.google.firebase.auth.FirebaseUser): AccountUser {
        val fallback = u.email?.substringBefore("@") ?: "Listener"
        val local = localAvatar(u.uid)
        val isPasswordAccount = u.providerData.any { it.providerId == "password" }
        return AccountUser(
            uid = u.uid,
            email = u.email,
            name = u.displayName?.trim()?.ifBlank { fallback } ?: fallback,
            photoUrl = local?.let { android.net.Uri.fromFile(it).toString() } ?: u.photoUrl?.toString(),
            hasPassword = isPasswordAccount,
            hasCustomPhoto = local != null,
            emailVerified = !isPasswordAccount || u.isEmailVerified
        )
    }

    private fun readKnownAccounts(): List<KnownAccount> = runCatching {
        val array = JSONArray(accountPrefs.getString(KEY_KNOWN_ACCOUNTS, "[]"))
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val uid = item.optString("uid").takeIf(String::isNotBlank) ?: return@mapNotNull null
            KnownAccount(
                uid = uid,
                email = item.optString("email").takeIf(String::isNotBlank),
                name = item.optString("name", "Listener").ifBlank { "Listener" },
                photoUrl = item.optString("photoUrl").takeIf(String::isNotBlank),
                hasPassword = item.optBoolean("hasPassword", false),
                lastUsedAt = item.optLong("lastUsedAt", 0L),
                // Recomputed after Firebase has completed its initial restoration for every app.
                hasCachedSession = false
            )
        }.sortedByDescending { it.lastUsedAt }
    }.getOrDefault(emptyList())

    private fun persistKnownAccounts(accounts: List<KnownAccount>) {
        val json = JSONArray()
        accounts.forEach { account ->
            json.put(
                JSONObject()
                    .put("uid", account.uid)
                    .put("email", account.email ?: "")
                    .put("name", account.name)
                    .put("photoUrl", account.photoUrl ?: "")
                    .put("hasPassword", account.hasPassword)
                    .put("lastUsedAt", account.lastUsedAt)
            )
        }
        accountPrefs.edit().putString(KEY_KNOWN_ACCOUNTS, json.toString()).apply()
    }

    private fun rememberAccount(user: AccountUser, hasCachedSession: Boolean = true) {
        val now = System.currentTimeMillis()
        val updated = (listOf(
            KnownAccount(user.uid, user.email, user.name, user.photoUrl, user.hasPassword, now, hasCachedSession)
        ) + _knownAccounts.value.filterNot { it.uid == user.uid })
            .take(MAX_KNOWN_ACCOUNTS)
        _knownAccounts.value = updated
        persistKnownAccounts(updated)
    }

    /** Returns the Firebase app name for a user without exposing the UID in app/component names. */
    private fun accountAppName(uid: String): String = "GeekifyAccount_${sha256Hex(uid).take(24)}"

    /**
     * Creates or retrieves a stable named FirebaseApp for [uid]. FirebaseAuth persists a separate
     * signed-in session for every FirebaseApp, so switching accounts does not require storing a
     * password or asking the identity provider to authenticate again.
     */
    private fun accountApp(uid: String): FirebaseApp = synchronized(sessionAppLock) {
        val name = accountAppName(uid)
        FirebaseApp.getApps(context).firstOrNull { it.name == name }
            ?: FirebaseApp.initializeApp(context, defaultApp.options, name)
            ?: FirebaseApp.getInstance(name)
    }

    private fun cachedAuthForUid(uid: String): Pair<FirebaseApp, FirebaseAuth>? {
        if (defaultAuth.currentUser?.uid == uid) return defaultApp to defaultAuth
        val app = runCatching { accountApp(uid) }.getOrNull() ?: return null
        val accountAuth = FirebaseAuth.getInstance(app)
        return if (accountAuth.currentUser?.uid == uid) app to accountAuth else null
    }

    /**
     * Checks Firebase's persisted session after its initial auth callback. This intentionally does
     * not trust the UI's cached boolean alone: a named FirebaseApp may still be restoring after
     * process recreation, and that must never trigger an unnecessary password/Google prompt.
     */
    suspend fun hasCachedSession(uid: String): Boolean {
        awaitSessionReady()
        val targetAuth = if (defaultAuth.currentUser?.uid == uid) {
            defaultAuth
        } else {
            FirebaseAuth.getInstance(accountApp(uid))
        }
        // A timeout is not evidence that the account needs credentials again. Surface it as a
        // recoverable session-restore error instead of opening the password/Google flow.
        awaitRestoredAccountAuth(targetAuth)
        val available = targetAuth.currentUser?.uid == uid
        val updated = _knownAccounts.value.map { account ->
            if (account.uid == uid) account.copy(hasCachedSession = available) else account
        }
        _knownAccounts.value = updated
        persistKnownAccounts(updated)
        return available
    }

    /**
     * Refreshes a saved account's Firebase token before a handoff. This distinguishes a genuinely
     * reusable session from a stale local FirebaseUser left behind after revocation or deletion.
     * Network failures are propagated so the active account is not signed out merely because the
     * device cannot validate another session at that moment.
     */
    suspend fun refreshCachedAccount(uid: String): AccountUser? {
        awaitSessionReady()
        val targetAuth = if (defaultAuth.currentUser?.uid == uid) {
            defaultAuth
        } else {
            FirebaseAuth.getInstance(accountApp(uid))
        }
        awaitRestoredAccountAuth(targetAuth)
        val beforeRefresh = targetAuth.currentUser?.takeIf { it.uid == uid } ?: return null
        if (beforeRefresh.uid != uid) return null

        try {
            // Let Firebase reuse a still-valid token and refresh it automatically only when needed.
            // Forcing a refresh and then calling reload() added two network round trips to every
            // account switch, making a healthy persisted session look like a re-authentication
            // problem on slow/offline connections.
            val tokenResult = beforeRefresh.getIdToken(false).await()
            val refreshed = targetAuth.currentUser
                ?.takeIf { it.uid == uid }
                ?: run {
                    markCachedSessionUnavailable(uid)
                    return null
                }

            val isPasswordAccount = refreshed.providerData.any { it.providerId == "password" }
            val emailVerified = tokenResult.claims["email_verified"] as? Boolean
                ?: refreshed.isEmailVerified
            if (isPasswordAccount && !emailVerified) {
                targetAuth.signOut()
                markCachedSessionUnavailable(uid)
                return null
            }

            return mapAccountUser(refreshed).also { rememberAccount(it, hasCachedSession = true) }
        } catch (error: com.google.firebase.auth.FirebaseAuthException) {
            val invalidSessionCodes = setOf(
                "ERROR_USER_NOT_FOUND",
                "ERROR_USER_DISABLED",
                "ERROR_INVALID_USER_TOKEN",
                "ERROR_USER_TOKEN_EXPIRED"
            )
            if (error.errorCode in invalidSessionCodes) {
                runCatching { targetAuth.signOut() }
                markCachedSessionUnavailable(uid)
                return null
            }
            throw error
        }
    }

    private fun markCachedSessionUnavailable(uid: String) {
        val updated = _knownAccounts.value.map { account ->
            if (account.uid == uid) account.copy(hasCachedSession = false) else account
        }
        _knownAccounts.value = updated
        persistKnownAccounts(updated)
    }

    /** The app backing the currently active FirebaseAuth; SyncRepository uses the same app for Firestore. */
    fun activeFirebaseApp(): FirebaseApp = activeApp

    /** Switches the active identity to an already-authenticated Firebase session without prompting. */
    fun activateCachedAccount(uid: String): Boolean {
        val cached = cachedAuthForUid(uid) ?: return false
        bindActiveSession(cached.first, cached.second)
        return auth.currentUser?.uid == uid
    }

    /**
     * Authenticates [credential] in the stable per-account FirebaseApp without disturbing the
     * currently active Firebase session. Call activateCachedAccount only after the old account's
     * local data has been safely backed up and cleared.
     */
    suspend fun persistCredentialSession(credential: AuthCredential, expectedUid: String): AccountUser {
        // Prefer an existing FirebaseApp session for this UID (including the legacy default app).
        // Otherwise create the stable per-account app. Signing in with the freshly validated
        // credential refreshes the SDK-managed session without persisting passwords or ID tokens.
        val existing = cachedAuthForUid(expectedUid)
        val targetApp = existing?.first ?: accountApp(expectedUid)
        val targetAuth = existing?.second ?: FirebaseAuth.getInstance(targetApp)
        val signedUser = targetAuth.signInWithCredential(credential).await().user
            ?: throw IllegalStateException("Firebase didn't return an account.")

        if (signedUser.uid != expectedUid) {
            targetAuth.signOut()
            throw IllegalStateException("Those credentials belong to a different account. Please try again.")
        }
        signedUser.reload().await()
        val passwordAccount = signedUser.providerData.any { it.providerId == "password" }
        if (passwordAccount && !signedUser.isEmailVerified) {
            runCatching { signedUser.sendEmailVerification().await() }
            targetAuth.signOut()
            markCachedSessionUnavailable(expectedUid)
            throw EmailVerificationRequiredException(
                "Please verify ${signedUser.email ?: "your email address"} before switching to this account."
            )
        }
        val mapped = mapAccountUser(signedUser)
        rememberAccount(mapped, hasCachedSession = true)
        return mapped
    }

    private fun bindActiveSession(app: FirebaseApp, targetAuth: FirebaseAuth, publish: Boolean = true) {
        if (auth !== targetAuth) {
            auth.removeAuthStateListener(activeAuthListener)
            activeApp = app
            auth = targetAuth
            auth.addAuthStateListener(activeAuthListener)
        } else {
            activeApp = app
        }
        targetAuth.currentUser?.uid?.let { uid ->
            accountPrefs.edit().putString(KEY_ACTIVE_ACCOUNT_UID, uid).apply()
        }
        if (publish && _sessionReady.value) publishUser()
    }

    /** Removes a saved account from the switcher and revokes its on-device Firebase session. */
    fun forgetKnownAccount(uid: String) {
        val updated = _knownAccounts.value.filterNot { it.uid == uid }
        _knownAccounts.value = updated
        persistKnownAccounts(updated)
        if (defaultAuth.currentUser?.uid == uid && auth !== defaultAuth) defaultAuth.signOut()
        val app = runCatching { accountApp(uid) }.getOrNull()
        if (app != null && app.name != defaultApp.name) {
            runCatching { FirebaseAuth.getInstance(app).signOut() }
            runCatching { app.delete() }
        }
    }

    private fun validationAuth(): Pair<FirebaseApp, FirebaseAuth> {
        val name = "GeekifyValidation-${validationAppCounter.incrementAndGet()}"
        val app = FirebaseApp.initializeApp(context, FirebaseApp.getInstance().options, name)
            ?: throw IllegalStateException("Couldn't prepare a secure account switch. Please try again.")
        return app to FirebaseAuth.getInstance(app)
    }

    /** Creates an email credential without changing the currently active Firebase session. */
    fun emailCredential(email: String, password: String): AuthCredential =
        EmailAuthProvider.getCredential(email.trim(), password)

    /**
     * Validates an account in a short-lived secondary Firebase app. This lets Geekify confirm the
     * next identity before backing up and clearing the current account's local library.
     */
    suspend fun validateCredentialWithoutChangingCurrent(credential: AuthCredential): AccountUser {
        val (validationApp, validationAuth) = validationAuth()
        try {
            val signedUser = validationAuth.signInWithCredential(credential).await().user
                ?: throw IllegalStateException("Firebase didn't return an account.")
            signedUser.reload().await()
            val passwordAccount = signedUser.providerData.any { it.providerId == "password" }
            if (passwordAccount && !signedUser.isEmailVerified) {
                runCatching { signedUser.sendEmailVerification().await() }
                throw EmailVerificationRequiredException(
                    "Please verify ${signedUser.email ?: "your email address"} before switching to this account."
                )
            }
            return mapAccountUser(signedUser)
        } finally {
            runCatching { validationAuth.signOut() }
            runCatching { validationApp.delete() }
        }
    }

    /** Adds an email account from the current session without replacing the currently signed-in account. */
    suspend fun createEmailAccountForAddition(name: String, email: String, password: String) {
        val (validationApp, validationAuth) = validationAuth()
        try {
            val created = validationAuth.createUserWithEmailAndPassword(email.trim(), password).await().user
                ?: throw IllegalStateException("Firebase didn't create the account.")
            created.updateProfile(com.google.firebase.auth.userProfileChangeRequest { displayName = name.trim() }).await()
            runCatching { created.sendEmailVerification().await() }
        } finally {
            runCatching { validationAuth.signOut() }
            runCatching { validationApp.delete() }
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

    suspend fun prepareGoogleCredential(activityContext: Context): AuthCredential =
        fetchGoogleCredential(activityContext)

    suspend fun signInWithCredential(credential: AuthCredential) {
        val signedUser = auth.signInWithCredential(credential).await().user
            ?: throw IllegalStateException("Firebase didn't return an account.")
        signedUser.reload().await()
        val passwordAccount = signedUser.providerData.any { it.providerId == "password" }
        if (passwordAccount && !signedUser.isEmailVerified) {
            runCatching { signedUser.sendEmailVerification().await() }
            auth.signOut()
            throw EmailVerificationRequiredException(
                "Please verify ${signedUser.email ?: "your email address"} before signing in."
            )
        }
        publishUser()
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

    suspend fun signOut() {
        preservePreferredUidWhenEmpty = false
        auth.signOut()
    }

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
