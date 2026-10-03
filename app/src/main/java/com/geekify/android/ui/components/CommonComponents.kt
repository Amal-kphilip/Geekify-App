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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.geekify.android.ui.theme.*

/** videoId of the track that is currently playing; lets every TrackRow highlight itself. */
val LocalNowPlayingId = compositionLocalOf<String?> { null }

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

/** Spotify-style filter pill: green when selected, dark grey otherwise, colour cross-fades. */
@Composable
fun PillChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg by animateColorAsState(if (selected) SpotifyGreen else InkElevated, tween(220), label = "chipBg")
    val fg by animateColorAsState(if (selected) Color.Black else TextPrimary, tween(220), label = "chipFg")
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(bg)
            .bouncyClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
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
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)
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
            Text(name.first().uppercase(), color = Color.Black, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
        } else {
            Icon(Icons.Default.Person, contentDescription = "Account", tint = Color.Black, modifier = Modifier.size(size * 0.55f))
        }
    }
}

/** Three little bars that dance while a track is playing. */
@Composable
fun EqualizerBars(
    modifier: Modifier = Modifier,
    color: Color = SpotifyGreen,
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
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val thumbUrl = track.thumbnails.lastOrNull()?.url
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(4.dp))
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

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                color = if (isPlaying) SpotifyGreen else TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
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
                    text = listOfNotNull(track.artist, track.duration).joinToString(" • "),
                    color = TextSecondary,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (onMoreClick != null) {
            IconButton(onClick = onMoreClick) {
                Icon(Icons.Default.MoreVert, contentDescription = "More", tint = TextSecondary)
            }
        }
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
        val thumbUrl = card.thumbnails.lastOrNull()?.url
        val isCircle = card.type == "artist"

        Box(
            modifier = Modifier
                .size(width)
                .clip(if (isCircle) CircleShape else RoundedCornerShape(6.dp))
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

        Spacer(Modifier.height(8.dp))

        Text(
            text = card.title,
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
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

/** Dark "New playlist" dialog shared by the Create tab and the Library screen. */
@Composable
fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = InkElevated,
        shape = RoundedCornerShape(16.dp),
        title = { Text("Give your playlist a name", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("My playlist #1", color = TextMuted) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = SpotifyGreen,
                    unfocusedIndicatorColor = TextMuted,
                    cursorColor = SpotifyGreen,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onCreate(name.trim()) },
                colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
                shape = CircleShape
            ) { Text("Create", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        }
    )
}
