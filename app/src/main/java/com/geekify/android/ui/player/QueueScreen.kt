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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geekify.android.ui.components.AuroraBackground
import com.geekify.android.ui.components.CircleIconButton
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircleIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        onClick = onBack,
                        size = 50.dp
                    )
                    Text(
                        "Playing Queue",
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                    if (state.queue.isNotEmpty()) {
                        CircleIconButton(
                            icon = Icons.Default.ClearAll,
                            contentDescription = "Clear Queue",
                            onClick = { viewModel.clearQueue() },
                            size = 50.dp
                        )
                    } else {
                        Spacer(Modifier.size(50.dp))
                    }
                }
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
                        .navigationBarsPadding()
                ) {
                    val nowPlaying = state.current
                    if (nowPlaying != null) {
                        item {
                            Text(
                                text = "Now playing",
                                color = TextSecondary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
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
                                text = "Next up (${nextTracks.size})",
                                color = TextSecondary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
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
