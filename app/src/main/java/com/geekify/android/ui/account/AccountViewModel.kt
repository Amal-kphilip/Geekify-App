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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AccountUiState(
    val user: AccountUser? = null,
    val knownAccounts: List<KnownAccount> = emptyList(),
    val syncState: SyncState = SyncState.IDLE,
    val syncError: String? = null,
    val isLoading: Boolean = false,
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

    init {
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
            sync.startSync(uid)
        }
    }

    fun signInEmail(email: String, pass: String, expectedUid: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val activeUid = auth.currentUid()
                if (activeUid == null && expectedUid == null) {
                    auth.signInEmail(email, pass)
                } else {
                    // Validate the target identity before backing up and clearing the active
                    // account. A mistyped password must never sign the current user out.
                    val credential = auth.emailCredential(email, pass)
                    val target = auth.validateCredentialWithoutChangingCurrent(credential)
                    if (expectedUid != null && target.uid != expectedUid) {
                        throw IllegalStateException("Those credentials belong to a different account. Please try again.")
                    }
                    if (activeUid != null && activeUid != target.uid) sync.signOutAndClear()
                    auth.signInWithCredential(credential)
                }
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = auth.friendlyError(e)) }
            }
        }
    }

    fun signUpEmail(name: String, email: String, pass: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, message = null) }
            try {
                if (auth.currentUid() != null) {
                    // Create and verify the additional identity in a temporary Firebase app so
                    // the currently active user's session and local library remain untouched.
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
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, message = null) }
            try {
                val activeUid = auth.currentUid()
                if (activeUid == null && expectedUid == null) {
                    auth.signInGoogle(activityContext)
                } else {
                    val credential = auth.prepareGoogleCredential(activityContext)
                    val target = auth.validateCredentialWithoutChangingCurrent(credential)
                    if (expectedUid != null && target.uid != expectedUid) {
                        throw IllegalStateException("Choose the Google account you previously added to Geekify.")
                    }
                    if (activeUid != null && activeUid != target.uid) sync.signOutAndClear()
                    auth.signInWithCredential(credential)
                }
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: AuthRepository.GoogleSignInCancelledException) {
                // The person closed the account picker: nothing to report.
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
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
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, message = null) }
            val uid = auth.user.value?.uid
            var cloudWiped = false
            try {
                auth.reauthenticate(password, activityContext)
                if (uid != null) {
                    cloudWiped = runCatching { sync.deleteCloudData(uid) }.isSuccess
                    if (!cloudWiped) {
                        throw IllegalStateException(
                            "Geekify couldn't remove your cloud data. Your account has not been deleted. Check your connection and try again."
                        )
                    }
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

    fun signOut(onSignedOut: () -> Unit = {}) {
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, message = null) }
            try {
                sync.signOutAndClear()
                _uiState.update { it.copy(isLoading = false) }
                onSignedOut()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Couldn't safely sign out. Your data has been kept on this device."
                    )
                }
            }
        }
    }
}
