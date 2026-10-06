package com.geekify.android.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotificationSettingsViewModel @Inject constructor(private val store: NotificationStore) : ViewModel() {
    val prefs: StateFlow<NotificationPrefs> =
        store.prefs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationPrefs())

    fun setUpdates(on: Boolean) { viewModelScope.launch { store.setUpdates(on) } }
    fun setTips(on: Boolean) { viewModelScope.launch { store.setTips(on) } }
    fun setEngagement(on: Boolean) { viewModelScope.launch { store.setEngagement(on) } }
}
