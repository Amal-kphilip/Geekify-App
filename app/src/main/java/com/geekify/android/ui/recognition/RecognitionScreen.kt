package com.geekify.android.ui.recognition

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.geekify.android.data.model.Track
import com.geekify.android.recognition.RecognitionStatus
import com.geekify.android.ui.theme.Lime
import com.geekify.android.ui.theme.OnAccent
import com.geekify.android.ui.theme.TextPrimary
import com.geekify.android.ui.theme.TextSecondary
import org.koin.androidx.compose.koinViewModel

@Composable
fun RecognitionScreen(
    onBack: () -> Unit,
    onPlayTrack: (Track) -> Unit,
    viewModel: RecognitionViewModel = koinViewModel()
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val audioLevel by viewModel.audioLevel.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val historyQuery by viewModel.historyQuery.collectAsStateWithLifecycle()
    val isResolvingTrack by viewModel.isResolvingTrack.collectAsStateWithLifecycle()
    val playbackError by viewModel.playbackError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showPermissionDeniedBanner by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopListening(abort = true)
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showPermissionDeniedBanner = false
            viewModel.startListening()
        } else {
            showPermissionDeniedBanner = true
        }
    }

    val pulseScale by animateFloatAsState(
        targetValue = if (status is RecognitionStatus.Listening) 1f + audioLevel * 0.35f else 1f,
        label = "micPulse"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                viewModel.stopListening(abort = true)
                onBack()
            }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Text(
                text = "Identify Music",
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (history.isNotEmpty()) {
                TextButton(onClick = { viewModel.clearHistory() }) {
                    Text("Clear History", color = TextSecondary, fontSize = 13.sp)
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF181820).copy(alpha = 0.85f))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(116.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(
                        if (status is RecognitionStatus.Listening) Lime else Color(0xFF282834)
                    )
                    .clickable {
                        when (status) {
                            is RecognitionStatus.Listening -> viewModel.stopListening(abort = true)
                            is RecognitionStatus.Processing -> Unit
                            else -> {
                                if (viewModel.hasMicrophonePermission()) {
                                    showPermissionDeniedBanner = false
                                    viewModel.startListening()
                                } else {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                when (status) {
                    is RecognitionStatus.Listening -> Icon(
                        Icons.Rounded.Stop,
                        contentDescription = "Stop listening",
                        tint = OnAccent,
                        modifier = Modifier.size(44.dp)
                    )
                    is RecognitionStatus.Processing -> CircularProgressIndicator(
                        color = Lime,
                        modifier = Modifier.size(40.dp),
                        strokeWidth = 3.dp
                    )
                    else -> Icon(
                        Icons.Rounded.Mic,
                        contentDescription = "Start listening",
                        tint = Lime,
                        modifier = Modifier.size(44.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            when (val s = status) {
                is RecognitionStatus.Ready -> {
                    Text("Tap to identify playing music", color = TextPrimary, fontWeight = FontWeight.Medium)
                    Text(
                        "Uses your microphone briefly to match audio fingerprints",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
                is RecognitionStatus.Listening -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = Lime, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Listening for music... (tap to cancel)", color = Lime, fontWeight = FontWeight.SemiBold)
                    }
                }
                is RecognitionStatus.Processing -> {
                    Text("Matching acoustic fingerprint...", color = TextPrimary, fontWeight = FontWeight.Medium)
                }
                is RecognitionStatus.NoMatch -> {
                    Text(s.message, color = Color(0xFFFCD34D), fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { viewModel.startListening() }) {
                        Text("Try Again", color = Lime)
                    }
                }
                is RecognitionStatus.Error -> {
                    Text(s.message, color = Color(0xFFF87171), fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        if (viewModel.hasMicrophonePermission()) {
                            viewModel.startListening()
                        } else {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }) {
                        Text("Retry", color = Lime)
                    }
                }
                is RecognitionStatus.Success -> {
                    val m = s.result
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = m.coverArtHqUrl ?: m.coverArtUrl,
                            contentDescription = m.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF282834))
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(m.title, color = TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(m.artist, color = TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            m.album?.let { Text(it, color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            enabled = !isResolvingTrack,
                            onClick = {
                                viewModel.playRecognizedItem(
                                    title = m.title,
                                    artist = m.artist,
                                    album = m.album,
                                    coverArtUrl = m.coverArtHqUrl ?: m.coverArtUrl,
                                    existingVideoId = m.youtubeVideoId,
                                    onResolved = onPlayTrack
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = OnAccent)
                        ) {
                            if (isResolvingTrack) {
                                CircularProgressIndicator(
                                    color = OnAccent,
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(if (isResolvingTrack) "Loading..." else "Play on Geekify")
                        }
                        m.shazamUrl?.let { url ->
                            TextButton(onClick = {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            }) {
                                Text("Open Match", color = TextPrimary)
                            }
                        }
                    }
                }
            }

            playbackError?.let { err ->
                Spacer(Modifier.height(8.dp))
                Text(err, color = Color(0xFFF87171), fontSize = 12.sp)
            }

            if (showPermissionDeniedBanner || !viewModel.hasMicrophonePermission()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Microphone permission is required so Geekify can listen to surrounding music and generate an acoustic fingerprint.",
                    color = Color(0xFFFCD34D),
                    fontSize = 12.sp
                )
                if (showPermissionDeniedBanner) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                            Text("Grant Permission", color = Lime, fontSize = 12.sp)
                        }
                        TextButton(onClick = {
                            runCatching {
                                val intent = Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null)
                                )
                                context.startActivity(intent)
                            }
                        }) {
                            Text("Open App Settings", color = TextPrimary, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = historyQuery,
            onValueChange = viewModel::onHistoryQueryChange,
            placeholder = { Text("Search recognition history") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(history, key = { it.id }) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1A1A22).copy(alpha = 0.78f))
                        .clickable {
                            viewModel.playRecognizedItem(
                                title = item.title,
                                artist = item.artist,
                                album = item.album,
                                coverArtUrl = item.coverArtUrl,
                                existingVideoId = item.youtubeVideoId,
                                historyId = item.id,
                                onResolved = onPlayTrack
                            )
                        }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = item.coverArtUrl,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF282834))
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.title, color = TextPrimary, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.artist, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { viewModel.deleteHistoryItem(item.id) }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = TextSecondary)
                    }
                }
            }
        }
    }
}
