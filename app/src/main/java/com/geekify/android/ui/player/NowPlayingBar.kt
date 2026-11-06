package com.geekify.android.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.geekify.android.player.PlaybackPosition
import com.geekify.android.ui.components.bestArtworkUrl
import com.geekify.android.ui.components.bouncyClickable
import com.geekify.android.ui.glass.GlassStyle
import com.geekify.android.ui.glass.liquidGlass
import com.geekify.android.ui.glass.trackPress
import com.geekify.android.ui.theme.*
import kotlinx.coroutines.flow.StateFlow

/**
 * Floating mini player: a frosted capsule with the cover, title / artist, a lime play button,
 * next, a hairline divider and shuffle (same grammar as the compact pill in the design).
 *
 * Supports [isInline] mode: collapses to a compact pill that sits between the active tab
 * circle and the search circle in the adaptive bottom bar.
 *
 * Coordinates with [transitionState] so its artwork, title/artist, Play/Pause button, Next button,
 * and outer capsule serve as the exact measured destination of the Now Playing collapse/expand
 * shared transformation.
 */
@Composable
fun NowPlayingBar(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
    isInline: Boolean = false,
    transitionState: PlayerTransitionState? = null,
    onClick: () -> Unit,
    onDismiss: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val liked by viewModel.isCurrentLiked.collectAsStateWithLifecycle()
    val track = state.current ?: return

    // Use the same resolved artwork URL as ExpandedPlayerScreen so Coil reuses the exact
    // decoded in-memory bitmap across both player states without reloading.
    val thumbUrl = track.thumbnails.bestArtworkUrl()
    val pressed = remember { mutableStateOf(false) }
    val pressAnim by animateFloatAsState(if (pressed.value) 1f else 0f, label = "miniPress")
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 72.dp.toPx() }
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val shape = if (isInline) RoundedCornerShape(28.dp) else RoundedCornerShape(32.dp)

    DisposableEffect(isInline, transitionState) {
        transitionState?.miniIsInline = isInline
        if (isInline) {
            transitionState?.miniNextBounds = Rect.Zero
        }
        onDispose { }
    }

    val controlsInteractive = (transitionState?.progress ?: 1f) >= 0.85f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (isInline) Modifier.height(56.dp) else Modifier.height(64.dp))
            .onGloballyPositioned { coords ->
                if (coords.isAttached) {
                    transitionState?.miniContainerBounds = coords.boundsInRoot()
                    transitionState?.miniIsInline = isInline
                }
            }
            // Floating glass surface only: the cover, title, artist and controls inside it stay sharp and opaque.
            .liquidGlass(shape, GlassStyle.Floating, pressProgress = { pressAnim })
            .trackPress(pressed)
            .bouncyClickable(
                pressedScale = 0.985f,
                onClick = {
                    if (controlsInteractive) onClick()
                }
            )
            .pointerInput(state.isPlaying, onDismiss, transitionState, screenHeightPx) {
                val velocityTracker = VelocityTracker()
                var totalDownDrag = 0f
                var totalUpDrag = 0f
                var isExpandingGesture = false

                detectVerticalDragGestures(
                    onDragStart = {
                        velocityTracker.resetTracking()
                        totalDownDrag = 0f
                        totalUpDrag = 0f
                        isExpandingGesture = false
                    },
                    onVerticalDrag = { change, dragAmount ->
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        if (transitionState != null && (dragAmount < -1f || isExpandingGesture || transitionState.progress < 0.995f)) {
                            isExpandingGesture = true
                            change.consume()
                            if (dragAmount < 0f) {
                                totalUpDrag += -dragAmount
                            } else {
                                totalUpDrag = (totalUpDrag - dragAmount).coerceAtLeast(0f)
                            }
                            transitionState.onDragDelta(dragAmount, screenHeightPx)
                        } else if (!state.isPlaying) {
                            if (dragAmount > 0f) {
                                totalDownDrag += dragAmount
                            } else if (totalDownDrag > 0f) {
                                totalDownDrag = 0f
                            }
                        }
                    },
                    onDragEnd = {
                        if (isExpandingGesture && transitionState != null) {
                            val velocityY = velocityTracker.calculateVelocity().y
                            transitionState.settleExpandGesture(
                                totalDragUpPx = totalUpDrag,
                                velocityPxPerSec = velocityY,
                                density = density,
                                fallbackScreenHeightPx = screenHeightPx
                            )
                        } else if (!state.isPlaying && totalDownDrag >= dismissThresholdPx) {
                            onDismiss()
                        }
                        totalDownDrag = 0f
                        totalUpDrag = 0f
                        isExpandingGesture = false
                    },
                    onDragCancel = {
                        if (isExpandingGesture && transitionState != null) {
                            transitionState.collapse()
                        }
                        totalDownDrag = 0f
                        totalUpDrag = 0f
                        isExpandingGesture = false
                    }
                )
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = if (isInline) 8.dp else 10.dp,
                    end = if (isInline) 8.dp else 6.dp,
                    top = if (isInline) 8.dp else 10.dp,
                    bottom = if (isInline) 8.dp else 10.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val artSize = if (isInline) 38.dp else 46.dp
            Box(
                modifier = Modifier
                    .size(artSize)
                    .onGloballyPositioned { coords ->
                        if (coords.isAttached) {
                            transitionState?.miniArtBounds = coords.boundsInRoot()
                        }
                    }
                    .graphicsLayer {
                        val p = transitionState?.progress ?: 1f
                        alpha = if (p >= 0.995f) 1f else 0f
                    }
                    .clip(CircleShape)
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
                    Icon(GeekifyIcons.MusicNote, contentDescription = null, tint = TextSecondary)
                }
            }

            Spacer(Modifier.width(if (isInline) 10.dp else 12.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .onGloballyPositioned { coords ->
                        if (coords.isAttached) {
                            transitionState?.miniTextBounds = coords.boundsInRoot()
                        }
                    }
                    .graphicsLayer {
                        val p = transitionState?.progress ?: 1f
                        alpha = if (p >= 0.995f) 1f else 0f
                    },
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = track.title,
                    color = TextPrimary,
                    fontSize = if (isInline) 13.5.sp else 15.sp,
                    lineHeight = if (isInline) 17.sp else 20.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.onGloballyPositioned { coords ->
                        if (coords.isAttached) {
                            transitionState?.miniTitleBounds = coords.boundsInRoot()
                        }
                    }
                )
                Spacer(Modifier.height(if (isInline) 1.dp else 2.dp))
                Text(
                    text = state.error ?: track.artist,
                    color = if (state.error != null) Color(0xFFFCD34D) else TextSecondary,
                    fontSize = if (isInline) 11.sp else 13.sp,
                    lineHeight = if (isInline) 14.sp else 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.onGloballyPositioned { coords ->
                        if (coords.isAttached) {
                            transitionState?.miniArtistBounds = coords.boundsInRoot()
                        }
                    }
                )
            }

            if (!isInline) {
                Spacer(Modifier.width(8.dp))

                LikeButton(
                    liked = liked,
                    onClick = { if (controlsInteractive) viewModel.toggleLikeCurrent() },
                    modifier = Modifier
                        .size(40.dp)
                        .graphicsLayer {
                            val p = transitionState?.progress ?: 1f
                            alpha = ((p - 0.62f) / 0.38f).coerceIn(0f, 1f)
                        },
                    size = 22.dp
                )
            } else {
                Spacer(Modifier.width(6.dp))
            }

            val playSize = if (isInline) 38.dp else 44.dp
            val playIconSize = if (isInline) 18.dp else 21.dp
            Box(
                modifier = Modifier
                    .size(playSize)
                    .onGloballyPositioned { coords ->
                        if (coords.isAttached) {
                            transitionState?.miniPlayBounds = coords.boundsInRoot()
                        }
                    }
                    .graphicsLayer {
                        val p = transitionState?.progress ?: 1f
                        alpha = if (p >= 0.995f) 1f else 0f
                    }
                    .clip(CircleShape)
                    .background(Lime)
                    .bouncyClickable(pressedScale = 0.92f) {
                        if (controlsInteractive) viewModel.togglePlay()
                    },
                contentAlignment = Alignment.Center
            ) {
                if (state.isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = OnAccent,
                        strokeWidth = 2.dp
                    )
                } else {
                    Crossfade(targetState = state.isPlaying, animationSpec = tween(150), label = "miniPlay") { playing ->
                        Icon(
                            imageVector = if (playing) GeekifyIcons.Pause else GeekifyIcons.Play,
                            contentDescription = if (playing) "Pause" else "Play",
                            tint = OnAccent,
                            modifier = Modifier.size(playIconSize)
                        )
                    }
                }
            }

            if (!isInline) {
                Spacer(Modifier.width(2.dp))

                IconButton(
                    onClick = { if (controlsInteractive) viewModel.next() },
                    modifier = Modifier
                        .size(40.dp)
                        .onGloballyPositioned { coords ->
                            if (coords.isAttached) {
                                transitionState?.miniNextBounds = coords.boundsInRoot()
                            }
                        }
                        .graphicsLayer {
                            val p = transitionState?.progress ?: 1f
                            alpha = if (p >= 0.995f) 1f else 0f
                        }
                ) {
                    Icon(GeekifyIcons.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(22.dp))
                }

                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .width(1.dp)
                        .height(22.dp)
                        .graphicsLayer {
                            val p = transitionState?.progress ?: 1f
                            alpha = ((p - 0.65f) / 0.35f).coerceIn(0f, 1f)
                        }
                        .background(Color.White.copy(alpha = 0.22f))
                )

                IconButton(
                    onClick = { if (controlsInteractive) viewModel.toggleShuffle() },
                    modifier = Modifier
                        .size(40.dp)
                        .graphicsLayer {
                            val p = transitionState?.progress ?: 1f
                            alpha = ((p - 0.65f) / 0.35f).coerceIn(0f, 1f)
                        }
                ) {
                    Icon(
                        GeekifyIcons.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (state.shuffle) Lime else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Hairline progress, eased so it glides rather than ticking. Isolated in its own composable so
        // position ticks recompose only this line, not the whole capsule.
        MiniProgressLine(
            position = viewModel.position,
            isInline = isInline,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .graphicsLayer {
                    val p = transitionState?.progress ?: 1f
                    alpha = ((p - 0.60f) / 0.40f).coerceIn(0f, 1f)
                }
        )
    }
}

@Composable
private fun MiniProgressLine(
    position: StateFlow<PlaybackPosition>,
    isInline: Boolean,
    modifier: Modifier = Modifier
) {
    val pos by position.collectAsStateWithLifecycle()
    val target = if (pos.durationMs > 0L) {
        (pos.positionMs.toFloat() / pos.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val progress by animateFloatAsState(target, tween(900, easing = LinearEasing), label = "miniProgress")
    Box(
        modifier = modifier
            .padding(
                horizontal = if (isInline) 18.dp else 30.dp,
                vertical = if (isInline) 2.dp else 5.dp
            )
            .fillMaxWidth()
            .height(if (isInline) 1.5.dp else 2.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(Color.White.copy(alpha = 0.16f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress)
                .background(Lime)
        )
    }
}

/** Heart that pops with a spring when toggled; lime when liked. */
@Composable
fun LikeButton(
    liked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 28.dp
) {
    val scale by animateFloatAsState(
        targetValue = if (liked) 1f else 0.92f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMedium),
        label = "likeScale"
    )
    IconButton(onClick = onClick, modifier = modifier) {
        Crossfade(targetState = liked, animationSpec = tween(150), label = "likeIcon") { isLiked ->
            Icon(
                imageVector = if (isLiked) GeekifyIcons.Favorite else GeekifyIcons.FavoriteBorder,
                contentDescription = "Like",
                tint = if (isLiked) Lime else Color.White.copy(alpha = 0.85f),
                modifier = Modifier
                    .size(size)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
            )
        }
    }
}
