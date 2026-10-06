package com.geekify.android.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import com.geekify.android.ui.glass.GlassMenu
import com.geekify.android.ui.glass.GlassMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.geekify.android.data.local.SavedCollection
import com.geekify.android.data.model.Track
import com.geekify.android.ui.components.*
import com.geekify.android.ui.theme.*

/** "My Music": back / title / more, filter chips, then a list of rounded-square rows with a play button. */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    photoUrl: String?,
    userName: String?,
    onAvatarClick: () -> Unit,
    onLikedClick: () -> Unit,
    onPlaylistClick: (String, String) -> Unit,
    onCollectionClick: (String, String) -> Unit = { _, _ -> },
    onBack: () -> Unit = {},
    onPlayTracks: (List<Track>) -> Unit = {}
) {
    val liked by viewModel.liked.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val savedCollections by viewModel.savedCollections.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("All") }
    var removeTarget by remember { mutableStateOf<RemoveTarget?>(null) }
    val haptic = LocalHapticFeedback.current
    val filters = listOf("All", "Playlists", "Liked Songs", "Albums")

    val showLiked = filter == "All" || filter == "Liked Songs"
    val showPlaylists = filter == "All" || filter == "Playlists"
    val shownSaved = savedCollections.filter {
        when (filter) {
            "All" -> true
            "Playlists" -> it.kind != "ALBUM"
            "Albums" -> it.kind == "ALBUM"
            else -> false
        }
    }
    val shownPlaylists = if (showPlaylists) playlists else emptyList()
    val isEmpty = !showLiked && shownPlaylists.isEmpty() && shownSaved.isEmpty()

    Box(modifier = Modifier.fillMaxSize().background(InkBackground)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircleIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack,
                    size = 50.dp
                )
                Text(
                    text = "My Music",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                Box {
                    CircleIconButton(
                        icon = Icons.Default.MoreHoriz,
                        contentDescription = "More",
                        onClick = { menuOpen = true },
                        size = 50.dp
                    )
                    GlassMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        GlassMenuItem("New playlist") {
                            menuOpen = false
                            showCreateDialog = true
                        }
                        GlassMenuItem("Account") {
                            menuOpen = false
                            onAvatarClick()
                        }
                    }
                }
            }

            Spacer(Modifier.height(22.dp))

            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filters) { name ->
                    PillChip(text = name, selected = filter == name, onClick = { filter = name })
                }
            }

            Spacer(Modifier.height(14.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp + LocalBottomInset.current)
            ) {
                if (showLiked) {
                    item {
                        LibraryRow(
                            title = "Liked Songs",
                            subtitle = "By You  •  ${liked.size} songs",
                            onClick = onLikedClick,
                            trailing = if (liked.isNotEmpty()) TrailingAction.Play { onPlayTracks(liked) } else TrailingAction.Chevron,
                            art = {
                                Box(Modifier.fillMaxSize().background(LikedGradient), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Favorite, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                                }
                            }
                        )
                    }
                }

                items(shownPlaylists, key = { "playlist:" + it.id }) { playlist ->
                    val cover = playlist.tracks.firstOrNull()?.thumbnails?.bestArtworkUrl(480)
                    LibraryRow(
                        title = playlist.name,
                        subtitle = "By You  •  ${playlist.tracks.size} songs",
                        onClick = { onPlaylistClick(playlist.id, playlist.name) },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            removeTarget = RemoveTarget.Playlist(playlist.id, playlist.name)
                        },
                        trailing = if (playlist.tracks.isNotEmpty()) TrailingAction.Play { onPlayTracks(playlist.tracks) } else TrailingAction.Chevron,
                        art = {
                            if (cover != null) {
                                AsyncImage(
                                    model = cover,
                                    contentDescription = playlist.name,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.QueueMusic, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(32.dp))
                                }
                            }
                        }
                    )
                }

                // Albums and playlists added from a collection page.
                items(shownSaved, key = { "saved:" + it.id }) { saved ->
                    val label = if (saved.kind == "ALBUM") "Album" else "Playlist"
                    LibraryRow(
                        title = saved.title,
                        subtitle = listOfNotNull(label, saved.subtitle?.takeIf { it.isNotBlank() }).joinToString("  •  "),
                        onClick = { onCollectionClick(saved.id, saved.kind) },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            removeTarget = RemoveTarget.Saved(saved)
                        },
                        trailing = TrailingAction.Chevron,
                        art = {
                            if (saved.thumbnailUrl != null) {
                                AsyncImage(
                                    model = saved.thumbnailUrl,
                                    contentDescription = saved.title,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.QueueMusic, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(32.dp))
                                }
                            }
                        }
                    )
                }

                if (isEmpty) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Nothing here yet. Save an album or create a playlist to see it.",
                                color = TextSecondary,
                                fontSize = 15.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        removeTarget?.let { target ->
            when (target) {
                is RemoveTarget.Saved -> ConfirmDialog(
                    title = "Remove from My Music?",
                    message = "\"${target.item.title}\" will be removed from your saved " +
                        (if (target.item.kind == "ALBUM") "albums" else "playlists") +
                        ". You can add it again any time.",
                    confirmLabel = "Remove",
                    icon = Icons.Default.Delete,
                    destructive = true,
                    onConfirm = {
                        viewModel.toggleSavedCollection(target.item)
                        removeTarget = null
                    },
                    onDismiss = { removeTarget = null }
                )
                is RemoveTarget.Playlist -> ConfirmDialog(
                    title = "Delete playlist?",
                    message = "\"${target.name}\" and its song list will be deleted from your library.",
                    confirmLabel = "Delete",
                    icon = Icons.Default.Delete,
                    destructive = true,
                    onConfirm = {
                        viewModel.deletePlaylist(target.id)
                        removeTarget = null
                    },
                    onDismiss = { removeTarget = null }
                )
            }
        }

        if (showCreateDialog) {
            CreatePlaylistDialog(
                onDismiss = { showCreateDialog = false },
                onCreate = {
                    viewModel.createPlaylist(it)
                    showCreateDialog = false
                }
            )
        }
    }
}

/** What a long press on a My Music row is about to remove. */
private sealed interface RemoveTarget {
    class Saved(val item: SavedCollection) : RemoveTarget
    class Playlist(val id: String, val name: String) : RemoveTarget
}

private sealed interface TrailingAction {
    /** Starts playback straight from the list. */
    class Play(val onPlay: () -> Unit) : TrailingAction
    /** Opens the item (nothing to play directly from here). */
    data object Chevron : TrailingAction
}

@Composable
private fun LibraryRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: TrailingAction,
    onLongClick: (() -> Unit)? = null,
    art: @Composable BoxScope.() -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bouncyCombinedClickable(pressedScale = 0.985f, onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(78.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(InkElevated),
            content = art
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = TextSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        when (trailing) {
            is TrailingAction.Play -> CircleIconButton(
                icon = Icons.Default.PlayArrow,
                contentDescription = "Play",
                onClick = trailing.onPlay,
                size = 44.dp,
                iconSize = 22.dp
            )
            TrailingAction.Chevron -> CircleIconButton(
                icon = Icons.Default.KeyboardArrowDown,
                contentDescription = "Open",
                onClick = onClick,
                size = 44.dp,
                iconSize = 24.dp,
                modifier = Modifier.rotate(-90f)
            )
        }
    }
}
