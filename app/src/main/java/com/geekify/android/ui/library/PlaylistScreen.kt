package com.geekify.android.ui.library

import com.geekify.android.ui.components.LocalBottomInset
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.geekify.android.data.model.Track
import com.geekify.android.ui.components.AuroraBackground
import com.geekify.android.ui.components.DetailHeader
import com.geekify.android.ui.components.TrackRow
import com.geekify.android.ui.components.totalDurationText
import com.geekify.android.ui.components.rememberArtColor
import com.geekify.android.ui.theme.*

@Composable
fun PlaylistScreen(
    playlistId: String,
    playlistName: String,
    viewModel: LibraryViewModel,
    onBack: () -> Unit,
    onPlayTrack: (Track, List<Track>) -> Unit,
    onShufflePlay: ((List<Track>) -> Unit)? = null
) {
    val tracks by viewModel.playlistTracks(playlistId).collectAsState(initial = emptyList())
    val coverUrl = tracks.firstOrNull()?.thumbnails?.lastOrNull()?.url
    val tint by rememberArtColor(coverUrl, fallback = InkElevated)

    AuroraBackground(tint = tint) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                DetailHeader(
                    onBack = onBack,
                    title = playlistName,
                    subtitle = null,
                    meta = listOfNotNull("${tracks.size} tracks", totalDurationText(tracks)).joinToString(" • "),
                    onPlay = if (tracks.isNotEmpty()) ({ onPlayTrack(tracks.first(), tracks) }) else null,
                    onShuffle = if (tracks.isNotEmpty() && onShufflePlay != null) ({ onShufflePlay(tracks) }) else null,
                    trailing = {
                        IconButton(onClick = {
                            viewModel.deletePlaylist(playlistId)
                            onBack()
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Playlist", tint = ErrorRed)
                        }
                    },
                    art = {
                        if (coverUrl != null) {
                            AsyncImage(
                                model = coverUrl,
                                contentDescription = playlistName,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(Icons.Default.QueueMusic, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(80.dp))
                        }
                    }
                )
            }

            if (tracks.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        Text("No tracks in this playlist yet", color = TextSecondary, fontSize = 16.sp)
                    }
                }
            }

            items(tracks, key = { it.videoId }) { track ->
                TrackRow(
                    track = track,
                    onClick = { onPlayTrack(track, tracks) },
                    onMoreClick = { viewModel.removeFromPlaylist(playlistId, track.videoId) }
                )
            }

            item { Spacer(Modifier.height(24.dp + LocalBottomInset.current)) }
        }
    }
}
