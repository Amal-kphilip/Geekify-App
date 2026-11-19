package com.geekify.android.ui.player

import android.os.Build
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import coil3.compose.AsyncImage
import com.geekify.android.R
import com.geekify.android.data.model.Track
import com.geekify.android.player.PlaybackPosition
import com.geekify.android.player.RepeatMode
import com.geekify.android.settings.LocalAppSettings
import com.geekify.android.ui.components.CircleIconButton
import com.geekify.android.ui.components.DialogButtonStyle
import com.geekify.android.ui.components.DialogPillButton
import com.geekify.android.ui.components.GeekifyDialog
import com.geekify.android.ui.components.MarqueeText
import com.geekify.android.ui.components.SeekBar
import com.geekify.android.ui.components.bestArtworkUrl
import com.geekify.android.ui.components.bouncyClickable
import com.geekify.android.ui.components.rememberArtColor
import com.geekify.android.ui.glass.glassBackdropSource
import com.geekify.android.ui.glass.rememberGlassBackdrop
import com.geekify.android.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/** Montserrat is used on the Now Playing screen only; the rest of the app keeps [AppFont]. */
private val MontserratFamily = FontFamily(
    Font(R.font.montserrat_regular, FontWeight.Normal),
    Font(R.font.montserrat_medium, FontWeight.Medium),
    Font(R.font.montserrat_semibold, FontWeight.SemiBold),
    Font(R.font.montserrat_bold, FontWeight.Bold)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpandedPlayerScreen(
    viewModel: PlayerViewModel,
    transitionState: PlayerTransitionState,
    isQueueVisible: Boolean = false,
    onDismiss: () -> Unit,
    onQueueClick: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lastSkipDirection = viewModel.lastSkipDirection
    val isLiked by viewModel.isCurrentLiked.collectAsStateWithLifecycle()
    val hasActiveSleepTimer by remember(viewModel) {
        viewModel.sleepTimerRemainingMs
            .map { it != null }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)
    var showSleepTimer by remember { mutableStateOf(false) }

    val track = state.current
    LaunchedEffect(track == null) {
        if (track == null) {
            showSleepTimer = false
            transitionState.snapCollapsed()
        }
    }
    if (track == null) return

    // Predictive Back / Android Back button integration:
    // Dragging the system back gesture previews the shared collapse transformation;
    // committing the back press completes the spring collapse to the mini-player.
    PredictiveBackHandler(enabled = !transitionState.isFullyCollapsed && !isQueueVisible) { backEvents ->
        try {
            backEvents.collect { backEvent ->
                val previewProgress = (backEvent.progress * 0.45f).coerceIn(0f, 0.85f)
                transitionState.onDrag(previewProgress)
            }
            onDismiss()
        } catch (_: CancellationException) {
            transitionState.expand()
        }
    }

    val keepScreenOn = LocalAppSettings.current.keepScreenOn
    val isExpandedActive = !transitionState.isFullyCollapsed
    val currentView = LocalView.current
    DisposableEffect(keepScreenOn, isExpandedActive, currentView) {
        val active = keepScreenOn && isExpandedActive
        if (active) currentView.keepScreenOn = true
        onDispose { if (active) currentView.keepScreenOn = false }
    }

    val thumbUrl = track.thumbnails.bestArtworkUrl()
    val artColor by rememberArtColor(thumbUrl, fallback = InkElevated)
    val playerCust = com.geekify.android.customization.LocalCustomizationState.current.player
    val gestureCust = com.geekify.android.customization.LocalCustomizationState.current.gestures
    val lyricsCust = com.geekify.android.customization.LocalCustomizationState.current.lyrics
    var showLyricsOverlay by remember { mutableStateOf(false) }
    val downloadManager: com.geekify.android.downloads.SongDownloadManager = org.koin.compose.koinInject()
    val allDownloads by downloadManager.downloads.collectAsState(initial = emptyList())
    val currentDownload = remember(allDownloads, track.videoId) {
        allDownloads.firstOrNull { it.videoId == track.videoId }
    }

    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val screenHeightPx = with(density) { config.screenHeightDp.dp.toPx() }

    // Preserve the existing paused-state shrink; the vinyl component keeps beat scaling on the disc only.
    val artBaseScale by animateFloatAsState(
        targetValue = if (state.isPlaying) 1f else 0.92f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "artBaseScale"
    )

    // Tonearm-gated playback is only valid while Now Playing is expanded in the foreground AND using Vinyl layout.
    // Release any pending transition as soon as the player starts collapsing, switches layout, or enters ON_STOP.
    val isForegroundExpanded by remember {
        derivedStateOf { transitionState.progress < 0.25f && !transitionState.isDragging }
    }
    val isVinylLayout = playerCust.layoutStyle == com.geekify.android.customization.PlayerLayoutStyle.VINYL_TONEARM
    val useVinyl = !showLyricsOverlay && isVinylLayout
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(viewModel, lifecycleOwner, isForegroundExpanded, useVinyl) {
        fun updateTonearmSync(started: Boolean) {
            val enabled = started && isForegroundExpanded && useVinyl
            viewModel.setTonearmSyncEnabled(enabled)
            viewModel.setReactiveArtworkMeterEnabled(started && isForegroundExpanded && state.isPlaying)
            if (!enabled) {
                viewModel.completeTonearmTransition(track.videoId)
            }
        }

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> updateTonearmSync(true)
                Lifecycle.Event.ON_STOP -> updateTonearmSync(false)
                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        updateTonearmSync(
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        )

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            viewModel.setTonearmSyncEnabled(false)
            viewModel.setReactiveArtworkMeterEnabled(false)
        }
    }
    LaunchedEffect(state.isPlaying, isForegroundExpanded) {
        viewModel.setReactiveArtworkMeterEnabled(state.isPlaying && isForegroundExpanded)
    }
    LaunchedEffect(useVinyl, track.videoId, state.tonearmTransitionPending) {
        if (!useVinyl) {
            viewModel.setTonearmSyncEnabled(false)
            viewModel.completeTonearmTransition(track.videoId)
        }
    }

    // Size the cover from both dimensions and apply layout + user artwork scale.
    val layoutScaleFactor = when {
        showLyricsOverlay -> 1.02f
        playerCust.layoutStyle == com.geekify.android.customization.PlayerLayoutStyle.MINIMAL -> 0.72f
        playerCust.layoutStyle == com.geekify.android.customization.PlayerLayoutStyle.ARTWORK_FOCUSED -> 1.12f
        playerCust.layoutStyle == com.geekify.android.customization.PlayerLayoutStyle.FULLSCREEN_ARTWORK -> 0.82f
        playerCust.layoutStyle == com.geekify.android.customization.PlayerLayoutStyle.EXPRESSIVE -> 0.94f
        else -> 1.0f
    } * playerCust.artworkScale.coerceIn(0.75f, 1.20f)
    val artSize = (minOf(config.screenWidthDp * 0.76f, config.screenHeightDp * 0.37f) * layoutScaleFactor).dp

    // Neighbouring songs for the fading three-line block under the title.
    val upcoming: List<Track> = state.queue.drop(state.index + 1)
    val previousTrack: Track? = state.queue.getOrNull(state.index - 1)

    val overlayInteraction = remember { MutableInteractionSource() }
    val sleepTimerBackdrop = rememberGlassBackdrop()
    val expandedControlsInteractive: () -> Boolean = {
        transitionState.progress < 0.18f && !transitionState.isDragging
    }
    val isMarqueeActive by remember(transitionState) {
        derivedStateOf { transitionState.progress < 0.02f }
    }
    val isSeekWaveActive by remember(transitionState) {
        derivedStateOf { transitionState.progress < 0.5f }
    }

    val clipPath = remember { Path() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                if (coords.isAttached) {
                    transitionState.rootBounds = coords.boundsInRoot()
                }
            }
            .drawWithContent {
                val p = transitionState.progress

                // Progressive ambient scrim over the underlying screen.
                val scrimAlpha = ((1f - p) * 0.50f).coerceIn(0f, 1f)
                if (scrimAlpha > 0.005f) {
                    drawRect(Color.Black.copy(alpha = scrimAlpha))
                }

                // Morphing player container surface clipping from full-screen to the mini-player capsule.
                val fullRect = Rect(0f, 0f, size.width, size.height)
                val targetMiniRect = transitionState.resolvedMiniContainerInLocal(fullRect, density)
                val currentContainerRect = lerp(fullRect, targetMiniRect, p)
                val targetRadiusPx = (if (transitionState.miniIsInline) 28.dp else 32.dp).toPx()
                val cornerRadiusPx = lerp(0f, targetRadiusPx, p)
                val roundRect = RoundRect(currentContainerRect, CornerRadius(cornerRadiusPx, cornerRadiusPx))

                clipPath.reset()
                clipPath.addRoundRect(roundRect)

                clipPath(clipPath) {
                    this@drawWithContent.drawContent()
                }

                // Subtle glass rim that emerges as the full-screen surface contracts into the mini-player.
                val rimAlpha = (p * (1f - ((p - 0.88f) / 0.12f).coerceIn(0f, 1f)) * 0.18f).coerceIn(0f, 1f)
                if (rimAlpha > 0.005f) {
                    drawPath(
                        path = clipPath,
                        color = Color.White.copy(alpha = rimAlpha),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
            }
            .pointerInput(screenHeightPx, gestureCust.swipeDownToCollapsePlayer) {
                if (!gestureCust.swipeDownToCollapsePlayer) return@pointerInput
                val velocityTracker = VelocityTracker()
                var totalDownDrag = 0f
                detectVerticalDragGestures(
                    onDragStart = {
                        velocityTracker.resetTracking()
                        totalDownDrag = 0f
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        totalDownDrag = (totalDownDrag + dragAmount).coerceAtLeast(0f)
                        transitionState.onDragDelta(dragAmount, screenHeightPx)
                    },
                    onDragEnd = {
                        val velocityY = velocityTracker.calculateVelocity().y
                        transitionState.settleCollapseGesture(
                            totalDragDownPx = totalDownDrag,
                            velocityPxPerSec = velocityY,
                            density = density,
                            fallbackScreenHeightPx = screenHeightPx
                        )
                        totalDownDrag = 0f
                    },
                    onDragCancel = {
                        transitionState.expand()
                        totalDownDrag = 0f
                    }
                )
            }
            .clickable(
                interactionSource = overlayInteraction,
                indication = null,
                onClick = {}
            )
            .glassBackdropSource(sleepTimerBackdrop, enabled = showSleepTimer)
    ) {
        // Morphing container backdrop: configurable background style + fullscreen artwork option
        val baseBgColor = when (playerCust.backgroundStyle) {
            com.geekify.android.customization.PlayerBackgroundStyle.AMOLED_PITCH -> Color.Black
            else -> InkBackground
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = transitionState.progress
                    alpha = (1f - ((p - 0.68f) / 0.32f).coerceIn(0f, 1f))
                }
                .background(baseBgColor)
        ) {
            when {
                playerCust.layoutStyle == com.geekify.android.customization.PlayerLayoutStyle.FULLSCREEN_ARTWORK && thumbUrl != null -> {
                    AsyncImage(
                        model = thumbUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0.0f to Color.Black.copy(alpha = 0.35f),
                                    0.45f to Color.Black.copy(alpha = 0.65f),
                                    1.0f to baseBgColor.copy(alpha = 0.96f)
                                )
                            )
                    )
                }
                playerCust.backgroundStyle == com.geekify.android.customization.PlayerBackgroundStyle.BLURRED_ARTWORK -> {
                    PlayerBackdrop(thumbUrl = thumbUrl, tint = artColor)
                }
                playerCust.backgroundStyle == com.geekify.android.customization.PlayerBackgroundStyle.DYNAMIC_GRADIENT -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(artColor.copy(alpha = 0.62f), baseBgColor)
                                )
                            )
                    )
                }
                else -> Unit
            }
        }

        val activePlayerFont = AppFont
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = activePlayerFont)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 22.dp)
            ) {
                // ---- Top bar: back / Now Playing / sleep timer / like ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .graphicsLayer {
                            val p = transitionState.progress
                            alpha = (1f - p * 2.6f).coerceIn(0f, 1f)
                            translationY = p * 42.dp.toPx()
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircleIconButton(
                        icon = GeekifyIcons.ArrowBack,
                        contentDescription = "Collapse",
                        onClick = { if (expandedControlsInteractive()) onDismiss() },
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
                    if (playerCust.showLyricsButton) {
                        CircleIconButton(
                            icon = GeekifyIcons.Lyrics,
                            contentDescription = "Lyrics",
                            onClick = { if (expandedControlsInteractive()) showLyricsOverlay = !showLyricsOverlay },
                            size = 46.dp,
                            container = Color.White.copy(alpha = 0.2f),
                            tint = if (showLyricsOverlay) Lime else TextPrimary
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    // Download Song action on Now Playing
                    if (playerCust.showDownloadButton) {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f))
                            .bouncyClickable(pressedScale = 0.92f) {
                                if (!expandedControlsInteractive()) return@bouncyClickable
                                when (currentDownload?.status) {
                                    com.geekify.android.data.local.DownloadEntity.STATUS_COMPLETED -> Unit
                                    com.geekify.android.data.local.DownloadEntity.STATUS_RUNNING,
                                    com.geekify.android.data.local.DownloadEntity.STATUS_QUEUED -> {
                                        downloadManager.pause(track.videoId)
                                    }
                                    com.geekify.android.data.local.DownloadEntity.STATUS_PAUSED,
                                    com.geekify.android.data.local.DownloadEntity.STATUS_FAILED -> {
                                        downloadManager.retry(track.videoId)
                                    }
                                    else -> {
                                        downloadManager.enqueue(track)
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        when (currentDownload?.status) {
                            com.geekify.android.data.local.DownloadEntity.STATUS_RUNNING,
                            com.geekify.android.data.local.DownloadEntity.STATUS_QUEUED -> {
                                val pct = currentDownload?.progress?.coerceIn(0f, 1f) ?: 0f
                                if (pct > 0.02f) {
                                    CircularProgressIndicator(
                                        progress = { pct },
                                        color = Lime,
                                        modifier = Modifier.size(26.dp),
                                        strokeWidth = 2.5.dp
                                    )
                                } else {
                                    CircularProgressIndicator(
                                        color = Lime,
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.5.dp
                                    )
                                }
                            }
                            com.geekify.android.data.local.DownloadEntity.STATUS_COMPLETED -> {
                                Icon(
                                    imageVector = GeekifyIcons.CloudDone,
                                    contentDescription = "Downloaded offline",
                                    tint = Lime,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            com.geekify.android.data.local.DownloadEntity.STATUS_FAILED,
                            com.geekify.android.data.local.DownloadEntity.STATUS_PAUSED -> {
                                Icon(
                                    imageVector = GeekifyIcons.Download,
                                    contentDescription = "Retry download",
                                    tint = Color(0xFFFCD34D),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            else -> {
                                Icon(
                                    imageVector = GeekifyIcons.Download,
                                    contentDescription = "Download song",
                                    tint = TextPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    }
                    if (playerCust.showSleepTimerButton) {
                        CircleIconButton(
                            icon = GeekifyIcons.Timer,
                            contentDescription = if (hasActiveSleepTimer) "Sleep timer active" else "Sleep timer",
                            onClick = { if (expandedControlsInteractive()) showSleepTimer = true },
                            size = 46.dp,
                            container = Color.White.copy(alpha = 0.2f),
                            tint = if (hasActiveSleepTimer) Lime else TextPrimary
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    if (playerCust.showLikeButton) {
                        CircleIconButton(
                            icon = if (isLiked) GeekifyIcons.Favorite else GeekifyIcons.FavoriteBorder,
                            contentDescription = if (isLiked) "Remove from Liked Songs" else "Add to Liked Songs",
                            onClick = { if (expandedControlsInteractive()) viewModel.toggleLikeCurrent() },
                            size = 46.dp,
                            container = Color.White.copy(alpha = 0.2f),
                            tint = if (isLiked) Lime else TextPrimary
                        )
                    }
                }

                Spacer(Modifier.weight(0.7f))

                // ---- Shared Element 1: Vinyl cover + tonearm -> Mini-Player Artwork ----
                Box(
                    modifier = Modifier
                        .zIndex(10f)
                        .align(Alignment.CenterHorizontally)
                        .size(artSize)
                        .onGloballyPositioned { coords ->
                            if (coords.isAttached) {
                                transitionState.expandedArtBounds = coords.boundsInRoot()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val p = transitionState.progress
                                val tf = computeSharedTransform(
                                    sourceBounds = transitionState.expandedArtBounds,
                                    destBounds = transitionState.miniArtBounds,
                                    progress = p,
                                    fallbackScale = 0.18f,
                                    fallbackOffsetY = screenHeightPx * 0.42f
                                )
                                translationX = tf.translationX
                                translationY = tf.translationY
                                scaleX = tf.scaleX
                                scaleY = tf.scaleY
                            }
                    ) {
                        key(playerCust.layoutStyle, showLyricsOverlay) {
                            if (showLyricsOverlay) {
                                SynchronizedLyricsView(
                                    track = track,
                                    positionFlow = viewModel.position,
                                    config = lyricsCust,
                                    onSeekTo = { if (expandedControlsInteractive()) viewModel.seek(it) },
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else if (useVinyl) {
                                VinylTonearmCover(
                                    trackId = track.videoId,
                                    coverUrl = thumbUrl,
                                    direction = lastSkipDirection,
                                    isPlaying = state.isPlaying,
                                    beatIntensity = viewModel.beatIntensity,
                                    baseScale = artBaseScale,
                                    collapseProgress = { transitionState.progress },
                                    onTonearmLanded = viewModel::completeTonearmTransition,
                                    modifier = Modifier.fillMaxSize(),
                                    discSize = artSize
                                )
                            } else if (playerCust.layoutStyle == com.geekify.android.customization.PlayerLayoutStyle.FULLSCREEN_ARTWORK) {
                                // Fullscreen Artwork mode uses the full-bleed backdrop image; keep an invisible
                                // horizontal swipe-to-skip gesture zone here without duplicating a smaller cover.
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .pointerInput(gestureCust.swipeArtworkToSkip, gestureCust.gestureSensitivity) {
                                            if (!gestureCust.swipeArtworkToSkip) return@pointerInput
                                            var dragX = 0f
                                            val thresholdPx = (64.dp / gestureCust.gestureSensitivity.coerceIn(0.6f, 1.5f)).toPx()
                                            detectHorizontalDragGestures(
                                                onDragStart = { dragX = 0f },
                                                onHorizontalDrag = { change, amount ->
                                                    change.consume()
                                                    dragX += amount
                                                },
                                                onDragEnd = {
                                                    if (dragX <= -thresholdPx) viewModel.next()
                                                    else if (dragX >= thresholdPx) viewModel.previous()
                                                    dragX = 0f
                                                }
                                            )
                                        }
                                )
                            } else {
                                // Standard / Minimal / Artwork-Focused / Expressive cover with horizontal swipe-to-skip
                                val effectiveShape = if (
                                    playerCust.layoutStyle != com.geekify.android.customization.PlayerLayoutStyle.VINYL_TONEARM &&
                                    playerCust.artworkShape == com.geekify.android.customization.PlayerArtworkShape.VINYL_DISC
                                ) {
                                    com.geekify.android.customization.PlayerArtworkShape.ROUNDED
                                } else {
                                    playerCust.artworkShape
                                }
                                val coverShape = when (effectiveShape) {
                                    com.geekify.android.customization.PlayerArtworkShape.CIRCLE,
                                    com.geekify.android.customization.PlayerArtworkShape.VINYL_DISC -> CircleShape
                                    com.geekify.android.customization.PlayerArtworkShape.SQUARE -> RoundedCornerShape(6.dp)
                                    com.geekify.android.customization.PlayerArtworkShape.ROUNDED -> RoundedCornerShape(28.dp)
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            scaleX = artBaseScale
                                            scaleY = artBaseScale
                                        }
                                        .clip(coverShape)
                                        .background(InkElevated)
                                        .pointerInput(gestureCust.swipeArtworkToSkip, gestureCust.gestureSensitivity) {
                                            if (!gestureCust.swipeArtworkToSkip) return@pointerInput
                                            var dragX = 0f
                                            val thresholdPx = (64.dp / gestureCust.gestureSensitivity.coerceIn(0.6f, 1.5f)).toPx()
                                            detectHorizontalDragGestures(
                                                onDragStart = { dragX = 0f },
                                                onHorizontalDrag = { change, amount ->
                                                    change.consume()
                                                    dragX += amount
                                                },
                                                onDragEnd = {
                                                    if (dragX <= -thresholdPx) viewModel.next()
                                                    else if (dragX >= thresholdPx) viewModel.previous()
                                                    dragX = 0f
                                                }
                                            )
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (thumbUrl != null) {
                                        AsyncImage(
                                            model = thumbUrl,
                                            contentDescription = track.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Icon(
                                            GeekifyIcons.MusicNote,
                                            contentDescription = null,
                                            tint = TextSecondary,
                                            modifier = Modifier.size(64.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.weight(0.6f))

                // ---- Shared Element 2: Title / artist (Expanded -> Compact trajectory) ----
                Column(
                    modifier = Modifier
                        .zIndex(8f)
                        .fillMaxWidth()
                        .onGloballyPositioned { coords ->
                            if (coords.isAttached) {
                                transitionState.expandedTextBounds = coords.boundsInRoot()
                            }
                        }
                        .graphicsLayer {
                            val p = transitionState.progress
                            val src = transitionState.expandedTextBounds
                            val dst = transitionState.miniTextBounds
                            if (src.width > 0f && dst.width > 0f) {
                                translationX = (dst.center.x - src.center.x) * p
                                translationY = (dst.center.y - src.center.y) * p
                            } else {
                                translationY = p * 120.dp.toPx()
                            }
                            val s = lerp(1f, 0.58f, p)
                            scaleX = s
                            scaleY = s
                            alpha = (1f - (p / 0.52f)).coerceIn(0f, 1f)
                        }
                ) {
                    val isExpressive = playerCust.layoutStyle == com.geekify.android.customization.PlayerLayoutStyle.EXPRESSIVE
                    val titleAlign = if (isExpressive) {
                        TextAlign.Start
                    } else {
                        TextAlign.Center
                    }
                    if (isExpressive) {
                        Text(
                            text = "NOW PLAYING",
                            color = Lime,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.4.sp,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { coords ->
                                if (coords.isAttached) {
                                    transitionState.expandedTitleBounds = coords.boundsInRoot()
                                }
                            }
                    ) {
                        Crossfade(targetState = track, animationSpec = tween(280), label = "trackTitle") { displayedTrack ->
                            MarqueeText(
                                text = displayedTrack.title,
                                modifier = Modifier.fillMaxWidth(),
                                style = androidx.compose.ui.text.TextStyle(
                                    fontFamily = activePlayerFont,
                                    fontSize = if (isExpressive) 30.sp else 27.sp,
                                    lineHeight = if (isExpressive) 35.sp else 32.sp,
                                    fontWeight = if (isExpressive) FontWeight.Bold else FontWeight.Medium
                                ),
                                color = TextPrimary,
                                textAlign = titleAlign,
                                speedDpPerSecond = if (isMarqueeActive) 42f else 0f
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { coords ->
                                if (coords.isAttached) {
                                    transitionState.expandedArtistBounds = coords.boundsInRoot()
                                }
                            }
                    ) {
                        MarqueeText(
                            text = track.artist,
                            modifier = Modifier.fillMaxWidth(),
                            style = androidx.compose.ui.text.TextStyle(
                                fontFamily = activePlayerFont,
                                fontSize = if (isExpressive) 16.sp else 15.sp,
                                fontWeight = FontWeight.Normal
                            ),
                            color = TextSecondary,
                            textAlign = titleAlign,
                            speedDpPerSecond = if (isMarqueeActive) 42f else 0f
                        )
                    }
                }

                currentDownload?.let { dl ->
                    val statusLabel = when (dl.status) {
                        com.geekify.android.data.local.DownloadEntity.STATUS_RUNNING ->
                            "Downloading ${(dl.progress * 100).toInt()}%..."
                        com.geekify.android.data.local.DownloadEntity.STATUS_QUEUED ->
                            "Queued for offline download..."
                        com.geekify.android.data.local.DownloadEntity.STATUS_PAUSED ->
                            "Download paused • Tap to resume"
                        com.geekify.android.data.local.DownloadEntity.STATUS_FAILED ->
                            "Download failed • Tap to retry"
                        com.geekify.android.data.local.DownloadEntity.STATUS_COMPLETED ->
                            "Available Offline"
                        else -> null
                    }
                    if (statusLabel != null) {
                        val badgeColor = when (dl.status) {
                            com.geekify.android.data.local.DownloadEntity.STATUS_COMPLETED -> Lime
                            com.geekify.android.data.local.DownloadEntity.STATUS_FAILED -> Color(0xFFF87171)
                            com.geekify.android.data.local.DownloadEntity.STATUS_PAUSED -> Color(0xFFFCD34D)
                            else -> TextSecondary
                        }
                        Text(
                            text = statusLabel,
                            color = badgeColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                                .clickable(
                                    enabled = dl.status == com.geekify.android.data.local.DownloadEntity.STATUS_FAILED ||
                                        dl.status == com.geekify.android.data.local.DownloadEntity.STATUS_PAUSED
                                ) {
                                    downloadManager.retry(track.videoId)
                                }
                                .graphicsLayer {
                                    alpha = (1f - transitionState.progress * 2.5f).coerceIn(0f, 1f)
                                }
                        )
                    }
                }

                state.error?.let { message ->
                    Text(
                        text = message,
                        color = Color(0xFFFCD34D),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .graphicsLayer {
                                alpha = (1f - transitionState.progress * 2.5f).coerceIn(0f, 1f)
                            }
                    )
                }

                Spacer(Modifier.height(18.dp))

                // ---- Fading three-line block: previous (dim) / up next (bright) / the one after (dim) ----
                val showUpNextBlock = playerCust.showUpNextPreview &&
                    playerCust.layoutStyle != com.geekify.android.customization.PlayerLayoutStyle.MINIMAL &&
                    playerCust.layoutStyle != com.geekify.android.customization.PlayerLayoutStyle.ARTWORK_FOCUSED
                if (showUpNextBlock) {
                    Box(
                        modifier = Modifier.graphicsLayer {
                            val p = transitionState.progress
                            alpha = (1f - p * 2.4f).coerceIn(0f, 1f)
                            translationY = p * 48.dp.toPx()
                            val s = lerp(1f, 0.90f, p)
                            scaleX = s
                            scaleY = s
                        }
                    ) {
                        UpNextLines(
                            previous = previousTrack?.title,
                            next = upcoming.getOrNull(0)?.title,
                            afterNext = upcoming.getOrNull(1)?.title,
                            onClick = { if (expandedControlsInteractive()) onQueueClick() }
                        )
                    }
                }

                Spacer(Modifier.weight(0.5f))

                // ---- Seek bar + times (repeat sits between them) ----
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            val p = transitionState.progress
                            alpha = (1f - p * 2.4f).coerceIn(0f, 1f)
                            translationY = p * 36.dp.toPx()
                            val s = lerp(1f, 0.92f, p)
                            scaleX = s
                            scaleY = s
                        }
                ) {
                    SeekSection(
                        position = viewModel.position,
                        isPlaying = state.isPlaying && isSeekWaveActive,
                        repeat = state.repeat,
                        showRepeatButton = playerCust.showRepeatButton,
                        onSeek = { if (expandedControlsInteractive()) viewModel.seek(it) },
                        onCycleRepeat = { if (expandedControlsInteractive()) viewModel.cycleRepeat() }
                    )
                }

                Spacer(Modifier.height(16.dp))

                // ---- Controls: shuffle, previous, shared play/pause, shared next, queue ----
                Row(
                    modifier = Modifier
                        .zIndex(9f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (playerCust.showShuffleButton) {
                        IconButton(
                            onClick = { if (expandedControlsInteractive()) viewModel.toggleShuffle() },
                            modifier = Modifier.graphicsLayer {
                                val p = transitionState.progress
                                alpha = (1f - p * 2.5f).coerceIn(0f, 1f)
                                val s = lerp(1f, 0.75f, p)
                                scaleX = s
                                scaleY = s
                            }
                        ) {
                            Icon(
                                GeekifyIcons.Shuffle,
                                contentDescription = "Shuffle",
                                tint = if (state.shuffle) Lime else TextPrimary,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    } else {
                        Spacer(Modifier.size(48.dp))
                    }

                    Box(
                        modifier = Modifier.graphicsLayer {
                            val p = transitionState.progress
                            alpha = (1f - p * 2.5f).coerceIn(0f, 1f)
                            val s = lerp(1f, 0.75f, p)
                            scaleX = s
                            scaleY = s
                        }
                    ) {
                        CircleIconButton(
                            icon = GeekifyIcons.SkipPrevious,
                            contentDescription = "Previous",
                            onClick = { if (expandedControlsInteractive()) viewModel.previous() },
                            size = 54.dp,
                            iconSize = 24.dp,
                            container = Color(0xFF211F28)
                        )
                    }

                    // Shared Play/Pause Button: 70dp Lime circle -> 44dp/38dp Mini-Player Lime circle
                    Box(
                        modifier = Modifier
                            .size(70.dp)
                            .onGloballyPositioned { coords ->
                                if (coords.isAttached) {
                                    transitionState.expandedPlayBounds = coords.boundsInRoot()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val p = transitionState.progress
                                    val tf = computeSharedTransform(
                                        sourceBounds = transitionState.expandedPlayBounds,
                                        destBounds = transitionState.miniPlayBounds,
                                        progress = p,
                                        fallbackScale = 44f / 70f,
                                        fallbackOffsetY = 0f
                                    )
                                    translationX = tf.translationX
                                    translationY = tf.translationY
                                    scaleX = tf.scaleX
                                    scaleY = tf.scaleY
                                }
                                .clip(if (playerCust.roundedPlayButton) CircleShape else RoundedCornerShape(22.dp))
                                .background(Lime)
                                .bouncyClickable(pressedScale = 0.92f) {
                                    if (expandedControlsInteractive()) viewModel.togglePlay()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (state.isBuffering) {
                                CircularProgressIndicator(
                                    color = OnAccent,
                                    modifier = Modifier.size(28.dp),
                                    strokeWidth = 3.dp
                                )
                            } else {
                                Crossfade(targetState = state.isPlaying, animationSpec = tween(180), label = "bigPlay") { playing ->
                                    Icon(
                                        imageVector = if (playing) GeekifyIcons.Pause else GeekifyIcons.Play,
                                        contentDescription = if (playing) "Pause" else "Play",
                                        tint = OnAccent,
                                        modifier = Modifier.size(30.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Shared Next Button: 54dp circle -> 40dp Mini-Player Next button
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .onGloballyPositioned { coords ->
                                if (coords.isAttached) {
                                    transitionState.expandedNextBounds = coords.boundsInRoot()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val p = transitionState.progress
                                    val hasMiniNext = !transitionState.miniIsInline && transitionState.miniNextBounds.width > 0f
                                    if (hasMiniNext) {
                                        val tf = computeSharedTransform(
                                            sourceBounds = transitionState.expandedNextBounds,
                                            destBounds = transitionState.miniNextBounds,
                                            progress = p,
                                            fallbackScale = 40f / 54f,
                                            fallbackOffsetY = 0f
                                        )
                                        translationX = tf.translationX
                                        translationY = tf.translationY
                                        scaleX = tf.scaleX
                                        scaleY = tf.scaleY
                                    } else {
                                        alpha = (1f - p * 2.5f).coerceIn(0f, 1f)
                                        val s = lerp(1f, 0.75f, p)
                                        scaleX = s
                                        scaleY = s
                                    }
                                }
                                .clip(CircleShape)
                                .bouncyClickable(pressedScale = 0.92f) {
                                    if (expandedControlsInteractive()) viewModel.next()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        val p = transitionState.progress
                                        alpha = (1f - p * 1.4f).coerceIn(0f, 1f)
                                    }
                                    .background(Color(0xFF211F28))
                            )
                            Icon(
                                imageVector = GeekifyIcons.SkipNext,
                                contentDescription = "Next",
                                tint = TextPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    if (playerCust.showQueueButton) {
                        IconButton(
                            onClick = { if (expandedControlsInteractive()) onQueueClick() },
                            modifier = Modifier.graphicsLayer {
                                val p = transitionState.progress
                                alpha = (1f - p * 2.5f).coerceIn(0f, 1f)
                                val s = lerp(1f, 0.75f, p)
                                scaleX = s
                                scaleY = s
                            }
                        ) {
                            Icon(GeekifyIcons.QueueMusic, contentDescription = "Queue", tint = TextPrimary, modifier = Modifier.size(26.dp))
                        }
                    } else {
                        Spacer(Modifier.size(48.dp))
                    }
                }

                Spacer(Modifier.height(20.dp))
            }
        }

        // ---- Shared Compact Title & Artist Overlay (travels along the same trajectory into NowPlayingBar) ----
        val miniTextRect = transitionState.miniTextBounds
        if (miniTextRect.width > 0f && miniTextRect.height > 0f) {
            val isInline = transitionState.miniIsInline
            val widthDp = with(density) { miniTextRect.width.toDp() }
            val heightDp = with(density) { miniTextRect.height.toDp() }

            Column(
                modifier = Modifier
                    .offset {
                        val root = transitionState.rootBounds
                        val localLeft = transitionState.miniTextBounds.left - root.left
                        val localTop = transitionState.miniTextBounds.top - root.top
                        IntOffset(localLeft.roundToInt(), localTop.roundToInt())
                    }
                    .size(width = widthDp, height = heightDp)
                    .graphicsLayer {
                        val p = transitionState.progress
                        val expRect = transitionState.expandedTextBounds
                        val mRect = transitionState.miniTextBounds
                        if (expRect.width > 0f && mRect.width > 0f) {
                            translationX = (expRect.center.x - mRect.center.x) * (1f - p)
                            translationY = (expRect.center.y - mRect.center.y) * (1f - p)
                        }
                        val s = lerp(1.36f, 1f, p)
                        scaleX = s
                        scaleY = s
                        alpha = ((p - 0.34f) / 0.50f).coerceIn(0f, 1f)
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
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(if (isInline) 1.dp else 2.dp))
                Text(
                    text = state.error ?: track.artist,
                    color = if (state.error != null) Color(0xFFFCD34D) else TextSecondary,
                    fontSize = if (isInline) 11.sp else 13.sp,
                    lineHeight = if (isInline) 14.sp else 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (showSleepTimer && transitionState.isFullyExpanded) {
            SleepTimerDialog(
                remainingMsFlow = viewModel.sleepTimerRemainingMs,
                onSelect = { minutes ->
                    viewModel.setSleepTimer(minutes)
                    showSleepTimer = false
                },
                onCancelTimer = {
                    viewModel.cancelSleepTimer()
                    showSleepTimer = false
                },
                onDismiss = { showSleepTimer = false },
                backdrop = sleepTimerBackdrop
            )
        }
    }
}

@Composable
private fun SleepTimerDialog(
    remainingMsFlow: kotlinx.coroutines.flow.StateFlow<Long?>,
    onSelect: (Int) -> Unit,
    onCancelTimer: () -> Unit,
    onDismiss: () -> Unit,
    backdrop: com.geekify.android.ui.glass.GlassBackdrop
) {
    val remainingMs by remainingMsFlow.collectAsStateWithLifecycle()
    GeekifyDialog(onDismissRequest = onDismiss, backdrop = backdrop) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(InkElevated),
                contentAlignment = Alignment.Center
            ) {
                Icon(GeekifyIcons.Timer, contentDescription = null, tint = Lime, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Sleep timer", color = TextPrimary, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = remainingMs?.let { "Active • ${formatSleepTimer(it)} remaining" } ?: "Stop playback automatically after a set time",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 2
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("Stop after", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))

        val options = listOf(5, 10, 15, 30, 45, 60, 90)
        options.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                row.forEach { minutes ->
                    DialogPillButton(
                        text = if (minutes >= 60) "${minutes / 60}h" + if (minutes % 60 == 0) "" else " ${minutes % 60}m" else "${minutes}m",
                        onClick = { onSelect(minutes) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
        }

        if (remainingMs != null) {
            Spacer(Modifier.height(4.dp))
            DialogPillButton(
                text = "Cancel timer",
                onClick = onCancelTimer,
                modifier = Modifier.fillMaxWidth(),
                style = DialogButtonStyle.Secondary
            )
        }
    }
}

private fun formatSleepTimer(ms: Long): String {
    val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
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
                            // The artwork must occupy the whole screen: a fixed-height blurred layer
                            // leaves a visible horizontal seam when the track artwork crossfades.
                            .fillMaxSize()
                            .blur(60.dp, BlurredEdgeTreatment.Unbounded)
                            .graphicsLayer {
                                scaleX = 1.18f
                                scaleY = 1.18f
                            }
                            .alpha(0.72f),
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

@Composable
private fun SeekSection(
    position: StateFlow<PlaybackPosition>,
    isPlaying: Boolean,
    repeat: RepeatMode,
    showRepeatButton: Boolean = true,
    onSeek: (Long) -> Unit,
    onCycleRepeat: () -> Unit
) {
    val pos by position.collectAsStateWithLifecycle()
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val totalDurationMs = pos.durationMs.coerceAtLeast(1L)
    val liveFraction = (pos.positionMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
    val fraction = if (isDragging) dragFraction else liveFraction
    val currentPositionMs = (fraction * totalDurationMs).toLong()
    val remainingMs = (totalDurationMs - currentPositionMs).coerceAtLeast(0L)

    SeekBar(
        fraction = fraction,
        onChange = {
            isDragging = true
            dragFraction = it
        },
        onChangeFinished = {
            onSeek((dragFraction * totalDurationMs).toLong())
            isDragging = false
        },
        modifier = Modifier.fillMaxWidth(),
        isPlaying = isPlaying
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(formatMs(currentPositionMs), color = TextSecondary, fontSize = 13.sp, modifier = Modifier.width(56.dp))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (showRepeatButton) {
                IconButton(onClick = onCycleRepeat, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = if (repeat == RepeatMode.ONE) GeekifyIcons.RepeatOne else GeekifyIcons.Repeat,
                        contentDescription = "Repeat",
                        tint = if (repeat != RepeatMode.OFF) Lime else TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
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
}
