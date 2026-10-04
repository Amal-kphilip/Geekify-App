package com.geekify.android.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.geekify.android.player.RepeatMode
import com.geekify.android.ui.components.AuroraBackground
import com.geekify.android.ui.components.SeekBar
import com.geekify.android.ui.components.bouncyClickable
import com.geekify.android.ui.components.rememberArtColor
import com.geekify.android.ui.components.bestArtworkUrl
import com.geekify.android.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpandedPlayerScreen(
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit,
    onQueueClick: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val isLiked by viewModel.isCurrentLiked.collectAsState()
    val track = state.current ?: run {
        onDismiss()
        return
    }

    // This handler is declared inside the full-screen destination, giving it precedence
    // over NavHost back handling. One system-back gesture now always collapses the player.
    BackHandler(onBack = onDismiss)

    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }

    val totalDurationMs = state.durationMs.coerceAtLeast(1L)
    val liveFraction = if (state.durationMs > 0L) (state.progressMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f) else 0f
    val fraction = if (isDragging) dragFraction else liveFraction
    val currentPositionMs = (fraction * totalDurationMs).toLong()

    val thumbUrl = track.thumbnails.bestArtworkUrl()
    val artColor by rememberArtColor(thumbUrl, fallback = InkElevated)

    // The cover gently shrinks when paused and springs back when playing.
    val artScale by animateFloatAsState(
        targetValue = if (state.isPlaying) 1f else 0.9f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "artScale"
    )

    // The no-op click target is intentional: it makes the entire full-screen surface a
    // pointer target, so blank areas cannot send taps through to cards behind this overlay.
    val overlayInteraction = remember { MutableInteractionSource() }
    AuroraBackground(
        tint = artColor,
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = overlayInteraction,
                indication = null,
                onClick = {}
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Collapse", tint = TextPrimary, modifier = Modifier.size(32.dp))
                }
                Text(
                    text = "NOW PLAYING",
                    color = TextPrimary.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                IconButton(onClick = onQueueClick) {
                    Icon(Icons.Default.QueueMusic, contentDescription = "Queue", tint = TextPrimary)
                }
            }

            Spacer(Modifier.weight(0.5f))

            // Album art
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .graphicsLayer {
                        scaleX = artScale
                        scaleY = artScale
                    }
                    .shadow(24.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .background(InkElevated),
                contentAlignment = Alignment.Center
            ) {
                Crossfade(targetState = thumbUrl, animationSpec = tween(400), label = "bigArt") { url ->
                    if (url != null) {
                        AsyncImage(
                            model = url,
                            contentDescription = track.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(Icons.Default.MusicNote, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(80.dp))
                    }
                }
            }

            Spacer(Modifier.weight(0.6f))

            // Title / artist / like
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        color = TextPrimary,
                        fontSize = 26.sp,
                        lineHeight = 31.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = track.artist,
                        color = TextSecondary,
                        fontSize = 17.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                LikeButton(liked = isLiked, size = 30.dp, onClick = { viewModel.toggleLikeCurrent() })
            }

            state.error?.let { message ->
                Text(
                    text = message,
                    color = Color(0xFFFCD34D),
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            }

            Spacer(Modifier.height(20.dp))

            // Seek bar with time labels on either side, like the reference.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(formatMs(currentPositionMs), color = TextSecondary, fontSize = 13.sp, modifier = Modifier.width(40.dp))
                SeekBar(
                    fraction = fraction,
                    onChange = {
                        isDragging = true
                        dragFraction = it
                    },
                    onChangeFinished = {
                        viewModel.seek((dragFraction * totalDurationMs).toLong())
                        isDragging = false
                    },
                    modifier = Modifier.weight(1f),
                    isPlaying = state.isPlaying
                )
                Text(
                    formatMs(totalDurationMs),
                    color = TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.width(40.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )
            }

            Spacer(Modifier.height(12.dp))

            Spacer(Modifier.height(8.dp))

            // Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { viewModel.toggleShuffle() }) {
                    Icon(
                        Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (state.shuffle) SpotifyGreen else TextPrimary,
                        modifier = Modifier.size(26.dp)
                    )
                }

                IconButton(onClick = { viewModel.previous() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = TextPrimary, modifier = Modifier.size(44.dp))
                }

                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .bouncyClickable(pressedScale = 0.92f) { viewModel.togglePlay() },
                    contentAlignment = Alignment.Center
                ) {
                    if (state.isBuffering) {
                        CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    } else {
                        Crossfade(targetState = state.isPlaying, animationSpec = tween(180), label = "bigPlay") { playing ->
                            Icon(
                                imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (playing) "Pause" else "Play",
                                tint = Color.Black,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }
                }

                IconButton(onClick = { viewModel.next() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = TextPrimary, modifier = Modifier.size(44.dp))
                }

                IconButton(onClick = { viewModel.cycleRepeat() }) {
                    Icon(
                        imageVector = if (state.repeat == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        contentDescription = "Repeat",
                        tint = if (state.repeat != RepeatMode.OFF) SpotifyGreen else TextPrimary,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(Modifier.weight(0.4f))
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
