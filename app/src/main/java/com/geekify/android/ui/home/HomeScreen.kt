package com.geekify.android.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.geekify.android.data.model.*
import com.geekify.android.ui.components.*
import com.geekify.android.ui.theme.*

private data class QuickItem(val title: String, val thumb: String?, val onClick: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    photoUrl: String?,
    userName: String?,
    onTrackClick: (Track, List<Track>) -> Unit,
    onTrackActions: (Track) -> Unit,
    onCardClick: (Card) -> Unit,
    onAccountClick: () -> Unit,
    onLikedClick: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val filters = listOf("Everything", "Songs", "Albums", "Playlists", "Artists")

    // Refresh the personal rows whenever Home is shown (cheap: skipped unless your listening changed).
    LaunchedEffect(Unit) { viewModel.onScreenShown() }

    val showPersonal = state.selectedFilter == "Everything"
    val showSongRows = showPersonal || state.selectedFilter == "Songs"

    // ---- Quick-access tiles: your mixes first, then what you played last, then the feed as a fallback ----
    val mixTiles = state.mixes.take(2).map { mix ->
        QuickItem(mix.title, mix.tracks.firstOrNull()?.thumbnails?.bestArtworkUrl(480)) {
            if (mix.tracks.isNotEmpty()) onTrackClick(mix.tracks.first(), mix.tracks)
        }
    }
    val recentTiles = state.recent.take(5 - mixTiles.size).map { track ->
        QuickItem(track.title, track.thumbnails.bestArtworkUrl(480)) { onTrackClick(track, state.recent) }
    }
    val personalTiles = mixTiles + recentTiles
    val feedTiles = state.shelves.flatMap { it.items }.distinctBy { it.title }
        .take((5 - personalTiles.size).coerceAtLeast(0))
        .map { item ->
            when (item) {
                is ShelfTrack -> QuickItem(item.value.title, item.value.thumbnails.bestArtworkUrl(480)) {
                    onTrackClick(item.value, listOf(item.value))
                }
                is ShelfCard -> QuickItem(item.value.title, item.value.thumbnails.bestArtworkUrl(480)) {
                    onCardClick(item.value)
                }
            }
        }
    val quickTiles = personalTiles + feedTiles

    // "Picked for you" = the top pick of your first mix (not a random item from the feed).
    val pickMix = state.mixes.firstOrNull { it.tracks.isNotEmpty() }
    val pickTrack = pickMix?.tracks?.firstOrNull()

    AuroraBackground {
        PullToRefreshBox(
            isRefreshing = state.isLoading && (state.shelves.isNotEmpty() || state.mixes.isNotEmpty()),
            onRefresh = { viewModel.loadHome(true) },
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                // Avatar + filter pills
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, top = 8.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(photoUrl = photoUrl, name = userName, onClick = onAccountClick)
                        Spacer(Modifier.width(12.dp))
                        LazyRow(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(filters) { filter ->
                                PillChip(
                                    text = if (filter == "Everything") "All" else filter,
                                    selected = state.selectedFilter == filter,
                                    onClick = { viewModel.setFilter(filter) }
                                )
                            }
                        }
                    }
                }

                // Quick-access grid (Liked Songs + a few recent picks)
                item {
                    val tiles = listOf(
                        QuickItem("Liked Songs", null, onLikedClick)
                    ) + quickTiles
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        tiles.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                pair.forEach { tile ->
                                    QuickTile(
                                        tile = tile,
                                        isLiked = tile.title == "Liked Songs" && tile.thumb == null,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }

                // Loading / error
                if (state.isLoading && state.shelves.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = SpotifyGreen)
                        }
                    }
                }

                if (state.error != null && state.shelves.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = state.error ?: "Could not load feed", color = ErrorRed, fontSize = 14.sp)
                        }
                    }
                }

                // First run: explain why there are no personal rows yet
                if (showPersonal && !state.hasTaste) {
                    item {
                        Text(
                            "Play or like a few songs and Geekify will build mixes around your taste.",
                            color = TextSecondary,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp)
                        )
                    }
                }

                // Jump back in
                if (showSongRows && state.recent.isNotEmpty()) {
                    item(key = "recent") {
                        ShelfRow(
                            shelf = Shelf("Recently played", state.recent.map(::ShelfTrack)),
                            modifier = Modifier.padding(top = 8.dp),
                            onTrackClick = onTrackClick,
                            onCardClick = onCardClick
                        )
                    }
                }

                // Picked for you
                if (showPersonal && pickTrack != null && pickMix != null) {
                    item(key = "picked") {
                        Column(modifier = Modifier.padding(top = 28.dp)) {
                            SectionTitle("Picked for you")
                            FeaturedCard(
                                label = "Song",
                                title = pickTrack.title,
                                subtitle = pickTrack.artist,
                                thumb = pickTrack.thumbnails.bestArtworkUrl(720),
                                onClick = { onTrackClick(pickTrack, pickMix.tracks) },
                                onAddClick = { onTrackActions(pickTrack) },
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                }

                // Your top mixes
                if (showPersonal && state.mixes.isNotEmpty()) {
                    item(key = "mixes") {
                        Column(modifier = Modifier.padding(top = 28.dp)) {
                            SectionTitle("Your top mixes")
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                items(state.mixes, key = { it.id }) { mix ->
                                    MixCover(
                                        mix = mix,
                                        onClick = {
                                            if (mix.tracks.isNotEmpty()) onTrackClick(mix.tracks.first(), mix.tracks)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Because you listened to ...
                if (showSongRows) {
                    items(state.becauseShelves, key = { "because:" + it.title }) { shelf ->
                        ShelfRow(shelf = shelf, onTrackClick = onTrackClick, onCardClick = onCardClick)
                    }
                }

                // Shelves from the feed
                items(state.shelves, key = { it.title }) { shelf ->
                    ShelfRow(shelf = shelf, onTrackClick = onTrackClick, onCardClick = onCardClick)
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun QuickTile(tile: QuickItem, isLiked: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(InkElevated)
            .bouncyClickable(pressedScale = 0.97f, onClick = tile.onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(InkPanel),
            contentAlignment = Alignment.Center
        ) {
            when {
                isLiked -> Box(
                    modifier = Modifier.fillMaxSize().background(LikedGradient),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Favorite, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
                tile.thumb != null -> AsyncImage(
                    model = tile.thumb,
                    contentDescription = tile.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                else -> Icon(Icons.Default.MusicNote, contentDescription = null, tint = TextSecondary)
            }
        }
        Text(
            text = tile.title,
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
    }
}

@Composable
private fun FeaturedCard(
    label: String,
    title: String,
    subtitle: String?,
    thumb: String?,
    onClick: () -> Unit,
    onAddClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(InkPanel)
            .bouncyClickable(pressedScale = 0.98f, onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(150.dp)
                .background(InkElevated),
            contentAlignment = Alignment.Center
        ) {
            if (thumb != null) {
                AsyncImage(
                    model = thumb,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = TextSecondary)
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(16.dp)
        ) {
            Text(label, color = TextSecondary, fontSize = 14.sp)
            Text(
                title,
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = TextSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = { onAddClick?.invoke() }, enabled = onAddClick != null) {
                    Icon(Icons.Default.AddCircleOutline, contentDescription = "Song actions", tint = TextSecondary, modifier = Modifier.size(28.dp))
                }
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.Black, modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}

@Composable
private fun MixCover(mix: Mix, onClick: () -> Unit) {
    val thumb = mix.tracks.firstOrNull()?.thumbnails?.bestArtworkUrl(480)
    Column(
        modifier = Modifier
            .width(156.dp)
            .bouncyClickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(156.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(InkElevated)
        ) {
            if (thumb != null) {
                AsyncImage(
                    model = thumb,
                    contentDescription = mix.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            // Scrim + title
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                    .padding(start = 10.dp, end = 10.dp, top = 24.dp, bottom = 8.dp)
            ) {
                Text(
                    mix.title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Little green badge top-left
            Box(
                modifier = Modifier
                    .padding(8.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .align(Alignment.TopStart),
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(SpotifyGreen))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = mix.subtitle,
            color = TextSecondary,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
