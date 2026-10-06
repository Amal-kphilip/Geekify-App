package com.geekify.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

    /** Screen requested by a notification tap (e.g. "recents"); consumed once by MainScreen. */
    private var requestedRoute by mutableStateOf<String?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* playback works either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestedRoute = intent?.getStringExtra(GeekifyNotifier.EXTRA_ROUTE)

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
                MainScreen(startRoute = requestedRoute, onStartRouteConsumed = { requestedRoute = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedRoute = intent.getStringExtra(GeekifyNotifier.EXTRA_ROUTE)
    }

    override fun onStart() {
        super.onStart()
        AppForeground.isVisible = true
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            notificationStore.updateState { it.copy(lastOpenedAt = now) }
            // If the user has just installed the announced version, its notification must disappear.
            notificationCoordinator.reconcile()
        }
    }

    override fun onStop() {
        AppForeground.isVisible = false
        super.onStop()
    }
}
