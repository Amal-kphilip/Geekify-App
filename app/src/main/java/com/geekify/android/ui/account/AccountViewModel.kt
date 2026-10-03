package com.geekify.android.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geekify.android.data.auth.AccountUser
import com.geekify.android.data.auth.AuthRepository
import com.geekify.android.data.auth.SyncState
import com.geekify.android.data.sync.SyncRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
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
        auth.user.onEach { u ->
            _uiState.update { it.copy(user = u) }
            if (u != null) {
                sync.startSync(u.uid)
            }
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

    fun signOut() {
        viewModelScope.launch {
            sync.signOutAndClear()
        }
    }
}
