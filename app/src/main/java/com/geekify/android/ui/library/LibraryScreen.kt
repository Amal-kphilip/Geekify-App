package com.geekify.android.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.geekify.android.ui.components.*
import com.geekify.android.ui.theme.*

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    photoUrl: String?,
    userName: String?,
    onAvatarClick: () -> Unit,
    onLikedClick: () -> Unit,
    onPlaylistClick: (String, String) -> Unit,
    onCollectionClick: (String, String) -> Unit = { _, _ -> }
) {
    val liked by viewModel.liked.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val savedCollections by viewModel.savedCollections.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }

    AuroraBackground {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Avatar(photoUrl = photoUrl, name = userName, onClick = onAvatarClick)
                Spacer(Modifier.width(12.dp))
                Text("Your Library", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Create Playlist", tint = TextPrimary, modifier = Modifier.size(28.dp))
                }
            }

            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    LibraryRow(
                        title = "Liked Songs",
                        subtitle = "Playlist • ${liked.size} songs",
                        onClick = onLikedClick,
                        art = {
                            Box(Modifier.fillMaxSize().background(LikedGradient), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Favorite, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                            }
                        }
                    )
                }

                items(playlists, key = { "playlist:" + it.id }) { playlist ->
                    LibraryRow(
                        title = playlist.name,
                        subtitle = "Playlist",
                        onClick = { onPlaylistClick(playlist.id, playlist.name) },
                        onRemove = { viewModel.deletePlaylist(playlist.id) },
                        removeLabel = "Delete playlist",
                        art = {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.QueueMusic, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(30.dp))
                            }
                        }
                    )
                }

                // Albums and playlists added from the collection page.
                items(savedCollections, key = { "saved:" + it.id }) { saved ->
                    val label = if (saved.kind == "ALBUM") "Album" else "Playlist"
                    LibraryRow(
                        title = saved.title,
                        subtitle = listOfNotNull(label, saved.subtitle?.takeIf { it.isNotBlank() }).joinToString(" • "),
                        onClick = { onCollectionClick(saved.id, saved.kind) },
                        onRemove = { viewModel.toggleSavedCollection(saved) },
                        removeLabel = "Remove from library",
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
                                    Icon(Icons.Default.QueueMusic, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(30.dp))
                                }
                            }
                        }
                    )
                }
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

@Composable
private fun LibraryRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
    removeLabel: String = "Remove",
    art: @Composable BoxScope.() -> Unit
) {
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    var confirmOpen by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .bouncyClickable(
                    pressedScale = 0.985f,
                    onLongClick = if (onRemove != null) {
                        {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuOpen = true
                        }
                    } else null,
                    onClick = onClick
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(InkElevated),
                content = art
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = TextSecondary, fontSize = 14.sp, maxLines = 1)
            }
        }

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = InkElevated
        ) {
            DropdownMenuItem(
                text = { Text(removeLabel, color = ErrorRed) },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = ErrorRed) },
                onClick = {
                    menuOpen = false
                    confirmOpen = true
                }
            )
        }
    }

    if (confirmOpen && onRemove != null) {
        AlertDialog(
            onDismissRequest = { confirmOpen = false },
            containerColor = InkElevated,
            shape = RoundedCornerShape(16.dp),
            title = { Text("$removeLabel?", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
            text = { Text("\"$title\" will be removed from your library.", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    confirmOpen = false
                    onRemove()
                }) { Text("Remove", color = ErrorRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmOpen = false }) { Text("Cancel", color = TextSecondary) }
            }
        )
    }
}
