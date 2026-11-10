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

/**
 * Handles:
 * 1. "Later" and swipe-away on the update notification: remember the dismissed version, never nag for it again.
 * 2. ACTION_MY_PACKAGE_REPLACED: immediately clear any active update notification as soon as the APK update installs.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, NotificationEntryPoint::class.java)
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                entry.notifier().cancelUpdate()
                NotificationWorker.schedule(context.applicationContext)
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        entry.coordinator().reconcile()
                    } finally {
                        pending.finish()
                    }
                }
            }
            GeekifyNotifier.ACTION_DISMISS_UPDATE -> {
                val code = intent.getIntExtra(GeekifyNotifier.EXTRA_VERSION_CODE, 0)
                if (code <= 0) return
                entry.notifier().cancelUpdate()
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        entry.store().updateState {
                            it.copy(
                                updateShownForVersion = maxOf(it.updateShownForVersion, code),
                                dismissedVersion = maxOf(it.dismissedVersion, code)
                            )
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
