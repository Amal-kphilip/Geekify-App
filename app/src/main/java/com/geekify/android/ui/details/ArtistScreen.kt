package com.geekify.android.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.geekify.android.data.model.Card
import com.geekify.android.data.model.Track
import com.geekify.android.ui.components.AuroraBackground
import com.geekify.android.ui.components.CardItem
import com.geekify.android.ui.components.SectionTitle
import com.geekify.android.ui.components.bouncyClickable
import com.geekify.android.ui.components.rememberArtColor
import com.geekify.android.ui.components.TrackRow
import com.geekify.android.ui.theme.*

@Composable
fun ArtistScreen(
    artistId: String,
    viewModel: DetailsViewModel,
    onBack: () -> Unit,
    onTrackClick: (Track, List<Track>) -> Unit,
    onCardClick: (Card) -> Unit
) {
    val state by viewModel.artistState.collectAsState()

    LaunchedEffect(artistId) {
        viewModel.loadArtist(artistId)
    }

    val artist = state.artist
    val thumbUrl = artist?.thumbnails?.lastOrNull()?.url
    val tint by rememberArtColor(thumbUrl, fallback = InkElevated)

    AuroraBackground(tint = tint) {
        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = SpotifyGreen)
            }
            state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(state.error ?: "Failed to load artist", color = ErrorRed)
            }
            artist != null -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                // Banner
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                            }
                        }
                        Box(
                            modifier = Modifier
                                .size(180.dp)
                                .clip(CircleShape)
                                .background(InkElevated),
                            contentAlignment = Alignment.Center
                        ) {
                            if (thumbUrl != null) {
                                AsyncImage(
                                    model = thumbUrl,
                                    contentDescription = artist.name,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(Icons.Default.Person, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(80.dp))
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = artist.name,
                            color = TextPrimary,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (artist.songs.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(SpotifyGreen)
                                    .bouncyClickable(pressedScale = 0.92f) { onTrackClick(artist.songs.first(), artist.songs) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Play top songs", tint = OnAccent, modifier = Modifier.size(34.dp))
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }

                if (artist.songs.isNotEmpty()) {
                    item { SectionTitle("Popular") }
                    items(artist.songs.take(10), key = { it.videoId }) { song ->
                        TrackRow(track = song, onClick = { onTrackClick(song, artist.songs) })
                    }
                }

                if (artist.albums.isNotEmpty()) {
                    item { Spacer(Modifier.height(12.dp)); SectionTitle("Albums") }
                    item { CardRow(artist.albums, onCardClick) }
                }

                if (artist.singles.isNotEmpty()) {
                    item { Spacer(Modifier.height(12.dp)); SectionTitle("Singles & EPs") }
                    item { CardRow(artist.singles, onCardClick) }
                }

                if (artist.related.isNotEmpty()) {
                    item { Spacer(Modifier.height(12.dp)); SectionTitle("Fans might also like") }
                    item { CardRow(artist.related, onCardClick) }
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun CardRow(cards: List<Card>, onCardClick: (Card) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(cards, key = { it.id }) { card ->
            CardItem(card = card, onClick = { onCardClick(card) })
        }
    }
}
