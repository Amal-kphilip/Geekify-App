package com.geekify.android.ui.player

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import com.geekify.android.ui.components.CircleIconButton
import com.geekify.android.ui.components.TrackRow
import com.geekify.android.ui.theme.TextMuted
import com.geekify.android.ui.theme.TextPrimary
import com.geekify.android.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()

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

    // One glass container floating over the dimmed app; the song rows inside stay clean (no per-row glass cards).
    // The scrim consumes taps so nothing behind the queue can be tapped. The full overlay follows the finger.
    CompositionLocalProvider(LocalGlassDim provides 0.42f) {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = dismissOffset.value
                alpha = 1f - (dismissOffset.value / screenHeightPx).coerceIn(0f, 1f) * 0.5f
            }
            .background(Color.Black.copy(alpha = 0.42f))
            .consumeTaps()
    ) {
      Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(top = 8.dp)
            .liquidGlass(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp), GlassStyle.Panel)
      ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 8.dp)
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
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
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
                            icon = Icons.Default.ClearAll,
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
                            TrackRow(
                                track = nowPlaying,
                                isPlaying = true,
                                onClick = {},
                                marqueeTitle = true
                            )
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
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TrackRow(
                                        track = track,
                                        modifier = Modifier.weight(1f),
                                        onClick = { viewModel.play(track, state.queue) },
                                        onMoreClick = { viewModel.removeFromQueue(index) },
                                        moreIcon = Icons.Default.Close,
                                        moreContentDescription = "Remove from queue",
                                        marqueeTitle = true
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
