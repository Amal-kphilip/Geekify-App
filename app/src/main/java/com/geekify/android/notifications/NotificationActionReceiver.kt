package com.geekify.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Handles:
 * 1. "Later" and swipe-away on the update notification: remember the dismissed version, never nag for it again.
 * 2. ACTION_MY_PACKAGE_REPLACED: immediately clear any active update notification as soon as the APK update installs.
 */
class NotificationActionReceiver : BroadcastReceiver(), KoinComponent {
    private val store: NotificationStore by inject()
    private val coordinator: NotificationCoordinator by inject()
    private val notifier: GeekifyNotifier by inject()

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                notifier.cancelUpdate()
                NotificationWorker.schedule(context.applicationContext)
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        coordinator.reconcile()
                    } finally {
                        pending.finish()
                    }
                }
            }
            GeekifyNotifier.ACTION_DISMISS_UPDATE -> {
                val code = intent.getIntExtra(GeekifyNotifier.EXTRA_VERSION_CODE, 0)
                if (code <= 0) return
                notifier.cancelUpdate()
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        store.updateState {
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
