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
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.geekify.android.data.model.*
import com.geekify.android.ui.components.*
import com.geekify.android.ui.theme.*

/** One card in the "Curated & trending" carousel. [lead] is the song the heart / queue / more buttons act on. */
private data class DiscoverItem(
    val key: String,
    val title: String,
    val description: String,
    val art: String?,
    val lead: Track?,
    val onPlay: () -> Unit
)

/** One row in the filtered daily content list. */
private data class ListRowItem(
    val title: String,
    val subtitle: String,
    val art: String?,
    val onClick: () -> Unit
)

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
    onLikedClick: () -> Unit,
    onSearchClick: () -> Unit = {},
    likedIds: Set<String> = emptySet(),
    onToggleLike: (Track) -> Unit = {},
    onAddToQueue: (Track) -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    val filters = listOf("Everything", "Songs", "Albums", "Playlists", "Artists")
    var showAllPlaylists by remember { mutableStateOf(false) }

    val firstName = userName?.trim()?.split(" ")?.firstOrNull()?.takeIf { it.isNotBlank() } ?: "there"

    // ---- Derived content for the home feed ----
    val allItems = state.shelves.flatMap { it.items }.distinctBy { it.title }

    val mixCards = state.mixes.map { mix ->
        val lead = mix.tracks.firstOrNull()
        DiscoverItem(
            key = "mix:" + mix.id,
            title = mix.title,
            description = mix.subtitle,
            art = lead?.thumbnails?.bestArtworkUrl(720),
            lead = lead,
            onPlay = { if (mix.tracks.isNotEmpty()) onTrackClick(mix.tracks.first(), mix.tracks) }
        )
    }
    val feedCards = allItems
        .filterIsInstance<ShelfCard>()
        .filter { it.value.type == "album" || it.value.type == "playlist" }
        .take(4)
    val discover = (mixCards + feedCards.map { item ->
        DiscoverItem(
            key = "card:" + item.value.id,
            title = item.value.title,
            description = item.value.subtitle?.takeIf { it.isNotBlank() }
                ?: item.value.type.replaceFirstChar { it.uppercase() },
            art = item.value.thumbnails.bestArtworkUrl(720),
            lead = null,
            onPlay = { onCardClick(item.value) }
        )
    }).take(6)

    val usedIds = feedCards.map { it.id }.toSet()
    val listItems = allItems.filter { it.id !in usedIds }.map { item ->
        when (item) {
            is ShelfTrack -> ListRowItem(
                title = item.value.title,
                subtitle = "By ${item.value.artist}",
                art = item.value.thumbnails.bestArtworkUrl(480),
                onClick = { onTrackClick(item.value, listOf(item.value)) }
            )
            is ShelfCard -> ListRowItem(
                title = item.value.title,
                subtitle = item.value.subtitle?.takeIf { it.isNotBlank() }
                    ?: item.value.type.replaceFirstChar { it.uppercase() },
                art = item.value.thumbnails.bestArtworkUrl(480),
                onClick = { onCardClick(item.value) }
            )
        }
    }
    val visibleList = if (showAllPlaylists) listItems.take(20) else listItems.take(5)
    val listSectionTitle = when (state.selectedFilter) {
        "Songs" -> "Top daily songs"
        "Albums" -> "Top daily albums"
        "Playlists" -> "Top daily playlists"
        "Artists" -> "Top daily artists"
        else -> "Top daily picks"
    }

    Box(modifier = Modifier.fillMaxSize().background(InkBackground)) {
        PullToRefreshBox(
            isRefreshing = state.isLoading && (state.shelves.isNotEmpty() || state.mixes.isNotEmpty()),
            onRefresh = { viewModel.loadHome(true) },
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                // ---- Header: glow, avatar, search / favourites, greeting, filter chips ----
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .drawBehind { drawHeaderGlow() }
                            .statusBarsPadding()
                            .padding(top = 14.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Avatar(photoUrl = photoUrl, name = userName, size = 58.dp, onClick = onAccountClick)
                            Spacer(Modifier.weight(1f))
                            CircleIconButton(
                                icon = Icons.Default.Search,
                                contentDescription = "Search",
                                onClick = onSearchClick,
                                size = 50.dp,
                                container = Color.White.copy(alpha = 0.14f)
                            )
                            Spacer(Modifier.width(12.dp))
                            CircleIconButton(
                                icon = Icons.Default.FavoriteBorder,
                                contentDescription = "Liked songs",
                                onClick = onLikedClick,
                                size = 50.dp,
                                container = Color.White.copy(alpha = 0.14f)
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        Text(
                            text = "Hi, $firstName",
                            color = TextPrimary,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )

                        Spacer(Modifier.height(20.dp))

                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
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

                // ---- Loading / error ----
                if (state.isLoading && state.shelves.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = Lime)
                        }
                    }
                }

                if (state.error != null && state.shelves.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 24.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(InkPanel)
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Couldn't load your feed", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                state.error ?: "Check your connection and try again.",
                                color = TextSecondary,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(top = 6.dp, bottom = 16.dp)
                            )
                            RetryPill(onClick = { viewModel.loadHome(true) })
                        }
                    }
                }

                // Cached shelves are showing: say so, and offer to refresh.
                if (state.fromCache || (state.error != null && state.shelves.isNotEmpty())) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, end = 20.dp, top = 16.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(InkElevated)
                                .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (state.error != null) "Offline. Showing saved content." else "Showing saved content…",
                                color = TextSecondary,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )
                            if (state.error != null) {
                                Text(
                                    "Retry",
                                    color = Lime,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .bouncyClickable { viewModel.loadHome(true) }
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }
                }

                // ---- Curated & trending ----
                if (discover.isNotEmpty()) {
                    item {
                        Column(modifier = Modifier.padding(top = 32.dp)) {
                            SectionTitle("Curated & trending")
                            Spacer(Modifier.height(6.dp))
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                items(discover.size, key = { discover[it].key }) { index ->
                                    val card = discover[index]
                                    val lead = card.lead
                                    DiscoverCard(
                                        item = card,
                                        color = DiscoverColors[index % DiscoverColors.size],
                                        liked = lead != null && lead.videoId in likedIds,
                                        onToggleLike = { lead?.let(onToggleLike) },
                                        onAddToQueue = { lead?.let(onAddToQueue) },
                                        onMore = { lead?.let(onTrackActions) },
                                        modifier = Modifier.fillParentMaxWidth(0.86f)
                                    )
                                }
                            }
                        }
                    }
                }

                // ---- Top daily playlists ----
                if (listItems.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 32.dp, end = 20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SectionTitle(listSectionTitle, modifier = Modifier.weight(1f))
                            if (listItems.size > 5) {
                                Text(
                                    text = if (showAllPlaylists) "Show less" else "See all",
                                    color = TextSecondary,
                                    fontSize = 16.sp,
                                    modifier = Modifier.bouncyClickable { showAllPlaylists = !showAllPlaylists }
                                )
                            }
                        }
                    }
                    items(visibleList.size) { index ->
                        PlaylistRow(item = visibleList[index])
                    }
                }

                // ---- Remaining shelves from the feed ----
                items(state.shelves, key = { it.title }) { shelf ->
                    ShelfRow(shelf = shelf, onTrackClick = onTrackClick, onCardClick = onCardClick)
                }

                item { Spacer(Modifier.height(24.dp + LocalBottomInset.current)) }
            }
        }
    }
}

/** Soft white / pink / violet light that blooms from the top-left corner and fades into the background. */
private fun DrawScope.drawHeaderGlow() {
    val w = size.width
    val h = size.height
    // violet bloom
    drawRect(
        Brush.radialGradient(
            colors = listOf(Color(0xFF8A4DFF).copy(alpha = 0.55f), Color.Transparent),
            center = Offset(w * 0.20f, h * 0.10f),
            radius = w * 0.95f
        )
    )
    // blue streak drifting right
    drawRect(
        Brush.radialGradient(
            colors = listOf(Color(0xFF4A78FF).copy(alpha = 0.38f), Color.Transparent),
            center = Offset(w * 0.45f, h * 0.18f),
            radius = w * 0.50f
        )
    )
    // pink core
    drawRect(
        Brush.radialGradient(
            colors = listOf(Color(0xFFFF8FC4).copy(alpha = 0.80f), Color.Transparent),
            center = Offset(w * 0.08f, 0f),
            radius = w * 0.58f
        )
    )
    // hot white centre
    drawRect(
        Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0f)),
            center = Offset(w * 0.10f, -h * 0.02f),
            radius = w * 0.30f
        )
    )
    // melt into the screen background
    drawRect(
        Brush.verticalGradient(
            0.45f to Color.Transparent,
            1f to InkBackground
        )
    )
}

@Composable
private fun DiscoverCard(
    item: DiscoverItem,
    color: Color,
    liked: Boolean,
    onToggleLike: () -> Unit,
    onAddToQueue: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(186.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(color)
            .bouncyClickable(pressedScale = 0.98f, onClick = item.onPlay)
    ) {
        // Artwork on the right, melting into the card colour.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(0.46f)
        ) {
            if (item.art != null) {
                AsyncImage(
                    model = item.art,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.horizontalGradient(listOf(color, Color.Transparent)))
                )
            } else {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = OnPastel.copy(alpha = 0.35f),
                    modifier = Modifier.align(Alignment.Center).size(56.dp)
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth(0.62f)
                .padding(start = 22.dp, top = 22.dp)
        ) {
            Text(
                text = item.title,
                color = OnPastel,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = item.description,
                color = OnPastel.copy(alpha = 0.82f),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .shadow(10.dp, CircleShape, ambientColor = OnPastel, spotColor = OnPastel)
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(OnPastel)
                    .bouncyClickable(pressedScale = 0.92f, onClick = item.onPlay),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            if (item.lead != null) {
                Spacer(Modifier.width(10.dp))
                CardAction(
                    icon = if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    description = if (liked) "Remove from Liked Songs" else "Add to Liked Songs",
                    onClick = onToggleLike
                )
                CardAction(Icons.Default.AddCircleOutline, "Add to queue", onAddToQueue)
                CardAction(Icons.Default.MoreHoriz, "More", onMore)
            }
        }
    }
}

@Composable
private fun RetryPill(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(Lime)
            .bouncyClickable(pressedScale = 0.96f, onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("Try again", color = OnAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CardAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .bouncyClickable(pressedScale = 0.88f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = OnPastel, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun PlaylistRow(item: ListRowItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bouncyClickable(pressedScale = 0.985f, onClick = item.onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(InkElevated),
            contentAlignment = Alignment.Center
        ) {
            if (item.art != null) {
                AsyncImage(
                    model = item.art,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = TextSecondary)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = item.subtitle,
                color = TextSecondary,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(12.dp))
        CircleIconButton(
            icon = Icons.Default.PlayArrow,
            contentDescription = "Play",
            onClick = item.onClick,
            size = 42.dp,
            iconSize = 22.dp
        )
    }
}
