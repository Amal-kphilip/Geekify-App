package com.geekify.android.notifications

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
class NotificationSettingsViewModel constructor(
    private val store: NotificationStore,
    private val context: Context
) : ViewModel() {
    val prefs: StateFlow<NotificationPrefs> =
        store.prefs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationPrefs())

    fun setUpdates(on: Boolean) { viewModelScope.launch { store.setUpdates(on); UpdatePushSubscription.sync(context, on) } }
    fun setTips(on: Boolean) { viewModelScope.launch { store.setTips(on) } }
    fun setEngagement(on: Boolean) { viewModelScope.launch { store.setEngagement(on) } }
}
