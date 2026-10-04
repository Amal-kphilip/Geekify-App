package com.geekify.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explicit
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.geekify.android.data.model.Card
import com.geekify.android.data.model.Track
import com.geekify.android.data.local.LocalPlaylist
import com.geekify.android.ui.theme.*

/** videoId of the track that is currently playing; lets every TrackRow highlight itself. */
val LocalNowPlayingId = compositionLocalOf<String?> { null }

/** Extra bottom space that scrolling lists need so their last row clears the floating nav + mini player. */
val LocalBottomInset = compositionLocalOf { 0.dp }

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(16.dp),
    backgroundColor: Color = InkGlass,
    borderColor: Color = InkGlassBorder,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(backgroundColor)
            .border(BorderStroke(1.dp, borderColor), shape),
        content = content
    )
}

/** Frosted-glass capsule used by the floating bottom navigation and the mini player. */
fun Modifier.glassPill(shape: Shape = RoundedCornerShape(40.dp)): Modifier = this
    .clip(shape)
    .background(NavPill)
    .background(Brush.verticalGradient(listOf(Color(0x1AFFFFFF), Color.Transparent)))
    .border(BorderStroke(1.dp, NavPillBorder), shape)

/** Round, softly filled icon button (search / favourites / back / more in the header rows). */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    iconSize: Dp = 22.dp,
    container: Color = InkElevated,
    tint: Color = TextPrimary
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(container)
            .bouncyClickable(pressedScale = 0.92f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** Filter chip: lime when selected, dark violet-grey otherwise; colours cross-fade. */
@Composable
fun PillChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg by animateColorAsState(if (selected) Lime else InkElevated, tween(220), label = "chipBg")
    val fg by animateColorAsState(if (selected) OnAccent else TextPrimary, tween(220), label = "chipFg")
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(bg)
            .bouncyClickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = fg, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = TextPrimary,
        fontSize = 22.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 8.dp)
    )
}

/** Round profile avatar: photo if we have one, otherwise the user's initial on green. */
@Composable
fun Avatar(
    photoUrl: String?,
    name: String?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (photoUrl == null) SpotifyGreen else InkElevated)
            .bouncyClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (photoUrl != null) {
            AsyncImage(
                model = photoUrl,
                contentDescription = "Account",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else if (!name.isNullOrBlank()) {
            Text(name.first().uppercase(), color = OnAccent, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
        } else {
            Icon(Icons.Default.Person, contentDescription = "Account", tint = OnAccent, modifier = Modifier.size(size * 0.55f))
        }
    }
}

/** Three little bars that dance while a track is playing. */
@Composable
fun EqualizerBars(
    modifier: Modifier = Modifier,
    color: Color = Lime,
    animate: Boolean = true
) {
    val transition = rememberInfiniteTransition(label = "eq")
    val durations = listOf(480, 640, 560)
    val heights = durations.mapIndexed { i, d ->
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(d, easing = LinearEasing), RepeatMode.Reverse, initialStartOffset = androidx.compose.animation.core.StartOffset(i * 120)),
            label = "bar$i"
        )
    }
    Row(
        modifier = modifier.size(width = 18.dp, height = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        heights.forEach { h ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(if (animate) h.value else 0.4f)
                    .clip(RoundedCornerShape(1.dp))
                    .background(color)
            )
        }
    }
}

@Composable
fun TrackRow(
    track: Track,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = LocalNowPlayingId.current == track.videoId,
    onClick: () -> Unit,
    onMoreClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .bouncyClickable(pressedScale = 0.985f, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val thumbUrl = track.thumbnails.bestArtworkUrl(480)
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(InkElevated),
            contentAlignment = Alignment.Center
        ) {
            if (thumbUrl != null) {
                AsyncImage(
                    model = thumbUrl,
                    contentDescription = track.title,
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
                text = track.title,
                color = if (isPlaying) Lime else TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isPlaying) {
                    EqualizerBars(modifier = Modifier.padding(end = 6.dp).size(width = 14.dp, height = 12.dp))
                }
                if (track.explicit) {
                    Icon(
                        imageVector = Icons.Default.Explicit,
                        contentDescription = "Explicit",
                        tint = TextSecondary,
                        modifier = Modifier.padding(end = 4.dp).size(16.dp)
                    )
                }
                Text(
                    text = listOfNotNull(track.artist, track.duration).joinToString("  •  "),
                    color = TextSecondary,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (onMoreClick != null) {
            Spacer(Modifier.width(8.dp))
            CircleIconButton(
                icon = Icons.Default.MoreHoriz,
                contentDescription = "More",
                onClick = onMoreClick,
                size = 40.dp,
                iconSize = 20.dp
            )
        }
    }
}

/** Actions available for a song wherever it appears in the app. */
@Composable
fun TrackActionsDialog(
    track: Track,
    liked: Boolean,
    playlists: List<LocalPlaylist>,
    onDismiss: () -> Unit,
    onToggleLike: () -> Unit,
    onAddToPlaylist: (String) -> Unit,
    onCreatePlaylist: (String) -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = InkElevated,
        shape = RoundedCornerShape(28.dp),
        title = {
            Text(
                track.title,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column {
                TextButton(onClick = { onToggleLike(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (liked) "Remove from Liked Songs" else "Add to Liked Songs", color = TextPrimary)
                }
                Spacer(Modifier.height(8.dp))
                Text("Add to playlist", color = TextSecondary, fontSize = 14.sp)
                if (playlists.isEmpty()) {
                    Text("Create a playlist to save this song.", color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                } else {
                    playlists.forEach { playlist ->
                        TextButton(
                            onClick = { onAddToPlaylist(playlist.id); onDismiss() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(playlist.name, color = TextPrimary) }
                    }
                }
                TextButton(onClick = { showCreateDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("New playlist", color = Lime, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) } }
    )

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                onCreatePlaylist(name)
                showCreateDialog = false
                onDismiss()
            }
        )
    }
}

@Composable
fun CardItem(
    card: Card,
    modifier: Modifier = Modifier,
    width: Dp = 150.dp,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .width(width)
            .bouncyClickable(onClick = onClick)
    ) {
        val thumbUrl = card.thumbnails.bestArtworkUrl(480)
        val isCircle = card.type == "artist"

        Box(
            modifier = Modifier
                .size(width)
                .clip(if (isCircle) CircleShape else RoundedCornerShape(22.dp))
                .background(InkElevated),
            contentAlignment = Alignment.Center
        ) {
            if (thumbUrl != null) {
                AsyncImage(
                    model = thumbUrl,
                    contentDescription = card.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = TextSecondary)
            }
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = card.title,
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        card.subtitle?.let {
            Text(
                text = it,
                color = TextSecondary,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** "New playlist" popup shared by the Create button, the My Music menu and the song actions dialog. */
@Composable
fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    val canCreate = name.isNotBlank()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    GeekifyDialog(onDismissRequest = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(InkElevated),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PlaylistAdd, contentDescription = null, tint = Lime, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("New playlist", color = TextPrimary, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text("Name it, then add songs from anywhere.", color = TextSecondary, fontSize = 13.5.sp)
            }
        }

        Spacer(Modifier.height(22.dp))

        TextField(
            value = name,
            onValueChange = { if (it.length <= 40) name = it },
            placeholder = { Text("My playlist #1", color = TextMuted) },
            singleLine = true,
            shape = CircleShape,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Sentences),
            keyboardActions = KeyboardActions(onDone = { if (canCreate) onCreate(name.trim()) }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = InkElevated,
                unfocusedContainerColor = InkElevated,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = Lime,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            ),
            modifier = Modifier.fillMaxWidth().focusRequester(focus)
        )

        Spacer(Modifier.height(24.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .clip(CircleShape)
                    .bouncyClickable(pressedScale = 0.97f, onClick = onDismiss),
                contentAlignment = Alignment.Center
            ) {
                Text("Cancel", color = TextSecondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Box(
                modifier = Modifier
                    .weight(1.4f)
                    .height(52.dp)
                    .clip(CircleShape)
                    .background(if (canCreate) Lime else InkElevated)
                    .then(
                        if (canCreate) Modifier.bouncyClickable(pressedScale = 0.97f) { onCreate(name.trim()) }
                        else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Create",
                    color = if (canCreate) OnAccent else TextMuted,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
