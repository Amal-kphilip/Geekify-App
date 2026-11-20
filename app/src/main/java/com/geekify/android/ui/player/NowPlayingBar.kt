package com.geekify.android.ui.player

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.geekify.android.customization.LocalCustomizationState
import com.geekify.android.customization.MiniPlayerArtworkShape
import com.geekify.android.customization.MiniPlayerBackgroundStyle
import com.geekify.android.customization.MiniPlayerStyle
import com.geekify.android.player.PlaybackPosition
import com.geekify.android.ui.components.bestArtworkUrl
import com.geekify.android.ui.components.bouncyClickable
import com.geekify.android.ui.components.rememberArtColor
import com.geekify.android.ui.glass.GlassStyle
import com.geekify.android.ui.glass.liquidGlass
import com.geekify.android.ui.glass.rememberReducedMotion
import com.geekify.android.ui.glass.trackPress
import com.geekify.android.ui.theme.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Customizable Geekify & Metrolist-inspired Mini Player supporting 6 distinct visual styles
 * ([MiniPlayerStyle]), 5 background treatments ([MiniPlayerBackgroundStyle]), Expressive
 * scalloped rotating artwork & progress ring, configurable controls, horizontal swipe-to-skip,
 * vertical expand/dismiss gestures, and shared-element bounds tracking with [PlayerTransitionState].
 */
@Composable
fun NowPlayingBar(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
    isInline: Boolean = false,
    transitionState: PlayerTransitionState? = null,
    onClick: () -> Unit,
    onDismiss: () -> Unit = {},
    onQueueClick: (() -> Unit)? = null
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val audioSettings by viewModel.audioSettings.collectAsStateWithLifecycle()
    val liked by viewModel.isCurrentLiked.collectAsStateWithLifecycle()
    val track = state.current ?: return
    val customization = LocalCustomizationState.current
    val miniCust = customization.miniPlayer
    val seekbarCust = customization.seekbar
    val gestureCust = customization.gestures
    val reducedMotion = rememberReducedMotion()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val thumbUrl = track.thumbnails.bestArtworkUrl()
    val artColor by rememberArtColor(thumbUrl, fallback = InkElevated)
    val animatedArtColor by animateColorAsState(artColor, tween(450), label = "miniArtColor")

    val pressed = remember { mutableStateOf(false) }
    val pressAnim by animateFloatAsState(if (pressed.value) 1f else 0f, label = "miniPress")
    val density = LocalDensity.current
    val sensitivity = gestureCust.gestureSensitivity.coerceIn(0.6f, 1.5f)
    val dismissThresholdPx = with(density) { (72.dp / sensitivity).toPx() }
    val skipThresholdPx = with(density) { (76.dp / sensitivity).toPx() }
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }

    val isExpressive = !isInline && miniCust.style == MiniPlayerStyle.EXPRESSIVE
    val isModern = !isInline && miniCust.style == MiniPlayerStyle.MODERN
    val isVintage = !isInline && miniCust.style == MiniPlayerStyle.VINTAGE

    val resolvedRadius = when {
        isInline -> 28.dp
        miniCust.style == MiniPlayerStyle.ROUNDED_CARD -> miniCust.cornerRadiusDp.coerceAtMost(20).dp
        miniCust.style == MiniPlayerStyle.COMPACT_SLIM -> 24.dp
        isVintage -> 12.dp
        isModern || isExpressive -> (miniCust.heightDp / 2).coerceIn(26, 36).dp
        else -> miniCust.cornerRadiusDp.dp
    }
    val resolvedHeight = when {
        isInline -> 56.dp
        miniCust.style == MiniPlayerStyle.COMPACT_SLIM -> miniCust.heightDp.coerceAtMost(56).dp
        else -> miniCust.heightDp.dp
    }
    val shape = RoundedCornerShape(resolvedRadius)
    val useGlass = miniCust.glassEffect &&
        miniCust.backgroundStyle == MiniPlayerBackgroundStyle.THEMED &&
        !isExpressive

    var horizontalDragOffset by remember { mutableFloatStateOf(0f) }

    DisposableEffect(isInline, transitionState) {
        transitionState?.miniIsInline = isInline
        if (isInline) {
            transitionState?.miniNextBounds = Rect.Zero
        }
        onDispose { }
    }

    val controlsInteractive: () -> Boolean = {
        (transitionState?.progress ?: 1f) >= 0.85f
    }

    // Gentle artwork shape rotation for Expressive style while playing
    val expressiveRotation by if (isExpressive && miniCust.expressiveCookieArtwork && miniCust.expressiveShapeMotion && state.isPlaying && !reducedMotion) {
        rememberInfiniteTransition(label = "expressiveMiniRot").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(18_000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "cookieAngle"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    val containerBgColor = when {
        isExpressive -> androidx.compose.ui.graphics.lerp(InkElevated, Lime, 0.20f).copy(alpha = miniCust.surfaceAlpha)
        miniCust.backgroundStyle == MiniPlayerBackgroundStyle.AMOLED_BLACK -> Color.Black.copy(alpha = miniCust.surfaceAlpha.coerceAtLeast(0.92f))
        miniCust.backgroundStyle == MiniPlayerBackgroundStyle.SOLID_DARK -> InkPanel.copy(alpha = miniCust.surfaceAlpha.coerceAtLeast(0.90f))
        else -> NavPill.copy(alpha = miniCust.surfaceAlpha)
    }

    val contentOnBg = when {
        miniCust.backgroundStyle == MiniPlayerBackgroundStyle.AMOLED_BLACK ||
            miniCust.backgroundStyle == MiniPlayerBackgroundStyle.BLURRED_ARTWORK ||
            miniCust.backgroundStyle == MiniPlayerBackgroundStyle.DYNAMIC_GRADIENT -> Color.White
        else -> TextPrimary
    }
    val secondaryOnBg = if (contentOnBg == Color.White) Color.White.copy(alpha = 0.72f) else TextSecondary

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(resolvedHeight)
            .graphicsLayer { translationX = horizontalDragOffset }
            .onGloballyPositioned { coords ->
                if (coords.isAttached) {
                    transitionState?.miniContainerBounds = coords.boundsInRoot()
                    transitionState?.miniIsInline = isInline
                }
            }
            .then(
                if (miniCust.elevationDp > 0 && !useGlass) {
                    Modifier.shadow(miniCust.elevationDp.dp, shape, clip = false)
                } else Modifier
            )
            .then(
                if (useGlass) {
                    Modifier.liquidGlass(shape, GlassStyle.Floating, pressProgress = { pressAnim })
                } else {
                    Modifier
                        .clip(shape)
                        .background(containerBgColor)
                        .border(
                            1.dp,
                            if (contentOnBg == Color.White) Color.White.copy(alpha = if (isExpressive) 0.16f else 0.12f)
                            else NavPillBorder,
                            shape
                        )
                }
            )
            .trackPress(pressed)
            .bouncyClickable(
                pressedScale = 0.985f,
                onClick = onClick
            )
            .pointerInput(state.isPlaying, onDismiss, transitionState, screenHeightPx, sensitivity) {
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
                        } else if (!state.isPlaying && gestureCust.swipeToDismissMiniPlayer && totalDownDrag >= dismissThresholdPx) {
                            if (gestureCust.hapticFeedback) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
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
            .pointerInput(gestureCust.swipeArtworkToSkip, skipThresholdPx) {
                if (!gestureCust.swipeArtworkToSkip) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { horizontalDragOffset = 0f },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        horizontalDragOffset = (horizontalDragOffset + dragAmount).coerceIn(-120f, 120f)
                    },
                    onDragEnd = {
                        val dx = horizontalDragOffset
                        if (dx <= -skipThresholdPx) {
                            if (gestureCust.hapticFeedback) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            viewModel.next()
                        } else if (dx >= skipThresholdPx) {
                            if (gestureCust.hapticFeedback) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            viewModel.previous()
                        }
                        val start = horizontalDragOffset
                        scope.launch {
                            Animatable(start).animateTo(
                                targetValue = 0f,
                                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
                            ) { horizontalDragOffset = value }
                        }
                    },
                    onDragCancel = { horizontalDragOffset = 0f }
                )
            }
    ) {
        // Optional rich backgrounds inside the mini-player surface
        if (!useGlass) {
            when (miniCust.backgroundStyle) {
                MiniPlayerBackgroundStyle.BLURRED_ARTWORK -> {
                    if (thumbUrl != null) {
                        AsyncImage(
                            model = thumbUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        Modifier.blur(20.dp, BlurredEdgeTreatment.Unbounded)
                                    } else Modifier
                                )
                                .alpha(0.65f)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.45f))
                        )
                    }
                }
                MiniPlayerBackgroundStyle.DYNAMIC_GRADIENT -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        animatedArtColor.copy(alpha = 0.85f),
                                        InkPanel.copy(alpha = 0.92f)
                                    )
                                )
                            )
                    )
                }
                else -> Unit
            }
        }

        val hPad = if (isInline) 8.dp else miniCust.horizontalPaddingDp.dp
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = hPad, vertical = if (isInline) 6.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val maxArt = (resolvedHeight - 14.dp).coerceAtLeast(32.dp)
            val artSize = if (isInline) 38.dp else miniCust.artworkSizeDp.dp.coerceIn(32.dp, maxArt)
            val showRingAroundArtwork = miniCust.showProgressIndicator && (isModern || (isExpressive && miniCust.expressiveProgressRing))
            val innerArtSize = if (showRingAroundArtwork) (artSize - 6.dp).coerceAtLeast(30.dp) else artSize

            val artShape = when {
                isExpressive && miniCust.expressiveCookieArtwork -> ScallopedCookieShape
                miniCust.artworkShape == MiniPlayerArtworkShape.CIRCLE -> CircleShape
                miniCust.artworkShape == MiniPlayerArtworkShape.ROUNDED -> RoundedCornerShape(11.dp)
                else -> RoundedCornerShape(4.dp)
            }

            // Ensure play/pause stays accessible even if user hides the dedicated play button
            val artworkTapTogglesPlay = (isExpressive && miniCust.expressiveArtworkPlaybackTap) || !miniCust.showPlayPauseButton

            if (miniCust.showArtwork) {
                Box(
                    modifier = Modifier
                        .size(artSize)
                        .onGloballyPositioned { coords ->
                            if (coords.isAttached) {
                                transitionState?.miniArtBounds = coords.boundsInRoot()
                            }
                        }
                        .graphicsLayer {
                            alpha = if (transitionState?.isExpandedOrTransitioning == true) 0f else 1f
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (showRingAroundArtwork) {
                        val seekPalette = resolveSeekbarPalette(seekbarCust, Lime, Lilac, contentOnBg)
                        MiniArtworkProgressRing(
                            trackId = track.videoId,
                            position = viewModel.position,
                            isPlaying = state.isPlaying,
                            isBuffering = state.isBuffering,
                            playbackSpeed = audioSettings.speed,
                            size = artSize,
                            color = seekPalette.activeColor,
                            trackColor = seekPalette.inactiveTrackColor
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(innerArtSize)
                            .graphicsLayer { rotationZ = expressiveRotation }
                            .clip(artShape)
                            .background(InkElevated)
                            .then(
                                if (artworkTapTogglesPlay) {
                                    Modifier.clickable { if (controlsInteractive()) viewModel.togglePlay() }
                                } else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { rotationZ = -expressiveRotation },
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
                            if (artworkTapTogglesPlay && !state.isPlaying) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.38f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        GeekifyIcons.Play,
                                        contentDescription = "Play",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.width(if (isInline) 10.dp else 12.dp))
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .onGloballyPositioned { coords ->
                        if (coords.isAttached) {
                            transitionState?.miniTextBounds = coords.boundsInRoot()
                        }
                    }
                    .graphicsLayer {
                        alpha = if (transitionState?.isExpandedOrTransitioning == true) 0f else 1f
                    },
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = track.title,
                    color = contentOnBg,
                    fontSize = if (isInline) 13.5.sp else 15.sp,
                    lineHeight = if (isInline) 17.sp else 19.sp,
                    fontWeight = if (isExpressive) FontWeight.SemiBold else FontWeight.Medium,
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
                    color = if (state.error != null) Color(0xFFFCD34D) else secondaryOnBg,
                    fontSize = if (isInline) 11.sp else 12.5.sp,
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
                Spacer(Modifier.width(4.dp))

                if (miniCust.showLikeButton) {
                    LikeButton(
                        liked = liked,
                        onClick = { if (controlsInteractive()) viewModel.toggleLikeCurrent() },
                        modifier = Modifier
                            .size(36.dp)
                            .graphicsLayer {
                                val p = transitionState?.progress ?: 1f
                                alpha = ((p - 0.62f) / 0.38f).coerceIn(0f, 1f)
                            },
                        size = 20.dp
                    )
                }
                if (miniCust.showPreviousButton) {
                    IconButton(
                        onClick = { if (controlsInteractive()) viewModel.previous() },
                        modifier = Modifier
                            .size(36.dp)
                            .graphicsLayer {
                                val p = transitionState?.progress ?: 1f
                                alpha = ((p - 0.62f) / 0.38f).coerceIn(0f, 1f)
                            }
                    ) {
                        Icon(GeekifyIcons.SkipPrevious, contentDescription = "Previous", tint = contentOnBg, modifier = Modifier.size(20.dp))
                    }
                }
            } else {
                Spacer(Modifier.width(6.dp))
            }

            if (isInline || miniCust.showPlayPauseButton) {
                val playSize = when {
                    isInline -> 38.dp
                    miniCust.style == MiniPlayerStyle.COMPACT_SLIM -> 38.dp
                    else -> 42.dp
                }
                val playIconSize = if (isInline) 18.dp else 20.dp
                Box(
                    modifier = Modifier
                        .size(playSize)
                        .onGloballyPositioned { coords ->
                            if (coords.isAttached) {
                                transitionState?.miniPlayBounds = coords.boundsInRoot()
                            }
                        }
                        .graphicsLayer {
                            alpha = if (transitionState?.isExpandedOrTransitioning == true) 0f else 1f
                        }
                        .clip(if (isExpressive) RoundedCornerShape(14.dp) else CircleShape)
                        .background(Lime)
                        .bouncyClickable(pressedScale = 0.92f) {
                            if (controlsInteractive()) viewModel.togglePlay()
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
            }

            if (!isInline) {
                if (miniCust.showNextButton) {
                    Spacer(Modifier.width(2.dp))
                    IconButton(
                        onClick = { if (controlsInteractive()) viewModel.next() },
                        modifier = Modifier
                            .size(38.dp)
                            .onGloballyPositioned { coords ->
                                if (coords.isAttached) {
                                    transitionState?.miniNextBounds = coords.boundsInRoot()
                                }
                            }
                            .graphicsLayer {
                                alpha = if (transitionState?.isExpandedOrTransitioning == true) 0f else 1f
                            }
                    ) {
                        Icon(GeekifyIcons.SkipNext, contentDescription = "Next", tint = contentOnBg, modifier = Modifier.size(21.dp))
                    }
                }

                if (miniCust.showQueueButton && onQueueClick != null) {
                    IconButton(
                        onClick = { if (controlsInteractive()) onQueueClick() },
                        modifier = Modifier
                            .size(36.dp)
                            .graphicsLayer {
                                val p = transitionState?.progress ?: 1f
                                alpha = ((p - 0.65f) / 0.35f).coerceIn(0f, 1f)
                            }
                    ) {
                        Icon(GeekifyIcons.QueueMusic, contentDescription = "Queue", tint = contentOnBg, modifier = Modifier.size(19.dp))
                    }
                }

                if (miniCust.showShuffleButton) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .width(1.dp)
                            .height(20.dp)
                            .graphicsLayer {
                                val p = transitionState?.progress ?: 1f
                                alpha = ((p - 0.65f) / 0.35f).coerceIn(0f, 1f)
                            }
                            .background(contentOnBg.copy(alpha = 0.22f))
                    )
                    IconButton(
                        onClick = { if (controlsInteractive()) viewModel.toggleShuffle() },
                        modifier = Modifier
                            .size(36.dp)
                            .graphicsLayer {
                                val p = transitionState?.progress ?: 1f
                                alpha = ((p - 0.65f) / 0.35f).coerceIn(0f, 1f)
                            }
                    ) {
                        Icon(
                            GeekifyIcons.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (state.shuffle) Lime else contentOnBg,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
        }

        // Bottom hairline / full-width progress bar (for styles that don't use the circular artwork ring)
        val showBottomLine = miniCust.showProgressIndicator && (isInline || (!isModern && !(isExpressive && miniCust.expressiveProgressRing)))
        if (showBottomLine) {
            MiniProgressLine(
                trackId = track.videoId,
                position = viewModel.position,
                isPlaying = state.isPlaying,
                isBuffering = state.isBuffering,
                playbackSpeed = audioSettings.speed,
                seekbarConfig = seekbarCust,
                inactiveBaseColor = contentOnBg,
                isInline = isInline,
                fullBleed = isVintage,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .graphicsLayer {
                        val p = transitionState?.progress ?: 1f
                        alpha = ((p - 0.60f) / 0.40f).coerceIn(0f, 1f)
                    }
            )
        }
    }
}

/** 12-lobe scalloped Expressive cookie shape inspired by Metrolist's ExpressiveMiniPlayer. */
val ScallopedCookieShape = GenericShape { size, _ ->
    val cx = size.width / 2f
    val cy = size.height / 2f
    val baseR = minOf(cx, cy) * 0.90f
    val amp = minOf(cx, cy) * 0.08f
    val lobes = 10
    val steps = 120
    for (i in 0..steps) {
        val theta = (i.toFloat() / steps) * (2f * PI.toFloat())
        val r = baseR + amp * cos(lobes * theta)
        val x = cx + r * cos(theta)
        val y = cy + r * sin(theta)
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

@Composable
private fun MiniArtworkProgressRing(
    trackId: String?,
    position: StateFlow<PlaybackPosition>,
    isPlaying: Boolean,
    isBuffering: Boolean,
    playbackSpeed: Float,
    size: Dp,
    color: Color,
    trackColor: Color
) {
    val pos by position.collectAsStateWithLifecycle()
    val authoritativeMs by rememberAuthoritativePlaybackPosition(
        trackId = trackId,
        positionFlow = position,
        isPlaying = isPlaying,
        isBuffering = isBuffering,
        playbackSpeed = playbackSpeed
    )
    val progress = calculateProgressFraction(authoritativeMs, pos.durationMs)
    Canvas(modifier = Modifier.size(size)) {
        val strokePx = 2.5.dp.toPx()
        val inset = strokePx / 2f
        val arcSize = Size(this.size.width - strokePx, this.size.height - strokePx)
        drawArc(
            color = trackColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = strokePx, cap = StrokeCap.Round)
        )
        if (progress > 0f) {
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
private fun MiniProgressLine(
    trackId: String?,
    position: StateFlow<PlaybackPosition>,
    isPlaying: Boolean,
    isBuffering: Boolean,
    playbackSpeed: Float,
    seekbarConfig: com.geekify.android.customization.SeekbarCustomizationConfig,
    isInline: Boolean,
    inactiveBaseColor: Color = TextPrimary,
    fullBleed: Boolean = false,
    modifier: Modifier = Modifier
) {
    val pos by position.collectAsStateWithLifecycle()
    val authoritativeMs by rememberAuthoritativePlaybackPosition(
        trackId = trackId,
        positionFlow = position,
        isPlaying = isPlaying,
        isBuffering = isBuffering,
        playbackSpeed = playbackSpeed
    )
    val progress = calculateProgressFraction(authoritativeMs, pos.durationMs)
    val palette = resolveSeekbarPalette(seekbarConfig, Lime, Lilac, inactiveBaseColor)
    Box(
        modifier = modifier
            .padding(
                horizontal = when {
                    fullBleed -> 0.dp
                    isInline -> 18.dp
                    else -> 28.dp
                },
                vertical = if (fullBleed) 0.dp else if (isInline) 2.dp else 4.dp
            )
            .fillMaxWidth()
            .height(if (fullBleed) 2.5.dp else if (isInline) 1.5.dp else 2.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(palette.inactiveTrackColor)
            .drawBehind {
                if (progress > 0f) {
                    val activeW = size.width * progress
                    if (palette.useGradient) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(palette.gradientStart, palette.gradientEnd),
                                startX = 0f,
                                endX = activeW.coerceAtLeast(1f)
                            ),
                            size = Size(activeW, size.height)
                        )
                    } else {
                        drawRect(palette.activeColor, size = Size(activeW, size.height))
                    }
                }
            }
    )
}

/** Heart that pops with a spring when toggled; uses [activeColor] when liked. */
@Composable
fun LikeButton(
    liked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    activeColor: Color = Lime,
    inactiveColor: Color = Color.White.copy(alpha = 0.85f)
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
                contentDescription = if (isLiked) "Remove from Liked Songs" else "Add to Liked Songs",
                tint = if (isLiked) activeColor else inactiveColor,
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
