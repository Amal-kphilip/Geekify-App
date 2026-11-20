package com.geekify.android.ui.player

import android.app.Activity
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.geekify.android.customization.LocalCustomizationState
import com.geekify.android.customization.LyricsAnimationStyle
import com.geekify.android.customization.LyricsCustomizationConfig
import com.geekify.android.customization.LyricsTextAlignment
import com.geekify.android.customization.PlayerBackgroundStyle
import com.geekify.android.data.model.Track
import com.geekify.android.lyrics.LyricsLookupOutcome
import com.geekify.android.lyrics.LyricsRepository
import com.geekify.android.lyrics.LyricsSubtitleAligner
import com.geekify.android.lyrics.LyricsSyncType
import com.geekify.android.lyrics.LyricsTranslationClient
import com.geekify.android.lyrics.LyricsTranslationConfig
import com.geekify.android.lyrics.OfflineRomanizer
import com.geekify.android.lyrics.ParsedLyrics
import com.geekify.android.player.PlaybackPosition
import com.geekify.android.ui.glass.rememberReducedMotion
import com.geekify.android.ui.theme.AppFont
import com.geekify.android.ui.theme.GeekifyIcons
import com.geekify.android.ui.theme.InkElevated
import com.geekify.android.ui.theme.InkPanel
import com.geekify.android.ui.theme.Lime
import com.geekify.android.ui.theme.OnAccent
import com.geekify.android.ui.theme.TextMuted
import com.geekify.android.ui.theme.TextPrimary
import com.geekify.android.ui.theme.TextSecondary
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.koin.compose.koinInject

/**
 * Full-viewport, borderless Metrolist-inspired Synchronized Lyrics view.
 * Renders directly over the active player background with generous top/bottom edge-fade,
 * upper-middle active line anchoring, word-level karaoke animations across all 5 styles,
 * progressive blur, manual-scroll recovery pill, and a dedicated Lyrics Options bottom sheet
 * for sync offset editing, provider switching, translation, and fullscreen mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SynchronizedLyricsView(
    track: Track,
    positionFlow: StateFlow<PlaybackPosition>,
    config: LyricsCustomizationConfig,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
    isBuffering: Boolean = false,
    playbackSpeed: Float = 1.0f,
    isFullscreen: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null,
    showOptionsSheet: Boolean = false,
    onDismissOptionsSheet: () -> Unit = {},
    onSetShowTranslation: ((Boolean) -> Unit)? = null,
    accentOverride: Color? = null,
    playerBackgroundStyle: PlayerBackgroundStyle = LocalCustomizationState.current.player.backgroundStyle,
    onLightSurface: Boolean = false
) {
    val lyricsRepository: LyricsRepository = koinInject()
    val httpClient: OkHttpClient = koinInject()
    val position by positionFlow.collectAsState()
    val authoritativePositionMs by rememberAuthoritativePlaybackPosition(
        trackId = track.videoId,
        positionFlow = positionFlow,
        isPlaying = isPlaying,
        isBuffering = isBuffering,
        playbackSpeed = playbackSpeed
    )
    val scope = rememberCoroutineScope()
    val reducedMotion = rememberReducedMotion()
    val hapticsEnabled = LocalCustomizationState.current.gestures.hapticFeedback
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current

    // Optional status bar hiding in fullscreen lyrics mode
    val view = LocalView.current
    DisposableEffect(isFullscreen, config.hideStatusBarInFullscreen, view) {
        val window = (view.context as? Activity)?.window
        val shouldHide = isFullscreen && config.hideStatusBarInFullscreen && window != null
        if (shouldHide) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
        }
        onDispose {
            if (shouldHide) {
                WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.statusBars())
            }
        }
    }

    var isLoading by remember(track.videoId) { mutableStateOf(true) }
    var outcome by remember(track.videoId) { mutableStateOf<LyricsLookupOutcome?>(null) }
    var activeLyrics by remember(track.videoId) { mutableStateOf<ParsedLyrics?>(null) }
    var alternatives by remember(track.videoId) { mutableStateOf<List<ParsedLyrics>>(emptyList()) }
    var perTrackOffsetMs by remember(track.videoId) { mutableLongStateOf(0L) }
    var retryToken by remember(track.videoId) { mutableIntStateOf(0) }
    var queriedWithKnownDuration by remember(track.videoId) { mutableStateOf(false) }
    var userScrolledManually by remember(track.videoId) { mutableStateOf(false) }

    var translatedByKey by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var isTranslating by remember(track.videoId) { mutableStateOf(false) }
    var translationError by remember(track.videoId) { mutableStateOf<String?>(null) }
    var manualTranslateNonce by remember(track.videoId) { mutableIntStateOf(0) }

    val resolvedPlayerDurationSec = (position.durationMs / 1000L).toInt().takeIf { it > 0 }

    LaunchedEffect(track.videoId, retryToken) {
        val requestVideoId = track.videoId
        isLoading = true
        val forceRefresh = retryToken > 0
        val collectAlts = showOptionsSheet || retryToken > 0 || config.preferWordSynced
        if (track.durationSeconds != null || resolvedPlayerDurationSec != null) {
            queriedWithKnownDuration = true
        }
        try {
            val result = lyricsRepository.getOrFetchOutcome(
                videoId = requestVideoId,
                title = track.title,
                artist = track.artist,
                durationSeconds = track.durationSeconds ?: resolvedPlayerDurationSec,
                durationMs = position.durationMs,
                album = track.album,
                forceRefresh = forceRefresh,
                collectAlternatives = collectAlts
            )
            if (track.videoId == requestVideoId) {
                outcome = result
                if (result is LyricsLookupOutcome.Success) {
                    val preferred = if (config.preferWordSynced && !result.lyrics.hasWordSync) {
                        result.alternatives.firstOrNull { it.hasWordSync } ?: result.lyrics
                    } else {
                        result.lyrics
                    }
                    activeLyrics = preferred
                    alternatives = result.alternatives
                    perTrackOffsetMs = preferred.offsetMs
                } else {
                    activeLyrics = null
                    alternatives = emptyList()
                }
                isLoading = false
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            if (track.videoId == requestVideoId) {
                outcome = LyricsLookupOutcome.NetworkFailure(e.message ?: "Unexpected lyrics lookup error")
                activeLyrics = null
                isLoading = false
            }
        }
    }

    LaunchedEffect(track.videoId, resolvedPlayerDurationSec) {
        if (!queriedWithKnownDuration && resolvedPlayerDurationSec != null && !isLoading) {
            val current = activeLyrics
            if (current == null || !current.isSynced) {
                queriedWithKnownDuration = true
                retryToken++
            }
        }
    }

    val lyricsLinesForTranslation = activeLyrics?.lines.orEmpty()
    val translationConfig = remember { LyricsTranslationConfig() }
    val translationKey = remember(track.videoId, activeLyrics?.provider, lyricsLinesForTranslation, translationConfig.targetLanguage) {
        LyricsSubtitleAligner.buildTranslationKey(
            trackId = track.videoId,
            provider = activeLyrics?.provider.orEmpty(),
            originalLines = lyricsLinesForTranslation.map { it.text },
            targetLanguage = translationConfig.targetLanguage
        )
    }
    val hasEmbeddedTranslations = remember(lyricsLinesForTranslation) {
        lyricsLinesForTranslation.any { !it.embeddedTranslation.isNullOrBlank() }
    }
    val cachedTranslationsForKey = translatedByKey[translationKey].orEmpty()

    // Cancellable, key-scoped translation request strictly separated from visibility
    LaunchedEffect(translationKey, config.showTranslation, manualTranslateNonce, hasEmbeddedTranslations) {
        if (!config.showTranslation) {
            isTranslating = false
            translationError = null
            return@LaunchedEffect
        }
        if (lyricsLinesForTranslation.isEmpty()) return@LaunchedEffect
        if (hasEmbeddedTranslations || cachedTranslationsForKey.size == lyricsLinesForTranslation.size) {
            isTranslating = false
            translationError = null
            return@LaunchedEffect
        }
        val shouldAutoTranslate = manualTranslateNonce > 0 ||
            lyricsLinesForTranslation.any { OfflineRomanizer.hasNonLatinScript(it.text) }
        if (!shouldAutoTranslate) return@LaunchedEffect

        val requestKey = translationKey
        val originalTexts = lyricsLinesForTranslation.map { it.text }
        isTranslating = true
        translationError = null
        try {
            val client = LyricsTranslationClient(httpClient)
            val res = client.translateLines(lines = originalTexts, config = translationConfig)
            // Guard against stale response if key changed or user disabled translation while in-flight
            if (requestKey == translationKey && config.showTranslation) {
                res.onSuccess { raw ->
                    val aligned = LyricsSubtitleAligner.alignTranslations(originalTexts, raw)
                    translatedByKey = translatedByKey + (requestKey to aligned)
                }.onFailure { err ->
                    translationError = err.message ?: "Translation failed"
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } finally {
            if (requestKey == translationKey) {
                isTranslating = false
            }
        }
    }

    // Effective translated lines are strictly empty whenever showTranslation is OFF
    val visibleTranslatedLines = remember(config.showTranslation, lyricsLinesForTranslation, cachedTranslationsForKey) {
        if (!config.showTranslation) {
            emptyList()
        } else {
            lyricsLinesForTranslation.mapIndexed { idx, line ->
                val raw = line.embeddedTranslation ?: cachedTranslationsForKey.getOrNull(idx)
                LyricsSubtitleAligner.sanitizeSubtitle(line.text, raw).orEmpty()
            }
        }
    }

    // Single authoritative lyrics clock: EffectiveTime = ActualPlaybackPosition + TrackOffset + GlobalOffset (+ lead)
    // Offsets are applied strictly ONCE here; raw line/word timestamps are compared against effectiveLyricsTimeMs.
    val timingLeadMs = if (config.activeByRealTiming) 0L else 140L
    val currentParsed = activeLyrics
    val lines = currentParsed?.lines.orEmpty()
    val isSynced = currentParsed?.isSynced == true
    val effectiveLyricsTimeMs = LyricsSyncCalculator.effectiveLyricsTimeMs(
        playbackPositionMs = authoritativePositionMs,
        trackOffsetMs = perTrackOffsetMs,
        globalOffsetMs = config.globalOffsetMs,
        anticipationMs = timingLeadMs
    )
    val activeIndex = remember(lines, isSynced, effectiveLyricsTimeMs) {
        if (isSynced) LyricsSyncCalculator.findActiveLineIndex(lines, effectiveLyricsTimeMs) else -1
    }

    val listState = rememberLazyListState()
    val isDraggingList by listState.interactionSource.collectIsDraggedAsState()

    // Track whether we've performed the immediate initial positioning for the current track/source
    var hasInitialSyncedScroll by remember(track.videoId, currentParsed?.provider, isLoading) {
        mutableStateOf(false)
    }

    LaunchedEffect(track.videoId, currentParsed?.provider) {
        userScrolledManually = false
    }

    LaunchedEffect(isDraggingList) {
        if (isDraggingList) {
            userScrolledManually = true
        }
    }

    LaunchedEffect(userScrolledManually, config.autoScroll) {
        if (userScrolledManually && config.autoScroll) {
            delay(5_500L)
            userScrolledManually = false
        }
    }

    val resolvedAccent = accentOverride ?: Lime
    val baseFg = if (onLightSurface) TextPrimary else Color.White
    val activeColor = when {
        config.useNeutralColor -> baseFg
        config.activeColorFollowsAccent -> resolvedAccent
        else -> baseFg
    }
    val inactiveBaseColor = baseFg

    val effectiveAnimStyle = if (reducedMotion) LyricsAnimationStyle.NONE else config.animationStyle
    val usePlusLighter = config.plusLighterBlend && playerBackgroundStyle == PlayerBackgroundStyle.LIQUID_GLASS

    // Borderless full-viewport container (NO dark square card!)
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val viewportHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(300f)
        val anchorOffsetPx = -(viewportHeightPx * 0.28f).toInt()
        val topPadDp = with(density) { (viewportHeightPx * 0.22f).toDp().coerceIn(56.dp, 140.dp) }
        val bottomPadDp = with(density) { (viewportHeightPx * 0.42f).toDp().coerceIn(120.dp, 240.dp) }

        // Authoritative single-owner scroll coordinator:
        // - Jumps immediately on initial lyrics open (even mid-song), track/provider switch, or large seeks (>6 lines)
        // - Smoothly animates on consecutive lyric line advances
        // - Never fires while isLoading == true (before LazyColumn is mounted)
        LaunchedEffect(
            track.videoId,
            currentParsed?.provider,
            isLoading,
            isSynced,
            lines.size,
            activeIndex,
            config.autoScroll,
            userScrolledManually,
            anchorOffsetPx
        ) {
            if (isLoading || lines.isEmpty()) return@LaunchedEffect
            if (!isSynced) {
                if (!hasInitialSyncedScroll) {
                    hasInitialSyncedScroll = true
                    runCatching { listState.scrollToItem(0, 0) }
                }
                return@LaunchedEffect
            }
            if (!config.autoScroll || userScrolledManually) return@LaunchedEffect
            val targetIndex = activeIndex.coerceIn(0, lines.lastIndex)
            val currentVisible = listState.firstVisibleItemIndex
            val isLargeJump = abs(targetIndex - currentVisible) > 6

            if (!hasInitialSyncedScroll || isLargeJump) {
                hasInitialSyncedScroll = true
                runCatching {
                    listState.scrollToItem(
                        index = targetIndex,
                        scrollOffset = anchorOffsetPx
                    )
                }
            } else {
                // Skip redundant scroll if item is already within 36px of the target anchor
                val visibleInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
                val alreadyAnchored = visibleInfo != null && abs(visibleInfo.offset - (-anchorOffsetPx)) < 36
                if (!alreadyAnchored) {
                    runCatching {
                        listState.animateScrollToItem(
                            index = targetIndex,
                            scrollOffset = anchorOffsetPx
                        )
                    }
                }
            }
        }

        when {
            isLoading -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        color = activeColor,
                        modifier = Modifier.size(34.dp),
                        strokeWidth = 3.dp
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Searching synchronized lyrics…",
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 13.5.sp
                    )
                }
            }

            currentParsed == null || lines.isEmpty() -> {
                val (headline, detail) = when (val o = outcome) {
                    is LyricsLookupOutcome.NetworkFailure ->
                        "Couldn't Reach Lyrics Providers" to o.message
                    is LyricsLookupOutcome.InvalidResponse ->
                        "Invalid Provider Response" to o.message
                    is LyricsLookupOutcome.NoMatch ->
                        "No Lyrics Found" to "Lyrics aren't available for this track yet."
                    else ->
                        "No Lyrics Found" to "Lyrics aren't available for this track yet."
                }
                val retryLabelColor = if (resolvedAccent.luminance() > 0.55f) OnAccent else Color.White
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .border(0.8.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(20.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = GeekifyIcons.Lyrics,
                            contentDescription = null,
                            tint = resolvedAccent.copy(alpha = 0.85f),
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = headline,
                        color = Color.White,
                        fontSize = 18.5.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = detail,
                        color = Color.White.copy(alpha = 0.70f),
                        fontSize = 13.5.sp,
                        lineHeight = 19.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(20.dp))
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(resolvedAccent)
                            .clickable { retryToken++ }
                            .padding(horizontal = 22.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = "Retry",
                            color = retryLabelColor,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0.0f to Color.Transparent,
                                    0.10f to Color.Black,
                                    0.86f to Color.Black,
                                    1.0f to Color.Transparent
                                ),
                                blendMode = BlendMode.DstIn
                            )
                        },
                    contentPadding = PaddingValues(
                        top = topPadDp,
                        bottom = bottomPadDp,
                        start = 10.dp,
                        end = 10.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy((14f * config.lineSpacingMultiplier).dp)
                ) {
                    itemsIndexed(lines, key = { idx, line -> "$idx:${line.timeMs}" }) { index, line ->
                        val isActive = isSynced && index == activeIndex
                        val isPast = isSynced && activeIndex >= 0 && index < activeIndex
                        val distance = if (isSynced && activeIndex >= 0) abs(index - activeIndex) else 0

                        val effectiveAlignment = resolveLineAlignment(
                            isActive = isActive,
                            lineText = line.text,
                            normalAlign = config.alignment,
                            activeAlign = config.activeAlignment,
                            respectProvider = config.respectProviderPositioning
                        )

                        val activeWordIdx = if (isActive && config.wordLevelHighlight && line.words.isNotEmpty()) {
                            LyricsSyncCalculator.findActiveWordIndex(line.words, effectiveLyricsTimeMs)
                        } else -1

                        val romanized = remember(line.text, line.embeddedRomanization, config.showRomanization) {
                            if (!config.showRomanization) null
                            else {
                                val rawRom = line.embeddedRomanization
                                    ?: if (OfflineRomanizer.hasNonLatinScript(line.text)) OfflineRomanizer.romanize(line.text) else null
                                LyricsSubtitleAligner.sanitizeSubtitle(line.text, rawRom)
                            }
                        }
                        val translation = visibleTranslatedLines.getOrNull(index)?.takeIf {
                            config.showTranslation && it.isNotBlank()
                        }

                        // Distinct motion profiles per LyricsAnimationStyle
                        val targetScale = when (effectiveAnimStyle) {
                            LyricsAnimationStyle.APPLE -> if (isActive) 1.04f else 0.95f
                            LyricsAnimationStyle.BRACCATO -> if (isActive) 1.06f else 0.94f
                            LyricsAnimationStyle.EXPRESSIVE -> if (isActive) 1.09f else 0.90f
                            LyricsAnimationStyle.SIMPLE -> 1.0f
                            LyricsAnimationStyle.NONE -> 1.0f
                        }
                        val targetLiftPx = when (effectiveAnimStyle) {
                            LyricsAnimationStyle.APPLE -> if (isActive) -7f else 0f
                            LyricsAnimationStyle.BRACCATO -> if (isActive) -11f else 0f
                            LyricsAnimationStyle.EXPRESSIVE -> if (isActive) -9f else 2f
                            LyricsAnimationStyle.SIMPLE, LyricsAnimationStyle.NONE -> 0f
                        }
                        val targetAlpha = when {
                            !isSynced -> 0.92f
                            isActive -> 1.0f
                            isPast -> (0.40f - distance * 0.045f).coerceAtLeast(0.20f)
                            else -> (0.56f - distance * 0.055f).coerceAtLeast(0.24f)
                        }
                        val targetBlurDp = if (config.progressiveBlur && isSynced && !isActive && !isDraggingList && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            (distance * 1.15f).coerceAtMost(5.5f).dp
                        } else 0.dp

                        val animSpecFloat = when (effectiveAnimStyle) {
                            LyricsAnimationStyle.NONE -> snap<Float>()
                            LyricsAnimationStyle.SIMPLE -> tween(180)
                            LyricsAnimationStyle.APPLE -> spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)
                            LyricsAnimationStyle.BRACCATO -> spring(dampingRatio = 0.60f, stiffness = Spring.StiffnessMedium)
                            LyricsAnimationStyle.EXPRESSIVE -> spring(dampingRatio = 0.70f, stiffness = 310f)
                        }

                        val animatedScale by animateFloatAsState(targetScale, animSpecFloat, label = "lyricScale")
                        val animatedLift by animateFloatAsState(targetLiftPx, animSpecFloat, label = "lyricLift")
                        val animatedAlpha by animateFloatAsState(targetAlpha, tween(240), label = "lyricAlpha")
                        val animatedBlur by animateDpAsState(targetBlurDp, tween(260), label = "lyricBlur")
                        val animatedLineColor by animateColorAsState(
                            targetValue = if (isActive || !isSynced) activeColor else inactiveBaseColor,
                            animationSpec = tween(220),
                            label = "lyricColor"
                        )

                        val braccatoWave = if (effectiveAnimStyle == LyricsAnimationStyle.BRACCATO && isActive && activeWordIdx >= 0 && isPlaying && !isBuffering) {
                            sin((authoritativePositionMs % 560L) / 560.0 * Math.PI).toFloat() * -4.5f
                        } else 0f

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable(enabled = config.tapToSeek && isSynced && line.timeMs >= 0L) {
                                    if (line.timeMs >= 0L) {
                                        if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        userScrolledManually = false
                                        onSeekTo(
                                            LyricsSyncCalculator.targetSeekPositionMs(
                                                lyricTimestampMs = line.timeMs,
                                                trackOffsetMs = perTrackOffsetMs,
                                                globalOffsetMs = config.globalOffsetMs,
                                                durationMs = position.durationMs
                                            )
                                        )
                                    }
                                }
                                .graphicsLayer {
                                    scaleX = animatedScale
                                    scaleY = animatedScale
                                    translationY = animatedLift + braccatoWave
                                    alpha = animatedAlpha
                                }
                                .then(
                                    if (animatedBlur > 0.2.dp && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        Modifier.blur(animatedBlur, BlurredEdgeTreatment.Unbounded)
                                    } else Modifier
                                )
                                .padding(vertical = 4.dp, horizontal = 6.dp)
                        ) {
                            val lineFontSize = (if (isActive || !isSynced) config.fontSizeSp else (config.fontSizeSp - 2f).coerceAtLeast(14f)).sp
                            val lineHeightSp = (config.fontSizeSp * config.lineSpacingMultiplier).sp
                            val activeShadow = if (isActive && (effectiveAnimStyle == LyricsAnimationStyle.APPLE || effectiveAnimStyle == LyricsAnimationStyle.EXPRESSIVE || usePlusLighter)) {
                                Shadow(
                                    color = activeColor.copy(alpha = if (usePlusLighter) 0.58f else 0.36f),
                                    offset = Offset.Zero,
                                    blurRadius = if (usePlusLighter) 20f else 12f
                                )
                            } else null

                            if (config.showOriginalLyrics) {
                                if (isActive && config.wordLevelHighlight && line.words.isNotEmpty()) {
                                    val annotated = buildAnnotatedString {
                                        line.words.forEachIndexed { wIdx, word ->
                                            val sung = wIdx <= activeWordIdx
                                            val isCurrentWord = wIdx == activeWordIdx
                                            val wordColor = when {
                                                isCurrentWord -> activeColor
                                                sung -> activeColor.copy(alpha = 0.92f)
                                                else -> inactiveBaseColor.copy(alpha = 0.42f)
                                            }
                                            val wordWeight = when {
                                                isCurrentWord && effectiveAnimStyle == LyricsAnimationStyle.EXPRESSIVE -> FontWeight.Bold
                                                isCurrentWord -> FontWeight.SemiBold
                                                sung -> FontWeight.SemiBold
                                                else -> FontWeight.Normal
                                            }
                                            withStyle(
                                                SpanStyle(
                                                    color = wordColor,
                                                    fontFamily = AppFont,
                                                    fontWeight = wordWeight,
                                                    shadow = if (isCurrentWord) activeShadow else null
                                                )
                                            ) {
                                                append(word.text)
                                                if (wIdx < line.words.lastIndex) append(" ")
                                            }
                                        }
                                    }
                                    Text(
                                        text = annotated,
                                        fontFamily = AppFont,
                                        fontSize = lineFontSize,
                                        lineHeight = lineHeightSp,
                                        textAlign = effectiveAlignment,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                } else {
                                    Text(
                                        text = line.text,
                                        color = animatedLineColor,
                                        fontFamily = AppFont,
                                        fontSize = lineFontSize,
                                        lineHeight = lineHeightSp,
                                        fontWeight = when {
                                            isActive && effectiveAnimStyle == LyricsAnimationStyle.EXPRESSIVE -> FontWeight.Bold
                                            isActive -> FontWeight.SemiBold
                                            else -> FontWeight.Normal
                                        },
                                        style = androidx.compose.material3.LocalTextStyle.current.copy(
                                            fontFamily = AppFont,
                                            shadow = activeShadow
                                        ),
                                        textAlign = effectiveAlignment,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }

                            if (!romanized.isNullOrBlank()) {
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = romanized,
                                    color = if (isActive) activeColor.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.50f),
                                    fontFamily = AppFont,
                                    fontSize = config.subtitleFontSizeSp.coerceIn(10f, 24f).sp,
                                    lineHeight = (config.subtitleFontSizeSp * 1.25f).sp,
                                    fontWeight = FontWeight.Normal,
                                    textAlign = effectiveAlignment,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            if (!translation.isNullOrBlank()) {
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = translation,
                                    color = if (isActive) Color.White.copy(alpha = 0.90f) else Color.White.copy(alpha = 0.58f),
                                    fontFamily = AppFont,
                                    fontSize = config.subtitleFontSizeSp.coerceIn(10f, 26f).sp,
                                    lineHeight = (config.subtitleFontSizeSp * 1.3f).sp,
                                    fontWeight = if (isActive) FontWeight.Normal else FontWeight.Light,
                                    textAlign = effectiveAlignment,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    // Metrolist-style subtle provider attribution at the very bottom of the lyrics scroll
                    item(key = "lyrics_provider_footer") {
                        val syncLabel = when (currentParsed.syncType) {
                            LyricsSyncType.WORD_SYNCED -> "Word-Synced"
                            LyricsSyncType.LINE_SYNCED -> "Synced"
                            LyricsSyncType.UNSYNCED -> "Plain Lyrics"
                        }
                        Text(
                            text = "Lyrics provided by ${currentParsed.provider.removePrefix("User:")} · $syncLabel",
                            color = Color.White.copy(alpha = 0.48f),
                            fontFamily = AppFont,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 24.dp, bottom = 12.dp)
                        )
                    }
                }

                // Floating "Sync to Lyrics" recovery pill when user manually scrolls away
                androidx.compose.animation.AnimatedVisibility(
                    visible = userScrolledManually && isSynced && activeIndex >= 0,
                    enter = fadeIn(tween(200)) + slideInVertically { it / 2 },
                    exit = fadeOut(tween(180)) + slideOutVertically { it / 2 },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(resolvedAccent)
                            .clickable {
                                userScrolledManually = false
                                scope.launch {
                                    runCatching {
                                        listState.animateScrollToItem(activeIndex, anchorOffsetPx)
                                    }
                                }
                            }
                            .padding(horizontal = 18.dp, vertical = 9.dp)
                    ) {
                        Text(
                            text = "Sync to Current Lyric",
                            color = OnAccent,
                            fontFamily = AppFont,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }

    // ---- Dedicated Metrolist-style Lyrics Options Bottom Sheet (Offset Editor, Sources, Translate, Fullscreen) ----
    if (showOptionsSheet) {
        val optionsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onDismissOptionsSheet,
            sheetState = optionsSheetState,
            containerColor = InkPanel,
            contentColor = TextPrimary,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            dragHandle = { BottomSheetDefaults.DragHandle(color = TextMuted) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Lyrics Options", color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)

                // Per-track Sync Offset Editor (preserving global offset independently)
                if (isSynced) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .background(InkElevated)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Track Sync Offset", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "Track: %+.2fs · Global: %+.2fs · Effective: %+.2fs".format(
                                perTrackOffsetMs / 1000f,
                                config.globalOffsetMs / 1000f,
                                (perTrackOffsetMs + config.globalOffsetMs) / 1000f
                            ),
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            TextButton(onClick = {
                                val next = perTrackOffsetMs - 250L
                                perTrackOffsetMs = next
                                scope.launch { lyricsRepository.updateOffset(track.videoId, next) }
                            }) { Text("-0.25s", color = resolvedAccent, fontWeight = FontWeight.Bold) }

                            TextButton(onClick = {
                                perTrackOffsetMs = 0L
                                scope.launch { lyricsRepository.updateOffset(track.videoId, 0L) }
                            }) { Text("Reset", color = TextSecondary) }

                            TextButton(onClick = {
                                val next = perTrackOffsetMs + 250L
                                perTrackOffsetMs = next
                                scope.launch { lyricsRepository.updateOffset(track.videoId, next) }
                            }) { Text("+0.25s", color = resolvedAccent, fontWeight = FontWeight.Bold) }
                        }
                    }
                }

                // Always-available Show Translated Lyrics toggle (persisted to LyricsCustomizationConfig.showTranslation)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(InkElevated)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = onSetShowTranslation != null) {
                                onSetShowTranslation?.invoke(!config.showTranslation)
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Show Translated Lyrics",
                                color = TextPrimary,
                                fontFamily = AppFont,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = when {
                                    !config.showTranslation -> "Hidden · Original lyrics remain visible"
                                    isTranslating -> "Translating lyrics…"
                                    translationError != null -> "Error: $translationError"
                                    visibleTranslatedLines.any { it.isNotBlank() } -> "Showing translated subtitles"
                                    else -> "Enabled · Tap Translate Lyrics to fetch"
                                },
                                color = TextSecondary,
                                fontFamily = AppFont,
                                fontSize = 12.sp
                            )
                        }
                        androidx.compose.material3.Switch(
                            checked = config.showTranslation,
                            onCheckedChange = { enabled ->
                                onSetShowTranslation?.invoke(enabled)
                            },
                            colors = androidx.compose.material3.SwitchDefaults.colors(
                                checkedThumbColor = OnAccent,
                                checkedTrackColor = resolvedAccent
                            )
                        )
                    }

                    // Separate Translate / Retry request action (strictly separated from visibility toggle)
                    if (config.showTranslation && !hasEmbeddedTranslations &&
                        (cachedTranslationsForKey.size != lyricsLinesForTranslation.size || translationError != null)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(resolvedAccent.copy(alpha = 0.18f))
                                .clickable(enabled = !isTranslating) {
                                    manualTranslateNonce++
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = when {
                                    isTranslating -> "Translating…"
                                    translationError != null -> "Retry Translation"
                                    else -> "Translate Lyrics"
                                },
                                color = resolvedAccent,
                                fontFamily = AppFont,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Fullscreen quick action
                if (onToggleFullscreen != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(if (isFullscreen) resolvedAccent.copy(alpha = 0.20f) else InkElevated)
                            .clickable {
                                onDismissOptionsSheet()
                                onToggleFullscreen()
                            }
                            .padding(vertical = 13.dp, horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isFullscreen) "Exit Fullscreen" else "Fullscreen Lyrics",
                            color = if (isFullscreen) resolvedAccent else TextPrimary,
                            fontFamily = AppFont,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Provider Source Picker
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Lyrics Sources", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { retryToken++ }) {
                            Text("Refresh All", color = resolvedAccent, fontSize = 12.5.sp)
                        }
                    }
                    if (alternatives.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            alternatives.forEach { alt ->
                                val isCurrent = currentParsed != null &&
                                    alt.provider == currentParsed.provider &&
                                    alt.syncType == currentParsed.syncType
                                val label = when (alt.syncType) {
                                    LyricsSyncType.WORD_SYNCED -> "${alt.provider} (Word)"
                                    LyricsSyncType.LINE_SYNCED -> "${alt.provider} (Synced)"
                                    LyricsSyncType.UNSYNCED -> "${alt.provider} (Plain)"
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(if (isCurrent) resolvedAccent else InkElevated)
                                        .clickable {
                                            scope.launch {
                                                val saved = lyricsRepository.selectAlternative(track.videoId, alt)
                                                activeLyrics = saved
                                                perTrackOffsetMs = saved.offsetMs
                                            }
                                        }
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = label,
                                        color = if (isCurrent) OnAccent else TextPrimary,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    } else if (currentParsed != null) {
                        Text(
                            text = "Active source: ${currentParsed.provider.removePrefix("User:")}",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

private fun resolveLineAlignment(
    isActive: Boolean,
    lineText: String,
    normalAlign: LyricsTextAlignment,
    activeAlign: LyricsTextAlignment,
    respectProvider: Boolean
): TextAlign {
    if (respectProvider) {
        val trimmed = lineText.trim()
        if (trimmed.startsWith("(") && trimmed.endsWith(")")) {
            return TextAlign.End
        }
    }
    val chosen = if (isActive) activeAlign else normalAlign
    return when (chosen) {
        LyricsTextAlignment.START -> TextAlign.Start
        LyricsTextAlignment.CENTER -> TextAlign.Center
        LyricsTextAlignment.END -> TextAlign.End
    }
}

/**
 * Deterministic sample lyric line for the independent Live Lyrics Preview.
 */
private data class PreviewLyricSample(
    val line: com.geekify.android.lyrics.LyricsLine,
    val romanization: String? = null,
    val translation: String? = null
)

private val SamplePreviewLyrics = listOf(
    PreviewLyricSample(
        line = com.geekify.android.lyrics.LyricsLine(
            timeMs = 0L,
            text = "Drifting through the midnight glow",
            words = listOf(
                com.geekify.android.lyrics.LyricsWord(0L, 650L, "Drifting"),
                com.geekify.android.lyrics.LyricsWord(700L, 1_300L, "through"),
                com.geekify.android.lyrics.LyricsWord(1_350L, 1_700L, "the"),
                com.geekify.android.lyrics.LyricsWord(1_750L, 2_450L, "midnight"),
                com.geekify.android.lyrics.LyricsWord(2_500L, 3_200L, "glow")
            )
        ),
        translation = "Floating gently under the night sky"
    ),
    PreviewLyricSample(
        line = com.geekify.android.lyrics.LyricsLine(
            timeMs = 3_400L,
            text = "밤하늘 별빛 · Electric dreams in stereo",
            words = listOf(
                com.geekify.android.lyrics.LyricsWord(3_400L, 4_150L, "밤하늘"),
                com.geekify.android.lyrics.LyricsWord(4_200L, 4_900L, "별빛"),
                com.geekify.android.lyrics.LyricsWord(4_950L, 5_100L, "·"),
                com.geekify.android.lyrics.LyricsWord(5_150L, 5_850L, "Electric"),
                com.geekify.android.lyrics.LyricsWord(5_900L, 6_500L, "dreams"),
                com.geekify.android.lyrics.LyricsWord(6_550L, 6_900L, "in"),
                com.geekify.android.lyrics.LyricsWord(6_950L, 7_700L, "stereo")
            )
        ),
        romanization = "Bamhaneul byeolbit · Electric dreams in stereo",
        translation = "Starlight in the night sky"
    ),
    PreviewLyricSample(
        line = com.geekify.android.lyrics.LyricsLine(
            timeMs = 7_900L,
            text = "Every beat pulls us closer home",
            words = listOf(
                com.geekify.android.lyrics.LyricsWord(7_900L, 8_450L, "Every"),
                com.geekify.android.lyrics.LyricsWord(8_500L, 9_050L, "beat"),
                com.geekify.android.lyrics.LyricsWord(9_100L, 9_700L, "pulls"),
                com.geekify.android.lyrics.LyricsWord(9_750L, 10_200L, "us"),
                com.geekify.android.lyrics.LyricsWord(10_250L, 10_950L, "closer"),
                com.geekify.android.lyrics.LyricsWord(11_000L, 11_800L, "home")
            )
        ),
        translation = "Each rhythm brings us back together"
    )
)

private const val PREVIEW_DURATION_MS = 12_000L

/**
 * Interactive, independent Live Lyrics Preview powered by the same [LyricsSyncCalculator],
 * [AppFont] (Sohne Breit), word-level karaoke highlighting, progressive blur, alignment,
 * translation/romanization, and all 5 [LyricsAnimationStyle] motion physics as the real player.
 * Never affects real playback or requires an active song.
 */
@Composable
fun LiveLyricsPreviewCard(
    config: LyricsCustomizationConfig,
    accentColor: Color,
    playerBackgroundStyle: PlayerBackgroundStyle,
    modifier: Modifier = Modifier
) {
    val reducedMotion = rememberReducedMotion()
    var previewPlaying by remember { mutableStateOf(true) }
    var previewPositionMs by remember { mutableLongStateOf(4_600L) }

    LaunchedEffect(previewPlaying) {
        if (!previewPlaying) return@LaunchedEffect
        var lastFrameNanos = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { frameNanos ->
                if (lastFrameNanos > 0L) {
                    val deltaMs = ((frameNanos - lastFrameNanos) / 1_000_000L).coerceIn(0L, 100L)
                    previewPositionMs = (previewPositionMs + deltaMs) % PREVIEW_DURATION_MS
                }
                lastFrameNanos = frameNanos
            }
        }
    }

    val sampleLines = remember { SamplePreviewLyrics.map { it.line } }
    val activeIndex = LyricsSyncCalculator.findActiveLineIndex(sampleLines, previewPositionMs)
    val effectiveAnimStyle = if (reducedMotion) LyricsAnimationStyle.NONE else config.animationStyle
    val usePlusLighter = config.plusLighterBlend && playerBackgroundStyle == PlayerBackgroundStyle.LIQUID_GLASS
    val activeColor = when {
        config.useNeutralColor -> Color.White
        config.activeColorFollowsAccent -> accentColor
        else -> Color.White
    }

    val previewBgBrush = when (playerBackgroundStyle) {
        PlayerBackgroundStyle.AMOLED_PITCH -> Brush.verticalGradient(listOf(Color.Black, Color(0xFF060608)))
        PlayerBackgroundStyle.SOLID_DARK -> Brush.verticalGradient(listOf(Color(0xFF15131C), Color(0xFF0E0D14)))
        PlayerBackgroundStyle.DYNAMIC_GRADIENT -> Brush.verticalGradient(listOf(accentColor.copy(alpha = 0.28f), Color(0xFF12101A)))
        PlayerBackgroundStyle.ANIMATED_GRADIENT -> Brush.linearGradient(listOf(accentColor.copy(alpha = 0.26f), Color(0xFF2B1B3D), Color(0xFF100E18)))
        PlayerBackgroundStyle.LIQUID_GLASS -> Brush.linearGradient(listOf(Color.White.copy(alpha = 0.14f), accentColor.copy(alpha = 0.22f), Color(0xFF15131E)))
        PlayerBackgroundStyle.BLURRED_ARTWORK -> Brush.verticalGradient(listOf(Color(0xFF282035), Color(0xFF12101A)))
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(previewBgBrush)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Width-aware responsive preview controls header (independent Play/Pause & Replay)
        val previewTimelineText = "${config.animationStyle.label} · ${formatPlaybackTime(previewPositionMs)} / ${formatPlaybackTime(PREVIEW_DURATION_MS)}"
        val currentFontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val useCompactTwoRow = maxWidth < 340.dp || currentFontScale > 1.25f

            if (useCompactTwoRow) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = previewTimelineText,
                        color = Color.White.copy(alpha = 0.78f),
                        fontFamily = AppFont,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 42.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.14f))
                                .clickable { previewPositionMs = 0L }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Replay",
                                color = Color.White,
                                fontFamily = AppFont,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 42.dp)
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = 0.22f))
                                .clickable { previewPlaying = !previewPlaying }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (previewPlaying) "Pause" else "Play",
                                color = accentColor,
                                fontFamily = AppFont,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = previewTimelineText,
                        color = Color.White.copy(alpha = 0.78f),
                        fontFamily = AppFont,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .widthIn(min = 72.dp)
                                .heightIn(min = 40.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.14f))
                                .clickable { previewPositionMs = 0L }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Replay",
                                color = Color.White,
                                fontFamily = AppFont,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                        Box(
                            modifier = Modifier
                                .widthIn(min = 72.dp)
                                .heightIn(min = 40.dp)
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = 0.22f))
                                .clickable { previewPlaying = !previewPlaying }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (previewPlaying) "Pause" else "Play",
                                color = accentColor,
                                fontFamily = AppFont,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }

        // Real lyrics line & word rendering with independent sample timeline
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy((12f * config.lineSpacingMultiplier).dp)
        ) {
            SamplePreviewLyrics.forEachIndexed { index, sample ->
                val line = sample.line
                val isActive = index == activeIndex
                val isPast = activeIndex >= 0 && index < activeIndex
                val distance = if (activeIndex >= 0) abs(index - activeIndex) else 0
                val effectiveAlignment = resolveLineAlignment(
                    isActive = isActive,
                    lineText = line.text,
                    normalAlign = config.alignment,
                    activeAlign = config.activeAlignment,
                    respectProvider = config.respectProviderPositioning
                )
                val activeWordIdx = if (isActive && config.wordLevelHighlight && line.words.isNotEmpty()) {
                    LyricsSyncCalculator.findActiveWordIndex(line.words, previewPositionMs)
                } else -1

                val targetScale = when (effectiveAnimStyle) {
                    LyricsAnimationStyle.APPLE -> if (isActive) 1.03f else 0.96f
                    LyricsAnimationStyle.BRACCATO -> if (isActive) 1.05f else 0.95f
                    LyricsAnimationStyle.EXPRESSIVE -> if (isActive) 1.07f else 0.92f
                    LyricsAnimationStyle.SIMPLE, LyricsAnimationStyle.NONE -> 1.0f
                }
                val targetAlpha = when {
                    isActive -> 1.0f
                    isPast -> (0.42f - distance * 0.05f).coerceAtLeast(0.24f)
                    else -> (0.58f - distance * 0.06f).coerceAtLeast(0.28f)
                }
                val targetBlurDp = if (config.progressiveBlur && !isActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (distance * 1.1f).coerceAtMost(4.5f).dp
                } else 0.dp

                val animatedScale by animateFloatAsState(targetScale, tween(220), label = "prevLyricScale")
                val animatedAlpha by animateFloatAsState(targetAlpha, tween(220), label = "prevLyricAlpha")
                val animatedBlur by animateDpAsState(targetBlurDp, tween(240), label = "prevLyricBlur")
                val animatedColor by animateColorAsState(
                    targetValue = if (isActive) activeColor else Color.White,
                    animationSpec = tween(220),
                    label = "prevLyricColor"
                )
                val activeShadow = if (isActive && (effectiveAnimStyle == LyricsAnimationStyle.APPLE || effectiveAnimStyle == LyricsAnimationStyle.EXPRESSIVE || usePlusLighter)) {
                    Shadow(
                        color = activeColor.copy(alpha = if (usePlusLighter) 0.55f else 0.35f),
                        offset = Offset.Zero,
                        blurRadius = if (usePlusLighter) 18f else 10f
                    )
                } else null

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { previewPositionMs = line.timeMs }
                        .graphicsLayer {
                            scaleX = animatedScale
                            scaleY = animatedScale
                            alpha = animatedAlpha
                        }
                        .then(
                            if (animatedBlur > 0.2.dp && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                Modifier.blur(animatedBlur, BlurredEdgeTreatment.Unbounded)
                            } else Modifier
                        )
                        .padding(vertical = 2.dp, horizontal = 4.dp)
                ) {
                    val lineFontSize = (if (isActive) config.fontSizeSp else (config.fontSizeSp - 2f).coerceAtLeast(14f)).sp
                    val lineHeightSp = (config.fontSizeSp * config.lineSpacingMultiplier).sp

                    if (config.showOriginalLyrics) {
                        if (isActive && config.wordLevelHighlight && line.words.isNotEmpty()) {
                            val annotated = buildAnnotatedString {
                                line.words.forEachIndexed { wIdx, word ->
                                    val sung = wIdx <= activeWordIdx
                                    val isCurrentWord = wIdx == activeWordIdx
                                    val wordColor = when {
                                        isCurrentWord -> activeColor
                                        sung -> activeColor.copy(alpha = 0.92f)
                                        else -> Color.White.copy(alpha = 0.42f)
                                    }
                                    withStyle(
                                        SpanStyle(
                                            color = wordColor,
                                            fontFamily = AppFont,
                                            fontWeight = if (sung) FontWeight.SemiBold else FontWeight.Normal,
                                            shadow = if (isCurrentWord) activeShadow else null
                                        )
                                    ) {
                                        append(word.text)
                                        if (wIdx < line.words.lastIndex) append(" ")
                                    }
                                }
                            }
                            Text(
                                text = annotated,
                                fontFamily = AppFont,
                                fontSize = lineFontSize,
                                lineHeight = lineHeightSp,
                                textAlign = effectiveAlignment,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text(
                                text = line.text,
                                color = animatedColor,
                                fontFamily = AppFont,
                                fontSize = lineFontSize,
                                lineHeight = lineHeightSp,
                                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                style = androidx.compose.material3.LocalTextStyle.current.copy(
                                    fontFamily = AppFont,
                                    shadow = activeShadow
                                ),
                                textAlign = effectiveAlignment,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    if (config.showRomanization && !sample.romanization.isNullOrBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = sample.romanization,
                            color = if (isActive) activeColor.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.50f),
                            fontFamily = AppFont,
                            fontSize = config.subtitleFontSizeSp.coerceIn(10f, 24f).sp,
                            lineHeight = (config.subtitleFontSizeSp * 1.25f).sp,
                            fontWeight = FontWeight.Normal,
                            textAlign = effectiveAlignment,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (config.showTranslation && !sample.translation.isNullOrBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = sample.translation,
                            color = if (isActive) Color.White.copy(alpha = 0.90f) else Color.White.copy(alpha = 0.58f),
                            fontFamily = AppFont,
                            fontSize = config.subtitleFontSizeSp.coerceIn(10f, 26f).sp,
                            lineHeight = (config.subtitleFontSizeSp * 1.3f).sp,
                            fontWeight = if (isActive) FontWeight.Normal else FontWeight.Light,
                            textAlign = effectiveAlignment,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}
