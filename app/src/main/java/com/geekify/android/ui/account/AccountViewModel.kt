package com.geekify.android.ui.account

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geekify.android.data.auth.AccountUser
import com.geekify.android.data.auth.AuthRepository
import com.geekify.android.data.auth.KnownAccount
import com.geekify.android.data.auth.SyncState
import com.geekify.android.data.sync.SyncRepository
import com.geekify.android.player.PlaybackService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.sync.Mutex

data class AccountUiState(
    val user: AccountUser? = null,
    val knownAccounts: List<KnownAccount> = emptyList(),
    val syncState: SyncState = SyncState.IDLE,
    val syncError: String? = null,
    val isLoading: Boolean = false,
    val isRestoringAccounts: Boolean = true,
    val error: String? = null,
    val message: String? = null
)

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val sync: SyncRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()
    private val accountOperationMutex = Mutex()

    /** Serializes sign-in, switching and sign-out so rapid taps cannot interleave two handoffs. */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun launchAccountOperation(block: suspend () -> Unit) {
        if (!accountOperationMutex.tryLock()) return
        // ATOMIC start guarantees the coroutine enters its try/finally even if the ViewModel is
        // cancelled before dispatch. This preserves single-flight tap handling without leaking the lock.
        viewModelScope.launch(start = CoroutineStart.ATOMIC) {
            try {
                auth.awaitSessionReady()
                block()
            } finally {
                accountOperationMutex.unlock()
            }
        }
    }

    init {
        _uiState.update { it.copy(isRestoringAccounts = !auth.sessionReady.value) }
        viewModelScope.launch {
            auth.awaitSessionReady()
            _uiState.update {
                it.copy(
                    isRestoringAccounts = false,
                    user = auth.user.value,
                    knownAccounts = auth.knownAccounts.value
                )
            }
        }

        auth.user.onEach { u ->
            _uiState.update { it.copy(user = u) }
            if (u == null) {
                sync.stopSync()
            }
        }.launchIn(viewModelScope)

        auth.knownAccounts.onEach { accounts ->
            _uiState.update { it.copy(knownAccounts = accounts) }
        }.launchIn(viewModelScope)

        // Start syncing only when the signed-in account changes, not on every name / photo edit.
        auth.user.map { it?.uid }.distinctUntilChanged().onEach { uid ->
            if (uid != null) {
                sync.startSync(uid)
            } else {
                sync.stopSync()
            }
        }.launchIn(viewModelScope)

        sync.syncState.onEach { s ->
            _uiState.update { it.copy(syncState = s) }
        }.launchIn(viewModelScope)

        sync.syncError.onEach { message ->
            _uiState.update { it.copy(syncError = message) }
        }.launchIn(viewModelScope)
    }

    fun retrySync() {
        if (_uiState.value.syncState == SyncState.SYNCING) return
        val uid = auth.currentUid() ?: auth.user.value?.uid ?: return
        _uiState.update { it.copy(error = null, message = null) }
        viewModelScope.launch {
            sync.startSync(uid, force = true)
        }
    }

    /** Switches to a saved FirebaseAuth session without asking for credentials again. */
    fun switchToKnownAccount(
        uid: String,
        onNeedsAuthentication: (KnownAccount) -> Unit = {}
    ) {
        if (_uiState.value.isLoading || auth.currentUid() == uid) return
        launchAccountOperation {
            _uiState.update { it.copy(isLoading = true, error = null, message = null) }
            val activeUid = auth.currentUid()
            var handoffPrepared = false
            var playbackStopped = false
            try {
                val savedAccount = auth.knownAccounts.value.firstOrNull { it.uid == uid }
                    ?: throw IllegalStateException("This account is no longer saved on this device.")
                if (!auth.hasCachedSession(uid)) {
                    _uiState.update { it.copy(isLoading = false, message = null) }
                    onNeedsAuthentication(savedAccount.copy(hasCachedSession = false))
                    return@launchAccountOperation
                }

                // Validate the restored SDK session before muting playback. If the token has expired,
                // the existing account stays active while the UI asks for one-time re-authentication.
                val destination = auth.refreshCachedAccount(uid)
                if (destination == null) {
                    _uiState.update { it.copy(isLoading = false, message = null) }
                    onNeedsAuthentication(savedAccount.copy(hasCachedSession = false))
                    return@launchAccountOperation
                }
                if (activeUid == null) sync.ensureLocalAccountOwnership(uid)

                // Quiesce playback only after the destination session has been confirmed as reusable.
                // Set the rollback marker first: service checkpoint flushing may itself fail.
                playbackStopped = true
                PlaybackService.stopForAccountTransition()

                if (activeUid != null) {
                    sync.prepareForAccountSwitch()
                    handoffPrepared = true
                }
                if (!auth.activateCachedAccount(uid)) {
                    throw IllegalStateException("The saved account couldn't be activated. Your previous account remains selected.")
                }

                // Also clear stale detached playback state when no Firebase user was active.
                sync.completeAccountSwitch(uid)
                playbackStopped = false
                _uiState.update { it.copy(isLoading = false, message = "Switched to ${destination.name}.") }
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    if (handoffPrepared) runCatching { sync.abortAccountSwitch(activeUid) }
                    else if (playbackStopped && activeUid == null) runCatching { sync.discardDetachedPlayback() }
                    else if (playbackStopped) runCatching { PlaybackService.abortAccountTransition() }
                }
                throw e
            } catch (e: Exception) {
                if (handoffPrepared) {
                    runCatching { sync.abortAccountSwitch(activeUid) }
                } else if (playbackStopped && activeUid == null) {
                    runCatching { sync.discardDetachedPlayback() }
                } else if (playbackStopped) {
                    runCatching { PlaybackService.abortAccountTransition() }
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Couldn't switch accounts. Your current account is still active."
                    )
                }
            }
        }
    }

    fun signInEmail(email: String, pass: String, expectedUid: String? = null) {
        launchAccountOperation {
            _uiState.update { it.copy(isLoading = true, error = null, message = null) }
            val activeUid = auth.currentUid()
            var handoffPrepared = false
            var playbackStopped = false
            try {
                // First resolve/validate the destination without changing the active identity.
                val destination = expectedUid?.let { auth.refreshCachedAccount(it) }
                val target = destination ?: run {
                    val credential = auth.emailCredential(email, pass)
                    val validated = auth.validateCredentialWithoutChangingCurrent(credential)
                    if (expectedUid != null && validated.uid != expectedUid) {
                        throw IllegalStateException("Those credentials belong to a different account. Please try again.")
                    }
                    auth.persistCredentialSession(credential, validated.uid)
                }

                if (activeUid != target.uid) {
                    // Record rollback responsibility before the service flushes old-account state.
                    playbackStopped = true
                    PlaybackService.stopForAccountTransition()
                    if (activeUid != null) {
                        sync.prepareForAccountSwitch()
                        handoffPrepared = true
                    } else {
                        sync.ensureLocalAccountOwnership(target.uid)
                    }
                    if (!auth.activateCachedAccount(target.uid)) {
                        throw IllegalStateException("The account session couldn't be activated. Your previous account remains saved.")
                    }
                    sync.completeAccountSwitch(target.uid)
                    playbackStopped = false
                } else {
                    // Re-authenticating the already active identity must not interrupt its playback.
                    sync.startSync(target.uid)
                }
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    if (handoffPrepared) runCatching { sync.abortAccountSwitch(activeUid) }
                    else if (playbackStopped && activeUid == null) runCatching { sync.discardDetachedPlayback() }
                    else if (playbackStopped) runCatching { PlaybackService.abortAccountTransition() }
                }
                throw e
            } catch (e: Exception) {
                if (handoffPrepared) {
                    runCatching { sync.abortAccountSwitch(activeUid) }
                } else if (playbackStopped && activeUid == null) {
                    runCatching { sync.discardDetachedPlayback() }
                } else if (playbackStopped) {
                    runCatching { PlaybackService.abortAccountTransition() }
                }
                _uiState.update { it.copy(isLoading = false, error = auth.friendlyError(e)) }
            }
        }
    }

    fun signUpEmail(name: String, email: String, pass: String) {
        launchAccountOperation {
            _uiState.update { it.copy(isLoading = true, error = null, message = null) }
            try {
                if (auth.currentUid() != null) {
                    auth.createEmailAccountForAddition(name, email, pass)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            message = "Verification email sent to ${email.trim()}. Your current account is still active. Verify the address, then add the account."
                        )
                    }
                } else {
                    auth.signUpEmail(name, email, pass)
                    _uiState.update { it.copy(isLoading = false) }
                }
            } catch (e: AuthRepository.EmailVerificationRequiredException) {
                _uiState.update { it.copy(isLoading = false, message = auth.friendlyError(e)) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = auth.friendlyError(e)) }
            }
        }
    }

    fun signInGoogle(activityContext: android.content.Context, expectedUid: String? = null) {
        launchAccountOperation {
            _uiState.update { it.copy(isLoading = true, error = null, message = null) }
            val activeUid = auth.currentUid()
            var handoffPrepared = false
            var playbackStopped = false
            try {
                // Reuse an already-restored Firebase session before opening Credential Manager.
                val destination = expectedUid?.let { auth.refreshCachedAccount(it) }
                val target = destination ?: run {
                    val credential = auth.prepareGoogleCredential(activityContext)
                    val validated = auth.validateCredentialWithoutChangingCurrent(credential)
                    if (expectedUid != null && validated.uid != expectedUid) {
                        throw IllegalStateException("Choose the Google account you previously added to Geekify.")
                    }
                    auth.persistCredentialSession(credential, validated.uid)
                }

                if (activeUid != target.uid) {
                    // Record rollback responsibility before the service flushes old-account state.
                    playbackStopped = true
                    PlaybackService.stopForAccountTransition()
                    if (activeUid != null) {
                        sync.prepareForAccountSwitch()
                        handoffPrepared = true
                    } else {
                        sync.ensureLocalAccountOwnership(target.uid)
                    }
                    if (!auth.activateCachedAccount(target.uid)) {
                        throw IllegalStateException("The Google account session couldn't be activated. Your previous account remains saved.")
                    }
                    sync.completeAccountSwitch(target.uid)
                    playbackStopped = false
                } else {
                    sync.startSync(target.uid)
                }
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: AuthRepository.GoogleSignInCancelledException) {
                if (handoffPrepared) runCatching { sync.abortAccountSwitch(activeUid) }
                else if (playbackStopped && activeUid == null) runCatching { sync.discardDetachedPlayback() }
                else if (playbackStopped) runCatching { PlaybackService.abortAccountTransition() }
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    if (handoffPrepared) runCatching { sync.abortAccountSwitch(activeUid) }
                    else if (playbackStopped && activeUid == null) runCatching { sync.discardDetachedPlayback() }
                    else if (playbackStopped) runCatching { PlaybackService.abortAccountTransition() }
                }
                throw e
            } catch (e: Exception) {
                if (handoffPrepared) runCatching { sync.abortAccountSwitch(activeUid) }
                else if (playbackStopped && activeUid == null) runCatching { sync.discardDetachedPlayback() }
                else if (playbackStopped) runCatching { PlaybackService.abortAccountTransition() }
                _uiState.update { it.copy(isLoading = false, error = auth.friendlyError(e)) }
            }
        }
    }

    fun resetPassword(email: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                auth.resetPassword(email)
                _uiState.update { it.copy(isLoading = false, message = "Password reset email sent.") }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = auth.friendlyError(e)) }
            }
        }
    }

    fun clearFeedback() {
        _uiState.update { it.copy(error = null, message = null) }
    }

    /** Forget an account from this device's switcher without deleting its Firebase account or cloud data. */
    fun forgetAccount(uid: String) {
        if (auth.currentUid() == uid) {
            _uiState.update { it.copy(error = "Sign out before removing the active account from this device.") }
            return
        }
        auth.forgetKnownAccount(uid)
    }

    fun updateName(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) {
            _uiState.update { it.copy(error = "Name can't be empty.", message = null) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, message = null) }
            try {
                auth.updateDisplayName(clean.take(40))
                _uiState.update { it.copy(isLoading = false, message = "Name updated.") }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = auth.friendlyError(e)) }
            }
        }
    }

    fun setAvatar(context: Context, uri: Uri) {
        val appContext = context.applicationContext
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, message = null) }
            try {
                val jpeg = withContext(Dispatchers.IO) { AvatarImage.compress(appContext, uri) }
                auth.setLocalAvatar(jpeg)
                _uiState.update { it.copy(isLoading = false, message = "Profile picture updated.") }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "Couldn't use that picture. Try a different one.") }
            }
        }
    }

    fun removeAvatar() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, message = null) }
            try {
                auth.removeLocalAvatar()
                _uiState.update { it.copy(isLoading = false, message = "Profile picture removed.") }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = auth.friendlyError(e)) }
            }
        }
    }

    /**
     * Confirms identity, wipes the cloud copy of the library, deletes the account, then clears this phone.
     * [password] is needed for email accounts; Google accounts are confirmed through Google's account sheet.
     */
    fun deleteAccount(password: String?, activityContext: Context, onDeleted: () -> Unit = {}) {
        launchAccountOperation {
            _uiState.update { it.copy(isLoading = true, error = null, message = null) }
            val uid = auth.user.value?.uid
            try {
                auth.reauthenticate(password, activityContext)
                if (uid != null) {
                    sync.deleteCloudData(uid)
                }
                auth.deleteAccount()
                // The cloud copy was already wiped before deleting the Firebase identity.
                // Do not attempt a normal sign-out backup against a deleted account.
                sync.clearAfterAccountDeletion()
                _uiState.update { it.copy(isLoading = false) }
                onDeleted()
            } catch (e: AuthRepository.GoogleSignInCancelledException) {
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                if (uid != null) runCatching { sync.resumeSync(uid) }
                _uiState.update { it.copy(isLoading = false, error = auth.friendlyError(e)) }
            }
        }
    }

    /**
     * Signs out of the currently selected Geekify account by automatically activating the most
     * recently used saved account that still has a Firebase SDK session on this device. If no
     * saved session is available, it performs a full sign-out and leaves the other identities in
     * the account list for a one-time re-authentication.
     */
    fun signOut(onSignedOut: () -> Unit = {}) {
        if (_uiState.value.isLoading) return
        launchAccountOperation {
            _uiState.update { it.copy(isLoading = true, error = null, message = null) }
            val currentUid = auth.currentUid()
            var handoffPrepared = false
            var playbackStopped = false
            try {
                // Record rollback responsibility before the service flushes old-account state.
                playbackStopped = true
                PlaybackService.stopForAccountTransition()
                // Validate candidates in most-recently-used order. Invalid/revoked sessions are
                // skipped; network errors abort the operation without signing out the active user.
                val candidates = auth.knownAccounts.value
                    .filterNot { it.uid == currentUid }
                    .sortedByDescending { it.lastUsedAt }
                var nextAccount: KnownAccount? = null
                for (candidate in candidates) {
                    if (!auth.hasCachedSession(candidate.uid)) continue
                    val refreshed = auth.refreshCachedAccount(candidate.uid)
                    if (refreshed != null) {
                        nextAccount = candidate.copy(name = refreshed.name, email = refreshed.email)
                        break
                    }
                }

                if (nextAccount != null) {
                    if (currentUid != null) {
                        sync.prepareForAccountSwitch()
                        handoffPrepared = true
                    } else {
                        sync.ensureLocalAccountOwnership(nextAccount.uid)
                    }
                    if (!auth.activateCachedAccount(nextAccount.uid)) {
                        throw IllegalStateException("The saved account couldn't be activated. Your current account remains selected.")
                    }
                    sync.completeAccountSwitch(nextAccount.uid)
                    playbackStopped = false
                    _uiState.update {
                        it.copy(isLoading = false, message = "Switched to ${nextAccount.name}.")
                    }
                } else {
                    sync.signOutAndClear()
                    val otherSavedAccounts = auth.knownAccounts.value.any { it.uid != currentUid }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            message = if (otherSavedAccounts) {
                                "Signed out. Your other saved accounts need a one-time sign-in before quick switching is available."
                            } else null
                        )
                    }
                }
                onSignedOut()
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    if (handoffPrepared) runCatching { sync.abortAccountSwitch(currentUid) }
                    else if (playbackStopped && currentUid == null) runCatching { sync.discardDetachedPlayback() }
                    else if (playbackStopped) runCatching { PlaybackService.abortAccountTransition() }
                }
                throw e
            } catch (e: Exception) {
                if (handoffPrepared) runCatching { sync.abortAccountSwitch(currentUid) }
                else if (playbackStopped && currentUid == null) runCatching { sync.discardDetachedPlayback() }
                else if (playbackStopped) runCatching { PlaybackService.abortAccountTransition() }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Couldn't safely sign out. Your account and local data have been kept."
                    )
                }
            }
        }
    }

}
