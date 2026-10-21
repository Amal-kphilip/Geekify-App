package com.geekify.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.geekify.android.data.sync.SyncRepository
import com.geekify.android.notifications.AppForeground
import com.geekify.android.notifications.GeekifyNotifier
import com.geekify.android.notifications.NotificationCoordinator
import com.geekify.android.notifications.NotificationStore
import com.geekify.android.notifications.NotificationWorker
import com.geekify.android.notifications.UpdatePushSubscription
import com.geekify.android.player.PlaybackService
import com.geekify.android.ui.navigation.MainScreen
import com.geekify.android.ui.theme.GeekifyTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var syncRepository: SyncRepository
    @Inject lateinit var notificationStore: NotificationStore
    @Inject lateinit var notificationCoordinator: NotificationCoordinator
    @Inject lateinit var appSettings: com.geekify.android.settings.AppSettingsRepository

    /** Screen requested by a notification tap (e.g. "recents"); consumed once by MainScreen. */
    private var requestedRoute by mutableStateOf<String?>(null)

    /** A release notification asks MainScreen to re-check GitHub and open the update sheet. */
    private var triggerUpdateCheck by mutableStateOf(false)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            lifecycleScope.launch {
                UpdatePushSubscription.sync(this@MainActivity, notificationStore.prefsNow().updates)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            appSettings.state.collect { state ->
                if (state.disableScreenshots) {
                    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
        requestedRoute = intent?.getStringExtra(GeekifyNotifier.EXTRA_ROUTE)
        triggerUpdateCheck = intent?.getBooleanExtra(GeekifyNotifier.EXTRA_TRIGGER_UPDATE_CHECK, false) == true

        // Android 13+: without this the playback notification (and lock-screen controls) stay hidden.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Start PlaybackService
        val serviceIntent = Intent(this, PlaybackService::class.java)
        // MediaSessionService promotes itself to a foreground media service when playback starts.
        // Starting it normally here avoids Android's five-second foreground-start deadline while
        // the user is still browsing before choosing a song.
        startService(serviceIntent)

        // Observe foregrounding for Firestore pull sync
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                syncRepository.onForeground()
            }
        }

        setContent {
            GeekifyTheme {
                MainScreen(
                    startRoute = requestedRoute,
                    onStartRouteConsumed = { requestedRoute = null },
                    triggerUpdateCheck = triggerUpdateCheck,
                    onUpdateCheckConsumed = { triggerUpdateCheck = false }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedRoute = intent.getStringExtra(GeekifyNotifier.EXTRA_ROUTE)
        triggerUpdateCheck = intent.getBooleanExtra(GeekifyNotifier.EXTRA_TRIGGER_UPDATE_CHECK, false)
    }

    override fun onStart() {
        super.onStart()
        AppForeground.isVisible = true
        lifecycleScope.launch {
            UpdatePushSubscription.sync(this@MainActivity, notificationStore.prefsNow().updates)
            val now = System.currentTimeMillis()
            notificationStore.updateState { it.copy(lastOpenedAt = now) }
            // If the user has just installed the announced version, its notification must disappear.
            notificationCoordinator.reconcile()
        }
    }

    override fun onStop() {
        AppForeground.isVisible = false
        // Run the existing notification pass shortly after Geekify leaves the foreground.
        // This keeps update/tip notifications responsive without running work while the app is visible.
        NotificationWorker.kick(this)
        super.onStop()
    }
}
