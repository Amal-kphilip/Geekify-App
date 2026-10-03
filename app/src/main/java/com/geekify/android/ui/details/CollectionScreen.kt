package com.geekify.android.ui.details

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.geekify.android.data.model.CollectionKind
import com.geekify.android.data.model.Track
import com.geekify.android.ui.components.AuroraBackground
import com.geekify.android.ui.components.DetailHeader
import com.geekify.android.ui.components.TrackRow
import com.geekify.android.ui.components.totalDurationText
import com.geekify.android.ui.components.rememberArtColor
import com.geekify.android.ui.theme.*

@Composable
fun CollectionScreen(
    id: String,
    kind: CollectionKind,
    viewModel: DetailsViewModel,
    onBack: () -> Unit,
    onPlayTrack: (Track, List<Track>) -> Unit,
    onTrackActions: (Track) -> Unit,
    onShufflePlay: ((List<Track>) -> Unit)? = null
) {
    val state by viewModel.collectionState.collectAsState()

    LaunchedEffect(id, kind) {
        viewModel.loadCollection(id, kind)
    }

    val collection = state.collection
    val thumbUrl = collection?.thumbnails?.lastOrNull()?.url
    val tint by rememberArtColor(thumbUrl, fallback = InkElevated)

    AuroraBackground(tint = tint) {
        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = SpotifyGreen)
            }
            state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(state.error ?: "Failed to load collection", color = ErrorRed)
            }
            collection != null -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    DetailHeader(
                        onBack = onBack,
                        title = collection.title,
                        subtitle = collection.artist,
                        description = collection.description,
                        meta = listOfNotNull(
                            if (kind == CollectionKind.ALBUM) "Album" else "Playlist",
                            collection.year,
                            "${collection.tracks.size} tracks",
                            totalDurationText(collection.tracks)
                        ).joinToString(" • "),
                        onPlay = if (collection.tracks.isNotEmpty()) ({ onPlayTrack(collection.tracks.first(), collection.tracks) }) else null,
                        onShuffle = if (collection.tracks.isNotEmpty() && onShufflePlay != null) ({ onShufflePlay(collection.tracks) }) else null,
                        art = {
                            if (thumbUrl != null) {
                                AsyncImage(
                                    model = thumbUrl,
                                    contentDescription = collection.title,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(Icons.Default.Album, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(80.dp))
                            }
                        }
                    )
                }

                items(collection.tracks, key = { it.videoId }) { track ->
                    TrackRow(
                        track = track,
                        onClick = { onPlayTrack(track, collection.tracks) },
                        onMoreClick = { onTrackActions(track) }
                    )
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}
