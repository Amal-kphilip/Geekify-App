package com.geekify.android.ui.account

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geekify.android.data.auth.AccountUser
import com.geekify.android.data.auth.AuthRepository
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
    val syncState: SyncState = SyncState.IDLE,
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
        auth.user.onEach { u -> _uiState.update { it.copy(user = u) } }.launchIn(viewModelScope)

        // Start syncing only when the signed-in account changes, not on every name / photo edit.
        auth.user.map { it?.uid }.distinctUntilChanged().onEach { uid ->
            if (uid != null) sync.startSync(uid)
        }.launchIn(viewModelScope)

        sync.syncState.onEach { s ->
            _uiState.update { it.copy(syncState = s) }
        }.launchIn(viewModelScope)
    }

    fun signInEmail(email: String, pass: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                auth.signInEmail(email, pass)
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = auth.friendlyError(e)) }
            }
        }
    }

    fun signUpEmail(name: String, email: String, pass: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                auth.signUpEmail(name, email, pass)
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = auth.friendlyError(e)) }
            }
        }
    }

    fun signInGoogle(activityContext: android.content.Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, message = null) }
            try {
                auth.signInGoogle(activityContext)
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
                    // Best effort: the account is still removed if the cloud copy can't be reached.
                    cloudWiped = runCatching { sync.deleteCloudData(uid) }.isSuccess
                }
                auth.deleteAccount()
                sync.signOutAndClear()
                _uiState.update { it.copy(isLoading = false) }
                onDeleted()
            } catch (e: AuthRepository.GoogleSignInCancelledException) {
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                if (uid != null && cloudWiped) runCatching { sync.resumeSync(uid) }
                _uiState.update { it.copy(isLoading = false, error = auth.friendlyError(e)) }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            sync.signOutAndClear()
        }
    }
}
