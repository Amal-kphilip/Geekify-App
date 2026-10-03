package com.geekify.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.geekify.android.data.sync.SyncRepository
import com.geekify.android.player.PlaybackService
import com.geekify.android.ui.navigation.MainScreen
import com.geekify.android.ui.theme.GeekifyTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var syncRepository: SyncRepository

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* playback works either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
                MainScreen()
            }
        }
    }
}
