package com.geekify.android.ui.player

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.geekify.android.customization.LocalCustomizationState
import com.geekify.android.customization.PlayerArtworkCropMode
import com.geekify.android.customization.PlayerArtworkPresentation
import com.geekify.android.customization.PlayerArtworkShape
import com.geekify.android.customization.PlayerBackgroundStyle
import com.geekify.android.customization.PlayerCustomizationConfig
import com.geekify.android.customization.PlayerLayoutStyle
import com.geekify.android.customization.ThemePaletteStyle
import com.geekify.android.data.local.DownloadEntity
import com.geekify.android.data.model.Track
import com.geekify.android.downloads.SongDownloadManager
import com.geekify.android.lyrics.LyricsLookupOutcome
import com.geekify.android.lyrics.LyricsRepository
import com.geekify.android.lyrics.ParsedLyrics
import com.geekify.android.player.PlaybackPosition
import com.geekify.android.player.RepeatMode as PlayerRepeatMode
import com.geekify.android.settings.LocalAppSettings
import com.geekify.android.ui.components.CircleIconButton
import com.geekify.android.ui.components.DialogButtonStyle
import com.geekify.android.ui.components.DialogPillButton
import com.geekify.android.ui.components.GeekifyDialog
import com.geekify.android.ui.components.MarqueeText
import com.geekify.android.ui.components.bestArtworkUrl
import com.geekify.android.ui.components.bouncyClickable
import com.geekify.android.ui.components.rememberArtColor
import com.geekify.android.ui.glass.glassBackdropSource
import com.geekify.android.ui.glass.rememberGlassBackdrop
import com.geekify.android.ui.glass.rememberReducedMotion
import com.geekify.android.ui.theme.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpandedPlayerScreen(
    viewModel: PlayerViewModel,
    transitionState: PlayerTransitionState,
    isQueueVisible: Boolean = false,
    onDismiss: () -> Unit,
    onQueueClick: () -> Unit,
    onNavigateToArtist: (String) -> Unit = {},
    onNavigateToAlbum: (String) -> Unit = {},
    onNavigateToEqualizer: () -> Unit = {},
    onNavigateToListenTogether: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val audioSettings by viewModel.audioSettings.collectAsStateWithLifecycle()
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

    val customizationViewModel: com.geekify.android.customization.CustomizationViewModel =
        org.koin.androidx.compose.koinViewModel()
    val customization = LocalCustomizationState.current
    val playerCust = customization.player
    val seekbarCust = customization.seekbar
    val gestureCust = customization.gestures
    val lyricsCust = customization.lyrics
    val themeCust = customization.theme
    val reducedMotion = rememberReducedMotion()
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current

    val lyricsTransition = rememberLyricsTransitionState()
    var isFullscreenLyrics by remember(lyricsCust.fullscreenDefault) { mutableStateOf(lyricsCust.fullscreenDefault) }
    var showLyricsOptions by remember { mutableStateOf(false) }
    var showMoreOptions by remember { mutableStateOf(false) }

    // Strict architectural isolation: if the player starts collapsing toward Mini-Player,
    // immediately settle Lyrics closed so the two shared transitions never compete.
    LaunchedEffect(transitionState.progress > 0.02f) {
        if (transitionState.progress > 0.02f && lyricsTransition.isVisible) {
            lyricsTransition.snapClosed()
        }
    }

    // Shared PendingSeekPosition across SeekSection and Artwork Double-Tap Seeking
    var sharedPendingSeek by remember(track.videoId) { mutableStateOf<PendingSeekPosition?>(null) }
    var artworkSeekFeedback by remember(track.videoId) { mutableStateOf<ArtworkSeekFeedback?>(null) }
    var artworkSeekFeedbackVisible by remember(track.videoId) { mutableStateOf(false) }
    var artworkSeekNonce by remember(track.videoId) { mutableIntStateOf(0) }

    LaunchedEffect(artworkSeekFeedback?.nonce) {
        if (artworkSeekFeedback == null) return@LaunchedEffect
        artworkSeekFeedbackVisible = true
        kotlinx.coroutines.delay(750L)
        artworkSeekFeedbackVisible = false
    }
    LaunchedEffect(gestureCust.artworkDoubleTapSkipEnabled) {
        if (!gestureCust.artworkDoubleTapSkipEnabled) {
            artworkSeekFeedbackVisible = false
            artworkSeekFeedback = null
        }
    }

    // Predictive Back / Android Back button integration:
    // If Lyrics is open, predictive back smoothly scrubs artwork & title back to Now Playing;
    // otherwise previews the existing Mini-Player shared collapse.
    PredictiveBackHandler(enabled = !transitionState.isFullyCollapsed && !isQueueVisible) { backEvents ->
        val closingLyrics = lyricsTransition.isVisible
        try {
            backEvents.collect { backEvent ->
                if (closingLyrics) {
                    lyricsTransition.onPredictiveBackDrag(backEvent.progress)
                } else {
                    val previewProgress = (backEvent.progress * 0.45f).coerceIn(0f, 0.85f)
                    transitionState.onDrag(previewProgress)
                }
            }
            if (closingLyrics) {
                lyricsTransition.close(reducedMotion = reducedMotion)
            } else {
                onDismiss()
            }
        } catch (_: CancellationException) {
            if (closingLyrics) {
                lyricsTransition.open(reducedMotion = reducedMotion)
            } else {
                transitionState.expand()
            }
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

    // Immersive player status-bar hiding
    DisposableEffect(playerCust.immersivePlayer, isExpandedActive, currentView) {
        val window = (currentView.context as? Activity)?.window
        val hide = playerCust.immersivePlayer && isExpandedActive && window != null
        if (hide) {
            val controller = WindowCompat.getInsetsController(window, currentView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
        }
        onDispose {
            if (hide) {
                WindowCompat.getInsetsController(window, currentView).show(WindowInsetsCompat.Type.statusBars())
            }
        }
    }

    val thumbUrl = track.thumbnails.bestArtworkUrl()
    val artColor by rememberArtColor(thumbUrl, fallback = InkElevated)
    val animatedArtColor by animateColorAsState(artColor, tween(550), label = "playerArtColor")

    // Dynamic accent harmonization when enabled or Content palette style is active
    val playerAccent = remember(animatedArtColor, Lime, themeCust.dynamicPlayerAccent, themeCust.paletteStyle) {
        if (themeCust.dynamicPlayerAccent || themeCust.paletteStyle == ThemePaletteStyle.CONTENT) {
            val boosted = androidx.compose.ui.graphics.lerp(animatedArtColor, Color.White, 0.45f)
            androidx.compose.ui.graphics.lerp(Lime, boosted, 0.35f)
        } else {
            Lime
        }
    }
    val onPlayerAccent = if (playerAccent.luminance() > 0.55f) Color(0xFF14110A) else Color.White

    // Check cellular/metered network for animated artwork power/data saving
    val isMeteredNetwork = remember(context, track.videoId, playerCust.disableAnimatedArtworkOnMobileData) {
        if (!playerCust.disableAnimatedArtworkOnMobileData) false
        else runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.isActiveNetworkMetered == true
        }.getOrDefault(false)
    }
    val allowAnimatedArtwork = playerCust.animatedArtworkEnabled && !isMeteredNetwork && !reducedMotion

    val downloadManager: SongDownloadManager = koinInject()
    val allDownloads by downloadManager.downloads.collectAsState(initial = emptyList())
    val currentDownload = remember(allDownloads, track.videoId) {
        allDownloads.firstOrNull { it.videoId == track.videoId }
    }

    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val screenHeightPx = with(density) { config.screenHeightDp.dp.toPx() }

    val artBaseScale by animateFloatAsState(
        targetValue = if (state.isPlaying || !allowAnimatedArtwork) 1f else 0.93f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "artBaseScale"
    )

    val isForegroundExpanded by remember {
        derivedStateOf { transitionState.progress < 0.25f && !transitionState.isDragging }
    }
    val isVinylLayout = playerCust.layoutStyle == PlayerLayoutStyle.VINYL_TONEARM
    val useVinyl = !lyricsTransition.isSettledOpen && isVinylLayout
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(viewModel, lifecycleOwner, isForegroundExpanded, useVinyl, allowAnimatedArtwork) {
        fun updateTonearmSync(started: Boolean) {
            val enabled = started && isForegroundExpanded && useVinyl
            viewModel.setTonearmSyncEnabled(enabled)
            viewModel.setReactiveArtworkMeterEnabled(started && isForegroundExpanded && state.isPlaying && allowAnimatedArtwork)
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
        updateTonearmSync(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            viewModel.setTonearmSyncEnabled(false)
            viewModel.setReactiveArtworkMeterEnabled(false)
        }
    }
    LaunchedEffect(state.isPlaying, isForegroundExpanded, allowAnimatedArtwork) {
        viewModel.setReactiveArtworkMeterEnabled(state.isPlaying && isForegroundExpanded && allowAnimatedArtwork)
    }
    LaunchedEffect(useVinyl, track.videoId, state.tonearmTransitionPending) {
        if (!useVinyl) {
            viewModel.setTonearmSyncEnabled(false)
            viewModel.completeTonearmTransition(track.videoId)
        }
    }

    val presentationFactor = when (playerCust.artworkPresentation) {
        PlayerArtworkPresentation.COMPACT -> 0.88f
        PlayerArtworkPresentation.IMMERSIVE -> 1.10f
        PlayerArtworkPresentation.STANDARD -> 1.0f
    }
    val layoutScaleFactor = when {
        playerCust.layoutStyle == PlayerLayoutStyle.MINIMAL -> 0.70f
        playerCust.layoutStyle == PlayerLayoutStyle.ARTWORK_FOCUSED -> 1.14f
        playerCust.layoutStyle == PlayerLayoutStyle.FULLSCREEN_ARTWORK -> 0.78f
        playerCust.layoutStyle == PlayerLayoutStyle.EXPRESSIVE -> 0.96f
        playerCust.layoutStyle == PlayerLayoutStyle.STANDARD -> 1.02f
        else -> 1.0f
    } * presentationFactor * playerCust.artworkScale.coerceIn(0.75f, 1.20f)
    val maxArtCap = minOf(config.screenWidthDp - 44f, config.screenHeightDp * 0.44f)
    val artSize = (minOf(config.screenWidthDp * 0.76f, config.screenHeightDp * 0.37f) * layoutScaleFactor)
        .coerceIn(160f, maxArtCap)
        .dp

    val upcoming: List<Track> = state.queue.drop(state.index + 1)
    val previousTrack: Track? = state.queue.getOrNull(state.index - 1)

    val overlayInteraction = remember { MutableInteractionSource() }
    val sleepTimerBackdrop = rememberGlassBackdrop()
    val expandedControlsInteractive: () -> Boolean = {
        transitionState.progress < 0.18f &&
            !transitionState.isDragging &&
            !lyricsTransition.targetOpen &&
            lyricsTransition.progress < 0.05f
    }
    val isMarqueeActive by remember(transitionState) {
        derivedStateOf { transitionState.progress < 0.02f }
    }
    val isSeekWaveActive by remember(transitionState) {
        derivedStateOf { transitionState.progress < 0.5f }
    }

    val clipPath = remember { Path() }

    val onDownloadClick: () -> Unit = {
        if (expandedControlsInteractive()) {
            when (currentDownload?.status) {
                DownloadEntity.STATUS_COMPLETED -> Unit
                DownloadEntity.STATUS_RUNNING, DownloadEntity.STATUS_QUEUED -> downloadManager.pause(track.videoId)
                DownloadEntity.STATUS_PAUSED, DownloadEntity.STATUS_FAILED -> downloadManager.retry(track.videoId)
                else -> downloadManager.enqueue(track)
            }
        }
    }

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

                val scrimAlpha = ((1f - p) * 0.50f).coerceIn(0f, 1f)
                if (scrimAlpha > 0.005f) {
                    drawRect(Color.Black.copy(alpha = scrimAlpha))
                }

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

                val rimAlpha = (p * (1f - ((p - 0.88f) / 0.12f).coerceIn(0f, 1f)) * 0.18f).coerceIn(0f, 1f)
                if (rimAlpha > 0.005f) {
                    drawPath(
                        path = clipPath,
                        color = Color.White.copy(alpha = rimAlpha),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
            }
            .pointerInput(screenHeightPx, gestureCust.swipeDownToCollapsePlayer, lyricsTransition.isVisible) {
                if (!gestureCust.swipeDownToCollapsePlayer || lyricsTransition.isVisible) return@pointerInput
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
        // ---- Dynamic / Customizable Background Layer (all 6 styles + Fullscreen Artwork) ----
        PlayerBackgroundLayer(
            thumbUrl = thumbUrl,
            artColor = animatedArtColor,
            playerCust = playerCust,
            isPlaying = state.isPlaying && !reducedMotion,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = transitionState.progress
                    alpha = (1f - ((p - 0.68f) / 0.32f).coerceIn(0f, 1f))
                }
        )

        val activePlayerFont = AppFont
        val layout = playerCust.layoutStyle
        val isMinimal = layout == PlayerLayoutStyle.MINIMAL
        val isExpressive = layout == PlayerLayoutStyle.EXPRESSIVE
        val isStandard = layout == PlayerLayoutStyle.STANDARD
        val isArtworkFocused = layout == PlayerLayoutStyle.ARTWORK_FOCUSED
        val isFullscreenArt = layout == PlayerLayoutStyle.FULLSCREEN_ARTWORK

        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = activePlayerFont)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // ---- Main Now Playing Composition (All 6 Layouts) ----
                // Kept mounted while Lyrics is closed or mid-transition so the EXACT SAME artwork
                // and song-title composables morph smoothly into the Lyrics header with zero reload.
                if (!lyricsTransition.isSettledOpen) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = if (isArtworkFocused) 18.dp else 22.dp)
                ) {
                    // ---- Top Navigation Bar: Back (left) · Now Playing (center) · MoreVert (right) ----
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .graphicsLayer {
                                val p = transitionState.progress
                                val lp = lyricsTransition.progress
                                alpha = ((1f - p * 2.6f).coerceIn(0f, 1f)) * ((1f - lp * 2.4f).coerceIn(0f, 1f))
                                translationY = p * 42.dp.toPx() - lp * 16.dp.toPx()
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val topBtnSize = if (playerCust.compactControls) 40.dp else 46.dp
                        CircleIconButton(
                            icon = GeekifyIcons.ArrowBack,
                            contentDescription = "Collapse",
                            onClick = { if (expandedControlsInteractive()) onDismiss() },
                            size = topBtnSize,
                            container = Color.White.copy(alpha = if (isMinimal) 0.12f else 0.18f),
                            tint = Color.White
                        )

                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = when {
                                    isExpressive -> "PLAYING FROM QUEUE"
                                    isMinimal -> ""
                                    else -> "Now Playing"
                                },
                                color = if (isExpressive) playerAccent else Color.White.copy(alpha = 0.90f),
                                fontSize = if (isExpressive) 11.sp else 16.sp,
                                fontWeight = if (isExpressive) FontWeight.Bold else FontWeight.Medium,
                                letterSpacing = if (isExpressive) 1.4.sp else 0.sp,
                                maxLines = 1
                            )
                            if (playerCust.showAlbumMetadata && !track.album.isNullOrBlank() && !isMinimal) {
                                Text(
                                    text = track.album,
                                    color = Color.White.copy(alpha = 0.62f),
                                    fontSize = 11.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Single vertical three-dot overflow button opening Metrolist-style PlayerMoreOptionsSheet
                        CircleIconButton(
                            icon = GeekifyIcons.MoreVert,
                            contentDescription = "More options",
                            onClick = { if (expandedControlsInteractive()) showMoreOptions = true },
                            size = topBtnSize,
                            container = Color.White.copy(alpha = if (isMinimal) 0.12f else 0.18f),
                            tint = Color.White
                        )
                    }

                    Spacer(Modifier.weight(if (isArtworkFocused) 0.35f else 0.7f))

                    // ---- Shared Element 1: Artwork / Vinyl ----
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
                                    val lp = lyricsTransition.progress
                                    if (p > 0.002f) {
                                        // Mini-Player <-> Now Playing transition (100% preserved)
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
                                    } else if (lp > 0.001f) {
                                        // Now Playing <-> Lyrics shared-artwork morph
                                        val tf = computeSharedTransform(
                                            sourceBounds = transitionState.expandedArtBounds,
                                            destBounds = lyricsTransition.lyricsArtBounds,
                                            progress = lp,
                                            fallbackScale = 0.16f,
                                            fallbackOffsetY = -screenHeightPx * 0.32f
                                        )
                                        translationX = tf.translationX
                                        translationY = tf.translationY
                                        scaleX = tf.scaleX
                                        scaleY = tf.scaleY
                                    }
                                }
                                .artworkSeekGestures(
                                    trackId = track.videoId,
                                    seekEnabled = gestureCust.artworkDoubleTapSkipEnabled && expandedControlsInteractive(),
                                    stepSeconds = gestureCust.artworkSkipDurationSeconds,
                                    progressive = gestureCust.artworkProgressiveSkipEnabled,
                                    onSeekDelta = { deltaMs ->
                                        if (!expandedControlsInteractive()) return@artworkSeekGestures
                                        val previousPos = viewModel.position.value.positionMs
                                        val basePos = sharedPendingSeek?.targetMs ?: previousPos
                                        val targetMs = viewModel.seekBy(deltaMs, basePositionMs = basePos)
                                        if (targetMs != null) {
                                            sharedPendingSeek = PendingSeekPosition(
                                                targetMs = targetMs,
                                                previousPositionMs = previousPos
                                            )
                                            if (gestureCust.hapticFeedback) {
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                            artworkSeekNonce++
                                            artworkSeekFeedback = ArtworkSeekFeedback(
                                                deltaMs = deltaMs,
                                                nonce = artworkSeekNonce
                                            )
                                        }
                                    }
                                )
                        ) {
                            key(playerCust.layoutStyle) {
                                if (useVinyl) {
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
                                } else if (isFullscreenArt) {
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
                                                        if (dragX <= -thresholdPx) {
                                                            if (gestureCust.hapticFeedback) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                            viewModel.next()
                                                        } else if (dragX >= thresholdPx) {
                                                            if (gestureCust.hapticFeedback) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                            viewModel.previous()
                                                        }
                                                        dragX = 0f
                                                    }
                                                )
                                            }
                                    )
                                } else {
                                    val effectiveShape = if (playerCust.artworkShape == PlayerArtworkShape.VINYL_DISC) {
                                        PlayerArtworkShape.ROUNDED
                                    } else {
                                        playerCust.artworkShape
                                    }
                                    val coverShape = when (effectiveShape) {
                                        PlayerArtworkShape.CIRCLE, PlayerArtworkShape.VINYL_DISC -> CircleShape
                                        PlayerArtworkShape.SQUARE -> RoundedCornerShape(4.dp)
                                        PlayerArtworkShape.ROUNDED -> RoundedCornerShape(
                                            when {
                                                isExpressive -> 24.dp
                                                isMinimal -> 20.dp
                                                isArtworkFocused -> 26.dp
                                                else -> 28.dp
                                            }
                                        )
                                    }
                                    val contentScale = if (playerCust.artworkCropMode == PlayerArtworkCropMode.FIT) {
                                        ContentScale.Fit
                                    } else {
                                        ContentScale.Crop
                                    }

                                    val artworkBoxContent: @Composable (Track) -> Unit = { displayTrack ->
                                        val displayUrl = displayTrack.thumbnails.bestArtworkUrl()
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .graphicsLayer {
                                                    scaleX = artBaseScale
                                                    scaleY = artBaseScale
                                                }
                                                .shadow(
                                                    elevation = if (isMinimal) 8.dp else 22.dp,
                                                    shape = coverShape,
                                                    ambientColor = animatedArtColor,
                                                    spotColor = animatedArtColor
                                                )
                                                .clip(coverShape)
                                                .background(InkElevated)
                                                .then(
                                                    if (isExpressive) {
                                                        Modifier.border(1.5.dp, playerAccent.copy(alpha = 0.45f), coverShape)
                                                    } else Modifier
                                                )
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
                                                            if (dragX <= -thresholdPx) {
                                                                if (gestureCust.hapticFeedback) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                viewModel.next()
                                                            } else if (dragX >= thresholdPx) {
                                                                if (gestureCust.hapticFeedback) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                viewModel.previous()
                                                            }
                                                            dragX = 0f
                                                        }
                                                    )
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (displayUrl != null) {
                                                AsyncImage(
                                                    model = displayUrl,
                                                    contentDescription = displayTrack.title,
                                                    contentScale = contentScale,
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

                                    if (playerCust.artworkTransitions && !reducedMotion) {
                                        AnimatedContent(
                                            targetState = track,
                                            transitionSpec = {
                                                val dir = if (lastSkipDirection < 0) -1 else 1
                                                (slideInHorizontally(tween(300)) { it / 3 * dir } + fadeIn(tween(260)))
                                                    .togetherWith(slideOutHorizontally(tween(260)) { -it / 3 * dir } + fadeOut(tween(200)))
                                            },
                                            label = "playerArtTransition"
                                        ) { displayed ->
                                            artworkBoxContent(displayed)
                                        }
                                    } else {
                                        artworkBoxContent(track)
                                    }
                                }
                            }

                            // Animated Metrolist-style double-tap seek feedback overlay across all 6 layouts
                            ArtworkSeekFeedbackOverlay(
                                feedback = artworkSeekFeedback,
                                visible = artworkSeekFeedbackVisible && transitionState.progress < 0.12f,
                                accentColor = playerAccent,
                                modifier = Modifier.matchParentSize()
                            )
                        }
                    }

                    Spacer(Modifier.weight(if (isArtworkFocused) 0.35f else 0.55f))

                    // ---- Optional Inline Live Lyric Preview Line ----
                    if (lyricsCust.showLyricsOnPlayer && !isMinimal) {
                        InlineLiveLyricBanner(
                            track = track,
                            positionFlow = viewModel.position,
                            globalOffsetMs = lyricsCust.globalOffsetMs,
                            accentColor = playerAccent,
                            onClick = { if (expandedControlsInteractive() && !lyricsTransition.targetOpen) lyricsTransition.open(reducedMotion) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp)
                                .graphicsLayer {
                                    val lp = lyricsTransition.progress
                                    alpha = ((1f - transitionState.progress * 2.5f).coerceIn(0f, 1f)) *
                                        ((1f - lp * 2.6f).coerceIn(0f, 1f))
                                }
                        )
                    }

                    // ---- Shared Element 2: Title / Artist (clean, NO duplicate heart here) ----
                    val leftAlignedMeta = isExpressive || isStandard || isArtworkFocused || isFullscreenArt
                    val titleAlign = if (leftAlignedMeta) TextAlign.Start else TextAlign.Center
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
                                val lp = lyricsTransition.progress
                                val src = transitionState.expandedTextBounds
                                if (p > 0.002f) {
                                    // Mini-Player <-> Now Playing (100% preserved)
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
                                } else if (lp > 0.001f) {
                                    // Now Playing <-> Lyrics shared title & artist morph
                                    val dst = lyricsTransition.lyricsTextBounds
                                    if (src.width > 0f && dst.width > 0f) {
                                        translationX = (dst.center.x - src.center.x) * lp
                                        translationY = (dst.center.y - src.center.y) * lp
                                    } else {
                                        translationY = -lp * screenHeightPx * 0.42f
                                    }
                                    val s = lerp(1f, 0.72f, lp)
                                    scaleX = s
                                    scaleY = s
                                    alpha = (1f - ((lp - 0.52f) / 0.34f)).coerceIn(0f, 1f)
                                }
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { coords ->
                                    if (coords.isAttached) {
                                        transitionState.expandedTitleBounds = coords.boundsInRoot()
                                    }
                                }
                        ) {
                            Crossfade(targetState = track, animationSpec = tween(260), label = "trackTitle") { displayedTrack ->
                                MarqueeText(
                                    text = displayedTrack.title,
                                    modifier = Modifier.fillMaxWidth(),
                                    style = androidx.compose.ui.text.TextStyle(
                                        fontFamily = activePlayerFont,
                                        fontSize = when {
                                            isExpressive -> 30.sp
                                            isMinimal -> 24.sp
                                            isArtworkFocused -> 23.sp
                                            else -> 26.sp
                                        },
                                        lineHeight = if (isExpressive) 35.sp else 31.sp,
                                        fontWeight = if (isExpressive) FontWeight.Bold else FontWeight.SemiBold
                                    ),
                                    color = Color.White,
                                    textAlign = titleAlign,
                                    speedDpPerSecond = if (isMarqueeActive) 42f else 0f
                                )
                            }
                        }
                        Spacer(Modifier.height(3.dp))
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
                                color = Color.White.copy(alpha = 0.72f),
                                textAlign = titleAlign,
                                speedDpPerSecond = if (isMarqueeActive) 42f else 0f
                            )
                        }
                    }

                    // Download status pill
                    currentDownload?.let { dl ->
                        val statusLabel = when (dl.status) {
                            DownloadEntity.STATUS_RUNNING -> "Downloading ${(dl.progress * 100).toInt()}%…"
                            DownloadEntity.STATUS_QUEUED -> "Queued for offline download…"
                            DownloadEntity.STATUS_PAUSED -> "Download paused • Tap to resume"
                            DownloadEntity.STATUS_FAILED -> "Download failed • Tap to retry"
                            DownloadEntity.STATUS_COMPLETED -> "Available Offline"
                            else -> null
                        }
                        if (statusLabel != null) {
                            val badgeColor = when (dl.status) {
                                DownloadEntity.STATUS_COMPLETED -> playerAccent
                                DownloadEntity.STATUS_FAILED -> Color(0xFFF87171)
                                DownloadEntity.STATUS_PAUSED -> Color(0xFFFCD34D)
                                else -> TextSecondary
                            }
                            Text(
                                text = statusLabel,
                                color = badgeColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = titleAlign,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 5.dp)
                                    .clickable(
                                        enabled = dl.status == DownloadEntity.STATUS_FAILED || dl.status == DownloadEntity.STATUS_PAUSED
                                    ) { downloadManager.retry(track.videoId) }
                                    .graphicsLayer {
                                        val p = transitionState.progress
                                        val lp = lyricsTransition.progress
                                        alpha = ((1f - p * 2.5f).coerceIn(0f, 1f)) * ((1f - lp * 2.4f).coerceIn(0f, 1f))
                                    }
                            )
                        }
                    }

                    state.error?.let { message ->
                        Text(
                            text = message,
                            color = Color(0xFFFCD34D),
                            fontSize = 12.sp,
                            textAlign = titleAlign,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .graphicsLayer {
                                    val p = transitionState.progress
                                    val lp = lyricsTransition.progress
                                    alpha = ((1f - p * 2.5f).coerceIn(0f, 1f)) * ((1f - lp * 2.4f).coerceIn(0f, 1f))
                                }
                        )
                    }

                    Spacer(Modifier.height(if (playerCust.compactControls) 10.dp else 14.dp))

                    // ---- Up Next Preview Block ----
                    val showUpNextBlock = playerCust.showUpNextPreview &&
                        !isMinimal &&
                        !isArtworkFocused
                    if (showUpNextBlock) {
                        Box(
                            modifier = Modifier.graphicsLayer {
                                val p = transitionState.progress
                                val lp = lyricsTransition.progress
                                alpha = ((1f - p * 2.4f).coerceIn(0f, 1f)) * ((1f - lp * 2.4f).coerceIn(0f, 1f))
                                translationY = p * 48.dp.toPx() + lp * 16.dp.toPx()
                                val s = lerp(1f, 0.90f, p)
                                scaleX = s
                                scaleY = s
                            }
                        ) {
                            UpNextLines(
                                previous = previousTrack?.title,
                                next = upcoming.getOrNull(0)?.title,
                                afterNext = upcoming.getOrNull(1)?.title,
                                compact = playerCust.compactControls || isStandard || isExpressive,
                                onClick = { if (expandedControlsInteractive()) onQueueClick() }
                            )
                        }
                    }

                    Spacer(Modifier.weight(0.45f))

                    // ---- Customizable Progress Bar + Elapsed/Remaining Timers ----
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                val p = transitionState.progress
                                val lp = lyricsTransition.progress
                                alpha = ((1f - p * 2.4f).coerceIn(0f, 1f)) * ((1f - lp * 2.4f).coerceIn(0f, 1f))
                                translationY = p * 36.dp.toPx() + lp * 14.dp.toPx()
                                val s = lerp(1f, 0.92f, p)
                                scaleX = s
                                scaleY = s
                            }
                    ) {
                        SeekSection(
                            trackId = track.videoId,
                            position = viewModel.position,
                            isPlaying = state.isPlaying,
                            isBuffering = state.isBuffering,
                            playbackSpeed = audioSettings.speed,
                            wavePlaying = state.isPlaying && isSeekWaveActive,
                            repeat = state.repeat,
                            playerCust = playerCust,
                            seekbarCust = seekbarCust,
                            activeColor = playerAccent,
                            secondaryAccentColor = Lilac,
                            pendingSeek = sharedPendingSeek,
                            onPendingSeekChange = { sharedPendingSeek = it },
                            onSeek = { if (expandedControlsInteractive()) viewModel.seek(it) },
                            onCycleRepeat = { if (expandedControlsInteractive()) viewModel.cycleRepeat() }
                        )
                    }

                    Spacer(Modifier.height(if (playerCust.compactControls) 10.dp else 14.dp))

                    // ---- Main Transport Controls Row: Shuffle → Previous → Play/Pause → Next → Like ----
                    val playBtnSize = if (playerCust.compactControls) 60.dp else 70.dp
                    val skipBtnSize = if (playerCust.compactControls) 46.dp else 54.dp
                    val playShape = if (playerCust.roundedPlayButton) CircleShape else RoundedCornerShape(20.dp)

                    Row(
                        modifier = Modifier
                            .zIndex(9f)
                            .fillMaxWidth(),
                        horizontalArrangement = if (isMinimal) Arrangement.SpaceEvenly else Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!isMinimal) {
                            if (playerCust.showShuffleButton) {
                                IconButton(
                                    onClick = { if (expandedControlsInteractive()) viewModel.toggleShuffle() },
                                    modifier = Modifier.graphicsLayer {
                                        val p = transitionState.progress
                                        val lp = lyricsTransition.progress
                                        alpha = ((1f - p * 2.5f).coerceIn(0f, 1f)) * ((1f - lp * 2.4f).coerceIn(0f, 1f))
                                        val s = lerp(1f, 0.75f, p)
                                        scaleX = s
                                        scaleY = s
                                    }
                                ) {
                                    Icon(
                                        GeekifyIcons.Shuffle,
                                        contentDescription = "Shuffle",
                                        tint = if (state.shuffle) playerAccent else Color.White,
                                        modifier = Modifier.size(if (playerCust.compactControls) 22.dp else 25.dp)
                                    )
                                }
                            } else {
                                Spacer(Modifier.size(44.dp))
                            }
                        }

                        Box(
                            modifier = Modifier.graphicsLayer {
                                val p = transitionState.progress
                                val lp = lyricsTransition.progress
                                alpha = ((1f - p * 2.5f).coerceIn(0f, 1f)) * ((1f - lp * 2.4f).coerceIn(0f, 1f))
                                val s = lerp(1f, 0.75f, p)
                                scaleX = s
                                scaleY = s
                            }
                        ) {
                            CircleIconButton(
                                icon = GeekifyIcons.SkipPrevious,
                                contentDescription = "Previous",
                                onClick = { if (expandedControlsInteractive()) viewModel.previous() },
                                size = skipBtnSize,
                                iconSize = if (playerCust.compactControls) 22.dp else 24.dp,
                                container = if (isMinimal) Color.Transparent else Color.White.copy(alpha = 0.14f),
                                tint = Color.White
                            )
                        }

                        // Shared Play/Pause Button
                        Box(
                            modifier = Modifier
                                .size(playBtnSize)
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
                                        val lp = lyricsTransition.progress
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
                                        alpha = (1f - lp * 2.4f).coerceIn(0f, 1f)
                                    }
                                    .clip(playShape)
                                    .background(playerAccent)
                                    .bouncyClickable(pressedScale = 0.92f) {
                                        if (expandedControlsInteractive()) viewModel.togglePlay()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (state.isBuffering) {
                                    CircularProgressIndicator(
                                        color = onPlayerAccent,
                                        modifier = Modifier.size(26.dp),
                                        strokeWidth = 3.dp
                                    )
                                } else {
                                    Crossfade(targetState = state.isPlaying, animationSpec = tween(180), label = "bigPlay") { playing ->
                                        Icon(
                                            imageVector = if (playing) GeekifyIcons.Pause else GeekifyIcons.Play,
                                            contentDescription = if (playing) "Pause" else "Play",
                                            tint = onPlayerAccent,
                                            modifier = Modifier.size(if (playerCust.compactControls) 26.dp else 30.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Shared Next Button
                        Box(
                            modifier = Modifier
                                .size(skipBtnSize)
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
                                        val lp = lyricsTransition.progress
                                        val lyricsFade = (1f - lp * 2.4f).coerceIn(0f, 1f)
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
                                            alpha = lyricsFade
                                        } else {
                                            alpha = ((1f - p * 2.5f).coerceIn(0f, 1f)) * lyricsFade
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
                                if (!isMinimal) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .graphicsLayer {
                                                val p = transitionState.progress
                                                alpha = (1f - p * 1.4f).coerceIn(0f, 1f)
                                            }
                                            .background(Color.White.copy(alpha = 0.14f))
                                    )
                                }
                                Icon(
                                    imageVector = GeekifyIcons.SkipNext,
                                    contentDescription = "Next",
                                    tint = Color.White,
                                    modifier = Modifier.size(if (playerCust.compactControls) 22.dp else 24.dp)
                                )
                            }
                        }

                        // Like / Favorite button immediately next to Next Track (NO queue icon here)
                        if (!isMinimal) {
                            if (playerCust.showLikeButton) {
                                LikeButton(
                                    liked = isLiked,
                                    onClick = { if (expandedControlsInteractive()) viewModel.toggleLikeCurrent() },
                                    size = if (playerCust.compactControls) 22.dp else 25.dp,
                                    activeColor = playerAccent,
                                    modifier = Modifier.graphicsLayer {
                                        val p = transitionState.progress
                                        val lp = lyricsTransition.progress
                                        alpha = ((1f - p * 2.5f).coerceIn(0f, 1f)) * ((1f - lp * 2.4f).coerceIn(0f, 1f))
                                        val s = lerp(1f, 0.75f, p)
                                        scaleX = s
                                        scaleY = s
                                    }
                                )
                            } else {
                                Spacer(Modifier.size(44.dp))
                            }
                        }
                    }

                    // ---- Bottom Action Pill: Lyrics · Timer · Up Next (ONLY queue icon on the screen!) ----
                    Spacer(Modifier.height(if (playerCust.compactControls) 12.dp else 16.dp))
                    PlayerBottomActionPill(
                        playerCust = playerCust,
                        showLyricsOverlay = false,
                        hasActiveSleepTimer = hasActiveSleepTimer,
                        accent = playerAccent,
                        onToggleLyrics = { if (expandedControlsInteractive()) lyricsTransition.open(reducedMotion) },
                        onOpenTimer = { if (expandedControlsInteractive()) showSleepTimer = true },
                        onOpenQueue = { if (expandedControlsInteractive()) onQueueClick() },
                        modifier = Modifier.graphicsLayer {
                            val p = transitionState.progress
                            val lp = lyricsTransition.progress
                            alpha = ((1f - p * 2.8f).coerceIn(0f, 1f)) * ((1f - lp * 2.2f).coerceIn(0f, 1f))
                        }
                    )

                    Spacer(Modifier.height(14.dp))
                }
                }

                // ---- Metrolist-Style Full-Viewport Borderless Lyrics Layer ----
                // Mounts immediately when opening begins so lyrics sync & initial active-line scroll
                // initialize right away while the shared artwork & title glide smoothly into place.
                if (lyricsTransition.isVisible) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(20f)
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .padding(horizontal = 18.dp)
                    ) {
                        // Compact track header bar with shared-bounds anchors + Lyrics Options + Close
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (thumbUrl != null) {
                                AsyncImage(
                                    model = thumbUrl,
                                    contentDescription = track.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .onGloballyPositioned { coords ->
                                            if (coords.isAttached) {
                                                lyricsTransition.lyricsArtBounds = coords.boundsInRoot()
                                            }
                                        }
                                        .graphicsLayer {
                                            // Hidden while the morphing Now Playing artwork is flying in;
                                            // hands off cleanly at exact sub-pixel alignment (zero duplicates).
                                            alpha = if (lyricsTransition.isSettledOpen) 1f else 0f
                                        }
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { lyricsTransition.close(reducedMotion) }
                                )
                                Spacer(Modifier.width(12.dp))
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .onGloballyPositioned { coords ->
                                        if (coords.isAttached) {
                                            lyricsTransition.lyricsTextBounds = coords.boundsInRoot()
                                        }
                                    }
                                    .graphicsLayer {
                                        val lp = lyricsTransition.progress
                                        alpha = ((lp - 0.56f) / 0.36f).coerceIn(0f, 1f)
                                    }
                                    .clickable { lyricsTransition.close(reducedMotion) }
                            ) {
                                Text(
                                    text = track.title,
                                    color = Color.White,
                                    fontSize = 15.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = track.artist,
                                    color = Color.White.copy(alpha = 0.70f),
                                    fontSize = 12.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row(
                                modifier = Modifier.graphicsLayer {
                                    val lp = lyricsTransition.progress
                                    alpha = ((lp - 0.35f) / 0.65f).coerceIn(0f, 1f)
                                }
                            ) {
                                IconButton(onClick = { showLyricsOptions = true }) {
                                    Icon(
                                        imageVector = GeekifyIcons.Sliders,
                                        contentDescription = "Lyrics options",
                                        tint = Color.White,
                                        modifier = Modifier.size(21.dp)
                                    )
                                }
                                IconButton(onClick = { lyricsTransition.close(reducedMotion) }) {
                                    Icon(
                                        imageVector = GeekifyIcons.Close,
                                        contentDescription = "Close lyrics",
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }

                        // Full available viewport for borderless synchronized lyrics
                        SynchronizedLyricsView(
                            track = track,
                            positionFlow = viewModel.position,
                            config = lyricsCust,
                            onSeekTo = { viewModel.seek(it) },
                            isPlaying = state.isPlaying,
                            isBuffering = state.isBuffering,
                            playbackSpeed = audioSettings.speed,
                            isFullscreen = isFullscreenLyrics,
                            onToggleFullscreen = { isFullscreenLyrics = !isFullscreenLyrics },
                            showOptionsSheet = showLyricsOptions,
                            onDismissOptionsSheet = { showLyricsOptions = false },
                            onSetShowTranslation = { enabled ->
                                customizationViewModel.setLyricsToggles(showTranslation = enabled)
                            },
                            accentOverride = playerAccent,
                            playerBackgroundStyle = playerCust.backgroundStyle,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .graphicsLayer {
                                    val lp = lyricsTransition.progress
                                    alpha = ((lp - 0.14f) / 0.86f).coerceIn(0f, 1f)
                                    translationY = (1f - lp) * 26.dp.toPx()
                                }
                        )

                        Spacer(Modifier.height(6.dp))

                        // Playback progress bar & compact controls
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    val lp = lyricsTransition.progress
                                    alpha = ((lp - 0.22f) / 0.78f).coerceIn(0f, 1f)
                                    translationY = (1f - lp) * 18.dp.toPx()
                                }
                        ) {
                            SeekSection(
                                trackId = track.videoId,
                                position = viewModel.position,
                                isPlaying = state.isPlaying,
                                isBuffering = state.isBuffering,
                                playbackSpeed = audioSettings.speed,
                                wavePlaying = state.isPlaying,
                                repeat = state.repeat,
                                playerCust = playerCust,
                                seekbarCust = seekbarCust,
                                activeColor = playerAccent,
                                secondaryAccentColor = Lilac,
                                pendingSeek = sharedPendingSeek,
                                onPendingSeekChange = { sharedPendingSeek = it },
                                onSeek = { viewModel.seek(it) },
                                onCycleRepeat = { viewModel.cycleRepeat() }
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp, bottom = if (isFullscreenLyrics) 10.dp else 8.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = { viewModel.previous() }) {
                                    Icon(GeekifyIcons.SkipPrevious, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(26.dp))
                                }
                                Box(
                                    modifier = Modifier
                                        .size(54.dp)
                                        .clip(if (playerCust.roundedPlayButton) CircleShape else RoundedCornerShape(16.dp))
                                        .background(playerAccent)
                                        .bouncyClickable(pressedScale = 0.92f) { viewModel.togglePlay() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (state.isPlaying) GeekifyIcons.Pause else GeekifyIcons.Play,
                                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                                        tint = onPlayerAccent,
                                        modifier = Modifier.size(25.dp)
                                    )
                                }
                                IconButton(onClick = { viewModel.next() }) {
                                    Icon(GeekifyIcons.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(26.dp))
                                }
                            }

                            if (!isFullscreenLyrics) {
                                PlayerBottomActionPill(
                                    playerCust = playerCust,
                                    showLyricsOverlay = true,
                                    hasActiveSleepTimer = hasActiveSleepTimer,
                                    accent = playerAccent,
                                    onToggleLyrics = { lyricsTransition.close(reducedMotion) },
                                    onOpenTimer = { showSleepTimer = true },
                                    onOpenQueue = onQueueClick,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- Shared Compact Title & Artist Overlay (travels smoothly into NowPlayingBar) ----
        val miniTextRect = transitionState.miniTextBounds
        if (miniTextRect.width > 0f && miniTextRect.height > 0f && transitionState.progress > 0.01f) {
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

        if (showMoreOptions && transitionState.isFullyExpanded) {
            PlayerMoreOptionsSheet(
                viewModel = viewModel,
                accentColor = playerAccent,
                onDismiss = { showMoreOptions = false },
                onNavigateToArtist = { artistId ->
                    showMoreOptions = false
                    onDismiss()
                    onNavigateToArtist(artistId)
                },
                onNavigateToAlbum = { albumId ->
                    showMoreOptions = false
                    onDismiss()
                    onNavigateToAlbum(albumId)
                },
                onNavigateToEqualizer = {
                    showMoreOptions = false
                    onDismiss()
                    onNavigateToEqualizer()
                },
                onNavigateToListenTogether = {
                    showMoreOptions = false
                    onDismiss()
                    onNavigateToListenTogether()
                }
            )
        }
    }
}

/**
 * Clean three-action Metrolist-inspired bottom pill: Lyrics · Timer · Up Next.
 * Up Next is the single authoritative Queue entry point on the expanded Now Playing screen.
 */
@Composable
private fun PlayerBottomActionPill(
    playerCust: PlayerCustomizationConfig,
    showLyricsOverlay: Boolean,
    hasActiveSleepTimer: Boolean,
    accent: Color,
    onToggleLyrics: () -> Unit,
    onOpenTimer: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!playerCust.showLyricsButton && !playerCust.showSleepTimerButton && !playerCust.showQueueButton) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(26.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (playerCust.showLyricsButton) {
            PlayerActionPill(
                icon = GeekifyIcons.Lyrics,
                label = "Lyrics",
                active = showLyricsOverlay,
                accent = accent,
                onClick = onToggleLyrics
            )
        }
        if (playerCust.showSleepTimerButton) {
            PlayerActionPill(
                icon = GeekifyIcons.Timer,
                label = "Timer",
                active = hasActiveSleepTimer,
                accent = accent,
                onClick = onOpenTimer
            )
        }
        if (playerCust.showQueueButton) {
            PlayerActionPill(
                icon = GeekifyIcons.QueueMusic,
                label = "Up Next",
                active = false,
                accent = accent,
                onClick = onOpenQueue
            )
        }
    }
}

@Composable
private fun PlayerActionPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) accent.copy(alpha = 0.22f) else Color.Transparent)
            .bouncyClickable(pressedScale = 0.94f, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) accent else Color.White.copy(alpha = 0.88f),
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = label,
            color = if (active) accent else Color.White.copy(alpha = 0.88f),
            fontSize = 12.5.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun PlayerDownloadButton(
    download: DownloadEntity?,
    size: androidx.compose.ui.unit.Dp,
    accent: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.18f))
            .bouncyClickable(pressedScale = 0.92f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        when (download?.status) {
            DownloadEntity.STATUS_RUNNING, DownloadEntity.STATUS_QUEUED -> {
                val pct = download.progress.coerceIn(0f, 1f)
                if (pct > 0.02f) {
                    CircularProgressIndicator(progress = { pct }, color = accent, modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                } else {
                    CircularProgressIndicator(color = accent, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                }
            }
            DownloadEntity.STATUS_COMPLETED -> {
                Icon(GeekifyIcons.CloudDone, contentDescription = "Downloaded offline", tint = accent, modifier = Modifier.size(20.dp))
            }
            DownloadEntity.STATUS_FAILED, DownloadEntity.STATUS_PAUSED -> {
                Icon(GeekifyIcons.Download, contentDescription = "Retry download", tint = Color(0xFFFCD34D), modifier = Modifier.size(20.dp))
            }
            else -> {
                Icon(GeekifyIcons.Download, contentDescription = "Download song", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun InlineLiveLyricBanner(
    track: Track,
    positionFlow: StateFlow<PlaybackPosition>,
    globalOffsetMs: Long,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lyricsRepository: LyricsRepository = koinInject()
    val position by positionFlow.collectAsStateWithLifecycle()
    var parsed by remember(track.videoId) { mutableStateOf<ParsedLyrics?>(null) }

    LaunchedEffect(track.videoId) {
        val result = runCatching {
            lyricsRepository.getOrFetchOutcome(
                videoId = track.videoId,
                title = track.title,
                artist = track.artist,
                durationSeconds = track.durationSeconds,
                durationMs = position.durationMs,
                album = track.album
            )
        }.getOrNull()
        parsed = (result as? LyricsLookupOutcome.Success)?.lyrics
    }

    val synced = parsed?.takeIf { it.isSynced } ?: return
    val effective = synced.copy(offsetMs = synced.offsetMs + globalOffsetMs)
    val idx = effective.activeLineIndex(position.positionMs)
    val lineText = effective.lines.getOrNull(idx)?.text?.takeIf { it.isNotBlank() } ?: return

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = lineText,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(180)) },
            label = "inlineLyricLine"
        ) { text ->
            Text(
                text = text,
                color = accentColor,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun PlayerBackgroundLayer(
    thumbUrl: String?,
    artColor: Color,
    playerCust: PlayerCustomizationConfig,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val baseBgColor = when (playerCust.backgroundStyle) {
        PlayerBackgroundStyle.AMOLED_PITCH -> Color.Black
        else -> if (ActiveThemePalette.isDark) InkBackground else Color(0xFF12101A)
    }

    Box(modifier = modifier.background(baseBgColor)) {
        when {
            playerCust.layoutStyle == PlayerLayoutStyle.FULLSCREEN_ARTWORK && thumbUrl != null -> {
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
                                0.0f to Color.Black.copy(alpha = 0.42f),
                                0.42f to Color.Black.copy(alpha = 0.30f),
                                0.72f to Color.Black.copy(alpha = 0.78f),
                                1.0f to baseBgColor.copy(alpha = 0.97f)
                            )
                        )
                )
            }

            playerCust.backgroundStyle == PlayerBackgroundStyle.BLURRED_ARTWORK -> {
                PlayerBackdrop(thumbUrl = thumbUrl, tint = artColor, baseBg = baseBgColor)
            }

            playerCust.backgroundStyle == PlayerBackgroundStyle.DYNAMIC_GRADIENT -> {
                val deepShade = androidx.compose.ui.graphics.lerp(artColor, Color.Black, 0.55f)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.0f to artColor.copy(alpha = 0.85f),
                                0.55f to deepShade,
                                1.0f to baseBgColor
                            )
                        )
                )
            }

            playerCust.backgroundStyle == PlayerBackgroundStyle.ANIMATED_GRADIENT -> {
                val phase by if (isPlaying) {
                    rememberInfiniteTransition(label = "animBg").animateFloat(
                        initialValue = 0f,
                        targetValue = (2f * PI).toFloat(),
                        animationSpec = infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Restart),
                        label = "animBgPhase"
                    )
                } else {
                    remember { mutableFloatStateOf(0f) }
                }
                val secondaryGlow = androidx.compose.ui.graphics.lerp(artColor, Lilac, 0.45f)
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRect(baseBgColor)
                    val cx1 = size.width * (0.35f + 0.25f * cos(phase))
                    val cy1 = size.height * (0.28f + 0.16f * sin(phase))
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(artColor.copy(alpha = 0.78f), Color.Transparent),
                            center = Offset(cx1, cy1),
                            radius = size.maxDimension * 0.75f
                        ),
                        radius = size.maxDimension * 0.75f,
                        center = Offset(cx1, cy1)
                    )
                    val cx2 = size.width * (0.68f + 0.22f * sin(phase * 0.8f))
                    val cy2 = size.height * (0.62f + 0.18f * cos(phase * 0.8f))
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(secondaryGlow.copy(alpha = 0.52f), Color.Transparent),
                            center = Offset(cx2, cy2),
                            radius = size.maxDimension * 0.65f
                        ),
                        radius = size.maxDimension * 0.65f,
                        center = Offset(cx2, cy2)
                    )
                }
            }

            playerCust.backgroundStyle == PlayerBackgroundStyle.LIQUID_GLASS -> {
                // Liquid Glass luminous frosted refraction over artwork
                if (thumbUrl != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    AsyncImage(
                        model = thumbUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .blur(48.dp, BlurredEdgeTreatment.Unbounded)
                            .graphicsLayer { scaleX = 1.25f; scaleY = 1.25f }
                            .alpha(0.68f)
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    artColor.copy(alpha = 0.42f),
                                    Color.White.copy(alpha = 0.08f),
                                    baseBgColor.copy(alpha = 0.72f)
                                )
                            )
                        )
                )
            }

            else -> Unit // SOLID_DARK and AMOLED_PITCH use baseBgColor cleanly
        }
    }
}

@Composable
private fun SleepTimerDialog(
    remainingMsFlow: StateFlow<Long?>,
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
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}

@Composable
private fun PlayerBackdrop(thumbUrl: String?, tint: Color, baseBg: Color) {
    val animatedTint by animateColorAsState(tint, tween(600), label = "backdropTint")
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to animatedTint,
                        0.6f to baseBg,
                        1f to baseBg
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
                            .fillMaxSize()
                            .blur(60.dp, BlurredEdgeTreatment.Unbounded)
                            .graphicsLayer { scaleX = 1.18f; scaleY = 1.18f }
                            .alpha(0.72f),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.28f to Color.Transparent,
                        0.65f to baseBg,
                        1f to baseBg
                    )
                )
        )
    }
}

@Composable
private fun UpNextLines(
    previous: String?,
    next: String?,
    afterNext: String?,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) 58.dp else 80.dp)
            .clip(RoundedCornerShape(16.dp))
            .bouncyClickable(pressedScale = 0.99f, onClick = onClick),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!compact) {
            Text(
                text = previous ?: " ",
                color = Color.White.copy(alpha = 0.26f),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
        Text(
            text = if (next != null) "Up next: $next" else "Nothing up next",
            color = Color.White.copy(alpha = 0.92f),
            fontSize = if (compact) 14.sp else 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Text(
            text = afterNext ?: " ",
            color = Color.White.copy(alpha = 0.26f),
            fontSize = if (compact) 12.5.sp else 14.sp,
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
    trackId: String?,
    position: StateFlow<PlaybackPosition>,
    isPlaying: Boolean,
    isBuffering: Boolean,
    playbackSpeed: Float,
    wavePlaying: Boolean,
    repeat: PlayerRepeatMode,
    playerCust: PlayerCustomizationConfig,
    seekbarCust: com.geekify.android.customization.SeekbarCustomizationConfig,
    activeColor: Color,
    secondaryAccentColor: Color,
    pendingSeek: PendingSeekPosition?,
    onPendingSeekChange: (PendingSeekPosition?) -> Unit,
    onSeek: (Long) -> Unit,
    onCycleRepeat: () -> Unit
) {
    val pos by position.collectAsStateWithLifecycle()
    val authoritativePositionMs by rememberAuthoritativePlaybackPosition(
        trackId = trackId,
        positionFlow = position,
        isPlaying = isPlaying,
        isBuffering = isBuffering,
        playbackSpeed = playbackSpeed
    )

    var isDragging by remember(trackId) { mutableStateOf(false) }
    var dragFraction by remember(trackId) { mutableFloatStateOf(0f) }

    // Clear pending seek once ExoPlayer acknowledges it or it times out
    LaunchedEffect(pos.positionMs, pos.durationMs, pendingSeek) {
        val pending = pendingSeek ?: return@LaunchedEffect
        if (pending.isAcknowledged(pos.positionMs) || pending.isExpired(System.nanoTime())) {
            onPendingSeekChange(null)
        }
    }

    val totalDurationMs = pos.durationMs.coerceAtLeast(0L)
    val effectiveDurationMs = totalDurationMs.coerceAtLeast(1L)

    val displayPositionMs = when {
        isDragging -> (dragFraction * totalDurationMs).toLong().coerceIn(0L, totalDurationMs)
        pendingSeek != null -> pendingSeek.targetMs.coerceIn(0L, totalDurationMs)
        else -> authoritativePositionMs.coerceIn(0L, totalDurationMs)
    }

    val fraction = if (isDragging) {
        dragFraction.coerceIn(0f, 1f)
    } else {
        calculateProgressFraction(displayPositionMs, totalDurationMs)
    }
    val remainingMs = (totalDurationMs - displayPositionMs).coerceAtLeast(0L)

    PlayerProgressBar(
        fraction = fraction,
        config = seekbarCust,
        onChange = { nextFraction ->
            isDragging = true
            dragFraction = nextFraction.coerceIn(0f, 1f)
        },
        onChangeFinished = {
            if (totalDurationMs > 0L) {
                val targetMs = (dragFraction * effectiveDurationMs).toLong().coerceIn(0L, totalDurationMs)
                onPendingSeekChange(
                    PendingSeekPosition(
                        targetMs = targetMs,
                        previousPositionMs = pos.positionMs
                    )
                )
                onSeek(targetMs)
            }
            isDragging = false
        },
        modifier = Modifier.fillMaxWidth(),
        activeColor = activeColor,
        secondaryAccentColor = secondaryAccentColor,
        inactiveBaseColor = Color.White,
        isPlaying = wavePlaying && !isBuffering
    )

    val labelSizeSp = (12.5f * seekbarCust.timeLabelScale.coerceIn(0.8f, 1.35f)).sp
    val showElapsed = seekbarCust.showElapsedTime
    val showRightLabel = seekbarCust.showRemainingTime

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showElapsed) {
            Text(
                text = formatPlaybackTime(displayPositionMs),
                color = Color.White.copy(alpha = 0.76f),
                fontSize = labelSizeSp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.width(64.dp),
                maxLines = 1,
                softWrap = false
            )
        } else {
            Spacer(Modifier.width(64.dp))
        }

        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (playerCust.showRepeatButton) {
                IconButton(onClick = onCycleRepeat, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (repeat == PlayerRepeatMode.ONE) GeekifyIcons.RepeatOne else GeekifyIcons.Repeat,
                        contentDescription = "Repeat",
                        tint = if (repeat != PlayerRepeatMode.OFF) activeColor else Color.White.copy(alpha = 0.70f),
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }

        if (showRightLabel) {
            Text(
                text = if (seekbarCust.showRemainingTime && playerCust.showRemainingTime) {
                    "-" + formatPlaybackTime(remainingMs)
                } else {
                    formatPlaybackTime(totalDurationMs)
                },
                color = Color.White.copy(alpha = 0.76f),
                fontSize = labelSizeSp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.width(64.dp),
                textAlign = TextAlign.End,
                maxLines = 1,
                softWrap = false
            )
        } else {
            Spacer(Modifier.width(64.dp))
        }
    }
}
