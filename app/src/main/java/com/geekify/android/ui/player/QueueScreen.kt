package com.geekify.android.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geekify.android.ui.components.AuroraBackground
import com.geekify.android.ui.components.TrackRow
import com.geekify.android.ui.theme.TextMuted
import com.geekify.android.ui.theme.TextPrimary
import com.geekify.android.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    AuroraBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("Playing Queue", fontWeight = FontWeight.Bold, color = TextPrimary) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                        }
                    },
                    actions = {
                        if (state.queue.isNotEmpty()) {
                            IconButton(onClick = { viewModel.clearQueue() }) {
                                Icon(Icons.Default.ClearAll, contentDescription = "Clear Queue", tint = TextSecondary)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { padding ->
            if (state.queue.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Queue is empty", color = TextSecondary, fontSize = 16.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 8.dp)
                ) {
                    val nowPlaying = state.current
                    if (nowPlaying != null) {
                        item {
                            Text(
                                text = "NOW PLAYING",
                                color = TextMuted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                            TrackRow(
                                track = nowPlaying,
                                isPlaying = true,
                                onClick = {}
                            )
                            Spacer(Modifier.height(16.dp))
                        }
                    }

                    val nextTracks = state.queue.drop(state.index + 1)
                    if (nextTracks.isNotEmpty()) {
                        item {
                            Text(
                                text = "NEXT UP (${nextTracks.size})",
                                color = TextMuted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }

                        itemsIndexed(state.queue, key = { index, item -> "${item.videoId}_$index" }) { index, track ->
                            if (index > state.index) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TrackRow(
                                        track = track,
                                        modifier = Modifier.weight(1f),
                                        onClick = { viewModel.play(track, state.queue) },
                                        onMoreClick = { viewModel.removeFromQueue(index) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
