package com.geekify.android.ui.player

import com.geekify.android.ui.theme.GeekifyIcons

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.launch
import com.geekify.android.ui.glass.GlassStyle
import com.geekify.android.ui.glass.LocalGlassDim
import com.geekify.android.ui.glass.consumeTaps
import com.geekify.android.ui.glass.liquidGlass
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import com.geekify.android.ui.components.CircleIconButton
import com.geekify.android.ui.components.TrackRow
import com.geekify.android.ui.components.bestArtworkUrl
import com.geekify.android.ui.components.rememberArtColor
import com.geekify.android.ui.theme.InkBackground
import com.geekify.android.ui.theme.InkElevated
import com.geekify.android.ui.theme.Lime
import com.geekify.android.ui.theme.TextMuted
import com.geekify.android.ui.theme.TextPrimary
import com.geekify.android.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var whyOptionFor by remember { mutableStateOf<String?>(null) }
    var expandedReasonFor by remember { mutableStateOf<String?>(null) }

    // Match the Now Playing swipe-to-dismiss interaction while preserving vertical queue scrolling.
    val listState = rememberLazyListState()
    val dismissOffset = remember { Animatable(0f) }
    val dismissScope = rememberCoroutineScope()
    val latestOnBack by rememberUpdatedState(onBack)
    val screenHeightPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenHeightDp.dp.toPx()
    }
    val dismissThresholdPx = screenHeightPx * 0.16f

    suspend fun settleDismissGesture() {
        val shouldDismiss = dismissOffset.value > dismissThresholdPx
        if (shouldDismiss) {
            dismissOffset.animateTo(screenHeightPx, tween(180))
            latestOnBack()
        } else {
            dismissOffset.animateTo(
                0f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy)
            )
        }
    }

    // The list can still scroll normally. Once it reaches the top, a downward pull drags the
    // queue overlay itself; releasing beyond the same 16% threshold used by Now Playing dismisses it.
    val queueDismissConnection = remember(listState, screenHeightPx, dismissThresholdPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                val currentOffset = dismissOffset.value
                if (currentOffset > 0f && available.y < 0f) {
                    val nextOffset = (currentOffset + available.y).coerceAtLeast(0f)
                    dismissScope.launch { dismissOffset.snapTo(nextOffset) }
                    return Offset(0f, nextOffset - currentOffset)
                }

                val listIsAtTop = listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
                if (available.y > 0f && listIsAtTop) {
                    dismissScope.launch {
                        dismissOffset.snapTo((dismissOffset.value + available.y).coerceAtLeast(0f))
                    }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (dismissOffset.value > 0f) {
                    settleDismissGesture()
                    return Velocity(0f, available.y)
                }
                return Velocity.Zero
            }
        }
    }

    // Registered here (inside the overlay) so it outranks the NavHost's own back handler: Back always
    // closes the queue first and never pops a screen underneath it.
    BackHandler(onBack = onBack)

    val currentTrack = state.current
    val thumbUrl = currentTrack?.thumbnails?.bestArtworkUrl()
    val artColor by rememberArtColor(thumbUrl, fallback = InkElevated)
    val sheetShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)

    // A rich opaque glass sheet floating over the dimmed underlying player.
    // The scrim consumes taps so nothing behind the queue can be tapped. The full overlay follows the finger.
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = dismissOffset.value
                alpha = 1f - (dismissOffset.value / screenHeightPx).coerceIn(0f, 1f) * 0.5f
            }
            .background(Color.Black.copy(alpha = 0.60f))
            .consumeTaps()
    ) {
      Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(top = 10.dp)
            .shadow(
                elevation = 28.dp,
                shape = sheetShape,
                ambientColor = Color.Black.copy(alpha = 0.7f),
                spotColor = Color.Black.copy(alpha = 0.7f)
            )
            .clip(sheetShape)
            .background(InkBackground)
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.20f),
                        Color.White.copy(alpha = 0.05f),
                        Color.Transparent
                    )
                ),
                shape = sheetShape
            )
      ) {
        // Ambient artwork glow washing down from top of the queue sheet
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(
                    Brush.verticalGradient(
                        0f to artColor.copy(alpha = 0.35f),
                        0.5f to artColor.copy(alpha = 0.08f),
                        1f to Color.Transparent
                    )
                )
        )

        // Drag handle pill centered at top
        Box(
            modifier = Modifier
                .padding(top = 10.dp)
                .align(Alignment.TopCenter)
                .size(width = 38.dp, height = 4.dp)
                .background(Color.White.copy(alpha = 0.28f), CircleShape)
        )

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)
                        // The header also acts as a grab surface, including when the queue is empty.
                        .pointerInput(screenHeightPx, dismissThresholdPx) {
                            detectVerticalDragGestures(
                                onVerticalDrag = { _, dragAmount ->
                                    if (dragAmount > 0f || dismissOffset.value > 0f) {
                                        dismissScope.launch {
                                            dismissOffset.snapTo(
                                                (dismissOffset.value + dragAmount).coerceAtLeast(0f)
                                            )
                                        }
                                    }
                                },
                                onDragEnd = { dismissScope.launch { settleDismissGesture() } },
                                onDragCancel = {
                                    dismissScope.launch {
                                        dismissOffset.animateTo(
                                            0f,
                                            spring(dampingRatio = Spring.DampingRatioMediumBouncy)
                                        )
                                    }
                                }
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircleIconButton(
                        icon = GeekifyIcons.ArrowBack,
                        contentDescription = "Back",
                        onClick = onBack,
                        size = 50.dp
                    )
                    Text(
                        "Playing Queue",
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                    if (state.queue.isNotEmpty()) {
                        CircleIconButton(
                            icon = GeekifyIcons.ClearAll,
                            contentDescription = "Clear Queue",
                            onClick = { viewModel.clearQueue() },
                            size = 50.dp
                        )
                    } else {
                        Spacer(Modifier.size(50.dp))
                    }
                }
            }
        ) { padding ->
            if (state.queue.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Queue is empty", color = TextSecondary, fontSize = 16.sp)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .navigationBarsPadding()
                        .nestedScroll(queueDismissConnection)
                ) {
                    val nowPlaying = state.current
                    if (nowPlaying != null) {
                        item {
                            Text(
                                text = "Now playing",
                                color = TextSecondary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                            )
                            val reason = state.recommendationReasons[nowPlaying.videoId]
                            TrackRow(
                                track = nowPlaying,
                                isPlaying = true,
                                onClick = {},
                                onLongClick = if (reason != null) {
                                    {
                                        whyOptionFor = nowPlaying.videoId
                                        expandedReasonFor = null
                                    }
                                } else null,
                                marqueeTitle = true
                            )
                            if (whyOptionFor == nowPlaying.videoId && reason != null) {
                                WhyThisTrackRow(
                                    reason = reason,
                                    expanded = expandedReasonFor == nowPlaying.videoId,
                                    onToggle = {
                                        expandedReasonFor = if (expandedReasonFor == nowPlaying.videoId) null else nowPlaying.videoId
                                    }
                                )
                            }
                            Spacer(Modifier.height(16.dp))
                        }
                    }

                    val nextTracks = state.queue.drop(state.index + 1)
                    if (nextTracks.isNotEmpty()) {
                        item {
                            Text(
                                text = "Next up (${nextTracks.size})",
                                color = TextSecondary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                            )
                        }

                        itemsIndexed(state.queue, key = { index, item -> "${item.videoId}_$index" }) { index, track ->
                            if (index > state.index) {
                                Column(Modifier.fillMaxWidth()) {
                                    val reason = state.recommendationReasons[track.videoId]
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        TrackRow(
                                            track = track,
                                            modifier = Modifier.weight(1f),
                                            onClick = {
                                                whyOptionFor = null
                                                expandedReasonFor = null
                                                viewModel.play(track, state.queue)
                                            },
                                            onLongClick = if (reason != null) {
                                                {
                                                    whyOptionFor = track.videoId
                                                    expandedReasonFor = null
                                                }
                                            } else null,
                                            onMoreClick = { viewModel.removeFromQueue(index) },
                                            moreIcon = GeekifyIcons.Close,
                                            moreContentDescription = "Remove from queue",
                                            marqueeTitle = true
                                        )
                                    }
                                    if (whyOptionFor == track.videoId && reason != null) {
                                        WhyThisTrackRow(
                                            reason = reason,
                                            expanded = expandedReasonFor == track.videoId,
                                            onToggle = {
                                                expandedReasonFor = if (expandedReasonFor == track.videoId) null else track.videoId
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
      }
    }
}


@Composable
private fun WhyThisTrackRow(
    reason: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(start = 92.dp, end = 20.dp, bottom = 8.dp)) {
        TextButton(onClick = onToggle, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)) {
            Text(if (expanded) "Why this track?  −" else "Why this track?  +", color = Lime, fontSize = 12.sp)
        }
        if (expanded) {
            Text(reason, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}
