package com.geekify.android.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.geekify.android.data.model.Card
import com.geekify.android.data.model.Track
import com.geekify.android.ui.components.*
import com.geekify.android.ui.theme.*

private val BrowseTileGradients = listOf(
    listOf(Color(0xFFFF375F), Color(0xFFBF5AF2)),
    listOf(Color(0xFF0A84FF), Color(0xFF5E5CE6)),
    listOf(Color(0xFF30D158), Color(0xFF00C7BE)),
    listOf(Color(0xFFFF9F0A), Color(0xFFFF375F)),
    listOf(Color(0xFF5E5CE6), Color(0xFF40C8E0)),
    listOf(Color(0xFFFF453A), Color(0xFFFF9F0A)),
    listOf(Color(0xFFBF5AF2), Color(0xFF5E5CE6)),
    listOf(Color(0xFF40C8E0), Color(0xFF0A84FF)),
    listOf(Color(0xFFFF2D55), Color(0xFFFF9500)),
    listOf(Color(0xFF32ADE6), Color(0xFF30D158)),
    listOf(Color(0xFFAF52DE), Color(0xFFFF2D55)),
    listOf(Color(0xFF007AFF), Color(0xFF5856D6))
)

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    photoUrl: String?,
    userName: String?,
    onAvatarClick: () -> Unit,
    onTrackClick: (Track, List<Track>) -> Unit,
    onTrackActions: (Track) -> Unit,
    onCardClick: (Card) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val tabs = listOf("All", "Songs", "Albums", "Artists", "Playlists")
    val searching = state.query.isNotBlank()
    val focusManager = LocalFocusManager.current
    var searchFocused by remember { mutableStateOf(false) }
    val showCancel = searchFocused || searching

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(IosPalette.systemGroupedBackground),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 1040.dp)
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Large Title Header (collapses smoothly when searching)
            AnimatedVisibility(visible = !searching) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Search",
                        color = IosPalette.label,
                        fontSize = 34.sp,
                        lineHeight = 41.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp
                    )
                    Avatar(
                        photoUrl = photoUrl,
                        name = userName,
                        size = 40.dp,
                        onClick = onAvatarClick
                    )
                }
            }

            // Cupertino Search Bar + Animated Cancel Action
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 20.dp,
                        end = 20.dp,
                        top = if (searching) 12.dp else 0.dp,
                        bottom = 10.dp
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(IosPalette.secondarySystemGroupedBackground)
                        .border(
                            width = 0.5.dp,
                            color = if (searchFocused) IosPalette.systemBlue.copy(alpha = 0.55f)
                            else Color.White.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(14.dp)
                        )
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = GeekifyIcons.Search,
                        contentDescription = null,
                        tint = IosPalette.secondaryLabel,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (state.query.isEmpty()) {
                            Text(
                                text = "Artists, songs, albums, and more",
                                color = IosPalette.secondaryLabel,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        BasicTextField(
                            value = state.query,
                            onValueChange = viewModel::onQueryChange,
                            singleLine = true,
                            textStyle = TextStyle(
                                color = IosPalette.label,
                                fontSize = 16.sp
                            ),
                            cursorBrush = SolidColor(IosPalette.systemBlue),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = { focusManager.clearFocus() }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { searchFocused = it.isFocused }
                        )
                    }
                    if (state.query.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(IosPalette.tertiaryLabel)
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) { viewModel.onQueryChange("") },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = GeekifyIcons.Close,
                                contentDescription = "Clear",
                                tint = IosPalette.secondarySystemGroupedBackground,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = showCancel,
                    enter = fadeIn() + expandHorizontally(expandFrom = Alignment.Start),
                    exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start)
                ) {
                    Text(
                        text = "Cancel",
                        color = IosPalette.systemBlue,
                        fontSize = 17.sp,
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) {
                                viewModel.onQueryChange("")
                                focusManager.clearFocus()
                            }
                            .padding(vertical = 8.dp)
                    )
                }
            }

            // Filter Pills
            AnimatedVisibility(visible = searching) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(tabs) { tab ->
                        val selected = state.selectedTab == tab
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(
                                    if (selected) Lime
                                    else IosPalette.secondarySystemGroupedBackground
                                )
                                .border(
                                    width = 0.5.dp,
                                    color = if (selected) Color.Transparent else Color.White.copy(alpha = 0.10f),
                                    shape = CircleShape
                                )
                                .bouncyClickable(pressedScale = 0.95f) {
                                    viewModel.onTabSelected(tab)
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = tab,
                                color = if (selected) OnAccent else IosPalette.label,
                                fontSize = 14.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    !searching -> {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 156.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(
                                start = 20.dp,
                                end = 20.dp,
                                top = 12.dp,
                                bottom = 28.dp + LocalBottomInset.current
                            ),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    text = "Browse Categories",
                                    color = IosPalette.label,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = (-0.4).sp,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            items(viewModel.genreTags.size) { index ->
                                val tag = viewModel.genreTags[index]
                                val gradient = BrowseTileGradients[index % BrowseTileGradients.size]
                                CategoryTile(
                                    title = tag,
                                    gradientColors = gradient,
                                    onClick = {
                                        focusManager.clearFocus()
                                        viewModel.onTagClick(tag)
                                    }
                                )
                            }
                        }
                    }

                    state.isLoading -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Lime)
                    }

                    state.error != null -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            color = IosPalette.secondarySystemGroupedBackground,
                            shape = IosCardShape
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = GeekifyIcons.Error,
                                    contentDescription = null,
                                    tint = IosPalette.systemRed,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = state.error ?: "Search failed",
                                    color = IosPalette.label,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    else -> {
                        val response = state.response
                        if (response != null) {
                            val isEmptyResult = response.songs.isEmpty() &&
                                response.albums.isEmpty() &&
                                response.artists.isEmpty() &&
                                response.playlists.isEmpty()

                            if (isEmptyResult) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            imageVector = GeekifyIcons.Search,
                                            contentDescription = null,
                                            tint = IosPalette.tertiaryLabel,
                                            modifier = Modifier.size(40.dp)
                                        )
                                        Spacer(Modifier.height(12.dp))
                                        Text(
                                            text = "No results for \"${state.query}\"",
                                            color = IosPalette.label,
                                            fontSize = 17.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = "Check the spelling or try a different search term.",
                                            color = IosPalette.secondaryLabel,
                                            fontSize = 13.5.sp
                                        )
                                    }
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(bottom = 28.dp + LocalBottomInset.current)
                                ) {
                                    // Top Result Spotlight Card on "All" tab
                                    val topSong = response.songs.firstOrNull()
                                    if (state.selectedTab == "All" && topSong != null) {
                                        item(key = "top_result_${topSong.videoId}") {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 20.dp, vertical = 10.dp)
                                            ) {
                                                Text(
                                                    text = "Top Result",
                                                    color = IosPalette.label,
                                                    fontSize = 20.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    letterSpacing = (-0.4).sp,
                                                    modifier = Modifier.padding(bottom = 10.dp)
                                                )
                                                TopResultSongCard(
                                                    track = topSong,
                                                    onClick = { onTrackClick(topSong, response.songs) },
                                                    onMoreClick = { onTrackActions(topSong) }
                                                )
                                            }
                                        }
                                    }

                                    if (response.songs.isNotEmpty() && (state.selectedTab == "All" || state.selectedTab == "Songs")) {
                                        item(key = "songs_header") {
                                            SectionTitle("Songs")
                                        }
                                        item(key = "songs_grouped_card") {
                                            Surface(
                                                color = IosPalette.secondarySystemGroupedBackground,
                                                shape = IosCardShape,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 20.dp)
                                            ) {
                                                Column(modifier = Modifier.fillMaxWidth()) {
                                                    response.songs.forEachIndexed { idx, song ->
                                                        TrackRow(
                                                            track = song,
                                                            onClick = { onTrackClick(song, response.songs) },
                                                            onLongClick = { onTrackActions(song) },
                                                            onMoreClick = { onTrackActions(song) }
                                                        )
                                                        if (idx < response.songs.lastIndex) {
                                                            IosSettingsDivider(insetStart = 88.dp)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    if (response.albums.isNotEmpty() && (state.selectedTab == "All" || state.selectedTab == "Albums")) {
                                        item(key = "albums_header") { SectionTitle("Albums") }
                                        item(key = "albums_row") { CardsRow(response.albums, onCardClick) }
                                    }

                                    if (response.artists.isNotEmpty() && (state.selectedTab == "All" || state.selectedTab == "Artists")) {
                                        item(key = "artists_header") { SectionTitle("Artists") }
                                        item(key = "artists_row") { CardsRow(response.artists, onCardClick) }
                                    }

                                    if (response.playlists.isNotEmpty() && (state.selectedTab == "All" || state.selectedTab == "Playlists")) {
                                        item(key = "playlists_header") { SectionTitle("Playlists") }
                                        item(key = "playlists_row") { CardsRow(response.playlists, onCardClick) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopResultSongCard(
    track: Track,
    onClick: () -> Unit,
    onMoreClick: () -> Unit
) {
    Surface(
        color = IosPalette.secondarySystemGroupedBackground,
        shape = IosCardShape,
        modifier = Modifier
            .fillMaxWidth()
            .clip(IosCardShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onMoreClick
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(IosPalette.tertiarySystemGroupedBackground),
                contentAlignment = Alignment.Center
            ) {
                val art = track.thumbnails.bestArtworkUrl(360)
                if (art != null) {
                    AsyncImage(
                        model = art,
                        contentDescription = track.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = GeekifyIcons.MusicNote,
                        contentDescription = null,
                        tint = IosPalette.secondaryLabel,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Lime.copy(alpha = 0.16f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "SONG",
                        color = Lime,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = track.title,
                    color = IosPalette.label,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = track.artist,
                    color = IosPalette.secondaryLabel,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Lime)
                    .bouncyClickable(pressedScale = 0.92f, onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = GeekifyIcons.Play,
                    contentDescription = "Play",
                    tint = OnAccent,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onMoreClick) {
                Icon(
                    imageVector = GeekifyIcons.MoreHoriz,
                    contentDescription = "More options",
                    tint = IosPalette.secondaryLabel
                )
            }
        }
    }
}

@Composable
private fun CardsRow(cards: List<Card>, onCardClick: (Card) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(cards, key = { it.id }) { card ->
            CardItem(card = card, onClick = { onCardClick(card) })
        }
    }
}

/** Rich Cupertino gradient category card with a tilted glass motif in the corner. */
@Composable
private fun CategoryTile(
    title: String,
    gradientColors: List<Color>,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(gradientColors))
            .border(0.5.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(20.dp))
            .bouncyClickable(pressedScale = 0.96f, onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 12.dp, y = 10.dp)
                .rotate(22f)
                .size(62.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = GeekifyIcons.MusicNote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(28.dp)
            )
        }

        Text(
            text = title,
            color = Color.White,
            fontSize = 17.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.76f)
                .padding(14.dp)
        )
    }
}
