package com.geekify.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface NotificationEntryPoint {
    fun store(): NotificationStore
    fun coordinator(): NotificationCoordinator
    fun notifier(): GeekifyNotifier
}

/** Handles "Later" and swipe-away on the update notification: remember the dismissed version, never nag for it again. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != GeekifyNotifier.ACTION_DISMISS_UPDATE) return
        val code = intent.getIntExtra(GeekifyNotifier.EXTRA_VERSION_CODE, 0)
        if (code <= 0) return
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, NotificationEntryPoint::class.java)
        entry.notifier().cancelUpdate()
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                entry.store().updateState { it.copy(dismissedVersion = maxOf(it.dismissedVersion, code)) }
            } finally {
                pending.finish()
            }
        }
    }
}
