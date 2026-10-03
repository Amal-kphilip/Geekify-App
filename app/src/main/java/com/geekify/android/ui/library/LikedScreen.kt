package com.geekify.android.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geekify.android.data.model.Track
import com.geekify.android.ui.components.AuroraBackground
import com.geekify.android.ui.components.DetailHeader
import com.geekify.android.ui.components.TrackRow
import com.geekify.android.ui.components.totalDurationText
import com.geekify.android.ui.theme.*

@Composable
fun LikedScreen(
    viewModel: LibraryViewModel,
    onBack: () -> Unit,
    onPlayTrack: (Track, List<Track>) -> Unit,
    onShufflePlay: ((List<Track>) -> Unit)? = null
) {
    val liked by viewModel.liked.collectAsState()

    AuroraBackground(tint = Color(0xFF3B2A9C)) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                DetailHeader(
                    onBack = onBack,
                    title = "Liked Songs",
                    subtitle = null,
                    meta = listOfNotNull("${liked.size} songs", totalDurationText(liked)).joinToString(" • "),
                    onPlay = if (liked.isNotEmpty()) ({ onPlayTrack(liked.first(), liked) }) else null,
                    onShuffle = if (liked.isNotEmpty() && onShufflePlay != null) ({ onShufflePlay(liked) }) else null,
                    art = {
                        Box(
                            modifier = Modifier.fillMaxSize().background(LikedGradient),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Favorite, contentDescription = null, tint = Color.White, modifier = Modifier.size(80.dp))
                        }
                    }
                )
            }

            if (liked.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        Text("No liked songs yet", color = TextSecondary, fontSize = 16.sp)
                    }
                }
            }

            items(liked, key = { it.videoId }) { track ->
                TrackRow(track = track, onClick = { onPlayTrack(track, liked) })
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
