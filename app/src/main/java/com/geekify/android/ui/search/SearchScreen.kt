package com.geekify.android.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geekify.android.data.model.Card
import com.geekify.android.data.model.Track
import com.geekify.android.ui.components.*
import com.geekify.android.ui.theme.*

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    photoUrl: String?,
    userName: String?,
    onAvatarClick: () -> Unit,
    onTrackClick: (Track, List<Track>) -> Unit,
    onCardClick: (Card) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val tabs = listOf("All", "Songs", "Albums", "Artists", "Playlists")
    val searching = state.query.isNotBlank()

    AuroraBackground {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            // Header collapses away once the user starts typing
            AnimatedVisibility(visible = !searching) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(photoUrl = photoUrl, name = userName, onClick = onAvatarClick)
                    Spacer(Modifier.width(16.dp))
                    Text("Search", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
            }

            // White search pill
            TextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = if (searching) 8.dp else 0.dp),
                placeholder = { Text("What do you want to listen to?", color = Color(0xFF4D4D4D), fontWeight = FontWeight.Medium) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Black) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", tint = Color.Black)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = Color.Black,
                    focusedTextColor = Color.Black,
                    unfocusedTextColor = Color.Black
                )
            )

            AnimatedVisibility(visible = searching) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(tabs) { tab ->
                        PillChip(text = tab, selected = state.selectedTab == tab, onClick = { viewModel.onTabSelected(tab) })
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    !searching -> {
                        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                            Text(
                                text = "Browse all",
                                color = TextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 24.dp, bottom = 12.dp)
                            )
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(bottom = 24.dp)
                            ) {
                                items(viewModel.genreTags.size) { index ->
                                    val tag = viewModel.genreTags[index]
                                    CategoryTile(
                                        title = tag,
                                        color = CategoryColors[index % CategoryColors.size],
                                        onClick = { viewModel.onTagClick(tag) }
                                    )
                                }
                            }
                        }
                    }
                    state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = SpotifyGreen)
                    }
                    state.error != null -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(text = state.error ?: "Search failed", color = ErrorRed, fontSize = 14.sp)
                    }
                    else -> {
                        val response = state.response
                        if (response != null) {
                            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                                if (response.songs.isNotEmpty() && (state.selectedTab == "All" || state.selectedTab == "Songs")) {
                                    item { SectionTitle("Songs") }
                                    items(response.songs, key = { it.videoId }) { song ->
                                        TrackRow(track = song, onClick = { onTrackClick(song, response.songs) })
                                    }
                                }
                                if (response.albums.isNotEmpty() && (state.selectedTab == "All" || state.selectedTab == "Albums")) {
                                    item { SectionTitle("Albums") }
                                    item { CardsRow(response.albums, onCardClick) }
                                }
                                if (response.artists.isNotEmpty() && (state.selectedTab == "All" || state.selectedTab == "Artists")) {
                                    item { SectionTitle("Artists") }
                                    item { CardsRow(response.artists, onCardClick) }
                                }
                                if (response.playlists.isNotEmpty() && (state.selectedTab == "All" || state.selectedTab == "Playlists")) {
                                    item { SectionTitle("Playlists") }
                                    item { CardsRow(response.playlists, onCardClick) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CardsRow(cards: List<Card>, onCardClick: (Card) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(cards, key = { it.id }) { card ->
            CardItem(card = card, onClick = { onCardClick(card) })
        }
    }
}

/** Colourful Spotify "Browse all" tile with a tilted art square peeking out of the corner. */
@Composable
private fun CategoryTile(title: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .bouncyClickable(pressedScale = 0.96f, onClick = onClick)
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth(0.7f)
                .padding(12.dp)
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 14.dp, y = 8.dp)
                .rotate(25f)
                .size(64.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(32.dp))
        }
    }
}
