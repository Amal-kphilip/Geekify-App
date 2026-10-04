package com.geekify.android.ui.player

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.geekify.android.data.model.Track
import com.geekify.android.player.RepeatMode
import com.geekify.android.ui.components.CircleIconButton
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
    val liveFraction = (state.progressMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
    val fraction = if (isDragging) dragFraction else liveFraction
    val currentPositionMs = (fraction * totalDurationMs).toLong()
    val remainingMs = (totalDurationMs - currentPositionMs).coerceAtLeast(0L)

    val thumbUrl = track.thumbnails.bestArtworkUrl()
    val artColor by rememberArtColor(thumbUrl, fallback = InkElevated)

    // Swipe down anywhere on the player (cover included) to minimise it. The sheet follows the finger.
    val dismissOffset = remember { Animatable(0f) }
    val dismissScope = rememberCoroutineScope()
    val screenHeightPx = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }

    // The cover gently shrinks when paused and springs back when playing.
    val artScale by animateFloatAsState(
        targetValue = if (state.isPlaying) 1f else 0.92f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "artScale"
    )

    // Size the cover from both dimensions so short screens never push the controls off screen.
    val config = LocalConfiguration.current
    val artSize = minOf(config.screenWidthDp * 0.76f, config.screenHeightDp * 0.37f).dp

    // Neighbouring songs for the fading three-line block under the title.
    val upcoming: List<Track> = state.queue.drop(state.index + 1)
    val previousTrack: Track? = state.queue.getOrNull(state.index - 1)

    // The no-op click target is intentional: it makes the entire full-screen surface a
    // pointer target, so blank areas cannot send taps through to cards behind this overlay.
    val overlayInteraction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = dismissOffset.value
                alpha = 1f - (dismissOffset.value / screenHeightPx).coerceIn(0f, 1f) * 0.5f
            }
            .background(InkBackground)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        dismissScope.launch {
                            dismissOffset.snapTo((dismissOffset.value + dragAmount).coerceAtLeast(0f))
                        }
                    },
                    onDragEnd = {
                        dismissScope.launch {
                            if (dismissOffset.value > screenHeightPx * 0.16f) {
                                dismissOffset.animateTo(screenHeightPx, tween(180))
                                onDismiss()
                            } else {
                                dismissOffset.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                            }
                        }
                    },
                    onDragCancel = {
                        dismissScope.launch { dismissOffset.animateTo(0f) }
                    }
                )
            }
            .clickable(
                interactionSource = overlayInteraction,
                indication = null,
                onClick = {}
            )
    ) {
        PlayerBackdrop(thumbUrl = thumbUrl, tint = artColor)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp)
        ) {
            // ---- Top bar: back / Now Playing / like ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircleIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Collapse",
                    onClick = onDismiss,
                    size = 50.dp,
                    container = Color.White.copy(alpha = 0.2f)
                )
                Text(
                    text = "Now Playing",
                    color = TextPrimary,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                CircleIconButton(
                    icon = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (isLiked) "Remove from Liked Songs" else "Add to Liked Songs",
                    onClick = { viewModel.toggleLikeCurrent() },
                    size = 50.dp,
                    container = Color.White.copy(alpha = 0.2f),
                    tint = if (isLiked) Lime else TextPrimary
                )
            }

            Spacer(Modifier.weight(0.7f))

            // ---- Circular cover ----
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(artSize)
                    .graphicsLayer {
                        scaleX = artScale
                        scaleY = artScale
                    }
                    .shadow(28.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
                    .clip(CircleShape)
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

            // ---- Title / artist ----
            Text(
                text = track.title,
                color = TextPrimary,
                fontSize = 27.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = track.artist,
                color = TextSecondary,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            state.error?.let { message ->
                Text(
                    text = message,
                    color = Color(0xFFFCD34D),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                )
            }

            Spacer(Modifier.height(18.dp))

            // ---- Fading three-line block: previous (dim) / up next (bright) / the one after (dim) ----
            UpNextLines(
                previous = previousTrack?.title,
                next = upcoming.getOrNull(0)?.title,
                afterNext = upcoming.getOrNull(1)?.title,
                onClick = onQueueClick
            )

            Spacer(Modifier.weight(0.5f))

            // ---- Seek bar + times (repeat sits between them) ----
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
                modifier = Modifier.fillMaxWidth(),
                isPlaying = state.isPlaying
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(formatMs(currentPositionMs), color = TextSecondary, fontSize = 13.sp, modifier = Modifier.width(56.dp))
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    IconButton(onClick = { viewModel.cycleRepeat() }, modifier = Modifier.size(34.dp)) {
                        Icon(
                            imageVector = if (state.repeat == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            contentDescription = "Repeat",
                            tint = if (state.repeat != RepeatMode.OFF) Lime else TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Text(
                    "-" + formatMs(remainingMs),
                    color = TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.width(56.dp),
                    textAlign = TextAlign.End
                )
            }

            Spacer(Modifier.height(16.dp))

            // ---- Controls: shuffle, previous, play / pause, next, queue ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { viewModel.toggleShuffle() }) {
                    Icon(
                        Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (state.shuffle) Lime else TextPrimary,
                        modifier = Modifier.size(26.dp)
                    )
                }

                CircleIconButton(
                    icon = Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    onClick = { viewModel.previous() },
                    size = 54.dp,
                    iconSize = 28.dp,
                    container = Color(0xFF211F28)
                )

                Box(
                    modifier = Modifier
                        .size(70.dp)
                        .clip(CircleShape)
                        .background(Lime)
                        .bouncyClickable(pressedScale = 0.92f) { viewModel.togglePlay() },
                    contentAlignment = Alignment.Center
                ) {
                    if (state.isBuffering) {
                        CircularProgressIndicator(color = OnAccent, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    } else {
                        Crossfade(targetState = state.isPlaying, animationSpec = tween(180), label = "bigPlay") { playing ->
                            Icon(
                                imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (playing) "Pause" else "Play",
                                tint = OnAccent,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                }

                CircleIconButton(
                    icon = Icons.Default.SkipNext,
                    contentDescription = "Next",
                    onClick = { viewModel.next() },
                    size = 54.dp,
                    iconSize = 28.dp,
                    container = Color(0xFF211F28)
                )

                IconButton(onClick = onQueueClick) {
                    Icon(Icons.Default.QueueMusic, contentDescription = "Queue", tint = TextPrimary, modifier = Modifier.size(26.dp))
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

/**
 * Blurred cover colour that washes in from the top and melts into black by the middle of the screen.
 * Blur needs Android 12+; older versions get the plain colour wash from the cover's average colour.
 */
@Composable
private fun PlayerBackdrop(thumbUrl: String?, tint: Color) {
    val animatedTint by animateColorAsState(tint, tween(600), label = "backdropTint")
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to animatedTint,
                        0.6f to InkBackground,
                        1f to InkBackground
                    )
                )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Crossfade(targetState = thumbUrl, animationSpec = tween(600), label = "backdropArt") { url ->
                if (url != null) {
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(520.dp)
                            .blur(60.dp, BlurredEdgeTreatment.Unbounded)
                            .graphicsLayer {
                                scaleX = 1.4f
                                scaleY = 1.4f
                            }
                            .alpha(0.85f),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
        // Fade everything below the top third into the black background.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.28f to Color.Transparent,
                        0.62f to InkBackground,
                        1f to InkBackground
                    )
                )
        )
    }
}

/** Three centred lines: the outer two are faint, the middle one bright. Tapping opens the queue. */
@Composable
private fun UpNextLines(
    previous: String?,
    next: String?,
    afterNext: String?,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
            .clip(RoundedCornerShape(16.dp))
            .bouncyClickable(pressedScale = 0.99f, onClick = onClick),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = previous ?: " ",
            color = TextPrimary.copy(alpha = 0.26f),
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Text(
            text = if (next != null) "Up next: $next" else "Nothing up next",
            color = TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Text(
            text = afterNext ?: " ",
            color = TextPrimary.copy(alpha = 0.26f),
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
